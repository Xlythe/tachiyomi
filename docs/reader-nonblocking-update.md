# Nonblocking reader update

This narrow update is based on delivered urgent commit 82a6ae276. It preserves
its crash, immediate-hide and loading-geometry fixes. Original images and rules
remain intact, and exact saved edits apply immediately. Automatic comparisons
run in one bounded background worker; new matches may appear after the original
page is visible. Stale results after edits/restore are rejected. The queue holds
at most two pending samples totalling 16 MiB, one active sample and 16 MiB of
rendered results. Larger samples remain visible and can be marked manually.

Matcher thresholds and strip decoder are unchanged. The experimental exact-whole
matcher, persistent fingerprints and preparation pipeline are excluded. This
restores reading responsiveness while that separate work remains under audit.
Watermark learning/polish and server reporting are not implemented. No phone
installation, original-image rewrite, upload of reading data or redownload is
needed. An ordinary signed app update preserves existing data.
