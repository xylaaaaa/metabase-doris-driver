(ns metabase.driver.doris
  "Standalone Apache Doris driver for Metabase community driver loading."
  (:require
   [metabase.driver :as driver]
   [metabase.driver.doris.connection]
   [metabase.driver.doris.query-processor]
   [metabase.driver.doris.sync]
   [metabase.driver.doris.types]
   [metabase.driver.sql-jdbc :as sql-jdbc]
   [metabase.driver.sql.util :as sql.u]
   [metabase.driver.sql.query-processor.like-escape-char-built-in :as like-escape-char-built-in]))

(set! *warn-on-reflection* true)

(driver/register! :doris :parent #{:sql-jdbc ::like-escape-char-built-in/like-escape-char-built-in})

(defmethod driver/display-name :doris [_]
  "Apache Doris")

(defmethod driver/prettify-native-form :doris
  [_ native-form]
  (sql.u/format-sql-and-fix-params :mysql native-form))

(defmethod sql-jdbc/impl-table-known-to-not-exist? :doris
  [_ ^java.sql.SQLException e]
  (boolean
   (re-find #"(?i)(unknown table|table .* does(?:n't| not) exist)"
            (or (.getMessage e) ""))))

(doseq [[feature supported?] {:set-timezone                     true
                              :basic-aggregations               true
                              :standard-deviation-aggregations  true
                              :expressions                      true
                              :expression-aggregations          true
                              :expression-literals              true
                              :temporal-extract                 true
                              :date-arithmetics                 true
                              :now                              true
                              :datetime-diff                    true
                              :schemas                          true
                              :connection/multiple-databases    true
                              :jdbc/statements                  true
                              :metadata/table-existence-check   true
                              :describe-default-expr            true
                              :describe-is-nullable             true
                              :fingerprint                      true
                              :native-parameters                true
                              :parameterized-sql                false
                              :native-parameter-card-reference  false
                              :native-temporal-units            false
                              :parameters/table-reference       false
                              :nested-queries                   false
                              :table-privileges                 false
                              :metadata/key-constraints         false
                              :describe-fks                     false
                              :describe-fields                  false
                              :describe-indexes                 false
                              :index-info                       false
                              :percentile-aggregations          true
                              :regex                            true
                              :regex/lookaheads-and-lookbehinds false
                              :split-part                       true
                              :nested-fields                    false
                              :nested-field-columns             false
                              :uploads                          false
                              :actions                          false
                              :actions/custom                   false
                              :actions/data-editing             false
                              :workspace                        false
                              :connection-impersonation         false
                              :connection-impersonation-requires-role false
                              :database-replication             false
                              :database-routing                 false
                              :convert-timezone                 true}]
  (defmethod driver/database-supports? [:doris feature] [_ _ _] supported?))
