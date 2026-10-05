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

# Changelog

## 1.0.1 - Unreleased

- Include the runtime guard for the foreign-key API removed in Metabase 0.63, fixing the v1.0.0 namespace-loading and
  sync failure reported in issue #7.
- Register the legacy `:describe-fks` feature only on runtimes that still recognize it; keep key-constraint metadata
  disabled on all validated versions.
- Adapt tests to the current native-stage parameter API and add Metabase 0.63.16 to the pinned test and CI matrices.
- Validate the exact candidate JAR and live internal-catalog smoke path on Metabase 0.63.16 and 0.60.1; retain automated
  validation on 0.59.6.3 and 0.60.2.2. See RELEASE-NOTES-1.0.1.md for the tested scope.
- Include post-v1.0.0 master improvements to invalid-credential messages, SQL/parameter error display, Connector/J
  2.x/3.x parameter parsing, and query error line numbers.

## 1.0.0 - 2026-07-13

- Cover the Doris capabilities exposed by the legacy Metabase driver.
- Add Doris-specific Query Builder, temporal, type, and metadata behavior.
- Support internal and validated external catalog metadata synchronization.
- Validate compatibility with Metabase 0.59.6.3, 0.60.1, and 0.60.2.2.
