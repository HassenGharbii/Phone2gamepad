# Phone2Gamepad Protocol v1

Normative, language-neutral. Both the Kotlin and C# implementations MUST match this document byte-for-byte.
All multi-byte integers are **little-endian**. Strings are UTF-8.

## 1. Ports (defaults, configurable on the PC)

| Purpose | Transport | Port |
|---|---|---|
| Discovery | UDP broadcast/unicast | 47820 |
| Control (pairing, session) | TCP | 47821 |
| Input + heartbeat | UDP | 47822 |

Discovery is separate from the input channel. Nothing is ever sent to the Internet.

## 2. Discovery (UDP 47820, unauthenticated, no secrets)

Request (phone → broadcast), 8 bytes:

| Off | Size | Field |
|---|---|---|
| 0 | 2 | magic `0x50 0x32` ("P2") |
| 2 | 1 | version = 1 |
| 3 | 1 | type = `0x10` DISCOVERY_REQUEST |
| 4 | 4 | request nonce u32 (echoed in response) |

Response (PC → requester unicast), variable:

| Off | Size | Field |
|---|---|---|
| 0 | 2 | magic |
| 2 | 1 | version = 1 |
| 3 | 1 | type = `0x11` DISCOVERY_RESPONSE |
| 4 | 4 | request nonce (echo) |
| 8 | 2 | tcp port u16 |
| 10 | 2 | udp port u16 |
| 12 | 1 | flags: bit0 = pairing currently open |
| 13 | 1 | name length N (1..63) |
| 14 | N | PC name UTF-8 |

Receivers MUST ignore datagrams with wrong magic/version/length.

## 3. UDP common header (12 bytes) — input channel

| Off | Size | Field |
|---|---|---|
| 0 | 2 | magic `0x50 0x32` |
| 2 | 1 | version = 1 |
| 3 | 1 | type |
| 4 | 4 | session id u32 (assigned by PC) |
| 8 | 4 | sequence u32 (per direction, starts at 1, increments per datagram) |

Types: `0x01` STATE (phone→PC), `0x02` HEARTBEAT (both directions).

Every UDP input-channel packet ends with a **16-byte tag** = first 16 bytes of
`HMAC-SHA256(key_dir, all preceding bytes)`. Direction keys: `k_c2s` (phone→PC), `k_s2c` (PC→phone); see §6.

### 3.1 STATE packet (type 0x01) — total 46 bytes

| Off | Size | Field | Notes |
|---|---|---|---|
| 0 | 12 | header | |
| 12 | 4 | timestamp ms u32 | sender clock, ms since session start, wraps |
| 16 | 2 | buttons u16 | bitmask, below |
| 18 | 1 | dpad u8 | bit0 up, bit1 down, bit2 left, bit3 right |
| 19 | 1 | reserved u8 | sender MUST send 0; receiver ignores |
| 20 | 2 | left stick X i16 | |
| 22 | 2 | left stick Y i16 | positive = up |
| 24 | 2 | right stick X i16 | |
| 26 | 2 | right stick Y i16 | positive = up |
| 28 | 1 | left trigger u8 | 0..255 |
| 29 | 1 | right trigger u8 | 0..255 |
| 30 | 16 | tag | |

Button bits: 0=A 1=B 2=X 3=Y 4=LB 5=RB 6=Back 7=Start 8=L3 9=R3 10=Guide; bits 11–15 reserved (ignored, sender sends 0).
Stick range: −32768..32767, neutral 0. The value −32768 is legal on the wire; any symmetric clamping is a driver-boundary concern.
Dpad: opposite directions pressed at once (up+down, left+right) are legal on the wire; the Windows side resolves them to neutral on that axis. Bits 4–7 reserved.

### 3.2 HEARTBEAT packet (type 0x02) — total 32 bytes

| Off | Size | Field |
|---|---|---|
| 0 | 12 | header |
| 12 | 4 | sender timestamp ms u32 |
| 16 | 4 | echo timestamp u32 = most recent peer timestamp received, or `0xFFFFFFFF` if none |
| 20 | 2 | echo delay ms u16 = ms between receiving that peer timestamp and sending this heartbeat (saturating) |
| 22 | 2 | reserved (0) |
| 24 | 16 | tag |

Total = header(12) + ts(4) + echo(4) + delay(2) + reserved(2) + tag(16) = **40 bytes**.
RTT (computed by the heartbeat sender when it receives the peer's heartbeat) = `now − echo − delay` (mod 2^32), valid only if echo ≠ 0xFFFFFFFF.

Heartbeats are sent by both sides at 2 Hz. The phone additionally sends STATE at ≥30 Hz while the gamepad screen is active, even if unchanged.

## 4. Validation (receiver, in this order; failure = drop + increment counter, never act)

1. Length equals the exact size for the type (STATE 46, HEARTBEAT 40).
2. Magic and version match (unknown version → drop, counted as `unknownVersion`).
3. Session id equals the current active session id.
4. Source IP equals the TCP control connection's peer IP.
5. Tag verifies (constant-time compare).
6. Sequence is "newer" than the highest accepted: `(int32)(seq − last) > 0` (serial-number arithmetic; wraparound-safe). Duplicates/old → dropped (`outOfOrder` / `duplicate`).
7. Only after all pass: apply state. Reserved bits are ignored.

Stale input: if no valid STATE/HEARTBEAT is accepted for **500 ms** the PC neutralizes the controller; after **5 s** the session is terminated. Neutral = all buttons 0, dpad 0, sticks 0, triggers 0. On TCP close the session ends immediately.

## 5. TCP control channel (port 47821)

Frames: `u32 length` (LE, 1..16384) followed by `length` bytes of UTF-8 JSON object with a string field `"t"` (message type). Binary values are base64 (standard, padded). Unknown fields ignored; unknown `t` → `error` reply. Max 4 failed frames before the PC closes the connection. The channel is unencrypted; every secret-bearing step is protected by the constructions in §6 (no secrets, PINs or keys are ever sent in the clear).

### 5.1 Messages

| `t` | Dir | Fields |
|---|---|---|
| `hello` | C→S | `ver`:1, `deviceId` (≤64 chars, random UUID generated at install), `deviceName` (≤64), `caps`:[strings] |
| `hello_ok` | S→C | `ver`:1, `serverName`, `serverId` (optional, ≤64 chars: random GUID generated once per PC install; clients SHOULD key stored pairing secrets by it, falling back to `serverName` when absent), `paired`:bool (is this deviceId paired?), `pairingOpen`:bool, `udpPort`, `caps`:[strings] |
| `pair_request` | C→S | `pub` (base64 uncompressed P‑256 point, 65 bytes), `nonce` (base64 16 random bytes) |
| `pair_challenge` | S→C | `pub` (base64 65 bytes), `nonce` (base64 16 bytes) |
| `pair_proof` | C→S | `proof` (base64 32 bytes) — client computed from the PIN |
| `pair_result` | S→C | `ok`:bool, `proof` (base64 32 bytes; present iff ok), `reason` (if !ok) |
| `session_request` | C→S | `nonce` (base64 16 bytes) |
| `session_challenge` | S→C | `nonce` (base64 16 bytes) |
| `session_auth` | C→S | `mac` (base64 32 bytes) |
| `session_start` | S→C | `ok`:bool, `sessionId`:u32, `mac` (base64 32 bytes), `reason` |
| `config_get` / `config_info` | C→S / S→C | reserved for later (server answers `error` `unsupported`) |
| `disconnect` | both | `reason` |
| `error` | both | `code` (string), `message` |

Error codes: `bad_frame`, `bad_version`, `unsupported`, `not_paired`, `pairing_closed`, `bad_pin`, `rate_limited`, `rejected_by_user`, `auth_failed`, `busy` (a session is already active), `internal`.

`hello` MUST be the first message; `deviceId`/`deviceName` are advisory identifiers, not credentials.

## 6. Pairing & session keys (all standard primitives, no custom crypto)

Primitives: ECDH P‑256, HKDF‑SHA256 (RFC 5869), HMAC‑SHA256.

### 6.1 Pairing (one time, PIN + on-PC confirmation)

PC shows a **6-digit PIN** valid for **120 s**, single use; max **5** wrong proofs per pairing window (then closed) and a global limit of 1 failed attempt per second per source IP.
1. C→S `pair_request{pub_c, nonce_c}`. PC generates ephemeral P‑256 key pair, replies `pair_challenge{pub_s, nonce_s}` (only if pairing window is open).
2. Both compute `shared = ECDH(priv, peer_pub)` (32-byte x coordinate), `salt = nonce_c || nonce_s`.
3. `confirmKey = HKDF(ikm = shared || ASCII(PIN), salt, info = "P2G confirm v1", L = 32)`.
4. Phone sends `proof_c = HMAC(confirmKey, "client" || pub_c || pub_s)` (pubs are the 65-byte raw points).
5. PC verifies `proof_c`; on failure → `pair_result{ok:false, reason:"bad_pin"}` (counts toward limit). On success the PC UI asks the user "Allow *deviceName*?" — only if the user accepts → `pair_result{ok:true, proof_s = HMAC(confirmKey, "server" || pub_c || pub_s)}`; decline → `rejected_by_user`.
6. Phone verifies `proof_s` (this authenticates the PC), then both derive `deviceSecret = HKDF(ikm = shared, salt, info = "P2G device secret v1", L = 32)` and store it:
   - Android: encrypted with an Android Keystore AES-GCM key, stored in app-private storage.
   - Windows: protected with DPAPI (`ProtectedData`, CurrentUser) in the app data folder, keyed by `deviceId`.
7. A passive eavesdropper learns nothing useful (ECDH); an active MITM gets one online guess at the PIN per pairing window attempt.

PIN and secrets are never logged.

### 6.2 Session (every connection)

1. `hello` → `hello_ok{paired:true}` else the phone must pair first.
2. C→S `session_request{nonce_c}`; S→C `session_challenge{nonce_s}`.
3. `mac_c = HMAC(deviceSecret, "auth-c" || nonce_c || nonce_s)` in `session_auth`.
4. PC verifies, allocates a random non-zero `sessionId` u32, replies `session_start{ok, sessionId, mac_s = HMAC(deviceSecret, "auth-s" || nonce_c || nonce_s || u32le(sessionId))}`. Phone verifies `mac_s`.
5. `okm = HKDF(ikm = deviceSecret, salt = nonce_c || nonce_s, info = "P2G session v1" || u32le(sessionId), L = 64)`; `k_c2s = okm[0..32]`, `k_s2c = okm[32..64]`.
6. Phone starts UDP to `udpPort` of the PC. Fresh nonces ⇒ fresh keys on every reconnect, so an old session's packets never validate. Key rotation = new session (reconnect); sessions are not long-lived.
7. Only one active session in v1 (`busy` otherwise). A new session with the same deviceId replaces the old one.

Revocation: PC deletes the deviceSecret; the next `session_auth` fails (`auth_failed`) / `hello_ok.paired=false`.

## 7. Versioning and compatibility

`version` is 1. Receivers reject other versions (UDP: drop; TCP: `bad_version` and close). Additive changes in later versions use new message types or reserved bits; v1 receivers ignore reserved bits and unknown JSON fields.

## 8. Test vectors

`test-vectors/vectors.json` contains hex packets (valid and invalid) with expected decode results, plus fixed-key tag/KDF vectors. Both implementations must pass them. Generated by `scripts/gen_vectors.py` (independent Python reference implementation); do not hand edit.
