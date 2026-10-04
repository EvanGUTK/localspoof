# LocalSpoof 0.7 — implementation handoff for Sol 6.1

Updated 2026-10-04. Read [104WORK](104WORK.md), [validation](VALIDATION_0_7.md), and current source before continuing. This release implements the selected usability and gas Vacation scope; real flight/provider qualification remains pending. Primary workflow is Windows controlling Android over USB. No hosted backend, account, automatic activity history, or application logging was added. Explicit bookmarks remain the local persistence exception. Online service requests have separate provider privacy implications.

## Architecture and ownership

The Android session domain is authoritative for location acknowledgments, playback, phase, radio restoration ownership, timers, remaining edits and Vacation accounting. `Session.kt` and `Vacation.kt` contain those rules. The Android runtime submits framework GPS/network test-provider fixes. Neither map may fabricate provider motion or change the active journey when the user merely clicks or searches.

Protocol minor 6 is required by both components. See [wire contract](../protocol/README.md) and [schema](../protocol/schema.json). Pairing compares a number displayed on both screens and requires phone approval; no secret typing is needed. Authenticated snapshots expose approved choices/geometry, stage estimates, edit ownership, timed pause and Vacation ledger. Discovery/pairing responses must not disclose those fields.

Remaining edits use Begin → Prepare → Apply or Cancel. Draft previews may be prepared while moving; Apply acquires a fresh held point and replans from it. Android's local draft helper is not an extra wire command. Failures leave the approved journey intact. Completed legs/stops, consumed fuel and spending never rewind. Undo retains one previous set of choices in RAM and rebuilds from the current point. Original Pause/Stop intent and flight radio gates take precedence over late work. Editing cancels automatic timed resume; an originally paused journey stays paused until Resume. Disconnect cancels only a hold owned by that controller, while phone playback continues.

Both maps keep approved and candidate geometry separate. Flight rendering adaptively samples the same spherical path used for movement, with projection-error and resource bounds. Follow is visible on Android, drag disables it, Fit provides an overview, and missing/stale fixes never invent a moving dot. Android keeps its attached WebView composed behind Settings, resizes/replays on return, preserves camera state through rotation, and offers renderer recovery.

Vacation uses liters, meters and L/100 km internally. UI converts explicit US/Imperial/metric assumptions. Starting fuel must be entered as percentage, volume or estimated remaining range; selecting a vehicle never fills the tank. Fuel stops account for road detours, reserve and acknowledged dwell. Hotel stops use check-in, stay and checkout stages; Leave now begins checkout. Stop completion records one ledger event. Future fuel corrections validate feasibility before changing the current estimate; accumulated usage/spending remains unchanged.

Desktop `vacation.ts`, planner UI and bounded native network adapters perform online road/station planning and official US vehicle lookups. Automatic fuel stops use stable identifiers, replacing only unmodified future automatic stops; manually edited stations stay pinned. During an ongoing refill, automatic recalculation asks the user to finish that stop or edit manually. Android supports manual/custom/offline choices and connected desktop plans; standalone Android worldwide online road planning is not implemented.

## Honest data limits

- Gasoline/diesel Drive Vacation only. EV charging and sensor spoofing remain deferred.
- FuelEconomy.gov supplies US vehicle selections, consumption and reference prices. Tank capacity requires verified/manual input. Consumption is an editable constant estimate; no speed-dependent curve is invented.
- Public station lookup is bounded and can fail or lack coverage. Live pump prices, grade and availability are not verified. Manual prices override estimates. Currency labels do not imply conversion.
- Hotel rates, nights, fees and durations are manual. Missing values stay unknown; partial totals are labelled. Fuel-consumption value and refill purchases are separate amounts.
- Eligible airports are open scheduled-service `large_airport` entries; this excludes regional Jackson/MKL and does not certify current international service.
- GPS/network/Fused delivery after airplane-mode restoration and Life360 refresh/classification are still unqualified. Live means LocalSpoof submitted a fix successfully, not that another app accepted or uploaded it.

## Build and continuation

Version is 0.7.0; Android version code is 7. Windows builds use PowerShell, Java 17, SDK 36/build-tools 35.0.0, Node 24+, Windows MSVC Rust and Tauri. This host uses Java 21 separately for Android unit tests. See [Windows toolchains](WINDOWS_TOOLCHAINS.md), [Android build](ANDROID_BUILD.md), and [desktop build](DESKTOP_BUILD.md). Developer TypeScript is required for `npm run typecheck`; this host supplies its existing toolchain through `LOCALSPOOF_TYPESCRIPT_PATH`.

Run `npm run verify` and `npm run typecheck`, Android domain/app unit tests and debug/release lint, Windows shell/native Rust tests and shell Clippy. Build with `tools/build-android.ps1`; sign with the existing identity through `tools/sign-android.ps1`; package Windows with `tools/build-desktop.ps1 -VerifiedSignedApk ... -ExpectedSignerSha256 ...`. Never recreate the release key. The public signing fingerprint and artifact hashes are in validation. Keys and DPAPI password material remain outside Git; DPAPI is not a portable password backup.

Local release outputs are `release/localspoof-0.7.0.apk` and `release/LocalSpoof_0.7.0_x64-setup.exe`. The desktop bundles the exact trusted production APK. Windows installer is not Authenticode signed. Build caches, binaries, diagnostic candidates and captures are ignored. The source and handoffs belong in the private `EvanGUTK/localspoof` repository; a laptop must clone/pull and configure its own tools and phone authorization.

## Next controlled qualification

Use OpenMob: list devices, screenshot and visible UI tree before each action, then verify. Do not publish unrelated coordinates on the user's daily phone. No synthetic journey or radio transition was authorized during this session. USB connection recovered, but sustained stability and the underlying transient host/phone USB cause are not established.

1. Use the user's chosen route to qualify moving Follow, map return, high-speed remaining edits, airport diversion, Undo, stops and phone-owned playback across controller disconnection. Include physical screen-off, rotation, keyboard and renderer recovery.
2. Install the development-only [location observer](../tools/location-probe/README.md) when ready for the chosen flight. Compare fresh GPS/network/Google Fused callbacks, LocalSpoof submission age/phase, connectivity and the user's observed Life360 timestamp across real manual airplane-mode gates. Its latest three readings stay in RAM; it does not inject locations or access other apps. Remove it after qualification.
3. Keep delivery correctness separate from Life360 flight detection. Do not promise invisible travel, sensor spoofing or suppression of third-party history.
4. Qualify long-route automatic fuel discovery, provider outage/call-budget limits, manual station replacement, unknown hotel fees and all currency/unit combinations on actual devices. Source/unit coverage does not establish live global data completeness.

Production Android blocks screenshots and disables WebView debugging. Pixel QA used an isolated same-signer diagnostic copy with only screen-capture protection omitted. The exact secure production APK was restored afterward; do not ship or embed that diagnostic candidate.
