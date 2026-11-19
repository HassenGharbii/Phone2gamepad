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

### Publishing (portable zips)

```powershell
scripts\publish-windows.ps1          # -SkipFrameworkDependent to build only the self-contained variant
```

Output goes to the git-ignored `dist\` folder:

| Artifact | Size (measured, v0.1.0) | Needs |
|---|---|---|
| `Phone2Gamepad-<ver>-win-x64-selfcontained.zip` | ~65 MB zip, ~155 MB unpacked | nothing (runtime included) |
| `Phone2Gamepad-<ver>-win-x64-framework-dependent.zip` | ~0.6 MB zip | the .NET 10 Desktop Runtime (x64) |
| `SHA256SUMS.txt` | | |

Each zip contains `README-first-run.txt` (driver, firewall, pairing). Only English UI resources are kept
(`SatelliteResourceLanguages=en`; the app UI is English). WPF is not trim-compatible, so no trimming is applied.

**Versioning:** the single source is the `VERSION` file at the repo root (read by `windows-app/Directory.Build.props`,
`publish-windows.ps1` and the installer script). The Android `versionName`/`versionCode` in `android-app/app/build.gradle.kts`
are still edited by hand; `build-android-release.ps1` warns when they differ from `VERSION`.

**Verified:** the publish script was run and both zips were produced; the *published* self-contained `Phone2Gamepad.exe` was
launched and reached its single-instance check (see Installation) and exited cleanly. The server start of the published exe
was **not** verified (the owner's dev instance owned the ports and the single-instance mutex).

### Installer (Inno Setup) - written, NOT compiled or tested

`installer/phone2gamepad.iss` is an Inno Setup 6 script. Inno Setup is not installed on the build machine, so the script has
**never been compiled or run**; expect to fix small issues on first use. Build: `scripts\publish-windows.ps1`, then
`iscc installer\phone2gamepad.iss` (optionally `/DAppVersion=x.y.z`). Output: `dist\Phone2Gamepad-Setup-<ver>.exe`.
Design: per-user install (no admin), Start Menu shortcut, no start-with-Windows, no driver install, no firewall changes;
an unchecked task opens the first-run/driver instructions; uninstall asks (default **No**) before deleting
`%LOCALAPPDATA%\Phone2Gamepad` (paired devices and settings). The `AppMutex` value (`Local\Phone2Gamepad.SingleInstance`)
is unverified against Inno's mutex lookup.

### Continuous integration

`.github/workflows/ci.yml` builds and tests the Windows solution (.NET 10, `windows-latest`) and runs the Android unit tests +
`assembleDebug` (JDK 17, API 36) on every push to `main` and on pull requests. The YAML parses, but **the workflow has not
run on GitHub yet**; the first run may need adjustments (for example Android SDK package names).

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

Signing is optional and wired in `app/build.gradle.kts`: when `android-app/keystore.properties` (git-ignored) or the
`P2G_KEYSTORE_FILE`, `P2G_KEYSTORE_PASSWORD`, `P2G_KEY_ALIAS`, `P2G_KEY_PASSWORD` environment variables are present **and** the
keystore file exists, the `release` build type is signed; otherwise no signing config is created and debug builds and unit tests are
unaffected.

```powershell
scripts\make-release-keystore.ps1     # once: interactive keytool, writes the .jks OUTSIDE the repo + keystore.properties
scriptsuild-android-release.ps1     # assembleRelease + apksigner verify -> dist\Phone2Gamepad-<ver>-release.apk
```

`keystore.properties` format: `storeFile=C:/path/p2g-release.jks`, `storePassword=...`, `keyAlias=...`, `keyPassword=...`.
**Back up the keystore and its passwords** (two offline copies): without it you can never ship an update that installs over the
same app id. Never commit them.

**Verified:** the Gradle wiring, using a throwaway test keystore in a scratch project copy: `signingReport` shows a `release`
config with credentials, and none without credentials or with a missing keystore file; `./gradlew help` passes on the real project.
**Not run:** `make-release-keystore.ps1` (interactive), and the full `build-android-release.ps1` (`assembleRelease` + `apksigner`);
no real release key exists yet.

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
