(ns metabase.driver.doris.sync
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.doris.connection :as doris.conn]
   [metabase.driver.doris.types :as doris.types]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.util.log :as log])
  (:import
   (java.sql Connection ResultSet)))

(def excluded-schemas
  #{"information_schema" "INFORMATION_SCHEMA" "__internal_schema" "mysql"})

(defn quote-name
  [s]
  (str "`" (str/replace (str s) #"`" "``") "`"))

(defn describe-catalog-sql
  [catalog]
  (let [catalog (doris.conn/normalize-catalog catalog)]
    (if (= catalog doris.conn/default-catalog)
      "SHOW DATABASES"
      (str "SHOW DATABASES FROM " (quote-name catalog)))))

(defn describe-schema-sql
  [catalog schema]
  (let [catalog (doris.conn/normalize-catalog catalog)]
    (if (= catalog doris.conn/default-catalog)
      (str "SHOW TABLES FROM " (quote-name schema))
      (str "SHOW TABLES FROM " (quote-name catalog) "." (quote-name schema)))))

(defn describe-table-sql
  [catalog schema table]
  (let [catalog (doris.conn/normalize-catalog catalog)]
    (if (= catalog doris.conn/default-catalog)
      (str "DESC " (quote-name schema) "." (quote-name table))
      (str "DESC " (quote-name catalog) "." (quote-name schema) "." (quote-name table)))))

(defn- get-schemas
  [catalog ^Connection conn]
  (with-open [stmt (.createStatement conn)
              rs   (.executeQuery stmt (describe-catalog-sql catalog))]
    (loop [schemas []]
      (if (.next ^ResultSet rs)
        (let [schema (.getString ^ResultSet rs 1)]
          (recur (if (contains? excluded-schemas schema)
                   schemas
                   (conj schemas schema))))
        schemas))))

(defn- get-tables-in-schema
  [catalog ^Connection conn schema]
  (try
    (with-open [stmt (.createStatement conn)
                rs   (.executeQuery stmt (describe-schema-sql catalog schema))]
      (loop [tables []]
        (if (.next ^ResultSet rs)
          (recur (conj tables {:name   (.getString ^ResultSet rs 1)
                               :schema schema}))
          tables)))
    (catch Exception e
      (log/warnf "Could not get tables from %s.%s: %s"
                 (doris.conn/normalize-catalog catalog) schema (.getMessage e))
      [])))

(defmethod driver/describe-database* :doris
  [driver database]
  (let [{:keys [catalog dbname]} (driver.conn/effective-details database)
        catalog (doris.conn/normalize-catalog catalog)
        dbname  (doris.conn/normalize-db dbname)]
    (sql-jdbc.execute/do-with-connection-with-options
     driver
     database
     nil
     (fn [^Connection conn]
       (let [schemas (if dbname
                       [dbname]
                       (get-schemas catalog conn))
             tables  (into #{}
                           (mapcat (fn [schema]
                                     (get-tables-in-schema catalog conn schema)))
                           schemas)]
         {:tables tables})))))

(defmethod driver/describe-table :doris
  [driver database {schema :schema table-name :name}]
  (let [{:keys [catalog]} (driver.conn/effective-details database)
        catalog (doris.conn/normalize-catalog catalog)]
    (sql-jdbc.execute/do-with-connection-with-options
     driver
     database
     nil
     (fn [^Connection conn]
       (with-open [stmt (.createStatement conn)
                   rs   (.executeQuery stmt (describe-table-sql catalog schema table-name))]
         {:schema schema
          :name   table-name
          :fields (loop [fields []
                         idx 0]
                    (if (.next ^ResultSet rs)
                      (let [col-name (.getString ^ResultSet rs "Field")
                            col-type (.getString ^ResultSet rs "Type")]
                        (recur (conj fields {:name              col-name
                                             :database-type     col-type
                                             :base-type         (doris.types/doris-type->base-type col-type)
                                             :database-position idx})
                               (inc idx)))
                      (set fields)))})))))

(defmethod driver/describe-table-fks :doris
  [_driver _database _table]
  nil)

(defmethod sql-jdbc.sync/current-user-table-privileges :doris
  [_driver _conn-spec & _options]
  nil)
