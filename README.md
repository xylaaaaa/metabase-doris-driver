<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Metabase Doris Driver

[![CI](https://github.com/xylaaaaa/metabase-doris-driver/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/xylaaaaa/metabase-doris-driver/actions/workflows/ci.yml)

A standalone, analytics-focused Metabase community driver for Apache Doris. It connects to a Doris FE through the MySQL
protocol and models Doris metadata as `catalog -> database -> table`.

Release target: **`v1.0.0`**

## Compatibility

The v1.0.0 driver preserves the complete 29-capability contract advertised by the legacy official Doris driver. An
automated parity test locks those capability values, including schema and field discovery, joins, date and text
expressions, timezone handling, standard deviation, and the capabilities that remain intentionally disabled.

This driver also adds verified behavior beyond that baseline:

- advanced Query Builder support for full joins, approximate percentile/median, regular-expression extraction,
  string splitting, timezone conversion, and `LAG`/`LEAD` window offsets;
- integer and floating-point expression casts;
- identifiers containing spaces;
- microsecond-precision current time through `NOW(6)`;
- Doris type aliases and integer display widths, including `DATEV2`, `JSONB`, and types such as `INT(11)`;
- richer field metadata for nullability, defaults, comments, and auto/generated flags when Doris exposes those
  values;
- Doris global/system timezone discovery;
- Doris-compatible connection defaults and error messages.

Validated Metabase versions:

| Metabase | Automated driver suite | Live Doris integration |
| --- | --- | --- |
| `0.59.6.3` | Passed | Not run |
| `0.60.1` | Passed | Passed |
| `0.60.2.2` | Passed | Not run |

These are exact tested versions, not a claim of compatibility with every later Metabase release.

## Catalog And Metadata Support

The driver supports the `internal` catalog and external catalogs exposed by Doris.

- Internal catalog field sync uses one ordered, streaming query against `information_schema.columns`. This avoids one
  metadata round trip per table.
- External catalog field sync uses Doris `SHOW DATABASES`, `SHOW TABLES`, and `SHOW FULL COLUMNS` statements because
  connector-backed metadata is not uniformly available through `information_schema`.
- Include/exclude filters can limit synchronized databases. System schemas are excluded by default.
- `ARRAY`, `MAP`, and `JSON` retain useful top-level Metabase types. `STRUCT`, `VARIANT`, `HLL`, and `BITMAP` remain
  visible as opaque fields. Nested child fields are not unfolded.

External catalog completeness depends on what the selected Doris connector exposes. In particular, Doris can return
empty `EXTRA` and `GENERATION_EXPRESSION` values through `information_schema.columns`. In that case Metabase cannot
identify auto-increment or generated columns even though the driver preserves those flags when present.

## Requirements

- Java 21
- Metabase `0.59.6.3`, `0.60.1`, or `0.60.2.2`
- A reachable Apache Doris FE MySQL protocol endpoint, normally port `9030`
- Clojure CLI only when building or running the test suite from source

## Download And Install

The GitHub release asset is created only after the `v1.0.0` tag is published. Once that release exists, download it
from [GitHub Releases](https://github.com/xylaaaaa/metabase-doris-driver/releases/latest). The expected asset name is:

```text
doris.metabase-driver-v1.0.0.jar
```

Until the tag is published, build the artifact from source; do not treat the Releases link as proof that a release is
already available.

Copy the downloaded or locally built JAR into Metabase's plugin directory and restart Metabase:

```bash
cp target/doris.metabase-driver.jar /path/to/metabase/plugins/
java -jar /path/to/metabase.jar
```

The Metabase startup log should report that the `doris` driver was registered. Add a database in the Metabase admin UI
and select **Apache Doris**.

## Build

From the repository root:

```bash
clojure -T:build jar
```

The source-only community-driver artifact is written to:

```text
target/doris.metabase-driver.jar
```

## Test

Run the default suite against Metabase `0.60.1`:

```bash
bash scripts/run-unit-tests.sh
```

Run the complete validated matrix:

```bash
for version in 0.59.6.3 0.60.1 0.60.2.2; do
  METABASE_TEST_VERSION="$version" bash scripts/run-unit-tests.sh
done
```

The test script downloads the selected official Metabase JAR into the ignored `.cache/test-deps/` directory and checks
its pinned SHA-256 digest before executing the suite. See [TESTING.md](TESTING.md) for test scope, live prerequisites,
and the release gate.

## Connection Settings

| Setting | Meaning |
| --- | --- |
| Host | Doris FE host |
| Port | Doris FE MySQL protocol port; defaults to `9030` |
| Catalog | Doris catalog; defaults to `internal` |
| Database | Optional database; leave empty to sync visible databases |
| Username / Password | Doris credentials |
| SSL | Enable JDBC TLS |
| Sync Schemas Include | Optional comma-separated database allowlist |
| Sync Schemas Exclude | Optional comma-separated database denylist; exclusion wins |
| Additional options | Additional MariaDB JDBC URL parameters |

When `Database` is set, metadata sync stays within that database. Without it, the driver discovers visible databases
inside the selected catalog and applies the include/exclude filters before listing tables.

## Deliberate Limits

- Metabase uploads, writeback actions, and persisted models are not supported. Native SQL runs with the configured
  Doris user's permissions; use a read-only Doris account when writes must be prevented.
- Field-filter, card-reference, and table-reference native template tags are not advertised.
- Primary-key, foreign-key, index, and table-privilege metadata are not synchronized.
- Nested fields inside Doris complex values are not expanded for Query Builder.
- Percentile and median use `PERCENTILE_APPROX`, so their results are approximate.
- Regular-expression lookahead and lookbehind are not advertised.
- Named timezone conversion depends on the timezone data available to the connected Doris deployment.
- External catalog behavior and metadata completeness remain connector-dependent.

The capability parity claim covers the legacy driver's 29 advertised capability flags. It does not imply support for
features the legacy driver also disabled, untested future Metabase versions, or every external catalog implementation.

## Repository Layout

```text
.
|-- .github/workflows/       # CI, release, and optional self-hosted integration
|-- resources/               # Metabase plugin manifest and icon
|-- scripts/                 # Unit and live validation helpers
|-- src/metabase/driver/     # Driver implementation
|-- test/metabase/driver/    # Compatibility and behavior tests
|-- build.clj
|-- deps.edn
|-- README.md
|-- TESTING.md
`-- IMPLEMENTATION-RESULTS.md
```

Implementation evidence for this release target is recorded in
[IMPLEMENTATION-RESULTS.md](IMPLEMENTATION-RESULTS.md).
