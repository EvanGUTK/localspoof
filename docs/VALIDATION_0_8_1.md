# LocalSpoof 0.8.1 — idle phone location and updater qualification

2026-10-05. Extends [105](105.md) and [the 0.8 handoff](HANDOFF_0_8_SOL_6_1.md). Android `dev.localspoof.android`, version code **9**, Windows **0.8.1**, protocol **1.6** unchanged. [Public signed release](https://github.com/EvanGUTK/localspoof-releases/releases/tag/v0.8.1); source remains private.

## Defect and resulting behavior

Before this patch, genuine phone fixes were collected only by `SimulationService`. Standalone planning before playback therefore had no phone dot/location and an empty airport-journey origin, even with a usable genuine GPS fix. A failing activity regression reproduced that condition before implementation.

`MainActivity` now owns a visible-screen location reader when the playback service is absent. It reuses `DeviceLocationSampler` and `PhoneLocationState`: precise permission and device Location must be enabled; mock, stale, invalid and out-of-order fixes remain rejected. Location and reader ownership stay in RAM. Stop, background and destroy close the reader; service takeover and installation reservation reject old callbacks. Playback retains its own source and authority.

An untouched empty non-Instant origin receives both coordinates from a fresh genuine fix. Manual, saved, prepared, busy and remaining-route origins are protected. Explicit **Use phone location as origin** remains available, invalidates an obsolete preview and supports permission, location-off, waiting and retry states. A subsequent manual edit rejects a delayed requested fix. This reader neither starts a simulation nor acquires mock-provider authority.

## Automated and release checks

- Red regression reproduced; **25 focused tests pass** after the final edits. Independent code/security review approved the change with no blocking finding.
- Full isolated Android verification: **71 domain + 194 app tests pass**, no failures/errors/skips. Debug/release lint: **zero errors/fatal issues**, ten warnings each. Debug and production release APKs build successfully.
- **Five Node update/catalog contract tests pass.** Windows production MSVC/Tauri/NSIS build succeeds with the matching signed companion APK. This patch changes Windows versions/companion, not updater logic; the full previous Windows suite remains recorded in [0.8 validation](VALIDATION_0_8.md).
- Publisher tooling verifies the production APK package/code/SDK/non-debuggable status/original signer and Windows installer version/pinned Minisign before signing exact catalog bytes. Original signing identities and feed URLs remain unchanged.
- **32 anonymous public-release checks pass**: exact catalog/signature bytes, original RSA authentication, pinned certificate/public-key agreement, version/code/OS/protocol metadata, bounded HTTPS redirects, both final artifact sizes/hashes and official Windows Minisign verification. An initial qualification request saw the previous correctly signed release through a cached latest redirect; a subsequent request using the qualification user agent authenticated 0.8.1. Actual old clients independently found 0.8.1.

## Actual 0.8.0 → 0.8.1 update test

Both installed production **0.8.0** apps were kept intact until the new release was published. No direct APK installation, manual installer launch, uninstall, data clearing or replacement key was used for this upgrade test.

**Windows:** actual installed app → Gear/Updates → Check → Download → Install. Six check/download assertions passed, including old-version identity, opt-in download policy and authenticated staged release. The official installer replaced the installed executable with the exact reviewed 0.8.1 build, installed the matching companion APK and restarted the installed app. Its UI then reported `Installed desktop version · 0.8.1`, `You have the current published version.` and `No phone connected`. Temporary loopback QA flags/profile were removed and the installed app was reopened normally.

**S24 Ultra / Android 16 / USB:** OpenMob observed the installed 0.8.0 app Idle with provider not prepared. Check found 0.8.1; the app downloaded and verified it. Android requested per-app install-source permission, then its normal update confirmation. A first automated tap opened the Play Protect help link and canceled installation; LocalSpoof retained 0.8.0, reported cancellation and allowed download/install retry. The retry completed through the same native updater. After reopening, Settings reported `Installed 0.8.1` and a fresh Check reported the latest compatible published release. No global Play Protect setting was disabled.

**Origin check on the updated phone:** choose Journey options → Airport journey. Both origin inputs populated with valid coordinates, and the UI showed `Device location · 0 s old`, `Idle · Phone control` and `Provider: not prepared`. The phone had not connected to Windows or started playback. Coordinates were excluded from validation records. This verifies fresh genuine location and automatic blank-origin fill before any mock activity; it does not claim visual map-pixel inspection under production `FLAG_SECURE`.

No journey was started or resumed. Same-package in-place upgrades avoided clearing saves; a nonempty user bookmark dataset was not separately compared before/after this run. Existing save compatibility tests passed.

## Final artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `localspoof-0.8.1.apk` | 22,710,824 | `1ca92759d6b8a6d84701c9e32025f15d6b9b7c9d7053865ea77ae9a0d193cec1` |
| `LocalSpoof_0.8.1_x64-setup.exe` | 9,793,225 | `9b5b9371dc418e0dd642221ab1d6510d298d984bacc686646c83326795c951df` |

Android certificate SHA-256: `862de591bf8bf3f8ae8e3109018fb5dfed0731d775c3acef0808c0cebdf46102`. The RSA catalog and separate Windows Minisign identities are the original ones. Windows updater signing is separate from Authenticode certification. Old release assets remain immutable.

Ignored local evidence under `.build/106/`: `origin-red.log`, `origin-final.log`, `android-final.log`, isolated test/lint reports, `update-contracts.log`, `build-release.log`, `sign-catalog.log`, `public-final/result.json`, `update-from-0_8/download-result.json`, `windows-installed-result.log`, `update-from-0_8/post-install.json`, `phone-update-result.json`. Raw device coordinates, user notifications and signing secrets are not added to this report or Git.

## Remaining qualification

Location acquisition still depends on Android permission, enabled Location and a fresh real fix; no coordinate fallback is invented. Full moving Follow/high-speed/screen-off qualification, real Wi-Fi-debugging pairing/loss/reconnect and third-party location receipt/classification remain separate checks from 0.8. This patch verifies the complete safe-idle updater cycle on these two installed apps, not every OS, cancellation stage or nonempty bookmark migration.

For the next public release, upload the reviewed explicit assets to a **draft**, then publish/set latest after upload completion. Keep signing identities, protocol bounds and Android version codes consistent. Authenticate both old clients' manual checks and final anonymous assets before reporting installation success.
