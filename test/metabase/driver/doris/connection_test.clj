(ns metabase.driver.doris.connection-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.doris.connection :as doris.conn]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.util.date-2 :as u.date])
  (:import
   (java.nio.charset StandardCharsets)
   (java.sql ResultSet ResultSetMetaData SQLException Types)))

(deftest connection-details->spec-test
  (testing "builds JDBC spec with default port"
    (let [spec (sql-jdbc.conn/connection-details->spec
                :doris
                {:host "localhost"
                 :catalog "internal"
                 :dbname "test_db"
                 :user "root"
                 :password ""})]
      (is (= "org.mariadb.jdbc.Driver" (:classname spec)))
      (is (= "mysql" (:subprotocol spec)))
      (is (= "//localhost:9030/internal.test_db" (:subname spec)))
      (is (= "root" (:user spec)))
      (is (nil? (:sessionVariables spec)))
      (is (= "true" (:useCompression spec)))
      (is (= "true" (:useLocalSessionState spec)))
      (is (= "UTF-8" (:characterSetResults spec)))))

  (testing "builds JDBC spec with custom port"
    (let [spec (sql-jdbc.conn/connection-details->spec
                :doris
                {:host "doris-fe"
                 :port 9031
                 :catalog "hive_catalog"
                 :dbname "tpch"
                 :user "admin"
                 :password "secret"})]
      (is (= "//doris-fe:9031/hive_catalog.tpch" (:subname spec)))
      (is (= "admin" (:user spec)))
      (is (= "secret" (:password spec)))))

  (testing "uses the default port when the submitted port is nil"
    (let [spec (sql-jdbc.conn/connection-details->spec
                :doris
                {:host "localhost"
                 :port nil
                 :catalog "internal"
                 :dbname "test_db"})]
      (is (= "//localhost:9030/internal.test_db" (:subname spec)))))

  (testing "includes SSL mode when specified"
    (let [spec (sql-jdbc.conn/connection-details->spec
                :doris
                {:host "localhost"
                 :catalog "internal"
                 :dbname "test_db"
                 :user "root"
                 :password ""
                 :ssl true
                 :ssl-mode "require"})]
      (is (= "trust" (:sslMode spec)))))

  (testing "includes additional options"
    (let [spec (sql-jdbc.conn/connection-details->spec
                :doris
                {:host "localhost"
                 :catalog "internal"
                 :dbname "test_db"
                 :user "root"
                 :password ""
                 :additional-options "useCompression=true&maxAllowedPacket=16777216"})]
      (is (= "true" (:useCompression spec)))
      (is (= "16777216" (:maxAllowedPacket spec))))))

(deftest jdbc-db-target-test
  (testing "defaults to internal.information_schema"
    (is (= "internal.information_schema"
           (doris.conn/jdbc-db-target {}))))

  (testing "builds internal catalog db target"
    (is (= "internal.analytics"
           (doris.conn/jdbc-db-target {:catalog "internal" :dbname "analytics"}))))

  (testing "builds external catalog db target"
    (is (= "hive_catalog.tpch"
           (doris.conn/jdbc-db-target {:catalog "hive_catalog" :dbname "tpch"}))))

  (testing "handles nil catalog"
    (is (= "internal.test_db"
           (doris.conn/jdbc-db-target {:catalog nil :dbname "test_db"}))))

  (testing "handles nil dbname"
    (is (= "hive_catalog.information_schema"
           (doris.conn/jdbc-db-target {:catalog "hive_catalog" :dbname nil})))))

(deftest parse-additional-options-test
  (testing "parses single option"
    (is (= {:useCompression "true"}
           (doris.conn/parse-additional-options "useCompression=true"))))

  (testing "parses multiple options"
    (is (= {:allowPublicKeyRetrieval "true"
            :useUnicode "true"
            :characterEncoding "UTF-8"}
           (doris.conn/parse-additional-options
            "allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8"))))

  (testing "handles empty string"
    (is (= {} (doris.conn/parse-additional-options ""))))

  (testing "handles nil"
    (is (= {} (doris.conn/parse-additional-options nil)))))

(deftest humanize-connection-error-message-test
  (testing "humanizes communications link failure"
    (is (= :cannot-connect-check-host-and-port
           (driver/humanize-connection-error-message
            :doris
            ["Communications link failure: java.net.ConnectException: Connection refused"]))))

  (testing "humanizes access denied error"
    (is (= :username-or-password-incorrect
           (driver/humanize-connection-error-message
            :doris
            ["Access denied for user 'root'@'localhost' (using password: YES)"]))))

  (testing "humanizes an actionable cause later in the exception chain"
    (is (= :username-or-password-incorrect
           (driver/humanize-connection-error-message
            :doris
            ["Unable to connect"
             "Access denied for user 'root'@'localhost' (using password: YES)"]))))

  (testing "humanizes unknown database error"
    (is (= :database-name-incorrect
           (driver/humanize-connection-error-message
            :doris
            ["Unknown database 'nonexistent_db'"]))))

  (testing "humanizes unknown catalog error"
    (is (= "Catalog not found. Please check the catalog name."
           (driver/humanize-connection-error-message
            :doris
            "Unknown catalog 'nonexistent_catalog'"))))

  (testing "humanizes table not found error"
    (is (= "Table not found. Please check that the table exists in the specified catalog and database."
           (driver/humanize-connection-error-message
            :doris
            "Table 'test_db.nonexistent_table' doesn't exist"))))

  (testing "humanizes SSL handshake error"
    (is (= "SSL handshake failed. Check your SSL settings or try disabling SSL."
           (driver/humanize-connection-error-message
            :doris
            "javax.net.ssl.SSLHandshakeException: PKIX path building failed"))))

  (testing "humanizes timeout error"
    (is (= "Connection timeout. Check network connectivity and Doris FE availability."
           (driver/humanize-connection-error-message
            :doris
            "Connection timed out after 30000ms"))))

  (testing "humanizes driver not found error"
    (is (= "JDBC driver not found. Please ensure the MariaDB JDBC driver is properly installed."
           (driver/humanize-connection-error-message
            :doris
            "No suitable driver found for jdbc:mariadb://localhost:9030"))))

  (testing "passes through unknown errors"
    (is (= "Some unknown error"
           (driver/humanize-connection-error-message
            :doris
            ["Some unknown error" "Outer wrapper"])))))

(deftest read-column-thunk-parses-raw-timestamp-bytes-test
  (letfn [(reader-for [value]
            (let [rs (proxy [ResultSet] []
                       (getObject
                         ([i] (throw (SQLException. (str "unsupported getObject(" i ")"))))
                         ([i _klass] (throw (SQLException. (str "unsupported getObject(" i ", klass)")))))
                       (getString [i] (throw (SQLException. (str "unsupported getString(" i ")"))))
                       (getBytes [_i] (.getBytes value StandardCharsets/UTF_8)))
                  rsmeta (proxy [ResultSetMetaData] []
                           (getColumnType [_i] java.sql.Types/TIMESTAMP)
                           (getColumnTypeName [_i] "DATETIME"))]
              (sql-jdbc.execute/read-column-thunk :doris rs rsmeta 1)))]
    (testing "parses timestamptz bytes with offset"
      (is (= "2014-07-03T01:30Z"
             (str ((reader-for "2014-07-03 01:30:00+00:00"))))))
    (testing "normalizes offset timestamps to UTC"
      (is (= "2019-11-01T07:23:18.331Z"
             (str ((reader-for "2019-11-01 00:23:18.331-07:00"))))))
    (testing "parses datetime bytes without offset"
      (is (= (u.date/parse "2019-04-21T16:43:00.123")
             ((reader-for "2019-04-21 16:43:00.123")))))))

(deftest read-column-thunk-preserves-duration-time-test
  (letfn [(read-time [value]
            (let [rs (proxy [ResultSet] []
                       (getObject
                         ([i] (throw (SQLException. (str "unsupported getObject(" i ")"))))
                         ([i _klass] (throw (SQLException. (str "unsupported getObject(" i ", klass)")))))
                       (getTime [_i] (throw (SQLException. "unsupported getTime")))
                       (getString [_i] value))
                  rsmeta (proxy [ResultSetMetaData] []
                           (getColumnType [_i] Types/TIME)
                           (getColumnTypeName [_i] "TIME"))]
              ((sql-jdbc.execute/read-column-thunk :doris rs rsmeta 1))))]
    (is (= "-12:34:56" (read-time "-12:34:56")))
    (is (= "25:01:02" (read-time "25:01:02")))))

(deftest read-column-thunk-preserves-standard-time-test
  (letfn [(read-time [value]
            (let [rs (proxy [ResultSet] []
                       (getObject
                         ([_i] value)
                         ([_i _klass] value))
                       (getString [_i]
                         (throw (SQLException. "unexpected string fallback"))))
                  rsmeta (proxy [ResultSetMetaData] []
                           (getColumnType [_i] Types/TIME)
                           (getColumnTypeName [_i] "TIME"))]
              ((sql-jdbc.execute/read-column-thunk :doris rs rsmeta 1))))]
    (is (= (java.time.LocalTime/parse "12:34:56.123")
           (read-time (java.time.LocalTime/parse "12:34:56.123"))))
    (is (nil? (read-time nil)))))
