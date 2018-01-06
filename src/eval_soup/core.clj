(ns eval-soup.core
  (:require [clojure.java.io :as io]
            [eval-soup.clojail :refer [thunk-timeout]]
            [clojure.core.async :as async])
  (:import [java.io File StringWriter]))

(defn wrap-security
  "Returns a function that wraps the given function in a sandbox.
  It uses eval_soup/java.policy to define permissions. By default,
  it only disallows exiting via System/exit."
  [thunk]
  (fn []
    (System/setProperty "java.security.policy"
      (-> "eval_soup/java.policy" io/resource .toString))
    (System/setSecurityManager
      (proxy [SecurityManager] []
        (checkExit [status#]
          (throw (SecurityException. "Exit not allowed.")))))
    (try (thunk)
      (finally (System/setSecurityManager nil)))))

(defmacro with-security
  "Convenience macro that wraps the body with wrap-security
  and then immediately executes it."
  [& body]
  `(apply (wrap-security (fn [] ~@body)) []))

(defn wrap-timeout
  "Returns a function that wraps the given function in a timeout checker.
  The timeout is specified in milliseconds. If the timeout is reached,
  an exceptino will be thrown."
  [thunk timeout]
  (fn []
    (thunk-timeout thunk timeout)))

(defn ^:private eval-form [form nspace {:keys [timeout
                                               disable-timeout?
                                               disable-security?]}]
  (try
    (cond-> (fn []
              (binding [*ns* nspace
                        *out* (StringWriter.)]
                (refer-clojure)
                [(eval form)
                 (if (and (coll? form) (= 'ns (first form)))
                   (-> form second create-ns)
                   *ns*)]))
            (not disable-timeout?) (wrap-timeout timeout)
            (not disable-security?) (wrap-security)
            true (apply []))
    (catch Exception e [e nspace])))

(defn ^:private str->form [s]
  (if (string? s)
    (binding [*read-eval* false]
      (read-string s))
    s))

(def ^{:doc "Alias to core.async's `chan` meant for use inside a form
  you want to evaluate. See the example in `code->results` that uses it."}
  chan async/chan)

(def ^{:doc "Alias to core.async's `put!` meant for use inside a form
  you want to evaluate. See the example in `code->results` that uses it."}
  put! async/put!)

(def ^{:doc "Alias to core.async's `<!!` meant for use inside a form
  you want to evaluate. See the example in `code->results` that uses it."}
  <!! async/<!!)

(defn code->results
  "Returns a vector of the evaluated result of each of the given forms.
  If any of the forms are strings, it will read them first."
  ([forms]
   (code->results forms {}))
  ([forms {:keys [timeout
                  disable-timeout?
                  disable-security?]
           :or {timeout 4000
                disable-timeout? false
                disable-security? false}
           :as opts}]
   (let [opts {:timeout timeout
               :disable-timeout? disable-timeout?
               :disable-security? disable-security?}
         forms (mapv str->form forms)]
     (loop [forms forms
            results []
            nspace (create-ns 'clj.user)]
       (if-let [form (first forms)]
         (let [[result current-ns] (eval-form form nspace opts)]
           (recur (rest forms) (conj results result) current-ns))
         results)))))

