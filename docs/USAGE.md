# GlassOS Aerial user guide

This guide covers the controls in [GlassOS Aerial](../README.md). For our Fire TV Home routing and Amazon privacy changes, see the separate [Fire TV setup record](FIRE-TV-SETUP.md).

For computer commands below, connect to your chosen TV and set `TV` to its ADB serial first:

```sh
TV="FIRE_TV_IP:5555"
adb connect "$TV"
```

## What GlassOS Aerial adds

### Uninstall apps from Home

Highlight an app in the top row or drawer, **hold OK**, then choose **Uninstall**. The TV opens its own confirmation dialog; confirm there to remove the app, or choose **Cancel** / press Back to keep it. The app list refreshes after successful removal. Uninstalling can delete that app’s local data; **Hide app** only removes its icon from GlassOS.

The shortcut works in **Manual** and **Most used** mode. In Manual mode, hold OK and choose **Move** to rearrange an app; arrows move it, OK saves and Back cancels. Inside a folder, hold OK or press Menu on a member for its options. Folder tiles themselves offer **Remove folder**, which leaves their apps installed.

Uninstall is offered only for downloaded apps, excluding GlassOS Aerial itself and the current native Home handler (such as the configured Launcher Manager). Built-in apps and updates to system apps are managed through the TV’s own Settings instead. Android may impose further restrictions, such as device-admin requirements.

The launcher requests the standard [uninstall confirmation](https://developer.android.com/reference/android/content/Intent#ACTION_UNINSTALL_PACKAGE) with [REQUEST_DELETE_PACKAGES](https://developer.android.com/reference/android/Manifest.permission#REQUEST_DELETE_PACKAGES). It does not use root, self-ADB, silent deletion, or all-user removal. No permissions prompt or setup change is needed for this normal manifest permission.

### App lock

Highlight an app, **hold OK**, then choose **Lock app**. The first time, choose a code: press any 4 of the arrows, Rewind, Play/Pause or Fast forward (a button can repeat), then press the same 4 again. OK, Back, Home and Menu are never part of a code. A locked app shows a small padlock on its tile, in the top row, the drawer and inside folders.

Opening a locked app from GlassOS (its tile, a folder or the Top Shelf) asks for the code; Back cancels. To lock it however it opens (a phone casting to it, Alexa, the remote's app buttons), GlassOS needs **Usage access** and **Display over other apps** (Settings > Permissions). A small background guard then watches which app comes to the front, as the Home watcher does, and covers a locked one with the code screen. The app beneath stops; the right code lets it carry on, and Back goes Home. The app stays open until another app or Home comes to the front or the TV sleeps (a screensaver coming on does not count, including Amazon's Ambient Experience on Fire TV, which is not yet tested there), so opening it again while it is already open and unlocked is not interrupted. A cast that arrives while the code screen is up may need to be sent again from the phone once the app is unlocked. Without those permissions, locking still works from GlassOS and the lock card says what is missing.

Hold OK and choose **Unlock app** to remove one lock (it needs the code). **Settings > App lock** shows how many apps are locked; with the code it offers **Change code** and **Turn off app lock**, which unlocks every app and forgets the code. After 5 wrong codes in a row, entry waits 30 seconds. GlassOS itself, home apps (such as Launcher Manager) and screensavers cannot be locked. A locked app that is uninstalled stays locked if it is reinstalled.

The code is stored only as a salted SHA-256 hash in GlassOS's private settings. Backups never include the code or the locked apps, so a restored backup neither carries nor removes a lock.

**Limits.** This is a launcher lock, not a security boundary. Someone who can use the TV's own Settings can force stop or clear GlassOS, which turns the guard off until GlassOS opens again. Locking Fire TV Settings as well closes most of those routes, but then a forgotten code needs ADB. The guard checks every 120 ms with the screen on, so a cast or voice-opened app can show for a moment before the code screen covers it; just after the Home button, Android can delay the cover by up to 5 seconds. Amazon's own parental-controls PIN is separate and unaffected.

**Forgot the code?** With the [Mac manager](../manager/README.md#tv-controls), choose the TV, open **TV controls** and choose **Set a new lock code**. The old code is not needed, and locked apps stay locked. **Turn off app lock** there unlocks every app and forgets the code instead. Both need ADB debugging on the TV and GlassOS 1.6.0 or later; the TV shows a notice when either is used. Without the manager, back up settings, clear GlassOS's data (Fire TV Settings > Applications > Manage installed applications > GlassOS Aerial > Clear data, or `adb shell pm clear io.github.abhishekkaisar.glassos.aerial`), finish setup again and choose **Restore from backup**. Clearing data also forgets your name, TMDB key and setup choices; check Settings > Permissions afterwards.

The manager's route adds no new way past the lock. Anyone who can use the TV's ADB can already defeat it (`pm clear`, `appops`, force stop), so GlassOS accepts these controls only from the TV's ADB shell; other apps on the TV cannot use them. A new code travels to the TV over ADB's network connection as plain text, so set it on a home network you trust.

### Most-used app order

Choose **Settings > App order > Most used**. The six most-used top-level items move to the top row; the rest of the drawer follows in descending usage order. Folder contents sort too, and each folder ranks by its visible members' combined time. Hidden and uninstalled apps do not contribute to that folder's score. Equal scores keep the order from your saved layout. Home-handler apps (including Launcher Manager), screensavers and Fire TV Ambient Experience receive no usage score, so idle/setup time does not promote them. They remain available in the app list.

Usage means **time the app was in the foreground**, rather than the number of launches or background-service time. GlassOS asks Android for the preceding 30 days using [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager#queryAndAggregateUsageStats(long,long)) and [foreground duration](https://developer.android.com/reference/android/app/usage/UsageStats#getTotalTimeInForeground()). Android may supply whole day/week/month buckets and limited retained history, so the UI says **about 30 days**. This is not an exact watch-time counter.

The ranking refreshes when you return Home. If a query finishes after you start navigating, its result waits for the next return instead of moving a tile under your remote. There is no periodic polling or extra background service. Choosing the mode in Settings can update the grid beneath that panel.

**Usage access is required**, separately from Amazon's Collect App Usage Data switch. Enable it through GlassOS **Settings > Permissions**, or grant only this permission from an authorized computer:

```sh
adb -s "$TV" shell appops set io.github.abhishekkaisar.glassos.aerial GET_USAGE_STATS allow
```

GlassOS does not change Amazon privacy settings or re-enable the blocked telemetry packages. Ranking history stays in memory; GlassOS does not save, log or upload it, and it is excluded from settings exports. Only the Manual/Most used preference travels in a backup. If access is denied or no usable history exists, the launcher keeps the manual layout and shows a status message in Settings.

**Manual** restores the saved top row, drawer and folder-member ordering. In Most used mode, holding OK on a tile offers folder, hide and eligible uninstall actions; choose **Use manual order** to move tiles yourself. **Edit top row** and **Top row apps** explain that they need Manual mode before changing the saved layout. Creating, removing or editing a folder updates the underlying manual membership without saving the automatic ranking over it.

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
  a few seconds (turn this off with Scene names); in cinema mode and the screensaver it shows bottom left while the
  cinema clock is in the top left corner, and makes way if the clock moves to it.
- Empty folder, every scene hidden, files gone or clips this TV cannot decode: the poster or the background chosen
  under "When Top Shelf art is unavailable" shows instead, and the browser says why.

### Collections and daily rhythm

Open **Scenery → Collection** to choose All scenes, Coastlines, Mountains, Forests & gardens, Deserts, Underwater, Space, Cities, Other, or Scheduled. Untagged clips belong to Other. Favourites and hidden-scene choices still apply. If the selected collection has no playable eligible clips, GlassOS uses your other eligible scenes and explains the fallback in Scenery.

The [Mac manager](../manager/README.md) edits titles, categories and daily start times, prepares clips, and syncs them. Returning Home rescans the library; manager transfers also request a refresh. Replacing a failed video under the same filename lets it retry without restarting the launcher. Updated scene names and posters refresh in the open scenery browser.

For a manually managed library, extend `scenes.json` with collection IDs:

```json
{
  "01-coast.mp4": {"title": "Evening Coast", "location": "Oregon", "collections": ["coast"]},
  "02-mountains.mp4": {"title": "Mountain Morning", "collections": ["mountains"]}
}
```

The IDs are `coast`, `mountains`, `forest`, `desert`, `underwater`, `space`, `city` and `other`. A clip may have several. Existing title-only metadata remains compatible.

An optional `scenery.json` alongside the videos defines the daily schedule:

```json
{
  "version": 1,
  "schedule": [
    {"start": 420, "collection": "coast"},
    {"start": 720, "collection": "mountains"},
    {"start": 1200, "collection": "space"}
  ]
}
```

`start` is a whole number of minutes after local midnight: 420 is 07:00, 720 is 12:00, and 1200 is 20:00. There may be 1–24 entries with distinct times; the last wraps into the following morning. `all` is also a valid scheduled collection. The file is limited to 8 KB. Malformed or missing schedules use All scenes. Choose **Scheduled** on the TV to enable it; the default remains All scenes. Minute ticks, clock/timezone changes and returning Home refresh the selection. The schedule is media metadata; a settings backup includes the selected mode but does not carry this file.

An entry can also follow the sun. In the manager, set its **Starts at** to Sunrise or Sunset and give an offset in minutes. In the file, add `anchor` (`"sunrise"` or `"sunset"`) and `offset` (whole minutes from −240 to 240; negative is before):

```json
{"start": 1113, "anchor": "sunset", "offset": 30, "collection": "space"}
```

GlassOS 1.6.0 and later starts this entry 30 minutes after today's sunset, worked out each day for the TV's place (see [Sun times](#sun-times)). `start` is still required, and starts must still differ. The manager sets it to that day's time in the TV's time zone's main city when you save and sync; a place set from the Mac never goes into this file. GlassOS uses it on days without a sunrise or sunset and when it has no place. GlassOS 1.5.x and earlier read only `start`, so they keep the times of your last sync. An unknown `anchor` or an `offset` out of range leaves that entry at its `start`.

### Cinema mode

After a while without input (Settings > **Cinema mode**: Off, 30 s, 1, 2, 5, 10 or 30 minutes; 1 minute by default)
the app icons, shelf and profile pill fade out, leaving the scenery and, if **Cinema clock** is on, a large clock.
The **first press of any button only wakes the home**: it is consumed (with its repeats and release), so it never
opens or moves an app. Volume buttons still change the volume. Focus comes back where it was. Cinema mode runs only
while GlassOS's home is in front and uncovered (not under Settings, a panel, a system dialog or another app), never
keeps the TV awake and changes no system setting: the Fire TV or Android TV screensaver, sleep and the GlassOS sleep
timer work exactly as before.

### Screensaver

GlassOS can be the TV's screensaver. When the TV is idle in any app, it plays the same scenery as the home: the clips
in Movies/GlassAerial (whatever the Top Shelf shows), with your collection, favourites, shuffle and daily rhythm,
carrying on from where the home stopped. Scene names, the cinema clock (and Move cinema clock) and night mode follow
your Settings. It does not start while a video is playing, and the first button press wakes the TV. With no clips, or
without storage access, it shows the background chosen under "When Top Shelf art is unavailable" (with the cinema
clock, if it is on); it never ends on its own.

GlassOS cannot choose its own screensaver: that is a protected TV setting, changed over ADB. In the
[Mac manager](../manager/README.md#tv-controls), choose the TV, open **TV controls**, choose **Check** in the
Screensaver panel, then **Use GlassOS scenery**. Its confirmation lists every setting it changes. The first time, the
manager saves the values it replaces on the Mac. It never restarts the TV and never changes its sleep timers.

**Settings > Screensaver** (Scenery and display, after Move cinema clock) shows **GlassOS scenery** or the TV's own,
such as "Fire TV’s own". Choose it for a card that says how to turn it on and offers **Preview**, which plays the
screensaver at once, even before it is the TV's screensaver; any button ends it. Preview is offered when GlassOS is open as an app (as it is through Launcher
Manager), not in the cover it shows over Fire TV's Home.

**Fire TV.**

- Amazon's **Ambient Experience** takes over from any other screensaver while it is on. The manager offers to turn it
  off; restart the TV once afterwards.
- With **Stay awake while plugged in** on (Developer options), no screensaver starts. The manager offers to turn it off.
- If the TV is set to sleep no later than its screensaver would start, it may sleep instead. The manager says so but
  leaves the sleep timers alone.
- Launcher Manager can also change the screensaver; the manager warns when it is installed.
- Not yet verified: whether Fire OS 8.1.8.5 (Fire TV Stick 4K Max, 2nd generation) keeps a third-party screensaver
  after these steps.

**Restore previous screensaver** in the manager puts back only the settings it changed, as they were before its first
change; a setting that did not exist is removed again. This also works when the TV did not keep GlassOS scenery. If
another screensaver (neither GlassOS scenery nor the saved one) was chosen since, it changes nothing and keeps the saved
values. Restart the TV once if it turned Ambient Experience back on.

### Night mode

Settings > **Night mode**, with **Night starts** and **Night ends** (Left and Right step half an hour; a window like
22:00 to 07:00 runs past midnight) and **Night dimming** (low, medium, strong). Inside the window GlassOS lays a
dark layer over its scenery and app grid; menus and panels stay full brightness. It is launcher dimming only and
does not change the TV's brightness. It follows the clock, time and time zone changes using the system's minute
tick while GlassOS is open, with no timer of its own. Equal start and end times mean night mode never turns on.

**Night follows** chooses **Clock** (the times above) or **Sunset & sunrise**. With the sun, **Night starts** counts from
sunset and **Night ends** from sunrise: Left and Right step 15 minutes, up to 2 hours either way, and the value shows
today's time, such as "Sunset +30 · 6:42 PM". When there are no sun times (no place for the TV's time zone, or a day
when the sun does not set or rise), or when the offsets leave no night or no day (short summer nights or winter days far
north or south), night mode uses the clock times and the value says so, such as "Sunset +30 · uses 10:00 PM". Turn
Night follows back to Clock to change those times.

### Sun times

Night mode and the daily rhythm can follow sunrise and sunset. GlassOS works the times out on the TV, with no network,
for one place:

- **From the TV's time zone** (the default): the zone's main city, such as Los Angeles for America/Los_Angeles, from
  the public time zone database, to 0.1°. This is an estimate: if you live far from that city it can be off by about
  an hour, or more in a wide time zone (such as China's) or far north. Zones without a city, such as UTC, have no sun
  times, so everything keeps its clock times.
- **From the Mac**: a place set in [GlassOS Manager > Daily rhythm](../manager/README.md#daily-rhythm) under **Sun
  times location**, typed as a latitude and longitude, taken from the Mac's location, or looked up from the Mac's
  internet address (ipinfo.io; a VPN or mobile hotspot can give the wrong city, so check it before saving). **Use time
  zone estimate** there goes back to the estimate.

**Settings > Sun times** shows where the times come from ("Los Angeles · estimate", "Home · from Mac" or
"Unavailable"; a long place name is shortened, never its source); choose it to see today's sunrise and sunset. They
are worked out again each day and when the time zone changes. Far north or south, on a day when the sun does not set
or does not rise, the clock times are used.

**Privacy.** The place is rounded to 0.1° (about 11 km) before it leaves the manager's page. It is kept only in
GlassOS's private settings on the TV and in the manager's data folder on the Mac, never in a settings backup or in
`scenery.json`. Clearing GlassOS's data forgets it.

### App folders

- **Create**: hold OK on an app, choose **Add to a folder** > **New folder** (the folder takes the app's
  place), or Settings > **New app folder**. Name it with the on-screen keyboard.
- **Open**: OK on the folder tile. OK on an app opens it; holding OK or pressing Menu offers **Remove from folder** and, where available, **Uninstall**.
  **Add apps** ticks apps in or out (an app is in one folder at a time; apps from another folder move), **Rename**,
  and **Remove folder**, which puts its apps back in the app list (nothing is uninstalled).
- A folder is one item on the top row and in the drawer: hold OK on it for options to move it in Manual mode, add it to or remove it from the
  top row, rename or remove it. Folder tiles show a small grid of their apps.
- Hidden or uninstalled apps stay members and come back in their folder when shown again or reinstalled. Every
  installed app that is not hidden is always somewhere: on the top row, in the drawer or in one folder.

### Screen Mirroring

**Settings > Screen Mirroring > Mirroring** (or Control Center > **Screen Mirroring**) lets an iPhone, iPad or Mac
mirror its screen or play music on the TV, compatible with AirPlay. It is off until you turn it on, and is shown only
on Fire OS 6 / Android 7.1 or later in builds that include it. On the sender, choose **Screen Mirroring** and the TV's
name; **Status** in Settings shows what the receiver is doing. Back hides the mirrored screen while mirroring goes on;
the Control Center tile, which then reads **Mirroring**, brings it back. **TV name**, **Require PIN** (a code shown on
the TV), **Resolution** and **Sender controls TV volume** are in the same section. To show a session that starts while
another app is open on Android 10 and later (Fire OS 8), GlassOS needs **Display over other apps** (Settings >
Permissions); it never turns that on by itself. Details, network exposure, troubleshooting and what is not supported yet are in the
[Screen Mirroring guide](SCREEN-MIRRORING.md).

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
fallback background, focus sounds, cinema, night (including Night follows and its offsets) and effects settings, the Manual/Most used preference, scenery favourites, hidden scenes,
shuffle, favourites only and scene names, and Screen Mirroring's on/off state, TV name, Require PIN, Resolution and
Sender controls TV volume, where the TV and build have Screen Mirroring (restoring them starts or stops the receiver to
match, and the confirmation says when it would turn it on; never its pairing identity). It never holds the TMDB key or other credentials, your name, photo or
country, the sun times place, setup and permission state, Home replacement and routing, the sleep timer, playback positions, device
identifiers, network details, media or signing data. Apps and clips missing on the new TV are kept in the settings
and appear once installed or copied. A backup made before 1.6.0 restores Night follows as Clock.

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
| Usage access | Most-used app order; app lock's casting protection; also detecting stock Home for the optional watcher | Most-used mode keeps manual order; the watcher cannot detect stock Home; locked apps ask for the code only when opened from GlassOS |
| Display over other apps | Showing GlassOS at once after Home; app lock's casting protection and Screen Mirroring over another app (both Android 10 and later) | GlassOS still comes back, a moment later; on Android 10 and later, casts and Alexa can open locked apps without the code, and mirroring that starts over another app plays hidden until you choose Mirroring in Control Center |
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
./gradlew testDebugUnitTest    # JVM unit tests: shuffle order, scene names, night windows, folders, backups, app lock codes, sun times, schedules
./gradlew assembleDebug lint   # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified with R8; signed if a keystore is configured (see ../CONTRIBUTING.md)
```

The unit tests cover the pure logic in `SceneLogic.kt`, `SceneCollections.kt`, `NightSchedule.kt`, `FolderModel.kt`,
`BackupCodec.kt`, `UsageOrder.kt`, `AppLock.kt`, `SunTimes.kt` and `ZoneCoords.kt`, and backup file writing (no device
needed). The views, playback and remote navigation need a TV or the Android TV
emulator.

**Target API.** GlassOS Aerial is sideloaded onto TVs (Fire TV, Android TV, Google TV) and is not published on Google
Play. It deliberately keeps `targetSdk 28`, like upstream GlassOS, so it keeps the classic shared-storage behaviour it
relies on for clips in Movies/GlassAerial, backups in Documents/GlassOS-Aerial and your Pictures, including on newer
Android versions, and so it behaves the same on old Fire OS. Google Play's minimum target API does not apply to a
sideloaded launcher, so the build turns off that one lint check, `ExpiredTargetSdkVersion`
([app/build.gradle.kts](../app/build.gradle.kts)); every other lint check stays on. Raising the target API would mean moving clip and backup
access to scoped storage first.


## Themes, app artwork and the moving clock

In Settings's **Scenery and display** section, use Left/Right on **Glass theme** to choose Classic glass, Coast, Forest, Ember or Midnight. These change glass-panel colours without replacing your scenery. Classic is the default. **Move cinema clock** moves the clock between four safe corners on minute ticks; turn it off for the original bottom-right placement. The **Cinema clock** switch still controls visibility.

Use the [Mac manager's Make it yours page](../manager/README.md#themes-and-custom-app-art) to create and sync custom app tiles. Alternatively, copy a small PNG named after an app's package to `/sdcard/Pictures/GlassOS/icons/` and restart or refresh GlassOS. The app limits file size to 2 MiB and image dimensions to 2048 × 2048, decodes at a bounded resolution, and falls back to the app's original art if loading fails. The manager produces 400 × 225 images that fit these limits.

Themes and the custom-art/clock switches travel in settings backups. Image files travel separately. Older backups leave the new choices unchanged.
