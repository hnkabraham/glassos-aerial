# Third-party notices

GlassOS Aerial is free software under the **GNU General Public License, version 3** (see `LICENSE`).
It is a fork of [GlassOS](https://github.com/AbhishekKaisar/glassos-tv) by Abhishek Kaisar and contributors.

The optional screen-mirroring receiver (Gradle module `:airplay`, which runs in the app's `:airplay`
process) includes the third-party software listed below. Each component keeps its own licence. All of
them are compatible with GPLv3, so the APK as a whole is distributed under GPLv3. The code adapted
from jqssun/android-airplay-server is GPLv3-only. The full licence texts are in this repository under
`airplay/src/main/assets/licenses/` and in the APK under `assets/licenses/`, next to a copy of this file.

This product is not affiliated with, endorsed by, sponsored by or certified by Apple Inc. See
[Trademarks](#trademarks) and [Legal note on FairPlay](#legal-note-on-fairplay-handshake-code).

## Getting the source code

The receiver's native sources are committed to this repository, already patched, under
`airplay/src/main/cpp/` (there are no git submodules). `airplay/src/main/cpp/third_party/VENDORED.txt`
records each upstream commit and patch, and `tools/airplay/vendor.sh --verify` rebuilds that tree from the
pinned upstreams to show it matches. The one source the repository does not contain is OpenSSL, which the
build downloads and checks by SHA-256:

```sh
git clone --branch <tag> https://github.com/hnkabraham/glassos-aerial.git
# OpenSSL source used by the build (downloaded by airplay/src/main/cpp/CMakeLists.txt, which checks the hash):
#   https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz
#   SHA-256 603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
```

Every release that includes the receiver also attaches `glassos-aerial-<version>-src.tar.gz`, which
contains the tagged tree and the OpenSSL tarball. `tools/airplay/make_source_bundle.sh` builds it from a
working tree: every file git tracks, plus the OpenSSL tarball (checked against the SHA-256 above), without
build outputs, signing or device keys, bug reports or `local.properties`.

## Summary

| Component | Version used | Licence (SPDX) | In the APK as |
|---|---|---|---|
| android-airplay-server (jqssun and contributors) | v0.0.36, `a46cca0` (2026-10-19) | GPL-3.0-only | JNI bridge and native audio engine in `libairplay_native.so`; Kotlin JNI bridge classes and receiver (service, renderers, discovery, audio helpers) |
| UxPlay `lib/` (FDH2/UxPlay) | `df67c21` (2026-09-04, 1.73.7 preparation) + patches 0001–0012 | LGPL-2.1-or-later (most files), GPL-3.0-or-later (`crypto.c/.h`), MIT (`srp.c/.h`) | `libairplay_native.so` |
| playfair (EstebanKubata) | as vendored in UxPlay `lib/playfair/` | GPL-3.0 | `libairplay_native.so` |
| llhttp (Fedor Indutny) | 9.3.0, as vendored in UxPlay `lib/llhttp/` | MIT | `libairplay_native.so` |
| libplist (libimobiledevice) | `f41b1ea` (2026-03-30; 2.7.0 + 55 commits) | LGPL-2.1-or-later; `jsmn` and `time64` are MIT | `libairplay_native.so` |
| OpenSSL (libcrypto only) | 3.5.9 (3.5 LTS) | Apache-2.0 | statically linked into `libairplay_native.so` |
| Oboe (Google) | 1.9.3 | Apache-2.0 | `liboboe.so` |
| LLVM libc++ (Android NDK r27) | NDK 27.0.12077973 | Apache-2.0 WITH LLVM-exception | `libc++_shared.so` |
| openssl-cmake (ViaDuck) | `4edd36a` + one GlassOS patch | MIT | build scripts only, not in the APK |
| Kotlin standard library (JetBrains) | 2.0.21 | Apache-2.0 | app code (already shipped before this feature) |

FFmpeg is not included. The receiver's build has an option, `AIRPLAY_FFMPEG`, that would link FFmpeg's
ALAC and AAC decoders (LGPL-2.1-or-later). It is off, and the FFmpeg sources are not part of this
repository. A release built with it on must add an FFmpeg entry here.

## Components

### android-airplay-server

Copyright © 2026 jqssun and contributors.
<https://github.com/jqssun/android-airplay-server>. Licensed under the GNU General Public License,
version 3 (`licenses/GPL-3.0.txt`).

GlassOS Aerial adapted the JNI bridge, the NsdManager mDNS shim, the native audio engine, the CMake
build, the UxPlay patch series, the Kotlin JNI bridge and the receiver's Kotlin classes (the service, the
video and audio renderers, NSD discovery, the DACP and DMAP helpers and the settings schema), which it
moved into its own package and restructured without AndroidX, coroutines, Media3 or Guava. It left out
the Compose and Hilt UI. Every adapted file starts with the notice "Adapted from
jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25", and the
repository's git history records each change.
`third_party/VENDORED.txt` lists the adapted files and their upstream paths.

### UxPlay

<https://github.com/FDH2/UxPlay>. UxPlay as a whole is GPLv3. The `lib/` files this app compiles carry
these notices:

- Copyright © 2011–2018 Juho Vähä-Herttua (shairplay); © 2019 dsafa22; © 2014 Joakim Plate;
  © 2020 Jaslo Ziska; © 2021–2026 F. Duncanh (fduncanh). Further modifications are by Florian
  Draschbacher. Licensed under the GNU Lesser General Public License, version 2.1 or (at your option)
  any later version (`licenses/LGPL-2.1.txt`).
- `crypto.c`, `crypto.h`: Copyright © 2019 Florian Draschbacher, © 2020 Jaslo Ziska. Licensed under the
  GNU General Public License, version 3 or (at your option) any later version.
- `srp.c`, `srp.h` (from csrp): Copyright © 2010, 2013–2014 Tom Cocagne. MIT licence
  (`licenses/MIT-csrp.txt`). Modified by fduncanh in 2023.

UxPlay credits these earlier authors: antimof (original UxPlay), Florian Draschbacher (RPiPlay),
dsafa22 (AirplayServer), Juho Vähä-Herttua and contributors (shairplay), and EstebanKubata (playfair).

**Modifications to UxPlay `lib/`.** The patches are kept in `airplay/src/main/cpp/patches/UxPlay/`.
`tools/airplay/vendor.sh` applies them when it vendors UxPlay, so the committed `lib/` is the patched
result:

| Patch | Files changed | Author, date |
|---|---|---|
| 0001-fix-hls-playlist-crash | `airplay_video.c`, `http_handlers.h` | jqssun, 2026-07-09 (last revised 2026-10-18) |
| 0002-fix-uaf-on-playlist-refresh | `airplay_video.c`, `http_handlers.h` | jqssun, 2026-07-09 (2026-10-18) |
| 0003-refresh-live-media-playlists-on-demand | `airplay_video.c/.h`, `http_handlers.h` | jqssun, 2026-07-09 (2026-10-18) |
| 0004-video-sender-compat | `http_handlers.h`, `raop_handlers.h` | jqssun, 2026-07-09 (2026-10-18) |
| 0005-signal-video-reset-on-abandoned-play-connection | `http_handlers.h`, `raop.c`, `raop.h` | jqssun, 2026-07-09 (2026-10-18) |
| 0006-deliver-volume-while-audio-stream-paused | `raop_handlers.h` | jqssun, 2026-08-03 (2026-10-18) |
| 0007-keep-session-on-ble-discovery-query | `raop.c` | jqssun, 2026-10-17 (2026-10-18) |
| 0008-fix-pin-pairing-proof-length | `raop_handlers.h` | jqssun, 2026-10-19 |
| 0009-hold-play-response-only-for-direct-urls | `http_handlers.h`, `raop.h` | jqssun, 2026-10-19 |
| 0010-modification-notices | adds a "modified by" line to each file above | GlassOS Aerial, 2026-10-25 |
| 0011-limit-pin-guessing | `raop.c`, `raop_handlers.h` (pair-setup-pin: a growing wait after each wrong code, a new code after three, steps in order) | GlassOS Aerial, 2026-10-26 |
| 0012-reject-invalid-srp-key | `pairing.c` (pair-setup-pin: a client key the SRP verifier refuses is a failed proof, not a NULL dereference; adds the file's "modified by" line) | GlassOS Aerial, 2026-10-26 |

### playfair

Created by EstebanKubata in 2015 (<https://github.com/EstebanKubata/playfair>) and vendored in UxPlay
`lib/playfair/`. Licensed under the GNU General Public License, version 3 (`licenses/GPL-3.0.txt`).
The playfair source files carry no copyright notice. This attribution follows UxPlay's README
("EstebanKubata: Created a FairPlay library called PlayFair … License: GNU GPL").

### llhttp

Copyright © 2018 Fedor Indutny. MIT licence (`licenses/MIT-llhttp.txt`).

### libplist

Copyright © 2008–2026 Nikias Bassen, Jonathan Beck, Martin Szulecki, Zach C. and the other authors
listed in libplist's `AUTHORS`. libcnary is Copyright © 2011 Joshua Hill.
<https://github.com/libimobiledevice/libplist>. Licensed under the GNU Lesser General Public License,
version 2.1 or (at your option) any later version (`licenses/LGPL-2.1.txt`).
Bundled inside it:

- jsmn: Copyright © 2010 Serge A. Zaitsev. MIT (`licenses/MIT-jsmn.txt`).
- time64: Copyright © 2007–2010 Michael G Schwern. MIT (`licenses/MIT-time64.txt`).

Only the C library sources the receiver compiles are vendored, unmodified.

### OpenSSL

Copyright © 1998–2026 The OpenSSL Project Authors. Copyright © 1995–1998 Eric A. Young, Tim J. Hudson.
<https://www.openssl.org/>. Licensed under the Apache License, version 2.0 (`licenses/Apache-2.0.txt`).
Only libcrypto is linked, statically. The source is unmodified. The build rewrites `util/mkbuildinf.pl` to strip
build paths from the embedded compiler flags, and that change does not affect the compiled code.

### Oboe

Copyright © The Android Open Source Project. Apache License 2.0 (`licenses/Apache-2.0.txt`).
<https://github.com/google/oboe>. Used unmodified, from its prefab AAR.

### LLVM libc++

Part of the LLVM Project, under the Apache License v2.0 with LLVM Exceptions (`licenses/Apache-2.0.txt`,
`licenses/LLVM-exception.txt`). It is shipped as `libc++_shared.so` from Android NDK r27.

### openssl-cmake

Copyright © 2015–2018 The ViaDuck Project. MIT (`licenses/MIT-openssl-cmake.txt`). Build tooling only.
GlassOS Aerial patched it so the OpenSSL tarball comes from a configurable URL into a cacheable download
directory, and so its install step works from a path with spaces (`airplay/src/main/cpp/patches/openssl-cmake/`).

### Kotlin standard library

Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors. Apache License 2.0
(`licenses/Apache-2.0.txt`).

## Trademarks

AirPlay, Apple TV, iPhone, iPad, Mac and FairPlay are trademarks of Apple Inc.,
registered in the U.S. and other countries and regions. Fire TV is a trademark of Amazon.com, Inc. or its
affiliates. Android TV and Google TV are trademarks of Google LLC. GlassOS Aerial is an independent
project. It is not affiliated with, endorsed by, sponsored by or certified by Apple, Amazon or Google, and
it is not part of Apple's MFi or "Works with Apple" programs. Apple product names appear here only to
describe compatibility. The TMDB logo is used under TMDB's attribution terms. This product uses the TMDB
API but is not endorsed or certified by TMDB.

## Legal note on FairPlay handshake code

UxPlay's README contains this disclaimer, reproduced verbatim:

> All the resources in this repository are written using only freely available information from the
> internet. The code and related resources are meant for educational purposes only. It is the
> responsibility of the user to make sure all local laws are adhered to.
>
> This project makes use of a third-party GPL library for handling FairPlay. The legal status of that
> library is unclear. Should you be a representative of Apple and have any objections against the
> legality of the library and its use in this project, please contact the developers and the
> appropriate steps will be taken.
>
> Given the large number of third-party AirPlay receivers (mostly closed-source) available for
> purchase, it is our understanding that an open source implementation of the same functionality
> wouldn't violate any of Apple's rights either.

The receiver uses playfair only for the session-key exchange that sets up a mirroring or audio
session. It is included so that independently written software can interoperate with Apple devices
that the user owns and chooses to cast from. It does not decrypt content protected by FairPlay
Streaming DRM. Such content, for example from the Apple TV app, does not play, and no code in this
project tries to get around that protection (cf. 17 U.S.C. § 1201(f), interoperability of independently
created programs). The screen-mirroring receiver is off until the user turns it on. Laws on reverse
engineering and interoperability differ between countries, and users are responsible for using it
lawfully. If you represent Apple Inc. and object to this component, please open an issue at
<https://github.com/hnkabraham/glassos-aerial/issues>. We will respond promptly.

## No warranty

The software is provided WITHOUT ANY WARRANTY, to the extent permitted by applicable law. See section
15 of the GNU GPL v3 and the warranty disclaimers in each licence in `licenses/`.
