(ns minirepl.session
  (:require [cljs.core]
            [minirepl.user :as user-session]
            [ajax.core :refer [POST]]))

(enable-console-print!)

;; the 1th element is the most recent value
(defn nth-last-value [session line-number n]
  (let [idx           (- line-number n)]
    (if (and (>= idx 0)
             (< idx line-number))
      (get-in session [:history idx :value])
      nil)))

(defn within
  "Invoke the function f with the dynamic bindings established by
   the session context."

  [session line-number f]

  (let [{:keys [value-history]} session]
    (binding [user-session/*one   (nth-last-value session
                                                  line-number
                                                  1)
              user-session/*two   (nth-last-value session
                                                  line-number
                                                  2)
              user-session/*three (nth-last-value session
                                                  line-number
                                                  3)
              cljs.core/*print-newline*  true
              cljs.core/*print-readably* true
              cljs.core/*print-fn*
                (fn [s]
                  (set! user-session/*out* (str user-session/*out* s)))]
      (f))))

(defn create!
  "Creates a hash representing the repl state."

  []
  {:history []})

(defn execjs!
  "Evaluate in a try-catch block. This allows us display
   errors to the user."

  [compiled-js]

  (try
    (js/eval compiled-js)
    (catch :default e
      (do
        (set! user-session/*return* e)
        (println e)))))

(defn new-expression [e]
  {:e          e
   :out        ""
   :value      nil
   :evaled     false})

(defn read!
  "Sends an asynchronous request to compile the
   ClojureScript expression expression to Javascript. Will call
   on-read when the server responds, with the compiled JS."

  [expression on-read]

    (let [{:keys [e]} expression]
      (POST "/repl"
          {:params  {:expression
                       (str "(do (def *return* " e ")"
                            "    (println *return*))")
                     :ns-identifier
                       'minirepl.user}
           :handler (fn [compilation-response]
                      (on-read compilation-response))})))

(defmulti eval!
  (fn [_ _ read-response]
    (cond (contains? read-response :compiled-js) :compiled-js
          (contains? read-response :compiler-error) :compiler-error)))

(defmethod eval! :compiler-error
  [session line-number read-response]

  (let [compiler-error (:compiler-error read-response)]
   (update-in session
             [:history line-number]
             #(assoc % :value  compiler-error
                       :out    compiler-error
                       :evaled true))))

(defmethod eval! :compiled-js
  [session line-number read-response]

  (let [compiled-js (:compiled-js read-response)]
    (within session line-number #(execjs! compiled-js))
    (let [value user-session/*return*
          e-out user-session/*out*]
      (set! user-session/*return* nil)
      (set! user-session/*out* "")
      (update-in session
                 [:history line-number]
                 #(assoc % :value  value
                           :out    e-out
                           :evaled true)))))
