import http.client
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core import Manager
from server import Server


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

    def test_upload_size_limit_before_reading_body(self):
        code, _ = self.request(
            "/api/upload", "POST", b"", {"Content-Length": str(5 * 1024**3)}
        )
        self.assertEqual(400, code)
        self.assertEqual([], list((self.manager.data / "uploads").iterdir()))


if __name__ == "__main__":
    unittest.main()
