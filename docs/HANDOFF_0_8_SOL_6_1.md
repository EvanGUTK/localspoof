# LocalSpoof 0.8 handoff for Sol 6.1

Implements [105](105.md) on the 0.7 baseline. Android version code 8, package `dev.localspoof.android`; Windows 0.8.0; protocol minor 6 unchanged. Read [validation](VALIDATION_0_8.md) for the exact scope/evidence and remaining physical checks.

## Architecture and behavior

- Phone remains the single journey/provider/ledger authority. Windows supplies an optional approved controller over USB or Android authenticated Wireless debugging. Connecting must never replace/start a journey.
- Mobile motion continues interrupted interpolation from the displayed point, uses genuine sample cadence, handles visibility idempotently and drives dot/Follow from the same position. Map bridge geometry/viewport work is coalesced. RAM-only `mobileMapDiagnostics` measures timing/counts without coordinates/history.
- `MobileOnlinePlanner`, `MobilePlanningNetwork`, `MobileVehicleCatalog` add native online road/fuel/eligible-airport/US vehicle-reference planning; `MobileSessionState` fences canceled/stale/phase-advanced work. Remaining Apply still requires a current runtime-owned edit and confirmed held point. A road-provider snap >0.1 m from that held point fails without moving the journey; do not silently create a connector or snap an acknowledged fix.
- Saved draft options now optionally carry `planningSource` and `automaticFuelStops`; older records remain compatible. No controller authority, activity ledger or credentials are persisted.
- Native `wireless.rs` validates private literal endpoints, bounded modern mDNS hints, six-digit OS code via stdin, CNXN/STLS endpoint preflight and Android TLS-port/physical-serial metadata. SDK owns TLS/workstation trust; LocalSpoof comparison approval remains separate. A preflight is not hostile-peer cryptographic transport attestation. USB install/radio validation remains strict; Wi-Fi flight uses phone reminders. No silent failover/daemon kill/generic shell IPC. Unscoped ULA IPv6 supported; scoped/link-local IPv6 and QR setup deferred.
- `MobileUpdates` and Windows `updater.rs` authenticate a fixed signed catalog, verify bounded artifact downloads, and stage explicit in-place upgrades. Android PackageInstaller always supports install-source permission/OS confirmation. Native runtime installation reservation blocks Start/Resume/Apply/late service attachment until completion/cancel; OS pending-session recovery fails closed. Radio/provider recovery is observed, not assumed.
- Windows uses the official Tauri updater with a small vendored verified-release factory, no logging, checked ShellExecute launch and owned-stage cleanup. Read `desktop/vendor/tauri-plugin-updater/LOCALSPOOF_PATCH.md` before library upgrades. No plugin JavaScript capabilities are enabled; update IPC accepts no user URL/key/manifest/binary.

## Release trust and publication

Source stays private at `EvanGUTK/localspoof`. Public binaries/catalog live at `EvanGUTK/localspoof-releases`. Fixed feed: `https://github.com/EvanGUTK/localspoof-releases/releases/latest/download/catalog.json`; detached signature `catalog.json.sig`.

Original Android release certificate SHA-256: `862de591bf8bf3f8ae8e3109018fb5dfed0731d775c3acef0808c0cebdf46102`. Its public certificate is bundled on Android and RSA public DER on Windows. Catalog uses standard RSA-SHA256 over exact UTF-8 bytes; Windows artifact additionally uses pinned Minisign. Windows updater signing is separate from Authenticode certification.

Original JKS/password and separate Windows updater key/password live outside Git under `%LOCALAPPDATA%\LocalSpoof\signing`. Credentials are Windows CurrentUser DPAPI protected. Never replace an established identity, print secrets, commit them, or embed a GitHub token. A laptop clone contains source/public keys; publishing from another machine requires securely restoring the original signing identities/passwords. DPAPI files alone are tied to the originating Windows user/environment. Preserve encrypted off-machine backups separately.

For the next release: increment workspace/Cargo/Tauri/Android version and Android code consistently; update release-version pins in build/sign scripts. Build/test both, sign Android with `tools/sign-android.ps1`, build matching Windows companion with `tools/build-desktop.ps1`, then run `tools/sign-update-catalog.ps1` against final stable APK/installer/notes. It re-verifies artifact identity/signatures/version and signs metadata; it does not publish. Keep protocol compatibility metadata accurate if changing protocol minor 6.

Publish only explicit reviewed APK/EXE/EXE.sig/catalog/catalog.sig/notes filenames to the public repository under the version tag; do not upload directories, private source, raw captures, keys, or diagnostic APKs. Keep old release assets immutable. Verify anonymous feed redirects, catalog signature and artifact signatures/hashes after publishing. Git pushes alone do not update installed binaries.

One initial in-place bootstrap is required for existing 0.7 installations. Future releases use Gear → Updates, without uninstalling or repeating setup. No automatic Start, background polling, code hot-loading or silent universal Android install promise.

## Qualification rules

Use OpenMob for physical phone QA: list devices, screenshot + visible tree, act, verify screenshot, wait for elements. Never inject an unrelated synthetic journey into the user's daily phone. The prior Live/Arrived held flight was stopped with explicit user approval before the in-place upgrade. For future sessions, observe current state and do not install over an active/held journey without explicit Stop. Preserve bookmarks/mock-app selection and restore the final secure production APK after any diagnostic build. Follow's full moving phone/desktop qualification needs a user-chosen route, fresh sample evidence, corners/holds/Settings return/rotation and high-speed flight. Third-party receipt, sensors and Life360 classification are separate and unguaranteed.

## Current release and next qualification

0.8 is installed on the Windows machine and S24 and published at https://github.com/EvanGUTK/localspoof-releases/releases/tag/v0.8.0. Both actual apps authenticated the public feed and reported current. Final secure APK was restored after private diagnostic pixel QA. Read validation for final hashes. No test journey was started.

Next: qualify a user-selected moving journey for Follow/frame cadence/corners/holds/screen-off; perform Wireless-debugging pairing/app approval on a trusted same LAN and measure loss/reconnect; use a genuinely newer signed release to qualify each complete updater installation cycle. Improve native coordinate-input focus scrolling with the Samsung keyboard without expanding the map overlays. These are physical qualification/UI followups, not measured results in this release.
