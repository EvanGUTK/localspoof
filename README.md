# LocalSpoof

Local Android location simulation with a sideloaded APK and a Windows desktop controller. No account, hosted server, cloud storage, ads, fees, automatic location history or application telemetry is implemented. Unsaved journeys stay in RAM. Preferences and explicitly bookmarked places/routes are stored locally; online map/search/routing providers receive the requests needed for those features and may log them.

The current release is **0.7.0**, with matching **protocol minor 6** components. Start with [104WORK](docs/104WORK.md), [the 0.7 handoff](docs/HANDOFF_0_7_SOL_6_1.md) and [current validation](docs/VALIDATION_0_7.md). Earlier 0.6 validation belongs to its original artifacts.

## Delivered in 0.7

- Persistent phone map when returning from Settings, usable Follow/Fit controls, retained camera state and renderer recovery. Phone and desktop draw spherical flight lines that match the simulated position.
- Approved and candidate routes remain separate. Ordinary map clicks retain active geometry. Change a remaining destination, stops, ground mode or eligible airport; Preview, Apply or Cancel from the current confirmed position.
- Add/reorder/remove future stops, one-edit Undo, phase/stage estimates, timed pauses, explicit next-stop jumps and phone-owned playback after desktop disconnection.
- Drive Vacation planning for gasoline/diesel: manual vehicle profile or US vehicle catalog, explicit starting percentage/volume/range, reserve, reachable station detours, stop durations and remaining-fuel correction.
- Estimated fuel use, refill cash and hotel costs. Hotel stops include check-in, stay and checkout; Leave now starts checkout. Missing prices/fees remain unknown. Currency labels are explicit and amounts are never silently converted.
- Local saved route choices include typed stops and Vacation assumptions. Saving never restores credentials, accumulated spending, completed-stop history or playback authority.
- Existing mph/km/h/m/s gauge, fixed or variable cruise, mapped-limit following, walking/car/plane speed changes, ETA and radio guidance remain available.

## Run Windows first

Open `release/LocalSpoof_0.7.0_x64-setup.exe` on the build machine, install and launch LocalSpoof. The matching signed companion is bundled. Worldwide map/search and desktop road previews work before connecting a phone; provider availability and coverage still apply.

Use the phone icon to discover/start ADB and scan USB. Enable USB debugging on Android, authorize the computer and install/open the bundled companion. Select LocalSpoof as the mock-location app in Developer options. Enable USB desktop control, compare the number on both screens and approve on the phone. Code typing and routine PowerShell commands are not required. Upgrade both components together.

Android owns foreground playback and requires visible phone arming. Stop terminates owned playback. The current production desktop connection is USB; wireless controller support remains a separate roadmap. OpenMob's development/testing connection is independent of the app's USB control policy.

## Estimates and coverage

Online maps are worldwide; optional PMTiles maps and GeoPacks provide explicitly scoped offline coverage. A global basemap does not imply global indexed offline road routing. Standalone Android online road/automatic station planning is still future work; the desktop performs those tasks today.

FuelEconomy.gov provides US vehicle selections and reference fuel data. Tank capacity requires verified/manual input. Consumption uses an editable constant MPG or L/100 km estimate; no speed-dependent curve, live pump price, fuel-grade availability or hotel rate is invented. Costs, detours, reserve and stops should be reviewed before starting. EV charging is deferred.

Major airport selection uses open scheduled-service `large_airport` catalog entries, excluding regional airports such as Jackson, Tennessee. This is a conservative catalog policy rather than a live airline schedule or airport certification.

Sensor spoofing remains deferred. GPS/network/Fused Location post-landing delivery and Life360 refresh/flight classification still require controlled real-device qualification. LocalSpoof Live status does not establish that another app received or accepted the same fix. Screenshots of the secure production phone app are deliberately blocked.

## Develop and continue on another computer

Source and handoffs are in the private [EvanGUTK/localspoof](https://github.com/EvanGUTK/localspoof) repository. Clone it and read [104WORK](docs/104WORK.md) and [the build handoff](docs/BUILD_HANDOFF_SOL_6_1.md). Git does not copy this machine's toolchains, signing identity, APKs/installers or local QA evidence. Keep the original Android signing identity backed up separately; do not generate a replacement for an existing installation.

Use Node 24+, Java 17, Android SDK 36/build-tools 35.0.0, the Gradle wrapper, Windows MSVC Rust and Tauri CLI. Windows build scripts are `tools/build-android.ps1`, `tools/sign-android.ps1` and `tools/build-desktop.ps1`; the signed companion's expected public certificate fingerprint is supplied to the desktop build. `npm run verify` runs Node/privacy/protocol/asset checks; `npm run typecheck` additionally requires the developer TypeScript toolchain. See the handoff for full Android and Rust checks.

Build caches, `.build/` QA receipts, `.tooling/`, generated release binaries and all signing material are excluded from Git. The Windows installer has no Authenticode publisher signature.
