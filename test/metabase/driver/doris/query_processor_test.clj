(ns metabase.driver.doris.query-processor-test
  (:require
   [clojure.test :refer :all]
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.common :as driver.common]
   [metabase.driver.doris.query-processor :as doris.qp]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.query-processor.middleware.catch-exceptions :as catch-exceptions]
   [metabase.util.honey-sql-2 :as h2x])
  (:import
   (clojure.lang ExceptionInfo)
   (java.math BigInteger)
   (java.sql Date PreparedStatement SQLException Time Timestamp Types)
   (java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime)
   (java.util UUID)))

(deftest sql-literal-for-display-test
  (are [value expected] (= expected (#'doris.qp/sql-literal-for-display value))
    "O'Reilly"                                  "'O''Reilly'"
    nil                                          "NULL"
    42                                           "42"
    true                                         "TRUE"
    false                                        "FALSE"
    (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
    "'123e4567-e89b-12d3-a456-426614174000'"
    (byte-array [0 1 127 -1])                    "X'00017FFF'")
  (is (thrown-with-msg? ExceptionInfo
                        #"Unsupported or non-finite SQL number"
                        (#'doris.qp/sql-literal-for-display Double/NaN)))
  (is (thrown-with-msg? ExceptionInfo
                        #"Unsupported or non-finite SQL number"
                        (#'doris.qp/sql-literal-for-display 1/2)))
  (is (thrown-with-msg? ExceptionInfo
                        #"session-dependent escaping"
                        (#'doris.qp/sql-literal-for-display "path\\file")))
  (is (thrown-with-msg? ExceptionInfo
                        #"session-dependent escaping"
                        (#'doris.qp/sql-literal-for-display "first\nsecond")))
  (is (thrown-with-msg? ExceptionInfo
                        #"Unsupported SQL parameter type"
                        (#'doris.qp/sql-literal-for-display (Object.)))))

(deftest temporal-sql-literal-for-display-test
  (are [value expected] (= expected (#'doris.qp/sql-literal-for-display value))
    (LocalDate/parse "2026-07-16")                          "'2026-07-16'"
    (LocalDateTime/parse "2026-07-16T10:15:30")             "'2026-07-16 10:15:30.000000'"
    (LocalDateTime/parse "2026-07-16T10:15:30.123456789")   "'2026-07-16 10:15:30.123456'"
    (LocalTime/parse "10:15:30.987654321")                  "'10:15:30.987654'"
    (Date/valueOf "2026-07-16")                             "'2026-07-16'"
    (Time/valueOf "10:15:30")                               "'10:15:30.000000'"
    (Timestamp/valueOf "2026-07-16 10:15:30.123456789")    "'2026-07-16 10:15:30.123456'")
  (with-redefs [driver-api/results-timezone-id (constantly "America/Los_Angeles")]
    (is (= "'2014-08-02 03:00:00.000000'"
           (#'doris.qp/sql-literal-for-display
            (OffsetDateTime/parse "2014-08-02T10:00:00Z"))))
    (is (= "'2014-08-02 12:00:00.000000'"
           (#'doris.qp/sql-literal-for-display
            (ZonedDateTime/parse "2014-08-02T12:00:00-07:00[America/Los_Angeles]")))))
  (is (= "'03:00:00.000000'"
         (#'doris.qp/sql-literal-for-display (OffsetTime/parse "10:00:00+07:00"))))
  (is (thrown-with-msg? ExceptionInfo
                        #"Unsupported SQL parameter type"
                        (#'doris.qp/sql-literal-for-display (Instant/parse "2026-07-16T10:15:30Z"))))
  (is (thrown-with-msg? ExceptionInfo
                        #"Unsupported SQL parameter type"
                        (#'doris.qp/sql-literal-for-display (java.util.Date.)))))

(deftest inline-parameters-for-display-test
  (let [sql (str "SELECT '?' AS single_quoted, \"?\" AS double_quoted, `?` AS identifier,\n"
                 "       category = ?, label = ? /* ? */, value = ? -- ?\n"
                 "  AND enabled = ? # ?\n"
                 "  AND untouched = '// ?' // ?\n")]
    (is (= (str "SELECT '?' AS single_quoted, \"?\" AS double_quoted, `?` AS identifier,\n"
                "       category = 'A', label = 'O''Reilly' /* ? */, value = NULL -- ?\n"
                "  AND enabled = TRUE # ?\n"
                "  AND untouched = '// ?' // ?\n")
           (#'doris.qp/inline-parameters-for-display sql ["A" "O'Reilly" nil true]))))
  (is (= "SELECT 名称 = '中文值', label = 'Alpha'"
         (#'doris.qp/inline-parameters-for-display
          "SELECT 名称 = ?, label = ?"
          ["中文值" "Alpha"]))))

(deftest inline-parameters-handles-escaped-quotes-test
  (is (= (str "SELECT 'it''s ?' AS doubled_single, \"a\"\"?\" AS doubled_double,\n"
              "       `a``?` AS doubled_backtick, value = 'actual'")
         (#'doris.qp/inline-parameters-for-display
          (str "SELECT 'it''s ?' AS doubled_single, \"a\"\"?\" AS doubled_double,\n"
               "       `a``?` AS doubled_backtick, value = ?")
          ["actual"]))))

(deftest inline-parameters-rejects-ambiguous-backslash-mode-test
  (is (thrown-with-msg? ExceptionInfo
                        #"backslash-escape mode"
                        (#'doris.qp/inline-parameters-for-display
                         "SELECT 'escaped\\'?' AS text, value = ?"
                         ["actual"]))))

(deftest inline-parameters-uses-jdbc-comment-rules-test
  (is (= "SELECT value--?"
         (#'doris.qp/inline-parameters-for-display "SELECT value--?" [])))
  (testing "uses the exact placeholder boundaries from the bundled JDBC parser"
    (is (= "/*/'BOUND'*/#\r?"
           (#'doris.qp/inline-parameters-for-display "/*/?*/#\r?" ["BOUND"])))))

(deftest inline-parameters-rejects-count-mismatches-test
  (is (thrown-with-msg? ExceptionInfo
                        #"parameter count does not match"
                        (#'doris.qp/inline-parameters-for-display "SELECT ?" [])))
  (is (thrown-with-msg? ExceptionInfo
                        #"parameter count does not match"
                        (#'doris.qp/inline-parameters-for-display "SELECT 1" [1])))
  (is (re-find #"Unable to safely expand SQL parameters"
               (#'doris.qp/expanded-sql-for-error "SELECT ?" []))))

(deftest expanded-sql-enforces-display-size-limits-test
  (is (re-find #"too large to display safely"
               (#'doris.qp/expanded-sql-for-error
                "SELECT ?"
                [(apply str (repeat 4097 "a"))])))
  (is (re-find #"too large to display safely"
               (#'doris.qp/expanded-sql-for-error
                "SELECT ?"
                [(byte-array 1025)])))
  (is (re-find #"too large to display safely"
               (#'doris.qp/expanded-sql-for-error
                "SELECT ?"
                [(.pow BigInteger/TEN 4097)])))
  (let [value  (apply str (repeat 4090 "a"))
        params (repeat 17 value)
        sql    (str "SELECT " (str/join ", " (repeat 17 "?")))]
    (is (re-find #"Expanded SQL query is too large to display safely"
                 (#'doris.qp/expanded-sql-for-error sql params)))))

(deftest query-error-includes-sql-test
  (let [query          {:native {:query  (str "SELECT *\n"
                                                "FROM warehouses\n"
                                                "WHERE provider = ?")
                                 :params ["A"]}}
        original-error (SQLException. "Doris parser error" "HY000" 2)
        jdbc-error     (doto (SQLException. "JDBC wrapper error" "08000" 0)
                         (.initCause original-error))
        error-data     {:type   :invalid-query
                        :sql    ["-- Metabase:: userID: 1 queryHash: internal-hash"
                                 "SELECT *"
                                 "FROM warehouses"
                                 "WHERE provider = ?"]
                        :params ["A"]}
        query-error    (ex-info "Error executing query: Doris parser error"
                                error-data
                                jdbc-error)
        thrown         (with-redefs [sql-jdbc.execute/execute-reducible-query
                                     (fn [& _]
                                       (throw query-error))]
                         (try
                           (driver/execute-reducible-query :doris query nil identity)
                           (catch ExceptionInfo error
                             error)))
        response       (catch-exceptions/exception-response thrown)
        visible-error  (ex-cause thrown)]
    (testing "the user-visible JDBC error includes the generated SQL"
      (is (= (str "Doris parser error\n\n"
                  "SQL query:\n"
                  "SELECT *\n"
                  "FROM warehouses\n"
                  "WHERE provider = 'A'")
             (:error response)))
      (is (= "HY000" (:state response)))
      (is (= :invalid-query (:error_type response))))
    (testing "JDBC diagnostics and the original exception are preserved"
      (is (instance? SQLException visible-error))
      (is (= "HY000" (.getSQLState ^SQLException visible-error)))
      (is (= 2 (.getErrorCode ^SQLException visible-error)))
      (is (identical? original-error
                      (.getNextException ^SQLException visible-error)))
      (is (= [jdbc-error]
             (vec (.getSuppressed ^SQLException visible-error)))))
    (testing "bound parameter values are safely inlined in the displayed SQL"
      (is (re-find #"WHERE provider = 'A'" (:error response)))
      (is (not (re-find #"Parameters:" (:error response))))
      (is (not (re-find #"internal-hash" (:error response))))
      (is (= error-data (ex-data thrown))))))

(deftest unrelated-query-errors-are-unchanged-test
  (let [query {:native {:query "SELECT 1"}}]
    (doseq [query-error [(ex-info "Unexpected query error"
                                  {:type :invalid-query}
                                  (RuntimeException. "not a JDBC error"))
                         (ex-info "Query canceled"
                                  {:type :invalid-query
                                   :query/query-canceled? true}
                                  (SQLException. "canceled"))
                         (ex-info "Connection error"
                                  {:type :connection-error}
                                  (SQLException. "connection failed"))]]
      (let [thrown (with-redefs [sql-jdbc.execute/execute-reducible-query
                                 (fn [& _]
                                   (throw query-error))]
                     (try
                       (driver/execute-reducible-query :doris query nil identity)
                       (catch ExceptionInfo error
                         error)))]
        (is (identical? query-error thrown))))))

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

(deftest float-cast-test
  (let [expr (sql.qp/->float :doris :field)]
    (is (= [:cast :field [:raw "double"]] (second expr)))
    (is (h2x/is-of-type? expr :double))))

(deftest identifiers-with-spaces-test
  (is (= ["SELECT `display name` FROM `metabase v1 spaced`"]
         (sql.qp/format-honeysql
          :doris
          {:select [(keyword "display name")]
           :from [(keyword "metabase v1 spaced")]}))))

(deftest unix-timestamp-conversion-test
  (testing "converts seconds to datetime"
    (is (= [:cast [:from_unixtime 1234567890] :datetime]
           (sql.qp/unix-timestamp->honeysql :doris :seconds 1234567890))))
  (testing "converts milliseconds to datetime"
    (is (= [:cast [:from_unixtime [:/ 1234567890000 1000.0]] [:raw "DATETIME(3)"]]
           (sql.qp/unix-timestamp->honeysql :doris :milliseconds 1234567890000)))))

(deftest current-datetime-test
  (testing "uses microsecond-precision NOW(6) with datetime type information"
    (let [expr (sql.qp/current-datetime-honeysql-form :doris)]
      (is (= [:now [:inline 6]] (second expr)))
      (is (= "datetime" (h2x/database-type expr))))))

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
    (binding [driver.common/*start-of-week* :tuesday]
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
    (binding [driver.common/*start-of-week* :monday]
      (is (= (sql.qp/adjust-day-of-week :doris
                                        [:dayofweek :field]
                                        (driver.common/start-of-week-offset-for-day :sunday))
             (sql.qp/date :doris :day-of-week :field)))))
  (testing "extracts week of year"
    (binding [driver.common/*start-of-week* :tuesday]
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
    (with-redefs [driver-api/results-timezone-id (constantly "America/Los_Angeles")]
      (sql-jdbc.execute/set-parameter :doris ps 1 (OffsetDateTime/parse "2014-08-02T10:00:00Z")))
    (is (= [1 (LocalDateTime/parse "2014-08-02T03:00:00") Types/TIMESTAMP]
           @captured))))

(deftest zoned-datetime-parameter-test
  (let [captured (atom nil)
        ps       (proxy [PreparedStatement] []
                   (setObject
                     ([i value]
                      (reset! captured [i value nil]))
                     ([i value sql-type]
                      (reset! captured [i value sql-type]))))]
    (with-redefs [driver-api/results-timezone-id (constantly "UTC")]
      (sql-jdbc.execute/set-parameter
       :doris
       ps
       1
       (ZonedDateTime/parse "2014-08-02T12:00:00-07:00[America/Los_Angeles]")))
    (is (= [1 (LocalDateTime/parse "2014-08-02T19:00:00") Types/TIMESTAMP]
           @captured))))
