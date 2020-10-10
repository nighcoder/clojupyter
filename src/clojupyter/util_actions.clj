(ns clojupyter.util-actions
  (:require [clojupyter.log :as log]
            [clojure.core.async :as async]
            [io.simplect.compose :refer [P]]
            [io.simplect.compose.action :as a]
            [java-time :as jtm])
  (:import java.time.format.DateTimeFormatter))

(defmacro ^{:style/indent :defn} closing-channels-on-exit!
  [channels & body]
  `(try ~@body
        (finally ~@(for [chan channels]
                     `(async/close! ~chan)))))

(defn uuid
  "Returns a random UUID as a string."
  []
  (str (java.util.UUID/randomUUID)))

(defn- set-indent-style!
  [var style]
  (alter-meta! var (P assoc :style/indent style)))

;;; ----------------------------------------------------------------------------------------------------
;;; EXTERNAL
;;; ----------------------------------------------------------------------------------------------------

(defn java-util-data-now
  []
  (new java.util.Date))

(defn now []
  (->> (.withNano (java.time.ZonedDateTime/now) 0)
       (jtm/format DateTimeFormatter/ISO_OFFSET_DATE_TIME)))

(defn set-defn-indent!
  [& vars]
  (doseq [var vars]
    (set-indent-style! var :defn)))

(defn set-var-private!
  [var]
  (alter-meta! var (P assoc :private true)))


(defn- with-exception-logging*
  ([form finally-form]
   `(try ~form
         (catch Exception e#
           (do (log/error e#)
               (throw e#)))
         (finally ~finally-form))))

(defmacro ^{:style/indent 1} with-exception-logging
  ([form]
   (with-exception-logging* form '(do)))
  ([form finally-form]
   (with-exception-logging* form finally-form)))

(defn assoc-meta!
  [k v var]
  (alter-meta! var #(assoc % k v)))
