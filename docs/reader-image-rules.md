# Reader image rules

Long press a loaded image and choose **Hide image**. The shaded band is removed.
It initially covers the entire image. Drag its upper and lower handles to retain
content above/below it; removing a middle band joins the two remaining pieces.
Pinch to zoom and drag the image to inspect long pages. **Apply** saves the rule
and refreshes open reader pages. Cancel changes nothing. A brief **Restore
original** action appears after applying a rule.
Long press a cropped image to edit its original or choose **Restore original**.

Rules and original samples are private app data, survive restarts and APK updates,
and apply to online, cached, downloaded and local reader images. Original chapter
files are never modified. App data clearing/uninstall removes the rules. They are
not currently included in Tachiyomi's library backup.

Matching first uses exact SHA-256 of the encoded image delivered to the reader,
across URLs and sources. Partial masks also record an exact SHA-256 of decoded
ARGB pixels. The identical band can then be removed from different story pages of
the same width: headers anchor to the top, footers to the bottom, and middle bands
to their recorded pixel offset. A detailed-row hash quickly rejects unrelated
pages before hashing the full band. Every pixel must match; resizing, lossy
re-encoding, or relocating middle bands needs another rule. Saved originals and
pixel coordinates are intended as evidence for future fuzzy matching work.
Animated images become a static first-frame image when cropped.

## ADB inspection (including release builds)

Use `eu.kanade.tachiyomi.debug` for debug builds. Add `-s SERIAL` to select a device
and `--user ID` to `content` commands when using another Android user.

The binary-safe exporter downloads the JSON and every original and checks their
SHA-256 hashes:

```sh
python tools/export_reader_image_rules.py ./image-audit --serial SERIAL --package eu.kanade.tachiyomi.debug
```

```sh
adb shell content query --uri content://eu.kanade.tachiyomi.reader_image_rules/rules
adb exec-out content read --uri content://eu.kanade.tachiyomi.reader_image_rules/rules > rules.json
adb exec-out content read --uri content://eu.kanade.tachiyomi.reader_image_rules/original/SHA256 > original.bin
adb shell content delete --uri content://eu.kanade.tachiyomi.reader_image_rules/rules/SHA256
```

Use a binary-safe shell for image redirection; Python `subprocess.run(...,
stdout=open(..., 'wb'), check=True)` works on Windows too. Rule deletion immediately
restores matching open pages. The provider accepts only the app UID, root and ADB
shell UID 2000; other apps cannot read the captured images or reading history.

Each record contains schema/algorithm versions, SHA-256, original dimensions,
`removeTop` (inclusive) / `removeBottom` (exclusive) pixel rows, full-hide status,
creation/update timestamps in Unix milliseconds, series/chapter IDs, names and
URLs, chapter number, one-based page number and count, image/page URLs, source ID,
name/base URL, extension package/name/version and app version/commit.
Partial rules also contain `maskPixelSha256`, `maskAlgorithm`, `maskAnchor`, and
an exact row fingerprint used as a fast prefilter. Pixel hashes use big-endian
ARGB8888 integers from the Android decoder, with rows concatenated top to bottom.

The image is the reader input **after extension processing**; the page number is
from the **filtered reader list**, not necessarily the raw website list. Use the
chapter URL with the recorded source base URL to fetch fresh raw pages, then
compare image URLs and the retained sample. Local sources have no extension/web
metadata. Source IDs are strings to avoid precision loss in JSON consumers.
