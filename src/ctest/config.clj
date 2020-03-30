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
    [clojure.java.io :as io])
  (:import
    com.mchange.v2.c3p0.ComboPooledDataSource))



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
     :import {:path "import"
              :order-number-column "auftragsn"
              :date-column "abnahme"
              :date-format "dd.MM.yyyy"
              :result-column "ergebnis"
              :negative-result "ungr"}
     :backup-path "backup"
     :server-config ^:replace {:port 8000
                               :host "localhost"
                               :ssl? true
                               :ssl-port 8443
                               :forwarded? false
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
  (let [{:keys [proxy-url, host, port, ssl-port]} (server-config)]
    (or
      proxy-url
      (if ssl-port
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


(defn update-config
  [config]
  (swap! ctest-config deep-merge
    (-> config
      (update-in [:server-config, :server-root] trim-slashes)
      (update-in [:qrcode-path] normalize-path)
      (update-in [:branding, :path] normalize-path)
      (update-in [:import, :path] normalize-path)
      (update-in [:backup-path] normalize-path)
      (update-in [:order-numbers] build-predicates))))


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
  (:backup-path @ctest-config))


(derive ::admin ::user)
(derive ::admin ::reporter)
(derive ::configadmin ::admin)

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
