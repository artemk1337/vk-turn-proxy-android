# Architecture

- `app` is the native Kotlin Android application. `VpnService` owns the Android TUN and foreground notification. Keep profiles in private application storage and disable backup.
- `backend` exposes the gomobile API in package `mobile`. It validates profiles, owns the detached TUN descriptor, WireGuard device and shared TURN transport.
- `scripts/build-backend.sh` builds the AAR for ARM64, ARMv7 and x86_64. Gradle consumes the generated `app/libs/backend.aar`.
- The pinned desktop Go module owns shared transport behavior and its allocation recovery invariants. Preserve upstream attribution.

Exclude the application's own UID from the VPN so TURN, authentication and recovery use the underlying network. Capture IPv6 and drop it until IPv6 tunneling is supported. Never log profile keys, credentials or imported JSON. Stop must cancel a concurrent connection attempt and release resources. A detached TUN descriptor belongs to the backend even when start fails.

Run `go test ./...` in `backend`, build the AAR, then run `./gradlew testDebugUnitTest lintDebug assembleDebug`. Keep UI strings in English and Russian resources. Verify foreground lifecycle, VPN consent and cancellation on Android before release.

