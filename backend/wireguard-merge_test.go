package mobile

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestMergeWireGuardProfilePreservesExtensions(t *testing.T) {
	var previous map[string]any
	json.Unmarshal([]byte(validProfile(t)), &previous)
	previous["customMetadata"] = map[string]any{"retained": true}
	fields := previous["settings"].(map[string]any)
	fields["futureSetting"] = "retained"
	fields["dnsServers"] = "9.9.9.9"
	fields["vkAuth"] = true
	fields["routing"] = map[string]any{"mode": "exclude", "custom": "example.com"}
	fields["presharedKey"] = fields["privateKey"]
	fields["privatekey"] = strings.Repeat("AgIC", 10) + "AgI="
	previousJSON, _ := json.Marshal(previous)
	imported, err := ImportProfile(wireGuardImportFixture("MTU=1420\n"), "https://vk.ru/call/join/test")
	if err != nil {
		t.Fatal(err)
	}
	merged, err := MergeWireGuardProfile(string(previousJSON), imported)
	if err != nil {
		t.Fatal(err)
	}
	var result map[string]json.RawMessage
	json.Unmarshal([]byte(merged), &result)
	if !strings.Contains(string(result["customMetadata"]), `"retained":true`) {
		t.Fatal("root extension was lost")
	}
	var resultSettings map[string]json.RawMessage
	json.Unmarshal(result["settings"], &resultSettings)
	if string(resultSettings["futureSetting"]) != `"retained"` {
		t.Fatal("settings extension was lost")
	}
	for _, stale := range []string{"presharedKey", "privatekey", "routing", "vkAuth"} {
		if _, ok := resultSettings[stale]; ok {
			t.Fatalf("stale known setting retained: %s", stale)
		}
	}
	effective, err := loadProfile(merged)
	if err != nil {
		t.Fatal(err)
	}
	if effective.DNS != "1.1.1.1" || effective.PresharedKey != "" || effective.VKAuth || effective.MTU != 1280 {
		t.Fatal("fresh configuration defaults were not applied")
	}
	if effective.PrivateKey != "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=" {
		t.Fatal("a stale key alias overrode the imported key")
	}
}

func TestMergeWireGuardProfileValidatesBothInputs(t *testing.T) {
	valid := validProfile(t)
	for _, pair := range [][2]string{{"invalid", valid}, {valid, "invalid"}} {
		if _, err := MergeWireGuardProfile(pair[0], pair[1]); err == nil {
			t.Fatal("invalid source accepted")
		}
	}
}
