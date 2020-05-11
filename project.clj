

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
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [compojure "1.6.1"]
                 [com.cemerick/friend "0.2.3"]
                 [ring/ring-core "1.8.1"]
                 [ring/ring-jetty-adapter "1.8.1"]
                 [ring/ring-json "0.5.0"]
                 [ring/ring-headers "0.3.0"]                ; replacing :remote-addr with origin address in proxy scenario
                 [org.clojure/java.jdbc "0.7.11"]           ; JDBC Binding
                 [com.mchange/c3p0 "0.9.5.5"]               ; connection pool
                 [org.xerial/sqlite-jdbc "3.31.1"]          ; SQLite
                 [selmer "1.12.23"]                         ; Templating
                 [org.clojure/tools.cli "1.0.194"]
                 [metosin/ring-http-response "0.9.1"]       ; Exception handling in responses
                 [org.clojure/tools.logging "1.0.0"]        ; logging, e.g. for fail2ban usage
                 [org.slf4j/slf4j-api "1.7.30"]
                 [org.slf4j/slf4j-log4j12 "1.7.30"]
                 ; QR codes
                 [com.google.zxing/core "3.4.0"]
                 [com.google.zxing/javase "3.4.0"]
                 ; persistence of database content (export, import)
                 [com.taoensso/nippy "2.14.0"]
                 ; watch file system
                 [hawk "0.2.10"]
                 [org.clojure/data.csv "1.0.0"]]
  :jar-name "ctest-lib-%s.jar"
  :uberjar-name "ctest-%s.jar"
  :profiles {:uberjar {:aot :all},
             :dev
             {:dependencies
              [[expectations "2.1.1"]]
              :plugins
              [[lein-autoexpect "1.4.2"]]}})
