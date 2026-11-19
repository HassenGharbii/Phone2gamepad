# Building Phone2Gamepad

Prerequisites on this repo's reference machine: Windows 11, .NET SDK 10.0.x, JDK 17, Android SDK API 36 (`ANDROID_HOME`).

## Windows app

```powershell
cd windows-app
dotnet build Phone2Gamepad.sln
dotnet test tests/Phone2Gamepad.Tests
dotnet run --project Phone2Gamepad.Windows          # WPF dashboard
dotnet run --project Phone2Gamepad.HeadlessHost -- --open-pairing   # console host, prints PIN
```

Projects: `Phone2Gamepad.Core` (protocol, crypto, mapping), `.Network` (discovery, pairing, sessions, UDP input),
`.VirtualGamepad` (`IVirtualGamepad`, ViGEm + mock), `.Windows` (WPF UI), `tests/`.
`Nefarius.ViGEm.Client` is pinned to 1.21.256.

### Publishing

| Option | Command | Tradeoff |
|---|---|---|
| Framework-dependent | `dotnet publish Phone2Gamepad.Windows -c Release -r win-x64 --self-contained false` | Small; user needs the .NET 10 Desktop Runtime |
| Self-contained | `dotnet publish Phone2Gamepad.Windows -c Release -r win-x64 --self-contained true` | ~70+ MB, no runtime install needed |

No installer is produced yet (see ROADMAP). The ViGEmBus driver is never bundled or installed by the app.

## Android app

### Requirements

| Tool | Version used |
|---|---|
| JDK | 17 (Temurin 17.0.20) |
| Android SDK | platform `android-36`, build-tools 35/36 (`ANDROID_HOME=C:\Android`) |
| Gradle | 9.9.1 via the checked-in wrapper (`android-app/gradlew`) |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin) |
| Kotlin / Compose compiler plugin | 2.4.21 |
| Compose BOM | 2026.03.01 |
| activity-compose / lifecycle-*-compose | 1.12.4 / 2.10.0 |
| DataStore preferences | 1.2.1 |
| kotlinx-coroutines | 1.11.0 |
| Test: JUnit 4.13.2, org.json 20260814 | |

Newer Compose/lifecycle releases (BOM 2026.09, lifecycle 2.11, activity 1.13) require `compileSdk 37`; the pins
above are the newest set that builds against the installed API 36 platform. To upgrade, install the API 37
platform with `sdkmanager "platforms;android-37"` and raise `compileSdk`.

minSdk 28 (Android 9), targetSdk 36. The only permissions are `INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_WIFI_STATE` and `VIBRATE`; there is no analytics, account or cloud dependency.

### Build and test

```powershell
cd android-app
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
.\gradlew testDebugUnitTest      # JVM unit tests, incl. shared/protocol/test-vectors/vectors.json
.\gradlew assembleDebug          # app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The unit tests read `shared/protocol/test-vectors/vectors.json` directly (added as a test resource directory in
`app/build.gradle.kts`), so the Kotlin implementation is checked byte-for-byte against the independent Python
reference generator (`scripts/gen_vectors.py`).

### Release build / signing

`.\gradlew assembleRelease` produces an unsigned APK. Create a keystore once
(`keytool -genkeypair -v -keystore p2g-release.jks -alias p2g -keyalg RSA -keysize 4096 -validity 10000`), keep it
and its passwords out of source control, and sign with `apksigner` or a `signingConfigs` block that reads them from
environment variables. Release signing has not been set up or tested in this repository yet.

### Permissions explained

* `INTERNET`: sockets to the PC on the local network (TCP 47821, UDP 47820/47822). No external hosts are contacted.
* `ACCESS_NETWORK_STATE`: shows whether the active network is Wi-Fi/Ethernet in the status overlay.
* `ACCESS_WIFI_STATE`: reserved for broadcast discovery on Wi-Fi.
* `VIBRATE`: optional haptic feedback (off switch in Settings).

### Testing the app without the Windows companion

`android-app/tools/fake_pc_server.py` is an independent Python implementation of the PC side of `PROTOCOL.md`
(discovery, PIN pairing with auto-accept, session keys, UDP validation, heartbeats) that logs every decoded
controller state. `pip install cryptography`, then
`python android-app/tools/fake_pc_server.py --pin 123456 [--tcp 47831 --udp 47832]` and connect from the phone
(use the manual IP screen when non-default ports are used). It is a development aid only, not the real companion.
