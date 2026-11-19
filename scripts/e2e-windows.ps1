<#
 End-to-end check of the Windows side WITHOUT a phone, against the real ViGEmBus driver:
 starts HeadlessHost, pairs the TestClient with the PIN, then drives every control and reads the result back through XInput.
 Requires ViGEmBus to be installed already (this script installs nothing).
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$win  = Join-Path $root 'windows-app'
dotnet build "$win\Phone2Gamepad.HeadlessHost" -v q | Out-Null
dotnet build "$win\tests\Phone2Gamepad.TestClient" -v q | Out-Null
$store = Join-Path $env:TEMP 'p2g-e2e-store.json'; $creds = Join-Path $env:TEMP 'p2g-e2e-creds.json'
Remove-Item $store, $creds -ErrorAction SilentlyContinue
$log = Join-Path $env:TEMP 'p2g-e2e-host.log'
$host_ = Start-Process "$win\Phone2Gamepad.HeadlessHost\bin\Debug\net10.0\Phone2Gamepad.HeadlessHost.exe" `
  -ArgumentList '--open-pairing','--auto-accept','--store',$store,'--run-seconds','60' -RedirectStandardOutput $log -PassThru -WindowStyle Hidden
try {
  $pin = $null
  for ($i = 0; $i -lt 50 -and -not $pin; $i++) { Start-Sleep -Milliseconds 200; $m = Select-String -Path $log -Pattern '^PIN (\d{6})' -ErrorAction SilentlyContinue; if ($m) { $pin = $m.Matches[0].Groups[1].Value } }
  if (-not $pin) { Get-Content $log; throw 'host did not report a PIN' }
  $tc = "$win\tests\Phone2Gamepad.TestClient\bin\Debug\net10.0\Phone2Gamepad.TestClient.exe"
  & $tc pair --pin $pin --store $creds
  & $tc e2e --store $creds
  exit $LASTEXITCODE
} finally { if (-not $host_.HasExited) { Stop-Process $host_ -Force } }
