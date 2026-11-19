# Phone2Gamepad

Turn an Android phone into a wireless Xbox-style gamepad for Windows. The phone shows an on-screen controller and sends input over the local network; a Windows companion app exposes a virtual Xbox 360 (XInput) controller that games recognize.

No cloud, no accounts, no telemetry. Everything stays on your LAN.

```
android-app/      Kotlin + Jetpack Compose app
windows-app/      .NET 10 WPF companion + core/network/driver libraries + tests
shared/protocol/  PROTOCOL.md and cross-language test vectors
docs/             architecture, driver decision, security, build, install, testing
```

## Status
See [docs/ROADMAP.md](docs/ROADMAP.md) for what is implemented, in progress and planned. Nothing is claimed as working unless it has been built and tested; see [docs/TESTING.md](docs/TESTING.md) for what was actually verified.

## Quick start
1. Install **ViGEmBus** (see [docs/DRIVER_DECISION.md](docs/DRIVER_DECISION.md)); the app never installs drivers itself.
2. Build and run the Windows app, click *Start server*, allow the firewall prompt for private networks.
3. Install the APK on the phone, same Wi-Fi network, choose your PC, enter the PIN shown on the PC, accept on the PC.
4. Open the gamepad screen. Check `joy.cpl` for "Controller (Xbox 360 for Windows)".

Details: [docs/BUILD.md](docs/BUILD.md), [docs/INSTALLATION.md](docs/INSTALLATION.md).

Screenshots: _placeholders — to be added._
