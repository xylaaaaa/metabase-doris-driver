(ns metabase.driver.doris
  "Standalone Apache Doris driver for Metabase community driver loading."
  (:require
   [metabase.driver :as driver]
   [metabase.driver.doris.connection]
   [metabase.driver.doris.query-processor]
   [metabase.driver.doris.sync]
   [metabase.driver.doris.types]))

(set! *warn-on-reflection* true)

(driver/register! :doris :parent :sql-jdbc)

(defmethod driver/display-name :doris [_]
  "Apache Doris")

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
                              :fingerprint                      true
                              :native-parameters                false
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
                              :percentile-aggregations          false
                              :regex                            false
                              :regex/lookaheads-and-lookbehinds false
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
                              :convert-timezone                 false}]
  (defmethod driver/database-supports? [:doris feature] [_ _ _] supported?))
