#!/usr/bin/env python3
"""Measure explicit launcher activities without changing Home or clearing app data.

Cold starts force-stop only the requested package. Results include the raw timings,
foreground check and PSS; am start -W is NOT a measurement of first usable video frame.
Use the same device and scene/settings for before/after comparisons. Output is private.
"""

import argparse
import json
from pathlib import Path
import re
import shlex
import shutil
import statistics
import subprocess
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--component", required=True)
    p.add_argument("--label", required=True)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--samples", type=int, default=5)
    p.add_argument("--settle", type=float, default=5)
    p.add_argument("--adb", default=shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    a = p.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", a.serial) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", a.component):
        p.error("Provide an explicit serial and package/activity component.")
    if not 2 <= a.samples <= 30 or not 2 <= a.settle <= 30:
        p.error("Use 2–30 samples and a 2–30 second settling period.")
    package = a.component.split("/")[0]
    a.output.mkdir(parents=True, exist_ok=True)

    def shell(*args):
        r = subprocess.run([a.adb, "-s", a.serial, "shell", shlex.join(str(x) for x in args)], capture_output=True, text=True, timeout=45)
        if r.returncode or re.search(r"(?im)^(Error:|Exception occurred|Failure)", r.stdout + r.stderr):
            raise RuntimeError((r.stdout + r.stderr).strip())
        return r.stdout.strip()

    def home():
        return shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")

    def start():
        raw = shell("am", "start", "-W", "-n", a.component)
        if "Status: ok" not in raw:
            raise RuntimeError("Activity did not start: " + raw)
        return {"raw": raw, **{k: int(v) for k, v in re.findall(r"(?m)^(ThisTime|TotalTime|WaitTime):\s*(\d+)", raw)}}

    power = shell("dumpsys", "power")
    if "mWakefulness=Asleep" in power or "mWakefulness=Dozing" in power or "Display Power: state=OFF" in power:
        raise RuntimeError("Wake the selected TV before measuring launch performance.")
    before = home()
    result = {"label": a.label, "component": a.component, "model": shell("getprop", "ro.product.model"),
              "build": shell("getprop", "ro.build.display.id"), "sdk": shell("getprop", "ro.build.version.sdk"),
              "settle_seconds": a.settle, "home_before": before, "samples": [],
              "limits": "Activity launch timings do not measure first usable frame. PSS includes shared pages proportionally; video decoder buffers may be attributed outside the app. Compare equivalent settings; default configurations across apps are not equivalent workloads."}
    version = shell("dumpsys", "package", package)
    match = re.search(r"versionName=([^\s]+)", version)
    result["version"] = match.group(1) if match else "unknown"
    try:
        for i in range(a.samples):
            shell("am", "force-stop", package)
            cold = start()
            time.sleep(a.settle)
            foreground = "\n".join(x.strip() for x in shell("dumpsys", "activity", "activities").splitlines() if "mResumedActivity" in x)
            if package not in foreground:
                raise RuntimeError("Launcher is not in the foreground; complete its setup first.")
            memory = shell("dumpsys", "meminfo", package)
            (a.output / f"memory-{i+1}.txt").write_text(memory)
            mem = re.search(r"TOTAL PSS:\s*(\d+)", memory) or re.search(r"(?m)^\s*TOTAL\s+(\d+)", memory)
            shell("am", "start", "-a", "android.settings.SETTINGS")
            time.sleep(1)
            warm = start()
            time.sleep(a.settle)
            result["samples"].append({"cold": cold, "return": warm, "pss_kb": int(mem.group(1)) if mem else None, "foreground": foreground})
        result["medians"] = {"cold_total_ms": statistics.median(r["cold"]["TotalTime"] for r in result["samples"]),
                             "return_wait_ms": statistics.median(r["return"]["WaitTime"] for r in result["samples"]),
                             "pss_kb": statistics.median(r["pss_kb"] for r in result["samples"] if r["pss_kb"] is not None)}
    finally:
        result["home_after"] = home()
        result["home_unchanged"] = result["home_after"] == before
        (a.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"label": a.label, "version": result["version"], "medians": result["medians"], "home_unchanged": result["home_unchanged"]}, indent=2))


if __name__ == "__main__":
    main()
