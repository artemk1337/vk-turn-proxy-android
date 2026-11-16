// SPDX-License-Identifier: MIT
package mobile

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"

	wgprofile "github.com/artemk1337/vk-turn-proxy-desktop/pkg/wireguard-profile"
)

type profile struct {
	Version  int      `json:"version"`
	Type     string   `json:"type"`
	Settings settings `json:"settings"`
}

type settings struct {
	Name         string          `json:"serverName"`
	PrivateKey   string          `json:"privateKey"`
	PublicKey    string          `json:"peerPublicKey"`
	PresharedKey string          `json:"presharedKey"`
	Address      string          `json:"tunnelAddress"`
	Peer         string          `json:"peerAddress"`
	VKLink       string          `json:"vkLink"`
	DNS          string          `json:"dnsServers"`
	MTU          int             `json:"tunnelMTU"`
	Connections  int             `json:"numConnections"`
	Keepalive    int             `json:"persistentKeepalive"`
	DTLS         bool            `json:"useDTLS"`
	UDP          bool            `json:"useUDP"`
	SRTP         bool            `json:"useSrtp"`
	Wrap         bool            `json:"useWrap"`
	WrapA        bool            `json:"useWrapA"`
	WrapS        bool            `json:"useWrapS"`
	CSQTT        bool            `json:"useCsqtt"`
	Routing      json.RawMessage `json:"routing"`
}

// DescribeWireGuard converts a supported WireGuard config to a connection profile.
func DescribeWireGuard(config, vkLink string) (string, error) {
	data, err := wgprofile.Build(wgprofile.Options{Config: config, VKLink: vkLink, UseSrtp: true})
	return string(data), err
}

// DescribeProfile validates a profile and returns public VPN interface parameters.
func DescribeProfile(input string) (string, error) {
	p, err := parseProfile(input)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(struct {
		Name    string   `json:"name"`
		Address string   `json:"address"`
		DNS     []string `json:"dns"`
		MTU     int      `json:"mtu"`
	}{p.Name, p.Address, splitDNS(p.DNS), p.MTU})
	return string(data), err
}

func parseProfile(input string) (settings, error) {
	var p profile
	if len(input) > 4<<20 {
		return settings{}, errors.New("profile exceeds 4 MiB")
	}
	p.Settings = settings{Address: "192.168.102.3/24", DNS: "1.1.1.1", MTU: 1280, Connections: 10, DTLS: true, SRTP: true}
	if json.Unmarshal([]byte(input), &p) != nil {
		return settings{}, errors.New("invalid profile JSON")
	}
	s := p.Settings
	if p.Version != 1 || p.Type != "connection" {
		return s, errors.New("use a version 1 connection profile")
	}
	if s.Wrap || s.WrapA || s.WrapS || s.CSQTT || (!s.SRTP && !s.DTLS) {
		return s, errors.New("supported transports are SRTP and DTLS")
	}
	if len(s.Routing) > 0 && string(s.Routing) != "null" {
		var routing struct {
			Mode        string `json:"mode"`
			LocalDirect bool   `json:"localDirect"`
		}
		if json.Unmarshal(s.Routing, &routing) != nil || routing.Mode != "" || routing.LocalDirect {
			return s, errors.New("routing policies are unsupported on Android")
		}
	}
	for _, key := range []string{s.PrivateKey, s.PublicKey} {
		if _, err := keyHex(key); err != nil {
			return s, err
		}
	}
	if s.PresharedKey != "" {
		if _, err := keyHex(s.PresharedKey); err != nil {
			return s, err
		}
	}
	address, err := netip.ParsePrefix(s.Address)
	if err != nil || !address.Addr().Is4() {
		return s, errors.New("tunnel address must be IPv4 CIDR")
	}
	host, port, err := net.SplitHostPort(s.Peer)
	n, portErr := strconv.Atoi(port)
	if err != nil || portErr != nil || host == "" || n < 1 || n > 65535 || strings.ContainsAny(host, "\r\n\t /\\?#@") {
		return s, errors.New("server address must be host:port")
	}
	u, err := url.Parse(s.VKLink)
	if err != nil || u.Scheme != "https" || u.User != nil || u.Port() != "" || (u.Host != "vk.ru" && u.Host != "vk.com" && u.Host != "vk.me") {
		return s, errors.New("enter an HTTPS VK call invitation link")
	}
	if !(strings.HasPrefix(u.Path, "/call/join/") || u.Host == "vk.me" && strings.HasPrefix(u.Path, "/join/")) {
		return s, errors.New("enter a VK call invitation link")
	}
	if s.MTU < 1000 || s.MTU > 1400 || s.Connections < 1 || s.Connections > 120 || s.Keepalive < 0 || s.Keepalive > 65535 {
		return s, errors.New("invalid MTU, connection count or keepalive")
	}
	if len(splitDNS(s.DNS)) == 0 {
		return s, errors.New("provide an IPv4 DNS server")
	}
	for _, value := range splitDNS(s.DNS) {
		ip, err := netip.ParseAddr(value)
		if err != nil || !ip.Is4() {
			return s, errors.New("DNS servers must be IPv4 addresses")
		}
	}
	return s, nil
}

func keyHex(value string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(value)
	if err != nil || len(b) != 32 {
		return "", errors.New("WireGuard keys must contain 32 base64-encoded bytes")
	}
	return hex.EncodeToString(b), nil
}

func splitDNS(value string) []string {
	return strings.FieldsFunc(value, func(r rune) bool { return r == ',' || r == ' ' || r == '\t' })
}

func (s settings) ipc() string {
	private, _ := keyHex(s.PrivateKey)
	public, _ := keyHex(s.PublicKey)
	config := fmt.Sprintf("private_key=%s\nreplace_peers=true\npublic_key=%s\nendpoint=%s\nreplace_allowed_ips=true\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=%d\n", private, public, s.Peer, s.Keepalive)
	if s.PresharedKey != "" {
		key, _ := keyHex(s.PresharedKey)
		config += "preshared_key=" + key + "\n"
	}
	return config
}
