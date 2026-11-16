// SPDX-License-Identifier: MIT
package mobile

import (
	"encoding/json"
	"errors"
	desktopsettings "github.com/artemk1337/vk-turn-proxy-desktop/pkg/desktop-settings"
)

// Defaults returns the shared desktop/mobile settings contract.
func Defaults() string {
	data, _ := json.Marshal(desktopsettings.Defaults())
	return string(data)
}

type editableSettings struct {
	Name               string          `json:"name"`
	Peer               string          `json:"peer"`
	Transport          string          `json:"transport"`
	TurnTransport      string          `json:"turnTransport"`
	Connections        int             `json:"connections"`
	MTU                int             `json:"mtu"`
	DNS                string          `json:"dns"`
	VKLink             string          `json:"vkLink"`
	Keepalive          int             `json:"keepalive"`
	TurnServerOverride string          `json:"turnServerOverride"`
	UplinkPaceKiB      int             `json:"uplinkPaceKiB"`
	CooldownSeconds    int             `json:"cooldownSeconds"`
	VKAuth             bool            `json:"vkAuth"`
	ForceLegacyCaptcha bool            `json:"forceLegacyCaptcha"`
	Routing            json.RawMessage `json:"routing"`
}

// GetProfileSettings returns validated public editable settings, excluding keys.
func GetProfileSettings(input string) (string, error) {
	canonical, err := ImportProfile(input, "")
	if err != nil {
		return "", err
	}
	s, err := parseProfile(canonical)
	if err != nil {
		return "", err
	}
	transport := "dtls"
	if s.SRTP {
		transport = "srtp"
	}
	data, err := json.Marshal(editableSettings{Name: s.Name, Peer: s.Peer, Transport: transport, TurnTransport: s.TurnTransport, Connections: s.Connections, MTU: s.MTU, DNS: s.DNS, VKLink: s.VKLink, Keepalive: s.Keepalive, TurnServerOverride: s.TurnServerOverride, UplinkPaceKiB: s.UplinkPaceKiB, CooldownSeconds: s.CooldownSeconds, VKAuth: s.VKAuth, ForceLegacyCaptcha: s.ForceLegacyCaptcha, Routing: s.Routing})
	return string(data), err
}

// UpdateProfile merges editable settings while retaining keys and unknown metadata.
func UpdateProfile(input, settingsJSON string) (string, error) {
	canonical, err := ImportProfile(input, "")
	if err != nil {
		return "", err
	}
	if len(settingsJSON) > maxProfileBytes {
		return "", errors.New("settings exceed 4 MiB")
	}
	var changes map[string]json.RawMessage
	if json.Unmarshal([]byte(settingsJSON), &changes) != nil || changes == nil {
		return "", errors.New("invalid editable settings JSON")
	}
	if raw, ok := changes["mtu"]; ok {
		var mtu int
		limits := desktopsettings.Defaults().MTU
		if json.Unmarshal(raw, &mtu) != nil || mtu < limits.Min || mtu > limits.Max {
			return "", errors.New("MTU must be between 1000 and 1400")
		}
	}
	var document map[string]json.RawMessage
	var fields map[string]json.RawMessage
	json.Unmarshal([]byte(canonical), &document)
	json.Unmarshal(document["settings"], &fields)
	for public, stored := range map[string]string{"name": "serverName", "peer": "peerAddress", "connections": "numConnections", "mtu": "tunnelMTU", "dns": "dnsServers", "vkLink": "vkLink", "keepalive": "persistentKeepalive", "turnServerOverride": "turnServerOverride", "uplinkPaceKiB": "uplinkPaceKiB", "cooldownSeconds": "credPoolCooldownSeconds", "vkAuth": "vkAuth", "forceLegacyCaptcha": "forceLegacyCaptcha", "routing": "routing"} {
		if value, ok := changes[public]; ok {
			if string(value) == "null" {
				return "", errors.New("editable settings cannot be null")
			}
			fields[stored] = value
		}
	}
	if raw, ok := changes["transport"]; ok {
		var transport string
		if json.Unmarshal(raw, &transport) != nil || (transport != "srtp" && transport != "dtls") {
			return "", errors.New("transport must be srtp or dtls")
		}
		fields["useSrtp"], _ = json.Marshal(transport == "srtp")
		fields["useDTLS"] = json.RawMessage("true")
	}
	if raw, ok := changes["turnTransport"]; ok {
		var turn string
		if string(raw) == "null" || json.Unmarshal(raw, &turn) != nil || (turn != "" && turn != "tcp" && turn != "udp") {
			return "", errors.New("TURN transport must be tcp or udp")
		}
		if turn == "" {
			delete(fields, "turnTransport")
		} else {
			fields["turnTransport"], _ = json.Marshal(turn)
		}
	}
	document["settings"], _ = json.Marshal(fields)
	output, _ := json.Marshal(document)
	return ImportProfile(string(output), "")
}
