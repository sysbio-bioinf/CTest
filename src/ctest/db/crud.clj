; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.db.crud
  (:require
    [clojure.string :as str]
    [clojure.edn :as edn]
    [clojure.java.jdbc :as jdbc]
    [clojure.tools.logging :as log]
    [cemerick.friend.credentials :as creds]
    [ctest.config :as c]
    [ctest.common :as common]))



(defn db-insert!
  [db-conn & args]
  (jdbc/with-db-transaction [conn db-conn] (apply jdbc/insert! conn args)))


(defn db-update!
  [db-conn & args]
  (jdbc/with-db-transaction [conn db-conn] (apply jdbc/update! conn args)))


(defn db-delete!
  [db-conn & args]
  (jdbc/with-db-transaction [conn db-conn] (apply jdbc/delete! conn args)))


(def rowid_keyword (keyword "last_insert_rowid()"))
(def max-id-keyword (keyword "max(id)"))


(defn capitalize-name
  [username]
  (when username
    (->> (str/split username #" ")
      (map #(->> (str/split % #"-") (map str/capitalize) (str/join "-")))
      (str/join " "))))


(defn lower-case
  [s]
  (when s
    (str/lower-case s)))


(defn has-user?
  "Exists given user in database?"
  [user-name]
  (boolean
    (seq
      (jdbc/query
        (c/db-connection)
        ["select username from user where username = ?" user-name]))))


(defn- encrypt-password
  [user-map]
  (if-let [password (user-map :password)]
    (assoc user-map :password (creds/hash-bcrypt password))
    user-map))


(defn put-user
  "Inserts or updates a user.
  user-map needs to contain :username, :password and :role.
  :username is also the primary key. Password is safed as bcypt hash."
  [user-map]
  (when-let [user-name (:username user-map)]
    (let [user (encrypt-password user-map)]
      (if (has-user? user-name)
        (db-update!
          (c/db-connection) :user user ["username = ?" user-name])
        (db-insert!
          (c/db-connection) :user user)))))


(defn- all-roles
  [role]
  (loop [roles #{role}, check-coll [role]]
    (if (zero? (count check-coll))
      roles
      (if-let [parent-roles (some-> check-coll peek parents)]
        (recur (into roles parent-roles), (-> check-coll pop (into parent-roles)))
        (recur roles, (pop check-coll))))))


(defn- add-role-keywords
  [user-coll]
  (let [user-map (first user-coll)]
    (if-let [role (some-> user-map :role edn/read-string)]
      (assoc user-map :role role, :roles (all-roles role))
      user-map)))


(defn- read-full-user
  "Returns a full user-map and applys a funtrion to it"
  [user-name fn]
  (jdbc/query
    (c/db-connection) ["SELECT * FROM user WHERE username = ?" user-name] :result-set-fn fn))


(defn authentication-map
  "Returns the Authentification map for a given user"
  [user-name]
  (read-full-user user-name add-role-keywords))


(defn read-user
  "Returns a user-map"
  [user-name]
  (read-full-user user-name #(dissoc (first %) :password)))


(defn read-users
  "Returns a collection of user-maps"
  []
  (jdbc/query (c/db-connection) ["SELECT username, fullname, role FROM user"]))


(defn delete-user
  [user-name]
  (db-delete!
    (c/db-connection)
    :user ["username = ?" user-name]))


(defn- update-or-insert!
  "Updates columns or inserts a new row in the specified table.
  See http://clojure-doc.org/articles/ecosystem/java_jdbc/using_sql.html#updating-or-inserting-rows-conditionally"
  [table row where-clause]
  (jdbc/with-db-transaction
    [t-con (c/db-connection)]
    (let [result (jdbc/update! t-con table row where-clause)]
      (if (zero? (first result))
        (jdbc/insert! t-con table row)
        result))))


(defn read-tracking-numbers
  ([]
   (read-tracking-numbers (c/db-connection)))
  ([db-conn]
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/query t-conn
       ["SELECT trackingnr FROM patient"]
       :result-set-fn set
       :row-fn :trackingnr))))


(defn read-patient-ordernr-set
  ([]
   (read-patient-ordernr-set (c/db-connection)))
  ([db-conn]
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/query t-conn
       "SELECT ordernr FROM patient"
       :row-fn :ordernr
       :result-set-fn set))))

(defn read-patient-by-order-number
  "Lookup the patient data with the specified order number."
  ([order-number]
   (read-patient-by-order-number (c/db-connection), order-number))
  ([db-conn, order-number]
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/query t-conn
       ["SELECT * FROM patient WHERE patient.ordernr = ?" order-number]
       :result-set-fn first))))


(defn delete-patient
  ([db-conn, order-number]
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/delete! t-conn, :patient ["ordernr = ?" order-number]))))


(defn read-patient-by-tracking-number
  "Lookup the patient data with the specified tracking number."
  ([tracking-number]
   (read-patient-by-tracking-number (c/db-connection), tracking-number))
  ([db-conn, tracking-number]
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/query t-conn
       ["SELECT * FROM patient WHERE patient.trackingnr = ?" tracking-number]
       :result-set-fn first))))


(defn create-patient
  [db-conn, {:keys [ordernr, trackingnr, created] :as patient}]
  (jdbc/with-db-transaction [t-conn db-conn]
    (jdbc/insert! t-conn, :patient, patient)))


(defn update-patient
  [db-conn, {:keys [ordernr] :as patient}]
  (jdbc/with-db-transaction [t-conn db-conn]
    (jdbc/update! t-conn, :patient, patient ["ordernr = ?" ordernr])))


(defn insert-report
  ([report]
   (insert-report (c/db-connection), report))
  ([db-conn, {:keys [timestamp, type, context, message] :as report}]
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/insert! t-conn, :reports, (select-keys report [:timestamp, :type, :context, :message])))))


(defn report-list
  ([]
   (report-list (c/db-connection)))
  ([db-conn]
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/query t-conn "SELECT * FROM reports"))))


(defn delete-reports
  ([report-ids]
   (delete-reports (c/db-connection), report-ids))
  ([db-conn, report-ids]
   (log/infof "Deleted reports with ids: %s" (str/join ", " (sort report-ids)))
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/execute! t-conn, (into ["DELETE FROM reports WHERE id = ?"] (mapv vector report-ids)), :multi? true))))


(defn count-view
  ([date-str]
   (count-view (c/db-connection), date-str))
  ([db-conn, date-str]
   (jdbc/with-db-transaction [t-conn db-conn]
     (if-let [{:keys [date, count]} (first (jdbc/query t-conn, ["SELECT * FROM views WHERE date = ?" date-str]))]
       (jdbc/update! t-conn, :views, {:count (inc count)}, ["date = ?" date-str])
       (jdbc/insert! t-conn, :views, {:date date-str, :count 1})))))


(defn views-per-day
  ([]
   (views-per-day (c/db-connection)))
  ([db-conn]
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/query t-conn "SELECT * FROM views"))))


(defn test-dates
  ([]
   (test-dates (c/db-connection)))
  ([db-conn]
   (jdbc/with-db-transaction [t-conn db-conn]
     (jdbc/query t-conn "SELECT created FROM patient"
       :row-fn :created))))