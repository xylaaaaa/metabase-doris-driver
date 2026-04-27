(ns metabase.driver.doris.query-processor
  (:require
   [clojure.string :as str]
   [metabase.driver.common :as driver.common]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.execute.old-impl :as sql-jdbc.old]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.log :as log])
  (:import
   (java.sql Connection ResultSet)))

(defmethod sql.qp/quote-style :doris [_] :mysql)

(defmethod sql.qp/unix-timestamp->honeysql [:doris :seconds]
  [_ _ expr]
  [:cast [:from_unixtime expr] :datetime])

(defmethod sql.qp/unix-timestamp->honeysql [:doris :milliseconds]
  [_ _ expr]
  [:cast [:from_unixtime [:/ expr 1000]] :datetime])

(defmethod sql.qp/current-datetime-honeysql-form :doris
  [_]
  :%now)

(defmethod sql.qp/date [:doris :default] [_ _ expr] expr)
(defmethod sql.qp/date [:doris :minute]  [_ _ expr] [:date_trunc "minute" expr])
(defmethod sql.qp/date [:doris :hour]    [_ _ expr] [:date_trunc "hour" expr])
(defmethod sql.qp/date [:doris :day]     [_ _ expr] [:date_trunc "day" expr])
(defmethod sql.qp/date [:doris :month]   [_ _ expr] [:date_trunc "month" expr])
(defmethod sql.qp/date [:doris :quarter] [_ _ expr] [:date_trunc "quarter" expr])
(defmethod sql.qp/date [:doris :year]    [_ _ expr] [:date_trunc "year" expr])

(defmethod sql.qp/date [:doris :minute-of-hour]  [_ _ expr] [:minute expr])
(defmethod sql.qp/date [:doris :hour-of-day]     [_ _ expr] [:hour expr])
(defmethod sql.qp/date [:doris :day-of-month]    [_ _ expr] [:day expr])
(defmethod sql.qp/date [:doris :day-of-year]     [_ _ expr] [:dayofyear expr])
(defmethod sql.qp/date [:doris :month-of-year]   [_ _ expr] [:month expr])
(defmethod sql.qp/date [:doris :year-of-era]     [_ _ expr] [:year expr])
(defmethod sql.qp/date [:doris :quarter-of-year] [_ _ expr] [:quarter expr])

(defn- doris-day-of-week
  [driver expr]
  (sql.qp/adjust-day-of-week driver
                             [:dayofweek expr]
                             (driver.common/start-of-week-offset-for-day :sunday)))

(defmethod sql.qp/date [:doris :day-of-week]
  [driver _ expr]
  (doris-day-of-week driver expr))

(defmethod sql.qp/date [:doris :week]
  [driver _ expr]
  [:date_add
   (sql.qp/date driver :day expr)
   [:interval [:- 1 (doris-day-of-week driver expr)] :day]])

(defmethod sql.qp/date [:doris :week-of-year]
  [driver unit expr]
  ((get-method sql.qp/date [:sql :week-of-year]) driver unit expr))

(defmethod sql.qp/date [:doris :week-of-year-iso]
  [_ _ expr]
  [:week expr 3])

(defmethod sql.qp/add-interval-honeysql-form :doris
  [_ hsql-form amount unit]
  [:date_add hsql-form [:interval amount (keyword (name unit))]])

(defn- timestampdiff-dates
  [unit x y]
  [:timestampdiff [:raw (str/upper-case (name unit))] (h2x/->date x) (h2x/->date y)])

(doseq [unit [:hour :minute :second]]
  (defmethod sql.qp/datetime-diff [:doris unit]
    [_ diff-unit x y]
    [:timestampdiff [:raw (str/upper-case (name diff-unit))] x y]))

(doseq [unit [:year :quarter :month :week]]
  (defmethod sql.qp/datetime-diff [:doris unit]
    [_ diff-unit x y]
    (timestampdiff-dates diff-unit x y)))

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
  :sunday)

(defmethod driver/db-default-timezone :doris
  [_driver _database]
  "UTC")

(defmethod sql-jdbc.old/set-timezone-sql :doris [_]
  "SET time_zone = %s")
