# LocalSpoof 0.8.2 — hotel browsing and Jump feedback

2026-10-05 local / 2026-10-06 UTC. Extends [105](105.md) and [the Sol 6.1 handoff](HANDOFF_0_8_SOL_6_1.md). Android `dev.localspoof.android`, version code **10**, Windows **0.8.2**, protocol **1.6** unchanged. [Public signed release](https://github.com/EvanGUTK/localspoof-releases/releases/tag/v0.8.2); source stays private.

## Defects and resulting behavior

**Hotels:** Android numbered POI pins used the generic map-selection callback, immediately staged coordinates and triggered a camera fit toward the old origin. Pins and list entries now open native details first. Only an explicit Select stages From/To/a stop; browsing, dismissal and selection retain results and camera position. Details show available provider name, category and distance from the searched center, without invented addresses, prices, ratings or availability. Selection stages a draft; Preview updates its geometry. Approved playback geometry remains independent. Explicit Fit and a new preview can still frame a route. Windows now provides the same details-before-selection behavior and keeps its camera/results.

Android's dedicated POI link is restricted to the owned map origin and exact path/query, current native RAM batch UUID and bounded index. Native confirmation captures batch, index and place and rechecks all three; stale clicks or replacement results cannot select another place. Desktop validates current result membership and current From/To/Add-stop role. Untrusted labels render as text. Neither browse nor Select starts or applies playback. No new bridge authority or persistence is added.

**Jump:** The engine intentionally retains a 120-second boarding hold before a jump flight and requires confirmed placements/radio transitions. Mobile previously exposed Jump during boarding/settling even though the engine rejected it, then displayed an inaccurate generic Resume message. The UI now uses phase-aware eligibility and explains boarding, landing, placement, airplane-mode, paused playback and active-edit waits. Boarding remaining time comes from the current runtime snapshot. Confirmation closes when eligibility changes, and a rejected tap consults fresh runtime state. Flight settings explain that Jump skips the airborne interval after departure/boarding/radio prerequisites. No engine guard or airplane requirement is bypassed.

## Verification

- Red regressions reproduced Android pin routing/camera behavior and desktop details-before-selection failures before fixes. Independent ECC code/security review approved the final change with no blocking findings.
- Final isolated Android suite: **71 domain + 214 app = 285 tests pass**, zero failures/errors/skips. Debug and release lint have **zero errors/fatal issues**, ten warnings each. Debug and production release builds succeed. Rendered Compose tests cover details/selection/staleness and Jump phase guidance.
- **170 Node tests pass**, including mobile renderer camera/POI regressions and desktop details/current-result/approved-route protection. TypeScript **6.0.3**, privacy and protocol/fixture checks pass.
- Eight runtime diagnostic cases across API 35/36 verify the actual framework provider submission calls and runtime confirmation behavior for ground/flight Jump, 120-second boarding and radio gates, Stop/failure and dwell. A minimal test shadow captures provider API arguments because Robolectric does not deliver these test-provider fixes. This measures submitted calls and app RAM state, **not OS or third-party consumption**.
- Actual production Windows/WebView2 QA: **14 checks pass** using real online hotel results in a separate disconnected 0.8.2 window. Pin/list details, explicit Select, preserved zoom/center, retained results and disabled playback passed. Layout at **1280×840 and 900×640** has no horizontal overflow, visible Select and a substantial map. Screenshots were inspected. The owned QA window was closed; the installed app was untouched. The first harness attempt failed because CDP tried to serialize the Map return value; returning a primitive corrected the harness without product changes.
- Original signing identities were retained. Publisher tooling verified package/code/SDK/non-debuggable status, Android signer, Windows version/pinned Minisign and exact signed catalog bytes. After publishing the fully uploaded draft, **32 anonymous public-release checks pass**: exact catalog/signature bytes, original RSA authentication, pinned certificate/key agreement, version/code/OS/protocol metadata, bounded HTTPS redirects, both artifact sizes/hashes and official Windows Minisign verification.

## Final artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `localspoof-0.8.2.apk` | 22,743,592 | `957045921223e198bf21289ee35808182bcbd911c9c69a941d78b24764f3e13c` |
| `LocalSpoof_0.8.2_x64-setup.exe` | 9,798,462 | `429aecd2c9eff00704cb5b24ce59a3c9f097c38b6593bca769371d96298a2f11` |

Android certificate SHA-256: `862de591bf8bf3f8ae8e3109018fb5dfed0731d775c3acef0808c0cebdf46102`. Catalog RSA and Windows Minisign identities are unchanged. Windows updater signatures are separate from Authenticode. Only the final APK/EXE/EXE signature/catalog/catalog signature were uploaded to a draft; old release assets remain immutable.

## Installed-device limits and next checks

OpenMob repeatedly reported the S24 offline during this patch. No phone installation, Stop, synthetic journey, radio change or physical-phone camera/Jump test was performed. The installed Windows app was also left intact. Both installed versions remain **0.8.1** until explicitly upgraded through Gear → Updates at safe idle after Stop and provider/radio cleanup. The complete previous 0.8.0 → 0.8.1 in-app upgrade cycle is documented in [0.8.1 validation](VALIDATION_0_8_1.md); it is not claimed as a 0.8.2 installation test.

Next physical check: use the user's chosen journey to verify the 120-second hold message, natural transition to eligible Jump, arrival placement/radio gate and hotel browse/Select camera on the S24. Do not inject an unrelated test destination. Moving Follow/screen-off, real wireless-debugging loss/reconnect, nonempty bookmark migration and third-party receipt/classification remain separately unqualified. Production screen protection stays enabled.

Ignored local evidence: `.build/107/` contains mobile/desktop red regressions, final Android test/lint reports, `node-tests-final.log`, `typecheck-final.log`, `build-release.log`, `sign-catalog.log`, `native-hotel-qa/result.json` and inspected screenshots, and public-release qualification results. These files, signing secrets and user coordinates are not committed.
