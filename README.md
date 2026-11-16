# VK TURN Proxy for Android

[Русский](README.ru.md)

A native Android VPN client with a Kotlin interface and a Go WireGuard/TURN backend shared with VK TURN Proxy Desktop.

<img src="screenshots/android-en-light.png" width="280" alt="Android application in English" />

## Requirements

- Android 8.0 or newer.
- A compatible WireGuard server and an HTTPS VK call invitation link.
- A version 1 connection profile, or a full-tunnel WireGuard `.conf` file.

## Use

Download the signed APK from GitHub Releases. When switching from a debug APK, uninstall the debug application and import your profiles again. [Releases](https://github.com/artemk1337/vk-turn-proxy-android/releases)

1. Install the APK and open VK TURN Proxy.
2. Import a connection JSON profile. For a WireGuard configuration, enter the VK call invitation link and import the `.conf` file.
3. Select the profile, tap Connect and approve Android's VPN permission request.
4. Disconnect in the application or its persistent notification.

Profiles are stored in private application storage with backups disabled. The foreground VPN service keeps the connection independent of the activity. The application routes its own transport traffic over the underlying network and captures IPv6 to prevent it from bypassing the IPv4 tunnel.

The initial Android implementation supports SRTP and DTLS transport, IPv4 full-tunnel profiles, custom DNS and WireGuard preshared keys. Import validation reports unsupported routing policies and transport modes. VK account sign-in and captcha interaction require further Android work.

The APK has been smoke-tested on an Android 15 emulator, including profile import/deletion, VPN permission denial, connection failure cleanup and connection cancellation. Live VPN traffic with a working server profile remains unverified.

## Build

Install Go 1.26, JDK 21 and the Android command-line tools. Set `ANDROID_HOME` to your SDK directory and `JAVA_HOME` to your JDK. Install the SDK packages and build the backend before running Gradle.

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018'
scripts/build-backend.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The generated APK is `app/build/outputs/apk/debug/app-debug.apk`. The backend AAR includes ARM64, ARMv7 and x86_64. The debug APK uses Android's development signing key. Production distribution requires your own release signing configuration.

Published APKs use the project's release signing key, stored separately from this repository. Build an unsigned release with `./gradlew assembleRelease`, then sign it with Android SDK `apksigner` and the same key to preserve update compatibility.

```sh
cd backend
go test -race ./...
go vet ./...
```

GitHub Actions builds the debug APK and uploads it as a workflow artifact.

## Related projects

- Shared transport and desktop application. [VK TURN Proxy Desktop](https://github.com/artemk1337/vk-turn-proxy-desktop)
- Original shared transport by anton48, cacggghp and other upstream contributors. [vk-turn-proxy-ios](https://github.com/anton48/vk-turn-proxy-ios)
- Related server implementation. [vk-turn-proxy](https://github.com/anton48/vk-turn-proxy)

GPL-3.0. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
