(ns metabase.driver.doris.sync-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.doris.connection :as doris.conn]
   [metabase.driver.doris.sync :as doris.sync]))

(deftest system-excluded-schemas-test
  (testing "excludes system schemas"
    (is (contains? doris.sync/system-excluded-schemas "information_schema"))
    (is (contains? doris.sync/system-excluded-schemas "__internal_schema"))
    (is (contains? doris.sync/system-excluded-schemas "mysql"))))

(deftest quote-name-test
  (testing "quotes simple names"
    (is (= "`test_db`" (doris.sync/quote-name "test_db"))))
  (testing "escapes backticks in names"
    (is (= "`test``db`" (doris.sync/quote-name "test`db"))))
  (testing "handles names with special characters"
    (is (= "`test-db.name`" (doris.sync/quote-name "test-db.name")))))

(deftest describe-catalog-sql-test
  (testing "generates SHOW DATABASES for internal catalog"
    (is (= "SHOW DATABASES"
           (doris.sync/describe-catalog-sql "internal"))))
  (testing "generates SHOW DATABASES FROM for external catalog"
    (is (= "SHOW DATABASES FROM `hive_catalog`"
           (doris.sync/describe-catalog-sql "hive_catalog"))))
  (testing "handles nil catalog as internal"
    (is (= "SHOW DATABASES"
           (doris.sync/describe-catalog-sql nil))))
  (testing "handles empty catalog as internal"
    (is (= "SHOW DATABASES"
           (doris.sync/describe-catalog-sql "")))))

(deftest describe-schema-sql-test
  (testing "generates SHOW TABLES for internal catalog"
    (is (= "SHOW TABLES FROM `test_db`"
           (doris.sync/describe-schema-sql "internal" "test_db"))))
  (testing "generates SHOW TABLES FROM catalog.schema for external catalog"
    (is (= "SHOW TABLES FROM `hive_catalog`.`tpch`"
           (doris.sync/describe-schema-sql "hive_catalog" "tpch"))))
  (testing "handles schema names with special characters"
    (is (= "SHOW TABLES FROM `test-db`"
           (doris.sync/describe-schema-sql "internal" "test-db")))))

(deftest describe-table-sql-test
  (testing "generates SHOW FULL COLUMNS for internal catalog"
    (is (= "SHOW FULL COLUMNS FROM `orders` FROM `test_db`"
           (doris.sync/describe-table-sql "internal" "test_db" "orders"))))
  (testing "generates SHOW FULL COLUMNS for external catalog"
    (is (= "SHOW FULL COLUMNS FROM `hive_catalog`.`tpch`.`orders`"
           (doris.sync/describe-table-sql "hive_catalog" "tpch" "orders"))))
  (testing "handles table names with special characters"
    (is (= "SHOW FULL COLUMNS FROM `order-items` FROM `test_db`"
           (doris.sync/describe-table-sql "internal" "test_db" "order-items"))))
  (testing "handles names with backticks"
    (is (= "SHOW FULL COLUMNS FROM `order``items` FROM `test_db`"
           (doris.sync/describe-table-sql "internal" "test_db" "order`items")))))

(deftest catalog-normalization-test
  (testing "normalizes catalog names"
    (is (= "internal" (doris.conn/normalize-catalog nil)))
    (is (= "internal" (doris.conn/normalize-catalog "")))
    (is (= "internal" (doris.conn/normalize-catalog "  ")))
    (is (= "hive_catalog" (doris.conn/normalize-catalog "hive_catalog")))
    (is (= "hive_catalog" (doris.conn/normalize-catalog "  hive_catalog  ")))))

(deftest db-normalization-test
  (testing "normalizes database names"
    (is (nil? (doris.conn/normalize-db nil)))
    (is (nil? (doris.conn/normalize-db "")))
    (is (nil? (doris.conn/normalize-db "  ")))
    (is (= "test_db" (doris.conn/normalize-db "test_db")))
    (is (= "test_db" (doris.conn/normalize-db "  test_db  ")))))

(deftest sql-generation-consistency-test
  (testing "internal catalog uses two-part names"
    (let [catalog "internal"
          schema "analytics"
          table "events"]
      (is (= "SHOW DATABASES" (doris.sync/describe-catalog-sql catalog)))
      (is (= "SHOW TABLES FROM `analytics`" (doris.sync/describe-schema-sql catalog schema)))
      (is (= "SHOW FULL COLUMNS FROM `events` FROM `analytics`"
             (doris.sync/describe-table-sql catalog schema table)))))

  (testing "external catalog uses three-part names"
    (let [catalog "hive_catalog"
          schema "tpch"
          table "lineitem"]
      (is (= "SHOW DATABASES FROM `hive_catalog`" (doris.sync/describe-catalog-sql catalog)))
      (is (= "SHOW TABLES FROM `hive_catalog`.`tpch`" (doris.sync/describe-schema-sql catalog schema)))
      (is (= "SHOW FULL COLUMNS FROM `hive_catalog`.`tpch`.`lineitem`"
             (doris.sync/describe-table-sql catalog schema table))))))

(deftest parse-schema-filter-list-test
  (testing "parses empty schema filter lists"
    (is (= #{} (doris.sync/parse-schema-filter-list nil)))
    (is (= #{} (doris.sync/parse-schema-filter-list ""))))
  (testing "parses and normalizes comma-separated schema filters"
    (is (= #{"analytics" "tpch" "openx_json"}
           (doris.sync/parse-schema-filter-list " analytics,tpch , OPENX_JSON ")))))

(deftest schema-visible-test
  (testing "excludes system schemas by default"
    (is (false? (doris.sync/schema-visible? {} "information_schema")))
    (is (false? (doris.sync/schema-visible? {} "__internal_schema")))
    (is (false? (doris.sync/schema-visible? {} "mysql"))))
  (testing "includes ordinary schemas by default"
    (is (true? (doris.sync/schema-visible? {} "analytics"))))
  (testing "applies include filters case-insensitively"
    (is (true? (doris.sync/schema-visible? {:include-schemas "tpch,analytics"} "TPCH")))
    (is (false? (doris.sync/schema-visible? {:include-schemas "tpch,analytics"} "other_db"))))
  (testing "applies exclude filters case-insensitively"
    (is (false? (doris.sync/schema-visible? {:exclude-schemas "tmp_db,staging"} "TMP_DB")))
    (is (true? (doris.sync/schema-visible? {:exclude-schemas "tmp_db,staging"} "analytics"))))
  (testing "exclude filters win over include filters"
    (is (false? (doris.sync/schema-visible? {:include-schemas "tpch,analytics"
                                             :exclude-schemas "analytics"}
                                            "analytics"))))
  (testing "nil schemas are never visible"
    (is (false? (doris.sync/schema-visible? {} nil)))))

(deftest full-column-row-metadata-normalization-test
  (testing "preserves comment/default/nullability when all metadata is present"
    (let [field (doris.sync/full-column-row->field
                 {"Field" "event_time"
                  "Type" "datetime(3)"
                  "Null" "NO"
                  "Default" "CURRENT_TIMESTAMP"
                  "Comment" "event timestamp"}
                 1)]
      (is (= "CURRENT_TIMESTAMP" (:database-default field)))
      (is (= false (:database-is-nullable field)))
      (is (= true (:database-required field)))
      (is (= "event timestamp" (:description field)))
      (is (= "event timestamp" (:field-comment field)))))
  (testing "keeps empty-string defaults and drops blank comments"
    (let [field (doris.sync/full-column-row->field
                 {"Field" "label"
                  "Type" "varchar(20)"
                  "Null" "YES"
                  "Default" ""
                  "Comment" "   "}
                 2)]
      (is (= "" (:database-default field)))
      (is (= true (:database-is-nullable field)))
      (is (= false (:database-required field)))
      (is (nil? (:description field)))
      (is (nil? (:field-comment field)))))
  (testing "uses tri-state nullability when metadata is unavailable"
    (let [field (doris.sync/full-column-row->field
                 {"Field" "mystery_col"
                  "Type" "varchar(20)"
                  "Null" nil
                  "Default" nil
                  "Comment" nil}
                 3)]
      (is (nil? (:database-is-nullable field)))
      (is (nil? (:database-required field)))
      (is (nil? (:database-default field)))
      (is (nil? (:description field)))
      (is (nil? (:field-comment field))))))
