; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.templates
  (:require
    [clojure.string :as str]
    [selmer.parser :as parser]
    [cemerick.friend :as friend]
    [ctest.config :as c]
    [ctest.db.crud :as crud]
    [ctest.version :as v]
    [ctest.reporting :as report]))



(defn- index-of-current
  "Counts index of current project-step of project"
  [steps]
  (when-let [done (take-while #(= 1 (:state %)) steps)]
    (count done)))


(defn add-auth-info
  "Adds authentification to project"
  [request]
  (let [auth (friend/current-authentication)]
    (assoc request
      :userLogin (:username auth),
      :isAdmin (contains? (:roles auth) ::c/admin),
      :isUser (contains? (:roles auth) ::c/user),
      :isConfigAdmin (contains? (:roles auth) ::c/configadmin),
      :isAuthenticated (not (nil? auth)))))

(defn add-server-root
  [m]
  (let [root (c/server-root)
        root (cond->> root (not (str/blank? root)) (str "/"))
        page-title (c/page-title)
        page-title-link (c/page-title-link)
        page-logo (c/page-logo)
        develop? (c/develop?)]
    (cond-> (assoc m :serverRoot root, :version (v/ctest-version))
      page-title (assoc :pageTitle page-title)
      page-title-link (assoc :pageTitleLink page-title-link)
      page-logo (assoc :pageLogo page-logo)
      develop? (assoc :develop develop?))))


;; Functions to render templates

(defn login [request]
  (parser/render-file "templates/login.html"
    (add-server-root {:request (add-auth-info request)})))


(defn user-list
  [request users]
  (parser/render-file "templates/userlist.html"
    (add-server-root {:request (add-auth-info request) :users users})))

(defn get-user
  [request user]
  (parser/render-file "templates/user.html"
    (add-server-root {:request (add-auth-info request) :user user})))


(defn create-patient
  [request]
  (let [{:keys [new-patient-hint, new-patient-note, input-format]} (c/order-numbers-config)]
    (parser/render-file "templates/createpatient.html"
      (add-server-root
        {:request (add-auth-info request)
         :newPatientHint new-patient-hint
         :newPatientNote new-patient-note
         :orderNumberInputFormat input-format}))))

(defn tracking-link
  [tracking-number]
  (str (c/tracking-server-domain) (c/server-location "/track/") tracking-number))

(defn show-patient-link
  [order-number]
  (str (c/tracking-server-domain) (c/server-location "/staff/patient/show/") order-number))


(defn extract-specified-order-number
  [order-number]
  (first (str/split order-number #"-")))

(defn patient-created-info
  [request, patient]
  (parser/render-file "templates/patientcreatedinfo.html"
    (add-server-root
      {:request (add-auth-info request)
       :patientDocumentLogo (or (c/patient-document-logo) (c/page-logo))
       :patient (assoc patient
                  :trackinglink (tracking-link (:trackingnr patient))
                  :ordernrwithoutdate (extract-specified-order-number (:ordernr patient))
                  :editlink (c/server-location (str "/staff/patient/edit/" (:ordernr patient)))
                  :editable (not (:statusupdated patient)))})))

(defn duplicate-patient
  [request, patient]
  (parser/render-file "templates/duplicatepatient.html"
    (add-server-root
      {:request (add-auth-info request)
       :patient (assoc patient :showlink (show-patient-link (:ordernr patient)))})))


(defn status-query
  ([request]
   (status-query request, nil))
  ([request, error]
   (parser/render-file "templates/statusquery.html"
     (add-server-root
       (cond-> {:request (add-auth-info request)}
         error
         (assoc :error error))))))


(defn test-status
  [request, {:keys [status] :as patient}]
  (parser/render-file "templates/teststatus.html"
    (add-server-root
      {:request (add-auth-info request)
       :patient (assoc patient :negative (= status (c/import-negative-result)))})))


(defn custom-error
  [request, {:keys [title, description] :as error}]
  (parser/render-file "templates/customerror.html"
    (add-server-root
      {:request (add-auth-info request)
       :error error})))


(defn unhandled-exception
  [request]
  {:status 500,
   :headers {"Content-Type" "text/html"}
   :body (custom-error request, {:title "Exception" :description "An exception occured."})})


(defn edit-patient
  [request, order-number]
  (try
    (if-let [{:keys [statusupdated] :as patient} (crud/read-patient-by-order-number order-number)]
      (if statusupdated
        (custom-error request
          {:title "Order Number cannot be changed!"
           :description (format "The order number \"%s\" cannot be changed anymore!" order-number)})
        (parser/render-file "templates/editpatient.html"
          (add-server-root
            {:request (add-auth-info request)
             :orderNumberInputFormat (c/order-number-input-format)
             :patient (assoc patient
                        :ordernrwithoutdate (extract-specified-order-number (:ordernr patient)))})))
      (custom-error request
        {:title "Order Number does not exist!"
         :description (format "The order number \"%s\" you specified does not exist!" order-number)}))
    (catch Throwable t
      (report/error "Edit Patient View"
        "Exception during rendering of edit patient view:\n%s"
        (report/cause-trace t))
      (unhandled-exception request))))


(defn find-patient
  ([request]
   (find-patient request, nil))
  ([request, error]
   (let [{:keys [find-patient-hint, find-patient-note]} (c/order-numbers-config)]
     (parser/render-file "templates/findpatient.html"
       (add-server-root
         (cond-> {:request (add-auth-info request)
                  :findPatientHint find-patient-hint
                  :findPatientNote find-patient-note}
           error
           (assoc :error error)))))))