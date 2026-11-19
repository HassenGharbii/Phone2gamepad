<#
.SYNOPSIS
  Interactively creates the Android release signing key and a git-ignored android-app\keystore.properties.
.DESCRIPTION
  The keystore is written OUTSIDE the repository by default (%USERPROFILE%\.phone2gamepad\p2g-release.jks).
  keytool prompts for the passwords itself; they are never put on the command line or printed.
  If you lose this keystore you can never publish an update that installs over the same app - BACK IT UP
  (two copies, offline) together with its passwords.
#>
param(
    [string]$KeystorePath = (Join-Path $env:USERPROFILE '.phone2gamepad\p2g-release.jks'),
    [string]$Alias = 'phone2gamepad'
)
$ErrorActionPreference = 'Stop'
$root  = Split-Path $PSScriptRoot -Parent
$props = Join-Path $root 'android-app\keystore.properties'

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool -and $env:JAVA_HOME) { $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe' }
if (-not $keytool -or -not (Test-Path $keytool)) { throw 'keytool not found: install a JDK 17 or set JAVA_HOME.' }
if (Test-Path $KeystorePath) { throw "Refusing to overwrite existing keystore: $KeystorePath" }
if ($KeystorePath -like "$root*") { throw 'Keystore must live outside the repository.' }
New-Item -ItemType Directory -Force (Split-Path $KeystorePath) | Out-Null

Write-Host "Creating $KeystorePath (alias '$Alias'). keytool will ask for passwords and certificate details."
& $keytool -genkeypair -v -keystore $KeystorePath -alias $Alias -keyalg RSA -keysize 4096 -validity 10000
if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }

$sp = Read-Host 'Re-enter the keystore password for keystore.properties' -AsSecureString
$kp = Read-Host 'Re-enter the key password (same as keystore password if you pressed Enter at keytool)' -AsSecureString
function Plain($s) { [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)) }
@(
    "storeFile=$($KeystorePath -replace '\\','/')",
    "storePassword=$(Plain $sp)",
    "keyAlias=$Alias",
    "keyPassword=$(Plain $kp)"
) | Set-Content -Path $props -Encoding ascii

Write-Host ''
Write-Host "Wrote $props (git-ignored; contains passwords in plain text - protect or delete it after building)."
Write-Host 'REMINDERS:'
Write-Host "  1. Back up $KeystorePath and its passwords in two safe offline places. Losing it = no more updates for this app id."
Write-Host '  2. Never commit the .jks or keystore.properties.'
Write-Host '  3. Build with scripts\build-android-release.ps1'
