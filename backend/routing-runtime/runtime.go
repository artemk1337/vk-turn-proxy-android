package routingruntime

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	routingpolicy "github.com/artemk1337/vk-turn-proxy-desktop/pkg/routing-policy"
	"golang.zx2c4.com/wireguard/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

type Dialer interface {
	DialContext(context.Context, string, string) (net.Conn, error)
}

const maxFlows = 512

// Runtime owns the Android TUN and forwards policy-selected IPv4 flows.
type Runtime struct {
	ctx          context.Context
	cancel       context.CancelFunc
	tun          tun.Device
	tunnel       Dialer
	direct       Dialer
	router       *router
	stack        *stack.Stack
	link         *channel.Endpoint
	once         sync.Once
	wg           sync.WaitGroup
	mu           sync.Mutex
	closed       bool
	connections  map[io.Closer]struct{}
	flows        chan struct{}
	writeMu      sync.Mutex
	tunnelDevice *tunnelDevice
}

func New(device tun.Device, tunnel Dialer, policy routingpolicy.Policy, dns []netip.Addr) (*Runtime, error) {
	route, err := newRouter(policy, dns)
	if err != nil {
		return nil, err
	}
	mtu, err := device.MTU()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	r := &Runtime{ctx: ctx, cancel: cancel, tun: device, tunnel: tunnel, direct: &net.Dialer{Timeout: 30 * time.Second}, router: route,
		link: channel.New(512, uint32(mtu), ""), connections: make(map[io.Closer]struct{}), flows: make(chan struct{}, maxFlows)}
	r.stack = stack.New(stack.Options{NetworkProtocols: []stack.NetworkProtocolFactory{ipv4.NewProtocol}, TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol}})
	if err := r.stack.CreateNIC(1, r.link); err != nil {
		r.stack.Close()
		cancel()
		return nil, errors.New("cannot create routing interface")
	}
	r.stack.SetPromiscuousMode(1, true)
	r.stack.SetSpoofing(1, true)
	r.stack.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	r.stack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcp.NewForwarder(r.stack, 0, maxFlows, r.forwardTCP).HandlePacket)
	r.stack.SetTransportProtocolHandler(udp.ProtocolNumber, udp.NewForwarder(r.stack, r.forwardUDP).HandlePacket)
	r.wg.Add(2)
	go r.readTUN()
	go r.writeTUN()
	return r, nil
}

func (r *Runtime) track(conn io.Closer) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return false
	}
	r.connections[conn] = struct{}{}
	return true
}

func (r *Runtime) release(conn io.Closer) {
	conn.Close()
	r.mu.Lock()
	delete(r.connections, conn)
	r.mu.Unlock()
}

func (r *Runtime) acquire() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return false
	}
	select {
	case r.flows <- struct{}{}:
		r.wg.Add(1)
		return true
	default:
		return false
	}
}

func (r *Runtime) finishFlow() {
	<-r.flows
	r.wg.Done()
}

func destination(id stack.TransportEndpointID) (netip.Addr, string) {
	ip, _ := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	return ip, net.JoinHostPort(ip.String(), strconv.Itoa(int(id.LocalPort)))
}

func (r *Runtime) forwardTCP(request *tcp.ForwarderRequest) {
	if !r.acquire() {
		request.Complete(true)
		return
	}
	defer r.finishFlow()
	id := request.ID()
	var queue waiter.Queue
	endpoint, err := request.CreateEndpoint(&queue)
	request.Complete(err != nil)
	if err != nil {
		return
	}
	conn := gonet.NewTCPConn(&queue, endpoint)
	if !r.track(conn) {
		conn.Close()
		return
	}
	defer r.release(conn)
	ip, address := destination(id)
	if id.LocalPort == 53 {
		r.serveDNS(conn, "tcp", address)
		return
	}
	r.forward(conn, "tcp", ip, address)
}

func (r *Runtime) forwardUDP(request *udp.ForwarderRequest) {
	if !r.acquire() {
		return
	}
	var queue waiter.Queue
	endpoint, err := request.CreateEndpoint(&queue)
	if err != nil {
		r.finishFlow()
		return
	}
	conn := gonet.NewUDPConn(&queue, endpoint)
	if !r.track(conn) {
		conn.Close()
		r.finishFlow()
		return
	}
	id := request.ID()
	go func() {
		defer r.finishFlow()
		defer r.release(conn)
		ip, address := destination(id)
		if id.LocalPort == 53 {
			r.serveDNS(conn, "udp", address)
			return
		}
		r.forward(conn, "udp", ip, address)
	}()
}

func (r *Runtime) forward(local net.Conn, network string, ip netip.Addr, address string) {
	dialer := r.direct
	if r.router.tunnel(ip, time.Now()) {
		dialer = r.tunnel
	}
	ctx, cancel := context.WithTimeout(r.ctx, 30*time.Second)
	remote, err := dialer.DialContext(ctx, network, address)
	cancel()
	if err != nil {
		return
	}
	if !r.track(remote) {
		remote.Close()
		return
	}
	defer r.release(remote)
	done := make(chan struct{})
	go func() {
		copyFlow(remote, local, network)
		close(done)
	}()
	copyFlow(local, remote, network)
	local.Close()
	remote.Close()
	<-done
}

func copyFlow(destination, source net.Conn, network string) {
	buffer := make([]byte, 65535)
	for {
		if network == "udp" {
			source.SetReadDeadline(time.Now().Add(2 * time.Minute))
		}
		n, err := source.Read(buffer)
		if n > 0 {
			written, writeErr := destination.Write(buffer[:n])
			if writeErr != nil || written != n {
				destination.Close()
				source.Close()
				return
			}
		}
		if err != nil {
			if network == "tcp" {
				if half, ok := destination.(interface{ CloseWrite() error }); ok {
					half.CloseWrite()
					return
				}
			}
			destination.Close()
			source.Close()
			return
		}
	}
}

func (r *Runtime) readTUN() {
	defer r.wg.Done()
	data := make([]byte, 65535)
	sizes := make([]int, 1)
	for {
		n, err := r.tun.Read([][]byte{data}, sizes, 0)
		if err != nil {
			return
		}
		if n == 0 || sizes[0] < 20 || data[0]>>4 != 4 {
			continue
		}
		if data[9] == 1 {
			r.forwardICMP(data[:sizes[0]])
			continue
		}
		packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), data[:sizes[0]]...))})
		r.link.InjectInbound(ipv4.ProtocolNumber, packet)
		packet.DecRef()
	}
}

func (r *Runtime) writeTUN() {
	defer r.wg.Done()
	for {
		packet := r.link.ReadContext(r.ctx)
		if packet == nil {
			return
		}
		view := packet.ToView()
		r.writeMu.Lock()
		_, err := r.tun.Write([][]byte{view.AsSlice()}, 0)
		r.writeMu.Unlock()
		view.Release()
		packet.DecRef()
		if err != nil {
			return
		}
	}
}

func (r *Runtime) Close() error {
	r.once.Do(func() {
		r.mu.Lock()
		r.closed = true
		r.cancel()
		connections := make([]io.Closer, 0, len(r.connections))
		for conn := range r.connections {
			connections = append(connections, conn)
		}
		r.mu.Unlock()
		for _, conn := range connections {
			conn.Close()
		}
		r.tun.Close()
		r.stack.Close()
		r.link.Close()
		r.wg.Wait()
	})
	return nil
}
