# Historical eager-fingerprint tests

These two files preserve the original experiment and regression evidence from local master.
They are intentionally outside the Android source set: they assert the retired eager startup/import
preparation lifecycle and a blocking `open` display API. They are not executed or packaged.

The production integration uses `ReaderAutomaticMatcher` and `ReaderCheckedStore` to keep display
nonblocking and persist complete decisions. `ReaderRuleFingerprintCache` is retained only as a lazy,
16 MiB derived-evidence tier inside optional/background matching; save/import/startup do not prepare it.
The current integrity/bounds/import tests remain in `ReaderFingerprintCacheTest`. Corpus exact-pixel
and retained-region acceptance remains in `ReaderImageCorpusTest`; whole-corpus latency, persistence
and downloaded sequences are covered by `ReaderCheckedCorpusTest` and `ReaderDownloadedSequenceTest`.

No user sample images, credentials, APKs or raw test output are stored here. The original commits and
all prior raw evidence remain retained separately. Native tests require an approved Android test host.
