(ns minirepl.core
  (:require [minirepl.repl :as minirepl]
            [minirepl.session :as repl-session]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(def current-session
  (atom (repl-session/create-session)))

(om/root
  minirepl/repl
  current-session
  {:target (. js/document (getElementById "app"))})

