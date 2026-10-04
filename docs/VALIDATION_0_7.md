# LocalSpoof 0.7 validation

Date: 2026-10-04. Evidence is scoped to this release and does not carry forward physical journey qualification from 0.6. Local receipts under `.build/104/` are intentionally ignored by Git and are not available in a fresh clone.

## Automated checks

| Check | Result |
|---|---|
| Node UI/tools tests | 152 passed |
| Privacy source scan and minor-6 protocol fixtures | Passed; source/fixtures are not a runtime privacy or transport audit |
| Desktop TypeScript 6.0.3 and asset build | Passed |
| Android session domain | 71 passed |
| Android app unit tests | 153 passed |
| Android debug/release lint and APK assembly | Passed |
| Windows shell Rust | 28 passed; one explicitly opt-in live-network test ignored |
| Windows native bridge | 15 unit and 8 integration tests passed |
| Windows shell Clippy with warnings denied | Passed |
| Development GPS/network/Fused observer | Debug APK assembly and lint passed; not run on the phone |

Independent domain/mobile/desktop review covered remaining-edit ownership and stale work, completed-stop preservation, fuel corrections, exactly-once stop accounting, radio/timer ownership, approved versus candidate geometry, local-save boundaries and cross-platform decoding. Review findings were corrected before packaging. A final idle-status text correction passed TypeScript and 48 focused UI tests; it does not change protocol or playback.

Local receipts: `node-final.log`, `android-full.log`, `windows-final.log`, `probe-build.log`, `status-fix.log`, and `package-desktop-final.log`.

## Observed Windows UI

The actual Windows/WebView2 release and browser preview each passed 23 checks: worldwide map rendered, Settings opened with contained focus and returned to a rendered map, disconnected playback remained disabled, explicit partial starting fuel and manual vehicle fallback were available, a real public-road preview returned multiple routed vertices with ETA/budget, and four viewports retained usable controls without horizontal overflow. Viewports: 1280×840, 900×640, 390×844 and 844×390. The two narrow cases are responsive layout emulation; the native window's production minimum is 900×640.

QA used public Nashville street endpoints only for disconnected desktop previews. No preview was started on the phone. A provider `NoSegment` response exposed unclear road-endpoint guidance; native/browser bounded error handling now explains selecting a public-road point. Checkbox labels and a small clear button were enlarged. The viewport assertion allows one pixel of CSS subpixel rounding.

Receipts/captures: `.build/104/native-qa/` and `.build/104/browser-qa/`. Captures verify the rendered map, not just tile requests or source presence. The final text-only idle-status correction was verified again on an authenticated physical USB connection.

## Observed Samsung S24 Ultra

OpenMob saw the physical SM-S928U, Android 16, over USB. The signed 0.7 production APK installed successfully. A same-package/same-signer isolated diagnostic candidate showed actual worldwide map pixels, visible Follow/Fit controls and a usable map after Settings → Map. Production deliberately blocks screenshots; UI tree confirms its Map/Follow controls, and the exact secure production APK was reinstalled after diagnostic capture. The diagnostic candidate was never bundled or published.

The new desktop discovered/started the installed ADB tool, scanned the authorized USB phone, requested comparison-number approval, and connected after the matching number was approved through OpenMob. Phone UI showed approval; desktop showed the authenticated ready state. Closing the owned QA controller returned phone UI to awaiting approval. No Start, mock-location injection, active route, radio toggle, sensor changes, app-data clearing or unrelated location was performed. Moving Follow/line alignment and active route edits are covered by regressions but remain pending physical qualification.

USB recovered after host/backend/interface troubleshooting and user reconnection. The sequence does not isolate a unique cause or prove sustained stability. Healthy Windows Samsung composite/ADB/MTP enumeration and subsequent OpenMob connection were observed; no global USB controller or driver was replaced.

## Release identity and remaining limits

Android: `dev.localspoof.android`, 0.7.0/code 7, non-debuggable release. Public certificate SHA-256: `862de591bf8bf3f8ae8e3109018fb5dfed0731d775c3acef0808c0cebdf46102`.

Production APK SHA-256: `74c3c77bad416c8bf461239215dcba99a43e46eb8a24cf3971c9091d241caca1` (22,514,144 bytes). Installation used this exact local file through OpenMob; this session did not pull/hash the installed phone package independently. Signing/zip alignment and trusted companion verification passed during packaging.

Windows installer SHA-256: `3ca81ab99e4398d7c5c9246932b52214ca506efdc00965d57e9f38730d49d495` (9,484,733 bytes). Silent installation completed successfully. The installed desktop executable matches the freshly built executable byte-for-byte, and the installed bundled companion matches the signed production APK. Windows executable remains Authenticode `NotSigned`. Receipts: `.build/104/installed-desktop.json`, `.build/104/usb-connection.json`, `release/artifacts.json`, and `release/SHA256SUMS.txt`.

Actual airplane-mode transitions, GPS/network/Fused consumer delivery after landing, Life360 refresh/classification, high-speed routes, phone-owned playback across physical USB loss, long-route automatic stations, hotel/fuel holds and screen-off/OEM behavior still require the user's chosen controlled journey. The observer APK is ready for that investigation. No guarantee of another app's acceptance or flight-history suppression is made. EV, sensor spoofing, production wireless control and standalone Android online road planning remain deferred.
