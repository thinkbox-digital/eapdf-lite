# Feature inventory

Current scope is summarised here; release and unreleased status live in
[project status](PROJECT_STATUS.md). Operational details belong to the linked
guides, not duplicate release-version tables.

## Creator

- Local EML files and directories.
- Six EA-PDF profiles using PDF 2.0 / PDF/A-4f.
- Original EML and native attachments in source-backed profiles. Isolated
  profiles retain rendered content and metadata.
- HTML and plain-text rendering, bounded font and image handling, required
  EA-PDF document structure, and folder membership in collections.
- Bounded parallel processing, supervised workers, recoverable publication,
  and optional CMS signing.

See the [Lite quick start](../README.md) and [configuration](../config/README.md).

## Even More Lite reader

- Local message and folder navigation, with adjustable panes for PDF pages
  and embedded assets.
- Recursive embedded-PDF navigation and original-byte asset downloads.
- Stored message and attachment-page associations, when available.

The [reader guide](../browser-viewer/README.md) describes supported previews
and size limits.

## Validation and limits

The standalone Java validator combines EA-PDF checks with veraPDF checks and
recursive member assessment. The optional Python CLI remains for regression tests and diagnostics.

See [validation coverage](VALIDATOR_COVERAGE.md),
[security boundaries](security/HARDENING.md) and [signing](DIGITAL_SIGNATURES.md).
