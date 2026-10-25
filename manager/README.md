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

Add a name and the address shown in the Fire Stick's network settings. Enable ADB debugging and accept the authorization prompt on that TV. Every action targets the selected saved device; the manager does not scan your network or change Home routing.

**Check device** reads the installed GlassOS version, storage, native Home handler, five Amazon privacy values and four package-disable states. Instagram checks compare each installed APK with the private bundle's known blocker hashes. A missing app, unknown identity and verified blocker have different labels. Disabling Android packages does not prove native Amazon services stopped or that network telemetry is absent.

**Update GlassOS** reads the bundle's verified build manifest, verifies the APK hash, package ID and signing certificate, archives the installed APK, saves a settings backup, then updates in place. The installed APK hash is checked afterward. There is no uninstall-on-error behavior and no automatic downgrade. On releases before 1.4, only an existing exported backup can be collected; export current settings on the TV first. Version 1.4 and later can export a fresh backup from the manager. If no backup is available, the update stops.

Recovery APKs and their records stay under the data folder's `recovery/`. Android can refuse a downgrade even with the same signing key; these files are recovery materials, not a guaranteed one-click rollback. Device permissions and Home routing are never part of a settings backup.

## Scenery library

- **Add saved collection** verifies and indexes the private bundle's existing clips and posters. It does not duplicate or re-encode them.
- Drop a video or click the import area. Choose a name, collection and clip length (30–180 seconds). Import starts at the source's beginning, keeps the original file unchanged, and makes a silent SDR H.264 clip up to 1080p/30 fps, plus a JPEG poster. HDR input is refused rather than silently producing incorrect colours. Files must be smaller than 4 GB.
- Every new encode receives a full decode check and SHA-256 hashes before appearing in the library. If it would exceed the selected storage budget, it is imported unselected.
- Edit titles and collections on each card. The collection buttons filter the browser; the **Sync** checkboxes select files for transfer.
- **Sync selected to TV** verifies local hashes, skips matching files already on the TV, checks free space, and uploads changed files under temporary names. Only a verified file is renamed into place. Metadata and the daily schedule follow successful media transfers. A disconnected transfer can be retried safely.

The budget is a limit for the selected transfer collection, in decimal MB. The manager also requires at least 400 MiB free space beyond the staged transfer. It **does not delete existing or deselected videos** from the TV; deselecting a scene stops future copying, not playback of a copy already there. Use the TV's Hide scene control to exclude an existing scene from playback. It also does not automatically download third-party media.

## Daily rhythm

Choose 1–24 distinct start times and a collection for each. Each entry lasts until the next and the last wraps across midnight. Save, sync, then choose **Scenery → Collection → Scheduled** on the TV. Time follows the TV's own clock and timezone. Empty collections fall back to other eligible scenes; hidden clips remain hidden. Select **All scenes** to return to the full library.

The schedule and scene metadata travel with the media as `scenery.json` and `scenes.json`. The TV's chosen collection mode travels in settings backups. Details and standalone examples are in [the user guide](../docs/USAGE.md#collections-and-daily-rhythm).

## Backups

**Back up settings** asks GlassOS 1.4+ to export a fresh file and saves a validated copy on this Mac. Accept storage access on the TV if prompted. A recent pre-existing file is not mistaken for a fresh export.

Choose another device and **Copy to selected TV** beside a saved backup. The TV opens its existing restore screen; choose the file and confirm there. Cancel leaves the current settings intact. Backups include layout, folders and display/scenery preferences, and exclude account credentials, usage history, device permissions and Home routing. Videos are separate.

## Local privacy and failure behavior

The service uses a fresh session token, checks the Host and Origin of API requests, denies cross-site API access, and serves only specific static files and known scene posters. It does not expose a general filesystem route or arbitrary ADB/shell command endpoint. Device changes run one operation at a time. The data folder holds device addresses, health reports, imported clips, backups and recovery files; keep it private and outside Git.

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
