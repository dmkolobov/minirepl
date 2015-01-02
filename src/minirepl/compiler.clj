(ns minirepl.compiler
  (:require
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.reader-types :as readers]
    [cljs.analyzer :as ana]
    [cljs.compiler :as compiler]
    [cljs.env :as env]))

(defn- string-reader
  "Called with uncompiled CLJS source, returns a reader
   for reading forms."

  [s]
  (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. s)))

(defn- forms-seq
  "Takes a string reader stream as an argument and returns
   a lazy sequence of CLJS forms."

  [stream]
  (let [rdr (readers/indexing-push-back-reader stream 1)
        forms-seq* (fn forms-seq* []
                     (lazy-seq
                      (if-let [form (reader/read rdr nil nil)]
                        (cons form (forms-seq*)))))]
    (forms-seq*)))

(defn- build-env
  "Create an analyzer environment."

  [expr-ns]
  {:ns      {:name expr-ns}
   :locals  {}
   :context :expr}) ;; possibly wrong

(defn- compile* [forms ana-env compile-env]
  (env/ensure
    (env/with-compiler-env compile-env
      (compiler/with-core-cljs nil
        (fn []
          (with-out-str
            (doseq [form forms]
              (compiler/emit (ana/analyze ana-env form)))))))))

(defn- ->cljs*
  "Compiles the CLJS expression, which may consist of zero or more forms.
   The current value of *ns* will be set to the symbol expr-ns."

  ([expression]
    (->cljs* expression 'minirepl.user nil))

  ([expression expr-ns]
    (->cljs* expression expr-ns nil))

  ([expression expr-ns comp-env]
    (let [code-stream (string-reader (str "(do " expression")"))
          forms       (forms-seq code-stream)
          ana-env     (build-env expr-ns)
          compile-env (if comp-env
                        comp-env
                        (atom {}))
          compiled-js (compile* forms ana-env compile-env)]
      {:comp-ns (get-in @compile-env [:cljs.analyzer/namespaces expr-ns])
       :comp-js  compiled-js
       :continue compile-env})))

(defn- make-var-source
  [comp-ns expr-ns]
  (reduce (fn [source def-name]
            (str source
                 "(set! " expr-ns "/*var-map* (assoc " expr-ns "/*var-map* '"
                                   def-name
                                   " (var " (str def-name) ")))"))
          ""
          (keys (:defs comp-ns))))

(defn ->cljs
  ""
  ([expression]
   (->cljs expression 'minirepl.user))

  ([expression expr-ns]
   (let [first-pass (->cljs* expression expr-ns)
         var-source (make-var-source (:comp-ns first-pass)
                                     expr-ns)
         var-pass   (->cljs* var-source
                             expr-ns
                             (:continue first-pass))]
     (println expr-ns)
     (println (:comp-ns first-pass))
     (println (str "var-source: " var-source))
     (str (:comp-js first-pass)
          (:comp-js var-pass)))))










