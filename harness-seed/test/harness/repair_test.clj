(ns harness.repair-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.repair :as repair]))

(defn- git! [dir & args]
  (apply p/shell {:dir dir :out :string :err :string} "git" args))

(defn- repo-with-one-commit
  "A throwaway repo holding one committed Clojure file, so `diff HEAD` has a
  HEAD to diff against."
  []
  (let [dir (str (fs/create-temp-dir))]
    (git! dir "init" "-q")
    (git! dir "config" "user.email" "t@example.com")
    (git! dir "config" "user.name" "T")
    (spit (str (fs/path dir "tracked.clj")) "(ns tracked)\n")
    (git! dir "add" "-A")
    (git! dir "commit" "-qm" "init")
    dir))

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

(deftest finds-changed-and-untracked-clojure-files
  (let [dir (repo-with-one-commit)]
    (spit (str (fs/path dir "tracked.clj")) "(ns tracked)\n(def a 1)\n")
    (spit (str (fs/path dir "fresh.clj")) "(ns fresh)\n")
    (spit (str (fs/path dir "notes.md")) "not clojure\n")
    (let [found (repair/changed-clojure-files dir)]
      (testing "a modified tracked file is found"
        (is (some #{"tracked.clj"} found)))
      (testing "an UNTRACKED namespace is found — git diff alone would miss it"
        (is (some #{"fresh.clj"} found)))
      (testing "non-Clojure changes are ignored"
        (is (not (some #{"notes.md"} found))))))
  (testing "a deleted file is not reported — :missing should mean something"
    (let [dir (repo-with-one-commit)]
      (fs/delete (fs/path dir "tracked.clj"))
      (is (= [] (repair/changed-clojure-files dir)))))
  (testing "a clean tree has nothing to repair"
    (is (= [] (repair/changed-clojure-files (repo-with-one-commit))))))

(deftest repo-root-locates-the-work-tree
  (let [dir (repo-with-one-commit)
        nested (fs/create-dirs (fs/path dir "src" "deep"))]
    (testing "found from a subdirectory, not just the root"
      (is (= (fs/real-path dir) (fs/real-path (repair/repo-root (str nested))))))
    (testing "no -z leaks into the path — rev-parse echoes it back as a line"
      (is (not (str/includes? (repair/repo-root dir) "-z")))))
  (testing "outside a work tree there is no root, and no exception"
    (is (nil? (repair/repo-root (str (fs/create-temp-dir)))))))

(deftest cljfmt-runs-under-each-sub-project-s-own-config
  ;; THE CASE THAT WAS MISSING. cljfmt reads .cljfmt.edn from the PROCESS
  ;; directory, so one call from a repository root formats every sub-project
  ;; with the root's config — which in this repo is none. `bb repair` reported
  ;; success and `bb gates` then failed on the file it had just repaired.
  ;;
  ;; Two sub-projects, only one configured, so the test fails if the grouping
  ;; collapses in EITHER direction: no sorting where it was asked for, or
  ;; sorting where it was not.
  (let [root (str (fs/create-temp-dir))
        unsorted "(ns a\n  (:require\n   [clojure.string :as str]\n   [clojure.set :as set]))\n"
        mk (fn [proj cfg?]
             (fs/create-dirs (fs/path root proj "src"))
             (when cfg?
               (spit (str (fs/path root proj ".cljfmt.edn")) "{:sort-ns-references? true}\n"))
             (spit (str (fs/path root proj "src" "a.clj")) unsorted)
             (str proj "/src/a.clj"))
        configured (mk "tidy" true)
        bare (mk "plain" false)]
    (repair/repair! root [configured bare])
    (let [tidy (slurp (str (fs/path root configured)))
          plain (slurp (str (fs/path root bare)))]
      (is (< (str/index-of tidy "clojure.set") (str/index-of tidy "clojure.string"))
          "the configured sub-project got its own rules, from a repo root with none")
      (is (< (str/index-of plain "clojure.string") (str/index-of plain "clojure.set"))
          "and the unconfigured one was not given rules it never asked for"))))

(deftest after-gate-0-the-fmt-gate-finds-nothing
  ;; THE INVARIANT, TESTED DIRECTLY RATHER THAN INFERRED. This namespace
  ;; claims a well-placed gate 0 makes gate 1 nearly a no-op, and the earlier
  ;; version of that claim was checked by asserting where a config file was
  ;; found — which is a fact about our search, not about whether the two
  ;; agree. Run `cljfmt check` the way the fmt gate does and see.
  (let [root (str (fs/create-temp-dir))
        proj (fs/path root "proj")]
    (fs/create-dirs (fs/path proj "src"))
    (spit (str (fs/path proj ".cljfmt.edn")) "{:sort-ns-references? true}\n")
    (spit (str (fs/path proj "src" "a.clj"))
          "(ns a\n  (:require\n   [clojure.string :as str]\n   [clojure.set :as set]))\n")
    ;; gate 0 from the REPOSITORY root, which has no config of its own
    (repair/repair! root ["proj/src/a.clj"])
    (let [{:keys [exit]} (p/shell {:dir (str proj) :out :string :err :string
                                   :continue true}
                                  "cljfmt" "check" "src")]
      (is (zero? exit)
          "the fmt gate, run the way bb gates runs it, has nothing left to say"))))

(deftest the-config-filename-is-cljfmt-s-business-not-ours
  ;; cljfmt accepts `.cljfmt.edn` AND `cljfmt.edn`. A hand-rolled search for
  ;; the dotted name alone missed the other and reintroduced the exact bug it
  ;; had just fixed, under a different filename. Delegating to cljfmt's own
  ;; search is what makes this test possible to write at all.
  (doseq [nm [".cljfmt.edn" "cljfmt.edn"]]
    (let [root (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path root "proj" "src"))
      (spit (str (fs/path root "proj" nm)) "{:sort-ns-references? true}\n")
      (spit (str (fs/path root "proj" "src" "a.clj"))
            "(ns a\n  (:require\n   [clojure.string :as str]\n   [clojure.set :as set]))\n")
      (repair/repair! root ["proj/src/a.clj"])
      (let [out (slurp (str (fs/path root "proj" "src" "a.clj")))]
        (is (< (str/index-of out "clojure.set") (str/index-of out "clojure.string"))
            (str nm " was not honoured"))))))

(deftest gate-0-sorts-ns-requires-so-the-fmt-gate-does-not-have-to
  ;; Two runs lost a cycle to a model writing `:require` forms out of
  ;; alphabetical order — D5's Coder and run 5's Tester. Neither is a
  ;; judgement worth a model round trip, and this namespace claims a
  ;; well-placed gate 0 makes gate 1 nearly a no-op.
  (let [dir (str (fs/create-temp-dir))
        f "src/app/svc.clj"]
    (fs/create-dirs (fs/path dir "src" "app"))
    ;; The config goes in the workspace, because cljfmt reads it from there
    ;; and sorting is not a default. A fixture without it would pass on a
    ;; gate 0 that never formatted anything.
    (spit (str (fs/path dir ".cljfmt.edn")) "{:sort-ns-references? true}\n")
    (spit (str (fs/path dir f))
          "(ns app.svc\n  (:require\n   [clojure.string :as str]\n   [clojure.set :as set]))\n")
    (repair/repair! dir [f])
    (let [out (slurp (str (fs/path dir f)))]
      (is (< (str/index-of out "clojure.set") (str/index-of out "clojure.string"))
          "cljfmt sorts them; gate 0 runs cljfmt so the fmt gate finds nothing"))))
