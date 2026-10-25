# Changelog

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
