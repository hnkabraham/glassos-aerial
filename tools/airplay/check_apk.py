#!/usr/bin/env python3
"""Checks how the screen-mirroring receiver (:airplay) is packaged in a GlassOS Aerial APK.

    python3 tools/airplay/check_apk.py app/build/outputs/apk/debug/app-debug.apk
    python3 tools/airplay/check_apk.py --max-bytes 7000000 app/build/outputs/apk/release/app-release-unsigned.apk
    python3 tools/airplay/check_apk.py --no-native app-debug.apk    # built with -Pglassos.airplay.native=false

With natives (the default), it fails unless:
  - lib/armeabi-v7a/ and lib/arm64-v8a/ each hold exactly libairplay_native.so, liboboe.so and libc++_shared.so,
    and there is no other ABI directory (no x86 or x86_64) and no libcrypto.so anywhere;
  - the libraries are stored compressed (useLegacyPackaging);
  - libairplay_native.so's DT_NEEDED has no libcrypto, libssl or libav* entry, its Android ELF note says API 22,
    and its dynamic symbol table defines only the JNI entry points: one
    Java_io_github_jqssun_airplay_bridge_NativeBridge_<name> per `external fun` in NativeBridge.kt (23 today);
  - the APK carries assets/licenses/THIRD_PARTY_NOTICES.md, identical to the repository's THIRD_PARTY_NOTICES.md;
  - every arm64-v8a library has its LOAD segments aligned for 16 KB pages (0x4000 or more);
  - the APK is at most --max-bytes, when given.

Needs only the Python standard library plus llvm-readelf and llvm-nm from NDK 27.0.12077973, found under
--ndk, $ANDROID_HOME/ndk/27.0.12077973, $ANDROID_SDK_ROOT/ndk/27.0.12077973, $ANDROID_NDK_HOME,
$ANDROID_NDK_ROOT or the default SDK location.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

NDK_VERSION = "27.0.12077973"
ABIS = ("armeabi-v7a", "arm64-v8a")
LIBS = {"libairplay_native.so", "liboboe.so", "libc++_shared.so"}
MIN_API = 22
JNI_PREFIX = "Java_io_github_jqssun_airplay_bridge_NativeBridge_"
REPO = Path(__file__).resolve().parents[2]
NATIVE_BRIDGE = REPO / "airplay/src/main/java/io/github/jqssun/airplay/bridge/NativeBridge.kt"
NOTICES = REPO / "THIRD_PARTY_NOTICES.md"
APK_NOTICES = "assets/licenses/THIRD_PARTY_NOTICES.md"


class Report:
    def __init__(self):
        self.failed = 0

    def check(self, ok, what):
        print(("PASS  " if ok else "FAIL  ") + what)
        if not ok:
            self.failed += 1

    @staticmethod
    def info(what):
        print("INFO  " + what)


def find_llvm_bin(explicit):
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            candidates.append(Path(os.environ[var]) / "ndk" / NDK_VERSION)
    for var in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        if os.environ.get(var):
            candidates.append(Path(os.environ[var]))
    home = Path.home()
    candidates += [home / "Library/Android/sdk/ndk" / NDK_VERSION, home / "Android/Sdk/ndk" / NDK_VERSION]
    for ndk in candidates:
        for host in ("darwin-x86_64", "linux-x86_64"):
            bin_dir = ndk / "toolchains/llvm/prebuilt" / host / "bin"
            if (bin_dir / "llvm-readelf").exists() and (bin_dir / "llvm-nm").exists():
                return bin_dir
    sys.exit(f"check_apk.py: llvm-readelf/llvm-nm not found; install ndk;{NDK_VERSION} or pass --ndk")


def run(tool, *args):
    return subprocess.run([str(tool), *map(str, args)], check=True, capture_output=True, text=True).stdout


def jni_name(method):
    # JNI short-name mangling; the bridge's method names are plain identifiers.
    return JNI_PREFIX + method.replace("_", "_1")


def expected_exports():
    if not NATIVE_BRIDGE.exists():
        return None
    names = re.findall(r"\bexternal\s+fun\s+(\w+)", NATIVE_BRIDGE.read_text(encoding="utf-8"))
    return {jni_name(n) for n in names}


def android_api(readelf, so):
    # .note.android.ident: the first 4 bytes of the description are the API level (little-endian).
    m = re.search(r"Android\s+0x[0-9a-f]+\s+NT_ANDROID_TYPE_IDENT\s+description data:((?:\s+[0-9a-f]{2}){4})",
                  run(readelf, "--notes", so))
    if not m:
        return None
    return int.from_bytes(bytes(int(b, 16) for b in m.group(1).split()), "little")


def check_native(apk, zf, bin_dir, rep):
    names = zf.namelist()
    libs = [n for n in names if n.startswith("lib/")]
    abis = sorted({n.split("/")[1] for n in libs})
    rep.check(abis == sorted(ABIS), f"ABI directories are exactly {', '.join(ABIS)} (found: {', '.join(abis) or 'none'})")
    for abi in ABIS:
        found = {n.split("/", 2)[2] for n in libs if n.startswith(f"lib/{abi}/")}
        rep.check(found == LIBS, f"lib/{abi}/ holds exactly {', '.join(sorted(LIBS))} (found: {', '.join(sorted(found)) or 'none'})")
    rep.check(not any(n.endswith("/libcrypto.so") for n in names), "no libcrypto.so in the APK")
    stored = [n for n in libs if zf.getinfo(n).compress_type != zipfile.ZIP_DEFLATED]
    rep.check(not stored, "native libraries are compressed (useLegacyPackaging)" + (f"; stored: {stored}" if stored else ""))

    expected = expected_exports()
    with tempfile.TemporaryDirectory() as tmp:
        for abi in ABIS:
            entry = f"lib/{abi}/libairplay_native.so"
            if entry not in names:
                continue
            so = Path(tmp) / abi / "libairplay_native.so"
            so.parent.mkdir(parents=True)
            so.write_bytes(zf.read(entry))
            info = zf.getinfo(entry)
            rep.info(f"{entry}: {info.file_size:,} bytes, {info.compress_size:,} compressed")

            needed = re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", run(bin_dir / "llvm-readelf", "-d", so))
            bad = [n for n in needed if re.match(r"lib(crypto|ssl|av\w*|sw\w*)\b", n)]
            rep.check(not bad, f"{abi}: DT_NEEDED has no libcrypto/libssl/libav* ({', '.join(needed)})")

            api = android_api(bin_dir / "llvm-readelf", so)
            rep.check(api == MIN_API, f"{abi}: Android ELF note says API {api} (want {MIN_API})")

            syms = []
            for line in run(bin_dir / "llvm-nm", "-D", "--defined-only", so).splitlines():
                parts = line.split()
                if parts:
                    syms.append(parts[-1])
            non_jni = [s for s in syms if not s.startswith(JNI_PREFIX)]
            rep.check(not non_jni, f"{abi}: {len(syms)} defined dynamic symbols, all {JNI_PREFIX}*"
                      + (f"; others: {non_jni[:10]}" if non_jni else ""))
            if expected is not None:
                missing, extra = sorted(expected - set(syms)), sorted(set(syms) - expected)
                rep.check(not missing and not extra,
                          f"{abi}: exports match the {len(expected)} external funs in NativeBridge.kt"
                          + (f"; missing {missing}" if missing else "") + (f"; unexpected {extra}" if extra else ""))

            if abi == "arm64-v8a":
                for lib in sorted(LIBS):
                    e = f"lib/{abi}/{lib}"
                    if e not in names:
                        continue
                    p = Path(tmp) / abi / lib
                    if not p.exists():
                        p.write_bytes(zf.read(e))
                    aligns = re.findall(r"^\s*LOAD\s.*\s(0x[0-9a-f]+)\s*$", run(bin_dir / "llvm-readelf", "-lW", p), re.M)
                    smallest = min((int(a, 16) for a in aligns), default=0)
                    rep.check(smallest >= 0x4000, f"{e}: LOAD segments aligned to {smallest:#x} (want 0x4000+ for 16 KB pages)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk")
    ap.add_argument("--no-native", action="store_true", help="expect a -Pglassos.airplay.native=false build (no lib/)")
    ap.add_argument("--max-bytes", type=int, help="fail if the APK is larger (the release budget is 7000000)")
    ap.add_argument("--ndk", help="NDK directory containing toolchains/llvm/prebuilt")
    args = ap.parse_args()

    apk = Path(args.apk)
    rep = Report()
    size = apk.stat().st_size
    rep.info(f"{apk}: {size:,} bytes")
    with zipfile.ZipFile(apk) as zf:
        if args.no_native:
            libs = [n for n in zf.namelist() if n.startswith("lib/")]
            rep.check(not libs, "no lib/ entries (built without natives)" + (f"; found {libs[:5]}" if libs else ""))
        else:
            check_native(apk, zf, find_llvm_bin(args.ndk), rep)
        has_notices = APK_NOTICES in zf.namelist()
        rep.check(has_notices, f"{APK_NOTICES} is in the APK")
        if has_notices and NOTICES.exists():
            rep.check(zf.read(APK_NOTICES) == NOTICES.read_bytes(),
                      f"{APK_NOTICES} is identical to the repository's THIRD_PARTY_NOTICES.md")
    if args.max_bytes is not None:
        rep.check(size <= args.max_bytes, f"APK size {size:,} <= {args.max_bytes:,} bytes")
    print("check_apk.py: " + ("OK" if not rep.failed else f"{rep.failed} check(s) failed"))
    return 1 if rep.failed else 0


if __name__ == "__main__":
    sys.exit(main())
