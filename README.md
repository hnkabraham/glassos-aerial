# GlassOS Aerial

**A quiet TV home screen with local aerial videos, your apps, and controls that work with the remote.**

This is our version of [GlassOS](https://github.com/AbhishekKaisar/glassos-tv), built around silent scenery that plays behind the app icons. Videos stay on the device, pause when another app opens, and resume when you come home. The fork adds scenery controls, cinema mode, scheduled dimming, folders, portable settings, lighter visual effects, and automatic app ordering by time spent.

**[Download the signed APK](https://github.com/hnkabraham/glassos-aerial/releases/tag/v1.4.0-aerial.1)** · **[Mac manager](manager/README.md)** · **[User guide](docs/USAGE.md)** · **[Fire TV launcher & privacy setup](docs/FIRE-TV-SETUP.md)** · **[Validation](docs/VALIDATION.md)**

Based on GlassOS v1.0.2-beta by **Abhishek Kaisar and contributors**. Their launcher is the foundation of this project; our additions use the same GPLv3 license.

## What we added

| Feature | What it does |
| --- | --- |
| Mac companion | Manage devices, verified updates, local video import, checked transfers and portable backups. [Manager guide](manager/README.md). |
| Collections & daily rhythm | Filter scenery by place and switch collections throughout the day using the TV’s local clock. |
| Playback recovery | Skip stalled clips, retry repaired files and refresh changed posters and titles. |
| Uninstall from Home | Hold OK on an app, choose Uninstall, then confirm in the TV’s own dialog. Available in both app-order modes and inside folders. |
| Most-used app order | Automatically ranks the top row, drawer and folder contents by recent foreground time. Manual mode restores your saved arrangement. |
| Local aerial videos | Silent, hardware-decoded scenery behind the icons; releases the decoder when Home loses focus and resumes the saved position on return. |
| Scenery browser | Poster thumbnails, next/previous, favourites, hidden scenes, scene names, and persistent shuffle without repeats. |
| Cinema mode | Fades the icons after inactivity, with an optional clock. The first remote press wakes the home without launching an app. |
| Night mode | Dims the home on a schedule, including overnight windows, with three strengths. |
| App folders | Create, name, reorder and edit folders in the top row or app drawer. |
| Backup and restore | Portable JSON for your layout, folders, display settings and scenery choices. |
| Lighter effects | Reduce transparency and motion for simpler panels and navigation. |

**Settings > App order > Most used** enables automatic sorting. It uses the TV’s local Usage access, keeps usage history out of backups, and leaves Amazon’s privacy opt-outs unchanged. See [how the ranking works](docs/USAGE.md#most-used-app-order).

The original glass panels, app drawer, remote navigation, Control Center, sleep timer and optional movie/TV artwork sources remain available.

## Our interface

![Local Mac scenery manager with generated test media](docs/validation/manager-library.jpg)

![Manual and Most used app-order choices](docs/validation/usage-order-settings.png)

These captures show our fork on an Android TV emulator with generated test clips. The simple colours are QA fixtures; on a TV the background is whichever aerial videos you supply.

| Scenery browser | Cinema mode |
| --- | --- |
| ![Scenery browser with favourites and scene controls](docs/validation/scenery.png) | ![Cinema mode with clock](docs/validation/cinema.png) |

![Folder panel with app membership and rename controls](docs/validation/folder-panel.png)

## Install and add scenery

Current release: **1.4.0-aerial.1**, version code **7**. Download `GlassOS-Aerial-1.4.0.apk` from our release page. Our APK uses `io.github.abhishekkaisar.glassos.aerial` and its own signing key, so it installs alongside upstream GlassOS. Updating an earlier Aerial build with the same key preserves its settings.

For ADB installation, enable debugging on the TV, accept its computer authorization prompt, and substitute its address:

```sh
TV="FIRE_TV_IP:5555"
adb connect "$TV"
adb -s "$TV" install -r GlassOS-Aerial-1.4.0.apk
adb -s "$TV" shell am start -n io.github.abhishekkaisar.glassos.aerial/io.github.abhishekkaisar.glassos.MainActivity
```

Finish setup, allow storage access and select **Settings > Top Shelf > Local aerial videos**. Copy your clips and optional matching stills into this folder:

```sh
adb -s "$TV" shell mkdir -p /sdcard/Movies/GlassAerial
adb -s "$TV" push "01-coast.mp4" /sdcard/Movies/GlassAerial/
adb -s "$TV" push "01-coast.jpg" /sdcard/Movies/GlassAerial/
```

MP4, MOV and M4V files are discovered by filename; decoding depends on the TV. Our tested library uses 1080p H.264, no audio, and matching JPEG posters. Return Home or reopen Scenery after copying a new library; the Mac manager requests a refresh after syncing.

Our personal setup has **20 compressed, 90-second clips**: about **30 minutes** of scenery using **1.09 GB** including posters. The videos are not included in this public repository or APK. Supply media you have permission to use. The app does not download scenery and needs no video API or account. The published APK has no embedded TMDB key; that optional artwork source requires your own key.

**Cinema mode defaults to 1 minute** with its clock enabled. App order starts in Manual; shuffle, night mode and reduced effects start off. All controls are in Settings; Scenery is also in Control Center. See the [full guide](docs/USAGE.md) for remote shortcuts, scene metadata and permission commands.

## How we made it the Fire TV launcher

Installing the APK alone does not change Fire OS's protected Home routing. Our tested configuration uses:

| Tool | What we used it for |
| --- | --- |
| ADB from a computer | Install apps, inspect the device and verify package/Home state. |
| [GhostLock karat v1.0.4](https://xdaforums.com/t/temp-root-fire-tv-stick-4k-2nd-gen-series-karat-mantra.4798627/) | Temporary root during the original device setup, allowing protected launcher/update settings to be changed. |
| [Launcher Manager — System User Edition 2.0.0](https://xdaforums.com/t/system-user-fire-cube-stick-tv-tablet-ps7704-fireos7-rs8149-fireos8.4759215/) | Own the native Home handler and launch GlassOS Aerial, with **Custom Launcher Support** and **Proxy Launcher Mode** enabled. |

The resulting route is:

```text
Home button / startup
  → Launcher Manager: com.wolf.tn/.ui.LMUHandler
  → GlassOS Aerial: io.github.abhishekkaisar.glassos.aerial/
                   io.github.abhishekkaisar.glassos.MainActivity
```

In this configuration, GlassOS **Replace Fire TV home is Off** because Launcher Manager handles Home. The Amazon launcher package remains enabled for Fire TV Settings; its `HomeActivity_vNext` component and `com.amazon.firehomestarter` were disabled. The old upstream GlassOS package was later disabled to prevent its boot receiver competing with Aerial.

We also blocked `com.amazon.device.software.ota`, `com.amazon.device.software.ota.override` and `com.amazon.sneakpeek`. **This blocks Fire OS security updates as well as feature updates.** It preserves the tested firmware, but does not guarantee that the configuration can never be undone.

This was verified on a **Fire TV Stick 4K Max, 2nd generation (AFTKRT / karat), Fire OS 8.1.8.5, build RS8185.3891N**. Temporary root disappeared after reboot while the Home route persisted. Two later attempts to regain root failed, including one kernel panic and automatic reboot. Treat this as a record of one tested device; check the authors' current model/kernel requirements before attempting another stick.

The [detailed setup record](docs/FIRE-TV-SETUP.md) includes the exact component changes, verification commands, tool hashes, and recovery notes. On a stick where Launcher Manager already owns Home, simply select **GlassOS Aerial** as its custom launcher; there is no reason to repeat the root step for an app upgrade. On ordinary Android TV, use **Settings > Home screen > Make Home**; available methods depend on its firmware.

## What we disabled for Amazon privacy

We turned **Device Usage Data**, **Collect App Usage Data**, and **Interest-Based Ads** off through Amazon's Settings UI, then checked the stored values after reboot. We also used ADB Package Manager to disable these packages for the primary user:

| Package | Observed result |
| --- | --- |
| `com.amazon.hybridadidservice` | Advertising-ID package disabled; no matching process observed after reboot. |
| `com.amazon.wirelessmetrics.service` | Wireless-metrics package disabled; no matching process observed after reboot. |
| `com.amazon.neo.minerva` | Android package disabled, but its native service still ran. |
| `com.amazon.neo.minerva.logcat` | Android package disabled, but its native service still ran. |

`com.amazon.device.metrics` was protected by Fire OS and remained enabled. We left `com.amazon.client.metrics.api`, shared logging/framework packages and privacy cloud components intact.

**This is partial telemetry reduction, not complete removal or an irreversible lock.** The Settings switches still exist. Native Amazon services and individual apps may still collect data, and resets or firmware changes can undo the setup. We did not perform a network-traffic audit proving that telemetry stopped.

The [privacy commands and checks](docs/FIRE-TV-SETUP.md#amazon-privacy-changes) document the successful disables, the Minerva limitation, the five opt-out values and how to restore package defaults. These are device changes made separately from the launcher; installing GlassOS Aerial does not apply them.

## Move your setup to another stick

1. Install the same signed Aerial APK and copy the video/poster folder.
2. On the first TV, choose **Settings > Back up settings**.
3. Copy the exported JSON from `/sdcard/Documents/GlassOS-Aerial/` to that folder on the new TV.
4. Choose **Settings > Restore from backup** on the new TV.
5. Configure its Home route separately, using the method supported by that model and firmware.

Backups include layout, folders, display and scenery choices. They exclude credentials, personal profile details, permissions, Home routing, device identifiers and media. If shared-storage write permission is refused, backups use the app's external files folder and are deleted on uninstall; the app shows that path explicitly.

## Tested configuration

- **63 JVM tests and 28 Python tests passed**, covering launcher behavior, schedules, backup validation, local API access and transfer failures.
- Debug and signed release builds passed; lint reported **0 errors** (113 warnings).
- Android TV API 31 emulator: remote navigation, scenery, folders, cinema wake, dimming/effects and backup/import.
- Fire TV Stick 4K Max / Fire OS 8.1.8.5: verified manager upgrade, all 20 scenes preserved, 10 Home/Settings cycles and sleep/wake. The emulator passed 12 cycles and sleep/wake. Sampled playback was muted and hardware-decoded.
- Persistent Home/startup and privacy opt-outs were checked after reboot during the earlier device setup. Reboot behavior was not retested for this release. Other physical models and older Fire OS versions are not validated for this release.

See [validation details and screenshots](docs/VALIDATION.md). The app retains minSdk 22; that is not a claim that the GhostLock/Launcher Manager method works on every supported Android version.

## Building

Use **JDK 17**, Android SDK platform **34**, and build tools **34.0.0**:

```sh
./gradlew testDebugUnitTest assembleDebug lint
./gradlew assembleRelease
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`. Release builds are unsigned unless you configure your own keystore. Keep signing keys and API keys outside the repository. A differently signed APK cannot update our published APK in place.

The launcher deliberately targets API 28 for its existing shared-storage behavior. The Play target-SDK age lint check is disabled; other checks remain enabled. See the [build notes](docs/USAGE.md#building) and [contribution guide](CONTRIBUTING.md). AI setup instructions are in [AGENTS.md](AGENTS.md).

## Recovery and uninstalling

If Launcher Manager owns Home, **restore a working Home route before uninstalling GlassOS Aerial or Launcher Manager**. Turning off GlassOS's overlay setting alone does not undo the native route. Restore through Launcher Manager with the required privileges and verify stock Home and Settings first; details are in [recovery](docs/FIRE-TV-SETUP.md#recovery).

For a normal Android TV default-home setup, select another Home app first. GlassOS **Prepare to uninstall** removes its optional device-admin state. Shared Documents backups and Movies clips remain after uninstalling; app-private backups do not.

## Credits and license

- **Abhishek Kaisar and contributors:** [upstream GlassOS](https://github.com/AbhishekKaisar/glassos-tv), the foundation of this fork.
- **GhostLock and Launcher Manager authors:** the separate device tools linked above. They are not bundled with our APK.
- **This fork:** local aerial playback and the controls documented here. File fork-specific issues in [our tracker](https://github.com/hnkabraham/glassos-aerial/issues).

**GNU GPL v3.0** — see [LICENSE](LICENSE). Independent project; not affiliated with Amazon, Google or Apple. Fire TV, Android TV and Apple TV are their respective owners' trademarks. The optional TMDB integration is not endorsed or certified by TMDB; streaming availability data comes through TMDB/JustWatch.
