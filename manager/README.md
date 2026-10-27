# GlassOS Manager

A local Mac companion for GlassOS Aerial. Manage several Fire Sticks, prepare silent scenery, copy settings and check each device without a cloud account.

## Open it

In the personal **Fire TV GlassOS** bundle, double-click **GlassOS Manager.app** or **Open GlassOS Manager.command**. The interface opens in your browser; its service listens only on `127.0.0.1`. Reopening the shortcut reuses the running service.

From a source checkout:

```sh
python3 manager/launch.py --data-dir "$HOME/Library/Application Support/GlassOS Manager"
```

Use Python 3.10 or later. Install Android Platform Tools for device actions, FFmpeg/ffprobe for video import, and Android SDK build tools (`apksigner`, `aapt`) for verified APK updates. There are no Python packages or frontend dependencies to install.

The optional `--bundle /path/to/Fire-TV-GlassOS` argument enables the saved 20-scene collection, verified APK updates and exact Instagram-blocker identity checks. This private bundle is separate from the public repository. Its videos, signing material and device records are not distributed here. Without a bundle you can still import your own videos, save devices, sync scenery, inspect devices and copy backups.

For foreground development, use `python3 manager/server.py --data-dir /path/to/private-data`. Stop that foreground process with Ctrl-C. `launch.py` starts a detached local process; its PID and log are stored in the data folder. Quit it with `kill PID` after operations finish. Closing the browser does not interrupt an ongoing transfer.

## Devices and updates

Add a name and the address shown in the Fire Stick's network settings. Enable ADB debugging and accept the authorization prompt on that TV. Every action targets the selected saved device; the manager does not scan your network or change Home routing. It changes the TV's own system settings only from [TV controls](#tv-controls), and only when you choose to: the screensaver settings listed there and GlassOS's Display over other apps permission. It never restarts the TV.

**Check device** reads the installed GlassOS version, storage, native Home handler, five Amazon privacy values and four package-disable states. Instagram checks compare each installed APK with the private bundle's known blocker hashes. A missing app, unknown identity and verified blocker have different labels. Disabling Android packages does not prove native Amazon services stopped or that network telemetry is absent.

**Update GlassOS** reads the bundle's verified build manifest, verifies the APK hash, package ID and signing certificate, archives the installed APK, saves a settings backup, then updates in place. The installed APK hash is checked afterward. There is no uninstall-on-error behavior and no automatic downgrade. On releases before 1.4, only an existing exported backup can be collected; export current settings on the TV first. Version 1.4 and later can export a fresh backup from the manager. If no backup is available, the update stops.

Recovery APKs and their records stay under the data folder's `recovery/`. Android can refuse a downgrade even with the same signing key; these files are recovery materials, not a guaranteed one-click rollback. Device permissions and Home routing are never part of a settings backup.

## Scenery library

- **Add saved collection** verifies and indexes the private bundle's existing clips and posters. It does not duplicate or re-encode them.
- Drop a video or click the import area. Choose a name, collection and clip length (30–180 seconds). Import starts at the source's beginning, keeps the original file unchanged, and makes a silent SDR H.264 clip up to 1080p/30 fps, plus a JPEG poster. HDR input is refused rather than silently producing incorrect colours. Files must be smaller than 4 GB.
- Every new encode receives a full decode check and SHA-256 hashes before appearing in the library. If it would exceed the selected storage budget, it is imported unselected.
- Edit titles and collections on each card. The collection buttons filter the browser; the **Sync** checkboxes select files for transfer.
- **Sync selected to TV** verifies local hashes, skips matching files already on the TV, checks free space, and uploads changed files under temporary names. Only a verified file is renamed into place. Metadata and the daily schedule follow successful media transfers. A disconnected transfer can be retried safely.

The budget is a limit for the selected transfer collection, in decimal MB. The manager also requires at least 400 MiB free space beyond the staged transfer. It **does not delete existing or deselected videos** from the TV; deselecting a scene stops future copying, not playback of a copy already there. Use the TV's Hide scene control to exclude an existing scene from playback. Catalog videos are downloaded only when you explicitly choose **Download & compress**.

## Scenery catalog

Open **Scenery catalog** and choose **Load catalog**. A blank URL uses [Aerial Community](https://github.com/AerialScreensaver/AerialCommunity); paste another public HTTPS Aerial-compatible JSON URL to use that feed. Search titles/places, choose a collection and maximum clip length, then **Download & compress**. The result appears in your library for normal verified sync. A short source stays short; no artificial looping is added.

The parser accepts an `assets` array (or a top-level array), reads `title`, `accessibilityLabel`, and `scene`, and prefers `url-1080-H264`, then `url-1080-SDR`, `url-720-H264`, and `url-4K-SDR`. HDR-only entries are skipped. Feed limits are 5 MiB / 5,000 assets; source downloads are capped at 4 GB and 30 minutes with a 1 GB Mac disk reserve. URLs must be public HTTPS, without credentials or custom ports; local-network feeds are not supported. Redirect destinations are checked too. Videos and posters stay local, and source URLs are kept with their library entries. Imports from the same feed/video are deduplicated.

Feed availability and media rights belong to their providers. The catalog does not grant redistribution rights or bundle third-party videos with GlassOS. Preview links open the source video in a separate tab; the browser catalog does not stream thumbnails or download the entire feed's videos.

## Themes and custom app art

Choose **Settings → Scenery and display → Glass theme** on the TV: Classic glass, Coast, Forest, Ember or Midnight. The default Classic palette retains the existing look. **Move cinema clock** defaults on and changes corners once a minute while cinema mode is active. Switching it off fixes the clock at bottom right. Movement uses discrete repositioning, including under Reduce motion; it does not add a continuous animation or guarantee protection from burn-in.

In **Make it yours**, read apps from the selected TV, choose a package, upload a PNG/JPEG/WebP image (up to 8 MiB / 40 megapixels), and save it. The manager fits it into a 400 × 225 PNG with a dark surround. **Sync app art to TV** verifies hashes and stages each replacement under `/sdcard/Pictures/GlassOS/icons/<package>.png`, then refreshes the launcher. Read-storage access is required on the TV. No app package is modified.

The TV's **Custom app art** switch lets you use original app icons without removing the images. **Restore original** in the manager removes that one saved replacement from the Mac and selected TV; other devices retain their copies. Sync your art separately to each stick. Portable settings backups include the theme, clock movement and custom-art switch, but exclude image files. Invalid/oversized/missing TV images fall back to the app's original resources.

## Device profiles and comparisons

**Device profiles** records model, firmware, Android API/version, supported ABIs and RAM visible to Android. It compares each health check with the previous check. **Use current state as baseline** first reads the device again, then saves an explicit baseline for that device. Future checks show changes to the launcher version, Home handler, firmware, privacy values, package-disable states and verified Instagram blockers. Free-storage changes are shown separately because they fluctuate.

The newest 30 reports per device are retained in `reports/<device-id>/`; the baseline is retained separately until replaced. Reports are private local files. A profile is an observation, not a compatibility guarantee or a cleanup prescription; it never applies debloat/root/Home changes.

## Daily rhythm

Choose 1–24 entries and a collection for each. Each entry lasts until the next and the last wraps across midnight. **Starts at** is a **Time**, or **Sunrise** or **Sunset** with an offset in minutes (−240 to 240, in steps of 5). A preview under a sun entry shows today's time, such as "Sunset +30 → 6:42 PM today". Times must differ, and two sun entries cannot share the same sunrise or sunset and offset. Save, sync, then choose **Scenery → Collection → Scheduled** on the TV. Time follows the TV's own clock and timezone. Empty collections fall back to other eligible scenes; hidden clips remain hidden. Select **All scenes** to return to the full library.

GlassOS 1.6.0 and later works out each sun entry's time on the TV every day. Every entry also keeps a fixed start, which older GlassOS versions use, and which the TV uses on days without a sunrise or sunset. When you save and when you sync, the manager sets it to that day's sun time in the TV's time zone's main city, moved on a minute if it meets another entry. That needs the TV's time zone, which **Check device** or the **Check** below reads. Until then the fixed start is 06:00 (sunrise) or 18:00 (sunset) plus the offset, and the preview asks you to check the TV. The fixed start never uses the place set below (the preview does): two sun times in `scenery.json` would give that place away.

**Sun times location** shows where the TV's sun times come from: a place set from this Mac, or an estimate from its time zone's main city, which can be off by about an hour, or more in a wide time zone or far north. Enter a place name (optional, up to 40 characters), latitude and longitude, or choose **Use this Mac's location** to fill them in where the browser allows it, then **Save to TV**. **Look up from internet address** fills them in from where this Mac's public internet address appears to be, as [ipinfo.io](https://ipinfo.io) reports it, with the city and region as the name; a VPN or mobile hotspot can give the wrong city, so check it before **Save to TV**. Nothing reaches the TV until you do. **Use time zone estimate** clears the place. The TV shows a notice for either. **Check** reads the TV's source and its sunrise and sunset for the day. Coordinates are rounded to 0.1° (about 11 km) in the page, before they are sent. The place is kept on the TV and in this Mac's device record, never in `scenery.json` or a settings backup. This panel needs GlassOS 1.6.0 or later.

The schedule and scene metadata travel with the media as `scenery.json` and `scenes.json`. The TV's chosen collection mode travels in settings backups. Details and standalone examples are in [the user guide](../docs/USAGE.md#collections-and-daily-rhythm).

## Backups

**Back up settings** asks GlassOS 1.4+ to export a fresh file and saves a validated copy on this Mac. Accept storage access on the TV if prompted. A recent pre-existing file is not mistaken for a fresh export.

Choose another device and **Copy to selected TV** beside a saved backup. The TV opens its existing restore screen; choose the file and confirm there. Cancel leaves the current settings intact. Backups include layout, folders and display/scenery preferences, and exclude account credentials, usage history, device permissions and Home routing. Videos are separate.

## TV controls

**TV controls** changes a few things on the selected TV over ADB, each only when you choose it. It needs GlassOS 1.6.0 or later; an older TV gets "Update GlassOS to 1.6.0 or later on this TV." The TV shows a notice when a new lock code is set or the app lock is turned off; the screensaver and casting-protection changes show none. Each panel's **Check** only reads.

GlassOS accepts these controls only from the TV's ADB shell (`adb shell content call`); other apps on the TV cannot use them. The manager sends only a fixed list of commands, and checks every value before it connects. This adds nothing for someone who already has ADB access to the TV: they could already clear GlassOS, change its permissions or force it to stop.

### App lock

- **Check** shows whether a lock code is set, how many apps are locked (and how many of those are not installed now), and whether casting protection is on.
- **Set a new lock code** asks for 4 remote buttons on screen (arrows, Rewind, Play/Pause, Fast forward; the keyboard's arrow keys work too), entered twice. The old code is not needed, so this is also the way back from a forgotten code. Locked apps stay locked.
- **Turn off app lock** asks first, then unlocks every app and deletes the code. To lock apps again, hold OK on an app on the TV.
- **Allow casting protection** appears only when the guard cannot run and GlassOS lacks Display over other apps, on Android 10 or later (older TVs do not need it; until **Check device** has read the TV's Android version, the manager assumes they do). After you confirm, it runs one command: `appops set io.github.abhishekkaisar.glassos.aerial SYSTEM_ALERT_WINDOW allow`. Run it with `default` instead of `allow` to undo it. The guard also needs Usage access, granted on the TV, and starts the next time GlassOS opens with an app locked.

The code exists only in the open page until it is sent, and is then cleared. It is never saved in the data folder or the browser's storage, and never appears in job messages, logs or errors. It crosses your home network in ADB's plain-text connection, so use these controls on a network you trust.

### Screen Mirroring

**Check** reads, and only reads, GlassOS's Screen Mirroring (GlassOS with Screen Mirroring and Fire OS 6 or later):
whether it is on, what the receiver is doing (ready and its name, mirroring, playing, showing a code, starting, could
not start, or stopped unexpectedly), Require PIN, Resolution, Sender controls TV volume, and restarts after a crash.
It sends one `airplay-status` call. The manager never turns Screen Mirroring on or off or changes its settings: that
is done on the TV, in Settings. A code shown on the TV never reaches the manager. See the
[Screen Mirroring guide](../docs/SCREEN-MIRRORING.md).

### Screensaver

**Check** reads the TV's screensaver settings and whether GlassOS's screensaver is installed. It lists what stops a screensaver (Stay awake while plugged in; Amazon's Ambient Experience) and notes what may get in the way (a sleep time no longer than the screensaver's wait; Launcher Manager, which can also change the screensaver).

**Use GlassOS scenery** appears after a Check finds GlassOS's screensaver on the TV. Its confirmation lists exactly these changes; the last two are checkboxes, shown only when that setting is on, and ticked by default:

| Setting | Set to |
| --- | --- |
| `secure screensaver_components` | `io.github.abhishekkaisar.glassos.aerial/io.github.abhishekkaisar.glassos.AerialDream` |
| `secure screensaver_enabled` | `1` |
| `secure screensaver_activate_on_sleep` | `1` |
| `secure amazon_ambient_enabled` (Fire TV) | `0`, if ticked; restart the TV once afterwards |
| `global stay_on_while_plugged_in` | `0`, if ticked |

Before its first change, the manager saves the TV's current screensaver settings on this Mac, once: using it again keeps that first set. Afterwards it reads the settings back and reports an error if the TV did not keep GlassOS as its screensaver; **Restore previous screensaver** then puts back what it changed. It never writes `screensaver_activate_on_dock`, `screensaver_default_component`, `sleep_timeout` or `screen_off_timeout`, and never restarts the TV. Whether Fire OS 8.1.8.5 keeps a third-party screensaver after these changes has not been verified yet.

**Restore previous screensaver** appears while a saved set exists. It puts back only the settings the manager changed, as they were before its first change: a setting that did not exist is deleted (`settings delete`), and the others are set back (`settings put`). It reads them back and clears the saved set only when every one matches. If the TV's screensaver is neither GlassOS scenery nor the one saved, because another was chosen on the TV or by another tool, Restore changes nothing and keeps the saved set; to put the saved values back anyway, choose **Use GlassOS scenery**, then **Restore previous screensaver**. If Restore turned Amazon's Ambient Experience back on, restart the TV once.

## Local privacy and failure behavior

The service uses a fresh session token, checks the Host and Origin of API requests, denies cross-site API access, and serves only specific static files and known scene posters. It does not expose a general filesystem route or arbitrary ADB/shell command endpoint. It goes to the internet only when you ask: **Load catalog** and **Download & compress** fetch that feed and video, and **Look up from internet address** sends one HTTPS request to `https://ipinfo.io/json`, with no account or key. That request tells ipinfo.io this Mac's public internet address, as visiting any website would. The manager keeps nothing from the reply: the address is never saved, logged or shown, and the place only fills the form (and the activity log until the manager quits). Device changes run one operation at a time. The data folder holds device addresses, health reports, imported clips, backups, recovery files, each TV's sun times place and its saved screensaver settings; keep it private and outside Git. It never holds an app lock code.

Tests run without an attached device:

```sh
python3 -m unittest discover -s manager/tests -v
node --check manager/static/app.js
```

The separate [reliability checker](../tools/reliability_check.py) exercises a selected TV's Home/Settings transitions and optional sleep/wake, recording decoder ownership, memory and frame evidence. It does not reboot or change system settings:

```sh
python3 tools/reliability_check.py --serial FIRE_TV_IP:5555 --cycles 10 --sleep-wake --output /path/to/private-results
```

Implementation references: [Android MediaPlayer lifecycle](https://developer.android.com/reference/android/media/MediaPlayer), [FFmpeg options](https://ffmpeg.org/ffmpeg.html), and [Python's HTTP server](https://docs.python.org/3/library/http.server.html). This is a local desktop companion, not a server intended for public hosting.
