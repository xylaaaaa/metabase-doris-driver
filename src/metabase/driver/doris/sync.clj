(ns metabase.driver.doris.sync
  (:require
   [clojure.string :as str]
   [honey.sql :as sql]
   [metabase.driver :as driver]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.doris.connection :as doris.conn]
   [metabase.driver.doris.types :as doris.types]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.util.log :as log])
  (:import
   (clojure.lang MultiFn)
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
    (let [default-value (str default-value)]
      (when-not (= "null" (str/lower-case default-value))
        default-value))))

(defn- normalize-comment
  [comment]
  (let [comment (some-> comment str)]
    (when-not (str/blank? comment)
      comment)))

(defn- row-value
  [row column]
  (if (contains? row column)
    (get row column)
    (get row (keyword column))))

(defn full-column-row->field
  [row idx]
  (let [col-name     (row-value row "Field")
        col-type     (row-value row "Type")
        nullable-val (row-value row "Null")
        default-val  (row-value row "Default")
        extra-val    (some-> (row-value row "Extra") str str/lower-case)
        comment-val  (row-value row "Comment")
        nullable?    (nullable-state nullable-val)
        default-val  (normalize-default default-val)
        comment-val  (normalize-comment comment-val)
        auto?        (boolean (some-> extra-val (str/includes? "auto_increment")))
        generated?   (boolean (some-> extra-val (str/includes? "generated")))]
    (cond-> {:name              col-name
             :database-type     col-type
             :base-type         (doris.types/doris-type->base-type col-type)
             :database-position idx
             :pk?                false
             :database-is-auto-increment auto?
             :database-is-generated generated?}
      (some? default-val)
      (assoc :database-default default-val)

      (some? nullable?)
      (assoc :database-is-nullable nullable?
             :database-required (and (not nullable?)
                                     (nil? default-val)
                                     (not auto?)
                                     (not generated?)))

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

(defn- get-tables
  [details catalog dbname ^Connection conn schema-names]
  (let [requested-schemas (some-> schema-names set)
        schemas           (if dbname
                            [dbname]
                            (get-schemas details catalog conn))
        schemas           (if requested-schemas
                            (filter requested-schemas schemas)
                            schemas)]
    (into []
          (mapcat (fn [schema]
                    (get-tables-in-schema catalog conn schema)))
          schemas)))

(defn- get-fields-in-table
  [catalog ^Connection conn {schema :schema table-name :name}]
  (let [sql (describe-table-sql catalog schema table-name)]
    (log/debugf "Doris sync: describe table with SQL [%s]" sql)
    (with-open [stmt (.createStatement conn)
                rs   (.executeQuery stmt sql)]
      (loop [fields []
             idx 0]
        (if (.next ^ResultSet rs)
          (let [row {"Field"   (.getString ^ResultSet rs "Field")
                     "Type"    (.getString ^ResultSet rs "Type")
                     "Null"    (.getString ^ResultSet rs "Null")
                     "Default" (.getString ^ResultSet rs "Default")
                     "Extra"   (.getString ^ResultSet rs "Extra")
                     "Comment" (.getString ^ResultSet rs "Comment")}]
            (recur (conj fields (full-column-row->field row idx))
                   (inc idx)))
          fields)))))

(defn- select-tables
  [tables schema-names table-names]
  (let [schema-names (some-> schema-names set)
        table-names  (some-> table-names set)]
    (->> tables
         (filter (fn [{:keys [schema name]}]
                   (and (or (nil? schema-names)
                            (contains? schema-names schema))
                        (or (nil? table-names)
                            (contains? table-names name)))))
         (sort-by (juxt :schema :name)))))

(defn- fields-with-table-identity
  [fields {:keys [schema name]}]
  (map #(assoc % :table-schema schema :table-name name)
       (sort-by :database-position fields)))

(defn- internal-describe-fields-query
  [details schema-names table-names]
  (let [dbname          (doris.conn/normalize-db (:dbname details))
        included        (parse-schema-filter-list (:include-schemas details))
        excluded        (into system-excluded-schemas
                              (parse-schema-filter-list (:exclude-schemas details)))
        conditions      (remove nil?
                                [[:= :c.table_catalog doris.conn/default-catalog]
                                 (when dbname
                                   [:= :c.table_schema dbname])
                                 (when (and (nil? dbname) (seq included))
                                   [:in [:lower :c.table_schema] (sort included)])
                                 (when (nil? dbname)
                                   [:not-in [:lower :c.table_schema] (sort excluded)])
                                 (when schema-names
                                   [:in :c.table_schema schema-names])
                                 (when table-names
                                   [:in :c.table_name table-names])])]
    (sql/format
     {:select [:c.column_name
               :c.ordinal_position
               :c.table_schema
               :c.table_name
               :c.column_type
               :c.data_type
               :c.is_nullable
               :c.column_default
               :c.extra
               :c.generation_expression
               :c.column_comment]
      :from [[:information_schema.columns :c]]
      :where (into [:and] conditions)
      :order-by [:c.table_schema :c.table_name :c.ordinal_position]}
     {:dialect :mysql})))

(defn- information-schema-row->field
  [{:keys [column_name ordinal_position table_schema table_name column_type data_type
           is_nullable column_default extra generation_expression column_comment]}]
  (let [generated? (not (str/blank? (some-> generation_expression str)))
        extra      (str/join " " (remove str/blank? [(some-> extra str)
                                                      (when generated? "generated")]))]
    (assoc (full-column-row->field
            {"Field" column_name
             "Type" (or column_type data_type)
             "Null" is_nullable
             "Default" column_default
             "Extra" extra
             "Comment" column_comment}
            (dec (long ordinal_position)))
           :table-schema table_schema
           :table-name table_name)))

(defn- get-internal-fields
  [database details schema-names table-names]
  (let [query (internal-describe-fields-query details schema-names table-names)]
    (log/debugf "Doris sync: batch describe fields with SQL [%s]" (first query))
    (eduction (map information-schema-row->field)
              (sql-jdbc.execute/reducible-query database query))))

(defmethod driver/describe-database* :doris
  [driver database]
  (let [details               (driver.conn/effective-details database)
        {:keys [catalog dbname]} details
        catalog               (doris.conn/normalize-catalog catalog)
        dbname                (doris.conn/normalize-db dbname)]
    (sql-jdbc.execute/do-with-connection-with-options
     driver
     database
     nil
     (fn [^Connection conn]
       (let [tables (set (get-tables details catalog dbname conn nil))]
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
       {:schema schema
        :name   table-name
        :fields (set (get-fields-in-table catalog conn {:schema schema :name table-name}))}))))

(defmethod driver/describe-fields :doris
  [driver database & {:keys [schema-names table-names]}]
  (if (or (and schema-names (empty? schema-names))
          (and table-names (empty? table-names)))
    []
    (let [details               (driver.conn/effective-details database)
          {:keys [catalog dbname]} details
          catalog               (doris.conn/normalize-catalog catalog)
          dbname                (doris.conn/normalize-db dbname)]
      (if (= catalog doris.conn/default-catalog)
        (get-internal-fields database details schema-names table-names)
        (sql-jdbc.execute/do-with-connection-with-options
         driver
         database
         nil
         (fn [^Connection conn]
           (let [tables (select-tables
                         (get-tables details catalog dbname conn schema-names)
                         schema-names
                         table-names)]
             (into []
                   (mapcat (fn [table]
                             (try
                               (fields-with-table-identity
                                (get-fields-in-table catalog conn table)
                                table)
                               (catch Throwable e
                                 (log/warn e
                                           (format "Failed to describe Doris table %s.%s.%s"
                                                   catalog (:schema table) (:name table)))
                                 []))))
                   tables))))))))

(when-let [legacy-describe-table-fks (ns-resolve 'metabase.driver 'describe-table-fks)]
  ;; Metabase removed this multimethod in 0.63. Register it only on older
  ;; versions so loading the Doris driver does not resolve a removed Var.
  (.addMethod ^MultiFn (var-get legacy-describe-table-fks)
              :doris
              (fn [_driver _database _table]
                #{})))

(defmethod sql-jdbc.sync/current-user-table-privileges :doris
  [_driver _conn-spec & _options]
  nil)
