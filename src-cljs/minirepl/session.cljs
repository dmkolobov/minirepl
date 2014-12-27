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
        (print (str e
                      "\n"
                      (.-fileName e)
                      ":"
                      (.-lineNumber e)))))))

(defn last-line-number [history]
  (let [last-expression (last history)]
    (if last-expression (+ (count (.split (:code last-expression)
                                          (js/RegExp. "\r\n|\r|\n")))
                                  (:line-number last-expression))
                        0)))

(defn new-expression [code history]
  (let [line-number (last-line-number history)]
    {:code        code
     :out         ""
     :value       nil
     :evaled      false
     :line-number line-number}))

(defn read!
  "Sends an asynchronous request to compile the
   ClojureScript expression expression to Javascript. Will call
   on-read when the server responds, with the compiled JS."

  [expression on-read]

    (let [{:keys [code]} expression]
      (POST "/repl"
          {:params  {:expression
                       (str "(do (def *return* " code ")"
                            "    (print *return*))")
                     :ns-identifier
                       'minirepl.user}
           :handler (fn [compilation-response]
                      (on-read compilation-response))})))

(defmulti eval!
  (fn [_ _ compiler-object]
    (cond (contains? compiler-object :compiled-js) :compiled-js
          (contains? compiler-object :compiler-error) :compiler-error)))

(defmethod eval! :compiler-error
  [session line-number compiler-object]

  (let [compiler-error (:compiler-error compiler-object)]
   (update-in session
             [:history line-number]
             #(assoc % :value  compiler-error
                       :out    compiler-error
                       :evaled true))))

(defmethod eval! :compiled-js
  [session line-number compiler-object]

  (let [compiled-js (:compiled-js compiler-object)]
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
