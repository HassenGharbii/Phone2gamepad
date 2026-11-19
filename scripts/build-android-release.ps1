<#
.SYNOPSIS
  Builds the signed Android release APK and verifies its signature with apksigner.
.DESCRIPTION
  Reads credentials from android-app\keystore.properties (see make-release-keystore.ps1) or the P2G_KEYSTORE_FILE /
  P2G_KEYSTORE_PASSWORD / P2G_KEY_ALIAS / P2G_KEY_PASSWORD environment variables. Refuses to continue if neither is
  configured (the Gradle build would otherwise silently produce an UNSIGNED apk). Output: dist\Phone2Gamepad-<ver>-release.apk
  Do not run while another Gradle build of android-app is in progress.
#>
$ErrorActionPreference = 'Stop'
$root    = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root 'android-app'
$dist    = Join-Path $root 'dist'
$version = (Get-Content (Join-Path $root 'VERSION') -Raw).Trim()

$props = Join-Path $android 'keystore.properties'
$envOk = $env:P2G_KEYSTORE_FILE -and $env:P2G_KEYSTORE_PASSWORD -and $env:P2G_KEY_ALIAS -and $env:P2G_KEY_PASSWORD
if (-not (Test-Path $props) -and -not $envOk) { throw 'No signing credentials: run scripts\make-release-keystore.ps1 or set the P2G_* environment variables.' }

# Keep the Android versionName in step with VERSION (single source of truth is the VERSION file).
$gradle = Get-Content (Join-Path $android 'app\build.gradle.kts') -Raw
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw 'versionName not found in app\build.gradle.kts' }
if ($Matches[1] -ne $version) { Write-Warning "Android versionName '$($Matches[1])' differs from VERSION '$version'. Update app\build.gradle.kts (versionName / versionCode) before releasing." }

if (-not $env:ANDROID_HOME) { throw 'ANDROID_HOME is not set.' }
Push-Location $android
try {
    & .\gradlew.bat assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'gradle assembleRelease failed' }
} finally { Pop-Location }

$apk = Get-ChildItem (Join-Path $android 'app\build\outputs\apk\release') -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw 'No release APK was produced.' }
if ($apk.Name -match 'unsigned') { throw "Gradle produced an unsigned APK ($($apk.Name)); credentials were not picked up." }

$bt = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '[^\d\.].*$','') } -Descending | Select-Object -First 1
$apksigner = Join-Path $bt.FullName 'apksigner.bat'
if (-not (Test-Path $apksigner)) { throw "apksigner not found in $($bt.FullName)" }
& $apksigner verify --verbose --print-certs $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify FAILED' }

New-Item -ItemType Directory -Force $dist | Out-Null
$out = Join-Path $dist "Phone2Gamepad-$version-release.apk"
Copy-Item $apk.FullName $out -Force
$hash = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
Add-Content -Path (Join-Path $dist 'SHA256SUMS.txt') -Value ('{0}  {1}' -f $hash, (Split-Path $out -Leaf)) -Encoding ascii
Write-Host "OK: $out  (sha256 $hash)"
