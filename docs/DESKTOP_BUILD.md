# Desktop build and local verification

LocalSpoof uses dependency-free TypeScript/canvas assets and a Rust/Tauri Windows shell. Node 24+ strips TypeScript for asset building; strict typechecking is a separate developer check. The native core has its own Cargo.lock. Actual Windows shell compilation, Clippy, locked release build, NSIS packaging and native WebView2 launch now pass; evidence is in [VALIDATION.md](VALIDATION.md).

## Source checks

```sh
npm run verify
npm run typecheck
cargo test --manifest-path desktop/native/Cargo.toml --locked
```

Strict checking requires a developer TypeScript installation or `LOCALSPOOF_TYPESCRIPT_PATH` pointing to `typescript.js`; no compiler is bundled. The current developer copy is under ignored `.tooling/typescript`. The Node test runner's test-isolation option retains the previously compatible synthetic harness settings.

`npm run preview` serves bundled assets on loopback and cannot control a phone. Actual Windows Chrome/Chromium preview QA passed 25 checks at four sizes, with screenshots and an accessibility tree. The release Tauri/WebView2 app passed 11 additional native smoke checks, including a harmless rejected command. Neither test exercised real USB or mock injection.

## Windows packaging

The installed Rust MSVC, Node, C++ Build Tools/Windows SDK, WebView2 and Tauri CLI are listed in [WINDOWS_TOOLCHAINS.md](WINDOWS_TOOLCHAINS.md). From a newly opened PowerShell:

```powershell
Set-Location 'E:\Codex\Location Spoofer'
.\tools\build-desktop.ps1
```

That command disables APK installation. To permit installation of the exact reviewed signed release APK:

```powershell
$Certificate = Join-Path $env:LOCALAPPDATA 'LocalSpoof\signing\localspoof-release.cer'
$Fingerprint = (Get-FileHash -LiteralPath $Certificate -Algorithm SHA256).Hash
.\tools\build-desktop.ps1 -VerifiedSignedApk '.\release\localspoof-0.1.0.apk' -ExpectedSignerSha256 $Fingerprint
```

The script checks one matching signer, package/version, non-debuggable status and bounded hash while holding a Windows read handle that blocks writes/replacement. The runtime reads/hashes once and streams those same bytes to the selected device. The current installer is bound to the signed APK recorded in `release/artifacts.json`; changing that APK requires another desktop build.

Tauri 2.9.5, tauri-build 2.5.3, runtime 2.9.2, macros/codegen 2.5.2 and utils 2.8.1 are pinned together. The first actual resolver otherwise selected newer internal APIs incompatible with the older shell. The generated `desktop/src-tauri/Cargo.lock` is retained; the builder passes `--locked` to Cargo through Tauri's runner arguments. Update this family deliberately together. NSIS output is `desktop/src-tauri/target/release/bundle/nsis/LocalSpoof_0.1.0_x64-setup.exe`; the current copy is in `release` and has no Authenticode publisher signature.

## Runtime and remaining qualification

Each session the operator supplies the absolute path to official Platform Tools `adb.exe`. The bridge checks version output, pins the server to loopback, verifies the chosen USB transport and scopes commands to one serial. Credentials/routes remain in memory; only its own forward is removed. ADB/OS authorization keys and diagnostics are outside that design. USB debugging and separate phone pairing approval are required; the APK can be non-debuggable.

The main webview uses incognito, bundled assets, a strict CSP, narrow native commands, disabled devtools and no generic shell/filesystem/clipboard/updater/log plugins. Native operations run away from the webview thread. A test-only launch environment enabled CDP for smoke inspection and is not persisted or part of production config.

Installer install/uninstall/upgrade, actual USB selection/pairing/install, background phone playback, phase-specific radio operations/Stop recovery, and filesystem/crash/backup/network canary audits remain pending. Incognito and local page traffic alone cannot prove runtime privacy. Windows x64 is the built target; macOS/Linux packaging is unqualified.
