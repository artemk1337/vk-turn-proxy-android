package mobile

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func validProfile(t *testing.T) string {
	t.Helper()
	key := "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
	input, err := json.Marshal(profile{Version: 1, Type: "connection", Settings: settings{PrivateKey: key, PublicKey: key, Address: "10.0.0.2/32", Peer: "example.com:56004", VKLink: "https://vk.ru/call/join/test", DNS: "1.1.1.1, 8.8.8.8", MTU: 1280, Connections: 10, DTLS: true, SRTP: true}})
	if err != nil {
		t.Fatal(err)
	}
	return string(input)
}

func TestDescribeProfileOmitsSecrets(t *testing.T) {
	got, err := DescribeProfile(validProfile(t))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(got, "AQEBA") || strings.Contains(got, "vk.ru") || strings.Contains(got, "example.com") {
		t.Fatal("description contains private data")
	}
	if !strings.Contains(got, "10.0.0.2/32") {
		t.Fatal("missing interface address")
	}
}

func TestProfileValidation(t *testing.T) {
	for name, change := range map[string]func(*profile){
		"version":            func(p *profile) { p.Version = 2 },
		"key injection":      func(p *profile) { p.Settings.PrivateKey = "secret\npublic_key=bad" },
		"endpoint injection": func(p *profile) { p.Settings.Peer = "example.com\nprivate_key=secret:123" },
		"transport":          func(p *profile) { p.Settings.WrapA = true },
		"dns":                func(p *profile) { p.Settings.DNS = "localhost" },
		"mtu":                func(p *profile) { p.Settings.MTU = 65535 },
		"routing":            func(p *profile) { p.Settings.Routing = json.RawMessage(`{"mode":"split"}`) },
		"vk domain":          func(p *profile) { p.Settings.VKLink = "https://attacker.example/call/join/test" },
	} {
		t.Run(name, func(t *testing.T) {
			var p profile
			json.Unmarshal([]byte(validProfile(t)), &p)
			change(&p)
			input, _ := json.Marshal(p)
			if _, err := parseProfile(string(input)); err == nil {
				t.Fatal("invalid profile accepted")
			} else if strings.Contains(err.Error(), "secret") {
				t.Fatal("error exposes secret")
			}
		})
	}
}

func TestWireGuardConversion(t *testing.T) {
	key := "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
	config := "[Interface]\nPrivateKey=" + key + "\nAddress=10.0.0.2/32\n[Peer]\nPublicKey=" + key + "\nEndpoint=example.com:56004\nAllowedIPs=0.0.0.0/0\n"
	input, err := DescribeWireGuard(config, "https://vk.ru/call/join/test")
	if err != nil {
		t.Fatal(err)
	}
	s, err := parseProfile(input)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(s.ipc(), "allowed_ip=0.0.0.0/0\n") {
		t.Fatal("missing full tunnel")
	}
	if !strings.Contains(s.ipc(), "persistent_keepalive_interval=0\n") {
		t.Fatal("import changed disabled keepalives")
	}
	if _, err := DescribeWireGuard(config+"PostUp=touch /tmp/pwn\n", "https://vk.ru/call/join/test"); err == nil {
		t.Fatal("unsupported directive accepted")
	}
}

func TestStopBeforeStartIsTerminal(t *testing.T) {
	e := NewEngine()
	e.Stop()
	e.Stop()
	var p profile
	json.Unmarshal([]byte(validProfile(t)), &p)
	if _, err := e.begin(p.Settings, ""); err == nil {
		t.Fatal("stopped engine restarted")
	}
	if !strings.Contains(e.Status(), `"state":"disconnected"`) {
		t.Fatal(e.Status())
	}
}

func TestNullSettingsRejected(t *testing.T) {
	for _, input := range []string{`null`, `{"version":1,"type":"connection","settings":null}`} {
		if _, err := parseProfile(input); err == nil {
			t.Fatal("null settings accepted")
		}
	}
}

func TestHandshakeStatus(t *testing.T) {
	now := time.Unix(1000, 0)
	for _, tc := range []struct {
		name, state        string
		active, handshaken bool
		elapsed            time.Duration
		want, warning      string
	}{
		{"initial", "connecting", true, false, 29 * time.Second, "connecting", ""},
		{"initial warning", "connecting", true, false, 30 * time.Second, "connecting", "WireGuard handshake has not completed; check server keys and transport"},
		{"idle established", "connecting", true, true, 24 * time.Hour, "connected", ""},
		{"lost transport", "connected", false, true, time.Hour, "reconnecting", "Transport connection failed, retrying"},
		{"quota wait", "waiting_quota", false, false, time.Hour, "waiting_quota", ""},
		{"recovered", "reconnecting", true, true, time.Hour, "connected", ""},
		{"stopped", "disconnected", true, true, time.Hour, "disconnected", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			state, warning := handshakeStatus(tc.state, "", tc.active, tc.handshaken, now.Add(-tc.elapsed), now)
			if state != tc.want || warning != tc.warning {
				t.Fatalf("got %q %q", state, warning)
			}
		})
	}
}
