Phone2Gamepad (Windows companion) - first run
=============================================

1. Virtual controller driver (required, installed by you)
   Phone2Gamepad creates a virtual Xbox 360 controller through the ViGEmBus driver.
   This package does NOT include or install the driver. Install the final signed
   ViGEmBus release (v1.22.0) from the official GitHub releases page, as administrator,
   and reboot if asked. The app shows "driver not installed" until it is present.
   Background and risks: docs/DRIVER_DECISION.md in the project repository.

2. Start the app
   Run Phone2Gamepad.exe and click "Start server". The dashboard shows your local IPs
   and ports (TCP 47821, UDP 47822, UDP 47820).

3. Windows Firewall
   Windows may ask whether to allow Phone2Gamepad on the network. Allow PRIVATE networks
   only. The app never changes firewall rules itself. Never port-forward these ports.

4. Pair the phone
   Phone and PC on the same Wi-Fi. In the app click "Open pairing" - a 6-digit PIN
   (valid 120 seconds) appears. On the phone choose this PC (or enter its IP and port
   47821), type the PIN, then approve the phone in the prompt on this PC.

5. Verify
   Open the gamepad screen on the phone, then run joy.cpl: "Controller (Xbox 360 for
   Windows)" should respond.

Start-with-Windows is OFF by default; the app asks before enabling it.
To uninstall the portable version, close the app (tray icon -> Exit) and delete this
folder. Paired-device data and settings live in %LOCALAPPDATA%\Phone2Gamepad - delete that
folder too if you want to remove all pairings.
