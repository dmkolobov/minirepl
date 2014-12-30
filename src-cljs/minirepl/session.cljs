(ns minirepl.session
  (:require [cljs.core]
            [minirepl.user :as user-session]
            [ajax.core :refer [POST]]))

(enable-console-print!)

(def *return*
  "Used for holding the values of evaluated user expressions."
  nil)

(defn session-state []
  [*return* *out*])

(defn clear-session-state! []
  (set! *return* nil)
  (set! *out* nil))

(defn- nth-or-nil
  "Return the nth item in 'coll', or nil if 'coll' is too short."
  [coll n]
  (if (< (count coll) (inc n))
    nil
    (nth coll n)))

(defn within
  "Invoke the function f with the dynamic bindings established by
   the session context."
  [session line-number f]
  (let [rhistory  (reverse (:history session))
        nth-value (comp (fn [expr _] (:value expr)) nth-or-nil)]
    (binding [user-session/*one   (nth-value rhistory 1)
              user-session/*two   (nth-value rhistory 2)
              user-session/*three (nth-value rhistory 3)]
      (f))))

(defn create-session
  "Creates a hash representing the repl state."
  [] {:history []})

(defn execjs!
  "Evaluate compiled user expression in a try-catch block.
   On error, set the *return* to the caught error instance."
  [compiled-js]
  (try (js/eval compiled-js)
       (catch :default e (set! *return* e))))

(defn count-lines
  "Count the number of lines in a piece of text."
  [text]
  (count (.split text (js/RegExp. "\r\n|\r|\n"))))

(defn line-count
  "Count the number of lines typed in the current session."
  [session]
  (reduce #(+ %1 (count-lines (:code %2)))
          0
          (:history session)))

(defn new-expression [code session]
  (let [line-number (line-count session)]
    {:code        code
     :out         ""
     :value       nil
     :evaled      false
     :line-number line-number}))

(defn read!
  "Sends an asynchronous request to compile a user expression.
   Calls 'on-read' when the response is received."

  [expression on-read]

    (let [{:keys [code]} expression]
      (POST "/repl"
          {:params  {:expression
                       (str "(set! minirepl.session/*return* " code ")")
                     :ns-identifier
                       'minirepl.user}
           :handler (fn [compilation-response]
                      (on-read compilation-response))})))

(defmulti eval!
  "Evaluated the compiled user expression. If there is a compilation error,
   the value of the expression is the compilation error object."
  (fn [_ _ compiler-object]
    (cond (contains? compiler-object :compiled-js) :compiled-js
          (contains? compiler-object :compiler-error) :compiler-error)))

(defmethod eval! :compiler-error
  [session line-number compiler-object]
  (let [compiler-error (:compiler-error compiler-object)]
   (update-in session
             [:history line-number]
             #(assoc % :value  compiler-error
                       :evaled true))))

(defmethod eval! :compiled-js
  [session line-number compiler-object]
  (let [compiled-js (:compiled-js compiler-object)]
    (within session line-number #(execjs! compiled-js))
    (let [[value out] (session-state)]
      (clear-session-state!)
      (update-in session
                 [:history line-number]
                 #(assoc % :value  value
                           :out    out
                           :evaled true)))))
