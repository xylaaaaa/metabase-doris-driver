(ns metabase.driver.doris.timezone-test
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.doris]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute])
  (:import
   (java.sql Connection Statement)))

(deftest db-default-timezone-test
  (let [database {:id 1}
        result   (atom {:global_tz "Asia/Shanghai"
                        :system_tz "UTC"})]
    (with-redefs [sql-jdbc.execute/do-with-connection-with-options
                  (fn [actual-driver actual-database options f]
                    (is (= :doris actual-driver))
                    (is (= database actual-database))
                    (is (nil? options))
                    (f ::connection))
                  jdbc/query
                  (fn [spec query]
                    (is (= {:connection ::connection} spec))
                    (is (re-find #"@@global\.time_zone" (first query)))
                    [@result])]
      (testing "uses a named global timezone"
        (is (= "Asia/Shanghai"
               (driver/db-default-timezone :doris database))))
      (testing "resolves SYSTEM to the server system timezone"
        (reset! result {:global_tz "SYSTEM"
                        :system_tz "America/Los_Angeles"})
        (is (= "America/Los_Angeles"
               (driver/db-default-timezone :doris database))))
      (testing "rejects an incomplete SYSTEM response"
        (reset! result {:global_tz "SYSTEM"
                        :system_tz " "})
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"without a system timezone"
             (driver/db-default-timezone :doris database))))))
  (testing "propagates connection failures"
    (with-redefs [sql-jdbc.execute/do-with-connection-with-options
                  (fn [& _]
                    (throw (ex-info "timezone lookup failed" {})))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"timezone lookup failed"
           (driver/db-default-timezone :doris {:id 1}))))))

(deftest set-timezone-test
  (testing "set-timezone! executes SET time_zone statement"
    (let [executed-sql (atom nil)
          mock-stmt (reify Statement
                      (execute [_ sql]
                        (reset! executed-sql sql)
                        true)
                      (close [_]))
          mock-conn (reify Connection
                      (createStatement [_]
                        mock-stmt))]

      (sql-jdbc.execute/set-time-zone-if-supported! :doris mock-conn "Asia/Shanghai")

      ;; Verify the SQL was executed
      (is (= "SET time_zone = 'Asia/Shanghai'" @executed-sql))))

  (testing "set-timezone! handles UTC timezone"
    (let [executed-sql (atom nil)
          mock-stmt (reify Statement
                      (execute [_ sql]
                        (reset! executed-sql sql)
                        true)
                      (close [_]))
          mock-conn (reify Connection
                      (createStatement [_]
                        mock-stmt))]

      (sql-jdbc.execute/set-time-zone-if-supported! :doris mock-conn "UTC")
      (is (= "SET time_zone = 'UTC'" @executed-sql))))

  (testing "set-timezone! handles offset-based timezones"
    (let [executed-sql (atom nil)
          mock-stmt (reify Statement
                      (execute [_ sql]
                        (reset! executed-sql sql)
                        true)
                      (close [_]))
          mock-conn (reify Connection
                      (createStatement [_]
                        mock-stmt))]

      (sql-jdbc.execute/set-time-zone-if-supported! :doris mock-conn "+08:00")
      (is (= "SET time_zone = '+08:00'" @executed-sql)))))

(deftest do-with-connection-with-options-resets-timezone-test
  (let [executed-sql (atom [])
        mock-stmt    (reify Statement
                       (execute [_ sql]
                         (swap! executed-sql conj sql)
                         true)
                       (close [_]))
        mock-conn    (reify Connection
                       (createStatement [_] mock-stmt)
                       (setReadOnly [_ _] nil)
                       (setAutoCommit [_ _] nil)
                       (setHoldability [_ _] nil))]
    (with-redefs [sql-jdbc.execute/do-with-resolved-connection (fn [_driver _db-or-id-or-spec _options f]
                                                                 (f mock-conn))
                  sql-jdbc.execute/set-best-transaction-level! (fn [& _] nil)
                  sql-jdbc.execute/set-role-if-supported!      (fn [& _] nil)
                  sql-jdbc.execute/recursive-connection?       (fn [] false)]
      (sql-jdbc.execute/do-with-connection-with-options
       :doris
       {:id 1}
       nil
       (fn [_conn] :ok))
      (is (= ["SET time_zone = 'UTC'"] @executed-sql)))))
