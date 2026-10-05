# Metabase Doris Driver Testing

This guide separates repeatable driver tests from live environment validation. Commands are run from the repository
root unless stated otherwise.

## Prerequisites

- Java 21
- Clojure CLI
- `curl` and a SHA-256 utility (`sha256sum` or `shasum`)
- A live Metabase and Doris deployment only for the integration section

## Build Check

Build the source-only community-driver JAR and inspect its required entries:

```bash
clojure -T:build jar
test -f target/doris.metabase-driver.jar
jar tf target/doris.metabase-driver.jar | grep -q 'metabase-plugin.yaml'
jar tf target/doris.metabase-driver.jar | grep -q 'metabase/driver/doris.clj'
jar tf target/doris.metabase-driver.jar | grep -q 'META-INF/LICENSE.txt'
jar tf target/doris.metabase-driver.jar | grep -q 'META-INF/NOTICE.txt'
```

## Automated Driver Suite

The supported test runner pins the Metabase download URL and SHA-256 digest for each validated version:

```bash
bash scripts/run-unit-tests.sh
```

`0.60.1` is the default. Select another validated runtime with `METABASE_TEST_VERSION`:

```bash
METABASE_TEST_VERSION=0.59.6.3 bash scripts/run-unit-tests.sh
METABASE_TEST_VERSION=0.60.1 bash scripts/run-unit-tests.sh
METABASE_TEST_VERSION=0.60.2.2 bash scripts/run-unit-tests.sh
METABASE_TEST_VERSION=0.63.16 bash scripts/run-unit-tests.sh
```

The v1.0.1 candidate matrix is:

| Metabase | Source suite | Exact candidate JAR suite | Live internal-catalog smoke |
| --- | --- | --- | --- |
| `0.59.6.3` | 85 tests / 402 assertions, passed | Not run | Not run |
| `0.60.1` | 85 / 402, passed | 85 / 402, passed | Passed |
| `0.60.2.2` | 85 / 402, passed | Not run | Not run |
| `0.63.16` | 85 / 400, passed | 85 / 400, passed | Passed |

All passing suites report zero failures and zero errors. Source suites run with the selected official Metabase
runtime; the JAR suites load driver source exclusively from the candidate artifact. Both include mocked JDBC and
metadata behavior and do not substitute for a live deployment test. Metabase 0.63.16 has two fewer assertions because
the removed `:describe-fks` feature is no longer queried; a separate test checks its registration boundary.

The suite covers:

- parity for the legacy capability flags still present in the selected runtime (29 on older versions, 28 on 0.63.16);
- legacy foreign-key API/feature registration boundaries and the disabled `:metadata/key-constraints` capability;
- JDBC URL construction, defaults, optional database/catalog selection, and timezone discovery;
- Doris-to-Metabase type mapping, aliases, parameterized integer types, defaults, and nullability;
- internal batched/streaming field discovery and external `SHOW` fallback behavior;
- schema filtering, identifiers containing spaces, and error isolation during metadata discovery;
- Query Builder SQL for joins, numeric casts, date/time operations, `NOW(6)`, percentile/median, regex extraction,
  split-part, timezone conversion, and window offsets;
- native values, parameters, Doris error humanization, and unsupported capability boundaries.

CI runs the same matrix in `.github/workflows/ci.yml`, builds the JAR, checks its contents, validates shell syntax, and
parses the YAML manifests.

## Live Integration

On 2026-10-05, the exact v1.0.1 candidate JAR was installed in the plugin directories of isolated Metabase `0.60.1`
and `0.63.16` instances. Both connected through an SSH tunnel to an existing Doris teaching deployment (image
`apache/doris:all-in-one-4.1.3`; FE reports `doris-4.1.3-rc02-7126cf65d96`). The live checks passed for:

- plugin loading and Doris connection validation;
- internal-catalog schema sync limited to one existing teaching database (39 tables discovered);
- native SQL execution;
- Query Builder grouped aggregation;
- approximate percentile generation/execution;
- a result alias containing spaces;
- `NOW(6)`, Unicode text, and the UTC session timezone;
- bigint, integer, decimal, date, and text metadata mapping;
- numeric native template tags and optional SQL blocks through each runtime's real parameter pipeline.

The SQL checks read one existing 10-row table. The source database received only metadata reads and SELECT queries;
no source objects were created, changed, or removed. Profiling and automatic query runs were disabled in the local
Metabase connection. Only the isolated local Metabase application databases were initialized or written.

The new live run did not cover external catalogs, source table/column identifiers containing spaces, generated-column
flags, named timezone conversion, every Query Builder expression, or performance at scale. The previous v1.0.0
validation record remains in `IMPLEMENTATION-RESULTS.md`; it is not additional candidate integration evidence.

Metabase `0.59.6.3` and `0.60.2.2` have automated validation only for this candidate.

### Reproduce The Internal Smoke Path

Prepare a Doris database containing the table and fields expected by `scripts/validate-local.sh`, or override the
variables shown below. Install the freshly built driver into a Metabase `0.60.1` plugin directory, start Metabase, and
run:

```bash
export METABASE_URL=http://127.0.0.1:3001
export METABASE_USERNAME=admin@example.com
export METABASE_PASSWORD='your-metabase-password'
export DORIS_HOST=127.0.0.1
export DORIS_PORT=9030
export DORIS_CATALOG=internal
export DORIS_DB=metabase_driver_test
export DORIS_USER=root
export DORIS_PASSWORD=''
export METABASE_DB_NAME='Local Doris V1 Test'
export TARGET_TABLE_NAME=metabase_v1_orders
export GROUP_FIELD_NAME=category
export METRIC_FIELD_NAME=amount
export TIME_FIELD_NAME=event_time

bash scripts/validate-local.sh
```

The helper validates health/login, database connection, synchronized table/field IDs, one native aggregate, and one
grouped MBQL query. It requires a real Metabase application database and test data; it is not part of the hermetic unit
suite.

### External Catalog Smoke Path

For an existing Metabase database entry configured against a Doris external catalog:

```bash
export METABASE_URL=http://127.0.0.1:3001
export METABASE_USERNAME=admin@example.com
export METABASE_PASSWORD='your-metabase-password'
export METABASE_DB_NAME='Doris External Catalog Test'
export DORIS_CATALOG=your_catalog
export DORIS_DB=your_database
export EXTERNAL_TABLE_NAME=your_table
export EXTERNAL_GROUP_FIELD=your_group_field
export EXTERNAL_METRIC_FIELD=your_numeric_field

bash scripts/validate-external-catalog.sh
```

External catalog outcomes depend on the selected Doris connector and its metadata. The driver uses the same public
metadata contract but deliberately falls back to catalog-qualified `SHOW` statements for this path.

## Metadata Caveat

The driver parses auto-increment, generated-column, nullability, default, and comment metadata when Doris returns it.
Current Doris deployments can leave `EXTRA` and `GENERATION_EXPRESSION` empty in
`information_schema.columns`; auto/generated detection is therefore unavailable for those rows. A test should not
assert those flags unless the source query actually returns them.

## Release Gate

Before publishing `v1.0.1`, require all of the following on the release commit:

1. `clojure -T:build jar` succeeds and the JAR contains the manifest and driver namespaces.
2. The automated suite passes on `0.59.6.3`, `0.60.1`, `0.60.2.2`, and `0.63.16`.
3. Shell syntax and YAML validation pass.
4. Isolated Metabase `0.60.1` and `0.63.16` instances load the exact release artifact.
5. Internal schema sync, native SQL, grouped Query Builder, percentile, temporal behavior, and native parameters
   pass against a live Doris FE. State the fixture and untested scope explicitly; an alias with spaces is not evidence
   for a source table/column name with spaces.
6. The plugin manifest version and Git tag both equal `1.0.1`/`v1.0.1`.
7. Review the release notes, checksum, candidate diff, and CI results, then obtain authorization to publish. The
   existing tag workflow publishes a public release automatically when a matching tag is pushed.
8. Only after the authorized release succeeds should the GitHub release asset be considered downloadable.

Do not broaden the version claim without adding the exact Metabase release to the automated matrix and rerunning the
relevant live checks.
