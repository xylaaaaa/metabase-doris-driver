(ns metabase.driver.doris.connection
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.util.date-2 :as u.date]
   [metabase.util.log :as log])
  (:import
   (java.nio.charset StandardCharsets)
   (java.sql ResultSet ResultSetMetaData SQLException Types)))

(def default-host "localhost")
(def default-port 9030)
(def default-catalog "internal")
(def fallback-db "information_schema")

(defn normalize-catalog
  "Normalize catalog name, defaulting to 'internal' if blank or nil."
  [catalog]
  (let [catalog (some-> catalog str str/trim)]
    (if (str/blank? catalog) default-catalog catalog)))

(defn normalize-db
  "Normalize database name, returning nil if blank."
  [dbname]
  (let [dbname (some-> dbname str str/trim)]
    (when-not (str/blank? dbname) dbname)))

(defn jdbc-db-target
  "Build the JDBC database target string in the format 'catalog.database'.
  Defaults to 'internal.information_schema' if both catalog and dbname are unspecified."
  [{:keys [catalog dbname]}]
  (let [catalog (normalize-catalog catalog)
        dbname  (normalize-db dbname)]
    (str catalog "." (or dbname fallback-db))))

(defn parse-additional-options
  "Parse JDBC additional options string (format: 'key1=value1&key2=value2') into a map.
  Returns empty map if input is blank or nil."
  [additional-options]
  (if (str/blank? additional-options)
    {}
    (into {}
          (comp (remove str/blank?)
                (map #(str/split % #"=" 2))
                (map (fn [[k v]] [(keyword k) (or v "")])))
          (str/split additional-options #"&"))))

(defmethod sql-jdbc.conn/connection-details->spec :doris
  [_ {:keys [host port catalog dbname user password ssl additional-options]
      :or   {host default-host
             port default-port}}]
  (let [jdbc-db (jdbc-db-target {:catalog catalog :dbname dbname})]
    (merge
     {:classname                "org.mariadb.jdbc.Driver"
      :subprotocol              "mysql"
     :subname                  (str "//" host ":" port "/" jdbc-db)
      :user                     user
      :password                 password
      :sslMode                  (if ssl "trust" "disable")
      :tinyInt1isBit            "false"
      :yearIsDateType           "false"
      :allowPublicKeyRetrieval  "true"
      :zeroDateTimeBehavior     "convertToNull"
      :useUnicode               "true"
      :characterEncoding        "UTF-8"}
     (parse-additional-options additional-options))))

(defmethod sql-jdbc.execute/do-with-connection-with-options :doris
  [driver db-or-id-or-spec {:keys [session-timezone] :as options} f]
  ((get-method sql-jdbc.execute/do-with-connection-with-options :sql-jdbc)
   driver
   db-or-id-or-spec
   (assoc (or options {}) :session-timezone (or session-timezone "UTC"))
   f))

(defn parse-doris-timestamp-bytes
  [raw-bytes]
  (when raw-bytes
    (let [parsed (u.date/parse
                  (str/replace-first (String. ^bytes raw-bytes StandardCharsets/UTF_8) " " "T"))]
      (cond
        (instance? java.time.OffsetDateTime parsed)
        (u.date/with-time-zone-same-instant parsed (java.time.ZoneId/of "UTC"))

        (instance? java.time.ZonedDateTime parsed)
        (.toOffsetDateTime ^java.time.ZonedDateTime
                           (u.date/with-time-zone-same-instant parsed (java.time.ZoneId/of "UTC")))

        :else
        parsed))))

(defmethod sql-jdbc.execute/read-column-thunk [:doris Types/TIMESTAMP]
  [_driver ^ResultSet rs ^ResultSetMetaData _rsmeta ^Integer i]
  (fn read-doris-timestamp-thunk []
    (some-> (.getBytes rs i)
            parse-doris-timestamp-bytes)))

(defmethod sql-jdbc.execute/read-column-thunk [:doris Types/TIME]
  [driver ^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (let [parent-thunk ((get-method sql-jdbc.execute/read-column-thunk [:sql-jdbc Types/TIME])
                      driver rs rsmeta i)]
    (fn read-doris-time-thunk []
      (try
        (parent-thunk)
        (catch SQLException _
          (.getString rs i))))))

(defmethod driver/can-connect? :doris
  [driver details]
  (try
    (sql-jdbc.conn/with-connection-spec-for-testing-connection [spec [driver details]]
      (jdbc/query spec ["SELECT 1"])
      true)
    (catch Exception e
      (log/errorf "Doris connection failed: %s" (.getMessage e))
      false)))

(defmethod driver/humanize-connection-error-message :doris
  [_ message]
  (let [msg (if (string? message) message (str message))]
    (cond
      (re-find #"(?i)communications link failure" msg)
      "Unable to connect to Doris. Please check that the host and port are correct."

      (re-find #"(?i)access denied" msg)
      "Access denied. Please check your username and password."

      (re-find #"(?i)unknown database" msg)
      "Database not found. Please check the catalog and database names."

      (re-find #"(?i)unknown catalog|catalog.*not found" msg)
      "Catalog not found. Please check the catalog name."

      (re-find #"(?i)table.*(?:not exist|doesn't exist|does not exist)|unknown table" msg)
      "Table not found. Please check that the table exists in the specified catalog and database."

      (re-find #"(?i)sslhandshake" msg)
      "SSL handshake failed. Check your SSL settings or try disabling SSL."

      (re-find #"(?i)timeout|timed out" msg)
      "Connection timeout. Check network connectivity and Doris FE availability."

      (re-find #"(?i)no suitable driver" msg)
      "JDBC driver not found. Please ensure the MariaDB JDBC driver is properly installed."

      :else
      msg)))
