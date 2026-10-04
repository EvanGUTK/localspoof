# Windows toolchains installed — 2026-10-02

The authorized setup installed the build tools on this Windows account. JDK/Gradle/SDK/Rust paths were merged into the user PATH without replacing its existing entries. JAVA_HOME, GRADLE_HOME, and ANDROID_HOME are Windows user variables. Open a new PowerShell window to inherit them.

| Tool | Installed version/location |
|---|---|
| Temurin JDK | 17.0.20.1+1; `%LOCALAPPDATA%\LocalSpoof\toolchains\temurin-17\jdk-17.0.20.1+1` |
| Temurin test JDK | 21.0.12.1+1; `%LOCALAPPDATA%\LocalSpoof\toolchains\temurin-21\jdk-21.0.12.1+1`; isolated Android rendered-test worker |
| Gradle | 8.13; `%LOCALAPPDATA%\LocalSpoof\toolchains\gradle-8.13` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`; platform 36, Build Tools 35.0.0, Platform Tools 37.0.1, Command-line Tools |
| Android Studio | 2026.2.1.8; `%LOCALAPPDATA%\LocalSpoof\toolchains\android-studio\bin\studio64.exe`; desktop shortcut created |
| Rust MSVC | rustc/Cargo 1.99.0 stable; `%USERPROFILE%\.cargo\bin`; rustfmt and Clippy |
| Tauri CLI | 2.9.5, installed with `--locked` |
| Existing tools | Windows Node 26.1.0, Visual Studio C++ Build Tools/Windows SDK, WebView2 |

Official download hashes were verified for JDK, Gradle, SDK command-line tools, Rust installer and Android Studio. Gradle wrapper generation produced the genuine 8.13 scripts/JAR; the Android PowerShell builder verifies wrapper JAR, binary distribution URL and distribution SHA-256 before execution. Tauri's mutually compatible 2.9 library components are pinned; the actual resolved shell Cargo.lock is retained, and packaging passes `--locked` to Cargo.

The Android builder auto-discovers the single installed test JDK 21 directory, or accepts `LOCALSPOOF_TEST_JAVA_HOME`. Robolectric Android 16 tests require Java 21. Gradle and production compilation still use JDK 17; the test setting is restored after the build and does not replace user JAVA_HOME/PATH. The official Temurin Windows archive and published SHA-256 were verified.

## Rebuild

```powershell
Set-Location 'E:\Codex\Location Spoofer'
.\tools\build-android.ps1 -Variant both
.\tools\sign-android.ps1
$Certificate = Join-Path $env:LOCALAPPDATA 'LocalSpoof\signing\localspoof-release.cer'
$Fingerprint = (Get-FileHash -LiteralPath $Certificate -Algorithm SHA256).Hash
.\tools\build-desktop.ps1 -VerifiedSignedApk '.\release\localspoof-0.6.0.apk' -ExpectedSignerSha256 $Fingerprint
```

To build a desktop with local APK installation disabled, omit `VerifiedSignedApk` and `ExpectedSignerSha256`. Download/dependency access is needed for first builds and official wrapper checksum verification; the app's runtime uses local assets.

Signing material lives at `%LOCALAPPDATA%\LocalSpoof\signing`, outside the repository, with restricted owner/SYSTEM/Administrators ACLs. The release keystore and its password are separate files. The password record uses Windows DPAPI for this account; copying that record to another account or reinstalling Windows does not make it decryptable there. Back up the keystore and an independently recoverable password securely before migrating this account. The public `.cer` certificate is safe to use for fingerprint checks; it is not a private key.

Build logs and browser QA artifacts are development files under `.build`; signing passwords/private keys are never printed or copied into that directory. Current build and physical-device qualification results are recorded in [VALIDATION.md](VALIDATION.md).
