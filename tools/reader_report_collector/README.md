# Explicit spam report collector (not deployed)

Only explicit whole-page hide and band-crop commits enqueue reports. Automatic detections,
imports, restores, watermark edits and background reading do not report. The application JSON
has exactly `image_hash` (encoded image SHA-256), `series_name` and `chapter_number`.
It contains no image bytes, selection coordinates, URLs, source IDs or device identifiers.

The client outbox survives process restarts, deduplicates identical reports, retains failed
requests with capped exponential backoff, and removes them only after an authenticated
collector acknowledgement. It holds at most 1,000 records / 8 MiB; when full it refuses the
new report with a visible warning while preserving the image rule and prior reports. Corrupt
records are held for diagnosis, never silently discarded. App private storage is excluded from
Android backup by the existing manifest. No endpoint or credential is shipped or generated.

Transport is inactive until an operator provisions both `endpoint` and `credential` in the
separate private `reader_report_transport` preferences. The endpoint must be HTTPS with
path `/v1/spam-reports`, no query, fragment or embedded credentials. TLS validation remains
standard, redirects are refused, and User-Agent is fixed to avoid a device/build identifier.
Provisioning UI and a tested deployment route are still pending; do not edit preferences
on a user's device or enable reporting as part of local tests.

The standard-library Python service listens on **loopback only**, authenticates an
operator-provided bearer token and validates the exact field allowlist. SQLite receipts are
idempotent across restarts. It keeps up to 100,000 records; capacity refusal leaves the client
report queued. Server-generated receipt and arrival time are local operational metadata,
not extra client payload. HTTP access logging is disabled. Do not enable reverse-proxy
access logs containing credentials, payloads, reading history or device identifiers.

Deployment remains **pending approval**: service account/path, bearer provisioning,
private HTTPS route/device reachability and persistent service/access changes must be reviewed
and approved before applying them. No credentials, network rules, service units or endpoint have been created. No live reports
have been sent and no historical rules/corpus are uploaded automatically. This collector
does not generate or distribute global spam rules.

Local test: `python -m unittest -v test_collector.py`. Tests use disposable SQLite files and an
ephemeral loopback HTTP server; they do not contact Ubuntu or any external service.
