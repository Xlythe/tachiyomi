# Chapter scroll checkpoints

Webtoon and continuous readers keep an original chapter/page identity, split-half identity,
and normalized point inside the displayed image. Rotation still uses the newer Android
hierarchy anchor. Opening the chapter again uses a private persisted checkpoint instead of
only the database's end-visible page index. Preceding image sizes and whole-chapter scroll
percentages are not used to identify the point.

Checkpoints are debounced by 750 ms while scrolling, saved on reader pause and viewer
destruction, and saved before changing the active adapter chapter. They use asynchronous
Android preferences writes; Android flushes queued writes during normal activity stop.
An abrupt kill/power loss can lose movement since the last completed checkpoint. This is
not a promise to recover every pixel after a crash.

`reader-scroll-positions-v1` is an additive, private preference file. It stores at most 512
recent chapter positions, not images, and evicts only its own oldest records. Database page
progress, read/completion state, source pages, spam rules and explicit review choices are
unchanged. No database migration or network reporting is involved. These extra checkpoints
are not included in Tachiyomi's portable backup format.

A checkpoint is scoped by manga ID, chapter ID and chapter URL. Its page count, database
page-progress stamp, read flag, and original page URL hash (when both entries have one)
must still agree. Incompatible or malformed records fall back to ordinary page progress.
Blank page locators are supported for downloaded chapters. A page replacement which keeps
all those identifiers is not detectable without inspecting its image bytes; reopening does
not synchronously hash or scan chapter images.

Restoration waits for decoded image geometry and survives another exit while waiting.
Hidden targets move to visible content without exposing the hidden pixels. A removed split
half falls back to its original page. Explicit page navigation cancels both disk and pending
hierarchy anchors, including a seek followed immediately by exit before the next layout.
Natural continuous chapter transitions keep their existing edge behavior rather than jumping
to an older bookmark in the destination chapter. Reaching the rendered chapter end removes
the auxiliary checkpoint; normal completed-chapter reopening behavior remains authoritative.
An already-read chapter being reread can retain a midpoint until its rendered end is reached.
Incognito sessions can read the prior normal checkpoint but never update or remove it, even
when disabling incognito finishes the activity.

The exit/reopen regression uses the production WebtoonViewer, adapter, layout manager and
Android preferences under Robolectric SDK 28. Its source/database-dependent ViewModel
callbacks and bitmap rendering are test doubles. Against delivered master `8cd97436b`,
opening a fresh activity without a recreation Bundle lost the 0.5 intra-image position and
returned 0.0. The unchanged regression passed with the persistence implementation.
Additional tests exercise real XML cold reads, chapter isolation, viewport changes, pending
decode, hidden/split changes, manual navigation, stale progress, completion, incognito,
concurrent writers and bounded eviction. These checks do not replace physical-device
rotation, database/source integration, or actual image-decoder testing.
