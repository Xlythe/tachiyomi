"""Explicit spam-report collector. No images, coordinates, URLs or device IDs are accepted."""
import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import re
import sqlite3
import unicodedata


def validate(payload):
    if not isinstance(payload, dict) or set(payload) != {"image_hash", "series_name", "chapter_number"}:
        raise ValueError("Exactly three authorized fields are required")
    digest, title, chapter = (payload[k] for k in ("image_hash", "series_name", "chapter_number"))
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid image hash")
    if not isinstance(title, str) or not title.strip() or len(title) > 512:
        raise ValueError("Invalid series name")
    if type(chapter) not in (int, float) or not math.isfinite(chapter) or abs(chapter) > 10_000_000:
        raise ValueError("Invalid chapter number")
    return {"image_hash": digest, "series_name": unicodedata.normalize("NFC", title),
            "chapter_number": float(chapter) if chapter else 0.0}


class Store:
    def __init__(self, path, max_records=100_000):
        self.path, self.max_records = str(path), max_records
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("""CREATE TABLE IF NOT EXISTS reports (
                receipt TEXT PRIMARY KEY, image_hash TEXT NOT NULL, series_name TEXT NOT NULL,
                chapter_number REAL NOT NULL, received_at TEXT NOT NULL)""")

    def accept(self, payload):
        payload = validate(payload)
        canonical = json.dumps(payload, sort_keys=True, ensure_ascii=False,
                               separators=(",", ":"), allow_nan=False).encode("utf-8")
        receipt = hashlib.sha256(canonical).hexdigest()
        with closing(sqlite3.connect(self.path, timeout=5)) as db, db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT 1 FROM reports WHERE receipt=?", (receipt,)).fetchone():
                return receipt
            if db.execute("SELECT count(*) FROM reports").fetchone()[0] >= self.max_records:
                raise OverflowError("Collector capacity reached; retain report on client")
            db.execute("INSERT INTO reports VALUES (?,?,?,?,?)", (receipt, payload["image_hash"],
                       payload["series_name"], payload["chapter_number"],
                       datetime.now(timezone.utc).isoformat()))
        return receipt


def handler(store, token):
    if not token or len(token) > 1024 or any(not 33 <= ord(c) <= 126 for c in token):
        raise ValueError("An operator-provided bearer credential is required")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # No IP/User-Agent/payload/Authorization logging.

        def reply(self, status, data):
            body = json.dumps(data, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if self.path != "/v1/spam-reports":
                return self.reply(404, {"error": "not_found"})
            if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
                return self.reply(401, {"error": "unauthorized"})
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 8192:
                    return self.reply(413, {"error": "payload_size"})
                if self.headers.get_content_type() != "application/json":
                    return self.reply(415, {"error": "content_type"})
                body = self.rfile.read(size)
                if len(body) != size:
                    return self.reply(400, {"error": "incomplete_payload"})
                payload = json.loads(body, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
                receipt = store.accept(payload)
                return self.reply(200, {"accepted": True, "receipt": receipt})
            except (ValueError, UnicodeError, TypeError):
                return self.reply(400, {"error": "invalid_payload"})
            except OverflowError:
                return self.reply(507, {"error": "capacity"})
            except sqlite3.Error:
                return self.reply(503, {"error": "retry_later"})

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8767)
    args = parser.parse_args()
    # Intentionally loopback-only. External HTTPS routing/auth provisioning requires operator approval.
    server = ThreadingHTTPServer(("127.0.0.1", args.port),
                                 handler(Store(args.database), args.token_file.read_text().strip()))
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
