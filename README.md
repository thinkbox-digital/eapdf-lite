# EA-PDF Lite

Convert local EML files into EA-PDF using the Java command-line creator, check
the resulting PDFs with the Java command-line validator, and open them in the
Even More Lite browser reader. The creator accepts an EML file or a directory
of EML files.

## Editions

- [EA-PDF Main](https://github.com/thinkbox-digital/eapdf) — the full creator, validator and browser readers.
- **EA-PDF Lite** (this checkout) — an EML command-line creator, a Java command-line validator and the Even More Lite reader.
- [EA-PDF Secure](https://github.com/thinkbox-digital/eapdf-secure) — the experimental C2PA provenance and PAdES signing and verification edition.

## Output profiles

| Layout | With original source and native attachments | Isolated |
| --- | --- | --- |
| One PDF per message | `1s` | `1si` |
| Multiple messages in one continuous PDF | `1m` | `1mi` |
| Collection with individually openable message PDFs | `1c` | `1ci` |

All six profiles use PDF 2.0 / PDF/A-4f. Isolated profiles omit original message
sources and native attachment contents. Visible email text, addresses, metadata
and inline images can still appear in isolated files.

## Build

Download the creator and validator JARs and their SHA-256 checksums from the
[Lite release](https://github.com/thinkbox-digital/eapdf-lite/releases/tag/v2.4.0-beta.4).
The commands below build the JARs from source into temporary folders.

Use Java 21 and Maven from the checkout root.

```sh
mvn -Deapdf.build.directory=/tmp/eapdf-lite-creator-build verify
mvn -f validator-desktop/pom.xml -Deapdf.build.directory=/tmp/eapdf-lite-validator-build verify
```

## Convert and validate

Run the creator JAR without arguments to see a quick-start guide and available options.

Run the commands from the checkout root. Replace the example input paths with
your own EML file or directory. Lite Creator generates all six EA-PDF profiles
with their required document structure. PDF/UA generation, embedded search
indexes, and PREMIS sidecars are outside the Lite Creator feature set.

```sh
java -jar /tmp/eapdf-lite-creator-build/emailtopdfa-lite-2.4.0-beta.4.jar \
  --jobs 2 --out /tmp/eapdf-lite-output /path/to/message.eml

java -jar /tmp/eapdf-lite-creator-build/emailtopdfa-lite-2.4.0-beta.4.jar \
  --jobs 2 --out /tmp/eapdf-lite-output --archive-profile c \
  --archive-output /tmp/eapdf-lite-output/archive_1c.pdf /path/to/eml-directory

java -jar /tmp/eapdf-lite-validator-build/eapdf-validator-2.4.0-beta.4.jar \
  /tmp/eapdf-lite-output/archive_1c.pdf /tmp/eapdf-lite-output/archive_1c.report.json
```

See the [Java validator guide](validator-desktop/README.md) for report details and exit codes.

Use `--omit-source` for `1si`. For batch archives, `--archive-profile m`, `mi`,
`c` or `ci` selects `1m`, `1mi`, `1c` or `1ci`. Read the validator report for the
EA-PDF and PDF/A checks, including collection children. Keep your original email
files.

## Read the PDF

Even More Lite opens local PDFs and lets you browse messages, folders, pages
and embedded assets. Its limit is 32 MiB and 2,048 pages per PDF. See the
[Even More Lite reader instructions](browser-viewer/README.md).

Download the Chromium, Firefox or web package from the
[Lite release](https://github.com/thinkbox-digital/eapdf-lite/releases/tag/v2.4.0-beta.4).
Follow the [installation instructions](browser-viewer/INSTALL.txt) for your browser.

## Release package acceptance

Run the same acceptance check locally with the candidate JARs and viewer ZIPs.
Use JDK 21, Python 3.12 with `pikepdf`, and the browser viewer's Node dependencies
and Playwright Chromium. For Firefox set `FIREFOX_PATH` and `GECKODRIVER_PATH`
if the executables are outside PATH. For the desktop JAR use a graphical session
or `xvfb-run` and set `JAVAFX_LIB` when JavaFX is installed separately.

```sh
python3 -B scripts/check_release_packages.py \
  --creator "$CREATOR_JAR" --validator "$VALIDATOR_JAR" \
  --viewer "$CHROMIUM_ZIP" --viewer "$FIREFOX_ZIP" \
  --desktop-viewer "$DESKTOP_JAR" --output "$(mktemp -d)/acceptance"
```

Pass each browser or web ZIP with a separate `--viewer` argument. The gate
generates all six profiles from dedicated EML fixtures, checks PDF structure
and payloads, requires standalone EA-PDF and veraPDF PDF/A-4f success, rejects
missing and invalid collection children, and opens the same PDFs in the
package readers. Successful checks write `SHA256SUMS` beside the logs and reports.

For a GitHub release, dispatch **Release package acceptance** on the
`v<VERSION>` tag with **publish** enabled. Its publish job uploads the
verified packages after rechecking their hashes.
The Chrome Store workflow also requires the successful acceptance run ID.

## License

[Apache License 2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md)
for bundled libraries.
