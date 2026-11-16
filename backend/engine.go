// SPDX-License-Identifier: MIT
package mobile

import (
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"

	"github.com/artemk1337/vk-turn-proxy-desktop/pkg/proxy"
)

type session interface {
	close()
	connected() bool
}

// Engine owns one VPN session. A stopped engine cannot be restarted.
type Engine struct {
	mu             sync.Mutex
	stopOnce       sync.Once
	stopped        bool
	started        bool
	state          string
	errorText      string
	proxy          *proxy.Proxy
	session        session
	readyAt        time.Time
	cookieHeader   string
	logs           []string
	logLimit       int
	lastLogState   string
	lastLogMessage string
	lastLogActive  int32
	retryAt        int64
	retryReason    string
}

func NewEngine() *Engine { return &Engine{state: "disconnected"} }

// Stop cancels connection attempts and releases TURN allocations before closing WireGuard.
func (e *Engine) Stop() {
	e.stopOnce.Do(e.stop)
}

func (e *Engine) stop() {
	e.mu.Lock()
	e.stopped = true
	p, s := e.proxy, e.session
	e.session = nil
	e.state = "disconnected"
	e.retryAt, e.retryReason = 0, ""
	e.appendLogLocked("VPN session stopped")
	e.mu.Unlock()
	if p != nil {
		p.Stop()
	}
	if s != nil {
		s.close()
	}
}

// Status returns connection state and transport statistics without credentials.
func (e *Engine) Status() string {
	e.mu.Lock()
	state, message, p, s, readyAt := e.state, e.errorText, e.proxy, e.session, e.readyAt
	retryAt, retryReason := e.retryAt, e.retryReason
	e.mu.Unlock()
	var stats proxy.Stats
	if p != nil {
		stats = p.GetStats()
	}
	if state != "disconnected" && stats.ActiveConns == 0 {
		if stats.AuthError != "" {
			state, message = "error", "VK authentication failed"
		} else if stats.CaptchaSID != "" {
			state, message = "waiting_captcha", "VK requires captcha authentication"
		}
	}
	if s != nil {
		state, message = handshakeStatus(state, message, stats.ActiveConns > 0, s.connected(), readyAt, time.Now())
	}
	if state == "connected" || state == "disconnected" || state == "error" || state == "waiting_captcha" {
		retryAt, retryReason = 0, ""
	}
	// Captcha URLs and identifiers can carry session data.
	e.recordStatus(state, message, stats.ActiveConns)
	stats.CaptchaImageURL = ""
	stats.CaptchaSID = ""
	stats.AuthError = ""
	data, _ := json.Marshal(struct {
		State       string      `json:"state"`
		Error       string      `json:"error"`
		Stats       proxy.Stats `json:"stats"`
		RetryAt     int64       `json:"retryAt"`
		RetryReason string      `json:"retryReason"`
	}{state, message, stats, retryAt, retryReason})
	return string(data)
}

func handshakeStatus(state, message string, active, handshaken bool, readyAt, now time.Time) (string, string) {
	if state == "disconnected" || state == "error" {
		return state, message
	}
	if active && handshaken {
		return "connected", ""
	}
	if state == "waiting_quota" {
		return state, message
	}
	if handshaken {
		return "reconnecting", "Transport connection failed, retrying"
	}
	if active && !readyAt.IsZero() && now.Sub(readyAt) >= 30*time.Second {
		return "connecting", "WireGuard handshake has not completed; check server keys and transport"
	}
	return state, message
}

func (e *Engine) begin(s settings, cachePath string) (*proxy.Proxy, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.stopped || e.started {
		return nil, errors.New("engine has already been started or stopped")
	}
	e.started = true
	e.state = "connecting"
	e.appendLogLocked("VPN session starting")
	if s.VKAuth && e.cookieHeader == "" {
		return nil, errors.New("VK account login is required")
	}
	proxy.SetVKCookieAuth(s.VKAuth || e.cookieHeader != "", e.cookieHeader, strings.Fields(s.VKLink))
	proxy.SetForceLegacyCaptcha(s.ForceLegacyCaptcha)
	proxy.SetUplinkPace(s.UplinkPaceKiB, 16)
	config := s.proxyConfig(cachePath)
	config.OnRetry = e.reportRetry
	p := proxy.NewProxy(config)
	e.proxy = p
	return p, nil
}

func (e *Engine) reportRetry(err error, delay time.Duration) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.stopped {
		return
	}
	if err == nil {
		e.errorText, e.retryReason, e.retryAt = "", "", 0
		e.state = "connecting"
		e.appendLogLocked("Transport connection established")
		return
	}
	e.retryAt = time.Now().Add(max(delay, 0)).UnixMilli()
	var cooldown *proxy.QuotaCooldownError
	switch {
	case errors.As(err, &cooldown):
		e.state, e.errorText, e.retryReason = "waiting_quota", "Previous TURN allocations may still be active", "cooldown"
		e.retryAt = cooldown.Until.UnixMilli()
	case proxy.IsQuotaError(err):
		e.state, e.errorText, e.retryReason = "waiting_quota", "TURN quota unavailable", "quota"
	default:
		e.state, e.errorText, e.retryReason = "reconnecting", "Transport connection failed, retrying", "network"
	}
	e.appendLogLocked(e.errorText)
}
