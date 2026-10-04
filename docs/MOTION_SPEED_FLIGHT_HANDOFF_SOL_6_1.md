# Motion, speed, tracking and flight handoff — Sol 6.1

Updated: 2026-10-03. **Implemented, rebuilt and installed under the user's “WM (Workmode) implement .md with sensor test” instruction.** Current results are below; the historical read-only pause remains recorded for provenance.

## Workmode implementation and qualification — 2026-10-03

The user resumed writable implementation. Delivered: actual submitted-speed gauge; fixed/continuous variable cruising through 125 MPH; live walk/car/plane speed controls; conservative acceleration/braking/turn slowdown; mapped-limit following with Unknown coverage; 200 ms provider scheduling; 500 ms desktop status polling; confirmed-point road/flight display animation with exact jump generations; Follow/Fit; truthful freshness and scheduling-gap messages; phone-owned playback after controller loss; manual Sensors Off help; true-gate flight notifications and original radio-state restoration. Normal mobile density remains at least 60% clear map in the rendered regression.

Use matching **protocol minor 5** components. Hello requires `motionTelemetry`, `variableCruise`, `mappedRoadLimits` and previous capabilities. `SetSpeeds` adds `drivingSpeedMode`, `driveMinMps`, `driveMaxMps`, `followRoadLimits`; car maximum is 56 m/s. Submitted telemetry includes speed, age, sequence and `placementGeneration`; changing a target does not masquerade as a submitted fix. Saved configuration has strict portable fields; telemetry/authority/history are excluded. Offline converter distinguishes mapped numeric limits from estimates, including directional limits. Online drive previews optionally enrich fixed-provider OSRM geometry with Valhalla's explicit `speed_limit`, strict snapping/alignment and bounded requests. Route geometry also goes to that public provider; no worldwide posted-limit coverage promise is made.

USB lifecycle qualification additionally found that closing a listener did not wake blocked `accept`. Explicit native shutdown now precedes descriptor close; bind-before-enabled state, dead-host explicit retry and generation-fenced failure reporting preserve approval boundaries and existing phone playback. Physical listener shutdown and same-process re-enable passed both while awaiting accept and with a paired client at a manual flight gate; secure reconnection succeeded. Stop also cancelled the flight prompt and left no playback wake lock.

Combined verification: **135 JavaScript tests**, TypeScript 5.9.3, protocol/source privacy checks, **57 domain and 143 Android app tests**, debug/release lint and APK assemblies. Lint passes with existing nonfatal dependency/platform/WebView warnings. Windows Rust: **26 shell tests and 22 bridge/boundary tests**, Clippy with warnings denied; one explicitly opted-in network test is excluded from ordinary runs. Separately, that real public Berlin test passed with 135 points and 134 verified limit-bearing segments. Independent ECC review caught and fixed empty-Unknown ingestion, trimmed Valhalla boundary lengths, old/duplicate ACKs, short jumps and loop interpolation. Windows packaging scripts now check native exit codes while tolerating normal stderr progress.

Physical S24 qualification uses the signed secure production APK and the real Windows native bridge. Fresh number-comparison approval succeeded without recording codes or credentials. An explicitly synthetic public Chicago straight-path DRIVE test produced 45/45 LIVE samples over 45 seconds, sequences 135→358 (5–6 new samples each one-second observation), speed 40.239–55.875 m/s, and maximum reported age 183 ms. That interval included eight seconds with the screen off. The real Windows gauge, marker and Follow/Fit controls were visible with no horizontal overflow. Live fixed 60 MPH changes, Pause/Resume and resumed submissions passed. Five seconds after real desktop disconnect, an independent phone consumer still received fresh mock GPS fixes. Stop ended the test; system wake-lock history is not treated as a currently-held lock observation.

**Sensor test result:** with the manually enabled Sensors Off tile, an independent isolated Android consumer registered both sensors and received **zero accelerometer and zero gyroscope callbacks**, alongside **148 mock GPS fixes in about 29.8 seconds**, a fresh reported speed of 55.045 m/s and fix age 157 ms. No raw XYZ or GPS coordinates were retained by the consumer. This establishes the observed coexistence of blocked motion callbacks and continuing mock location/speed. It does not supply replacement sensor events or establish any third-party driving classification. The attempted enabled-sensor positive control was **inconclusive** because automated tile interaction did not establish OFF; it is explicitly not an A/B pass. User's original Sensors Off ON state is preserved. Earlier bounded stock-phone injection modes failed; no root, exploit or target-APK modification was used.

The final signed phone/Windows combination also passed a public ORD→MDW manual flight gate: desired airplane mode ON, observed OFF, the dedicated flight notification contained enable-airplane guidance, and radio state stayed OFF. Stop cancelled the prompt. This checks the gate and guidance, not completed ON/OFF flight transitions.

Evidence: [USB Stop/re-enable](../.build/motion/usb-re-enable-result.json), [flight gate](../.build/motion/flight-gate-result.json), [flight notification](../.build/motion/flight-notification-result.json), [final installed identity](../.build/motion/final-install-result.json), [motion samples](../.build/motion/physical-motion-result.json), [independent sensor/GPS consumer](../.build/motion/sensors-off-mock-result.json), [live controls](../.build/motion/live-controls-result.json), [disconnect continuity](../.build/motion/disconnect-playback-result.json), [Windows capture](../.build/motion/desktop-live.png). These are explicitly authorized development qualification receipts, not production application activity logging.

Packaging uses the existing release certificate and bundles the exact signed APK into NSIS. The final APK is installed on the S24; its SHA-256 is `a7cd7bd42bc4d839b1eb503be8b0450c169db1aedc780c7b3877382286aa0308` and matches the installed Windows companion. The Windows installer upgrade passed, its installed executable matches packaged bytes, and the installed app is open normally without the task debugging environment. The phone is on Map with playback stopped and USB desktop control enabled. The temporary sensor consumer and task browser profile were removed; Sensors Off remains ON and airplane mode OFF.

Deliverables: [signed APK](../release/localspoof-0.6.0.apk), [Windows installer](../release/LocalSpoof_0.6.0_x64-setup.exe), [artifact hashes](../release/artifacts.json), [cleanup receipt](../.build/motion/cleanup-result.json). Reconnect from the Windows app after its installation restart; phone consent remains required.

Windows setup remains unsigned with Authenticode. Eight-second screen-off observation does not qualify hours of OEM/battery behavior. Actual flight radio-transition testing and third-party acceptance retain their explicitly stated scope. No systemwide replacement gyro/accelerometer events were verified or implemented.

## Scope and decisions

Keep desktop-to-Android control as the primary workflow, with the phone owning provider placement, consent, current playback state and radio gates. Preserve the existing map-first interface, explicit Preview/Start/Stop, local bookmarks, RAM-only activity and lack of application logs. Keep at least 60% of the normal portrait map viewport unobscured. Route options belong in the existing dismissible details sheet rather than another permanent overlay.

The requested speed gauge reports the speed of the current successfully submitted simulation sample. Label it clearly as simulated speed; it is not independent evidence of physical phone movement or what another app accepted. Target speed, cruise range and estimated arrival remain separate concepts. Honor the user’s existing speed-unit preference; support MPH prominently, with the existing km/h and m/s options retained. Missing or stale speed is shown as unavailable, never replaced by the configured target.

“Work mode” is ambiguous. For this handoff it means continuing development; no commute, workplace scheduling or background automation feature is assumed.

## Implemented behavior

1. **Actual-speed gauge:** compact numeric readout in the existing playback row, using acknowledged sample telemetry. Details show sample age and a truthful Live, Stale, Paused, Waiting, Idle or Error state. Preserve ETA and Pause/Resume visibility, including HOLDING.
2. **Variable driving cruise:** explicit Fixed/Variable selection, separate minimum and maximum speeds, and an example range of 90–125 mph. Validate finite values, minimum ≤ maximum, and the engine's driving bound. This range is a cruising preference: braking, stops, lower mapped limits and geometry-based turn slowdowns can reduce speed below its minimum. Avoid choosing independent random speeds every GPS tick; use a continuous target profile. Changes apply only through an explicit action; editing a draft invalidates its prepared preview.
3. **Follow mapped road limits:** optional control that caps the cruising target where trusted route-edge metadata exists. Show Unknown when metadata is absent; never infer a road's legal limit from a generic routing profile or its calculated duration. It must not affect walking or airborne speed.
4. **Smooth tracking:** retain only the preceding display sample and the newest acknowledged point in RAM. A bounded display animation may move between these acknowledged points. Do not extrapolate beyond the newest fix, create synthetic provider updates, or conceal scheduling gaps. Stop, ownership changes, teleport/jump, radio gates and stale telemetry cancel animation and use an exact current point or explicit unavailable state.
5. **Flight radio prompts:** issue airplane-mode ON/OFF guidance only at the engine's true departure and restoration gates. Offer the existing system radio-settings action. Deduplicate alerts by active session and gate/phase, cancel obsolete alerts on Stop or gate completion, and keep private coordinates/credentials out of notifications. The notification does not itself imply the radio changed; observation and existing acknowledgement barriers determine advancement. Simulation-only mode does not request actual radio changes.

Useful small additions are Follow location / Fit route controls, current connection/sample health in details, and an explicit reason/action when playback pauses or waits. Reuse existing controls; defer new dashboards, trip history, automatic schedules and commute features. These address navigation and reliability problems without crowding the map.

## Architecture and integration contract

| Layer | Responsibility |
|---|---|
| Domain coordinator | Validate cruise controls, update speed continuously with bounded acceleration, account for stops/limits and route bends, preserve ACK and radio barriers, expose estimates and scheduling-gap state |
| Android provider/service | Publish telemetry from successful placement only; use monotonic timestamps and increasing sample sequence; publish gate-specific foreground notification guidance |
| Runtime/protocol | Carry optional controls and acknowledged telemetry without extending control leases, storing location history or granting renderer authority |
| Android UI/session state | Validate draft inputs, preserve physical speed across unit changes, explicitly apply live changes, display actual speed and status, keep details in a sheet |
| Android/Windows map renderer | Animate display only between current acknowledged samples; bounded viewport sizing and lifecycle ownership remain intact |
| Saved route schema | Explicitly save configuration only if cross-platform schema support is added and reviewed; exclude sample telemetry, credentials and session approval |

The coordinated source contract is currently:

- `DrivingSpeedMode`: `FIXED` or `VARIABLE`.
- `DrivingSpeedControls(mode, driveMinMps, driveMaxMps, followRoadLimits)`. Existing defaults are fixed with min/max 13.9 m/s and follow-limits enabled. Proposed 90/125 mph values are 40.2336/55.88 m/s.
- `PlanRequest.speedControls` and `SessionSnapshot.speedControls`; optional `SessionSnapshot.roadLimitMps`.
- `RuntimeModel.setSpeeds(...)` retains existing speed/revision arguments and adds optional named `drivingSpeedMode`, `driveMinMps`, `driveMaxMps`, `followRoadLimits`.
- `PhoneLocation` adds optional `speedMps`, optional `bearingDegrees`, `sampleSequence` and `placementGeneration`.
- `PhoneView.motion`: `MotionTelemetry(speedMps, sampleAgeMs, sampleSequence, roadLimitMps, roadLimitSource, health, placementGeneration)`.
- Engine telemetry contract treats submitted samples older than 1,500 ms as stale and uses `SCHEDULING_GAP` to expose interrupted playback. Road-limit provenance is `MAPPED` or `UNKNOWN`.
- Implemented mobile events: driving-mode selection, follow-limits selection, range edits, and an explicit live-controls apply event.

Review the meaning of fresh telemetry at a radio/provider gate and paused state before displaying a numeric value. A fresh stored sample does not prove continued motion after playback stops advancing.

## Mobile implementation status after authorization resumed

Mobile source is implemented and frozen for review. The owner changed `MobileUi.kt`, `MobileSessionState.kt`, `MobilePresentation.kt`, `MainActivity.kt`, `MobileSavedStore.kt`, `MobileMapView.kt`, `mobile-map/map.mjs`, new `mobile-map/motion.mjs`, the application-asset checksum manifest and focused mobile tests. Production screen-security/debug settings are unchanged; no installation, signing or phone actions were performed by this owner.

- The existing compact playback row displays submitted simulated speed in the selected unit, plus live/stale/paused/wait state. Details separate that readout from target speed, ETA and mapped-limit provenance. Both the visible native gauge and renderer expire a stalled publication on their own monotonic clock; scheduling-gap pauses have an explicit explanation.
- Draft and explicit live controls support fixed/variable driving, ordered physical min/max values through 56 m/s (including 90–125 mph), and route-limit following. Preview ownership is invalidated by draft edits. Walking validation is independent of hidden car-range errors. Applying a fixed car speed selects fixed cruise rather than leaving a variable profile active.
- Portable saves preserve `drivingSpeedMode`, `driveMinMps`, `driveMaxMps`, `followRoadLimits`; bounds and boolean types are strict. Legacy absent bounds use the saved physical speed, matching desktop defaults. Saving activity, telemetry or controller authority is still prohibited.
- Static route geometry is cached separately from current motion. Display animation updates an independent marker source, follows acknowledged road vertices, retains only the current/previous ACK, returns the exact endpoint, and stops on stale/paused/waiting state, ownership changes, teleport/discontinuity, Stop or disposal. Older/conflicting ACKs cannot move the dot or renew freshness. Actual submitted placement generation makes even a short explicit jump snap exactly. Airborne tracking supports mixed ground/flight plans. No future extrapolation is performed.
- Follow location and Fit route are display-only controls inside the existing map-key dialog. The permanent map chrome gains no new control row. The normal 384×628 viewport regression still requires at least 60% clear map, including live telemetry and retained nearby results.
- In-app flight guidance is tied to a true pending radio gate and compares the required radio state with the observation. It restores the original state rather than unconditionally demanding OFF. Service notifications remain the engine owner's implementation.
- Advanced Settings explains the manual Sensors Off developer tile and its limits; there is no automatic toggle or systemwide sensor-spoof switch. Unknown posted limits are labeled honestly; offline estimated/profile caps may still constrain route playback and are disclosed separately.

Verification: nine Node tests exercise the actual mobile renderer/motion assets, including early state replay, exact/stale category pins, road-corner tracking, flight tracking, duplicate ACKs, self-expiring freshness, stale ACK rejection, exact short jumps, cancellation and absence of per-frame road redraw. The focused Android run passed 67 tests: 22 policy, 23 rendered-layout, 3 motion-presentation, 7 presentation, 7 session-state and 5 portable-save tests. The repeat after final ACK-ordering, placement-generation, range-label and paused-radio repairs also passed 67/67 with zero failures/errors/skips; log: `.build/motion-mobile/focused.log`. Root owns full builds/lint, distribution artifacts and separate physical sensor/mock-location qualification.

## Independent engine/service source review

The mobile owner independently reviewed the current domain coordinator, actual-submission telemetry, service scheduler/wake lifecycle and flight gates without changing those owners' source. Atomic revision-bound control validation, bounded acceleration/braking/turn handling, scheduling-gap pause without catch-up, successful-injection-only telemetry, paused/gated speed suppression, active-consent wake ownership and observed-radio/placement barriers were verified in source. The controller lease is distinct from continuing phone playback consent. Original-state radio restoration and simulation-only behavior remain explicit.

This source review does not qualify actual 200 ms provider cadence, Samsung screen-off behavior, battery impact or another app's acceptance. The minor foreground-notification wording issue was repaired by root: paused status now takes precedence over radio guidance, matching the high-priority gate alerts and native mobile guidance. Root owns the full validation and physical receipts.

## Historical source status at the read-only pause

The Android UI/map owner made **no code or test edits** during this motion task. `MobileUi.kt`, `MobileSessionState.kt`, `MobilePresentation.kt`, `MainActivity.kt`, `MobileMapView.kt`, mobile assets and mobile tests remain at their prior owner baseline. No gauge, cruise UI, new event wiring, map interpolation or saved-schema integration was implemented by this owner. No builds, tests, installs, signing or phone actions were run by this owner.

Source edits had begun under the earlier writable request before the user changed the active scope. All implementation agents were interrupted, and those partial edits were left untouched. Installed applications and release artifacts were not replaced.

| Owner | Partial files | Status at pause |
|---|---|---|
| Engine | `android/session-domain/src/main/kotlin/dev/localspoof/domain/{Session,Resources}.kt`; `android/app/src/main/kotlin/dev/localspoof/android/{PhoneLocationState,MotionTelemetry,RuntimeModel,WireJson,PhoneControlServer}.kt` | Variable cruise, 56 m/s cap, optional limit following/braking lookahead, approximate ETA, submitted-sample telemetry, optional online edge-limit parsing. Uncompiled, no new regression tests or build run. |
| Desktop | `desktop/ui/src/{motion,app,settings,model,playback,saved}.ts`; `desktop/ui/{index.html,style.css}`; `desktop/ui/tests/{motion,settings}.test.mjs` | Partial controls/polling/gauge/interpolation. **Could not typecheck/build at that pause:** `drivingPayload`, `renderLiveDriving`, and `WorldMap.follow` integration unfinished. Motion tests had three passes and one floating-point assertion failure; tolerance was changed without a rerun. Generated UI assets untouched. |
| Root | `desktop/src-tauri/src/network.rs` | Only added `OnlineRoute.edge_speed_limits`; its existing constructor has no initializer yet, so this partial change must be completed before compiling. No provider enrichment implemented. |
| Mobile | This Markdown handoff only | No mobile UI/map, test, build, install or device changes by this owner. |

**Historical pause restriction: this partial source was not packaged.** In particular, At that pause Android injection remained every 1,000 ms; no new wake lock, 200 ms scheduler, or gate-notification behavior exists yet. Native request whitelists and saved schema still need parity with the proposed fields. The current UI snapshot schema and protocol minor/capability handshake must be reviewed before release. The desktop partial render loop would redraw the whole map each frame; optimize marker movement separately before treating smoothing as a performance fix.

Known pending integration issues:

- The prior mobile presentation and saved-store driving cap is 55 m/s (about 123.03 mph); the proposed engine cap is 56 m/s so that 125 mph is accepted. All mobile/native/protocol/saved validators must agree before exposing that range.
- Mobile draft and `MainActivity` currently have no new driving-controls events or `PlanRequest.speedControls` wiring.
- Existing saved-route schemas do not yet have reviewed portable cruise-control fields. A saved route must not silently lose newly introduced controls.
- The map currently renders the latest native point directly. The new sample sequence/health fields are not yet included in its payload, so no new smoothing/staleness behavior is delivered.
- Engine acceleration/profile/limit behavior and gate notifications require independent source review and new validation. No passed baseline test establishes their correctness. The partial desktop radio helper only recognizes OFF restoration and must be generalized to the engine’s original-state restoration target.
- The workspace is not a Git repository at its current root, so a Git-based changed-file inventory was unavailable. Do not infer that source is unchanged merely from the lack of a Git diff.

The prior shipped 0.6.0 category/layout baseline had 170 Android tests and release validation recorded in [VALIDATION_0_6.md](VALIDATION_0_6.md). Those results apply to that stated artifact and source scope, **not** the partial motion changes. The baseline renderer's bounded network queue, deferred initial-state replay, factory-owned WebView and explicit pixel viewport sizing must remain intact. Preserve secure production flags and the absence of WebView debugging.

## Reliability investigation and acceptance targets

Read-only source inspection found two contributors to visible steps: the phone service submits one fix per second, and desktop snapshot polling was every two seconds. At 125 mph that is about 56 metres per provider fix; at 230 m/s flight speed it is 230 metres. The domain intentionally pauses after a scheduling gap over five seconds. These are plausible contributors to the user’s symptoms, not proof of every reported multi-minute freeze or another app’s refresh behavior.

Plan 200 ms provider ticks and approximately 500 ms desktop status polling, measured with monotonic time. Keep heartbeat independent of slower planning/search/rendering work and serialize protocol mutations. Do not call network planning or map work from the authoritative tick. Investigate CPU sleep, lifecycle, provider failures, controller expiry and Samsung background policy separately. An active foreground-session wake lock is a proposed screen-off mitigation; release it on Stop, failed start, authority loss and service teardown, and measure its battery impact. No automatic bypass of consent or unrestricted always-on service.

Animate only confirmed display updates along the known route, cancel on real teleport/Stop/stale state, and avoid rebuilding route layers at animation-frame frequency. Differentiate acknowledged provider movement from desktop transport age and rendered marker age. No persisted movement diagnostics. Third-party apps can still independently throttle, cache or reject mock updates; do not promise a particular app will display every 200 ms sample.

Qualification must include variable/fixed ground speeds and air speeds, screen-off/background and long runs, lost USB/controller lease, pause/resume, Stop, public road-limit changes and duplicate/stale telemetry. Use consenting public test locations and a separate test consumer to verify location timestamps/speed/sample cadence rather than relying solely on the map animation. Targets are a stable update cadence, no unexplained multi-minute hold, bounded interpolation and explicit reason/actions for intentional waits. Gaps must never cause a giant catch-up teleport.

## Airplane-mode guidance

For guided/manual Fly, arrival at the departure airport and boarding complete before a persistent prompt says **Enable airplane mode on your phone now**. Trigger a phone notification and desktop banner once per new ON gate, keep an in-app prompt when notification permission is absent, and provide a system-settings action. The flight starts only after the phone observes airplane mode ON. At the restoration gate, request the original observed radio state. Say **Turn airplane mode off on your phone now** only when the pending restoration target is OFF; otherwise say **Restore your original airplane-mode setting now** when a change is actually needed. Final driving waits for the required restoration observation. User delay is a visible wait, not an apparent freeze. Jumps retain these gates. Simulation-only flights do not request real radio changes; USB-managed flights retain ownership/restore semantics and report observed state without asking the user to repeat a completed step. Do not equate airplane mode with Sensors Off.

## Sensors Off, APK patching and emulator findings

### Options and product decisions

| Option | Root needed? | Actual scope | Product decision |
|---|---|---|---|
| Android/Samsung Sensors Off | No, for the user’s developer tile | Suppresses supported sensor access; does not manufacture motion | Optional manual setup guidance in advanced settings; no automatic toggle or driving-classification claim |
| Modify a specific APK | Not necessarily | Changes only paths inside the separately modified application | Not part of the normal desktop/companion architecture; feasibility is app-specific |
| Android Emulator | No physical-phone root | Virtual sensors inside the emulator | Use for controlled development tests; not a replacement for physical S24 qualification |
| Stock S24 sensor injection through tested ADB modes | No usable path demonstrated | Requires supported system/HAL testing mechanisms | Do not expose a nonfunctional sensor-spoof switch |

### Sensors Off

Android provides this developer tile to suspend `SensorManager` events, deny camera access and silence ordinary microphone recording. It does not supply replacement acceleration or angular velocity. Phone-call microphone access is an exception. Availability can vary by vendor. [Android Sensors Off documentation](https://source.android.com/docs/core/interaction/sensors/sensors-off).

On this actual S24, a read-only package query confirmed `com.android.settings/.development.qstile.DevelopmentTiles$SensorsOff` exists. The tile was **not enabled or toggled** in this read-only follow-up. Manual path: **Settings → Developer options → Quick settings developer tiles → Sensors Off**, then tap the added Quick Settings tile. Developer options are already enabled for ADB.

Do not advertise this as preventing all tracking: GNSS/location, networks, cached data and remote services are separate concerns. No Life360 classification or GPS-continuation experiment was performed with the tile enabled. A later controlled experiment can compare event delivery and mock-location updates, then restore the original state; absence of motion readings is not driving motion.

### Patching a particular APK

A modified client can substitute inputs in its own sensor-handling code without rooting the phone, if it can be installed and still functions. Altering LocalSpoof’s own listener cannot change sensor events delivered to Life360 or any other installed app. Accelerometer XYZ values describe acceleration and gyroscope XYZ describes angular velocity; they are not latitude/longitude coordinates. [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion).

Rebuilding invalidates the original signature. Without the original signing identity, the changed APK cannot normally update the installed original; separate installation or removing the original has distinct compatibility/data consequences. [Android app signing](https://developer.android.com/studio/publish/app-signing). Certificate checks or app/server integrity verification can reject altered clients. This is a possible limitation, not a claim that a particular Life360 version performs a particular check. [Play Integrity app verdicts](https://developer.android.com/google/play/integrity/verdicts).

Finding one `SensorEventListener` is not evidence of complete coverage: native code, direct channels, activity-recognition services, split packages and other inputs may exist. No target APK was extracted, patched, resigned, installed or redistributed. Do not add a generic target-app patcher to the product based on this feasibility discussion.

### Emulator

The official emulator provides virtual pose/sensor controls and console sensor commands for repeatable test inputs. Query supported sensors for the selected AVD instead of assuming every system image provides the same gyroscope controls. These readings are visible to applications running in that emulator, not applications on the USB-connected S24. Recorded/replayed scenarios remain test fixtures, without introducing user journey history into production. [Extended controls](https://developer.android.com/studio/run/emulator-extended-controls), [Emulator sensor console](https://developer.android.com/studio/run/emulator-console).

### Actual stock-phone injection probe, before read-only steering

The earlier writable instruction explicitly authorized a sensor feasibility probe. Physical-phone metadata showed production build `user`, `ro.debuggable=0`, accelerometer flags `0x0`, gyroscope flags `0x40`; none includes injection flag `0x10`. [Android sensor capability flags](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/Sensor.java).

- Standard `data_injection` request did not activate an injection mode: global service state remained `NORMAL`. A process exit code of zero was not treated as success.
- `replay_data_injection` and `hal_bypass_replay_data_injection` reported `INVALID_OPERATION`; Android’s implementation restricts these replay modes on production builds. [SensorService implementation](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/master/services/sensorservice/SensorService.cpp).
- Normal-mode restoration was issued after each probe and global `Mode : NORMAL` verified at the end. Individual client connection labels are not the global-mode check.
- No synthetic sensor event was injected, no root/exploit attempted, and no sensor replacement was demonstrated. This is a bounded capability check, not proof that every conceivable future method is impossible.

The sanitized receipt is [sensor-capability-result.json](../.build/motion/sensor-capability-result.json), with no device identifier, private GPS position, raw event samples or secrets. It is development evidence, not application activity logging. Sensors Off, emulator input and changing a single APK do not invalidate the result because they use different mechanisms.

## Online speed-limit data

Existing online routing remains FOSSGIS OSRM geometry/duration; its calculated speed is not a legal limit. Offline edge metadata already exists, but its provenance must distinguish mapped limits from pack-builder defaults before using the MAPPED label. [OSRM API](https://project-osrm.org/docs/v5.24.0/api/).

The implemented additional source is a fixed Valhalla endpoint for optional, bounded route-edge enrichment. Valhalla distinguishes `speed_limit` from routing `speed` and provides trace attributes. Respect its public service limits; no provider account or our own hosted server is proposed. [Valhalla speed fields](https://valhalla.github.io/valhalla/concepts/speeds/), [Map matching API](https://valhalla.github.io/valhalla/api/map-matching/), [Public demo policy](https://valhalla.github.io/valhalla/).

A pre-pause request using public Chicago test points returned matching positions but **no posted-limit values**. That response demonstrates the API is reachable, not useful limit coverage for that segment or worldwide availability. At that pause no enrichment code was implemented. The resumed implementation now validates units, match direction and geometry, reject discontinuities/ambiguous paths, apply hard request/body/deadline bounds and treat failure/missing coverage as Unknown. Never assign a speed limit from nearby parallel roads or imply real-time legal accuracy. Online-provider disclosure must cover sending route geometry to this additional service; offline mode must make no enrichment calls.

## Historical implementation ordering

1. Reconcile and finish the partial source contracts with regression tests, native validation, version/capability handling and saved-schema parity.
2. Fix tick/background/transport stalls and qualify real provider cadence before polishing map movement.
3. Finish truthful speed gauge, smooth variable range and explicit limit-following behavior in both interfaces; preserve live walking/flight speed changes and ETA.
4. Add validated optional online limit enrichment and distinguish all missing/default metadata honestly.
5. Implement ACK-bounded marker animation, Follow location/Fit route and clear stale/wait reasons without blocking heartbeat or overcrowding the map.
6. Add phase-specific airplane ON/OFF notifications and qualify guided/USB/simulation behavior.
7. Review and test; only then rebuild, sign and replace APK/Windows distribution artifacts. Sensor privacy guidance and emulator qualification are separate small additions; systemwide sensor replacement remains unsupported by the tested stock-phone path.

## Delivered checklist for Sol 6.1

The checklist originated during the read-only pause and is now delivered. Physical observations and remaining qualification limits are explicitly scoped above.

- [x] Honor the read-only pause, then resume only after the user's new writable instruction.
- [x] Audit the engine owner's complete partial-change inventory; reconcile domain, runtime and protocol contracts.
- [x] Add meaningful regressions for truthful gauge values, missing/stale data, unit conversion, 125 mph acceptance and invalid/reversed cruise ranges.
- [x] Implement RAM-only mobile draft controls and explicit live-apply events; wire `MainActivity` after agreeing the engine API.
- [x] Place the mobile gauge within existing compact playback chrome and the range/limit controls in details. Focused tests preserve ≥60% uncovered map in IDLE/READY/ACTIVE/HOLDING with zero/eight places and accessible large-font fallback.
- [x] Make mapped-limit metadata explicit, bounded and optional. Unsupported routes must show Unknown without fabricated legal limits.
- [x] Add display-only interpolation tests: ACK-only updates, duplicate sequence, stale sample, scheduling gap, pause, teleport, Stop, radio gating and renderer disposal. No unbounded history or extrapolation.
- [x] Add actual gate notification tests, including guided flight, simulation-only mode, original-state restoration (including initially ON) and user delay; keep Stop reachable.
- [x] Decide and review portable save fields before saving cruise controls; align all validators and unknown-field rejection across Android and Windows.
- [x] Run focused tests, independent review, then full checks under renewed authorization. Record exact source/artifact identity and qualify physical playback separately from emulator/layout tests.
- [x] Build/sign/rebind the companion/installer only after checks pass and the writable scope permits it. Do not describe partial source as installed or delivered.
