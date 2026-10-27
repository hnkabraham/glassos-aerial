#!/usr/bin/env bash
# Rebuilds the vendored native sources of the :airplay module (screen mirroring) from pinned upstream commits.
#
#   tools/airplay/vendor.sh            regenerate airplay/src/main/cpp/third_party/ and patches/UxPlay/0001-0009
#   tools/airplay/vendor.sh --verify   regenerate into a temporary directory and diff it against the committed tree;
#                                      exits 1 if anything differs
#
# A maintainer runs this after changing a pin or a patch. Gradle and CI never run it: the build only uses the
# committed result, so it needs no git and no submodules.
#
# What it does:
#   - exports the pinned commits below (git archive, so only committed content counts);
#   - applies patches/UxPlay/*.patch to UxPlay in name order: 0001-0009 are jqssun's, copied verbatim from
#     jqssun at its pin; 0010 and later are GlassOS patches;
#   - applies patches/openssl-cmake/*.patch to openssl-cmake;
#   - copies the subset the build uses plus the licence files (FFmpeg is not vendored: AIRPLAY_FFMPEG is OFF;
#     libplist's cnary.c, a test program with its own main(), is left out);
#   - writes third_party/VENDORED.txt.
# The files adapted from jqssun (the JNI bridge, cmake/*.cmake, CMakeLists.txt, the Kotlin bridge and
# consumer-rules.pro) are owned by GlassOS after the first import: this script only creates them when they are
# missing, and --verify reports how they differ from upstream without failing on it (it fails only when one is
# missing or has lost its "Adapted from" header). The Kotlin receiver classes ported from jqssun (PORTED_FILES:
# the service, renderers, discovery and audio helpers) were restructured for GlassOS, so this script never creates
# them; --verify fails the same way when one is missing or has lost its header.
#
# Requirements: bash, git, tar, diff, and network access to github.com. To reuse local clones instead of
# fetching, point these variables at git repositories that contain the pinned commits (only their object
# databases are read, never their working trees):
#   AIRPLAY_JQSSUN_SRC          a jqssun/android-airplay-server clone. When it is set, the three variables below
#                               default to its submodules under app/src/main/cpp/third_party/.
#   AIRPLAY_UXPLAY_SRC  AIRPLAY_LIBPLIST_SRC  AIRPLAY_OPENSSL_CMAKE_SRC
set -euo pipefail

JQSSUN_URL=https://github.com/jqssun/android-airplay-server.git
JQSSUN_COMMIT=a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea          # v0.0.36
UXPLAY_URL=https://github.com/FDH2/UxPlay.git
UXPLAY_COMMIT=df67c212a433cf6dda3676dd40c097900d24e645          # the commit jqssun a46cca0 pins
LIBPLIST_URL=https://github.com/libimobiledevice/libplist.git
LIBPLIST_COMMIT=f41b1ea67045e0c09339974d83e389972d84f166        # 2.7.0-55-gf41b1ea, pinned by jqssun a46cca0
OPENSSL_CMAKE_URL=https://github.com/viaduck/openssl-cmake.git
OPENSSL_CMAKE_COMMIT=4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8   # branch v3, pinned by jqssun a46cca0

HEADER_LINE="Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25"
HEADER_LICENCE="Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md)."

# jqssun path (relative to its repository) -> GlassOS path (relative to this repository)
BRIDGE_FILES="
app/src/main/cpp/CMakeLists.txt                     airplay/src/main/cpp/CMakeLists.txt
app/src/main/cpp/android_dnssd_shim.c               airplay/src/main/cpp/android_dnssd_shim.c
app/src/main/cpp/android_dnssd_shim.h               airplay/src/main/cpp/android_dnssd_shim.h
app/src/main/cpp/android_raop_callbacks.c           airplay/src/main/cpp/android_raop_callbacks.c
app/src/main/cpp/android_raop_callbacks.h           airplay/src/main/cpp/android_raop_callbacks.h
app/src/main/cpp/audio_decoder.h                    airplay/src/main/cpp/audio_decoder.h
app/src/main/cpp/audio_engine.cpp                   airplay/src/main/cpp/audio_engine.cpp
app/src/main/cpp/audio_engine.h                     airplay/src/main/cpp/audio_engine.h
app/src/main/cpp/audio_output.h                     airplay/src/main/cpp/audio_output.h
app/src/main/cpp/audio_time.h                       airplay/src/main/cpp/audio_time.h
app/src/main/cpp/log_sink.h                         airplay/src/main/cpp/log_sink.h
app/src/main/cpp/native_bridge.cpp                  airplay/src/main/cpp/native_bridge.cpp
app/src/main/cpp/timeline_buffer.h                  airplay/src/main/cpp/timeline_buffer.h
app/src/main/cpp/cmake/BuildFFmpeg.cmake            airplay/src/main/cpp/cmake/BuildFFmpeg.cmake
app/src/main/cpp/cmake/PatchOpenSSL.cmake           airplay/src/main/cpp/cmake/PatchOpenSSL.cmake
app/src/main/cpp/cmake/scrub_mkbuildinf.cmake       airplay/src/main/cpp/cmake/scrub_mkbuildinf.cmake
app/src/main/kotlin/io/github/jqssun/airplay/bridge/NativeBridge.kt         airplay/src/main/java/io/github/jqssun/airplay/bridge/NativeBridge.kt
app/src/main/kotlin/io/github/jqssun/airplay/bridge/RaopCallbackHandler.kt  airplay/src/main/java/io/github/jqssun/airplay/bridge/RaopCallbackHandler.kt
app/src/main/kotlin/io/github/jqssun/airplay/bridge/LogListener.kt          airplay/src/main/java/io/github/jqssun/airplay/bridge/LogListener.kt
app/proguard-rules.pro                              airplay/consumer-rules.pro
"

# Kotlin receiver classes ported from jqssun into GlassOS's package (jqssun path -> GlassOS path). Never created by
# this script; --verify checks that each exists and keeps its "Adapted from" header.
PORTED_FILES="
app/src/main/kotlin/io/github/jqssun/airplay/service/AirPlayService.kt        airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/service/AirPlayService.kt
app/src/main/kotlin/io/github/jqssun/airplay/service/AirPlayService.kt        airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/service/Session.kt
app/src/main/kotlin/io/github/jqssun/airplay/Prefs.kt                         airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/AirPlayConfig.kt
app/src/main/kotlin/io/github/jqssun/airplay/Display.kt                       airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/render/DisplayInfo.kt
app/src/main/kotlin/io/github/jqssun/airplay/renderer/AudioConfig.kt          airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/render/AudioConfig.kt
app/src/main/kotlin/io/github/jqssun/airplay/renderer/AudioRenderer.kt        airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/render/AudioRenderer.kt
app/src/main/kotlin/io/github/jqssun/airplay/renderer/DecoderSelector.kt      airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/render/DecoderSelector.kt
app/src/main/kotlin/io/github/jqssun/airplay/renderer/EglCore.kt              airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/render/EglCore.kt
app/src/main/kotlin/io/github/jqssun/airplay/renderer/VideoPipeline.kt        airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/render/VideoPipeline.kt
app/src/main/kotlin/io/github/jqssun/airplay/renderer/VideoRenderer.kt        airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/render/VideoRenderer.kt
app/src/main/kotlin/io/github/jqssun/airplay/discovery/NsdServiceManager.kt   airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/discovery/NsdServiceManager.kt
app/src/main/kotlin/io/github/jqssun/airplay/audio/DacpController.kt          airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/audio/DacpController.kt
app/src/main/kotlin/io/github/jqssun/airplay/audio/DmapParser.kt              airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/audio/DmapParser.kt
app/src/main/kotlin/io/github/jqssun/airplay/audio/TrackInfo.kt               airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/audio/TrackInfo.kt
app/src/main/kotlin/io/github/jqssun/airplay/audio/VolumeBroadcast.kt         airplay/src/main/java/io/github/abhishekkaisar/glassos/airplay/audio/VolumeBroadcast.kt
"

LIBPLIST_FILES="
AUTHORS COPYING COPYING.LESSER
include/plist/plist.h
libcnary/COPYING libcnary/node.c libcnary/node_list.c
libcnary/include/node.h libcnary/include/node_list.h libcnary/include/object.h
src/base64.c src/base64.h src/bplist.c src/bytearray.c src/bytearray.h src/hashtable.c src/hashtable.h
src/jplist.c src/jsmn.c src/jsmn.h src/oplist.c src/out-default.c src/out-limd.c src/out-plutil.c
src/plist.c src/plist.h src/ptrarray.c src/ptrarray.h src/strbuf.h src/time64.c src/time64.h
src/time64_limits.h src/xplist.c
"

die() { echo "vendor.sh: $*" >&2; exit 1; }

VERIFY=0
case "${1:-}" in
  "") ;;
  --verify) VERIFY=1 ;;
  -h|--help) sed -n '2,31p' "$0"; exit 0 ;;
  *) die "unknown argument: $1 (use --verify or nothing)" ;;
esac

for t in git tar diff; do command -v "$t" >/dev/null || die "missing tool: $t"; done
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
CPP="$REPO/airplay/src/main/cpp"
[ -d "$CPP/patches/UxPlay" ] || die "missing $CPP/patches/UxPlay"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/airplay-vendor.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

if [ -n "${AIRPLAY_JQSSUN_SRC:-}" ]; then
  : "${AIRPLAY_UXPLAY_SRC:=$AIRPLAY_JQSSUN_SRC/app/src/main/cpp/third_party/UxPlay}"
  : "${AIRPLAY_LIBPLIST_SRC:=$AIRPLAY_JQSSUN_SRC/app/src/main/cpp/third_party/libplist}"
  : "${AIRPLAY_OPENSSL_CMAKE_SRC:=$AIRPLAY_JQSSUN_SRC/app/src/main/cpp/third_party/openssl-cmake}"
fi

# export NAME URL COMMIT LOCAL_CLONE: writes the committed tree of COMMIT to $WORK/up/NAME
export_tree() {
  local name="$1" url="$2" commit="$3" src="$4" dest="$WORK/up/$1" git_dir
  mkdir -p "$dest"
  if [ -n "$src" ]; then
    git -C "$src" cat-file -e "$commit^{commit}" 2>/dev/null || die "$src does not contain $name $commit"
    git_dir="$src"
    echo "$name: $commit from local clone $src"
  else
    git_dir="$WORK/git/$name"
    git init -q --bare "$git_dir"
    echo "$name: fetching $commit from $url"
    git -C "$git_dir" fetch -q --depth 1 "$url" "$commit" || die "fetching $name $commit from $url failed"
  fi
  git -C "$git_dir" -c core.autocrlf=false -c core.eol=lf archive --format=tar "$commit" | tar -x -C "$dest"
}

export_tree jqssun "$JQSSUN_URL" "$JQSSUN_COMMIT" "${AIRPLAY_JQSSUN_SRC:-}"
export_tree UxPlay "$UXPLAY_URL" "$UXPLAY_COMMIT" "${AIRPLAY_UXPLAY_SRC:-}"
export_tree libplist "$LIBPLIST_URL" "$LIBPLIST_COMMIT" "${AIRPLAY_LIBPLIST_SRC:-}"
export_tree openssl-cmake "$OPENSSL_CMAKE_URL" "$OPENSSL_CMAKE_COMMIT" "${AIRPLAY_OPENSSL_CMAKE_SRC:-}"
UP="$WORK/up"

# The pins must be the submodule commits jqssun's tree records, or its patches may not apply as intended.
if [ -n "${AIRPLAY_JQSSUN_SRC:-}" ]; then JQ_GIT="$AIRPLAY_JQSSUN_SRC"; else JQ_GIT="$WORK/git/jqssun"; fi
for pair in "UxPlay $UXPLAY_COMMIT" "libplist $LIBPLIST_COMMIT" "openssl-cmake $OPENSSL_CMAKE_COMMIT"; do
  set -- $pair
  pinned="$(git -C "$JQ_GIT" ls-tree "$JQSSUN_COMMIT" "app/src/main/cpp/third_party/$1" | awk '{print $3}')"
  [ "$pinned" = "$2" ] || die "jqssun $JQSSUN_COMMIT pins $1 at '$pinned', this script at $2"
done

OUT="$WORK/out"
mkdir -p "$OUT/third_party" "$OUT/patches/UxPlay"

# ---- patches: jqssun's 0001-0009 verbatim, then the committed GlassOS ones (0010 and later) ----
JQ_PATCHES="$UP/jqssun/app/src/main/cpp/patches/UxPlay"
cp "$JQ_PATCHES"/*.patch "$OUT/patches/UxPlay/"
for p in "$CPP"/patches/UxPlay/*.patch; do
  n="$(basename "$p")"
  [ -e "$JQ_PATCHES/$n" ] || cp "$p" "$OUT/patches/UxPlay/$n"
done

apply_patches() {  # apply_patches TREE PATCH...
  local tree="$1"; shift
  git -C "$tree" init -q
  for p in "$@"; do
    git -C "$tree" apply --unidiff-zero --whitespace=nowarn "$p" || die "$(basename "$p") does not apply to $tree"
  done
}

# ---- UxPlay: lib/ (patched), LICENSE, README.md (source of the disclaimer quoted in THIRD_PARTY_NOTICES.md) ----
UX_PATCHES=()
while IFS= read -r p; do UX_PATCHES+=("$p"); done < <(ls "$OUT"/patches/UxPlay/*.patch | LC_ALL=C sort)
apply_patches "$UP/UxPlay" "${UX_PATCHES[@]}"
mkdir -p "$OUT/third_party/UxPlay"
cp -R "$UP/UxPlay/lib" "$OUT/third_party/UxPlay/lib"
cp "$UP/UxPlay/LICENSE" "$UP/UxPlay/README.md" "$OUT/third_party/UxPlay/"

# ---- libplist: the C library sources the build compiles, without cnary.c, plus licences ----
for f in $LIBPLIST_FILES; do
  mkdir -p "$OUT/third_party/libplist/$(dirname "$f")"
  cp "$UP/libplist/$f" "$OUT/third_party/libplist/$f"
done

# ---- openssl-cmake: the whole repository plus the GlassOS patches ----
OSC_PATCHES=()
while IFS= read -r p; do OSC_PATCHES+=("$p"); done < <(ls "$CPP"/patches/openssl-cmake/*.patch | LC_ALL=C sort)
apply_patches "$UP/openssl-cmake" "${OSC_PATCHES[@]}"
rm -rf "$UP/openssl-cmake/.git"
cp -R "$UP/openssl-cmake" "$OUT/third_party/openssl-cmake"

# ---- VENDORED.txt ----
{
  echo "Vendored native sources of the :airplay module (screen mirroring)."
  echo "Generated by tools/airplay/vendor.sh. Do not edit files under third_party/ by hand: change a pin or add a"
  echo "patch under ../patches/, re-run the script, and check the result with tools/airplay/vendor.sh --verify."
  echo
  echo "UxPlay/"
  echo "  upstream  $UXPLAY_URL"
  echo "  commit    $UXPLAY_COMMIT"
  echo "  contents  lib/ (with llhttp/ and playfair/), LICENSE, README.md"
  echo "  licence   GPL-3.0 project; lib/ files LGPL-2.1-or-later, except crypto.c/.h (GPL-3.0-or-later),"
  echo "            srp.c/.h (MIT), llhttp/ (MIT) and playfair/ (GPL-3.0)"
  echo "  patches   applied in this order (sha256 of the patch file):"
  for p in "${UX_PATCHES[@]}"; do
    n="$(basename "$p")"
    if [ -e "$JQ_PATCHES/$n" ]; then who="jqssun $JQSSUN_COMMIT, verbatim"; else who="GlassOS Aerial"; fi
    echo "    $n  $(sha256 "$p")  ($who)"
  done
  echo
  echo "libplist/"
  echo "  upstream  $LIBPLIST_URL"
  echo "  commit    $LIBPLIST_COMMIT (2.7.0-55-gf41b1ea)"
  echo "  contents  the C library subset compiled by CMakeLists.txt; libcnary/cnary.c (a test program), the C++"
  echo "            API, tools, tests and autotools files are left out"
  echo "  licence   LGPL-2.1-or-later (jsmn and time64: MIT); COPYING (GPL-2.0) covers only libplist's tools"
  echo
  echo "openssl-cmake/"
  echo "  upstream  $OPENSSL_CMAKE_URL"
  echo "  commit    $OPENSSL_CMAKE_COMMIT"
  echo "  contents  the whole repository (build tooling only, nothing of it is in the APK)"
  echo "  licence   MIT"
  echo "  patches   applied in this order (sha256 of the patch file):"
  for p in "${OSC_PATCHES[@]}"; do echo "    $(basename "$p")  $(sha256 "$p")  (GlassOS Aerial)"; done
  echo
  echo "Not vendored"
  echo "  OpenSSL   downloaded at build time by openssl-cmake with a pinned SHA-256 (see ../CMakeLists.txt)"
  echo "  FFmpeg    compiled out (AIRPLAY_FFMPEG=OFF); jqssun pins n8.1.2 = 38b88335f99e76ed89ff3c93f877fdefce736c13"
  echo "  Oboe      com.google.oboe:oboe:1.9.3, a prefab AAR resolved by Gradle"
  echo
  echo "Adapted from jqssun/android-airplay-server ($JQSSUN_URL @ $JQSSUN_COMMIT, GPL-3.0)"
  echo "  These files carry an \"Adapted from\" header and are maintained by GlassOS. vendor.sh only creates them"
  echo "  when they are missing; --verify lists how they differ from upstream."
  echo "$BRIDGE_FILES" | while read -r from to; do if [ -n "$from" ]; then echo "    $to  <-  $from"; fi; done
  echo "  Ported to GlassOS's package and restructured (never regenerated; each keeps the header):"
  echo "$PORTED_FILES" | while read -r from to; do if [ -n "$from" ]; then echo "    $to  <-  $from"; fi; done
} > "$OUT/third_party/VENDORED.txt"

# ---- adapted jqssun files: upstream plus the header ----
with_header() {  # with_header SRC: prints SRC with the "Adapted from" header in its comment syntax
  case "$1" in
    *.c|*.h|*.cpp|*.kt)
      printf '/*\n * %s\n * %s\n */\n' "$HEADER_LINE" "$HEADER_LICENCE" ;;
    *)
      printf '# %s\n# %s\n' "$HEADER_LINE" "$HEADER_LICENCE" ;;
  esac
  cat "$1"
}

if [ "$VERIFY" = 1 ]; then
  status=0
  echo
  echo "== diff -r of the generated tree against airplay/src/main/cpp/third_party"
  diff -r "$OUT/third_party" "$CPP/third_party" && echo "third_party/: no differences" || status=1
  echo "== jqssun patches in airplay/src/main/cpp/patches/UxPlay"
  patches_ok=1
  for p in "$JQ_PATCHES"/*.patch; do
    n="$(basename "$p")"
    cmp -s "$p" "$CPP/patches/UxPlay/$n" || { echo "differs or missing: patches/UxPlay/$n"; patches_ok=0; status=1; }
  done
  [ "$patches_ok" = 0 ] || echo "patches/UxPlay/0001-0009: identical to jqssun $JQSSUN_COMMIT"
  # The GlassOS changes in these files are expected and only reported; a missing file or a lost "Adapted from"
  # header (required by GPLv3 section 5(a)) fails.
  echo "== adapted jqssun files (GlassOS changes against upstream plus header; each must keep the header)"
  while read -r from to; do
    [ -n "$from" ] || continue
    if [ ! -e "$REPO/$to" ]; then echo "  missing      $to"; status=1; continue; fi
    if ! grep -qF "$HEADER_LINE" "$REPO/$to"; then echo "  NO HEADER    $to"; status=1; fi
    with_header "$UP/jqssun/$from" > "$WORK/expected"
    if cmp -s "$WORK/expected" "$REPO/$to"; then
      echo "  header only  $to"
    else
      counts="$( (diff "$WORK/expected" "$REPO/$to" || true) | awk '/^</{d++} /^>/{a++} END{printf "+%d -%d", a, d}')"
      echo "  modified     $to ($counts lines)"
    fi
  done <<EOF
$BRIDGE_FILES
EOF
  echo "== ported jqssun Kotlin classes (restructured for GlassOS; each must keep the header)"
  while read -r from to; do
    [ -n "$from" ] || continue
    [ -e "$UP/jqssun/$from" ] || { echo "  no upstream  $from"; status=1; continue; }
    if [ ! -e "$REPO/$to" ]; then echo "  missing      $to"; status=1; continue; fi
    if grep -qF "$HEADER_LINE" "$REPO/$to"; then echo "  ported       $to"; else echo "  NO HEADER    $to"; status=1; fi
  done <<EOF
$PORTED_FILES
EOF
  if [ "$status" = 0 ]; then echo "vendor.sh --verify: OK"; else echo "vendor.sh --verify: the committed tree differs" >&2; fi
  exit "$status"
fi

rm -rf "$CPP/third_party"
cp -R "$OUT/third_party" "$CPP/third_party"
cp "$JQ_PATCHES"/*.patch "$CPP/patches/UxPlay/"
echo "$BRIDGE_FILES" | while read -r from to; do
  [ -n "$from" ] || continue
  if [ ! -e "$REPO/$to" ]; then
    mkdir -p "$(dirname "$REPO/$to")"
    with_header "$UP/jqssun/$from" > "$REPO/$to"
    echo "created $to"
  fi
done
echo "Vendored into ${CPP#$REPO/}/third_party. Check with: tools/airplay/vendor.sh --verify"
