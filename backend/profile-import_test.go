package mobile

import (
	"encoding/base64"
	"encoding/json"
	"net/url"
	"strings"
	"testing"
	"time"
)

func profileDocument(t *testing.T) map[string]any {
	t.Helper()
	var document map[string]any
	if err := json.Unmarshal([]byte(validProfile(t)), &document); err != nil {
		t.Fatal(err)
	}
	return document
}

func encodeDocument(t *testing.T, document any) string {
	t.Helper()
	data, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func TestDesktopImportForms(t *testing.T) {
	raw := validProfile(t)
	forms := []string{raw, "\ufeff" + raw}
	for _, encoding := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		encoded := encoding.EncodeToString([]byte(raw))
		forms = append(forms, encoded, "vkturnproxy://import?data="+encoded, "vkturnproxy://import?data="+url.QueryEscape(encoded), "vkturnproxy:?data="+encoded)
	}
	for _, input := range forms {
		canonical, err := ImportProfile(" \n"+input+"\n", "")
		if err != nil {
			t.Fatal(err)
		}
		s, err := parseProfile(canonical)
		if err != nil || !s.SRTP || !s.DTLS || s.Peer != "example.com:56004" {
			t.Fatalf("profile changed: %v", err)
		}
	}
}

func TestLiteralPlusAndMetadataPreserved(t *testing.T) {
	doc := profileDocument(t)
	fields := doc["settings"].(map[string]any)
	fields["serverName"] = "~~~"
	doc["futureMetadata"] = map[string]any{"enabled": true}
	fields["futureSetting"] = "retained"
	raw := encodeDocument(t, doc)
	encoded := base64.StdEncoding.EncodeToString([]byte(raw))
	for i := 0; !strings.Contains(encoded, "+"); i++ {
		if i > 10 {
			t.Fatal("fixture has no literal plus")
		}
		fields["serverName"] = fields["serverName"].(string) + "~"
		raw = encodeDocument(t, doc)
		encoded = base64.StdEncoding.EncodeToString([]byte(raw))
	}
	canonical, err := ImportProfile("vkturnproxy://import?data="+encoded, "")
	if err != nil {
		t.Fatal(err)
	}
	updated, err := UpdateProfile(canonical, `{"name":"Edited","connections":40,"transport":"dtls","turnTransport":"udp","mtu":1300,"dns":"9.9.9.9","keepalive":0,"turnServerOverride":"203.0.113.1:3478","uplinkPaceKiB":64,"cooldownSeconds":200,"vkAuth":true,"forceLegacyCaptcha":true}`)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	json.Unmarshal([]byte(updated), &got)
	if got["futureMetadata"] == nil || got["settings"].(map[string]any)["futureSetting"] != "retained" {
		t.Fatal("unknown metadata lost")
	}
	if got["settings"].(map[string]any)["privateKey"] != fields["privateKey"] || got["settings"].(map[string]any)["peerPublicKey"] != fields["peerPublicKey"] {
		t.Fatal("editing public settings changed WireGuard keys")
	}
	s, err := parseProfile(updated)
	if err != nil {
		t.Fatal(err)
	}
	config := s.proxyConfig("private-cache")
	if config.UseSrtp || !config.UseDTLS || !config.UseUDP || config.NumConns != 40 || config.CredPoolCooldown != 200*time.Second || len(config.TurnServers) != 1 || s.UplinkPaceKiB != 64 || s.Keepalive != 0 || !s.VKAuth || !s.ForceLegacyCaptcha {
		t.Fatal("edited settings not applied to transport")
	}
	public, err := GetProfileSettings(updated)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(public, "AQEBA") || !strings.Contains(public, `"connections":40`) || !strings.Contains(public, `"transport":"dtls"`) {
		t.Fatal("invalid public settings")
	}
	preserved, err := UpdateProfile(updated, `{"turnTransport":""}`)
	if err != nil {
		t.Fatal(err)
	}
	ps, _ := parseProfile(preserved)
	if ps.UDP || ps.proxyConfig("").UseUDP {
		t.Fatal("profile TURN transport was not restored")
	}
}

func TestTURNTransportOverrideRestoresImportedBase(t *testing.T) {
	for _, baseUDP := range []bool{false, true} {
		document := profileDocument(t)
		fields := document["settings"].(map[string]any)
		fields["useUDP"], fields["futureSetting"] = baseUDP, "retained"
		base := encodeDocument(t, document)
		public, err := GetProfileSettings(base)
		if err != nil {
			t.Fatal(err)
		}
		var editable editableSettings
		json.Unmarshal([]byte(public), &editable)
		if editable.TurnTransport != "" {
			t.Fatal("fresh import has an explicit override")
		}
		override := "udp"
		if baseUDP {
			override = "tcp"
		}
		updated, err := UpdateProfile(base, encodeDocument(t, map[string]string{"turnTransport": override}))
		if err != nil {
			t.Fatal(err)
		}
		s, err := loadProfile(updated)
		if err != nil {
			t.Fatal(err)
		}
		if s.UDP != baseUDP || s.proxyConfig("").UseUDP == baseUDP {
			t.Fatal("override changed imported base or was not applied")
		}
		public, err = GetProfileSettings(updated)
		if err != nil {
			t.Fatal(err)
		}
		json.Unmarshal([]byte(public), &editable)
		if editable.TurnTransport != override {
			t.Fatal("editor did not reopen stored override")
		}
		restored, err := UpdateProfile(updated, `{"turnTransport":""}`)
		if err != nil {
			t.Fatal(err)
		}
		s, err = loadProfile(restored)
		if err != nil {
			t.Fatal(err)
		}
		if s.UDP != baseUDP || s.proxyConfig("").UseUDP != baseUDP || !strings.Contains(restored, `"futureSetting":"retained"`) {
			t.Fatal("base transport or metadata was not restored")
		}
		public, err = GetProfileSettings(restored)
		if err != nil {
			t.Fatal(err)
		}
		json.Unmarshal([]byte(public), &editable)
		if editable.TurnTransport != "" {
			t.Fatal("editor did not reopen From profile selection")
		}
	}
}

func TestDesktopDefaultsAndTransportEnum(t *testing.T) {
	doc := profileDocument(t)
	fields := doc["settings"].(map[string]any)
	for _, key := range []string{"numConnections", "tunnelMTU", "persistentKeepalive", "useSrtp", "useWrap", "useWrapA", "useWrapS", "useCsqtt"} {
		delete(fields, key)
	}
	canonical, err := ImportProfile(encodeDocument(t, doc), "")
	if err != nil {
		t.Fatal(err)
	}
	s, _ := parseProfile(canonical)
	if s.Connections != 30 || s.MTU != 1280 || s.Keepalive != 25 || !s.SRTP {
		t.Fatal("desktop defaults differ")
	}
	fields["useSrtp"] = false
	canonical, err = ImportProfile(encodeDocument(t, doc), "")
	if err != nil {
		t.Fatal(err)
	}
	s, _ = parseProfile(canonical)
	if s.SRTP {
		t.Fatal("explicit legacy transport ignored")
	}
	fields["useSrtp"], fields["useWrap"] = true, true
	if _, err := ImportProfile(encodeDocument(t, doc), ""); err != nil {
		t.Fatal("SRTP must take precedence over WRAP")
	}
	delete(fields, "useSrtp")
	fields["useWrap"] = false
	canonical, err = ImportProfile(encodeDocument(t, doc), "")
	if err != nil {
		t.Fatal(err)
	}
	s, _ = parseProfile(canonical)
	if s.SRTP || !s.DTLS {
		t.Fatal("explicit transport flag set did not replace defaults")
	}
}

func TestFullBackupSelection(t *testing.T) {
	doc := profileDocument(t)
	fields := doc["settings"].(map[string]any)
	var first, active map[string]any
	json.Unmarshal([]byte(encodeDocument(t, fields)), &first)
	json.Unmarshal([]byte(encodeDocument(t, fields)), &active)
	first["serverName"], active["serverName"] = "First", "Active"
	active["numConnections"] = 50
	doc["type"] = "full"
	fields["servers"] = []any{first, active}
	fields["activeServer"] = "Active"
	canonical, err := ImportProfile(encodeDocument(t, doc), "")
	if err != nil {
		t.Fatal(err)
	}
	s, _ := parseProfile(canonical)
	if s.Name != "Active" || s.Connections != 50 {
		t.Fatal("active server not selected")
	}
	active["useWrapA"] = true
	if _, err := ImportProfile(encodeDocument(t, doc), ""); err == nil {
		t.Fatal("unsupported active server silently replaced")
	}
	fields["activeServer"] = "missing"
	first["useWrapA"] = true
	active["useWrapA"] = false
	if _, err := ImportProfile(encodeDocument(t, doc), ""); err != nil {
		t.Fatal("first compatible fallback not selected")
	}
}

func TestMalformedImportAndPatchErrorsAreSafe(t *testing.T) {
	good := base64.StdEncoding.EncodeToString([]byte(validProfile(t)))
	for _, input := range []string{"secret", "vkturnproxy://other?data=" + good, "vkturnproxy://import?data=" + good + "&data=" + good, "vkturnproxy://import?data=%XX", `{"version":1,"type":"connection","settings":null}`, base64.StdEncoding.EncodeToString([]byte(`{"version":1,"type":"full","settings":{}}`))} {
		if _, err := ImportProfile(input, ""); err == nil {
			t.Fatal("invalid import accepted")
		} else if strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), good) {
			t.Fatal("error leaked input")
		}
	}
	for _, patch := range []string{`null`, `{"connections":null}`, `{"connections":121}`, `{"transport":"wrap"}`, `{"keepalive":-1}`, `{"uplinkPaceKiB":-1}`} {
		if _, err := UpdateProfile(validProfile(t), patch); err == nil {
			t.Fatalf("invalid patch accepted: %s", patch)
		}
	}
}

func TestLegacyMTUImportAndEditorBounds(t *testing.T) {
	for stored, want := range map[int]int{0: 1280, 900: 1000, 1420: 1400, 1300: 1300} {
		document := profileDocument(t)
		document["settings"].(map[string]any)["tunnelMTU"] = stored
		canonical, err := ImportProfile(encodeDocument(t, document), "")
		if err != nil {
			t.Fatal(err)
		}
		s, _ := parseProfile(canonical)
		if s.MTU != want {
			t.Fatalf("stored %d resolved to %d", stored, s.MTU)
		}
	}
	for _, patch := range []string{`{"mtu":0}`, `{"mtu":900}`, `{"mtu":1420}`} {
		if _, err := UpdateProfile(validProfile(t), patch); err == nil {
			t.Fatal("out of bounds editor MTU accepted")
		}
	}
}

func TestWireGuardImportWithLeadingComments(t *testing.T) {
	key := "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
	config := "\ufeff\n# VPN profile\n; generated config\n\n[Interface]\nPrivateKey=" + key + "\nAddress=10.0.0.2/32\n[Peer]\nPublicKey=" + key + "\nEndpoint=example.com:56004\nAllowedIPs=0.0.0.0/0\n"
	if _, err := ImportProfile(config, "https://vk.ru/call/join/test"); err != nil {
		t.Fatal(err)
	}
	if _, err := ImportProfile(config+"PostUp=secret-command\n", "https://vk.ru/call/join/test"); err == nil {
		t.Fatal("unsupported directive accepted")
	} else if strings.Contains(err.Error(), "secret-command") || strings.Contains(err.Error(), key) {
		t.Fatal("validation error leaked input")
	}
}

func TestSavedLegacyProfileEntryPointsAgree(t *testing.T) {
	document := profileDocument(t)
	fields := document["settings"].(map[string]any)
	delete(fields, "useSrtp")
	fields["useWrap"] = false
	fields["tunnelMTU"] = 1420
	raw := encodeDocument(t, document)
	public, err := GetProfileSettings(raw)
	if err != nil {
		t.Fatal(err)
	}
	var editable editableSettings
	json.Unmarshal([]byte(public), &editable)
	description, err := DescribeProfile(raw)
	if err != nil {
		t.Fatal(err)
	}
	var interfaceSettings struct {
		MTU int `json:"mtu"`
	}
	json.Unmarshal([]byte(description), &interfaceSettings)
	effective, err := loadProfile(raw)
	if err != nil {
		t.Fatal(err)
	}
	config := effective.proxyConfig("")
	if editable.Transport != "dtls" || config.UseSrtp || !config.UseDTLS || editable.MTU != 1400 || interfaceSettings.MTU != 1400 || effective.MTU != 1400 {
		t.Fatal("saved legacy profile differs between editor, interface and transport")
	}
}

func TestFullBackupSelectedServerDoesNotInheritLegacyFields(t *testing.T) {
	document := profileDocument(t)
	document["type"] = "full"
	fields := document["settings"].(map[string]any)
	selected := map[string]any{"serverName": "Selected", "privateKey": fields["privateKey"], "peerPublicKey": fields["peerPublicKey"], "peerAddress": "selected.example:56004"}
	fields["dnsServers"], fields["numConnections"], fields["tunnelAddress"], fields["useUDP"] = "9.9.9.9", 77, "10.99.0.2/24", true
	fields["tunnelMTU"], fields["persistentKeepalive"], fields["futureSetting"] = 1300, 10, "retained"
	fields["servers"], fields["activeServer"] = []any{selected}, "Selected"
	canonical, err := ImportProfile(encodeDocument(t, document), "")
	if err != nil {
		t.Fatal(err)
	}
	s, err := loadProfile(canonical)
	if err != nil {
		t.Fatal(err)
	}
	if s.DNS != "1.1.1.1" || s.Connections != 30 || s.Address != "192.168.102.3/24" || s.UDP {
		t.Fatal("selected server inherited legacy top-level server values")
	}
	if s.MTU != 1300 || s.Keepalive != 10 || s.VKLink != fields["vkLink"] || !strings.Contains(canonical, `"futureSetting":"retained"`) {
		t.Fatal("genuine backup globals or unknown metadata lost")
	}
	delete(selected, "privateKey")
	if _, err := ImportProfile(encodeDocument(t, document), ""); err == nil {
		t.Fatal("selected server borrowed legacy private key")
	}
}

func TestLegacyFullBackupName(t *testing.T) {
	document := profileDocument(t)
	document["type"] = "full"
	canonical, err := ImportProfile(encodeDocument(t, document), "")
	if err != nil {
		t.Fatal(err)
	}
	s, _ := loadProfile(canonical)
	if s.Name != "Server1" {
		t.Fatal("legacy full backup default name differs from desktop")
	}
}
