package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"sync"

	routingdata "github.com/artemk1337/vk-turn-proxy-desktop/pkg/routing-data"
	routingpolicy "github.com/artemk1337/vk-turn-proxy-desktop/pkg/routing-policy"
)

var routingStores = struct {
	sync.Mutex
	stores map[string]*routingdata.Store
}{stores: make(map[string]*routingdata.Store)}

func routingStore(directory string) *routingdata.Store {
	directory = filepath.Clean(directory)
	routingStores.Lock()
	defer routingStores.Unlock()
	store := routingStores.stores[directory]
	if store == nil {
		store = routingdata.NewStore(directory)
		routingStores.stores[directory] = store
	}
	return store
}

// RoutingCatalog reads the previously downloaded complete catalog pair.
func RoutingCatalog(directory string) (string, error) {
	if !filepath.IsAbs(directory) {
		return "", errors.New("routing directory must be absolute")
	}
	catalog, err := routingStore(directory).Catalog()
	if err != nil {
		return "", errors.New("routing catalogs are unavailable; download them in settings")
	}
	data, err := json.Marshal(catalog)
	return string(data), err
}

// UpdateRoutingData downloads and atomically replaces the complete catalog pair.
func UpdateRoutingData(directory string) (string, error) {
	if !filepath.IsAbs(directory) {
		return "", errors.New("routing directory must be absolute")
	}
	catalog, err := routingStore(directory).Update(context.Background())
	if err != nil {
		return "", errors.New("routing catalog update failed; previous catalogs were retained")
	}
	data, err := json.Marshal(catalog)
	return string(data), err
}

func validateRouting(raw json.RawMessage) error {
	_, err := parseRouting(raw)
	return err
}

func parseRouting(raw json.RawMessage) (routingpolicy.Settings, error) {
	var settings routingpolicy.Settings
	if len(raw) != 0 && string(raw) != "null" {
		if json.Unmarshal(raw, &settings) != nil {
			return settings, errors.New("invalid routing settings")
		}
	}
	if err := settings.Validate(); err != nil {
		return settings, errors.New("invalid routing settings")
	}
	return settings, nil
}

func resolveProfileRouting(raw json.RawMessage, cacheDir string) (routingpolicy.Policy, error) {
	settings, err := parseRouting(raw)
	if err != nil {
		return routingpolicy.Policy{}, err
	}
	policy := routingpolicy.Policy{Mode: "exclude", LocalDirect: settings.LocalDirect}
	if settings.Mode == "" {
		return policy, nil
	}
	policy.Mode = settings.Mode
	policy.Prefixes, policy.Domains, err = routingpolicy.ParseCustom(settings.Custom)
	if err != nil {
		return policy, errors.New("invalid custom routing rules")
	}
	if len(settings.GeoIP)+len(settings.GeoSite) > 0 {
		store := routingStore(filepath.Join(filepath.Dir(cacheDir), "routing-data"))
		rules, err := store.Resolve(settings.GeoIP, settings.GeoSite)
		if err != nil {
			return policy, errors.New("selected routing catalogs are unavailable; update them in settings")
		}
		policy.Prefixes = append(policy.Prefixes, rules.Prefixes...)
		for _, domain := range rules.Domains {
			policy.Domains = append(policy.Domains, routingpolicy.Domain{Type: domain.Type, Value: domain.Value})
		}
	}
	return policy, policy.Validate()
}
