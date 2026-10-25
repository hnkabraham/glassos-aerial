import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core import (
    Device,
    Error,
    Manager,
    address,
    atomic_json,
    digest,
    schedule,
    REMOTE,
    RESERVE,
)


class MemoryDevice(Device):
    def __init__(self):
        self.files = {}
        self.commands = []
        self.available = 8 * 1024**3
        self.bad_push = False
        self.interrupt = False

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
        if args[0] == "mv":
            self.files[args[-1]] = self.files.pop(args[-2])
        elif args[0] == "rm":
            self.files.pop(args[-1], None)
        return ""

    def start(self, action=None):
        self.commands.append(("start", action))
        return ""


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


if __name__ == "__main__":
    unittest.main()
