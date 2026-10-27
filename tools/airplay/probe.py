#!/usr/bin/env python3
"""Probes a GlassOS Screen Mirroring receiver over RTSP, the way a sender's first requests do.

    python3 tools/airplay/probe.py HOST [PORT]          (PORT defaults to 7000)
    python3 tools/airplay/probe.py --json HOST [PORT]

On the emulator, forward the port first:  adb -s emulator-5556 forward tcp:17000 tcp:7000
then:                                      python3 tools/airplay/probe.py 127.0.0.1 17000

It sends `GET /info RTSP/1.0` with a CSeq header (UxPlay answers /info only to RTSP/1.0 requests that carry one;
curl's HTTP/1.1 without CSeq is ignored or taken for an HLS request), parses the binary plist answer, and then sends
`OPTIONS *` and reads its Public header. Exit status 0 when both answer 200 and /info has name, deviceID, features,
model and pk; 1 otherwise. Standard library only. Each request opens one TCP connection, which the receiver counts
as a sender connection until it closes.
"""
import argparse
import json
import plistlib
import socket
import sys

REQUIRED_INFO = ("name", "deviceID", "features", "model", "pk")
USER_AGENT = "GlassOS-probe/1.0"


class ProbeError(Exception):
    pass


def rtsp(host, port, method, path, cseq, timeout):
    """One RTSP request on its own connection. Returns (status line, headers dict lower-cased, body bytes)."""
    request = (f"{method} {path} RTSP/1.0\r\n"
               f"CSeq: {cseq}\r\n"
               f"User-Agent: {USER_AGENT}\r\n"
               f"\r\n").encode("ascii")
    with socket.create_connection((host, port), timeout=timeout) as s:
        s.settimeout(timeout)
        s.sendall(request)
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = s.recv(4096)
            if not chunk:
                raise ProbeError(f"{method} {path}: connection closed before the response headers ended")
            data += chunk
            if len(data) > 65536:
                raise ProbeError(f"{method} {path}: response headers too long")
        head, _, body = data.partition(b"\r\n\r\n")
        lines = head.decode("iso-8859-1").split("\r\n")
        status = lines[0]
        headers = {}
        for line in lines[1:]:
            k, sep, v = line.partition(":")
            if sep:
                headers[k.strip().lower()] = v.strip()
        try:
            length = int(headers.get("content-length", "0"))
        except ValueError:
            raise ProbeError(f"{method} {path}: bad Content-Length {headers.get('content-length')!r}") from None
        while len(body) < length:
            chunk = s.recv(min(65536, length - len(body)))
            if not chunk:
                raise ProbeError(f"{method} {path}: body ended after {len(body)} of {length} bytes")
            body += chunk
        return status, headers, body[:length]


def plain(value):
    """plist values made printable: bytes as hex, nested containers recursively."""
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, dict):
        return {k: plain(v) for k, v in value.items()}
    if isinstance(value, list):
        return [plain(v) for v in value]
    return value


def probe(host, port, timeout):
    result = {"host": host, "port": port, "ok": False}
    status, headers, body = rtsp(host, port, "GET", "/info", 1, timeout)
    result["info_status"] = status
    result["info_content_type"] = headers.get("content-type", "")
    info = {}
    if body:
        try:
            info = plistlib.loads(body)
        except Exception as e:  # plistlib raises several types for malformed input
            raise ProbeError(f"GET /info: the body is not a plist ({e})") from None
    if not isinstance(info, dict):
        raise ProbeError("GET /info: the plist is not a dictionary")
    result["info"] = plain(info)
    result["missing"] = [k for k in REQUIRED_INFO if k not in info]

    status2, headers2, _ = rtsp(host, port, "OPTIONS", "*", 2, timeout)
    result["options_status"] = status2
    result["public"] = headers2.get("public", "")

    result["ok"] = (status.endswith(" 200 OK") and status2.endswith(" 200 OK") and not result["missing"]
                    and "SETUP" in result["public"])
    return result


def summary(r):
    info = r.get("info", {})
    out = [f"GET /info  -> {r['info_status']}  ({r['info_content_type'] or 'no content type'})"]
    for k in ("name", "deviceID", "model", "features", "sourceVersion", "macAddress", "pk"):
        if k in info:
            out.append(f"  {k:<14}{info[k]}")
    extra = sorted(k for k in info if k not in ("name", "deviceID", "model", "features", "sourceVersion",
                                                "macAddress", "pk"))
    if extra:
        out.append(f"  also: {', '.join(extra)}")
    if r["missing"]:
        out.append(f"  MISSING: {', '.join(r['missing'])}")
    out.append(f"OPTIONS *  -> {r['options_status']}")
    out.append(f"  Public: {r['public'] or '(none)'}")
    out.append("PASS" if r["ok"] else "FAIL")
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description="RTSP GET /info and OPTIONS probe for the Screen Mirroring receiver.")
    ap.add_argument("host")
    ap.add_argument("port", nargs="?", type=int, default=7000)
    ap.add_argument("--timeout", type=float, default=5.0, help="seconds per network operation (default 5)")
    ap.add_argument("--json", action="store_true", help="print the result as JSON")
    args = ap.parse_args(argv)
    try:
        r = probe(args.host, args.port, args.timeout)
    except (OSError, ProbeError) as e:
        if args.json:
            print(json.dumps({"host": args.host, "port": args.port, "ok": False, "error": str(e)}))
        else:
            print(f"FAIL: {e}", file=sys.stderr)
        return 1
    print(json.dumps(r, indent=2, default=str) if args.json else summary(r))
    return 0 if r["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
