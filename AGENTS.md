# AGENTS.md

This repository is **GlassOS Aerial**, a fork of [GlassOS](https://github.com/AbhishekKaisar/glassos-tv).
Use these instructions for the fork. Its package and signing certificate differ from the upstream app.

# Part 1: Installing GlassOS Aerial on a TV

Help in plain language. Use the device and permissions already established in the conversation; only ask for
missing information. Explain the next meaningful action, then carry out work the user has authorized.

## Scope and privacy

- Install the requested launcher and the user's own local media. Do not disable system apps, apply root exploits,
  change firmware update settings, reboot, or alter the Home route unless the user specifically requests that work.
- Keep device addresses, logs, app-data backups and personal screenshots private. Use neutral emulator captures for
  public UI screenshots. Never publish signing keys, passwords, API keys or cached third-party videos.
- Keep the previous APK before upgrading. Use `adb install -r`; a signing mismatch is a reason to stop and explain,
  not to uninstall and lose the user's settings.
- The app's preferences backup excludes credentials, device permissions and Home routing. Media is copied separately.
- Android TV / Fire TV system launcher behavior depends on firmware. Do not promise that installing this APK makes
  it the permanent Home app. Existing Launcher Manager configurations can continue selecting GlassOS Aerial.

## Download and verify

Fork releases: https://github.com/hnkabraham/glassos-aerial/releases
Upstream releases are a different application and do not contain these fork features. GitHub's latest shortcut can
omit prereleases; inspect the release list. Download only the selected fork's release asset or build from reviewed
source. Compare the APK checksum with that release's checksums and verify its certificate with `apksigner`.

The personal fork release certificate SHA-256 is:

```
235c4b2e2dc61d035bbcde530ab3ac94c8a2f653ffa9607ea9e87d379d8d0441
```

Self-built debug APKs use a different key unless explicitly signed with the release key. Never request the private
key just to install a release. If the certificate or checksum does not match the selected release, explain and stop.

## Install on the selected device

Use Android Platform Tools from Google. Honor any existing ADB authorization and use an explicit serial when more
than one device is attached. For a newly connected device, the person must accept its ADB authorization prompt.

```sh
adb devices -l
adb -s <device> install -r GlassOS-Aerial-1.1.0.apk
adb -s <device> shell am start -n io.github.abhishekkaisar.glassos.aerial/io.github.abhishekkaisar.glassos.MainActivity
```

Allow storage access when the app requests it for local scenery or backups. On supported Android-based Fire TVs,
local MP4/MOV/M4V clips go in `/sdcard/Movies/GlassAerial/`, with optional matching JPEG posters and `scenes.json`.
Use the README for settings and backup/import instructions. Grant optional overlay, usage, notification or device
admin permissions only for the corresponding feature the person wants. Do not assume they are needed for scenery.

The package is `io.github.abhishekkaisar.glassos.aerial`, but Android component class names remain under
`io.github.abhishekkaisar.glassos`. Always use fully qualified components, rather than appending `.MainActivity` or
`.AdminReceiver` to the suffixed application ID.

## Verify and report

Check the installed version, actual Home behavior, remote navigation, silent scenery, and that launching another
app releases the video decoder. Report only observed results. If a step fails, diagnose within the authorized scope;
do not introduce system modifications as a workaround. Explain any remaining manual step clearly.

## Recovery

Restore a working Home route before removing a launcher that currently handles Home. For a routine app regression,
keep app data and use a previously verified APK with the same signing key; Android may require an explicit downgrade
option. Do not uninstall to force a downgrade. The app's **Prepare to uninstall** action removes its optional device
admin state when the user actually asks to uninstall.

---

# Part 2: Working on the code

## Build

JDK 17 and the Android SDK (platform 34, build tools 34.0.0). No keys needed.

```sh
./gradlew testDebugUnitTest assembleDebug lint     # app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/build.yml`) runs the same command on every pull request and must pass. `main` is protected:
open a pull request.

## Layout

All code is in `app/src/main/java/io/github/abhishekkaisar/glassos/`. Kotlin, plain Android Views, no AndroidX,
minSdk 22 (Fire OS 5 and 6 must keep working).

- `Platform.kt`: Fire TV versus Android TV (`isFireTv`), stock homes, `isDefaultHome`, Settings intents. Platform
  differences belong here or behind `Platform.isFireTv`, not scattered.
- `MainActivity.kt`, `HomeScreen.kt`, `HeroView.kt`, `HeroRepo.kt`, `Tmdb.kt`: home, Top Shelf and its data.
- `AerialVideoView.kt`, `SceneLogic.kt`: local video lifecycle, scene metadata, filtering and playlist.
- `Panels.kt`, `FolderModel.kt`: remote-driven scenery/folder panels and folder layout.
- `Backup.kt`, `BackupCodec.kt`, `NightSchedule.kt`: portable settings I/O/validation and dimming schedules.
- `ControlCenter.kt`, `SysControl.kt`: Control Center and the system actions behind it.
- `Setup.kt`, `Grants.kt`, `AdbClient.kt`: first-run setup and the self-ADB grants.
- `HomeWatcher.kt`, `HomeOverlay.kt`, `BootReceiver.kt`: covering the stock home and starting at boot.

## Test

Create an Android TV emulator (Device Manager > Television, API 28 or newer), `adb install -r` the debug APK and drive
it with the arrow keys, Enter, Escape and Home. The emulator does not exercise Fire TV paths; say in the pull request
what you could not test. See CONTRIBUTING.md.

## Rules

- No secrets in commits: no TMDB key, keystore, passwords or private IP addresses. `local.properties` and
  `~/.glass-release` stay untracked.
- Match the surrounding code style. Keep drawing cheap (TV GPUs are slow: blur once and cache).
- Keep the look identical unless the change is about design. Add before and after screenshots for any UI change.
- Do not add a dependency without a strong reason.
