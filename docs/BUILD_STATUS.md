# Build status — LocalSpoof 0.6.0

Current Workmode motion update uses matching protocol minor 5. Speed gauge, smooth variable cruise through 125 MPH, optional mapped limits, tracking, flight guidance and Sensors Off help are implemented. Combined checks pass: 135 Node, 57 domain/143 Android app, TypeScript, Android lint/assemblies, Windows 26 shell/22 bridge and Clippy. Actual S24 mock-speed, 45-second cadence, brief screen-off, live edits, Pause/Resume and desktop-disconnect continuity passed. See [motion handoff](MOTION_SPEED_FLIGHT_HANDOFF_SOL_6_1.md) for sensor qualifications and current artifact/installation status.

Earlier validation follows with its original scope and dates; this note supersedes earlier minor-4/current-build claims.


Nearby category filters and a compact map legend are delivered in both apps. Android uses a 48dp result peek, a dismissible list sheet, a map-key dialog and compact search/category/journey controls. Density tests preserve at least 60% map visibility at 384×628 across four states with zero/eight results. An actual S24 diagnostic candidate showed eight public Chicago hotel pins, approximately 68.8% uncovered map, usable sheet/key controls and draft-only named pin selection. Same-version per-user Windows installer upgrade and normal installed-app opening passed; clean installation/uninstall remain untested. Final matching APK/NSIS packaging and rebuilt-desktop bundled installation passed; installed bytes match the current receipt and production screen/debug protections were verified. Fresh pairing approval, playback and radio qualification remain pending.

Earlier viewport-fix Android/Windows 0.6 artifacts are built. Both require protocol minor 4. The desktop now has a dark map workspace and Teleport/Route planning; the Android UI has worldwide online map/search and a bottom journey panel. Live speed, ETA, custom stops, named next-stop jumps, and explicitly saved local items are implemented. Current 0.6 passes 28 actual Windows/WebView2 planner checks and five production local-save checks. Signed-companion upgrade and opening passed on one authorized physical USB phone; twelve real-phone approval/fresh-dot/heartbeat checks passed before the renderer-fix upgrade. An earlier renderer correction installed through the desktop passed four checks, but the user still saw a blank map. Same-package diagnostics reproduced a zero-height map viewport. The final production-UI candidate passed actual S24 map/gear/Settings/return navigation and keyboard shrink/restoration checks. Diagnostic captures temporarily disabled secure-screen protection and enabled debugging; the final production build restores those protections. Final APK/NSIS packaging, bundled installation (four checks), installed-hash matching, actual secure-window/no-debug-socket checks, debug-forward cleanup, and normal final desktop opening passed. Fresh phone approval, playback, and radio qualification remain pending.

| Artifact | Location | Status |
|---|---|---|
| Windows x64 setup | `release/LocalSpoof_0.6.0_x64-setup.exe` | Built; same-version per-user upgrade and normal installed-app opening pass; Authenticode NotSigned; clean install/uninstall untested |
| Signed release APK | `release/localspoof-0.6.0.apk` | Final secure category/layout APK built and installed; installed hash matches receipt; versionCode 6/name 0.6.0; original signer |
| Release signature sidecar | `release/localspoof-0.6.0.apk.idsig` | Built with release signing |
| Debug APK | `release/localspoof-0.6.0-debug.apk` | Debug assembly passes; development signer cannot update release installation |
| Public integrity receipt | `release/artifacts.json`, `release/SHA256SUMS.txt` | Regenerated for 0.6; public artifact hashes and signing identity recorded |

| Capability | Implemented behavior | Qualification / limitation |
|---|---|---|
| Nearby category finder / legend | Hotels, Food, Coffee, Shops and More; eight named results within 10 km at zoom ≥9; explicit area refresh; numbered pins/list; RAM only; selection stages a draft | Desktop 15 browser + 15 actual WebView2 checks pass; Actual S24 candidate sheet/key/pin/map-density checks pass; final secure packaging and bundled installation pass; partial OSM coverage, no hours/prices |
| Desktop map/search | Dark MapLibre/OpenFreeMap map, explicit Photon/Nominatim/Off search, perspective option | 33 browser checks and 28 actual Windows planner checks pass; provider coverage/logging apply |
| Teleport and road planning | Teleport destination; WALK/DRIVE road routes and ordered stops with individual dwells | Desktop online road planning remains the primary workflow; no phone needed for preview |
| Android map interface | Worldwide online map/search, bottom Teleport/Route panel, bookmark and settings UI | Actual S24 diagnostic candidate map/gear/Settings/return and keyboard height checks pass; final secure installation/security checks pass; secure captures blocked as expected; standalone online roads not claimed |
| Phone marker | Fresh DEVICE before arming; follows successful SIMULATION submissions; authenticated Snapshot | Fresh DEVICE acquisition passed before renderer-fix upgrade; actual mock playback pending; latest RAM sample only |
| Live controls | Phone-owned ETA, separate walk/car/plane speeds, named next-stop jumps | Mutations require approval/revision; placement and radio gates retained; real-device behavior pending |
| Flight planning | 1,153 scheduled, open `large_airport` entries; automatic/manual choices reject regional MKL | Conservative major-airport proxy, not proof of live flights or international certification; actual radio transitions pending |
| USB approval | Six-digit comparison and explicit phone consent; native-only credentials; idempotent Enable | Matching signed companion upgraded/opened over physical USB; 12 real 0.6 consent/fresh-dot checks passed before renderer-fix upgrade; fresh approval of final secure category/layout build pending |
| Local saved items | Explicit named places/route drafts, collections, import/export, rename/delete/clear | Five production local-save IPC checks pass; fixed local storage, bounded validation, atomic writes and cross-process mutation lock; no cloud/server or automatic history |
| Offline basemap | Indexed local PMTiles reads; no offline map/search/route HTTP | Planet archive supplied separately; offline vector labels omitted |
| Offline road graph | GeoPack routing with profile, one-way and speed constraints | Full-planet offline road routing remains unfinished |
| Privacy | Unsaved activity and authority in RAM; explicitly saved items persist locally | Provider and OS/SDK state remain outside application history guarantees; no forensic qualification claim |
| Future Wi-Fi | Roadmap only | Current controller requires physical USB |

Current source/build verification passes:

- **115 Node tests**, including **103 desktop + 12 tool tests**; strict TypeScript **6.0.3**; source privacy and protocol checks.
- Windows native core **20**, Linux core **28**, desktop shell **22**, and Clippy.
- Android **170 tests (50 domain + 120 app)**, zero failures/errors/skips; debug/release assembly and lint pass. A Windows DEX output lock persisted across two retries; isolated task build directories resolved it. [Final report](../.build/places/android-density-isolated-final.log).
- Final original-certificate APK/NSIS packaging, exact bundled companion bytes and regenerated hash/signing receipts pass. Rebuilt desktop bundled installation passes four checks; production installed hash, secure window, absent debug socket and owned-forward cleanup pass.
- Nearby categories: **15 browser and 15 actual Windows/WebView2 checks**; [contract and review](PLACES_FILTER_PLAN.md).
- **33 isolated browser checks at six viewports**: 1440×900, 1280×840, 1024×640, 900×640, 700×700, and 390×844. Four checks exercise synthetic active-control layouts; they do not prove phone authentication or playback.

Earlier 0.5 passed 28 native planner checks, production signed-APK installation, and 12 scoped real-phone checks covering approval, fresh DEVICE location, captured/edited origin, ten seconds of heartbeats, and disconnect clearing. Those observations do not qualify 0.6 hardware behavior. Historical 0.4/0.5 evidence remains in [VALIDATION.md](VALIDATION.md) and [HANDOFF_0_5_HISTORICAL.md](HANDOFF_0_5_HISTORICAL.md).

Earlier viewport-fix 0.6 hardware checks confirmed one authorized physical USB phone and bundled-desktop verified-byte upgrade into primary user 0. Installed hash matches the public receipt; actual secure-window and absent-debug-socket checks passed, and the owned debugging forward was removed. Actual secure-APK gear navigation labels passed UI Automator inspection; the phone was left on Map. The final desktop opened normally without debug environment; one normal window remains visible. No fresh approval, mock playback, or radio transition occurred after the final update. See [current 0.6 validation](VALIDATION_0_6.md).

Pending 0.6 gates include fresh approval after the final secure upgrade, mock playback/consumer compatibility, screen-off and OEM behavior, radio phases/restoration, sustained USB stability, and clean installer installation/uninstall. Mock locations remain detectable; Life360 compatibility is unverified. Nothing is committed or published.

See [code/security review](V06_CODE_SECURITY_REVIEW.md), [distribution](DISTRIBUTION.md), [current handoff](BUILD_HANDOFF_SOL_6_1.md), [worldwide architecture](WORLDWIDE_MAP_PLAN.md), [toolchains](WINDOWS_TOOLCHAINS.md), and [wireless roadmap](WIRELESS_CONTROL_ROADMAP.md).
