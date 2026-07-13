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

# Metabase Doris Driver v1.0.0 Readiness Results

Date: 2026-07-13

## Outcome

The driver implementation covers the complete capability contract of the legacy official Doris driver and adds tested
metadata, temporal, and Query Builder behavior. The exact 29 legacy capability flags are locked by an automated parity
test, and the complete driver suite passes against Metabase `0.59.6.3`, `0.60.1`, and `0.60.2.2`.

A live end-to-end run on Metabase `0.60.1` loaded the driver and exercised a real Doris FE. No live run is claimed for
the other two Metabase versions, and no compatibility claim is made for untested future releases.

This document records release evidence. It does not claim that a GitHub `v1.0.0` release or downloadable asset already
exists; those are created only when the release tag is published successfully.

## Legacy Compatibility Baseline

The parity test verifies all 29 values from the legacy Doris capability map. The supported baseline includes:

- schemas and field discovery;
- left, right, inner, and full joins;
- integer, float, date, text, and literal expressions;
- current time, datetime difference, timezone conversion, and session timezone setting;
- identifiers with spaces, split-part, percentile, standard-deviation, and offset window functions.

It also preserves the legacy driver's intentional `false` values for unsupported behavior such as FK/index discovery,
table privileges, uploads, persisted models, nested field columns, impersonation, case-sensitivity filter controls, and
regex lookaround syntax.

Capability parity means the new driver does not regress the legacy advertised contract. It does not turn disabled
legacy features into supported features or guarantee every behavior outside the tested paths.

## Additional v1.0.0 Behavior

Beyond the compatibility baseline, the implementation includes:

- advanced Query Builder translation for approximate percentile/median (`PERCENTILE_APPROX`), first regex match
  (`REGEXP_EXTRACT`), `SPLIT_PART`, `CONVERT_TZ`, and `LAG`/`LEAD`;
- correct integer and floating-point casts;
- quoted identifiers containing spaces;
- microsecond-precision `NOW(6)` with datetime type information;
- Doris aliases and parameterized type names such as `DATEV2`, `JSONB`, `INT(11)`, and `BIGINT(20)`;
- column nullability, default, comment, auto-increment, and generated-column parsing when supplied by Doris;
- system/global database timezone discovery rather than a hard-coded timezone;
- connection defaults compatible with the Doris FE MySQL endpoint and clearer Doris error messages.

## Metadata Architecture

Internal and external catalogs intentionally use different discovery implementations behind the same Metabase driver
contract:

| Path | Field discovery | Reason |
| --- | --- | --- |
| `internal` catalog | One ordered streaming query over `information_schema.columns` | Avoids one query per table and bounds materialization |
| External catalog | Catalog-qualified `SHOW DATABASES`, `SHOW TABLES`, and `SHOW FULL COLUMNS` | Connector-backed column metadata is not uniformly exposed through `information_schema` |

Schema include/exclude filtering is applied before external table enumeration. Per-table external metadata errors are
isolated so one unsupported view or table does not discard fields from the rest of the schema.

Doris complex columns remain visible at the top level:

| Doris type | Metabase type |
| --- | --- |
| `ARRAY` | `type/Array` |
| `MAP` | `type/Dictionary` |
| `JSON` / `JSONB` | `type/JSON` |
| `STRUCT`, `VARIANT`, `HLL`, `BITMAP` | `type/*` |

Nested child-field expansion is outside the v1.0.0 scope.

## Automated Verification

The same driver suite was executed with each pinned Metabase application JAR:

| Metabase | Driver suite | Scope |
| --- | --- | --- |
| `0.59.6.3` | Passed | Compatibility, connection, sync, types, Query Builder, timezone, errors |
| `0.60.1` | Passed | Compatibility, connection, sync, types, Query Builder, timezone, errors |
| `0.60.2.2` | Passed | Compatibility, connection, sync, types, Query Builder, timezone, errors |

The runner verifies the SHA-256 digest of each downloaded Metabase JAR. CI also builds the source-only driver artifact,
checks required JAR entries, validates shell syntax, and parses repository YAML.

## Live Metabase 0.60.1 Verification

The built driver was installed into a live Metabase `0.60.1` plugin directory and used against a live Doris FE. The
observed end-to-end checks were:

- Metabase startup registered the Doris driver;
- connection validation succeeded on the Doris FE MySQL endpoint;
- a complete internal-catalog metadata sync finished;
- integer width metadata mapped to Metabase integer types;
- native SQL returned expected rows and preserved microsecond `NOW(6)` behavior;
- character length treated multibyte text as characters rather than bytes;
- grouped Query Builder aggregation executed successfully;
- approximate percentile Query Builder SQL generated and executed successfully;
- a table and columns containing spaces generated quoted SQL and executed successfully;
- a Query Builder current-time expression generated `NOW(6)`;
- global/system timezone discovery returned the Doris database timezone.

These checks establish the primary v1.0.0 runtime path on Metabase `0.60.1`. They are not evidence for every Doris
external connector, every table format, or versions of Metabase outside the automated matrix.

## Known Metadata Limitation

The driver can preserve auto-increment and generated-column flags only when Doris exposes them. In the validated Doris
environment, `information_schema.columns.EXTRA` was empty and `GENERATION_EXPRESSION` was `NULL` for columns where
other Doris metadata commands could provide more detail. Therefore real internal-catalog rows may still show
`database_is_auto_increment=false` and `database_is_generated=false`.

This is a source-metadata limitation, not an unhandled driver value: unit tests verify both flags when non-empty values
are returned. External connectors can have additional completeness differences.

## Release Interpretation

The code and verification evidence are suitable for a `v1.0.0` release candidate with these boundaries:

- analytics-focused access with Metabase write features disabled; enforcing read-only native SQL requires a read-only
  Doris account;
- exact support claims limited to Metabase `0.59.6.3`, `0.60.1`, and `0.60.2.2`;
- live end-to-end evidence limited to Metabase `0.60.1`;
- external catalog metadata and query behavior remain connector-dependent;
- unsupported legacy capabilities remain unsupported;
- the release does not exist until the tag workflow completes and publishes its artifact.

The reproducible commands and final publication checklist are in [TESTING.md](TESTING.md).
