# Android source and build qualification

Current Workmode build: 57 domain/143 app tests, both lint variants and APK assemblies pass. Signed S24/Windows playback and sensor scope are recorded in [motion validation](MOTION_SPEED_FLIGHT_HANDOFF_SOL_6_1.md). Earlier counts and NOT RUN statements below are historical.

The Android application is native Kotlin/Compose and the phone owns playback. Sources implement framework GPS/network test providers, a user-started location foreground service, privacy-aware inputs, coordinate/grid and GeoPack rendering, graph/custom routes, airport phases, guided radio changes, local USB pairing, atomic preparation/commit, and bounded pack transfer. The worldwide desktop routing update adds safe ingestion of online walking/driving geometry and both airport ground legs. The current Windows Gradle build passed all 52 JVM tests, debug/release lint and both APK assemblies on 2026-10-02. Release signing is a separate packaging step. The follow-up airport-access update adds two domain regressions; its rebuild is pending. Installation and physical-device behavior are **NOT RUN**.

## Worldwide desktop routing and the Android boundary

The Windows desktop can obtain worldwide online map/routing data and send a complete route through approved USB control. The phone does not need a regional GeoPack for these plans. Standalone Android remains an offline coordinate/GeoPack UI; it has no internet permission, online place search or worldwide street-map renderer in this Windows-first change. A standalone Android network UI requires a separate implementation before claiming those features.

`PreparePlan` declares `routeSource: "ONLINE_ROUTER"` and either `onlineRoute: {profile: "WALK" | "DRIVE", provider, points: [{lat, lon}]}` or, for FLIGHT, `onlineFlightRoutes: {departureAirportId, arrivalAirportId, originGround: OnlineRoute, destinationGround: OnlineRoute}`. Airport ground profiles must be DRIVE. The phone checks exact catalog/selected airport IDs and eligible airports before staging a flight. Hello advertises `onlineRouteGeometry`, `onlineFlightGroundRoutes` and `maxOnlineRoutePoints`; protocol major remains 1, with additive minor 1 capabilities.

Network geometry is untrusted. The phone rejects conflicting custom/online paths, mismatched provenance/profiles, invalid coordinates, provider labels containing control characters, more than 12,000 points (both flight legs combined), segments longer than 100 km and routes longer than 20,000 km. The existing 1 MiB framed-message limit applies before JSON parsing. First/last points must lie within 250 meters of user-requested endpoints. For online flights only, departure ground-end and arrival ground-start points may lie within 5,000 meters of their identified catalog airports; this handles catalog/runway centroids away from public roads. Ordinary walking/driving and offline graph snapping retain the 250-meter limit. Geometry is copied before preparation and playback begins at its actual first coordinate; the app never inserts a hidden connector to the selected point. Preview exposes actual snapped endpoints/access coordinates plus `routeSource`, `routeProvider`, `originAirportAccessDistanceMeters` and `destinationAirportAccessDistanceMeters`. The online air leg starts and ends at those actual access coordinates, and its distance/duration estimate uses them instead of creating centroid connector jumps. The phone validates geometry and its own speeds/timing, not independent road legality, provider identity, traffic or network accuracy.

Both flight surface legs must validate completely before commit. Flight duration, radio observation, provider placement acknowledgment, settling, Stop and scheduling-gap handling remain on the phone. Online routing requests are desktop operations; playback does not depend on continued network access. Existing offline graph and explicit custom path flows remain available.

## Toolchain

Use Java 17, Android SDK platform 36, Android Build Tools 35.0.0, Gradle 8.13, Android Gradle Plugin 8.11.1, Kotlin 2.2.21, and Android Studio supporting AGP 8.11. The [AGP 8.11 compatibility table](https://developer.android.com/build/releases/agp-8-11-0-release-notes) specifies Gradle 8.13, JDK 17, and maximum API 36. The Kotlin Compose compiler plugin matches the Kotlin version, following the [official migration guidance](https://kotlinlang.org/docs/compose-compiler-migration-guide.html). SDK baseline is API 29; target is API 36.

The authorized Windows setup installed official, checksum-verified tools. Actual paths and rebuild commands are in [WINDOWS_TOOLCHAINS.md](WINDOWS_TOOLCHAINS.md). The genuine Gradle 8.13 wrapper scripts and JAR were generated and retained. The PowerShell builder validates the wrapper JAR against the official checksum and verifies the exact binary distribution URL and checksum on every invocation.

```powershell
Set-Location 'E:\Codex\Location Spoofer'
.\tools\build-android.ps1 -Variant both
.\tools\sign-android.ps1
```

Configure the SDK through the installed Windows user environment, Android Studio, or a local, untracked `android/local.properties` containing `sdk.dir`. Gradle resolves Google/Maven dependencies during builds; the shipping app itself has no internet permission or runtime cloud client. Debug output is `android/app/build/outputs/apk/debug/app-debug.apk`; the unsigned release intermediate is `android/app/build/outputs/apk/release/app-release-unsigned.apk`. The signed release is `release/localspoof-0.2.0.apk`. Signing material remains outside the repository. The verified release certificate SHA-256 is `862de591bf8bf3f8ae8e3109018fb5dfed0731d775c3acef0808c0cebdf46102`.

## Tests and current evidence

The current Windows Gradle run completed all requested tasks successfully. JUnit XML reports show 35 domain tests and 17 app tests, with zero failures, errors or skips. `ControllerLeaseTest` covers phone confirmation, pairing expiry, wrong-token/replay rejection, revocation and heartbeat expiry. `WireJsonTest` covers coordinate validation, explicit custom paths, endpoint completion, graph reference validation, depth/UTF-8 limits and bounded reads. `PublicResponseTest` covers unauthenticated snapshot isolation and minimal approved credential delivery. Domain tests cover geometry, route restrictions, speed caps, scheduling, pause/Stop, airport access points, provider acknowledgment and radio phases. New online tests cover independent road geometry without a GeoPack, source/profile conflicts, mutable-list isolation, endpoint snapping, point/segment/total-length bounds, catalog airport selection, both prepared flight legs and placement acknowledgment. Wire tests reject missing/mismatched provenance, unsupported fields and invalid/oversized coordinate arrays.

| Domain JaCoCo metric | Covered / total | Coverage |
|---|---|---|
| Lines | 595 / 621 | 95.81% |
| Instructions | 6,577 / 7,469 | 88.06% |
| Branches | 704 / 997 | 70.61% |

Coverage applies to `session-domain`; app/Android framework coverage was not measured. JVM unit tests do not exercise an installed APK, framework mock injection, real USB transport, or foreground-service lifecycle behavior.

Both `lintDebug` and `lintRelease` passed with zero errors and five warnings about newer dependency/tool versions. Compatible versions remain pinned. Two narrow lint exceptions document intentional behavior: `MockLocation` applies only to the permission required for this release APK's mock-provider purpose; `StaticFieldLeak` applies only to the process-owned adapter, which retains application context and is released on service detach. Activity state saving calls its superclass for framework bookkeeping, then clears the output bundle. A launcher vector icon is included. APK assets contain the airport catalog and synthetic GeoPack, with Markdown documentation excluded.

Additional structural checks passed: the privacy source scan and six protocol fixtures agree with current Android response fields and lifecycle guards. Manifest checks confirm no internet permission, disabled backup, a private location service, and the mock-location declaration. These are source/packaging checks; no device filesystem, network or lifecycle qualification is inferred. The initial domain failure was a fixture assumption about the permitted 250-meter snap radius; the corrected test explicitly checks mode-eligible snapping and rejection at a tighter radius. Actual compiler and asset-merger failures were fixed before the successful final run.

## Device qualification

1. Install APK, enable Developer options, select LocalSpoof as mock-location app, grant precise location and notification permissions, and enable device location. The mock-location declaration makes the app selectable; the user-controlled AppOp authorizes provider operations.
2. Start from the visible phone UI. Verify a separate consumer receives GPS and network mock samples, timestamps advance, and mock flags remain true. Google fused-provider compatibility is not claimed.
3. Check Pause holds fresh samples, Resume preserves progress, destination Holding refreshes samples, and Stop cleans up both owned providers. Revoking permission or disabling location must stop progression and show recovery guidance.
4. Test a two-hour screen-off run, Doze, battery saver, task dismissal, process kill, force-stop, reboot, and scheduling gaps. The foreground service is not sticky and retains no reboot/restart journal. Timing and OEM support remain unqualified until measured.
5. Enable USB desktop control explicitly, create an ADB forward to `localabstract:localspoof.control.v1`, enter the 120-second secret, approve on phone, then negotiate current state. Verify rejection, stale revisions/sequences, duplicate IDs, malformed/oversized/partial frames, transfer limits, two attached phones, unplug, and local Stop.
6. Import only local GeoPack JSON. Imports are held in memory, never copied to app disk or granted persistent URI permissions. Verify connected walking/driving graph routes; missing coverage fails instead of becoming a road route. Canvas graticules are not a world street map. Synthetic fixture roads are labeled synthetic.
7. Verify the full flight timeline with both surface legs prepared first, using both offline graphs and desktop online routes. For online flights, verify access coordinates and disclosed distances from catalog airport centroids; access beyond 5 km must fail clearly. Offline graphs retain 250-meter snapping. Neither flow adds connectors to centroid coordinates. Synthetic route tests do not qualify real airport reachability. Phone observes actual airplane-mode settings; the APK cannot toggle them. Optional authenticated desktop radio requests carry a stable phase ID, and acknowledgment requires matching observed state. Lost desktop control exposes manual phone steps; Stop/disconnect cannot promise radio restoration.
8. Use synthetic canary coordinates/secrets and inspect app files, logs, caches, backup, and device transfer after Stop/crash/restart with egress blocked. No app activity file, automatic clipboard use, saved UI state, analytics, logging SDK, account, ads or payment flow is intended. OS/input/system-service behavior is outside the app promise.

Read [implementation plan](IMPLEMENTATION_PLAN.md), [architecture](ARCHITECTURE.md), and [validation](VALIDATION.md) before making compatibility claims. Compilation and signing are complete; physical-device qualification remains the manual release gate.
