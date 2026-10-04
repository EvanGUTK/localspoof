# LocalSpoof 0.6 implementation plan

The 0.5 APK and desktop are the verified baseline. The user authorized implementation of the prior live ETA/speed/next-stop proposal plus a map-first desktop and phone interface. Desktop-to-Android remains the primary control path. Wi-Fi control and fully standalone Android world routing remain later work.

## User flows

The main screen is a dark map with mint controls. Name, saved-items button and settings gear sit above a worldwide search field. Two primary choices are Teleport and Route. Teleport picks a place and previews placement. Route exposes Walk, Drive and Fly, From/To places, a route summary and advanced journey options. Exact coordinates and airport overrides move into expandable details. Start still requires explicit phone consent; Stop stays reachable outside scrolling. Pending pairing comparison/Approve/Deny is presented prominently without requiring a trip through hidden settings.

Phone uses the same map vocabulary: road lines, dashed illustrative flight connection, endpoints and the fresh real/device or submitted simulation dot. Its map receives actual current phone runtime preview and location state; it does not manufacture movement from estimated times. Gear owns connection setup, permissions, units, provider selection/disclosure and advanced resources. Existing accessible forms remain reachable in settings/journey options. The phone can search and select places while Windows continues to supply online road planning. Do not describe search/map access as a completed standalone worldwide route engine.

Playback shows remaining duration and arrival estimate. Waiting for manual radio action and Pause are named states rather than precise arrival promises. Walking, ground driving and air speeds are separate values. Authenticated live changes preserve current path position, update future duration/ETA and advance revision exactly once. Jump to next stop names its target and preserves provider-placement acknowledgement and required airplane/restoration barriers. Flight mode may skip timed waiting; it cannot bypass consent, restoration or Stop ownership.

## Privacy and local saves

The user explicitly answered that saved locations/routes should persist locally with no cloud. Only an explicit Save writes a validated immutable draft/route copy; no automatic history. Never serialize current GPS, credentials, comparison values, session/revision ownership, radio state, counters or provider callbacks unless a coordinate was intentionally part of the selected saved place/plan. Saved items have user names and optional folders, local rename/delete, export/import and clear-all. Import is bounded and strict, and loading a saved item only stages a new preview; it never starts or reconnects. App-private storage is excluded from backups; exports require an explicit document picker. Activity otherwise remains RAM-only.

## Mobile renderer and networking

Use Android WebView as a display-only renderer with locally bundled, integrity-recorded MapLibre JS/CSS/worker assets from the desktop vendor build. Serve only known packaged paths under a synthetic HTTPS app origin. CSP denies navigation/forms/remote scripts and allows only bundled workers/known synthetic resource paths. No addJavascriptInterface, file/content URL access, DOM storage, cookies, downloads, external intents or remote HTML. Native pushes a bounded escaped JSON map-state snapshot through evaluateJavascript; the renderer cannot start/pair/change speed/Stop or call arbitrary native methods. A narrow map-tap return may use a fixed app-origin, bounded coordinates message through WebViewClient only; reject all other navigation.

Map requests are intercepted and mapped to fixed HTTPS OpenFreeMap paths. Phone search uses a separate strict Photon adapter; Windows offers Photon, Nominatim and Off. Searches are submitted explicitly. HTTPS redirects are bounded/revalidated or refused, bodies/coordinates/result counts have caps, timeouts/rate limits apply, no URL strings or provider HTML are surfaced as executable content. Provider attribution/disclosure remains visible. Add INTERNET permission for this feature, keep cleartext disabled. WebView network loading/storage/history caches are disabled; native map cache is bounded RAM only and owned by the visible map lifecycle. Destroy cancels requests, clears current state and releases renderer. No WebView console logging or debugging in production. This is an application activity boundary, not a claim that Android/provider infrastructure keeps no records.

MapLibre Native was considered: its Android ambient/offline database makes additional disk-cache auditing necessary. Reusing the already audited packaged JS renderer is the narrower 0.6 change. Android documents serving packaged HTTPS-origin content and restricting JavaScript bridges; follow these interfaces without adding a generic bridge.

## Airports

Default departure and arrival use scheduled, open `large_airport` catalog entries only. Medium fields such as Jackson TN are excluded from automatic choice and overrides. This conservative major-hub rule is mirrored in Android and desktop; 1,153 current catalog entries qualify. The source has no authoritative metro classification, so the interface calls them major airports rather than inventing international metadata. Validate every ID and catalog point at the phone boundary and never imply live airline operating data.

## Implementation ownership and order

1. Root native/Windows owner: save storage, narrow IPC, protocol/capability version, packaging/build, signed companion trust, native review and actual hardware QA.
2. Runtime Android/domain owner: validated speed/ETA/next-stop mechanics, command lease/revision barriers, airport policy, protocol fixtures and MainActivity dispatch integration. Define MobileUiState/event contract before UI edits.
3. Mobile/UI owner: map-first MobileUi/MobilePresentation/MobileSessionState, display-only WebView assets/native bounded provider adapter, explicit Android saved-item store/actions, responsive rendered tests; coordinate MainActivity wiring with runtime owner.
4. Desktop UI owner: map-first rail/sidebar, Teleport/Route/search/settings/saved-items, staged stops, live controls, renderer style and provider routes.
5. Independent final review: approved/private boundaries, import/storage/map policies, stale callback cancellation, playback phases, restored settings and protocol compatibility. Root records validation in current handoff.

## Acceptance checks

- Teleport/Route and gear/bookmark controls fit 320 dp phones, landscape, tablets and large font; every essential action is reachable and Stop remains outside scrolling. Fresh pairing still works and enabling twice preserves the service-owned USB listener.
- Road/air/instant segment previews and DEVICE/SIMULATION dots use runtime snapshots. Unknown/stale fix is visibly unavailable. Map loading failure leaves native controls usable. Worldwide search is bounded/provider-disclosed and pressing search does not start playback.
- Different walk/car/air speeds update ETA without changing position or bypassing barriers; pause/wait/skip/Stop cases are tested in domain and authenticated dispatcher tests.
- Jackson TN is excluded from automatic airport selection; nearest eligible major/international choice is mirrored and catalog IDs cannot be forged.
- Save/reload survives a process restart only after explicit Save. Load never restores authority. Oversized/malformed import rejects atomically, exported data has no runtime credentials, Delete/Clear remove persisted items. Draft edits do not mutate saved copies.
- Native networking/storage and production WebView settings have meaningful regression tests; old privacy checker claims about no INTERNET/no persistence are revised to the exact opt-in save and explicit map/search boundaries.
- Kotlin/Node/TypeScript/native tests and lint pass, original signing identity preserved, compatible APK and Windows installer delivered with honest hardware qualification limits.

## Primary references consulted

- Android packaged web content: https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader
- Android WebView bridges: https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges
- MapLibre Android ambient cache: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-manager/set-maximum-ambient-cache-size.html

## Implementation and verification record

Mobile source is implemented with packaged display-only map assets, strict fixed-provider outbound map/search, default Map screen, gear/bookmark controls, compact search, Teleport/Route sheet, runtime live geometry/dot/ETA/speed/jump controls, and explicit app-private saved copies. The portable version-1 saved format accepts at most 100 items/512 KiB, retains per-stop waits/custom paths/boarding hold and original imported USB policy/defaults on re-export. Phone-local loading of a Windows USB policy uses disclosed manual radio steps and never restores a connection or controller authority. Document export uses the document provider the user explicitly chooses; it cannot promise that an external document provider is local-only.

Root's final corrected Android run reports 158 tests (50 domain/108 app), no failures/errors/skips, and successful debug/release lint and assemblies. Mobile test breakdown: MapPolicy 12 across API 29/36, SavedStore 4, rendered layout/action cases 17, Presentation 7 and ActivityPrivacy 5. Node syntax check of packaged map module passes. These checks validate finite layout and native settings/data boundaries; Robolectric does not qualify real WebGL tile rendering. Actual signed-device map/playback, complete radio transitions and long USB/background stability remain separate hardware work.

Independent runtime/protocol source review is in [MAP_FIRST_V06_RUNTIME_REVIEW.md](MAP_FIRST_V06_RUNTIME_REVIEW.md). Source integrity for phone packaged map assets is recorded in `android/app/src/main/assets/mobile-map/vendor/manifest.json`; upstream MapLibre license accompanies those assets. No new Android dependency was added.

### Physical map rendering correction

The first signed S24 Ultra test exposed a blank map and an invisible settings glyph. An isolated Android WebView 153 diagnostic, using public Chicago coordinates without GPS permission or controller authority, reproduced two rendering faults. Immediate acquisition of four network permits rejected 22 of 32 startup resources and left zero visible map features. Separately, the first native map snapshot arrived before the style was ready and was never replayed: the camera stayed at zoom 1.5 and its marker source was absent.

The native map adapter now queues fairly behind four active requests, with at most 32 waiting requests and a 12-second queue deadline. Active HTTP response reading has its own 12-second deadline and existing byte/path/encoding limits; queued and active deadlines are separate. Closing the map cancels queued work and releases acquired permits. WebView's direct network loading remains blocked. The renderer retains a dirty snapshot and retries on idle once the style can accept it, then marks that revision rendered to avoid repeated GeoJSON updates. Packaged startup health displays only fixed error categories without coordinates, URLs or logging. Settings, bookmark and search controls use drawn vector icons; the gear has an explicit mint outline.

The corrected physical diagnostic loaded all 48 requested resources with no rejection or console error, rendered 1,740 features, applied initial zoom 15 and created the marker source. Root recorded its public-data receipt in `.build/global/v06-map-physical-result.json` and removed the isolated diagnostic APK afterward. This qualifies the actual Android renderer pipeline; production journey playback, radio transitions and long background/USB stability still need their own hardware checks.

Focused correction checks passed: 12 map policy tests across API 29/36 and 17 rendered layout tests, including queued-burst handling and visible mint gear pixels at normal/enlarged font sizes. The permanent `tools/tests/mobile-map.test.mjs` regression passes and checks that an early native snapshot renders once after style readiness without an idle redraw loop. Full Android lint, assemblies and regression tests are being repeated before the corrected signed APK and its desktop trust binding are packaged.

### Production UI viewport qualification

The isolated renderer was insufficient to qualify the production Compose screen. A subsequent user report prompted authorized screenshots and a temporary diagnostic build of the actual app, using the same package and source with capture/debugging enabled only for that test. The installed release hash and foreground activity were verified first. Actual WebView inspection found a 384 × 628 CSS-pixel window while `html`, `body` and the map container had zero computed height; its canvas retained the pre-layout 300-pixel default. Even CSS viewport units resolved to zero in that affected WebView. Explicit pixel dimensions immediately restored the real phone map and header.

The final renderer reads bounded, finite native window dimensions and sets explicit pixel sizes on its root/body/map container before resizing MapLibre. Resize events coalesce and unchanged dimensions cause no further resize. AndroidView now creates the WebView in its factory and releases it through `onRelease`, clips it to its actual bounds and uses a dark native background. The packaged page starts only after attachment and nonzero native layout. CSS provides a fixed map and viewport-height fallback as well. Policy tests verify startup gating with Robolectric's fake provider frame supplied explicitly; the permanent VM regression verifies zero CSS height repaired from a valid window height and checks that resize and idle callbacks do not loop.

The exact-code candidate on the S24 displayed the public Chicago route/markers, a 628-pixel map canvas and 1,558 features. The gear menu stayed visible while the map remained rendered; Settings opened its unit controls. Returning to Map created a fresh renderer and displayed the worldwide map without any injected layout or map state. Production screenshot protection and disabled WebView debugging were restored afterward. Root's repeated full Android run passed 160 tests (50 domain/110 app), both assemblies and debug/release lint. Focused mobile checks passed 14 policy tests and 17 rendered tests. Final secure installation, keyboard/back checks and desktop APK trust rebinding are packaging/hardware steps; long background/USB playback and full radio transitions remain separately qualified.
