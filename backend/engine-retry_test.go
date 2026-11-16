package mobile

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/artemk1337/vk-turn-proxy-desktop/pkg/proxy"
)

func TestRetryDeadlineAndSafeReason(t *testing.T) {
	until := time.Now().Add(10 * time.Minute)
	for _, tc := range []struct {
		name          string
		err           error
		state, reason string
	}{
		{"cooldown", fmt.Errorf("private-secret: %w", &proxy.QuotaCooldownError{Until: until}), "waiting_quota", "cooldown"},
		{"quota", errors.New("error 486: private-secret"), "waiting_quota", "quota"},
		{"network", errors.New("private-secret"), "reconnecting", "network"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := NewEngine()
			before := time.Now()
			e.reportRetry(tc.err, time.Minute)
			var status struct {
				State, RetryReason string
				RetryAt            int64
			}
			if err := json.Unmarshal([]byte(e.Status()), &status); err != nil {
				t.Fatal(err)
			}
			if status.State != tc.state || status.RetryReason != tc.reason {
				t.Fatal(e.Status())
			}
			if tc.reason == "cooldown" {
				if status.RetryAt != until.UnixMilli() {
					t.Fatal("cooldown deadline changed")
				}
			} else if status.RetryAt < before.Add(time.Minute).UnixMilli() || status.RetryAt > time.Now().Add(time.Minute).UnixMilli() {
				t.Fatal("retry delay not reflected in deadline")
			}
			if strings.Contains(e.Status(), "private-secret") || strings.Contains(e.Logs(), "private-secret") {
				t.Fatal("raw retry error exposed")
			}
			e.reportRetry(nil, 0)
			if e.retryAt != 0 || e.retryReason != "" {
				t.Fatal("recovery retained retry metadata")
			}
			e.reportRetry(tc.err, time.Minute)
			e.Stop()
			e.reportRetry(tc.err, time.Minute)
			if e.retryAt != 0 || e.retryReason != "" {
				t.Fatal("stopped engine retained retry metadata")
			}
		})
	}
}
