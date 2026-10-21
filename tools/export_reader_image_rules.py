#!/usr/bin/env python3
"""Export user-authored reader masks and original image samples through ADB."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--serial", help="ADB device serial")
    parser.add_argument("--package", default="eu.kanade.tachiyomi")
    parser.add_argument("--user", type=int, default=0)
    parser.add_argument("--metadata-only", action="store_true")
    parser.add_argument("--archive", type=Path, help="Also create a portable ZIP for the in-app importer")
    args = parser.parse_args()
    if args.archive and args.metadata_only:
        parser.error("--archive requires original samples; omit --metadata-only")
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    base = f"content://{args.package}.reader_image_rules"

    def read(path):
        result = subprocess.run(
            adb + ["exec-out", "content", "read", "--user", str(args.user), "--uri", f"{base}/{path}"],
            check=True, capture_output=True, timeout=60,
        )
        # Android's content command sometimes returns exit status 0 on provider errors.
        if not result.stdout:
            raise RuntimeError(result.stderr.decode(errors="replace").strip() or "Empty ADB response")
        return result.stdout

    records = json.loads(read("rules"))
    if not isinstance(records, list):
        raise ValueError("Expected a JSON list of reader image rules")
    args.output.mkdir(parents=True, exist_ok=True)
    if not args.metadata_only:
        for record in records:
            digest = record["sha256"]
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError("Invalid image hash in exported rule")
            raw = read(f"original/{digest}")
            if hashlib.sha256(raw).hexdigest() != digest:
                raise ValueError(f"Original image hash mismatch: {digest}")
            (args.output / f"{digest}.original").write_bytes(raw)
    (args.output / "rules.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    if args.archive:
        with zipfile.ZipFile(args.archive, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("format.json", json.dumps({"format": "tachiyomi-reader-image-rules", "version": 1}))
            archive.write(args.output / "rules.json", "rules.json")
            for record in records:
                name = f"{record['sha256']}.original"
                archive.write(args.output / name, name)
    print(f"Exported {len(records)} rules to {args.output}")


if __name__ == "__main__":
    main()
