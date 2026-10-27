"""Local GlassOS companion. Explicit devices, checked files, and no shell=True."""

from __future__ import annotations
import hashlib
import json
import math
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
import sun

PACKAGE = "io.github.abhishekkaisar.glassos.aerial"
ACTIVITY = PACKAGE + "/io.github.abhishekkaisar.glassos.MainActivity"
# GlassOS's ADB-only controls (ManagerProvider.kt, GlassOS 1.6.0 and later), reached with `content call`.
PROVIDER = "content://" + PACKAGE + ".manager"
UPDATE_GLASSOS = "Update GlassOS to 1.6.0 or later on this TV."
NO_ANSWER = "GlassOS on this TV did not answer. Try again; if it keeps failing, update GlassOS to 1.6.0 or later."
# A call that ran out of time: a slow or busy TV, not an outdated GlassOS.
TV_SLOW = "The TV took too long to answer; it may be busy. Wait a moment, then try again."
# GlassOS's screensaver (AerialDream.kt, GlassOS 1.6.0 and later), as the TV's screensaver setting names it.
DREAM = PACKAGE + "/io.github.abhishekkaisar.glassos.AerialDream"
LAUNCHER_MANAGER = "com.wolf.tn"
# The TV settings a screensaver depends on: (namespace, key, kind). The first three, and the two the user may opt into
# (Amazon's Ambient Experience, Stay awake), are the only ones ever written; the timeouts are read for the report.
SCREENSAVER = [
    ("secure", "screensaver_components", "components"),
    ("secure", "screensaver_enabled", "int"),
    ("secure", "screensaver_activate_on_sleep", "int"),
    ("secure", "screensaver_activate_on_dock", "int"),
    ("secure", "screensaver_default_component", "components"),
    ("secure", "amazon_ambient_enabled", "int"),
    ("secure", "sleep_timeout", "int"),
    ("system", "screen_off_timeout", "int"),
    ("global", "stay_on_while_plugged_in", "int"),
]
COMPONENT = r"[A-Za-z0-9._]+/[A-Za-z0-9._$]+"
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


def lock_code(value):
    """An app lock code: 4 remote buttons as the TV stores them (LockCode.LETTERS in AppLock.kt).
    U D L R are the arrows, W Rewind, P Play/Pause, F Fast forward. The message never repeats the input."""
    code = value.strip().upper() if isinstance(value, str) else ""
    if not re.fullmatch(r"[UDLRWPF]{4}", code):
        raise Error("A lock code is 4 remote buttons: ↑ ↓ ← → ⏪ ⏯ ⏩.")
    return code


LABEL_CHARS = set("0123456789 .,'-")


def place_label(text):
    """A place name with only letters, digits, spaces and . , ' - (others dropped) and its spaces collapsed."""
    return " ".join("".join(c for c in text if c.isalpha() or c in LABEL_CHARS).split())


def sun_location(value):
    """The place sun times are worked out for, as the TV keeps it: {lat, lon, label} with the coordinates rounded to
    0.1 degree (about 11 km), or None to go back to the TV's time-zone estimate. The name keeps letters, digits,
    spaces and . , ' - (others are dropped), up to 40 characters."""
    if value is None:
        return None
    if not isinstance(value, dict):
        raise Error("Enter a latitude and a longitude.")
    lat, lon, label = value.get("lat"), value.get("lon"), value.get("label", "")
    for v, limit in ((lat, 90), (lon, 180)):
        if type(v) not in (int, float) or not math.isfinite(v) or not -limit <= v <= limit:
            raise Error("Enter a latitude from −90 to 90 and a longitude from −180 to 180.")
    if label is None:
        label = ""
    if not isinstance(label, str):
        raise Error("The place name must be text.")
    label = place_label(label)
    if len(label) > 40:
        raise Error("Keep the place name to 40 characters.")
    return {"lat": sun.tenth(lat), "lon": sun.tenth(lon), "label": label}


def sun_arg(value):
    """The --arg of `sun-location`: "lat,lon,label", or None (no --arg: `content` cannot send an empty one) to clear."""
    if value is None:
        return None
    m = re.fullmatch(r"(-?\d{1,3}(?:\.\d)?),(-?\d{1,3}(?:\.\d)?),(.*)", value, re.S) if isinstance(value, str) else None
    if not m:
        raise Error("Enter a latitude from −90 to 90 and a longitude from −180 to 180.")
    place = sun_location({"lat": float(m.group(1)), "lon": float(m.group(2)), "label": m.group(3)})
    if place["label"] != m.group(3):
        raise Error("The place name must be text.")
    return f"{place['lat']:.1f},{place['lon']:.1f},{place['label']}"


# Where this Mac's public internet address appears to be, asked only when the user chooses the lookup. The reply
# carries the address itself; only the rounded place leaves ip_place().
IP_LOOKUP = "https://ipinfo.io/json"
IP_LOOKUP_LIMIT = 16 * 1024
IP_LOOKUP_FAILED = "Couldn’t look up a place from this Mac’s internet address. Type the place instead."


def ip_place(data):
    """The place in an ipinfo.io reply, checked and rounded as sun_location() does: {lat, lon, label}, the name from
    the city and region (else country), shortened to the city, then dropped, if it would not fit in 40 characters."""
    if len(data) > IP_LOOKUP_LIMIT:
        raise ValueError("Reply too large.")
    doc = json.loads(data)
    loc = doc.get("loc") if isinstance(doc, dict) else None
    number = r"(-?[0-9]{1,3}(?:\.[0-9]{1,10})?)"  # ASCII digits only: \d would also take other scripts' digits
    m = re.fullmatch(number + "," + number, loc) if isinstance(loc, str) else None
    if not m:
        raise ValueError("No coordinates in the reply.")
    city, region, country = (
        place_label(v) if isinstance(v, str) else "" for v in (doc.get("city"), doc.get("region"), doc.get("country"))
    )
    area = region if region and region != city else country
    names = [", ".join(x for x in (city, area) if x), city, ""]
    label = next(x for x in names if len(x) <= 40)
    return sun_location({"lat": float(m.group(1)), "lon": float(m.group(2)), "label": label})


# The provider methods this manager may call, each with the validator of its --arg (None: it takes none). A
# validator that returns None sends no --arg.
GLASS_METHODS = {
    "status": None,
    "lock-set-code": lock_code,
    "lock-off": None,
    "sun-location": sun_arg,
    # Read only: Screen Mirroring's state. This manager never starts, stops or changes it.
    "airplay-status": None,
}
GLASS_REPLY = re.compile(r"^Result: Bundle\[\{glass=(\{.*\})\}\]\s*$", re.M)
GLASS_ERRORS = {
    "denied": "The TV refused this request: only its ADB shell may use GlassOS's controls.",
    "bad-code": "The TV refused that lock code; nothing changed.",
    "not-saved": "GlassOS could not save the change on the TV; nothing changed.",
    "unknown-method": "Update GlassOS on this TV to use this control.",
    "unsupported": "Update GlassOS on this TV to use this control.",
    "bad-location": "The TV refused that place; nothing changed.",
}


def glass_reply(out):
    """The JSON object in `content call` output. `content` exits 0 even when it fails, so only GlassOS's own
    one-key Bundle counts as an answer. Errors are fixed sentences: adb's output is never shown."""
    found = GLASS_REPLY.findall(out)
    # Looked for outside GlassOS's answer only: a place name the TV echoes back may contain these words.
    rest = GLASS_REPLY.sub("", out)
    if "Could not find provider" in rest or "Unknown authority" in rest:
        raise Error(UPDATE_GLASSOS)
    if "SecurityException" in rest:
        raise Error("This TV does not let ADB reach GlassOS's controls.")
    if "Error while accessing provider" in rest or len(found) != 1:
        raise Error(NO_ANSWER)
    try:
        reply = json.loads(found[0])
    except ValueError:
        raise Error(NO_ANSWER) from None
    if not isinstance(reply, dict) or type(reply.get("ok")) is not bool:
        raise Error(NO_ANSWER)
    if not reply["ok"]:
        code = reply.get("error")
        raise Error(
            GLASS_ERRORS.get(code, "GlassOS could not do that on this TV.")
            if isinstance(code, str)
            else NO_ANSWER
        )
    return reply


def mirroring_line(m):
    """What a checked Screen Mirroring record says, as the TV's Settings say it (Off whatever the receiver last did)."""
    if not m["on"]:
        return "off"
    if m["gave_up"] or m["error"] == "crashed":
        return "stopped unexpectedly"
    if m["state"] == "ERROR":
        return "could not start"
    if m["state"] != "RUNNING":
        return "starting"
    if m["pin_shown"]:
        return "showing a code" if m["screen"] else "code requested (hidden on the TV)"
    if m["mode"] in ("MIRROR", "VIDEO"):
        return "mirroring"
    if m["mode"] == "AUDIO":
        return "playing music"
    return f"ready as “{m['name']}”"


def setting_value(kind, value):
    """A screensaver setting as read from the TV, saved here or written back: "null" (not set), a whole number, or
    (components) package/class names separated by commas. Anything else is refused before a single setting changes."""
    if value == "null":
        return value
    if isinstance(value, str):
        if kind == "components" and re.fullmatch(f"{COMPONENT}(?:,{COMPONENT})*", value):
            return value
        if kind == "int" and re.fullmatch(r"-?\d{1,10}", value) and -(2**31) <= int(value) < 2**31:
            return value
    raise Error("A screensaver setting on this TV has a value this manager does not recognize; nothing was changed.")


def screensaver_options(value):
    """What "Use GlassOS scenery" may also turn off, when it is on: {"ambient": bool, "stayAwake": bool}."""
    if value is None:
        value = {}
    if (
        not isinstance(value, dict)
        or set(value) - {"ambient", "stayAwake"}
        or any(type(v) is not bool for v in value.values())
    ):
        raise Error("Choose which settings to change.")
    return {"ambient": value.get("ambient", False), "stayAwake": value.get("stayAwake", False)}


def schedule(value, place=None, zone=None, on=None):
    """The daily rhythm as scenery.json carries it (version 1). Rows are {start, collection} or {anchor: "sunrise" |
    "sunset", offset, collection}. Every written row keeps a distinct `start`: GlassOS before 1.6.0 reads only that,
    and the TV falls back to it on days without a sun time. A sun row's start is today's time for `place` (lat, lon)
    in the TV's `zone`, or 06:00 / 18:00 plus the offset without one, moved on a minute while it meets another row's."""
    if not isinstance(value, list) or not 1 <= len(value) <= 24:
        raise Error("Choose 1–24 schedule entries.")
    out = []
    for row in value:
        if not isinstance(row, dict) or row.get("collection") not in COLLECTIONS:
            raise Error("Each schedule entry needs a time and collection.")
        if row.get("anchor") is None:
            if type(row.get("start")) is not int or not 0 <= row["start"] <= 1439:
                raise Error("Each schedule entry needs a time and collection.")
            out.append({"start": row["start"], "collection": row["collection"]})
            continue
        offset = row.get("offset", 0)
        if (
            row["anchor"] not in sun.ANCHORS
            or type(offset) is not int
            or not -sun.MAX_OFFSET <= offset <= sun.MAX_OFFSET
            or offset % 5
        ):
            raise Error(
                "A sunrise or sunset entry needs an offset from −240 to 240 minutes, in steps of 5."
            )
        out.append({"anchor": row["anchor"], "offset": offset, "collection": row["collection"]})
    fixed = [r["start"] for r in out if "anchor" not in r]
    pairs = [(r["anchor"], r["offset"]) for r in out if "anchor" in r]
    if len(set(fixed)) != len(fixed) or len(set(pairs)) != len(pairs):
        raise Error("Schedule times must be different.")
    tz = sun.zone(zone)
    today = sun.day(on or sun.today(tz), tz, *place) if tz and place else None
    used = set(fixed)
    for i, r in enumerate(out):
        if "anchor" not in r:
            continue
        start = sun.minute(today, r["anchor"], r["offset"], tz) if today else None
        if start is None:
            start = ((360 if r["anchor"] == "sunrise" else 1080) + r["offset"]) % 1440
        while start in used:
            start = (start + 1) % 1440
        used.add(start)
        out[i] = {"start": start, **r}
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

    def glass_call(self, method, arg=None):
        """One call to GlassOS's ADB-only provider; returns its JSON reply. The argument may be a lock code, so
        neither adb's output nor a timeout (whose text holds the whole command line) reaches an error."""
        if method not in GLASS_METHODS or (GLASS_METHODS[method] is None and arg is not None):
            raise Error("Unknown GlassOS control.")
        args = ["content", "call", "--uri", PROVIDER, "--method", method]
        value = GLASS_METHODS[method](arg) if GLASS_METHODS[method] else None
        if value is not None:
            args += ["--arg", value]
        try:
            out = self.shell(*args, timeout=30)
        except Error as e:
            out = str(e)  # run() refuses some failure output; classified below, never shown
        except subprocess.TimeoutExpired:
            raise Error(TV_SLOW) from None
        except Exception:
            raise Error(NO_ANSWER) from None
        return glass_reply(out)


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
            for row in public["devices"]:
                row["sun_today"] = self._sun_today(row)
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
                          ("android", "ro.build.version.release"), ("abi", "ro.product.cpu.abilist"),
                          ("timezone", "persist.sys.timezone")]:
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
            # The selected TV's time zone (its main city) gives sun rows today's time as their fixed start.
            periods = (
                schedule(payload["schedule"], **self._sun_context(payload.get("device"), shared=True))
                if "schedule" in payload
                else None
            )
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
            # Sun rows' fixed starts are worked out again for this TV's time zone and today, before anything is sent.
            periods = schedule(self.state["schedule"], **self._sun_context(id, shared=True))
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

    # App lock, through GlassOS's ADB-only provider. The code is checked before connecting, goes to the TV once and
    # is never logged, returned or saved: job records and state.json reach the browser.

    def applock_status(self, id, log):
        d = self.device(id)
        log("Reading the app lock on the TV…")
        return self._applock_saved(id, d.glass_call("status"), log)

    def applock_set(self, id, code, log):
        code = lock_code(code)
        d = self.device(id)
        log("Sending the new lock code to the TV…")
        try:
            d.glass_call("lock-set-code", code)
        except Error:
            raise  # glass_call's errors are fixed sentences
        except Exception:
            raise Error("Could not change the lock code; the TV keeps its old one.") from None
        log("The TV saved the new code and showed a notice. Locked apps stay locked.")
        return self._applock_reread(id, d, "The TV saved the new code.", log)

    def applock_off(self, id, log):
        d = self.device(id)
        log("Turning off the app lock on the TV…")
        d.glass_call("lock-off")
        log("App lock is off on the TV: every app opens without a code, and the code is gone.")
        return self._applock_reread(id, d, "App lock is off on the TV.", log)

    def applock_allow_guard(self, id, log):
        d = self.device(id)
        log("Allowing GlassOS to display over other apps…")
        d.shell("appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
        saved = self._applock_reread(id, d, "The permission was sent.", log)
        if not saved["overlay"]:
            raise Error("The TV kept Display over other apps off for GlassOS.")
        if not saved["usage"]:
            log("Casting protection also needs Usage access: on the TV, open GlassOS Settings → Permissions.")
        elif saved.get("guard_running"):
            log("Casting protection is on.")
        else:
            log("Casting protection is allowed. It starts the next time GlassOS opens on the TV with an app locked.")
        return saved

    def _applock_reread(self, id, d, done, log):
        try:
            reply = d.glass_call("status")
        except Error:
            raise Error(done + " Its app lock could not be read back; choose Check.") from None
        return self._applock_saved(id, reply, log)

    def _applock_saved(self, id, reply, log):
        """Keeps the non-secret part of a status reply on the device row."""
        info = reply.get("lock")
        if not isinstance(info, dict):
            raise Error(NO_ANSWER)

        def flag(key):
            if type(info.get(key)) is not bool:
                raise Error(NO_ANSWER)
            return info[key]

        def count(key):
            if type(info.get(key)) is not int or not 0 <= info[key] <= 100_000:
                raise Error(NO_ANSWER)
            return info[key]

        saved = {
            "code": flag("code"),
            "locked": count("locked"),
            "installed_locked": count("installedLocked"),
            "guard": flag("guard"),
            "overlay": flag("overlay"),
            "usage": flag("usage"),
            "checked_at": time.time(),
        }
        if type(info.get("guardRunning")) is bool:
            saved["guard_running"] = info["guardRunning"]
        with self.lock:
            row = next((x for x in self.state["devices"] if x["id"] == id), None)
            if row is not None:
                row["applock"] = saved
                self.save()
        n = saved["installed_locked"]
        log(
            ("Lock code set. " if saved["code"] else "No lock code. ")
            + ("1 app is locked." if n == 1 else f"{n} apps are locked.")
        )
        return dict(saved)

    # Screen Mirroring, read only. The TV's reply says whether a PIN is on its screen, never the PIN; this keeps
    # neither the receiver's port nor its process.

    MIRROR_STATES = ("STOPPED", "STARTING", "RUNNING", "ERROR")
    MIRROR_MODES = ("IDLE", "MIRROR", "AUDIO", "VIDEO")
    MIRROR_RES = ("auto", "1080p", "720p")

    def mirroring_status(self, id, log):
        d = self.device(id)
        log("Reading Screen Mirroring on the TV…")
        saved = self._mirroring_saved(id, d.glass_call("airplay-status"))
        if not saved["supported"]:
            log("This TV or GlassOS build has no Screen Mirroring (it needs Fire OS 6 or later).")
        elif not saved["on"]:
            log("Screen Mirroring is off. Turn it on in GlassOS Settings on the TV.")
        else:
            log("Screen Mirroring is on: " + mirroring_line(saved) + ".")
        return saved

    def _mirroring_saved(self, id, reply):
        """Keeps the checked, non-secret part of an airplay-status reply on the device row."""
        info = reply.get("airplay")
        if not isinstance(info, dict):
            raise Error(NO_ANSWER)

        def flag(source, key):
            if type(source.get(key)) is not bool:
                raise Error(NO_ANSWER)
            return source[key]

        def choice(source, key, allowed):
            if source.get(key) not in allowed:
                raise Error(NO_ANSWER)
            return source[key]

        def count(key):
            if type(info.get(key)) is not int or not 0 <= info[key] <= 1000:
                raise Error(NO_ANSWER)
            return info[key]

        name = info.get("name")
        # One DNS-SD label: at most 63 bytes, on one line.
        if not isinstance(name, str) or len(name.encode()) > 63 or any(ord(c) < 32 for c in name):
            raise Error(NO_ANSWER)
        error = info.get("error")
        if error is not None and (not isinstance(error, str) or not re.fullmatch(r"[a-z-]{1,40}", error)):
            raise Error(NO_ANSWER)
        settings = info.get("settings")
        if not isinstance(settings, dict):
            raise Error(NO_ANSWER)
        saved = {
            "supported": flag(info, "supported"),
            "on": flag(info, "on"),
            "state": choice(info, "state", self.MIRROR_STATES),
            "error": error,
            "name": name,
            "mode": choice(info, "mode", self.MIRROR_MODES),
            "connections": count("connections"),
            "pin_shown": flag(info, "pinShown"),
            "screen": flag(info, "activityVisible"),
            "restarts": count("restarts"),
            "gave_up": flag(info, "gaveUp"),
            "pin": flag(settings, "pin"),
            "resolution": choice(settings, "res", self.MIRROR_RES),
            "sync_volume": flag(settings, "sync_volume"),
            "checked_at": time.time(),
        }
        with self.lock:
            row = next((x for x in self.state["devices"] if x["id"] == id), None)
            if row is not None:
                row["mirroring"] = saved
                self.save()
        return dict(saved)

    # Sun times. The place goes to the TV and onto the device row (rounded to 0.1 degree), never into scenery.json:
    # that file sits in the TV's shared storage. Nor do sun rows' fixed starts there come from it (two of them would
    # give the place away): they use the time zone's main city, which only GlassOS before 1.6.0 and days without a
    # sun time need.

    def _sun_context(self, id, shared=False):
        """schedule()'s place and zone for a saved device: the place set here, else its zone's city, else none.
        `shared` (for scenery.json): its zone's city even when a place is set. Reads the saved record only (no adb),
        so it is safe under self.lock."""
        row = next((x for x in self.state["devices"] if x["id"] == id), None) if id else None
        if not row:
            return {}
        zone = (row.get("sun_status") or {}).get("zone") or (row.get("health") or {}).get("timezone")
        if not sun.zone(zone):
            return {}
        place = row.get("sun")
        if place and not shared:
            return {"place": (place["lat"], place["lon"]), "zone": zone}
        estimate = sun.zone_place(zone)
        return {"place": estimate[:2], "zone": zone} if estimate else {"zone": zone}

    def _sun_today(self, row):
        """Today's sun times for the daily rhythm preview, worked out here as the TV does; never fails a snapshot."""
        try:
            context = self._sun_context(row["id"])
            tz = sun.zone(context.get("zone"))
            if not tz:
                return {"zone": None}
            out = {"zone": context["zone"], "source": "none", "sunrise": None, "sunset": None, "polar": None}
            if "place" not in context:
                return out
            saved = row.get("sun")
            out["source"] = "manager" if saved else "timezone"
            out["label"] = saved["label"] if saved else sun.zone_place(context["zone"])[2]
            day = sun.day(sun.today(tz), tz, *context["place"])
            out.update(
                sunrise=sun.minute(day, "sunrise", 0, tz),
                sunset=sun.minute(day, "sunset", 0, tz),
                polar=day["polar"],
            )
            return out
        except Exception:
            return {"zone": None}

    def sun_status(self, id, log):
        d = self.device(id)
        log("Reading sun times on the TV…")
        return self._sun_saved(id, d.glass_call("status"), log)

    def sun_set_location(self, id, location, log):
        """Sends the place (or None: back to the time-zone estimate) to the TV; checked before connecting."""
        place = sun_location(location)
        d = self.device(id)
        if place:
            log(f"Sending {place['label'] or 'the place'} ({place['lat']:.1f}, {place['lon']:.1f}) to the TV…")
            arg = f"{place['lat']:.1f},{place['lon']:.1f},{place['label']}"
        else:
            log("Asking the TV to estimate sun times from its time zone…")
            arg = None
        reply = d.glass_call("sun-location", arg)
        with self.lock:
            row = next((x for x in self.state["devices"] if x["id"] == id), None)
            if row is not None:
                if place:
                    row["sun"] = place
                else:
                    row.pop("sun", None)
                self.save()
        try:
            return self._sun_saved(id, reply, log)
        except Error:
            raise Error("The TV saved the place. Its sun times could not be read back; choose Check.") from None

    def sun_lookup(self, log):
        """Fills the place form from this Mac's internet address. Talks to ipinfo.io, never to a TV, and saves nothing:
        the result goes back to the page only. Every failure is one fixed sentence."""
        log("Asking ipinfo.io where this Mac’s internet address is…")
        try:
            place = ip_place(catalog.fetch(IP_LOOKUP, limit=IP_LOOKUP_LIMIT, timeout=8))
        except Exception:
            raise Error(IP_LOOKUP_FAILED) from None
        log(
            f"Found: {place['label'] or 'a place'} ({place['lat']:.1f}, {place['lon']:.1f}) — check this is right, then"
            " Save to TV."
        )
        return place

    def _sun_saved(self, id, reply, log):
        """Keeps what the TV reports about sun times (status's `sun`) on the device row."""
        info = reply.get("sun")
        if not isinstance(info, dict):
            raise Error(NO_ANSWER)
        source, zone = info.get("source"), info.get("zone")
        times = [info.get("sunrise"), info.get("sunset")]
        if (
            source not in ("manager", "timezone", "none")
            or not isinstance(zone, str)
            or not re.fullmatch(r"[A-Za-z0-9_+\-/:]{1,64}", zone)
            or any(v is not None and not (isinstance(v, str) and re.fullmatch(r"[0-2]\d:[0-5]\d", v)) for v in times)
            or info.get("polar") not in (None, "up", "down")
        ):
            raise Error(NO_ANSWER)
        saved = {
            "source": source,
            "zone": zone,
            "sunrise": times[0],
            "sunset": times[1],
            "polar": info.get("polar"),
            "checked_at": time.time(),
        }
        if source != "none":
            label, lat, lon = info.get("label"), info.get("lat"), info.get("lon")
            if (
                not isinstance(label, str)
                or len(label) > 40
                or any(type(v) not in (int, float) for v in (lat, lon))
                or not -90 <= lat <= 90
                or not -180 <= lon <= 180
            ):
                raise Error(NO_ANSWER)
            saved.update(label=label, lat=lat, lon=lon)
        with self.lock:
            row = next((x for x in self.state["devices"] if x["id"] == id), None)
            if row is not None:
                row["sun_status"] = saved
                # A place the TV has from another Mac, or from a save whose reply was lost, is the one to show here.
                if source == "manager":
                    row["sun"] = {"lat": sun.tenth(lat), "lon": sun.tenth(lon), "label": place_label(label)}
                self.save()
        where = {
            "manager": f"{saved.get('label') or 'the place'} set from this Mac",
            "timezone": f"{saved.get('label')}, estimated from the time zone {zone}",
            "none": f"no place: the time zone {zone} has none, so clock times apply",
        }[source]
        polar = {"up": " The sun does not set there today.", "down": " The sun does not rise there today."}
        log(
            f"Sun times on the TV: {where}."
            + (f" Sunrise {times[0]}, sunset {times[1]}." if times[0] and times[1] else polar.get(saved["polar"], ""))
        )
        return dict(saved)

    # Screensaver. GlassOS cannot choose its own screensaver (that takes WRITE_SECURE_SETTINGS); the TV's ADB shell can.
    # The first change saves the values it replaces, once, so Restore can put them back. Nothing here restarts the TV
    # or changes its sleep timers.

    def _screensaver_probe(self, d):
        """The tracked settings (checked), whether the TV lists GlassOS's screensaver, and whether Launcher Manager,
        which can also set the screensaver, is installed. Read only."""
        values = {}
        for ns, key, kind in SCREENSAVER:
            values[key] = setting_value(kind, d.shell("settings", "get", ns, key).strip() or "null")
        query = ("query-services", "--brief", "-a", "android.service.dreams.DreamService")
        installed = None
        for args in (("cmd", "package") + query, ("pm",) + query):
            try:
                out = d.shell(*args)
            except Error:
                continue  # no `cmd` before Android 7, and no query-services in older `pm`
            if "nknown command" not in out:
                installed = bool(re.search(r"(?m)^\s*" + re.escape(DREAM) + r"\s*$", out))
                break
        if installed is None:
            # Fire OS 5 and 6: the package dump lists the services that answer each action.
            out = d.shell("dumpsys", "package", PACKAGE)
            installed = "android.service.dreams.DreamService" in out and DREAM in out
        launcher = "package:" + LAUNCHER_MANAGER in d.shell("pm", "list", "packages", LAUNCHER_MANAGER).split()
        return values, installed, launcher

    def _screensaver_saved(self, id, values, installed, launcher):
        """Keeps what the TV reports about its screensaver on the device row, beside the saved previous values."""
        active = values["screensaver_components"].split(",")[0] == DREAM
        blockers, warnings = [], []
        if values["stay_on_while_plugged_in"] not in ("null", "0"):
            blockers.append("Stay awake while plugged in is on, so no screensaver can start.")
        if values["amazon_ambient_enabled"] not in ("null", "0"):
            blockers.append("Amazon’s Ambient Experience is on, and it overrides other screensavers.")
        sleep, off = values["sleep_timeout"], values["screen_off_timeout"]
        if sleep != "null" and off != "null" and 0 < int(sleep) <= int(off):
            warnings.append("This TV goes to sleep no later than a screensaver would start, so it may sleep instead.")
        if launcher:
            warnings.append("Launcher Manager is installed, and it can change the screensaver too.")
        with self.lock:
            row = next((x for x in self.state["devices"] if x["id"] == id), None)
            saver = row.setdefault("screensaver", {}) if row is not None else {}
            # Set from here and not restored since, yet the TV now has a third screensaver (not the one saved: a TV
            # that went back to its own, even straight after Use GlassOS scenery, was not changed elsewhere).
            previous = saver.get("previous")
            drift = (
                isinstance(previous, dict)
                and not active
                and values["screensaver_components"] != previous.get("screensaver_components")
            )
            if drift:
                warnings.insert(0, "The screensaver was changed elsewhere since GlassOS Manager set it.")
            saver.update(
                current=dict(values),
                installed=installed,
                launcher_manager=launcher,
                active=active,
                blockers=blockers,
                warnings=warnings,
                drift=drift,
                checked_at=time.time(),
            )
            if row is not None:
                self.save()
            return json.loads(json.dumps(saver))

    def _screensaver_report(self, status, log):
        components = status["current"]["screensaver_components"]
        log(
            "Screensaver: "
            + ("GlassOS scenery." if status["active"] else "not set." if components == "null" else components + ".")
        )
        for line in status["blockers"] + status["warnings"]:
            log(line)

    def screensaver_check(self, id, log):
        d = self.device(id)
        log("Reading the TV’s screensaver settings…")
        status = self._screensaver_saved(id, *self._screensaver_probe(d))
        self._screensaver_report(status, log)
        if not status["installed"]:
            log("GlassOS’s screensaver is not on this TV. " + UPDATE_GLASSOS)
        return status

    def screensaver_set(self, id, options, log):
        """Makes GlassOS scenery the TV's screensaver; with `options`, also turns off what would stop it starting."""
        options = screensaver_options(options)
        d = self.device(id)
        log("Reading the TV’s screensaver settings…")
        values, installed, launcher = self._screensaver_probe(d)
        if not installed:
            self._screensaver_saved(id, values, installed, launcher)
            raise Error("GlassOS’s screensaver is not on this TV. " + UPDATE_GLASSOS)
        writes = [
            ("secure", "screensaver_components", DREAM),
            ("secure", "screensaver_enabled", "1"),
            ("secure", "screensaver_activate_on_sleep", "1"),
        ]
        ambient = options["ambient"] and values["amazon_ambient_enabled"] not in ("null", "0")
        if ambient:
            writes.append(("secure", "amazon_ambient_enabled", "0"))
        if options["stayAwake"] and values["stay_on_while_plugged_in"] not in ("null", "0"):
            writes.append(("global", "stay_on_while_plugged_in", "0"))
        kinds = {key: kind for _, key, kind in SCREENSAVER}
        with self.lock:
            row = next((x for x in self.state["devices"] if x["id"] == id), None)
            if row is None:
                raise Error("Choose a saved Fire Stick first.")
            saver = row.setdefault("screensaver", {})
            # Saved once: using it again keeps the values from before the first time, which Restore puts back.
            if "previous" not in saver:
                saver.update(previous=dict(values), saved_at=time.time(), changed=[])
                log("Saved the TV’s current screensaver settings on this Mac, so Restore can put them back.")
            # Noted before writing, so Restore also covers a change made before a later one failed.
            changed = set(saver.get("changed", [])) | {key for _, key, _ in writes}
            saver["changed"] = [key for _, key, _ in SCREENSAVER if key in changed]
            self.save()
        for ns, key, value in writes:
            d.shell("settings", "put", ns, key, setting_value(kinds[key], value))
        status = self._screensaver_saved(id, *self._screensaver_probe(d))
        if not status["active"]:
            raise Error(
                "The TV did not keep GlassOS scenery as its screensaver. Its saved settings stay on this Mac; choose "
                "Restore previous screensaver to put back what changed."
            )
        log("GlassOS scenery is now this TV’s screensaver: it starts when the TV is idle, never during a video.")
        if ambient:
            log("Restart the TV once, so Amazon’s Ambient Experience lets go of the screensaver.")
        for line in status["blockers"] + status["warnings"]:
            log(line)
        return status

    def screensaver_restore(self, id, log):
        """Puts back what GlassOS Manager changed, as saved before its first change, unless the TV has since been
        given another screensaver: that is left alone, and so is everything else."""
        with self.lock:
            row = next((x for x in self.state["devices"] if x["id"] == id), None)
            saved = json.loads(json.dumps((row or {}).get("screensaver") or {}))
        previous, changed = saved.get("previous"), saved.get("changed", [])
        if not isinstance(previous, dict) or not isinstance(changed, list):
            raise Error("GlassOS Manager has not changed this TV’s screensaver, so there is nothing to restore.")
        # Checked again before anything reaches the TV: state.json is an ordinary file on this Mac.
        plan = [(ns, key, setting_value(kind, previous.get(key))) for ns, key, kind in SCREENSAVER if key in changed]
        d = self.device(id)
        log("Reading the TV’s screensaver settings…")
        values, installed, launcher = self._screensaver_probe(d)
        # Back on the saved screensaver (the TV's own), the saved values suit it; another one could break.
        components = values["screensaver_components"]
        if components.split(",")[0] != DREAM and components != previous.get("screensaver_components"):
            self._screensaver_saved(id, values, installed, launcher)
            raise Error(
                "The TV’s screensaver was changed elsewhere since GlassOS Manager set it, so nothing was restored. "
                "To put the saved settings back anyway, choose Use GlassOS scenery, then Restore previous screensaver."
            )
        for ns, key, value in plan:
            # A setting that was not there before is removed, not set to the text "null".
            if value == "null":
                d.shell("settings", "delete", ns, key)
            else:
                d.shell("settings", "put", ns, key, value)
        after = self._screensaver_probe(d)
        if any(after[0][key] != value for _, key, value in plan):
            self._screensaver_saved(id, *after)
            raise Error("The TV did not take back every saved setting. They stay saved on this Mac; try Restore again.")
        with self.lock:
            row = next((x for x in self.state["devices"] if x["id"] == id), None)
            if row is not None:
                for key in ("previous", "saved_at", "changed"):
                    row.get("screensaver", {}).pop(key, None)
                self.save()
        status = self._screensaver_saved(id, *after)
        log("Restored the screensaver settings this TV had before GlassOS Manager changed them.")
        if "amazon_ambient_enabled" in changed and previous.get("amazon_ambient_enabled") not in ("null", "0"):
            log("Restart the TV once, so Amazon’s Ambient Experience comes back.")
        self._screensaver_report(status, log)
        return status
