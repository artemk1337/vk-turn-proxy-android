package routingruntime

import (
	"context"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	routingpolicy "github.com/artemk1337/vk-turn-proxy-desktop/pkg/routing-policy"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func TestDNSForwardingLearnsBeforeReplyAndAlwaysUsesTunnel(t *testing.T) {
	for _, network := range []string{"tcp", "udp"} {
		t.Run(network, func(t *testing.T) {
			target, stop := dnsServer(t, network)
			defer stop()
			clientTUN, client, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.0.0.2")}, nil, 1280)
			if err != nil {
				t.Fatal(err)
			}
			tunnel := &recordingDialer{target: target}
			direct := &recordingDialer{target: target}
			runtime, err := New(clientTUN, tunnel, routingpolicy.Policy{Mode: "include", Domains: []routingpolicy.Domain{{Type: "domain", Value: "selected.example"}}}, nil)
			if err != nil {
				t.Fatal(err)
			}
			runtime.direct = direct
			defer runtime.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			conn, err := client.DialContext(ctx, network, "203.0.113.53:53")
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(5 * time.Second))
			query, _ := (&dnsmessage.Message{Header: dnsmessage.Header{ID: 77}, Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("selected.example."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}).Pack()
			var response []byte
			if network == "tcp" {
				if err := writeDNSFrame(conn, query); err != nil {
					t.Fatal(err)
				}
				response, err = readDNSFrame(conn)
			} else {
				if _, err := conn.Write(query); err != nil {
					t.Fatal(err)
				}
				buffer := make([]byte, 65535)
				var n int
				n, err = conn.Read(buffer)
				response = buffer[:n]
			}
			if err != nil {
				t.Fatal(err)
			}
			var answer dnsmessage.Message
			if answer.Unpack(response) != nil || !answer.Response || answer.ID != 77 {
				t.Fatal("invalid forwarded DNS answer")
			}
			if !runtime.router.tunnel(netip.MustParseAddr("203.0.113.7"), time.Now()) {
				t.Fatal("answer escaped before its domain route was ready")
			}
			if tunnel.count() != 1 || direct.count() != 0 {
				t.Fatal("DNS bypassed the VPN")
			}
		})
	}
}

func dnsAnswer(query []byte) []byte {
	var message dnsmessage.Message
	if message.Unpack(query) != nil || len(message.Questions) != 1 {
		return nil
	}
	message.Response = true
	message.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: message.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: [4]byte{203, 0, 113, 7}}}}
	answer, _ := message.Pack()
	return answer
}

func dnsServer(t *testing.T, network string) (string, func()) {
	t.Helper()
	if network == "tcp" {
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		go func() {
			for {
				conn, err := listener.Accept()
				if err != nil {
					return
				}
				go func() {
					defer conn.Close()
					query, err := readDNSFrame(conn)
					if err == nil {
						writeDNSFrame(conn, dnsAnswer(query))
					}
				}()
			}
		}()
		return listener.Addr().String(), func() { listener.Close() }
	}
	listener, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		buffer := make([]byte, 65535)
		for {
			n, source, err := listener.ReadFrom(buffer)
			if err != nil {
				return
			}
			listener.WriteTo(dnsAnswer(buffer[:n]), source)
		}
	}()
	return listener.LocalAddr().String(), func() { listener.Close() }
}

type recordingDialer struct {
	target    string
	mu        sync.Mutex
	addresses []string
}

func (d *recordingDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	d.mu.Lock()
	d.addresses = append(d.addresses, address)
	d.mu.Unlock()
	return (&net.Dialer{}).DialContext(ctx, network, d.target)
}

func (d *recordingDialer) count() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.addresses)
}

func TestForwardTCPAndUDPSelectPhysicalOrTunnel(t *testing.T) {
	for _, network := range []string{"tcp", "udp"} {
		for _, mode := range []string{"include", "exclude"} {
			t.Run(network+"/"+mode, func(t *testing.T) {
				target, stop := echoServer(t, network)
				defer stop()
				clientTUN, client, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.0.0.2")}, nil, 1280)
				if err != nil {
					t.Fatal(err)
				}
				tunnel := &recordingDialer{target: target}
				direct := &recordingDialer{target: target}
				runtime, err := New(clientTUN, tunnel, routingpolicy.Policy{Mode: mode, Prefixes: []string{"203.0.113.0/24"}}, nil)
				if err != nil {
					t.Fatal(err)
				}
				runtime.direct = direct
				defer runtime.Close()
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				defer cancel()
				conn, err := client.DialContext(ctx, network, "203.0.113.7:1234")
				if err != nil {
					t.Fatal(err)
				}
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(5 * time.Second))
				if _, err := conn.Write([]byte("hello")); err != nil {
					t.Fatal(err)
				}
				response := make([]byte, 5)
				if _, err := io.ReadFull(conn, response); err != nil {
					t.Fatal(err)
				}
				if string(response) != "hello" {
					t.Fatalf("unexpected echo %q", response)
				}
				if mode == "include" && (tunnel.count() != 1 || direct.count() != 0) {
					t.Fatal("selected destination bypassed tunnel")
				}
				if mode == "exclude" && (direct.count() != 1 || tunnel.count() != 0) {
					t.Fatal("excluded destination entered tunnel")
				}
				closed := make(chan struct{})
				go func() { runtime.Close(); close(closed) }()
				select {
				case <-closed:
				case <-time.After(5 * time.Second):
					t.Fatal("runtime close did not cancel active flow")
				}
			})
		}
	}
}

func echoServer(t *testing.T, network string) (string, func()) {
	t.Helper()
	if network == "tcp" {
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		go func() {
			for {
				conn, err := listener.Accept()
				if err != nil {
					return
				}
				go func() { defer conn.Close(); io.Copy(conn, conn) }()
			}
		}()
		return listener.Addr().String(), func() { listener.Close() }
	}
	listener, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		buffer := make([]byte, 65535)
		for {
			n, source, err := listener.ReadFrom(buffer)
			if err != nil {
				return
			}
			listener.WriteTo(buffer[:n], source)
		}
	}()
	return listener.LocalAddr().String(), func() { listener.Close() }
}
