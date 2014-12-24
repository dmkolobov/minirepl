(ns minirepl.repl
  (:require [minirepl.session :as repl-session]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :as async :refer [chan put! <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn static-mirror [content owner]
  (reify
    om/IRender
    (render [_]
      (dom/pre #js {:className "static-mirror"
                    :data-lang "clojure"}
        content))))

(defn repl-expression [expression owner]
  (reify
    om/IRender
    (render [_]
      (dom/div #js {:className "expression-text"}
        (om/build static-mirror expression)))))

(defn repl-value [val owner]
  (let [{:keys [value out evaled]} val]
    (reify
      om/IRender
      (render [_]
        (dom/div #js {:className "expression-value"}
          (if evaled
            (om/build static-mirror out)
            (dom/div #js {:className "evaluation-spinner"}
              (dom/span #js {:className "fa fa-spinner fa-spin"}))))))))

(defn print-item [params owner]
  (let [[line-number item]           params
        {:keys [e value evaled out]} item]
    (reify
      om/IRender
      (render [_]
        (dom/li #js {:className "print-expression"
                     :key       line-number}
          (dom/div #js {:className "expression-header"}
            (str line-number " =>"))
          (dom/div #js {:className "repl-expression"}
            (om/build repl-expression e)
            (om/build repl-value
                      {:value  value
                       :out    out
                       :evaled evaled})))))))

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
          print-item
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
      (let [{:keys [reader-chan]} (om/get-state owner)
            code-mirror (.fromTextArea js/CodeMirror
                          (.getElementById js/document "repl-reader")
                          #js {:mode      "clojure"
                               :matchBrackets     true
                               :autoCloseBrackets true
                               :theme             "paraiso-dark"
                               :extraKeys
                               #js {:Cmd-E
                                    (fn [cm]
                                      (put! reader-chan
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
    (render-state [_ {:keys [expression reader-chan]}]
      (dom/div #js {:className "repl-reader print-expression"}
        (dom/div #js {:className "expression-header"}
          (str (count (:history session)) " =>"))
        (dom/textarea #js {:className "repl-text-input repl-expression"
                           :ref       "expression"
                           :id        "repl-reader"
                           :cols      80}
          nil)))))

(defn repl-component [session owner]
  (reify
    om/IInitState
    (init-state [_]
      {:reader-chan (chan)
       :eval-chan   (chan)})

    om/IWillMount
    (will-mount [_]
      (let [{:keys [reader-chan eval-chan]} (om/get-state owner)]
        (go (loop []
          (let [expression  (repl-session/new-expression (<! reader-chan))
                line-number (count (:history @session))]
            (repl-session/read! expression  #(put! eval-chan [line-number %]))
            (om/transact! session :history #(conj % expression))
            (recur))))
        (go (loop []
          (let [[line-number compilation-response] (<! eval-chan)]
            (let [session* (repl-session/eval! @session line-number compilation-response)]
              (om/transact! session (constantly session*)))
            (recur))))))

    om/IRenderState
    (render-state [this {:keys [reader-chan]}]
      (dom/div #js {:className "web-repl"}
        (om/build repl-printer session)
        (om/build repl-reader
                  session
                  {:init-state {:reader-chan reader-chan}})))))
