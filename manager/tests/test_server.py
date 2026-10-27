import http.client
import json
import sys
import tempfile
import threading
import time
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core import DREAM, Manager
from server import Server
import sun


class FakeTV:
    """Stands in for a connected TV: GlassOS's provider answers, nothing is run."""

    def __init__(self):
        self.calls = []
        self.codes = []

    def glass_call(self, method, arg=None):
        self.calls.append(method)
        if arg is not None:
            self.codes.append(arg)
        lock = {"code": True, "locked": 1, "installedLocked": 1, "guard": True, "usage": True, "overlay": True}
        sun = {"source": "manager" if arg else "timezone", "zone": "America/Los_Angeles", "label": "Home",
               "lat": 34.1, "lon": -118.2, "sunrise": "07:12", "sunset": "18:05", "polar": None}
        if method == "status":
            return {"ok": True, "lock": lock, "sun": sun}
        if method == "airplay-status":
            return {"ok": True, "airplay": {
                "supported": True, "on": True, "state": "RUNNING", "error": None, "name": "Den", "port": 7000,
                "connections": 0, "mode": "IDLE", "activityVisible": False, "pinShown": False, "pid": 4321,
                "restarts": 0, "gaveUp": False, "settings": {"pin": False, "res": "1080p", "sync_volume": True}}}
        return {"ok": True, "sun": sun} if method == "sun-location" else {"ok": True}

    def shell(self, *args, **kwargs):
        self.calls.append(args[0])
        return ""


class ScreensaverFakeTV(FakeTV):
    """Answers `settings` and the dream query like a TV with GlassOS 1.6.0 on it."""

    def __init__(self):
        super().__init__()
        self.settings = {("secure", "screensaver_components"): "com.example/.Dream", ("global", "stay_on_while_plugged_in"): "1"}

    def shell(self, *args, **kwargs):
        self.calls.append(" ".join(args[:2]))
        if args[0] == "settings":
            key = args[2:4]
            if args[1] == "get":
                return self.settings.get(key, "null")
            if args[1] == "put":
                self.settings[key] = args[4]
            elif args[1] == "delete":
                self.settings.pop(key, None)
            return ""
        if args[:2] == ("cmd", "package"):
            return DREAM
        return ""


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.manager = Manager(Path(self.tmp.name))
        self.server = Server(self.manager)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(
        self, path="/api/state", method="GET", body=None, headers=None, auth=True
    ):
        h = {"X-GlassOS-Token": self.server.token} if auth else {}
        h.update(headers or {})
        c = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        c.request(method, path, body=body, headers=h)
        r = c.getresponse()
        data = r.read()
        status = r.status
        c.close()
        return status, data

    def test_initial_navigation_from_another_tab_is_allowed(self):
        self.assertEqual(
            200,
            self.request("/", headers={"Sec-Fetch-Site": "cross-site"}, auth=False)[0],
        )

    def test_api_requires_session_token(self):
        self.assertEqual(403, self.request(auth=False)[0])
        self.assertEqual(200, self.request()[0])

    def test_host_and_origin_checked(self):
        for headers in [
            {"Host": "evil.example"},
            {"Origin": "https://evil.example"},
            {"Sec-Fetch-Site": "cross-site"},
        ]:
            self.assertEqual(403, self.request(headers=headers)[0])

    def test_cross_origin_mutation_refused(self):
        code, _ = self.request(
            "/api/device",
            "POST",
            json.dumps({"name": "x", "address": "tv.local"}),
            {"Content-Type": "application/json", "Origin": "https://evil.example"},
        )
        self.assertEqual(400, code)
        self.assertEqual([], self.manager.state["devices"])

    def test_no_filesystem_route(self):
        for path in ["/../state.json", "/state.json", "/api/poster/../../state.json"]:
            self.assertEqual(404, self.request(path)[0])

    def test_saved_device_and_invalid_action(self):
        code, _ = self.request(
            "/api/device",
            "POST",
            json.dumps({"name": "Test", "address": "tv.local"}),
            {"Content-Type": "application/json"},
        )
        self.assertEqual(200, code)
        self.assertEqual(1, len(self.manager.state["devices"]))
        code, _ = self.request(
            "/api/action",
            "POST",
            json.dumps({"action": "reboot"}),
            {"Content-Type": "application/json"},
        )
        self.assertEqual(400, code)

    def test_app_lock_actions_need_the_session(self):
        tv = FakeTV()
        self.manager.add_device("Test", "tv.local")
        id = self.manager.state["devices"][0]["id"]
        self.manager.device = lambda _: tv
        actions = [
            ("applock-status", {}),
            ("applock-code", {"code": "WPFR"}),
            ("applock-off", {}),
            ("applock-guard", {}),
        ]
        refusals = [
            ({"Content-Type": "application/json"}, False),
            ({"Content-Type": "application/json", "Origin": "https://evil.example"}, True),
            ({"Content-Type": "application/json", "Sec-Fetch-Site": "cross-site"}, True),
        ]
        for action, extra in actions:
            body = json.dumps({"action": action, "device": id, **extra})
            # The POST path refuses a missing token or a foreign origin with 400, like every other POST.
            for headers, auth in refusals:
                self.assertEqual(400, self.request("/api/action", "POST", body, headers, auth)[0])
            self.assertEqual([], self.manager.jobs)
        for action, extra in actions:
            body = json.dumps({"action": action, "device": id, **extra})
            code, data = self.request(
                "/api/action", "POST", body, {"Content-Type": "application/json"}
            )
            self.assertEqual(202, code)
            self.assertIn("job", json.loads(data))
            self.wait()
            self.assertEqual("done", self.manager.jobs[-1]["status"], self.manager.jobs[-1].get("error"))
        self.assertEqual(
            ["status", "lock-set-code", "status", "lock-off", "status", "appops", "status"], tv.calls
        )
        self.assertEqual(["WPFR"], tv.codes)
        self.assertNotIn("WPFR", self.request()[1].decode())

    def test_mirroring_check_is_read_only(self):
        tv = FakeTV()
        self.manager.add_device("Test", "tv.local")
        id = self.manager.state["devices"][0]["id"]
        self.manager.device = lambda _: tv
        body = json.dumps({"action": "mirroring-status", "device": id})
        # Like every POST: no session, no job.
        self.assertEqual(400, self.request("/api/action", "POST", body, {"Content-Type": "application/json"}, False)[0])
        self.assertEqual([], self.manager.jobs)
        code, _ = self.request("/api/action", "POST", body, {"Content-Type": "application/json"})
        self.assertEqual(202, code)
        self.wait()
        job = self.manager.jobs[-1]
        self.assertEqual(("Check Screen Mirroring", "done"), (job["title"], job["status"]), job.get("error"))
        self.assertEqual(["airplay-status"], tv.calls)
        self.assertEqual([], tv.codes)
        state = json.loads(self.request()[1])
        m = state["devices"][0]["mirroring"]
        self.assertEqual(("RUNNING", "Den", True), (m["state"], m["name"], m["on"]))
        self.assertNotIn("4321", json.dumps(state))
        # There is no way to start, stop or change it from here.
        for action in ("mirroring-start", "mirroring-stop", "mirroring-set", "airplay-start"):
            body = json.dumps({"action": action, "device": id})
            self.assertEqual(400, self.request("/api/action", "POST", body, {"Content-Type": "application/json"})[0])

    def test_sun_actions(self):
        tv = FakeTV()
        self.manager.add_device("Test", "tv.local")
        id = self.manager.state["devices"][0]["id"]
        self.manager.device = lambda _: tv
        place = {"lat": 47.61, "lon": -122.33, "label": "Seattle"}
        for action, extra, title in [
            ("sun-status", {}, "Check sun times"),
            ("sun-location", {"location": place}, "Save sun times place"),
            ("sun-location", {"location": None}, "Use time zone estimate"),
        ]:
            body = json.dumps({"action": action, "device": id, **extra})
            self.assertEqual(400, self.request("/api/action", "POST", body, {"Content-Type": "application/json"}, False)[0])
            code, _ = self.request("/api/action", "POST", body, {"Content-Type": "application/json"})
            self.assertEqual(202, code)
            self.wait()
            job = self.manager.jobs[-1]
            self.assertEqual((title, "done"), (job["title"], job["status"]), job.get("error"))
        self.assertEqual(["status", "sun-location", "sun-location"], tv.calls)
        self.assertEqual(["47.6,-122.3,Seattle"], tv.codes)
        self.assertNotIn("sun", self.manager.state["devices"][0])
        # A bad place fails in the job, before the TV is reached.
        body = json.dumps({"action": "sun-location", "device": id, "location": {"lat": 120, "lon": 0}})
        self.assertEqual(202, self.request("/api/action", "POST", body, {"Content-Type": "application/json"})[0])
        self.wait()
        self.assertEqual("error", self.manager.jobs[-1]["status"])
        self.assertEqual(3, len(tv.calls))

    def test_sun_lookup_action(self):
        tv = FakeTV()
        self.manager.add_device("Test", "tv.local")
        self.manager.device = lambda _: tv
        reply = json.dumps({"ip": "203.0.113.77", "hostname": "host-203-0-113-77.example.net", "city": "Seattle",
                            "region": "Washington", "country": "US", "loc": "47.6062,-122.3321"}).encode()
        body = json.dumps({"action": "sun-lookup"})
        with patch("catalog.fetch", return_value=reply) as fetch:
            for headers, auth in [
                ({"Content-Type": "application/json"}, False),
                ({"Content-Type": "application/json", "Origin": "https://evil.example"}, True),
                ({"Content-Type": "application/json", "Sec-Fetch-Site": "cross-site"}, True),
            ]:
                self.assertEqual(400, self.request("/api/action", "POST", body, headers, auth)[0])
            self.assertEqual([], self.manager.jobs)
            fetch.assert_not_called()
            # No device is needed: it asks the internet, never the TV.
            code, data = self.request("/api/action", "POST", body, {"Content-Type": "application/json"})
            self.assertEqual(202, code)
            self.wait()
        job = self.manager.jobs[-1]
        self.assertEqual(("Look up place from internet address", "done"), (job["title"], job["status"]), job.get("error"))
        self.assertEqual({"lat": 47.6, "lon": -122.3, "label": "Seattle, Washington"}, job["result"])
        self.assertEqual([], tv.calls)
        self.assertNotIn("sun", self.manager.state["devices"][0])
        state = self.request()[1].decode()
        self.assertIn("Seattle, Washington", state)
        for text in [data.decode(), state, (Path(self.tmp.name) / "state.json").read_text()]:
            self.assertNotIn("203.0.113.77", text)
            self.assertNotIn("203-0-113-77", text)

    def test_screensaver_actions(self):
        tv = ScreensaverFakeTV()
        self.manager.add_device("Test", "tv.local")
        id = self.manager.state["devices"][0]["id"]
        self.manager.device = lambda _: tv
        for action, extra, title in [
            ("screensaver-check", {}, "Check screensaver"),
            ("screensaver-set", {"options": {"ambient": True, "stayAwake": True}}, "Use GlassOS scenery"),
            ("screensaver-restore", {}, "Restore previous screensaver"),
        ]:
            body = json.dumps({"action": action, "device": id, **extra})
            for headers, auth in [
                ({"Content-Type": "application/json"}, False),
                ({"Content-Type": "application/json", "Origin": "https://evil.example"}, True),
            ]:
                self.assertEqual(400, self.request("/api/action", "POST", body, headers, auth)[0])
            code, _ = self.request("/api/action", "POST", body, {"Content-Type": "application/json"})
            self.assertEqual(202, code)
            self.wait()
            job = self.manager.jobs[-1]
            self.assertEqual((title, "done"), (job["title"], job["status"]), job.get("error"))
        self.assertEqual(3, len(self.manager.jobs))
        # Set turned Stay awake off and Restore put it back, with GlassOS's screensaver gone again.
        self.assertEqual("1", tv.settings[("global", "stay_on_while_plugged_in")])
        self.assertEqual("com.example/.Dream", tv.settings[("secure", "screensaver_components")])
        self.assertIn("settings put", tv.calls)
        self.assertFalse([c for c in tv.calls if "reboot" in c])
        # Options that are not booleans fail in the job, before the TV is reached.
        tv.calls.clear()
        body = json.dumps({"action": "screensaver-set", "device": id, "options": {"ambient": "yes"}})
        self.assertEqual(202, self.request("/api/action", "POST", body, {"Content-Type": "application/json"})[0])
        self.wait()
        self.assertEqual("error", self.manager.jobs[-1]["status"])
        self.assertEqual([], tv.calls)

    def test_schedule_save_uses_the_selected_tv(self):
        self.manager.add_device("Test", "tv.local")
        row = self.manager.state["devices"][0]
        row["health"] = {"timezone": "America/Los_Angeles"}
        rows = [{"anchor": "sunset", "offset": 30, "collection": "space"}, {"start": 420, "collection": "coast"}]
        with patch("sun.today", return_value=date(2026, 6, 21)):
            code, _ = self.request(
                "/api/configure", "POST", json.dumps({"schedule": rows, "device": row["id"]}),
                {"Content-Type": "application/json"},
            )
        self.assertEqual(200, code)
        saved = self.manager.state["schedule"]
        self.assertEqual(["start", "collection"], list(saved[0]))
        self.assertEqual(("sunset", 30), (saved[1]["anchor"], saved[1]["offset"]))
        # That TV's zone (Los Angeles: sunset 20:08 + 30), not 18:00 + 30; the latter without the system's zone.tab.
        self.assertEqual(1238 if sun.zone_place("America/Los_Angeles") else 1110, saved[1]["start"])
        code, data = self.request(
            "/api/configure", "POST", json.dumps({"schedule": [dict(rows[0], offset=7)]}),
            {"Content-Type": "application/json"},
        )
        self.assertEqual(400, code)
        self.assertIn("steps of 5", json.loads(data)["error"])

    def wait(self):
        for _ in range(400):
            if not self.manager.work_lock.locked():
                return
            time.sleep(0.005)

    def test_upload_size_limit_before_reading_body(self):
        code, _ = self.request(
            "/api/upload", "POST", b"", {"Content-Length": str(5 * 1024**3)}
        )
        self.assertEqual(400, code)
        self.assertEqual([], list((self.manager.data / "uploads").iterdir()))


if __name__ == "__main__":
    unittest.main()
