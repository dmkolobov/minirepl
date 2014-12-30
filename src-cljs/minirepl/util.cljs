(ns minirepl.util
  (:require [cljs.core.async :as async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn nth-or-nil
  "Return the nth item in 'coll', or nil if 'coll' is too short."
  [coll n]
  (if (< (count coll) (inc n))
    nil
    (nth coll n)))

(defn count-lines
  "Count the number of lines in a piece of text."
  [s]
  (count (.split s (js/RegExp. "\r\n|\r|\n"))))


(defn consume-channel
  "FIXME"

  [f c]
  (go (loop []
        (let [item (<! c)]
          (f item)
          (recur)))))
