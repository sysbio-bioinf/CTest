; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.runtime
  (:require
    [clojure.string :as str]
    [clojure.stacktrace :refer [print-cause-trace]]
    [ring.adapter.jetty :as jetty]
    [clojure.tools.logging :as log]
    [ctest.config :as c]
    [ctest.daemons.import :as import]
    [ctest.daemons.backup :as backup]
    [ctest.reporting :as report])
  (:import
    (org.eclipse.jetty.server Server AbstractConnector)
    org.apache.log4j.PropertyConfigurator))


; variables instead of atom since the values are only changed at startup and shutdown
(def ^:private running-server nil)
(def ^:private keep-running true)


(defn keep-running?
  []
  keep-running)


(defn- server-url
  [host, port, ssl? server-root]
  (let [proto (if ssl? "https" "http")]
    (if (str/blank? server-root)
      (format "%s://%s:%s" proto host port)
      (format "%s://%s:%s/%s" proto host port server-root))))


(defn start-server
  [app]
  (when-not running-server
    (alter-var-root #'keep-running (constantly true))
    (let [{:keys [ssl?] :as config} (-> (c/server-config) (assoc :join? false)),
          server (jetty/run-jetty
                   app
                   (cond-> config
                     (not ssl?) (dissoc :ssl-port :key-password :keystore)))]
      (alter-var-root #'running-server (constantly server))
      (let [{:keys [host, port, ssl-port, ssl?, server-root]} (c/server-config)]
        (println "ctest started - Server listening on:")
        (println (server-url host, port, false, server-root))
        (when ssl?
          (println (server-url host, ssl-port, true, server-root))))
      server)))


(def ^:private pending-requests (atom 0))


(defn wait-for-completed-requests
  [timeout]
  (loop []
    (when (pos? @pending-requests)
      (Thread/sleep timeout)
      (recur))))


(defn wrap-shutdown
  [shutdown-routes, handler]
  (fn [request]
    (if (keep-running?)
      ; execute handler and keep track of the number of pending operations
      (try
        (swap! pending-requests inc)
        (handler request)
        (finally
          (swap! pending-requests dec)))
      ; display shutdown notice
      (shutdown-routes request))))


(defn stop-server []
  (try
    (when running-server
      (binding [log/*force* :direct]
        (alter-var-root #'keep-running (constantly false))
        (log/info "Server shutdown initiated.")
        (future
          (try
            (log/info "Waiting for requests to complete ...")
            ; wait for pending requests to complete
            (wait-for-completed-requests 250)
            ; sleep 1 second to let pending requests be served (since pending request are counted as completed in the middleware)
            (Thread/sleep 1000)
            (shutdown-agents)
            (log/info "Server shutdown finished.")
            ; stop jetty
            (let [server running-server]
              (alter-var-root #'running-server (constantly nil))
              (.stop ^Server server))
            ; after stopping the jetty server the program will exit
            (catch Throwable t
              (log/errorf "Exception in server shutdown thread:\n%s"
                (report/cause-trace t)))))))
    (catch Throwable t
      (log/errorf "Exception when initiating server shutdown:\n%s"
        (report/cause-trace t)))))


(defn- signal-available?
  []
  (try
    (Class/forName "sun.misc.Signal")
    true
    (catch Throwable t
      false)))


(defmacro signal-handler!
  [signal, handler-fn]
  (if (signal-available?)
    `(try
       (sun.misc.Signal/handle (sun.misc.Signal. ~signal),
         (proxy [sun.misc.SignalHandler] []
           (handle [sig#] (~handler-fn sig#))))
       (catch IllegalArgumentException e#
         (report/error "Startup" "Unable to set signal handler for signal: %s" ~signal)))
    `(report/error "Startup" "Signal handlers are not available on this platform. (signal: %s)" ~signal)))



(defn create-stop-handler
  [shutting-down?, signal]
  (fn [sig]
    (report/info "Shutdown" "Received %s signal.", signal)
    (when-let [stopped-fut (when (dosync
                                   (when-not (ensure shutting-down?)
                                     (alter shutting-down? (constantly true))))
                             (log/infof "Stopping server (signal: %s) ...", signal)
                             (import/stop-daemon)
                             (backup/stop-backup-daemon)
                             (stop-server))]
      (deref stopped-fut))))

(defn shutdown-on-sigterm!
  []
  (let [shutting-down? (ref false)]
    (let [sigterm-handler (create-stop-handler shutting-down?, "TERM")
          sigint-handler (create-stop-handler shutting-down?, "INT")]
      (signal-handler! "TERM", sigterm-handler)
      (signal-handler! "INT", sigint-handler))))