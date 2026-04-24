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

(def system-excluded-schemas
  #{"information_schema" "__internal_schema" "mysql"})

(defn quote-name
  "Quote a SQL identifier with backticks, escaping any existing backticks."
  [s]
  (str "`" (str/replace (str s) #"`" "``") "`"))

(defn describe-catalog-sql
  "Generate SQL to list databases in a catalog.
  For internal catalog: SHOW DATABASES
  For external catalog: SHOW DATABASES FROM `catalog`"
  [catalog]
  (let [catalog (doris.conn/normalize-catalog catalog)]
    (if (= catalog doris.conn/default-catalog)
      "SHOW DATABASES"
      (str "SHOW DATABASES FROM " (quote-name catalog)))))

(defn describe-schema-sql
  "Generate SQL to list tables in a schema.
  For internal catalog: SHOW TABLES FROM `schema`
  For external catalog: SHOW TABLES FROM `catalog`.`schema`"
  [catalog schema]
  (let [catalog (doris.conn/normalize-catalog catalog)]
    (if (= catalog doris.conn/default-catalog)
      (str "SHOW TABLES FROM " (quote-name schema))
      (str "SHOW TABLES FROM " (quote-name catalog) "." (quote-name schema)))))

(defn describe-table-sql
  "Generate SQL to describe a table's columns.
  For internal catalog: DESC `schema`.`table`
  For external catalog: DESC `catalog`.`schema`.`table`"
  [catalog schema table]
  (let [catalog (doris.conn/normalize-catalog catalog)]
    (if (= catalog doris.conn/default-catalog)
      (str "SHOW FULL COLUMNS FROM " (quote-name table) " FROM " (quote-name schema))
      (str "SHOW FULL COLUMNS FROM "
           (quote-name catalog) "." (quote-name schema) "." (quote-name table)))))

(defn parse-schema-filter-list
  [value]
  (if (str/blank? value)
    #{}
    (into #{}
          (comp (map str/trim)
                (remove str/blank?)
                (map str/lower-case))
          (str/split value #","))))

(defn normalize-schema-name
  [schema]
  (some-> schema str str/trim str/lower-case))

(defn schema-visible?
  [{:keys [include-schemas exclude-schemas]} schema]
  (let [schema-name      (normalize-schema-name schema)
        included-schemas (parse-schema-filter-list include-schemas)
        excluded-schemas (into system-excluded-schemas
                               (parse-schema-filter-list exclude-schemas))]
    (boolean
     (and schema-name
          (not (contains? excluded-schemas schema-name))
          (or (empty? included-schemas)
              (contains? included-schemas schema-name))))))

(defn- nullable-state
  [nullable-value]
  (case (some-> nullable-value str str/trim str/upper-case)
    "YES" true
    "NO" false
    nil))

(defn- normalize-default
  [default-value]
  (when (some? default-value)
    (str default-value)))

(defn- normalize-comment
  [comment]
  (let [comment (some-> comment str)]
    (when-not (str/blank? comment)
      comment)))

(defn full-column-row->field
  [row idx]
  (let [col-name     (or (get row "Field") (get row :Field))
        col-type     (or (get row "Type") (get row :Type))
        nullable-val (or (get row "Null") (get row :Null))
        default-val  (or (get row "Default") (get row :Default))
        comment-val  (or (get row "Comment") (get row :Comment))
        nullable?    (nullable-state nullable-val)
        default-val  (normalize-default default-val)
        comment-val  (normalize-comment comment-val)]
    (cond-> {:name              col-name
             :database-type     col-type
             :base-type         (doris.types/doris-type->base-type col-type)
             :database-position idx}
      (some? default-val)
      (assoc :database-default default-val)

      (some? nullable?)
      (assoc :database-is-nullable nullable?
             :database-required (not nullable?))

      (some? comment-val)
      (assoc :description comment-val
             :field-comment comment-val))))

(defn- get-schemas
  [details catalog ^Connection conn]
  (let [sql (describe-catalog-sql catalog)]
    (log/debugf "Doris sync: list schemas with SQL [%s]" sql)
    (with-open [stmt (.createStatement conn)
                rs   (.executeQuery stmt sql)]
      (loop [schemas []]
        (if (.next ^ResultSet rs)
          (let [schema (.getString ^ResultSet rs 1)]
            (recur (if (schema-visible? details schema)
                     (conj schemas schema)
                     schemas)))
          (do
            (log/debugf "Doris sync: catalog=%s include-schemas=%s exclude-schemas=%s visible-schemas=%s"
                        (doris.conn/normalize-catalog catalog)
                        (:include-schemas details)
                        (:exclude-schemas details)
                        schemas)
            schemas))))))

(defn- get-tables-in-schema
  "Fetch tables from a schema. Throws exception if the query fails (e.g., catalog not found, permission denied).
  Returns empty vector only if the schema genuinely has no tables."
  [catalog ^Connection conn schema]
  (let [sql (describe-schema-sql catalog schema)]
    (log/debugf "Doris sync: list tables with SQL [%s]" sql)
    (with-open [stmt (.createStatement conn)
                rs   (.executeQuery stmt sql)]
    (loop [tables []]
      (if (.next ^ResultSet rs)
        (recur (conj tables {:name   (.getString ^ResultSet rs 1)
                             :schema schema}))
        (do
          (log/debugf "Doris sync: catalog=%s schema=%s tables=%s"
                     (doris.conn/normalize-catalog catalog) schema (map :name tables))
          tables))))))

(defmethod driver/describe-database* :doris
  [driver database]
  (let [details               (driver.conn/effective-details database)
        {:keys [catalog dbname]} details
        catalog (doris.conn/normalize-catalog catalog)
        dbname  (doris.conn/normalize-db dbname)]
    (sql-jdbc.execute/do-with-connection-with-options
     driver
     database
     nil
     (fn [^Connection conn]
       (let [schemas (if dbname
                       [dbname]
                       (get-schemas details catalog conn))
             tables  (into #{}
                           (mapcat (fn [schema]
                                     (get-tables-in-schema catalog conn schema)))
                           schemas)]
         (log/debugf "Doris sync: describe-database catalog=%s dbname=%s table-count=%d"
                    catalog dbname (count tables))
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
                      (let [row {"Field"   (.getString ^ResultSet rs "Field")
                                 "Type"    (.getString ^ResultSet rs "Type")
                                 "Null"    (.getString ^ResultSet rs "Null")
                                 "Default" (.getString ^ResultSet rs "Default")
                                 "Comment" (.getString ^ResultSet rs "Comment")}]
                        (recur (conj fields (full-column-row->field row idx))
                               (inc idx)))
                      (set fields)))})))))

(defmethod driver/describe-table-fks :doris
  [_driver _database _table]
  nil)

(defmethod sql-jdbc.sync/current-user-table-privileges :doris
  [_driver _conn-spec & _options]
  nil)
