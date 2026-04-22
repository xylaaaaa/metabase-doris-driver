(ns metabase.driver.doris.types
  (:require
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]))

(def doris-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"(?i)^boolean$"              :type/Boolean]
    [#"(?i)^tinyint$"              :type/Integer]
    [#"(?i)^smallint$"             :type/Integer]
    [#"(?i)^int$"                  :type/Integer]
    [#"(?i)^bigint$"               :type/BigInteger]
    [#"(?i)^largeint$"             :type/BigInteger]
    [#"(?i)^float$"                :type/Float]
    [#"(?i)^double$"               :type/Float]
    [#"(?i)^decimal.*"             :type/Decimal]
    [#"(?i)^varchar.*"             :type/Text]
    [#"(?i)^char.*"                :type/Text]
    [#"(?i)^string$"               :type/Text]
    [#"(?i)^text$"                 :type/Text]
    [#"(?i)^json$"                 :type/JSON]
    [#"(?i)^date$"                 :type/Date]
    [#"(?i)^datetime.*"            :type/DateTime]
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
