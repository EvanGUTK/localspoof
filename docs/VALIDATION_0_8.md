# LocalSpoof 0.8 validation

Date: 2026-10-05. Local evidence under `.build/105/` is ignored by Git; this report separates automated evidence from physical qualification. Android code 8; protocol minor 6.

## Automated checks

| Check | Result |
|---|---|
| Node UI/tools suite | 166 passed |
| Desktop TypeScript 6.0.3, privacy scan, protocol fixtures, assets | Passed |
| Android session domain | 71 passed |
| Android app unit tests | 187 passed |
| Android debug/release lint and assembly | Passed |
| Linux native bridge | 27 unit + 8 boundary tests passed |
| Windows shell Rust | 34 passed; one explicit online qualification test ignored |
| Windows shell/native Clippy with warnings denied | Passed |
| Vendored official updater | 5 unit + 2 doctests passed |
| Protected Windows signing identity | Official Tauri signing/pinned Minisign verification round trip passed |

Follow regressions were reproduced before changes: overlapping ACK backward movement, duplicate receipt cadence and repeated visible replay. Focused tests then passed without inventing unacknowledged movement. Native tests additionally verify code-on-stdin, private endpoint constraints, old socket rejection, strict USB radio/install boundaries, app approval credentials and owned-forward cleanup. Unit/fixture checks do not prove real network handshakes or third-party receipt.

Independent reviews across Android planner/runtime/mobile updater, desktop updater/transport and release tools caught and fixed cancellation ownership, phase-advanced drafts, station choice preservation, pending Android install recovery, pre-connect legacy socket rejection, artifact publication validation and installer launch/cleanup failures. No unresolved critical finding was reported. Official updater provenance/licenses are retained.

## Platform qualification status

The user explicitly approved Stop and an in-place upgrade of the prior Live/Arrived held flight. OpenMob stopped it, verified Idle/provider not prepared, and installed 0.8 with the original identity, without uninstalling or clearing data. A same-key private diagnostic release temporarily disabled secure capture for pixel QA; the exact production source was restored and the final secure production APK reinstalled. Diagnostic bytes/captures are ignored and were never published.

S24 pixel/accessibility QA verified the world map, visible gear/Follow, Settings → Map return, standalone online road planning with no GeoPack/desktop (0.7 mi / 2 min preview), and its road-following map geometry. Preview was discarded with Stop; no synthetic journey was started and no airplane transition occurred. Final production Settings reports Installed 0.8.0, Idle/provider not prepared, retained mph preference, and a manual public signed-feed check returned “You have the latest compatible published release.” Production screenshots are protected by FLAG_SECURE; this is expected, not evidence of a blank physical display.

Actual Windows release UI passed 23 map/route/ETA/cost/responsive checks, 7 new Wi-Fi/Updates dialog checks and 7 public manual-update checks. Its final NSIS installer upgraded Windows in place; installed EXE matches the final build and bundled companion matches the final signed APK. Owned remote-debug QA windows were closed after testing.

Public release: https://github.com/EvanGUTK/localspoof-releases/releases/tag/v0.8.0. Only the final production APK, installer, installer signature, catalog and catalog signature were uploaded. Anonymous download verification passed 32 checks: exact raw catalog authentication by original RSA certificate, platform/version/protocol/size metadata, complete artifact SHA-256, and official Minisign verification. Source remains private at EvanGUTK/localspoof.

| Final artifact | Bytes | SHA-256 |
|---|---:|---|
| localspoof-0.8.0.apk | 22,710,824 | e10f24b9a2d535f7208c10f8d1fc39d93ef68478f80491fa19ca18ec42ec7f28 |
| LocalSpoof_0.8.0_x64-setup.exe | 9,786,925 | 7085353d285d353c5c2c8dda12d109e1583a67ff92003a675d7c5b41ad0a1e25 |

Local ignored evidence: verify-android.log, native-qa/result.json, updates-ui-qa/result.json, public-update-ui/result.json, public-final/result.json, installed-desktop.json and phone-road-preview.png under .build/105.

**Remaining qualification:** moving Follow at real speeds/corners/holds/screen-off and an actual trusted same-LAN Wireless-debugging OS pairing/app-approval/reconnect/flight-loss cycle were not measured. The S24 stayed on USB/5G during this session. Future-version download → Android OS confirmation/Windows installer handoff must be exercised with a genuinely newer signed release; current-version feed checks and in-place bootstrap installs do not prove that full future-update cycle. No Life360/sensor/third-party receipt guarantee is made. Manual coordinate input can require scrolling after the keyboard opens; a focused input is not always brought into view on this Samsung keyboard configuration. Prefer map/search for normal use and include keyboard focus visibility in the next UI pass.

## Known limits

OS install-source permission and Android confirmation remain part of updates. One initial in-place upgrade adds the updater to existing 0.7 installations. Public download hosting can retain request metadata; no LocalSpoof account/journey cloud store or automatic activity logging is added. Windows updater signatures do not supply Authenticode publisher reputation.

Online road/place/station/reference coverage is provider dependent. US vehicle data does not supply verified universal tank sizes or speed-consumption curves; manual input/estimated pricing remains available. Missing posted speed limits stay unknown. A held suffix that would snap to another road/lane is rejected rather than jumping. QR pairing and scoped/link-local IPv6 are deferred. SDK owns Wireless-debugging TLS trust; preflight endpoint behavior is not hostile-peer transport attestation.
