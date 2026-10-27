"""Tests of probe.py against a local stand-in for the receiver: python3 -m unittest discover -s tools/airplay"""
import io
import plistlib
import socket
import threading
import unittest
from contextlib import redirect_stdout

import probe

INFO = {"name": "Den", "deviceID": "AA:BB:CC:DD:EE:FF", "features": 0x5A7FFEE6, "model": "AppleTV3,2",
        "pk": bytes(range(32)), "sourceVersion": "220.68", "macAddress": "AA:BB:CC:DD:EE:FF"}
PUBLIC = "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"


class FakeReceiver:
    """Answers like UxPlay: /info only to RTSP/1.0 with CSeq, a binary plist body, and OPTIONS with Public."""

    def __init__(self, info=INFO, public=PUBLIC):
        self.info, self.public, self.requests = info, public, []
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(4)
        self.port = self.srv.getsockname()[1]
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self):
        while True:
            try:
                c, _ = self.srv.accept()
            except OSError:
                return
            with c:
                data = b""
                while b"\r\n\r\n" not in data:
                    chunk = c.recv(4096)
                    if not chunk:
                        break
                    data += chunk
                lines = data.decode("ascii").split("\r\n")
                self.requests.append(lines[0])
                cseq = next((l.split(":", 1)[1].strip() for l in lines if l.lower().startswith("cseq:")), None)
                if cseq is None or not lines[0].endswith("RTSP/1.0"):
                    continue  # UxPlay drops these without an answer
                if lines[0].startswith("GET /info"):
                    body = plistlib.dumps(self.info, fmt=plistlib.FMT_BINARY)
                    c.sendall(f"RTSP/1.0 200 OK\r\nContent-Type: application/x-apple-binary-plist\r\n"
                              f"Content-Length: {len(body)}\r\nCSeq: {cseq}\r\n\r\n".encode() + body)
                else:
                    c.sendall(f"RTSP/1.0 200 OK\r\nPublic: {self.public}\r\nCSeq: {cseq}\r\n\r\n".encode())

    def close(self):
        self.srv.close()


class ProbeTest(unittest.TestCase):
    def test_a_receiver_that_answers_passes(self):
        r = FakeReceiver()
        try:
            result = probe.probe("127.0.0.1", r.port, 3)
        finally:
            r.close()
        self.assertTrue(result["ok"])
        self.assertEqual(["GET /info RTSP/1.0", "OPTIONS * RTSP/1.0"], r.requests)
        self.assertEqual("Den", result["info"]["name"])
        self.assertEqual(bytes(range(32)).hex(), result["info"]["pk"])
        self.assertEqual([], result["missing"])
        self.assertIn("SETUP", result["public"])

    def test_a_missing_key_fails(self):
        info = dict(INFO)
        del info["pk"]
        r = FakeReceiver(info=info)
        try:
            out = io.StringIO()
            with redirect_stdout(out):
                rc = probe.main(["127.0.0.1", str(r.port), "--timeout", "3"])
        finally:
            r.close()
        self.assertEqual(1, rc)
        self.assertIn("MISSING: pk", out.getvalue())

    def test_nothing_listening_fails_cleanly(self):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        out = io.StringIO()
        with redirect_stdout(out):
            rc = probe.main(["--json", "127.0.0.1", str(port), "--timeout", "1"])
        self.assertEqual(1, rc)
        self.assertIn('"ok": false', out.getvalue())


if __name__ == "__main__":
    unittest.main()
