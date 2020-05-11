; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.config
  (:require
    [clojure.string :as str]
    [clojure.pprint :refer [pprint]]
    [clojure.stacktrace :as st]
    [clojure.java.io :as io]
    [ctest.common :as common]
    [clojure.tools.logging :as log])
  (:import
    com.mchange.v2.c3p0.ComboPooledDataSource
    (org.apache.log4j PropertyConfigurator)))



(defonce ^:private ctest-config
  (atom
    {:log-level :info
     :log-file "ctest.log"
     :data-base-name "ctest.db"
     :qrcode-path "storage/qrcodes"
     :branding {:path "storage/branding"
                :page-logo "logo.png"
                :patient-document-logo "logo.png"
                :page-title "Corona Virus Information",
                :page-title-link "http://your.hospital.org/corona"}
     :order-numbers {:input-format "[0-9]{8,8}"
                     :append-date? true
                     :new-patient-hint "Order Number"
                     :new-patient-note "Note: Order Number must be an eight digit number."
                     :find-patient-hint "XXXXXXXX-YYYYMMDD"
                     :find-patient-note "Note: Order Number must be an eight digit number combined with eight digit date separated by a hyphen."}
     :import ^:replace {:path "import"
                        :order-number-column "auftragsn"
                        :date-column "abnahme"
                        :date-format "dd.MM.yyyy"
                        :result-column "ergebnis"
                        :negative-result "ungr"
                        :column-separator ";"}
     :backup {:path "backup"
              :start-minute 30
              :interval 60}
     :server-config ^:replace {:port 8000
                               :host "localhost"
                               :ssl? false
                               :ssl-port 8443
                               :server-root ""
                               :proxy-url nil
                               :keystore "keystore.jks"
                               :key-password "password"}}))


(defn write-config-file
  [filename, options]
  (let [config (merge @ctest-config options)]
    (spit filename
      (with-open [w (java.io.StringWriter.)]
        (pprint config w)
        (str w)))))


(defn server-config
  []
  (:server-config @ctest-config))


(defn page-title
  []
  (get-in @ctest-config [:branding, :page-title]))


(defn page-title-link
  []
  (get-in @ctest-config [:branding, :page-title-link]))


(defn develop?
  []
  (:develop? @ctest-config))


(defn tracking-server-domain
  []
  (let [{:keys [proxy-url, host, port, ssl?, ssl-port]} (server-config)]
    (or
      proxy-url
      (if ssl?
        (cond-> (str "https://" host) (not= ssl-port 443) (str ":" ssl-port))
        (cond-> (str "http://" host) (not= port 80) (str ":" port))))))


(defn server-root
  []
  (or (:server-root (server-config)) ""))


(defn server-location
  ([path]
   (server-location path, false))
  ([^String path, always-prefix-slash?]
   (let [{:keys [^String server-root]} (server-config)]
     (if (str/blank? server-root)
       path
       (let [slash? (.startsWith path "/")]
         (str
           (when (or slash? always-prefix-slash?) "/")
           server-root
           (when-not (or (str/blank? path) slash?) "/")
           path))))))


(defn trim-slashes
  [^String s]
  (when s
    (let [b (if (.startsWith s "/") 1 0)
          n (.length s)
          e (if (.endsWith s "/") (dec n) n)]
      (.substring s b e))))


(defn normalize-path
  [^String path]
  (when path
    (cond-> path
      (and (not (.endsWith path "/")) (not (str/blank? path)))
      (str "/"))))



(defn deep-merge
  "Merges the `source` map into the `sink` map such that nested map values are merged as well."
  [sink, source]
  (persistent!
    (reduce-kv
      (fn [res-map, k, source-value]
        (let [sink-value (get sink k)]
          (assoc! res-map k
            (cond
              (-> sink-value meta :replace)
              source-value,
              (and (map? sink-value) (map? source-value))
              (deep-merge sink-value, source-value)
              :else
              source-value))))
      (transient sink)
      source)))


(defn matches-pattern?
  [pattern, text]
  (boolean
    (when (string? text)
      (re-matches pattern text))))


(defn build-predicates
  [{:keys [input-format, append-date? date-format] :as order-numbers-config}]
  (let [input-regex (re-pattern input-format)
        order-number-regex (if append-date?
                             (re-pattern (str input-format "-[0-9]{8,8}"))
                             input-regex)]
    (assoc order-numbers-config
      :valid-order-number-input? (partial matches-pattern? input-regex)
      :valid-order-number? (partial matches-pattern? order-number-regex))))


(defn to-char
  [x]
  (cond
    (string? x) (first x)
    (char? x) x
    :else (char x)))


(defn update-config
  [config]
  (swap! ctest-config deep-merge
    (-> config
      (update-in [:server-config, :server-root] trim-slashes)
      (update-in [:qrcode-path] normalize-path)
      (update-in [:branding, :path] normalize-path)
      (update-in [:import, :path] normalize-path)
      (update-in [:import, :column-separator] to-char)
      (update-in [:backup, :path] normalize-path)
      (update-in [:order-numbers] build-predicates))))



(defn check-import-settings
  [{{:keys [path
            column-separator
            order-number-column
            result-column
            negative-result
            date-column
            date-format]} :import,
    {:keys [append-date?]} :order-numbers}]
  (cond-> []
    (str/blank? path)
    (conj "You did not specify a path for the CSV import.")

    (not (common/file-exists? path))
    (conj (format
            "You specified an import path \"%s\" for the CSV import which does not exist."
            path))

    (and column-separator (not (or (string? column-separator) (char? column-separator))))
    (conj (format
            "You specified an invalid :column-separator \"%s\" for the CSV import (valid example: \";\")."
            column-separator))

    (str/blank? order-number-column)
    (conj "You did not specify an :order-number-column for the CSV import.")

    (str/blank? result-column)
    (conj "You did not specify a :result-column for the CSV import.")

    (str/blank? negative-result)
    (conj "You did not specify the :negative-result value for the CSV import.")

    (and append-date? (str/blank? date-column))
    (conj "You did specify to use order numbers with appended date but did not specify a :date-column for CSV import.")

    (and append-date? (str/blank? date-format))
    (conj "You did specify to use order numbers with appended date but did not specify a :date-format for CSV import.")))


(defn check-order-numbers
  [{{:keys [input-format]} :order-numbers}]
  (cond-> []
    (str/blank? input-format)
    (conj "You did not specify an input format for the order numbers.")))


(defn check-config
  []
  (let [config @ctest-config]
    (not-empty
      (persistent!
        (reduce
          (fn [error-vec, check-fn]
            (if-let [errors (check-fn config)]
              (if (sequential? errors)
                (reduce conj! error-vec errors)
                (conj! error-vec errors))
              error-vec))
          (transient [])
          [check-import-settings
           check-order-numbers])))))


(defn database-config
  [db-filename]
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname db-filename})


(def ^:private ^:once db-spec
  (atom (database-config "ctest.db")))


(defn pool
  [spec]
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass (:classname spec))
               (.setJdbcUrl (str "jdbc:" (:subprotocol spec) ":" (:subname spec)))
               (.setUser (:user spec))
               (.setPassword (:password spec))
               ;; expire excess connections after 30 minutes of inactivity:
               (.setMaxIdleTimeExcessConnections (* 30 60))
               ;; expire connections after 3 hours of inactivity:
               (.setMaxIdleTime (* 3 60 60))
               ;; only one connection for SQLite
               (.setMinPoolSize 1)
               (.setMaxPoolSize 1))]
    {:datasource cpds}))


(def db-connection-pool (atom nil))


(defn db-connection
  []
  (if-let [db-conn @db-connection-pool]
    db-conn
    (swap! db-connection-pool #(if (nil? %) (pool @db-spec) %))))


(defn update-db-name [name]
  (swap! db-spec assoc :subname name)
  (reset! db-connection-pool nil))


(defn qrcode-path
  []
  (get-in @ctest-config [:qrcode-path]))


(defn branding-path
  []
  (get-in @ctest-config [:branding, :path]))


(defn page-logo
  []
  (get-in @ctest-config [:branding, :page-logo]))


(defn patient-document-logo
  []
  (get-in @ctest-config [:branding, :patient-document-logo]))


(defn branding-logo-path
  []
  (str (io/file (branding-path) (page-logo))))


(defn import-path []
  (get-in @ctest-config [:import, :path]))

(defn backup-path []
  (get-in @ctest-config [:backup, :path]))

(defn backup-config
  []
  (:backup @ctest-config))


(derive :role/admin :role/user)
(derive :role/admin :role/reporter)
(derive :role/configadmin :role/admin)

(defn set-daemon
  [daemon]
  (swap! ctest-config assoc :daemon daemon))

(defn daemon
  []
  (:daemon @ctest-config))

(defn set-backup-service
  [service]
  (swap! ctest-config assoc :backup-service service))

(defn backup-service
  []
  (:backup-service @ctest-config))


(defn order-number-input-format
  []
  (get-in @ctest-config [:order-numbers, :input-format]))


(defn order-number-append-date?
  []
  (get-in @ctest-config [:order-numbers, :append-date?]))


(defn order-number-date-format
  []
  (get-in @ctest-config [:order-numbers, :date-format]))


(defn order-numbers-config
  []
  (:order-numbers @ctest-config))


(defn import-config
  []
  (:import @ctest-config))


(defn import-negative-result
  []
  (get-in @ctest-config [:import, :negative-result]))


(defn valid-order-number-input-fn
  []
  (get-in @ctest-config [:order-numbers, :valid-order-number-input?]))


(defn valid-order-number-fn
  []
  (get-in @ctest-config [:order-numbers, :valid-order-number?]))


(defn import-csv-columns
  []
  (let [{:keys [order-number-column, date-column, result-column]} (import-config)
        column-names (cond-> [order-number-column, result-column]
                       (order-number-append-date?)
                       (conj date-column))]
    (mapv #(-> % str/trim str/lower-case keyword) column-names)))


(def ^:const db-encoding-in-progress "in progress")
(def ^:const db-encoding-negative "negative")

(defn valid-status-encoding?
  [x]
  (or
    (= x db-encoding-negative)
    (= x db-encoding-in-progress)))

(defn negative-status?
  [s]
  (= s db-encoding-negative))


(defn configure-logging
  "Configures the logging for ctest. Log level and log file can be specified in the configuration."
  [{:keys [log-level, log-file] :as config}]
  (let [props (doto (System/getProperties)
                (.setProperty "log4j.rootLogger" (format "%s, file" (-> log-level name str/upper-case)))
                (.setProperty "log4j.appender.file" "org.apache.log4j.RollingFileAppender")
                (.setProperty "log4j.appender.file.File" (str log-file))
                (.setProperty "log4j.appender.file.MaxFileSize" "4MB")
                (.setProperty "log4j.appender.file.MaxBackupIndex" "5")
                (.setProperty "log4j.appender.file.layout" "org.apache.log4j.PatternLayout")
                (.setProperty "log4j.appender.file.layout.ConversionPattern" "%d{yyyy.MM.dd HH:mm:ss} %5p %c: %m%n")
                ; jetty is too chatty
                (.setProperty "log4j.logger.org.eclipse.jetty" "INFO"))]
    (PropertyConfigurator/configure props))
  nil)


(defn read-config
  [config-file]
  (try
    ; we trust anyone with access to the config file, so just load it
    (load-file config-file)
    (catch Throwable t
      (let [log-file "startup-errors.log"]
        (binding [log/*force* :direct]
          (configure-logging {:log-level :info, :log-file log-file})
          (log/errorf "Error when reading config file \"%s\":\n%s"
            config-file
            (with-out-str (st/print-cause-trace t)))
          (common/exit 2
            (format "Error when reading config file \"%s\": \"%s\"\nFor details see \"%s\"."
              config-file
              (.getMessage t)
              log-file)))))))


(defn db-exists?
  [config-filename, db-filename]
  (if (.exists (io/file db-filename))
    true
    (let [msg (format
                (str
                  "The database file \"%s\" does not exist!\n"
                  "You have the following two options to fix that:\n"
                  "(1) Fix the path to the existing database file in the configuration \"%s\".\n"
                  "(2) Rerun the ctest initialisation.")
                (-> db-filename io/file .getAbsolutePath),
                (-> config-filename io/file .getAbsolutePath))]
      (println msg)
      (log/error "Startup" msg)
      false)))


(defn read+setup-config
  [config-file]
  (let [{:keys [data-base-name] :as config} (read-config config-file)]
    (update-config config)
    (if-let [errors (check-config)]
      (do
        (println
         (format "The configuration file \"%s\" contains the following errors:\n  %s"
           config-file
           (str/join "\n  " errors)))
        false)
      (do
        (configure-logging config)
        (update-db-name data-base-name)
        (db-exists? config-file, data-base-name)))))