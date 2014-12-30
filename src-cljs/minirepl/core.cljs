(ns minirepl.core
  (:require [minirepl.repl :as minirepl]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(def current-session
  (atom (minirepl/create-session)))

(om/root
  minirepl/repl
  current-session
  {:target (. js/document (getElementById "app"))})

