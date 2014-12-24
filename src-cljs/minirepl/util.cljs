(ns minirepl.util
  (:require [cljs.core.async :as async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn consume-channel
  "FIXME"

  [f c]
  (go (loop []
        (let [item (<! c)]
          (f item)
          (recur)))))
