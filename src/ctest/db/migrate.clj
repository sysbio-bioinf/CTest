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
    [taoensso.nippy :as nippy]))


(defn table-set
  [db-conn]
  (set
    (jdbc/query db-conn
      ["SELECT name FROM sqlite_master WHERE type='table'"] :row-fn (comp keyword :name))))


(defn table-columns
  [db-conn, table]
  (jdbc/query db-conn [(format "PRAGMA table_info(%s)" (name table))]
    :row-fn (comp keyword :name),
    :result-set-fn vec))


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
            (reduce
              (fn [_, row-data]
                (jdbc/insert! db-conn, table, (select-keys row-data table-cols))
                nil)
              nil
              rows))))
      nil
      table-data-map)))


(defn import-data
  [db-filename, import-filename]
  (let [table-data-map (nippy/thaw-from-file import-filename)]
    (init/create-database-if-needed db-filename)
    (let [db-conn (db-connection db-filename)]
      (insert-table-data db-conn, table-data-map)
      nil)))