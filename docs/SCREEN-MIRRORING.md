# Screen Mirroring

GlassOS Aerial can show the screen of an iPhone, iPad or Mac on the TV, and play its music. It is compatible with
AirPlay screen mirroring and audio; it is not made, endorsed or certified by Apple. The receiver is built on
[UxPlay](https://github.com/FDH2/UxPlay) and [jqssun's Android receiver](https://github.com/jqssun/android-airplay-server)
and runs in its own process (`:airplay`), so a problem there never takes the home down. It is **off until you turn
it on**.

It needs Fire OS 6 or Android 7.1 (API 25) or later, and a GlassOS build that includes it. Where it cannot run, the
Settings section and the Control Center tile are not shown at all. The sender and the TV must be on the same network.

So far it has been tested on an Android TV emulator with simulated senders only. Real iPhone and Mac sessions and
Fire TV hardware are still to be validated.

## Turn it on and mirror

1. On the TV, open **Settings > Screen Mirroring > Mirroring** and choose it to turn it **On**, or press Menu for
   Control Center and choose **Screen Mirroring**. **Status** then says **Ready as "<TV name>"**.
2. On an iPhone or iPad, open Control Center, choose **Screen Mirroring** and pick the TV's name. On a Mac, use
   **Screen Mirroring** in the menu bar's Control Center.
3. The TV shows the mirrored screen full-screen, sized to fit (a phone held upright shows with bars at the sides).
   The scenery stops while you mirror and plays again when you stop.

On the mirrored screen, OK shows a **Disconnect** button, which ends the session for every sender. **Back** hides the
mirrored screen and returns to GlassOS while mirroring goes on; the Control Center tile then reads **Mirroring**, and
choosing it shows the screen again. Music on its own does not take over the screen: the scenery keeps playing and the
tile reads **Playing** (choose it to see what is playing).

When it is on, the receiver starts again by itself after the TV restarts and after GlassOS is updated.

## Control Center tile

| Label | Means | Choosing it |
| --- | --- | --- |
| Screen Mirroring (dim) | Off | turns it on |
| Screen Mirroring (lit) | On, waiting for a sender | turns it off |
| Screen Mirroring (lit) | On, but it could not start or stopped unexpectedly (Settings > Status says why) | turns it off; turn it on again to retry |
| Mirroring | A screen is mirrored | shows the mirrored screen |
| Playing | Music is playing | shows what is playing |
| PIN shown | A sender asked for the code | shows the code |

The tile is never Apple's AirPlay symbol; it is GlassOS's own: a TV showing a phone's screen.

## Settings

**Settings > Screen Mirroring**, under the line "Compatible with AirPlay from iPhone, iPad and Mac":

- **Mirroring**: on or off.
- **Status** (not selectable): **Off**, **Starting…**, **Ready as "<name>"**, **Connecting…**, **Mirroring**,
  **Playing**, **Showing a code**, a short reason it could not start (such as **No free network port**), or
  **Stopped unexpectedly**: the receiver kept crashing, and GlassOS stopped restarting it (it restarts it after 2,
  10 and 60 seconds first). Turn Mirroring off and on to try again. "· hidden" means a session is running with its screen hidden; choose the tile to
  see it. Off always reads Off.
- **TV name**: the name iPhone, iPad and Mac list. Edit it with the on-screen keyboard; leave it empty to use the TV's
  own device name. At most 63 bytes.
- **Require PIN**: each sender must type a 4-digit code that the TV shows. The code appears on the TV only, never in
  logs, notifications, backups or the Mac manager. Senders pair again after each restart of the receiver, so they may
  ask for the code again. After a wrong code the next try waits longer each time (1, 2, 4 … up to 60 seconds), and
  after three wrong codes the TV shows a new one.
- **Resolution**: **1080p** (the default), **720p** (try it if mirroring stutters) or **Auto** (the TV's own size,
  never more than 1080p).
- **Sender controls TV volume**: the sender's volume slider changes the TV's volume, and the TV's volume buttons the
  sender's. Skipped on TVs whose volume is fixed (common over HDMI); the sound then follows the sender only.
- **Over other apps** (Android 10 and later, so Fire OS 8): whether GlassOS may show the mirrored screen over another
  app (see below). Choosing it opens the permissions step. Older versions need no permission for that, and do not show
  the row.

Changing the name, PIN or resolution restarts the receiver, which drops a connected sender; the volume setting
applies at once. Settings changed while Mirroring is off apply the next time it is on.

## Display over other apps

When mirroring starts while another app is in front (say, during a show), Fire OS 8 and Android 10 or later let
GlassOS bring the mirrored screen up only if it may **Display over other apps**. Without that permission the session
still connects and plays, hidden: the tile reads **Mirroring**, and choosing it in Control Center shows it. A PIN
request would also stay hidden until you open Control Center. When GlassOS itself is on screen, nothing is needed.

GlassOS never changes this permission by itself. To allow it, use **Settings > Permissions > Set Up** (the TV's own
ADB, as for the other permissions), or from a computer:

```sh
adb -s "$TV" shell appops set io.github.abhishekkaisar.glassos.aerial SYSTEM_ALERT_WINDOW allow
```

Run it with `default` instead of `allow` to undo it. The same permission also serves the Home overlay and the app
lock's casting protection.

## On your network

While Mirroring is on, the TV announces itself over Bonjour (mDNS) and listens on TCP port 7000 (another free port
if 7000 is taken, for example by another receiver app) and a few UDP ports. Anyone on the same network can mirror to
it unless **Require PIN** is on. Turn Mirroring off when you do not use it. Require PIN keeps casual use out on a
shared network, but it is a 4-digit code: on a network you do not trust, keep Mirroring off.
The pairing identity (`airplay.pem`) and a fallback device address stay in GlassOS's private storage.

## Backups and GlassOS Manager

A settings backup made where Screen Mirroring is available carries its on/off state, the TV name you chose, Require
PIN, Resolution and Sender controls TV volume. Restoring one starts, restarts or stops the receiver to match; when it
would turn Screen Mirroring on, the confirmation says so, and whether a code will be needed. A TV or build without
Screen Mirroring neither writes nor restores these settings. A backup never holds the pairing identity, the
receiver's private files or the advanced settings below.

In the [Mac manager](../manager/README.md#tv-controls), **TV controls > Screen Mirroring > Check** shows the state and
settings, read only. It never turns mirroring on or off and never shows a code. Advanced settings (port, H.265,
frame rate, audio buffering) are reachable only over ADB:

```sh
adb -s "$TV" shell content call --uri content://io.github.abhishekkaisar.glassos.aerial.manager --method airplay-status
adb -s "$TV" shell content call --uri content://io.github.abhishekkaisar.glassos.aerial.manager --method airplay-set --arg max_fps=30
```

`airplay-set` takes `key=value` (an empty value restores the default); see `ManagerProvider.kt` for every key.

## Troubleshooting

- **The TV is not in the sender's list.** Check that **Status** says Ready, that both are on the same network (not a
  guest network, and with no "client isolation" or VPN in between), and that the router passes Bonjour (mDNS).
  Turning Mirroring off and on announces the TV again.
- **Status: No free network port.** The receiver could open neither port 7000 nor another one. Restart the TV, then
  turn Mirroring off and on.
- **Status: Stopped unexpectedly.** Turn Mirroring off and on. If it keeps happening, note what you were mirroring and
  open an issue.
- **Mirroring started but the TV shows the home or another app.** The session is hidden: choose **Mirroring** in
  Control Center, or allow Display over other apps.
- **The picture stutters or lags.** Try Resolution 720p, and a 5 GHz network for both devices.
- **No sound from music.** Apple Lossless (ALAC) streams are not supported in this build (see below); AAC plays
  through the TV's own decoder.
- **The sender asks for a code but none shows.** Open GlassOS, press Menu and choose **PIN shown** in Control Center;
  allow Display over other apps so the code shows over other apps too.

## Not supported yet

- **Video handover** ("play on TV" from YouTube, Safari or other apps, which sends a stream address instead of the
  screen): the receiver does not offer it, so such apps mirror the whole screen instead.
- **Apple Lossless audio** and software audio decoding: FFmpeg is not included in this build.
- **Protected video** (the Apple TV app and other FairPlay Streaming content) does not play, and no code here tries to
  get around that protection.
- Fire OS 5 (Android 5.1): the feature is hidden there.

## Trademarks

AirPlay, iPhone, iPad, Mac and Apple TV are trademarks of Apple Inc., registered in the U.S. and other countries and
regions. GlassOS Aerial is an independent project, not affiliated with, endorsed, sponsored or certified by Apple, and
not part of Apple's MFi or "Works with Apple" programs. Apple's product names appear only to say what it works with.
See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md#trademarks).

## Source code and licences

GlassOS Aerial is free software under the GNU GPL v3; the receiver's components keep their own licences (LGPL 2.1 or
later, GPL v3, MIT, Apache 2.0), all listed in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). The full texts are
in the app under **Settings > About > Open-source licences**.

The complete corresponding source of every build is the tagged source in this repository, including the vendored
native code under `airplay/src/main/cpp/`, plus OpenSSL's source tarball, which the build downloads and checks by
SHA-256. Releases that include Screen Mirroring are meant to attach it as one file,
`glassos-aerial-<version>-src.tar.gz`, which `tools/airplay/make_source_bundle.sh` builds from the files git tracks
(untracked files only with `--include-untracked`, which lists them):

```sh
tools/airplay/make_source_bundle.sh ~/Desktop     # writes ~/Desktop/glassos-aerial-<version>-src.tar.gz
```

If a release you received has no source bundle, ask in the
[issue tracker](https://github.com/hnkabraham/glassos-aerial/issues) and you will be given the source for that version.
