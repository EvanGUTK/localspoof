# Local Android Location Simulator — implementation plan

Prepared 2026-10-02 using ECC `orch-build-mvp`. Sources of truth: [architecture](ARCHITECTURE.md), [handoff](HANDOFF_SOL_6_1.md), and the user's subsequent explicit instruction to build all features. That later instruction authorizes implementation and supersedes the handoff's earlier documentation-only boundary. It does not authorize publication, remote upload, or changes to protected environment directories.

Initial environment inventory: DNS resolution to Maven/npm is blocked; no Linux JDK, Gradle, Android SDK, or ADB was found. Node 24 can execute stripped TypeScript; Rust and cached serde/serde_json are available. Tauri crates and GTK development packages are unavailable. Therefore native source can be created here, but Android assembly and Tauri desktop execution cannot presently be claimed. Portable TypeScript and Rust checks remain executable.

## Scope and completion contract

Build a sideloaded Android app and optional Windows-first desktop controller. The phone owns simulation, route computation, and lifecycle. Cover instant hold, walking, driving, airport journeys, local map/resource import, USB pairing/control, guided airplane mode, capability-tested USB airplane operations, and manual distribution. Keep accounts, ads, payments, logs, retained activity, online services, hidden mock markers, per-app hooks, and root outside the product.

Static geographic resources may persist under the architecture's stated default. Settings, coordinates, searches, routes, current camera position, credentials, selected device, and activity remain memory-only. Missing tools, unavailable network, absent geographic packs, and unattached devices are recorded as unverified checks rather than passed gates. A substitute local renderer or explicitly labeled custom-path mode can provide useful functionality while SDK integration is unverified; neither earns a road-routing or full map claim.

## Ordered vertical slices

| Slice | End-to-end behavior | Exit evidence |
|---|---|---|
| 0. Constraints and contracts | Pin interfaces, wire examples, release privacy policy, environment inventory | Local contract validation; identify available JDK/SDK/Gradle/Node/Rust and attached devices; current API documentation review |
| 1. Standalone instant hold | Visible Android UI accepts coordinates, checks readiness, user starts service, provider emits repeated samples, Stop cleans up | Pure-domain hold/stop tests; Android compile and separate-consumer/device observations when available; never claim device behavior from compilation |
| 2. Route playback | User creates labeled custom path with walking/driving speed, previews, starts, pauses, resumes, completes in Holding | Geometry/timing/atomic replacement tests; background scheduling gap keeps last emitted position; UI/service have one owner |
| 3. Static resources and road routes | Import validated GeoPack; render local map; search local places; calculate graph-based walk/drive geometry on phone | Parser tests, complete coverage checks, profile-aware Dijkstra tests, network-blocked execution; external map/routing libraries remain optional future gates |
| 4. USB desktop | Desktop selects exact device, optionally installs local APK, forwards socket, pairs with phone confirmation, prepares a plan, displays live snapshot | Protocol/frame/security tests; two-phone selection, unplug, sleep, stale-command checks; native bridge never exposes arbitrary shell |
| 5. Airport journey | Long move offers preview; select airports and validated ground legs; boarding, guided airplane switch, timed interval, arrival settle, restore, final leg | Exact threshold and phase failure tests; every missing resource detected before radio change; all progress remains phone-owned |
| 6. Conditional USB radios | User opts in during preview; desktop detects shell capability, executes requested phase, verifies observed state; disconnect converts to guided step | Per-device capability observation; original state and user changes honored; no blind restore |
| 7. Qualification and delivery | Local debug APK/desktop builds, reproducible release instructions, third-party notices, explicit support matrix | Available automated checks; signed release only with publisher key supplied; real-device privacy/power/install evidence before release claims |

Slices can share work on independent modules, but service ownership, protocol schemas, and state semantics stay fixed. Review the complete slice plan before scaffolding, then review each concrete slice and checks. Existing user authorization covers routine reversible building; do not create commits or publish without authorization.

## Module interfaces

### Plain Kotlin domain

`GeoPoint` validates finite latitude [-90, 90] and longitude [-180, 180]. `Sample` carries coordinate, optional valid altitude, accuracy meters, speed meters/second, bearing degrees, and monotonic/wall timestamps. `RouteSegment` carries geometry, cumulative distance, traversal profile, maximum speed constraints when known, and dwell. `Plan` is an immutable fully prepared sequence, with origin source and explicit endpoint Holding semantics. Keep mutable preparation buffers outside active playback.

`SessionCoordinator` serializes mutations through `prepare(request, expectedRevision)`, `commit(preparedId, expectedRevision)`, `start`, `pause`, `resume`, `stop`, `tick(monotonicMillis)`, and `snapshot`. Preparation is cancellable and runs outside the mutation loop. Commit checks captured revision and atomically swaps complete validated data. Stop increments the revision, invalidates pending preparations and controller lease, takes precedence over queued external operations, then tears down providers and data. Error strings contain stable codes and useful static descriptions, never serialized user input.

`SessionSnapshot` contains revision, state, current sample, mode, journey phase, current provider health, capabilities, and estimated remaining duration. It contains no historical timeline. `ProviderAdapter` supports `capabilities`, `prepare`, `inject`, `health`, `cleanup`. Android runtime provides time, notification, lifecycle, and provider adapters; it cannot persist or reconstruct sessions.

`RoutePlanner.plan(origin, destination, profile, resources)` returns complete geometry plus coverage or structured failure. `AirportIndex.candidates(point, limit)` operates on a bundled static snapshot, filtering closed/heliport/private choices by defaults. `ResourceCatalog` validates manifests and offers read-only static assets; no selected-region preference or accessed-tile history. `RadioController` exposes guided or USB capability, observed state, phase-specific request/result, and session-original state. It is independent from sample submission health.

### Android runtime

Foreground service is started only from visible phone activity, type `location`, with required platform permissions. Desktop plan acceptance while idle creates a phone-visible pending plan; the user starts it on phone. Live playback retains one coordinator in process memory; Compose connects using service binding and observes current state. Service is not sticky and has no boot revival. Notification provides Pause/Resume and Stop and contains no places or coordinates. Permission/provider errors trigger controlled cleanup. Cold launch exposes reset without remembering a previous session.

No coordinate-bearing `rememberSaveable`, Bundle, preferences, Room, file cache, WorkManager request, clipboard, crash report, or production log call. Manifest disables backup, supplies extraction exclusions, omits internet permission in the default local-socket/file-resource build, and keeps service/socket inaccessible until user enables desktop control. Secure-window handling and keyboard no-learning requests reduce accidental disclosure while leaving OS guarantees explicit.

### Wire protocol v1

Language-neutral schema and fixtures live under `protocol/`; Kotlin and TypeScript mirror that contract. UTF-8 JSON frames have a four-byte unsigned big-endian length prefix and a 1,048,576-byte limit. Reject invalid lengths before allocating bodies, enforce transfer totals/count/time limits, and reject nonfinite/invalid coordinates and unknown major version. Initial envelope fields: `protocolMajor`, `protocolMinor`, `sessionId`, `commandId`, `sequence`, `expectedRevision`, `type`, `payload`. Authentication credential belongs inside the bounded frame, never a URL/tool argument.

Implemented commands: `Hello`, `Capabilities`, `PairRequest`, `PollPair`, `PreparePlan`, `CommitPlan`, `Start`, `Pause`, `Resume`, `Stop`, `Snapshot`, `Heartbeat`, `Airports`, `Places`, `ImportGeoPack`, `BeginGeoPack`, `GeoPackChunk`, `CommitGeoPack`, and native-only `RadioPhaseResult`. Radio requests arrive in authenticated snapshots; replies use bounded `ok`/`error` envelopes. Pair approval is a local phone action. Pairing starts with a phone-displayed cryptographically random 128-bit secret, expires after 120 seconds, and requires explicit phone confirmation. Generate a fresh controller credential after approval; hold one lease only, cap failed attempts, and clear on disconnect. Heartbeats every two seconds, stale after six. Sequence and expectedRevision gate mutations; authorized Stop is exempt from stale revision. A bounded acknowledgment cache allows idempotent retry only within the current lease. GeoPack chunks stage in memory; commit verifies complete size/hash/schema before installing resources. Plan geometry is calculated completely on the phone rather than transferred as plan chunks. See [the current protocol](../protocol/README.md).

Use a phone abstract local socket, desktop ADB `forward tcp:0 localabstract:<app socket>`, and loopback only. Apply exact `-s <selected serial>` to every device mutation and tunnel operation. Clean up only owned forwards. Route requests go to the phone; desktop never emits per-tick coordinates. Detached desktop stops displaying stale Active as current, while ordinary phone playback continues.

### Desktop boundary

Tauri native operations are a fixed allowlist: inspect/select a user-provided ADB executable, enumerate USB devices, verify/install a local APK, create/remove owned forward, establish bounded protocol connection, and perform only the explicit capability-tested airplane operation. Launch with argument arrays, cap stdout/stderr, avoid retaining serials/output, reject arbitrary shell/path commands. Use generic window titles, disabled browser storage/service workers/history/autofill, incognito webview when available, restrictive CSP, local resource handlers, and no remote pages or updater.

## Offline map and routing integration gates

Initial implementation decision: dependency-free native Android canvas and desktop canvas render local GeoPack JSON geometry. GeoPack contains schema/version, source/license/coverage, places, airports, graph nodes, walk/drive edge permissions and costs, plus map features. Phone route planning uses bounded profile-aware Dijkstra over the installed graph. An offline OSM XML-to-GeoPack conversion tool accepts user-provided extracts and preserves attribution; bundled synthetic graphs exist only for tests/demo and are labeled synthetic. This changes the initial adapters, not phone session ownership or privacy guarantees. Record the choice in a new implementation ADR.

Always-offline coordinate/grid rendering and labeled custom-path editing operate even without a geographic pack. Imported packs have versioned manifests, coverage bounds, byte limits, optional SHA-256 checksums, attribution, and compatible profiles. Validate malformed geometries, unsupported schemas, graph references, input size, and coverage before activation. An archive adapter, if added later, must also reject path traversal, symlinks and excessive expansion. Do not save import access history.

MapLibre Native and MapLibre GL JS are conditional map renderer candidates. Android's documented [resource transform callback](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.storage/-file-source/set-resource-transform.html) rewrites requested URLs; that API alone does not prove cache-free local archives. Verify local archive reads and persistent-cache behavior before enabling the production renderer. Desktop file/custom handlers must never cause a request to leave the app. Incognito [Tauri configuration](https://v2.tauri.app/reference/config/) is one control, with filesystem audits still required. Fail closed on unsupported local integration.

[BRouter core modules](https://github.com/abrensch/brouter), fixed profiles, and static `.rd5` segments remain a later compatibility option. Its server is excluded because that path emits request logs. Any later integration must use core read-only APIs with file outputs disabled, preserve MIT notices, verify segment completeness, and bound cancellation/time/memory. The initial GeoPack router instead follows explicit profile-permitted edges and reports incomplete/disconnected coverage. OSM-derived resource licenses and provenance remain separate obligations. Do not falsely represent synthetic test geometry as real roads.

Airport snapshot is generated independently of user activity from licensed static sources such as [OurAirports data](https://ourairports.com/data/). Bundle source date and public-domain notice. Destination/origin ground coverage must both pass before a flight is ready. Great-circle distance strictly greater than 402,336 meters triggers the suggestion; the threshold is not a third-party detection claim.

## Required acceptance matrix

| Area | Concrete checks | Where |
|---|---|---|
| Validation | Invalid/nonfinite coordinates, zero-length and duplicate points, antimeridian interpolation, bounded velocities/dwells | Pure Kotlin tests |
| Playback | Monotonic samples; real geometry speed/bearing; pause keeps point; >5-second scheduler gap pauses at last emitted point; destination hold; cleanup on Stop | Domain tests and separate Android consumer |
| Ownership | One ticking service; UI recreation binds live state; pending background desktop Start requires phone action; no boot/sticky recovery | Android lifecycle checks |
| Replacement | Failed preparation leaves old plan; stale commit rejected; Stop cancels staged plan and beats delayed commands | Coordinator tests |
| Wire safety | Split frames, multiple frames, oversized/negative-equivalent length, malformed JSON, unsupported major, stale token/revision/sequence, duplicate command, incomplete chunks | Shared fixtures, native/Kotlin tests |
| USB | Rejected pairing, two phones, exact device selection, unplug, desktop crash, laptop sleep, revocation, reconnect requires pairing | Native tests plus physical USB |
| Journey | Below/at/above threshold; missing airport/ground route; same airport; every phase boundary; radio unavailable/user-intervention/timeout; no blind restore | Domain fixtures and device radio checks |
| Privacy | Synthetic canary values absent from files/cache/logs/backup/tool arguments after Stop/crash/restart; app egress blocked; SDK logs suppressed | Instrumented filesystem/network audits |
| Background | Two hours screen off, Doze, battery saver, UI dismissal, revoked permissions, memory pressure, force-stop, reboot | Physical Pixel/AOSP, Samsung, restrictive OEM |
| Delivery | Compile/assemble APK; signer/install/upgrade; desktop bundle; local packs attribution; Android install-verification requirements | Build environment and target Windows/Android |

Automated test output uses synthetic data only. Production has no telemetry or diagnostic exporter. Track each check in `docs/VALIDATION.md` as PASS, FAIL, or NOT RUN with command/device/build and limitation. A compile success is not phone mock-location, screen-off, radio, third-party compatibility, desktop installer, or privacy proof. All device support claims remain conditional on recorded observations.

## Environment and release boundaries

Inventory tools before installing; use workspace-local caches/config paths where protected home directories cannot be changed. Restricted network or missing SDKs may prevent resolving real dependencies. Implement complete interfaces and source independently where possible, record unavailable builds honestly, and leave one-command build instructions for an equipped environment. Never ship fake adapters as verified platform capability. Optional dependencies fail with visible unavailable/coverage status.

ECC safety review covers manifest permissions, transport credentials, bounded parsers, archive validation, exact ADB targeting, process invocation, provider rollback, radio restoration, and memory-only handling. Final delivery lists features implemented, tests actually run, and release gates still unverified. Source and packaging can be complete while real-device qualification remains outstanding; those states must stay distinct.
