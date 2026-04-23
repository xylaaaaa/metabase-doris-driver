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
│   └── validate-external-catalog.sh
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
8. `Additional options`

## Support Matrix

### Supported

1. `internal` catalog metadata sync
2. `internal` catalog native query execution
3. `internal` catalog Query Builder basic aggregation, filter, and time bucketing
4. top-level complex type display during sync
5. field sync includes top-level nullability/default/comment metadata where Doris `SHOW FULL COLUMNS` provides it

### Experimental

1. `external catalog` metadata sync using:
   - `SHOW DATABASES FROM <catalog>`
   - `SHOW TABLES FROM <catalog>.<db>`
   - `DESC <catalog>.<db>.<table>`
2. `external catalog` read-only native query execution
3. `external catalog` Query Builder aggregation on table layouts that behave like regular relational tables

Experimental means the SQL path exists in the driver and is intentionally in scope, but validation still depends on the
specific external catalog backend and table type.

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
./scripts/validate-external-catalog.sh
```

The script validates:

1. Metabase health and login
2. Doris connection validation for the target external catalog
3. Metadata visibility for the configured Metabase database entry
4. A native query against the target external table
5. A grouped MBQL query using the configured group and metric fields
6. An optional temporal breakout if `EXTERNAL_TIME_FIELD` is provided

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
