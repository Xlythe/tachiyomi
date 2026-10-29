from contextlib import closing
import http.client
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from collector import Store, handler, validate


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="reader-collector-test-")
        self.database = Path(self.temp.name) / "reports.sqlite3"
        self.store = Store(self.database, max_records=2)
        self.report = {"image_hash": "a" * 64, "series_name": "Test series", "chapter_number": 12.5}

    def tearDown(self):
        self.temp.cleanup()

    def test_restart_and_duplicate_retry_have_one_durable_record(self):
        receipt = self.store.accept(self.report)
        self.assertEqual(receipt, Store(self.database).accept(self.report))
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual(1, db.execute("SELECT count(*) FROM reports").fetchone()[0])

    def test_extra_private_fields_are_rejected(self):
        for key in ("image", "coordinates", "url", "device_id", "timestamp", "history"):
            with self.assertRaises(ValueError):
                validate(dict(self.report, **{key: "forbidden"}))

    def test_invalid_types_and_nonfinite_chapters_are_rejected(self):
        for value in (True, "12", float("nan"), float("inf")):
            with self.assertRaises(ValueError):
                validate(dict(self.report, chapter_number=value))
        for value in ("", "z" * 64, "a" * 63):
            with self.assertRaises(ValueError):
                validate(dict(self.report, image_hash=value))

    def test_capacity_does_not_silently_delete_reports_and_duplicates_still_work(self):
        first = self.store.accept(self.report)
        self.store.accept(dict(self.report, image_hash="b" * 64))
        with self.assertRaises(OverflowError):
            self.store.accept(dict(self.report, image_hash="c" * 64))
        self.assertEqual(first, self.store.accept(self.report))

    def test_numeric_representation_and_unicode_normalization_are_idempotent(self):
        first = self.store.accept(dict(self.report, series_name="Caf\u00e9", chapter_number=1))
        second = self.store.accept(dict(self.report, series_name="Cafe\u0301", chapter_number=1.0))
        self.assertEqual(first, second)

    def test_loopback_http_requires_auth_and_exact_schema(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler(self.store, "test-only-credential"))
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            def post(payload, auth):
                conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
                try:
                    conn.request("POST", "/v1/spam-reports", json.dumps(payload),
                                 {"Content-Type": "application/json", "Authorization": auth})
                    response = conn.getresponse()
                    return response.status, json.loads(response.read())
                finally:
                    conn.close()
            self.assertEqual(401, post(self.report, "wrong")[0])
            self.assertEqual(400, post(dict(self.report, coordinates=[1, 2, 3, 4]),
                                       "Bearer test-only-credential")[0])
            status, result = post(self.report, "Bearer test-only-credential")
            self.assertEqual(200, status)
            self.assertTrue(result["accepted"])
        finally:
            server.shutdown()
            server.server_close()
            worker.join(3)


if __name__ == "__main__":
    unittest.main()
