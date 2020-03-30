; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.common
  (:require [clojure.java.io :as io])
  (:import (java.security SecureRandom)
           (com.google.zxing.client.j2se MatrixToImageWriter)
           (com.google.zxing EncodeHintType BarcodeFormat)
           (com.google.zxing.qrcode.decoder ErrorCorrectionLevel)
           (com.google.zxing.qrcode QRCodeWriter)
           (java.nio.file StandardCopyOption Files)))


(defn ->vector
  [xs]
  (if (vector? xs)
    xs
    (vec xs)))


(defn format-uuid
  [^bytes uuid-bytes, ^long byte-group-size]
  (let [size (alength uuid-bytes)]
    (loop [i 0, sb (StringBuilder.)]
      (if (< i size)
        (do
          (doto sb
            (cond->
              (and (pos? i) (zero? (mod i byte-group-size)))
              (.append "-"))
            (.append (format "%02X" (aget uuid-bytes i))))
          (recur
            (unchecked-inc i)
            sb))
        (str sb)))))


(let [prng (SecureRandom.)]
  (defn random-uuid
    [^long size, ^long byte-group-size]
    (let [uuid-bytes (byte-array size)
          _ (locking prng
              (.nextBytes prng uuid-bytes))]
      (format-uuid uuid-bytes, byte-group-size))))


(defn generate-qr-code-file
  [file, ^String content, size]
  (let [qr-code-writer (QRCodeWriter.)
        bit-matrix (.encode qr-code-writer content, BarcodeFormat/QR_CODE, size, size, {EncodeHintType/ERROR_CORRECTION ErrorCorrectionLevel/H})]
    (MatrixToImageWriter/writeToPath bit-matrix, "PNG", (.toPath (io/file file)))))


(defn ensure-parent-directory
  [file]
  (-> file (io/file) (.getParentFile) (.mkdirs)))


(defn ensure-directory
  [file]
  (-> file (io/file) (.mkdirs)))


(defn file-exists?
  [file]
  (.exists (io/file file)))


(defn rename-file
  [old-file, new-file, & {:keys [replace?]}]
  (Files/move
    (.toPath (io/file old-file))
    (.toPath (io/file new-file))
    ^"[Ljava.nio.file.StandardCopyOption;" (into-array StandardCopyOption
                                             (cond-> []
                                               replace?
                                               (conj StandardCopyOption/REPLACE_EXISTING)))))


(defn copy-file
  [source-file, target-file, & {:keys [replace?]}]
  (Files/copy
    (.toPath (io/file source-file))
    (.toPath (io/file target-file))
    ^"[Ljava.nio.file.StandardCopyOption;" (into-array StandardCopyOption
                                             (cond-> []
                                               replace?
                                               (conj StandardCopyOption/REPLACE_EXISTING)))))


(defn copy-resource
  [resource-name, target-file, & {:keys [replace?]}]
  (Files/copy
    (-> resource-name io/resource io/input-stream)
    (.toPath (io/file target-file))
    ^"[Ljava.nio.file.StandardCopyOption;" (into-array StandardCopyOption
                                             (cond-> []
                                               replace?
                                               (conj StandardCopyOption/REPLACE_EXISTING)))))