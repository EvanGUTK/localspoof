# Windows map and planner UI

TypeScript/HTML/CSS with locally bundled, pinned MapLibre, PMTiles and Protomaps renderer modules. The default map covers the world through OpenFreeMap; explicit walking/driving route previews work without a phone. Native Rust supplies fixed-provider HTTPS resources and routes. Production CSP permits app/IPC resources only. The browser preview has development-only direct-provider fallbacks.

`src/app.ts`: UI, cancellation, preparation/start/Stop and paired lease heartbeat. `src/world-map.ts`: renderer, bytes-vs-JSON protocol decoding, markers/date-line route, dirty-state rendering after tiles finish loading, archive lifecycle and bounded file reads. `src/geography.ts`: normalized road/search/airport adapters and optional local GeoPack routing. Coordinate payloads contain only lat/lon; airport identity is passed separately.

Offline selection makes no provider requests. Imported planet PMTiles provide basemap coverage; local GeoPacks provide separately bounded road graphs. Full-planet offline routing is unfinished. Local vector styles omit remote labels/icons. A map preview does not arm phone playback or control radios.

Build with `node tools/build-ui.mjs`, inspect through `node tools/preview.mjs`, test with `node --test desktop/ui/tests/*.test.mjs`. UI build uses bundled SDK files; SDK regeneration/types need pinned npm dependencies. See [worldwide design](../../docs/WORLDWIDE_MAP_PLAN.md) and [validation](../../docs/VALIDATION.md).
