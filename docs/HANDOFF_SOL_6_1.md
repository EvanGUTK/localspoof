# Handoff for GPT-6.1 Sol

Prepared: 2026-10-02. Workspace: `/mnt/e/Codex/Location Spoofer`.

**Historical architecture handoff:** the user subsequently authorized implementation using ECC, then Windows toolchain installation and artifact creation. Those instructions supersede the original documentation-only boundary and earlier single-turn read-only inspection. Signed APK and Windows installer builds are complete. Read the [current build handoff](BUILD_HANDOFF_SOL_6_1.md), [BUILD_STATUS.md](BUILD_STATUS.md), and [VALIDATION.md](VALIDATION.md) before continuing. Physical-device qualification remains incomplete.

## Read this first

At the time this handoff was prepared, the user's instruction was **“plan and create architecture ONLY no code”** and then create a Markdown handoff for Sol 6.1. The package completed that documentation task. The later explicit build request supplies implementation authorization; this paragraph records the original boundary.

Read [ARCHITECTURE.md](ARCHITECTURE.md), then the [ADR index](adr/README.md). When planning began there was no application scaffold, dependency installation, prototype, build, or executed device test. The workspace was otherwise empty apart from reserved environment directories. Do not report feasibility gates as passed without subsequent evidence.

## Requested product

Create an Android location simulator inspired by the interaction model at [Wiped](https://www.wiped.me/), distributed outside app stores. It must work as a sideloaded APK and optionally through a desktop app connected to the phone. No root, accounts, sign-up, logs, retained activity, ads, paid features, subscriptions, or runtime cloud dependencies.

Required modes: set/hold a location instantly, walk a route, drive a route, and an airport journey for long-distance moves. Above 250 statute miles, suggest travel to a nearby suitable airport, airplane mode, a timed flight interval, arrival at a destination airport, restoration of connectivity, and an optional final ground route.

Do not copy the reference product's iOS mechanism or add saved favorites merely because it offers them. Do not claim that 250 miles is a known Life360 detection threshold.

## Assumptions awaiting user feedback

Two optional questions were asked during planning. Without an answer, the package assumes:

1. Windows is the first desktop target; macOS and Linux can follow.
2. Static offline map/routing/airport data may remain installed, while user activity never persists.

These are proposed defaults, not recorded user approvals. Incorporate any later answer before building dependent features. The Android 10 minimum and chosen technology stack are also proposals, subject to the evidence gates below.

If static pack storage is declined, use bundled assets and session-only imports. Explain the offline coverage consequences. Do not add hidden persistence or online routing to compensate.

## Architecture to preserve

- The phone owns the active session and all injection timing. Desktop and Android interfaces are controllers of the same coordinator.
- Kotlin/Compose Android app; plain Kotlin session domain; a visible, user-started location foreground service.
- Tauri 2/Rust/TypeScript desktop proposal. Keep shell/tool access behind a narrow native bridge. Desktop previews use local maps; route calculation runs on the connected phone.
- Public framework mock-provider adapter first. Optional GMS fused adapter only after interoperability and privacy evidence; no assumptions that one provider covers all consumers.
- Offline BRouter integration candidate, local MapLibre rendering candidates, bundled OurAirports snapshot, regional static packs.
- USB ADB tunnel to a phone-local socket, versioned bounded protocol, ephemeral local pairing, a single authorized controller, phone Stop priority.
- No backend, user-data database, stored settings, saved state containing coordinates, route history, telemetry, production diagnostics, online maps, or automatic updates.
- The default APK should avoid internet permission if its selected map and socket adapters can operate entirely through files/local sockets. A dependency demanding internet access needs explanation and a revised capability boundary, not silent manifest merging.

The app's guarantee concerns its own handling of activity. Android, ADB, receiving apps, and external download hosts are outside that guarantee. SDK caches are inside the integration audit and cannot be excused as “someone else's library.”

## Nonnegotiable platform behavior

Mock locations are detectable. The APK cannot promise silent airplane-mode toggling. USB shell control is a conditional capability. Airplane mode does not guarantee network silence or suppress every location source. Root-free does not establish universal legality.

Start the foreground service from visible phone UI. When the desktop connects to an idle app, the user may need to arm the session on the phone. A live foreground service can then receive desktop commands without repeated starts. No boot-based revival, WorkManager tick loop, stealth service, or persistence-based recovery.

Pause freezes route progress while holding the last point. Completion holds the destination. Stop releases mocking and drops session data. Process death loses the session; cleanup may not run. Do not claim that stopping instantly forces every target app to display the real location.

Normal USB disconnect leaves ordinary phone playback running. Flight radio transitions become guided phone steps. Restore airplane mode only if this session changed it, using the remembered original state while still in memory. After process loss, manual recovery may be needed.

## First implementation work, only after authorization

### Phase 0 — prove the difficult constraints

Build small, disposable feasibility probes before investing in polished UI. Use synthetic locations and no production activity logs.

| Probe | Evidence required | Failure response |
|---|---|---|
| Framework location provider | Separate consumer receives mocks; normal cleanup works; hard-stop outcome observed | Narrow support matrix or revise provider adapter |
| Google fused consumer | Establish whether framework-only samples reach it reliably | Evaluate isolated optional fused adapter; document gaps |
| Background runtime | Screen-off, Doze, battery saver, task dismissal, force-stop behavior on real devices | Tune lifecycle/power policy; report limits honestly |
| Airplane mode | APK guided flow works; USB capability and observed state verified on each supported OEM | Keep guided mode; do not fake automated success |
| Map/routing privacy | All map dependencies local; no activity-bearing cache or router files | Replace integration or reduce initial UI scope |
| Desktop privacy | Incognito webview plus crash/restart filesystem audit; no sensitive cache/history | Replace desktop component if contract cannot be met |
| ADB transport | Local socket works; no remote listeners; tool logs/keys understood; no location in args | Fix boundary; document unavoidable tool metadata |

Do not use an emulator as sole evidence for radio, power, USB, or OEM support. Capture concise synthetic test results in development documentation; no shipping user-log feature.

### Phase 1 — minimal standalone APK

Implement setup guidance, provider readiness, explicit Start/Stop, instant hold, foreground notification, memory-only state, and cold-launch cleanup/reset. Prove the lifecycle before route or map complexity. Coordinate input can serve as the initial UI while offline map integration is validated.

Exit: a real device can start a synthetic hold, continue through UI dismissal, stop normally, and restart with no restored user data. A separate test consumer confirms what it receives.

### Phase 2 — offline planning and routes

Integrate the validated local resource adapters and BRouter engine. Add offline place lookup, pack coverage, walking/driving geometry, speed/dwell controls, route preview, timing, pause/resume, and destination hold. Disable the router's output/history/diagnostic files.

Exit: no-network route calculation and playback work within installed coverage. Missing resources fail explicitly. Timing, geometry, pause, and replacement-plan behavior pass deterministic tests.

### Phase 3 — desktop controller

Add device selection, local APK install flow, phone setup guidance, ephemeral pairing, capabilities, atomic plan transfer, current snapshots, and USB disconnect/reconnect handling. The desktop must never become the per-tick playback source.

Exit: two connected phones cannot be confused; invalid or stale commands are rejected; normal phone playback survives cable removal and laptop sleep; no activity is stored by the desktop.

### Phase 4 — airport journey

Implement airport candidates, offline surface-leg validation, the exact 402,336-meter threshold, editable phase durations, guided airplane mode, and arrival hold. Add optional USB radio automation only for capability-tested devices.

Exit: failure at every phase boundary results in a known holding/manual/error state. No silent arrival jump, blind connectivity restore, invented flight availability, or promise of third-party acceptance.

### Phase 5 — release qualification

Complete device/API compatibility, power, privacy, and signed-distribution checks. Review app/SDK permissions, logs, backups, browser persistence, map attribution, and pack integrity. Recheck Android's current direct-install/verification rules. Document actual support, not theoretical support.

Exit: signed APK and Windows package can operate offline with documented limitations and no activity-bearing app files. Source-license choice and publisher signing-key custody must be settled before publication. Do not publish or upload artifacts unless subsequently authorized.

## Suggested future module layout

This is a planning list, not a request to create directories now:

- `android/app`: Compose UI, setup, notification, lifecycle integration.
- `android/session-domain`: plans, state transitions, virtual clock, interpolation.
- `android/platform-location`: framework and optional compatibility adapters.
- `android/geo-resources`: read-only pack access, routing, airport/place queries.
- `android/local-control`: local socket, session authorization, command dispatcher.
- `desktop/ui`: local map and controller interface.
- `desktop/native`: ADB process boundary, device selection, transport, radio adapter.
- `protocol`: language-neutral contract and synthetic test fixtures.
- `docs`: architecture, decisions, compatibility evidence, release instructions.

## Meaningful acceptance checks

- Below/at/above the flight threshold; invalid coordinates; antimeridian geometry; duplicate waypoints; zero-distance routes.
- Stable sample timestamps and speed/bearing consistent with route geometry; scheduler gaps pause progress rather than teleporting forward.
- Stop wins over queued commands; stale revisions cannot replace a plan; failed preparation leaves the current session intact.
- Reboot, crash, force-stop, and OS task-stop never resurrect user activity.
- USB unplug, desktop crash, permission revocation, and rejected airplane operations have visible, bounded recovery paths.
- Network-blocked operation includes map styles, glyphs, search, routing, and startup; no hidden downloads.
- Synthetic canary coordinates/searches do not appear in app files, webview cache, SDK output, tool arguments, logs, or backups after stop/crash/restart.
- Pairing has no saved device identity or credential. Stop clears the route, buffers, pending operations, and command cache.
- Surface-route coverage is checked for both ends of a flight before connectivity changes.
- Production builds include no diagnostic export, analytics, ads, accounts, payment, hidden bypass, or app-specific detection-avoidance code.

## Source refresh and handoff completion

The architecture links primary Android documentation, Google FLP documentation, AOSP shell implementation, BRouter, MapLibre, Tauri, OurAirports, and OSM policy. They were reviewed for this plan on 2026-10-02. Revalidate fast-changing Android restrictions and dependency behavior when implementing; pin versions only after that review.

This handoff is complete when the next model understands the intended product, assumptions, capability limits, privacy contract, and ordered evidence gates. It deliberately does not claim a working application.
