# Metabase Doris Driver Testing Guide

## Test Levels

### Level 1: Unit Tests (Local, No Database Required)
**Location**: `test/metabase/driver/doris*_test.clj`

**Run**:
```bash
clojure -X:test
```

**Coverage**:
- [x] Connection string building
- [x] SQL generation (SHOW DATABASES/TABLES/COLUMNS)
- [x] Type mapping (30+ Doris types)
- [x] Capability declarations
- [x] Native parameter substitution
- [x] Error message humanization
- [x] Timezone handling

**Status**: ✅ 195+ assertions passing

---

### Level 2: Integration Tests (Requires Live Doris)
**Location**: `test/metabase/test/data/doris.clj` (to be created)

**Setup**:
1. Start Doris FE + BE (docker-compose or local)
2. Create test database: `metabase_test`
3. Load test data (see `scripts/load-test-data.sql`)

**Run**:
```bash
# Set environment
export MB_DORIS_TEST_HOST=localhost
export MB_DORIS_TEST_PORT=9030
export MB_DORIS_TEST_USER=root
export MB_DORIS_TEST_PASSWORD=

# Run integration tests
clojure -X:test :includes '[:integration]'
```

**Test Categories**:

#### 2.1 Connection & Sync
- [ ] Connect to internal catalog
- [ ] Connect to external catalog (Hive/Iceberg)
- [ ] Sync databases (with include/exclude filters)
- [ ] Sync tables
- [ ] Sync columns with metadata (nullable, default, comment)
- [ ] Handle connection errors gracefully

#### 2.2 Query Execution
- [ ] Native SQL query
- [ ] Native SQL with template parameters `{{param}}`
- [ ] Native SQL with optional blocks `[[AND x = {{y}}]]`
- [ ] Query Builder: simple SELECT
- [ ] Query Builder: aggregation (COUNT, SUM, AVG, MIN, MAX)
- [ ] Query Builder: GROUP BY
- [ ] Query Builder: WHERE filters (=, !=, <, >, LIKE)
- [ ] Query Builder: ORDER BY
- [ ] Query Builder: LIMIT

#### 2.3 Temporal Functions
- [ ] Date truncation (minute, hour, day, week, month, quarter, year)
- [ ] Date extraction (minute-of-hour, hour-of-day, day-of-month, day-of-week, week-of-year, month-of-year, quarter-of-year, year)
- [ ] Date arithmetic (add/subtract intervals)
- [ ] Datetime diff (day, hour, minute, second)
- [ ] Unix timestamp conversion (seconds, milliseconds)
- [ ] Timezone handling (UTC default)
- [ ] Week start day (Sunday)

#### 2.4 Type Handling
- [ ] BOOLEAN
- [ ] TINYINT, SMALLINT, INT, BIGINT
- [ ] LARGEINT (128-bit, no truncation)
- [ ] FLOAT, DOUBLE
- [ ] DECIMAL(p,s)
- [ ] VARCHAR, CHAR, STRING, TEXT
- [ ] DATE
- [ ] DATETIME, DATETIMEV2 (with microsecond precision)
- [ ] TIMESTAMP
- [ ] JSON (display as JSON type)
- [ ] ARRAY (display as Array, not unfolded)
- [ ] MAP (display as Dictionary, not unfolded)
- [ ] STRUCT (display as type/*, not unfolded)
- [ ] BITMAP, HLL, VARIANT (display as type/*)

#### 2.5 Complex Scenarios
- [ ] Large result sets (10K+ rows)
- [ ] Wide tables (100+ columns)
- [ ] NULL handling
- [ ] Empty tables
- [ ] Tables with Chinese column names
- [ ] Query cancellation
- [ ] Connection timeout
- [ ] Query timeout

---

### Level 3: Metabase Official Test Suite (Requires Metabase Source)
**Location**: Metabase source `test/metabase/driver/sql_jdbc_test.clj`

**Setup**:
1. Clone Metabase source: `git clone https://github.com/metabase/metabase.git`
2. Add Doris driver to `modules/drivers/`
3. Configure test database in `test_config.edn`

**Run**:
```bash
cd metabase
clojure -X:dev:drivers:drivers-dev:test :only metabase.driver.sql-jdbc-test
```

**Official Test Suites**:

#### 3.1 Driver Protocol Tests
From `test/metabase/driver_test.clj`:
- [ ] `describe-database`
- [ ] `describe-table`
- [ ] `describe-table-fks`
- [ ] `details-fields`
- [ ] `can-connect?`
- [ ] `database-supports?` (all capabilities)

#### 3.2 SQL JDBC Tests
From `test/metabase/driver/sql_jdbc_test.clj`:
- [ ] Connection pooling
- [ ] Transaction handling
- [ ] Prepared statement execution
- [ ] Result set metadata
- [ ] JDBC type mapping
- [ ] Connection error handling

#### 3.3 Query Processor Tests
From `test/metabase/query_processor_test.clj`:
- [ ] MBQL → SQL compilation
- [ ] Aggregation queries
- [ ] Breakout queries
- [ ] Filter queries
- [ ] Join queries (if supported)
- [ ] Nested queries (if supported)
- [ ] Field literals
- [ ] Expression evaluation

#### 3.4 Sync Tests
From `test/metabase/sync/*_test.clj`:
- [ ] Initial sync
- [ ] Incremental sync
- [ ] Schema changes detection
- [ ] Field fingerprinting
- [ ] Table row count estimation

#### 3.5 Temporal Tests
From `test/metabase/driver/sql/query_processor/datetime_test.clj`:
- [ ] All date bucketing units
- [ ] All date extraction units
- [ ] Timezone conversions
- [ ] Week semantics (start day, ISO week)
- [ ] Datetime arithmetic edge cases

---

## Quick Validation Checklist

### Smoke Test (5 minutes)
```bash
# 1. Build driver
clojure -T:build jar

# 2. Copy to Metabase plugins
cp target/doris.metabase-driver.jar $MB_PLUGINS_DIR/

# 3. Start Metabase
java -jar metabase.jar

# 4. In Metabase UI:
- Add Doris database
- Run sync
- Create a question with Query Builder
- Run a native SQL query
```

### Core Functionality Test (30 minutes)
Use `scripts/validate-local.sh`:
```bash
./scripts/validate-local.sh
```

Covers:
- Connection validation
- Metadata sync
- Native query execution
- Query Builder aggregation
- Temporal bucketing
- Type handling

### External Catalog Test (1 hour)
Use `scripts/validate-external-catalog.sh`:
```bash
./scripts/validate-external-catalog.sh
```

Requires:
- Hive catalog configured
- Sample Hive tables

### Complex Metadata Test (1 hour)
Use `scripts/validate-complex-metadata.sh`:
```bash
./scripts/validate-complex-metadata.sh
```

Covers:
- Complex types (ARRAY, MAP, STRUCT, JSON)
- Large tables
- Schema filtering
- Column metadata (nullable, default, comment)

---

## Continuous Integration

### GitHub Actions (Automated)
**Location**: `.github/workflows/test.yml`

**Triggers**:
- Every push to master
- Every pull request
- Nightly builds

**Jobs**:
1. Unit tests (always run)
2. Integration tests (requires Doris docker)
3. Build verification
4. Release artifact generation

### Self-Hosted Runner (For Integration Tests)
**Location**: `.github/workflows/integration.yml`

**Requirements**:
- Self-hosted runner with Docker
- Doris docker-compose setup
- Test data pre-loaded

---

## Test Data Setup

### Minimal Test Dataset
```sql
CREATE DATABASE metabase_test;

USE metabase_test;

CREATE TABLE orders (
  id INT,
  customer_id INT,
  product VARCHAR(100),
  amount DECIMAL(10,2),
  order_date DATE,
  created_at DATETIME
) DUPLICATE KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 1;

INSERT INTO orders VALUES
  (1, 101, 'Laptop', 1200.00, '2026-01-15', '2026-01-15 10:30:00'),
  (2, 102, 'Mouse', 25.50, '2026-01-16', '2026-01-16 14:20:00'),
  (3, 101, 'Keyboard', 75.00, '2026-01-17', '2026-01-17 09:15:00'),
  (4, 103, 'Monitor', 350.00, '2026-01-18', '2026-01-18 16:45:00');
```

### Full Test Dataset
See `scripts/load-test-data.sql` for:
- All Doris data types
- NULL values
- Edge cases (empty strings, zero dates, large numbers)
- Chinese characters
- Complex types (ARRAY, MAP, STRUCT, JSON)

---

## Expected Test Results

### Unit Tests
- **Target**: 100% pass
- **Current**: 195+ assertions, all passing

### Integration Tests
- **Target**: 95%+ pass (some edge cases may fail)
- **Current**: Not yet run (requires Doris instance)

### Official Metabase Tests
- **Target**: 90%+ pass (some advanced features may not be supported)
- **Current**: Not yet run (requires Metabase source integration)

---

## Known Test Failures (Acceptable for v1)

1. **FK/PK constraints**: Doris doesn't enforce these, tests expecting constraint metadata will fail
2. **Index metadata**: `:index-info` disabled, related tests will skip
3. **Nested field expansion**: `:nested-field-columns` disabled, JSON unfolding tests will skip
4. **Table privileges**: `:table-privileges` disabled, permission tests will skip
5. **Uploads/Actions**: Write operations disabled for v1

---

## How to Add New Tests

### 1. Unit Test
```clojure
;; test/metabase/driver/doris/my_feature_test.clj
(ns metabase.driver.doris.my-feature-test
  (:require [clojure.test :refer :all]
            [metabase.driver.doris :as doris]))

(deftest my-feature-test
  (testing "feature works correctly"
    (is (= expected-result (doris/my-function input)))))
```

### 2. Integration Test
```clojure
;; test/metabase/test/data/doris.clj
(ns metabase.test.data.doris
  (:require [metabase.test.data.interface :as tx]))

(defmethod tx/dbdef->connection-details :doris [_ _ {:keys [database-name]}]
  {:host "localhost"
   :port 9030
   :catalog "internal"
   :dbname database-name
   :user "root"
   :password ""})
```

### 3. Add to CI
```yaml
# .github/workflows/test.yml
- name: Run new test
  run: clojure -X:test :includes '[:my-feature]'
```

---

## Debugging Failed Tests

### 1. Enable Debug Logging
```bash
export MB_LOG_LEVEL=DEBUG
export MB_DB_LOGGING_LEVEL=DEBUG
```

### 2. Check Metabase Logs
```bash
tail -f metabase.log | grep -i doris
```

### 3. Check Doris FE Logs
```bash
tail -f fe/log/fe.log | grep -i metabase
```

### 4. Inspect Generated SQL
Add to driver code:
```clojure
(log/debugf "Generated SQL: %s" sql)
```

---

## Resources

- [Metabase Driver Development Guide](https://www.metabase.com/docs/latest/developers-guide/drivers/start)
- [Metabase Test Data Interface](https://github.com/metabase/metabase/blob/master/test/metabase/test/data/interface.clj)
- [ClickHouse Driver Tests](https://github.com/metabase/metabase/tree/master/modules/drivers/clickhouse/test) (good reference)
- [Doris SQL Reference](https://doris.apache.org/docs/sql-manual/sql-reference/)
