# GlassOS Aerial user guide

This guide covers the controls in [GlassOS Aerial](../README.md). For our Fire TV Home routing and Amazon privacy changes, see the separate [Fire TV setup record](FIRE-TV-SETUP.md).

For computer commands below, connect to your chosen TV and set `TV` to its ADB serial first:

```sh
TV="FIRE_TV_IP:5555"
adb connect "$TV"
```

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
adb -s "$TV" shell appops set $P GET_USAGE_STATS allow
adb -s "$TV" shell appops set $P SYSTEM_ALERT_WINDOW allow
adb -s "$TV" shell cmd notification allow_listener $P/$C.MediaListener
adb -s "$TV" shell dpm set-active-admin $P/$C.AdminReceiver
adb -s "$TV" shell pm grant $P android.permission.READ_EXTERNAL_STORAGE
adb -s "$TV" shell pm grant $P android.permission.WRITE_EXTERNAL_STORAGE
# Android TV only, optional: make GlassOS Aerial the default home app (Android 7 and later)
adb -s "$TV" shell cmd package set-home-activity $P/$C.MainActivity
```

That notification command is for Android 8 and later (Fire OS 7 and later). On Fire OS 5 and 6 use
`adb -s "$TV" shell settings put secure enabled_notification_listeners $P/$C.MediaListener` instead, and if another app
already has notification access, append to the list with a colon rather than replacing it (check first with
`adb -s "$TV" shell settings get secure enabled_notification_listeners`).

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

<img src="../app/src/main/res/drawable-nodpi/tmdb_logo.png" alt="TMDB" width="160">

This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming availability comes from TMDB's
watch provider data (powered by JustWatch).

### Building with your own TMDB key

Our published Aerial APK contains **no TMDB key**. To use the optional trending-title source, get your own API key at
[themoviedb.org](https://www.themoviedb.org/settings/api) and put it in `local.properties`:

```properties
tmdb.apiKey=your_v3_api_key
```

or set the `TMDB_API_KEY` environment variable. Without a key, the Top Shelf shows the built-in scenes; a key can
also be typed in Settings > TMDB API key.

## Building

Requirements: JDK 17 and the Android SDK (platform 34, build tools 34.0.0).

```sh
./gradlew testDebugUnitTest    # JVM unit tests: shuffle order, scene names, night windows, folders, backups
./gradlew assembleDebug lint   # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified with R8; signed if a keystore is configured (see ../CONTRIBUTING.md)
```

The unit tests cover the pure logic in `SceneLogic.kt`, `NightSchedule.kt`, `FolderModel.kt` and `BackupCodec.kt`, and
backup file writing (no device needed). The views, playback and remote navigation need a TV or the Android TV
emulator.

**Target API.** GlassOS Aerial is sideloaded onto TVs (Fire TV, Android TV, Google TV) and is not published on Google
Play. It deliberately keeps `targetSdk 28`, like upstream GlassOS, so it keeps the classic shared-storage behaviour it
relies on for clips in Movies/GlassAerial, backups in Documents/GlassOS-Aerial and your Pictures, including on newer
Android versions, and so it behaves the same on old Fire OS. Google Play's minimum target API does not apply to a
sideloaded launcher, so the build turns off that one lint check, `ExpiredTargetSdkVersion`
([app/build.gradle.kts](../app/build.gradle.kts)); every other lint check stays on. Raising the target API would mean moving clip and backup
access to scoped storage first.
