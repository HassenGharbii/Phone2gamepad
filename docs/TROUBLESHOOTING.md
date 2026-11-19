# Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Dashboard says driver not installed | Install ViGEmBus yourself (`DRIVER_DECISION.md`); restart the app |
| First virtual-pad plug-in fails once | Seen once during testing (Win32Exception); the app retries 3 times. Restart the server if it persists |
| Phone doesn't list the PC | Different networks/VLANs, router client isolation, or firewall blocking UDP 47820. Use manual IP:47821 |
| Manual connect times out | Firewall blocks TCP 47821 (allow the app on Private networks), wrong IP (use the Wi-Fi adapter's address, not 172.x virtual adapters), server not started |
| Another program already uses a port | Only one server instance can run; stop the headless host / other instance. Check `netstat -ano \| findstr 4782` |
| "bad_pin" / "rate_limited" | PIN is single use, 120 s, 5 attempts; open a new pairing window and wait 1 s between attempts |
| "busy" | Another phone already has the session |
| "auth_failed" / need to re-pair | Device was revoked on the PC, or app data was cleared; pair again |
| Buttons stick / controller goes neutral during play | Wi-Fi stalls >500 ms neutralize by design; use 5 GHz, avoid weak signal |
| No controller in `joy.cpl` | Server not running, no active session, or driver problem; run `TestClient.exe xinput` (from `windows-app/tests`) to inspect XInput slots |
| Game ignores the pad | Game must support XInput; check that no other virtual device (e.g. DS4Windows) is in the way |

Logs are shown in the dashboard log view. PINs and secrets are never logged.
