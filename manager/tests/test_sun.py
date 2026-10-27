"""sun.py against the USNO tables for 2026 (rounded to the nearest minute): the same vectors as SunTimesTest.kt."""

from datetime import date
from pathlib import Path
import sys
import tempfile
import unittest
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sun

LA = ZoneInfo("America/Los_Angeles")
OSLO = ZoneInfo("Europe/Oslo")


def times(on, tz, lat, lon):
    d = sun.day(on, tz, lat, lon)
    return d, sun.minute(d, "sunrise", 0, tz), sun.minute(d, "sunset", 0, tz)


class SunTests(unittest.TestCase):
    def near(self, expected, actual, what):
        self.assertIsNotNone(actual, what)
        h, m = map(int, expected.split(":"))
        diff = (actual - (h * 60 + m) + 720) % 1440 - 720
        self.assertLessEqual(abs(diff), 1, f"{what}: expected {expected}, got {actual // 60}:{actual % 60:02d}")

    def check(self, tz, on, lat, lon, rise, set_):
        d, r, s = times(on, tz, lat, lon)
        self.assertIsNone(d["polar"])
        self.near(rise, r, f"sunrise {on} {lat},{lon}")
        self.near(set_, s, f"sunset {on} {lat},{lon}")

    def test_matches_usno_tables(self):
        self.check(LA, date(2026, 6, 21), 47.6062, -122.3321, "05:12", "21:11")  # Seattle, PDT
        self.check(LA, date(2026, 12, 21), 47.6062, -122.3321, "07:55", "16:20")  # Seattle, PST
        self.check(LA, date(2026, 6, 21), 37.7749, -122.4194, "05:48", "20:35")  # San Francisco
        self.check(LA, date(2026, 12, 21), 37.7749, -122.4194, "07:21", "16:54")
        self.check(ZoneInfo("Australia/Sydney"), date(2026, 12, 21), -33.8688, 151.2093, "05:41", "20:05")
        self.check(OSLO, date(2026, 6, 21), 59.9139, 10.7522, "03:54", "22:44")
        self.check(OSLO, date(2026, 12, 21), 59.9139, 10.7522, "09:18", "15:12")
        self.check(OSLO, date(2026, 11, 27), 69.6492, 18.9553, "11:20", "11:42")  # Tromsø

    def test_dst_days_use_the_offset_at_the_event(self):
        # Converting with the midnight offset would give 06:12 and 07:12.
        self.check(LA, date(2026, 3, 8), 34.0522, -118.2428, "07:12", "18:55")
        self.check(LA, date(2026, 11, 1), 34.0522, -118.2428, "06:13", "17:00")

    def test_polar_day_and_night(self):
        svalbard = ZoneInfo("Arctic/Longyearbyen")
        d, r, s = times(date(2026, 6, 21), svalbard, 78.2232, 15.6267)
        self.assertEqual(("up", None, None), (d["polar"], r, s))
        self.assertEqual("down", sun.day(date(2026, 12, 21), svalbard, 78.2232, 15.6267)["polar"])
        self.assertEqual("down", sun.day(date(2026, 11, 28), OSLO, 69.6492, 18.9553)["polar"])

    def test_events_after_midnight_fold_into_the_day(self):
        tz = ZoneInfo("Atlantic/Reykjavik")
        d, r, s = times(date(2026, 6, 21), tz, 64.1466, -21.9426)
        self.near("02:55", r, "Reykjavik sunrise")
        self.assertEqual(4, s)
        seattle = sun.day(date(2026, 6, 21), LA, 47.6062, -122.3321)
        self.assertEqual(11, sun.minute(seattle, "sunset", 180, LA))
        self.assertIsNone(sun.minute(seattle, "noon", 0, LA))

    def test_resolve_matches_the_tv(self):
        rows = [
            {"start": 420, "anchor": "sunrise", "offset": -30, "collection": "coast"},
            {"start": 720, "collection": "mountains"},
            {"start": 1080, "anchor": "sunset", "offset": 0, "collection": "space"},
        ]
        today = sun.resolve(rows, date(2026, 6, 21), LA, 47.6062, -122.3321)
        self.assertEqual([282, 720, 1271], [r["start"] for r in today])
        polar = sun.resolve(rows, date(2026, 6, 21), ZoneInfo("Arctic/Longyearbyen"), 78.2232, 15.6267)
        self.assertEqual([420, 720, 1080], [r["start"] for r in polar])

    def test_civil_days_and_rounding(self):
        self.assertEqual(0, sun.days_from_civil(1970, 1, 1))
        self.assertEqual(-1, sun.days_from_civil(1969, 12, 31))
        self.assertEqual(20751, sun.days_from_civil(2026, 10, 25))
        self.assertEqual(-135140, sun.days_from_civil(1600, 1, 1))
        # Halves up, as Math.round on the TV: round() would take 340.5 to the even 340.
        self.assertEqual((34.1, -118.2, 0.0), (sun.tenth(34.05), sun.tenth(-118.25), sun.tenth(-0.04)))
        self.assertEqual("0.0", str(sun.tenth(-0.04)))

    def test_zone_places_from_zone_tab(self):
        with tempfile.TemporaryDirectory() as tmp:
            tab = Path(tmp) / "zone.tab"
            zi = Path(tmp) / "tzdata.zi"
            tab.write_text(
                "# comment\nUS\t+340308-1181434\tAmerica/Los_Angeles\tPacific\nIN\t+2232+08822\tAsia/Kolkata\n"
            )
            zi.write_text("Z America/Los_Angeles -7:52:58 - LMT 1883\nL America/Los_Angeles US/Pacific\nL Asia/Kolkata Asia/Calcutta\nL Etc/UTC UTC\n")
            self.assertEqual((34.1, -118.2, "Los Angeles"), sun.zone_place("America/Los_Angeles", tab, zi))
            self.assertEqual((34.1, -118.2, "Los Angeles"), sun.zone_place("US/Pacific", tab, zi))
            self.assertEqual((22.5, 88.4, "Kolkata"), sun.zone_place("Asia/Calcutta", tab, zi))
            self.assertIsNone(sun.zone_place("UTC", tab, zi))
            self.assertIsNone(sun.zone_place("Asia/Calcutta", tab, Path(tmp) / "missing.zi"))
        self.assertIsNone(sun.zone_place("America/Los_Angeles", Path("/nonexistent/zone.tab")))

    def test_unknown_zones(self):
        for name in [None, "", "Test", "Not/AZone", "../etc/passwd", "America/Los Angeles"]:
            self.assertIsNone(sun.zone(name), name)
        self.assertIsNotNone(sun.zone("America/Los_Angeles"))


if __name__ == "__main__":
    unittest.main()
