; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.actions.tools
  (:import (java.time ZoneId ZonedDateTime Instant LocalDateTime)
           (java.sql Timestamp)
           (java.time.format DateTimeFormatter DateTimeFormatterBuilder)
           (java.time.temporal ChronoField))
  (:require [clojure.stacktrace :refer [print-cause-trace]]))



(defn now
  ^ZonedDateTime []
  (ZonedDateTime/now (ZoneId/systemDefault)))

(defn timestamp
  ^long [^ZonedDateTime zoned-date-time]
  (.getTime (Timestamp/from (.toInstant zoned-date-time))))

(defn zoned-date-time
  ^ZonedDateTime [^long unix-timestamp]
  (ZonedDateTime/ofInstant (Instant/ofEpochMilli unix-timestamp), (ZoneId/systemDefault)))

(defn formatted-now
  []
  (let [today (now)
        dateformat (DateTimeFormatter/ofPattern "dd.MM.yyyy HH:mm")]
    (.format today dateformat)))


(defn parse-date
  [date-str]
  (.atZone
    (LocalDateTime/parse date-str
      (-> (DateTimeFormatterBuilder.)
        (.appendPattern "dd.MM.yyyy")
        (.parseDefaulting ChronoField/HOUR_OF_DAY 0)
        (.parseDefaulting ChronoField/MINUTE_OF_HOUR 0)
        (.parseDefaulting ChronoField/SECOND_OF_MINUTE 0)
        (.toFormatter)))
    (ZoneId/systemDefault)))


(defn date-parser
  [date-format]
  (let [formatter (-> (DateTimeFormatterBuilder.)
                    (.appendPattern date-format)
                    (.parseDefaulting ChronoField/HOUR_OF_DAY 0)
                    (.parseDefaulting ChronoField/MINUTE_OF_HOUR 0)
                    (.parseDefaulting ChronoField/SECOND_OF_MINUTE 0)
                    (.toFormatter))
        system-zone (ZoneId/systemDefault)]
    (fn parse-date [date-str]
      (.atZone
        (LocalDateTime/parse date-str, formatter)
        system-zone))))


(defn date-formatter
  [date-format]
  (let [formatter (DateTimeFormatter/ofPattern date-format)]
    (fn format [^ZonedDateTime zoned-date-time]
      (.format zoned-date-time formatter))))


(defn date-id
  [^ZonedDateTime zoned-date-time]
  (.format zoned-date-time (DateTimeFormatter/ofPattern "yyyyMMdd")))

(defn date-time-filename-suffix
  [^ZonedDateTime zoned-date-time]
  (.format zoned-date-time (DateTimeFormatter/ofPattern "yyyyMMdd-HHmm")))


(defn date-dashed-iso-format
  [^ZonedDateTime zoned-date-time]
  (.format zoned-date-time (DateTimeFormatter/ofPattern "yyyy-MM-dd")))

; single definition
(defn ordernr-with-date
  [ordernr, date]
  (str ordernr "-" (date-id date)))