(ns metabase.driver.doris.types
  (:require
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]))

(def doris-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"(?i)^boolean$"              :type/Boolean]
    [#"(?i)^tinyint(?:\(\d+\))?$"  :type/Integer]
    [#"(?i)^smallint(?:\(\d+\))?$" :type/Integer]
    [#"(?i)^int(?:eger)?(?:\(\d+\))?$" :type/Integer]
    [#"(?i)^bigint(?:\(\d+\))?$"   :type/BigInteger]
    [#"(?i)^largeint(?:\(\d+\))?$" :type/BigInteger]
    [#"(?i)^float$"                :type/Float]
    [#"(?i)^double$"               :type/Float]
    [#"(?i)^decimal.*"             :type/Decimal]
    [#"(?i)^varchar.*"             :type/Text]
    [#"(?i)^char.*"                :type/Text]
    [#"(?i)^string$"               :type/Text]
    [#"(?i)^text$"                 :type/Text]
    [#"(?i)^jsonb?$"                :type/JSON]
    [#"(?i)^date(?:v2)?$"           :type/Date]
    [#"(?i)^time(?:\(\d+\))?$"     :type/Time]
    [#"(?i)^datetime.*"             :type/DateTime]
    [#"(?i)^timestamptz.*"          :type/DateTimeWithTZ]
    [#"(?i)^timestamp.*"           :type/DateTime]
    [#"(?i)^array.*"               :type/Array]
    [#"(?i)^map.*"                 :type/Dictionary]
    [#"(?i)^struct.*"              :type/*]
    [#"(?i)^bitmap$"               :type/*]
    [#"(?i)^hll$"                  :type/*]
    [#"(?i)^variant$"              :type/*]
    [#".*"                         :type/*]]))

(defmethod sql-jdbc.sync/database-type->base-type :doris
  [_ field-type]
  (doris-type->base-type field-type))
