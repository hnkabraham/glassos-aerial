# Our Fire TV launcher and privacy setup

This records the device work behind [GlassOS Aerial](../README.md), performed on **October 24, 2026**. These changes are separate from the APK. Installing or updating the app does not root a device, disable Amazon packages, block updates or replace protected Home components.

## Tested device and tools

| Item | Recorded version |
| --- | --- |
| Device | Fire TV Stick 4K Max, 2nd generation — AFTKRT / karat |
| Fire OS | 8.1.8.5, build RS8185.3891N |
| Kernel | `5.10.43-android12-9-g8b4baa819147` |
| Temporary-root tool | `ghostlock-karat-v1.0.4.zip` |
| Home manager | Launcher Manager System User Edition, `LM_2.0.0.apk`, package `com.wolf.tn` |
| Current launcher | GlassOS Aerial 1.2.0-aerial.1, package `io.github.abhishekkaisar.glassos.aerial` |

Original tool sources:

- [GhostLock: temporary root for the Fire TV Stick 4K second-generation series](https://xdaforums.com/t/temp-root-fire-tv-stick-4k-2nd-gen-series-karat-mantra.4798627/)
- [Launcher Manager: System User Edition](https://xdaforums.com/t/system-user-fire-cube-stick-tv-tablet-ps7704-fireos7-rs8149-fireos8.4759215/)

These are the versions used, not a claim that they are the latest or support every firmware. The successful GhostLock log matched its target by **kernel banner**, using an RS8185.3879N profile on the recorded RS8185.3891N build. The marketing version “8.1.8.5” alone is not enough to establish compatibility.

Recorded SHA-256 hashes of the downloaded artifacts:

```text
ghostlock-karat-v1.0.4.zip
b1df5a99c9ca9266be90a02eee62ba717f2cdc8850adf8b7a14fadf091c6532e

LM_2.0.0.apk
cb15baaef74634f8c6e6509a3e1dd4d81a9c083533966d590577a71a1e795b81
```

For commands below, choose the target explicitly. Replace the placeholder with its ADB address; accept the authorization prompt on that TV.

```sh
TV="FIRE_TV_IP:5555"
adb connect "$TV"
adb -s "$TV" shell getprop ro.product.model
adb -s "$TV" shell getprop ro.build.display.id
adb -s "$TV" shell uname -r
```

## Persistent Home routing

### What we did

1. Saved device/package information and app data, then used the matching **GhostLock** release to obtain temporary root.
2. Installed **Launcher Manager System User Edition 2.0.0** and configured its privileged Home handling while that access was available. Enabled **Custom Launcher Support** and **Proxy Launcher Mode**.
3. Selected GlassOS as the custom launcher. Once our Aerial fork was ready, changed that selection to **GlassOS Aerial** using its full component:

   ```text
   io.github.abhishekkaisar.glassos.aerial/io.github.abhishekkaisar.glassos.MainActivity
   ```

4. Left GlassOS **Replace Fire TV home** off. That setting controls GlassOS's watcher/overlay fallback; Launcher Manager now owns native Home.
5. Verified Home and Fire TV Settings, then rebooted during the original setup. The route persisted, the device returned to **SELinux Enforcing**, and the temporary root daemon was no longer accessible.
6. Later disabled the old upstream GlassOS package, preserving its data, so its boot receiver could not compete with Aerial.

This was a **GhostLock-assisted Launcher Manager setup**, not proof that the older standalone “system user” exploit works unchanged on this firmware. Use the original authors' current instructions for a fresh device; this history is not a substitute for their model-specific prerequisites and ordering.

### Resulting state

| Package or component | Recorded state / purpose |
| --- | --- |
| `com.wolf.tn/.ui.LMUHandler` | Native Home handler; opens the selected custom launcher. |
| `com.amazon.tv.launcher` | Package remains enabled so Fire TV Settings can open. |
| `com.amazon.tv.launcher/.ui.HomeActivity_vNext` | Disabled stock Home component. |
| `com.amazon.firehomestarter` | Disabled. |
| `com.amazon.tv.settings.v2/.compliance.CTSDummySettingActivity` | Disabled by Launcher Manager. |
| `com.amazon.tv.settings.v2/.compliance.CTSDummySettingsHandler` | Disabled by Launcher Manager. |
| `com.android.permissioncontroller/.role.ui.HomeSettingsActivity` | Disabled by Launcher Manager. |
| `io.github.abhishekkaisar.glassos` | Old upstream app disabled for user 0 after switching to Aerial; data retained. |

We did not remove the Amazon launcher package or flash firmware. Do not disable the whole Amazon launcher to reproduce this table: the standalone Settings activity depends on it.

Check the native handler:

```sh
adb -s "$TV" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Our expected result is `com.wolf.tn/.ui.LMUHandler`. Check the selected launcher in Launcher Manager too; the resolver alone does not prove it selected Aerial. Then open another app, press Home, and check that Aerial opens. Fire TV Settings must also remain accessible. Reboot verification applies to the original device setup and earlier Aerial build; the 1.1.0 and 1.2.0 upgrades were checked without another reboot.

**For another stick already using this Home handler:** open Launcher Manager and select GlassOS Aerial. Routine upgrades use `adb install -r` with the same signing key. Repeating the root exploit is unnecessary for those upgrades.

### Update blocking and reliability

The original setup disabled:

- `com.amazon.device.software.ota`
- `com.amazon.device.software.ota.override`
- `com.amazon.sneakpeek`

The GhostLock log recorded these update changes, and they remained disabled after reboot. **Blocking them also blocks Fire OS security fixes.** Keeping this firmware preserves the tested environment, but does not make Home routing irreversible or immune to a reset.

Two later GhostLock attempts during privacy work failed to regain root. The second caused a **kernel panic and automatic reboot**; the device recovered and we stopped retrying. A previous success is not a guarantee that the next attempt will succeed. No bootloader unlock or persistent root was installed.

## Amazon privacy changes

### Turn off the visible controls

In Amazon's **Settings > Preferences > Privacy Settings**, we turned off:

- Device Usage Data
- Collect App Usage Data
- Interest-Based Ads

The Interest-Based Ads switch briefly reverted while the other settings were being changed, so we checked all three again and then verified the stored values after reboot. We used the UI for the opt-outs, rather than assuming a direct settings-database write applied every related change.

All five of these **secure** values read `0`:

```sh
adb -s "$TV" shell settings get secure settingsApp:deviceUsageData:value
adb -s "$TV" shell settings get secure settingsApp:appUsageData:value
adb -s "$TV" shell settings get secure advertisingIdApp:interestBasedAds:value
adb -s "$TV" shell settings get secure usage_metrics_marketing_enabled
adb -s "$TV" shell settings get secure USAGE_METRICS_UPLOAD_ENABLED
```

These are verification commands. A `null` response on another build does not establish an opt-out; names and implementation can differ.

### Disable the separate advertising-ID and wireless-metrics packages

Package Manager accepted these disables for the primary Android user on our build:

```sh
adb -s "$TV" shell pm disable-user --user 0 com.amazon.hybridadidservice
adb -s "$TV" shell pm disable-user --user 0 com.amazon.wirelessmetrics.service
```

Both remained `disabled-user` after reboot and no matching processes were observed. Check the disabled list and, if needed, each package dump:

```sh
adb -s "$TV" shell pm list packages -d --user 0
adb -s "$TV" shell dumpsys package com.amazon.hybridadidservice
adb -s "$TV" shell dumpsys package com.amazon.wirelessmetrics.service
```

A protected-package error on another firmware is a failed change, not a reason to assume the disable worked.

### Minerva and what remains

We also applied these package-level changes:

```sh
adb -s "$TV" shell pm disable-user --user 0 com.amazon.neo.minerva
adb -s "$TV" shell pm disable-user --user 0 com.amazon.neo.minerva.logcat
```

**Their native processes continued running, including after reboot.** These commands are recorded for completeness; they did not achieve native Minerva shutdown. A disabled APK entry is not proof that the associated native service stopped.

An attempt to disable `com.amazon.device.metrics` returned `SecurityException: Cannot disable a protected package`; it remained enabled. We left `com.amazon.client.metrics.api`, shared system logging/framework packages and the inspected privacy cloud receiver/provider components unchanged.

The result is **opt-outs plus partial package blocking**. We did not remove every telemetry path, remove the Settings switches or make them impossible to re-enable. Individual apps and native Amazon services can still collect data. No packet-capture audit established zero telemetry, and a reset or firmware change can undo the changes.

### Restore the package defaults

The four packages originally had the default enabled state. To undo our package overrides on this device:

```sh
adb -s "$TV" shell pm default-state --user 0 com.amazon.hybridadidservice
adb -s "$TV" shell pm default-state --user 0 com.amazon.wirelessmetrics.service
adb -s "$TV" shell pm default-state --user 0 com.amazon.neo.minerva
adb -s "$TV" shell pm default-state --user 0 com.amazon.neo.minerva.logcat
```

This does not turn the privacy opt-outs on. Recheck their values separately. These reversal commands are documented recovery actions, not changes we applied after hardening.

## Recovery

Keep **Launcher Manager and a working custom launcher installed** until another Home route has been restored. GlassOS **Prepare to uninstall** removes its optional app-level device admin; it does not undo Launcher Manager's protected system changes.

Preferred recovery is through Launcher Manager: with the privileges required by that firmware available, turn **Custom Launcher Support** off, then confirm stock Home and Fire TV Settings both work. Only then remove a launcher. Regaining temporary root may itself fail, as it did in our later attempts, so do not assume recovery is one guaranteed command.

For an experienced operator with a **verified root shell**, the original recovery notes recorded the following manual stock-Home sequence. It was **not executed or validated as a rollback** during our setup:

```sh
# Inside a verified root shell; not ordinary unprivileged adb shell.
# Restore stock Home before changing the preferred Home handler.
pm enable com.amazon.tv.launcher
pm enable com.amazon.tv.launcher/.ui.HomeActivity_vNext
pm enable com.amazon.firehomestarter
cmd package set-home-activity com.amazon.tv.launcher/.ui.HomeActivity_vNext
pm default-state com.amazon.tv.settings.v2/.compliance.CTSDummySettingActivity
pm default-state com.amazon.tv.settings.v2/.compliance.CTSDummySettingsHandler
pm default-state com.android.permissioncontroller/.role.ui.HomeSettingsActivity
am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

To also restore the update packages' original default state, with the required privileges:

```sh
pm default-state com.amazon.device.software.ota
pm default-state com.amazon.device.software.ota.override
pm default-state com.amazon.sneakpeek
```

Updates may patch the customization method. Verify Home, Settings and package states after any recovery work. Do not overwrite live package-restriction files with archived dumps; those dumps are references, not firmware recovery images.

## Evidence and scope

This summary was transcribed from our saved installation record, successful GhostLock log, Launcher Manager state, package dumps, privacy hardening record and post-reboot checks. Private device addresses, raw logs, app-data backups and signing files are kept out of the public repository. The public [app validation record](VALIDATION.md) covers the later Aerial release.

The observations describe one device and its recorded firmware. Neither this README nor an APK installation guarantees permanent Home replacement or total telemetry removal on another Fire TV.
