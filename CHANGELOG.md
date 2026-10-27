# Changelog

## Unreleased (fork): Screen Mirroring

- **Screen Mirroring:** the TV can show an iPhone, iPad or Mac screen and play its music, compatible with AirPlay (UxPlay and jqssun's Android receiver, in their own `:airplay` process). Off until turned on. Fire OS 6 / Android 7.1 or later; hidden below that and in builds without the receiver (`-Pglassos.airplay.native=false`). [Guide](docs/SCREEN-MIRRORING.md).
- **Settings > Screen Mirroring** ("Compatible with AirPlay from iPhone, iPad and Mac"): Mirroring on or off, a live status line (Off, Starting, Ready as "name", Mirroring, Playing, a reason it failed, or Stopped unexpectedly), TV name (on-screen keyboard; empty is the TV's own name), Require PIN, Resolution (1080p, 720p or Auto up to 1080p), Sender controls TV volume, and (Android 10 and later) whether it may show over other apps, which opens the permissions step. Name, PIN and resolution restart the receiver; the volume setting applies at once.
- **Control Center > Screen Mirroring:** a new tile (GlassOS's own symbol: a TV showing a phone's screen) turns it on and off; during a session it reads Mirroring, Playing or PIN shown and brings the receiver screen up. A quick off and on is serialised: the start waits for the receiver to say it stopped, and it starts again in the same process.
- The receiver screen: the mirrored picture sized to fit, a Disconnect button, the PIN on screen, Back hides it while mirroring goes on. Scenery stops for the session and plays again after it, screensaver included.
- Starts again after a reboot (BOOT_COMPLETED, QUICKBOOT_POWERON) and an update (MY_PACKAGE_REPLACED) when it is on, and when the home comes back to a launcher that lost track of it; never on LOCKED_BOOT_COMPLETED.
- Backups carry Screen Mirroring on/off, TV name, Require PIN, Resolution and Sender controls TV volume, validated on import, only where Screen Mirroring is available; restoring one that turns it on says so first. Never the pairing identity, the receiver's private files or its advanced settings.
- Require PIN: the code is shown on the TV only (not in the notification). UxPlay patch 0011 keeps pair-setup-pin's steps in order and adds a growing wait after wrong codes and a new code after three. Patch 0012 answers a malformed pairing key with an error instead of a crash; before it, any device on the network could stop the receiver this way, with or without Require PIN.
- **About:** Apple's trademarks, the Screen Mirroring components, "you may redistribute it under the GNU GPL v3", and **Open-source licences**, a D-pad reader for every licence text in the app (Up and Down scroll; Fast forward and Rewind turn a page).
- **GlassOS Manager > TV controls > Screen Mirroring:** a read-only Check of its state and settings (`airplay-status`). The manager never starts, stops or changes it.
- `tools/airplay/make_source_bundle.sh` builds `glassos-aerial-<version>-src.tar.gz` (the tracked files with the vendored sources and the OpenSSL tarball, without build outputs or keys; untracked files only with `--include-untracked`, listed) for releases that include the receiver.
- Not yet: video handover from apps (HLS), Apple Lossless audio (no FFmpeg). Tested on an Android TV emulator with simulated senders; Fire TV and real iPhone and Mac sessions are untested. 136 + 42 JVM tests (Screen Mirroring's labels and start rule, backups, licences) and 97 Python tests pass.

## GlassOS Aerial 1.6.0-aerial.1 (fork, unreleased)

- **App lock:** hold OK on an app (top row, drawer or folder) and choose **Lock app**. The first lock sets a 4-button remote code (arrows, Rewind, Play/Pause, Fast forward), entered twice. Locked tiles show a padlock; opening one from a tile, folder or the Top Shelf asks for the code. **Unlock app** needs the code.
- **Locked however it opens:** with Usage access and Display over other apps, a foreground guard covers a locked app with a code screen whenever it comes to the front outside GlassOS (a cast, Alexa or a remote button). Emulator-tested with a simulated outside launch; real casts and Alexa are untested. The right code lets it carry on until another app or Home comes forward or the TV sleeps; Back goes Home. The guard runs only while an app is locked and restarts after a reboot or update.
- **Settings > App lock:** shows the locked count; with the code, change it or turn the lock off. Five wrong codes in a row pause entry for 30 seconds.
- The code is kept as a salted SHA-256 hash. Backups never include the code or the locked list. GlassOS, home apps and screensavers cannot be locked.
- **Screensaver:** GlassOS scenery can be the TV's screensaver whenever the TV is idle in any app. It plays the home's clips and collection from where the home stopped, with scene names, the cinema clock and night dimming. Without clips it shows the fallback background (and the cinema clock, if it is on). **Settings > Screensaver** shows which screensaver the TV uses and offers a preview. GlassOS cannot choose it itself; GlassOS Manager does, over ADB. A screensaver does not re-lock an open locked app; on Fire TV, Amazon's Ambient Experience counts as one (not yet tried on a Fire TV). Not yet tried on Fire OS 8.1.8.5.
- **Sun times:** **Settings > Night follows** can move night mode to sunset and sunrise, with 15-minute offsets up to 2 hours. Daily rhythm entries can start at sunrise or sunset plus an offset. The TV works the times out for its time zone's main city (an estimate, off by about an hour, or more in a wide time zone or far north) or for a place set from the Mac; **Settings > Sun times** shows which. The place is rounded to 0.1° and never goes into backups or `scenery.json`. Each sun entry still carries a fixed start (worked out for the time zone's main city), which GlassOS 1.5.x and days without a sunrise or sunset use.
- Backups carry Night follows and its offsets. A backup from before 1.6.0 restores Night follows as Clock.
- **GlassOS Manager > TV controls.** **App lock**, through a new ADB-only control channel in GlassOS that only the TV's ADB shell can call, sets a new code without the old one or turns the lock off, and the TV shows a notice for either; it can also allow casting protection (Display over other apps, one `appops` command). The code is never saved or logged on the Mac. **Screensaver** (plain ADB settings commands, with no notice on the TV) checks the TV's screensaver settings, makes GlassOS scenery the screensaver (optionally turning off Amazon Ambient Experience and Stay awake) and restores the values it replaced. It never restarts the TV or changes its sleep timers.
- **GlassOS Manager > Daily rhythm:** each entry **Starts at** a time, sunrise or sunset, with a preview of today's time, and a **Sun times location** panel sends the TV a place or returns it to the time-zone estimate. **Look up from internet address** fills in the place from ipinfo.io, only when chosen, for you to check before saving; the manager keeps nothing from the reply.
- Added JVM tests for app lock codes, hashing, retry limits, unlock sessions and screensaver starts, sun times (reference days including DST changes and polar days), time-zone places, sun-anchored schedules and night windows, and Python tests for the manager's lock, sun and screensaver controls. 117 JVM and 91 Python tests pass.

## GlassOS Aerial 1.5.0-aerial.1 (fork, 2026-10-24)

- **Scenery catalog:** GlassOS Manager loads Aerial Community or a public HTTPS Aerial JSON feed, downloads selected SDR videos, compresses them on the Mac and syncs them through verified transfers.
- **Personalization:** five glass palettes, custom app images imported through the manager, and a cinema clock that changes corners each minute. Preferences travel in backups; app images are synced separately.
- **Device profiles:** observed hardware and software, the latest 30 health reports, and comparisons against the previous check and a saved per-device baseline.
- **Performance tool:** explicit-device Activity launch and PSS measurements, with a check that the device is awake.
- 66 JVM and 36 Python tests pass.

## GlassOS Aerial 1.4.0-aerial.1 (fork, 2026-10-24)

- Added a local Mac manager for named devices, verified APK updates and recovery copies, fresh settings exports, and TV-confirmed restore.
- Added video import with silent H.264 encoding, posters, full decode checks, storage budgets and staged/hash-verified transfers.
- Added scene collections and daily schedules, with backwards-compatible metadata and settings backups.
- Refresh changed scene metadata and posters, retry repaired files, release the player promptly on pause, and skip stalled preparations/playback.
- Added accessible thumbnail labels, collision-resistant backup filenames and a repeatable lifecycle/decoder ownership checker.
- Added companion tests for interrupted transfers, low storage, corrupt sources, fresh backup identity and local API isolation.

## GlassOS Aerial 1.3.0-aerial.1 (fork, 2026-10-24)

- Hold OK on an app to open its actions in either app-order mode. Uninstall opens the TV’s native confirmation; Cancel keeps the app and layout. Manual rearranging now starts with **Move** in this menu.
- Folder members also expose uninstall through held OK or Menu. Folder removal still keeps apps installed.
- System apps, system-app updates, GlassOS itself and the selected native Home handler are excluded from the uninstall shortcut.
- Uses only the normal request-uninstall permission. Package-removal broadcasts refresh the app grid; no silent deletion or device-level changes.

## GlassOS Aerial 1.2.0-aerial.1 (fork, 2026-10-24)

- **Most-used app order:** optional sorting of the top row, drawer and folder contents by foreground time from roughly the last 30 days. Folders use the combined time of their visible members. Ties keep the manual order.
- Home handlers, screensavers and Fire TV Ambient Experience are excluded from usage scoring so idle/setup time does not displace viewing apps. They remain available in the app list.
- **Manual layout preserved:** switching back restores it. Folder/hide actions remain available while automatic sorting is on; manual editing explicitly switches modes.
- Refreshes on returning Home, off the UI thread. Results that arrive after navigation begins wait for the next return. No extra background service or polling.
- Uses the existing Usage access permission, independently of Amazon privacy opt-outs. Missing permission/history falls back to manual order. Usage history is never saved, logged, uploaded or exported; only the sort preference travels in backups.
- App tiles now expose their labels to accessibility services as positions change.
- Added ranking and backup compatibility checks; 57 JVM tests pass.

## GlassOS Aerial 1.1.0-aerial.1 (fork, 2026-10-24)

GlassOS Aerial is a fork of GlassOS (package `io.github.abhishekkaisar.glassos.aerial`, version code 4). Entries
from 1.0.2-beta down are upstream GlassOS.

- **Scenery browser** (Settings > Scenery, Control Center > Scenery, OK on the Top Shelf): poster thumbnails of the
  clips in Movies/GlassAerial, decoded at thumbnail size off the UI thread. OK plays a scene; Menu marks it a
  favourite or hides it (hidden scenes stay listed, faded, to show again); Next scene, Previous, Shuffle,
  Favourites only, Scene names and Show all scenes. Left and Right on the Top Shelf change the scene.
- **Shuffle without repeats**: every eligible clip plays once per round, and a round never starts with the clip
  that ended the last one. Clips that become eligible mid-round join it; hidden ones never play. The play order,
  clip and position are kept across restarts (the earlier clip and position settings carry over).
- **Scene names**: the clip's name and place show top left for a few seconds when it changes, from the file name
  (numbers, underscores and quality tags removed) or an optional `scenes.json`.
- Empty, all-hidden, missing or undecodable libraries fall back to the poster or the chosen still background.
- **Cinema mode**: after 1 minute without input (30 s to 30 min, or off) the home fades to its scenery, with an
  optional clock. The first button press only wakes it and is consumed; volume still works. Only while the home is
  in front and uncovered; never keeps the TV awake or changes system settings.
- **Night mode**: dims the scenery and app grid between two times (past midnight too), at three strengths. Launcher
  dimming only, not the TV's brightness; follows the clock and time zone without a timer of its own.
- **App folders** on the top row and in the drawer: create (from an app's move menu or Settings), rename, add and
  remove apps, open, remove (apps go back to the app list). Hidden and uninstalled members stay members.
- **Settings backup and restore** in Documents/GlassOS-Aerial: versioned JSON, written atomically, fully validated
  before anything is applied, with clear errors. Layout, folders, display and scenery choices only; no credentials,
  personal details, permissions, routing, playback positions or device data.
- **Reduce transparency** (solid panels, no backdrop sampling) and **Reduce motion** (no springs, tilts, wiggles or
  sliding panels). Both off by default.
- Option cards can show a message and scroll; the storage grant also covers writing backups.
- JVM unit tests for the playlist, scene names and metadata, night windows, folder placement and backups; CI runs
  them.

## 1.0.2-aerial.1 (fork)

- Local aerial videos as a Top Shelf source: silent, hardware-decoded clips from Movies/GlassAerial, released when
  Home is left and resumed where they stopped.

## 1.0.2-beta (2026-10-23)

- Hold OK on any app to move it, like Apple TV: the tile lifts and wiggles, the arrows move it across the top row
  and the apps below, OK drops it and Back puts everything back. A full top row passes its last app down to the
  front of the drawer. Menu while moving adds the app to or removes it from the top row, or hides it.
- Every app can now be re-ordered, not only the top row. The order is kept; new apps go at the end and uninstalled
  apps drop out. Until you move an app the drawer stays alphabetical.
- Hidden apps: hide an app from its menu, or choose apps in Settings > Hidden apps, which also shows them again.
  Hidden apps stay out of the drawer, the top row and the top row picker, and still open from the Top Shelf.
- A hold of OK on an app no longer adds it to or removes it from the top row; that is in the move menu now.
- Edit top row: the other tiles keep jiggling after an app is moved.
- One-tap permission setup: when the TV's ADB accepts the connection and then closes it (Fire OS since 7.6.6.9 and
  8.1.0.3, and late Fire OS 6, block apps from using the TV's own ADB), setup now says so and points to the manual
  grants from a computer. Other ADB errors show the exception and its message.

## 1.0.1-beta (2026-10-22)

GlassOS now targets Android TV and Google TV as well as Fire TV. The repository moved to
https://github.com/AbhishekKaisar/glassos-tv (the old URL redirects).

- Detects the platform (Fire TV, or Android TV / Google TV) and keeps every Fire TV behaviour as it was.
- Android TV: a new setup step, "Make GlassOS your home screen", that tries the system's default home prompt, the
  Default home app page, then `cmd package set-home-activity` over the TV's own ADB. Also in Settings > Home screen.
- When GlassOS is the default home app, the home watcher does not run at all; Home and boot open GlassOS directly.
- Builds that keep their own home (the stock launcher outranks the chosen default) fall back to the watcher, which
  now also covers the Google TV, Android TV and Leanback launchers.
- Control Center on Android TV opens the standard Settings pages (Wi-Fi, Sound, Accessibility, Bluetooth or
  Remotes & Accessories, Settings). Parental is hidden there; Switch Profile opens accounts and profiles where the
  TV has that page.
- Settings pages opened from GlassOS on Android TV get their own task, so Home always returns to GlassOS.
- Setup's debugging instructions and connection errors are written per platform, including Android 11 wireless
  debugging, which does not open port 5555.
- Footer and About: not affiliated with Google, Amazon or Apple; trademark notices for Android TV, Google TV and
  Fire TV.

## 1.0.0-beta (2026-10-22)

First public release.

- tvOS-inspired home for Fire TV: rotating Top Shelf, glass shelf of up to six apps with a peeking app drawer,
  adaptive profile pill, Control Center, comes back after Home, starts at boot.
- Top Shelf sources: TMDB (default, prefers your installed streaming services in your country), Apple TV
  (unofficial, off by default) and built-in scenes.
- First-run setup: name with an on-screen keyboard, profile photo or the glass avatar, country, top-row apps.
- One-tap permission setup through the TV's own ADB, with manual commands documented.
