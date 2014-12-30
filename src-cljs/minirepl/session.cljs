(ns minirepl.session
  (:require [cljs.core]
            [minirepl.user :as user-session]
            [ajax.core :refer [POST]]))

(enable-console-print!)

(def *return* nil)
(def *out* nil)

(defn nth-last-value
  "Helper function for getting nth most recently executed
  expression value."
  [session n]
  (let [current-index (count (:history session))
        index         (- current-index (inc n))]
    (if (and (>= index 0)
             (< index current-index))
      (get-in session [:history index :value])
      nil)))

(defn within
  "Invoke the function f with the dynamic bindings established by
   the session context."
  [session line-number f]
  (let [{:keys [value-history]} session]
    (binding [user-session/*one
              (nth-last-value session 1)

              user-session/*two
              (nth-last-value session 2)

              user-session/*three
              (nth-last-value session 3)

              cljs.core/*print-newline*  true
              cljs.core/*print-readably* true

              cljs.core/*print-fn*
                (fn [s]
                  (set! minirepl.session/*out* (str minirepl.session/*out* s)))]
      (f))))

(defn create-session
  "Creates a hash representing the repl state."
  [] {:history []})

(defn execjs!
  "Evaluate in a try-catch block. This allows us display
   errors to the user."
  [compiled-js]
  (try
    (js/eval compiled-js)
    (catch :default e (set! *return* e))))

(defn count-lines [text]
  (count (.split text (js/RegExp. "\r\n|\r|\n"))))

(defn total-lines [session]
  (reduce #(+ %1 (count-lines (:code %2)))
          0
          (:history session)))

(defn new-expression [code session]
  (let [line-number (total-lines session)]
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
                       (str "(set! minirepl.session/*return* " code ")")
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

(defn session-state []
  [*return* *out*])

(defn clear-session-state! []
  (set! *return* nil)
  (set! *out* nil))

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
