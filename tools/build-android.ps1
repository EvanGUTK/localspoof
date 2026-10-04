param(
    [ValidateSet("debug", "release", "both")][string]$Variant = "debug",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$GradleHome = ""
)
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$PreviousTestJava = $env:LOCALSPOOF_TEST_JAVA_HOME
if (-not $env:LOCALSPOOF_TEST_JAVA_HOME -and $env:LOCALAPPDATA) {
    $TestJdkRoot = Join-Path $env:LOCALAPPDATA 'LocalSpoof\toolchains\temurin-21'
    $TestJdks = @(Get-ChildItem -LiteralPath $TestJdkRoot -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') })
    if ($TestJdks.Count -eq 1) { $env:LOCALSPOOF_TEST_JAVA_HOME = $TestJdks[0].FullName }
}
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
}
if ($SdkRoot) { $env:ANDROID_HOME = $SdkRoot; $env:ANDROID_SDK_ROOT = $SdkRoot }
if ($GradleHome) { $env:Path = (Join-Path $GradleHome 'bin') + ';' + $env:Path }
Push-Location (Join-Path $ProjectRoot "android")
try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw "Install Java 17 and set JAVA_HOME before building." }
    $DistributionSha = ([string](Invoke-RestMethod 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256')).Trim()
    $WrapperJarSha = ([string](Invoke-RestMethod 'https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256')).Trim()
    if ($DistributionSha -notmatch '^[a-fA-F0-9]{64}$' -or $WrapperJarSha -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid official Gradle checksum.' }
    $WrapperJar = 'gradle\wrapper\gradle-wrapper.jar'
    $WrapperProperties = 'gradle\wrapper\gradle-wrapper.properties'
    if ((Test-Path $WrapperJar) -and (Get-FileHash -Algorithm SHA256 $WrapperJar).Hash -ine $WrapperJarSha) {
        throw 'Existing Gradle wrapper JAR does not match the official 8.13 checksum.'
    }
    $Properties = if (Test-Path $WrapperProperties) { @(Get-Content $WrapperProperties) } else { @() }
    $Urls = @($Properties | Where-Object { $_ -match '^distributionUrl=' })
    if ($Urls.Count -ne 1 -or $Urls[0] -ne 'distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip') {
        throw 'Wrapper distribution URL must be the official Gradle 8.13 binary archive.'
    }
    $Hashes = @($Properties | Where-Object { $_ -match '^distributionSha256Sum=' })
    if ($Hashes.Count -gt 1 -or ($Hashes.Count -eq 1 -and $Hashes[0] -ine "distributionSha256Sum=$DistributionSha")) {
        throw 'Wrapper distribution checksum differs from the official Gradle 8.13 archive.'
    }
    if (-not (Test-Path 'gradlew.bat') -or -not (Test-Path $WrapperJar) -or $Hashes.Count -eq 0) {
        if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) { throw "Install Gradle 8.13, then rerun to generate the official wrapper." }
        $NativePreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & gradle --no-daemon wrapper --gradle-version 8.13 --distribution-type bin --gradle-distribution-sha256-sum $DistributionSha
            $NativeExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $NativePreference }
        if ($NativeExit -ne 0) { throw "Official Gradle wrapper generation failed." }
    }
    if ((Get-FileHash -Algorithm SHA256 $WrapperJar).Hash -ine $WrapperJarSha) { throw 'Generated wrapper JAR does not match the official Gradle 8.13 checksum.' }
    $Properties = @(Get-Content $WrapperProperties)
    $Urls = @($Properties | Where-Object { $_ -match '^distributionUrl=' })
    $Hashes = @($Properties | Where-Object { $_ -match '^distributionSha256Sum=' })
    if ($Urls.Count -ne 1 -or $Urls[0] -ne 'distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip' -or
        $Hashes.Count -ne 1 -or $Hashes[0] -ine "distributionSha256Sum=$DistributionSha") {
        throw 'Generated wrapper URL or distribution checksum validation failed.'
    }
    $BuildTasks = @(':session-domain:test', ':session-domain:jacocoTestReport', ':app:testDebugUnitTest', ':app:lintDebug')
    if ($Variant -in @('debug', 'both')) { $BuildTasks += ':app:assembleDebug' }
    if ($Variant -in @('release', 'both')) { $BuildTasks += @(':app:lintRelease', ':app:assembleRelease') }
    $NativePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat --no-daemon --console=plain @BuildTasks
        $NativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $NativePreference }
    if ($NativeExit -ne 0) { throw "Android build/verification failed. No release qualification claimed." }
    Write-Output "Android build tasks completed. Release APK is unsigned. Physical-device qualification remains required."
} finally { Pop-Location; $env:LOCALSPOOF_TEST_JAVA_HOME = $PreviousTestJava }
