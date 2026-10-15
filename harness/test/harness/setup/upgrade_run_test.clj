(ns harness.setup.upgrade-run-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.loop.driver :as driver]
   [harness.setup.app :as app]
   [harness.setup.front-door :as front-door]
   [harness.setup.init :as init]
   [harness.setup.skills :as skills]
   [harness.setup.template :as template]
   [harness.setup.upgrade :as upgrade]
   [harness.setup.upgrade-run :as ur]
   [harness.setup.workspace :as workspace]))

(def git-env
  {"GIT_AUTHOR_NAME" "harness-test" "GIT_AUTHOR_EMAIL" "harness@test"
   "GIT_COMMITTER_NAME" "harness-test" "GIT_COMMITTER_EMAIL" "harness@test"})

(def pin (template/pin (template/load-pins)))

(defn- git-out [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true :extra-env git-env} "git" args)]
    (when-not (zero? exit) (throw (ex-info (str "git " (str/join " " args) ": " err) {})))
    (str/trim out)))

(def overview-v1
  "The fake KIT's one plan document at the old version: an instruction, content, a table row, with
  room between them so that each change is its own hunk."
  (str "# Overview\n\n> Instruction one: delete this line once the section is filled.\n\n"
       "one\ntwo\nthree\nfour\nfive\nsix\nseven\n\n"
       "Content line A.\n\n"
       "eight\nnine\nten\neleven\ntwelve\nthirteen\nfourteen\n\n"
       "| What | This project |\n|---|---|\n| Retry cap | <n> |\n\n"
       "fifteen\nsixteen\nseventeen\neighteen\nnineteen\ntwenty\n"))

(def overview-v2
  "The new version: the instruction reworded, content line A revised, the row's label changed."
  (-> overview-v1
      (str/replace "> Instruction one: delete this line once the section is filled." "> Instruction one, reworded: delete this line when filled.")
      (str/replace "Content line A." "Content line A, revised.")
      (str/replace "| Retry cap | <n> |" "| Retry cap, per role | <n> |")))

(defn- fake-kit!
  "A KIT clone in a temp folder: the real harness bb.edn, agents and skills; a tiny build-docs;
  one commit, tagged 0.6.4. Returns its path."
  []
  (let [real (str (fs/parent (fs/real-path ".")))
        kit (str (fs/path (fs/real-path (fs/create-temp-dir)) "KIT"))]
    (fs/create-dirs (fs/path kit "harness" "resources"))
    (fs/copy (fs/path real "harness" "bb.edn") (fs/path kit "harness" "bb.edn"))
    (fs/copy-tree (fs/path real "harness" "agents") (fs/path kit "harness" "agents"))
    (fs/copy (fs/path real "harness" "resources" "template-pins.edn") (fs/path kit "harness" "resources" "template-pins.edn"))
    (fs/copy-tree (fs/path real "skills") (fs/path kit "skills"))
    (fs/create-dirs (fs/path kit "build-docs"))
    (spit (str (fs/path kit "build-docs" "README.md")) "# The plan's documents\n")
    (spit (str (fs/path kit "build-docs" "00-overview.md")) overview-v1)
    (git-out kit "init" "-q" "-b" "main")
    (git-out kit "add" "-A")
    (git-out kit "commit" "-q" "-m" "the KIT at 0.6.4")
    (git-out kit "tag" "0.6.4")
    kit))

(defn- release!
  "The next release in the fake KIT: the overview changed, a new document, the role file changed; tagged 0.6.5."
  [kit]
  (spit (str (fs/path kit "build-docs" "00-overview.md")) overview-v2)
  (spit (str (fs/path kit "build-docs" "01-new.md")) "# A document the release adds\n")
  (spit (str (fs/path kit "build-docs" "README.md")) "# The plan's documents\n\nRevised by the release.\n")
  (spit (str (fs/path kit "harness" "agents" "interactive-programmer.md"))
        (str (slurp (str (fs/path kit "harness" "agents" "interactive-programmer.md"))) "\nA line the release adds.\n"))
  (git-out kit "add" "-A")
  (git-out kit "commit" "-q" "-m" "the KIT at 0.6.5")
  (git-out kit "tag" "0.6.5")
  (git-out kit "rev-parse" "HEAD"))

(defn- generator [files]
  (fn [dir env argv]
    (if (= "clojure" (first argv))
      (let [target (edn/read-string (last argv))]
        (doseq [[path content] files]
          (fs/create-dirs (fs/parent (fs/path target path)))
          (spit (str (fs/path target path)) content))
        {:exit 0 :out ""})
      (#'app/run-process dir env argv))))

(def generated-files
  {"deps.edn" "{:deps {}}\n" "src/xyx/core.clj" "(ns xyx.core)\n"
   "src/xyx/views.clj" (str "(ns xyx.views)\n\n(defn base [c] [:html c])\n\n(def home-page\n  (base [:h1 \"Welcome to Clojure Stack Lite\"]))\n")})

(defn- workspace!
  "A workspace made by `bb init` from the fake KIT at 0.6.4, the project then at work: the
  overview filled (the instruction deleted, the row filled), loop.edn with a literal KIT path."
  [kit]
  (let [ws-dir (str (fs/path (fs/parent kit) "xyx"))
        lay (init/layout {:name "xyx" :kit-dir kit :dir ws-dir
                          :build-docs (init/build-docs-files kit)
                          :skills (skills/sources kit)
                          :harness-tasks (front-door/harness-tasks (slurp (str (fs/path kit "harness" "bb.edn"))))
                          :rule-mirrors ["xyx-app/AGENTS.md"]
                          :kit-commit (init/kit-commit kit)
                          :kit-version (init/kit-version kit)
                          :loop/defaults (:loop/defaults pin)})]
    (init/create! lay {:git-env git-env
                       :app-fn (app/app-fn pin {:app-name "xyx" :git-env git-env :kit-version "0.6.4" :run (generator generated-files)})})
    (let [ws (workspace/find-workspace ws-dir)
          build (:workspace/build ws)
          overview (fs/path build "docs" "00-overview.md")
          loop-edn (fs/path build "loop.edn")]
      (spit (str overview) (-> (slurp (str overview))
                               (str/replace "> Instruction one: delete this line once the section is filled.\n\n" "")
                               (str/replace "| Retry cap | <n> |" "| Retry cap | 3 |")))
      (spit (str loop-edn) (str/replace (slurp (str loop-edn)) "--config {{kit}}/harness/bb.edn" "--config /old/place/KIT/harness/bb.edn"))
      (git-out build "add" "-A")
      (git-out build "commit" "-q" "-m" "the project's plan step")
      ws)))

(defn- raw [ws] (edn/read-string (slurp (str (fs/path (:workspace/dir ws) "workspace.edn")))))

;; ---------------------------------------------------------------------------

(deftest the-pure-parts
  (testing "the upgrade's id is deterministic"
    (is (= "to-0.6.5-from-abc1234" (ur/upgrade-id "abc1234567" "def4567890" "0.6.5")))
    (is (= "to-def4567-from-abc1234" (ur/upgrade-id "abc1234567" "def4567890" nil))))
  (testing "renamed keys move, and an old key beside the new one is dropped"
    (is (= {:workspace/build "b" :x 1} (ur/migrate-keys {:workspace/plan "b" :x 1})))
    (is (= {:workspace/build "b"} (ur/migrate-keys {:workspace/plan "old" :workspace/build "b"})))
    (is (= {:x 1} (ur/migrate-keys {:x 1}))))
  (testing "a literal KIT path in a gate command becomes {{kit}}"
    (is (= ["[:deps \"bb --config {{kit}}/harness/bb.edn boundary\"]" true]
           (ur/rewrite-kit-path "[:deps \"bb --config /Users/x/ws/KIT/harness/bb.edn boundary\"]")))
    (is (= ["bb fmt-check" false] (ur/rewrite-kit-path "bb fmt-check"))))
  (testing "a diff parses into files and hunks"
    (let [files (ur/parse-diff (str "diff --git a/build-docs/a.md b/build-docs/a.md\n--- a/build-docs/a.md\n+++ b/build-docs/a.md\n"
                                    "@@ -1,2 +1,2 @@\n-old\n+new\n ctx\n@@ -9,1 +9,1 @@\n-x\n+y\n"
                                    "diff --git a/build-docs/n.md b/build-docs/n.md\nnew file mode 100644\n--- /dev/null\n+++ b/build-docs/n.md\n@@ -0,0 +1 @@\n+hello\n"))]
      (is (= ["build-docs/a.md" "build-docs/n.md"] (mapv :path files)))
      (is (= 2 (count (:hunks (first files)))))
      (is (= ["-old" "+new" " ctx"] (:lines (first (:hunks (first files))))))
      (is (:new? (second files)))))
  (testing "a hunk is dropped only when it removes instructions now gone and adds only instructions"
    (let [doc "# T\n\nContent.\n"]
      (is (= :instruction-removed (ur/hunk-class {:lines ["-> old instruction" "+> new instruction" " Content."]} doc)))
      (is (= :instruction-removed (ur/hunk-class {:lines ["-> old instruction" " Content."]} doc)))
      (is (= :conflict (ur/hunk-class {:lines ["-> old instruction" "+## A heading the release adds" " Content."]} doc))
          "added content counts")
      (is (= :conflict (ur/hunk-class {:lines ["-> old instruction" "+> new"]} "> old instruction\n"))
          "the instruction still stands: not gone")
      (is (= :conflict (ur/hunk-class {:lines ["-| Retry cap | <n> |" "+| Retry cap, per role | <n> |"]} "| Retry cap | 3 |\n"))
          "a filled row is content")
      (is (= :conflict (ur/hunk-class {:lines ["+only additions"]} doc)))))
  (testing "a hunk's patch names the build's docs/"
    (is (str/starts-with? (ur/hunk-patch "x.md" {:header "@@ -1 +1 @@" :lines ["-a" "+b"]}) "--- a/docs/x.md\n+++ b/docs/x.md\n@@ -1 +1 @@\n-a\n+b\n"))))

(deftest the-orientation-marker-knows-its-own
  (let [text (init/with-marker "# ws\n\nhello\n" "0.6.5" "abc")]
    (is (str/starts-with? text "<!-- GENERATED by `bb init` from the KIT at 0.6.5 (abc); sha256:"))
    (is (= :current (init/marker-state text)))
    (is (= :edited (init/marker-state (str text "an edit\n"))))
    (is (= :unmarked (init/marker-state "# ws\n\nhello\n")))))

(deftest phase-two-brings-a-workspace-up-step-by-step
  (let [kit (fake-kit!)
        ws (workspace! kit)
        from (git-out kit "rev-parse" "HEAD")
        ws-dir (:workspace/dir ws)
        build (:workspace/build ws)
        app (:workspace/app ws)
        _ (is (= from (:workspace/kit-commit (raw ws))))
        ;; the project's own rule: the mirror must re-render on the upgrade branch
        _ (spit (str (fs/path build "rules.edn")) (pr-str [{:id :layer-boundaries :text "Respect the boundaries of layer {{layer}}. FILLED."}]))
        _ (git-out build "commit" "-q" "-am" "the rules filled")
        to (release! kit)
        sw (java.io.StringWriter.)
        r (binding [*out* sw] (ur/after-pull! ws kit {:gates [[:fmt "true"]] :generate (fn [_] (throw (ex-info "no generation: the pin is as it was" {})))}))
        out (str sw)
        records (:records r)]
    (is (:ok? r) out)
    (is (= (set ur/steps) (set (keys records))) "every step recorded")
    (is (every? #(= :done (:status (get records %))) ur/steps) (pr-str (map (juxt identity #(:status (get records %))) ur/steps)))
    (testing "the record, the report and the copies"
      (let [dir (fs/path (:workspace/work ws) "upgrades" (ur/upgrade-id from to "0.6.5"))]
        (is (= (str dir) (:dir r)))
        (is (fs/exists? (fs/path dir "done.edn")))
        (is (fs/exists? (fs/path dir "report.md")))
        (is (fs/exists? (fs/path dir "before" "CLAUDE.md")) "the root file as it was, before it was rewritten")
        (is (fs/exists? (fs/path dir "before" "workspace.edn")))))
    (testing "the orientation: CLAUDE.md rewritten with a fresh marker, the role file copied again"
      (let [claude (slurp (str (fs/path ws-dir "CLAUDE.md")))]
        (is (= :current (init/marker-state claude)))
        (is (str/includes? claude "at 0.6.5 ("))
        (is (not (fs/exists? (fs/path ws-dir "CLAUDE.md.new"))))
        (is (str/ends-with? (slurp (str (fs/path ws-dir ur/role-file))) "A line the release adds.\n"))))
    (testing "the build documents, hunk by hunk: the content applied, the reworded instruction dropped, the filled row a conflict; the new document added; committed"
      (let [overview (slurp (str (fs/path build "docs" "00-overview.md")))
            detail (:detail (:build-docs records))]
        (is (str/includes? overview "Content line A, revised."))
        (is (not (str/includes? overview "Instruction one")) "the project deleted it; the rewording is not put back")
        (is (str/includes? overview "| Retry cap | 3 |") "the project's row stands")
        (is (fs/exists? (fs/path build "docs" "01-new.md")))
        (is (str/includes? detail "1 applied, 1 dropped as instructions removed, 1 conflict"))
        (is (= 1 (count (:conflicts (:build-docs records)))))
        (is (fs/exists? (fs/path ws-dir (first (:conflicts (:build-docs records))))) "the hunk saved for the Architect")
        (is (empty? (fs/glob build "**/*.rej")) "never a .rej in the build")
        (is (str/includes? (git-out build "log" "-3" "--format=%s") "KIT 0.6.5: the build documents"))
        (is (= "" (git-out build "status" "--porcelain")) "the build is clean after")))
    (testing "loop.edn's literal path is {{kit}} again, committed"
      (is (str/includes? (slurp (str (fs/path build "loop.edn"))) "{{kit}}/harness/bb.edn"))
      (is (not (str/includes? (slurp (str (fs/path build "loop.edn"))) "/old/place"))))
    (testing "the application: the pin as it was, so no template commit - a branch for the mirror alone, gated, awaiting its merge"
      (let [a (:app (:app records))]
        (is (= "upgrade-0.6.5" (:branch a)))
        (is (= :awaiting-merge (:state a)))
        (is (= "1" (git-out app "rev-list" "--count" "main..upgrade-0.6.5")) "one commit: the mirror")
        (is (str/includes? (git-out app "log" "-1" "--format=%s" "upgrade-0.6.5") "the rule mirror re-rendered"))
        (is (str/includes? (git-out app "show" "upgrade-0.6.5:AGENTS.md") "FILLED.") "rendered from the project's rules, not the shipped ones")
        (is (= "1" (git-out app "rev-list" "--count" "template")) "nothing new on template")))
    (testing "workspace.edn: the commit and version moved, the upgrade recorded with what it left"
      (let [w (raw ws)
            u (:workspace/upgraded-to w)]
        (is (= to (:workspace/kit-commit w)))
        (is (= "0.6.5" (:workspace/kit-version w)))
        (is (= from (:from u)))
        (is (= "upgrade-0.6.5" (get-in u [:app :branch])))
        (is (= 1 (count (:conflicts u))))
        (is (= [] (:new u)))))
    (testing "the doctor's notice and bb next's line say what is left, the same words"
      (let [ws* (workspace/find-workspace ws-dir)
            open (upgrade/open-after-upgrade ws* (raw ws*))
            lines (upgrade/upgrade-lines open)]
        (is (= :awaiting-merge (get-in open [:app :state])))
        (is (some #(str/includes? % "awaits its merge") lines))
        (is (some #(str/includes? % "did not apply") lines))
        (is (some #(str/includes? % "awaits its merge") (upgrade/expectations (upgrade/facts ws* kit))))))
    (testing "run again: at this KIT's commit already"
      (let [e (try (with-out-str (ur/after-pull! ws kit {})) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (:nothing (ex-data e)))))))

(deftest a-stopped-step-is-resumed-and-an-edited-orientation-gets-a-new-beside-it
  (let [kit (fake-kit!)
        ws (workspace! kit)
        ws-dir (:workspace/dir ws)
        build (:workspace/build ws)
        _ (release! kit)
        ;; the project edited its CLAUDE.md, and has uncommitted work in the overview
        _ (spit (str (fs/path ws-dir "CLAUDE.md")) (str (slurp (str (fs/path ws-dir "CLAUDE.md"))) "\nOur own note.\n"))
        _ (spit (str (fs/path build "docs" "00-overview.md")) (str (slurp (str (fs/path build "docs" "00-overview.md"))) "uncommitted\n"))
        r1 (binding [*out* (java.io.StringWriter.)] (ur/after-pull! ws kit {:gates [[:fmt "true"]]}))]
    (is (not (:ok? r1)))
    (is (= :build-docs (:stopped r1)))
    (is (str/includes? (get-in r1 [:records :build-docs :detail]) "uncommitted edits"))
    (is (= :done (get-in r1 [:records :orientation :status])) "the steps before it are done")
    (testing "the edited CLAUDE.md is the project's: the new text beside it, marked"
      (is (str/ends-with? (slurp (str (fs/path ws-dir "CLAUDE.md"))) "Our own note.\n"))
      (is (= :current (init/marker-state (slurp (str (fs/path ws-dir "CLAUDE.md.new"))))) "taking it whole re-adopts the file")
      (is (= ["CLAUDE.md.new"] (get-in r1 [:records :orientation :new]))))
    (testing "the work committed, the run picks up at build-docs and finishes"
      (git-out build "commit" "-q" "-am" "the project's work")
      (let [r2 (binding [*out* (java.io.StringWriter.)] (ur/after-pull! ws kit {:gates [[:fmt "true"]]}))]
        (is (:ok? r2))
        (is (= (:dir r1) (:dir r2)) "the same folder")
        (is (= :done (get-in r2 [:records :build-docs :status])))
        (is (= ["CLAUDE.md.new"] (:new (:workspace/upgraded-to (raw ws)))))
        (is (some #(str/includes? % "CLAUDE.md.new") (upgrade/upgrade-lines (upgrade/open-after-upgrade ws (raw ws)))))))))

(deftest a-workspace-on-a-renamed-key-is-migrated-first-and-the-run-goes-through
  (let [kit (fake-kit!)
        ws0 (workspace! kit)
        f (fs/path (:workspace/dir ws0) "workspace.edn")
        _ (spit (str f) (str/replace (slurp (str f)) ":workspace/build" ":workspace/plan"))
        ws (workspace/find-workspace (:workspace/dir ws0))
        _ (is (nil? (:workspace/build ws)) "read through this version's code, the old key is nil")
        _ (release! kit)
        r (binding [*out* (java.io.StringWriter.)] (ur/after-pull! ws kit {:gates [[:fmt "true"]]}))]
    (is (:ok? r) (pr-str (:records r)))
    (is (= "renamed: :workspace/plan -> :workspace/build" (get-in r [:records :workspace-keys :detail])))
    (let [w (raw ws)]
      (is (contains? w :workspace/build))
      (is (not (contains? w :workspace/plan))))))

(deftest a-document-with-uncommitted-edits-stops-the-step-before-anything-is-written
  (let [kit (fake-kit!)
        ws (workspace! kit)
        build (:workspace/build ws)
        _ (release! kit)
        ;; two documents change in the release; the SECOND has uncommitted edits
        _ (spit (str (fs/path build "docs" "README.md")) "# The plan's documents\n\nmy uncommitted note\n")
        r1 (binding [*out* (java.io.StringWriter.)] (ur/after-pull! ws kit {:gates [[:fmt "true"]]}))]
    (is (= :build-docs (:stopped r1)))
    (is (str/includes? (get-in r1 [:records :build-docs :detail]) "docs/README.md has uncommitted edits"))
    (is (not (str/includes? (slurp (str (fs/path build "docs" "00-overview.md"))) "revised")) "the first document was not patched")
    (is (not (fs/exists? (fs/path build "docs" "01-new.md"))) "nothing added either")
    (git-out build "commit" "-q" "-am" "the note")
    (let [r2 (binding [*out* (java.io.StringWriter.)] (ur/after-pull! ws kit {:gates [[:fmt "true"]]}))]
      (is (:ok? r2) (pr-str (:records r2)))
      (is (str/includes? (slurp (str (fs/path build "docs" "00-overview.md"))) "Content line A, revised."))
      (is (str/includes? (slurp (str (fs/path build "docs" "README.md"))) "my uncommitted note") "the project's line kept")
      (is (= "" (git-out build "status" "--porcelain"))))))

(deftest the-notice-follows-the-runs-state-and-says-when-a-branch-is-gone
  (let [kit (fake-kit!)
        ws (workspace! kit)
        app (:workspace/app ws)
        build (:workspace/build ws)
        _ (spit (str (fs/path build "rules.edn")) (pr-str [{:id :layer-boundaries :text "Respect the boundaries of layer {{layer}}. FILLED."}]))
        _ (git-out build "commit" "-q" "-am" "the rules filled")
        _ (release! kit)
        r (binding [*out* (java.io.StringWriter.)] (ur/after-pull! ws kit {:gates [[:fmt "true"]]}))
        run-dir (get-in r [:records :app :app :run-dir])
        open #(upgrade/open-after-upgrade ws (raw ws))]
    (is (:ok? r))
    (is (= :awaiting-merge (get-in (open) [:app :state])))
    (testing "merged through the loop: the run's :merged event says so, the branch gone with it"
      (spit (str (fs/path run-dir "decision.edn")) (pr-str {:decision "x"}))
      (binding [*out* (java.io.StringWriter.)] (driver/merge! (driver/context run-dir) (str (fs/path run-dir "decision.edn"))))
      (is (= :merged (get-in (open) [:app :state])))
      (is (some #(str/includes? % "is merged") (upgrade/upgrade-lines (open)))))
    (testing "a branch deleted by hand with no merge recorded is gone, not merged"
      (let [w (raw ws)
            w2 (-> w (assoc-in [:workspace/upgraded-to :app :branch] "upgrade-x") (assoc-in [:workspace/upgraded-to :app :run-dir] "/nowhere"))]
        (spit (str (fs/path (:workspace/dir ws) "workspace.edn")) (pr-str w2))
        (is (= :gone (get-in (open) [:app :state])))
        (is (some #(str/includes? % "gone and no merge is recorded") (upgrade/upgrade-lines (open))))))
    (is (not (fs/exists? (fs/path app "AGENTS.md.rej"))))))
