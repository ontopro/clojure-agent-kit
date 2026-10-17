(ns harness.setup.next-test
  "`bb next`: the table, pure over facts; then the facts read from a workspace
  on disk."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.next :as next]
   [harness.setup.workspace :as workspace]))

(def kit-dir (str (fs/normalize (fs/absolutize ".."))))

(defn- step [facts] ((juxt :step :owner) (next/next-action facts)))

(def ready
  "A workspace whose plan passes, with stage 1 pulled: the base the table is
  walked from."
  {:workspace? true :brief? true :plan-problems []
   :stage {:id 1 :plan "stage-1/stage-1-plan.md" :gates {:stage/id "1" :stage/approved nil :blueprint/signed nil :stage/closed nil}
           :blueprint nil :reviewed? false :packets [] :merged #{} :runs {}}})

(defn- with-stage [m] (update ready :stage merge m))
(defn- gate [m k v] (assoc-in m [:stage :gates k] v))

(deftest before-a-workspace-the-next-action-is-setup
  (let [a (next/next-action {:workspace? false})]
    (is (= 0 (:step a)))
    (is (= "setup" (:name a)))
    (is (str/includes? (:run a) "bb self-check, then bb init"))
    (is (str/includes? (next/render a) "next: step 0 - setup\n  owner:   the person"))))

(deftest a-workspace-with-no-brief-is-scoped-there
  (let [a (next/next-action {:workspace? true :brief? false})]
    (is (= [1 "scoping"] [(:step a) (:name a)]))
    (is (str/includes? (:run a) "/kit-start, then /kit-scoping"))
    (is (str/includes? (:run a) "bb brief"))))

(deftest the-plan-comes-before-any-stage
  (is (= [1 "the person, with an Architect session"] (step {:workspace? true :brief? false :plan-problems ["x"]})) "no brief: scoping first")
  (is (str/includes? (:because (next/next-action {:workspace? true :brief? false})) "<the brief>"))
  (let [a (next/next-action {:workspace? true :brief? true :plan-problems ["a" "b"]})]
    (is (= 2 (:step a)))
    (is (str/includes? (:run a) "/kit-plan"))
    (is (= "bb plan-check: 2 things left" (:because a))))
  (let [a (next/next-action {:workspace? true :brief? true :plan-problems [] :stage nil})]
    (is (= [5 "the person"] ((juxt :step :owner) a)) "the plan passes and no stage exists: stage 0's approval")
    (is (str/includes? (:run a) "stage-0-gates.edn"))))

(deftest a-plan-check-problem-is-said-beside-a-stage-past-its-plan-never-instead
  ;; The proof build closed stage 0 and plan-check found four "marks" in a record a KIT tool
  ;; wrote; `bb next` then said "step 2 - the plan, for stage 0" of a closed stage.
  (let [problems ["docs/stages/stage-0/stage-0-security-review/r.edn: 4 marks left to fill"]
        closed (-> (with-stage {:id 0 :plan "stage-0-plan.md" :blueprint "stage-0-blueprint.md" :reviewed? true
                                :packets ["t-01"] :merged #{"t-01"}})
                   (assoc-in [:stage :gates] {:stage/id "0" :stage/approved {:date "d" :cap 20 :by "Ada"}
                                              :blueprint/signed {:date "d" :by "Ada"} :stage/closed {:date "d"}})
                   (assoc :plan-problems problems :build "xyx-build"))
        a (next/next-action closed)]
    (is (= 7 (:step a)) "stage 0 is closed: the next stage is pulled, not the plan")
    (is (= "bb plan-check: 1 thing left - xyx-build/docs/stages/stage-0/stage-0-security-review/r.edn: 4 marks left to fill - fix it before stage 1's plan"
           (:also a)))
    (is (str/includes? (next/render a) "\n  also:    bb plan-check: 1 thing left")))
  (testing "a stage plan being written carries its marks: said, as that"
    (let [a (next/next-action (assoc ready :plan-problems ["docs/stages/stage-1/stage-1-plan.md: 3 marks left to fill"]))]
      (is (= 7 (:step a)))
      (is (str/includes? (:also a) "stage 1's plan and blueprint are being written"))))
  (testing "the window runs until the blueprint is signed, whichever order the plan and the approval came in"
    (let [approved {:stage/id "1" :stage/approved {:date "d" :cap 20 :by "Ada"}}]
      (is (next/plan-step? nil) "before any stage")
      (is (next/plan-step? (:stage ready)) "a later stage, its plan written and not approved")
      (is (next/plan-step? {:id 0 :plan "stage-0-plan.md" :gates (assoc approved :stage/id "0")}) "stage 0, approved before its plan")
      (is (next/plan-step? {:id 1 :plan "stage-1/stage-1-plan.md" :blueprint "stage-1/stage-1-blueprint.md" :gates approved}) "the blueprint being written")
      (is (not (next/plan-step? {:id 1 :plan "p" :gates (assoc approved :blueprint/signed {:date "d" :by "Ada"})})) "signed: a mark now is left behind")
      (is (not (next/plan-step? {:id 1 :plan "p" :gates (assoc approved :stage/closed {:date "d"})})) "closed")))
  (testing "before stage 0 is approved, the plan passes its check first: step 2"
    (is (= 2 (:step (next/next-action (assoc (with-stage {:id 0 :plan nil :gates nil}) :plan-problems ["x"])))))
    (is (= 2 (:step (next/next-action (assoc (with-stage {:id 0 :plan nil :gates {:stage/id "0" :stage/approved nil}}) :plan-problems ["x"]))))))
  (testing "no problem, no line"
    (is (nil? (:also (next/next-action ready))))
    (is (not (str/includes? (next/render (next/next-action ready)) "also:")))))

(deftest the-stages-three-gates-walk-the-table
  (testing "stage 0: approval before its plan"
    (let [s0 (with-stage {:id 0 :plan nil :gates nil})]
      (is (= [5 "the person"] (step s0)) "no gates record yet")
      (is (= [5 "the person"] (step (assoc-in s0 [:stage :gates] {:stage/id "0" :stage/approved nil}))))
      (let [a (next/next-action (assoc-in s0 [:stage :gates] {:stage/id "0" :stage/approved {:date "d" :by "Ada"}}))]
        (is (= [6 "the Architect's session"] ((juxt :step :owner) a)))
        (is (str/includes? (:run a) "/kit-stage-plan")))))
  (testing "a later stage: its plan, then approval"
    (let [pulled (with-stage {:plan nil})]
      (is (= [7 "the Architect's session"] (step pulled)))
      (is (str/includes? (:run (next/next-action pulled)) "/kit-stage-plan")))
    (is (= [7 "the person"] (step (with-stage {:gates nil}))) "a plan with no gates record: copy the template")
    (is (= [7 "the person"] (step ready)) "written, not approved")
    (is (str/includes? (:run (next/next-action ready)) ":stage/approved")))
  (testing "approved: the blueprint, its review, the sign-off"
    (let [approved (gate ready :stage/approved {:date "d" :cap "$5" :by "Ada"})]
      (is (= [8 "the Architect's session"] (step approved)))
      (let [bp (with-stage {:gates (:gates (:stage approved)) :blueprint "stage-1/stage-1-blueprint.md"})]
        (is (= [9 "the Architect's session"] (step bp)))
        (is (str/includes? (:run (next/next-action bp)) "bb blueprint-review docs/stages/stage-1/stage-1-blueprint.md"))
        (let [reviewed (assoc-in bp [:stage :reviewed?] true)]
          (is (= [10 "the person"] (step reviewed)))
          (is (str/includes? (:run (next/next-action reviewed)) ":blueprint/signed"))))))
  (testing "signed: the packets, one at a time, by their records"
    (let [signed (-> (with-stage {:blueprint "stage-1/stage-1-blueprint.md" :reviewed? true :packets ["t1" "t2" "t3"]})
                     (gate :stage/approved {:date "d" :by "Ada"})
                     (gate :blueprint/signed {:date "d" :by "Ada"}))]
      (is (= [8 "the Architect's session"] (step (assoc-in signed [:stage :packets] []))) "a blueprint with no packet")
      (let [a (next/next-action signed)]
        (is (= [11 "the Architect's session"] ((juxt :step :owner) a)))
        (is (str/includes? (:run a) "bb spec-from-blueprint docs/stages/stage-1/stage-1-blueprint.md t1"))
        (is (= "t1 has no run record; 0 of 3 packets merged" (:because a))))
      (let [a (next/next-action (assoc-in signed [:stage :merged] #{"t1"}))]
        (is (str/includes? (:run a) " t2 ") "the first unmerged, in the blueprint's order")
        (is (= "t2 has no run record; 1 of 3 packets merged" (:because a))))
      (is (= [13 "the person"] (step (-> signed (assoc-in [:stage :merged] #{"t1"}) (assoc-in [:stage :runs] {"t2" :awaiting-merge})))))
      (is (= [12 "the person"] (step (assoc-in signed [:stage :runs] {"t1" :escalated}))))
      (is (= [12 "the Architect's session"] (step (assoc-in signed [:stage :runs] {"t1" :gates-failed}))))
      (is (= [11 "the Architect's session"] (step (assoc-in signed [:stage :runs] {"t1" :abandoned}))))
      (let [all (assoc-in signed [:stage :merged] #{"t1" "t2" "t3"})]
        (is (= [14 "the Architect's session"] (step all)))
        (is (str/includes? (:run (next/next-action all)) "/kit-stage-end"))
        (let [closed (gate all :stage/closed {:date "d"})]
          (is (= [7 "the person"] (step closed)))
          (is (str/includes? (:run (next/next-action closed)) "stage-2-gates.edn"))
          (is (= "stage 1 is closed" (:because (next/next-action closed)))))))))

(deftest a-pre-release-stage-has-a-fourth-gate-its-threat-model-signed
  (let [all (-> (with-stage {:kind "pre-release" :blueprint "stage-1/stage-1-blueprint.md" :reviewed? true
                             :packets ["t1"] :merged #{"t1"}})
                (gate :stage/approved {:date "d" :by "Ada"})
                (gate :blueprint/signed {:date "d" :by "Ada"}))]
    (let [a (next/next-action all)]
      (is (= [14 "the Architect's session"] ((juxt :step :owner) a)) "/kit-stage-end still runs the stage's end")
      (is (str/includes? (:run a) "every line of docs/02-architecture.md §15 answered with its evidence"))
      (is (str/includes? (:run a) ":security/signed {:date :by <name>} in docs/stages/stage-1/stage-1-gates.edn, before :stage/closed")))
    (let [closed (gate all :stage/closed {:date "d"})]
      (is (= [14 "the person"] (step closed)) "closed without it: the person signs before the next stage is pulled")
      (is (= "stage 1 is pre-release and closed without its threat model signed" (:because (next/next-action closed))))
      (is (= [7 "the person"] (step (gate closed :security/signed {:date "d" :by "Ada"}))) "signed: the next stage"))
    (testing "another kind is not asked, and a plan with no kind line is another kind"
      (is (= "/kit-stage-end" (:run (next/next-action (assoc-in all [:stage :kind] "increment")))))
      (is (= [7 "the person"] (step (-> all (assoc-in [:stage :kind] nil) (gate :stage/closed {:date "d"}))))))))

(deftest a-sign-off-names-who-signed
  (testing "a :by that names no one: missing, blank, not a string, the template's words, the role - any case"
    (doseq [by [nil "" "  " :ada "NAME OF WHO SIGNED" "the person" " The Person "]]
      (is (next/unnamed? (cond-> {:date "d"} (some? by) (assoc :by by))) (pr-str by))))
  (testing "a name, and a gate not passed, are not"
    (is (not (next/unnamed? {:date "d" :by "Ada"})))
    (is (not (next/unnamed? nil))))
  (testing "bb next says it at the gate's own step, before anything after it"
    (let [approved (gate ready :stage/approved {:date "d" :cap "$5" :by "the person"})
          a (next/next-action approved)]
      (is (= [7 "the person"] ((juxt :step :owner) a)))
      (is (= "set :by in :stage/approved to the name of who signed, in docs/stages/stage-1/stage-1-gates.edn" (:run a)))
      (is (= "stage 1's :stage/approved has :by \"the person\" - a sign-off names who signed" (:because a))))
    (let [s0 (with-stage {:id 0 :plan nil :gates {:stage/id "0" :stage/approved {:date "d" :cap "$5"}}})]
      (is (= [5 "the person"] (step s0)) "stage 0's approval is step 5")
      (is (= "stage 0's :stage/approved has no :by - a sign-off names who signed" (:because (next/next-action s0)))))
    (let [signed (-> (with-stage {:blueprint "stage-1/stage-1-blueprint.md" :reviewed? true :packets ["t1"]})
                     (gate :stage/approved {:date "d" :by "Ada"})
                     (gate :blueprint/signed {:date "d" :by "NAME OF WHO SIGNED"}))]
      (is (= [10 "the person"] (step signed)) "the blueprint's sign-off, not the packet")
      (is (= [11 "the Architect's session"] (step (gate signed :blueprint/signed {:date "d" :by "Ada"}))) "named: the packet"))
    (let [closed (-> (with-stage {:kind "pre-release" :blueprint "stage-1/stage-1-blueprint.md" :reviewed? true
                                  :packets ["t1"] :merged #{"t1"}})
                     (gate :stage/approved {:date "d" :by "Ada"})
                     (gate :blueprint/signed {:date "d" :by "Ada"})
                     (gate :stage/closed {:date "d"})
                     (gate :security/signed {:date "d" :by ""}))]
      (is (= [14 "the person"] (step closed)) "the threat model's signature, before the next stage"))))

(deftest the-blueprints-human-prep-comes-before-the-packet-it-blocks
  ;; The proof build signed its blueprint with three of §0's rows open, each blocking t-01, and
  ;; `bb next` said "step 11 - a packet"; the Architect's session read §0 first, this time.
  (let [text (str "# Blueprint\n\n## 0. Dispatch preconditions — HUMAN PREP (not task packets)\n\n"
                  "> guidance\n\n| # | Prep | Blocks | Status |\n|---|---|---|---|\n"
                  "| HP-0 | | t-0n | |\n"
                  "| HP-1 | a library pinned | t-01 | done 2026-10-13 |\n"
                  "| HP-2 | the content synced | t-01, t-02 | |\n"
                  "| HP-3 | the balance above the floor | every task | per run |\n"
                  "| HP-4 | colour tokens | t-04 | open |\n"
                  "| HP-5 | an account | t-010 | |\n"
                  "\n## 1. Data shapes\n\n| # | Prep | Blocks | Status |\n|---|---|---|---|\n| HP-9 | not §0 | t-01 | |\n")
        rows (next/preconditions text)
        signed (with-stage {:blueprint "stage-1/stage-1-blueprint.md" :reviewed? true :packets ["t-01" "t-02" "t-04"]
                            :gates {:stage/id "1" :stage/approved {:date "d" :cap 20 :by "Ada"} :blueprint/signed {:date "d" :by "Ada"}}
                            :preconditions rows})]
    (is (= ["HP-1" "HP-2" "HP-3" "HP-4" "HP-5"] (mapv :id rows)) "§0 only; the template's empty HP-0 is not a row")
    (is (= [:done :open :per-run :open :open] (mapv (comp next/precondition-state :status) rows)))
    (is (= :open (next/precondition-state "green 2026-10-13")) "a word that is not done or per run is open")
    (is (= :done (next/precondition-state "**Done** 2026-10-13")))
    (is (not (next/blocks? "t-010" "t-01")) "an id is a word")
    (is (next/blocks? "Every task" "t-07"))
    (let [a (next/next-action signed)]
      (is (= [11 "the person (with the Architect's session)"] ((juxt :step :owner) a)))
      (is (str/starts-with? (:run a) "do HP-2 (the content synced), then set its Status to done in docs/stages/stage-1/stage-1-blueprint.md §0"))
      (is (= "HP-2 is open in docs/stages/stage-1/stage-1-blueprint.md §0 and blocks t-01" (:because a))))
    (testing "settled, the packet - with the per-run prep named in its step, not as a stop"
      (let [a (next/next-action (assoc-in signed [:stage :preconditions 1 :status] "done"))]
        (is (= [11 "the Architect's session"] ((juxt :step :owner) a)))
        (is (str/starts-with? (:run a) "per run, first HP-3 (docs/stages/stage-1/stage-1-blueprint.md §0); then bb spec-from-blueprint"))))
    (testing "a row blocking another packet does not stop this one; it stops its own"
      (let [s (-> signed (assoc-in [:stage :preconditions 1 :status] "done") (assoc-in [:stage :merged] #{"t-01" "t-02"}))]
        (is (= "HP-4 is open in docs/stages/stage-1/stage-1-blueprint.md §0 and blocks t-04" (:because (next/next-action s))))))
    (testing "a run already under way is not held back"
      (is (= 12 (:step (next/next-action (assoc-in signed [:stage :runs] {"t-01" :running}))))))
    (is (= [] (next/preconditions "# A blueprint with no §0\n")))))

(deftest a-stage-plans-kind-is-its-kind-line
  (is (= "pre-release" (next/stage-kind "# Stage 5\n\n**Status:** PLANNED\n**Kind:** pre-release  \n**Version:** 0.1\n")))
  (is (= "walking skeleton" (next/stage-kind "**Kind:** walking skeleton\n")))
  (is (nil? (next/stage-kind "# Stage 1\n\nno kind here\n"))))

(deftest every-action-names-a-step-an-owner-a-command-and-a-reason
  (doseq [f [{:workspace? false} {:workspace? true :brief? false} ready
             (gate ready :stage/approved {:date "d" :by "Ada"}) (with-stage {:gates nil})]]
    (let [a (next/next-action f)]
      (is (contains? next/steps (:step a)))
      (is (every? (comp seq str a) [:name :owner :run :because])))))

;; ---------------------------------------------------------------------------
;; the facts, from a workspace on disk
;; ---------------------------------------------------------------------------

(deftest the-facts-are-read-from-the-builds-files
  (is (= {:workspace? false} (next/facts nil)))
  (is (= {} (next/run-statuses nil)) "a workspace.edn with no :workspace/records (the doctor reports it) is not a crash")
  (let [ws (str (fs/real-path (fs/create-temp-dir {:prefix "next-"})))
        build (fs/path ws "xyx-build")
        stages (fs/path build "docs" "stages")]
    (fs/create-dirs stages)
    (fs/create-dirs (fs/path build "runs"))
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/kit kit-dir :workspace/app "xyx-app" :workspace/build "xyx-build" :workspace/work "work"
                   :workspace/records "xyx-build/runs"}))
    (spit (str (fs/path build "docs" "source.md")) "# Source\n\n| S-1 | <the brief> |\n")
    (let [f (next/facts (workspace/find-workspace ws))]
      (is (true? (:workspace? f)))
      (is (false? (:brief? f)) "the template's mark still stands")
      (is (nil? (:stage f)))
      (is (= 1 (:step (next/next-action f))) "no brief yet: scoping, in the workspace"))
    (spit (str (fs/path build "docs" "source.md")) "# Source\n\n| S-1 | the brief, received |\n")
    (spit (str (fs/path stages "stage-N-template.md")) "# a template\n")
    (spit (str (fs/path stages "stage-N-gates-template.edn")) "{:stage/id \"<N>\"}")
    (fs/create-dirs (fs/path stages "stage-0"))
    (fs/create-dirs (fs/path stages "stage-1" "reviews"))
    (spit (str (fs/path stages "stage-0" "stage-0-plan.md")) "# Stage 0\n")
    (spit (str (fs/path stages "stage-0" "stage-0-gates.edn")) (pr-str {:stage/id "0" :stage/approved {:date "d" :by "Ada"} :stage/closed {:date "d"}}))
    (spit (str (fs/path stages "stage-1" "stage-1-plan.md")) "# Stage 1\n\n**Kind:** walking skeleton\n")
    (spit (str (fs/path stages "stage-1" "stage-1-gates.edn")) (pr-str {:stage/id "1" :stage/approved {:date "d" :by "Ada"} :blueprint/signed {:date "d" :by "Ada"}}))
    (spit (str (fs/path stages "stage-1" "stage-1-blueprint.md"))
          "# Blueprint\n\n```clojure\n{:task/id \"t1\"}\n```\n\n```clojure\n{:task/id \"t2\"}\n```\n")
    (spit (str (fs/path stages "stage-1" "reviews" "blueprint-review.edn")) "{:count 0}")
    (spit (str (fs/path build "runs" "r1.edn")) (pr-str {:run/id "r1" :task/id "t1" :run/status :merged :run/started-at (java.util.Date. 1000)}))
    (spit (str (fs/path build "runs" "r2.edn")) (pr-str {:run/id "r2" :task/id "t2" :run/status :escalated :run/started-at (java.util.Date. 2000)}))
    (spit (str (fs/path build "runs" "r3.edn")) (pr-str {:run/id "r3" :task/id "t2" :run/status :awaiting-merge :run/started-at (java.util.Date. 3000)}))
    (let [f (next/facts (workspace/find-workspace ws))
          s (:stage f)]
      (is (true? (:brief? f)))
      (is (= 1 (:id s)) "the highest-numbered stage folder; templates are not stages")
      (is (= "stage-1/stage-1-plan.md" (:plan s)))
      (is (= "walking skeleton" (:kind s)) "the plan's kind line")
      (is (= "stage-1/stage-1-blueprint.md" (:blueprint s)))
      (is (true? (:reviewed? s)))
      (is (= ["t1" "t2"] (:packets s)) "in the blueprint's order")
      (is (= #{"t1"} (:merged s)))
      (is (= {"t2" :awaiting-merge} (:runs s)) "the latest record's status, by its start")
      (is (= [] (:preconditions s)) "a blueprint with no §0 has no human prep")
      (is (seq (:plan-problems f)) "the plan check ran, and this workspace has no overlay, no layers.edn, no loop.edn")
      (let [a (next/next-action f)]
        (is (= 13 (:step a)) "a stage under way keeps its own step")
        (is (str/starts-with? (:also a) "bb plan-check: ") "and plan-check is said beside it")
        (is (str/includes? (:also a) "fix it before stage 2's plan")))
      (is (= 13 (:step (next/next-action (assoc f :plan-problems [])))) "with the plan passing: t2's run awaits its merge")
      (is (= "xyx-build" (:build f)) "the build folder, as the workspace names it")
      (let [a (next/next-action (-> f (assoc :plan-problems []) (assoc-in [:stage :merged] #{})))]
        (is (str/includes? (:run a) "bb spec-from-blueprint xyx-build/docs/stages/stage-1/stage-1-blueprint.md t1")
            "a printed command runs from the workspace folder, where every command is run")))))

;; ---------------------------------------------------------------------------
;; where we are: `bb next --where`
;; ---------------------------------------------------------------------------

(def ^:private build-docs (str (fs/path kit-dir "build-docs")))

(deftest the-overview-and-the-log-are-read-as-their-tables
  (let [overview (slurp (str (fs/path build-docs "00-overview.md")))
        log (slurp (str (fs/path build-docs "04-decision-log.md")))]
    (testing "the shipped templates, marks and all"
      (is (= 6 (count (next/table-rows overview "4"))) "every row of the stage map, the separator not one")
      (is (= [0 1 2] (keys (next/stage-map overview))) "a numbered stage only: not N, … or N+1")
      (is (= {:kind "spike" :name "" :status "PLANNED"} (dissoc (get (next/stage-map overview) 0) :delivers)))
      (is (= "the door" (:name (get (next/stage-map "## 4. M\n\n| Stage | Kind | Name | Status |\n|---|---|---|---|\n| **1** | x | the door | y |\n") 1)))
          "a Name column without (optional)")
      (is (= ["R1"] (next/open-risks overview)))
      (is (= [{:id "D1" :section "2"} {:id "D2" :section "2"}] (next/open-decisions log)) "D1 PROVISIONAL, D2 OPEN; the RESOLVED P0 rows and the blank ones are not"))
    (testing "a section ends at the next heading; one the text lacks has no rows"
      (is (= [] (next/table-rows "## 4. A\n\n### sub\n\n| a |\n|---|\n| 1 |\n" "4")))
      (is (= [{"a" "1" "b" ""}] (next/table-rows "### 6b. X\n\n| a | b |\n|---|---|\n| 1 |\n" "6b")) "a short row: its missing cells blank")
      (is (= [] (next/table-rows "## 4a. Risks\n\n| # |\n|---|\n| R9 |\n" "4")) "4 is not 4a")
      (is (nil? (next/section-lines "# none\n" "4"))))
    (testing "a filled log: RESOLVED that was PROVISIONAL is not open; bold or not, the Status cell is read"
      (is (= [{:id "D3" :section "2"} {:id "D4" :section "2"} {:id "P0-9" :section "4"}]
             (next/open-decisions
              (str "## 2. D\n\n| # | Decision | Status / Resolution |\n|---|---|---|\n"
                   "| D2 | x | **RESOLVED 2026-10-13: y** - was PROVISIONAL |\n| D3 | x | **PROVISIONAL: z** |\n"
                   "| D4 | x | **OPEN** - owner: stage 4 |\n"
                   "## 4. P0\n\n| # | Decision | Status / Resolution |\n|---|---|---|\n| P0-9 | x | PROVISIONAL - a note |\n")))))))

(deftest a-stages-kind-is-its-phase
  (is (= ["B PROVE" "C BUILD" "C BUILD" "D PRE-RELEASE" "E RELEASE" nil nil]
         (map next/phase ["spike" "walking skeleton" "increment" "pre-release" "release" "something else" nil]))))

(def ^:private where-at-stage-1
  {:workspace? true :brief? true :plan-problems [] :build "xyx-build" :kit "KIT"
   :stage {:id 1 :kind "walking skeleton" :plan "stage-1/stage-1-plan.md" :blueprint "stage-1/stage-1-blueprint.md" :reviewed? true
           :gates {:stage/id "1" :stage/approved {:date "2026-10-14" :by "Ada"} :blueprint/signed {:date "2026-10-15" :by "Ada"}}
           :packets ["t1" "t2" "t3"] :merged #{"t1"} :runs {} :preconditions []}
   :stages {0 {:gates {:stage/closed {:date "2026-10-13"}}} 1 {}}
   :map {0 {:kind "spike" :name "the proof" :delivers "the loop proved" :status "DONE"}
         1 {:kind "walking skeleton" :name "the front door" :delivers (apply str "FR-1 to FR-12: " (repeat 30 "a page, "))
            :status "IN PROGRESS"}
         2 {:kind "increment" :name "" :delivers "more" :status "NOT STARTED"}}
   :decisions [{:id "D3" :section "2"} {:id "D8" :section "2"} {:id "P0-20" :section "4"}]
   :risks ["R4" "R5"]})

(deftest the-block-says-where-the-project-stands
  (let [block (next/render-where where-at-stage-1 (next/next-action where-at-stage-1))
        lines (str/split-lines block)]
    (is (= "where: xyx-build - phase C BUILD, stage 1 of 0-2: walking skeleton, \"the front door\"" (first lines)))
    (is (= ["  stage map:   xyx-build/docs/00-overview.md §4 - status there: IN PROGRESS"
            (str "  stage goal:  FR-1 to FR-12: " (subs (apply str (repeat 30 "a page, ")) 0 81) "…")
            "               xyx-build/docs/stages/stage-1/stage-1-plan.md §1"
            "  blueprint:   xyx-build/docs/stages/stage-1/stage-1-blueprint.md - reviewed, signed 2026-10-15; 3 packets, 1 merged"
            "  done:        stage 0 closed 2026-10-13; stage 1 approved 2026-10-14, blueprint signed 2026-10-15"]
           (subvec lines 1 6)))
    (is (str/starts-with? (nth lines 6) "  next:        step 11 - a packet; owner: the Architect's session; run: bb spec-from-blueprint xyx-build/docs/"))
    (is (= ["  open:        decisions PROVISIONAL or OPEN: D3, D8 (§2), P0-20 (§4) - xyx-build/docs/04-decision-log.md"
            "               risks OPEN: R4, R5 - xyx-build/docs/00-overview.md §4a"]
           (subvec lines 7 9)))
    (is (= ["  read more:   phase KIT/method.md \"01 — The phase map\" · step n KIT/workflow.md \"The steps, and what each produces\""
            "               the project plan xyx-build/docs/00-overview.md (§3 lists its documents)"]
           (subvec lines 9 11))
        "the KIT's words in the workspace's KIT folder, the project plan in the build")
    (is (str/starts-with? (nth lines 11) "               in xyx-build/docs/: FR-n 01-requirements.md §6b"))
    (testing "every kind of ID is in the read more lines, and no line runs long"
      (doseq [{:keys [id section]} next/id-kinds]
        (is (str/includes? (str/join "\n" (drop 9 lines)) (str id " ")) id)
        (is (str/includes? block (str "§" section))))
      (is (every? #(< (count %) 140) (drop 9 lines)))))
  (testing "what cannot be read is said as such"
    (let [wf (-> where-at-stage-1 (assoc :map nil :decisions nil :risks []) (assoc-in [:stage :blueprint] nil)
                 (assoc-in [:stage :kind] nil))
          block (next/render-where wf (next/next-action wf))]
      (is (str/includes? block "phase (not read: the stage's kind), stage 1\n"))
      (is (str/includes? block "§4 (not there)"))
      (is (str/includes? block "stage goal:  (no Delivers cell on the stage map)"))
      (is (str/includes? block "blueprint:   (none yet)"))
      (is (str/includes? block "open:        decisions: (no xyx-build/docs/04-decision-log.md)\n               risks: none OPEN"))))
  (testing "more than six: the first six and a count"
    (let [wf (assoc where-at-stage-1 :decisions (mapv #(hash-map :id (str "D" %) :section "2") (range 1 10)))]
      (is (str/includes? (next/render-where wf (next/next-action wf))
                         "decisions PROVISIONAL or OPEN: D1, D2, D3, D4, D5, D6 (§2) and 3 more - "))))
  (testing "a closed stage is behind us: the block is the next stage's, not pulled yet"
    (let [wf (-> where-at-stage-1
                 (assoc-in [:stage :merged] #{"t1" "t2" "t3"})
                 (assoc-in [:stage :gates :stage/closed] {:date "2026-10-16"})
                 (assoc-in [:stages 1 :gates] {:stage/closed {:date "2026-10-16"}}))
          lines (str/split-lines (next/render-where wf (next/next-action wf)))]
      (is (= ["where: xyx-build - phase C BUILD, stage 1 closed 2026-10-16; next, stage 2 of 0-2: increment - not pulled yet"
              "  stage map:   xyx-build/docs/00-overview.md §4 - status there: NOT STARTED"
              "  stage goal:  more"
              "               (no plan yet in xyx-build/docs/stages/stage-2/)"
              "  blueprint:   (none yet)"
              "  done:        stage 0 closed 2026-10-13; stage 1 closed 2026-10-16"]
             (subvec lines 0 6)))
      (is (str/starts-with? (nth lines 6) "  next:        step 7 - the stage plan; owner: the person; run: pull stage 2")))
    (let [wf (-> where-at-stage-1 (assoc-in [:stage :gates :stage/closed] {:date "d"}) (update :map dissoc 2))]
      (is (str/includes? (next/render-where wf (next/next-action wf)) "§4 (no row for stage 2)") "past the map's last row: said")))
  (testing "a long also: line is cut, and says bb next has it whole"
    (let [wf (assoc where-at-stage-1 :plan-problems [(str "docs/x.md: " (str/join ", " (repeat 40 "a mark")) " left to fill")])
          also (some #(when (str/starts-with? % "  also:") %) (str/split-lines (next/render-where wf (next/next-action wf))))]
      (is (str/ends-with? also "… (bb next prints it whole)"))
      (is (< (count also) 220))))
  (testing "stage 0 with no kind read is the spike; before a stage, the phase and next only"
    (let [wf (-> where-at-stage-1 (assoc :map nil) (update :stage assoc :id 0 :kind nil))]
      (is (str/starts-with? (next/render-where wf (next/next-action wf)) "where: xyx-build - phase B PROVE, stage 0: spike\n")))
    (let [wf {:workspace? true :brief? true :plan-problems ["x"] :build "xyx-build"}]
      (is (= (str "where: xyx-build - phase B PROVE, before stage 0: the brief, then the plan\n"
                  "  next:        step 2 - the plan, for stage 0; owner: the Architect's session; run: /kit-plan; bb plan-check lists what is left, bb plan-review before the approval")
             (next/render-where wf (next/next-action wf)))))
    (is (str/starts-with? (next/render-where {:workspace? false} (next/next-action {:workspace? false}))
                          "where: phase A SETUP - no workspace yet\n  next:        step 0 - setup"))))

(deftest every-kind-of-id-has-its-home-in-the-templates
  ;; the table `bb next --where` prints and the templates it points into: a file renamed, a
  ;; section renumbered or its **IDs.** sentence lost there fails here, not in a person's reading
  (doseq [{:keys [id file section]} next/id-kinds
          :let [f (fs/path build-docs file)
                lines (when (fs/exists? f) (next/section-lines (slurp (str f)) section))]]
    (is (fs/exists? f) (str id ": " file))
    (is (some? lines) (str id ": " file " §" section))
    (is (some #(str/starts-with? % "**IDs.** ") lines) (str id ": " file " §" section " says what it defines"))))

(deftest the-kits-own-words-and-the-project-plan-have-their-homes
  ;; the KIT's root is the second root the block points into: a heading renamed there fails here
  (doseq [{:keys [id file heading]} next/kit-terms
          :let [f (fs/path kit-dir file)]]
    (is (fs/exists? f) (str id ": " file))
    (is (some #{(str "## " heading)} (str/split-lines (slurp (str f)))) (str id ": " file " \"" heading "\"")))
  (let [{:keys [file section heading]} next/project-plan
        lines (str/split-lines (slurp (str (fs/path build-docs file))))]
    (is (some #{(str "## " section ". " heading)} lines) "the overview's section that lists the plan's documents")))

(deftest where-reads-the-builds-documents
  (let [ws (str (fs/real-path (fs/create-temp-dir {:prefix "where-"})))
        build (fs/path ws "xyx-build")
        docs (fs/path build "docs")]
    (fs/create-dirs (fs/path docs "stages" "stage-0"))
    (fs/create-dirs (fs/path docs "stages" "stage-1"))
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/kit kit-dir :workspace/app "xyx-app" :workspace/build "xyx-build" :workspace/work "work"}))
    (spit (str (fs/path docs "source.md")) "# Source\n\n| S-1 | the brief, received |\n")
    (spit (str (fs/path docs "stages" "stage-0" "stage-0-gates.edn")) (pr-str {:stage/id "0" :stage/closed {:date "2026-10-13"}}))
    (spit (str (fs/path docs "stages" "stage-1" "stage-1-plan.md")) "# Stage 1\n\n**Kind:** walking skeleton\n")
    (let [wf (next/where-facts (workspace/find-workspace ws))]
      (is (= kit-dir (:kit wf)) "a KIT outside the workspace, said absolute: a relative path would climb out through ..")
      (is (= 1 (get-in wf [:stage :id])))
      (is (= {:stage/closed {:date "2026-10-13"}} (dissoc (get-in wf [:stages 0 :gates]) :stage/id)))
      (is (every? nil? ((juxt :map :decisions :risks) wf)) "no overview, no log: nil, said as such by the block"))
    (spit (str (fs/path docs "00-overview.md"))
          (str "## 4. Stage map\n\n| Stage | Kind | Name (optional) | Delivers | Cap | Status |\n|---|---|---|---|---|---|\n"
               "| **0** | spike | | the proof | $5 | DONE |\n| **1** | walking skeleton | the door | the home page | $5 | IN PROGRESS |\n\n"
               "## 4a. Risk register\n\n| # | Risk | Status |\n|---|---|---|\n| R1 | a | **RETIRED** |\n| R2 | b | OPEN |\n"))
    (spit (str (fs/path docs "04-decision-log.md"))
          "## 2. Architecture decisions (D)\n\n| # | Decision | Status / Resolution |\n|---|---|---|\n| D1 | x | **PROVISIONAL: y** |\n")
    (let [wf (next/where-facts (workspace/find-workspace ws))]
      (is (= "the home page" (get-in wf [:map 1 :delivers])))
      (is (= [{:id "D1" :section "2"}] (:decisions wf)))
      (is (= ["R2"] (:risks wf)))
      (is (str/includes? (next/render-where wf (next/next-action wf)) "where: xyx-build - phase C BUILD, stage 1 of 0-1: walking skeleton, \"the door\"")))
    (is (= {:workspace? false} (next/where-facts nil)))))

(deftest a-packet-is-merged-by-its-own-stages-run
  ;; stage 1 reuses stage 0's task id: stage 0's merged run of it is not stage 1's
  (let [ws (str (fs/real-path (fs/create-temp-dir {:prefix "next-reuse-"})))
        build (fs/path ws "xyx-build")
        stages (fs/path build "docs" "stages")
        records (fs/path build "runs")]
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/kit kit-dir :workspace/app "xyx-app" :workspace/build "xyx-build" :workspace/work "work"
                   :workspace/records "xyx-build/runs"}))
    (doseq [[i gates] [[0 {:stage/id "0" :stage/approved {:date "2026-10-13" :by "Ada"} :blueprint/signed {:date "2026-10-13" :by "Ada"}
                           :stage/closed {:date "2026-10-13"}}]
                       [1 {:stage/id "1" :stage/approved {:date "2026-10-16" :by "Ada"} :blueprint/signed {:date "2026-10-16" :by "Ada"}}]]
            :let [d (fs/path stages (str "stage-" i))]]
      (fs/create-dirs d)
      (spit (str (fs/path d (str "stage-" i "-plan.md"))) (str "# Stage " i "\n\n**Kind:** increment\n"))
      (spit (str (fs/path d (str "stage-" i "-blueprint.md"))) "# b\n\n```clojure\n{:task/id \"t-02-pages\"}\n```\n")
      (spit (str (fs/path d (str "stage-" i "-gates.edn"))) (pr-str gates)))
    (spit (str (fs/path build "docs" "source.md")) "# Source\n\n| S-1 | the brief, received |\n")
    (fs/create-dirs (fs/path stages "stage-1" "reviews"))
    (spit (str (fs/path stages "stage-1" "reviews" "blueprint-review.edn")) (pr-str {:cost 0.1}))
    (fs/create-dirs records)
    (spit (str (fs/path records "s0-t02.edn"))
          (pr-str {:run/id "s0-t02" :task/id "t-02-pages" :run/status :merged :run/started-at #inst "2026-10-13T15:00:00Z"}))
    (let [f (next/facts (workspace/find-workspace ws))]
      (is (= 1 (get-in f [:stage :id])))
      (is (= #{} (get-in f [:stage :merged])) "stage 0's run of the same id is not stage 1's")
      (is (= 11 (:step (next/next-action f))) "the packet is next, not the stage's end"))))
