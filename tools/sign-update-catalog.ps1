param([Parameter(Mandatory=$true)][string]$Apk,[Parameter(Mandatory=$true)][string]$Installer,[Parameter(Mandatory=$true)][string]$ReleaseNotes,[string]$JavaHome=$env:JAVA_HOME,[string]$SdkRoot=$env:ANDROID_HOME,[string]$SigningDirectory=(Join-Path $env:LOCALAPPDATA 'LocalSpoof\signing'))
$ErrorActionPreference='Stop'
$Root=Split-Path -Parent $PSScriptRoot
$Version=(Get-Content (Join-Path $Root 'package.json') -Raw|ConvertFrom-Json).version
$CodeMatch=[regex]::Match((Get-Content (Join-Path $Root 'android\app\build.gradle.kts') -Raw),'versionCode\s*=\s*(\d+)')
if(-not $CodeMatch.Success){throw 'Missing Android release version.'}
$Code=$CodeMatch.Groups[1].Value
$Certificate=Join-Path $Root 'android\app\src\main\assets\updates\release.cer'
$Signer=(Get-FileHash $Certificate -Algorithm SHA256).Hash.ToLowerInvariant()
$Catalog=Join-Path $Root 'release\catalog.json'
$Signature="$Catalog.sig"
$Keystore=Join-Path $SigningDirectory 'localspoof-release.jks'
$PasswordFile=Join-Path $SigningDirectory 'localspoof-release.password.dpapi'
if(-not(Test-Path $Keystore) -or -not(Test-Path $PasswordFile)){throw 'Original release identity is required.'}
# Authenticate both supplied artifacts before issuing publisher catalog authority.
if(-not $SdkRoot -or -not $JavaHome){throw 'Android verification toolchains are required.'}
$Apksigner=Join-Path $SdkRoot 'build-tools\35.0.0\apksigner.bat'
$Aapt=Join-Path $SdkRoot 'build-tools\35.0.0\aapt.exe'
function Read-VerifiedOutput([string]$Tool,[string[]]$Arguments){
 $Old=$ErrorActionPreference
 try{$ErrorActionPreference='Continue';$Lines=@(& $Tool @Arguments 2>&1|ForEach-Object{[string]$_});$Exit=$LASTEXITCODE}finally{$ErrorActionPreference=$Old}
 if($Exit -ne 0 -or $Lines.Count -gt 256 -or ($Lines|Where-Object{$_.Length -gt 4096})){throw 'Artifact inspection failed or exceeded bounds.'}
 return $Lines
}
$Signed=@(Read-VerifiedOutput $Apksigner @('verify','--print-certs',$Apk))
$Signers=@($Signed|Where-Object{$_ -match '^Signer #[0-9]+ certificate SHA-256 digest: '})
if($Signers.Count -ne 1 -or $Signers[0] -notmatch '^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$' -or $Matches[1] -ine $Signer){throw 'APK does not have the original release signer.'}
$Metadata=@(Read-VerifiedOutput $Aapt @('dump','badging',$Apk))
$Package=@($Metadata|Where-Object{$_ -match '^package: '})
$ExpectedVersion=[regex]::Escape($Version)
if($Package.Count -ne 1 -or $Package[0] -notmatch "^package: name='dev\.localspoof\.android' versionCode='$Code' versionName='$ExpectedVersion'( |$)" -or @($Metadata|Where-Object{$_ -match '^application-debuggable'}).Count -gt 0 -or @($Metadata|Where-Object{$_ -eq "sdkVersion:'29'"}).Count -ne 1){throw 'APK package/version/SDK differs from the reviewed release.'}
$Product=[Diagnostics.FileVersionInfo]::GetVersionInfo([IO.Path]::GetFullPath($Installer)).ProductVersion
if($Product -notmatch "^$ExpectedVersion(?:\.0)?$"){throw 'Windows installer version differs from the reviewed release.'}
$Old=$ErrorActionPreference
try{
 $ErrorActionPreference='Continue'
 & cargo run --quiet --locked --manifest-path (Join-Path $Root 'tools\signing\verify-windows\Cargo.toml') -- $Installer "$Installer.sig" (Join-Path $Root 'desktop\src-tauri\resources\windows-updater.pub')
 $Verified=$LASTEXITCODE
}finally{$ErrorActionPreference=$Old}
if($Verified -ne 0){throw 'Windows updater signature verification failed.'}
$Previous=$env:LOCALSPOOF_KEYSTORE_PASSWORD
try{
 & node (Join-Path $Root 'tools\release-catalog.mjs') $Version $Code $Apk $Installer "$Installer.sig" $ReleaseNotes $Catalog $Signer
 if($LASTEXITCODE -ne 0){throw 'Catalog generation failed.'}
 $Secure=(Get-Content $PasswordFile -Raw).Trim()|ConvertTo-SecureString
 $Credential=New-Object Management.Automation.PSCredential('localspoof',$Secure)
 $env:LOCALSPOOF_KEYSTORE_PASSWORD=$Credential.GetNetworkCredential().Password
 & (Join-Path $JavaHome 'bin\java.exe') (Join-Path $Root 'tools\signing\CatalogSigner.java') $Keystore $Catalog $Signature $Certificate
 if($LASTEXITCODE -ne 0){throw 'Catalog signature failed.'}
 Write-Output 'Catalog ready for reviewed release publication; this script does not publish files.'
}finally{$env:LOCALSPOOF_KEYSTORE_PASSWORD=$Previous;$Credential=$null;$Secure=$null}
