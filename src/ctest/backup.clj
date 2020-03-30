; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.backup
  (:require [ctest.actions.tools :as t]
            [ctest.config :as c]
            [ctest.reporting :as report]
            [clojure.java.io :as io]
            [ctest.db.migrate :as migrate]
            [ctest.common :as common])
  (:import (java.util.concurrent ScheduledExecutorService Executors TimeUnit)
           (java.time.temporal ChronoUnit)
           (java.time ZonedDateTime)))



(defn perform-backup
  [backup-dir]
  (try
    (let [now (t/now)
          suffix (t/date-time-filename-suffix now)
          backup-file (io/file backup-dir, (str "ctest-db-backup-" suffix ".data"))]
      (common/ensure-parent-directory backup-file)
      (report/info "Backup", "Starting backup to file %s." (str backup-file))
      (try
        ; export backup
        (migrate/export-data-from-db (c/db-connection), backup-file)
        ; success
        (report/info "Backup", "Backup to file %s completed successfully." (str backup-file))
        (catch Throwable t
          (report/error "Backup",
            "Error occured during backup to %s:\n%s"
            (str backup-file)
            (report/cause-trace t)))))
    (catch Throwable t
      (report/error "Backup"
        "Exception in backup procedure:\n%s"
        (report/cause-trace t)))))


(def ^:const minute-per-hour 30)
(def ^:const period 60)


(defn start-backup-daemon
  []
  (try
    (if-let [backup-dir (c/backup-path)]
      (let [now (t/now)
            current-hour (.truncatedTo now ChronoUnit/HOURS)
            next-half (.plusMinutes current-hour minute-per-hour)
            delay (.between ChronoUnit/MINUTES now next-half)
            delay (cond-> delay (neg? delay) (+ period))
            service (doto (Executors/newScheduledThreadPool 1)
                      (.scheduleAtFixedRate (partial perform-backup backup-dir), delay, period, TimeUnit/MINUTES))]
        (c/set-backup-service service)
        nil)
      (report/error "Backup", "You forgot to specify a :backup-path in the config file!"))
    (catch Throwable t
      (report/error "Backup"
        "Exception in backup startup:\n%s"
        (report/cause-trace t)))))


(defn stop-backup-daemon
  []
  (when-let [^ScheduledExecutorService service (c/backup-service)]
    (c/set-backup-service nil)
    (.shutdownNow service)
    nil))