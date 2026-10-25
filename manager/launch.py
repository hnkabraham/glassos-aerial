#!/usr/bin/env python3
"""Start or reopen the local companion without leaving a Terminal window running."""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
import webbrowser
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data-dir", type=Path, required=True)
    p.add_argument("--bundle", type=Path)
    a = p.parse_args()
    a.data_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(a.data_dir, 0o700)

    def running():
        try:
            record = json.loads((a.data_dir / "server.json").read_text())
            base, token = record["url"].split("/#")
            request = urllib.request.Request(
                base + "/api/state", headers={"X-GlassOS-Token": token}
            )
            with urllib.request.urlopen(request, timeout=2) as response:
                if response.status == 200:
                    return record["url"]
        except Exception:
            pass
        return None

    url = running()
    if not url:
        cmd = [
            sys.executable,
            str(Path(__file__).with_name("server.py")),
            "--no-open",
            "--data-dir",
            str(a.data_dir),
        ]
        if a.bundle:
            cmd += ["--bundle", str(a.bundle)]
        log = (a.data_dir / "server.log").open("a")
        os.chmod(a.data_dir / "server.log", 0o600)
        process = subprocess.Popen(
            cmd,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=log,
            start_new_session=True,
        )
        log.close()
        for _ in range(50):
            url = running()
            if url:
                break
            if process.poll() is not None:
                break
            time.sleep(0.1)
    if not url:
        raise SystemExit(
            "Could not start GlassOS Manager. See " + str(a.data_dir / "server.log")
        )
    webbrowser.open(url)


if __name__ == "__main__":
    main()
