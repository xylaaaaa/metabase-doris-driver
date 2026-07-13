(ns metabase.driver.doris.compatibility-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.doris]
   [metabase.driver.doris.query-processor]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.honey-sql-2 :as h2x])
  (:import
   (java.sql PreparedStatement Types)
   (java.time LocalTime OffsetDateTime OffsetTime ZonedDateTime)))

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

(deftest text-length-counts-characters-test
  (is (= [:char_length :field]
         (sql.qp/->honeysql :doris [:length :field]))))

(deftest offset-temporal-values-use-doris-timezone-conversion-test
  (is (= "CAST('10:00:00.123456' AS TIME(6))"
         (sql.qp/inline-value
          :doris
          (LocalTime/parse "10:00:00.123456"))))
  (is (= "CAST('10:00:00.123456' AS TIME(6))"
         (sql.qp/inline-value
          :doris
          (OffsetTime/parse "18:00:00.123456+08:00"))))
  (is (= "convert_tz('2026-04-20 18:00:00.123456', '+08:00', @@session.time_zone)"
         (sql.qp/inline-value
          :doris
          (OffsetDateTime/parse "2026-04-20T18:00:00.123456+08:00"))))
  (is (= "convert_tz('2026-04-20 18:00:00.123456', 'Asia/Shanghai', @@session.time_zone)"
         (sql.qp/inline-value
          :doris
          (ZonedDateTime/parse "2026-04-20T18:00:00.123456+08:00[Asia/Shanghai]")))))

(deftest offset-time-parameter-uses-utc-test
  (let [captured (atom nil)
        ps (proxy [PreparedStatement] []
             (setObject
               ([i value]
                (reset! captured [i value nil]))
               ([i value sql-type]
                (reset! captured [i value sql-type]))))]
    (sql-jdbc.execute/set-parameter
     :doris ps 1 (OffsetTime/parse "18:00:00.123+08:00"))
    (is (= [1 (LocalTime/parse "10:00:00.123") Types/TIME]
           @captured))))

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
