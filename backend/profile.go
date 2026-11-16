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
	"time"
	"unicode"

	desktopsettings "github.com/artemk1337/vk-turn-proxy-desktop/pkg/desktop-settings"
	"github.com/artemk1337/vk-turn-proxy-desktop/pkg/proxy"
	wgprofile "github.com/artemk1337/vk-turn-proxy-desktop/pkg/wireguard-profile"
)

type profile struct {
	Version  int      `json:"version"`
	Type     string   `json:"type"`
	Settings settings `json:"settings"`
}

type settings struct {
	Name               string          `json:"serverName"`
	PrivateKey         string          `json:"privateKey"`
	PublicKey          string          `json:"peerPublicKey"`
	PresharedKey       string          `json:"presharedKey"`
	Address            string          `json:"tunnelAddress"`
	Peer               string          `json:"peerAddress"`
	VKLink             string          `json:"vkLink"`
	DNS                string          `json:"dnsServers"`
	MTU                int             `json:"tunnelMTU"`
	Connections        int             `json:"numConnections"`
	Keepalive          int             `json:"persistentKeepalive"`
	DTLS               bool            `json:"useDTLS"`
	UDP                bool            `json:"useUDP"`
	TurnTransport      string          `json:"turnTransport,omitempty"`
	SRTP               bool            `json:"useSrtp"`
	Wrap               bool            `json:"useWrap"`
	WrapA              bool            `json:"useWrapA"`
	WrapS              bool            `json:"useWrapS"`
	CSQTT              bool            `json:"useCsqtt"`
	Routing            json.RawMessage `json:"routing"`
	TurnServerOverride string          `json:"turnServerOverride"`
	UplinkPaceKiB      int             `json:"uplinkPaceKiB"`
	CooldownSeconds    int             `json:"credPoolCooldownSeconds"`
	ForceLegacyCaptcha bool            `json:"forceLegacyCaptcha"`
	VKAuth             bool            `json:"vkAuth"`
}

// DescribeWireGuard converts a supported WireGuard config to a connection profile.
func DescribeWireGuard(config, vkLink string) (string, error) {
	data, err := wgprofile.Build(wgprofile.Options{Config: config, VKLink: vkLink, UseSrtp: true})
	return string(data), err
}

// DescribeProfile validates a profile and returns public VPN interface parameters.
func DescribeProfile(input string) (string, error) {
	p, err := loadProfile(input)
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
	p.Settings = defaultSettings()
	if json.Unmarshal([]byte(input), &p) != nil {
		return settings{}, errors.New("invalid profile JSON")
	}
	s := p.Settings
	s.PrivateKey, s.PublicKey, s.PresharedKey = strings.TrimSpace(s.PrivateKey), strings.TrimSpace(s.PublicKey), strings.TrimSpace(s.PresharedKey)
	s.Address, s.Peer, s.DNS = strings.TrimSpace(s.Address), strings.TrimSpace(s.Peer), strings.TrimSpace(s.DNS)
	if s.Name == "" {
		s.Name = "Imported profile"
	}
	if s.MTU == 0 {
		s.MTU = desktopsettings.Defaults().MTU.Default
	}
	if p.Version != 1 || p.Type != "connection" {
		return s, errors.New("use a version 1 connection profile")
	}
	if s.Wrap || s.WrapA || s.WrapS || s.CSQTT || (!s.SRTP && !s.DTLS) {
		return s, errors.New("supported transports are SRTP and DTLS")
	}
	if s.TurnTransport != "" && s.TurnTransport != "tcp" && s.TurnTransport != "udp" {
		return s, errors.New("TURN transport must be tcp or udp")
	}
	if err := validateRouting(s.Routing); err != nil {
		return s, err
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
	links := strings.Fields(s.VKLink)
	if len(links) == 0 {
		return s, errors.New("enter a VK call invitation link")
	}
	for _, link := range links {
		u, err := url.Parse(link)
		if err != nil || u.Scheme != "https" || u.User != nil || u.Port() != "" || (u.Host != "vk.ru" && u.Host != "vk.com" && u.Host != "vk.me") {
			return s, errors.New("enter an HTTPS VK call invitation link")
		}
		if !(strings.HasPrefix(u.Path, "/call/join/") || u.Host == "vk.me" && strings.HasPrefix(u.Path, "/join/")) {
			return s, errors.New("enter a VK call invitation link")
		}
	}
	limits := desktopsettings.Defaults()
	if s.MTU < limits.MTU.Min || s.MTU > limits.MTU.Max || s.Connections < limits.Connections.Min || s.Connections > limits.Connections.Max || s.Keepalive < 0 || s.Keepalive > 65535 {
		return s, errors.New("invalid MTU, connection count or keepalive")
	}
	if s.UplinkPaceKiB < 0 || s.CooldownSeconds < 0 || int64(s.CooldownSeconds) > (1<<63-1)/int64(time.Second) {
		return s, errors.New("invalid uplink pacing or credential cooldown")
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

func defaultSettings() settings {
	d := desktopsettings.Defaults()
	return settings{Name: "Imported profile", Address: "192.168.102.3/24", DNS: "1.1.1.1", MTU: d.MTU.Default, Connections: d.Connections.Default, Keepalive: 25, CooldownSeconds: 150, DTLS: true, SRTP: d.DefaultTransport == "srtp"}
}

func (s settings) proxyConfig(cachePath string) proxy.Config {
	// Desktop ignores invalid TURN overrides and uses automatic relay selection.
	servers, _ := proxy.ParseTURNServers(s.TurnServerOverride)
	useUDP := s.UDP
	if s.TurnTransport != "" {
		useUDP = s.TurnTransport == "udp"
	}
	return proxy.Config{PeerAddr: s.Peer, VKLink: strings.Fields(s.VKLink)[0], UseDTLS: s.DTLS, UseUDP: useUDP, UseSrtp: s.SRTP, NumConns: s.Connections, TurnServers: servers, CredPoolCooldown: time.Duration(s.CooldownSeconds) * time.Second, CredCachePath: cachePath, GOMAXPROCS: -1}
}

func keyHex(value string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(value)
	if err != nil || len(b) != 32 {
		return "", errors.New("WireGuard keys must contain 32 base64-encoded bytes")
	}
	return hex.EncodeToString(b), nil
}

func splitDNS(value string) []string {
	return strings.FieldsFunc(value, func(r rune) bool { return r == ',' || unicode.IsSpace(r) })
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
