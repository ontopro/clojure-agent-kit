(ns harness.repair-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.repair :as repair]))

(deftest repairs-delimiters-and-reports-what-it-touched
  ;; Shells the real clj-paren-repair. On a machine without it, repair!
  ;; throws a named cause pointing at `bb doctor` rather than an
  ;; IOException from three frames down.
  (let [dir (str (fs/create-temp-dir))]
    (spit (str (fs/path dir "broken.clj")) "(defn foo [x]\n  (inc x)\n")
    (spit (str (fs/path dir "fine.clj")) "(defn bar [x]\n  (dec x))\n")
    (let [res (repair/repair! dir ["broken.clj" "fine.clj" "ghost.clj"])]
      (testing "existing files are repaired in place, missing ones reported"
        (is (= ["broken.clj" "fine.clj"] (:repaired res)))
        (is (= ["ghost.clj"] (:missing res)))
        (is (zero? (:exit res))))
      (testing "the unbalanced file is actually fixed"
        (is (str/includes? (slurp (str (fs/path dir "broken.clj"))) "(inc x))")))))
  (testing "nothing to repair is a no-op success, and shells nothing"
    (let [res (repair/repair! (str (fs/create-temp-dir)) ["nope.clj"])]
      (is (= [] (:repaired res)))
      (is (zero? (:exit res))))))

(deftest ensures-a-trailing-newline
  ;; cljfmt does not enforce one, and no later gate catches its absence —
  ;; only a Reviewer noticed, which is a frontier model doing a job a
  ;; two-line function can do for free.
  (let [dir (str (fs/create-temp-dir))]
    (spit (str (fs/path dir "no-newline.clj")) "(ns a)\n\n(def x 1)")
    (spit (str (fs/path dir "has-newline.clj")) "(ns b)\n\n(def y 2)\n")
    (spit (str (fs/path dir "empty.clj")) "")
    (let [res (repair/repair! dir ["no-newline.clj" "has-newline.clj" "empty.clj"])]
      (is (= ["no-newline.clj"] (:newlines res)) "only the file that needed it")
      (is (str/ends-with? (slurp (str (fs/path dir "no-newline.clj"))) "(def x 1)\n"))
      (testing "a file that already ends in a newline is untouched"
        (is (= "(ns b)\n\n(def y 2)\n" (slurp (str (fs/path dir "has-newline.clj"))))))
      (testing "an empty file stays empty — nothing was written to it"
        (is (= "" (slurp (str (fs/path dir "empty.clj")))))))))
