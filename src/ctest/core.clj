; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.core
  (:require
    [clojure.string :as string]
    [clojure.java.io :as io]
    [clojure.tools.cli :as cli]
    [clojure.tools.logging :as log]
    [ring.middleware.http-response :refer [wrap-http-response]]
    [ring.middleware.proxy-headers :refer [wrap-forwarded-remote-addr]]
    [compojure.handler :as handler]
    [cemerick.friend :as friend]
    (cemerick.friend
      [workflows :as workflows]
      [credentials :as creds])
    [ctest.config :as c]
    [ctest.routes :as routes]
    [ctest.db.crud :as crud]
    [ctest.db.init :as init]
    [ctest.db.migrate :as migrate]
    [ctest.runtime :as runtime]
    [ctest.daemons.import :as import]
    [ctest.daemons.backup :as backup]
    [ctest.reporting :as report]
    [clojure.string :as str]
    [ctest.common :as common])
  (:gen-class))


(defn failed-login
  [{{:keys [username]} :params, remote-ip :remote-addr, :as request}]
  (log/errorf "Failed login attempt for user \"%s\" from \"%s\"", username, remote-ip)
  (workflows/interactive-login-redirect request))


(defn authenticate
  [{:keys [username] :as login-data}]
  (let [auth-result (creds/bcrypt-credential-fn crud/authentication-map, login-data)]
    (when auth-result
      (log/infof "Successful login of user \"%s\"." username))
    auth-result))


; Enable authentication
(defn app
  [{:keys [port, ssl?, ssl-port, server-root] :as server-config}]
  (binding [friend/*default-scheme-ports* {:http port, :https ssl-port}]
    (handler/site
      (routes/wrap-uncaught-exception-logging
        (runtime/wrap-shutdown (cond-> (routes/use-server-root server-root, (routes/shutdown-routes))
                                 ssl? (friend/requires-scheme :https)),
          (wrap-forwarded-remote-addr
            (wrap-http-response
              (friend/authenticate
                (cond-> (routes/use-server-root server-root, (routes/app-routes))
                  ssl? (friend/requires-scheme :https))
                {:allow-anon? true
                 :credential-fn authenticate
                 :default-landing-uri (c/server-location "/")
                 :login-uri (c/server-location "/login")
                 :login-failure-handler failed-login
                 :unauthorized-handler routes/unauthorized-handler
                 :workflows [(workflows/interactive-form)]}))))))))

(def init-options
  [["-a" "--admin NAME" "Name of the admin user" :default "admin"]
   ["-p" "--password SECRET" "Admins password" :default "ctest"]
   ["-d" "--data-base-name NAME" "Name of the database. ctest will not override a existing database file." :default "ctest.db"]
   ["-h" "--help"]])

(def run-options
  [["-c" "--config-file FILENAME" "Path to the config file" :default "ctest.conf"]
   ["-h" "--help"]])


(defn app-usage []
  (->> ["Usage: ctest action args"
        ""
        "Actions:"
        "  init             Initialize the ctest instance"
        "  run              Run ctest"
        "  export db file   Export given database to the specified file."
        "  import db file   Import data from given file into the specified data base."
        "  upgrade config   Upgrades the database specified in the configuration file to be usable with this version of CTest."
        ""
        "For informations about args use:"
        "  ctest init -h"
        "or"
        "  ctest run -h"]
    (string/join \newline)))

(defn init-usage [summary]
  (->> ["Initialise the ctest instance."
        ""
        summary]
    (string/join \newline)))

(defn run-usage [summary]
  (->> ["Start the ctest instance with a given config file."
        ""
        summary]
    (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
    (string/join \newline errors)))


(defn run
  "Run ctest"
  [& run-args]
  (let [{:keys [options errors summary]} (cli/parse-opts (first run-args) run-options)
        {:keys [config-file]} options]
    ;; Handle help and error conditions
    (cond
      (:help options) (common/exit 0 (run-usage summary))
      (not (.exists (clojure.java.io/as-file config-file))) (common/exit 1 "Config file missing")
      errors (common/exit 1 (error-msg errors)))
    ; no errors so far
    (when (c/read+setup-config config-file)
      ; Start server (query server-config atom, since default settings might be missing in the config read from the file)
      ; immediate backup
      (let [backup-dir (c/backup-path)]
        (when-not (str/blank? backup-dir)
          (backup/perform-backup backup-dir)))
      ; create tables if needed
      (init/create-tables-if-needed (c/db-connection))
      ; start server and daemons
      (let [server (runtime/start-server (app (c/server-config)))]
        (import/start-daemon)
        (backup/start-backup-daemon)
        (runtime/shutdown-on-sigterm!)
        server))))


(defn prepare-filesystem-if-needed
  [{:keys [storage] :as config}]
  (c/update-config config)
  (common/ensure-directory (c/qrcode-path))
  (common/ensure-directory (c/branding-path))
  (when-let [logo (c/branding-logo-path)]
    (when-not (common/file-exists? logo)
      (common/copy-resource "public/images/CTestLogo.png" logo))))


(defn init
  "Init ctest"
  [& init-args]
  (let [{:keys [options errors summary]} (cli/parse-opts (first init-args) init-options)]
    ;; Handle help and error conditions
    (cond
      (:help options) (common/exit 0 (init-usage summary))
      errors (common/exit 1 (error-msg errors)))
    ;; Create default conf
    (when-not (.exists (clojure.java.io/as-file "ctest.conf"))
      (c/write-config-file "ctest.conf", (dissoc options :password :admin :template-file)))
    ;; create folders
    (prepare-filesystem-if-needed (c/read-config "ctest.conf"))
    ;; create db
    (when (init/create-database-if-needed (:data-base-name options))
      ; database had to be created, add admin user
      (crud/put-user {:username (:admin options), :password (:password options) :role :role/configadmin})
      (report/info "Initialization", "CTest instance created."))))


(defn -main [& args]
  (case (first args)
    "init" (init (rest args))
    "run" (run (rest args))
    "export" (let [[db-filename, export-filename] (rest args)]
               (migrate/export-data-from-db-file db-filename, export-filename))
    "import" (let [[db-filename, import-filename] (rest args)]
               (migrate/import-data db-filename, import-filename))
    "upgrade" (let [config-file (second args)]
                (migrate/upgrade-if-needed config-file))
    (common/exit 1 (app-usage))))
