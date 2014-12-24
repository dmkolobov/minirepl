(ns minirepl.compiler
  (:require
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.reader-types :as readers]
    [cljs.analyzer :as ana]
    [cljs.compiler :as compiler]
    [cljs.env :as env]))

(defn string-reader
  "Called with uncompiled CLJS source, returns a reader
   for reading forms."

  [s]
  (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. s)))

(defn forms-seq
  "Takes a string reader stream as an argument and returns
   a lazy sequence of CLJS forms."

  [stream]
  (let [rdr (readers/indexing-push-back-reader stream 1)
        forms-seq* (fn forms-seq* []
                     (lazy-seq
                      (if-let [form (reader/read rdr nil nil)]
                        (cons form (forms-seq*)))))]
    (forms-seq*)))

(defn build-env
  "Create an analyzer environment."

  [ns-identifier]
  {:ns      {:name ns-identifier}
   :locals  {}
   :context :expr}) ;; possibly wrong

(defn ->cljs
  "Compiles the CLJS expression, which may consist of zero or more forms.
   The current value of *ns* will be set to the symbol ns-identifier."

  ([expression]
    (->cljs expression 'minirepl.user))

  ([expression ns-identifier]
    (let [code-stream (string-reader (str "(do " expression")"))
          forms       (forms-seq code-stream)
          user-env    (build-env ns-identifier)]
      (env/ensure
        (compiler/with-core-cljs nil
          (fn []
            (with-out-str
              (doseq [form forms]
                (compiler/emit (ana/analyze user-env form))))))))))
