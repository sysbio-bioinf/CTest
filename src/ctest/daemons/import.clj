; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.daemons.import
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hawk.core :as hawk]
            [ctest.config :as c]
            [ctest.actions.tools :as t]
            [ctest.reporting :as report]
            [ctest.db.crud :as crud]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log])
  (:import (java.io File)
           (java.nio.file WatchService)
           (java.time ZonedDateTime)))




;; Loading
(defn csv-data->maps [csv-data]
  (map zipmap
    (->> (first csv-data) ;; first row is the header
      (map keyword) ;; drop if you want string keys instead
      repeat)
    (rest csv-data)))

;; Test loading
#_(csv-data->maps (csv/read-csv (io/reader "../data/test1.csv")))

;; Loading and parsing
(defn csv-data->maps->parse [csv-file]
  (->> (csv/read-csv (io/reader csv-file))
    csv-data->maps
    (map (fn [csv-record] (update-in csv-record [:Auftragsnummer] #(Long/parseLong %))))))

;; Test loading parsing
#_(csv-data->maps->parse "../data/test1.csv")


(defn file-size
  ^long [^File file]
  (.length file))

; 1 second
(def ^:const minimal-duration 1)

(defn enough-modification-duration?
  [^ZonedDateTime last-modification, ^ZonedDateTime now]
  (-> last-modification
    (.plusSeconds minimal-duration)
    (.isBefore now)))


(defn unchanged-files
  [watch-state]
  (dosync
    (let [now (t/now)
          file-map (ensure watch-state)
          unmodified-files (persistent!
                             (reduce-kv
                               (fn [result-list, filename, {:keys [size, time, ^File file]}]
                                 (if (and
                                       (== size (file-size file))
                                       (enough-modification-duration? time now))
                                   (conj! result-list filename)
                                   result-list))
                               (transient [])
                               file-map))
          new-file-map (persistent!
                         (reduce
                           (fn [result-map, filename]
                             (dissoc! result-map filename))
                           (transient file-map)
                           unmodified-files))]
      (ref-set watch-state new-file-map)
      (not-empty unmodified-files))))


(defn csv-row->patient
  [{:keys [mandatory-columns, date-id-converter]}, row]
  (let [[ordernr, status, date-str] (mapv row mandatory-columns)
        ordernr (cond-> ordernr
                  date-id-converter
                  (str "-" (date-id-converter date-str)))]
    {:ordernr ordernr, :status (some-> status str/lower-case)}))


(defn prev-day
  ^ZonedDateTime [^ZonedDateTime date]
  (.minusDays date 1))


(defn update-status
  "Determine new status based on old and new status.
  The old status gets overwritten if it is not set of if the new status is equal to negative-result."
  [negative-result old-status, new-status]
  (if (or (str/blank? old-status) (= new-status negative-result))
    new-status
    old-status))


(defn import-into-db
  [{:keys [negative-result] :as import-config}, csv-rows]
  (let [now-timestamp (t/timestamp (t/now))
        ordernr-set (crud/read-patient-ordernr-set)
        patient-list (mapv (partial csv-row->patient import-config) csv-rows)]
    (reduce
      (fn [update-count, {:keys [ordernr, status] :as imported-patient}]
        (let [updated? (when (contains? ordernr-set ordernr)
                         (jdbc/with-db-transaction [t-conn (c/db-connection)]
                           (let [{old-status :status :as db-patient} (crud/read-patient-by-order-number t-conn, ordernr)
                                 new-status (update-status negative-result, old-status, status)]
                             (when-not (= old-status new-status)
                               (crud/update-patient t-conn, (assoc db-patient :status new-status :statusupdated now-timestamp))
                               true))))]
          (cond-> update-count updated? inc)))
      0
      patient-list)))


(defn row-map
  [column-names, row-values]
  (->> row-values
    (mapv #(some-> % str/trim))
    (zipmap column-names)))

(defn blank-row?
  [row]
  (or
    (zero? (count row))
    (every? str/blank? row)))

(defn import-csv-file
  [{:keys [mandatory-columns, column-separator] :as import-config}, csv-file]
  (report/info "CSV import", "Import from %s" csv-file)
  (let [rows (vec (remove blank-row? (csv/read-csv (io/reader csv-file) :separator column-separator)))
        header (first rows)
        column-names (mapv #(-> % str/trim str/lower-case keyword) header)
        content-rows (rest rows)
        missing-columns (not-empty (remove (set column-names) mandatory-columns))]
    (if missing-columns
      (report/error
        "CSV import"
        "The following colums are missing in the file %s: %s (found columns: %s)"
        csv-file
        (->> missing-columns (map name) sort (str/join ", "))
        (->> column-names (map name) sort (str/join ",")))
      (let [update-count (import-into-db import-config, (mapv (partial row-map column-names) content-rows))]
        (report/info "CSV import", "Import from %s finished. %s patients have been updated." csv-file, update-count)))))


(defn create-date-id-converter
  [date-format]
  (let [date-parser (t/date-parser date-format)
        date-formatter (t/date-formatter "yyyyMMdd")]
    (fn convert-date-to-id [date-str]
      (-> date-str date-parser date-formatter))))


(defn import-loop
  [watch-state]
  (loop []
    (when-not (.isInterrupted (Thread/currentThread))
      (let [{:keys [negative-result, date-format, column-separator]} (c/import-config)
            import-config {:mandatory-columns (c/import-csv-columns)
                           :column-separator column-separator
                           :negative-result negative-result
                           :date-id-converter (when (c/order-number-append-date?)
                                                (create-date-id-converter date-format))}
            recur? (try
                     (if-let [files-to-import (unchanged-files watch-state)]
                       (doseq [csv-file files-to-import]
                         (import-csv-file import-config, csv-file))
                       (Thread/sleep 100))
                     ; continue
                     true
                     (catch InterruptedException _
                       false)
                     (catch Throwable t
                       (report/error "CSV import"
                         "Exception in import loop: %s"
                         (report/cause-trace t))
                       ; continue
                       true))]
        (when recur?
          (recur))))))


(defn watch-event-handler
  [watch-state, context, {:keys [^File file] :as event}]
  (try
    (let [event-time (t/now)
          size (file-size file)
          filename (.toString file)]
      (log/infof "File change in import directory %s %s %d bytes." filename (:kind event) size)
      (dosync
        (alter watch-state update-in [filename]
          (fn [file-data]
            (assoc (or file-data {})
              :time event-time
              :size size
              :file file)))))
    (catch Throwable t
      (report/error "CSV import"
        "Exception in file system watch handler:\n%s"
        (report/cause-trace t))))
  context)


(defn csv-file?
  [context, event]
  (let [^File file (:file event)]
    (-> file .toString (.endsWith ".csv"))))


(defn new-csv-file?
  [context, event]
  (and
    (hawk/file? context, event)
    (not (hawk/deleted? context, event))
    (csv-file? context, event)))


(defn csv-files
  [import-path]
  (->> (io/file import-path)
    (.listFiles)
    (filter
      (fn [^File f]
        (and
          (.isFile f)
          (.endsWith (.toString f) ".csv"))))))


(defn initial-watch-state
  [import-path]
  (let [now (t/now)
        file-list (csv-files import-path)]
    (persistent!
      (reduce
        (fn [file-map, ^File csv-file]
          (assoc! file-map
            (.toString csv-file)
            {:time now
             :size (file-size csv-file)
             :file csv-file}))
        (transient {})
        file-list))))


(defn start-daemon
  []
  (try
    (let [import-path (c/import-path)]
      (if (str/blank? import-path)
        (report/error "CSV import" "CSV import impossible because no import path specified in config!")
        (do
          (try
            (.mkdirs (io/file import-path))
            (catch Throwable t
              (report/error "CSV import"
                "Could not create import directory \"%s\":\n%s"
                import-path
                (report/cause-trace t))))
          (let [watch-state (ref (initial-watch-state import-path))
                daemon (hawk/watch! [{:paths [import-path]
                                      :filter new-csv-file?
                                      :handler (partial watch-event-handler watch-state)}])
                loop-thread (doto (Thread. ^Runnable (partial import-loop watch-state))
                              .start)]
            (c/set-daemon (assoc daemon :loop-thread loop-thread))))))
    (catch Throwable t
      (report/error "CSV import"
        "Exception on CSV import daemon start:\n%s"
        (report/cause-trace t))))
  nil)


(defn stop-daemon
  []
  (when-let [{:keys [^Thread thread, ^WatchService watcher, ^Thread loop-thread]} (c/daemon)]
    (c/set-daemon nil)
    (.close watcher)
    (.stop thread)
    (.interrupt loop-thread)
    nil))

