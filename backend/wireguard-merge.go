// SPDX-License-Identifier: MIT
package mobile

import (
	"encoding/json"
	"strings"
)

// MergeWireGuardProfile retains extensions while replacing known profile fields
// with a newly imported WireGuard configuration. UI overrides apply afterwards.
func MergeWireGuardProfile(previous, imported string) (string, error) {
	previous, err := ImportProfile(previous, "")
	if err != nil {
		return "", err
	}
	imported, err = ImportProfile(imported, "")
	if err != nil {
		return "", err
	}
	var original, replacement map[string]json.RawMessage
	json.Unmarshal([]byte(previous), &original)
	json.Unmarshal([]byte(imported), &replacement)
	var originalSettings, replacementSettings, knownSettings map[string]json.RawMessage
	json.Unmarshal(original["settings"], &originalSettings)
	json.Unmarshal(replacement["settings"], &replacementSettings)
	schema, _ := json.Marshal(settings{})
	json.Unmarshal(schema, &knownSettings)
	// encoding/json matches struct fields case-insensitively. Remove aliases
	// too, so old keys cannot override a fresh key after map serialization.
	for key := range originalSettings {
		for known := range knownSettings {
			if strings.EqualFold(key, known) {
				delete(originalSettings, key)
				break
			}
		}
	}
	for key, value := range replacementSettings {
		originalSettings[key] = value
	}
	for key := range original {
		if strings.EqualFold(key, "version") || strings.EqualFold(key, "type") || strings.EqualFold(key, "settings") {
			delete(original, key)
		}
	}
	for key, value := range replacement {
		original[key] = value
	}
	original["settings"], _ = json.Marshal(originalSettings)
	merged, _ := json.Marshal(original)
	return ImportProfile(string(merged), "")
}
