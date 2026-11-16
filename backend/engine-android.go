//go:build android

// SPDX-License-Identifier: MIT
package mobile

import (
	"errors"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/artemk1337/vk-turn-proxy-desktop/pkg/turnbind"
	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

type androidSession struct {
	device *device.Device
	once   sync.Once
}

func (s *androidSession) close() { s.once.Do(s.device.Close) }

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
func (e *Engine) Start(fd int, profileJSON, cacheDir string) error {
	// Transport's diagnostic logger is process-wide and may include credentials.
	log.SetOutput(io.Discard)
	owned := true
	defer func() {
		if owned && fd >= 0 {
			unix.Close(fd)
		}
	}()
	s, err := parseProfile(profileJSON)
	if err != nil {
		return err
	}
	if fd < 0 {
		return errors.New("invalid VPN file descriptor")
	}
	if !filepath.IsAbs(cacheDir) {
		return errors.New("cache directory must be absolute")
	}
	if err := os.MkdirAll(cacheDir, 0700); err != nil {
		return errors.New("cannot create private cache directory")
	}
	if err := os.Chmod(cacheDir, 0700); err != nil {
		return errors.New("cannot protect cache directory")
	}
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
	t, _, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err != nil {
		return errors.New("cannot attach VPN interface")
	}
	owned = false
	d := device.NewDevice(t, turnbind.NewTURNBind(p), device.NewLogger(device.LogLevelSilent, ""))
	session := &androidSession{device: d}
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		session.close()
		return errors.New("connection cancelled")
	}
	e.session = session
	e.mu.Unlock()
	if err := d.IpcSet(s.ipc()); err != nil {
		return errors.New("cannot configure WireGuard")
	}
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
