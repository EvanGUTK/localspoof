# LocalSpoof

For the next development session, start with [104WORK — usability, gas Vacation and map fixes](docs/104WORK.md). It records the selected future scope and unresolved user-reported defects; these additions are not implemented yet.

Source and handoffs are maintained in the private [EvanGUTK/localspoof](https://github.com/EvanGUTK/localspoof) repository. Build caches, local device-test evidence under `.build/`, generated installers/APKs under `release/`, and signing material are excluded from Git. Documentation links to those local receipts work in the development workspace; the receipts are not uploaded. Keep the existing Android signing key backed up separately for compatible future updates.

Current Workmode motion update uses matching protocol minor 5. Speed gauge, smooth variable cruise through 125 MPH, optional mapped limits, tracking, flight guidance and Sensors Off help are implemented. Combined checks pass: 135 Node, 57 domain/143 Android app, TypeScript, Android lint/assemblies, Windows 26 shell/22 bridge and Clippy. Actual S24 mock-speed, 45-second cadence, brief screen-off, live edits, Pause/Resume and desktop-disconnect continuity passed. See [motion handoff](docs/MOTION_SPEED_FLIGHT_HANDOFF_SOL_6_1.md) for sensor qualifications and current artifact/installation status.

Earlier validation follows with its original scope and dates; this note supersedes earlier minor-4/current-build claims.


Android location simulation with a sideloaded APK and a Windows desktop controller. The desktop opens a worldwide map and plans Teleport, Walk, Drive, or staged Fly journeys. Android owns actual location playback, foreground operation, and phone approval. No account, cloud storage, hosted server, ads, app fees, telemetry, or automatic activity history is implemented.

Nearby categories and the compact map legend are delivered on Windows and Android. Desktop qualification passes **15 browser and 15 actual Windows/WebView2 checks**. The phone now uses a compact result peek, a dismissible results sheet and a map-key dialog. Actual S24 candidate checks confirm a visible map with eight public Chicago hotel pins, usable sheet/key controls and draft-only pin selection. Final matching 0.6 APK/Windows artifacts are built; the secure APK was installed through the rebuilt desktop bundle, with matching bytes and runtime security checks. Both components use protocol minor 4. Fresh pairing approval, playback and radio qualification remain pending.

Current Windows qualification includes **28 actual planner checks** and **five production local-save checks**. Earlier companion upgrade/installation passed; 12 real-phone approval/fresh-dot checks belong to the pre-fix build. The later blank-map report was traced to a zero-height document/map viewport despite loaded features. See [current validation](docs/VALIDATION_0_6.md) for scoped candidate results and [the historical 0.5 handoff](docs/HANDOFF_0_5_HISTORICAL.md) for earlier evidence.

## What's new in 0.6

- A dark desktop map workspace with floating worldwide search, a compact Teleport/Route dock, and bookmark, phone, and settings controls. Coordinates and advanced airport/radio options are under Journey details.
- Nearby Hotels, Food, Coffee and Shops, with Fuel, Health and Attractions under More. Numbered pins match the results list; an expandable legend explains map symbols. Results stay in RAM and selecting a place stages a draft.
- A mobile map interface with worldwide online maps/search, a bottom journey panel, and settings behind the gear. Desktop-to-Android planning remains the primary workflow.
- Phone-owned live ETA, separate walking/car/plane speeds, and explicitly named next-stop jumps. Speed edits continue from the current position; jumps retain placement and required radio gates.
- Custom walking/driving routes with ordered stops and individual stop durations.
- Explicitly saved places and route drafts, names, collections, and local JSON import/export. These are the only intentionally retained application items. Unsaved activity stays in memory; no automatic history or cloud synchronization is added.
- Airport choices restricted to 1,153 scheduled, open `large_airport` catalog entries. This is a conservative proxy for major metro/international airports and excludes Jackson regional (`MKL`); it is not a live airline or airport-certification check.

Clear preview and Stop remove current activity, while explicitly saved items remain until deleted through Saved. Loading a saved route requires a new preview and never restores approval or starts playback.

## Test Windows first

Run `release/LocalSpoof_0.6.0_x64-setup.exe`, then launch LocalSpoof. Use the floating search or select a location on the map. Choose Teleport, or Route followed by Walk/Drive/Fly, then **Preview journey**. Online map and route previews do not require a phone or geographic import. **Start on phone** requires an approved, prepared phone plan and visible phone arming.

Use the phone icon to scan USB and discover/start ADB automatically. Install the bundled signed companion if needed, open it on Android, and enable USB desktop control. Compare the six-digit number on both screens, then approve on the phone. No code typing or clipboard is required. Older companions must be updated; 0.5 and 0.6 are not a matching controller pair.

The Windows installer has **no Authenticode publisher signature**. Same-version per-user upgrade and normal installed-app opening passed. Clean installation and uninstall remain untested. Real 0.6 phone playback, screen-off/OEM operation, and radio transitions remain pending qualification.

## Online and optional offline maps

Online is the default. OpenFreeMap supplies worldwide tiles, FOSSGIS OSRM supplies desktop walking/driving road routes, and Photon or Nominatim supplies explicit place searches. Public provider coverage, availability, request limits, and logging apply. This is not a guarantee of every Google/Apple Maps search result. LocalSpoof does not host a server; online providers receive viewed map areas, queries, and submitted route endpoints.

To find nearby places, search for a city or zoom to level 9 or above, then choose a category. **Search this area** refreshes explicitly after panning. Photon supplies up to eight named results within 10 km of the map center; coverage is partial OpenStreetMap data, with no prices, hours or availability. Category finding is online only. [Category contract and review](docs/PLACES_FILTER_PLAN.md).

For offline desktop basemaps, import a local PMTiles archive, including a separately acquired planet archive. Bounded range reads avoid loading the whole file into RAM. OpenMapTiles/Protomaps vector and PNG/JPEG/WebP raster archives are supported; offline vector labels are omitted to avoid remote font/sprite requests. No planet file is bundled or downloaded automatically.

Offline roads require a separate GeoPack road graph. A PMTiles basemap is not routing data. Full-planet offline road routing and a complete standalone Android online road planner remain unfinished. [Worldwide architecture and boundaries](docs/WORLDWIDE_MAP_PLAN.md).

## Build and verification

```sh
npm run verify
cargo test --manifest-path desktop/native/Cargo.toml --locked
cargo test --manifest-path desktop/src-tauri/Cargo.toml --locked
```

Current desktop/source checks pass: **115 Node tests (103 desktop + 12 tools)**, strict TypeScript 6.0.3, privacy/protocol checks, Windows native **20** / Linux native **28**, desktop shell **22**, and Clippy. Android **170 tests (50 domain + 120 app)**, both APK assemblies and debug/release lint pass. Final original-signer and trusted-companion packaging/installation checks pass. Isolated browser rendering passes **33 checks across six viewports**, including compact live-control layout; this is not physical playback evidence.

The bundled SDK assets allow UI builds with Node 24+. Regeneration uses `npm ci --ignore-scripts --no-audit --no-fund` followed by `node tools/build-map-vendor.mjs`. Strict checking uses an installed TypeScript or `LOCALSPOOF_TYPESCRIPT_PATH` pointing to `typescript.js`. Rust dependencies must be cached or accessible through the network.

- [Current build status](docs/BUILD_STATUS.md), [0.6 validation](docs/VALIDATION_0_6.md), and [code/security review](docs/V06_CODE_SECURITY_REVIEW.md)
- [Local distribution](docs/DISTRIBUTION.md) and [Windows toolchains](docs/WINDOWS_TOOLCHAINS.md)
- [Sol 6.1 handoff](docs/BUILD_HANDOFF_SOL_6_1.md), [architecture](docs/ARCHITECTURE.md), and [protocol](protocol/README.md)

Android's mock-location Developer option is required; root is not required. Mock locations remain detectable. Life360/fused-location compatibility and sustained device behavior are unverified. Public providers, Android, ADB, and OS/SDK state are outside the application's no-automatic-history design. Source is stored in the private repository; no application backend or public distribution is hosted.

Wi-Fi control remains [roadmap only](docs/WIRELESS_CONTROL_ROADMAP.md); this release uses physical USB.
