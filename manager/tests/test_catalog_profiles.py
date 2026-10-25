import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import catalog
import profiles
from core import Manager, Error


class CatalogTests(unittest.TestCase):
    def test_prefers_h264_and_skips_hdr_duplicates_and_unsafe_urls(self):
        asset = {"title": "Coast", "scene": "coast", "url-1080-H264": "https://example.com/coast.mov", "url-4K-SDR": "https://example.com/4k.mov"}
        doc = {"assets": [asset, asset, {"url-4K-HDR": "https://example.com/hdr.mov"}, {"url-1080-H264": "file:///etc/passwd"}]}
        result = catalog.parse(json.dumps(doc).encode(), catalog.COMMUNITY)
        self.assertEqual(1, len(result))
        self.assertEqual("1080-H264", result[0]["quality"])
        self.assertEqual("coast", result[0]["collection"])
        self.assertEqual(result, catalog.parse(json.dumps(doc).encode(), catalog.COMMUNITY))

    def test_untrusted_feed_shape_and_size(self):
        for doc in [None, {}, {"assets": {}}, {"assets": [0] * 5001}]:
            with self.assertRaises(ValueError):
                catalog.parse(json.dumps(doc).encode(), catalog.COMMUNITY)
        with self.assertRaises(ValueError):
            catalog.parse(b" " * (5 * 1024**2 + 1), catalog.COMMUNITY)

    def test_rejects_private_network_and_credentials(self):
        for url in ["http://example.com/x", "https://u:p@example.com/x", "https://example.com:444/x", "https://example.com/\nfile"]:
            with self.assertRaises(ValueError):
                catalog.https_url(url)
        for ip in ["127.0.0.1", "192.168.1.1", "169.254.169.254", "::1"]:
            with patch("catalog.socket.getaddrinfo", return_value=[(2, 1, 6, "", (ip, 443))]):
                with self.assertRaises(ValueError):
                    catalog.public_url("https://example.com/x")

    def test_failed_feed_keeps_previous_catalog(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = Manager(tmp)
            m.state["catalog"] = {"entries": [{"id": "old"}]}
            with patch("catalog.fetch", return_value=b"not json"):
                with self.assertRaises(ValueError):
                    m.load_catalog(None, lambda _: None)
            self.assertEqual("old", m.state["catalog"]["entries"][0]["id"])

    def test_interrupted_download_removes_staging_and_keeps_library(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = Manager(tmp)
            m.state["catalog"] = {"entries": [{"id": "one", "title": "A coast", "quality": "1080-H264", "url": "https://example.com/v.mov"}]}
            def interrupt(url, source, *args):
                source.write_bytes(b"partial")
                raise ValueError("interrupted")
            with patch("catalog.fetch", side_effect=interrupt):
                with self.assertRaisesRegex(ValueError, "interrupted"):
                    m.import_catalog("one", 90, "coast", lambda _: None)
            self.assertEqual([], list((Path(tmp) / "uploads").iterdir()))
            self.assertEqual([], m.state["scenes"])

    def test_bad_icon_package_cannot_escape_owned_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = Manager(tmp)
            source = Path(tmp) / "uploads/test.upload"
            source.write_bytes(b"image")
            with self.assertRaises(Error):
                m.import_icon(source, "../outside", lambda _: None)
            self.assertFalse(source.exists())
            self.assertEqual([], list((Path(tmp) / "icons").iterdir()))


class ProfileTests(unittest.TestCase):
    def test_configuration_diff_excludes_time_and_free_space(self):
        before = {"checked_at": 1, "free_bytes": 123, "version": "1.4", "home": "custom", "privacy": {"usage": "0"}, "disabled": {"metrics": True}}
        after = dict(before, checked_at=2, free_bytes=100, version="1.5")
        changes = profiles.compare(before, after)
        self.assertEqual(["GlassOS"], [c["label"] for c in changes])
        self.assertFalse(changes[0]["attention"])
        after.update(privacy={"usage": "1"}, disabled={"metrics": False}, home="stock")
        self.assertEqual(3, sum(c["attention"] for c in profiles.compare(before, after)))

    def test_unknown_device_does_not_claim_compatibility(self):
        p = profiles.describe({"model": "Unknown", "sdk": "not a number"})
        self.assertEqual("Unknown", p["title"])
        self.assertIn("not inferred", p["notes"][0])
        self.assertEqual([], profiles.compare(None, {}))


if __name__ == "__main__":
    unittest.main()
