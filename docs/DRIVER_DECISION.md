# Driver Decision: Virtual Xbox 360 / XInput controller on Windows

Status: **Decided (provisional)** — ViGEmBus behind `IVirtualGamepad`. Date: 2026-11-19.

## Candidates

| Option | XInput (Xbox 360) | Maintained | License | Signed | Notes |
|---|---|---|---|---|---|
| **ViGEmBus 1.22.0** + `Nefarius.ViGEm.Client` 1.21.256 | Yes (virtual X360 pad, true XInput) | **No — retired/archived 2023-11-02** (trademark conflict with ViGEM GmbH). Final release v1.22.0 | Driver BSD-3-Clause; client MIT | Yes, Microsoft attestation/WHQL-signed; works with HVCI on Win10/11 | Needs admin to install once; no admin to use. Installed already on the dev PC (v1.17.333, 2020) |
| **Nefarius VirtualPad** | Intended successor | Active | **Proprietary / commercial**, redistribution restricted | Yes | Not usable in an open-source project without a license from the author |
| **vJoy** | **No** (DirectInput only, not XInput) | Sporadic | Open | Signed forks exist | Many modern games ignore DirectInput devices; does not meet "genuine Xbox-compatible controller" |
| Keyboard/mouse emulation (SendInput) | No | n/a | n/a | n/a | Explicitly excluded by the product spec |
| Own KMDF/UMDF driver | Possible | Us | Us | Needs EV cert + Microsoft attestation signing | Out of scope for MVP; months of work and signing cost |

Sources: Nefarius docs "End of Life Statement" (docs.nefarius.at/projects/ViGEm/End-of-Life), github.com/nefarius/ViGEmBus releases (v1.22.0, 2023-11-02), NuGet Nefarius.ViGEm.Client 1.21.256 (MIT).

## Decision

Use **ViGEmBus** as the first driver implementation because it is the only signed, open-licensed driver that exposes a real XInput pad today, and it is **already installed** on the target machine (no driver installation or security change needed by this project).

Mitigations for the retired-upstream risk:
1. All output goes through `IVirtualGamepad` (`Phone2Gamepad.VirtualGamepad`); networking and UI never reference ViGEm types.
2. The app never installs the driver. If it is missing, the dashboard shows "driver not installed" with instructions linking to the final signed release (v1.22.0) and asks the user to install it themselves.
3. We do **not** bundle the driver in our installer (redistribution to be reviewed with its BSD-3 terms; install is explicit and user-initiated).
4. Revisit when VirtualPad licensing is clarified, or if a maintained open alternative appears.

## Limitations / caveats
- No security updates will come from upstream. The driver runs in kernel mode; users should install only the official signed release and be aware of this.
- Installed version on the dev machine is 1.17.333 (2020), older than the final 1.22.0; both expose the same Xbox 360 target API used here.
- Rumble/vibration feedback is available from the driver but is **not** wired to the phone in v1 (not shown as functional in the UI).
- Windows 10/11 only (x64/ARM64).

## Install / remove / upgrade / troubleshoot
- Install: download `ViGEmBus_1.22.0_x64_x86_arm64.exe` from the official GitHub releases page; run as administrator; reboot if prompted.
- Verify: Device Manager → "System devices" → *Nefarius Virtual Gamepad Emulation Bus*; with the app connected, `joy.cpl` shows "Controller (Xbox 360 for Windows)".
- Upgrade: run the newer installer over the old one. Remove: Apps & features → ViGEm Bus Driver.
- Troubleshooting: see `TROUBLESHOOTING.md`.
