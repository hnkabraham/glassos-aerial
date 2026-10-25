# GlassOS Aerial 1.3.0-aerial.1 validation

Validated on October 24, 2026. Package `io.github.abhishekkaisar.glassos.aerial`, version code 6.

## Build and automated checks

All **57 JVM tests passed**, with no failures or errors. Debug and optimized signed release builds passed; lint reported **0 errors, 113 warnings**. The release retains the previous signing certificate and has no embedded TMDB key. The standard `REQUEST_DELETE_PACKAGES` permission requests the OS confirmation, with no silent-delete privilege.

## Remote UI on Android TV API 31

- Held OK opens the actions menu in Manual and Most used mode; releasing the held key does not select an action.
- Uninstall opens the system dialog for the selected disposable fixture. Back cancels, preserving its installation and all preferences. Confirming removal deletes the fixture and updates the grid without restarting GlassOS.
- Manual > Move still moves tiles; Back restores the original layout. Uninstalling does not overwrite saved manual order.
- A folder member supports both held OK and Menu. Confirmed removal updates the visible membership; saved membership remains available for a later reinstall. Folder tiles offer Remove folder, with no Uninstall action.
- The system Settings app has no Uninstall action. All menu rows and the native confirmation were visually checked at 1920×1080.

These are neutral emulator captures using generated clips and local fixture apps.

| Before | After |
| --- | --- |
| ![Previous held-OK menu](validation/uninstall-before-menu.png) | ![Held-OK menu with Uninstall](validation/uninstall-after-menu.png) |

| Manual mode | Folder member |
| --- | --- |
| ![Manual actions](validation/uninstall-manual-menu.png) | ![Folder actions](validation/uninstall-folder-menu.png) |

![Native uninstall confirmation](validation/uninstall-confirmation.png)

## Physical Fire TV

Fire TV Stick 4K Max (2nd generation, AFTKRT), Fire OS 8.1.8.5.

- Upgraded the signed build in place. The held-OK action opened Fire TV's native confirmation for a temporary local fixture; Cancel retained it, and confirmation removed it and refreshed Home.
- The active Launcher Manager Home handler had no Uninstall action. No existing user app was removed: the final installed-package list exactly matched the initial list after the fixture was removed.
- The launcher released its video-codec resources while the confirmation was open. Home returned to GlassOS and `OMX.MTK.VIDEO.DECODER.AVC` hardware aerial playback resumed.
- The disabled-package list, all five Amazon privacy values (`0`), native Home handler and 20-clip library matched the pre-upgrade audit. Usage access remained allowed and Most used stayed selected.

## Limits

Fire OS 5/6/7, other physical models, device-admin restrictions and a missing/blocked system uninstaller were not exercised. minSdk 22 is retained. Reboot and fresh Home setup were not retested. Earlier feature validation follows.

---

# Earlier release: 1.2.0-aerial.1

Validated on October 24, 2026. Package `io.github.abhishekkaisar.glassos.aerial`, version code 5.

## Automated checks

**57 JVM tests passed**, with no failures or errors. New coverage checks usage ranking, stable ties, missing/irrelevant history, folder totals and membership, overflow protection, layout reachability, and optional/strict backup preference handling. Debug and signed release builds passed. Lint: **0 errors, 113 warnings**.

## Remote UI on Android TV API 31

- Selecting Most used without Usage access showed the missing-permission message and kept the manual layout.
- After granting local Usage access, two emulator-only fixture apps were opened for controlled durations (6 and 14 seconds). The longer-used app ranked ahead of the shorter-used one; the six highest-ranked items occupied the top row and the remaining item stayed in the drawer.
- Switching to Manual restored the original dock and drawer order. Switching back restored the automatic ranking. Saved dock/order/folder/hidden preferences remained unchanged.
- A real held OK event opened the automatic-mode context menu. Adding an app to a folder edited the underlying manual dock rather than overwriting it with the ranked six-item row.
- Opening the sorted folder picker and saving unchanged membership preserved all manual member ordering and settings.
- The App order dialog and Home layouts were visually inspected at 1920×1080. Neutral synthetic videos and labelled fixture apps are used here; no personal device history is published.

| Manual layout | Most-used layout |
| --- | --- |
| ![Saved manual layout](validation/usage-manual-home.png) | ![Automatically ranked layout](validation/usage-automatic-home.png) |

![App order setting](validation/usage-order-settings.png)

## Physical Fire TV

Fire TV Stick 4K Max (2nd generation, AFTKRT), Fire OS 8.1.8.5.

- Installed the signed version-code-5 release in place with the same signing certificate; its compiled TMDB key is empty.
- Granted only GlassOS Aerial's local Usage access and enabled Most used. Both the top row and drawer reflected existing foreground history; Ambient Experience and Launcher Manager no longer displaced viewing apps.
- The 20 local videos remained present. Home playback used `OMX.MTK.VIDEO.DECODER.AVC`; opening Fire TV Settings released the launcher's video-codec resources, and pressing Home restored hardware playback.
- All five recorded Amazon privacy values remained `0`, the disabled-package list matched the pre-upgrade snapshot, and Launcher Manager remained the native Home handler. No root, firmware, package-disable or routing changes were made for this release.

## Limits

The ranking uses Android's retained aggregate buckets, not an exact 30-day stopwatch. OS history and accounting can vary across devices. Fire OS 5/6/7, other physical models, fresh-device Home setup and reboot behavior were not retested for this release. Decoder checks sampled playback rather than watching all 20 clips. Earlier feature validation is recorded below.

---

# Earlier release: 1.1.0-aerial.1

Validated on October 24, 2026. Package `io.github.abhishekkaisar.glassos.aerial`, version code 4.

## Build and automated checks

- JDK 17, Android SDK/build tools 34.0.0.
- `testDebugUnitTest`: **49 tests, 0 failures, 0 errors**. Coverage includes scene naming/metadata/filtering,
  persistent shuffle and previous-history behavior, overnight schedules, folder layout, and strict backup validation
  and size limits.
- Debug and signed, optimized release builds succeeded. Lint: **0 errors, 113 warnings**.
- `ExpiredTargetSdkVersion` is the sole disabled lint check: this sideloaded launcher deliberately targets API 28
  for its existing shared-storage behavior. See the build file and README. Other warnings remain visible.
- The release APK contains no compiled TMDB key. Its signing certificate matches the preceding Aerial release,
  and an in-place upgrade retained the existing settings.

## Android TV emulator

Android TV API 31, ARM64, 1920×1080. Neutral, generated silent clips were used for these screenshots.

Remote-navigation checks covered the scenery browser and favourites; folder creation, opening, membership,
renaming and removal; night dimming; reduced transparency/motion; and backup export/import. A malformed backup
with duplicate folder membership was rejected with the original preferences byte-for-byte unchanged. A valid
backup restored the expected settings, and a shared-storage export excluded credentials and Home-routing state.

Cinema mode faded the launcher after the selected timeout. A held OK woke it without opening an app, and a later
intentional press worked. The final build also corrected and visually verified the four-button folder panel.

| Before: original Aerial home | After: new home/folder layout |
| --- | --- |
| ![Before](validation/before-home.png) | ![After](validation/after-home.png) |

| Scenery browser | Cinema mode |
| --- | --- |
| ![Scenery](validation/scenery.png) | ![Cinema](validation/cinema.png) |

![Final folder panel with all four controls visible](validation/folder-panel.png)

## Physical Fire TV

Fire TV Stick 4K Max (2nd generation, AFTKRT), Fire OS 8.1.8.5.

- Installed the signed release with `adb install -r`; retained the user's app layout and local library.
- Scenery browser detected **20 clips**. Sample playback used `OMX.MTK.VIDEO.DECODER.AVC` hardware decoding.
- Opening the standalone Fire TV Settings activity released the launcher's video-codec resources. Pressing Home
  returned to GlassOS Aerial and hardware playback resumed. The existing Launcher Manager Home handler remained
  selected.
- No system packages, firmware settings, privacy settings or Home-routing settings were changed for this upgrade.

## Limits

Fire OS 5/6/7, other physical models, fresh-device Home setup and reboot behavior were not retested for this
release. The previous Aerial version had reboot testing on this stick. This check was not a complete watch-through
of all 20 clips. Synthetic emulator media is not representative of every hardware codec or media file. The project
retains minSdk 22; support for older devices still needs physical testing.
