(ns harness.setup.init-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.money.report :as report]
   [harness.rules :as rules]
   [harness.setup.init :as init]
   [harness.setup.workspace :as workspace]))

;; The tests run in harness/, so the KIT they copy from is the real one around them.
(def kit-dir (str (fs/parent (fs/real-path "."))))

(def git-env
  {"GIT_AUTHOR_NAME" "harness-test" "GIT_AUTHOR_EMAIL" "harness@test"
   "GIT_COMMITTER_NAME" "harness-test" "GIT_COMMITTER_EMAIL" "harness@test"})

(defn- tmp [] (str (fs/real-path (fs/create-temp-dir))))

(defn- request [m]
  (merge {:name "xyx" :kit-dir kit-dir :plan-template (init/plan-template-files kit-dir)} m))

(defn- paths [lay] (set (map :path (:entries lay))))

(deftest the-default-workspace-is-the-folder-the-kit-is-in
  (let [lay (init/layout (request {:kit-dir "/w/clojure-agent-kit" :plan-template ["00-overview.md"]}))]
    (is (= "/w" (:workspace/dir lay)))
    (is (true? (:default? lay)))
    (is (= {:workspace/kit "clojure-agent-kit" :workspace/app "xyx-app" :workspace/build "xyx-build"
            :workspace/work "work" :workspace/rule-mirrors []
            :workspace/rules-overlay "xyx-build/rules.edn"
            :workspace/records "xyx-build/runs" :workspace/run-tables "xyx-build/RUNS.md"}
           (:workspace lay))
        "the KIT is named relatively, so the workspace can be moved whole")
    (is (= #{"workspace.edn" "README.md" "CLAUDE.md" ".claude/agents/interactive-programmer.md"
             "xyx-app" "xyx-build" "xyx-build/README.md" "xyx-build/rules.edn" "xyx-build/profile.edn"
             "xyx-build/loop.edn" "xyx-build/runs" "xyx-build/RUNS.md"
             "xyx-build/docs/00-overview.md" "work" "work/runs"}
           (paths lay))
        "the records' folder and their document among them: four documents said the plan held both, and init wrote neither")
    (is (= ["xyx-build"] (:repos lay)) "the application is not this part's to create")))

(deftest a-brief-is-filed-as-source-md-s-first-row-and-its-appendix
  ;; Step 0 happens before a workspace exists, so its output - the brief - had nowhere to go
  ;; but by hand. With `:brief`, source.md is written, not copied: §1's row names it and
  ;; Appendix A carries it verbatim; everything else the template leaves to fill is left.
  (let [filed (init/source-with-brief (slurp (str (fs/path kit-dir "plan-template" "source.md")))
                                      "Build me a site.\n\nFor patients." "2026-10-06")]
    (is (str/includes? filed "| S-1 | the brief | text, Appendix A below | 2026-10-06 | the person |"))
    (is (str/ends-with? filed "## Appendix A — The brief, as given\n\nBuild me a site.\n\nFor patients.\n"))
    (is (not (str/includes? filed init/brief-row)) "the template's row is replaced, not kept beside")
    (is (str/includes? filed "<what it shows>") "§2 stays the Architect's to fill"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no S-1 row" (init/source-with-brief "# no row" "x" "d")))
  (let [with (init/layout (request {:brief "Build me a site." :today "2026-10-06" :plan-template ["source.md" "00-overview.md"]}))
        without (init/layout (request {:plan-template ["source.md"]}))
        entry (fn [lay p] (first (filter #(= p (:path %)) (:entries lay))))]
    (is (str/includes? (:content (entry with "xyx-build/docs/source.md")) "## Appendix A") "written, with the brief")
    (is (:copy-from (entry with "xyx-build/docs/00-overview.md")) "the other documents are still copied")
    (is (:copy-from (entry without "xyx-build/docs/source.md")) "without a brief, source.md is copied as shipped")))

(deftest an-explicit-folder-is-used-as-given-and-the-kit-may-be-elsewhere
  (let [lay (init/layout (request {:kit-dir "/tools/clojure-agent-kit" :dir "/projects/xyx"}))]
    (is (= "/projects/xyx" (:workspace/dir lay)))
    (is (false? (:default? lay)))
    (is (= "/tools/clojure-agent-kit" (get-in lay [:workspace :workspace/kit]))
        "absolute: the health check and the workspace's pointers must still find it")
    (is (str/includes? (:content (first (filter #(= "CLAUDE.md" (:path %)) (:entries lay))))
                       "/tools/clojure-agent-kit/method.md"))))

(deftest the-workspace-claude-md-orients-and-carries-no-rules
  ;; The KIT's own CLAUDE.md is the rules for CHANGING the KIT; an import of it would hand a
  ;; project's Architect those rules. And a rule written here would be a rule outside the source.
  (let [lay (init/layout (request {:kit-dir "/w/clojure-agent-kit"}))
        content (:content (first (filter #(= "CLAUDE.md" (:path %)) (:entries lay))))]
    (is (not (re-find #"(?m)^@" content)) "no import line")
    (doseq [named ["clojure-agent-kit/" "xyx-app/" "xyx-build/" "xyx-build/rules.edn" "work/" "workspace.edn"
                   "clojure-agent-kit/method.md" "clojure-agent-kit/harness/README.md"
                   "docs/00-overview.md" "AGENTS.md" ".claude/agents/interactive-programmer.md"
                   "bb plan-check" "bb plan-review"]]
      (is (str/includes? content named) named))
    (is (str/includes? content "not this project's") "the KIT's own CLAUDE.md is said to be the KIT's")
    (is (str/includes? content "THE PLAN IS FILLED HERE") "the Architect's session is told the plan is its job")
    (is (str/includes? content "THE RULES ARE FILLED HERE TOO") "and where the rule source's placeholders are filled")
    (is (not (str/includes? content "REPL-first")) "no rule from the source is restated")
    (is (< (count (str/split-lines content)) 29) "orientation, one screen; not a second rules file")))

(deftest what-the-application-generator-knows-is-passed-in-not-known-here
  (let [lay (init/layout (request {:kit-dir "/tools/clojure-agent-kit"
                                   :rule-mirrors ["xyx-app/AGENTS.md"]
                                   :loop/defaults {:nrepl/cmd ["clojure" "-Srepro" "-M:test:nrepl"]
                                                   :gates [[:deps "bb --config {{kit}}/harness/bb.edn boundary"]]}}))
        content (fn [path] (:content (first (filter #(= path (:path %)) (:entries lay)))))]
    (is (= ["xyx-app/AGENTS.md"] (:workspace/rule-mirrors (edn/read-string (content "workspace.edn")))))
    (is (= {:run/id "<one per run>" :profile "profile.edn"
            :nrepl/cmd ["clojure" "-Srepro" "-M:test:nrepl"]
            :gates [[:deps "bb --config /tools/clojure-agent-kit/harness/bb.edn boundary"]]}
           (edn/read-string (content "xyx-build/loop.edn")))
        "{{kit}} is the KIT's absolute path, wherever the KIT is")
    (is (str/includes? (content "xyx-build/loop.edn") ":architecture {:from \"arch\" :files [\"layers.edn\"]}")
        "the header says how a layers.edn entry reaches a run - the file every run directory copies is where an adopter looks")))

(deftest it-refuses-in-sentences-and-says-nothing-when-it-will-run
  (let [req (request {:kit-dir "/w/clojure-agent-kit"})
        lay (init/layout req)
        clean {:kit? true :existing [] :other-repos [] :workspaces-below [] :seats ["agy-ide" "claude"] :doctor/ok? true}
        why (fn [req lay facts] (str/join "\n" (init/refusals req lay facts)))]
    (is (= [] (init/refusals req lay clean)))
    (testing "a name that cannot be a folder and a namespace"
      (doseq [bad ["Xyx" "1abc" "a_b" "a--b" "a-" "" nil "a b" "../x"]]
        (is (str/includes? (why (assoc req :name bad) lay clean) "cannot be used") (pr-str bad)))
      (doseq [good ["xyx" "my-site" "a1" "site-2-go"]]
        (is (= [] (init/refusals (assoc req :name good) lay clean)) good)))
    (testing "anything already there is named, absolutely, and nothing is overwritten"
      (is (str/includes? (why req lay (assoc clean :existing ["CLAUDE.md" "xyx-build"]))
                         "/w/CLAUDE.md, /w/xyx-build")))
    (testing "the DEFAULT target is refused when it looks shared, and says how to proceed"
      (let [s (why req lay (assoc clean :other-repos ["some-project" "another"]))]
        (is (str/includes? s "some-project, another"))
        (is (str/includes? s "bb init xyx <dir>"))))
    (testing "an explicit folder is consent: other repositories there are the person's business"
      (is (= [] (init/refusals req (init/layout (assoc req :dir "/w"))
                               (assoc clean :other-repos ["some-project"])))))
    (testing "the DEFAULT target is refused when it already holds workspaces: a development folder"
      (let [s (why req lay (assoc clean :workspaces-below ["abc" "xyx"]))]
        (is (str/includes? s "abc, xyx"))
        (is (str/includes? s "development folder"))
        (is (str/includes? s "clone the KIT INTO it")))
      (is (= [] (init/refusals req (init/layout (assoc req :dir "/w"))
                               (assoc clean :workspaces-below ["abc"])))
          "an explicit folder is consent here too"))
    (testing "a folder inside the KIT's clone: nothing of a project is written there"
      (doseq [dir ["/w/clojure-agent-kit" "/w/clojure-agent-kit/harness/ws"]]
        (is (str/includes? (why req (init/layout (assoc req :dir dir)) clean) "inside the KIT's clone") dir))
      (is (= [] (init/refusals req (init/layout (assoc req :dir "/w/clojure-agent-kit-2")) clean))
          "a sibling whose name merely starts the same is not inside it"))
    (testing "a red doctor, and a folder that is not the KIT"
      (is (str/includes? (why req lay (assoc clean :doctor/ok? false)) "bb doctor"))
      (is (str/includes? (why req lay (assoc clean :kit? false)) "not a clone of the KIT")))
    (testing "every reason at once, not the first"
      (is (= 3 (count (init/refusals (assoc req :name "X") lay
                                     (assoc clean :existing ["work"] :doctor/ok? false))))))))

(deftest the-survey-sees-what-is-there
  (let [ws (tmp)
        kit (str (fs/path ws "clojure-agent-kit"))
        _ (fs/create-dirs (fs/path kit ".git"))
        _ (fs/create-dirs (fs/path kit "plan-template"))
        req (request {:kit-dir kit :plan-template ["00-overview.md"]})
        lay (init/layout req)]
    (is (= {:kit? true :existing [] :other-repos [] :workspaces-below [] :seats [] :doctor/ok? true}
           (init/survey req lay (constantly true)))
        "the KIT's own clone is not an 'other repository'")
    (spit (str (fs/path kit "workspace.edn")) "{}")
    (is (= [] (:workspaces-below (init/survey req lay (constantly true))))
        "nor is it a workspace below, whatever it holds")
    (fs/create-dirs (fs/path ws "abc"))
    (spit (str (fs/path ws "abc" "workspace.edn")) "{}")
    (is (= ["abc"] (:workspaces-below (init/survey req lay (constantly true)))))
    (fs/create-dirs (fs/path ws "neighbour" ".git"))
    (fs/create-dirs (fs/path ws "not-a-repo"))
    (fs/create-dirs (fs/path ws "xyx-build" "docs"))
    (spit (str (fs/path ws "xyx-build" "README.md")) "")
    (fs/create-dirs (fs/path ws ".claude"))
    (let [facts (init/survey req lay (constantly false))]
      (is (= ["neighbour"] (:other-repos facts)) "abc/ has no .git: a workspace is not a repository")
      (is (= ["xyx-build"] (:existing facts))
          "a folder that exists is reported once, not once per file in it; a bare .claude/ is no conflict")
      (is (false? (:doctor/ok? facts))))))

(defn- git-out [dir & args]
  (str/trim (:out (apply p/shell {:dir dir :out :string :err :string} "git" args))))

(deftest a-workspace-is-created-beside-nothing-it-did-not-make
  (let [ws (str (fs/path (tmp) "xyx"))
        req (request {:dir ws})
        lay (init/layout req)
        kit-before (git-out kit-dir "status" "--porcelain")]
    (is (= [] (init/refusals req lay (init/survey req lay (constantly true))))
        "a folder that does not exist yet is a fine target")
    (let [made (init/create! lay {:git-env git-env})
          plan (str (fs/path ws "xyx-build"))]
      (is (false? (:app/created? made)))
      (testing "every entry is there except the application, which no generator was given for"
        (doseq [{:keys [path app?]} (:entries lay)]
          (is (= (not app?) (fs/exists? (fs/path ws path))) path)))
      (testing "the plan is a repository with one commit and nothing left over"
        (is (= "1" (git-out plan "rev-list" "--count" "HEAD")))
        (is (= "" (git-out plan "status" "--porcelain")))
        (is (= (slurp (str (fs/path kit-dir "plan-template" "stages" "stage-N-template.md")))
               (slurp (str (fs/path plan "docs" "stages" "stage-N-template.md"))))
            "the template keeps its tree, so their relative links still resolve")
        (is (fs/exists? (fs/path plan "docs" "README.md")) "the order of writing travels with the documents")
        (is (str/includes? (slurp (str (fs/path plan "README.md"))) "IS THE BUILD'S SIDE OF THE PROJECT")
            "the build's README says why it is called the build: settings and records beside the plan"))
      (testing "the workspace itself is not a repository"
        (is (not (fs/exists? (fs/path ws ".git")))))
      (testing "the harness finds it from a run directory, with the KIT absolute"
        (let [found (workspace/find-workspace (str (fs/path ws "work" "runs")))]
          (is (= ws (:workspace/dir found)))
          (is (= kit-dir (:workspace/kit found)))
          (is (= (str (fs/path ws "xyx-app")) (:workspace/app found)))
          (is (= [] (:workspace/rule-mirrors found)))
          (is (= (str (fs/path ws "xyx-build" "rules.edn")) (:workspace/rules-overlay found)))
          (is (= (str (fs/path ws "xyx-build" "runs")) (:workspace/records found)) "where `record` copies run.edn")
          (is (= (str (fs/path ws "xyx-build" "RUNS.md")) (:workspace/run-tables found)) "and what publishes it")
          (testing "both exist from init, and report-check holds them to each other from the first gate run"
            ;; The fifth project found neither, wrote RUNS.md by hand, and `record` made runs/.
            (is (= :both (report/published-state (:workspace/run-tables found) (:workspace/records found))))
            (is (= {} (report/published-reports (slurp (:workspace/run-tables found)))) "the header publishes no report")
            (is (= [] (report/drift (slurp (:workspace/run-tables found)) {})) "and nothing drifts against no record"))
          (is (= (rules/shipped) (rules/project-rules found))
              "the overlay as written changes nothing until it is filled")))
      (testing "nothing was written into the KIT's clone"
        (is (= kit-before (git-out kit-dir "status" "--porcelain"))))
      (testing "a second run refuses, naming what is there"
        (let [facts (init/survey req lay (constantly true))]
          (is (= ["workspace.edn" "README.md" "CLAUDE.md" ".claude/agents/interactive-programmer.md"
                  "xyx-build" "work"]
                 (:existing facts)))
          (is (seq (init/refusals req lay facts))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"appeared while"
                              (init/create! lay {:git-env git-env}))
            "and the writer itself will not overwrite, whoever calls it")))))

(deftest the-application-is-made-by-a-function-this-part-knows-nothing-about
  (let [ws (str (fs/path (tmp) "xyx"))
        seen (atom nil)
        made (init/create! (init/layout (request {:dir ws}))
                           {:git-env git-env
                            :app-fn (fn [dir] (reset! seen dir) (fs/create-dirs dir))})]
    (is (true? (:app/created? made)))
    (is (= (str (fs/path ws "xyx-app")) @seen))))

(deftest the-rule-tasks-read-the-workspaces-mirrors
  (let [ws (tmp)
        seed (str (fs/path ws "clojure-agent-kit" "harness"))]
    (fs/create-dirs (fs/path seed "resources"))
    (spit (str (fs/path seed "resources" "rule-mirrors.edn")) (pr-str ["AGENTS.md"]))
    (is (= ["AGENTS.md"] (rules/mirror-paths seed)) "no workspace, no more mirrors")
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/app "xyx-app" :workspace/rule-mirrors ["xyx-app/AGENTS.md"]}))
    (is (= ["AGENTS.md" (str (fs/path ws "xyx-app" "AGENTS.md"))] (rules/mirror-paths seed))
        "the application's mirror, absolute, because the task runs in harness/")
    ;; A KIT kept OUTSIDE the workspace walks up to nothing; pointed at it, it lists the same.
    (let [elsewhere (str (fs/path (tmp) "clojure-agent-kit" "harness"))]
      (fs/create-dirs (fs/path elsewhere "resources"))
      (spit (str (fs/path elsewhere "resources" "rule-mirrors.edn")) (pr-str ["AGENTS.md"]))
      (is (= ["AGENTS.md"] (rules/mirror-paths elsewhere)) "from elsewhere, the walk-up finds no workspace")
      (is (= ["AGENTS.md" (str (fs/path ws "xyx-app" "AGENTS.md"))]
             (rules/mirror-paths elsewhere (workspace/current ["--workspace" ws] elsewhere)))
          "pointed at the workspace, the application's mirror is listed from there"))))

(deftest the-rules-overlay-is-written-with-the-placeholders-standing-and-before-the-application
  ;; The clone's rule source is never edited by a project; what a project fills lives in the
  ;; plan. As written it changes nothing, so `start` still lists the three - in the plan's file.
  (let [lay (init/layout (request {:kit-dir "/w/clojure-agent-kit" :rule-mirrors ["xyx-app/AGENTS.md"]}))
        content (fn [path] (:content (first (filter #(= path (:path %)) (:entries lay)))))
        overlay (edn/read-string (content "xyx-build/rules.edn"))
        shipped (rules/shipped)]
    (is (= "xyx-build/rules.edn" (:workspace/rules-overlay (edn/read-string (content "workspace.edn")))))
    (is (= (mapv :id (filter (comp (rules/placeholder-ids shipped) :id) shipped)) (mapv :id overlay))
        "exactly the source's placeholder rules, in the source's order")
    (is (= (mapv :text (filter (comp (rules/placeholder-ids shipped) :id) shipped)) (mapv :text overlay))
        "text as shipped, the placeholder standing")
    (is (= (mapv :id (rules/unfilled shipped)) (mapv :id (rules/unfilled (rules/overlay shipped overlay)))))
    (is (str/includes? (content "xyx-build/rules.edn") "clojure-agent-kit/harness/resources/agent-rules.edn")
        "the file says what it overlays, by the workspace's own name for the KIT")
    (let [order (mapv :path (:entries lay))]
      (is (< (.indexOf order "xyx-build/rules.edn") (.indexOf order "xyx-app"))
          "the application's mirror is rendered from the overlay at generation, so the overlay is there first"))))

(deftest the-plan-gets-the-seats-shipped-profile-and-loop-edn-names-it
  ;; The profile is a project's, so it lives in the plan: a copy of the shipped example for
  ;; the seat, whole, under a line saying so. The clone's resources/profiles/ is never the
  ;; destination - the KIT's tests hold that folder to exactly the shipped files.
  (let [content (fn [lay path] (:content (first (filter #(= path (:path %)) (:entries lay)))))
        default (init/layout (request {:dir "/projects/xyx"}))
        agy (init/layout (request {:dir "/projects/xyx" :seat "agy-ide"}))]
    (is (str/starts-with? (content default "xyx-build/profile.edn") ";; THIS PROJECT'S PROFILE"))
    (is (str/ends-with? (content default "xyx-build/profile.edn")
                        (slurp (init/shipped-profile kit-dir "claude")))
        "the shipped example, whole and header kept, for the default seat")
    (is (str/ends-with? (content agy "xyx-build/profile.edn") (slurp (init/shipped-profile kit-dir "agy-ide")))
        "--seat picks another")
    (is (= (edn/read-string (slurp (init/shipped-profile kit-dir "claude")))
           (edn/read-string (content default "xyx-build/profile.edn")))
        "and reads as the same profile")
    (is (= "profile.edn" (:profile (edn/read-string (content default "xyx-build/loop.edn"))))
        "relative to the plan, where the driver resolves it in a workspace")
    (testing "a seat the KIT ships no example for is refused by name, before anything is written"
      (let [req (request {:dir "/projects/xyx" :seat "emacs"})
            lay (init/layout req)
            facts (init/survey req lay (constantly true))]
        (is (nil? (content lay "xyx-build/profile.edn")))
        (is (= ["agy-ide" "claude"] (:seats facts)))
        (is (some #(re-find #"no shipped profile for seat \"emacs\": the KIT has agy-ide, claude" %)
                  (init/refusals req lay facts)))
        (is (empty? (filter #(re-find #"seat" %) (init/refusals (request {:dir "/projects/xyx"}) default facts)))
            "the default seat is shipped")))))

(deftest init-s-arguments-are-parsed-as-name-dir-seat-and-dry-run
  (is (= {:positional ["xyx" "/w"] :seat "agy-ide" :dry-run? true}
         (init/parse-args ["xyx" "--seat" "agy-ide" "/w" "--dry-run"]))
      "--seat takes the next argument, wherever it sits")
  (is (= {:positional ["xyx"]} (init/parse-args ["xyx"])))
  (is (= {:positional ["mnj"] :app "mnj-site" :build "mnj-build-docs" :brief "brief.md"}
         (init/parse-args ["mnj" "--app" "mnj-site" "--build" "mnj-build-docs" "--brief" "brief.md"]))
      "--app and --build take the next argument too"))

(deftest the-repositories-folders-can-be-named-and-the-name-stays-the-namespace
  ;; A real project calls its repositories what it calls them; the experiments never
  ;; needed to. The name is still the application's namespace, and everything after
  ;; init reads the folders from workspace.edn.
  (let [lay (init/layout (request {:kit-dir "/w/clojure-agent-kit" :name "mnj"
                                   :app "mnj-breastconnect-site" :build "mnj-breastconnect-plan"
                                   :rule-mirrors ["mnj-breastconnect-site/AGENTS.md"]
                                   :plan-template ["00-overview.md"]}))
        content (fn [path] (:content (first (filter #(= path (:path %)) (:entries lay)))))]
    (is (= {:workspace/kit "clojure-agent-kit"
            :workspace/app "mnj-breastconnect-site" :workspace/build "mnj-breastconnect-plan"
            :workspace/work "work" :workspace/rule-mirrors ["mnj-breastconnect-site/AGENTS.md"]
            :workspace/rules-overlay "mnj-breastconnect-plan/rules.edn"
            :workspace/records "mnj-breastconnect-plan/runs"
            :workspace/run-tables "mnj-breastconnect-plan/RUNS.md"}
           (:workspace lay)))
    (is (= ["mnj-breastconnect-plan"] (:repos lay)))
    (is (contains? (paths lay) "mnj-breastconnect-plan/docs/00-overview.md"))
    (is (not-any? #(str/includes? % "mnj-app") (paths lay)) "no default suffix survives")
    (is (str/includes? (content "CLAUDE.md") "`mnj-breastconnect-site/`")
        "the orientation names the folders as chosen")
    (is (str/includes? (content "README.md") "# mnj - a workspace") "and the project by its name")
    (testing "a folder name is held to the name's pattern, and the two must differ"
      (let [clean {:kit? true :existing [] :other-repos [] :workspaces-below [] :seats ["claude"] :doctor/ok? true}
            why (fn [m] (str/join "\n" (init/refusals (request m) (init/layout (request m)) clean)))]
        (is (= [] (init/refusals (request {:name "mnj" :app "a-site" :build "a-build"})
                                 (init/layout (request {:name "mnj" :app "a-site" :build "a-build"})) clean)))
        (is (str/includes? (why {:name "mnj" :app "My Site"}) "a folder name cannot be used"))
        (is (str/includes? (why {:name "mnj" :build "../build"}) "a folder name cannot be used"))
        (is (str/includes? (why {:name "mnj" :app "same" :build "same"}) "the same folder"))))))
