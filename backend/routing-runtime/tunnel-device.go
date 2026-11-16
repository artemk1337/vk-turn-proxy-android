package routingruntime

import (
	"context"
	"net"
	"os"
	"sync"

	"golang.zx2c4.com/wireguard/tun"
)

// tunnelDevice preserves raw ICMP while TCP and UDP use the forwarding stack.
type tunnelDevice struct {
	base    tun.Device
	runtime *Runtime
	ctx     context.Context
	cancel  context.CancelFunc
	packets chan []byte
	done    chan struct{}
	once    sync.Once
	mu      sync.Mutex
	closed  bool
}

// WrapTunnelDevice transfers ownership of the WireGuard netstack TUN to a mux.
// The WireGuard device closes the mux after Runtime.Close has stopped flows.
func (r *Runtime) WrapTunnelDevice(base tun.Device) tun.Device {
	ctx, cancel := context.WithCancel(context.Background())
	device := &tunnelDevice{base: base, runtime: r, ctx: ctx, cancel: cancel, packets: make(chan []byte, 128), done: make(chan struct{})}
	r.mu.Lock()
	r.tunnelDevice = device
	r.mu.Unlock()
	go device.readBase()
	return device
}

func (d *tunnelDevice) readBase() {
	defer close(d.done)
	buffer := make([]byte, 65535)
	sizes := make([]int, 1)
	for {
		n, err := d.base.Read([][]byte{buffer}, sizes, 0)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		packet := append([]byte(nil), buffer[:sizes[0]]...)
		select {
		case d.packets <- packet:
		case <-d.ctx.Done():
			// Drain pending stack writes until Close shuts the underlying TUN.
		}
	}
}

func (d *tunnelDevice) enqueue(packet []byte) {
	select {
	case d.packets <- append([]byte(nil), packet...):
	case <-d.ctx.Done():
	default:
	}
}

func (d *tunnelDevice) Read(buffers [][]byte, sizes []int, offset int) (int, error) {
	select {
	case packet := <-d.packets:
		if len(buffers) == 0 || len(sizes) == 0 || len(buffers[0])-offset < len(packet) {
			return 0, os.ErrInvalid
		}
		sizes[0] = copy(buffers[0][offset:], packet)
		return 1, nil
	case <-d.ctx.Done():
		return 0, net.ErrClosed
	}
}

func rawICMPReply(packet []byte) bool {
	if len(packet) < 28 || packet[0]>>4 != 4 || packet[9] != 1 {
		return false
	}
	headerSize := int(packet[0]&15) * 4
	if headerSize < 20 || len(packet) < headerSize+8 {
		return false
	}
	kind := packet[headerSize]
	if kind == 0 || kind == 8 {
		return true
	}
	// Errors for re-originated TCP/UDP sessions belong to the VPN netstack.
	quoted := packet[headerSize+8:]
	return len(quoted) >= 20 && quoted[0]>>4 == 4 && quoted[9] == 1
}

func (d *tunnelDevice) Write(buffers [][]byte, offset int) (int, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closed {
		return 0, net.ErrClosed
	}
	for i, packet := range buffers {
		if rawICMPReply(packet[offset:]) {
			d.runtime.writeMu.Lock()
			_, err := d.runtime.tun.Write([][]byte{packet}, offset)
			d.runtime.writeMu.Unlock()
			if err != nil {
				return i, err
			}
		} else if _, err := d.base.Write([][]byte{packet}, offset); err != nil {
			return i, err
		}
	}
	return len(buffers), nil
}

func (d *tunnelDevice) Close() error {
	var err error
	d.once.Do(func() {
		d.cancel()
		d.mu.Lock()
		d.closed = true
		err = d.base.Close()
		d.mu.Unlock()
		<-d.done
	})
	return err
}

func (d *tunnelDevice) File() *os.File           { return d.base.File() }
func (d *tunnelDevice) MTU() (int, error)        { return d.base.MTU() }
func (d *tunnelDevice) Name() (string, error)    { return d.base.Name() }
func (d *tunnelDevice) Events() <-chan tun.Event { return d.base.Events() }
func (d *tunnelDevice) BatchSize() int           { return 1 }
