(ns build
  (:require
   [clojure.java.io :as io]
   [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file "target/doris.metabase-driver.jar")
(def legal-files {"LICENSE.txt" "META-INF/LICENSE.txt"
                  "NOTICE.txt"  "META-INF/NOTICE.txt"})

(defn clean [_]
  (b/delete {:path "target"}))

(defn- prepare-class-dir []
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (doseq [[source target] legal-files]
    (let [target-file (io/file class-dir target)]
      (io/make-parents target-file)
      (io/copy (io/file source) target-file))))

(defn jar [_]
  (clean nil)
  (prepare-class-dir)
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn uber [_]
  (clean nil)
  (prepare-class-dir)
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis basis}))
