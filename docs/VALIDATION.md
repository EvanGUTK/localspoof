# Historical validation record — LocalSpoof 0.5.0, 2026-10-03

Current builds are 0.6. See [current validation](VALIDATION_0_6.md) and [current artifact receipt](../release/artifacts.json). The observations below belong to historical builds.

## Historical 0.5 package

The matching minor-3 Android/Windows source is frozen and built. Historical 0.5 artifact hashes are recorded below; the live release receipt now describes 0.6. Fresh actual native planner and signed-phone installation results follow; 12 real approval/fresh-device-dot checks now pass and remain separate from mock playback qualification.

| Check | Result | Evidence / limits |
|---|---|---|
| Node verification | PASS, 97 | UI/model/planner/address/location/keepalive/privacy/geography/converters and shared protocol checks |
| Strict TypeScript | PASS, 6.0.3 | Final source including ownership fixes |
| Source privacy/protocol | PASS | Minor-3 capability/location/segment fixtures and source boundaries; not a runtime forensic guarantee |
| Windows native / Linux core | PASS, 19 / 27 | Existing approval, USB, install and bounds tests with Clippy; synthetic transports do not prove device consent |
| Windows shell | PASS, 13 + Clippy | Fixed-provider bounds, airport endpoint validation and tool discovery |
| Android tests | PASS, 126 | 42 domain/84 app, zero failures/errors/skips; final `android-planner-final.log` BUILD SUCCESSFUL in 49 s |
| Live metadata tests | PASS | Non-mock validation, latest RAM owner, stale/future/accuracy rejection, whole-injection publication, approved Snapshot only, changed-point retry/current age and original 6001 ms lease expiry |
| Flight/layout tests | PASS | All ground/transit combinations and ACK/radio/pause/Stop semantics; engine great-circle air samples; measured 320x480/640x360 font 2.5 explicit choices/Stop without clipped labels |
| Android builds/lint | PASS | Final debug/release assembly and both lint variants; pinned-version warnings remain |
| Release signing/trust | PASS | Same original release certificate `862de591bf8bf3f8ae8e3109018fb5dfed0731d775c3acef0808c0cebdf46102`; wrong signer negative gate passes; exact versionCode 5/name 0.5.0 non-debuggable companion bound to desktop |
| Windows NSIS / receipt | PASS | `release/LocalSpoof_0.5.0_x64-setup.exe`, signed/debug APK and release `.idsig`; hashes/sizes regenerated; Authenticode NotSigned |
| Fresh actual 0.5 native/world/planner QA | PASS, 28 | Actual release/WebView2 world map, Drive/Walk, Berlin/Tokyo roads and disjoint-continent error; >3,200 airport selectors; TIMED two-road/one-air preview, mixed road/instant and both-instant/JUMP typed rendering; unpaired dot absent/Start disabled; search, offline PMTiles/no remote requests, Clear, no overflow/JS exceptions/console diagnostics. Async map source update was awaited in QA; no production fix. Evidence `.build/global/native-qa/result.json`, `native-planner-qa.log`. |
| Actual 0.5 physical USB scan/install | PASS | One authorized physical USB phone, zero unauthorized; production native verified-byte signed APK installation into primary user 0. Companion opened; user enabled it. |
| Actual 0.5 approval/fresh DEVICE dot | PASS, 12 | Physical USB, matching comparison without input, Start disabled before consent, explicit user approval, native-only credential/comparison clearing, authenticated fresh DEVICE fix and marker, Use phone origin capture with subsequent edited origin preserved, ten seconds of approved heartbeat, pairing does not start a journey, explicit Disconnect clears device/comparison/location availability. Evidence `.build/global/phone-planner-approval-result.json`, `phone-planner-dot-qa.log`. No coordinates/code/credential logged or screenshots; no mock journey/radio operation. |
| Actual 0.5 simulation/background/consumer/radio | PENDING | Fresh DEVICE marker is not mock-provider playback, consumer compatibility, screen-off/OEM or radio/restoration qualification. |

Signed 0.5 APK SHA-256: `49136b2855db6fd9867aaf4b1f0e38eb73ac993b8d7e4a5028d2ce06817033bd`; installer SHA-256: `a391a45ed3d65ae85912d96fd700b6adf9c0845432dcc742040ee1972dc2f3e0`. Independent source review passes after correcting nonflight option keys, stale/weak metadata and segment validation, missing-token public dispatch, large-font labels, Snapshot history in deduplication and old-route keepalive ownership. A latest-only point is not an independently confirmed third-party app position. Final phone checks logged only public pass/fail facts; Clear cleanup completed. An initial QA-script async-wrapper syntax issue was corrected without a production change or bypassing the real phone approval. After Disconnect/Clear, root closed only its owned CDP QA app through an exact-path helper and opened the ordinary release 0.5 main window for the user. No WebView debugging setting was persisted.

## Historical 0.4 approval package and actual phone checks

Built 0.4 passed 81 Node tests / strict TS 6.0.3, Windows core 19 / Linux 27 / shell 13 with Clippy, Android 107 (37 domain/70 app), debug/release lint/assembly, original signing/trust negative gate and NSIS. Signed 0.4 installed on the S24 using production native verified-byte streaming with definitive Success into user 0; the app was opened and the user enabled desktop control.

A subsequent ADB disappearance occurred despite one present Windows interface. Official SDK 37.0.1 reported LIBADBUSB. A controlled process-only legacy restart was performed only after checking exact SDK executable, zero devices/forwards and one loopback 5037 listener. It first exposed an offline transport. Following user reconnection, actual Windows 0.4 passed **15 settings/live-USB checks** and **eight real-phone approval checks**: matching comparison number, explicit user approval, native-only credential consumption, cleared comparison, unpaired Start disabled, ten seconds of approved heartbeat and explicit disconnect cleanup. No actual coordinates were logged, mock journey started or radio changed. This does not establish sustained reliability or prove the backend alone caused recovery. Future Windows client-started daemon cold starts use process-only `ADB_USB_LEGACY=1`; ordinary scans never restart a shared daemon.

These are 0.4 hardware facts, not 0.5 location/playback qualification. Future Wi-Fi support is [roadmap only](WIRELESS_CONTROL_ROADMAP.md); current USB-only transport policy is unchanged.

## Historical 0.3 USB and mobile work

The user authorized desktop-owned ADB setup, speed/session settings, Android UI overhaul and investigation of repeated disconnects. Android 0.3 build/signing and Windows NSIS packaging passed. The desktop local-I/O keepalive fix and real-phone idle handshake passed; the record below separates measured checks from remaining physical qualification.

| Check | Observed result | Scope |
|---|---|---|
| Desktop Node tests | PASS, 74 | UI/model/settings/flow/privacy/geography/converters; npm verify and strict TS6.0.3 pass |
| Windows native core | PASS, 17 | Physical USB fallback, public Hello failures, binary smart-socket installer and bounded status/deadline fixtures; Clippy pass |
| Linux native core | PASS, 24 | Includes platform-specific process fixtures; Clippy pass |
| Windows shell | PASS, 13 | Fixed-provider networking plus known-tool discovery; Clippy pass |
| Real S24 Ultra installation | PASS, signed 0.2 baseline | Production Windows smart-socket installer returned definitive Success for verified bytes into primary user0 |
| Real S24 public Hello | PASS, signed 0.2 baseline | Compatible phone-control service verified; no pairing secret read, simulation started or radio changed |
| SDK cold startup | PASS | Isolated task daemon on loopback port15038 with USB/emulator acquisition disabled; official SDK client started it, then only owned PID closed |
| USB continuity sample | PASS, six samples | ~15s, each one ready authorized phone and one present OK ADB interface; finite observation does not establish sustained USB reliability |
| Android unit/domain tests | PASS, 90 | 37 domain/53 app, zero failures/errors/skips; test-only Java 21 worker, production/Gradle Java 17 |
| Android rendered layouts | PASS, 48 scene configurations plus additional cases | API 35: Connect/Journey/Settings at 320x480,360x800,640x360,840x640dp x fonts 1/1.5/2/2.5. API 36 compact Connect, API 29 Journey,1280x800/font2 capped840dp. Measured root/content/action/native-input bounds, reachability, actual text overflow/fonts/privacy/callbacks. Optional PNG export unsupported; no pixel screenshot review claim. |
| Android real-activity privacy | PASS, API 29/35/36 | Secure flag, autofill/capture opt-out, saved Bundle clearing; inert lifecycle with no mock/service/radio/pair action. API 29 uses manager opt-out; per-view setters guarded API 30+. |
| Android both builds/lint | PASS | tools/build-android.ps1 -Variant both,57 s, 184 tasks; both lint 0 errors, 6 pinned dependency-version warnings. VersionCode 3/name 0.3.0, API 29/36. |
| Android release signing | PASS | Original release certificate, alignment/package/version/non-debuggable/signature verified; restricted signing ACL receipt recorded after final packaging |
| Real S24 0.3 companion install | PASS | Production Windows native verified-byte installer definitive Success for signed 0.3 APK, user0; updated MainActivity opened. No simulation or radio action. |
| Real S24 0.3 idle Hello | PASS | Same physical phone socket answered public Hello before and after eight seconds idle; no secret read, pairing, mock journey or radio transition. Task-owned forward removed afterward. |
| Windows 0.3 native worldwide integration | PASS, 22 | Final release executable/WebView2 with an isolated test-only profile; Chicago/Berlin/Tokyo road routes, airport flight preview, search, offline indexed PMTiles, Clear/no overflow/no JS diagnostics; no paired journey or radios |
| Windows 0.3 settings and live USB | PASS, 15 | Actual final native UI: units/conversion/no drift/defaults/reset, SDK auto-discovery, bundled companion path, authorized S24 selection, installation availability, public Hello before pairing, unpaired Start disabled, disconnect cleanup, no overflow/JS errors. No pairing secret entered, APK reinstallation, simulation or radio action. Windows extended local-drive resource prefix normalized; six Node regressions reject UNC/device variants and preserve manual paths. |
| Later physical USB observation | TEMPORARY DROP; RECOVERED | ADB listed 0 devices in six later samples while Windows reported one present OK ADB interface. Official SDK 37.0.1 daemon on local 5037 confirmed by server-status; no shared daemon was killed. After user reported reconnection, physical USB verification and all 15 final native UI checks passed. The cause of the transport disappearance and sustained stability remain unqualified; this is independent of the tested phone idle-socket fix. |
| Windows 0.3 trusted package | PASS | Wrong signer rejected; source/staged handles held, stage rehash, trusted digest compiled, exact signed 0.3 companion in NSIS bundle; final assets rebuilt and all artifact hash/size/trust-digest/resource equality independently rechecked |

Task-owned Windows QA processes were closed. WebView2 debugging arguments and unique test profile overrides were process-only and restored after launch. User-installed app processes and the shared ADB daemon were preserved.

## Historical 0.2.0 worldwide baseline

Current worldwide Windows work used public, fixed test coordinates and modest live provider requests. No phone was paired/installed, no personal location or third-party account was used, and no radio was changed. The original 0.1.0 evidence remains below as historical baseline.

| Current check | Result | Evidence and practical limits |
|---|---|---|
| Node/UI/converter tests | PASS, 47 | `npm run verify`; model 14, UI flows 8, privacy 2, geography/map 14, converters 9. Includes exact native lat/lon-only airport coordinates, source/radius/profile bounds, speed/one-way/tie parity, cancellation and archive races. |
| Strict TypeScript | PASS | TypeScript 6.0.3, latest source; compiler is development-only. |
| Source privacy / protocol / assets | PASS | Scanner now includes vendored `.mjs`; SDK diagnostics including timer calls disabled; protocol fixtures and local module/asset build pass. Source scan does not prove OS forensic absence. |
| Android JVM | PASS, 54 | Domain 37/app 17; zero errors/failures/skips. Online geometry and 5 km airport-only access regressions included. Ordinary/user endpoints remain 250 m. |
| Domain coverage | MEASURED | Lines 602/627 = 96.01%; instructions 6695/7599 = 88.10%; branches 715/1013 = 70.58%. Domain only, not Android services or whole-app coverage. |
| Android debug/release build and lint | PASS | Fresh `tools/build-android.ps1 -Variant both`; 182 tasks, 27s; versionCode 2/name 0.2.0, API 29/36. Unsigned assembly then original release-key signing. Physical lifecycle/injection remains untested. |
| Windows network shell tests / Clippy | PASS, 12 | Locked Cargo tests exercise provider URL allowlists, decoder/body/snap/airport/rate/cache boundaries. Windows all-target Clippy with warnings denied passes. Native core prior 8 Windows/13 Linux tests unchanged. |
| Signing / trust / NSIS | PASS | APK signature/alignment/package/version/non-debuggable checks, wrong-fingerprint rejection, held-file hash and compiled trusted digest verified. Current `release/artifacts.json` / `SHA256SUMS.txt` record public identity. Windows installer publisher unsigned. Install/upgrade/uninstall not exercised. |
| Browser worldwide integration | PASS, 16 | Real Chromium: street tiles, driving 44-point Chicago geometry, walk, Tokyo search, unpaired Start disabled, offline controls and actual small PMTiles reader, Clear, no overflow/exceptions/app console calls. Direct-provider development browser path, not production memory-only HTTP qualification. |
| Actual Windows release integration | PASS, 22 | Native Tauri/WebView2: world/street rendering; Chicago walk/drive and Berlin/Tokyo driving; disconnected Tokyo-Chicago error; real Chicago Midway → LaGuardia flight preview with both surface legs; search, offline PMTiles import/no remote requests, Clear, no overflow/JS exceptions/console diagnostics. Native webview requests stayed on local app/IPC origins; native Rust made expected provider HTTPS calls. No phone/radio control. |

Ignored evidence is in `.build/global`: final build/test/Clippy/typecheck/signature logs; browser/native QA JSON, drive and flight screenshots; tiny raster PMTiles fixture. That fixture tests indexed reads and decoding and is not geographic coverage or distributed planet data. QA logs use public synthetic/test coordinates, not user activity.

SDK code/resources were bundled locally with pinned versions, full license texts and hash records. OpenFreeMap TileJSON, glyph and sprite response shapes were verified against the fixed native resource policy. No provider HTTP headers reach the webview. Full planet offline graph/Android world-map support, global multi-instance public-service quota compliance, sustained Android screen-off/USB/radio behavior, installer lifecycle and OS/webview filesystem/log/crash/backup canary audits remain unqualified.

QA used process-only WebView2 remote-debugging arguments. No debugging environment was persisted; task-owned app/browser/preview processes were closed afterward. No commits, publication or phone installation occurred.

## Historical 0.1.0 baseline

The authorized Windows setup/build turn resolved the earlier environment blockers. Tests used synthetic data. No phone installation, live personal location, third-party account, USB control session, or real radio change was performed.

| Check | Result | Evidence and limits |
|---|---|---|
| Windows toolchains | PASS | JDK 17, Gradle 8.13, Android platform 36/Build Tools 35.0.0/Platform Tools, Studio, Rust MSVC and Tauri CLI installed; user environment configured. [Versions/paths](WINDOWS_TOOLCHAINS.md). Official download hashes and wrapper integrity verified. |
| `npm run verify` | PASS, 29 tests | 14 model, 2 UI privacy, 4 mock-DOM, 6 OSM converter and 3 airport converter tests; privacy source scan, six protocol assertions and UI asset build. Includes modal focus/import accessibility and stale response/Stop regressions. |
| `npm run typecheck` | PASS | TypeScript 6.0.3 via local developer-only `.tooling/typescript/node_modules/typescript/lib/typescript.js`; latest UI source checked. No compiler bundled into runtime. |
| Native Rust core, Linux | PASS, 13 tests | Prior source-turn evidence: two bridge, six boundary and five Unix synthetic APK/process tests. Verified-byte source replacement, hash/bounds, exact install success and child timeout/output caps. No actual ADB install. |
| Native Rust core, Windows | PASS, 8 tests | Windows Cargo `test --manifest-path desktop/native/Cargo.toml --locked`, Rust 1.99.0 MSVC. The five Unix process fixtures are platform-gated and did not run on Windows. |
| Tauri shell compile/Clippy | PASS | Windows `cargo check --manifest-path desktop/src-tauri/Cargo.toml`; then `cargo clippy --manifest-path desktop/src-tauri/Cargo.toml --locked --all-targets -- -D warnings`. Compatible Tauri components pinned and actual Cargo.lock retained. |
| Formatting | PASS | Prior core/shell `cargo fmt -- --check`; build source changes retained formatting. |
| Kotlin domain JUnit | PASS, 29 tests | Genuine Gradle/JDK Windows run, zero failures/errors/skips. Initial snapping-fixture assumption corrected to test default eligible-road snap and strict-radius rejection. |
| Android app JUnit | PASS, 14 tests | Lease approval/expiry/replay, bounded wire/resource parsing, custom endpoints and public-response privacy. Zero failures/errors/skips; JVM tests do not run Android services/providers. |
| Domain JaCoCo | PASS for module scope | Lines 547/573 = 95.46%; instructions 5721/6617 = 86.46%; branches 563/831 = 67.75%. Report: `android/session-domain/build/reports/jacoco/test/jacocoTestReport.xml`. No whole-app or Android adapter coverage claim. |
| Node model coverage | PASS for model scope | Earlier model run: 100% lines, 90.99% branches, 96.43% functions across 14 tests. Does not measure native/phone behavior. |
| Android debug/release compilation, lint and assembly | PASS | `tools/build-android.ps1 -Variant both`, final `BUILD SUCCESSFUL`, 182 tasks, 30 seconds. Both lint variants have zero errors and five newer-version warnings for deliberately pinned compatible dependencies. Asset collision, Kotlin generic inference and framework saved-state call repaired. Narrow intentional mock-purpose/application-context lint exceptions documented in [ANDROID_BUILD.md](ANDROID_BUILD.md). |
| APK signing/alignment | PASS | `tools/sign-android.ps1`: RSA-3072 release key outside repository, DPAPI password, restricted ACLs checked; zipalign and apksigner verify pass. One signer, certificate SHA-256 `862de591bf8bf3f8ae8e3109018fb5dfed0731d775c3acef0808c0cebdf46102`; package `dev.localspoof.android`, version 0.1.0, non-debuggable, min API 29/target 36 verified. |
| Desktop APK trust gate | PASS | Wrong expected signer rejected before Cargo compilation; correct independent certificate fingerprint, package/version, non-debuggable state and same-handle APK hash accepted. Artifact held against write/replacement through build. |
| Windows release/NSIS packaging | PASS | `tools/build-desktop.ps1 -VerifiedSignedApk ... -ExpectedSignerSha256 ...`; locked Cargo release build and NSIS bundle succeed. Installer copied to `release/LocalSpoof_0.1.0_x64-setup.exe`. Authenticode status is `NotSigned`. Installer install/uninstall/upgrade not exercised. |
| Actual browser UI | PASS, 25 checks | Isolated Windows Chrome/Chromium CDP: 1280×840, 900×640, 390×844, 844×390; overflow/canvas/target sizes, mode controls, modal focus/Escape, local GeoPack file import/search, no external page requests, exceptions or app console calls. Actual screenshots inspected; marker/landscape/focus findings fixed. Evidence `.build/browser/qa-result.json`, PNGs and accessibility tree. This is a focused check, not full WCAG conformance. |
| Actual Windows Tauri/WebView2 launch | PASS, 11 checks | Release executable showed its main window and bundled UI, native bridge/scan available, Start disabled unpaired, dialog focus restored, native command rejected invalid executable path. Reload/smoke traffic used only local asset/IPC origins; no JS exceptions or app console calls. `.build/native-browser/qa-result.json` and screenshot. Test-only CDP enabled through launcher environment; production config has devtools disabled. No actual ADB/USB operation. |
| Artifact integrity and signing ACLs | PASS | `release/artifacts.json` and `SHA256SUMS.txt` record sizes, digests, certificate, APK trust binding, x64 and unsigned Windows publisher status. Keystore/password ACLs allow only current Windows user, SYSTEM and Administrators. |
| USB pairing/install/control and mock consumers | NOT RUN | Requires attached target phones and explicit device testing. Neither foreground-service lifecycle nor Google fused-provider behavior follows from JVM/build success. |
| Screen-off/background, Stop/provider cleanup and radio restoration | NOT RUN | Two-hour/OEM/Doze/battery/kill/reboot/unplug/permission and flight phase tests remain physical-device gates. |
| Third-party compatibility | NOT RUN | Life360 compatibility and mock-detection behavior unverified. Mock markers are preserved. |
| Runtime privacy audit | NOT RUN | Source scans and bounded local UI traffic do not establish absence of OS/ADB/webview/keyboard files, crash traces or logs. Synthetic canary filesystem/backup/network audit remains. |

Logs are under `.build/windows`: `android-build.log`, `desktop-check.log`, `package-release.log`, `browser-qa.log`, `native-ui-qa.log`, and `artifact-verification.log`. These contain synthetic developer/test evidence, not app activity. Private signing material is outside the workspace and is never printed.

Build artifacts are ready for local testing. Physical qualification remains incomplete; nothing was installed on a phone, published, uploaded or committed.
