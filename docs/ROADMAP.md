# Roadmap

Legend: ✅ built and tested · 🟡 built, partially verified · ⬜ not started

## Implemented
- ✅ Shared protocol v1 + independent test vectors (Python reference; C# and Kotlin both pass)
- ✅ Windows server: discovery, PIN pairing with confirmation/rate limit, DPAPI store, sessions, UDP input, neutralization (52 tests; verified through XInput against real ViGEmBus with a simulated phone)
- ✅ `IVirtualGamepad` with ViGEm and mock implementations
- 🟡 Android app: discovery, pairing, Keystore storage keyed by PC `serverId`, session/reconnect, landscape gamepad, profiles (create/rename/duplicate/delete/import/export), layout editor, deadzone/sensitivity/curve/trigger mode (123 JVM tests; 8 bundled vector skins per profile, skin gallery, creator, `.p2gskin` import/export, in-game Menu → Edit layout/Skins; profiles screen, editor and reconnect-after-update checked on a real phone; Tuning screen, import/export and resize not hand-checked)
- 🟡 Windows WPF dashboard with tray, diagnostics export (redacted), log level, config migration, `serverId` in handshake: 85 tests; tray Exit and window-close shutdown verified programmatically; tray/startup prompt not hand-checked
- ✅ Real phone ↔ real Windows app (hand-tested): discovery, PIN pairing, all 14 XInput inputs, analog triggers/sticks, multi-touch, Wi-Fi-drop neutralization, auto-reconnect. Owner reports it works on hardwaretester.com/gamepad (browser Gamepad API). Still unverified: a real game/`joy.cpl`, stability, tray/startup prompt (see `TESTING.md`)

- 🟡 Android skins: 8 bundled original vector skins, per-profile selection, gallery, custom skin creator, `.p2gskin` export/import (data-only, strict validation); in-game Menu → Edit layout / Skins (123 JVM tests; gallery, editor preview and menu checked on a real phone; creator and file import/export not hand-checked)

## Not yet built
- ⬜ Online skin catalog / marketplace (deliberately not built: needs an opt-in, review and moderation design first)
- ⬜ Online skin catalog (opt-in; needs a separate privacy/security decision — skins are shared as `.p2gskin` files for now)
- ⬜ QR pairing
- ⬜ Installer, release signing, clean-machine test, long-run stability test, real-game test
- ⬜ Advanced: gyro, turbo, macros, USB transport, multi-phone, PC→phone vibration, extra layouts

## Known limits
UDP input is authenticated, not encrypted. ViGEmBus is retired upstream. Tested only on one phone (Android 14) and one PC.
