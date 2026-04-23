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

# Metabase Doris Driver Implementation Results

Date: 2026-04-22

This document records what was implemented in the local `metabase-doris-driver` repository and what was verified
against a live Metabase instance and a live Apache Doris FE.

## What was implemented

The repository now contains a standalone Metabase community driver project with these core pieces:

1. Plugin manifest
   - `resources/metabase-plugin.yaml`
2. Source-only build configuration
   - `deps.edn`
   - `build.clj`
3. Driver entrypoint
   - `src/metabase/driver/doris.clj`
4. Doris connection logic
   - `src/metabase/driver/doris/connection.clj`
5. Doris metadata sync logic
   - `src/metabase/driver/doris/sync.clj`
6. Doris type mapping
   - `src/metabase/driver/doris/types.clj`
7. Minimal SQL dialect hooks
   - `src/metabase/driver/doris/query_processor.clj`
8. Pure helper tests
   - `test/metabase/driver/doris_test.clj`
9. Local run helper
   - `run-local-metabase.sh`

## Implemented v1 scope

The current driver implements the intended v1 baseline:

1. `:doris -> :sql-jdbc` standalone driver registration
2. Doris FE MySQL protocol connectivity
3. Explicit `catalog` and `dbname` connection model
4. Doris-native metadata sync via:
   - `SHOW DATABASES`
   - `SHOW DATABASES FROM <catalog>`
   - `SHOW TABLES FROM <db>`
   - `SHOW TABLES FROM <catalog>.<db>`
   - `SHOW FULL COLUMNS FROM <table> FROM <db>`
   - `SHOW FULL COLUMNS FROM <catalog>.<db>.<table>`
5. Doris type mapping for:
   - boolean
   - numeric types
   - decimal
   - string-like types
   - date / datetime / timestamp
   - `ARRAY` mapped to `:type/Array`
   - `MAP` mapped to `:type/Dictionary`
   - `JSON` mapped to `:type/JSON`
   - `STRUCT` / `VARIANT` / `HLL` / `BITMAP` downgraded to `:type/*`
6. Basic SQL dialect hooks for:
   - MySQL quoting
   - `from_unixtime`
   - current datetime
   - `date_trunc`
   - `datetime-diff`
7. Richer field metadata propagation for:
   - `database-default`
   - `database-is-nullable`
   - `database-required`
   - `field-comment`
8. Explicitly disabled unstable v1 capabilities:
   - native parameters
   - parameterized SQL capability advertisement
   - FK metadata sync
   - index metadata sync
   - uploads
   - actions / writeback
   - nested field expansion

In other words, the current v1 scope should be read as:

1. `internal` catalog: primary validated target
2. `external catalog`: basic sync/query path included in implementation, but not yet broadly validated across all
   catalog types
3. complex types: visible at the top-level metadata layer, but not yet unfolded into nested fields

## Environment prepared for validation

The following local runtime pieces were installed or prepared:

1. Java 21
   - `/mnt/disk1/chenjunwei/tools/jdk-21`
2. Clojure CLI
   - `/mnt/disk1/chenjunwei/tools/clojure-cli`
3. Metabase JAR
   - `/mnt/disk1/chenjunwei/tools/metabase-local/metabase.jar`
4. Plugin directory
   - `/mnt/disk1/chenjunwei/tools/metabase-local/plugins`

## Build verification

The formal standalone build command was executed successfully:

```bash
export JAVA_HOME=/mnt/disk1/chenjunwei/tools/jdk-21
export PATH=/mnt/disk1/chenjunwei/tools/clojure-cli/bin:$JAVA_HOME/bin:$PATH
cd /mnt/disk1/chenjunwei/doris_build/metabase-doris-driver
clojure -T:build jar
```

Produced artifact:

```text
/mnt/disk1/chenjunwei/doris_build/metabase-doris-driver/target/doris.metabase-driver.jar
```

The generated JAR contains:

1. `metabase-plugin.yaml`
2. `metabase.driver.doris`
3. Doris support namespaces
4. Driver icon resources

## Runtime verification

## 1. Metabase startup

Metabase was launched locally with:

```bash
export MB_PLUGINS_DIR=/mnt/disk1/chenjunwei/tools/metabase-local/plugins
export MB_DB_FILE=/mnt/disk1/chenjunwei/tools/metabase-local/metabase-app-db
export MB_JETTY_PORT=3001
export JAVA_HOME=/mnt/disk1/chenjunwei/tools/jdk-21
export PATH=$JAVA_HOME/bin:$PATH
java -jar /mnt/disk1/chenjunwei/tools/metabase-local/metabase.jar
```

Verified results:

1. `GET /api/health` returned `{"status":"ok"}`
2. Metabase completed initialization on port `3001`
3. Startup logs contained:
   - `Registered driver :doris`

## 2. Plugin loading

Metabase successfully discovered the local plugin JAR and loaded the Doris driver.

Verified evidence:

1. Logs showed `Loading plugins in /mnt/disk1/chenjunwei/tools/metabase-local/plugins`
2. Logs showed `Registered driver :doris (parents: [:sql-jdbc])`
3. `GET /api/session/properties` included a `doris` engine definition

## 3. Live Doris connectivity

The driver was verified against a live local Doris FE on:

```text
127.0.0.1:9030
```

Independent baseline check:

```bash
mysql -h127.0.0.1 -P9030 -uroot -e 'select 1'
```

Result:

```text
1
1
```

Metabase connection validation succeeded for:

1. `internal.information_schema`
2. `internal.metabase_driver_test`

Representative validate response:

```json
{
  "host": "127.0.0.1",
  "port": 9030,
  "catalog": "internal",
  "dbname": "metabase_driver_test",
  "user": "root",
  "password": "",
  "ssl": true,
  "valid": true
}
```

Note: Metabase currently echoes `ssl: true` in the response payload even when the request sends `ssl: false`.
Actual connectivity is successful because the driver now sets `sslMode=disable` unless SSL is explicitly requested.
This should be treated as a follow-up cleanup item, not a blocker for current v1 validation.

## 4. Metadata sync verification

A dedicated Doris test database was created:

```text
metabase_driver_test
```

With table:

```text
metabase_v1_orders
```

And data:

```text
(1, 'A', 10, '2026-04-20 10:00:00')
(2, 'A', 15, '2026-04-20 11:00:00')
(3, 'B', 7,  '2026-04-21 09:00:00')
(4, 'B', 3,  '2026-04-21 10:00:00')
```

Metabase sync results:

1. Database `Local Doris V1 Test` was created successfully
2. `initial_sync_status` reached `complete`
3. Table `metabase_v1_orders` was discovered
4. Fields were synced with stable IDs:
   - `id -> 676`
   - `category -> 677`
   - `amount -> 678`
   - `event_time -> 679`

## 5. Native query verification

Executed through `/api/dataset` using a native query:

```sql
select count(*) as row_count, sum(amount) as total_amount
from metabase_v1_orders
```

Result:

```text
[[4, 35]]
```

This confirms:

1. Native query execution works
2. Result metadata is returned
3. Numeric aggregation results are interpreted correctly

## 6. Query Builder verification

### Aggregation with breakout

Query Builder style query:

- source table: `metabase_v1_orders`
- breakout: `category`
- aggregation: `sum(amount)`
- order by: `category asc`

Result:

```text
[['A', 25], ['B', 10]]
```

### Temporal breakout

Query Builder style query:

- source table: `metabase_v1_orders`
- breakout: `event_time` bucketed by `day`
- aggregation: `count(*)`
- order by: day ascending

Result:

```text
[['2026-04-20T00:00:00+08:00', 2], ['2026-04-21T00:00:00+08:00', 2]]
```

This confirms:

1. Basic aggregation works
2. Breakout works
3. Date bucketing works
4. Timezone-aware results are being returned through Metabase

### Filter query

Query Builder style query:

- source table: `metabase_v1_orders`
- filter: `amount > 10`
- aggregation: `count(*)`

Result:

```text
[[1]]
```

This confirms:

1. Numeric filter translation works
2. Aggregation after filtering works

## What is done versus not done

## Done

1. Standalone repository layout
2. Formal build command works
3. Plugin JAR is loadable by Metabase
4. Doris engine shows up in Metabase
5. Doris connection validation works
6. Metadata sync works for the tested v1 path
7. Native query works
8. Query Builder basic aggregation works
9. Query Builder temporal breakout works
10. Query Builder numeric filter works

## Not done yet

1. Broad external catalog validation coverage across different catalog types
2. Native template parameters (`{{param}}`)
3. FK metadata support
4. Index metadata support
5. Upload / writeback support
6. CI automation

## Remaining follow-up items

1. Review capability exposure and ensure inherited default features are tightened where necessary.
2. Investigate why validate responses echo `ssl: true` even when `ssl: false` is requested.
3. Add a stable background launch script or container workflow for Metabase local validation.
4. Add broader query validation:
   - more order-by coverage
   - additional date functions
   - more database types
5. Add `external catalog` validation once the v1 internal path is accepted.

## External catalog validation status

The driver now includes a dedicated smoke validation helper:

- `scripts/validate-external-catalog.sh`

What it covers:

1. Connection validation for a Doris external catalog target
2. Metadata lookup for an already-created Metabase database entry
3. Native query execution against an external catalog table
4. Grouped MBQL query execution against an external catalog table
5. Optional temporal breakout when a time field is supplied

What it does not claim yet:

1. Validation across every external catalog implementation
2. Validation for every external table type
3. Nested-field support for complex external catalog columns

## FE-side external catalog sample verification

Even before running the full Metabase smoke flow, the Doris FE currently exposes a verified external catalog sample
that matches the driver's experimental scope:

```text
catalog: doris_jdbc_catalog
database: regression_test_jdbc_catalog_p0
table: test_insert_order
```

Verified FE-side SQL:

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

Observed results:

```text
row_count total_aid
2         2

gameid total_aid
g1     1
g2     1
```

This provides a concrete external catalog target for the Metabase-side validation helper:

```bash
export METABASE_DB_NAME="Local Doris External Catalog Test"
export DORIS_CATALOG="doris_jdbc_catalog"
export DORIS_DB="regression_test_jdbc_catalog_p0"
export EXTERNAL_TABLE_NAME="test_insert_order"
export EXTERNAL_GROUP_FIELD="gameid"
export EXTERNAL_METRIC_FIELD="aid"
./scripts/validate-external-catalog.sh
```

## FE-side complex type sample verification

A second table in the same catalog confirms that top-level complex type display is meaningful for external catalogs:

```text
catalog: doris_jdbc_catalog
database: regression_test_jdbc_catalog_p0
table: test_jni_complex_type
field: arr_text array<text>
```

Verified FE-side SQL:

```sql
DESC doris_jdbc_catalog.regression_test_jdbc_catalog_p0.test_jni_complex_type;
SELECT count(*) FROM doris_jdbc_catalog.regression_test_jdbc_catalog_p0.test_jni_complex_type;
SELECT id, name
FROM doris_jdbc_catalog.regression_test_jdbc_catalog_p0.test_jni_complex_type
ORDER BY id
LIMIT 5;
```

Observed result highlights:

```text
arr_text array<text>
count(*) = 5
```

This supports the current v1 statement that complex types are visible at the top-level metadata layer even though
nested-field unfolding remains disabled.

## Summary

The repository now has more than a static scaffold.

It has been validated as a real working local v1 prototype:

1. built through the formal `clojure -T:build jar` path
2. loaded by a live Metabase instance
3. connected to a live Doris FE
4. synced metadata from a real Doris test database
5. executed both native SQL and Query Builder queries successfully
