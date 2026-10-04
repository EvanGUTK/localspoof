param(
    [string]$VerifiedSignedApk = "",
    [string]$ExpectedSignerSha256 = "",
    [string]$SdkRoot = $env:ANDROID_HOME
)
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$PreviousHash = $env:LOCALSPOOF_TRUSTED_APK_SHA256
$ApkStream = $null
$BundledApkStream = $null
$BundleConfig = '{"bundle":{"resources":[]}}'
$LocationPushed = $false
function Read-ToolOutput {
    param([string]$Tool, [string[]]$Arguments)
    $Output = @(& $Tool @Arguments | ForEach-Object {
        if ([string]$_ -and ([string]$_).Length -gt 4096) { throw 'APK metadata output exceeds the allowed line length.' }
        [string]$_
    } | Select-Object -First 257)
    if ($LASTEXITCODE -ne 0) { throw 'Android artifact inspection failed.' }
    if ($Output.Count -gt 256) { throw 'APK metadata output exceeds the allowed line count.' }
    return $Output
}
try {
    Push-Location $ProjectRoot
    $LocationPushed = $true
    $NativePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & node tools/build-ui.mjs
        $UiBuildExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $NativePreference }
    if ($UiBuildExit -ne 0) { throw "Desktop UI asset build failed." }
    if ($VerifiedSignedApk) {
        if ($ExpectedSignerSha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Provide the SHA-256 fingerprint exported from the trusted release signing certificate.' }
        $ApkPath = (Resolve-Path -LiteralPath $VerifiedSignedApk).ProviderPath
        if ([IO.Path]::GetExtension($ApkPath) -ine '.apk') { throw 'VerifiedSignedApk must identify an APK file.' }
        # Holding this handle blocks Windows writers and replacement throughout verification and compilation.
        $ApkStream = [IO.File]::Open($ApkPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        if ($ApkStream.Length -le 0 -or $ApkStream.Length -gt 209715200) { throw 'APK must be nonempty and at most 200 MiB.' }
        if ($SdkRoot) {
            $Apksigner = Join-Path $SdkRoot 'build-tools\35.0.0\apksigner.bat'
            $Aapt = Join-Path $SdkRoot 'build-tools\35.0.0\aapt.exe'
            if (-not (Test-Path -LiteralPath $Apksigner) -or -not (Test-Path -LiteralPath $Aapt)) { throw 'Android build-tools 35.0.0 are required for artifact verification.' }
        } else {
            $ApksignerCommand = Get-Command apksigner -CommandType Application -ErrorAction SilentlyContinue
            $AaptCommand = Get-Command aapt -CommandType Application -ErrorAction SilentlyContinue
            if (-not $ApksignerCommand -or -not $AaptCommand) { throw 'Put Android SDK apksigner and aapt on PATH or provide SdkRoot.' }
            $Apksigner = $ApksignerCommand.Source
            $Aapt = $AaptCommand.Source
        }
        $SignatureOutput = @(Read-ToolOutput -Tool $Apksigner -Arguments @('verify', '--verbose', '--print-certs', $ApkPath))
        $CertificateLines = @($SignatureOutput | Where-Object { $_ -match '^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$' })
        if ($CertificateLines.Count -ne 1 -or $CertificateLines[0] -notmatch '^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$') { throw 'APK must have exactly one verified release signer.' }
        if ($Matches[1] -ine $ExpectedSignerSha256) { throw 'APK signer differs from the trusted release certificate.' }
        $Metadata = @(Read-ToolOutput -Tool $Aapt -Arguments @('dump', 'badging', $ApkPath))
        $PackageLines = @($Metadata | Where-Object { $_ -match '^package: ' })
        if ($PackageLines.Count -ne 1 -or $PackageLines[0] -notmatch "^package: name='dev\.localspoof\.android' versionCode='7' versionName='0\.7\.0'( |$)") { throw 'APK package or release version differs from LocalSpoof 0.7.0.' }
        if (@($Metadata | Where-Object { $_ -match '^application-debuggable(?:\s|$)' }).Count -gt 0) { throw 'Debuggable APKs cannot be trusted by the release desktop build.' }
        $Digest = [Security.Cryptography.SHA256]::Create()
        try {
            $env:LOCALSPOOF_TRUSTED_APK_SHA256 = ([BitConverter]::ToString($Digest.ComputeHash($ApkStream))).Replace('-', '').ToLowerInvariant()
        } finally { $Digest.Dispose() }
        # Ship the already verified companion with the Windows installer. Keep both
        # source and staged files locked against writers until packaging finishes.
        $ResourceDirectory = Join-Path $ProjectRoot 'desktop\src-tauri\resources'
        New-Item -ItemType Directory -Force $ResourceDirectory | Out-Null
        $BundledApkPath = Join-Path $ResourceDirectory 'localspoof-companion.apk'
        $ApkStream.Position = 0
        $StageStream = [IO.File]::Open($BundledApkPath, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $ApkStream.CopyTo($StageStream) } finally { $StageStream.Dispose() }
        $BundledApkStream = [IO.File]::Open($BundledApkPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        $StageDigest = [Security.Cryptography.SHA256]::Create()
        try {
            $StageHash = ([BitConverter]::ToString($StageDigest.ComputeHash($BundledApkStream))).Replace('-', '').ToLowerInvariant()
            if ($StageHash -ne $env:LOCALSPOOF_TRUSTED_APK_SHA256) { throw 'Bundled APK differs from the verified release APK.' }
        } finally { $StageDigest.Dispose() }
        $BundleConfig = '{"bundle":{"resources":{"resources/localspoof-companion.apk":"companion/localspoof.apk"}}}'
    } else { Remove-Item Env:LOCALSPOOF_TRUSTED_APK_SHA256 -ErrorAction SilentlyContinue }
    Pop-Location
    $LocationPushed = $false
    Push-Location (Join-Path $ProjectRoot "desktop")
    $LocationPushed = $true
    # Pass JSON through a file: Windows PowerShell's native argument marshalling
    # can strip embedded quotes from an inline JSON command-line argument.
    $BundleConfigDirectory = Join-Path $ProjectRoot '.build'
    New-Item -ItemType Directory -Force $BundleConfigDirectory | Out-Null
    $BundleConfigPath = Join-Path $BundleConfigDirectory 'desktop-bundle-config.json'
    [IO.File]::WriteAllText($BundleConfigPath, $BundleConfig, (New-Object Text.UTF8Encoding($false)))
    # Rust emits normal compiler progress on stderr. Check the native exit code,
    # rather than treating successful compiler output as a PowerShell exception.
    $NativePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & cargo tauri build --config src-tauri/tauri.conf.json --config $BundleConfigPath -- --locked
        $NativeBuildExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $NativePreference }
    if ($NativeBuildExit -ne 0) { throw "Native desktop build failed. Check Rust/MSVC/WebView2 prerequisites." }
    Write-Output "Desktop build completed. Webview privacy and physical-device qualification remain required."
} finally {
    if ($LocationPushed) { Pop-Location }
    if ($null -ne $ApkStream) { $ApkStream.Dispose() }
    if ($null -ne $BundledApkStream) { $BundledApkStream.Dispose() }
    if ($null -eq $PreviousHash) { Remove-Item Env:LOCALSPOOF_TRUSTED_APK_SHA256 -ErrorAction SilentlyContinue }
    else { $env:LOCALSPOOF_TRUSTED_APK_SHA256 = $PreviousHash }
}
