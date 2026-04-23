(ns metabase.driver.doris-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.doris.connection :as doris.conn]
   [metabase.driver.doris.sync :as doris.sync]
   [metabase.driver.doris.types :as doris.types]))

(deftest jdbc-db-target-test
  (testing "defaults to internal information_schema"
    (is (= "internal.information_schema"
           (doris.conn/jdbc-db-target {}))))
  (testing "builds internal catalog db target"
    (is (= "internal.analytics"
           (doris.conn/jdbc-db-target {:catalog "internal" :dbname "analytics"}))))
  (testing "builds external catalog db target"
    (is (= "hive_catalog.tpch"
           (doris.conn/jdbc-db-target {:catalog "hive_catalog" :dbname "tpch"})))))

(deftest parse-additional-options-test
  (is (= {:allowPublicKeyRetrieval "true"
          :useUnicode "true"}
         (doris.conn/parse-additional-options
          "allowPublicKeyRetrieval=true&useUnicode=true"))))

(deftest describe-sql-test
  (is (= "SHOW DATABASES"
         (doris.sync/describe-catalog-sql "internal")))
  (is (= "SHOW DATABASES FROM `hive_catalog`"
         (doris.sync/describe-catalog-sql "hive_catalog")))
  (is (= "SHOW TABLES FROM `internal_db`"
         (doris.sync/describe-schema-sql "internal" "internal_db")))
  (is (= "SHOW TABLES FROM `hive_catalog`.`tpch`"
         (doris.sync/describe-schema-sql "hive_catalog" "tpch")))
  (is (= "DESC `internal_db`.`orders`"
         (doris.sync/describe-table-sql "internal" "internal_db" "orders")))
  (is (= "DESC `hive_catalog`.`tpch`.`orders`"
         (doris.sync/describe-table-sql "hive_catalog" "tpch" "orders"))))

(deftest type-mapping-test
  (is (= :type/Boolean (doris.types/doris-type->base-type "BOOLEAN")))
  (is (= :type/BigInteger (doris.types/doris-type->base-type "LARGEINT")))
  (is (= :type/DateTime (doris.types/doris-type->base-type "DATETIMEV2")))
  (is (= :type/Array (doris.types/doris-type->base-type "ARRAY<INT>")))
  (is (= :type/Dictionary (doris.types/doris-type->base-type "MAP<VARCHAR,INT>")))
  (is (= :type/JSON (doris.types/doris-type->base-type "JSON")))
  (is (= :type/* (doris.types/doris-type->base-type "STRUCT<a:INT,b:VARCHAR>")))
  (is (= :type/* (doris.types/doris-type->base-type "VARIANT")))
  (is (= :type/* (doris.types/doris-type->base-type "BITMAP"))))

(deftest capability-test
  (testing "v1 supported capabilities are enabled"
    (is (true? (driver/database-supports? :doris :set-timezone nil)))
    (is (true? (driver/database-supports? :doris :basic-aggregations nil)))
    (is (true? (driver/database-supports? :doris :standard-deviation-aggregations nil)))
    (is (true? (driver/database-supports? :doris :expressions nil)))
    (is (true? (driver/database-supports? :doris :temporal-extract nil)))
    (is (true? (driver/database-supports? :doris :date-arithmetics nil)))
    (is (true? (driver/database-supports? :doris :now nil)))
    (is (true? (driver/database-supports? :doris :datetime-diff nil)))
    (is (true? (driver/database-supports? :doris :schemas nil)))
    (is (true? (driver/database-supports? :doris :connection/multiple-databases nil))))

  (testing "v1 unsupported capabilities are disabled"
    (is (false? (driver/database-supports? :doris :native-parameters nil)))
    (is (false? (driver/database-supports? :doris :parameterized-sql nil)))
    (is (false? (driver/database-supports? :doris :table-privileges nil)))
    (is (false? (driver/database-supports? :doris :metadata/key-constraints nil)))
    (is (false? (driver/database-supports? :doris :describe-fks nil)))
    (is (false? (driver/database-supports? :doris :describe-indexes nil)))
    (is (false? (driver/database-supports? :doris :index-info nil)))
    (is (false? (driver/database-supports? :doris :nested-fields nil)))
    (is (false? (driver/database-supports? :doris :nested-field-columns nil)))
    (is (false? (driver/database-supports? :doris :uploads nil)))
    (is (false? (driver/database-supports? :doris :actions nil)))))
