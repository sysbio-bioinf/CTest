; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.db.migrate
  (:require
    [clojure.java.io :as io]
    [clojure.java.jdbc :as jdbc]
    [ctest.config :as c]
    [ctest.db.init :as init]
    [ctest.db.crud :as crud]
    [taoensso.nippy :as nippy]
    [clojure.string :as str]
    [clojure.stacktrace :as st]
    [ctest.common :as common]
    [clojure.edn :as edn]))


(defn table-set
  [db-conn]
  (set
    (jdbc/query db-conn
      ["SELECT name FROM sqlite_master WHERE type='table'"]
      {:row-fn (comp keyword :name)})))


(defn table-columns
  [db-conn, table]
  (jdbc/query db-conn [(format "PRAGMA table_info(%s)" (name table))]
    {:row-fn (comp keyword :name)
     :result-set-fn vec}))


(defn db-data
  [db-conn]
  (jdbc/with-db-transaction [t-conn db-conn]
    (let [tables (table-set t-conn)]
      (persistent!
        (reduce
          (fn [table-data-map, table]
            (cond-> table-data-map
              (contains? tables table)
              (assoc! table (vec (jdbc/query t-conn [(format "SELECT * FROM %s" (name table))])))))
          (transient {})
          [:patient :user :reports :views])))))


(defn db-connection
  [db-filename]
  (c/pool (c/database-config db-filename)))


(defn export-data-from-db
  [db-conn, export-filename]
  (let [data (db-data db-conn)]
    (nippy/freeze-to-file export-filename, data)))


(defn export-data-from-db-file
  [db-filename, export-filename]
  (export-data-from-db (db-connection db-filename), export-filename))


(defn insert-table-data
  [db-conn, table-data-map]
  (let [existing-tables (table-set db-conn)]
    (reduce-kv
      (fn [_, table, rows]
        (when (contains? existing-tables table)
          (let [table-cols (table-columns db-conn, table)]
            (jdbc/insert-multi! db-conn, table
              (mapv #(select-keys % table-cols) rows))
            ; return nothing
            nil)))
      nil
      table-data-map)))


(defn import-data
  [db-filename, import-filename]
  (let [table-data-map (nippy/thaw-from-file import-filename)]
    (init/create-database-if-needed db-filename)
    (let [db-conn (db-connection db-filename)]
      (insert-table-data db-conn, table-data-map)
      nil)))



(defn new-user-role
  [old-role]
  (when old-role
    [old-role (keyword "role" (name old-role))]))


(defn user-role-update
  [user-roles]
  (let [renaming-map (into {}
                       (keep new-user-role)
                       user-roles)]
    (jdbc/with-db-transaction [t-conn (c/db-connection)]
      (let [user-list (crud/read-users t-conn)]
        (doseq [new-user (mapv #(update % :role (comp renaming-map edn/read-string)) user-list)]
          (println "  UPDATING" (:username new-user))
          (crud/update-user-role t-conn, new-user))))))


(defn user-role-update-check
  []
  (when-let [user-roles (not-empty (crud/user-role-set))]
    (when (some #(-> % namespace (not= "role")) user-roles)
      ["user roles" (partial user-role-update user-roles)])))


(defn status-encoding-update
  [encodings]
  (let [negative-import-encoding (c/import-negative-result)
        {negative-encodings true, in-progress-encodings false} (group-by #(= negative-import-encoding %) encodings)
        ne-count (count negative-encodings)
        ipe-count (count in-progress-encodings)]
    (if (and (<= ne-count 1) (<= ipe-count 1))
      (let [renaming-map (cond-> {}
                           (== ne-count 1) (assoc (first negative-encodings) c/db-encoding-negative)
                           (== ipe-count 1) (assoc (first in-progress-encodings) c/db-encoding-in-progress))]
        (println "  UPDATING" (->> renaming-map (map #(apply format "%s => %s" %)) (str/join ", ")))
        (crud/update-status (c/db-connection), renaming-map))
      (throw (Exception. (format "At most 1 encoding of each type expected! Found negativ = %s and in-progress = %s" negative-encodings, in-progress-encodings))))))


(defn status-encoding-update-check
  []
  (when-let [encodings (-> (crud/status-encoding-set)
                         (disj nil)
                         not-empty)]
    (when (not-every? c/valid-status-encoding? encodings)
      ["status encodings" (partial status-encoding-update encodings)])))


(defn qrcode-path-update
  []
  (crud/remove-qrcode-prefix "qrcodes/"))


(defn qrcode-path-update-check
  []
  (when (crud/patients-with-qrcode-prefix? "qrcodes/")
    ["qrcode path" qrcode-path-update]))

(defn required-updates
  []
  (->> [user-role-update-check, status-encoding-update-check, qrcode-path-update-check]
    (keep (fn [f] (f)))
    vec
    not-empty))


(defn upgrade-if-needed
  [config-file]
  (when (c/read+setup-config config-file)
    (when-let [required-updates-vec (required-updates)]
      (doseq [[desc, update-fn] required-updates-vec]
        (println "UPGRADE" desc "STARTED.")
        (try
          (update-fn)
          (catch Throwable t
            (println "UPGRADE" desc "FAILED:")
            (st/print-cause-trace t)
            (common/exit 1 (str "UPGRADE " desc " FAILED!"))))
        (println "UPGRADE" desc "FINISHED.")))))