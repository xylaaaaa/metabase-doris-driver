# v1.0.1 release notes (candidate)

The v1.0.0 JAR fails to finish loading on Metabase 0.63.16 because it unconditionally registers
`metabase.driver/describe-table-fks`, which Metabase 0.63 removed. This candidate includes the existing master runtime
guard, allowing Doris metadata synchronization to run on 0.63.16 while retaining the legacy method on older versions.
It addresses [issue #7](https://github.com/xylaaaaa/metabase-doris-driver/issues/7).

The removed `:describe-fks` capability is also registered conditionally. Foreign-key metadata remains disabled through
`:metadata/key-constraints`. The native-parameter tests now exercise the API used by the selected Metabase runtime,
and both CI workflows include the exact 0.63.16 runtime with a pinned SHA-256.

Since v1.0.0, master also improved invalid-credential messages, SQL and bound-parameter error display, Connector/J
2.x/3.x parameter parsing, and error line numbers. These existing master changes are included in the candidate.

## Validation

| Metabase | Automated source suite | Exact candidate JAR suite | Live internal-catalog smoke |
| --- | --- | --- | --- |
| 0.59.6.3 | 85 tests / 402 assertions; passed | Not run | Not run |
| 0.60.1 | 85 / 402; passed | 85 / 402; passed | Passed |
| 0.60.2.2 | 85 / 402; passed | Not run | Not run |
| 0.63.16 | 85 / 400; passed | 85 / 400; passed | Passed |

All suites have zero failures and zero errors. They include mocked JDBC/metadata behavior. The separate live runs on
2026-10-05 loaded the candidate from real Metabase plugin directories and used an existing Doris teaching database
(image 4.1.3; FE reports `doris-4.1.3-rc02-7126cf65d96`). They verified connection validation, schema sync, native
aggregation, grouped Query Builder, approximate percentile, `NOW(6)`, Unicode, a result alias containing spaces, and
numeric native parameters including optional SQL blocks. Source operations were metadata reads and SELECT only.

Compatibility claims apply to these exact Metabase versions and this tested scope. External catalogs and source
table/column names containing spaces were not retested live for this candidate. This is not a blanket claim for every
0.63.x version, Doris version, or connector. Details and release gates are in [TESTING.md](TESTING.md).

## Installation after publication

1. Download `doris.metabase-driver-v1.0.1.jar` and its checksum from the v1.0.1 release.
2. Keep one Doris driver JAR in the Metabase plugin directory, replacing the previous Doris driver JAR.
3. Restart Metabase and confirm the `doris` driver loads and the selected database's metadata sync completes.

Until the release is published, build the candidate using `clojure -T:build jar`; the output is
`target/doris.metabase-driver.jar`. Java 21 is required. This document is a prepared draft, and no v1.0.1 release or tag
was published as part of the validation work.
