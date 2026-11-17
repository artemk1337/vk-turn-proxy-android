# Third-party notices

VK TURN Proxy Android as a whole is distributed under the GNU General Public License version 3. See [LICENSE](LICENSE). Existing file-level copyright and SPDX notices apply to their respective files. Files marked `SPDX-License-Identifier: MIT` are also available under the [MIT license](https://opensource.org/license/mit).

## Shared transport

The Android client uses the Go transport, profile parser and routing packages from [VK TURN Proxy Desktop v1.15.2](https://github.com/artemk1337/vk-turn-proxy-desktop/tree/v1.15.2), as pinned in [backend/go.mod](backend/go.mod).

The shared transport originated in [anton48/vk-turn-proxy-ios at `5c289b58fd8e772512d81487c69a224659a8c6da`](https://github.com/anton48/vk-turn-proxy-ios/tree/5c289b58fd8e772512d81487c69a224659a8c6da), a GPL-3.0 derivative of [cacggghp/vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy). Original authors include anton48, cacggghp and other upstream contributors.

WireGuard Go is distributed under the MIT license. Go mobile and the Go runtime retain their BSD-style licenses. Dependencies retain their respective licenses. [WireGuard Go](https://git.zx2c4.com/wireguard-go/)

## Routing

Userspace packet forwarding uses the gVisor network stack under the Apache License 2.0 and its Go dependencies. Catalog parsing and policy matching reuse the desktop project's routing packages. [gVisor](https://gvisor.dev/)

The apps download two routing catalogs from Loyalsoldier. [GeoIP (geoip.dat)](https://github.com/Loyalsoldier/geoip/blob/master/LICENSE) is licensed under CC BY-SA 4.0. [GeoSite (geosite.dat)](https://github.com/Loyalsoldier/v2ray-rules-dat/blob/master/LICENSE) is distributed by v2ray-rules-dat under GPL-3.0.

## QR scanning

QR decoding uses ZXing core 3.5.3 under the Apache License 2.0. Copyright remains with the ZXing authors. [ZXing](https://github.com/zxing/zxing) · [License](https://github.com/zxing/zxing/blob/zxing-3.5.3/LICENSE)
