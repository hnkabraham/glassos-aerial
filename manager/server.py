#!/usr/bin/env python3
"""GlassOS Manager: a private, loopback-only desktop companion. Python 3.10+."""

import argparse
import fcntl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import secrets
import shutil
import socket
from urllib.parse import unquote, urlsplit
import uuid
import webbrowser

from core import Error, Manager, atomic_json

STATIC = Path(__file__).parent / "static"
MAX_UPLOAD = 4_000_000_000


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, manager, port=0):
        self.manager = manager
        self.token = secrets.token_urlsafe(32)
        super().__init__(("127.0.0.1", port), Handler)
        self.origin = f"http://127.0.0.1:{self.server_port}"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # tokens, device addresses and user filenames do not enter a web access log

    def setup(self):
        super().setup()
        self.connection.settimeout(60)

    def headers_ok(self, auth=False):
        if self.headers.get("Host") != self.server.origin.removeprefix("http://"):
            raise Error("Invalid local host.")
        origin = self.headers.get("Origin")
        if auth and origin and origin != self.server.origin:
            raise Error("Cross-origin requests are refused.")
        if auth and self.headers.get("Sec-Fetch-Site") == "cross-site":
            raise Error("Cross-site requests are refused.")
        if auth and not secrets.compare_digest(
            self.headers.get("X-GlassOS-Token", ""), self.server.token
        ):
            raise Error("Session expired. Reopen GlassOS Manager.")

    def reply(self, value, status=200, kind="application/json"):
        data = json.dumps(value).encode() if kind == "application/json" else value
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        )
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        try:
            path = urlsplit(self.path).path
            self.headers_ok(auth=path.startswith("/api/"))
            if path == "/api/state":
                return self.reply(self.server.manager.snapshot())
            if path.startswith("/api/poster/"):
                id = path.removeprefix("/api/poster/")
                with self.server.manager.lock:
                    row = next(
                        (
                            x
                            for x in self.server.manager.state["scenes"]
                            if x["id"] == id
                        ),
                        None,
                    )
                if not row or not row.get("poster_path"):
                    return self.reply({"error": "No poster"}, 404)
                p = Path(row["poster_path"])
                if p.stat().st_size > 8 * 1024**2:
                    raise Error("Poster too large.")
                return self.reply(p.read_bytes(), kind="image/jpeg")
            allowed = {
                "/": "index.html",
                "/app.js": "app.js",
                "/style.css": "style.css",
            }
            if path not in allowed:
                return self.reply({"error": "Not found"}, 404)
            p = STATIC / allowed[path]
            kind = mimetypes.guess_type(p.name)[0] or "text/plain"
            return self.reply(p.read_bytes(), kind=kind)
        except Error as e:
            self.reply({"error": str(e)}, 403)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            self.reply({"error": str(e)}, 500)

    def do_POST(self):
        source = None
        try:
            self.headers_ok(auth=True)
            if self.headers.get("Transfer-Encoding"):
                raise Error("A Content-Length is required.")
            size = int(self.headers.get("Content-Length", "-1"))
            if size < 0:
                raise Error("A Content-Length is required.")
            m = self.server.manager
            path = urlsplit(self.path).path
            if path == "/api/upload":
                if not 0 < size <= MAX_UPLOAD:
                    raise Error("Choose a video smaller than 4 GB.")
                if m.work_lock.locked():
                    raise Error(
                        "Wait for the current operation to finish before importing."
                    )
                if shutil.disk_usage(m.data).free < size + 1024**3:
                    raise Error("Not enough space on this Mac for import and encoding.")
                source = m.data / "uploads" / (uuid.uuid4().hex + ".upload")
                with source.open("wb") as f:
                    left = size
                    while left:
                        block = self.rfile.read(min(left, 1024**2))
                        if not block:
                            raise Error("Upload interrupted; no scene was added.")
                        f.write(block)
                        left -= len(block)
                filename = unquote(self.headers.get("X-Filename", "video"))
                title = unquote(self.headers.get("X-Title", ""))
                collection = self.headers.get("X-Collection", "other")
                seconds = self.headers.get("X-Seconds", "90")
                staged = source
                result = m.job(
                    "Import video",
                    lambda log: m.import_video(
                        staged, filename, title, collection, seconds, log
                    ),
                )
                source = None
                return self.reply(result, 202)
            if size > 256 * 1024:
                raise Error("Request too large.")
            if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                raise Error("JSON is required.")
            data = json.loads(self.rfile.read(size))
            if not isinstance(data, dict):
                raise Error("Expected a JSON object.")
            if path == "/api/device":
                m.add_device(data.get("name"), data.get("address"))
                return self.reply({"ok": True})
            if path == "/api/configure":
                if not m.work_lock.acquire(blocking=False):
                    raise Error(
                        "Wait for the current operation to finish before editing."
                    )
                try:
                    m.configure(data)
                finally:
                    m.work_lock.release()
                return self.reply({"ok": True})
            if path == "/api/action":
                id = data.get("device")
                action = data.get("action")
                actions = {
                    "inspect": ("Check device", lambda log: m.inspect(id, log)),
                    "bundle": ("Import saved scenery", m.import_bundle),
                    "sync": ("Sync selected scenery", lambda log: m.sync(id, log)),
                    "backup": ("Back up settings", lambda log: m.backup(id, log)),
                    "restore": (
                        "Copy settings backup",
                        lambda log: m.restore(id, data.get("backup"), log),
                    ),
                    "update": ("Update GlassOS", lambda log: m.update(id, log)),
                    "home": ("Open GlassOS", lambda log: m.device(id).start()),
                }
                if action not in actions:
                    raise Error("Unknown operation.")
                title, work = actions[action]
                return self.reply(m.job(title, work), 202)
            return self.reply({"error": "Not found"}, 404)
        except (Error, ValueError, KeyError, TypeError) as e:
            self.reply({"error": str(e)}, 400)
        except (BrokenPipeError, ConnectionResetError, socket.timeout):
            pass
        except Exception as e:
            self.reply({"error": str(e)}, 500)
        finally:
            if source is not None:
                source.unlink(missing_ok=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "--data-dir",
        type=Path,
        default=Path.home() / "Library/Application Support/GlassOS Manager",
    )
    p.add_argument("--bundle", type=Path)
    p.add_argument("--port", type=int, default=0)
    p.add_argument("--no-open", action="store_true")
    args = p.parse_args()
    args.data_dir.mkdir(parents=True, exist_ok=True)
    lock = (args.data_dir / "server.lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        saved = json.loads((args.data_dir / "server.json").read_text())
        if not args.no_open:
            webbrowser.open(saved["url"])
        print("GlassOS Manager is already running.")
        return
    os.umask(0o077)
    m = Manager(args.data_dir, args.bundle)
    server = Server(m, args.port)
    url = server.origin + "/#" + server.token
    atomic_json(
        args.data_dir / "server.json",
        {"url": url, "pid": os.getpid(), "port": server.server_port},
    )
    print("GlassOS Manager running locally at " + server.origin, flush=True)
    if not args.no_open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        (args.data_dir / "server.json").unlink(missing_ok=True)


if __name__ == "__main__":
    main()
