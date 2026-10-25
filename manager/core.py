"""Local GlassOS companion. Explicit devices, checked files, and no shell=True."""

from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import threading
import time
import uuid

import catalog
import profiles

PACKAGE = "io.github.abhishekkaisar.glassos.aerial"
ACTIVITY = PACKAGE + "/io.github.abhishekkaisar.glassos.MainActivity"
REMOTE = "/sdcard/Movies/GlassAerial"
DOCUMENTS = "/sdcard/Documents/GlassOS-Aerial"
CERT = "235c4b2e2dc61d035bbcde530ab3ac94c8a2f653ffa9607ea9e87d379d8d0441"
COLLECTIONS = {
    "all": "All scenes",
    "coast": "Coastlines",
    "mountains": "Mountains",
    "forest": "Forests & gardens",
    "desert": "Deserts",
    "underwater": "Underwater",
    "space": "Space",
    "city": "Cities",
    "other": "Other",
}
PRIVACY = [
    "settingsApp:deviceUsageData:value",
    "settingsApp:appUsageData:value",
    "advertisingIdApp:interestBasedAds:value",
    "usage_metrics_marketing_enabled",
    "USAGE_METRICS_UPLOAD_ENABLED",
]
DISABLED = [
    "com.amazon.hybridadidservice",
    "com.amazon.wirelessmetrics.service",
    "com.amazon.neo.minerva",
    "com.amazon.neo.minerva.logcat",
]
INSTAGRAM = ["com.instagram.airwave", "com.instagram.android", "com.instagram.lite"]
RESERVE = 400 * 1024**2


class Error(Exception):
    pass


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for block in iter(lambda: f.read(1024**2), b""):
            h.update(block)
    return h.hexdigest()


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name("." + path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with tmp.open("w") as f:
            json.dump(value, f, indent=2)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)


def address(value):
    if not isinstance(value, str) or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9.-]*(?::[0-9]{1,5})?", value
    ):
        raise Error(
            "Enter a device hostname or IPv4 address, optionally followed by :port."
        )
    result = value if ":" in value else value + ":5555"
    if not 1 <= int(result.rsplit(":", 1)[1]) <= 65535:
        raise Error("Port must be between 1 and 65535.")
    return result


def name(value):
    if not isinstance(value, str) or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9_.-]{0,180}", value
    ):
        raise Error("Invalid managed filename.")
    return value


def schedule(value):
    if not isinstance(value, list) or not 1 <= len(value) <= 24:
        raise Error("Choose 1–24 schedule entries.")
    out = []
    for row in value:
        if (
            not isinstance(row, dict)
            or type(row.get("start")) is not int
            or not 0 <= row["start"] <= 1439
            or row.get("collection") not in COLLECTIONS
        ):
            raise Error("Each schedule entry needs a time and collection.")
        out.append({"start": row["start"], "collection": row["collection"]})
    if len({r["start"] for r in out}) != len(out):
        raise Error("Schedule times must be different.")
    return sorted(out, key=lambda r: r["start"])


def tool(which):
    found = shutil.which(which)
    if found:
        return found
    candidates = [Path("/opt/homebrew/bin") / which, Path("/usr/local/bin") / which]
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    if which == "adb":
        candidates.insert(0, sdk / "platform-tools/adb")
    if which in ("apksigner", "aapt"):
        candidates = (
            list(sorted((sdk / "build-tools").glob("*/" + which), reverse=True))
            + candidates
        )
    for p in candidates:
        if p.is_file():
            return str(p)
    raise Error(
        f"{which} is missing. Install Android Platform Tools / SDK build tools or FFmpeg as appropriate."
    )


def run(args, timeout=60):
    env = os.environ.copy()
    java = Path("/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home")
    if java.is_dir():
        env["JAVA_HOME"] = str(java)
    r = subprocess.run(
        [str(a) for a in args], capture_output=True, text=True, timeout=timeout, env=env
    )
    out = (r.stdout + r.stderr).strip()
    if r.returncode or re.search(
        r"(?im)^\s*(error:|failure\b|exception occurred|security exception)", out
    ):
        raise Error(out[-4000:] or f"{Path(args[0]).name} failed ({r.returncode})")
    return out


class Device:
    def __init__(self, serial):
        self.serial = address(serial)

    def adb(self, *args, timeout=60):
        return run([tool("adb"), "-s", self.serial, *args], timeout)

    def shell(self, *args, timeout=60):
        return self.adb("shell", shlex.join([str(x) for x in args]), timeout=timeout)

    def connect(self):
        run([tool("adb"), "connect", self.serial], 20)
        if self.adb("get-state", timeout=10) != "device":
            raise Error("Accept the ADB authorization prompt on the TV.")

    def exists(self, path):
        return (
            self.shell(
                "sh", "-c", "if [ -f " + shlex.quote(path) + " ]; then echo yes; fi"
            )
            == "yes"
        )

    def hash(self, path):
        return self.shell("sha256sum", path).split()[0]

    def free(self):
        lines = self.shell("df", "-k", "/sdcard").splitlines()
        try:
            return int(lines[-1].split()[3]) * 1024
        except (ValueError, IndexError):
            raise Error("Could not read free storage; no files were changed.")

    def put(self, local, remote, expected=None):
        """Stage beside the destination; verify before atomic rename. An interrupted push never touches the old file."""
        expected = expected or digest(local)
        tmp = remote + ".glassos-part"
        try:
            self.adb("push", str(local), tmp, timeout=900)
            if self.hash(tmp) != expected:
                raise Error("Transfer checksum mismatch; original file kept.")
            self.shell("mv", "-f", tmp, remote)
        finally:
            try:
                self.shell("rm", "-f", tmp)
            except Exception:
                pass  # retry overwrites only this manager's staging file

    def start(self, action=None):
        args = ["am", "start", "-n", ACTIVITY]
        if action:
            args += ["--es", "managerAction", action]
        return self.shell(*args)


class Manager:
    def __init__(self, data, bundle=None):
        self.data = Path(data).resolve()
        self.data.mkdir(parents=True, exist_ok=True)
        os.chmod(self.data, 0o700)
        self.bundle = Path(bundle).resolve() if bundle else None
        self.lock = threading.RLock()
        self.work_lock = threading.Lock()
        self.jobs = []
        p = self.data / "state.json"
        self.state = (
            json.loads(p.read_text())
            if p.exists()
            else {
                "devices": [],
                "scenes": [],
                "budget_mb": 2048,
                "schedule": [
                    {"start": 0, "collection": "space"},
                    {"start": 420, "collection": "coast"},
                    {"start": 720, "collection": "mountains"},
                    {"start": 1080, "collection": "underwater"},
                ],
            }
        )
        for folder in ["media", "uploads", "backups", "reports", "recovery", "icons"]:
            (self.data / folder).mkdir(exist_ok=True)
        # Incomplete uploads cannot be valid library entries; remove only our own staging files at startup.
        for f in (self.data / "uploads").glob("*.upload"):
            f.unlink()

    def save(self):
        atomic_json(self.data / "state.json", self.state)

    def snapshot(self):
        with self.lock:
            public = json.loads(json.dumps(self.state))
            for scene in public["scenes"]:
                scene.pop("path", None)
                scene.pop("poster_path", None)
            public.update(
                collections=COLLECTIONS,
                jobs=json.loads(json.dumps(self.jobs[-30:])),
                backups=self.backups(),
                bundle_available=bool(self.bundle),
                data_dir=str(self.data),
            )
            return public

    def job(self, title, work):
        if not self.work_lock.acquire(blocking=False):
            raise Error("Another operation is running. Wait for it to finish.")
        j = {
            "id": uuid.uuid4().hex,
            "title": title,
            "status": "running",
            "messages": [],
            "started": time.time(),
        }
        with self.lock:
            self.jobs.append(j)

        def log(s):
            with self.lock:
                j["messages"] = (j["messages"] + [str(s)])[-100:]

        def worker():
            try:
                result = work(log)
                with self.lock:
                    j.update(status="done", result=result)
            except Exception as e:
                with self.lock:
                    j.update(status="error", error=str(e))
            finally:
                with self.lock:
                    j["finished"] = time.time()
                self.work_lock.release()

        threading.Thread(target=worker, daemon=True).start()
        return {"job": j["id"]}

    def device(self, id):
        with self.lock:
            row = next((x for x in self.state["devices"] if x["id"] == id), None)
        if not row:
            raise Error("Choose a saved Fire Stick first.")
        d = Device(row["address"])
        d.connect()
        return d

    def add_device(self, label, addr):
        addr = address(addr)
        if not isinstance(label, str) or not 1 <= len(label.strip()) <= 60:
            raise Error("Give this device a name (1–60 characters).")
        with self.lock:
            if any(x["address"] == addr for x in self.state["devices"]):
                raise Error("That device is already saved.")
            self.state["devices"].append(
                {"id": uuid.uuid4().hex, "name": label.strip(), "address": addr}
            )
            self.save()

    def inspect(self, id, log):
        d = self.device(id)
        log("Reading device, storage, Home and privacy settings…")
        props = {
            key: d.shell("getprop", prop)
            for key, prop in [
                ("model", "ro.product.model"),
                ("build", "ro.build.display.id"),
                ("sdk", "ro.build.version.sdk"),
            ]
        }
        for key, prop in [("manufacturer", "ro.product.manufacturer"), ("device", "ro.product.device"),
                          ("android", "ro.build.version.release"), ("abi", "ro.product.cpu.abilist")]:
            props[key] = d.shell("getprop", prop)
        memory = d.shell("cat", "/proc/meminfo")
        mem = re.search(r"MemTotal:\s+(\d+) kB", memory)
        props["memory_bytes"] = int(mem.group(1)) * 1024 if mem else None
        props["free_bytes"] = d.free()
        props["home"] = d.shell(
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.HOME",
        )
        props["privacy"] = {k: d.shell("settings", "get", "secure", k) for k in PRIVACY}
        disabled = set(
            d.shell("pm", "list", "packages", "-d", "--user", "0").splitlines()
        )
        props["disabled"] = {k: "package:" + k in disabled for k in DISABLED}
        pkg = d.shell("dumpsys", "package", PACKAGE)
        match = re.search(r"versionName=([^\s]+)", pkg)
        props["version"] = match.group(1) if match else "Not installed"
        props["instagram"] = {}
        installed = set(d.shell("pm", "list", "packages", "--user", "0").splitlines())
        manifest = (
            self.bundle / "Instagram Install Block/manifest.json"
            if self.bundle
            else None
        )
        known = (
            json.loads(manifest.read_text()) if manifest and manifest.exists() else None
        )
        # Match locally saved blocker hashes; absence/presence alone never proves an install block.
        for p in INSTAGRAM:
            path = (
                d.shell("pm", "path", p).splitlines()
                if "package:" + p in installed
                else []
            )
            value = "Not installed" if not path else "Installed — identity unverified"
            if known and len(path) == 1 and path[0].startswith("package:"):
                sha = d.hash(path[0][8:])
                records = known.get("blocks", [])
                if isinstance(records, dict):
                    records = [dict(v, package=k) for k, v in records.items()]
                if any(
                    r.get("package") == p and r.get("sha256") == sha for r in records
                ):
                    value = "Blocker verified"
            props["instagram"][p] = value
        props["checked_at"] = time.time()
        with self.lock:
            row = next(x for x in self.state["devices"] if x["id"] == id)
            previous = row.get("health")
            row["previous_health"] = previous
            row["health"] = props
            row["profile"] = profiles.describe(props)
            row["changes"] = profiles.compare(previous, props)
            row["baseline_changes"] = profiles.compare(row.get("baseline"), props)
            self.save()
        history = self.data / "reports" / id
        atomic_json(history / (str(time.time_ns()) + ".json"), props)
        for old in sorted(history.glob("*.json"))[:-30]:
            old.unlink()
        atomic_json(self.data / "reports" / f"{id}-health.json", props)
        log(
            "Checks saved. Package disables are partial telemetry reduction; native services may still run."
        )
        return props

    def import_bundle(self, log):
        if not self.bundle:
            raise Error(
                "Start with --bundle pointing at your saved Fire TV GlassOS folder."
            )
        work = self.bundle / "work-session-2026-10-24/aerial-work"
        doc = json.loads((work / "library.json").read_text())
        files = {x["name"]: x for x in doc["files"]}
        with self.lock:
            existing = {x["name"] for x in self.state["scenes"]}
        added = []
        # All source files are checked before state changes; original personal media stays in its folder.
        for filename, item in files.items():
            name(filename)
            if not filename.endswith(".mp4") or filename in existing:
                continue
            p = work / "clips" / filename
            poster = p.with_suffix(".jpg")
            if digest(p) != item["sha256"]:
                raise Error("Saved media checksum mismatch: " + filename)
            if poster.exists() and digest(poster) != files[poster.name]["sha256"]:
                raise Error("Poster checksum mismatch.")
            title = re.sub(r"^\d+-", "", p.stem).replace("-", " ").title()
            category = next(
                (
                    v
                    for terms, v in [
                        (["coral", "kelp"], "underwater"),
                        (["aurora", "space"], "space"),
                        (["redwood", "tea", "wildflower"], "forest"),
                        (["liwa", "monument", "canyon"], "desert"),
                        (
                            [
                                "patagonia",
                                "iceland",
                                "greenland",
                                "yosemite",
                                "himalaya",
                                "scotland",
                            ],
                            "mountains",
                        ),
                        (["hawaii", "tahiti", "coast", "beach"], "coast"),
                    ]
                    if any(t in filename for t in terms)
                ),
                "other",
            )
            added.append(
                {
                    "id": uuid.uuid4().hex,
                    "name": filename,
                    "title": title,
                    "collection": category,
                    "path": str(p),
                    "poster_path": str(poster) if poster.exists() else None,
                    "sha256": item["sha256"],
                    "poster_sha256": digest(poster) if poster.exists() else None,
                    "bytes": p.stat().st_size
                    + (poster.stat().st_size if poster.exists() else 0),
                    "duration": 90,
                    "enabled": True,
                }
            )
            log("Verified " + title)
        with self.lock:
            self.state["scenes"].extend(added)
            self.save()
        return {"added": len(added)}

    def import_video(self, source, filename, title, collection, seconds, log):
        source = Path(source)
        try:
            if collection not in COLLECTIONS or collection == "all":
                raise Error("Choose a scene collection.")
            seconds = int(seconds)
            if seconds not in (30, 60, 90, 120, 180):
                raise Error("Clip duration must be 30, 60, 90, 120 or 180 seconds.")
            if not isinstance(title, str) or len(title) > 120:
                raise Error("Scene title is too long.")
            probe = json.loads(
                run(
                    [
                        tool("ffprobe"),
                        "-v",
                        "error",
                        "-protocol_whitelist",
                        "file,pipe",
                        "-show_streams",
                        "-show_format",
                        "-of",
                        "json",
                        source,
                    ]
                )
            )
            video = next(
                (s for s in probe["streams"] if s["codec_type"] == "video"), None
            )
            if not video:
                raise Error("This file has no video stream.")
            if video.get("color_transfer") in ("smpte2084", "arib-std-b67"):
                raise Error(
                    "Use an SDR video; HDR needs tone mapping before importing."
                )
            stem = "scene-" + uuid.uuid4().hex[:12]
            out = self.data / "media" / (stem + ".mp4")
            poster = out.with_suffix(".jpg")
            log("Compressing a silent 1080p H.264 clip…")
            try:
                run(
                    [
                        tool("ffmpeg"),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-nostdin",
                        "-protocol_whitelist",
                        "file,pipe",
                        "-i",
                        source,
                        "-t",
                        str(seconds),
                        "-map",
                        "0:v:0",
                        "-an",
                        "-sn",
                        "-dn",
                        "-vf",
                        "scale=w=min(1920\\,iw):h=min(1080\\,ih):force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1,fps=30",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "medium",
                        "-crf",
                        "23",
                        "-profile:v",
                        "high",
                        "-level",
                        "4.1",
                        "-pix_fmt",
                        "yuv420p",
                        "-movflags",
                        "+faststart",
                        out,
                    ],
                    3600,
                )
                log("Checking the complete encoded clip and making a thumbnail…")
                run(
                    [
                        tool("ffmpeg"),
                        "-v",
                        "error",
                        "-xerror",
                        "-nostdin",
                        "-i",
                        out,
                        "-f",
                        "null",
                        "-",
                    ],
                    600,
                )
                run(
                    [
                        tool("ffmpeg"),
                        "-v",
                        "error",
                        "-nostdin",
                        "-i",
                        out,
                        "-frames:v",
                        "1",
                        "-vf",
                        "scale=640:-2",
                        "-update",
                        "1",
                        poster,
                    ],
                    60,
                )
                info = json.loads(
                    run(
                        [
                            tool("ffprobe"),
                            "-v",
                            "error",
                            "-show_format",
                            "-of",
                            "json",
                            out,
                        ]
                    )
                )
                row = {
                    "id": uuid.uuid4().hex,
                    "name": out.name,
                    "title": title.strip() or Path(filename).stem[:120],
                    "collection": collection,
                    "path": str(out),
                    "poster_path": str(poster),
                    "sha256": digest(out),
                    "poster_sha256": digest(poster),
                    "bytes": out.stat().st_size + poster.stat().st_size,
                    "duration": float(info["format"]["duration"]),
                    "enabled": True,
                }
                with self.lock:
                    total = (
                        sum(x["bytes"] for x in self.state["scenes"] if x["enabled"])
                        + row["bytes"]
                    )
                    if total > self.state["budget_mb"] * 1_000_000:
                        row["enabled"] = False
                    self.state["scenes"].append(row)
                    self.save()
                log(
                    "Imported"
                    + (
                        " (not selected: exceeds your sync budget)."
                        if not row["enabled"]
                        else "."
                    )
                )
                return {"scene": row["id"]}
            except Exception:
                out.unlink(missing_ok=True)
                poster.unlink(missing_ok=True)
                raise
        finally:
            source.unlink(missing_ok=True)

    def configure(self, payload):
        with self.lock:
            if "budget_mb" in payload:
                n = payload["budget_mb"]
                if type(n) is not int or not 128 <= n <= 16384:
                    raise Error("Storage budget must be 128–16384 MB.")
            periods = schedule(payload["schedule"]) if "schedule" in payload else None
            if "scene" in payload:
                edit = payload["scene"]
                row = next(
                    (x for x in self.state["scenes"] if x["id"] == edit.get("id")), None
                )
                if not row:
                    raise Error("Scene no longer exists.")
                title = edit.get("title", row["title"])
                cat = edit.get("collection", row["collection"])
                enabled = edit.get("enabled", row["enabled"])
                if (
                    not isinstance(title, str)
                    or not 1 <= len(title.strip()) <= 120
                    or cat not in COLLECTIONS
                    or cat == "all"
                    or type(enabled) is not bool
                ):
                    raise Error("Invalid scene settings.")
                row.update(title=title.strip(), collection=cat, enabled=enabled)
            if "budget_mb" in payload:
                self.state["budget_mb"] = payload["budget_mb"]
            if periods is not None:
                self.state["schedule"] = periods
            self.save()

    def sync(self, id, log):
        d = self.device(id)
        with self.lock:
            scenes = json.loads(
                json.dumps([x for x in self.state["scenes"] if x["enabled"]])
            )
            budget = self.state["budget_mb"]
            periods = self.state["schedule"][:]
        if not scenes:
            raise Error("Select at least one scene before syncing.")
        if sum(s["bytes"] for s in scenes) > budget * 1_000_000:
            raise Error(
                "Selected scenes exceed the storage budget. Deselect scenes or raise the budget."
            )
        files = []
        meta = {}
        for s in scenes:
            name(s["name"])
            p = Path(s["path"])
            if digest(p) != s["sha256"]:
                raise Error("Local video changed: " + s["title"])
            files.append((p, REMOTE + "/" + s["name"], s["sha256"]))
            if s.get("poster_path"):
                p = Path(s["poster_path"])
                if digest(p) != s["poster_sha256"]:
                    raise Error("Local poster changed: " + s["title"])
                files.append(
                    (
                        p,
                        REMOTE + "/" + Path(s["name"]).with_suffix(".jpg").name,
                        s["poster_sha256"],
                    )
                )
            meta[s["name"]] = {"title": s["title"], "collections": [s["collection"]]}
        if len(json.dumps(meta, ensure_ascii=False).encode("utf-8")) > 64 * 1024:
            raise Error(
                "Scene names exceed the TV metadata limit. Shorten names or select fewer scenes."
            )
        pending = []
        for p, remote, sha in files:
            if not d.exists(remote) or d.hash(remote) != sha:
                pending.append((p, remote, sha))
        need = sum(p.stat().st_size for p, _, _ in pending) + RESERVE
        if d.free() < need:
            raise Error(
                "Not enough free space for a safe staged transfer plus 400 MB reserve. Existing files were kept."
            )
        d.shell("mkdir", "-p", REMOTE)
        for i, (p, remote, sha) in enumerate(pending):
            log(f"Transferring {i + 1}/{len(pending)}: {p.name}")
            d.put(p, remote, sha)
        # Commit metadata after every selected file is verified. Never prune unmanaged or deselected videos implicitly.
        with tempfile.TemporaryDirectory(dir=self.data) as tmp:
            for filename, doc in [
                ("scenes.json", meta),
                ("scenery.json", {"version": 1, "schedule": periods}),
            ]:
                p = Path(tmp) / filename
                atomic_json(p, doc)
                d.put(p, REMOTE + "/" + filename)
        log(
            f"{len(files)} files verified; {len(files) - len(pending)} were already current. Existing extra videos were kept."
        )
        d.start("refresh")
        return {
            "verified": len(files),
            "transferred": len(pending),
            "note": "Choose Scenery > Collection > Scheduled on the TV to use the schedule.",
        }

    def backups(self):
        return [
            {"id": p.name, "name": p.name, "bytes": p.stat().st_size}
            for p in sorted((self.data / "backups").glob("*.json"), reverse=True)
        ]

    def backup(self, id, log, fresh=True):
        d = self.device(id)
        before = (
            set(
                d.shell(
                    "sh", "-c", "ls " + DOCUMENTS + "/*.json 2>/dev/null || true"
                ).splitlines()
            )
            if fresh
            else set()
        )
        if fresh:
            log(
                "Asking GlassOS to export current settings. Accept storage access on the TV if requested."
            )
            d.start("backup")
        # Export uses a new filename. Baseline includes existing names so stale backups never masquerade as fresh.
        return self._collect_backup(d, id, log, fresh, before)

    def _collect_backup(self, d, id, log, fresh, before=None):
        # The action was issued immediately before this call: compare TV file modification time with a small skew allowance.
        deadline = time.monotonic() + 35
        latest = None
        for _ in range(36 if fresh else 1):
            out = d.shell(
                "sh", "-c", "ls -t " + DOCUMENTS + "/*.json 2>/dev/null || true"
            )
            candidates = [
                x
                for x in out.splitlines()
                if x.startswith(DOCUMENTS + "/")
                and re.fullmatch(r"[A-Za-z0-9_.-]+\.json", x.rsplit("/", 1)[-1])
            ]
            if candidates:
                latest = candidates[0]
                if not fresh:
                    break
                device_now = int(d.shell("date", "+%s"))
                modified = int(d.shell("stat", "-c", "%Y", latest))
                if latest not in (before or set()) and 0 <= device_now - modified <= 40:
                    break
                latest = None
            if time.monotonic() > deadline:
                break
            time.sleep(1)
        if not latest:
            raise Error(
                "No fresh backup found. On the TV finish setup, allow storage, and choose Settings > Back up settings, then retry."
            )
        filename = (
            time.strftime("%Y%m%d-%H%M%S")
            + "-"
            + id[:8]
            + "-"
            + uuid.uuid4().hex[:6]
            + ".json"
        )
        target = self.data / "backups" / filename
        staging = target.with_suffix(".part")
        d.adb("pull", latest, str(staging))
        try:
            if staging.stat().st_size > 256 * 1024:
                raise Error("Backup exceeds the supported size.")
            doc = json.loads(staging.read_text())
            if doc.get("format") != "glassos-aerial-backup" or doc.get("version") != 1:
                raise Error("Not a supported GlassOS settings backup.")
        except Exception:
            staging.unlink(missing_ok=True)
            raise
        os.chmod(staging, 0o600)
        os.replace(staging, target)
        os.chmod(target, 0o600)
        log("Settings saved on this Mac: " + filename)
        return {"backup": filename, "fresh": fresh}

    def restore(self, id, backup_id, log):
        filename = name(backup_id)
        p = self.data / "backups" / filename
        if not p.is_file():
            raise Error("Choose an existing settings backup.")
        d = self.device(id)
        d.shell("mkdir", "-p", DOCUMENTS)
        d.put(p, DOCUMENTS + "/" + filename)
        d.start("restore")
        log("Backup copied. Choose it and confirm Restore on the TV.")
        return {"confirmation": "TV"}

    def update(self, id, log):
        if not self.bundle:
            raise Error("No verified build bundle is configured.")
        work = self.bundle / "work-session-2026-10-24/aerial-work"
        record = json.loads((work / "active-build.json").read_text())
        apk = work / name(record["apk_file"])
        if digest(apk) != record["apk_sha256"]:
            raise Error("APK checksum mismatch; update refused.")
        signed = run([tool("apksigner"), "verify", "--print-certs", apk])
        if CERT not in signed.lower():
            raise Error(
                "APK signing certificate is not our GlassOS Aerial certificate."
            )
        badging = run([tool("aapt"), "dump", "badging", apk])
        if f"name='{PACKAGE}'" not in badging:
            raise Error("APK package is not GlassOS Aerial.")
        d = self.device(id)
        log("Saving the installed APK and available exported settings before updating…")
        installed = set(d.shell("pm", "list", "packages", "--user", "0").splitlines())
        paths = (
            d.shell("pm", "path", PACKAGE).splitlines()
            if "package:" + PACKAGE in installed
            else []
        )
        if paths:
            if len(paths) != 1 or not paths[0].startswith("package:"):
                raise Error("Cannot safely archive this split APK installation.")
            recovery = (
                self.data / "recovery" / (time.strftime("%Y%m%d-%H%M%S") + "-" + id[:8])
            )
            recovery.mkdir()
            old = recovery / "previous.apk"
            d.adb("pull", paths[0][8:], str(old))
            old_cert = run([tool("apksigner"), "verify", "--print-certs", old])
            if CERT not in old_cert.lower():
                raise Error(
                    "Installed app uses a different signing key; no uninstall will be attempted."
                )
            info = d.shell("dumpsys", "package", PACKAGE)
            version_match = re.search(r"versionCode=(\d+)", info)
            code = int(version_match.group(1)) if version_match else 0
            if code > record["version_code"]:
                raise Error(
                    "Installed version is newer than this bundle; downgrade refused."
                )
            backup = (
                self.backup(id, log, True)
                if code >= 7
                else self._collect_backup(d, id, log, False)
            )
            atomic_json(
                recovery / "record.json",
                {
                    "previous_sha256": digest(old),
                    "settings": backup,
                    "target_sha256": record["apk_sha256"],
                },
            )
        if d.free() < apk.stat().st_size * 3 + RESERVE:
            raise Error("Too little free storage for a safe update.")
        result = d.adb("install", "-r", str(apk), timeout=240)
        if "Success" not in result:
            raise Error("Install did not report success; no uninstall was attempted.")
        installed_paths = d.shell("pm", "path", PACKAGE).splitlines()
        if (
            len(installed_paths) != 1
            or not installed_paths[0].startswith("package:")
            or d.hash(installed_paths[0][8:]) != record["apk_sha256"]
        ):
            raise Error(
                "Installed APK could not be verified. Recovery copies were kept; no uninstall was attempted."
            )
        d.start()
        log(
            "GlassOS updated and installed checksum verified; recovery files are saved on this Mac."
        )
        return self.inspect(id, log)

    def baseline(self, id, log):
        current = self.inspect(id, log)
        with self.lock:
            row = next(x for x in self.state["devices"] if x["id"] == id)
            row["baseline"] = current
            row["baseline_changes"] = []
            self.save()
        atomic_json(self.data / "reports" / f"{id}-baseline.json", current)
        log("Saved this device's current state as its comparison baseline.")
        return {"checked_at": current["checked_at"]}

    def load_catalog(self, url, log):
        url = url.strip() if isinstance(url, str) and url.strip() else catalog.COMMUNITY
        log("Reading the scenery catalog; videos are downloaded only when you import one…")
        entries = catalog.parse(catalog.fetch(url), url)
        with self.lock:
            self.state["catalog"] = {"url": url, "loaded_at": time.time(), "entries": entries}
            self.save()
        log(f"{len(entries)} SDR scenes available. Select a scene to download and compress.")
        return {"count": len(entries)}

    def import_catalog(self, id, seconds, collection, log):
        with self.lock:
            entry = next((dict(x) for x in self.state.get("catalog", {}).get("entries", []) if x["id"] == id), None)
            if any(x.get("catalog_id") == id for x in self.state["scenes"]):
                raise Error("This catalog scene is already in your library.")
        if not entry:
            raise Error("Refresh the catalog and choose a scene.")
        if str(seconds) not in ("30", "60", "90", "120", "180") or collection not in COLLECTIONS or collection == "all":
            raise Error("Choose a clip length and a scene collection.")
        source = self.data / "uploads" / (uuid.uuid4().hex + ".upload")
        try:
            log(f"Downloading {entry['title']} ({entry['quality']})…")
            catalog.fetch(entry["url"], source, 4_000_000_000, log)
            result = self.import_video(source, "catalog-video", entry["title"], collection, seconds, log)
            with self.lock:
                row = next(x for x in self.state["scenes"] if x["id"] == result["scene"])
                row.update(catalog_id=id, source_url=entry["url"], source_feed=entry["feed"])
                self.save()
            return result
        finally:
            source.unlink(missing_ok=True)

    def list_apps(self, id, log):
        d = self.device(id)
        packages = set()
        for category in ("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER"):
            output = d.shell("cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN", "-c", category)
            packages.update(re.findall(r"(?m)^\s*([A-Za-z][A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+\s*$", output))
        packages.discard(PACKAGE)
        packages.difference_update(INSTAGRAM)
        with self.lock:
            row = next(x for x in self.state["devices"] if x["id"] == id)
            row["apps"] = sorted(packages)
            self.save()
        log(f"Found {len(packages)} launchable apps.")
        return {"apps": sorted(packages)}

    def import_icon(self, source, package, log):
        source = Path(source)
        try:
            if not isinstance(package, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package) or len(package) > 180:
                raise Error("Choose an app package for this image.")
            probe = json.loads(run([tool("ffprobe"), "-v", "error", "-protocol_whitelist", "file,pipe", "-show_streams", "-of", "json", source]))
            streams = probe.get("streams", [])
            image = next((s for s in streams if s.get("codec_type") == "video"), {})
            if image.get("codec_name") not in ("png", "mjpeg", "webp") or not 0 < image.get("width", 0) * image.get("height", 0) <= 40_000_000:
                raise Error("Choose a PNG, JPEG or WebP image up to 40 megapixels.")
            target = self.data / "icons" / (package + ".png")
            stage = target.with_name(uuid.uuid4().hex + ".png")
            try:
                run([tool("ffmpeg"), "-v", "error", "-nostdin", "-protocol_whitelist", "file,pipe", "-i", source,
                     "-frames:v", "1", "-vf", "scale=400:225:force_original_aspect_ratio=decrease,pad=400:225:(ow-iw)/2:(oh-ih)/2:color=0x161c28,setsar=1",
                     "-update", "1", stage])
                os.replace(stage, target)
            finally:
                stage.unlink(missing_ok=True)
            with self.lock:
                icons = self.state.setdefault("icons", {})
                icons[package] = {"sha256": digest(target), "bytes": target.stat().st_size}
                self.save()
            log("Custom app art saved. Sync app art to send it to the selected TV.")
            return {"package": package}
        finally:
            source.unlink(missing_ok=True)

    def sync_icons(self, id, log):
        with self.lock:
            icons = dict(self.state.get("icons", {}))
        d = self.device(id)
        if not icons:
            raise Error("Import an app image first.")
        files = [(self.data / "icons" / (name(pkg) + ".png"), pkg, row) for pkg, row in icons.items()]
        for local, pkg, row in files:
            if digest(local) != row["sha256"]:
                raise Error("An app image changed; import it again before syncing.")
        if d.free() < RESERVE + sum(r["bytes"] for r in icons.values()):
            raise Error("Keep at least 400 MiB free on the TV.")
        remote = "/sdcard/Pictures/GlassOS/icons"
        d.shell("mkdir", "-p", remote)
        for local, pkg, row in files:
            d.put(local, remote + "/" + pkg + ".png", row["sha256"])
            log("Synced art for " + pkg)
        d.start("refresh")
        return {"count": len(files)}

    def reset_icon(self, id, package, log):
        # Only the selected, manager-owned artwork is removed; original app resources stay intact.
        if not isinstance(package, str) or package not in self.state.get("icons", {}):
            raise Error("Choose one of your saved custom app images.")
        d = self.device(id)
        d.shell("rm", "-f", "/sdcard/Pictures/GlassOS/icons/" + name(package) + ".png")
        d.start("refresh")
        with self.lock:
            self.state["icons"].pop(package)
            self.save()
        (self.data / "icons" / (package + ".png")).unlink(missing_ok=True)
        log("Restored this app's original art on the selected TV and removed its saved replacement.")
