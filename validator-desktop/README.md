# Standalone Java EA-PDF validator

This command-line tool checks one PDF at a time. It builds into one
runnable JAR. **Java 21 or later is the only runtime installation needed.**
No Python, Docker, browser, server, source checkout or separate rule files are
needed. The PDF/A engine, its libraries, the EA-PDF rule descriptions and the
selected ISO 32005 profile are built into the JAR.

## Current state

A file can now receive **PASS** when the required checks complete successfully.
Actual violations produce **FAIL**. Required comparisons with unavailable evidence
still produce **INCOMPLETE**. Operational errors, cancellation and timeouts also
produce **INCOMPLETE**, unless a completed check has already proved a violation.
Finding wording and occurrence counts can differ because the two engines
aggregate individual observations differently; rule decisions are compared for
every file. This is automated validation, not independent certification.

Checks include PDF/A, selected ISO 32005 structure rules, EA-PDF identification,
XMP types, catalog/profile settings, original and embedded file bytes, source
matching, MIME body and attachment metadata, document parts, structure ownership,
parent-tree correspondence, attachment annotations and collection children.
The existing limits on unsupported original-source formats, ambiguous ownership,
large structure inspections and isolated attachment evidence remain visible.

The validator does not display emails, follow links, or connect to email accounts.

## Collection processing

Collection members are checked by four persistent JVM processes. The coordinator
reads the parent PDF serially, stages at most eight members, and merges child
reports in collection order. Nested collections stay within the assigned worker;
there is no recursive process multiplication. Every PDF/A, ISO 32005 and EA-PDF
check still runs. Worker errors remain incomplete findings, never successful checks.

Workers recycle after 128 child PDFs to release accumulated parser/JIT state.
Each defaults to one quarter of the coordinator heap, bounded between 128 MiB
and the current available-memory share (four workers use their own heaps in addition to
the coordinator). `VALIDATOR_CHILD_HEAP_MIB` overrides the per-worker allowance.
`VALIDATOR_CHILD_PROCESSES=1` selects the serial path for comparisons; the normal
value is `4`. These are environment variables or Java system properties, not
new command-line arguments. Cancellation stops workers and removes staged files.
Progress reports include completed and total collection members.

## Build

From the repository root, using Java 21 and Maven:

```sh
mvn -f validator-desktop/pom.xml verify
```

On Linux, the JAR is written to `/tmp/eapdf-validator-build/eapdf-validator-2.4.0-beta.1.jar`.
Override the build directory with `-Deapdf.build.directory=/tmp/your-build`.
No build files are written to the creator's `target/` directory.
`verify` checks the final shaded JAR's manifest, ZIP CRCs, bundled runtime classes,
resources and Java 21 entry point from a clean directory. The JAR launches
`eapdf.JavaValidation`.

To build, verify and atomically replace the distributable:

```sh
python3 scripts/package_validator.py
(cd dist && sha256sum -c eapdf-validator-2.4.0-beta.1.jar.sha256)
```

Publish the executable `eapdf-validator-2.4.0-beta.1.jar`, not the unshaded `original-` JAR.
The packaging command verifies the build before replacing the distributable.

## Run

```sh
java -jar /path/to/eapdf-validator-2.4.0-beta.1.jar input.pdf validation-report.json
```

The JAR includes its runtime libraries and validation rules. It also runs in a
headless Java 21 environment.

The exit code is `0` for **PASS**, `2` for **FAIL** or **INCOMPLETE**, and `1` if
the command receives the wrong number of arguments. Read the report's summary
to distinguish a failed check from an incomplete run.

The validator reads the input PDF and streams the complete report to the
specified JSON file. It also writes `.progress.json` and `.summary.json` sidecars.
Private scratch prefers disk storage when the system temp folder uses RAM.
Collection members are checked recursively with cycle detection and byte
accounting.

On Linux, workers use the inherited hard open-file allowance. An explicit
`VALIDATOR_MAX_OPEN_FILES` can lower it. Work files/reports default to one eighth
of free working-disk space and aggregate scratch to one quarter. The controller
passes the same allowances to its worker and collection children.
`VALIDATOR_MAX_WORK_FILE_BYTES` and `VALIDATOR_MAX_SCRATCH_BYTES` override these
shares. Regeneration divides its disk allowance among concurrent jobs instead.
The Linux virtual-address allowance includes admitted disk mappings in addition
to the heap and native headroom; mapped file size is not resident RAM usage.
Disk-streamed attachment decoding follows the work-file allowance. Paths that
require an attachment byte array retain a heap-based allowance (one quarter);
XMP defaults to one sixteenth of the heap, subject to Java array capacity.
Explicit stream settings remain available. Exhausted budgets produce incomplete checks, not invented
conformance failures. Real missing source information can still produce failures.

Set the JVM heap with `-Xmx` before `-jar` when a large PDF needs more memory.
Large MIME sources share one disk file instead of retained body arrays.

The veraPDF rule predicates and bundled profiles are unchanged. A source-included
adaptation spills exact visited identities to disk; a bounded glyph cache preserves
PDF/A-1 inventory semantics. See THIRD_PARTY_NOTICES.md. These are local resource
changes, not an upstream release or independent certification.

## Checks for developers

Run `mvn -f validator-desktop/pom.xml verify` to test the validator and its
runnable JAR. Versions and bundled rule/profile hashes are checked before use.
The PDF/A library runs directly inside the validator.
