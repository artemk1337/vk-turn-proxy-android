package mobile

import (
	"strings"
	"testing"
)

func wireGuardImportFixture(mtu string) string {
	const key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
	return "[Interface]\nPrivateKey=" + key + "\nAddress=10.0.0.2/32\n" + mtu +
		"[Peer]\nPublicKey=" + key + "\nEndpoint=example.com:56004\nAllowedIPs=0.0.0.0/0\n"
}

func TestWireGuardImportMTU(t *testing.T) {
	for _, test := range []struct {
		name, value string
		want        int
	}{
		{"absent", "", 1280},
		{"too large", "MTU=1420\n", 1280},
		{"too small", "MTU=900\n", 1280},
		{"zero", "MTU=0\n", 1280},
		{"negative", "MTU=-1\n", 1280},
		{"minimum", "MTU=1000\n", 1000},
		{"maximum", "MTU=1400\n", 1400},
		{"custom", "MTU=1320\n", 1320},
		{"case and comments", "  mTu = 1420 ; imported MTU\n", 1280},
	} {
		t.Run(test.name, func(t *testing.T) {
			config := wireGuardImportFixture(test.value)
			for name, input := range map[string]string{
				"plain":    config,
				"BOM CRLF": "\ufeff\r\n# WireGuard profile\r\n" + strings.ReplaceAll(config, "\n", "\r\n"),
			} {
				t.Run(name, func(t *testing.T) {
					canonical, err := ImportProfile(input, "https://vk.ru/call/join/test")
					if err != nil {
						t.Fatal(err)
					}
					profile, err := parseProfile(canonical)
					if err != nil {
						t.Fatal(err)
					}
					if profile.MTU != test.want {
						t.Fatalf("MTU = %d, want %d", profile.MTU, test.want)
					}
					if _, err := UpdateProfile(canonical, `{"mtu":1300}`); err != nil {
						t.Fatalf("normalized profile cannot be edited: %v", err)
					}
				})
			}
		})
	}
}

func TestWireGuardMTUNormalizationPreservesValidation(t *testing.T) {
	for _, mtu := range []string{"MTU=invalid\n", "MTU=1420\nMTU=1500\n", "MTU=1420\nmtu=1280\n", "MTU=1420\x00\n"} {
		if _, err := DescribeWireGuard(wireGuardImportFixture(mtu), "https://vk.ru/call/join/test"); err == nil {
			t.Fatal("malformed or duplicate MTU accepted")
		}
	}
	for _, setting := range []string{"PostUp=secret-command\n", "MTU=1420\n"} {
		if _, err := DescribeWireGuard(wireGuardImportFixture("MTU=1420\n")+setting, "https://vk.ru/call/join/test"); err == nil {
			t.Fatal("unsupported setting accepted")
		} else if strings.Contains(err.Error(), "secret-command") {
			t.Fatal("validation error exposed input")
		}
	}
	config := wireGuardImportFixture("MTU=1420 # preserve MTU=1420 in comment\n")
	want := strings.Replace(config, "MTU=1420 #", "MTU=1280 #", 1)
	if got := normalizeWireGuardMTU(config); got != strings.TrimSpace(want) {
		t.Fatal("normalization changed configuration beyond the MTU value")
	}
}

func TestWireGuardPeerOverrideBeforeValidation(t *testing.T) {
	config := strings.Replace(wireGuardImportFixture("MTU=1420\n"), "Endpoint=example.com:56004\n", "", 1)
	if _, err := ImportWireGuardProfile(config, "https://vk.ru/call/join/test", ""); err == nil {
		t.Fatal("missing endpoint accepted without an override")
	}
	canonical, err := ImportWireGuardProfile(config, "https://vk.ru/call/join/test", "192.0.2.1:56004")
	if err != nil {
		t.Fatal(err)
	}
	profile, err := loadProfile(canonical)
	if err != nil {
		t.Fatal(err)
	}
	if profile.Peer != "192.0.2.1:56004" || profile.MTU != 1280 {
		t.Fatal("endpoint override or MTU normalization was not applied")
	}
	if _, err := ImportWireGuardProfile(config, "https://vk.ru/call/join/test", "invalid"); err == nil {
		t.Fatal("invalid endpoint override accepted")
	}
}
