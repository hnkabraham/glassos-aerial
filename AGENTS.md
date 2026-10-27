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
adb -s <device> install -r GlassOS-Aerial-1.5.0.apk
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

JDK 17 and the Android SDK (platform 34, build tools 34.0.0). No keys needed. The screen-mirroring module
`airplay/` also needs NDK 27.0.12077973 and CMake 3.22.1 (`sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"`) and,
on the host, perl, GNU make, patch and python3, which build OpenSSL from source. The first build downloads the
OpenSSL source tarball once (SHA-256 checked) into `.cache/airplay-downloads/`; later clean builds reuse it
offline. `-Pglassos.airplay.native=false` needs none of this: the APK then has no native libraries and the
feature stays hidden.

```sh
./gradlew testDebugUnitTest assembleDebug lint     # app/build/outputs/apk/debug/app-debug.apk
python3 tools/airplay/check_apk.py app/build/outputs/apk/debug/app-debug.apk   # native packaging (CI runs it)
```

CI (`.github/workflows/build.yml`) runs the same command on every pull request and must pass. `main` is protected:
open a pull request.

## Layout

App code is in `app/src/main/java/io/github/abhishekkaisar/glassos/`; the screen-mirroring receiver is the
`airplay/` module. Kotlin, plain Android Views, no AndroidX, minSdk 22 (Fire OS 5 and 6 must keep working).

- `Platform.kt`: Fire TV versus Android TV (`isFireTv`), stock homes, `isDefaultHome`, Settings intents. Platform
  differences belong here or behind `Platform.isFireTv`, not scattered.
- `MainActivity.kt`, `HomeScreen.kt`, `HeroView.kt`, `HeroRepo.kt`, `Tmdb.kt`: home, Top Shelf and its data.
- `AppUsage.kt`, `UsageOrder.kt`: local foreground-time ranking, with manual-layout preservation.
- `AppLock.kt`, `AppLockGuard.kt`, `ManagerProvider.kt`: app lock, its foreground guard, and the manager's ADB-only controls (`content call`).
- `AerialDream.kt`: the screensaver (DreamService), its view and its preview activity.
- `SunTimes.kt`, `ZoneCoords.kt`, `SunNow.kt`: sunrise/sunset math, time-zone places (generated by `tools/gen_zone_coords.py`; do not edit) and today's cached times.
- `manager/`: loopback-only Python companion (`sun.py` mirrors `SunTimes.kt`); private data belongs outside the repository. Run `python3 -m unittest discover -s manager/tests -v`. Never publish its state, tokens, media or device reports.
- `tools/reliability_check.py`: explicit-device lifecycle checks; output stays private.
- `AerialVideoView.kt`, `SceneLogic.kt`, `SceneCollections.kt`: local video lifecycle, scene metadata, filtering and playlist.
- `Panels.kt`, `FolderModel.kt`: remote-driven scenery/folder panels and folder layout.
- `Backup.kt`, `BackupCodec.kt`, `NightSchedule.kt`: portable settings I/O/validation and dimming schedules.
- `ControlCenter.kt`, `SysControl.kt`: Control Center and the system actions behind it.
- `Setup.kt`, `Grants.kt`, `AdbClient.kt`: first-run setup and the self-ADB grants.
- `HomeWatcher.kt`, `HomeOverlay.kt`, `BootReceiver.kt`: covering the stock home and starting at boot.
- `airplay/`: screen-mirroring receiver (AirPlay protocol; in progress). `src/main/cpp/` holds the JNI bridge and
  CMake build adapted from jqssun/android-airplay-server (each file keeps its "Adapted from" header) and, under
  `third_party/`, vendored UxPlay, libplist and openssl-cmake. Never edit `third_party/` by hand: change a pin in
  `tools/airplay/vendor.sh` or a patch in `src/main/cpp/patches/`, rerun the script, and check that
  `tools/airplay/vendor.sh --verify` shows no differences. The Kotlin JNI classes stay in jqssun's package
  `io.github.jqssun.airplay.bridge`, because the native symbol names depend on it. Licences:
  `THIRD_PARTY_NOTICES.md`, copied with the licence texts into `src/main/assets/licenses/` (check_apk.py fails if
  the copy differs). The receiver (`service/AirPlayService.kt`) runs alone in the `:airplay` process with all native
  code; the launcher reaches it only through `AirPlay.kt` (start, apply, stop and status over a Messenger, with
  `Supervisor.kt` restarting it after a crash) and never loads the library. Kotlin classes ported from jqssun keep
  the "Adapted from" header too (`vendor.sh --verify` checks it). The receiver screen (`ui/AirPlayActivity.kt`,
  `ui/ReceiverView.kt`; when it opens and closes is `ui/ReceiverScreen.kt`) runs in `:airplay` too; while a mirrored
  screen plays, `ScreenMirroring.kt` holds the scenery decoder (`SceneSession.hold`). Off unless the `airplay_on` pref
  is set. On the launcher's side, `ScreenMirroring.kt` also turns it on and off and starts it after boot, an update
  (`BootReceiver`) or when the home resumes; `MirrorState.kt` is the tile's and Settings' text and logic, the Settings
  section is in `Overlays.kt`, the tile (`Glyph.MIRROR`) in `ControlCenter.kt`, the name editor and the About licence
  reader in `Setup.kt` (its pure part, `Licences.kt`, orders, titles and reflows `assets/licenses`). User-facing text says "Screen Mirroring", never "AirPlay Server" or "AirPlay 2", and no Apple
  glyph (docs/SCREEN-MIRRORING.md). `tools/airplay/make_source_bundle.sh` builds a release's source tarball. Over
  ADB, ManagerProvider's `airplay-status`, `airplay-start`, `airplay-stop`, `airplay-show` and `airplay-set key=value`
  drive it (debug builds add `airplay-debug feed-mp4 PATH`, which plays an MP4 in as a mirror
  session, and `airplay-debug pin NNNN`), and `tools/airplay/probe.py HOST PORT` checks its RTSP `/info` answer.

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
