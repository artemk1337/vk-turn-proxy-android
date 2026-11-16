// SPDX-License-Identifier: MIT
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"net/url"
	"strings"
	"time"

	vkauth "github.com/artemk1337/vk-turn-proxy-desktop/pkg/vk-auth"
)

// NormalizeCookies retains only the two cookies used for VK authentication.
func NormalizeCookies(input string) (string, error) {
	cookies, err := vkauth.ParseCookieInput(strings.NewReader(input))
	if err != nil {
		return "", errors.New("invalid VK cookies")
	}
	header, _, err := vkauth.CookieHeader(cookies, time.Now())
	if err != nil {
		return "", errors.New("VK cookies are invalid or expired")
	}
	return header, nil
}

// CheckAccount returns a bounded state without exposing remote response data.
func CheckAccount(input string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return string(vkauth.Check(ctx, input))
}

// SetAccount snapshots the account for this connection before Start.
func (e *Engine) SetAccount(input string) error {
	header := ""
	if strings.TrimSpace(input) != "" {
		var err error
		header, err = NormalizeCookies(input)
		if err != nil {
			return err
		}
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.started || e.stopped {
		return errors.New("set the VK account before starting the connection")
	}
	e.cookieHeader = header
	return nil
}

// Captcha is private UI data. Callers must never persist or log its URL.
func (e *Engine) Captcha() string {
	e.mu.Lock()
	p, stopped := e.proxy, e.stopped
	e.mu.Unlock()
	challenge := struct {
		Pending bool   `json:"pending"`
		URL     string `json:"url,omitempty"`
	}{}
	if p != nil && !stopped {
		stats := p.GetStats()
		if validCaptchaURL(stats.CaptchaImageURL) {
			challenge.Pending = true
			challenge.URL = stats.CaptchaImageURL
		}
	}
	data, _ := json.Marshal(challenge)
	return string(data)
}

// SolveCaptcha passes a user-provided answer to the active transport.
func (e *Engine) SolveCaptcha(answer string) error {
	answer = strings.TrimSpace(answer)
	if len(answer) == 0 || len(answer) > 16<<10 || strings.ContainsAny(answer, "\r\n\x00") {
		return errors.New("invalid captcha answer")
	}
	e.mu.Lock()
	p, stopped := e.proxy, e.stopped
	e.mu.Unlock()
	if p == nil || stopped {
		return errors.New("no connection is waiting for captcha")
	}
	stats := p.GetStats()
	if stats.CaptchaImageURL == "" && stats.CaptchaSID == "" {
		return errors.New("no connection is waiting for captcha")
	}
	p.SolveCaptcha(answer)
	return nil
}

// RefreshCaptcha refreshes an interactive challenge on a worker thread.
func (e *Engine) RefreshCaptcha() string {
	e.mu.Lock()
	p, stopped := e.proxy, e.stopped
	e.mu.Unlock()
	if p != nil && !stopped {
		stats := p.GetStats()
		if stats.CaptchaImageURL != "" || stats.CaptchaSID != "" {
			p.RefreshCaptchaURL()
		}
	}
	return e.Captcha()
}

func validCaptchaURL(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.User != nil || u.Port() != "" {
		return false
	}
	host := strings.ToLower(u.Hostname())
	for _, suffix := range []string{"vk.com", "vk.ru", "vk-captcha.ru", "vk-captcha.com", "vkuser.net", "userapi.com"} {
		if host == suffix || strings.HasSuffix(host, "."+suffix) {
			return true
		}
	}
	return false
}
