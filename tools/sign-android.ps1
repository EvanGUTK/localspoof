param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$SigningDirectory = (Join-Path $env:LOCALAPPDATA 'LocalSpoof\signing')
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$UnsignedApk = Join-Path $ProjectRoot 'android\app\build\outputs\apk\release\app-release-unsigned.apk'
$ReleaseDirectory = Join-Path $ProjectRoot 'release'
if (-not $JavaHome -or -not $SdkRoot) { throw 'Set JavaHome and SdkRoot to the installed toolchains.' }
if (-not (Test-Path $UnsignedApk)) { throw 'Build and validate the unsigned release APK before signing.' }
$Keytool = Join-Path $JavaHome 'bin\keytool.exe'
$Zipalign = Join-Path $SdkRoot 'build-tools\35.0.0\zipalign.exe'
$Apksigner = Join-Path $SdkRoot 'build-tools\35.0.0\apksigner.bat'
foreach ($Tool in @($Keytool, $Zipalign, $Apksigner)) { if (-not (Test-Path $Tool)) { throw 'Android signing toolchain is incomplete.' } }
if ([IO.Path]::GetFullPath($SigningDirectory).StartsWith([IO.Path]::GetFullPath($ProjectRoot), [StringComparison]::OrdinalIgnoreCase)) { throw 'Keep signing keys outside the repository.' }
$PreviousPassword = $env:LOCALSPOOF_KEYSTORE_PASSWORD
$PreviousJava = $env:JAVA_HOME
try {
    New-Item -ItemType Directory -Force $SigningDirectory | Out-Null
    $Identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $Acl = New-Object Security.AccessControl.DirectorySecurity
    $Acl.SetOwner((New-Object Security.Principal.NTAccount($Identity)))
    $Acl.SetAccessRuleProtection($true, $false)
    foreach ($Principal in @($Identity, 'SYSTEM', 'BUILTIN\Administrators')) {
        $Acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($Principal, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
    }
    [IO.Directory]::SetAccessControl($SigningDirectory, $Acl)
    $Keystore = Join-Path $SigningDirectory 'localspoof-release.jks'
    $PasswordFile = Join-Path $SigningDirectory 'localspoof-release.password.dpapi'
    if (-not(Test-Path $Keystore) -or -not(Test-Path $PasswordFile)) { throw 'Original release identity is missing. Restore its key/password outside Git; updates cannot use a replacement key.' }
    foreach ($ExistingFile in @($Keystore, $PasswordFile)) {
        if (Test-Path -LiteralPath $ExistingFile) {
            $FileAcl = New-Object Security.AccessControl.FileSecurity
            $FileAcl.SetOwner((New-Object Security.Principal.NTAccount($Identity)))
            $FileAcl.SetAccessRuleProtection($true, $false)
            foreach ($Principal in @($Identity, 'SYSTEM', 'BUILTIN\Administrators')) {
                $FileAcl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($Principal, 'FullControl', 'Allow')))
            }
            [IO.File]::SetAccessControl($ExistingFile, $FileAcl)
        }
    }
    $SecurePassword = (Get-Content -LiteralPath $PasswordFile -Raw).Trim() | ConvertTo-SecureString
    $Credential = New-Object Management.Automation.PSCredential('localspoof', $SecurePassword)
    $env:LOCALSPOOF_KEYSTORE_PASSWORD = $Credential.GetNetworkCredential().Password
    $env:JAVA_HOME = $JavaHome
    $Certificate = Join-Path $SigningDirectory 'localspoof-release.cer'
    $NativePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Keytool -exportcert -keystore $Keystore -alias localspoof -file $Certificate -storepass:env LOCALSPOOF_KEYSTORE_PASSWORD
        $NativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $NativePreference }
    if ($NativeExit -ne 0) { throw 'Release certificate export failed.' }
    $SignerSha256 = (Get-FileHash -LiteralPath $Certificate -Algorithm SHA256).Hash.ToLowerInvariant()
    $PinnedCertificate = Join-Path $ProjectRoot 'android\app\src\main\assets\updates\release.cer'
    if ($SignerSha256 -ne (Get-FileHash $PinnedCertificate -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'Release key differs from the original installed-app identity.' }
    New-Item -ItemType Directory -Force $ReleaseDirectory | Out-Null
    $AlignedApk = Join-Path $ReleaseDirectory 'localspoof-0.8.1-aligned.apk'
    $SignedApk = Join-Path $ReleaseDirectory 'localspoof-0.8.1.apk'
    $NativePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Zipalign -f -p 4 $UnsignedApk $AlignedApk
        $NativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $NativePreference }
    if ($NativeExit -ne 0) { throw 'APK alignment failed.' }
    $NativePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Apksigner sign --ks $Keystore --ks-key-alias localspoof --ks-pass env:LOCALSPOOF_KEYSTORE_PASSWORD --key-pass env:LOCALSPOOF_KEYSTORE_PASSWORD --out $SignedApk $AlignedApk
        $NativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $NativePreference }
    if ($NativeExit -ne 0) { throw 'APK signing failed.' }
    $NativePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Apksigner verify --verbose --print-certs $SignedApk
        $NativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $NativePreference }
    if ($NativeExit -ne 0) { throw 'Signed APK verification failed.' }
    $NativePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Zipalign -c -p 4 $SignedApk
        $NativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $NativePreference }
    if ($NativeExit -ne 0) { throw 'Signed APK alignment verification failed.' }
    Remove-Item -LiteralPath $AlignedApk
    Write-Output "Signed APK: $SignedApk"
    Write-Output "Expected signer SHA-256: $SignerSha256"
    Write-Output "Private signing directory: $SigningDirectory"
    Write-Output 'Password is encrypted for this Windows user with DPAPI; never upload signing material.'
} finally {
    $env:LOCALSPOOF_KEYSTORE_PASSWORD = $PreviousPassword
    $env:JAVA_HOME = $PreviousJava
    $SecurePassword = $null; $Credential = $null
}
