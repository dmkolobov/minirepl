(ns minirepl.repl
  (:require [minirepl.util :as util]
            [minirepl.session :as repl-session]
            [minirepl.editor :as editor]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :as async :refer [chan put!]]))

(defn spinner [_]
  (om/component
    (dom/div #js {:className "evaluation-spinner"}
             (dom/div #js {:className "fa fa-spinner fa-spin"}))))

;; Printing user expressions
;; =========================

(defn print-expr-code [expression owner]
  (om/component
    (dom/div #js {:className "expression-code"}
      (om/build editor/mirror {:theme        "paraiso-dark"
                               :content      (:code expression)
                               :number       true
                               :first-number (:line-number expression)
                               :readonly     true}))))

(defn print-expr-value [val owner]
  (let [{:keys [value out evaled]} val]
    (om/component
      (dom/div #js {:className "expression-value"}
        (if evaled
          (om/build editor/mirror {:theme    "paraiso-dark"
                                   :readonly true
                                   :content  out})
          (om/build spinner nil))))))

(defn print-expression [params owner]
  (let [[line-number expression]        params
        {:keys [code value evaled out]} expression]
    (om/component
        (dom/li #js {:className "repl-expression"
                     :key       line-number}
            (om/build print-expr-code expression)
            (dom/hr #js {:className "seam"})
            (om/build print-expr-value expression)))))

(defn repl-printer [session owner]
  (om/component
      (apply
        dom/ul #js {:className "repl-printer"}
        (om/build-all
          print-expression
          (map-indexed (fn [line-num item]
                         [line-num item])
                       (:history session))))))

;; Reading user expressions
;; ========================

(defn repl-reader [session owner]
  (reify
    om/IDidUpdate
    (did-update [_ _ _]
                (om/refresh! owner))

    om/IRenderState
    (render-state [_ {:keys [source-chan]}]
      (let [line-number (repl-session/last-line-number (:history session))]
        (dom/div #js {:className "repl-reader"}
          (om/build editor/mirror
                    {:theme        "paraiso-dark"
                     :number       true
                     :first-number line-number
                     :content      ""}
                    {:init-state {:submit-chan source-chan}}))))))


;; Updating repl component state
;; =============================

(defn process-input!
  "FIXME"

  [session code done]
  (let [history    (:history @session)
        expression (repl-session/new-expression code history)
        index      (count history)]
    (repl-session/read! expression #(done [index %]))
    (om/transact! session :history #(conj % expression))))

(defn process-response!
  "FIXME "

  [session compiler-response]
  (let [[line-number compiler-object] compiler-response
        session* (repl-session/eval! @session line-number compiler-object)]
    (om/transact! session (constantly session*))))

;; Main component
;; ==============

(defn repl-component [session owner]
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
            (process-input! session code #(put! compiler-chan %)))
          source-chan)
        (util/consume-channel
          (fn [compiler-response]
            (process-response! session compiler-response))
          compiler-chan)))

    om/IRenderState
    (render-state [this {:keys [source-chan]}]
      (dom/div #js {:className "web-repl"}
        (om/build repl-printer session)
        (om/build repl-reader
                  session
                  {:init-state {:source-chan source-chan}})))))
