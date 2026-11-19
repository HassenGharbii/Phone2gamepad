# Security and privacy

## Scope and threat model
Phone2Gamepad is local-network-only. No account, cloud service or telemetry exists. The threat model is a **hostile device on the same Wi-Fi network** (including a passive sniffer and an active man-in-the-middle) trying to (a) inject controller input, (b) pair without the owner's consent, or (c) replay captured traffic. Out of scope: a compromised PC or phone, physical access, malicious kernel drivers.

## Network exposure
| Port | Proto | Purpose | Authentication |
|---|---|---|---|
| 47820 | UDP | discovery (reply contains PC name, ports only) | none, no secrets |
| 47821 | TCP | pairing + session setup | PIN-bound ECDH / HMAC (see below) |
| 47822 | UDP | input + heartbeat | HMAC-SHA256 tag per packet |

The server accepts peers only from private/loopback ranges by default and must never be port-forwarded. There is no command execution, file access, shell or remote-desktop feature: the only effect of a valid session is setting the state of one virtual gamepad.

## Pairing
- The PC shows a random 6-digit PIN valid 120 s, single use. Max 5 wrong proofs per window, and one failed attempt per second per source IP.
- ECDH (P-256) key exchange; the PIN is mixed into a HKDF-derived confirmation key, so the PIN is never sent and a passive sniffer cannot recover the shared secret. An active attacker gets one online PIN guess per attempt.
- The PC asks the user to **confirm each new phone**; declining aborts pairing.
- Both sides authenticate each other with HMAC proofs; the result is a per-device 32-byte secret.
- Storage: PC — DPAPI (current user); phone — AES-GCM key held in the Android Keystore. Paired devices can be revoked from the Windows app; a revoked secret fails at the next session.

## Session and input packets
Each connection performs a fresh HMAC challenge/response, then derives two directional keys (HKDF) bound to a random session id. Every UDP packet carries a 16-byte HMAC-SHA256 tag. Packets are dropped if: wrong length/version, wrong session, source IP differs from the TCP peer, bad tag, or non-increasing sequence (serial-number arithmetic, wrap-safe). Old sessions can never validate after reconnect. Silence for 500 ms neutralizes the controller; 5 s ends the session; TCP close ends it immediately.

Only standard primitives (ECDH P-256, HKDF-SHA256, HMAC-SHA256) from the platform libraries are used. Details: `shared/protocol/PROTOCOL.md`.

## Known limitations (be honest)
- Packets are **authenticated but not encrypted**: an eavesdropper on the LAN can see button states. The TCP channel is likewise not encrypted; secret-bearing steps are protected cryptographically.
- A 6-digit PIN limits security to rate-limited online guessing during a 120 s window that the user must open and then approve.
- The Android secret is keyed by PC name (the protocol has no server id yet), so two PCs with identical names can clash.
- ViGEmBus is a retired kernel driver (see `DRIVER_DECISION.md`).
- Not yet done: external security review, fuzzing, automated tests of the Android Keystore path.

## Logging
PINs, secrets and keys are never logged. Diagnostics export is planned; it must exclude secrets by default.
