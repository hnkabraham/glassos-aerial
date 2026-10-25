#!/usr/bin/env python3
"""Exercise GlassOS leaving Home for Settings and returning. Writes private evidence to --output.
Does not reboot, clear logs/data, change Home routing, or modify privacy/package settings.
"""

import argparse
import json
import re
import shlex
import shutil
import subprocess
import time
from pathlib import Path

P = "io.github.abhishekkaisar.glassos.aerial"
ACT = P + "/io.github.abhishekkaisar.glassos.MainActivity"


def owned_player_blocks(dump, pids):
    blocks = re.split(r"(?m)^\s*Client\s*$", dump)[1:]
    owned = []
    for block in blocks:
        pid = re.search(r"\bpid(?:\(|:\s*)(\d+)", block)
        if pid and pid.group(1) in pids:
            owned.append(block.split("registered handlers:")[0])
    return owned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cycles", type=int, default=10)
    parser.add_argument("--sleep-wake", action="store_true")
    parser.add_argument(
        "--adb",
        default=shutil.which("adb")
        or str(Path.home() / "Library/Android/sdk/platform-tools/adb"),
    )
    a = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", a.serial) or not 1 <= a.cycles <= 100:
        parser.error("Use an explicit ADB serial and 1–100 cycles.")
    a.output.mkdir(parents=True, exist_ok=True)

    def run(*args):
        if args and args[0] == "shell":
            args = ("shell", shlex.join(args[1:]))
        r = subprocess.run(
            [a.adb, "-s", a.serial, *args], capture_output=True, text=True, timeout=45
        )
        if r.returncode:
            raise RuntimeError((r.stdout + r.stderr).strip())
        return r.stdout

    def save(name, text):
        (a.output / name).write_text(text)

    def foreground():
        return "\n".join(
            x.strip()
            for x in run("shell", "dumpsys", "activity", "activities").splitlines()
            if "mResumedActivity" in x
        )

    def memory():
        s = run("shell", "dumpsys", "meminfo", P)
        m = re.search(r"TOTAL PSS:\s*(\d+)", s) or re.search(
            r"^\s*TOTAL\s+(\d+)", s, re.M
        )
        return {"pss_kb": int(m.group(1)) if m else None, "raw": s}

    start = run("shell", "date", "+%m-%d %H:%M:%S.000").strip()

    def logs():
        return run(
            "shell",
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "-T",
            start,
            "GlassAerial:I",
            "AndroidRuntime:E",
            "*:S",
        )

    def latest_events():
        return [x for x in logs().splitlines() if "GlassAerial" in x]

    def player():
        dump = run("shell", "dumpsys", "media.player")
        pids = set(run("shell", "pidof", P).split())
        owned = owned_player_blocks(dump, pids)
        return dump, owned

    def await_play(previous=None):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            dump, owned = player()
            if owned and any("state(5)" in b for b in owned):
                return dump
            time.sleep(0.5)
        raise AssertionError(
            "No active aerial player after returning Home. Open the aerial source and close any panels first."
        )

    run("shell", "am", "start", "-n", ACT)
    for _ in range(3):
        run("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    before = memory()
    save("memory-before.txt", before["raw"])
    save("gfx-before.txt", run("shell", "dumpsys", "gfxinfo", P))
    rows = []
    try:
        for n in range(a.cycles):
            plays = 0
            run("shell", "am", "start", "-a", "android.settings.SETTINGS")
            time.sleep(1)
            away = foreground()
            assert P not in away, "Settings did not take the foreground"
            away_dump, owned = player()
            assert not owned, "GlassOS still owns a player after leaving Home"
            save(f"cycle-{n + 1:02}-away-player.txt", away_dump)
            run("shell", "am", "start", "-n", ACT)
            home_dump = await_play(plays)
            time.sleep(1)
            save(f"cycle-{n + 1:02}-home-player.txt", home_dump)
            assert P in foreground(), "GlassOS did not return to the foreground"
            mem = memory()
            rows.append(
                {
                    "cycle": n + 1,
                    "away": away,
                    "pss_kb": mem["pss_kb"],
                    "released": True,
                    "resumed": True,
                }
            )
            print(
                f"Cycle {n + 1}/{a.cycles}: released and resumed; PSS {mem['pss_kb']} KB",
                flush=True,
            )
        if a.sleep_wake:
            plays = 0
            run("shell", "input", "keyevent", "KEYCODE_SLEEP")
            time.sleep(2)
            sleep_dump, owned = player()
            assert not owned, "Sleep did not release the decoder"
            save("sleep-player.txt", sleep_dump)
            run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            run("shell", "am", "start", "-n", ACT)
            await_play(plays)
            print("Sleep/wake: released and resumed", flush=True)
        after = memory()
        save("memory-after.txt", after["raw"])
        save("gfx-after.txt", run("shell", "dumpsys", "gfxinfo", P))
        log = logs()
        save("lifecycle.log", log)
        assert "FATAL EXCEPTION" not in log, (
            "Runtime crash observed; inspect lifecycle.log"
        )
        result = {
            "cycles": rows,
            "sleep_wake": a.sleep_wake,
            "before_pss_kb": before["pss_kb"],
            "after_pss_kb": after["pss_kb"],
            "crash_observed": False,
            "note": "PSS is sampled, not a proof of absence of leaks. Decoder ownership and running state are checked in media.player against the launcher PID; dumps are preserved.",
        }
        save("result.json", json.dumps(result, indent=2) + "\n")
    finally:
        try:
            save("lifecycle.log", logs())
        finally:
            run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            run("shell", "am", "start", "-n", ACT)


if __name__ == "__main__":
    main()
