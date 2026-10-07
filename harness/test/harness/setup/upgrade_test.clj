(ns harness.setup.upgrade-test
  "What a pulled KIT expects that a workspace lacks: the lines, pure over
  facts; then the facts read from a real workspace against this clone."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.skills :as skills]
   [harness.setup.upgrade :as upgrade]
   [harness.setup.workspace :as workspace]))

(def kit-dir (str (fs/normalize (fs/absolutize ".."))))

(def complete
  "A workspace with every key, made at the KIT's current commit, nothing changed."
  {:dir "/w" :kit "/k" :keys-present upgrade/expected-keys :made-at "abc" :head "abc" :behind 0
   :template-changed [] :guidance-changed [] :mirrors-drifted [] :profile "/w/xyx-build/profile.edn"
   :profile-missing []})

(deftest a-complete-workspace-at-the-kits-commit-is-told-so-and-nothing-else
  (is (= ["made at KIT commit abc, which is where the KIT is"] (upgrade/expectations complete)))
  (is (str/includes? (upgrade/render complete) "Workspace /w\n  - made at KIT commit abc")))

(deftest the-lines-name-what-is-expected-what-is-here-and-the-command-that-shows-it
  (testing "commits later, and the changes since"
    (let [lines (upgrade/expectations (assoc complete :head "def" :behind 3
                                             :template-changed ["plan-template/01-requirements.md"]
                                             :guidance-changed [:data-conventions]))]
      (is (= "made at KIT commit abc; the KIT is 3 commits later, at def" (first lines)))
      (is (some #(str/includes? % "the plan template changed since: plan-template/01-requirements.md") lines))
      (is (some #(str/includes? % "git -C /k diff abc HEAD -- plan-template/") lines) "the command that shows it")
      (is (some #(str/includes? % "placeholder rule :data-conventions changed since this workspace filled it") lines))))
  (testing "a commit this clone does not have"
    (is (str/includes? (first (upgrade/expectations (assoc complete :behind nil)))
                       "does not have: another KIT, or a commit not yet pulled")))
  (testing "the version beside the commit, where a tag names one; nothing where none does"
    (is (= "made at KIT commit abc (version 0.6.0); the KIT is 3 commits later, at def (version 0.7.0)"
           (first (upgrade/expectations (assoc complete :head "def" :behind 3 :made-version "0.6.0" :head-version "0.7.0")))))
    (is (= "made at KIT commit abc, which is where the KIT is" (first (upgrade/expectations complete)))
        "a workspace from before the versions, against a clone with no tag: the line as it was"))
  (testing "no commit recorded: the line says how to add it, and nothing claims what changed"
    (let [lines (upgrade/expectations (assoc complete :made-at nil :behind nil
                                             :keys-present (remove #{:workspace/kit-commit} upgrade/expected-keys)))]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) ":workspace/kit-commit \"<the KIT commit this workspace was made at>\""))))
  (testing "a renamed key is named with its replacement, and not also reported missing"
    (let [lines (upgrade/expectations (assoc complete :keys-present (-> (set upgrade/expected-keys)
                                                                        (disj :workspace/build)
                                                                        (conj :workspace/plan))))]
      (is (some #(str/includes? % ":workspace/plan, which the harness no longer reads: the key is :workspace/build now") lines))
      (is (not (some #(str/includes? % "has no :workspace/build") lines)))))
  (testing "a missing key, a drifted mirror, a profile without a role"
    (let [lines (upgrade/expectations (assoc complete :keys-present (remove #{:workspace/records} upgrade/expected-keys)
                                             :mirrors-drifted ["/w/xyx-app/AGENTS.md"]
                                             :profile-missing [:plan-reviewer]))]
      (is (some #(str/includes? % "has no :workspace/records") lines))
      (is (some #(str/includes? % "/w/xyx-app/AGENTS.md does not match the rule source: run `bb rules-sync`") lines))
      (is (some #(str/includes? % "/w/xyx-build/profile.edn has no :plan-reviewer") lines))))
  (testing "a skill the workspace lacks, and one it edited"
    (let [lines (upgrade/expectations (assoc complete :skills-missing ["scoping" "plan"] :skills-drifted ["stage-end"]))]
      (is (some #(str/includes? % ".claude/skills/ lacks the KIT's scoping, plan skills (bb init writes them since 2026-10-07): run `bb skills-sync`") lines))
      (is (some #(str/includes? % ".claude/skills/stage-end/SKILL.md is not the rendering of the KIT's skills/stage-end/SKILL.md: run `bb skills-sync`") lines))))
  (testing "nothing missing renders as such"
    (is (str/includes? (upgrade/render (assoc complete :made-at nil :behind nil
                                              :keys-present (remove #{:workspace/kit-commit} upgrade/expected-keys)))
                       "workspace.edn records no :workspace/kit-commit"))))

(deftest the-facts-are-read-from-a-workspace-against-this-clone
  ;; A workspace made at this clone's HEAD, with the shipped profile copied in and no mirror:
  ;; nothing has changed since, the profile has every role, and the report says so.
  (let [ws (str (fs/real-path (fs/create-temp-dir)))
        head (str/trim (:out (p/shell {:dir kit-dir :out :string} "git" "rev-parse" "HEAD")))]
    (fs/create-dirs (fs/path ws "xyx-build"))
    (fs/copy (fs/path kit-dir "harness" "resources" "profiles" "claude.edn") (fs/path ws "xyx-build" "profile.edn"))
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/kit "k" :workspace/app "xyx-app" :workspace/plan "xyx-build" :workspace/work "work"
                   :workspace/rule-mirrors [] :workspace/rules-overlay "xyx-build/rules.edn"
                   :workspace/records "xyx-build/runs" :workspace/run-tables "xyx-build/RUNS.md"
                   :workspace/kit-commit head}))
    ;; the old key, so the lookup finds no build folder; the profile is found through
    ;; :workspace/build, which this file lacks on purpose
    (let [found (workspace/find-workspace ws)
          f (upgrade/facts found kit-dir)]
      (is (= head (:made-at f)))
      (is (= 0 (:behind f)) "made at HEAD: nothing is later")
      (is (= [] (:template-changed f)))
      (is (= [] (:guidance-changed f)))
      (is (= [] (:mirrors-drifted f)))
      (is (nil? (:profile f)) "no :workspace/build, so no profile is found - which the renamed-key line explains")
      (let [lines (upgrade/expectations f)]
        (is (some #(str/includes? % "which is where the KIT is") lines))
        (is (some #(str/includes? % ":workspace/plan, which the harness no longer reads") lines))))
    (testing "with the key renamed, the profile is found and has every role"
      (spit (str (fs/path ws "workspace.edn"))
            (pr-str {:workspace/kit "k" :workspace/app "xyx-app" :workspace/build "xyx-build" :workspace/work "work"
                     :workspace/rule-mirrors [] :workspace/rules-overlay "xyx-build/rules.edn"
                     :workspace/records "xyx-build/runs" :workspace/run-tables "xyx-build/RUNS.md"
                     :workspace/kit-commit head}))
      (let [f (upgrade/facts (workspace/find-workspace ws) kit-dir)]
        (is (str/ends-with? (:profile f) "profile.edn"))
        (is (= [] (:profile-missing f)))
        (is (= (skills/shipped kit-dir) (:skills-missing f)) "no .claude/skills/ yet: every shipped skill is missing")
        (is (some #(str/includes? % ".claude/skills/ lacks the KIT's") (upgrade/expectations f))))
      (testing "with the skills rendered, one line: at the KIT's commit"
        (skills/sync! kit-dir ws :commit head)
        (let [f (upgrade/facts (workspace/find-workspace ws) kit-dir)]
          (is (= [] (:skills-missing f)))
          (is (= [] (:skills-drifted f)))
          (is (= ["made at KIT commit "] (map #(subs % 0 19) (upgrade/expectations f))))
          (spit (str (fs/path ws ".claude" "skills" "plan" "SKILL.md")) "edited\n")
          (is (= ["plan"] (:skills-drifted (upgrade/facts (workspace/find-workspace ws) kit-dir))) "an edited copy drifts"))))))
