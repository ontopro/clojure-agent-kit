(ns harness.models.review-tools-test
  "The security reviewer's tools, tried on a small temporary tree. The tools that start a
  process take a sandbox, which these tests replace with a recording stand-in."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.models.review-tools :as rt]
   [harness.models.tools :as tools]))

(defn- tree!
  "A temporary folder holding `files` (path -> text); its path."
  [files]
  (let [dir (str (fs/create-temp-dir {:prefix "kit-review-tools"}))]
    (doseq [[rel text] files]
      (fs/create-dirs (fs/parent (fs/path dir rel)))
      (spit (str (fs/path dir rel)) text))
    dir))

(defn- call
  "Run tool `nm` with `args` in `dir`, or in the context map given in its place."
  [dir-or-ctx nm args]
  (let [ctx (if (map? dir-or-ctx) dir-or-ctx {:dir dir-or-ctx})]
    (tools/invoke rt/specs ctx {:id "c1" :name nm :args args})))

(deftest the-reviewer-has-its-own-registry-and-the-coders-is-untouched
  (is (= #{"read_file" "search"} (set (keys rt/specs))) "grows as the tools are added")
  (is (= #{"read_file" "write_file" "edit_file" "nrepl_eval" "note"} (set (keys tools/specs)))
      "no review tool leaks into the coder's registry")
  (is (nil? (get rt/specs "nrepl_eval")) "a reviewer has no REPL"))

(deftest a-registry-is-declared-to-a-model-in-either-shape
  (let [names (set (keys rt/specs))]
    (is (= (sort names) (map #(get-in % [:function :name]) (tools/declarations :openai names rt/specs))))
    (is (= (sort names) (map :name (tools/declarations :anthropic names rt/specs))))))

(deftest search-finds-lines-and-names-the-file-and-number
  (let [dir (tree! {"src/app/a.clj" "(ns app.a)\n(defn find-note [id] id)\n"
                    "src/app/b.clj" "(ns app.b)\n(find-note 1)\n"
                    "README.md" "nothing here\n"})
        r (call dir "search" {:pattern "find-note"})]
    (is (false? (:error? r)))
    (is (= ["src/app/a.clj:2: (defn find-note [id] id)" "src/app/b.clj:2: (find-note 1)"]
           (str/split-lines (:content r))))
    (testing "below a path"
      (is (= ["src/app/b.clj:2: (find-note 1)"]
             (str/split-lines (:content (call dir "search" {:pattern "find-note" :path "src/app/b.clj"}))))))
    (testing "a pattern is a regular expression"
      (is (= 2 (count (str/split-lines (:content (call dir "search" {:pattern "\\(find-note|\\[id\\]"})))))))
    (testing "nothing matching says so, and is not an error"
      (let [r (call dir "search" {:pattern "absent-thing"})]
        (is (false? (:error? r)))
        (is (str/includes? (:content r) "no line matches"))))))

(deftest search-skips-build-output-and-binary-files
  (let [dir (tree! {"src/a.clj" "needle\n"
                    "target/classes/a.clj" "needle\n"
                    ".git/config" "needle\n"
                    "node_modules/x/index.js" "needle\n"})]
    (spit (str (fs/path dir "image.png")) (str "needle" (char 0) "needle"))
    (is (= ["src/a.clj:1: needle"] (str/split-lines (:content (call dir "search" {:pattern "needle"})))))))

(deftest search-is-bounded
  (let [dir (tree! {"big.txt" (str/join "\n" (repeat 250 "match"))
                    "long.txt" (str "match " (apply str (repeat 1000 "x")))})
        r (call dir "search" {:pattern "match"})
        lines (str/split-lines (:content r))]
    (is (= (inc rt/max-matches) (count lines)) "the matches, then the line saying it stopped")
    (is (str/includes? (last lines) (str "stopped at " rt/max-matches " matches")))
    (is (every? #(<= (count %) (+ rt/max-line 80)) lines) "a long line is cut")))

(deftest search-refuses-what-is-not-a-search
  (let [dir (tree! {"a.clj" "x\n"})]
    (is (:error? (call dir "search" {})))
    (is (str/includes? (:content (call dir "search" {:pattern "("})) "not a valid regular expression"))
    (is (str/includes? (:content (call dir "search" {:pattern "x" :path "../.."})) "outside the workspace"))
    (is (str/includes? (:content (call dir "search" {:pattern "x" :path "nope"})) "does not exist"))))
