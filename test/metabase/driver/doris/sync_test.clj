(ns metabase.driver.doris.sync-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.doris]
   [metabase.driver.doris.connection :as doris.conn]
   [metabase.driver.doris.sync :as doris.sync]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute])
  (:import
   (java.sql Connection ResultSet Statement)))

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
      (is (= false (:database-required field)))
      (is (= false (:database-is-auto-increment field)))
      (is (= false (:database-is-generated field)))
      (is (= false (:pk? field)))
      (is (= "event timestamp" (:description field)))
      (is (= "event timestamp" (:field-comment field)))))
  (testing "recognizes auto-increment and generated column metadata"
    (let [auto-field (doris.sync/full-column-row->field
                      {"Field" "id"
                       "Type" "bigint"
                       "Null" "NO"
                       "Default" nil
                       "Extra" "AUTO_INCREMENT"
                       "Comment" ""}
                      0)
          generated-field (doris.sync/full-column-row->field
                           {"Field" "total"
                            "Type" "decimal(12,2)"
                            "Null" "NO"
                            "Default" nil
                            "Extra" "STORED GENERATED"
                            "Comment" ""}
                           1)]
      (is (= true (:database-is-auto-increment auto-field)))
      (is (= false (:database-required auto-field)))
      (is (= true (:database-is-generated generated-field)))
      (is (= false (:database-required generated-field)))))
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
  (testing "normalizes textual NULL defaults"
    (let [field (doris.sync/full-column-row->field
                 {"Field" "optional_label"
                  "Type" "varchar(20)"
                  "Null" "YES"
                  "Default" "NULL"
                  "Comment" nil}
                 2)]
      (is (nil? (:database-default field)))))
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

(defn- fake-result-set
  [rows]
  (let [idx (atom -1)]
    (proxy [ResultSet] []
      (next [] (< (swap! idx inc) (count rows)))
      (getString [column] (get (nth rows @idx) column))
      (close [] nil))))

(defn- fake-connection
  [results queries]
  (proxy [Connection] []
    (createStatement []
      (proxy [Statement] []
        (executeQuery [sql]
          (swap! queries conj sql)
          (fake-result-set
           (or (get results sql)
               (throw (ex-info "Unexpected sync SQL" {:sql sql})))))
        (close [] nil)))
    (close [] nil)))

(deftest describe-fields-test
  (let [database    {:id 1
                     :name "Doris"
                     :engine :doris
                     :details {:catalog "hive_catalog" :dbname "analytics"}}
        queries     (atom [])
        checkouts   (atom 0)
        results     {"SHOW TABLES FROM `hive_catalog`.`analytics`"
                     [{1 "events"} {1 "orders"}]

                     "SHOW FULL COLUMNS FROM `hive_catalog`.`analytics`.`orders`"
                     [{"Field" "id"
                       "Type" "bigint"
                       "Null" "NO"
                       "Default" nil
                       "Extra" "auto_increment"
                       "Comment" "identifier"}
                      {"Field" "amount"
                       "Type" "decimal(12,2)"
                       "Null" "NO"
                       "Default" "0.00"
                       "Extra" ""
                       "Comment" "metric amount"}]}
        conn        (fake-connection results queries)]
    (with-redefs [sql-jdbc.execute/do-with-connection-with-options
                  (fn [_driver _database _options f]
                    (swap! checkouts inc)
                    (f conn))]
      (is (= [{:name "id"
               :database-type "bigint"
               :base-type :type/BigInteger
               :database-position 0
               :pk? false
               :database-is-auto-increment true
               :database-is-generated false
               :database-is-nullable false
               :database-required false
               :description "identifier"
               :field-comment "identifier"
               :table-schema "analytics"
               :table-name "orders"}
              {:name "amount"
               :database-type "decimal(12,2)"
               :base-type :type/Decimal
               :database-position 1
               :pk? false
               :database-is-auto-increment false
               :database-is-generated false
               :database-default "0.00"
               :database-is-nullable false
               :database-required false
               :description "metric amount"
               :field-comment "metric amount"
               :table-schema "analytics"
               :table-name "orders"}]
             (vec (driver/describe-fields
                   :doris
                   database
                   :schema-names ["analytics"]
                   :table-names ["orders"]))))
      (is (= 1 @checkouts))
      (is (= ["SHOW TABLES FROM `hive_catalog`.`analytics`"
              "SHOW FULL COLUMNS FROM `hive_catalog`.`analytics`.`orders`"]
             @queries))
      (is (= []
             (vec (driver/describe-fields :doris database :schema-names [])))))))

(deftest external-describe-fields-filters-schemas-before-listing-tables-test
  (let [database {:id 1
                  :name "Doris"
                  :engine :doris
                  :details {:catalog "hive_catalog"}}
        queries  (atom [])
        results  {"SHOW DATABASES FROM `hive_catalog`"
                  [{1 "analytics"} {1 "archive"}]
                  "SHOW TABLES FROM `hive_catalog`.`analytics`"
                  [{1 "orders"}]
                  "SHOW TABLES FROM `hive_catalog`.`archive`"
                  []
                  "SHOW FULL COLUMNS FROM `hive_catalog`.`analytics`.`orders`"
                  [{"Field" "id"
                    "Type" "int"
                    "Null" "NO"
                    "Default" nil
                    "Extra" ""
                    "Comment" ""}]}
        conn     (fake-connection results queries)]
    (with-redefs [sql-jdbc.execute/do-with-connection-with-options
                  (fn [_driver _database _options f]
                    (f conn))]
      (is (= ["id"]
             (mapv :name
                   (driver/describe-fields
                    :doris
                    database
                    :schema-names ["analytics"]
                    :table-names ["orders"]))))
      (is (= ["SHOW DATABASES FROM `hive_catalog`"
              "SHOW TABLES FROM `hive_catalog`.`analytics`"
              "SHOW FULL COLUMNS FROM `hive_catalog`.`analytics`.`orders`"]
             @queries)))))

(deftest external-describe-fields-isolates-table-errors-test
  (let [database {:id 1
                  :name "Doris"
                  :engine :doris
                  :details {:catalog "hive_catalog" :dbname "analytics"}}
        queries  (atom [])
        results  {"SHOW TABLES FROM `hive_catalog`.`analytics`"
                  [{1 "broken_view"} {1 "orders"}]
                  "SHOW FULL COLUMNS FROM `hive_catalog`.`analytics`.`orders`"
                  [{"Field" "id"
                    "Type" "int"
                    "Null" "NO"
                    "Default" nil
                    "Extra" ""
                    "Comment" ""}]}
        conn     (fake-connection results queries)]
    (with-redefs [sql-jdbc.execute/do-with-connection-with-options
                  (fn [_driver _database _options f]
                    (f conn))]
      (is (= [{:table-schema "analytics" :table-name "orders" :name "id"}]
             (mapv #(select-keys % [:table-schema :table-name :name])
                   (driver/describe-fields :doris database))))
      (is (= ["SHOW TABLES FROM `hive_catalog`.`analytics`"
              "SHOW FULL COLUMNS FROM `hive_catalog`.`analytics`.`broken_view`"
              "SHOW FULL COLUMNS FROM `hive_catalog`.`analytics`.`orders`"]
             @queries)))))

(deftest internal-describe-fields-batch-query-test
  (let [database   {:id 1
                    :name "Doris"
                    :engine :doris
                    :details {:catalog "internal" :dbname "analytics"}}
        query-calls (atom [])
        rows        [{:column_name "id"
                      :ordinal_position 1
                      :table_schema "analytics"
                      :table_name "orders"
                      :column_type "bigint"
                      :is_nullable "NO"
                      :column_default nil
                      :extra "auto_increment"
                      :generation_expression ""
                      :column_comment "identifier"}
                     {:column_name "computed_id"
                      :ordinal_position 2
                      :table_schema "analytics"
                      :table_name "orders"
                      :column_type "int(11)"
                      :is_nullable "NO"
                      :column_default nil
                      :extra ""
                      :generation_expression "abs(id)"
                      :column_comment "computed"}]]
    (with-redefs [sql-jdbc.execute/do-with-connection-with-options
                  (fn [& _]
                    (throw (ex-info "Internal field sync must use the reducible query path" {})))
                  sql-jdbc.execute/reducible-query
                  (fn [actual-database query]
                    (swap! query-calls conj [actual-database query])
                    rows)]
      (let [fields (vec (driver/describe-fields
                         :doris
                         database
                         :schema-names ["analytics"]
                         :table-names ["orders"]))]
        (is (= [{:name "id"
                 :table-schema "analytics"
                 :table-name "orders"
                 :database-position 0
                 :database-is-auto-increment true
                 :database-is-generated false
                 :database-required false}
                {:name "computed_id"
                 :table-schema "analytics"
                 :table-name "orders"
                 :database-position 1
                 :database-is-auto-increment false
                 :database-is-generated true
                 :database-required false}]
               (mapv #(select-keys % [:name
                                      :table-schema
                                      :table-name
                                      :database-position
                                      :database-is-auto-increment
                                      :database-is-generated
                                      :database-required])
                     fields)))
        (is (= 1 (count @query-calls)))
        (is (= database (ffirst @query-calls)))))))

(deftest legacy-describe-table-fks-compatibility-test
  (if-let [legacy-describe-table-fks (ns-resolve 'metabase.driver 'describe-table-fks)]
    (testing "registers the legacy method when Metabase still provides it"
      (is (= #{} ((var-get legacy-describe-table-fks) :doris {:id 1} {:id 2}))))
    (testing "does not require the removed method on Metabase 0.63 and later"
      (is (false? (driver/database-supports? :doris :metadata/key-constraints nil))))))
