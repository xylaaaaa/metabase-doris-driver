(ns metabase.driver.doris.types-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.doris.types :as doris.types]))

(deftest boolean-type-mapping-test
  (testing "maps BOOLEAN to type/Boolean"
    (is (= :type/Boolean (doris.types/doris-type->base-type "BOOLEAN")))
    (is (= :type/Boolean (doris.types/doris-type->base-type "boolean")))))

(deftest integer-type-mapping-test
  (testing "maps TINYINT to type/Integer"
    (is (= :type/Integer (doris.types/doris-type->base-type "TINYINT")))
    (is (= :type/Integer (doris.types/doris-type->base-type "tinyint"))))
  (testing "maps SMALLINT to type/Integer"
    (is (= :type/Integer (doris.types/doris-type->base-type "SMALLINT"))))
  (testing "maps INT to type/Integer"
    (is (= :type/Integer (doris.types/doris-type->base-type "INT"))))
  (testing "maps BIGINT to type/BigInteger"
    (is (= :type/BigInteger (doris.types/doris-type->base-type "BIGINT"))))
  (testing "maps LARGEINT to type/BigInteger"
    (is (= :type/BigInteger (doris.types/doris-type->base-type "LARGEINT")))))

(deftest float-type-mapping-test
  (testing "maps FLOAT to type/Float"
    (is (= :type/Float (doris.types/doris-type->base-type "FLOAT"))))
  (testing "maps DOUBLE to type/Float"
    (is (= :type/Float (doris.types/doris-type->base-type "DOUBLE")))))

(deftest decimal-type-mapping-test
  (testing "maps DECIMAL to type/Decimal"
    (is (= :type/Decimal (doris.types/doris-type->base-type "DECIMAL")))
    (is (= :type/Decimal (doris.types/doris-type->base-type "DECIMAL(10,2)")))
    (is (= :type/Decimal (doris.types/doris-type->base-type "DECIMALV3(38,9)")))))

(deftest string-type-mapping-test
  (testing "maps VARCHAR to type/Text"
    (is (= :type/Text (doris.types/doris-type->base-type "VARCHAR")))
    (is (= :type/Text (doris.types/doris-type->base-type "VARCHAR(255)"))))
  (testing "maps CHAR to type/Text"
    (is (= :type/Text (doris.types/doris-type->base-type "CHAR")))
    (is (= :type/Text (doris.types/doris-type->base-type "CHAR(10)"))))
  (testing "maps STRING to type/Text"
    (is (= :type/Text (doris.types/doris-type->base-type "STRING"))))
  (testing "maps TEXT to type/Text"
    (is (= :type/Text (doris.types/doris-type->base-type "TEXT")))))

(deftest json-type-mapping-test
  (testing "maps JSON to type/JSON"
    (is (= :type/JSON (doris.types/doris-type->base-type "JSON")))
    (is (= :type/JSON (doris.types/doris-type->base-type "json")))))

(deftest date-type-mapping-test
  (testing "maps DATE to type/Date"
    (is (= :type/Date (doris.types/doris-type->base-type "DATE"))))
  (testing "maps TIME to type/Time"
    (is (= :type/Time (doris.types/doris-type->base-type "TIME")))
    (is (= :type/Time (doris.types/doris-type->base-type "TIME(3)"))))
  (testing "maps DATETIME to type/DateTime"
    (is (= :type/DateTime (doris.types/doris-type->base-type "DATETIME")))
    (is (= :type/DateTime (doris.types/doris-type->base-type "DATETIMEV2")))
    (is (= :type/DateTime (doris.types/doris-type->base-type "DATETIMEV2(3)")))))

(deftest timestamp-type-mapping-test
  (testing "maps TIMESTAMP to type/DateTime"
    (is (= :type/DateTime (doris.types/doris-type->base-type "TIMESTAMP")))))

(deftest complex-type-mapping-test
  (testing "maps ARRAY to type/Array"
    (is (= :type/Array (doris.types/doris-type->base-type "ARRAY")))
    (is (= :type/Array (doris.types/doris-type->base-type "ARRAY<INT>")))
    (is (= :type/Array (doris.types/doris-type->base-type "array<array<boolean>>")))
    (is (= :type/Array (doris.types/doris-type->base-type "array<map<boolean,boolean>>"))))
  (testing "maps MAP to type/Dictionary"
    (is (= :type/Dictionary (doris.types/doris-type->base-type "MAP")))
    (is (= :type/Dictionary (doris.types/doris-type->base-type "MAP<STRING,INT>")))
    (is (= :type/Dictionary (doris.types/doris-type->base-type "map<varchar(10),boolean>"))))
  (testing "maps STRUCT to type/*"
    (is (= :type/* (doris.types/doris-type->base-type "STRUCT")))
    (is (= :type/* (doris.types/doris-type->base-type "STRUCT<a:INT,b:STRING>")))))

(deftest doris-specific-type-mapping-test
  (testing "maps BITMAP to type/*"
    (is (= :type/* (doris.types/doris-type->base-type "BITMAP"))))
  (testing "maps HLL to type/*"
    (is (= :type/* (doris.types/doris-type->base-type "HLL"))))
  (testing "maps VARIANT to type/*"
    (is (= :type/* (doris.types/doris-type->base-type "VARIANT")))))

(deftest unknown-type-mapping-test
  (testing "maps unknown types to type/*"
    (is (= :type/* (doris.types/doris-type->base-type "UNKNOWN_TYPE")))
    (is (= :type/* (doris.types/doris-type->base-type "CUSTOM_TYPE")))))

(deftest case-insensitive-mapping-test
  (testing "handles mixed case type names"
    (is (= :type/Integer (doris.types/doris-type->base-type "Int")))
    (is (= :type/Text (doris.types/doris-type->base-type "VarChar")))
    (is (= :type/DateTime (doris.types/doris-type->base-type "DateTime")))))
