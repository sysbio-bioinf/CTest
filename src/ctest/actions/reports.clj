; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.actions.reports
  (:require [ctest.db.crud :as crud]
            [ctest.reporting :as report])
  (:import (java.lang.management ManagementFactory)
           (com.sun.management UnixOperatingSystemMXBean)))



(defn report-list
  []
  (try
    {:status 200
     :body {:report-list (crud/report-list)}}
    (catch Throwable t
      (report/error "Report List", "Exception during report list query:\n%s", (report/cause-trace t))
      {:status 500})))



(defn memory-info
  []
  (let [memory-bean (ManagementFactory/getMemoryMXBean)
        heap-usage (.getHeapMemoryUsage memory-bean)]
    {:used-memory (.getUsed heap-usage)
     :max-memory (.getMax heap-usage)}))


(defn cpu-usage-info
  []
  (let [os-bean (ManagementFactory/getOperatingSystemMXBean)]
    (cond
      (instance? com.sun.management.OperatingSystemMXBean os-bean)
      (let [^com.sun.management.OperatingSystemMXBean os-bean os-bean]
        {:system-cpu-load (.getSystemCpuLoad os-bean)
         :process-cpu-load (.getProcessCpuLoad os-bean)})

      (instance? UnixOperatingSystemMXBean os-bean)
      (let [^UnixOperatingSystemMXBean os-bean os-bean]
        {:system-cpu-load (.getSystemCpuLoad os-bean)
         :process-cpu-load (.getProcessCpuLoad os-bean)}))))


(defn system-info
  []
  (try
    {:status 200
     :body (merge (memory-info) (cpu-usage-info))}
    (catch Throwable t
      (report/error "System Info", "Exception during system info query:\n%s", (report/cause-trace t))
      {:status 500})))


(defn delete-reports
  [{:keys [report-ids]}]
  (try
    (cond
      (empty? report-ids)
      {:status 400, :body "No report ids specified (:report-ids)!"}

      (every? integer? report-ids)
      (do
        (crud/delete-reports report-ids)
        {:status 200})

      :else
      {:status 400, :body "You must specify the report ids as integer values."})
    (catch Throwable t
      (report/error "Delete Reports", "Exception during delete report query:\n%s", (report/cause-trace t))
      {:status 500})))


(defn views-per-day
  []
  (try
    {:status 200
     :body {:views-per-day (vec (sort-by :date (crud/views-per-day)))}}
    (catch Throwable t
      (report/error "Views per Day", "Exception during views per day query:\n%s", (report/cause-trace t))
      {:status 500})))


(defn test-dates
  []
  (try
    {:status 200
     :body {:creation-dates (crud/test-dates)}}
    (catch Throwable t
      (report/error "Creation Dates", "Exception during creation dates query:\n%s", (report/cause-trace t))
      {:status 500})))