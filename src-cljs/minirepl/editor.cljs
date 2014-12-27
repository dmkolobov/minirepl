(ns minirepl.editor
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :as async :refer [put!]]))

(defn mirror-value [cm]
  (let [current-doc (.getDoc cm)]
    (.getValue current-doc)))

(defn key-bindings [submit-chan]
  (clj->js {:Cmd-E (fn [cm _]
                     (put! submit-chan (mirror-value cm))
                     (.setValue cm ""))
            :Cmd-R (constantly nil)}))

(defn base-config [theme]
  {:mode              "clojure"
   :matchBrackets     true
   :theme             theme})

(defn parse-options [submit-chan options]
  (let [{:keys [content readonly number theme]} options]
    (as-> (base-config theme) config
          (if readonly (assoc config :readOnly true)
                       (assoc config
                              :extraKeys (key-bindings submit-chan)
                              :autoCloseBrackets true))
          (if number (assoc config :lineNumbers true) config)
          (if content (assoc config :value content)))))

(defn mirror [options owner]
  (reify
    om/IInitState
    (init-state [_] {})

    om/IDidMount
    (did-mount [_]
      (let [node (om/get-node owner)
            config (parse-options (om/get-state owner :submit-chan) options)]
        (js/CodeMirror node (clj->js config))))

    om/IRenderState
    (render-state [_ _]
      (dom/pre #js {:className ""} nil))))
