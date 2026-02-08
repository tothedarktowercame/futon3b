(ns futon3.gate.util
  (:require [clojure.string :as str])
  (:import (java.time Instant)
           (java.util UUID)))

(defn now-iso []
  (str (Instant/now)))

(defn gen-id
  "Generate a stable-ish id with PREFIX."
  [prefix]
  (str prefix "-" (.substring (str (UUID/randomUUID)) 0 10)))

(defn blankish? [s]
  (or (nil? s)
      (and (string? s) (str/blank? s))))

