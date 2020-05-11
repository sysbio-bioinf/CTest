; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.actions.patient
  (:require [ctest.actions.tools :as t]
            [ctest.db.crud :as crud]
            [ctest.common :as common]
            [clojure.java.jdbc :as jdbc]
            [ctest.config :as c]
            [clojure.java.io :as io]
            [ctest.templates :as templates]
            [clojure.string :as str]
            [ring.util.response :as response]
            [clojure.tools.logging :as log]
            [ctest.reporting :as report])
  (:import (java.io File)
           (java.time ZonedDateTime)
           (java.nio.file Files StandardCopyOption)))



(defn create-tracking-number
  [conn]
  (let [trackingnr-set (crud/read-tracking-numbers conn)]
    (loop []
      (let [tracking-nr (common/random-uuid 6, 2)]
        (if (contains? trackingnr-set trackingnr-set)
          (recur)
          tracking-nr)))))


(defn valid-tracking-number?
  [tracking-number]
  (some? (re-matches #"[0-9A-F]{4,4}-[0-9A-F]{4,4}-[0-9A-F]{4,4}" tracking-number)))


(defn qrcode-file
  ^File [ordernr]
  (io/file (format "%s.png" ordernr)))


(defn valid-ordernr-input?
  [ordernr-without-date]
  ((c/valid-order-number-input-fn) ordernr-without-date))


(defn valid-ordernr?
  [ordernr]
  ((c/valid-order-number-fn) ordernr))


(defn qrcode-path
  ^File [ordernr]
  (io/file (c/qrcode-path) (qrcode-file ordernr)))


(defn create-patient
  "Creates a new patient entry with trackingnr and tracking QR code."
  [request, ordernr-without-date]
  (try
    (let [current-datetime (t/now)
          ordernr (if (c/order-number-append-date?)
                    (t/ordernr-with-date ordernr-without-date, current-datetime)
                    ordernr-without-date)]
      (if-let [patient (crud/read-patient-by-order-number ordernr)]
        ; check whether all patient related data is present
        {:type :page
         :page (templates/duplicate-patient request, (assoc patient :ordernrwithoutdate ordernr-without-date))}
        ; create new patient with tracking number (within same transaction to guarantee uniqueness)
        (let [{:keys [trackingnr] :as patient} (jdbc/with-db-transaction [t-conn (c/db-connection)]
                                                 (let [trackingnr (create-tracking-number t-conn)
                                                       patient-data {:ordernr ordernr
                                                                     :trackingnr trackingnr
                                                                     :created (t/timestamp current-datetime)}]
                                                   (crud/create-patient t-conn, patient-data)
                                                   patient-data))
              target-file (qrcode-file ordernr)
              fs-path (io/file (c/qrcode-path) target-file)
              patient (assoc patient :qrcode (str target-file))]
          ; ensure that the directory exists
          (common/ensure-parent-directory fs-path)
          (common/generate-qr-code-file fs-path, (templates/tracking-link trackingnr), 350)
          (crud/update-patient (c/db-connection), patient)
          {:type :patient
           :patient patient})))
    (catch Throwable t
      (report/error "Create Patient"
        "Exception during patient creation:\n%s"
        (report/cause-trace t))
      (templates/unhandled-exception request))))


(defn lookup-patient
  [tracking-number]
  (if (valid-tracking-number? tracking-number)
    (try
      (if-let [patient (crud/read-patient-by-tracking-number tracking-number)]
        {:patient patient}
        {:error-type "NOTFOUND"})
      (catch Throwable t
        {:error-type "EXCEPTION"}))
    {:error-type "INVALIDFORMAT"}))


(defn count-view
  []
  (try
    (let [date-str (t/date-dashed-iso-format (t/now))]
      (crud/count-view date-str))
    (catch Throwable t
      (report/error "Lookup Test Status"
        "Exception on incrementing view count:\n%s"
        (report/cause-trace t)))))


(defn lookup-test-status
  [request, tracking-number]
  (try
    (if (str/blank? tracking-number)
      ; no tracking number specified, show query view
      (templates/status-query request)
      ; lookup patient
      (let [{:keys [error-type, patient]} (lookup-patient tracking-number)]
        (if error-type
          ; on error communicate error type and tracking numbe
          (do
            ; log access to non-existent tracking links.
            (when (= error-type "NOTFOUND")
              (let [{remote-ip :remote-addr, :as request} request]
                (log/errorf "Non-existent tracking link access from \"%s\"" remote-ip)))
            ; serve status query with error information
            (templates/status-query request, {:type error-type, :trackingnr tracking-number}))
          ; on success render status
          (do
            (count-view)
            (templates/test-status request, patient)))))
    (catch Throwable t
      (report/error "Lookup Test Status"
        "Exception during test status lookup:\n%s"
        (report/cause-trace t))
      (templates/unhandled-exception request))))


(defn app-test-status
  [tracking-number]
  (try
    (if (str/blank? tracking-number)
      {:status 400
       :body "No tracking number specified"}
      (let [{:keys [error-type, patient]} (lookup-patient tracking-number)]
        ; error handling
        (if error-type
          {:status (if (= error-type "EXCEPTION") 500 400)
           :body (case error-type
                   "EXCEPTION" "An exception occured during database access!"
                   "NOTFOUND"  (format "Tracking number \"%s\" does not exist!" tracking-number)
                   "INVALIDFORMAT" (format "The format of the specified tracking number \"%s\" is invalid!"
                                     tracking-number))}
          ; success
          {:status 200
           :body (if (c/negative-status? (:status patient))
                   c/db-encoding-negative
                   c/db-encoding-in-progress)})))
    (catch Throwable t
      (report/error "App Test Status Query"
        "Exception during app test status query:\n%s"
        (report/cause-trace t))
      {:status 500
       :body "An exception occured!"})))


(defn rename-qrcode-file
  [old-order-number, new-order-number]
  (try
    (let [old-file (qrcode-path old-order-number)
          new-file (qrcode-path new-order-number)]
      (cond
        (not (common/file-exists? old-file))
        (report/error "Rename QR code file",
          "The source file \"%s\" does not exist!"
          (str old-file))

        (common/file-exists? new-file)
        (report/error "Rename QR code file",
          "The target file \"%s\" does already exist!"
          (str new-file))

        :else
        (common/rename-file old-file, new-file, :replace? true)))
    (catch Throwable t
      (report/error "Rename QR code file"
        "Excpetion during renaming of the QR code file from old order number \"%s\" to \"%s\":\n%s"
        old-order-number
        new-order-number
        (report/cause-trace t)))))


(defn rename-order-number
  [request, order-number, correctedOrderNr-without-date]
  (try
    (if-let [{:keys [created, statusupdated] :as patient} (crud/read-patient-by-order-number order-number)]
      (if statusupdated
        (templates/custom-error request
          {:title "Order Number cannot be changed!"
           :description (format "The order number \"%s\" cannot be changed anymore!" order-number)})
        (let [corrected-order-number (if (c/order-number-append-date?)
                                       (t/ordernr-with-date correctedOrderNr-without-date, (t/zoned-date-time created))
                                       correctedOrderNr-without-date)]
          (if (crud/read-patient-by-order-number corrected-order-number)
            ; attempt to rename patient to existing order number
            (templates/custom-error request
              {:title "The specified Order Number exists already!"
               :description (format "The order number \"%s\" cannot be changed to an already existing order number!" order-number)})
            ; perform renaming
            (let [new-qrcode-filename (qrcode-file corrected-order-number)]
              ; rename qrcode image file
              (rename-qrcode-file order-number, corrected-order-number)
              ; do not change anything if order numbers are the same
              (when-not (= order-number corrected-order-number)
                (jdbc/with-db-transaction [t-conn (c/db-connection)]
                  (crud/create-patient t-conn, (assoc patient :ordernr corrected-order-number :qrcode new-qrcode-filename))
                  (crud/delete-patient t-conn, order-number)))
              ; redirect to show page
              (response/redirect (c/server-location (str "/staff/patient/show/" corrected-order-number)))))))
      (templates/custom-error request
        {:title "Order Number does not exist!"
         :description (format "The order number \"%s\" you specified does not exist!" order-number)}))
    (catch Throwable t
      (report/error "Rename Order Number"
        "Exception during order number renaming:\n%s"
        (report/cause-trace t))
      (templates/unhandled-exception request))))


(defn find-patient
  [request, order-number]
  (try
    (let [order-number (str/trim order-number)]
      (if (valid-ordernr? order-number)
        (if-let [patient (crud/read-patient-by-order-number order-number)]
          (response/redirect (c/server-location (str "/staff/patient/show/" order-number)))
          (templates/find-patient request
            {:type "NOTFOUND"
             :ordernr order-number}))
        (templates/find-patient request
          {:type "INVALIDFORMAT"
           :ordernr order-number})))
    (catch Throwable t
      (report/error "Find Patient"
        "Exception during find patient:\n%s"
        (report/cause-trace t))
      (templates/unhandled-exception request))))