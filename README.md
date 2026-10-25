# GlassOS Aerial

**GlassOS Aerial** is a fork of [GlassOS](https://github.com/AbhishekKaisar/glassos-tv) by Abhishek Kaisar, a calm,
glassy home screen for Android TV, Google TV and Amazon Fire TV inspired by tvOS. The fork adds silent local aerial
video scenery and a scenery browser, an idle cinema mode, a night mode, app folders, settings backup and restore,
and lighter visual effects. Everything else is upstream GlassOS (forked at v1.0.2-beta); credit for it belongs to the
upstream project.

GlassOS and GlassOS Aerial are free software under the GNU GPL v3.

- The fork's package is `io.github.abhishekkaisar.glassos.aerial`, so it installs next to upstream GlassOS
  (`io.github.abhishekkaisar.glassos`) without replacing it. Its Android classes keep upstream's
  `io.github.abhishekkaisar.glassos` names, so adb commands need fully qualified components (see
  [Manual grants](#manual-grants)).
- Fork builds are signed with the fork's own key, not upstream's. Upstream's releases, certificate and checksums
  belong to upstream GlassOS and say nothing about this fork's APKs.

## What GlassOS Aerial adds

### Aerial scenery

Settings > Top Shelf > **Local aerial videos** plays your own clips, muted, behind the home with the platform's
hardware decoder. Only a visible, focused home holds a decoder: opening an app, the screensaver or a system dialog
releases it and saves the playhead, and coming back resumes the same clip where it stopped. Nothing keeps the
screen on, so the TV's own screensaver and sleep settings apply as usual.

Put the clips in **`/sdcard/Movies/GlassAerial/`** (`.mp4`, `.mov` or `.m4v`), each with an optional poster of the
same name (`.jpg`, `.jpeg` or `.png`) that shows while the video starts and tints the glass. GlassOS needs storage
access to read them (Settings > Permissions, or the storage prompt). GlassOS never downloads videos; copy your own,
for example:

```sh
adb push "03 - Golden Gate Bridge - San Francisco.mp4" /sdcard/Movies/GlassAerial/
adb push "03 - Golden Gate Bridge - San Francisco.jpg" /sdcard/Movies/GlassAerial/
```

Clips play in file-name order, so number them. Names are made readable for the scene label: the leading number,
underscores, slug hyphens and quality tags (4K, HDR, 1080p...) go, and ` - ` separates the scene from its place, so
`03_golden-gate_bridge 4K.mp4` reads "Golden Gate Bridge" and `07 - Victoria Harbour - Hong Kong.mov` reads
"Victoria Harbour", "Hong Kong". To name clips yourself, add an optional `scenes.json` in the same folder (at most
64 KB; anything malformed is ignored and the file names are used):

```json
{
  "03-gg.mp4": { "title": "Golden Gate Bridge", "location": "San Francisco" },
  "04-coast": "Big Sur Coast"
}
```

**Scenery** (Settings > Scenery, Control Center > Scenery, or OK on the Top Shelf) shows every clip as a poster
thumbnail over the playing video. Thumbnails are decoded at thumbnail size off the UI thread.

- **OK** on a scene plays it now. **Menu** on a scene adds it to or removes it from your **favourites** (★), or
  **hides** it. Hidden scenes stay in the browser, faded, so you can show them again (or use **Show all scenes**).
- **Next scene** and **Previous**, and the **Shuffle**, **Favourites only** and **Scene names** switches. Shuffle
  plays every eligible clip once before any repeats, and a new round never starts with the clip that just ended;
  the play order, the clip and its position survive restarting the app. Favourites only falls back to every visible
  scene when no favourite can play.
- With the Top Shelf focused, **Left** and **Right** change the scene. When a clip changes its name shows top left for
  a few seconds (turn this off with Scene names).
- Empty folder, every scene hidden, files gone or clips this TV cannot decode: the poster or the background chosen
  under "When Top Shelf art is unavailable" shows instead, and the browser says why.

### Cinema mode

After a while without input (Settings > **Cinema mode**: Off, 30 s, 1, 2, 5, 10 or 30 minutes; 1 minute by default)
the app icons, shelf and profile pill fade out, leaving the scenery and, if **Cinema clock** is on, a large clock.
The **first press of any button only wakes the home**: it is consumed (with its repeats and release), so it never
opens or moves an app. Volume buttons still change the volume. Focus comes back where it was. Cinema mode runs only
while GlassOS's home is in front and uncovered (not under Settings, a panel, a system dialog or another app), never
keeps the TV awake and changes no system setting: the Fire TV or Android TV screensaver, sleep and the GlassOS sleep
timer work exactly as before.

### Night mode

Settings > **Night mode**, with **Night starts** and **Night ends** (Left and Right step half an hour; a window like
22:00 to 07:00 runs past midnight) and **Night dimming** (low, medium, strong). Inside the window GlassOS lays a
dark layer over its scenery and app grid; menus and panels stay full brightness. It is launcher dimming only and
does not change the TV's brightness. It follows the clock, time and time zone changes using the system's minute
tick while GlassOS is open, with no timer of its own. Equal start and end times mean night mode never turns on.

### App folders

- **Create**: hold OK on an app, press Menu, **Add to a folder** > **New folder** (the folder takes the app's
  place), or Settings > **New app folder**. Name it with the on-screen keyboard.
- **Open**: OK on the folder tile. OK on an app opens it; Menu on an app offers **Remove from folder**.
  **Add apps** ticks apps in or out (an app is in one folder at a time; apps from another folder move), **Rename**,
  and **Remove folder**, which puts its apps back in the app list (nothing is uninstalled).
- A folder is one item on the top row and in the drawer: hold OK on it to move it, add it to or remove it from the
  top row, rename or remove it. Folder tiles show a small grid of their apps.
- Hidden or uninstalled apps stay members and come back in their folder when shown again or reinstalled. Every
  installed app that is not hidden is always somewhere: on the top row, in the drawer or in one folder.

### Back up and restore settings

Settings > **Back up settings** writes a versioned JSON file to **`/sdcard/Documents/GlassOS-Aerial/`** and shows
its path (`glassos-aerial-backup-YYYYMMDD-HHMMSS.json`). GlassOS asks for storage write access the first time; if it
is refused the file goes to `/sdcard/Android/data/io.github.abhishekkaisar.glassos.aerial/files/backups/`, which is
deleted with the app, and GlassOS says so. Files are written to a temporary file, synced and renamed into place.

Settings > **Restore from backup** lists the backups in those folders, newest first; choose one, check the summary
and choose **Restore**. The whole file is checked before anything changes (format, version, at most 256 KB, list
sizes, types, package names, folder ids, no app in two folders, no reference to a missing folder); an invalid file
changes nothing and the card says what is wrong. To move settings to another TV:

```sh
adb -s <old-tv> pull /sdcard/Documents/GlassOS-Aerial/glassos-aerial-backup-20261024-163012.json
adb -s <new-tv> shell mkdir -p /sdcard/Documents/GlassOS-Aerial
adb -s <new-tv> push glassos-aerial-backup-20261024-163012.json /sdcard/Documents/GlassOS-Aerial/
```

A backup holds only: the top row, drawer order and hidden apps, folders, the Top Shelf source and built-in
fallback background, focus sounds, cinema, night and effects settings, and scenery favourites, hidden scenes,
shuffle, favourites only and scene names. It never holds the TMDB key or other credentials, your name, photo or
country, setup and permission state, Home replacement and routing, the sleep timer, playback positions, device
identifiers, network details, media or signing data. Apps and clips missing on the new TV are kept in the settings
and appear once installed or copied.

### Lighter visual effects

Settings > **Reduce transparency** draws every glass panel (the shelf, the pill, Settings, Control Center, menus,
Scenery and folders) as a solid panel without sampling the blurred art, and the drawer's backdrop as a plain dark
fill; solid panels also skip the redraws glass needs when the art behind it changes. Settings > **Reduce motion**
turns off the focus spring, tile tilt, edit wiggle and sliding panels: focus, scrolling and panels change at once,
and fades are kept short. Both are off by default, keeping the upstream look: the Fire TV sticks GlassOS targets do
not report themselves as low-RAM devices, so there is no reliable signal to switch them on automatically. The art
is still blurred once per image (never per frame).

![Before and after: the Fire TV home becomes GlassOS](docs/glassos-demo.gif)

## Screenshots

See the [fork screenshots and validation record](docs/VALIDATION.md) for the new scenery, cinema and folder UI.

![Home: the Top Shelf with the glass shelf of apps](docs/screenshots/home.png)

| | |
|---|---|
| ![Control Center](docs/screenshots/control-center.png) | ![Settings](docs/screenshots/settings.png) |
| Control Center (Menu) | Settings |
| ![First-run setup: permissions](docs/screenshots/setup.png) | ![First-run setup: home screen](docs/screenshots/setup_home_step.png) |
| First-run setup, one-tap permissions | Android TV: make GlassOS your home screen |

These are upstream GlassOS screenshots. Home and Control Center are from a Fire TV Stick 4K using the Apple TV
(unofficial) Top Shelf source. TMDB is the default source and looks the same, with trending titles from your own
streaming apps. Settings and setup were taken on the Android TV emulator. The fork's additions are pictured in the validation record linked above.

## Features (from upstream GlassOS)

- **Top Shelf**: full-screen art of trending series and films, with title logos. Titles on the streaming apps you
  have installed (Netflix, Prime Video, Disney+, Apple TV+, Max and others) come first, for the country you
  choose. OK on a title opens its app.
- **Glass shelf**: up to six favourite apps on a frosted glass shelf, with the first row of the app drawer peeking
  in below it. Long press OK on any app to add it to the top row or remove it; Settings > Edit top row moves them.
- **App drawer**: every installed app, with labels, a scroll below the shelf.
- **Adaptive profile pill**: the time and your avatar in the top right, with a tint that adapts to the art behind it.
- **Control Center** (Menu, or OK on the pill): Sleep, Wi-Fi, Do Not Disturb, Audio, a sleep timer, Controllers,
  Accessibility, Parental Controls (Fire TV), Now Playing (while something plays) and VPN (while a VPN is on). On
  Android TV the buttons open the standard Settings pages.
- **Your Home button**: on Android TV GlassOS can be the default home app. Where the TV does not allow that (Fire TV,
  and Android TV or Google TV builds that keep their own home), GlassOS notices the stock home and covers it, so
  the Home button still lands in GlassOS.
- **Starts at boot**, with the Fire TV boot logo handled correctly.
- **First-run setup**: your name (on-screen keyboard), an optional profile photo, your country, your top-row apps,
  one-tap permissions and, on Android TV, a "Make GlassOS your home screen" step.
- Built-in procedural scenes (night desert, dusk ocean, aurora, dark) when there is no network, or your own
  photos from the Pictures/Glass folder.

## Install

GlassOS Aerial is not in Google Play or the Amazon Appstore. Download the APK from this fork's
[Releases](https://github.com/hnkabraham/glassos-aerial/releases) page, or build it yourself (see
[Building](#building)). The [upstream Releases](https://github.com/AbhishekKaisar/glassos-tv/releases) are upstream
GlassOS, a different app without these additions. Replacing the system home is always optional and manual; GlassOS
Aerial changes nothing in the system on its own.

### Android TV and Google TV

1. Allow installs from your installer app: Settings > Device Preferences > Security & restrictions > Unknown
   sources (on Google TV: Settings > System > Apps > Security & restrictions > Unknown sources), and turn it on
   for the app you use below.
2. Install with one of:
   - **Downloader** (by AFTVnews, in Google Play): enter the APK's URL from the Releases page.
   - **Send Files to TV**: send the APK from your phone, then open it on the TV.
   - **adb from a computer** (see below).
3. Open GlassOS. Setup starts on its own.

### Fire TV

1. Settings > My Fire TV > Developer options: turn on **Apps from Unknown Sources** (or, on newer Fire OS,
   **Install unknown apps** for Downloader). No Developer options? Open Settings > My Fire TV > About and press OK
   on your device name 7 times.
2. Install **Downloader** from the Appstore, open it and enter the APK's URL from the Releases page.
3. Install, then open GlassOS. Setup starts on its own.

### With adb from a computer

```sh
adb connect <tv-ip>:5555
adb install -r GlassOS-Aerial-1.1.0.apk
adb shell am start -n io.github.abhishekkaisar.glassos.aerial/io.github.abhishekkaisar.glassos.MainActivity
```

## Set it up with an AI coding agent

Not comfortable with adb? Open an AI coding agent (Claude Code, Codex, Cursor, Gemini CLI and similar) on your
computer and tell it:

> Set up GlassOS Aerial on my TV using AGENTS.md from this repository

[AGENTS.md](AGENTS.md) has the agent instructions for this fork (its package, release and certificate differ from
upstream GlassOS).

## Set GlassOS as your home screen

### Android TV and Google TV

In setup (or GlassOS Settings > Home screen) choose **Make Home**. GlassOS tries, in order: the system's "Set
GlassOS as your default home app?" prompt, the Default home app page in Settings, and finally
`cmd package set-home-activity` over the TV's own ADB. If Home then opens GlassOS, the step shows a tick and no
watcher runs at all.

Many Android TV and Google TV builds keep their own home no matter what is chosen: the stock launcher's Home entry
outranks any other app's, so the setting changes but Home still opens the stock home. GlassOS detects that and falls
back to the watcher (Usage access and Display over other apps, below), which brings GlassOS back after Home.

Advanced, at your own risk: if you want Home to open GlassOS directly on such a TV, you can turn off the stock
launcher from a computer. Undo it before uninstalling GlassOS, or the TV has no home screen.

```sh
# Google TV: com.google.android.apps.tv.launcherx   Android TV: com.google.android.tvlauncher
adb shell pm disable-user --user 0 com.google.android.tvlauncher
# undo
adb shell pm enable com.google.android.tvlauncher
```

### Fire TV

Fire OS does not let apps replace its home. GlassOS watches for the Fire TV home and covers it, which needs Usage
access and Display over other apps. Settings > Replace Fire TV home turns this on or off.

## One-tap permission setup

A few features need permissions that only ADB can grant. GlassOS can grant them to itself, once, using the TV's own
ADB:

1. Turn on debugging:
   - **Fire TV**: Settings > My Fire TV > Developer options > **ADB debugging**.
   - **Android TV / Google TV**: Settings > Device Preferences > About, press OK on **Build** 7 times. Then Device
     Preferences > Developer options > **USB debugging** (and **Network debugging** where the TV has it).
2. In GlassOS setup (or Settings > Permissions), choose **Set Up**.
3. The TV asks "Allow USB debugging?". Tick **Always allow from this computer** and choose **Allow**.

GlassOS connects to `127.0.0.1:5555` with its own key, runs the grant commands on itself and shows a tick next to
each permission. You can turn debugging off again afterwards.

On Android 11 and later, **Wireless debugging** uses pairing codes and a random port, so it does not open port 5555.
If GlassOS says the connection was refused, use the manual grants from a computer below (after `adb pair` and
`adb connect` as the TV's Wireless debugging screen shows), or connect once over USB and run `adb tcpip 5555`.

On Fire TV, Fire OS updates since 2024 (7.6.6.9, 8.1.0.3 and late Fire OS 6) block apps from using the TV's own ADB, so
setup says the Fire TV closed its ADB to GlassOS. Run these from a computer instead: the [manual grants](#manual-grants)
after `adb connect <tv-ip>:5555`.

Every feature works without its permission, with less:

| Permission | Used for | Without it |
|---|---|---|
| Usage access | Noticing the stock home after the Home button (not needed when GlassOS is the default home) | Home goes to the stock home; open GlassOS from your apps |
| Display over other apps | Showing GlassOS at once after Home | GlassOS still comes back, a moment later |
| Notification access | Now Playing and Do Not Disturb | Those tiles are hidden or open the TV's settings |
| Device admin | Sleep and the sleep timer | Fire TV: Sleep opens the Fire TV sleep menu. Android TV: Sleep is unavailable |
| Storage | Your profile photo and own backgrounds, aerial videos in Movies/GlassAerial, backups in Documents/GlassOS-Aerial | The glass avatar and built-in scenes; backups go to the app's own folder |

GlassOS reads no notifications: notification access is only how Android lets an app see media sessions.

### Manual grants

If you prefer a computer, these are the same grants, for both platforms. Each is optional and only for its feature.
In this fork the package (`P`) and the class names (`C`) differ, so components are written out in full:

```sh
P=io.github.abhishekkaisar.glassos.aerial
C=io.github.abhishekkaisar.glassos
adb shell appops set $P GET_USAGE_STATS allow
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener $P/$C.MediaListener
adb shell dpm set-active-admin $P/$C.AdminReceiver
adb shell pm grant $P android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant $P android.permission.WRITE_EXTERNAL_STORAGE
# Android TV only, optional: make GlassOS Aerial the default home app (Android 7 and later)
adb shell cmd package set-home-activity $P/$C.MainActivity
```

That notification command is for Android 8 and later (Fire OS 7 and later). On Fire OS 5 and 6 use
`adb shell settings put secure enabled_notification_listeners $P/$C.MediaListener` instead, and if another app
already has notification access, append to the list with a colon rather than replacing it (check first with
`adb shell settings get secure enabled_notification_listeners`).

## Top Shelf sources

Choose in Settings > Top Shelf:

- **TMDB** (default): trending titles from [TMDB](https://www.themoviedb.org/), preferring those on your installed
  streaming services in your country. Refreshed at most every 6 hours and cached for offline use.
- **Apple TV (unofficial)**: the featured titles of the Apple TV app's web catalogue. Unofficial and off by default;
  it may stop working at any time.
- **Built-in scenes**: no network at all.
- **Local aerial videos** (this fork): your own clips from Movies/GlassAerial, see [Aerial scenery](#aerial-scenery).

When a source has no art (no network, no key), the background chosen under "When Top Shelf art is unavailable" is
shown.

<img src="app/src/main/res/drawable-nodpi/tmdb_logo.png" alt="TMDB" width="160">

This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming availability comes from TMDB's
watch provider data (powered by JustWatch).

### Building with your own TMDB key

Release builds include a TMDB key. To build your own, get a free API key at
[themoviedb.org](https://www.themoviedb.org/settings/api) and put it in `local.properties`:

```properties
tmdb.apiKey=your_v3_api_key
```

or set the `TMDB_API_KEY` environment variable. Without a key, the Top Shelf shows the built-in scenes; a key can
also be typed in Settings > TMDB API key.

## Limitations

### Fire TV

- **Home takes about a second.** Fire OS does not let apps replace its home. GlassOS notices the Fire TV home and
  covers it, which takes about 0.8 to 1.8 seconds after Home from another app (no visible change when you press
  Home inside GlassOS).
- **Some Fire TV settings pages are locked** to Amazon's own apps (Display & Audio, Accessibility, Parental
  Controls, the profile picker, Amazon search). For those, GlassOS opens the Fire TV Settings grid and tells you
  which tile to choose.
- **Alexa and the remote's app buttons** still open Amazon's screens.

### Android TV and Google TV

- **Some builds block a different default home** (see above). GlassOS then uses the same watcher as on Fire TV, with
  the same short delay after Home.
- **Parental controls** have no standard Settings page on stock Android TV, so that button is hidden. **Switch
  Profile** opens the accounts and profiles page where the TV has one, and is hidden where it does not.
- **Google Assistant and the remote's app buttons** still open Google's screens.

### Both

- **Opening a title**: TMDB has no app-specific title ids, so OK on a TMDB title opens the streaming app rather than
  the title itself.

### Tested on (upstream GlassOS)

- Android TV emulator (Android 12, API 31): setup, the home step (prompt, Default home app page and ADB), GlassOS as
  the default home (Home and boot), the watcher fallback when the stock launcher keeps Home, and the Control Center
  Settings buttons.
- Fire TV Stick 4K (Fire OS 6, API 25): the private build this release comes from, daily use.

Not yet tried on real Android TV or Google TV hardware (Nvidia Shield, Chromecast with Google TV, Xiaomi Mi Box,
Onn) or on Fire OS 7 and 8. Reports are welcome.

## Uninstall and restore the stock home

GlassOS changes nothing in the system itself, so removing it brings everything back.

### Android TV and Google TV

- **Keep it, but use the stock home**: Settings > Apps > Default apps > Home app (or Settings > Apps > Special app
  access > Default home app, depending on the build), and choose the stock home. If GlassOS was covering the stock
  home instead, turn off GlassOS Settings > Replace Android TV home (or Google TV home).
- **Remove it**:
  1. GlassOS Settings > **Prepare to uninstall**. This removes GlassOS's device admin (Android blocks uninstalling
     an active device admin) and stops it covering the stock home.
  2. If you turned off the stock launcher with adb, turn it back on first: `adb shell pm enable <launcher package>`.
  3. Settings > Apps > GlassOS > **Uninstall**, or `adb uninstall io.github.abhishekkaisar.glassos`. Android goes
     back to the stock home on its own.
     For this fork: `adb uninstall io.github.abhishekkaisar.glassos.aerial`.

### Fire TV

- **Keep it, but use the Fire TV home**: GlassOS Settings > Replace Fire TV home: **Off**.
- **Remove it**:
  1. GlassOS Settings > **Prepare to uninstall**.
  2. Fire TV Settings > Applications > Manage Installed Applications > GlassOS Aerial > **Uninstall**, or from a
     computer: `adb uninstall io.github.abhishekkaisar.glassos.aerial` (upstream GlassOS:
     `adb uninstall io.github.abhishekkaisar.glassos`).

Backups in Documents/GlassOS-Aerial and clips in Movies/GlassAerial are your files and stay after uninstalling.

## Building

Requirements: JDK 17 and the Android SDK (platform 34, build tools 34.0.0).

```sh
./gradlew testDebugUnitTest    # JVM unit tests: shuffle order, scene names, night windows, folders, backups
./gradlew assembleDebug lint   # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified with R8; signed if a keystore is configured (see CONTRIBUTING.md)
```

The unit tests cover the pure logic in `SceneLogic.kt`, `NightSchedule.kt`, `FolderModel.kt` and `BackupCodec.kt`, and
backup file writing (no device needed). The views, playback and remote navigation need a TV or the Android TV
emulator.

**Target API.** GlassOS Aerial is sideloaded onto TVs (Fire TV, Android TV, Google TV) and is not published on Google
Play. It deliberately keeps `targetSdk 28`, like upstream GlassOS, so it keeps the classic shared-storage behaviour it
relies on for clips in Movies/GlassAerial, backups in Documents/GlassOS-Aerial and your Pictures, including on newer
Android versions, and so it behaves the same on old Fire OS. Google Play's minimum target API does not apply to a
sideloaded launcher, so the build turns off that one lint check, `ExpiredTargetSdkVersion`
(`app/build.gradle.kts`); every other lint check stays on. Raising the target API would mean moving clip and backup
access to scoped storage first.

## Contributing

Issues with the fork's additions belong in this fork's repository; issues with GlassOS itself belong upstream. See
[CONTRIBUTING.md](CONTRIBUTING.md) for how to build (no keys needed) and test on an emulator. Upstream questions go
in GlassOS's [Discussions](https://github.com/AbhishekKaisar/glassos-tv/discussions).

## Licence

GNU General Public License v3.0. See [LICENSE](LICENSE). GlassOS is by Abhishek Kaisar and contributors;
GlassOS Aerial's changes are under the same licence.

---

GlassOS is not affiliated with or endorsed by Google, Amazon or Apple. Android TV and Google TV are trademarks of
Google LLC. Fire TV is a trademark of Amazon.com, Inc.
