//go:build android

// SPDX-License-Identifier: MIT
package mobile

import (
	"errors"
	"io"
	"log"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	routingruntime "github.com/artemk1337/vk-turn-proxy-android/backend/routing-runtime"
	"github.com/artemk1337/vk-turn-proxy-desktop/pkg/turnbind"
	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type androidSession struct {
	device  *device.Device
	once    sync.Once
	routing *routingruntime.Runtime
}

func (s *androidSession) close() {
	s.once.Do(func() {
		if s.routing != nil {
			s.routing.Close()
		}
		s.device.Close()
	})
}

func (s *androidSession) connected() bool {
	status, err := s.device.IpcGet()
	if err != nil {
		return false
	}
	for _, line := range strings.Split(status, "\n") {
		if strings.HasPrefix(line, "last_handshake_time_sec=") && line != "last_handshake_time_sec=0" {
			return true
		}
	}
	return false
}

// Start takes ownership of fd, including on failure. Call from a worker thread.
func (e *Engine) Start(fd int, profileJSON, cacheDir string) (result error) {
	// Transport's diagnostic logger is process-wide and may include credentials.
	log.SetOutput(io.Discard)
	stage := "profile validation"
	defer func() {
		if result != nil {
			e.mu.Lock()
			e.appendLogLocked("VPN startup failed during " + stage)
			e.mu.Unlock()
		}
	}()
	owned := true
	defer func() {
		if owned && fd >= 0 {
			unix.Close(fd)
		}
	}()
	s, err := loadProfile(profileJSON)
	if err != nil {
		return err
	}
	if fd < 0 {
		return errors.New("invalid VPN file descriptor")
	}
	stage = "private cache setup"
	if !filepath.IsAbs(cacheDir) {
		return errors.New("cache directory must be absolute")
	}
	if err := os.MkdirAll(cacheDir, 0700); err != nil {
		return errors.New("cannot create private cache directory")
	}
	if err := os.Chmod(cacheDir, 0700); err != nil {
		return errors.New("cannot protect cache directory")
	}
	stage = "routing policy resolution"
	policy, err := resolveProfileRouting(s.Routing, cacheDir)
	if err != nil {
		return err
	}
	stage = "transport initialization"
	p, err := e.begin(s, filepath.Join(cacheDir, "turn-credentials.json"))
	if err != nil {
		return err
	}
	success := false
	defer func() {
		if !success {
			e.Stop()
		}
	}()
	stage = "VPN interface setup"
	t, _, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err != nil {
		return errors.New("cannot attach VPN interface")
	}
	owned = false
	wgTUN := t
	var routing *routingruntime.Runtime
	routingSettings, _ := parseRouting(s.Routing)
	if routingSettings.Mode != "" || routingSettings.LocalDirect {
		stage = "routing forwarder setup"
		address, _ := netip.ParsePrefix(s.Address)
		var dns []netip.Addr
		for _, value := range splitDNS(s.DNS) {
			ip, _ := netip.ParseAddr(value)
			dns = append(dns, ip)
		}
		var network *netstack.Net
		wgTUN, network, err = netstack.CreateNetTUN([]netip.Addr{address.Addr()}, dns, s.MTU)
		if err != nil {
			t.Close()
			return errors.New("cannot create routing tunnel")
		}
		routing, err = routingruntime.New(t, network, policy, dns)
		if err != nil {
			wgTUN.Close()
			t.Close()
			return errors.New("cannot start routing forwarder")
		}
		wgTUN = routing.WrapTunnelDevice(wgTUN)
	}
	d := device.NewDevice(wgTUN, turnbind.NewTURNBind(p), device.NewLogger(device.LogLevelSilent, ""))
	session := &androidSession{device: d, routing: routing}
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		session.close()
		return errors.New("connection cancelled")
	}
	e.session = session
	e.mu.Unlock()
	stage = "WireGuard configuration"
	if err := d.IpcSet(s.ipc()); err != nil {
		return errors.New("cannot configure WireGuard")
	}
	stage = "TURN connection"
	if err := d.Up(); err != nil {
		return errors.New("cannot start VPN transport")
	}
	publicKey, _ := keyHex(s.PublicKey)
	var key device.NoisePublicKey
	if err := key.FromHex(publicKey); err != nil {
		return errors.New("cannot decode WireGuard peer key")
	}
	peer := d.LookupPeer(key)
	if peer == nil {
		return errors.New("WireGuard peer is missing")
	}
	// Initiate once even when the imported profile disables keepalives.
	// WireGuard's timers retry a transient send failure.
	_ = peer.SendHandshakeInitiation(false)
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return errors.New("connection cancelled")
	}
	e.state = "connecting"
	e.errorText = ""
	e.readyAt = time.Now()
	e.mu.Unlock()
	success = true
	return nil
}
