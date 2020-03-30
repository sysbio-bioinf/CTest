; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.reporting
  (:require [clojure.tools.logging :as log]
            [clojure.stacktrace :as st]
            [ctest.actions.tools :as t]
            [ctest.db.crud :as crud]
            [ctest.config :as c]))



(defn cause-trace
  [^Throwable t]
  (with-out-str (st/print-cause-trace t)))


(defn create-report
  [type, context, message]
  (try
    (let [now-ts (t/timestamp (t/now))]
      (crud/insert-report (c/db-connection),
        {:timestamp now-ts,
         :type (name type)
         :context context
         :message message}))
    (catch Throwable t
      (log/errorf "Failed to write \"%s\" report for context \"%s\" to database.\n%s"
        type,
        context
        (cause-trace t)))))


(defn error
  [context, message-fmt, & args]
  (let [message (apply format (str context ": " message-fmt) args)]
    (log/error message)
    (create-report :error, context, message)))


(defn warn
  [context, message-fmt, & args]
  (let [message (apply format (str context ": " message-fmt) args)]
    (log/warn message)
    (create-report :warn, context, message)))


(defn info
  [context, message-fmt, & args]
  (let [message (apply format (str context ": " message-fmt) args)]
    (log/info message)
    (create-report :info, context, message)))


(defn trace
  [context, message-fmt, & args]
  (log/trace (apply format (str context ": " message-fmt) args)))


(defn debug
  [context, message-fmt, & args]
  (log/debug (apply format (str context ": " message-fmt) args)))