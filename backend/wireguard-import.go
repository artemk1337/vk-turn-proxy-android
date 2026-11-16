// SPDX-License-Identifier: MIT
package mobile

import (
	"strconv"
	"strings"

	desktopsettings "github.com/artemk1337/vk-turn-proxy-desktop/pkg/desktop-settings"
	wgprofile "github.com/artemk1337/vk-turn-proxy-desktop/pkg/wireguard-profile"
)

// DescribeWireGuard converts a supported WireGuard config to a connection profile.
func DescribeWireGuard(config, vkLink string) (string, error) {
	return buildWireGuardProfile(config, vkLink, "")
}

// ImportWireGuardProfile applies a peer override before validating the endpoint.
func ImportWireGuardProfile(config, vkLink, peerAddress string) (string, error) {
	data, err := buildWireGuardProfile(config, vkLink, peerAddress)
	if err != nil {
		return "", err
	}
	return ImportProfile(data, "")
}

func buildWireGuardProfile(config, vkLink, peerAddress string) (string, error) {
	data, err := wgprofile.Build(wgprofile.Options{Config: normalizeWireGuardMTU(config), VKLink: vkLink, PeerAddress: strings.TrimSpace(peerAddress), UseSrtp: true})
	return string(data), err
}

func normalizeWireGuardMTU(config string) string {
	if len(config) > maxProfileBytes {
		return config
	}
	limits := desktopsettings.Defaults().MTU
	lines := strings.Split(strings.TrimPrefix(strings.TrimSpace(config), "\ufeff"), "\n")
	section := ""
	for index, line := range lines {
		setting := strings.TrimSpace(strings.SplitN(strings.SplitN(line, "#", 2)[0], ";", 2)[0])
		if strings.HasPrefix(setting, "[") {
			section = strings.ToLower(strings.TrimSpace(strings.TrimSuffix(strings.TrimPrefix(setting, "["), "]")))
			continue
		}
		key, value, ok := strings.Cut(setting, "=")
		if section != "interface" || !ok || !strings.EqualFold(strings.TrimSpace(key), "mtu") {
			continue
		}
		value = strings.TrimSpace(value)
		mtu, err := strconv.Atoi(value)
		if err != nil || mtu >= limits.Min && mtu <= limits.Max {
			continue
		}
		// Keep every setting and comment so the shared parser still rejects
		// duplicate directives and unsupported configuration content.
		prefix, original, _ := strings.Cut(line, "=")
		lines[index] = prefix + "=" + strings.Replace(original, value, strconv.Itoa(limits.Default), 1)
	}
	return strings.Join(lines, "\n")
}
