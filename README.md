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

This directory contains a standalone Apache Doris sample driver for Metabase community driver loading.

## Scope

This is a v1, read-only driver sample with the following goals:

1. Connect to Doris through the FE MySQL protocol endpoint.
2. Sync databases, tables, and fields using Doris-native SQL.
3. Support basic Query Builder date and aggregation behavior.
4. Avoid unstable capabilities such as native template parameters, table privilege sync, FK sync, and index sync.

Current implementation is optimized for `internal` catalog first. The connection model keeps an explicit `catalog`
field so that future `external catalog` support does not require restructuring.

## Project Layout

```text
samples/connect/metabase-doris-driver/
├── README.md
├── deps.edn
├── build.clj
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

## Known Limitations

This v1 sample intentionally keeps several capabilities disabled:

1. Native template parameters (`{{param}}`)
2. Parameterized SQL capability advertisement
3. Table privilege sync
4. FK metadata sync
5. Index metadata sync
6. Upload / writeback actions
7. Nested-field expansion

## Verification Status

This sample was scaffolded in an environment without Java, Maven, or Clojure CLI available, so runtime compilation
and Metabase integration tests were not executed here. The implementation and tests have been added, but build and
runtime verification still need to be performed in a Java-enabled environment.
