(ns metabase.driver.doris.compatibility-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.doris]
   [metabase.driver.doris.query-processor]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.honey-sql-2 :as h2x]))

(deftest advanced-query-builder-capabilities-test
  (testing "verified legacy Query Builder capabilities are advertised"
    (doseq [feature [:full-join
                     :percentile-aggregations
                     :regex
                     :split-part
                     :convert-timezone
                     :window-functions/offset]]
      (is (true? (driver/database-supports? :doris feature nil))
          (str "Expected Doris to support " feature))))

  (testing "unsupported regular expression syntax remains disabled"
    (is (false? (driver/database-supports? :doris :regex/lookaheads-and-lookbehinds nil)))))

(deftest percentile-honeysql-test
  (testing "uses Doris approximate percentile aggregation"
    (is (= [:percentile_approx :field [:inline 0.9]]
           (sql.qp/->honeysql :doris [:percentile :field 0.9]))))

  (testing "uses the 50th percentile for median"
    (is (= [:percentile_approx :field [:inline 0.5]]
           (sql.qp/->honeysql :doris [:median :field])))))

(deftest regex-match-first-honeysql-test
  (is (= [:regexp_extract :field "([A-Z]+)" [:inline 0]]
         (sql.qp/->honeysql :doris [:regex-match-first :field "([A-Z]+)"]))))

(deftest split-part-honeysql-test
  (let [position [:inline 2]]
    (is (= [:case
            [:< position 1]
            ""
            :else
            [:coalesce [:split_part :field "-" position] ""]]
           (sql.qp/->honeysql :doris [:split-part :field "-" 2])))))

(deftest convert-timezone-honeysql-test
  (testing "reorders Metabase target/source arguments for Doris CONVERT_TZ"
    (let [expr (sql.qp/->honeysql
                :doris
                [:convert-timezone :field "Asia/Shanghai" "UTC"])]
      (is (= [:convert_tz :field "UTC" "Asia/Shanghai"] (second expr)))
      (is (= "datetime" (h2x/database-type expr)))))

  (testing "requires a source timezone for values without timezone metadata"
    (is (thrown?
         clojure.lang.ExceptionInfo
         (sql.qp/->honeysql
          :doris
          [:convert-timezone :field "Asia/Shanghai" nil])))))
