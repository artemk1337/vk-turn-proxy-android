// SPDX-License-Identifier: MIT
package mobile

import (
	"encoding/json"
	"errors"
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
	mu        sync.Mutex
	stopOnce  sync.Once
	stopped   bool
	started   bool
	state     string
	errorText string
	proxy     *proxy.Proxy
	session   session
	readyAt   time.Time
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
	e.mu.Unlock()
	var stats proxy.Stats
	if p != nil {
		stats = p.GetStats()
	}
	if state != "disconnected" && stats.ActiveConns == 0 {
		if stats.AuthError != "" {
			state, message = "error", "VK authentication failed"
		} else if stats.CaptchaSID != "" {
			state, message = "error", "VK requires captcha authentication"
		}
	}
	if s != nil {
		state, message = handshakeStatus(state, message, stats.ActiveConns > 0, s.connected(), readyAt, time.Now())
	}
	// Captcha URLs and identifiers can carry session data.
	stats.CaptchaImageURL = ""
	stats.CaptchaSID = ""
	stats.AuthError = ""
	data, _ := json.Marshal(struct {
		State string      `json:"state"`
		Error string      `json:"error"`
		Stats proxy.Stats `json:"stats"`
	}{state, message, stats})
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
	p := proxy.NewProxy(proxy.Config{PeerAddr: s.Peer, VKLink: s.VKLink, UseDTLS: s.DTLS, UseUDP: s.UDP, UseSrtp: s.SRTP, NumConns: s.Connections, CredCachePath: cachePath, GOMAXPROCS: -1,
		OnRetry: func(err error, _delay time.Duration) {
			e.mu.Lock()
			defer e.mu.Unlock()
			if e.stopped {
				return
			}
			if err == nil {
				e.errorText = ""
				e.state = "connecting"
				return
			}
			if proxy.IsQuotaError(err) {
				e.state = "waiting_quota"
				e.errorText = "TURN quota unavailable"
			} else {
				e.state = "reconnecting"
				e.errorText = "Transport connection failed, retrying"
			}
		}})
	e.proxy = p
	return p, nil
}
