(ns harness.setup.template-upgrade-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.loop.driver :as driver]
   [harness.loop.log :as log]
   [harness.setup.app :as app]
   [harness.setup.init :as init]
   [harness.setup.template :as template]
   [harness.setup.template-upgrade :as tu]
   [harness.setup.workspace :as workspace]))

(def git-env
  {"GIT_AUTHOR_NAME" "harness-test" "GIT_AUTHOR_EMAIL" "harness@test"
   "GIT_COMMITTER_NAME" "harness-test" "GIT_COMMITTER_EMAIL" "harness@test"})

(def pin (template/pin (template/load-pins)))

(def new-pin
  "The pin moved: the same template at a later tag and commit."
  (assoc pin :git/tag "kit-v9.9" :git/sha "abcdef0123456789abcdef0123456789abcdef01"))

(def views-clj
  (str "(ns xyx.views)\n\n"
       "(defn base\n  \"Base component for html page.\"\n  [content]\n  [:html {:lang \"en\"} [:body content]])\n\n"
       "(def home-page\n  (base\n    [:h1 \"Welcome to \" [:span \"Clojure Stack Lite\"]]))\n"))

(def generated-files
  "What the template generates at the pin, as a test's stand-in for deps-new."
  {"deps.edn" "{:deps {}}\n" "src/xyx/core.clj" "(ns xyx.core)\n" "src/xyx/views.clj" views-clj})

(defn- write-tree! [target files]
  (doseq [[path content] files]
    (fs/create-dirs (fs/parent (fs/path target path)))
    (spit (str (fs/path target path)) content)))

(defn- generator
  "`app-fn`'s `:run`: the `clojure` argv writes `files` into its target; git runs for real."
  [files]
  (fn [dir env argv]
    (if (= "clojure" (first argv))
      (do (write-tree! (edn/read-string (last argv)) files) {:exit 0 :out ""})
      (#'app/run-process dir env argv))))

(defn- git-out [dir & args]
  (str/trim (:out (apply p/shell {:dir (str dir) :out :string :err :string :continue true :extra-env git-env} "git" args))))

(defn- workspace!
  "A workspace made as `bb init` makes it, the application generated from `generated-files`."
  []
  (let [kit-dir (str (fs/parent (fs/real-path ".")))
        ws-dir (str (fs/path (fs/real-path (fs/create-temp-dir)) "xyx"))
        lay (init/layout {:name "xyx" :kit-dir kit-dir :dir ws-dir
                          :build-docs (init/build-docs-files kit-dir)
                          :rule-mirrors ["xyx-app/AGENTS.md"]
                          :loop/defaults (:loop/defaults pin)})]
    (init/create! lay {:git-env git-env
                       :app-fn (app/app-fn pin {:app-name "xyx" :git-env git-env :run (generator generated-files)})})
    (workspace/find-workspace ws-dir)))

(defn- run-upgrade!
  "`tu/upgrade!` against `ws`, the new pin generating `files`, the gates `gates`."
  [ws files gates]
  (tu/upgrade! ws {:pin new-pin :env git-env :gates gates
                   :generate (fn [target] (fs/create-dirs target) (write-tree! target files))}))

(defn- state [ws id]
  (edn/read-string {:default tagged-literal} (slurp (str (fs/path (:workspace/work ws) "runs" id "state.edn")))))

(def id "template-upgrade-kit-v9.9")

;; ---------------------------------------------------------------------------

(deftest what-a-generated-commit-says-it-came-from
  (is (= {:template "io.github.ontopro/clojure-stack-lite" :tag "kit-v1.2" :sha7 "9ca4b7b"}
         (tu/generated-from "The application as generated, untouched: io.github.ontopro/clojure-stack-lite kit-v1.2 (9ca4b7b)")))
  (is (= {:template "io.github.ontopro/clojure-stack-lite" :local-root "/src/template"}
         (tu/generated-from "The application as generated, untouched: io.github.ontopro/clojure-stack-lite from the local clone /src/template - NOT the pinned commit")))
  (is (nil? (tu/generated-from "AGENTS.md from the KIT's rule source")))
  (is (nil? (tu/generated-from nil)))
  (testing "the pin as it stands is the same source; a local clone never is by name"
    (let [from (tu/generated-from (str app/untouched-subject (app/generation-label pin nil)))]
      (is (tu/same-source? from pin nil))
      (is (not (tu/same-source? from new-pin nil)))
      (is (not (tu/same-source? from pin "/src/template"))))))

(deftest the-runs-id-and-the-worktrees-states
  (is (= "template-upgrade-kit-v9.9" (tu/run-id new-pin nil)))
  (is (= "template-upgrade-local" (tu/run-id new-pin "/src/template")))
  (let [runs (fs/create-temp-dir)]
    (is (= "x" (tu/fresh-id runs "x")) "no run of that id")
    (fs/create-dirs (fs/path runs "x"))
    (spit (str (fs/path runs "x" "state.edn")) (pr-str {:events [{:event/kind :stopped}]}))
    (is (= "x" (tu/fresh-id runs "x")) "a run not merged is resumed, not renamed")
    (spit (str (fs/path runs "x" "state.edn")) (pr-str {:events [{:event/kind :merged}]}))
    (is (= "x-2" (tu/fresh-id runs "x")) "a merged run's id is taken"))
  (is (= :resolved (tu/worktree-state {:merge-head? true :conflicts [] :porcelain ""})) "every conflict staged, the merge not committed")
  (is (= :merging (tu/worktree-state {:merge-head? false :conflicts ["a.clj"] :porcelain "UU a.clj"})))
  (is (= :uncommitted (tu/worktree-state {:merge-head? false :conflicts [] :porcelain " M a.clj"})))
  (is (= :committed (tu/worktree-state {:merge-head? false :conflicts [] :porcelain ""})))
  (is (= "xyx" (tu/app-name-of "{xyx.core #{} xyx.views #{}}")))
  (is (nil? (tu/app-name-of "not edn {")))
  (is (= [[:fmt "bb fmt-check"] [:deps "bb --config /k/harness/bb.edn boundary"]]
         (tu/resolve-kit [[:fmt "bb fmt-check"] [:deps "bb --config {{kit}}/harness/bb.edn boundary"]] "/k"))))

(deftest bb-init-leaves-the-template-branch-at-the-untouched-tree
  (let [ws (workspace!)
        app (:workspace/app ws)]
    (is (= (git-out app "rev-list" "--max-parents=0" "HEAD") (git-out app "rev-parse" "template")))
    (is (= "2" (git-out app "rev-list" "--count" "main")))))

(deftest a-release-is-merged-through-the-template-branch-and-the-loops-merge
  (let [ws (workspace!)
        app (:workspace/app ws)
        work (:workspace/work ws)
        ;; the project worked on main since: its own commit
        _ (spit (str (fs/path app "src" "xyx" "core.clj")) "(ns xyx.core)\n(def project 1)\n")
        _ (git-out app "commit" "-q" "-am" "the project's own work")
        before (git-out app "rev-parse" "main")
        ;; the release changes deps.edn and adds a file; views.clj as the template had it
        release (assoc generated-files "deps.edn" "{:deps {:a 1}}\n" "resources/new.txt" "new\n")
        r (run-upgrade! ws release [[:fmt "true"] [:test "true"]])]
    (is (= :awaiting-merge (:outcome r)))
    (testing "template holds the new tree, as a second commit with the tag in its subject"
      (is (= "2" (git-out app "rev-list" "--count" "template")))
      (is (= (str app/untouched-subject (app/generation-label new-pin nil)) (git-out app "log" "-1" "--format=%s" "template")))
      (is (= #{"deps.edn" "src/xyx/core.clj" "src/xyx/views.clj" "resources/new.txt"}
             (set (str/split-lines (git-out app "ls-tree" "-r" "--name-only" "template")))))
      (is (= {:template (str (:template pin)) :tag "kit-v9.9" :sha7 "abcdef0"} (tu/generated-from (git-out app "log" "-1" "--format=%s" "template")))))
    (testing "the upgrade branch, off main, carries git's merge of template; main is untouched"
      (is (= before (git-out app "rev-parse" "main")))
      (let [wt (str (fs/path work "worktrees" id "app"))]
        (is (fs/exists? (fs/path wt "resources" "new.txt")))
        (is (= "1" (git-out wt "rev-list" "--count" "--merges" (str "main.." id)))
            "one merge commit past main")
        (is (str/includes? (slurp (str (fs/path wt "src" "xyx" "views.clj"))) "Clojure Agent Kit") "the KIT's page survives")))
    (testing "the run's state is the loop's: the gate session, the gates' steps, the stop"
      (let [s (state ws id)]
        (is (= :template-upgrade (:run/kind s)))
        (is (= id (get-in s [:sessions :gate :worktree/branch])))
        (is (= [:fmt :test] (mapv :step/name (:steps s))))
        (is (= [:template-commit :merge :gates :stopped] (mapv :event/kind (:events s))))
        (is (= :awaiting-merge (tu/last-stop s)))
        (is (= (mapv :event/kind (:events s)) (mapv :event/kind (log/read-log (str (fs/path work "runs" id "events.log"))))))))
    (testing "run again: already green, nothing changes"
      (let [r2 (run-upgrade! ws release [[:fmt "true"]])]
        (is (= :awaiting-merge (:outcome r2)))
        (is (= 4 (count (:events (state ws id)))))))
    (testing "bb run-loop merge takes it: --no-ff into main, the record, the worktree and the branch gone, template kept"
      (let [run-dir (str (fs/path work "runs" id))
            decision (str (fs/path run-dir "decision.edn"))]
        (spit decision (pr-str {:decision "the release's changes are wanted"}))
        (with-out-str (driver/merge! (driver/context run-dir) decision))
        (is (str/starts-with? (git-out app "log" "-1" "--format=%s" "main") "Merge branch"))
        (is (= "new\n" (slurp (str (fs/path app "resources" "new.txt")))) "main has the release")
        (is (fs/exists? (fs/path run-dir "run.edn")))
        (is (= :merged (:run/status (edn/read-string {:default tagged-literal} (slurp (str (fs/path run-dir "run.edn")))))))
        (is (not (fs/exists? (fs/path work "worktrees" id "app"))))
        (is (not= 0 (:exit (p/shell {:dir app :out :string :err :string :continue true} "git" "rev-parse" "-q" "--verify" (str "refs/heads/" id)))) "the branch is deleted")
        (is (= "2" (git-out app "rev-list" "--count" "template")) "template stays, the old side next time")))
    (testing "and at the pin now, there is nothing to upgrade to"
      (let [e (try (run-upgrade! ws release []) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (:nothing-to-upgrade (ex-data e)))
        (is (str/includes? (ex-message e) "kit-v9.9 (abcdef0) already"))))))

(deftest a-conflict-is-the-architects-and-the-run-resumes-after-the-resolution
  (let [ws (workspace!)
        work (:workspace/work ws)
        ;; the release changes the template's own home page - the form the KIT's second commit replaced
        release (assoc generated-files "src/xyx/views.clj" (str/replace views-clj "Clojure Stack Lite" "Clojure Stack Lite 2"))
        r (run-upgrade! ws release [[:fmt "true"]])
        wt (str (fs/path work "worktrees" id "app"))]
    (is (= :conflict (:outcome r)))
    (is (= ["src/xyx/views.clj"] (:conflicts r)))
    (is (str/includes? (slurp (str (fs/path wt "src" "xyx" "views.clj"))) "<<<<<<<") "the merge stands in the worktree")
    (let [s (state ws id)]
      (is (= :escalated (tu/last-stop s)))
      (is (= :architect (:route (last (:events s)))))
      (is (str/includes? (:reason (last (:events s))) "src/xyx/views.clj")))
    (testing "run again, unresolved: still the Architect's, no new events"
      (is (= :conflict (:outcome (run-upgrade! ws release [[:fmt "true"]]))))
      (is (= 3 (count (:events (state ws id))))))
    (testing "resolved and committed in the worktree, the gates run and the run awaits its merge"
      (spit (str (fs/path wt "src" "xyx" "views.clj")) (str "(ns xyx.views)\n(defn base [c] c)\n" (app/home-page "xyx" "0.6.5") "\n"))
      (git-out wt "add" "-A")
      (git-out wt "commit" "-q" "-m" "the template's page change against the KIT's page: the KIT's kept")
      (let [r3 (run-upgrade! ws release [[:fmt "true"]])]
        (is (= :awaiting-merge (:outcome r3)))
        (is (= [:template-commit :merge :stopped :resolved :gates :stopped] (mapv :event/kind (:events (state ws id)))))))
    (testing "a red gate stops for the Architect, and a further commit gates again"
      (git-out wt "commit" "-q" "--allow-empty" "-m" "another commit")
      (is (= :gates-red (:outcome (run-upgrade! ws release [[:test "false"]]))))
      (is (= :escalated (tu/last-stop (state ws id))))
      (is (= :awaiting-merge (:outcome (run-upgrade! ws release [[:test "true"]])))))
    (testing "the merge refuses an uncommitted worktree, by name"
      (spit (str (fs/path wt "deps.edn")) "{:deps {:b 2}}\n")
      (let [run-dir (str (fs/path work "runs" id))
            decision (str (fs/path run-dir "decision.edn"))
            _ (spit decision (pr-str {:decision "x"}))
            e (try (with-out-str (driver/merge! (driver/context run-dir) decision)) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :not-committed (:run-loop/error (ex-data e))))
        (git-out wt "checkout" "--" "deps.edn")))))

(deftest an-application-without-the-untouched-first-commit-is-refused-with-the-words
  (let [ws (workspace!)
        app (:workspace/app ws)]
    ;; an application made by hand: no template branch, and a history whose root is not the generated tree
    (fs/delete-tree app)
    (fs/create-dirs app)
    (git-out app "init" "-q" "-b" "main")
    (spit (str (fs/path app "deps.edn")) "{}")
    (git-out app "add" "-A")
    (git-out app "commit" "-q" "-m" "hand-made")
    (let [e (try (run-upgrade! ws generated-files []) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :refused (:template-upgrade/error (ex-data e))))
      (is (str/includes? (ex-message e) "is not the untouched generated tree"))
      (is (not= 0 (:exit (p/shell {:dir app :out :string :err :string :continue true} "git" "rev-parse" "-q" "--verify" "refs/heads/template")))))))

(deftest an-older-application-gets-the-branch-from-its-first-commit
  (let [ws (workspace!)
        app (:workspace/app ws)
        root (git-out app "rev-list" "--max-parents=0" "HEAD")]
    (git-out app "branch" "-D" "template")
    (let [r (run-upgrade! ws (assoc generated-files "resources/new.txt" "new\n") [[:fmt "true"]])]
      (is (= :awaiting-merge (:outcome r)))
      (is (= root (git-out app "rev-parse" "template~1")) "made at the first commit, then the release on it"))))

(deftest a-commit-after-the-gates-is-refused-at-the-merge-until-gated-again
  (let [ws (workspace!)
        work (:workspace/work ws)
        release (assoc generated-files "resources/new.txt" "new\n")
        wt (str (fs/path work "worktrees" id "app"))
        run-dir (str (fs/path work "runs" id))
        decision (str (fs/path run-dir "decision.edn"))]
    (is (= :awaiting-merge (:outcome (run-upgrade! ws release [[:fmt "true"]]))))
    (git-out wt "commit" "-q" "--allow-empty" "-m" "a commit after the gates")
    (spit decision (pr-str {:decision "x"}))
    (let [e (try (with-out-str (driver/merge! (driver/context run-dir) decision)) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :not-gated (:run-loop/error (ex-data e))))
      (is (str/includes? (ex-message e) "bb template-upgrade")))
    (is (= :awaiting-merge (:outcome (run-upgrade! ws release [[:fmt "true"]]))) "gated again")
    (with-out-str (driver/merge! (driver/context run-dir) decision))
    (is (str/starts-with? (git-out (:workspace/app ws) "log" "-1" "--format=%s" "main") "Merge branch"))))

(deftest a-template-tip-ahead-of-main-with-no-run-is-taken-without-generating
  ;; a run that failed between the template commit and its state, or a run directory deleted to
  ;; start over: template is ahead of main, and the release is still to take
  (let [ws (workspace!)
        app (:workspace/app ws)
        work (:workspace/work ws)
        release (assoc generated-files "resources/new.txt" "new\n")]
    (is (= :awaiting-merge (:outcome (run-upgrade! ws release [[:fmt "true"]]))))
    (fs/delete-tree (fs/path work "runs" id))
    (git-out app "worktree" "remove" "--force" (str (fs/path work "worktrees" id "app")))
    (git-out app "branch" "-D" id)
    (let [tip (git-out app "rev-parse" "template")
          r (tu/upgrade! ws {:pin new-pin :env git-env :gates [[:fmt "true"]]
                             :generate (fn [_] (throw (ex-info "generated again" {})))})]
      (is (= :awaiting-merge (:outcome r)))
      (is (= tip (git-out app "rev-parse" "template")) "template as it was")
      (is (= tip (:commit (first (:events (state ws id))))) "the run's template commit is the tip")
      (is (= "2" (git-out app "rev-list" "--count" "template"))))))

(deftest a-worktree-lost-mid-conflict-is-merged-again-not-gated-as-it-is
  (let [ws (workspace!)
        app (:workspace/app ws)
        work (:workspace/work ws)
        release (assoc generated-files "src/xyx/views.clj" (str/replace views-clj "Clojure Stack Lite" "Clojure Stack Lite 2"))
        wt (str (fs/path work "worktrees" id "app"))]
    (is (= :conflict (:outcome (run-upgrade! ws release [[:fmt "true"]]))))
    (git-out app "worktree" "remove" "--force" wt)
    (is (not (fs/exists? wt)))
    (let [r (run-upgrade! ws release [[:fmt "true"]])]
      (is (= :conflict (:outcome r)) "merged again, the conflict again - never the base gated as the upgrade")
      (is (= ["src/xyx/views.clj"] (:conflicts r)))
      (is (str/includes? (slurp (str (fs/path wt "src" "xyx" "views.clj"))) "<<<<<<<"))
      (is (= [:template-commit :merge :stopped :merge :stopped] (mapv :event/kind (:events (state ws id))))))))

(deftest a-merge-that-failed-because-main-moved-takes-the-newly-gated-tip-not-the-recorded-one
  (let [ws (workspace!)
        app (:workspace/app ws)
        work (:workspace/work ws)
        release (assoc generated-files "resources/new.txt" "the template's\n")
        wt (str (fs/path work "worktrees" id "app"))
        run-dir (str (fs/path work "runs" id))
        decision (str (fs/path run-dir "decision.edn"))
        merge! (fn [] (try (with-out-str (driver/merge! (driver/context run-dir) decision)) nil
                           (catch clojure.lang.ExceptionInfo e e)))]
    (is (= :awaiting-merge (:outcome (run-upgrade! ws release [[:fmt "true"]]))))
    ;; main moves during a long resolution: the same new file, other words
    (fs/create-dirs (fs/path app "resources"))
    (spit (str (fs/path app "resources" "new.txt")) "main's\n")
    (git-out app "add" "-A")
    (git-out app "commit" "-q" "-m" "main moved")
    (spit decision (pr-str {:decision "x"}))
    (testing "the first merge fails and is aborted; main is as it was"
      (is (= :merge-failed (:run-loop/error (ex-data (merge!)))))
      (is (= "main moved" (git-out app "log" "-1" "--format=%s" "main")))
      (is (= :merge-failed (:event/kind (last (:events (state ws id)))))))
    (testing "the Architect brings main in on the branch, gates again, and THAT tip merges"
      (is (not= 0 (:exit (p/shell {:dir wt :out :string :err :string :continue true :extra-env git-env} "git" "merge" "--no-edit" "main"))))
      (spit (str (fs/path wt "resources" "new.txt")) "both\n")
      (git-out wt "add" "-A")
      (git-out wt "commit" "-q" "-m" "main merged in, the file resolved")
      (is (= :not-gated (:run-loop/error (ex-data (merge!)))) "the new tip is ungated: refused, not the recorded commit merged by name")
      (is (= :awaiting-merge (:outcome (run-upgrade! ws release [[:fmt "true"]]))))
      (is (nil? (merge!)))
      (is (= "both\n" (slurp (str (fs/path app "resources" "new.txt")))) "main has the resolution")
      (is (= 2 (count (filter #(= :merge-commit (:event/kind %)) (:events (state ws id)))))
          "the second attempt recorded the tip it merged"))))

(deftest a-candidates-tree-equal-to-templates-unmerged-tip-is-taken-from-the-tip
  ;; item 16's candidate rounds on a re-run: the same local clone generates the same tree
  (let [ws (workspace!)
        app (:workspace/app ws)
        work (:workspace/work ws)
        release (assoc generated-files "resources/new.txt" "new\n")
        run (fn [generate] (tu/upgrade! ws {:pin pin :local-root "/src/template" :env git-env :gates [[:fmt "true"]] :generate generate}))
        write (fn [target] (fs/create-dirs target) (write-tree! target release))
        local-id "template-upgrade-local"]
    (is (= :awaiting-merge (:outcome (run write))))
    (is (= {:template (str (:template pin)) :local-root "/src/template"} (tu/generated-from (git-out app "log" "-1" "--format=%s" "template"))))
    (fs/delete-tree (fs/path work "runs" local-id))
    (git-out app "worktree" "remove" "--force" (str (fs/path work "worktrees" local-id "app")))
    (git-out app "branch" "-D" local-id)
    (let [tip (git-out app "rev-parse" "template")
          generated (atom 0)
          r (run (fn [target] (swap! generated inc) (write target)))]
      (is (= :awaiting-merge (:outcome r)))
      (is (= 1 @generated) "a candidate is generated: only its tree can say it is the tip's")
      (is (= tip (git-out app "rev-parse" "template")) "no third commit on template")
      (is (= tip (:commit (first (:events (state ws local-id)))))))
    (testing "and merged, the same candidate again is nothing to upgrade to"
      (let [run-dir (str (fs/path work "runs" local-id))
            decision (str (fs/path run-dir "decision.edn"))]
        (spit decision (pr-str {:decision "x"}))
        (with-out-str (driver/merge! (driver/context run-dir) decision))
        (let [e (try (run write) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (:nothing-to-upgrade (ex-data e)))
          (is (str/includes? (ex-message e) "and it is merged")))))))

(deftest bb-template-upgrade-resumes-an-upgrades-run-whatever-its-id-and-keeps-its-hook
  ;; `bb upgrade` names its run upgrade-<version> and asks for the rule mirror before the gates; after
  ;; a conflict the Architect resolves there and runs `bb template-upgrade`, which passes no id and
  ;; no hook - it must find THAT run, and still re-render the mirror on it
  (let [ws (workspace!)
        app (:workspace/app ws)
        work (:workspace/work ws)
        build (:workspace/build ws)
        _ (spit (str (fs/path build "rules.edn")) (pr-str [{:id :layer-boundaries :text "Respect the boundaries of layer {{layer}}. FILLED."}]))
        release (assoc generated-files "src/xyx/views.clj" (str/replace views-clj "Clojure Stack Lite" "Clojure Stack Lite 2"))
        generate (fn [target] (fs/create-dirs target) (write-tree! target release))
        r1 (tu/upgrade! ws {:pin new-pin :env git-env :gates [[:fmt "true"]] :generate generate
                            :id "upgrade-0.6.5" :release "0.6.5" :branch-anyway? true :before-gates :rule-mirror})
        wt (str (fs/path work "worktrees" "upgrade-0.6.5" "app"))]
    (is (= :conflict (:outcome r1)))
    (is (= {:kind :rule-mirror :release "0.6.5"} (:hook (state ws "upgrade-0.6.5"))) "the hook recorded by name")
    (spit (str (fs/path wt "src" "xyx" "views.clj")) (str "(ns xyx.views)\n(defn base [c] c)\n" (app/home-page "xyx" "0.6.5") "\n"))
    (git-out wt "add" "-A")
    (git-out wt "commit" "-q" "-m" "resolved")
    (testing "the entry point's call: no id, no hook - the same run, the mirror on it, gated, awaiting its merge"
      (let [r2 (tu/upgrade! ws {:pin new-pin :env git-env :gates [[:fmt "true"]] :generate generate})]
        (is (= :awaiting-merge (:outcome r2)))
        (is (not (fs/exists? (fs/path work "runs" "template-upgrade-kit-v9.9"))) "no second run")
        (is (= [:template-commit :merge :stopped :resolved :gates :stopped] (mapv :event/kind (:events (state ws "upgrade-0.6.5")))))
        (is (str/includes? (git-out app "log" "-1" "--format=%s" "upgrade-0.6.5") "the rule mirror re-rendered"))
        (is (str/includes? (git-out app "show" "upgrade-0.6.5:AGENTS.md") "FILLED."))
        (is (= (git-out app "rev-parse" "upgrade-0.6.5") (:head (last (filter #(= :gates (:event/kind %)) (:events (state ws "upgrade-0.6.5"))))))
            "the gates ran on the tip with the mirror")))))
