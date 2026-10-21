;; One test namespace of the KIT, run in the review sandbox from harness/: `bb run-one.clj <ns>`.
;; bb.edn's :paths give the source; the tests are added here, as `bb test` adds them.
(babashka.classpath/add-classpath "test")
(require '[clojure.test :as t])
(let [n (symbol (first *command-line-args*))]
  (require n)
  (System/exit (if (t/successful? (t/run-tests n)) 0 1)))
