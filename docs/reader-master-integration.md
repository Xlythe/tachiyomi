# Reader master integration, October 2026

This integration preserves the delivered reader fixes and reconciles the local branches without rewriting their commits.

- Reader display and explicit saved choices do not wait for automatic classification. Loading geometry, zero-height hidden rows, UI-thread visibility publication and the parent-owned RecyclerView layout parameters are retained.
- Completion uses visible terminal content while persisting original page identity. Unknown pending suffixes cannot imply completion.
- Checked results persist for clean and matched images with content hash and matcher/rule provenance. Rule changes invalidate clean results. Download prechecks traverse existing completed chapters in bounded resumable batches; incomplete or failed pages remain unknown. No chapter is redownloaded.
- Whole-page pixel-identical samples can match reencoded images and top/bottom embeds. Pixel probes and full ARGB hashes must agree. Approximate matching keeps its conservative texture and agreement thresholds, with the detailed thin-band tier retained.
- Template fingerprints are lazy and bounded to 16 MiB, with a 128-entry metadata cache. Large optional signature allocation is rejected before allocation. Lossy image strip decoding retains its original semantics; bounded full decoding is restricted to lossless PNG.
- Manual watermark selection retains source-coordinate alignment, pinch transitions, preview, cancel and undo. This does not add trained detection or guarantee reconstruction of original pixels.
- The optional spam outbox and standalone collector source are included but inactive. Only explicitly reported image hash, series name and chapter number are permitted payload fields. No endpoint or credential is provisioned; no uploads or server activation occur as part of this integration.

Historical eager-fingerprint corpus/coordinator prototypes are preserved under `tools/reader_fingerprint_prototype`, outside active Android test sources. Current reader, corpus and cache regressions remain under `app/src/androidTest`. A compile check is not an instrumentation run. Private corpus data, credentials, APKs and raw failed evidence are not committed.

## Build qualification limits

The public pinned FlexibleAdapter `c8013533` Maven coordinate returned HTTP 404 on both JitPack hosts during integration. Local qualification uses the already retained source-built artifact through a private init script. That cache and the separate dependency checkout are not committed here. A clean GitHub runner may fail dependency resolution independently of these reader changes; no workflow or runner configuration was changed to conceal it.

Android test sources are compiled in this integration, but installation and execution remain separately restricted. JVM test passes do not establish gesture behavior or full-corpus performance on a device.
