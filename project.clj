

(defn get-version
  []
  (let [version-fn (try
                     (load-file "src/ctest/version.clj")
                     (catch java.io.FileNotFoundException e
                       ; workaround for CCW (version number is not needed anyway)
                       (constantly "0.0.0-REPL-DEV")))]
    (version-fn)))

(def version (get-version))

(defproject ctest version
  :description "Track laboratory projects"
  :main ctest.core
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [compojure "1.3.2"]
                 [com.cemerick/friend "0.2.1"]
                 [ring/ring-jetty-adapter "1.3.2"]
                 [ring/ring-json "0.3.1"]
                 [org.clojure/java.jdbc "0.3.6"]           ; JDBC Binding
                 [com.mchange/c3p0 "0.9.5"]                ; connection pool
                 [org.xerial/sqlite-jdbc "3.8.7"]          ; SQLite
                 [selmer "0.8.0"]                          ; Templating
                 [org.clojure/tools.cli "0.3.1"]
                 [com.draines/postal "1.11.3"]
                 [metosin/ring-http-response "0.5.2"]      ; Exception handling in responses
                 [org.clojure/tools.logging "0.3.1"]       ; logging, e.g. for fail2ban usage
                 [org.slf4j/slf4j-api "1.7.10"]
                 [org.slf4j/slf4j-log4j12 "1.7.10"]
                 ; QR codes
                 [com.google.zxing/core "3.4.0"]
                 [com.google.zxing/javase "3.4.0"]
                 ; persistence of database content (export, import)
                 [com.taoensso/nippy "2.14.0"]
                 ; watch file system
                 [hawk "0.2.10"]
                 [org.clojure/data.csv "1.0.0"]]
  :jar-name ~(format "ctest-lib-%s.jar" version)
  :uberjar-name ~(format "ctest-%s.jar" version)
  :profiles {:uberjar {:aot :all},
             :dev
             {:dependencies
              [[expectations "2.1.1"]]
              :plugins
              [[lein-autoexpect "1.4.2"]]}})
