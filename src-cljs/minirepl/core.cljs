(ns minirepl.core
  (:require [minirepl.repl :as repl]
            [minirepl.session :as repl-session]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(def current-session
  (atom (repl-session/create!)))

(om/root
  repl/repl-component
  current-session
  {:target (. js/document (getElementById "app"))})

