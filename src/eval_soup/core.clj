(ns eval-soup.core
  (:require [clojure.java.io :as io]
            [eval-soup.clojail :refer [thunk-timeout]])
  (:import [java.io File StringWriter]))

(def timeout 4000)

(defmacro with-security [& body]
  `(do
     (System/setProperty "java.security.policy"
                        (-> "eval_soup/java.policy" io/resource .toString))
     (System/setSecurityManager
       (proxy [SecurityManager] []
         (checkExit [status#]
           (throw (SecurityException. "Exit not allowed.")))))
     (try ~@body
       (finally (System/setSecurityManager nil)))))

(defn eval-form-safely [form nspace]
  (with-security
    (thunk-timeout
      (fn []
        (binding [*ns* nspace
                  *out* (StringWriter.)]
          (refer-clojure)
          [(eval form)
           (if (and (coll? form) (= 'ns (first form)))
             (-> form second create-ns)
             *ns*)]))
      timeout)))

(defn eval-form [form-str nspace]
  (binding [*read-eval* false]
    (try
      (eval-form-safely (read-string form-str) nspace)
      (catch Exception e [e nspace]))))

(defn code->results [forms]
  (loop [forms forms
         results []
         nspace (create-ns 'clj.user)]
    (if-let [form (first forms)]
      (let [[result current-ns] (eval-form form nspace)]
        (recur (rest forms) (conj results result) current-ns))
      results)))

