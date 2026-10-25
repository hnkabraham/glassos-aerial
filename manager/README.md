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
