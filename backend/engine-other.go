//go:build !android

// SPDX-License-Identifier: MIT
package mobile

import "errors"

func (e *Engine) Start(fd int, profileJSON, cacheDir string) error {
	return errors.New("VPN sessions require Android")
}
