from datetime import date
import json
from pathlib import Path
import string
import subprocess
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core import (
    Device,
    Error,
    Manager,
    address,
    atomic_json,
    digest,
    glass_reply,
    ip_place,
    lock_code,
    schedule,
    screensaver_options,
    setting_value,
    sun_location,
    DREAM,
    IP_LOOKUP,
    IP_LOOKUP_FAILED,
    IP_LOOKUP_LIMIT,
    NO_ANSWER,
    PACKAGE,
    PROVIDER,
    REMOTE,
    RESERVE,
    SCREENSAVER,
    TV_SLOW,
    UPDATE_GLASSOS,
)
import sun
import core

# What ManagerProvider.kt prints through `content call` (Android's org.json writes "/" as "\/").
STATUS = (
    'Result: Bundle[{glass={"ok":true,"version":"1.6.0-aerial.1","versionCode":9,'
    '"lock":{"code":true,"locked":3,"installedLocked":2,"guard":false,"usage":true,'
    '"overlay":false,"guardRunning":false},"sun":{"source":"none","zone":"America\\/Los_Angeles"},'
    '"screensaver":{"component":null,"active":false,"enabled":null}}}]'
)
SUN = {
    "source": "manager", "zone": "America/Los_Angeles", "label": "Home", "lat": 34.1, "lon": -118.2,
    "sunrise": "06:12", "sunset": "17:01", "polar": None,
}
MISSING_PROVIDER = (
    "Error while accessing provider:io.github.abhishekkaisar.glassos.aerial.manager\n"
    "java.lang.IllegalStateException: Could not find provider: io.github.abhishekkaisar.glassos.aerial.manager\n"
    "\tat com.android.commands.content.Content$Command.execute(Content.java:521)"
)


def ok(**fields):
    return "Result: Bundle[{glass=" + json.dumps({"ok": True, **fields}, separators=(",", ":")) + "}]"


SUN_REPLY = ok(sun=SUN)

# ManagerProvider's airplay-status reply: a running receiver with a PIN on the TV's screen (never in the reply).
MIRROR = {
    "supported": True, "on": True, "connected": True, "state": "RUNNING", "error": None, "name": "Den",
    "port": 7000, "connections": 1, "mode": "IDLE", "activityVisible": True, "offersView": False, "pinShown": True,
    "videoWidth": 0, "videoHeight": 0, "pid": 4325, "restarts": 0, "gaveUp": False,
    "settings": {"name": "Den", "pin": True, "res": "720p", "sync_volume": False, "port": 7000, "h265": "auto"},
}


def mirror(**changes):
    m = json.loads(json.dumps(MIRROR))
    for k, v in changes.items():
        if k in ("pin", "res", "sync_volume"):
            m["settings"][k] = v
        else:
            m[k] = v
    return ok(airplay=m)


class MemoryDevice(Device):
    def __init__(self):
        self.files = {}
        self.commands = []
        self.available = 8 * 1024**3
        self.bad_push = False
        self.interrupt = False
        # `content call` output per provider method; an exception instance is raised instead.
        self.replies = {}

    def exists(self, p):
        return p in self.files

    def hash(self, p):
        import hashlib

        return hashlib.sha256(self.files[p]).hexdigest()

    def free(self):
        return self.available

    def adb(self, *args, **kwargs):
        self.commands.append(args)
        if args[0] == "push":
            self.files[args[2]] = (
                b"partial"
                if self.bad_push or self.interrupt
                else Path(args[1]).read_bytes()
            )
            if self.interrupt:
                raise Error("connection lost")
        return "Success"

    def shell(self, *args, **kwargs):
        self.commands.append(args)
        if args[:2] == ("content", "call"):
            reply = self.replies.get(args[args.index("--method") + 1], "")
            if isinstance(reply, BaseException):
                raise reply
            return reply
        if args[0] == "mv":
            self.files[args[-1]] = self.files.pop(args[-2])
        elif args[0] == "rm":
            self.files.pop(args[-1], None)
        return ""

    def start(self, action=None):
        self.commands.append(("start", action))
        return ""


AMAZON = "com.amazon.ftv.screensaver/.app.services.ScreensaverService"


class ScreensaverTV(MemoryDevice):
    """A Fire TV's screensaver settings as `settings` and the package manager report them. A key missing from
    `settings` reads as "null", as on a TV."""

    def __init__(self):
        super().__init__()
        self.settings = {
            ("secure", "screensaver_components"): AMAZON,
            ("secure", "screensaver_enabled"): "1",
            ("secure", "screensaver_default_component"): AMAZON,
            ("secure", "amazon_ambient_enabled"): "1",
            ("secure", "sleep_timeout"): "-1",
            ("system", "screen_off_timeout"): "600000",
            ("global", "stay_on_while_plugged_in"): "3",
        }
        self.dreams = [AMAZON, DREAM]
        self.packages = []

    def shell(self, *args, **kwargs):
        if args[0] == "settings":
            self.commands.append(args)
            verb, ns, key = args[1:4]
            if verb == "get":
                return self.settings.get((ns, key), "null")
            if verb == "put":
                self.settings[(ns, key)] = args[4]
            elif verb == "delete":
                self.settings.pop((ns, key), None)
            return ""
        if args[:3] == ("cmd", "package", "query-services"):
            self.commands.append(args)
            return "\n".join(self.dreams) or "No services found"
        if args[:3] == ("pm", "list", "packages"):
            self.commands.append(args)
            return "\n".join("package:" + p for p in self.packages if args[3] in p)
        return super().shell(*args, **kwargs)

    def writes(self):
        return [c for c in self.commands if c[0] == "settings" and c[1] in ("put", "delete")]


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.m = Manager(self.root / "data")
        self.d = MemoryDevice()
        self.m.device = lambda _: self.d

    def tearDown(self):
        self.tmp.cleanup()

    def scene(self, filename="scene.mp4", data=b"video"):
        p = self.root / filename
        p.write_bytes(data)
        row = {
            "id": filename,
            "name": filename,
            "title": "Test",
            "collection": "coast",
            "path": str(p),
            "poster_path": None,
            "sha256": digest(p),
            "bytes": len(data),
            "duration": 90,
            "enabled": True,
        }
        self.m.state["scenes"].append(row)
        return row

    def test_explicit_addresses_reject_shell_text(self):
        self.assertEqual("tv.local:5555", address("tv.local"))
        for value in [
            "-s",
            "x;reboot",
            "x:0",
            "x:65536",
            "x/../../",
            "x\nreboot",
            "x$(id)",
            None,
        ]:
            with self.assertRaises(Error):
                address(value)

    def test_schedule_is_atomic_and_strict(self):
        self.assertEqual(
            [
                {"start": 0, "collection": "space"},
                {"start": 420, "collection": "coast"},
            ],
            schedule(
                [
                    {"start": 420, "collection": "coast"},
                    {"start": 0, "collection": "space"},
                ]
            ),
        )
        for bad in [
            [],
            [{"start": True, "collection": "coast"}],
            [{"start": 0, "collection": "bad"}],
            [{"start": 0, "collection": "space"}] * 2,
        ]:
            with self.assertRaises(Error):
                schedule(bad)

    def test_failed_push_never_replaces_old_file(self):
        row = self.scene()
        target = REMOTE + "/scene.mp4"
        self.d.files[target] = b"old"
        self.d.bad_push = True
        with self.assertRaises(Error):
            self.d.put(row["path"], target)
        self.assertEqual(b"old", self.d.files[target])
        self.assertNotIn(target + ".glassos-part", self.d.files)

    def test_interrupted_push_is_retryable(self):
        row = self.scene()
        target = REMOTE + "/scene.mp4"
        self.d.files[target] = b"old"
        self.d.interrupt = True
        with self.assertRaises(Error):
            self.d.put(row["path"], target)
        self.assertEqual(b"old", self.d.files[target])
        self.d.interrupt = False
        self.d.put(row["path"], target)
        self.assertEqual(b"video", self.d.files[target])

    def test_space_refusal_before_any_mutation(self):
        self.scene()
        self.d.available = RESERVE
        with self.assertRaisesRegex(Error, "free space"):
            self.m.sync("test", lambda _: None)
        self.assertEqual([], self.d.commands)
        self.assertEqual({}, self.d.files)

    def test_corrupt_source_refused_before_remote_write(self):
        r = self.scene()
        Path(r["path"]).write_bytes(b"changed")
        with self.assertRaisesRegex(Error, "changed"):
            self.m.sync("test", lambda _: None)
        self.assertEqual({}, self.d.files)

    def test_budget_blocks_transfer(self):
        self.scene()
        self.m.state["budget_mb"] = 0
        with self.assertRaisesRegex(Error, "budget"):
            self.m.sync("test", lambda _: None)
        self.assertEqual([], self.d.commands)

    def test_existing_files_skipped_and_extras_kept(self):
        self.scene()
        self.d.files[REMOTE + "/scene.mp4"] = b"video"
        self.d.files[REMOTE + "/personal.mp4"] = b"personal"
        out = self.m.sync("test", lambda _: None)
        self.assertEqual(0, out["transferred"])
        self.assertEqual(b"personal", self.d.files[REMOTE + "/personal.mp4"])
        self.assertEqual(
            ["coast"],
            json.loads(self.d.files[REMOTE + "/scenes.json"])["scene.mp4"][
                "collections"
            ],
        )
        self.assertTrue(self.d.files[REMOTE + "/scenery.json"])

    def test_metadata_committed_only_after_good_transfers(self):
        self.scene()
        self.d.bad_push = True
        self.d.files[REMOTE + "/scenes.json"] = b"old metadata"
        with self.assertRaises(Error):
            self.m.sync("test", lambda _: None)
        self.assertEqual(b"old metadata", self.d.files[REMOTE + "/scenes.json"])

    def test_bad_schedule_cannot_partly_change_budget(self):
        old = self.m.state["budget_mb"]
        with self.assertRaises(Error):
            self.m.configure({"budget_mb": 3000, "schedule": []})
        self.assertEqual(old, self.m.state["budget_mb"])

    def test_absent_instagram_is_a_valid_health_result(self):
        from unittest.mock import Mock

        self.m.add_device("Test", "tv.local")
        id = self.m.state["devices"][0]["id"]
        d = Mock()
        d.free.return_value = 1024**3

        def shell(*args, **kwargs):
            if args[:2] == ("pm", "path"):
                raise AssertionError("Must not query a missing package path")
            if args[:3] == ("pm", "list", "packages"):
                return (
                    "package:io.github.abhishekkaisar.glassos.aerial"
                    if "-d" not in args
                    else ""
                )
            if args[0] == "getprop":
                return "31" if args[1].endswith("sdk") else "Test"
            if args[0] == "settings":
                return "null"
            if args[0] == "dumpsys":
                return "versionName=test"
            return "example/.Home"

        d.shell.side_effect = shell
        self.m.device = lambda _: d
        result = self.m.inspect(id, lambda _: None)
        self.assertEqual({"Not installed"}, set(result["instagram"].values()))

    def test_recent_existing_backup_is_not_called_fresh(self):
        from unittest.mock import Mock

        d = Mock()
        d.shell.side_effect = lambda *args, **kwargs: (
            "/sdcard/Documents/GlassOS-Aerial/old.json" if args[0] == "sh" else "100"
        )
        with patch("core.time.monotonic", side_effect=[0, 100]):
            with self.assertRaisesRegex(Error, "No fresh backup"):
                self.m._collect_backup(
                    d,
                    "test",
                    lambda _: None,
                    True,
                    {"/sdcard/Documents/GlassOS-Aerial/old.json"},
                )
        d.adb.assert_not_called()

    def test_update_rejects_changed_apk_before_connecting(self):
        from unittest.mock import Mock

        bundle = self.root / "bundle"
        work = bundle / "work-session-2026-10-24/aerial-work"
        work.mkdir(parents=True)
        apk = work / "GlassOS.apk"
        apk.write_bytes(b"original")
        atomic_json(
            work / "active-build.json",
            {"apk_file": apk.name, "apk_sha256": digest(apk)},
        )
        apk.write_bytes(b"changed")
        m = Manager(self.root / "updater", bundle)
        m.device = Mock()
        with self.assertRaisesRegex(Error, "checksum mismatch"):
            m.update("test", lambda _: None)
        m.device.assert_not_called()

    def test_update_rejects_wrong_certificate_before_connecting(self):
        from unittest.mock import Mock

        bundle = self.root / "bundle"
        work = bundle / "work-session-2026-10-24/aerial-work"
        work.mkdir(parents=True)
        apk = work / "GlassOS.apk"
        apk.write_bytes(b"fixture")
        atomic_json(
            work / "active-build.json",
            {"apk_file": apk.name, "apk_sha256": digest(apk)},
        )
        m = Manager(self.root / "updater", bundle)
        m.device = Mock()
        with (
            patch("core.tool", return_value="apksigner"),
            patch("core.run", return_value="Signer certificate SHA-256 digest: wrong"),
        ):
            with self.assertRaisesRegex(Error, "signing certificate"):
                m.update("test", lambda _: None)
        m.device.assert_not_called()

    def test_portable_public_state_omits_media_paths(self):
        self.scene()
        state = self.m.snapshot()
        self.assertNotIn("path", state["scenes"][0])
        self.assertNotIn("poster_path", state["scenes"][0])

    def test_atomic_json_replaces_and_has_private_permissions(self):
        p = self.root / "a.json"
        atomic_json(p, {"old": True})
        atomic_json(p, {"new": True})
        self.assertEqual({"new": True}, json.loads(p.read_text()))
        self.assertEqual(0o600, p.stat().st_mode & 0o777)

    def test_unknown_device_never_defaults_to_another(self):
        m = Manager(self.root / "other")
        m.add_device("Example", "tv.local")
        with self.assertRaises(Error):
            m.device("missing")

    def test_job_failures_release_exclusive_worker(self):
        import time

        self.m.job("fail", lambda _: (_ for _ in ()).throw(Error("test failure")))
        for _ in range(100):
            if not self.m.work_lock.locked():
                break
            time.sleep(0.005)
        self.assertEqual("error", self.m.jobs[-1]["status"])
        self.assertFalse(self.m.work_lock.locked())

    # ---------- App lock through GlassOS's ADB-only provider ----------

    def tv(self):
        self.m.add_device("Test", "tv.local")
        return self.m.state["devices"][-1]["id"]

    def run_job(self, work):
        import time

        self.m.job("App lock", work)
        for _ in range(400):
            if not self.m.work_lock.locked():
                break
            time.sleep(0.005)
        return self.m.jobs[-1]

    def everything_kept(self):
        """Every job record, the browser's state and every file the manager wrote."""
        files = "".join(
            p.read_text(errors="replace") for p in self.m.data.rglob("*") if p.is_file()
        )
        return json.dumps(self.m.jobs) + json.dumps(self.m.snapshot()) + files

    def test_glass_reply_reads_only_glassos_answers(self):
        self.assertEqual(
            "content://io.github.abhishekkaisar.glassos.aerial.manager", PROVIDER
        )
        r = glass_reply("WARNING: linker: unused DT entry\n" + STATUS + "\n")
        self.assertEqual(2, r["lock"]["installedLocked"])
        self.assertEqual("America/Los_Angeles", r["sun"]["zone"])
        self.assertIsNone(r["screensaver"]["component"])
        with self.assertRaises(Error) as caught:
            glass_reply(MISSING_PROVIDER)
        self.assertEqual(UPDATE_GLASSOS, str(caught.exception))
        # A place name the TV echoes back is part of its answer, not an error from `content`.
        for label in ["SecurityException Bay", "Unknown authority", "Could not find provider"]:
            self.assertEqual(label, glass_reply(ok(sun=dict(SUN, label=label)))["sun"]["label"])
        for bad in [
            "",
            "Result: null",
            "Result: Bundle[{}]",
            "Result: Bundle[mParcelledData.dataSize=120]",
            'Result: Bundle[{other={"ok":true}}]',
            "Result: Bundle[{glass={not json}}]",
            'Result: Bundle[{glass={"locked":3}}]',
            'Result: Bundle[{glass={"ok":"yes"}}]',
            ' Result: Bundle[{glass={"ok":true}}]',
            'Result: Bundle[{glass={"ok":true}}] and more',
            STATUS + "\n" + STATUS,
            "Error while accessing provider:x\njava.lang.SecurityException: Permission Denial: opening provider",
            "Error while accessing provider:x\njava.lang.NullPointerException",
        ]:
            with self.assertRaises(Error, msg=bad):
                glass_reply(bad)
        # A refusal maps to a fixed sentence; the TV's own text is not shown.
        for code, text in [("bad-code", "refused that lock code"), ("unknown-method", "Update GlassOS")]:
            with self.assertRaisesRegex(Error, text) as caught:
                glass_reply(
                    'Result: Bundle[{glass={"ok":false,"error":"%s","message":"FROM-TV"}}]' % code
                )
            self.assertNotIn("FROM-TV", str(caught.exception))

    def test_lock_letters_match_the_tv(self):
        # AppLockTest.kt checks that LockCode.LETTERS is these same seven letters.
        def valid(code):
            try:
                return lock_code(code) == code
            except Error:
                return False

        self.assertEqual(
            "DFLPRUW", "".join(c for c in string.ascii_uppercase if valid(c * 4))
        )
        self.assertEqual("WPFR", lock_code(" wpfr "))

    def test_bad_lock_codes_refused_before_connecting(self):
        from unittest.mock import Mock

        self.m.device = Mock()
        for bad in [
            None, "", "UUD", "UUDPP", "UU;D", "UUDX", "U D P", "UUDP\nreboot",
            "ＵＵＤＰ", "$(id)", 1234, ["UUDP"],
        ]:
            with self.assertRaises(Error) as caught:
                self.m.applock_set("test", bad, lambda _: None)
            if isinstance(bad, str) and bad.strip():
                self.assertNotIn(bad.strip(), str(caught.exception))
        self.m.device.assert_not_called()

    def test_glass_call_sends_only_known_controls(self):
        for method, arg in [
            ("reboot", None),
            ("sun-location", "91,0,Home"),
            ("sun-location", "34.1,-118.2,Home; reboot"),
            ("sun-location", "--34.1,-118.2,Home"),
            ("status", "x"),
            ("lock-off", "UDLR"),
            ("lock-set-code", None),
            ("lock-set-code", "UU;D"),
        ]:
            with self.assertRaises(Error):
                self.d.glass_call(method, arg)
        self.assertEqual([], self.d.commands)
        self.d.replies["status"] = STATUS
        self.d.glass_call("status")
        self.assertEqual(
            [("content", "call", "--uri", PROVIDER, "--method", "status")], self.d.commands
        )
        # `content` cannot send an empty --arg: clearing the sun place sends none.
        self.d.commands.clear()
        self.d.replies["sun-location"] = SUN_REPLY
        self.d.glass_call("sun-location")
        self.d.glass_call("sun-location", "34.1,-118.2,St. John's, NL")
        self.assertEqual(
            [
                ("content", "call", "--uri", PROVIDER, "--method", "sun-location"),
                ("content", "call", "--uri", PROVIDER, "--method", "sun-location", "--arg", "34.1,-118.2,St. John's, NL"),
            ],
            self.d.commands,
        )

    def test_new_lock_code_goes_to_the_tv_once_and_nowhere_else(self):
        id = self.tv()
        self.d.replies = {"lock-set-code": ok(locked=3), "status": STATUS}
        job = self.run_job(lambda log: self.m.applock_set(id, " wpfr ", log))
        self.assertEqual("done", job["status"], job.get("error"))
        self.assertEqual(
            [("content", "call", "--uri", PROVIDER, "--method", "lock-set-code", "--arg", "WPFR")],
            [c for c in self.d.commands if "WPFR" in c],
        )
        row = self.m.state["devices"][0]["applock"]
        self.assertEqual(
            {"code", "locked", "installed_locked", "guard", "overlay", "usage", "guard_running", "checked_at"},
            set(row),
        )
        self.assertEqual(
            (True, 3, 2, False, False, True),
            (row["code"], row["locked"], row["installed_locked"], row["guard"], row["overlay"], row["usage"]),
        )
        kept = self.everything_kept()
        self.assertNotIn("WPFR", kept)
        self.assertNotIn("wpfr", kept)

    def test_failed_lock_code_change_reveals_nothing(self):
        id = self.tv()
        for reply in [
            # A timeout's text holds the whole command line; adb's output can be anything.
            subprocess.TimeoutExpired(["adb", "shell", "content call --arg WPFR"], 30, output="WPFR"),
            Error("adb: error: closed WPFR"),
            OSError("WPFR"),
            'Result: Bundle[{glass={"ok":false,"error":"bad-code","message":"WPFR"}}]',
            "WPFR",
            MISSING_PROVIDER,
        ]:
            self.d.replies = {"lock-set-code": reply, "status": STATUS}
            job = self.run_job(lambda log: self.m.applock_set(id, "WPFR", log))
            self.assertEqual("error", job["status"])
            self.assertNotIn("WPFR", json.dumps(job))
        self.assertNotIn("applock", self.m.state["devices"][0])
        self.assertNotIn("WPFR", self.everything_kept())

    def test_slow_tv_is_not_called_outdated(self):
        id = self.tv()
        # What subprocess raises when `content call` runs out of time: its text holds the whole command line.
        slow = subprocess.TimeoutExpired(["adb", "shell", "content call --arg WPFR"], 30, output="WPFR")
        self.d.replies = {"status": slow, "lock-set-code": slow, "lock-off": slow, "sun-location": slow}
        for work in [
            lambda log: self.m.applock_status(id, log),
            lambda log: self.m.applock_set(id, "WPFR", log),
            lambda log: self.m.applock_off(id, log),
            lambda log: self.m.sun_status(id, log),
            lambda log: self.m.sun_set_location(id, None, log),
        ]:
            job = self.run_job(work)
            self.assertEqual(("error", TV_SLOW), (job["status"], job.get("error")))
            self.assertNotIn("WPFR", json.dumps(job))
        self.assertNotIn("update", TV_SLOW.lower())
        self.assertNotIn("WPFR", self.everything_kept())
        # The code reached the TV but reading it back ran out of time: said as such.
        self.d.replies = {"lock-set-code": ok(locked=3), "status": slow}
        with self.assertRaisesRegex(Error, "saved the new code.*choose Check"):
            self.m.applock_set(id, "UUDP", lambda _: None)

    def test_device_timeout_reaches_glass_call_without_the_command(self):
        # Device.shell -> adb -> run() lets subprocess's timeout through unwrapped; glass_call names it.
        d = Device("tv.local")
        timeout = subprocess.TimeoutExpired(["adb", "-s", "tv.local:5555", "shell", "content call --arg WPFR"], 30)
        with patch("core.tool", return_value="adb"), patch("core.subprocess.run", side_effect=timeout) as run:
            with self.assertRaises(Error) as caught:
                d.glass_call("lock-set-code", "WPFR")
        self.assertEqual(TV_SLOW, str(caught.exception))
        self.assertEqual(30, run.call_args.kwargs["timeout"])
        self.assertIsNone(caught.exception.__cause__)
        self.assertTrue(caught.exception.__suppress_context__)
        # Any other failure to run adb still reads as no answer.
        with patch("core.tool", return_value="adb"), patch("core.subprocess.run", side_effect=OSError("WPFR")):
            with self.assertRaises(Error) as caught:
                d.glass_call("status")
        self.assertEqual(NO_ANSWER, str(caught.exception))

    def test_code_saved_but_not_read_back_says_so(self):
        id = self.tv()
        self.d.replies = {"lock-set-code": ok(locked=3), "status": "Result: null"}
        with self.assertRaisesRegex(Error, "saved the new code.*choose Check"):
            self.m.applock_set(id, "UUDP", lambda _: None)

    def test_missing_provider_asks_for_an_update(self):
        id = self.tv()
        for reply in [
            MISSING_PROVIDER,
            # run() refuses some failure output itself; the answer is the same.
            Error("Exception occurred while executing 'call':\n" + MISSING_PROVIDER),
        ]:
            self.d.replies["status"] = reply
            with self.assertRaises(Error) as caught:
                self.m.applock_status(id, lambda _: None)
            self.assertEqual(UPDATE_GLASSOS, str(caught.exception))
        self.assertNotIn("applock", self.m.state["devices"][0])

    def test_unexpected_status_is_not_saved(self):
        id = self.tv()
        for bad in [
            ok(),
            ok(lock=[]),
            ok(lock={"code": "yes"}),
            STATUS.replace('"locked":3', '"locked":-1'),
            STATUS.replace('"usage":true', '"usage":1'),
        ]:
            self.d.replies["status"] = bad
            with self.assertRaises(Error) as caught:
                self.m.applock_status(id, lambda _: None)
            self.assertEqual(NO_ANSWER, str(caught.exception))
        self.assertNotIn("applock", self.m.state["devices"][0])

    def test_lock_off_and_casting_protection(self):
        id = self.tv()
        off = STATUS.replace(
            '"code":true,"locked":3,"installedLocked":2', '"code":false,"locked":0,"installedLocked":0'
        )
        self.d.replies = {"lock-off": ok(), "status": off}
        out = self.m.applock_off(id, lambda _: None)
        self.assertEqual((False, 0), (out["code"], out["locked"]))
        self.assertIn(("content", "call", "--uri", PROVIDER, "--method", "lock-off"), self.d.commands)
        self.d.commands.clear()
        self.d.replies["status"] = STATUS.replace('"overlay":false', '"overlay":true').replace(
            '"guard":false', '"guard":true'
        )
        out = self.m.applock_allow_guard(id, lambda _: None)
        self.assertEqual(("appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow"), self.d.commands[0])
        self.assertTrue(out["overlay"] and out["guard"])
        # The TV keeps the permission off: said plainly, with what it reports saved.
        self.d.replies["status"] = STATUS
        with self.assertRaisesRegex(Error, "kept Display over other apps off"):
            self.m.applock_allow_guard(id, lambda _: None)
        self.assertFalse(self.m.state["devices"][0]["applock"]["overlay"])

    # ---------- Screen Mirroring, read only ----------

    def test_mirroring_status_only_reads_and_keeps_no_pin_port_or_pid(self):
        id = self.tv()
        self.d.replies = {"airplay-status": mirror()}
        messages = []
        out = self.m.mirroring_status(id, messages.append)
        # One call, with no argument; nothing else reaches the TV.
        self.assertEqual([("content", "call", "--uri", PROVIDER, "--method", "airplay-status")], self.d.commands)
        row = self.m.state["devices"][0]["mirroring"]
        self.assertEqual(
            {"supported", "on", "state", "error", "name", "mode", "connections", "pin_shown", "screen", "restarts",
             "gave_up", "pin", "resolution", "sync_volume", "checked_at"},
            set(row),
        )
        self.assertEqual(
            (True, True, "RUNNING", "Den", True, "720p", False),
            (row["supported"], row["on"], row["state"], row["name"], row["pin_shown"], row["resolution"], row["sync_volume"]),
        )
        self.assertEqual(out["state"], row["state"])
        self.assertIn("Screen Mirroring is on: showing a code.", messages)
        kept = json.dumps(self.m.state)
        self.assertNotIn("4325", kept)
        self.assertNotIn("7000", kept)

    def test_mirroring_off_reads_off_whatever_the_receiver_last_did(self):
        id = self.tv()
        self.d.replies = {"airplay-status": mirror(on=False, state="ERROR", error="native-init", pinShown=False)}
        messages = []
        out = self.m.mirroring_status(id, messages.append)
        self.assertEqual("off", core.mirroring_line(out))
        self.assertIn("Screen Mirroring is off. Turn it on in GlassOS Settings on the TV.", messages)
        cases = [
            (dict(state="RUNNING", pinShown=False), "ready as “Den”"),
            (dict(state="RUNNING", pinShown=False, mode="MIRROR"), "mirroring"),
            (dict(state="RUNNING", pinShown=False, mode="AUDIO"), "playing music"),
            (dict(state="RUNNING", pinShown=True), "showing a code"),
            (dict(state="RUNNING", pinShown=True, activityVisible=False), "code requested (hidden on the TV)"),
            (dict(state="STARTING", pinShown=False), "starting"),
            (dict(state="ERROR", error="port", pinShown=False), "could not start"),
            (dict(state="ERROR", error="crashed", gaveUp=True, pinShown=False), "stopped unexpectedly"),
        ]
        for changes, line in cases:
            self.d.replies = {"airplay-status": mirror(**changes)}
            self.assertEqual(line, core.mirroring_line(self.m.mirroring_status(id, lambda _: None)))

    def test_mirroring_unsupported_tv(self):
        id = self.tv()
        self.d.replies = {"airplay-status": mirror(supported=False, on=False, state="STOPPED", pinShown=False, name="")}
        messages = []
        self.assertFalse(self.m.mirroring_status(id, messages.append)["supported"])
        self.assertIn("needs Fire OS 6", messages[-1])
        # GlassOS before Screen Mirroring does not know the control.
        self.d.replies = {"airplay-status": 'Result: Bundle[{glass={"ok":false,"error":"unknown-method","message":"x"}}]'}
        with self.assertRaisesRegex(Error, "Update GlassOS"):
            self.m.mirroring_status(id, lambda _: None)

    def test_unexpected_mirroring_reply_is_not_saved(self):
        id = self.tv()
        for bad in [
            ok(),
            ok(airplay=[]),
            mirror(on="yes"),
            mirror(state="RUNNINGISH"),
            mirror(mode=3),
            mirror(connections=-1),
            mirror(restarts="1"),
            mirror(error="Native Init!"),
            mirror(name="x" * 64),
            mirror(name="Den\nTV"),
            mirror(res="4k"),
            mirror(pin="yes"),
            ok(airplay={**MIRROR, "settings": None}),
        ]:
            self.d.replies = {"airplay-status": bad}
            with self.assertRaises(Error) as caught:
                self.m.mirroring_status(id, lambda _: None)
            self.assertEqual(NO_ANSWER, str(caught.exception))
        self.assertNotIn("mirroring", self.m.state["devices"][0])

    def test_screen_mirroring_is_never_changed_from_here(self):
        self.assertIsNone(core.GLASS_METHODS["airplay-status"])
        self.assertFalse({"airplay-start", "airplay-stop", "airplay-set", "airplay-show", "airplay-debug"} & set(core.GLASS_METHODS))
        with self.assertRaisesRegex(Error, "Unknown GlassOS control"):
            self.d.glass_call("airplay-status", "on")
        with self.assertRaisesRegex(Error, "Unknown GlassOS control"):
            self.d.glass_call("airplay-start")

    # ---------- Daily rhythm entries that follow the sun ----------

    def test_sun_rows_get_distinct_fixed_starts(self):
        rows = [
            {"start": 1080, "collection": "space"},
            {"anchor": "sunset", "offset": 0, "collection": "coast"},
            {"anchor": "sunrise", "offset": -30, "collection": "forest", "start": 5},
        ]
        # No place or zone: 06:00 / 18:00 plus the offset, moved off the fixed 18:00. A sent start is ignored.
        self.assertEqual(
            [
                {"start": 330, "anchor": "sunrise", "offset": -30, "collection": "forest"},
                {"start": 1080, "collection": "space"},
                {"start": 1081, "anchor": "sunset", "offset": 0, "collection": "coast"},
            ],
            schedule(rows),
        )
        # Seattle on the June solstice: today's sunrise 05:12 and sunset 21:11 in the TV's zone.
        out = schedule(rows, (47.6062, -122.3321), "America/Los_Angeles", date(2026, 6, 21))
        self.assertEqual([282, 1080, 1271], [r["start"] for r in out])
        # A sun time on a fixed row's minute moves on to the next free minute.
        out = schedule(
            [{"start": 1271, "collection": "space"}, {"start": 1272, "collection": "city"}, rows[1]],
            (47.6062, -122.3321), "America/Los_Angeles", date(2026, 6, 21),
        )
        self.assertEqual(1273, out[-1]["start"])
        # Polar night, or a zone the Mac does not know: the fixed fallback.
        out = schedule([rows[1]], (78.2232, 15.6267), "Arctic/Longyearbyen", date(2026, 12, 21))
        self.assertEqual(1080, out[0]["start"])
        self.assertEqual(1080, schedule([rows[1]], (47.6, -122.3), "Not/AZone")[0]["start"])
        # Past midnight: Seattle's sunset (21:10.9) + 4 hours is 01:11.
        out = schedule(
            [{"anchor": "sunset", "offset": 240, "collection": "space"}],
            (47.6062, -122.3321), "America/Los_Angeles", date(2026, 6, 21),
        )
        self.assertEqual(71, out[0]["start"])

    def test_sun_rows_are_strict(self):
        for bad in [
            [{"anchor": "dusk", "offset": 0, "collection": "coast"}],
            [{"anchor": "sunset", "offset": 241, "collection": "coast"}],
            [{"anchor": "sunset", "offset": -245, "collection": "coast"}],
            [{"anchor": "sunset", "offset": 7, "collection": "coast"}],
            [{"anchor": "sunset", "offset": True, "collection": "coast"}],
            [{"anchor": "sunset", "offset": -30.0, "collection": "coast"}],
            [{"anchor": "sunset", "offset": "30", "collection": "coast"}],
            [{"anchor": "sunset", "offset": 0, "collection": "bad"}],
            [{"anchor": 1, "offset": 0, "collection": "coast"}],
            # Two rows at the same sun time, or two at the same clock time.
            [{"anchor": "sunset", "offset": 0, "collection": "coast"}] * 2,
            [{"start": 60, "collection": "coast"}, {"start": 60, "collection": "space"}],
        ]:
            with self.assertRaises(Error, msg=bad):
                schedule(bad)

    def test_sync_writes_version_1_with_a_start_on_every_row(self):
        self.scene()
        self.m.add_device("Test", "tv.local")
        row = self.m.state["devices"][0]
        row["health"] = {"timezone": "America/Los_Angeles"}
        row["sun"] = {"lat": 47.6, "lon": -122.3, "label": "Seattle"}
        with patch("sun.today", return_value=date(2026, 6, 21)):
            self.m.configure(
                {
                    "device": row["id"],
                    "schedule": [
                        {"start": 420, "collection": "coast"},
                        {"anchor": "sunset", "offset": 30, "collection": "space"},
                    ],
                }
            )
            self.assertEqual(
                {"start", "anchor", "offset", "collection"}, set(self.m.state["schedule"][1])
            )
            self.m.sync(row["id"], lambda _: None)
        doc = json.loads(self.d.files[REMOTE + "/scenery.json"])
        self.assertEqual(1, doc["version"])
        self.assertEqual(["start", "collection"], list(doc["schedule"][0]))
        self.assertEqual(["start", "anchor", "offset", "collection"], list(doc["schedule"][1]))
        self.assertTrue(all(type(r["start"]) is int for r in doc["schedule"]))
        self.assertEqual(2, len({r["start"] for r in doc["schedule"]}))
        # The place never goes into the TV's shared storage, not even as sun times: the fixed start is the zone's
        # city's (Los Angeles: sunset 20:08 + 30), not Seattle's (21:11 + 30), nor 18:00 + 30 without the TV's zone.
        # Saving (configure) works it out the same way. Without the system's zone.tab there is no city: 18:00 + 30.
        tz, city = sun.zone("America/Los_Angeles"), sun.zone_place("America/Los_Angeles")
        seattle = sun.minute(sun.day(date(2026, 6, 21), tz, 47.6, -122.3), "sunset", 30, tz)
        expected = sun.minute(sun.day(date(2026, 6, 21), tz, *city[:2]), "sunset", 30, tz) if city else 1110
        self.assertEqual((1301, 1238 if city else 1110), (seattle, expected))
        self.assertEqual(expected, doc["schedule"][1]["start"])
        self.assertEqual(expected, self.m.state["schedule"][1]["start"])
        text = self.d.files[REMOTE + "/scenery.json"].decode()
        for key in ["lat", "lon", "Seattle", "47.6"]:
            self.assertNotIn(key, text)

    def test_location_is_checked_and_rounded(self):
        self.assertEqual(
            {"lat": 34.1, "lon": -118.2, "label": "Los Angeles"},
            sun_location({"lat": 34.05, "lon": -118.25, "label": "  Los   Angeles "}),
        )
        self.assertEqual(0.0, sun_location({"lat": -0.04, "lon": 0})["lat"])
        self.assertEqual("-0.0", str(-0.0))
        self.assertEqual("0.0", str(sun_location({"lat": -0.04, "lon": 0})["lat"]))
        self.assertEqual("St. John's, NL", sun_location({"lat": 47.6, "lon": -52.7, "label": "St. John's, NL"})["label"])
        self.assertEqual("Home script", sun_location({"lat": 1, "lon": 2, "label": "Home <script>"})["label"])
        self.assertEqual("Zürich", sun_location({"lat": 47.4, "lon": 8.5, "label": "Zürich"})["label"])
        self.assertEqual("", sun_location({"lat": 1, "lon": 2})["label"])
        self.assertIsNone(sun_location(None))
        for bad in [
            {"lat": 91, "lon": 0},
            {"lat": 0, "lon": -180.5},
            {"lat": float("nan"), "lon": 0},
            {"lat": float("inf"), "lon": 0},
            {"lat": True, "lon": 0},
            {"lat": "34.1", "lon": 0},
            {"lon": 0},
            {"lat": 0, "lon": 0, "label": "x" * 41},
            {"lat": 0, "lon": 0, "label": 5},
            "34.1,-118.2",
            [34.1, -118.2],
        ]:
            with self.assertRaises(Error, msg=bad):
                sun_location(bad)

    def test_bad_location_refused_before_connecting(self):
        from unittest.mock import Mock

        self.m.device = Mock()
        with self.assertRaises(Error):
            self.m.sun_set_location("test", {"lat": 95, "lon": 0}, lambda _: None)
        self.m.device.assert_not_called()

    def test_location_is_sent_rounded_and_kept_on_the_device(self):
        id = self.tv()
        self.d.replies["sun-location"] = SUN_REPLY
        out = self.m.sun_set_location(id, {"lat": 34.06, "lon": -118.24, "label": "Home"}, lambda _: None)
        self.assertEqual(
            ("content", "call", "--uri", PROVIDER, "--method", "sun-location", "--arg", "34.1,-118.2,Home"),
            self.d.commands[-1],
        )
        row = self.m.state["devices"][0]
        self.assertEqual({"lat": 34.1, "lon": -118.2, "label": "Home"}, row["sun"])
        self.assertEqual(("manager", "06:12", "17:01"), (out["source"], out["sunrise"], out["sunset"]))
        self.assertEqual("America/Los_Angeles", row["sun_status"]["zone"])
        # The preview now uses the saved place in the TV's zone.
        today = self.m.snapshot()["devices"][0]["sun_today"]
        self.assertEqual(("manager", "Home", "America/Los_Angeles"), (today["source"], today["label"], today["zone"]))
        self.assertIsInstance(today["sunrise"], int)
        # Clearing sends no --arg and forgets the place here too.
        estimate = dict(SUN, source="timezone", label="Los Angeles")
        self.d.replies["sun-location"] = ok(sun=estimate)
        self.m.sun_set_location(id, None, lambda _: None)
        self.assertEqual(("content", "call", "--uri", PROVIDER, "--method", "sun-location"), self.d.commands[-1])
        self.assertNotIn("sun", self.m.state["devices"][0])
        # The time-zone estimate needs the system's zone.tab (macOS and most Linux have one).
        estimate = "timezone" if sun.zone_place("America/Los_Angeles") else "none"
        self.assertEqual(estimate, self.m.snapshot()["devices"][0]["sun_today"]["source"])

    def test_check_keeps_a_place_set_elsewhere(self):
        # Set from another Mac, or saved on the TV while its reply was lost: Check brings it onto this Mac's record.
        id = self.tv()
        self.d.replies["status"] = ok(sun=dict(SUN, label="Home <b>", lat=51.5, lon=-0.1))
        self.m.sun_status(id, lambda _: None)
        self.assertEqual({"lat": 51.5, "lon": -0.1, "label": "Home b"}, self.m.state["devices"][0]["sun"])
        today = self.m.snapshot()["devices"][0]["sun_today"]
        self.assertEqual(("manager", "Home b"), (today["source"], today["label"]))

    def test_unexpected_sun_status_is_not_saved(self):
        id = self.tv()
        for bad in [
            ok(),
            ok(sun=[]),
            ok(sun=dict(SUN, source="gps")),
            ok(sun=dict(SUN, zone="America/Los Angeles; reboot")),
            ok(sun=dict(SUN, sunrise="6:12")),
            ok(sun=dict(SUN, polar="sideways")),
            ok(sun=dict(SUN, lat="34.1")),
            ok(sun=dict(SUN, lat=True)),
            ok(sun=dict(SUN, label="x" * 41)),
        ]:
            self.d.replies["status"] = bad
            with self.assertRaises(Error) as caught:
                self.m.sun_status(id, lambda _: None)
            self.assertEqual(NO_ANSWER, str(caught.exception))
        self.assertNotIn("sun_status", self.m.state["devices"][0])
        self.d.replies["status"] = ok(sun={"source": "none", "zone": "UTC", "sunrise": None, "sunset": None, "polar": None})
        self.assertEqual("none", self.m.sun_status(id, lambda _: None)["source"])
        self.assertEqual(
            {"zone": "UTC", "source": "none", "sunrise": None, "sunset": None, "polar": None},
            self.m.snapshot()["devices"][0]["sun_today"],
        )

    def test_check_reads_the_tv_time_zone(self):
        from unittest.mock import Mock

        self.m.add_device("Test", "tv.local")
        id = self.m.state["devices"][0]["id"]
        d = Mock()
        d.free.return_value = 1024**3
        d.shell.side_effect = lambda *a, **k: (
            "Europe/Oslo" if a == ("getprop", "persist.sys.timezone") else ""
        )
        self.m.device = lambda _: d
        self.assertEqual("Europe/Oslo", self.m.inspect(id, lambda _: None)["timezone"])
        # With no place set, the preview estimates from the zone's city.
        today = self.m.snapshot()["devices"][0]["sun_today"]
        estimate = "timezone" if sun.zone_place("Europe/Oslo") else "none"
        self.assertEqual((estimate, "Europe/Oslo"), (today["source"], today["zone"]))


    # ---------- Screensaver ----------

    def screensaver_tv(self):
        self.d = ScreensaverTV()
        self.m.device = lambda _: self.d
        return self.tv()

    def assertNothingRisky(self):
        """No restart of any kind, and the sleep timers are never written."""
        sent = [" ".join(c) for c in self.d.commands]
        self.assertFalse([c for c in sent if "reboot" in c or "shutdown" in c], sent)
        self.assertFalse([c for c in self.d.writes() if c[3] in ("sleep_timeout", "screen_off_timeout")])

    def test_screensaver_values_are_strict(self):
        for kind, good in [
            ("components", [DREAM, AMAZON, "null", "a.b/.C,d.e/f.G$Inner"]),
            ("int", ["0", "1", "-1", "2147483647", "-2147483648", "null"]),
        ]:
            for value in good:
                self.assertEqual(value, setting_value(kind, value))
        for kind, bad in [
            ("components", ["", "a.b", "a.b/", "a b/c", "a.b/c,", "a.b/c;reboot", "$(reboot)/x", "a/b\nc/d", None, 1]),
            ("int", ["", "1.5", "0x10", " 1", "1;reboot", "2147483648", "99999999999", "true", None, 1]),
        ]:
            for value in bad:
                with self.assertRaises(Error, msg=repr(value)):
                    setting_value(kind, value)
        self.assertEqual({"ambient": False, "stayAwake": False}, screensaver_options(None))
        self.assertEqual({"ambient": True, "stayAwake": False}, screensaver_options({"ambient": True}))
        for bad in [[], "ambient", {"ambient": "yes"}, {"ambient": 1}, {"reboot": True}]:
            with self.assertRaises(Error, msg=repr(bad)):
                screensaver_options(bad)

    def test_screensaver_check_only_reads(self):
        id = self.screensaver_tv()
        self.d.packages = ["com.wolf.tn"]
        messages = []
        out = self.m.screensaver_check(id, messages.append)
        self.assertEqual([], self.d.writes())
        self.assertNothingRisky()
        self.assertTrue(out["installed"])
        self.assertFalse(out["active"] or out["drift"])
        self.assertEqual("null", out["current"]["screensaver_activate_on_sleep"])
        self.assertEqual(2, len(out["blockers"]))
        self.assertTrue(any("Launcher Manager" in w for w in out["warnings"]))
        saved = self.m.state["devices"][0]["screensaver"]
        self.assertEqual(AMAZON, saved["current"]["screensaver_components"])
        self.assertNotIn("previous", saved)
        self.assertIn("Screensaver: " + AMAZON + ".", messages)

    def test_screensaver_set_saves_the_previous_values_once(self):
        id = self.screensaver_tv()
        before = {key: self.d.settings.get((ns, key), "null") for ns, key, _ in SCREENSAVER}
        messages = []
        out = self.m.screensaver_set(id, {"ambient": True, "stayAwake": True}, messages.append)
        self.assertTrue(out["active"])
        self.assertEqual([], out["blockers"])
        self.assertEqual(
            [
                ("settings", "put", "secure", "screensaver_components", DREAM),
                ("settings", "put", "secure", "screensaver_enabled", "1"),
                ("settings", "put", "secure", "screensaver_activate_on_sleep", "1"),
                ("settings", "put", "secure", "amazon_ambient_enabled", "0"),
                ("settings", "put", "global", "stay_on_while_plugged_in", "0"),
            ],
            self.d.writes(),
        )
        self.assertTrue(any("Restart the TV once" in m for m in messages))
        saved = self.m.state["devices"][0]["screensaver"]
        self.assertEqual(before, saved["previous"])
        self.assertEqual("null", saved["previous"]["screensaver_activate_on_sleep"])
        # Using it again (after something else changed the TV) keeps the values from before the first time.
        self.d.settings[("secure", "screensaver_components")] = "other.app/.Dream"
        self.d.settings[("secure", "sleep_timeout")] = "3600000"
        self.m.screensaver_set(id, None, lambda _: None)
        self.assertEqual(before, self.m.state["devices"][0]["screensaver"]["previous"])
        self.assertNothingRisky()

    def test_screensaver_set_leaves_ambient_and_stay_awake_unless_asked(self):
        id = self.screensaver_tv()
        out = self.m.screensaver_set(id, None, lambda _: None)
        self.assertEqual(
            ["screensaver_components", "screensaver_enabled", "screensaver_activate_on_sleep"],
            [c[3] for c in self.d.writes()],
        )
        self.assertEqual(2, len(out["blockers"]))
        self.assertEqual("1", self.d.settings[("secure", "amazon_ambient_enabled")])
        # Not there (an Android TV): nothing to turn off, whatever the options say.
        self.d.settings.pop(("secure", "amazon_ambient_enabled"))
        self.d.settings[("global", "stay_on_while_plugged_in")] = "0"
        self.d.commands.clear()
        self.m.screensaver_set(id, {"ambient": True, "stayAwake": True}, lambda _: None)
        self.assertEqual(3, len(self.d.writes()))

    def test_screensaver_restore_deletes_what_was_not_set(self):
        id = self.screensaver_tv()
        original = dict(self.d.settings)
        self.m.screensaver_set(id, {"ambient": True, "stayAwake": True}, lambda _: None)
        self.d.commands.clear()
        messages = []
        out = self.m.screensaver_restore(id, messages.append)
        self.assertEqual(
            [
                ("settings", "put", "secure", "screensaver_components", AMAZON),
                ("settings", "put", "secure", "screensaver_enabled", "1"),
                ("settings", "delete", "secure", "screensaver_activate_on_sleep"),
                ("settings", "put", "secure", "amazon_ambient_enabled", "1"),
                ("settings", "put", "global", "stay_on_while_plugged_in", "3"),
            ],
            self.d.writes(),
        )
        self.assertEqual(original, self.d.settings)
        self.assertFalse(out["active"])
        self.assertNotIn("previous", self.m.state["devices"][0]["screensaver"])
        self.assertTrue(any("Restart the TV once" in m for m in messages))
        with self.assertRaisesRegex(Error, "nothing to restore"):
            self.m.screensaver_restore(id, lambda _: None)
        self.assertNothingRisky()

    def test_screensaver_restore_refuses_after_a_change_elsewhere(self):
        id = self.screensaver_tv()
        self.m.screensaver_set(id, {"ambient": True}, lambda _: None)
        saved = json.loads(json.dumps(self.m.state["devices"][0]["screensaver"]["previous"]))
        # Another tool chose a third screensaver.
        self.d.settings[("secure", "screensaver_components")] = "other.app/.Dream"
        self.d.commands.clear()
        with self.assertRaisesRegex(Error, "changed elsewhere"):
            self.m.screensaver_restore(id, lambda _: None)
        self.assertEqual([], self.d.writes())
        row = self.m.state["devices"][0]["screensaver"]
        self.assertEqual(saved, row["previous"])
        self.assertTrue(row["drift"])
        self.assertEqual("0", self.d.settings[("secure", "amazon_ambient_enabled")])
        self.assertTrue(self.m.screensaver_check(id, lambda _: None)["drift"])
        self.assertNothingRisky()

    def test_screensaver_kept_off_by_the_tv_can_be_restored(self):
        # A TV that puts its own screensaver back at once: Use fails its read-back, after Ambient and Stay awake changed.
        id = self.screensaver_tv()
        original = dict(self.d.settings)
        shell = self.d.shell

        def reverting(*args, **kwargs):
            out = shell(*args, **kwargs)
            if args[:4] == ("settings", "put", "secure", "screensaver_components"):
                self.d.settings[("secure", "screensaver_components")] = AMAZON
            return out

        self.d.shell = reverting
        with self.assertRaisesRegex(Error, "did not keep GlassOS scenery") as caught:
            self.m.screensaver_set(id, {"ambient": True, "stayAwake": True}, lambda _: None)
        self.assertIn("Restore previous screensaver", str(caught.exception))
        row = self.m.state["devices"][0]["screensaver"]
        self.assertEqual(original[("secure", "amazon_ambient_enabled")], row["previous"]["amazon_ambient_enabled"])
        self.assertIn("stay_on_while_plugged_in", row["changed"])
        self.assertEqual("0", self.d.settings[("secure", "amazon_ambient_enabled")])
        # Nothing else chose a screensaver: no false warning, and Restore puts back what changed.
        self.assertFalse(row["drift"])
        self.assertNotIn("changed elsewhere", " ".join(row["warnings"]))
        messages = []
        self.m.screensaver_restore(id, messages.append)
        self.assertEqual(original, self.d.settings)
        self.assertNotIn("previous", self.m.state["devices"][0]["screensaver"])
        self.assertTrue(any("Restart the TV once" in m for m in messages))
        self.assertNothingRisky()

    def test_screensaver_restore_keeps_the_saved_set_until_all_is_back(self):
        id = self.screensaver_tv()
        self.m.screensaver_set(id, {"ambient": True, "stayAwake": True}, lambda _: None)
        saved = json.loads(json.dumps(self.m.state["devices"][0]["screensaver"]))
        shell = self.d.shell

        def stubborn(*args, **kwargs):
            # The TV ignores one setting being put back.
            if args[:4] == ("settings", "put", "global", "stay_on_while_plugged_in"):
                self.d.commands.append(args)
                return ""
            return shell(*args, **kwargs)

        self.d.shell = stubborn
        with self.assertRaisesRegex(Error, "did not take back every saved setting"):
            self.m.screensaver_restore(id, lambda _: None)
        row = self.m.state["devices"][0]["screensaver"]
        self.assertEqual((saved["previous"], saved["changed"]), (row["previous"], row["changed"]))
        self.assertEqual("0", self.d.settings[("global", "stay_on_while_plugged_in")])
        # Once the TV takes it, Restore finishes and forgets the saved set.
        self.d.shell = shell
        self.m.screensaver_restore(id, lambda _: None)
        self.assertNotIn("previous", self.m.state["devices"][0]["screensaver"])
        self.assertEqual("3", self.d.settings[("global", "stay_on_while_plugged_in")])

    def test_restore_mentions_a_restart_only_when_ambient_comes_back_on(self):
        id = self.screensaver_tv()
        self.d.settings[("secure", "amazon_ambient_enabled")] = "0"
        self.m.screensaver_set(id, {"ambient": True}, lambda _: None)
        # Ambient turned on later, then off again by a second Use: Restore puts back the first (off).
        self.d.settings[("secure", "amazon_ambient_enabled")] = "1"
        self.m.screensaver_set(id, {"ambient": True}, lambda _: None)
        self.assertIn("amazon_ambient_enabled", self.m.state["devices"][0]["screensaver"]["changed"])
        messages = []
        self.m.screensaver_restore(id, messages.append)
        self.assertEqual("0", self.d.settings[("secure", "amazon_ambient_enabled")])
        self.assertFalse(any("Restart the TV once" in m for m in messages), messages)

    def test_screensaver_needs_glassos_on_the_tv(self):
        id = self.screensaver_tv()
        self.d.dreams = [AMAZON]
        with self.assertRaises(Error) as caught:
            self.m.screensaver_set(id, {"ambient": True, "stayAwake": True}, lambda _: None)
        self.assertIn(UPDATE_GLASSOS, str(caught.exception))
        self.assertEqual([], self.d.writes())
        row = self.m.state["devices"][0]["screensaver"]
        self.assertFalse(row["installed"])
        self.assertNotIn("previous", row)

    def test_older_tvs_are_asked_another_way(self):
        id = self.screensaver_tv()
        shell = self.d.shell

        def old_android(*args, **kwargs):
            if args[0] in ("cmd", "pm") and "query-services" in args:
                self.d.commands.append(args)
                raise Error("/system/bin/sh: cmd: not found")
            if args[:2] == ("dumpsys", "package"):
                return "  android.service.dreams.DreamService:\n    1a2b3c " + DREAM + " filter 4d5e6f"
            return shell(*args, **kwargs)

        self.d.shell = old_android
        self.assertTrue(self.m.screensaver_check(id, lambda _: None)["installed"])
        self.assertEqual(2, sum("query-services" in c for c in self.d.commands))

    def test_unexpected_values_change_nothing(self):
        id = self.screensaver_tv()
        self.d.settings[("secure", "screensaver_components")] = "x.y/.Z;reboot"
        with self.assertRaisesRegex(Error, "does not recognize"):
            self.m.screensaver_set(id, None, lambda _: None)
        self.assertEqual([], self.d.writes())
        self.assertNotIn("screensaver", self.m.state["devices"][0])
        # A saved value edited on this Mac is refused before the TV is reached.
        from unittest.mock import Mock

        self.d.settings[("secure", "screensaver_components")] = AMAZON
        self.m.screensaver_set(id, None, lambda _: None)
        self.m.state["devices"][0]["screensaver"]["previous"]["screensaver_components"] = "x.y/.Z $(reboot)"
        self.m.device = Mock()
        with self.assertRaises(Error):
            self.m.screensaver_restore(id, lambda _: None)
        with self.assertRaises(Error):
            self.m.screensaver_set(id, {"ambient": "yes"}, lambda _: None)
        self.m.device.assert_not_called()


# An ipinfo.io reply as it arrives (same keys as a live one; documentation address and made-up host).
IP = "203.0.113.77"
IPINFO = {
    "ip": IP, "hostname": "host-203-0-113-77.example.net", "city": "Seattle", "region": "Washington", "country": "US",
    "loc": "47.6062,-122.3321", "org": "AS64500 Example Net", "postal": "98101", "timezone": "America/Los_Angeles",
    "readme": "https://ipinfo.io/missingauth",
}


def ipinfo(**fields):
    return json.dumps({**IPINFO, **fields}).encode()


class FakeResponse:
    def __init__(self, body, length=True):
        self.body = body
        self.headers = {"Content-Length": str(len(body))} if length else {}

    def read(self, n):
        out, self.body = self.body[:n], self.body[n:]
        return out

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class SunLookupTests(unittest.TestCase):
    """The internet address lookup: ipinfo.io only (mocked here), never a TV, and only the rounded place comes back."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.m = Manager(Path(self.tmp.name) / "data")
        self.m.add_device("Living room", "tv.local")
        self.m.device = Mock(side_effect=AssertionError("the lookup never reaches a TV"))

    def tearDown(self):
        self.tmp.cleanup()

    def test_good_reply(self):
        self.assertEqual({"lat": 47.6, "lon": -122.3, "label": "Seattle, Washington"}, ip_place(ipinfo()))
        log = []
        with patch("catalog.fetch", return_value=ipinfo()) as fetch:
            self.assertEqual({"lat": 47.6, "lon": -122.3, "label": "Seattle, Washington"}, self.m.sun_lookup(log.append))
        fetch.assert_called_once_with(IP_LOOKUP, limit=IP_LOOKUP_LIMIT, timeout=8)
        self.assertEqual("Found: Seattle, Washington (47.6, -122.3) — check this is right, then Save to TV.", log[-1])
        self.m.device.assert_not_called()

    def test_rounded_half_up_as_the_tv(self):
        for loc, lat, lon in [
            ("34.05,-118.25", 34.1, -118.2),
            ("-33.86,151.25", -33.9, 151.3),
            ("-0.04,-0.04", 0.0, 0.0),
            ("90,-180", 90.0, -180.0),
            ("12,3", 12.0, 3.0),
        ]:
            place = ip_place(ipinfo(loc=loc))
            self.assertEqual((lat, lon), (place["lat"], place["lon"]), loc)
            self.assertEqual((sun.tenth(float(loc.split(",")[0])), sun.tenth(float(loc.split(",")[1]))), (lat, lon))
        self.assertEqual("0.0,0.0", "{lat},{lon}".format(**ip_place(ipinfo(loc="-0.04,-0.04"))))

    def test_label_from_city_and_region_sanitised(self):
        for fields, label in [
            ({"city": "Seattle <b>", "region": "Washington!"}, "Seattle b, Washington"),
            ({"city": "  St.   John's ", "region": "Newfoundland and Labrador"}, "St. John's, Newfoundland and Labrador"),
            ({"city": "Zürich", "region": "Zurich"}, "Zürich, Zurich"),
            ({"city": "Paris", "region": None, "country": "FR"}, "Paris, FR"),
            ({"city": "Paris", "region": "", "country": "FR"}, "Paris, FR"),
            ({"city": "Singapore", "region": "Singapore", "country": "SG"}, "Singapore, SG"),
            ({"city": 5, "region": "Washington"}, "Washington"),
            ({"city": None, "region": None, "country": None}, ""),
            ({"city": "Llanfairpwllgwyngyll", "region": "Wales and the Isle of Anglesey"}, "Llanfairpwllgwyngyll"),
            ({"city": "x" * 41}, ""),
            ({"city": "<script>", "region": "&&"}, "script, US"),
        ]:
            place = ip_place(ipinfo(**fields))
            self.assertEqual(label, place["label"], fields)
            self.assertLessEqual(len(place["label"]), 40)

    def test_missing_or_bad_coordinates_refused(self):
        bad = [
            json.dumps({k: v for k, v in IPINFO.items() if k != "loc"}).encode(),
            ipinfo(loc=None), ipinfo(loc=[47.6, -122.3]), ipinfo(loc=47.6), ipinfo(loc=""), ipinfo(loc="47.6"),
            ipinfo(loc="47.6,-122.3,5"), ipinfo(loc=" 47.6,-122.3"), ipinfo(loc="47.6, -122.3"), ipinfo(loc="NaN,0"),
            ipinfo(loc="inf,0"), ipinfo(loc="1e1,2"), ipinfo(loc="47.6,-122.3\n"), ipinfo(loc="٤٧.٦,٠"),
            json.dumps({"ip": IP, "bogon": True}).encode(),
        ]
        for data in bad:
            with self.assertRaises((Error, ValueError), msg=data):
                ip_place(data)

    def test_out_of_range_refused(self):
        for loc in ["90.1,0", "-90.05,0", "0,180.01", "0,-180.5", "999,0", "0,999"]:
            with self.assertRaises(Error, msg=loc):
                ip_place(ipinfo(loc=loc))

    def test_not_json_or_not_an_object_refused(self):
        for data in [b"", b"<html>Rate limited</html>", b"\xff\xfe", b"[]", b"null", b'"47.6,-122.3"', b"{"]:
            with self.assertRaises(ValueError, msg=data):
                ip_place(data)

    def test_oversize_reply_refused(self):
        data = ipinfo(readme="x" * IP_LOOKUP_LIMIT)
        with self.assertRaises(ValueError):
            ip_place(data)
        # Through the real HTTPS client: refused by Content-Length, or while reading when there is none.
        for response in [FakeResponse(data), FakeResponse(data, length=False)]:
            opener = Mock()
            opener.open.return_value = response
            with patch("catalog.socket.getaddrinfo", return_value=[(2, 1, 6, "", ("34.117.59.81", 443))]), patch(
                "catalog.build_opener", return_value=opener
            ):
                with self.assertRaisesRegex(Error, "^" + IP_LOOKUP_FAILED + "$"):
                    self.m.sun_lookup(lambda _: None)
            self.assertEqual(8, opener.open.call_args.kwargs["timeout"])
            self.assertEqual(IP_LOOKUP, opener.open.call_args.args[0].full_url)

    def test_network_failures_give_one_fixed_sentence(self):
        import socket
        from urllib.error import HTTPError, URLError

        for failure in [
            socket.timeout("timed out"),
            TimeoutError(),
            URLError("[Errno 8] nodename nor servname provided"),
            HTTPError(IP_LOOKUP, 429, "Too Many Requests " + IP, {}, None),
            ValueError("Catalog downloads must use public Internet addresses."),
            ConnectionResetError(),
        ]:
            log = []
            with patch("catalog.fetch", side_effect=failure):
                with self.assertRaises(Error, msg=repr(failure)) as caught:
                    self.m.sun_lookup(log.append)
            self.assertEqual(IP_LOOKUP_FAILED, str(caught.exception))
            self.assertIsNone(caught.exception.__cause__)
            self.assertTrue(caught.exception.__suppress_context__)
        with patch("catalog.fetch", return_value=b"<html>"):
            with self.assertRaisesRegex(Error, "^" + IP_LOOKUP_FAILED + "$"):
                self.m.sun_lookup(lambda _: None)
        self.m.device.assert_not_called()

    def test_address_never_kept_or_returned(self):
        before = json.dumps(self.m.state, sort_keys=True)
        with patch("catalog.fetch", return_value=ipinfo()):
            self.m.job("Look up place from internet address", self.m.sun_lookup)
            for _ in range(400):
                if not self.m.work_lock.locked():
                    break
                time.sleep(0.005)
        job = self.m.jobs[-1]
        self.assertEqual("done", job["status"], job.get("error"))
        self.assertEqual({"lat", "lon", "label"}, set(job["result"]))
        # Nothing saved: not on the device record (the lookup only fills the form) or anywhere else in state.json.
        self.assertEqual(before, json.dumps(self.m.state, sort_keys=True))
        self.assertNotIn("sun", self.m.state["devices"][0])
        texts = [json.dumps(self.m.jobs), json.dumps(self.m.snapshot())]
        texts += [p.read_text(errors="replace") for p in Path(self.m.data).rglob("*") if p.is_file()]
        for text in texts:
            for secret in [IP, "203-0-113-77", "AS64500", "98101"]:
                self.assertNotIn(secret, text)
        # A failed lookup keeps nothing either.
        with patch("catalog.fetch", side_effect=OSError("connect to " + IP + " failed")):
            self.m.job("Look up place from internet address", self.m.sun_lookup)
            for _ in range(400):
                if not self.m.work_lock.locked():
                    break
                time.sleep(0.005)
        self.assertEqual(("error", IP_LOOKUP_FAILED), (self.m.jobs[-1]["status"], self.m.jobs[-1]["error"]))
        self.assertNotIn(IP, json.dumps(self.m.snapshot()))
        self.m.device.assert_not_called()


if __name__ == "__main__":
    unittest.main()
