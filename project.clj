(defproject
  minirepl     "0.1.0-SNAPSHOT"

  :description "FIXME: write description"
  :url         "http://example.com/FIXME"

  :dependencies [[com.taoensso/tower "3.0.2"]
                 [reagent-forms "0.2.6"]
                 [markdown-clj "0.9.58" :exclusions [com.keminglabs/cljx]]
                 [prone "0.6.0"]
                 [selmer "0.7.6"]
                 [im.chit/cronj "1.4.3"]
                 [com.taoensso/timbre "3.3.1"]
                 [cljs-ajax "0.3.3"]
                 [noir-exception "0.2.3"]
                 [lib-noir "0.9.4"]
                 [org.clojure/clojurescript "0.0-2511"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojure "1.6.0"]
                 [om "0.8.0-beta5"]
                 [environ "1.0.0"]
                 [ring-server "0.3.1"]
                 [secretary "1.2.1"]]

  :repl-options  {:init-ns minirepl.repl}
  :jvm-opts      ["-Xmx512m", "-server"]

  :plugins [[lein-ring "0.8.13"]
            [lein-environ "1.0.0"]
            [lein-ancient "0.5.5"]
            [lein-cljsbuild "1.0.3"]]

  :ring    {:handler minirepl.handler/app,
            :init minirepl.handler/init,
            :destroy minirepl.handler/destroy}
  :profiles

    {:uberjar    {:cljsbuild
                   {:jar true,
                    :builds
                    {:app
                     {:source-paths ["env/prod/cljs"],
                      :compiler {:optimizations :advanced, :pretty-print false}}}},
                   :hooks [leiningen.cljsbuild],
                   :omit-source true,
                   :env {:production true},
                   :aot :all},

   :production   {:ring {:open-browser? false,
                         :stacktraces? false,
                         :auto-reload? false}},

   :dev          {:dependencies [[ring-mock "0.1.5"]
                                 [ring/ring-devel "1.3.1"]
                                 [pjstadig/humane-test-output "0.6.0"]],
                  :injections   [(require 'pjstadig.humane-test-output)
                                 (pjstadig.humane-test-output/activate!)],
                  :env {:dev true}}}

  :cljsbuild
  {:builds
   {:app
    {:source-paths ["src-cljs"],
     :compiler
     {:output-dir "resources/public/js/out",
      :optimizations :none,
      :output-to "resources/public/js/app.js",
      :source-map "resources/public/js/out.js.map",
      :pretty-print true}}}}
  :min-lein-version "2.0.0")
