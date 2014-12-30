(ns minirepl.repl
  (:require [cljs.core]
            [minirepl.util :as util]
            [minirepl.editor :as editor]
            [minirepl.user :as user-session]
            [ajax.core :refer [POST]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :as async :refer [chan put!]]))

;;;; Sessions
;;;; ========

(def *return*
  "Used for holding the values of evaluated user expressions."
  nil)

(defn session-state []
  [*return*])

(defn clear-session-state! []
  (set! *return* nil))

(defn create-session
  "Creates a hash representing the repl state."
  [] {:history []})

(defn line-count
  "Count the number of lines typed in the current session."
  [session]
  (reduce #(+ %1 (util/count-lines (:code %2)))
          0
          (:history session)))

(defn new-expression [code line-number]
  {:code        code
   :out         ""
   :value       js/undefined
   :line-number line-number})

;;;; JavaScript Execution
;;;; ====================

(defn within
  "Invoke the function f with the dynamic bindings established by
   the session context."
  [session line-number f]
  (let [rhistory  (reverse (:history session))
        nth-value (comp (fn [expr _] (:value expr)) util/nth-or-nil)]
    (binding [user-session/*one   (nth-value rhistory 1)
              user-session/*two   (nth-value rhistory 2)
              user-session/*three (nth-value rhistory 3)]
      (f))))

(defn execjs!
  "Evaluate compiled user expression in a try-catch block.
   On error, set the *return* to the caught error instance."
  [compiled-js]
  (try (js/eval compiled-js)
       (catch :default e (set! *return* e))))

;;;; Types
;;;; =====

(defn- error? [v] (instance? js/Error v))
(defn- function? [v] (instance? js/Function v))

(defn- function-name [f]
  (let [name (.-name f)]
    (if (seq name)
      name
      'anonymous)))

;;;; Printing user codes
;;;; ===================

(defn- print-code
  "Component for displaying user expression codes."
  [expression owner]
  (om/component
    (dom/div #js {:className "expression-code"}
      (om/build editor/mirror {:theme        "paraiso-dark"
                               :content      (:code expression)
                               :number       true
                               :first-number (:line-number expression)
                               :readonly     true}))))

;;;; Printing user values
;;;; ====================

(defn- print-cursor [cursor]
  (binding [*print-readably*]
    (pr-str (om/value cursor))))

(defn- print-dispatch [expr _]
  (let [{:keys [value evaled]} expr]
    (cond (== value js/undefined) :unevaluated
          (error? value)          js/Error
          (function? value)       js/Function
          :else                   :default)))

(defmulti print-value print-dispatch)

(defn- print-value*
  [opts owner]
  (let [{:keys [content]} opts]
    (reify
      om/IRender
      (render [_]
      (om/build editor/mirror
                {:theme    "paraiso-dark"
                 :readonly true
                 :content  content})))))

(defmethod print-value :unevaluated
  [_]
  (om/component
    (dom/div #js {:className "evaluation-spinner"}
             (dom/div #js {:className "fa fa-spinner fa-spin"}))))

(defmethod print-value js/Error
  [expr]
  (let [{:keys [value]} expr]
    (reify
      om/IRender
      (render [_]
        (dom/div #js {:className "evaluation-error"}
                 (print-str value))))))

(defmethod print-value js/Function
  [expr]
  (let [f (expr :value)
        fname (function-name f)]
    (reify
      om/IRender
      (render [_]
        (dom/div #js {:className "expression-value"}
                 (om/build print-value*
                           {:content  (str "Procedure#" fname)}))))))

(defmethod print-value :default
  [expr]
  (reify
    om/IRender
    (render [_]
      (dom/div #js {:className "expression-value"}
               (om/build print-value*
                         {:content  (print-cursor (expr :value))})))))

(defn- print-expression [expression owner]
  (let [{:keys [code value]} expression]
    (om/component
        (dom/li #js {:className "repl-expression"}
            (om/build print-code expression)
            (dom/hr #js {:className "seam"})
            (om/build print-value expression)))))

(defn- repl-printer [session owner]
  (om/component
      (apply
        dom/ul #js {:className "repl-printer"}
        (om/build-all print-expression
                      (:history session)
                      {:key :line-number}))))

;; Reading user expressions
;; ========================

(defn- repl-reader [session owner]
  (reify
    om/IDidUpdate
    (did-update [_ _ _]
      (om/refresh! owner))

    om/IRenderState
    (render-state [_ {:keys [source-chan]}]
      (let [line-number (line-count session)]
        (dom/div #js {:className "repl-reader"}
          (om/build editor/mirror
                    {:theme        "paraiso-dark"
                     :number       true
                     :first-number line-number
                     :content      ""}
                    {:init-state {:submit-chan source-chan}}))))))

;; Updating repl component state
;; =============================

(defn- set-expr-value!
  [session index value]
  (om/transact! session
                [:history index :value]
                (constantly value)))

(defn wrap-code
  "Wrap user expression code in a set! call for capturing its value in the
   dynamic *return* var."
  [code]
  (str "(set! minirepl.repl/*return* " code ")"))

(defn read!
  "Sends an asynchronous request to compile a user expression.
   Calls 'on-read' when the response is received."
  [session code compiler-chan]
    (let [index       (count (:history @session))
          line-number (line-count @session)
          expression  (new-expression code line-number)]
      (POST "/repl"
            {:params  {:expression    (wrap-code code)
                       :ns-identifier 'minirepl.user}
             :handler #(put! compiler-chan [index %])})
      (om/transact! session :history #(conj % expression))))

(defmulti eval!
  "Evaluated the compiled user expression. If there is a compilation error,
   the value of the expression is the compilation error object."
  (fn [_ _ compiler-object]
    (cond (contains? compiler-object :compiled-js) :compiled-js
          (contains? compiler-object :compiler-error) :compiler-error)))

(defmethod eval! :compiler-error
  [session index compiler-object]
  (set-expr-value! session index (:compiler-error compiler-object)))

(defmethod eval! :compiled-js
  [session index compiler-object]
  (within session index #(execjs! (:compiled-js compiler-object)))
  (let [[value]     (session-state)]
    (clear-session-state!)
    (set-expr-value! session index value)))


;; Main component
;; ==============

(defn repl [session owner]
  (reify
    om/IInitState
    (init-state [_]
      {:source-chan   (chan)
       :compiler-chan (chan)})

    om/IWillMount
    (will-mount [_]
      (let [{:keys [source-chan compiler-chan]} (om/get-state owner)]
        (util/consume-channel
          (fn [code]
            (read! session code compiler-chan))
          source-chan)
        (util/consume-channel
          (fn [compiler-response]
            (let [[index compiler-object] compiler-response]
              (eval! session index compiler-object)))
          compiler-chan)))

    om/IRenderState
    (render-state [this {:keys [source-chan]}]
      (dom/div #js {:className "web-repl"}
        (om/build repl-printer session)
        (om/build repl-reader
                  session
                  {:init-state {:source-chan source-chan}})))))
