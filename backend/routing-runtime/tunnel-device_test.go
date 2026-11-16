package routingruntime

import (
	"bytes"
	"encoding/binary"
	"net"
	"net/netip"
	"os"
	"sync"
	"testing"
	"time"

	routingpolicy "github.com/artemk1337/vk-turn-proxy-desktop/pkg/routing-policy"
	"golang.org/x/net/icmp"
	"golang.zx2c4.com/wireguard/tun"
)

type packetDevice struct {
	reads  chan []byte
	writes chan []byte
	done   chan struct{}
	once   sync.Once
	events chan tun.Event
}

func newPacketDevice() *packetDevice {
	return &packetDevice{reads: make(chan []byte, 16), writes: make(chan []byte, 16), done: make(chan struct{}), events: make(chan tun.Event)}
}

func (d *packetDevice) Read(buffers [][]byte, sizes []int, offset int) (int, error) {
	select {
	case packet := <-d.reads:
		sizes[0] = copy(buffers[0][offset:], packet)
		return 1, nil
	case <-d.done:
		return 0, net.ErrClosed
	}
}
func (d *packetDevice) Write(buffers [][]byte, offset int) (int, error) {
	for i, packet := range buffers {
		select {
		case d.writes <- append([]byte(nil), packet[offset:]...):
		case <-d.done:
			return i, net.ErrClosed
		}
	}
	return len(buffers), nil
}
func (d *packetDevice) Close() error             { d.once.Do(func() { close(d.done) }); return nil }
func (d *packetDevice) File() *os.File           { return nil }
func (d *packetDevice) Name() (string, error)    { return "test", nil }
func (d *packetDevice) MTU() (int, error)        { return 1280, nil }
func (d *packetDevice) Events() <-chan tun.Event { return d.events }
func (d *packetDevice) BatchSize() int           { return 1 }

func echoPacket(destination string) []byte {
	packet := make([]byte, 30)
	packet[0], packet[8], packet[9] = 0x45, 64, 1
	binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
	copy(packet[12:16], netip.MustParseAddr("10.0.0.2").AsSlice())
	copy(packet[16:20], netip.MustParseAddr(destination).AsSlice())
	copy(packet[20:], []byte{8, 0, 0, 0, 0, 42, 0, 17, 'o', 'k'})
	binary.BigEndian.PutUint16(packet[22:24], checksum(packet[20:]))
	binary.BigEndian.PutUint16(packet[10:12], checksum(packet[:20]))
	return packet
}

func TestRawTunneledICMPAndControlMessageOwnership(t *testing.T) {
	android, base := newPacketDevice(), newPacketDevice()
	runtime, err := New(android, &net.Dialer{}, routingpolicy.Policy{Mode: "exclude"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	wireguard := runtime.WrapTunnelDevice(base)
	defer func() { runtime.Close(); wireguard.Close() }()
	request := echoPacket("203.0.113.7")
	android.reads <- request
	read := make(chan []byte, 1)
	go func() {
		buffer := make([]byte, 256)
		sizes := make([]int, 1)
		n, err := wireguard.Read([][]byte{buffer}, sizes, 16)
		if err == nil && n == 1 {
			read <- buffer[16 : 16+sizes[0]]
		}
	}()
	select {
	case got := <-read:
		if !bytes.Equal(got, request) {
			t.Fatal("raw ICMP request changed")
		}
	case <-time.After(time.Second):
		t.Fatal("ICMP did not reach WireGuard")
	}
	reply := append([]byte(nil), request...)
	copy(reply[12:16], request[16:20])
	copy(reply[16:20], request[12:16])
	reply[20] = 0
	if _, err := wireguard.Write([][]byte{append(make([]byte, 8), reply...)}, 8); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-android.writes:
		if !bytes.Equal(got, reply) {
			t.Fatal("raw ICMP reply changed")
		}
	case <-time.After(time.Second):
		t.Fatal("ICMP did not return to Android")
	}
	control := make([]byte, 48)
	copy(control, reply[:20])
	control[20] = 3
	control[28] = 0x45
	control[37] = 6
	if _, err := wireguard.Write([][]byte{control}, 0); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-base.writes:
		if !bytes.Equal(got, control) {
			t.Fatal("TCP error changed")
		}
	case <-time.After(time.Second):
		t.Fatal("TCP ICMP error escaped its netstack")
	}
}

func TestPhysicalICMPEcho(t *testing.T) {
	probe, err := icmp.ListenPacket("udp4", "0.0.0.0")
	if err != nil {
		t.Skipf("unprivileged ping sockets unavailable: %v", err)
	}
	probe.Close()
	android := newPacketDevice()
	runtime, err := New(android, &net.Dialer{}, routingpolicy.Policy{Mode: "include"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer runtime.Close()
	android.reads <- echoPacket("127.0.0.1")
	select {
	case reply := <-android.writes:
		if len(reply) != 30 || reply[20] != 0 || binary.BigEndian.Uint16(reply[24:26]) != 42 || string(reply[28:]) != "ok" {
			t.Fatalf("invalid physical echo %x", reply)
		}
		if checksum(reply[:20]) != 0 || checksum(reply[20:]) != 0 {
			t.Fatal("invalid reply checksums")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("physical echo timed out")
	}
}
