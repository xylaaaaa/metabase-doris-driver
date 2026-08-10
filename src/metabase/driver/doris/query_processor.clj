(ns metabase.driver.doris.query-processor
  (:require
   [clojure.string :as str]
   [java-time.api :as t]
   [metabase.driver.common :as driver.common]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.execute.old-impl :as sql-jdbc.old]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.sql.query-processor.util :as sql.qp.u]
   [metabase.driver.sql.util :as sql.u]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.log :as log])
  (:import
   (clojure.lang ExceptionInfo Reflector)
   (java.nio.charset StandardCharsets)
   (java.sql Connection Date PreparedStatement ResultSet SQLException Time Timestamp)
   (java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime)
   (java.util UUID)))

(defmethod sql.qp/quote-style :doris [_] :mysql)

(defmethod sql.qp/->honeysql [:doris ::h2x/identifier]
  [_ [_ identifier-type components :as identifier]]
  (if (and (#{:table :field} identifier-type)
           (sequential? components)
           (string? (first components))
           (str/includes? (first components) "."))
    (let [[catalog schema] (str/split (first components) #"\." 2)]
      (with-meta
        (apply h2x/identifier identifier-type catalog schema (rest components))
        (meta identifier)))
    identifier))

(def ^:dynamic *preserve-offset-datetime-parameters*
  false)

(defn- offset-date-time-parameter-value
  [value]
  (if *preserve-offset-datetime-parameters*
    (t/local-date-time (t/with-offset-same-instant value (t/zone-offset 0)))
    (let [zone   (t/zone-id (driver-api/results-timezone-id))
          offset (.. zone getRules (getOffset (t/instant value)))]
      (t/local-date-time (t/with-offset-same-instant value offset)))))

(defn- offset-time-parameter-value
  [value]
  (t/local-time (t/with-offset-same-instant value (t/zone-offset 0))))

(def ^:private doris-error-location-pattern
  #"\(line\s+(\d+),\s*pos\s+(\d+)\)")

(def ^:private metabase-query-comment-pattern
  #"^\s*--\s*Metabase::")

(defn- innermost-sql-exception
  [error]
  (loop [cause error
         found nil]
    (if cause
      (recur (ex-cause cause)
             (if (instance? SQLException cause) cause found))
      found)))

(def ^:private byte-array-class
  (class (byte-array 0)))

(def ^:private max-display-sql-length 65536)
(def ^:private max-display-literal-length 4096)
(def ^:private max-display-binary-length 1024)

(defn- checked-display-literal
  [literal]
  (when (> (count literal) max-display-literal-length)
    (throw (ex-info "Rendered SQL parameter is too large to display safely."
                    {:length (count literal)})))
  literal)

(defn- quoted-sql-string
  [value]
  (let [value (str value)]
    (when (> (count value) max-display-literal-length)
      (throw (ex-info "SQL string parameter is too large to display safely."
                      {:length (count value)})))
    (when (some (fn [character]
                  (or (= character \\)
                      (Character/isISOControl (int character))))
                value)
      (throw (ex-info "SQL string parameter requires session-dependent escaping."
                      {})))
    ;; Doubling apostrophes is equivalent with and without NO_BACKSLASH_ESCAPES.
    (checked-display-literal (str "'" (str/replace value "'" "''") "'"))))

(defn- sql-number-for-display
  [value]
  (checked-display-literal
   (cond
     (or (integer? value) (decimal? value))
     (str value)

     (and (instance? Double value) (Double/isFinite ^double value))
     (str value)

     (and (instance? Float value) (Float/isFinite ^float value))
     (str value)

     :else
     (throw (ex-info "Unsupported or non-finite SQL number."
                     {:value value})))))

(defn- bytes->hex
  [^bytes value]
  (when (> (alength value) max-display-binary-length)
    (throw (ex-info "Binary SQL parameter is too large to display safely."
                    {:length (alength value)})))
  (let [digits "0123456789ABCDEF"
        result (StringBuilder. (* 2 (alength value)))]
    (doseq [byte-value value]
      (let [unsigned-value (bit-and (int byte-value) 0xff)]
        (.append result (.charAt digits (bit-shift-right unsigned-value 4)))
        (.append result (.charAt digits (bit-and unsigned-value 0x0f)))))
    (.toString result)))

(defn- local-date-time-sql-string
  [^LocalDateTime value]
  ;; Connector/J encodes temporal parameters with microsecond precision.
  (t/format "yyyy-MM-dd HH:mm:ss.SSSSSS" value))

(defn- local-time-sql-string
  [^LocalTime value]
  (t/format "HH:mm:ss.SSSSSS" value))

(defn- temporal-sql-literal-for-display
  [value]
  (cond
    (instance? LocalDate value)      (quoted-sql-string (t/format "yyyy-MM-dd" value))
    (instance? LocalDateTime value)  (quoted-sql-string (local-date-time-sql-string value))
    (instance? LocalTime value)      (quoted-sql-string (local-time-sql-string value))
    (instance? Date value)           (temporal-sql-literal-for-display (.toLocalDate ^Date value))
    (instance? Time value)           (temporal-sql-literal-for-display (.toLocalTime ^Time value))
    (instance? Timestamp value)      (temporal-sql-literal-for-display (.toLocalDateTime ^Timestamp value))
    (instance? OffsetDateTime value) (temporal-sql-literal-for-display
                                      (offset-date-time-parameter-value value))
    (instance? OffsetTime value)     (temporal-sql-literal-for-display
                                      (offset-time-parameter-value value))
    (instance? ZonedDateTime value)  (temporal-sql-literal-for-display
                                      (offset-date-time-parameter-value (.toOffsetDateTime ^ZonedDateTime value)))
    :else                            (throw (ex-info "Unsupported temporal SQL parameter type."
                                                     {:type (class value)}))))

(defn- sql-literal-for-display
  [value]
  (cond
    (nil? value)                       "NULL"
    (or (string? value) (char? value)) (quoted-sql-string value)
    (boolean? value)                   (if value "TRUE" "FALSE")
    (number? value)                    (sql-number-for-display value)
    (or (instance? LocalDate value)
        (instance? LocalDateTime value)
        (instance? LocalTime value)
        (instance? Date value)
        (instance? Time value)
        (instance? Timestamp value)
        (instance? OffsetDateTime value)
        (instance? OffsetTime value)
        (instance? ZonedDateTime value))
    (temporal-sql-literal-for-display value)
    (instance? UUID value)             (quoted-sql-string value)
    (instance? byte-array-class value) (checked-display-literal (str "X'" (bytes->hex value) "'"))
    :else                              (throw (ex-info "Unsupported SQL parameter type."
                                                       {:type (class value)}))))

(defn- append-display-fragment!
  [^StringBuilder result ^String fragment]
  (let [new-length (+ (.length result) (.length fragment))]
    (when (> new-length max-display-sql-length)
      (throw (ex-info "Expanded SQL query is too large to display safely."
                      {:length new-length})))
    (.append result fragment)))

(defn- split-sql-at-byte-positions
  [^String sql positions]
  (let [sql-bytes (.getBytes sql StandardCharsets/UTF_8)]
    (loop [positions (seq positions)
           parts     []
           start     0]
      (if-let [position (first positions)]
        (recur (next positions)
               (conj parts (String. sql-bytes start (- position start) StandardCharsets/UTF_8))
               (inc position))
        (conj parts
              (String. sql-bytes start (- (alength sql-bytes) start) StandardCharsets/UTF_8))))))

(defn- connector-j-major-version
  []
  ;; Metabase currently bundles Connector/J 2.x, while the standalone driver dependency is 3.x.
  ;; Dispatch from the JDBC driver that actually owns the prepared statement semantics at runtime.
  (.getMajorVersion
   ^java.sql.Driver
   (Reflector/invokeConstructor
    (Class/forName "org.mariadb.jdbc.Driver")
    (object-array 0))))

(defn- connector-j-2-parameter-parts
  [^String sql no-backslash-escapes?]
  (let [parsed (Reflector/invokeStaticMethod
                (Class/forName "org.mariadb.jdbc.internal.util.dao.ClientPrepareResult")
                "parameterParts"
                (object-array [sql no-backslash-escapes?]))]
    (mapv #(String. ^bytes % StandardCharsets/UTF_8)
          (Reflector/invokeInstanceMethod parsed "getQueryParts" (object-array 0)))))

(defn- connector-j-3-parameter-parts
  [^String sql no-backslash-escapes?]
  (let [parsed (Reflector/invokeStaticMethod
                (Class/forName "org.mariadb.jdbc.util.ClientParser")
                "parameterParts"
                (object-array [sql no-backslash-escapes?]))]
    (split-sql-at-byte-positions
     sql
     (Reflector/invokeInstanceMethod parsed "getParamPositions" (object-array 0)))))

(defn- jdbc-parameter-parts
  [^String sql no-backslash-escapes?]
  (let [major-version (connector-j-major-version)]
    (case major-version
      2 (connector-j-2-parameter-parts sql no-backslash-escapes?)
      3 (connector-j-3-parameter-parts sql no-backslash-escapes?)
      (throw (ex-info "Unsupported MariaDB Connector/J major version."
                      {:major-version major-version})))))

(defn- inline-parameters-for-display
  [^String sql params]
  (when (> (.length sql) max-display-sql-length)
    (throw (ex-info "SQL query is too large to display safely."
                    {:length (.length sql)})))
  (let [params                      (vec params)
        parameter-parts             (jdbc-parameter-parts sql false)
        no-backslash-escape-parts   (jdbc-parameter-parts sql true)
        parameter-count             (count params)
        placeholder-count           (dec (count parameter-parts))]
    (when-not (= parameter-parts no-backslash-escape-parts)
      (throw (ex-info "SQL parameter parsing depends on the session backslash-escape mode."
                      {})))
    (when-not (= parameter-count placeholder-count)
      (throw (ex-info "SQL parameter count does not match placeholder count."
                      {:parameter-count   parameter-count
                       :placeholder-count placeholder-count})))
    (let [result (StringBuilder. (.length sql))]
      (loop [parts  parameter-parts
             values params]
        (append-display-fragment! result (first parts))
        (if (seq values)
          (do
            (append-display-fragment! result (sql-literal-for-display (first values)))
            (recur (next parts) (next values)))
          (.toString result))))))

(defn- expanded-sql-for-error
  [sql params]
  (try
    (inline-parameters-for-display sql params)
    (catch Exception error
      (format "<Unable to safely expand SQL parameters: %s>" (ex-message error)))))

(defn- sql-lines
  [sql]
  (cond
    (string? sql)     (str/split sql #"\r?\n" -1)
    (sequential? sql) (mapcat #(str/split (str %) #"\r?\n" -1) sql)
    :else             []))

(defn- metabase-prefix-line-count
  [executed-sql]
  (count
   (take-while #(re-find metabase-query-comment-pattern %)
               (sql-lines executed-sql))))

(defn- adjust-error-location
  [message executed-sql]
  (let [line-offset (metabase-prefix-line-count executed-sql)]
    (if (zero? line-offset)
      message
      (str/replace
       message
       doris-error-location-pattern
       (fn [[original database-line position]]
         (let [database-line (Long/parseLong database-line)
               editor-line   (- database-line line-offset)]
           (if (pos? editor-line)
             (format "(line %d, pos %s)" editor-line position)
             original)))))))

(defn- ^SQLException sql-exception-with-query
  [^SQLException error ^Throwable original-cause sql params executed-sql]
  (doto (SQLException. (str (adjust-error-location (ex-message error) executed-sql)
                            "\n\nSQL query:\n"
                            (expanded-sql-for-error sql params))
                       (.getSQLState error)
                       (.getErrorCode error))
    (.setNextException error)
    (.addSuppressed original-cause)))

(defmethod driver/execute-reducible-query :doris
  [driver query context respond]
  (try
    (sql-jdbc.execute/execute-reducible-query driver query context respond)
    (catch ExceptionInfo error
      (let [error-data    (ex-data error)
            sql-exception (innermost-sql-exception error)]
        (if (and (= :invalid-query (:type error-data))
                 (not (:query/query-canceled? error-data))
                 sql-exception)
          (throw (ex-info (ex-message error)
                          error-data
                          (sql-exception-with-query sql-exception
                                                    (ex-cause error)
                                                    (get-in query [:native :query])
                                                    (:params error-data)
                                                    (:sql error-data))))
          (throw error))))))

(defmethod sql.qp/->honeysql [:doris :field]
  [driver [_ field-id opts :as field-clause]]
  (let [expr       ((get-method sql.qp/->honeysql [:sql :field]) driver field-clause)
        field-type (or (:effective-type opts)
                       (:base-type opts)
                       (when (integer? field-id)
                         (when-let [field-metadata (driver-api/field (driver-api/metadata-provider) field-id)]
                           ((some-fn :effective-type :base-type) field-metadata))))]
    (if (isa? field-type :type/DateTimeWithTZ)
      (h2x/with-type-info expr (merge (or (h2x/type-info expr) {})
                                      {:effective-type field-type}))
      expr)))

(defmethod sql.qp/->integer :doris
  [driver value]
  (h2x/maybe-cast (sql.qp/integer-dbtype driver) [:round value]))

(defmethod sql-jdbc.execute/set-parameter [:doris OffsetDateTime]
  [driver ^PreparedStatement prepared-statement ^Integer i value]
  (sql-jdbc.execute/set-parameter
   driver
   prepared-statement
   i
   (offset-date-time-parameter-value value)))

(defmethod sql-jdbc.execute/set-parameter [:doris OffsetTime]
  [driver ^PreparedStatement prepared-statement ^Integer i value]
  (sql-jdbc.execute/set-parameter
   driver
   prepared-statement
   i
   (offset-time-parameter-value value)))

(defn- format-offset
  [value]
  (let [offset (t/format "ZZZZZ" (t/zone-offset value))]
    (if (= offset "Z") "UTC" offset)))

(defn- convert-timezone-inline
  [value source-timezone]
  (format "convert_tz('%s', '%s', @@session.time_zone)"
          (t/format "yyyy-MM-dd HH:mm:ss.SSSSSS" value)
          source-timezone))

(defn- inline-local-time
  [value]
  (format "CAST('%s' AS TIME(6))" (t/format "HH:mm:ss.SSSSSS" value)))

(defmethod sql.qp/inline-value [:doris LocalTime]
  [_ value]
  (inline-local-time value))

(defmethod sql.qp/inline-value [:doris OffsetTime]
  [_ value]
  (inline-local-time
   (t/local-time (t/with-offset-same-instant value (t/zone-offset 0)))))

(defmethod sql.qp/inline-value [:doris OffsetDateTime]
  [_ value]
  (convert-timezone-inline value (format-offset value)))

(defmethod sql.qp/inline-value [:doris ZonedDateTime]
  [_ value]
  (convert-timezone-inline value (str (t/zone-id value))))

(defmethod sql.qp/unix-timestamp->honeysql [:doris :seconds]
  [_ _ expr]
  [:cast [:from_unixtime expr] :datetime])

(defmethod sql.qp/unix-timestamp->honeysql [:doris :milliseconds]
  [_ _ expr]
  [:cast [:from_unixtime [:/ expr 1000.0]] [:raw "DATETIME(3)"]])

(defmethod sql.qp/current-datetime-honeysql-form :doris
  [_]
  (h2x/with-database-type-info [:now [:inline 6]] "datetime"))

(defmethod sql.qp/->honeysql [:doris :percentile]
  [driver [_ arg p]]
  [:percentile_approx
   (sql.qp/->honeysql driver arg)
   (sql.qp/->honeysql driver p)])

(defmethod sql.qp/->honeysql [:doris :median]
  [driver [_ arg]]
  (sql.qp/->honeysql driver [:percentile arg 0.5]))

(defmethod sql.qp/->honeysql [:doris :regex-match-first]
  [driver [_ arg pattern]]
  [:regexp_extract
   (sql.qp/->honeysql driver arg)
   (sql.qp/->honeysql driver pattern)
   [:inline 0]])

(defmethod sql.qp/->honeysql [:doris :length]
  [driver [_ arg]]
  [:char_length (sql.qp/->honeysql driver arg)])

(defmethod sql.qp/->honeysql [:doris :split-part]
  [driver [_ text divider position]]
  (let [text     (sql.qp/->honeysql driver text)
        divider  (sql.qp/->honeysql driver divider)
        position (sql.qp/->honeysql driver position)]
    [:case
     [:< position 1]
     ""
     :else
     [:coalesce [:split_part text divider position] ""]]))

(defmethod sql.qp/->honeysql [:doris :convert-timezone]
  [driver [_ arg target-timezone source-timezone]]
  (let [expr       (sql.qp/->honeysql driver arg)
        timestamp? (or (sql.qp.u/field-with-tz? arg)
                       (h2x/is-of-type? expr "timestamp"))]
    (sql.u/validate-convert-timezone-args timestamp? target-timezone source-timezone)
    (h2x/with-database-type-info
     [:convert_tz expr (or source-timezone (driver-api/results-timezone-id)) target-timezone]
     "datetime")))

(defn- preserve-type-info
  [expr new-expr]
  (if-let [type-info (h2x/type-info expr)]
    (h2x/with-type-info new-expr type-info)
    new-expr))

(defn- timestamptz-expr?
  [expr]
  (or (h2x/is-of-type? expr #"^timestamptz")
      (some-> expr h2x/effective-type (#(isa? % :type/DateTimeWithTZ)))))

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
(defmethod sql.qp/date [:doris :day-of-year]     [_ _ expr] [:cast [:date_format expr (h2x/literal "%j")] :int])
(defmethod sql.qp/date [:doris :month-of-year]   [_ _ expr] [:cast [:date_format expr (h2x/literal "%m")] :int])
(defmethod sql.qp/date [:doris :year-of-era]     [_ _ expr] [:year expr])
(defmethod sql.qp/date [:doris :quarter-of-year] [_ _ expr] [:quarter expr])

(defn- doris-day-of-week
  [driver expr]
  (sql.qp/adjust-day-of-week driver
                             [:dayofweek expr]
                             (driver.common/start-of-week-offset-for-day :sunday)))

(defn- doris-local-date
  [expr]
  [:cast [:date_format expr (h2x/literal "%Y-%m-%d")] :date])

(defn- date-only-expr
  [expr]
  (if (timestamptz-expr? expr)
    (doris-local-date expr)
    (h2x/->date expr)))

(defn- doris-timestamptz-week
  [driver expr]
  (let [week-date [:date_add
                   (doris-local-date expr)
                   [:interval [:- 1 (doris-day-of-week driver expr)] :day]]]
    [:cast
     [:concat [:cast week-date :string] " 00:00:00"]
     :timestamptz]))

(defmethod sql.qp/date [:doris :day-of-week]
  [driver _ expr]
  (doris-day-of-week driver expr))

(defmethod sql.qp/date [:doris :week]
  [driver _ expr]
  (if (h2x/is-of-type? expr #"^timestamptz")
    (doris-timestamptz-week driver expr)
    [:date_add
     (sql.qp/date driver :day expr)
     [:interval [:- 1 (doris-day-of-week driver expr)] :day]]))


(defmethod sql.qp/date [:doris :week-of-year]
  [driver unit expr]
  ((get-method sql.qp/date [:sql :week-of-year]) driver unit expr))

(defmethod sql.qp/date [:doris :week-of-year-iso]
  [_ _ expr]
  [:week expr 3])

(defmethod sql.qp/add-interval-honeysql-form :doris
  [_ hsql-form amount unit]
  (preserve-type-info
   hsql-form
   [:date_add hsql-form [:interval amount (keyword (name unit))]]))

(defn- timestampdiff-dates
  [unit x y]
  [:timestampdiff [:raw (str/upper-case (name unit))] (date-only-expr x) (date-only-expr y)])

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
  (throw (ex-info "Doris does not support coercing binary values to temporal types."
                  {:expr expr
                   :coercion :Coercion/YYYYMMDDHHMMSSBytes->Temporal})))

(defmethod sql.qp/cast-temporal-byte [:doris :Coercion/ISO8601Bytes->Temporal]
  [_ _ expr]
  (throw (ex-info "Doris does not support coercing binary values to temporal types."
                  {:expr expr
                   :coercion :Coercion/ISO8601Bytes->Temporal})))

(defmethod driver/db-start-of-week :doris [_]
  :sunday)

(defmethod sql-jdbc.old/set-timezone-sql :doris [_]
  "SET time_zone = %s")
