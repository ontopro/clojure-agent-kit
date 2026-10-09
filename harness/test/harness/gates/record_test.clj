(ns harness.gates.record-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.record :as record]))

(defn- git! [dir & args]
  (apply p/shell {:dir dir :out :string :err :string} "git" args))

(defn- repo-with-one-commit []
  (let [dir (str (fs/create-temp-dir))]
    (git! dir "init" "-q")
    (git! dir "config" "user.email" "t@example.com")
    (git! dir "config" "user.name" "T")
    (spit (str (fs/path dir ".gitignore")) ".local/\n")
    (spit (str (fs/path dir "a.txt")) "a\n")
    (git! dir "add" "-A")
    (git! dir "commit" "-qm" "init")
    dir))

(deftest the-working-tree-is-what-git-add-all-would-commit
  (let [dir (repo-with-one-commit)
        committed (str/trim (:out (git! dir "rev-parse" "HEAD^{tree}")))]
    (testing "a clean tree is HEAD's tree, and the staged tree is too"
      (is (= committed (record/working-tree dir) (record/staged-tree dir))))
    (testing "an untracked file counts; an ignored one does not; the real index is untouched"
      (spit (str (fs/path dir "b.txt")) "b\n")
      (fs/create-dirs (fs/path dir ".local"))
      (spit (str (fs/path dir ".local" "x")) "x\n")
      (let [wt (record/working-tree dir)]
        (is (not= committed wt))
        (is (= committed (record/staged-tree dir)))
        (git! dir "add" "b.txt")
        (is (= wt (record/staged-tree dir)))))
    (testing "a deleted file counts"
      (fs/delete (fs/path dir "a.txt"))
      (is (not= (record/staged-tree dir) (record/working-tree dir))))))

(deftest outcome-stops-at-the-first-throw
  (let [ran (atom [])
        step (fn [n] [n (fn [] (swap! ran conj n))])]
    (testing "all green"
      (is (= {:exit 0} (record/outcome [(step "a") (step "b")])))
      (is (= ["a" "b"] @ran)))
    (testing "a shell's non-zero exit, the step named, later steps not run"
      (reset! ran [])
      (is (= {:exit 3 :step "lint"}
             (record/outcome [(step "a")
                              ["lint" (fn [] (throw (ex-info "boom" {:exit 3})))]
                              (step "c")])))
      (is (= ["a"] @ran)))
    (testing "failed tests carried by name"
      (is (= {:exit 1 :step "test" :failed-tests ["x-test/y"]}
             (record/outcome [["test" (fn [] (throw (ex-info "t" {:babashka/exit 1
                                                                  :failed-tests ["x-test/y"]})))]]))))
    (testing "an exception with no data is exit 1 with its message"
      (let [o (record/outcome [["fmt" (fn [] (throw (RuntimeException. "nope")))]])]
        (is (= [1 "fmt"] [(:exit o) (:step o)]))
        (is (str/includes? (:error o) "nope"))))))

(deftest refusal-reads-the-record-against-the-staged-tree
  (let [green {:exit 0 :tree "abcdef1234" :tree-after "abcdef1234"}]
    (is (nil? (record/refusal green "abcdef1234")))
    (is (str/includes? (record/refusal nil "abcdef1234") "no gates record"))
    (is (str/includes? (record/refusal (assoc green :exit 1 :step "test" :failed-tests ["a/b"]) "abcdef1234")
                       "red, at test - a/b"))
    (is (str/includes? (record/refusal (assoc green :tree-after "0000000000") "abcdef1234")
                       "changed while the gates ran"))
    (is (str/includes? (record/refusal green "1234567890")
                       "gates ran on tree abcdef1 and this commit is tree 1234567"))))

(deftest flaky-is-a-failure-then-a-pass-on-the-same-tree
  (let [run (fn [started tree & {:keys [exit step failed]}]
              (cond-> {:started started :tree tree :exit (or exit 0)}
                step (assoc :step step)
                failed (assoc :failed-tests failed)))
        red-a (run "2026-10-09T10:00:00Z" "T1" :exit 1 :step "test" :failed ["a/x" "a/y"])
        green (run "2026-10-09T10:05:00Z" "T1")]
    (testing "failed, then passed on the same tree"
      (is (= [{:test "a/x" :tree "T1" :failed (:started red-a) :passed (:started green)}
              {:test "a/y" :tree "T1" :failed (:started red-a) :passed (:started green)}]
             (record/flaky [green red-a]))))
    (testing "a pass on another tree is a fix, not a flake"
      (is (empty? (record/flaky [red-a (run "2026-10-09T10:05:00Z" "T2")]))))
    (testing "a later run red before the tests proves nothing"
      (is (empty? (record/flaky [red-a (run "2026-10-09T10:05:00Z" "T1" :exit 1 :step "lint")]))))
    (testing "still failing later is not flaky; another test passing is"
      (is (= ["a/y"] (map :test (record/flaky [red-a (run "2026-10-09T10:05:00Z" "T1" :exit 1 :step "test" :failed ["a/x"])])))))
    (testing "a pass BEFORE the failure is not counted"
      (is (empty? (record/flaky [(run "2026-10-09T09:00:00Z" "T1") red-a]))))
    (testing "the lines printed after the run that passed"
      (is (= ["flaky: a/x failed earlier on this tree and passed in this run"
              "flaky: a/y failed earlier on this tree and passed in this run"]
             (record/flaky-now green [red-a green])))
      (is (empty? (record/flaky-now (run "2026-10-09T10:06:00Z" "T2") [red-a green]))))))

(deftest code-is-committed-with-a-document
  (testing "no code staged: nothing asked"
    (is (nil? (record/document-refusal ["README.md" "harness/health/records/macos.edn" "harness/test/x_test.clj"]))))
  (testing "code with DEVLOG.md or NOTES.md: carried"
    (is (nil? (record/document-refusal ["harness/src/harness/a.clj" "DEVLOG.md"])))
    (is (nil? (record/document-refusal ["skills/plan/SKILL.md" "NOTES.md"]))))
  (testing "code alone, each kind of path: refused, the paths named"
    (doseq [p ["harness/src/harness/a.clj" "tools/security/check.bb" "skills/plan/SKILL.md" "plan-template/docs/01.md"]]
      (is (str/includes? (str (record/document-refusal [p])) p))))
  (testing "a document only counts at the root"
    (is (some? (record/document-refusal ["tools/x.bb" "tools/DEVLOG.md"]))))
  (testing "a long list is cut at three"
    (is (str/includes? (record/document-refusal (map #(str "tools/f" % ".bb") (range 5))) "and 2 more"))))

(deftest staged-paths-are-what-the-commit-changes
  (let [dir (repo-with-one-commit)]
    (spit (str (fs/path dir "b c.txt")) "b\n")
    (spit (str (fs/path dir "a.txt")) "changed\n")
    (is (empty? (record/staged-paths dir)))
    (git! dir "add" "-A")
    (is (= #{"a.txt" "b c.txt"} (set (record/staged-paths dir))))))

(deftest a-staged-notes-bumps-its-stamp
  (let [notes (fn [stamp] (str "# Notes\n\n" stamp " (row 1: something)**\n\nbody\n"))
        at (fn [s] (java.time.LocalDateTime/parse s))
        now (at "2026-10-09T10:30:41")
        head (notes "**Updated 2026-10-08 23:32")]
    (is (= (at "2026-10-08T23:32") (record/stamp head)))
    (testing "bumped, to now or before: carried"
      (is (nil? (record/stamp-refusal head (notes "**Updated 2026-10-09 10:30") now)))
      (is (nil? (record/stamp-refusal head (notes "**Updated 2026-10-09 09:00") now))))
    (testing "a new NOTES.md needs only a stamp not in the future"
      (is (nil? (record/stamp-refusal nil (notes "**Updated 2026-10-09 10:00") now))))
    (testing "the same stamp, or an older one: not bumped"
      (is (str/includes? (record/stamp-refusal head head now) "not bumped"))
      (is (str/includes? (record/stamp-refusal head (notes "**Updated 2026-10-01 10:00") now) "not bumped")))
    (testing "later than the clock"
      (is (str/includes? (record/stamp-refusal head (notes "**Updated 2026-10-09 10:31") now) "later than the clock")))
    (testing "no stamp at all"
      (is (str/includes? (record/stamp-refusal head "# Notes\nUpdated 2026-10-09 10:00\n" now) "no `**Updated")))))
