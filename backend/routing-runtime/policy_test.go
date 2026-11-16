package routingruntime

import (
	"net/netip"
	"testing"
	"time"

	routingpolicy "github.com/artemk1337/vk-turn-proxy-desktop/pkg/routing-policy"
	"golang.org/x/net/dns/dnsmessage"
)

func TestRoutingDirectionsAndDNSPriority(t *testing.T) {
	now := time.Now()
	for _, mode := range []string{"include", "exclude"} {
		r, err := newRouter(routingpolicy.Policy{Mode: mode, Prefixes: []string{"203.0.113.0/24"}, LocalDirect: true}, []netip.Addr{netip.MustParseAddr("10.0.0.53")})
		if err != nil {
			t.Fatal(err)
		}
		for raw, want := range map[string]bool{"203.0.113.5": mode == "include", "198.51.100.5": mode == "exclude", "192.168.1.1": false, "10.0.0.53": true} {
			if got := r.tunnel(netip.MustParseAddr(raw), now); got != want {
				t.Fatalf("mode=%s ip=%s got=%t want=%t", mode, raw, got, want)
			}
		}
	}
}

func TestDNSAliasesAndServiceHintsExpire(t *testing.T) {
	now := time.Now()
	r, err := newRouter(routingpolicy.Policy{Mode: "include", Domains: []routingpolicy.Domain{{Type: "domain", Value: "selected.example"}}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	name := dnsmessage.MustNewName("selected.example.")
	alias := dnsmessage.MustNewName("cdn.example.")
	query := dnsmessage.Message{Header: dnsmessage.Header{ID: 17}, Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
	answer := query
	answer.Response = true
	answer.Answers = []dnsmessage.Resource{
		{Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeCNAME, Class: dnsmessage.ClassINET, TTL: 5}, Body: &dnsmessage.CNAMEResource{CNAME: alias}},
		{Header: dnsmessage.ResourceHeader{Name: alias, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 30}, Body: &dnsmessage.AResource{A: [4]byte{203, 0, 113, 8}}},
		{Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeHTTPS, Class: dnsmessage.ClassINET, TTL: 30}, Body: &dnsmessage.HTTPSResource{SVCBResource: dnsmessage.SVCBResource{Priority: 1, Target: dnsmessage.MustNewName("."), Params: []dnsmessage.SVCParam{{Key: dnsmessage.SVCParamIPv4Hint, Value: []byte{203, 0, 113, 9}}}}}},
	}
	q, _ := query.Pack()
	a, err := answer.Pack()
	if err != nil {
		t.Fatal(err)
	}
	if err := r.observeDNS(q, a, now); err != nil {
		t.Fatal(err)
	}
	for _, ip := range []string{"203.0.113.8", "203.0.113.9"} {
		address := netip.MustParseAddr(ip)
		if !r.tunnel(address, now) {
			t.Fatalf("DNS route missing before answer %s", ip)
		}
		delay := 31 * time.Second
		if ip == "203.0.113.8" {
			delay = 6 * time.Second
		}
		if r.tunnel(address, now.Add(delay)) {
			t.Fatalf("DNS route did not expire %s", ip)
		}
	}
	answer.ID++
	bad, _ := answer.Pack()
	if r.observeDNS(q, bad, now) == nil {
		t.Fatal("unrelated DNS response accepted")
	}
}

func TestDNSCacheLimitIsAtomic(t *testing.T) {
	r, err := newRouter(routingpolicy.Policy{Mode: "exclude"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	expires := time.Now().Add(time.Minute)
	for i := range maxDNSRoutes {
		r.learned[netip.AddrFrom4([4]byte{10, byte(i >> 16), byte(i >> 8), byte(i)})] = expires
	}
	if r.learn(map[netip.Addr]time.Time{netip.MustParseAddr("203.0.113.1"): expires}, time.Now()) == nil {
		t.Fatal("cache overflow accepted")
	}
	if len(r.learned) != maxDNSRoutes {
		t.Fatal("cache partially changed on overflow")
	}
}
