# Persistent reader checks — source candidate, not released

Integrated with delivered seams/hide `df16b27d2`, originally based on `fc001f8bb`; keeps its foreground display independent of native automatic
matching. No original chapter/download file is modified.

`ReaderCheckedStore` holds clean decisions and inferred rendered decisions in private
`reader-checked-v1` storage, limited to 4,096 entries / 64 MiB, with 16 MiB per payload.
Keys include the encoded image SHA-256, matcher/decoder version (including OS build) and
the semantic rule corpus. The same encoded content can reuse a decision in another chapter
or after process restart. A visually similar image with different encoded bytes still needs
comparison. Manual exact edits are checked first and always override inferred results.

Clean decisions require the same corpus. Positive decisions retain the hashes and semantic
versions of supporting rules. Removing/changing a supporting rule invalidates the result.
An unrelated added rule preserves a proven whole hide; a cached crop can display immediately
while it is checked for additional bands. Metadata labels/timestamps do not invalidate results. Whole hides retain the renderer's valid one-row PNG alongside their zero-height display decision.
Errors, missing templates, cancellation, unsupported images and incomplete checks never
become a clean verdict. Checksums cover stored metadata and rendered bytes; replacement is
staged/fsynced with backup recovery. Only derived cache files are evicted.

Completed background downloads enqueue a persisted local chapter cursor after final
rename/archive. One worker reads complete local page files or seeks CBZ entries without
extraction. It shares deduplicated comparison work with the reader, and waiting visible
pages take priority over download checks. Queue storage is capped at eight chapters;
overload drops the oldest precheck job, leaving those pages unknown until opened. Individual
pages above 16 MiB are skipped as unknown. Failed jobs retry with capped backoff; a cursor
survives restart. Generation guards prevent an old worker from advancing a requeued chapter.

Before rendering, the reader checks exact edits, ready RAM results and the persistent cache.
Unknown pages display promptly and may change when background matching completes. This
does **not** promise no flash for unseen content, a changed corpus, an evicted entry, or a
chapter whose prechecks are incomplete. It never reinstates serial 40-second display waits.
Future prefetch tuning must preserve that constraint.

Validation so far: standalone JVM tests cover store restart/cross-chapter reuse, invalidation,
interrupted/corrupt writes, error states, size/entry eviction, queue deduplication/priority and
stale completion. Android integration, real completed directory/CBZ downloads, process
recreation and full-corpus cold/warm timing/memory are **pending**. Do not ship this source
candidate until those pass. Compilation and disposable-emulator qualification use a separate private G: task directory; delivered builds and original evidence stay intact.
