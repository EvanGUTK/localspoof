# LocalSpoof implementation and build report — 2026-10-02

The user authorized implementation from the architecture using ECC, then authorized Windows toolchain installation and artifact creation after a single-turn read-only inspection. Those build tasks are complete. The signed APK and Windows x64 installer are available under `release`; hardware qualification remains pending.

## Delivered

Android Kotlin/Compose and a phone-owned domain implement instant hold, walk/drive graph or explicitly custom routes, staged airport journeys, prepare/commit replacement, visible foreground service, framework mock injection, local resources and manual radio guidance. Windows Tauri/Rust/canvas implements scoped USB forwarding, ephemeral phone-approved pairing, bounded authenticated control, optional phase/ownership-bound airplane operations, and installation of one certificate/package/hash-verified APK. Offline OSM/airport conversion tools, synthetic graph, approximate airport reference, protocol fixtures, genuine Gradle wrapper and locked native builds are included.

[ADR 0004](../docs/adr/0004-initial-local-geopack-adapters.md) records the initial canvas/GeoPack adapters. Real regional road data must be imported. No global street coverage, automatic APK-only airplane toggle, Life360 compatibility or whole-app absence of OS traces is claimed.

## Measured evidence

All 43 Kotlin JVM tests and both Android lint/assembly variants pass. Domain JaCoCo lines are 95.46%, branches 67.75%; coverage scope is one module. Node 29 tests, strict TypeScript and source/privacy/protocol checks pass. Windows Rust core has 8 passing tests; prior Linux run has 13, including five Unix-only synthetic process/install fixtures. Tauri Windows compilation/Clippy, locked release build and NSIS packaging pass.

Release APK signing/alignment, expected package/version/non-debuggable state, certificate identity and restricted private ACLs pass. Desktop build rejects a wrong signer and binds its release to the verified APK bytes. The installer is Authenticode unsigned. Public artifact receipts record SHA-256 digests and sizes.

Actual Windows Chrome/Chromium preview inspection passed 25 checks at four viewport sizes and repaired focus, target size, marker labeling and short-window layout. Actual release Tauri/WebView2 launch passed 11 smoke checks, including bundled UI, native bridge and harmless malformed-path rejection. No real ADB/phone operation was performed. [VALIDATION.md](../docs/VALIDATION.md) records command/log evidence and practical limits.

## Review and remaining work

[ECC code review](../docs/CODE_REVIEW.md), [security review](../docs/SECURITY_REVIEW.md), and [source evaluator feedback](feedback-001.md) are retained. Actual build failures were repaired before delivery; the original evaluator describes its earlier source-only assessment, with current native build evidence recorded separately.

**Delivery: signed Android APK and Windows installer built for local testing.** Physical mock consumers, USB pairing/install/selection, background/provider/Stop behavior, flight radios/restoration, installer install/upgrade/uninstall, third-party compatibility and runtime filesystem/log/backup/network audits remain untested. Follow [current Sol handoff](../docs/BUILD_HANDOFF_SOL_6_1.md) and [build status](../docs/BUILD_STATUS.md).

Toolchains were installed outside the workspace under the latest explicit authorization. Private signing material is outside the repository. No application was installed on a phone, no commits/remote uploads/publication occurred, and host safety hooks were not installed. Scoped operations and independent review were used; no commit approval is pending because no commit is proposed.

## Worldwide Windows 0.2.0 delivery

User decision: online primary, both map options, worldwide coverage, Windows first. Online world map and independent walk/drive/airport previews are implemented; optional planet PMTiles basemap import works. Full-planet offline routing and standalone Android world maps remain unfinished and are clearly disclosed.

Final gates passed: Node 47, Kotlin 54 with both lint/APK variants, strict TypeScript, privacy/protocol/assets, native shell 12 and Windows Clippy, release signing/held-file trust verification including negative signer, and NSIS packaging. Actual final Windows Tauri/WebView2 passed 22 checks, including public test-coordinate routes in Chicago/Berlin/Tokyo, disconnected-continent failure, a real airport journey preview, search, offline archive and cleanup, no app console/JS errors and no direct remote webview origins. Expected native HTTPS provider requests still occur and the routing provider logs submitted endpoints. No phone/radio/installer lifecycle qualification is claimed.

Independent ECC review found no new blocker after the final source fixes. GO for the requested local Windows online testing. Preserve the remaining scope/qualification limits in the Sol 6.1 handoff; this is not universal Android or planet-offline release qualification. New public artifact identity is in release/artifacts.json and SHA256SUMS.txt. No commits/publication/phone installation occurred.
