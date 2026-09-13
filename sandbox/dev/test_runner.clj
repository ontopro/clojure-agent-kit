(ns test-runner
  "Zero-dependency test runner: find the test namespaces, require them, run
  them, exit non-zero on failure.

  Hand-rolled rather than pulling in a test-runner dependency, for the same
  reason harness-seed's bb.edn does it this way — the sandbox should stay
  small enough to read in one sitting, and the exit code is the entire
  contract the gate runner cares about."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t]))

(defn test-namespaces
  "Namespace symbols for every `*_test.clj` under `test/`, sorted.

  Public so it can be checked from the REPL — a runner that silently finds
  zero namespaces and exits 0 is indistinguishable from a green suite."
  []
  (->> (file-seq (io/file "test"))
       (filter #(str/ends-with? (.getName %) "_test.clj"))
       (map #(-> (.getPath %)
                 (str/replace #"^test/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn -main [& _]
  (let [nses (test-namespaces)]
    (when (empty? nses)
      (println "test-runner: no *_test.clj found under test/ — refusing to report green")
      (System/exit 1))
    (apply require nses)
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (shutdown-agents)
      (System/exit (if (pos? (+ fail error)) 1 0)))))
