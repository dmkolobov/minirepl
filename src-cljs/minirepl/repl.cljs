(ns minirepl.repl
  (:require [minirepl.util :as util]
            [minirepl.session :as repl-session]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :as async :refer [chan put!]]))

(defn static-mirror [contents]
  (om/component
    (dom/pre #js {:className "static-mirror"
                  :data-lang "clojure"}
             contents)))

(defn spinner [_]
  (om/component
    (dom/div #js {:className "evaluation-spinner"}
             (dom/div #js {:className "fa fa-spinner fa-spin"}))))

(defn print-expr-header [line-number]
  (om/component
    (dom/div #js {:className "expression-header"}
             (str line-number " =>"))))

(defn print-expr-code [expression owner]
  (om/component
    (dom/div #js {:className "expression-text"}
      (om/build static-mirror expression))))

(defn print-expr-value [val owner]
  (let [{:keys [value out evaled]} val]
    (om/component
      (dom/div #js {:className "expression-value"}
        (if evaled
          (om/build static-mirror out)
          (om/build spinner nil))))))

(defn print-expression [params owner]
  (let [[line-number item]           params
        {:keys [e value evaled out]} item]
    (om/component
        (dom/li #js {:className "print-expression"
                     :key       line-number}
          (om/build print-expr-header line-number)
          (dom/div #js {:className "repl-expression"}
            (om/build print-expr-code e)
            (om/build print-expr-value
                      {:value  value
                       :out    out
                       :evaled evaled}))))))

(defn repl-printer [session owner]
  (reify
    om/IDidUpdate
    (did-update [_ _ _]
      (.colorize js/CodeMirror
                 (.getElementsByClassName js/document "static-mirror")
                 "clojure"))

    om/IRender
    (render [_]
      (apply
        dom/ul #js {:className "repl-printer"}
        (om/build-all
          print-expression
          (map-indexed (fn [line-num item]
                         [line-num item])
                       (:history session)))))))

(defn repl-reader [session owner]
  (reify
    om/IInitState
    (init-state [_]
      {:expression ""})

    om/IDidMount
    (did-mount [_]
      (let [{:keys [user-input-chan]} (om/get-state owner)
            code-mirror (.fromTextArea js/CodeMirror
                          (.getElementById js/document "repl-reader")
                          #js {:mode      "clojure"
                               :matchBrackets     true
                               :autoCloseBrackets true
                               :theme             "paraiso-dark"
                               :extraKeys
                               #js {:Cmd-E
                                    (fn [cm]
                                      (put! user-input-chan
                                            (om/get-state owner :expression))
                                      (.setValue cm "")
                                      (om/set-state! owner :expression ""))}})]
        (.on code-mirror
             "changes"
             (fn [_]
               (let [current-doc (.getDoc code-mirror)
                     current-val (.getValue current-doc)]
                 (om/set-state! owner :expression current-val))))))

    om/IRenderState
    (render-state [_ {:keys [expression user-input-chan]}]
      (dom/div #js {:className "repl-reader print-expression"}
        (om/build print-expr-header (count (:history session)))
        (dom/textarea #js {:className "repl-text-input repl-expression"
                           :ref       "expression"
                           :id        "repl-reader"
                           :cols      80}
          nil)))))

(defn process-input!
  "FIXME"

  [session code done]
  (let [expression  (repl-session/new-expression code)
        line-number (count (:history @session))]
    (repl-session/read! expression #(done [line-number %]))
    (om/transact! session :history #(conj % expression))))

(defn process-response!
  "FIXME "

  [session compiler-response]
  (let [[line-number compiler-object] compiler-response
        session* (repl-session/eval! @session line-number compiler-object)]
    (om/transact! session (constantly session*))))

(defn repl-component [session owner]
  (reify
    om/IInitState
    (init-state [_]
      {:user-input-chan      (chan)
       :compiler-output-chan (chan)})

    om/IWillMount
    (will-mount [_]
      (let [{:keys [user-input-chan compiler-output-chan]} (om/get-state owner)]
        (util/consume-channel
          (fn [code]
            (process-input! session code #(put! compiler-output-chan %)))
          user-input-chan)
        (util/consume-channel
          (fn [compiler-response]
            (process-response! session compiler-response))
          compiler-output-chan)))

    om/IRenderState
    (render-state [this {:keys [user-input-chan]}]
      (dom/div #js {:className "web-repl"}
        (om/build repl-printer session)
        (om/build repl-reader
                  session
                  {:init-state {:user-input-chan user-input-chan}})))))
