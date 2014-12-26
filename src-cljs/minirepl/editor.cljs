(ns minirepl.editor
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :as async :refer [put!]]))

(defn static-mirror [contents]
  (om/component
    (dom/pre #js {:className "static-mirror"
                  :data-lang "clojure"}
             contents)))

(defn mirror-value [cm]
  (let [current-doc (.getDoc cm)]
    (.getValue current-doc)))

(defn init-key-bindings [submit-chan]
  (clj->js {:Cmd-E (fn [cm _]
                     (put! submit-chan (mirror-value cm))
                     (.setValue cm ""))
            :Cmd-R (constantly nil)}))

(defn mirror [options owner]
  (let [{:keys [theme]} options]
    (reify
      om/IDidMount
      (did-mount [_]
        (.fromTextArea js/CodeMirror
          (om/get-node owner)
          #js {:mode              "clojure"
               :matchBrackets     true
               :autoCloseBrackets true
               :theme             theme
               :extraKeys         (init-key-bindings (om/get-state owner :submit-chan))}))

      om/IRenderState
      (render-state [_ _]
        (dom/textarea #js {:className "repl-text-input repl-expression"} nil)))))
