; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.db.init
  (:require
    [clojure.java.jdbc :as jdbc]
    [ctest.config :as c]))


(defn create-user-table
  [db-conn, table-set]
  (when-not (contains? table-set :user)
    (jdbc/db-do-commands
      db-conn
      (jdbc/create-table-ddl
        :user
        [[:username "TEXT PRIMARY KEY"]
         [:password "TEXT"]
         [:fullname "TEXT"]
         [:role "TEXT"]]))))

(defn create-patient-table
  [db-conn, table-set]
  (when-not (contains? table-set :patient)
    (jdbc/db-do-commands
      db-conn
      (jdbc/create-table-ddl
        :patient
        [[:ordernr "TEXT PRIMARY KEY"]
         [:created "INTEGER"]
         [:trackingnr "TEXT"]
         [:status "TEXT"]
         [:statusupdated "INTEGER"]
         [:qrcode "TEXT"]]))))

(defn create-reports-table
  [db-conn, table-set]
  (when-not (contains? table-set :reports)
    (jdbc/db-do-commands
      db-conn
      (jdbc/create-table-ddl
        :reports
        [[:id "INTEGER PRIMARY KEY AUTOINCREMENT"]
         [:timestamp "INTEGER"]
         [:type "TEXT"]
         [:context "TEXT"]
         [:message "TEXT"]]))))


(defn create-views-table
  [db-conn, table-set]
  (when-not (contains? table-set :views)
    (jdbc/db-do-commands
      db-conn
      (jdbc/create-table-ddl
        :views
        [[:date "TEXT PRIMARY KEY"]
         [:count "INTEGER"]]))))


(defn existing-tables
  [db-conn]
  (jdbc/with-db-metadata [metadata db-conn]
    (with-open [tables-resultset (.getTables metadata nil nil "%" nil)]
      (->> tables-resultset jdbc/result-set-seq (mapv (comp keyword :table_name)) (into #{})))))


(defn create-tables-if-needed
  [db-conn]
  (let [table-set (existing-tables db-conn)]
    (create-user-table db-conn, table-set)
    (create-patient-table db-conn, table-set)
    (create-reports-table db-conn, table-set)
    (create-views-table db-conn, table-set)))


(defn create-database-if-needed
  "Creates the database file if it does not exists. Returns true if the database had to be created and fals otherwise."
  [db-filename]
  (if (.exists (clojure.java.io/as-file db-filename))
    false
    (let [db-conn (c/pool (c/database-config db-filename))]
      (create-tables-if-needed db-conn)
      true)))