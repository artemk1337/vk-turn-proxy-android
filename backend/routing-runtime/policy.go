package routingruntime

import (
	"errors"
	"net/netip"
	"sync"
	"time"

	routingpolicy "github.com/artemk1337/vk-turn-proxy-desktop/pkg/routing-policy"
)

const maxDNSRoutes = 65536

type router struct {
	policy  routingpolicy.Policy
	matcher *routingpolicy.Matcher
	dns     map[netip.Addr]bool
	mu      sync.Mutex
	learned map[netip.Addr]time.Time
}

func newRouter(policy routingpolicy.Policy, dns []netip.Addr) (*router, error) {
	matcher, err := routingpolicy.Compile(policy)
	if err != nil {
		return nil, err
	}
	r := &router{policy: policy, matcher: matcher, dns: make(map[netip.Addr]bool), learned: make(map[netip.Addr]time.Time)}
	for _, ip := range dns {
		r.dns[ip.Unmap()] = true
	}
	return r, nil
}

func (r *router) tunnel(ip netip.Addr, now time.Time) bool {
	ip = ip.Unmap()
	if r.dns[ip] {
		return true
	}
	if r.policy.LocalDirect && routingpolicy.IsLocal(ip) {
		return false
	}
	r.mu.Lock()
	expires, learned := r.learned[ip]
	if learned && !now.Before(expires) {
		delete(r.learned, ip)
		learned = false
	}
	r.mu.Unlock()
	matched := r.matcher.MatchesIP(ip) || learned
	return (r.policy.Mode == "include") == matched
}

func (r *router) learn(addresses map[netip.Addr]time.Time, now time.Time) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	for ip, expiry := range r.learned {
		if !now.Before(expiry) {
			delete(r.learned, ip)
		}
	}
	missing := 0
	for ip := range addresses {
		if _, ok := r.learned[ip]; !ok {
			missing++
		}
	}
	if len(r.learned)+missing > maxDNSRoutes {
		return errors.New("DNS routing cache is full")
	}
	for ip, expiry := range addresses {
		if expiry.After(r.learned[ip]) {
			r.learned[ip] = expiry
		}
	}
	return nil
}
