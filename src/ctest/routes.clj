; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.routes
  (:require
    [clojure.string :as str]
    [ring.util.response :as response]
    [ring.middleware.json :as json]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [compojure.core :as core]
    [compojure.route :as route]
    [compojure.response :as cr]
    [cemerick.friend :as friend]
    [selmer.parser :as parser]
    [clojure.stacktrace :refer [print-cause-trace]]
    [ctest.templates :as templates]
    [ctest.db.crud :as db]
    [ctest.actions.user :as user-action]
    [ctest.actions.patient :as patient-action]
    [ctest.actions.reports :as reports-action]
    [ctest.config :as c]
    [ctest.db.crud :as crud]
    [ctest.reporting :as report])
  (:gen-class))


(defn shutdown-page
  []
  {:status 503
   :headers {"Content-Type" "text/html"}
   :body (parser/render-file "templates/shutdown.html" (templates/add-server-root {}))})

; Define routes

(core/defroutes user-routes
  (core/GET "/" request (templates/user-list request (db/read-users)))
  (core/PUT ["/:name"] [name :as request] (user-action/update-user (:body request)))
  (core/POST "/" request (user-action/create-user (:body request)))
  (core/DELETE ["/:name"] [name] (user-action/delete-user name))
  (core/GET ["/:name"] [name :as request] (templates/get-user request (db/read-user name))))


(core/defroutes patient-routes
  (core/GET "/" request
    (templates/create-patient request))
  (core/POST "/create" [orderNr, :as request]
    (let [orderNr (str/trim orderNr)]
      (if (patient-action/valid-ordernr-input? orderNr)
        ; valid order number, proceed
        (let [{:keys [type, page, patient]} (patient-action/create-patient request, orderNr)]
          (case type
            :page page
            :patient (response/redirect (c/server-location (str "/staff/patient/show/" (:ordernr patient))))
            (response/redirect (c/server-location "/staff/patient"))))
        ; invalid order number
        (response/redirect (c/server-location "/staff/patient")))))
  (core/GET "/edit/:orderNr" [orderNr, :as request]
    (let [orderNr (str/trim orderNr)]
      (if (patient-action/valid-ordernr? orderNr)
        (templates/edit-patient request, orderNr)
        (templates/custom-error request
          {:title "Invalid Order Number"
           :description (format "The order number \"%s\" you specified has an invalid format!" orderNr)}))))
  (core/POST "/edit/:orderNr" [orderNr, correctedOrderNr, :as request]
    (let [orderNr (str/trim orderNr)
          correctedOrderNr (str/trim correctedOrderNr)
          orderNr-valid? (patient-action/valid-ordernr? orderNr)
          correctedOrderNr-valid? (patient-action/valid-ordernr-input? correctedOrderNr)]
      (if (and orderNr-valid? correctedOrderNr-valid?)
        (patient-action/rename-order-number request, orderNr, correctedOrderNr)
        (let [invalid-number-name (cond->> "Order Number" orderNr-valid? (str "Corrected "))]
          (templates/custom-error request
            {:title (str "Invalid " invalid-number-name)
             :description (format "The %s \"%s\" you specified has an invalid format!" invalid-number-name orderNr)})))))
  (core/GET "/find" request
    (templates/find-patient request))
  (core/POST "/find" [orderNr :as request]
    (patient-action/find-patient request, orderNr))
  (core/GET "/show/:orderNr" [orderNr, :as request]
    (let [orderNr (str/trim orderNr)]
      (if-let [patient (crud/read-patient-by-order-number orderNr)]
        (templates/patient-created-info request, patient)
        (templates/custom-error request
          {:title "Patient does not exist"
           :description (format "There is no patient with order number \"%s\" in the database!" orderNr)})))))


(core/defroutes api-routes
  (core/context "/patient" []
    (friend/wrap-authorize patient-routes, #{::c/user}))
  (core/context "/usr" []
    (friend/wrap-authorize user-routes, #{::c/admin})))


(core/defroutes reports-routes
  (core/context "/reports" []
    (friend/wrap-authorize
      (core/routes
        (core/GET "/list" [type, context :as request]
          (reports-action/report-list type, context))
        (core/GET "/views" request
          (reports-action/views-per-day))
        (core/GET "/test-dates" request
          (reports-action/test-dates))
        (core/POST "/delete" request
          (reports-action/delete-reports (:body request)))
        (core/GET "/system" request
          (reports-action/system-info)))
      #{::c/reporter})))


(defn wrap-cache-control
  [handler]
  (fn [request]
    (let [response (handler request)]
      (some-> response
        (update-in [:headers]
          #(merge {"Cache-Control" "max-age=60, must-revalidate"} %))))))


(def not-found
  (core/rfn request
    (-> (cr/render
          (templates/custom-error
            request,
            {:title "Page not found!"
             :description "The page you are trying to access does not exist!"})
          request)
      (response/status 404))))

; Compose routes
(defn app-routes
  []
  (core/routes
    (core/GET ["/track/:urlTrackingNr"] [urlTrackingNr, trackingNr, app :as request]
      (let [tnr (cond
                  (some-> urlTrackingNr (patient-action/valid-tracking-number?)) urlTrackingNr
                  (some-> trackingNr (patient-action/valid-tracking-number?)) trackingNr
                  ; none of the two is valid, choose one that is specified non-empty
                  ; (validity is checked later again for error message display)
                  :else (or (not-empty urlTrackingNr) (not-empty trackingNr)))]
        (if (some? app)
          (patient-action/app-test-status tnr)
          (patient-action/lookup-test-status request, tnr))))
    (core/GET ["/track:sep" :sep #"/?"] [sep, trackingNr :as request]
      (patient-action/lookup-test-status request, trackingNr))
    ; serve resources from "public/"
    (wrap-cache-control (wrap-not-modified (wrap-content-type (route/resources "/"))))
    ; login
    (core/GET "/login" request (templates/login request))
    (friend/logout (core/ANY "/logout" request (response/redirect (c/server-location "/"))))
    ; branding resources
    (route/files "/branding" {:root (c/branding-path)})
    ; staff routes, require login
    (core/context "/staff" []
      (friend/wrap-authorize
        (core/routes
          (core/GET "/" []
            (response/redirect (c/server-location "/staff/patient")))

          (route/files "/qrcodes" {:root (c/qrcode-path)})

          (json/wrap-json-response
            (json/wrap-json-body api-routes {:keywords? true})))
        #{::c/user}))
    ; reports
    (json/wrap-json-response
      (json/wrap-json-body reports-routes {:keywords? true}))
    ; redirect top level page depending on user role
    (core/GET "/" request
      (let [{:keys [isAuthenticated, isUser]} (templates/add-auth-info request)]
        (if isAuthenticated
          (if isUser
            (response/redirect (c/server-location "/staff/patient"))
            (response/redirect (c/server-location "/reports/system")))
          (response/redirect (c/server-location "/track")))))
    ; nothing matches
    not-found))


(defn unauthorized-handler
  [req]
  {:status 401
   :headers {"Content-Type" "text/html"}
   :body (templates/custom-error
           req,
           {:title "Unauthourized access!"
            :description "You are not authorized to access this page."})})


(defn shutdown-routes
  []
  (core/routes
    (route/resources "/")
    (route/not-found (shutdown-page))))


(defn use-server-root
  "If a server root directory is given, then use this as prefix for all routes."
  [server-root, routes]
  (if (str/blank? server-root)
    routes
    (core/routes
      (core/context (c/server-location "", true) [] routes)
      not-found)))



(defn wrap-uncaught-exception-logging
  "The given handler will be wrapped in a try catch that logs all exceptions."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (let [{:keys [uri, request-method]} request]
          (report/error "Uncaught"
            "Caught exception for request \"%s %s\":\n%s"
            (some-> request-method name str/upper-case)
            uri
            (report/cause-trace t))
          (templates/unhandled-exception request))))))