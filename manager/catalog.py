"""Aerial-compatible public HTTPS feeds. Nothing is fetched until requested."""

import hashlib
import ipaddress
import json
import shutil
import socket
import time
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

COMMUNITY = "https://raw.githubusercontent.com/AerialScreensaver/AerialCommunity/master/entries.json"
SOURCE_PAGE = "https://github.com/AerialScreensaver/AerialCommunity"
QUALITIES = ("url-1080-H264", "url-1080-SDR", "url-720-H264", "url-4K-SDR")


def https_url(value):
    if not isinstance(value, str) or len(value) > 4096 or any(ord(c) < 33 for c in value):
        raise ValueError("Use a public HTTPS URL.")
    u = urlsplit(value)
    if u.scheme != "https" or not u.hostname or u.username or u.password or u.port not in (None, 443):
        raise ValueError("Use a public HTTPS URL without credentials or a custom port.")
    return value


def public_url(value):
    https_url(value)
    host = urlsplit(value).hostname
    addresses = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
        raise ValueError("Catalog downloads must use public Internet addresses.")
    return value


class PublicRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        public_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url, output=None, limit=5 * 1024**2, log=lambda _: None):
    """Bounded streaming with a disk reserve, time limit, and checked redirects."""
    req = Request(public_url(url), headers={"User-Agent": "GlassOS-Aerial-Manager/1.5", "Accept-Encoding": "identity"})
    started = time.monotonic()
    data = bytearray()
    total = 0
    next_log = 100 * 1024**2
    try:
        with build_opener(PublicRedirect()).open(req, timeout=30) as response:
            length = response.headers.get("Content-Length")
            if length and int(length) > limit:
                raise ValueError("Download exceeds the size limit.")
            f = output.open("wb") if output else None
            try:
                while True:
                    block = response.read(1024**2)
                    if not block:
                        break
                    total += len(block)
                    if total > limit or time.monotonic() - started > 1800:
                        raise ValueError("Download exceeded its size or time limit.")
                    if f:
                        if shutil.disk_usage(output.parent).free < len(block) + 1024**3:
                            raise ValueError("Keep at least 1 GB free on this Mac for encoding.")
                        f.write(block)
                        if total >= next_log:
                            log(f"Downloaded {total // 1_000_000} MB…")
                            next_log += 100 * 1024**2
                    else:
                        data.extend(block)
                if length and total != int(length):
                    raise ValueError("Download was interrupted.")
            finally:
                if f:
                    f.close()
        return bytes(data)
    except Exception:
        if output:
            output.unlink(missing_ok=True)
        raise


def parse(data, feed):
    https_url(feed)
    if len(data) > 5 * 1024**2:
        raise ValueError("Feed is too large.")
    doc = json.loads(data)
    assets = doc.get("assets") if isinstance(doc, dict) else doc
    if not isinstance(assets, list) or len(assets) > 5000:
        raise ValueError("Expected an Aerial JSON feed with at most 5,000 assets.")
    out, seen = [], set()
    categories = {"city", "coast", "mountains", "forest", "desert", "underwater", "space", "other"}
    for asset in assets:
        if not isinstance(asset, dict):
            continue
        quality = next((q for q in QUALITIES if isinstance(asset.get(q), str) and asset[q]), None)
        if not quality:
            continue  # Never silently choose HDR, an audio stream, or an unknown codec URL.
        try:
            url = https_url(asset[quality])
        except ValueError:
            continue
        id = hashlib.sha256((feed + "\n" + url).encode()).hexdigest()[:24]
        if id in seen:
            continue
        seen.add(id)
        title = asset.get("title") or asset.get("accessibilityLabel") or asset.get("id") or "Untitled scene"
        if not isinstance(title, str):
            title = "Untitled scene"
        place = asset.get("accessibilityLabel", "")
        category = asset.get("scene", "other")
        category = {"nature": "other", "sea": "coast", "landscape": "other"}.get(category, category) if isinstance(category, str) else "other"
        out.append({"id": id, "title": title[:120], "place": place[:120] if isinstance(place, str) else "",
                    "collection": category if category in categories else "other", "quality": quality.removeprefix("url-"),
                    "url": url, "feed": feed})
    if not out:
        raise ValueError("No supported SDR video URLs found in this feed.")
    return out
