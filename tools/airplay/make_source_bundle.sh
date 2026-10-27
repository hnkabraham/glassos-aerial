#!/usr/bin/env bash
# Builds glassos-aerial-<version>-src.tar.gz: the complete corresponding source of a GlassOS Aerial APK that includes
# the screen-mirroring receiver (GNU GPL v3, section 6), for a release to attach next to the APK.
#
#   tools/airplay/make_source_bundle.sh [--include-untracked] [OUTPUT_DIR]    (default: the current directory)
#
# What goes in, under one top directory glassos-aerial-<version>/:
#   - every file git tracks, as it is on disk now (so build from the commit you release, with nothing uncommitted;
#     the script warns when tracked files differ from the commit);
#   - the vendored native sources (airplay/src/main/cpp/third_party/) like any other file;
#   - .cache/airplay-downloads/openssl-<version>.tar.gz, the one source the repository does not hold, checked against
#     the SHA-256 pinned in airplay/src/main/cpp/CMakeLists.txt (a normal build downloads it there once).
# Untracked files stay out (a bugreport, an adb key or a pulled pairing key left in the tree would otherwise be
# published); the script says how many it left out. --include-untracked adds those .gitignore does not exclude and
# lists each one. What stays out either way: .git, .cache/ (but the OpenSSL tarball), and, whatever git says,
# local.properties, keystore.properties, tmdb.properties, keys and keystores (*.jks, *.keystore, *.p12, *.pem, *.pk8,
# *.p8, adbkey*), .env files, bug reports (bugreport*.zip) and heap dumps (*.hprof).
#
# The version is app/build.gradle.kts's versionName. Needs bash, git, rsync, tar and shasum or sha256sum; works with
# the BSD tools on macOS and the GNU ones on Linux. Whether and how a release attaches the bundle is the
# maintainer's decision; THIRD_PARTY_NOTICES.md says every release with the receiver should.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../.." && pwd)
untracked=0
if [ "${1:-}" = --include-untracked ]; then untracked=1; shift; fi
case "${1:-}" in -*) echo "make_source_bundle.sh: unknown option $1 (only --include-untracked)" >&2; exit 2 ;; esac
OUT_DIR=${1:-$PWD}
mkdir -p "$OUT_DIR"
OUT_DIR=$(cd "$OUT_DIR" && pwd)

version=$(sed -n 's/^ *versionName = "\(.*\)".*/\1/p' "$REPO/app/build.gradle.kts" | head -n 1)
[ -n "$version" ] || { echo "make_source_bundle.sh: no versionName in app/build.gradle.kts" >&2; exit 1; }
cmake="$REPO/airplay/src/main/cpp/CMakeLists.txt"
ssl_version=$(sed -n 's/^set(AIRPLAY_OPENSSL_VERSION \([0-9.]*\))/\1/p' "$cmake")
ssl_sha=$(sed -n 's/^set(AIRPLAY_OPENSSL_SHA256 \([0-9a-f]*\))/\1/p' "$cmake")
ssl_rel=.cache/airplay-downloads/openssl-$ssl_version.tar.gz
name=glassos-aerial-$version
archive=$OUT_DIR/$name-src.tar.gz

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

[ -f "$REPO/$ssl_rel" ] || { echo "make_source_bundle.sh: $ssl_rel is missing; build once (./gradlew assembleDebug) to download it" >&2; exit 1; }
[ "$(sha256 "$REPO/$ssl_rel")" = "$ssl_sha" ] || { echo "make_source_bundle.sh: $ssl_rel does not match the pinned SHA-256" >&2; exit 1; }
git -C "$REPO" rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "make_source_bundle.sh: $REPO is not a git working tree" >&2; exit 1; }

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
list=$stage/files

# Never packed, tracked or not: local settings, keys, environment files, bug reports and heap dumps.
deny='(^|/)(local\.properties|keystore\.properties|tmdb\.properties|adbkey[^/]*|\.env[^/]*|bugreport[^/]*\.zip)$|\.(jks|keystore|p12|pem|pk8|p8|hprof)$|(^|/)\.cache/'
# Files git lists that exist on disk, less the deny-list. --no-optional-locks: reading never rewrites git's index.
on_disk() { tr '\0' '\n' | { grep -v -E "$deny" || true; } | while IFS= read -r f; do if [ -f "$REPO/$f" ]; then printf '%s\n' "$f"; fi; done; }
git -C "$REPO" --no-optional-locks ls-files -z --cached | on_disk > "$list"
git -C "$REPO" --no-optional-locks ls-files -z --others --exclude-standard | on_disk > "$stage/untracked"
extra=$(wc -l < "$stage/untracked" | tr -d ' ')
if [ "$untracked" = 1 ]; then
  cat "$stage/untracked" >> "$list"
  echo "including $extra untracked file(s):"
  sed 's/^/  + /' "$stage/untracked"
elif [ "$extra" != 0 ]; then
  echo "left out $extra untracked file(s) (commit them, or pass --include-untracked to pack and list them)" >&2
fi
git -C "$REPO" --no-optional-locks diff --quiet HEAD -- 2>/dev/null ||
  echo "warning: tracked files differ from the last commit; the bundle has them as they are on disk" >&2
printf '%s\n' "$ssl_rel" >> "$list"

mkdir "$stage/$name"
rsync -a --files-from="$list" "$REPO/" "$stage/$name/"
# The archive itself never lands inside what it packs.
tar -C "$stage" -czf "$archive.part" "$name"
mv "$archive.part" "$archive"

files=$(wc -l < "$list" | tr -d ' ')
echo "$archive"
echo "  $files files, $(du -h "$archive" | cut -f1 | tr -d ' '), sha256 $(sha256 "$archive")"
echo "  includes $ssl_rel (sha256 $ssl_sha) and the vendored native sources"
