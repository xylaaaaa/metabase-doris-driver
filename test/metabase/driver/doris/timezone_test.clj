(ns metabase.driver.doris.timezone-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver])
  (:import
   (java.sql Connection Statement)))

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

      ;; Execute set-timezone!
      (driver/set-timezone! :doris mock-conn "Asia/Shanghai")

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

      (driver/set-timezone! :doris mock-conn "UTC")
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

      (driver/set-timezone! :doris mock-conn "+08:00")
      (is (= "SET time_zone = '+08:00'" @executed-sql)))))
