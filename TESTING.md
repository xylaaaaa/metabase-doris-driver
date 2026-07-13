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
```

The v1.0.0 readiness matrix is:

| Metabase | Result |
| --- | --- |
| `0.59.6.3` | Passed |
| `0.60.1` | Passed |
| `0.60.2.2` | Passed |

The suite covers:

- exact parity for all 29 legacy Doris capability flags;
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

The release target has been exercised end to end with Metabase `0.60.1` and a live Doris FE. That validation covered:

- plugin loading and Doris connection validation;
- full internal-catalog metadata sync;
- native SQL execution;
- Query Builder grouped aggregation;
- approximate percentile generation/execution;
- identifiers with spaces;
- `NOW(6)` and temporal/timezone behavior;
- integer and datetime metadata mapping.

The other two Metabase versions in the matrix are verified by the automated suite, not by a claimed live integration
run.

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

Before publishing `v1.0.0`, require all of the following on the release commit:

1. `clojure -T:build jar` succeeds and the JAR contains the manifest and driver namespaces.
2. The automated suite passes on `0.59.6.3`, `0.60.1`, and `0.60.2.2`.
3. Shell syntax and YAML validation pass.
4. A clean Metabase `0.60.1` instance loads the exact release artifact.
5. Internal metadata sync, native SQL, grouped Query Builder, percentile, spaced identifiers, and temporal behavior
   pass against a live Doris FE.
6. The plugin manifest version and Git tag both equal `1.0.0`/`v1.0.0`.
7. Only after those checks should the GitHub release asset be considered downloadable.

Do not broaden the version claim without adding the exact Metabase release to the automated matrix and rerunning the
relevant live checks.
