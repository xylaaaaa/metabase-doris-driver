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

This directory contains a standalone Apache Doris sample driver for Metabase community driver loading.

## Scope

This is a v1, read-only driver sample with the following goals:

1. Connect to Doris through the FE MySQL protocol endpoint.
2. Sync catalogs, databases, tables, and fields using Doris-native SQL.
3. Support basic Query Builder date and aggregation behavior.
4. Expose Doris complex types at the top-level metadata layer during schema sync.
5. Preserve richer column metadata such as nullability, default values, and comments during sync.
6. Avoid unstable capabilities such as native template parameters, table privilege sync, FK sync, and index sync.

Current implementation should be understood as:

1. `internal` catalog is the primary validated v1 target.
2. `external catalog` basic sync SQL paths are already included in the driver skeleton and kept in v1 scope, but still
   require environment-specific validation per catalog type.
3. complex types are visible in synced metadata as top-level field types, but are not unfolded into nested child fields.

The connection model keeps an explicit `catalog` field so that the driver is built around Doris
`catalog -> db -> table` semantics instead of classic single-level MySQL database semantics.

## Project Layout

```text
samples/connect/metabase-doris-driver/
├── README.md
├── IMPLEMENTATION-RESULTS.md
├── deps.edn
├── build.clj
├── run-local-metabase.sh
├── scripts/
│   ├── validate-local.sh
│   ├── validate-external-catalog.sh
│   └── validate-complex-metadata.sh
├── resources/
│   ├── metabase-plugin.yaml
│   └── metabase_driver/doris/icon.svg
├── src/
│   └── metabase/driver/
│       ├── doris.clj
│       └── doris/
│           ├── connection.clj
│           ├── query_processor.clj
│           ├── sync.clj
│           └── types.clj
└── test/
    └── metabase/driver/doris_test.clj
```

## Build

This project follows the lightweight pattern used by standalone Metabase community drivers: package source and
resources into a source-only JAR, and let Metabase compile the namespaces at runtime.

Requirements:

1. Java
2. Clojure CLI

Build commands:

```bash
cd samples/connect/metabase-doris-driver
clojure -T:build jar
```

Output:

```text
target/doris.metabase-driver.jar
```

## Install

Copy the generated JAR into the Metabase `plugins/` directory and restart Metabase.

Example:

```bash
cp target/doris.metabase-driver.jar /path/to/metabase/plugins/
```

## Connection Settings

The plugin exposes these Doris-specific fields:

1. `Host`
2. `Port` (default `9030`)
3. `Catalog` (default `internal`)
4. `Database` (optional)
5. `Username`
6. `Password`
7. `SSL`
8. `Sync Schemas Include` (optional, comma-separated)
9. `Sync Schemas Exclude` (optional, comma-separated)
10. `Additional options`

Schema filtering behavior:

1. System schemas such as `information_schema`, `__internal_schema`, and `mysql` are excluded by default.
2. `Sync Schemas Include` acts as an allowlist for metadata sync.
3. `Sync Schemas Exclude` acts as a denylist for metadata sync.
4. If both are provided, the exclude list wins.
5. If `Database` is explicitly set, sync remains scoped to that single database.

Current metadata completeness behavior:

1. `database_is_nullable` and `database_required` are preserved from `SHOW FULL COLUMNS`.
2. Column comments are also copied into Metabase `description` when the external catalog exposes them.
3. JDBC external column defaults are preserved in fresh Metabase metadata sync when Doris FE includes the
   JDBC default propagation fix proposed in `apache/doris#62781`.
4. Other external catalog types still depend on the metadata completeness exposed by the underlying Doris connector.

## Support Matrix

### Supported

1. `internal` catalog metadata sync
2. `internal` catalog native query execution
3. `internal` catalog Query Builder basic aggregation, filter, and time bucketing
4. top-level complex type display during sync
5. one validated Hive/HMS-style external catalog path:
   - `test_hive2_external_sql_block_rule.tpch1_parquet.orders`
   - native query
   - grouped MBQL query
   - temporal breakout
6. one validated JDBC external catalog path:
   - `doris_jdbc_catalog.regression_test_jdbc_catalog_p0.base`
   - native query
   - grouped MBQL query
   - temporal breakout
7. one validated Iceberg external catalog path:
   - `codex_iceberg_check.format_v1.sample_parquet`
   - native query
   - grouped MBQL query
   - temporal breakout
8. one validated Paimon external catalog path:
   - `paimon_local_test.db1.all_table`
   - native query
   - grouped MBQL query
   - temporal breakout
9. field sync includes top-level nullability/default/comment metadata where Doris `SHOW FULL COLUMNS` provides it

### Experimental

1. other `external catalog` metadata sync using:
   - `SHOW DATABASES FROM <catalog>`
   - `SHOW TABLES FROM <catalog>.<db>`
   - `DESC <catalog>.<db>.<table>`
2. external catalog read-only native query execution outside the validated Hive/JDBC/Iceberg/Paimon sample paths
3. external catalog Query Builder aggregation on table layouts that behave like regular relational tables outside the
   validated Hive/JDBC/Iceberg/Paimon sample paths

Experimental means the SQL path exists in the driver and is intentionally in scope, but validation still depends on the
specific external catalog backend and table type.

### External Catalog Verification Matrix

| Catalog Type | Sample Path | Metadata Sync | Native Query | Grouped MBQL | Temporal Breakout | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Hive/HMS | `test_hive2_external_sql_block_rule.tpch1_parquet.orders` | Yes | Yes | Yes | Yes | Baseline Hive-style read path |
| JDBC | `doris_jdbc_catalog.regression_test_jdbc_catalog_p0.base` | Yes | Yes | Yes | Yes | Also used for JSON/HLL metadata validation |
| Iceberg | `codex_iceberg_check.format_v1.sample_parquet` | Yes | Yes | Yes | Yes | Parquet-backed sample with date breakout |
| Paimon | `paimon_local_test.db1.all_table` | Yes | Yes | Yes | Yes | Local sample with simple row data |
| Remote Doris | `codex_remote_variant_catalog.codex_remote_variant_db.remote_variant_t` | Yes | N/A | N/A | N/A | Used for `VARIANT` metadata verification |
| JDBC Bitmap | `doris_jdbc_catalog_query_bitmap.regression_test_jdbc_catalog_p0_query_bitmap.metric_table` | Yes | N/A | N/A | N/A | Used for `BITMAP` metadata verification |

### Metadata Verification Matrix

| Metadata Area | Status | Verified Catalogs | Notes |
| --- | --- | --- | --- |
| Schema filtering | Yes | JDBC | `include-schemas` live-verified; default system schema exclusion also active |
| Nullability / required | Yes | JDBC | Fresh metadata sync confirmed `database_is_nullable` and `database_required` |
| Comments -> description | Yes | JDBC | Fresh metadata sync confirmed `description` is populated from external comments |
| Defaults | Yes* | JDBC | Fresh metadata sync confirmed `aid=0` and `pname=其他` for `test_insert_order`; current validation depends on the Doris FE fix proposed in `apache/doris#62781` |
| ARRAY top-level type | Yes | JDBC, Paimon, Hive/HMS, Iceberg | Verified as `type/Array` |
| MAP top-level type | Yes | Paimon, Hive/HMS | Verified as `type/Dictionary` |
| STRUCT top-level type | Yes | Paimon, Hive/HMS | Verified as `type/*` |
| JSON top-level type | Yes | JDBC | Verified as `type/JSON` after Doris JDBC JSONB mapping fix |
| VARIANT top-level type | Yes | Remote Doris | Verified as `type/*` |
| HLL top-level type | Yes | JDBC | Verified as `type/*` |
| BITMAP top-level type | Yes | JDBC | Verified as `type/*` |

## Known Limitations

This v1 sample intentionally keeps several capabilities disabled:

1. Native template parameters (`{{param}}`)
2. Parameterized SQL capability advertisement
3. Table privilege sync
4. FK metadata sync
5. Index metadata sync
6. Upload / writeback actions
7. Nested-field expansion

Complex type support in this sample is intentionally limited to **display / sync visibility**:

1. `ARRAY` -> `:type/Array`
2. `MAP` -> `:type/Dictionary`
3. `JSON` -> `:type/JSON`
4. `STRUCT` / `VARIANT` / `HLL` / `BITMAP` -> `:type/*`

This means Metabase can see these columns during schema sync, but the driver does not yet unfold them into nested
fields for Query Builder.

Top-level complex type metadata has been validated against these real external catalog samples:

1. JDBC catalog:
   - `test_jni_complex_type.arr_text array<text>` -> `type/Array`
   - `base.json_col json` -> `type/JSON`
   - `bowen_hll_test.user_log_acct hll` -> `type/*`
2. Paimon catalog:
   - `complex_tab.c2 array<bigint>` -> `type/Array`
   - `complex_tab.c3 map<varchar(10),boolean>` -> `type/Dictionary`
   - `array_nested.c2 array<array<boolean>>` -> `type/Array`
   - `array_nested.c15 array<map<boolean,boolean>>` -> `type/Array`
   - `row_native_test.c_row struct<...>` -> `type/*`
3. Hive/HMS catalog:
   - `json_table.numbers array<int>` -> `type/Array`
   - `json_table.scores map<text,int>` -> `type/Dictionary`
   - `json_table.details struct<a:int,b:text,c:bigint>` -> `type/*`
4. Remote Doris catalog:
   - `remote_variant_t.v variant<...>` -> `type/*`
5. JDBC bitmap catalog:
   - `metric_table.device_id bitmap` -> `type/*`

These validated samples now cover top-level `ARRAY`, `MAP`, `STRUCT`, `JSON`, `VARIANT`, `HLL`, and `BITMAP`
metadata behavior.

## External Catalog Validation

Use the external validation helper after you have already created a Doris database entry in Metabase that points at an
external catalog.

Known FE-side sample target discovered in the current Doris environment:

```text
catalog: doris_jdbc_catalog
database: regression_test_jdbc_catalog_p0
table: test_insert_order
group field: gameid
metric field: aid
```

Example:

```bash
export METABASE_DB_NAME="Local Doris External Catalog Test"
export DORIS_CATALOG="doris_jdbc_catalog"
export DORIS_DB="regression_test_jdbc_catalog_p0"
export EXTERNAL_TABLE_NAME="test_insert_order"
export EXTERNAL_GROUP_FIELD="gameid"
export EXTERNAL_METRIC_FIELD="aid"
export EXTERNAL_DEFAULT_ASSERTIONS="aid=0;pname=其他"
./scripts/validate-external-catalog.sh
```

The script validates:

1. Metabase health and login
2. Doris connection validation for the target external catalog
3. Metadata visibility for the configured Metabase database entry
4. Optional `database_default` assertions when `EXTERNAL_DEFAULT_ASSERTIONS` is provided
5. A native query against the target external table
6. A grouped MBQL query using the configured group and metric fields
7. An optional temporal breakout if `EXTERNAL_TIME_FIELD` is provided

`EXTERNAL_DEFAULT_ASSERTIONS` uses a semicolon-separated `field=value` format. For example:

```text
aid=0;pname=其他
```

If you want to inspect the same target directly on Doris FE before involving Metabase, these SQL statements are a good
smoke set:

```sql
SHOW DATABASES FROM doris_jdbc_catalog;
SHOW TABLES FROM doris_jdbc_catalog.regression_test_jdbc_catalog_p0;
DESC doris_jdbc_catalog.regression_test_jdbc_catalog_p0.test_insert_order;

SELECT count(*) AS row_count, sum(aid) AS total_aid
FROM doris_jdbc_catalog.regression_test_jdbc_catalog_p0.test_insert_order;

SELECT gameid, sum(aid) AS total_aid
FROM doris_jdbc_catalog.regression_test_jdbc_catalog_p0.test_insert_order
GROUP BY gameid
ORDER BY gameid;
```

There is also a simple complex-type sample in the same catalog:

```text
catalog: doris_jdbc_catalog
database: regression_test_jdbc_catalog_p0
table: test_jni_complex_type
interesting field: arr_text array<text>
```

Use the complex metadata helper to verify that Metabase preserved the expected top-level `base_type`:

```bash
export METABASE_DB_NAME="Local Doris External JDBC Complex Metadata v1"
export DORIS_CATALOG="doris_jdbc_catalog"
export DORIS_DB="regression_test_jdbc_catalog_p0"
export COMPLEX_TABLE_NAME="test_jni_complex_type"
export EXPECTED_FIELD_BASE_TYPES="arr_text:type/Array"
./scripts/validate-complex-metadata.sh
```

JDBC JSON metadata can be validated the same way:

```bash
export METABASE_DB_NAME="Local Doris External JDBC JSON Base Metadata v1"
export DORIS_CATALOG="doris_jdbc_catalog"
export DORIS_DB="regression_test_jdbc_catalog_p0"
export COMPLEX_TABLE_NAME="base"
export EXPECTED_FIELD_BASE_TYPES="json_col:type/JSON"
./scripts/validate-complex-metadata.sh
```

JDBC HLL metadata can be validated the same way:

```bash
export METABASE_DB_NAME="Local Doris External JDBC HLL Metadata v1"
export DORIS_CATALOG="doris_jdbc_catalog"
export DORIS_DB="regression_test_jdbc_catalog_p0"
export COMPLEX_TABLE_NAME="bowen_hll_test"
export EXPECTED_FIELD_BASE_TYPES="user_log_acct:type/*"
./scripts/validate-complex-metadata.sh
```

Paimon complex-type metadata can be validated the same way:

```bash
export METABASE_DB_NAME="Local Doris External Paimon Complex Tab Metadata v1"
export DORIS_CATALOG="paimon_local_test"
export DORIS_DB="db1"
export COMPLEX_TABLE_NAME="complex_tab"
export EXPECTED_FIELD_BASE_TYPES="c2:type/Array,c3:type/Dictionary"
./scripts/validate-complex-metadata.sh
```

Hive/HMS complex-type metadata can also be validated:

```bash
export METABASE_DB_NAME="Local Doris External Hive OpenX JSON Metadata v1"
export DORIS_CATALOG="test_hive2_external_sql_block_rule"
export DORIS_DB="openx_json"
export COMPLEX_TABLE_NAME="json_table"
export EXPECTED_FIELD_BASE_TYPES="numbers:type/Array,scores:type/Dictionary,details:type/*"
./scripts/validate-complex-metadata.sh
```

Remote Doris `VARIANT` metadata can also be validated:

```bash
export METABASE_DB_NAME="Local Doris External Remote Variant Metadata v1"
export DORIS_CATALOG="codex_remote_variant_catalog"
export DORIS_DB="codex_remote_variant_db"
export COMPLEX_TABLE_NAME="remote_variant_t"
export EXPECTED_FIELD_BASE_TYPES="v:type/*"
./scripts/validate-complex-metadata.sh
```

JDBC bitmap metadata can also be validated:

```bash
export METABASE_DB_NAME="Local Doris External JDBC Bitmap Metadata v1"
export DORIS_CATALOG="doris_jdbc_catalog_query_bitmap"
export DORIS_DB="regression_test_jdbc_catalog_p0_query_bitmap"
export COMPLEX_TABLE_NAME="metric_table"
export EXPECTED_FIELD_BASE_TYPES="device_id:type/*"
./scripts/validate-complex-metadata.sh
```

Validated Hive sample results now recorded in `IMPLEMENTATION-RESULTS.md`:

1. native query count/sum succeeds
2. grouped MBQL query by `o_orderstatus` succeeds
3. temporal breakout by `o_orderdate` succeeds

Validated JDBC catalog sample results also recorded in `IMPLEMENTATION-RESULTS.md`:

1. native query count/sum on `base` succeeds
2. grouped MBQL query by `varchar_col` succeeds
3. temporal breakout by `date_col` succeeds

Validated Iceberg catalog sample results also recorded in `IMPLEMENTATION-RESULTS.md`:

1. native query count/sum on `sample_parquet` succeeds
2. grouped MBQL query by `city` succeeds
3. temporal breakout by `col_date` succeeds

Validated Paimon catalog sample results also recorded in `IMPLEMENTATION-RESULTS.md`:

1. native query count/sum on `all_table` succeeds
2. grouped MBQL query by `c14` succeeds
3. temporal breakout by `c12` succeeds

## Verification Status

Implementation and runtime validation results are recorded in:

- [IMPLEMENTATION-RESULTS.md](IMPLEMENTATION-RESULTS.md)

At this point the following have been verified locally:

1. `clojure -T:build jar`
2. Metabase plugin loading
3. Doris connection validation
4. Metadata sync
5. Native query execution
6. Query Builder basic aggregation, filter, and time bucketing
7. Top-level complex-type metadata validation for JDBC and Paimon external samples
