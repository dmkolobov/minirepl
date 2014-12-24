(ns minirepl.routes.home
  (:require [minirepl.util :as util]
            [minirepl.compiler :as compiler]
            [compojure.core :refer :all]
            [noir.response :refer [edn content-type]]
            [clojure.pprint :refer [pprint]]))

(defn do-compile
  "Wrap compilation of the expression in a try-catch block"

  [expression ns-identifier]
  (try
    (let [compiled-js (compiler/->cljs expression ns-identifier)]
      (println (str "compiled successfully:"
                    expression))
      {:compiled-js compiled-js})
    (catch Exception e
      (println (str "compiled with errors:"
                    expression))
      {:compiler-error (.getMessage e)})))

(defn compilation-route
  "Compile the requested CLJS expression expression under the
   namespace specified by ns-identifier."

  [request]
  (let [{:keys [expression ns-identifier]} (:params request)]
    (do-compile expression ns-identifier)))

(defroutes home-routes
  (POST "/repl"
        request
        (edn (compilation-route request))))
