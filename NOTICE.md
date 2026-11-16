# Third-party notices

VK TURN Proxy Android is distributed under GPL-3.0. Existing copyright and SPDX notices remain applicable to their files.

## Shared transport

The Android client uses the Go transport and profile parser from VK TURN Proxy Desktop. The dependency is pinned in `backend/go.mod`. [VK TURN Proxy Desktop](https://github.com/artemk1337/vk-turn-proxy-desktop)

The shared transport originated in anton48/vk-turn-proxy-ios, source snapshot `5c289b58fd8e772512d81487c69a224659a8c6da`. Original authors include anton48, cacggghp and other upstream contributors. Original MIT notices remain in the shared source. [Upstream project](https://github.com/anton48/vk-turn-proxy-ios)

WireGuard Go is distributed under the MIT license. Go mobile and the Go runtime retain their BSD-style licenses. Dependencies retain their respective licenses. [WireGuard Go](https://git.zx2c4.com/wireguard-go/)

## Routing

Userspace packet forwarding uses the gVisor network stack under the Apache License 2.0 and its Go dependencies. Catalog parsing and policy matching reuse the desktop project's routing packages. [gVisor](https://gvisor.dev/)

## QR scanning

QR decoding uses ZXing core 3.5.3 under the Apache License 2.0. Copyright remains with the ZXing authors. [ZXing](https://github.com/zxing/zxing) · [License](https://github.com/zxing/zxing/blob/master/LICENSE)
