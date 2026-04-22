(ns metabase.driver.doris.sync-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.doris.connection :as doris.conn]
   [metabase.driver.doris.sync :as doris.sync]))

(deftest excluded-schemas-test
  (testing "excludes system schemas"
    (is (contains? doris.sync/excluded-schemas "information_schema"))
    (is (contains? doris.sync/excluded-schemas "INFORMATION_SCHEMA"))
    (is (contains? doris.sync/excluded-schemas "__internal_schema"))
    (is (contains? doris.sync/excluded-schemas "mysql"))))

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
  (testing "generates DESC for internal catalog"
    (is (= "DESC `test_db`.`orders`"
           (doris.sync/describe-table-sql "internal" "test_db" "orders"))))
  (testing "generates DESC for external catalog"
    (is (= "DESC `hive_catalog`.`tpch`.`orders`"
           (doris.sync/describe-table-sql "hive_catalog" "tpch" "orders"))))
  (testing "handles table names with special characters"
    (is (= "DESC `test_db`.`order-items`"
           (doris.sync/describe-table-sql "internal" "test_db" "order-items"))))
  (testing "handles names with backticks"
    (is (= "DESC `test_db`.`order``items`"
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
      (is (= "DESC `analytics`.`events`" (doris.sync/describe-table-sql catalog schema table)))))

  (testing "external catalog uses three-part names"
    (let [catalog "hive_catalog"
          schema "tpch"
          table "lineitem"]
      (is (= "SHOW DATABASES FROM `hive_catalog`" (doris.sync/describe-catalog-sql catalog)))
      (is (= "SHOW TABLES FROM `hive_catalog`.`tpch`" (doris.sync/describe-schema-sql catalog schema)))
      (is (= "DESC `hive_catalog`.`tpch`.`lineitem`" (doris.sync/describe-table-sql catalog schema table))))))
