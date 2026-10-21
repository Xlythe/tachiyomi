# Reader image rules

Long press a loaded image and choose **Hide image**. The shaded band is removed.
It initially covers the entire image. Drag its upper and lower handles to retain
content above/below it; removing a middle band joins the two remaining pieces.
Pinch to zoom and drag the image to inspect long pages. **Apply** saves the rule
and refreshes open reader pages. Cancel changes nothing. A brief **Restore
original** action appears after applying a rule.
Long press a cropped image to edit its original or choose **Restore original**.

Handles attract nearby sharp, full-width contrast boundaries **during the drag**.
Capture is within 8 dp; the handle stays at the boundary through small movements
until the unsnapped finger position is over 18 dp away. Pull farther to override a
wrong guess. Finger-up keeps the last displayed position with no additional snap.
Accessibility actions move either handle one source pixel without snapping.

Rules and original samples are private app data, survive restarts and APK updates,
and apply to online, cached, downloaded and local reader images. Original chapter
files are never modified. App data clearing/uninstall removes the rules. They are
not currently included in Tachiyomi's library backup.

Matching first uses exact SHA-256 of the encoded image delivered to the reader,
across URLs and sources. Partial masks also record an exact SHA-256 of decoded
ARGB pixels. The identical band can then be removed from different story pages of
the same width: headers anchor to the top, footers to the bottom, and middle bands
to their recorded pixel offset. A detailed-row hash quickly rejects unrelated
pages before hashing the full band. Every pixel must match in this first pass;
resizing and lossy re-encoding fall through to the conservative matcher below.
Saved originals and
pixel coordinates remain the evidence for each rule.
Animated images become a static first-frame image when cropped.

The exact checks are followed by conservative colour-signature matching. This
normalizes width to 48 samples while retaining aspect ratio and checks every row
of the proposed removal. Small colour changes and proportionate resizing can
match, including whole spam pages learned with older app versions. Short or
low-detail samples, blank panels and unrelated boundary rows do not match.
For spam split across pages, only a template suffix at a page's top or a template
prefix at its bottom is considered. At least 35% of the sample and 12 normalized
rows must agree. Very small fragments deliberately remain visible. Interior
approximate matches are limited to the recorded offset. Adjacent and overlapping
matched bands are joined and removed once, using original coordinates.
Approximate cuts additionally require a single sharp source-resolution boundary
near each interior edge; ambiguous or seamless boundaries stay visible. Full-page
matches need no interior boundary. Alpha must also agree.
Signatures are limited to 4,096 normalized rows; images over 40 million pixels are
not rendered. Unsupported or oversized optional matches pass through unchanged.
These thresholds need further validation against the active device's real corpus.

## Optional watermark experiment

In the editor choose **Circle watermark (experimental)**, drag an oval around a
small watermark and choose **Preview guess**. Apply becomes available only after
a successful preview. This interpolates horizontally from pixels just outside the
oval, preserves image dimensions and every pixel outside the oval, and may blur
or invent artwork inside it. It is a local inpainting approximation, not recovery
of the original. Restore original remains available.

The correction applies only to the identical encoded image. It never learns a
global watermark-removal rule from repeated circles. A translucent overlay obeys
`observed = alpha * watermark + (1 - alpha) * original`; a single marked sample
does not determine both the overlay and the unknown original. Reliable inversion
would need a calibrated overlay/alpha model or aligned clean reference images.
Repeated unknown backgrounds alone do not guarantee that model.

## Portable corpus without ADB

**Export rules** and **Import rules** in the editor use Android's document picker.
No server or account is contacted by the app. Choose a local document provider for
local storage; a cloud document provider transfers the file only when you choose
that destination yourself. The ZIP contains `format.json` (format
`tachiyomi-reader-image-rules`, version 1), `rules.json`, and
`SHA256.original` samples. It includes reading metadata and original images, so
share it privately. Generated replacements are rebuilt on import.

Imports verify SHA-256, schema, bounds, methods and strict entry names, reject
duplicates and paths, and accept at most 1,000 rules, 32 MiB per entry and 512 MiB
total uncompressed. They stage files privately and publish a single atomic index
after validation/rendering completes. Invalid/interrupted imports leave existing
rules intact. Existing local choices take priority for duplicate image hashes.
An older ADB export can be packaged with the exporter below using `--archive`.
An older installed APK needs either its existing ADB exporter or an app update
before it can show these new file-picker buttons.

## ADB inspection (including release builds)

Use `eu.kanade.tachiyomi.debug` for debug builds. Add `-s SERIAL` to select a device
and `--user ID` to `content` commands when using another Android user.

The binary-safe exporter downloads the JSON and every original and checks their
SHA-256 hashes:

```sh
python tools/export_reader_image_rules.py ./image-audit --serial SERIAL --package eu.kanade.tachiyomi.debug
# Add --archive ./reader-image-rules.zip for the portable in-app format.
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

## Local validation and delivery

The pure Kotlin signature and sticky-drag regression suite runs without Android
or the app's UI dependencies:

```sh
./gradlew -p tools/reader-analysis-tests test
```

Android instrumentation additionally covers exact pixels, overlapping crops,
repeated stream reads/restoration, corpus round trips, malformed archives,
watermark boundaries, resizing, re-encoding, and split-page matching. Installing
the app/test APKs on any device requires confirmation.

On DESKTOP-WILL, the prior build's missing FlexibleAdapter artifact is available
from the local Maven repository under
`../scratch/FlexibleAdapter-build/maven-pinned`. Runtime source is upstream
`c80135339bcff5f7f8c2c2380329dfc155b26232`; the local build setup changes only build
configuration/manifest namespace. Its AAR SHA-256 is
`5b101dc70c52cffd0b85a5c4a3aff6049496ad7162371987edac3f92234186a8`.
Use the existing `../scratch/pinned-dependency.init.gradle` as `--init-script`;
no dependency version change is required. It is a machine-local build prerequisite.

Prior delivery notes identify `eu.kanade.tachiyomi.debug`, signed by the local
Android Debug certificate with SHA-256
`f5de63e01bb6e876bb2250ad5f6b3d1f7e94a6f0dd00ce49f2aae17222bb0fcf`.
This is evidence about the previous APK, not a fresh inspection of the travelling
device. The `standardPreview` variant uses that package/certificate with release
optimizations and debugging disabled. The normal release variant has no signing
configuration. Verify the installed package and signer before updating; preserve
app data and the corpus, and do not uninstall to work around a signature mismatch.

Android requires signing continuity for updates:
https://developer.android.com/studio/publish/app-signing
Wireless ADB requires device-side setup and pairing:
https://developer.android.com/tools/adb
Tailscale connectivity alone does not authorize or establish ADB. A compatible
signed APK can instead be transferred privately and opened by the user in the
Android installer. No transfer, pairing, security setting change, or installation
is performed by this work.
