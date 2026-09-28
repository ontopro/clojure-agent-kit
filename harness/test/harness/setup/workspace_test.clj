(ns harness.setup.workspace-test
  "The lookup a command runs through: walked up to, pointed at, or the run's."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.workspace :as workspace]))

(defn- workspace!
  "A workspace folder with a `workspace.edn` naming a plan and an overlay, and
  a folder that is in no workspace at all."
  []
  (let [ws (str (fs/real-path (fs/create-temp-dir)))]
    (fs/create-dirs (fs/path ws "xyx-plan"))
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/app "xyx-app" :workspace/plan "xyx-plan"
                   :workspace/rules-overlay "xyx-plan/rules.edn"}))
    {:ws ws :elsewhere (str (fs/real-path (fs/create-temp-dir)))}))

(deftest the-flag-is-taken-out-of-the-arguments-and-needs-a-value
  (is (= {:workspace "/w" :args ["--check" "a.md"]}
         (workspace/split-args ["--check" "--workspace" "/w" "a.md"])))
  (is (= {:workspace nil :args ["--check"]} (workspace/split-args ["--check"])))
  (is (= {:workspace nil :args []} (workspace/split-args nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a folder"
                        (workspace/split-args ["--workspace"]))))

(deftest pointed-at-is-the-flag-then-the-variable-and-is-pure
  (is (= {:dir "/f" :from :flag} (workspace/pointed-at ["--workspace" "/f"] "/e")) "the flag wins")
  (is (= {:dir "/e" :from :env} (workspace/pointed-at [] "/e")) "then KIT_WORKSPACE")
  (is (nil? (workspace/pointed-at [] nil)))
  (is (nil? (workspace/pointed-at ["a"] "  ")) "a blank variable is unset"))

(deftest a-command-finds-its-workspace-by-walking-up-or-by-being-pointed-at-it
  (let [{:keys [ws elsewhere]} (workspace!)]
    (testing "from inside, the walk-up as before"
      (let [found (workspace/current nil (str (fs/path ws "xyx-plan")))]
        (is (= ws (:workspace/dir found)))
        (is (= :walk-up (:workspace/from found)))))
    (testing "from outside, nothing - the KIT's own folder is in no workspace"
      (is (nil? (workspace/current nil elsewhere))))
    (testing "from outside, pointed at it by the flag: found, and said to be"
      (let [found (workspace/current ["--check" "--workspace" ws] elsewhere)]
        (is (= ws (:workspace/dir found)))
        (is (= :flag (:workspace/from found)))
        (is (= (str (fs/path ws "xyx-plan" "rules.edn")) (:workspace/rules-overlay found))
            "with its paths absolute, like the walk-up's"))
      (is (= ws (:workspace/dir (workspace/current ["--workspace" (str (fs/path ws "xyx-plan"))] elsewhere)))
          "a folder under the workspace names it too: the same walk-up, from there"))
    (testing "pointed at a folder that is in no workspace: a fault by name, not nil"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--workspace names .* and no workspace.edn"
                            (workspace/current ["--workspace" elsewhere] elsewhere)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"KIT_WORKSPACE names"
                            (workspace/named elsewhere :env))
          "the variable's message names the variable"))
    (testing "the run's workspace outranks everything, and says so"
      (binding [workspace/*of-run* (workspace/find-workspace ws)]
        (let [found (workspace/current ["--workspace" elsewhere] elsewhere)]
          (is (= ws (:workspace/dir found)))
          (is (= :run (:workspace/from found))))))))
