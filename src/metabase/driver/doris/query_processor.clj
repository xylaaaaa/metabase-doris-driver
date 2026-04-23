(ns metabase.driver.doris.query-processor
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.execute.old-impl :as sql-jdbc.old]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.log :as log])
  (:import
   (java.sql Connection ResultSet)))

(defmethod sql.qp/quote-style :doris [_] :mysql)

(defmethod sql.qp/unix-timestamp->honeysql [:doris :seconds]
  [_ _ expr]
  [:from_unixtime expr])

(defmethod sql.qp/unix-timestamp->honeysql [:doris :milliseconds]
  [_ _ expr]
  [:from_unixtime [:/ expr 1000]])

(defmethod sql.qp/current-datetime-honeysql-form :doris
  [_]
  :%now)

(defmethod sql.qp/date [:doris :default] [_ _ expr] expr)
(defmethod sql.qp/date [:doris :minute]  [_ _ expr] [:date_trunc "minute" expr])
(defmethod sql.qp/date [:doris :hour]    [_ _ expr] [:date_trunc "hour" expr])
(defmethod sql.qp/date [:doris :day]     [_ _ expr] [:date_trunc "day" expr])
(defmethod sql.qp/date [:doris :week]    [_ _ expr] [:date_trunc "week" expr])
(defmethod sql.qp/date [:doris :month]   [_ _ expr] [:date_trunc "month" expr])
(defmethod sql.qp/date [:doris :quarter] [_ _ expr] [:date_trunc "quarter" expr])
(defmethod sql.qp/date [:doris :year]    [_ _ expr] [:date_trunc "year" expr])

(defmethod sql.qp/date [:doris :minute-of-hour]  [_ _ expr] [:minute expr])
(defmethod sql.qp/date [:doris :hour-of-day]     [_ _ expr] [:hour expr])
(defmethod sql.qp/date [:doris :day-of-month]    [_ _ expr] [:day expr])
(defmethod sql.qp/date [:doris :month-of-year]   [_ _ expr] [:month expr])
(defmethod sql.qp/date [:doris :year-of-era]     [_ _ expr] [:year expr])
(defmethod sql.qp/date [:doris :day-of-week]     [_ _ expr] [:dayofweek expr])
(defmethod sql.qp/date [:doris :week-of-year]    [_ _ expr] [:week expr])
(defmethod sql.qp/date [:doris :quarter-of-year] [_ _ expr] [:quarter expr])

(defmethod sql.qp/add-interval-honeysql-form :doris
  [_ hsql-form amount unit]
  [:date_add hsql-form [:interval amount (keyword (name unit))]])

(doseq [unit [:year :month :hour :minute :second]]
  (defmethod sql.qp/datetime-diff [:doris unit]
    [_ diff-unit x y]
    [:timestampdiff [:raw (str/upper-case (name diff-unit))] x y]))

(defmethod sql.qp/datetime-diff [:doris :day]
  [_ _ x y]
  [:datediff y x])

(defmethod sql.qp/cast-temporal-string [:doris :Coercion/ISO8601->DateTime]
  [_ _ expr]
  [:cast expr :datetime])

(defmethod sql.qp/cast-temporal-string [:doris :Coercion/ISO8601->Date]
  [_ _ expr]
  [:cast expr :date])

(defmethod sql.qp/cast-temporal-string [:doris :Coercion/YYYYMMDDHHMMSSString->Temporal]
  [_ _ expr]
  [:cast expr :datetime])

(defmethod sql.qp/cast-temporal-byte [:doris :Coercion/YYYYMMDDHHMMSSBytes->Temporal]
  [_ _ expr]
  [:cast expr :datetime])

(defmethod driver/db-start-of-week :doris [_]
  :monday)

(defmethod driver/db-default-timezone :doris
  [driver database]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   database
   nil
   (fn [^Connection conn]
     (try
       (with-open [stmt (.createStatement conn)
                   rs   (.executeQuery stmt "SELECT @@system_time_zone")]
         (when (.next ^ResultSet rs)
           (.getString ^ResultSet rs 1)))
       (catch Exception e
         (log/warnf "Failed to fetch Doris system timezone, falling back to UTC: %s" (.getMessage e))
         "UTC")))))

(defmethod sql-jdbc.old/set-timezone-sql :doris [_]
  "SET time_zone = %s")
