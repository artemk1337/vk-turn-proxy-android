package mobile

import (
	"encoding/json"
	"path/filepath"
	"sync"
	"testing"
)

func TestRoutingStoresShareUpdateAndReadSynchronization(t *testing.T) {
	directory := t.TempDir()
	var workers sync.WaitGroup
	stores := make(chan any, 20)
	for range 20 {
		workers.Go(func() { stores <- routingStore(filepath.Join(directory, "unused", "..")) })
	}
	workers.Wait()
	close(stores)
	want := routingStore(directory)
	for got := range stores {
		if got != want {
			t.Fatal("concurrent API calls received different stores")
		}
	}
}

func TestResolveCustomRoutingAndFullTunnelCompatibility(t *testing.T) {
	for _, input := range []string{"", `null`, `{}`, `{"mode":"","localDirect":true}`} {
		policy, err := resolveProfileRouting(json.RawMessage(input), filepath.Join(t.TempDir(), "transport"))
		if err != nil {
			t.Fatal(err)
		}
		if policy.Mode != "exclude" || len(policy.Prefixes) != 0 || len(policy.Domains) != 0 {
			t.Fatal("full tunnel changed")
		}
	}
	policy, err := resolveProfileRouting(json.RawMessage(`{"mode":"include","custom":"203.0.113.0/24\nexample.com","localDirect":true}`), filepath.Join(t.TempDir(), "transport"))
	if err != nil {
		t.Fatal(err)
	}
	if policy.Mode != "include" || len(policy.Prefixes) != 1 || len(policy.Domains) != 1 || !policy.LocalDirect {
		t.Fatal("custom policy lost settings")
	}
	if _, err := resolveProfileRouting(json.RawMessage(`{"mode":"include","geoip":["ru"]}`), filepath.Join(t.TempDir(), "transport")); err == nil {
		t.Fatal("missing selected catalog silently ignored")
	}
}
