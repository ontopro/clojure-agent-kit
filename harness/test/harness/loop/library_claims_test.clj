(ns harness.loop.library-claims-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.loop.library-claims :as lc]))

(deftest a-function-named-by-its-namespace-is-a-library-claim
  (is (= '[clojure.string/trim] (lc/named-functions "the reviewer says clojure.string/trim keeps U+2003")))
  (is (= '[str/blank?] (lc/named-functions "amended to `str/blank?` instead")) "an alias, quoted")
  (is (= '[str/trim] (lc/named-functions "(str/trim \"\\u2003\")")) "an alias, in a form")
  (is (= '[ring.util.codec/form-decode clojure.string/trim]
         (lc/named-functions "ring.util.codec/form-decode drops it, and clojure.string/trim; again clojure.string/trim"))
      "each once, in order"))

(deftest a-path-or-prose-is-not-one
  (is (= [] (lc/named-functions "see src/app/views/search.clj and work/runs/")) "a path")
  (is (= [] (lc/named-functions "either and/or, read/write")) "prose: no form, no quote, no dotted namespace")
  (is (= [] (lc/named-functions "the target says nothing about a library"))))

(deftest a-claim-with-its-result-is-silent
  (let [shown "(clojure.string/trim \"\\u2003\") => \"\""]
    (is (lc/evaluated? shown))
    (is (= [] (lc/unevaluated shown)))
    (is (nil? (lc/warning shown 7888))))
  (is (nil? (lc/warning "no function here" nil))))

(deftest an-unevaluated-claim-is-said-with-the-port-or-how-to-start-one
  (let [w (lc/warning "clojure.string/trim keeps U+2003" 7888)]
    (is (str/starts-with? w "the reason names clojure.string/trim and shows no evaluation - "))
    (is (str/includes? w lc/rule))
    (is (str/includes? w "clj-nrepl-eval -p 7888 '(clojure.string/trim ...)'")))
  (testing "no .nrepl-port: how to start the Architect's REPL (the person, 2026-10-27)"
    (is (str/includes? (lc/warning "clojure.string/trim keeps it" nil) "start its REPL there with clojure -M:test:nrepl")))
  (testing "warn! prints it and returns the functions; never throws"
    (let [out (with-out-str (is (= '[clojure.string/trim] (lc/warn! "clojure.string/trim keeps it" nil))))]
      (is (str/starts-with? out "  note: the reason names clojure.string/trim")))
    (is (= "" (with-out-str (is (= [] (lc/warn! "nothing" nil))))))))
