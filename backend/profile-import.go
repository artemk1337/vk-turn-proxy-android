// SPDX-License-Identifier: MIT
// Connection decoding and selection follow vk-turn-proxy-desktop's console importer.
package mobile

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	desktopsettings "github.com/artemk1337/vk-turn-proxy-desktop/pkg/desktop-settings"
	"net/url"
	"strings"
)

const maxProfileBytes = 4 << 20

func loadProfile(input string) (settings, error) {
	canonical, err := ImportProfile(input, "")
	if err != nil {
		return settings{}, err
	}
	return parseProfile(canonical)
}

// ImportProfile accepts desktop exports, connection links, base64 and WireGuard configs.
// A nonempty vkLink overrides the imported invitation link.
func ImportProfile(input, vkLink string) (string, error) {
	if len(input) > maxProfileBytes {
		return "", errors.New("profile exceeds 4 MiB")
	}
	text := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(input), "\ufeff"))
	if isWireGuardConfig(text) {
		converted, err := DescribeWireGuard(text, vkLink)
		if err != nil {
			return "", err
		}
		return ImportProfile(converted, "")
	}
	encoded := !strings.HasPrefix(text, "{")
	var err error
	if strings.HasPrefix(strings.ToLower(text), "vkturnproxy:") {
		text, err = decodeConnectionURL(text)
	} else if encoded {
		text, err = decodeConnectionBase64(text)
	}
	if err != nil {
		return "", err
	}
	var document map[string]json.RawMessage
	if json.Unmarshal([]byte(text), &document) != nil || document == nil {
		return "", errors.New("profile contains invalid JSON")
	}
	var version int
	var kind string
	if json.Unmarshal(document["version"], &version) != nil || version != 1 {
		return "", errors.New("use a version 1 profile")
	}
	if json.Unmarshal(document["type"], &kind) != nil || (kind != "connection" && kind != "full") {
		return "", errors.New("profile type must be full or connection")
	}
	if encoded && kind != "connection" {
		return "", errors.New("encoded payload must contain a connection profile")
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(document["settings"], &fields) != nil || fields == nil {
		return "", errors.New("profile settings are missing")
	}
	var servers []map[string]json.RawMessage
	if raw, ok := fields["servers"]; ok && json.Unmarshal(raw, &servers) != nil {
		return "", errors.New("profile servers are invalid")
	}
	if kind == "connection" && len(servers) > 0 {
		return "", errors.New("a connection profile must contain one server in settings")
	}
	if kind == "full" && len(servers) > 0 {
		selected, err := selectImportedServer(servers, fields["activeServer"])
		if err != nil {
			return "", err
		}
		// A server entry has its own defaults. Legacy top-level server fields
		// are separate from genuine backup globals such as MTU and VK links.
		for _, key := range []string{"serverName", "privateKey", "peerPublicKey", "presharedKey", "tunnelAddress", "peerAddress", "dnsServers", "numConnections", "credPoolCooldownSeconds", "turnServerOverride", "useDTLS", "useUDP", "useSrtp", "useWrap", "useWrapA", "useWrapS", "useCsqtt"} {
			delete(fields, key)
		}
		for key, value := range selected {
			fields[key] = value
		}
	} else if kind == "full" {
		fields["serverName"] = json.RawMessage(`"Server1"`)
	}
	delete(fields, "servers")
	delete(fields, "activeServer")
	if err := normalizeTransport(fields, kind == "connection"); err != nil {
		return "", err
	}
	if vkLink != "" {
		fields["vkLink"], _ = json.Marshal(vkLink)
	}
	if raw, ok := fields["tunnelMTU"]; ok {
		var mtu int
		if json.Unmarshal(raw, &mtu) == nil {
			limits := desktopsettings.Defaults().MTU
			if mtu == 0 {
				mtu = limits.Default
			} else {
				mtu = min(max(mtu, limits.Min), limits.Max)
			}
			fields["tunnelMTU"], _ = json.Marshal(mtu)
		}
	}
	document["type"] = json.RawMessage(`"connection"`)
	document["settings"], _ = json.Marshal(fields)
	canonical, err := json.Marshal(document)
	if err != nil {
		return "", errors.New("cannot encode connection profile")
	}
	if _, err := parseProfile(string(canonical)); err != nil {
		return "", err
	}
	return string(canonical), nil
}

func isWireGuardConfig(text string) bool {
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		return strings.HasPrefix(line, "[")
	}
	return false
}

func decodeConnectionBase64(text string) (string, error) {
	normalized := strings.NewReplacer("-", "+", "_", "/").Replace(text)
	encoding := base64.RawStdEncoding
	if strings.Contains(normalized, "=") {
		encoding = base64.StdEncoding
	}
	raw, err := encoding.DecodeString(normalized)
	if err != nil || len(strings.TrimSpace(string(raw))) == 0 || len(raw) > maxProfileBytes {
		return "", errors.New("connection profile contains invalid base64")
	}
	return string(raw), nil
}

func decodeConnectionURL(text string) (string, error) {
	u, err := url.Parse(text)
	if err != nil || !strings.EqualFold(u.Scheme, "vkturnproxy") || u.User != nil || u.Port() != "" || (u.Host != "" && !strings.EqualFold(u.Host, "import")) || u.Path != "" || u.Fragment != "" {
		return "", errors.New("connection URL must use vkturnproxy://import?data=...")
	}
	var payload string
	found := false
	for _, item := range strings.Split(u.RawQuery, "&") {
		key, value, _ := strings.Cut(item, "=")
		key, err = url.PathUnescape(key)
		if err != nil {
			return "", errors.New("connection URL contains invalid percent escapes")
		}
		if key != "data" {
			continue
		}
		if found {
			return "", errors.New("connection URL contains multiple data parameters")
		}
		found = true
		payload, err = url.PathUnescape(value)
		if err != nil {
			return "", errors.New("connection URL contains invalid percent escapes")
		}
	}
	if payload == "" {
		return "", errors.New("connection URL is missing its data parameter")
	}
	return decodeConnectionBase64(payload)
}

func selectImportedServer(servers []map[string]json.RawMessage, activeRaw json.RawMessage) (map[string]json.RawMessage, error) {
	var active string
	if len(activeRaw) > 0 && json.Unmarshal(activeRaw, &active) != nil {
		return nil, errors.New("active server selection is invalid")
	}
	if active != "" {
		for _, server := range servers {
			var name string
			json.Unmarshal(server["serverName"], &name)
			if name == active {
				if err := normalizeTransport(server, false); err != nil {
					return nil, err
				}
				return server, nil
			}
		}
	}
	for _, server := range servers {
		if server != nil && normalizeTransport(server, false) == nil {
			return server, nil
		}
	}
	return nil, errors.New("profile has no supported SRTP or DTLS server")
}

func normalizeTransport(fields map[string]json.RawMessage, connection bool) error {
	s := defaultSettings()
	data, _ := json.Marshal(fields)
	if json.Unmarshal(data, &s) != nil {
		return errors.New("invalid profile settings")
	}
	if connection {
		for _, key := range []string{"useSrtp", "useWrap", "useWrapA", "useWrapS", "useCsqtt"} {
			if raw, ok := fields[key]; ok && string(raw) != "null" {
				s.SRTP = false
				if raw, ok := fields["useSrtp"]; ok && string(raw) != "null" {
					json.Unmarshal(raw, &s.SRTP)
				}
				break
			}
		}
		s.WrapS = !s.CSQTT && s.WrapS
		s.WrapA = !s.CSQTT && !s.WrapS && s.WrapA
		s.SRTP = !s.CSQTT && !s.WrapS && !s.WrapA && s.SRTP
		s.Wrap = !s.CSQTT && !s.WrapS && !s.WrapA && !s.SRTP && s.Wrap
	}
	if s.Wrap || s.WrapA || s.WrapS || s.CSQTT || (!s.SRTP && !s.DTLS) {
		return errors.New("supported transports are SRTP and DTLS")
	}
	fields["useSrtp"], _ = json.Marshal(s.SRTP)
	fields["useDTLS"], _ = json.Marshal(s.DTLS)
	for _, key := range []string{"useWrap", "useWrapA", "useWrapS", "useCsqtt"} {
		fields[key] = json.RawMessage("false")
	}
	return nil
}
