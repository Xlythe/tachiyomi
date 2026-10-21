import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile


spec = importlib.util.spec_from_file_location("exporter", Path(__file__).with_name("export_reader_image_rules.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class ExportReaderImageRulesTest(unittest.TestCase):
    def test_portable_archive_preserves_original_bytes_and_schema(self):
        raw = b"binary original\x00\xff\r\n"
        digest = hashlib.sha256(raw).hexdigest()
        records = [{"schemaVersion": 1, "sha256": digest}]

        def read(command, **kwargs):
            data = json.dumps(records).encode() if command[-1].endswith("/rules") else raw
            return SimpleNamespace(stdout=data, stderr=b"")

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "samples"
            archive = Path(directory) / "rules.zip"
            with patch("sys.argv", ["exporter", str(output), "--archive", str(archive)]), patch.object(exporter.subprocess, "run", side_effect=read):
                exporter.main()
            with zipfile.ZipFile(archive) as bundle:
                self.assertEqual(raw, bundle.read(f"{digest}.original"))
                self.assertEqual(records, json.loads(bundle.read("rules.json")))
                self.assertEqual({"format": "tachiyomi-reader-image-rules", "version": 1}, json.loads(bundle.read("format.json")))

    def test_bad_original_checksum_is_not_exported(self):
        records = [{"sha256": "0" * 64}]

        def read(command, **kwargs):
            data = json.dumps(records).encode() if command[-1].endswith("/rules") else b"wrong sample"
            return SimpleNamespace(stdout=data, stderr=b"")

        with tempfile.TemporaryDirectory() as directory:
            with patch("sys.argv", ["exporter", directory]), patch.object(exporter.subprocess, "run", side_effect=read):
                with self.assertRaisesRegex(ValueError, "hash mismatch"):
                    exporter.main()
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_archive_requires_originals(self):
        with patch("sys.argv", ["exporter", "unused", "--archive", "unused.zip", "--metadata-only"]), patch.object(exporter.subprocess, "run") as adb:
            with self.assertRaises(SystemExit):
                exporter.main()
            adb.assert_not_called()


if __name__ == "__main__":
    unittest.main()
