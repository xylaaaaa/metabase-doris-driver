(ns metabase.driver.doris.query-processor-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.common :as driver.common]
   [metabase.driver.doris.query-processor :as doris.qp]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.test :as mt]
   [metabase.util.honey-sql-2 :as h2x])
  (:import
   (java.sql PreparedStatement Types)
   (java.time LocalDateTime OffsetDateTime)))

(deftest quote-style-test
  (testing "uses MySQL quoting style"
    (is (= :mysql (sql.qp/quote-style :doris)))))

(deftest integer-cast-test
  (testing "casts via round before converting to integer"
    (let [expr (sql.qp/->integer :doris :field)]
      (is (= [:cast [:round :field] [:raw "BIGINT"]]
             (second expr)))
      (is (= {:database-type "bigint"}
             (nth expr 2))))))

(deftest unix-timestamp-conversion-test
  (testing "converts seconds to datetime"
    (is (= [:cast [:from_unixtime 1234567890] :datetime]
           (sql.qp/unix-timestamp->honeysql :doris :seconds 1234567890))))
  (testing "converts milliseconds to datetime"
    (is (= [:cast [:from_unixtime [:/ 1234567890000 1000.0]] [:raw "DATETIME(3)"]]
           (sql.qp/unix-timestamp->honeysql :doris :milliseconds 1234567890000)))))

(deftest current-datetime-test
  (testing "uses NOW() function"
    (is (= :%now (sql.qp/current-datetime-honeysql-form :doris)))))

(deftest date-truncation-test
  (testing "truncates to minute"
    (is (= [:date_trunc "minute" :field]
           (sql.qp/date :doris :minute :field))))
  (testing "truncates to hour"
    (is (= [:date_trunc "hour" :field]
           (sql.qp/date :doris :hour :field))))
  (testing "truncates to day"
    (is (= [:date_trunc "day" :field]
           (sql.qp/date :doris :day :field))))
  (testing "truncates to month"
    (is (= [:date_trunc "month" :field]
           (sql.qp/date :doris :month :field))))
  (testing "truncates to quarter"
    (is (= [:date_trunc "quarter" :field]
           (sql.qp/date :doris :quarter :field))))
  (testing "truncates to year"
    (is (= [:date_trunc "year" :field]
           (sql.qp/date :doris :year :field)))))

(deftest week-truncation-test
  (testing "truncates week based on start-of-week setting"
    (mt/with-temporary-setting-values [start-of-week :tuesday]
      (is (= [:date_add
              [:date_trunc "day" :field]
              [:interval
               [:- 1
                (sql.qp/adjust-day-of-week :doris
                                           [:dayofweek :field]
                                           (driver.common/start-of-week-offset-for-day :sunday))]
               :day]]
             (sql.qp/date :doris :week :field))))))

(deftest date-extraction-test
  (testing "extracts minute of hour"
    (is (= [:minute :field]
           (sql.qp/date :doris :minute-of-hour :field))))
  (testing "extracts hour of day"
    (is (= [:hour :field]
           (sql.qp/date :doris :hour-of-day :field))))
  (testing "extracts day of month"
    (is (= [:day :field]
           (sql.qp/date :doris :day-of-month :field))))
  (testing "extracts day of year"
    (is (= [:cast [:date_format :field (h2x/literal "%j")] :int]
           (sql.qp/date :doris :day-of-year :field))))
  (testing "extracts month of year"
    (is (= [:cast [:date_format :field (h2x/literal "%m")] :int]
           (sql.qp/date :doris :month-of-year :field))))
  (testing "extracts year"
    (is (= [:year :field]
           (sql.qp/date :doris :year-of-era :field))))
  (testing "extracts day of week"
    (mt/with-temporary-setting-values [start-of-week :monday]
      (is (= (sql.qp/adjust-day-of-week :doris
                                        [:dayofweek :field]
                                        (driver.common/start-of-week-offset-for-day :sunday))
             (sql.qp/date :doris :day-of-week :field)))))
  (testing "extracts week of year"
    (mt/with-temporary-setting-values [start-of-week :tuesday]
      (is (= ((get-method sql.qp/date [:sql :week-of-year]) :doris :week-of-year :field)
             (sql.qp/date :doris :week-of-year :field)))))
  (testing "extracts ISO week of year"
    (is (= [:week :field 3]
           (sql.qp/date :doris :week-of-year-iso :field))))
  (testing "extracts quarter"
    (is (= [:quarter :field]
           (sql.qp/date :doris :quarter-of-year :field)))))

(deftest add-interval-test
  (testing "adds interval to date"
    (is (= [:date_add :field [:interval 5 :day]]
           (sql.qp/add-interval-honeysql-form :doris :field 5 :day)))
    (is (= [:date_add :field [:interval 3 :month]]
           (sql.qp/add-interval-honeysql-form :doris :field 3 :month)))))

(deftest timezone-aware-add-interval-preserves-type-test
  (let [expr    (h2x/with-database-type-info :field "timestamptz(3)")
        shifted (sql.qp/add-interval-honeysql-form :doris expr -4 :hour)]
    (is (= "timestamptz(3)"
           (h2x/database-type shifted)))))

(deftest datetime-diff-test
  (testing "calculates day difference with datediff"
    (is (= [:datediff :end :start]
           (sql.qp/datetime-diff :doris :day :start :end))))
  (testing "calculates hour difference with timestampdiff"
    (is (= [:timestampdiff [:raw "HOUR"] :start :end]
           (sql.qp/datetime-diff :doris :hour :start :end))))
  (testing "calculates week difference with timestampdiff"
    (is (= [:timestampdiff [:raw "WEEK"] (h2x/->date :start) (h2x/->date :end)]
           (sql.qp/datetime-diff :doris :week :start :end))))
  (testing "calculates week difference directly for timestamptz expressions"
    (let [x (h2x/with-type-info :start {:effective-type :type/DateTimeWithTZ})
          y (h2x/with-type-info :end {:effective-type :type/DateTimeWithTZ})]
      (is (= [:timestampdiff [:raw "WEEK"]
              [:cast [:date_format x (h2x/literal "%Y-%m-%d")] :date]
              [:cast [:date_format y (h2x/literal "%Y-%m-%d")] :date]]
             (sql.qp/datetime-diff :doris :week x y)))))
  (testing "calculates month difference with timestampdiff on dates"
    (is (= [:timestampdiff [:raw "MONTH"] (h2x/->date :start) (h2x/->date :end)]
           (sql.qp/datetime-diff :doris :month :start :end))))
  (testing "calculates year difference with timestampdiff on dates"
    (is (= [:timestampdiff [:raw "YEAR"] (h2x/->date :start) (h2x/->date :end)]
           (sql.qp/datetime-diff :doris :year :start :end))))
  (testing "calculates minute difference with timestampdiff"
    (is (= [:timestampdiff [:raw "MINUTE"] :start :end]
           (sql.qp/datetime-diff :doris :minute :start :end))))
  (testing "calculates quarter difference with timestampdiff"
    (is (= [:timestampdiff [:raw "QUARTER"] (h2x/->date :start) (h2x/->date :end)]
           (sql.qp/datetime-diff :doris :quarter :start :end))))
  (testing "calculates second difference with timestampdiff"
    (is (= [:timestampdiff [:raw "SECOND"] :start :end]
           (sql.qp/datetime-diff :doris :second :start :end)))))

(deftest cast-temporal-string-test
  (testing "casts ISO8601 string to datetime"
    (is (= [:cast "2026-04-22T10:30:00" :datetime]
           (sql.qp/cast-temporal-string :doris :Coercion/ISO8601->DateTime "2026-04-22T10:30:00"))))
  (testing "casts ISO8601 string to date"
    (is (= [:cast "2026-04-22" :date]
           (sql.qp/cast-temporal-string :doris :Coercion/ISO8601->Date "2026-04-22"))))
  (testing "casts YYYYMMDDHHmmss string to datetime"
    (is (= [:cast "20260422103000" :datetime]
           (sql.qp/cast-temporal-string :doris :Coercion/YYYYMMDDHHMMSSString->Temporal "20260422103000")))))

(deftest offset-datetime-parameter-test
  (let [captured (atom nil)
        ps       (proxy [PreparedStatement] []
                   (setObject
                     ([i value]
                      (reset! captured [i value nil]))
                     ([i value sql-type]
                      (reset! captured [i value sql-type]))))]
    (mt/with-results-timezone-id "America/Los_Angeles"
      (sql-jdbc.execute/set-parameter :doris ps 1 (OffsetDateTime/parse "2014-08-02T10:00:00Z")))
    (is (= [1 (LocalDateTime/parse "2014-08-02T03:00:00") Types/TIMESTAMP]
           @captured))))
