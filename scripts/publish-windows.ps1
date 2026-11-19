<#
.SYNOPSIS
  Publishes the Windows companion as portable zips (self-contained and framework-dependent) into <repo>\dist.
.DESCRIPTION
  Version comes from <repo>\VERSION (single source; windows-app\Directory.Build.props reads the same file).
  Produces dist\Phone2Gamepad-<ver>-win-x64-selfcontained.zip, ...-framework-dependent.zip, and SHA256SUMS.txt.
  Installs nothing, touches no registry/firewall/driver. Requires the .NET 10 SDK.
.PARAMETER SkipFrameworkDependent
  Only build the self-contained variant.
#>
param([switch]$SkipFrameworkDependent)
$ErrorActionPreference = 'Stop'
$root    = Split-Path $PSScriptRoot -Parent
$project = Join-Path $root 'windows-app\Phone2Gamepad.Windows\Phone2Gamepad.Windows.csproj'
$dist    = Join-Path $root 'dist'
$version = (Get-Content (Join-Path $root 'VERSION') -Raw).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "VERSION must look like 1.2.3, got '$version'" }
$readme  = Join-Path $PSScriptRoot 'templates\README-first-run.txt'

New-Item -ItemType Directory -Force $dist | Out-Null
$zips = @()

function Publish-Variant([string]$name, [bool]$selfContained) {
    $folderName = "Phone2Gamepad-$version-win-x64-$name"
    $out = Join-Path $dist $folderName
    if (Test-Path $out) { Remove-Item $out -Recurse -Force }
    Write-Host "== Publishing $folderName"
    $sc = if ($selfContained) { 'true' } else { 'false' }
    dotnet publish $project -c Release -r win-x64 --self-contained $sc -o $out -p:SatelliteResourceLanguages=en --nologo -v q
    if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed ($name)" }
    Copy-Item $readme (Join-Path $out 'README-first-run.txt')
    $zip = Join-Path $dist "$folderName.zip"
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path (Join-Path $out '*') -DestinationPath $zip -CompressionLevel Optimal
    $size = (Get-ChildItem $out -Recurse -File | Measure-Object Length -Sum).Sum
    Write-Host ("   folder {0:N1} MB, zip {1:N1} MB" -f ($size/1MB), ((Get-Item $zip).Length/1MB))
    return $zip
}

$zips += Publish-Variant 'selfcontained' $true
if (-not $SkipFrameworkDependent) { $zips += Publish-Variant 'framework-dependent' $false }

$sums = Join-Path $dist 'SHA256SUMS.txt'
$lines = foreach ($z in $zips) { '{0}  {1}' -f (Get-FileHash $z -Algorithm SHA256).Hash.ToLower(), (Split-Path $z -Leaf) }
Set-Content -Path $sums -Value $lines -Encoding ascii
Write-Host "== Checksums ($sums)"; $lines | ForEach-Object { Write-Host "   $_" }
Write-Host "Done. Framework-dependent needs the .NET 10 Desktop Runtime (x64); self-contained needs nothing."
