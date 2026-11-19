# Installation

No installer exists yet; this describes the current build-from-source setup. See `ROADMAP.md`.

## 1. Virtual controller driver (one time, by you)
The app never installs drivers. Install **ViGEmBus** (final signed release v1.22.0, from the official GitHub releases page) as administrator; reboot if asked. Check Device Manager → System devices → *Nefarius Virtual Gamepad Emulation Bus*. Background and risks: `DRIVER_DECISION.md`.

## 2. Windows companion
Build (see `BUILD.md`) and run `Phone2Gamepad.Windows`. Click **Start server**. The dashboard lists your local IPs and ports.

**Firewall:** on first start Windows may ask to allow the app on *private* networks — allow private only. Ports used: UDP 47820, TCP 47821, UDP 47822 (inbound). The app never changes firewall rules itself. If the phone can't find the PC, add an inbound rule yourself for the app executable on the Private profile.

**Start with Windows** is off by default; the app asks before enabling it. **Uninstall:** delete the app folder; paired-device data is in `%APPDATA%` (Phone2Gamepad). ViGEmBus is removed separately via Apps & features.

## 3. Android app
Enable "install unknown apps" for your file manager or use `adb install -r app-debug.apk` (see `BUILD.md`). Requires Android 9+. Permissions: internet/network state/Wi-Fi state/vibrate only.

## 4. Pair
1. Put phone and PC on the same Wi-Fi network (guest/"client isolation" networks block discovery).
2. On the PC, click **Open pairing** — a 6-digit PIN (valid 120 s) appears.
3. On the phone, pick the PC (or enter its IP and port 47821 manually) and enter the PIN.
4. Approve the phone on the PC prompt.
Later connections skip the PIN. Revoke a phone from the paired devices list on the PC.

## 5. Verify
Open the gamepad screen. In Windows run `joy.cpl`: **Controller (Xbox 360 for Windows)** should respond. Then test in an XInput game.
