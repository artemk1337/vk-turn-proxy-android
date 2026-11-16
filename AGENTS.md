# Architecture

- `app` is the native Kotlin Android application. `VpnService` owns the Android TUN and foreground notification. Profile editing and application preferences have separate storage. Keep profile keys and account credentials out of presentation preferences and disable backup.
- `backend` exposes the gomobile API in package `mobile`. It imports desktop connection links, base64, JSON backups and WireGuard configurations, validates editable settings and owns the detached TUN descriptor, WireGuard device and shared TURN transport. Preserve unknown profile metadata during edits.
- `ProfileEditor` opens before parsing and owns separate Link/JSON and WireGuard source forms. `ProfileInput` retains each mode's draft. Store original sources and explicit UI overrides as flat private `androidEditor` metadata. Keep inherited values distinguishable from explicit overrides. Never recursively embed editor metadata in its own source.
- `backend/routing-runtime` owns userspace direct/tunnel forwarding and DNS policy enforcement. Follow its local `AGENTS.md` for routing invariants.
- Account screens own isolated VK website login, private account storage, health checks and interactive captcha. Account data reaches a connection through an immutable session snapshot.
- `AppUpdates` checks stable GitHub releases and downloads bounded APKs into private storage. Verify SHA-256, package identity, increasing version and the installed signing certificate before granting the system installer read access through `UpdateApkProvider`. Installation always requires Android confirmation.
- `docs/demo` owns a static browser reproduction of the native screens with in-memory simulated data. Its build extracts English/Russian strings from Android resources. Keep its controls and palette aligned with the native UI. `.github/workflows/demo-pages.yml` publishes GitHub Pages independently of APK builds.
- `scripts/build-backend.sh` builds the AAR for ARM64, ARMv7 and x86_64. Gradle consumes the generated `app/libs/backend.aar`.
- The pinned desktop Go module owns shared transport behavior and its allocation recovery invariants. Preserve upstream attribution.

Exclude the application's own UID from the VPN so TURN, authentication and recovery use the underlying network. Capture IPv6 and drop it until IPv6 tunneling is supported. Never log profile keys, credentials or imported JSON. Stop must cancel a concurrent connection attempt and release resources. A detached TUN descriptor belongs to the backend even when start fails.

Keep desktop import compatibility, including literal `+` in base64 connection URLs and explicit transport flag semantics. Use the shared desktop settings contract for bounded connection defaults. Routing is an immutable per-connection policy. Record domain-derived IPv4 decisions before releasing DNS answers, including HTTPS/SVCB hints. Keep configured VPN DNS reachable through the tunnel. Retain the previous complete catalog pair when updates fail.

WireGuard import normalizes numeric MTU values outside the tunnel bounds to the shared default before parsing. Preserve duplicate/directive validation. Apply an explicit peer override before endpoint validation. Replacing WireGuard source must retain unknown profile extensions while clearing stale known fields. Guard asynchronous editor callbacks by their owning form generation and retain private drafts across activity destruction.

Run `go test ./...` in `backend`, build the AAR, then run `./gradlew testDebugUnitTest lintDebug assembleDebug`. Keep UI strings in English and Russian resources. Verify foreground lifecycle, VPN consent and cancellation on Android before release.

## Browser demo and README GIFs

- Build with `python3 docs/demo/build.py /tmp/android-demo` and serve with `python3 -m http.server --directory /tmp/android-demo`. Match native screens and mark simulated connections. Keep demo data in memory.
- Record `?lang=ru` and `?lang=en` at 420×840 with Playwright. Capture menu/dialog changes as PNG frames at 6 fps into `/tmp`. Assemble GIFs with Pillow using 128 colors, 167 ms per frame and `loop=0`.
- Save GIFs as `screenshots/interface-demo-{ru,en}.gif`, link them to Pages in both READMEs and include them in release assets and `SHA256SUMS`.
- `.github/workflows/demo-pages.yml` publishes Pages from `main`. Set Pages source to GitHub Actions. After a `[skip ci]` release commit, run `gh workflow run demo-pages.yml --ref main` and wait for successful deployment.
