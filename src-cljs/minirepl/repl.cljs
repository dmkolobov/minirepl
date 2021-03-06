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

(def *value*
  "Used for holding the values of evaluated user expressions."
  nil)

(def *value-str*
  "Use for holding the printed value of user expressions."
  "")

(def *out*
  "Used for holding the result of printing."
  "")

(defn clear-eval-state! []
  (set! *value* nil)
  (set! *value-str* "")
  (set! *out* ""))

(defn create-session
  "Creates a hash representing the repl state."
  [] {:history []})

(defn line-count
  "Count the number of lines typed in the current session."
  [session]
  (reduce (fn [total expr]
            (+ total (util/count-lines (:code expr))))
          0
          (:history session)))

(defn new-expression [code line-number]
  {:evaled?     false
   :code        code
   :out         ""
   :value       nil
   :line-number line-number})

;;;; JavaScript Execution
;;;; ====================

(defn eval-js!
  "Invoke the function f with the dynamic bindings established by
   the session context."
  [session line-number compiled-js]
  (let [rhistory  (reverse (:history session))
        nth-value (comp (fn [expr _] (:value expr)) util/nth-or-nil)]
    (binding [*print-newline*     true
              *print-readably*    true
              *print-length*      100
              user-session/*one   (nth-value rhistory 1)
              user-session/*two   (nth-value rhistory 2)
              user-session/*three (nth-value rhistory 3)
              user-session/dvar   (fn [sym] (get-in user-session/*var-map* [sym]))]
      (try (js/eval compiled-js)
           (catch :default e (set! *value* e))))))

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

(defn- print-dispatch [expr _]
  (let [{:keys [value evaled?]} expr]
    (cond (not evaled?) :unevaluated
          (error? value)                  js/Error
          (function? value)               js/Function
          :else                           :default)))

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

(defmulti print-value print-dispatch)

(defmethod print-value :unevaluated
  [_ owner]
  (om/component
    (dom/div #js {:className "evaluation-spinner"}
             (dom/div #js {:className "fa fa-spinner fa-spin"}))))

(defmethod print-value js/Error
  [expr owner]
    (reify
      om/IRender
      (render [_]
        (dom/div #js {:className "evaluation-error"}
                 (.-message (:value expr))))))

(defmethod print-value js/Function
  [expr owner]
  (let [f (expr :value)
        fname (function-name f)]
    (reify
      om/IRender
      (render [_]
        (dom/div #js {:className "expression-value"}
                 (om/build print-value*
                           {:content  (:value-str expr)}))))))

(defmethod print-value :default
  [expr owner]
  (reify
    om/IRender
    (render [_]
      (dom/div #js {:className "expression-value"}
               (om/build print-value*
                         {:content (:value-str expr)})))))

(defn- print-out
  [[out] owner]
  (om/component
   (dom/div #js {:className "expression-out"}
            (om/build print-value*
                      {:content out}))))

(defn- print-expression
  [expr owner]
  (let [{:keys [code value out]} expr]
    (om/component
      (dom/li #js {:className "repl-expression"}
          (om/build print-code expr)
          (dom/hr #js {:className "seam"})
          (when (seq out)
            (om/build print-out [out]))
          (om/build print-value expr)))))

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

(defn wrap-code
  "Wrap user expression code in a set! call for capturing its value in the
   dynamic *return* var."
  [code]
  (str "(set! minirepl.repl/*value* (do " code "))"
       "(set! minirepl.repl/*value-str* (pr-str minirepl.repl/*value*))"))

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
  (om/transact! session
                [:history index]
                #(assoc % :value (:compiler-error compiler-object))))

(defmethod eval! :compiled-js
  [session index compiler-object]
  (eval-js! session
            index
            (:compiled-js compiler-object))
  (let [value     *value*
        value-str *value-str*
        out       *out*]
    (clear-eval-state!)
    (om/transact! session
                  [:history index]
                  (fn [expr]
                    (assoc expr
                           :out       out
                           :value     value
                           :value-str value-str
                           :evaled?   true)))))

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
      (set! *print-fn* (fn [s]
                         (set! *out* (str *out* s))))
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
