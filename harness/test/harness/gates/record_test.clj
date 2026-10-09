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
