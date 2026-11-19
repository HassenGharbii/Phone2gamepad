# Roadmap

Legend: ✅ built and tested · 🟡 built, partially verified · ⬜ not started

## Implemented
- ✅ Shared protocol v1 + independent test vectors (Python reference; C# and Kotlin both pass)
- ✅ Windows server: discovery, PIN pairing with confirmation/rate limit, DPAPI store, sessions, UDP input, neutralization (52 tests; verified through XInput against real ViGEmBus with a simulated phone)
- ✅ `IVirtualGamepad` with ViGEm and mock implementations
- 🟡 Android app: discovery, pairing, Keystore storage keyed by PC `serverId`, session/reconnect, landscape gamepad, profiles (create/rename/duplicate/delete/import/export), layout editor, deadzone/sensitivity/curve/trigger mode (123 JVM tests; 8 bundled vector skins per profile, skin gallery, creator, `.p2gskin` import/export, in-game Menu → Edit layout/Skins; profiles screen, editor and reconnect-after-update checked on a real phone; Tuning screen, import/export and resize not hand-checked)
- 🟡 Windows app UI: Windows 11-style design system (dark/light/system + high-contrast tokens), left navigation (Home, Controller, Phones, Mapping, Diagnostics, Settings), first-run checklist, live controller drawing, styled pairing approval (delayed Allow), phone rename/remove, remembered window placement, tray status badge + notifications; 104 tests; screenshots verified; tray menu/notifications and high contrast not visually checked
- ✅ Real phone ↔ real Windows app (hand-tested): discovery, PIN pairing, all 14 XInput inputs, analog triggers/sticks, multi-touch, Wi-Fi-drop neutralization, auto-reconnect. Owner reports it works on hardwaretester.com/gamepad (browser Gamepad API). Still unverified: a real game/`joy.cpl`, stability, tray/startup prompt (see `TESTING.md`)

- 🟡 Android skins: 8 bundled original vector skins, per-profile selection, gallery, custom skin creator, `.p2gskin` export/import (data-only, strict validation); in-game Menu → Edit layout / Skins (123 JVM tests; gallery, editor preview and menu checked on a real phone; creator and file import/export not hand-checked)

- 🟡 Android controller types: Xbox, Racing (touch wheel, analog pedals, paddle shifters) and Fighting (big 8-way pad, six domed attack buttons, optional turbo) drawn as controller shells with built-in vector art or a private picture printed on them; stored per profile; 149 JVM tests; wheel, pedals and fighting buttons checked through XInput on a real phone; Tuning wheel sliders and multi-finger wheel+pedal use not hand-checked

- 🟡 Android UI/UX round 6: design system (tokens, typography, components, local icons), onboarding (3 steps), PIN boxes with paste/auto-submit and specific errors, Home with auto-connect and Play, Customize hub with live preview, grouped Settings, minimal gamepad HUD, landscape two-pane layouts (175 JVM tests; checked on a real phone; TalkBack and light theme not done)

## Not yet built
- ⬜ Online skin catalog / marketplace (deliberately not built: needs an opt-in, review and moderation design first)
- ⬜ Online skin catalog (opt-in; needs a separate privacy/security decision — skins are shared as `.p2gskin` files for now)
- ⬜ QR pairing
- 🟡 Packaging: portable zips (self-contained ~65 MB / framework-dependent ~0.6 MB) built by `scripts/publish-windows.ps1` and the published exe launch-tested; Inno Setup script written but **not compiled**; Android release signing wired (config verified, real release build + apksigner not run, no release key yet); GitHub Actions CI written, **not yet run on GitHub**
- ⬜ Clean-machine install test, long-run stability test, real-game test
- ⬜ Advanced: gyro, turbo, macros, USB transport, multi-phone, PC→phone vibration, more controller types (e.g. flight stick)

## Known limits
UDP input is authenticated, not encrypted. ViGEmBus is retired upstream. Tested only on one phone (Android 14) and one PC.

- Racing controller round 5: realistic wheel (grip texture, stitching, spokes, hub, GPU-rotated), metal pedals with travel animation and gauge, curved paddle shifters, handbrake lever, optional tilt steering (off by default), full Tuning section (167 JVM tests; checked on a real phone except tilt direction/scale, haptics and multi-finger use).
