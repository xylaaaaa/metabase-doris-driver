(ns metabase.driver.doris.query-processor-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.doris.query-processor :as doris.qp]
   [metabase.driver.sql.query-processor :as sql.qp]))

(deftest quote-style-test
  (testing "uses MySQL quoting style"
    (is (= :mysql (sql.qp/quote-style :doris)))))

(deftest unix-timestamp-conversion-test
  (testing "converts seconds to datetime"
    (is (= [:from_unixtime 1234567890]
           (sql.qp/unix-timestamp->honeysql :doris :seconds 1234567890))))
  (testing "converts milliseconds to datetime"
    (is (= [:from_unixtime [:/ 1234567890000 1000]]
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
  (testing "truncates to week"
    (is (= [:date_trunc "week" :field]
           (sql.qp/date :doris :week :field))))
  (testing "truncates to month"
    (is (= [:date_trunc "month" :field]
           (sql.qp/date :doris :month :field))))
  (testing "truncates to quarter"
    (is (= [:date_trunc "quarter" :field]
           (sql.qp/date :doris :quarter :field))))
  (testing "truncates to year"
    (is (= [:date_trunc "year" :field]
           (sql.qp/date :doris :year :field)))))

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
  (testing "extracts month of year"
    (is (= [:month :field]
           (sql.qp/date :doris :month-of-year :field))))
  (testing "extracts year"
    (is (= [:year :field]
           (sql.qp/date :doris :year-of-era :field))))
  (testing "extracts day of week"
    (is (= [:dayofweek :field]
           (sql.qp/date :doris :day-of-week :field))))
  (testing "extracts week of year"
    (is (= [:week :field]
           (sql.qp/date :doris :week-of-year :field))))
  (testing "extracts quarter"
    (is (= [:quarter :field]
           (sql.qp/date :doris :quarter-of-year :field)))))

(deftest add-interval-test
  (testing "adds interval to date"
    (is (= [:date_add :field [:interval 5 :day]]
           (sql.qp/add-interval-honeysql-form :doris :field 5 :day)))
    (is (= [:date_add :field [:interval 3 :month]]
           (sql.qp/add-interval-honeysql-form :doris :field 3 :month)))))

(deftest datetime-diff-test
  (testing "calculates day difference with datediff"
    (is (= [:datediff :end :start]
           (sql.qp/datetime-diff :doris :day :start :end))))
  (testing "calculates hour difference with timestampdiff"
    (is (= [:timestampdiff [:raw "HOUR"] :start :end]
           (sql.qp/datetime-diff :doris :hour :start :end))))
  (testing "calculates minute difference with timestampdiff"
    (is (= [:timestampdiff [:raw "MINUTE"] :start :end]
           (sql.qp/datetime-diff :doris :minute :start :end))))
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
