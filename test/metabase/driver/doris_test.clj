(ns metabase.driver.doris-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.doris]
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
  (is (= "SHOW FULL COLUMNS FROM `orders` FROM `internal_db`"
         (doris.sync/describe-table-sql "internal" "internal_db" "orders")))
  (is (= "SHOW FULL COLUMNS FROM `hive_catalog`.`tpch`.`orders`"
         (doris.sync/describe-table-sql "hive_catalog" "tpch" "orders"))))

(deftest full-column-row->field-test
  (let [field (doris.sync/full-column-row->field
               {"Field" "amount"
                "Type" "decimal(12,2)"
                "Null" "YES"
                "Default" "0.00"
                "Comment" "metric amount"}
               3)]
    (is (= "amount" (:name field)))
    (is (= "decimal(12,2)" (:database-type field)))
    (is (= :type/Decimal (:base-type field)))
    (is (= 3 (:database-position field)))
    (is (= "metric amount" (:field-comment field)))
    (is (= "0.00" (:database-default field)))
    (is (= true (:database-is-nullable field)))
    (is (= false (:database-required field)))))

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
  (testing "supported capabilities are enabled"
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

  (testing "unsupported capabilities are disabled"
    (is (true? (driver/database-supports? :doris :native-parameters nil)))
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

(deftest native-parameter-substitution-test
  (testing "Doris native queries support basic template-tag substitution"
    (binding [driver/*driver* :doris]
      (is (= {:query "SELECT * FROM test_insert_order WHERE aid = 2"
              :template-tags {"aid" {:name "aid" :display-name "Aid" :type :number}}
              :parameters [{:type :number
                            :target [:variable [:template-tag "aid"]]
                            :value 2}]
              :params []}
             (driver/substitute-native-parameters
              :doris
              {:query "SELECT * FROM test_insert_order WHERE aid = {{aid}}"
               :template-tags {"aid" {:name "aid" :display-name "Aid" :type :number}}
               :parameters [{:type :number
                             :target [:variable [:template-tag "aid"]]
                             :value 2}]}))))))

(deftest native-parameter-optional-block-test
  (testing "Doris native queries support optional blocks"
    (binding [driver/*driver* :doris]
      (is (= {:query "SELECT * FROM test_insert_order WHERE 1 = 1 AND pname = ?"
              :template-tags {"pname" {:name "pname" :display-name "Pname" :type :text}}
              :parameters [{:type :text
                            :target [:variable [:template-tag "pname"]]
                            :value "wow"}]
              :params ["wow"]}
             (driver/substitute-native-parameters
              :doris
              {:query "SELECT * FROM test_insert_order WHERE 1 = 1 [[AND pname = {{pname}}]]"
               :template-tags {"pname" {:name "pname" :display-name "Pname" :type :text}}
               :parameters [{:type :text
                             :target [:variable [:template-tag "pname"]]
                             :value "wow"}]}))))))
