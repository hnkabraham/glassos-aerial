"""Sunrise and sunset for the daily rhythm, with the same math as GlassOS's SunTimes.kt on the TV.

The NOAA solar calculator (Meeus, Astronomical Algorithms, low-precision solar position), evaluated again at each
event's estimated instant, with 0.833 degrees for refraction and the sun's disc: within a minute of the USNO tables
between 72 degrees south and north. No network: a time zone's place comes from the system's zone.tab when there is one.
"""

from __future__ import annotations
from datetime import date, datetime, timedelta, timezone
from fractions import Fraction
from functools import lru_cache
import math
from pathlib import Path
import re
from zoneinfo import ZoneInfo

DAY_MS = 86_400_000
ANCHORS = ("sunrise", "sunset")
MAX_OFFSET = 240
D2R = math.pi / 180
COS_Z = math.cos(90.833 * D2R)
ZONEINFO = Path("/usr/share/zoneinfo")
COORD = re.compile(r"^([+-])(\d{2})(\d{2})(\d{2})?([+-])(\d{3})(\d{2})(\d{2})?$")


def days_from_civil(y, m, d):
    """Days from 1970-01-01 (Howard Hinnant's days_from_civil, truncating division as in Kotlin)."""
    y -= m <= 2
    n = y if y >= 0 else y - 399
    era = n // 400 if n >= 0 else -(-n // 400)
    yoe = y - era * 400
    doy = (153 * (m + (-3 if m > 2 else 9)) + 2) // 5 + d - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


def _sun(ms):
    """(Equation of time in minutes, declination in radians) at an instant."""
    t = (ms / DAY_MS + 2440587.5 - 2451545.0) / 36525
    l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360
    m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
    e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
    c = (math.sin(m * D2R) * (1.914602 - t * (0.004817 + 0.000014 * t))
         + math.sin(2 * m * D2R) * (0.019993 - 0.000101 * t) + math.sin(3 * m * D2R) * 0.000289)
    om = 125.04 - 1934.136 * t
    lam = l0 + c - 0.00569 - 0.00478 * math.sin(om * D2R)
    eps = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60 + 0.00256 * math.cos(om * D2R)
    decl = math.asin(math.sin(eps * D2R) * math.sin(lam * D2R))
    y = math.tan(eps * D2R / 2) ** 2
    eqt = 4 / D2R * (y * math.sin(2 * l0 * D2R) - 2 * e * math.sin(m * D2R)
                     + 4 * e * y * math.sin(m * D2R) * math.cos(2 * l0 * D2R)
                     - 0.5 * y * y * math.sin(4 * l0 * D2R) - 1.25 * e * e * math.sin(2 * m * D2R))
    return eqt, decl


def rise_set(noon_guess_ms, lat, lon):
    """(rise_ms, set_ms), or "up" / "down" when the sun stays above or below the horizon all day.
    noon_guess_ms is 12:00 local clock time on the local date; it only picks the day."""
    eqt, _ = _sun(noon_guess_ms)
    base = noon_guess_ms - noon_guess_ms % DAY_MS  # 00:00 UTC of the guess's UTC day
    transit = base + (720 - 4 * lon - eqt) * 60000
    if transit - noon_guess_ms > DAY_MS / 2:
        base -= DAY_MS
    elif noon_guess_ms - transit > DAY_MS / 2:
        base += DAY_MS
    out = []
    for sign in (1, -1):  # rise has a positive hour angle in the NOAA formula
        est = base + (720 - 4 * lon - eqt) * 60000
        for _ in range(2):
            e2, d2 = _sun(est)
            cos_h = COS_Z / (math.cos(lat * D2R) * math.cos(d2)) - math.tan(lat * D2R) * math.tan(d2)
            if cos_h > 1:
                return "down"
            if cos_h < -1:
                return "up"
            h = math.acos(cos_h) / D2R
            est = base + (720 - 4 * (lon + sign * h) - e2) * 60000
        out.append(est)
    return out[0], out[1]


def zone(name):
    """A ZoneInfo for an IANA name, or None (unknown, empty, or not a zone name at all)."""
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_+\-]*(/[A-Za-z0-9_+\-]+)*", name):
        return None
    try:
        return ZoneInfo(name)
    except Exception:
        return None


def _offset_ms(ms, tz):
    return int(datetime.fromtimestamp(ms / 1000, timezone.utc).astimezone(tz).utcoffset().total_seconds() * 1000)


def local_minute(ms, tz):
    """Local minute of day (0..1439) at an instant, rounded to the nearest minute as the USNO tables are."""
    r = (ms + 30000) // 60000 * 60000
    return int((r + _offset_ms(r, tz)) % DAY_MS // 60000)


def day(on, tz, lat, lon):
    """Sunrise and sunset instants (ms) on local date `on` in zone `tz`: {"sunrise", "sunset", "polar"}."""
    noon = datetime(on.year, on.month, on.day, 12, tzinfo=tz)
    # The zone's standard offset that day, as TimeZone.rawOffset on the TV.
    raw = int((noon.utcoffset() - (noon.dst() or timedelta(0))).total_seconds() * 1000)
    r = rise_set(days_from_civil(on.year, on.month, on.day) * DAY_MS + DAY_MS // 2 - raw, lat, lon)
    if isinstance(r, str):
        return {"sunrise": None, "sunset": None, "polar": r}
    return {"sunrise": math.floor(r[0] + 0.5), "sunset": math.floor(r[1] + 0.5), "polar": None}


def minute(sun_day, anchor, offset, tz):
    """Local minute of `anchor` + `offset` minutes (elapsed time, right across a DST change), or None."""
    t = sun_day.get(anchor) if anchor in ANCHORS else None
    return None if t is None else local_minute(t + offset * 60000, tz)


def resolve(rows, on, tz, lat, lon):
    """Today's schedule, as the TV works it out (SceneSchedule.resolve): sun rows at their event plus offset, or at
    their fixed start without one; sorted by start, the later row first on a tie."""
    sd = day(on, tz, lat, lon)
    out = []
    for i, r in enumerate(rows):
        m = minute(sd, r["anchor"], r.get("offset", 0), tz) if r.get("anchor") else None
        out.append((r["start"] if m is None else m, i, dict(r, start=r["start"] if m is None else m)))
    return [r for _, _, r in sorted(out, key=lambda x: (x[0], x[1]))]


def tenth(value):
    """Rounded to 0.1 degree, halves up as Math.round on the TV (never -0.0)."""
    return math.floor(value * 10 + 0.5) / 10 + 0.0


def _tenths(sign, d, m, s):
    t = int((int(d) + Fraction(int(m), 60) + Fraction(int(s or 0), 3600)) * 10 + Fraction(1, 2))
    return -t if sign == "-" else t


@lru_cache(maxsize=4)
def _places(zone_tab, zi):
    zones, links = {}, {}
    try:
        for line in Path(zone_tab).read_text(encoding="utf-8").splitlines():
            cols = line.split("\t")
            if line.startswith("#") or len(cols) < 3:
                continue
            m = COORD.match(cols[1])
            if m:
                g = m.groups()
                zones[cols[2]] = (_tenths(*g[0:4]) / 10, _tenths(*g[4:8]) / 10)
    except OSError:
        return {}, {}
    try:
        # tzdata.zi's links: "L target old-name".
        for line in Path(zi).read_text(encoding="utf-8").splitlines():
            cols = line.split()
            if len(cols) == 3 and cols[0] == "L":
                links[cols[2]] = cols[1]
    except OSError:
        pass
    return zones, links


def zone_place(name, zone_tab=ZONEINFO / "zone.tab", zi=ZONEINFO / "tzdata.zi"):
    """(lat, lon, label) of a time zone's principal city at 0.1 degree, as the TV estimates it, or None."""
    zones, links = _places(str(zone_tab), str(zi))
    for _ in range(8):
        if name in zones or name not in links:
            break
        name = links[name]
    if name not in zones:
        return None
    lat, lon = zones[name]
    return lat, lon, name.rsplit("/", 1)[-1].replace("_", " ")


def today(tz):
    """Today's date in the TV's zone (the Mac's own date without one)."""
    return datetime.now(tz).date() if tz else date.today()
