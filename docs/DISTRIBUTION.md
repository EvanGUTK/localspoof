# Local installation and distribution

Current 0.6 artifacts are the signed [Android APK](../release/localspoof-0.6.0.apk), its [signature sidecar](../release/localspoof-0.6.0.apk.idsig), and the [Windows installer](../release/LocalSpoof_0.6.0_x64-setup.exe). These are the final category/layout artifacts. Current source passes **115 Node tests (103 desktop + 12 tools)** and **170 Android tests (50 domain + 120 app)**, with both APK assemblies and debug/release lint passing. Original-signer packaging, exact bundled APK bytes, rebuilt-desktop installation and installed-hash/security checks pass. Final APK SHA-256 is `daf03a87ea5d2839d593d7722fb66e2938a3367621e08e189d7f18b31e5d6f9b`; [receipts](../release/artifacts.json) and [checksums](../release/SHA256SUMS.txt) are refreshed. The installer has no Authenticode publisher signature. Same-version per-user upgrade and normal installed-app opening passed; clean installation and uninstall remain untested.

Windows passes 28 actual planner checks and five production local-save IPC checks. Twelve earlier phone approval/fresh-dot checks remain attributed to the pre-fix build. After a subsequent release still showed a blank map, same-package diagnostics identified a zero-height map surface despite loaded features. The earlier viewport-fix production-UI candidate passed actual S24 map/gear/Settings/return navigation and keyboard height checks. Authorized captures temporarily disabled screen security and enabled debugging; the final signed production build restores those protections. Actual secure-APK gear labels passed UI Automator inspection, and the phone was left on Map. Runtime checks confirm `FLAG_SECURE`, no WebView debugging socket, and owned-forward cleanup; final secure screenshots are black as expected. Authorized captures qualify the production-UI candidate; no final-secure map pixel capture is claimed.

Use matching **0.6 components with protocol minor 5**. Earlier 0.5 native planner, signed-APK installation, approval, and fresh-dot evidence remains in [the historical handoff](HANDOFF_0_5_HISTORICAL.md) and [validation](VALIDATION.md). It does not qualify the new build. The regenerated 0.6 `release/artifacts.json` and `SHA256SUMS.txt` record artifact hashes and signing identity. See [current 0.6 validation](VALIDATION_0_6.md).

## Android

Use the signed release APK for ordinary local testing. Copy it to the phone and allow installation from the chosen local source when Android requests it. The copied debug APK is at `release/localspoof-0.6.0-debug.apk`; it uses a different development signer and cannot update an installed release APK with the same package name.

Open LocalSpoof, enable Developer options, and select LocalSpoof as the mock-location app. Grant precise location and notification permissions, enable device location, and arm playback from the visible phone UI. No root is required. The new interface provides a worldwide online map/search, Teleport/Route controls, bookmarks, and a settings gear. Desktop-to-Android online road planning remains the main workflow; complete standalone online road planning is not claimed.

Package identity: `dev.localspoof.android`, versionCode **6**, versionName **0.6.0**, minimum API 29, target API 36. The original certificate SHA-256 remains `862de591bf8bf3f8ae8e3109018fb5dfed0731d775c3acef0808c0cebdf46102`. Back up the release key and recoverable password outside the repository; updates require the same identity. [Toolchain and signing details](WINDOWS_TOOLCHAINS.md).

Before relying on playback, verify location submission, Pause/Resume/Stop, screen-off behavior, and guided airplane-mode transitions on the target device.

## Windows and USB control

Run `release/LocalSpoof_0.6.0_x64-setup.exe`. WebView2 is required; the standard Tauri installer may offer Microsoft runtime setup if it is missing. Local execution/build success does not establish installer upgrade or uninstall behavior.

The desktop bundles the exact trusted signed Android 0.6 companion. Open the phone icon, then **Scan USB** or **Find installed tools** to discover the SDK Platform Tools ADB path and bundled APK. Scan starts ADB automatically; users do not need PowerShell. Manual absolute tool/APK paths remain available. This machine's ADB is `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

Enable USB debugging, authorize this computer on the unlocked phone, and choose that phone on Windows. If necessary, click **Install companion APK** and wait for the visible success message. Open LocalSpoof on Android, grant location permission, and tap **Enable USB desktop control**. Connect the selected phone from Windows; compare the six-digit number on both screens and approve only when they match. No typing or clipboard is required. Expired or denied requests can be retried explicitly; an incompatible older APK must be updated.

Preview a journey before starting it. During active playback, ETA, Pause/Stop, and named next-stop jumps remain visible; live walk/car/plane speeds are under **Adjust live speeds**. Jumps keep required placement and radio steps. Final category/layout bundled-desktop APK upgrade passed four UI checks and installed matching signed bytes. Fresh approval of this final build, playback and radio qualification remain pending.

The bridge holds approval and activity in memory and removes only its owned ADB forward. Android/ADB/OS authorization and diagnostic state are outside this design. Wi-Fi control remains [roadmap only](WIRELESS_CONTROL_ROADMAP.md).

## Nearby places and map legend

Search for a city or zoom to level 9 or above, then choose Hotels, Food, Coffee or Shops; More includes Fuel, Health and Attractions. Category taps explicitly search the current map center. **Search this area** refreshes after panning; moving the map alone sends no new category request. Photon returns up to eight named OSM places within 10 km. Pins and result rows share numbers, and distances follow the selected units. Select a result to stage From, To or Add stop, then preview separately.

Categories require online maps and enabled search. Results remain in memory and clear with Stop/Clear/offline; saving remains a separate bookmark action. Public coverage is partial, and the finder does not provide hours, prices or availability. Online providers receive the area/category and may retain requests. The expandable map legend explains markers and route lines. Phone layout now uses a 48dp result peek/dismissible sheet and a map-key dialog. Actual S24 diagnostic-candidate checks verify eight public hotel pins, map visibility, sheet/key controls, named pin selection and the last result above navigation. Final secure packaging and rebuilt-desktop bundled installation pass; diagnostic screenshots are not final secure-APK pixel captures. [Contract and review](PLACES_FILTER_PLAN.md).

## Settings and saved items

The gear contains map/search providers, optional offline imports, mph/km/h/m/s and distance units, mode defaults, map auto-fit, follow-phone, and perspective controls. Preferences remain in memory for the current window. Changing units preserves physical speed; live speed changes update the phone without rebuilding a route.

Bookmarks retain only items explicitly saved by the user: named places and route drafts, optionally grouped into collections. They stay on the local computer or phone; there is no cloud storage, account, hosted server, automatic history, or automatic synchronization. JSON import/export is explicit and bounded. Imported routes preserve portable flight-speed aliases, stop durations, and boarding holds. USB radio capability is device/controller-specific; loading a saved draft cannot grant it.

Windows stores saved items in the fixed application-local data directory as `saved-items-v1.json`. An OS lock protects explicit mutations across app instances; read-only loading does not create a profile. Android stores its explicit items privately with backup disabled. Clear preview and Stop remove current activity but retain bookmarks until the user deletes them through Saved. Loading a route requires another preview and never restores pairing or starts playback.

## Rebuild and trust binding

[Windows toolchain instructions](WINDOWS_TOOLCHAINS.md) describe installed paths. Build the matching APK and desktop with the original release certificate fingerprint:

```powershell
$Certificate = Join-Path $env:LOCALAPPDATA 'LocalSpoof\signing\localspoof-release.cer'
$Fingerprint = (Get-FileHash -LiteralPath $Certificate -Algorithm SHA256).Hash
.\tools\build-desktop.ps1 -VerifiedSignedApk '.\release\localspoof-0.6.0.apk' -ExpectedSignerSha256 $Fingerprint
```

The builder checks the exact package/version, non-debuggable state, signer, and bounded hash while preventing source APK replacement. Runtime installation streams the same verified bytes through the selected physical USB device and loopback ADB server. A changed APK requires a matching desktop rebuild. Omitting trust-bound APK parameters disables companion installation.

Standard desktop output is under `desktop/src-tauri/target/release/bundle/nsis`; this session used an isolated build target to preserve the running older app. The copied `release/LocalSpoof_0.6.0_x64-setup.exe` is the distribution artifact.

## Geography and remaining gates

Online worldwide map/road previews do not require a GeoPack or phone. Flight selection now uses only 1,153 scheduled, open `large_airport` catalog entries, including manual overrides. This conservative major metro/international proxy excludes Jackson regional `MKL`; it does not verify current airline connections or international certification.

Optional offline basemaps use separately acquired PMTiles archives. Offline roads need GeoPack data; full-planet offline road routing remains unfinished. Preserve data attribution. Public online providers receive viewed areas, searches, and route endpoints and may retain requests. Local saving does not change provider policies.

See [build status](BUILD_STATUS.md), [code/security review](V06_CODE_SECURITY_REVIEW.md), and [current validation](VALIDATION_0_6.md) before making device-support or privacy claims. Mock locations remain detectable; Life360/fused-location compatibility is unverified. No app-store publication is required for local sideloading.
