(ns harness.setup.where-test
  "`bb next --where --edn`: the cells read, pure; then the whole map from a workspace on disk,
  read back equal."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.where :as where]
   [harness.setup.workspace :as workspace]))

(deftest a-cap-cell-is-a-number-and-the-rest-of-the-cell
  (is (= {:cap 20} (where/cap "$20")))
  (is (= {:cap 12 :cap-note "proposed"} (where/cap "$12 proposed")))
  (is (= {:cap 20 :cap-note "spent $6.95"} (where/cap "**$20**; spent $6.95")) "the first $ figure is the cap")
  (is (= {:cap 7.5} (where/cap "$7.5")))
  (is (= {} (where/cap "—")) "a dash is no cap, not one unread")
  (is (= {} (where/cap "")))
  (is (= {:cap nil :cap-note "not read: twelve dollars"} (where/cap "twelve dollars")) "said, not guessed"))

(deftest the-command-line-refuses-a-since-it-cannot-count-from
  (is (= {:edn? true :full? false :since "2026-10-13" :args ["--where" "--workspace" "w"]}
         (where/parse-args ["--where" "--edn" "--since" "2026-10-13" "--workspace" "w"])))
  (is (= {:edn? false :full? false :since nil :args ["--where"]} (where/parse-args ["--where"])) "the block's own form, untouched")
  (is (= {:error "--since needs a date after it, YYYY-MM-DD; got 2026-13-99"}
         (where/parse-args ["--where" "--edn" "--since" "2026-13-99"]))
      "a date's shape is not a date: the thirteenth month")
  (is (= {:error "--since needs a date after it, YYYY-MM-DD"} (where/parse-args ["--where" "--edn" "--since"])))
  (is (= {:edn? false :full? true :since "2026-10-13" :args ["--where"]} (where/parse-args ["--where" "--full" "--since" "2026-10-13"])))
  (is (= {:error "--since needs --edn or --full: the block does not count since a date"}
         (where/parse-args ["--where" "--since" "2026-10-13"]))
      "said, not dropped"))

(deftest money-is-rounded-to-the-cent-once
  (is (= 6.95 (where/cents 6.953277549999999)))
  (is (= 5.82 (where/cents 5.818458400000001)))
  (is (nil? (where/cents nil))))

(deftest a-stages-days-count-both-ends
  (is (= 1 (where/days "2026-10-13" "2026-10-13")) "approved and closed on one day")
  (is (= 4 (where/days "2026-10-13" "2026-10-16")))
  (is (nil? (where/days "2026-10-13" nil)) "a stage not closed has no length yet")
  (is (nil? (where/days "soon" "2026-10-16"))))

(deftest a-stage-map-cell-gives-its-ids-not-its-prose
  (is (= ["R1" "R2"] (where/ids-in "R1 and R2 held as the skeleton is built for real")))
  (is (= ["D7"] (where/ids-in "D7's open half, with how pages link")))
  (is (= ["D2" "S1" "P0-8" "RV-2"] (where/ids-in "D2, S1, P0-8 - each pass or fallback, RV-2, D2")) "once each, in order")
  (is (= [] (where/ids-in "—")))
  (is (= [] (where/ids-in "the tabs on a phone; FR-3 is a requirement, not a decision"))))

(deftest a-decisions-owner-is-the-sentence-after-owner
  (is (= "stage 4, the stage that builds search (S-2.8)"
         (where/owner-of "**OPEN** - owner: stage 4, the stage that builds search (S-2.8). It decides the index")))
  (is (= "the person, with Dr. Okafor's team (S-1.2), before the pre-release stage closes"
         (where/owner-of "**OPEN** - owner: the person, with Dr. Okafor's team (S-1.2), before the pre-release stage closes. GL-3 records it."))
      "a title's full stop does not end it")
  (is (= "the pre-release stage" (where/owner-of "**OPEN** - Owner: the pre-release stage")))
  (is (nil? (where/owner-of "**OPEN** - decided by the spike"))))

(deftest the-person-is-named-by-the-words-or-by-a-signers-name
  (is (where/names-the-person? [] "no spike: the person's, and the pre-release stage's exit criterion"))
  (is (where/names-the-person? ["Ada"] "Ada, before the release"))
  (is (not (where/names-the-person? ["Ada"] "Adam's call")) "a name as a word")
  (is (not (where/names-the-person? ["Ada"] "the pre-release stage"))))

;; THE PERSON'S WORDS: a look recorded or not was "mighty confusing" - with none recorded the page
;; counted from the last closed stage's approval and said "From 2026-10-20: stage 5 approved", read
;; as the state now. Since the last report, what happened.
(deftest since-counts-from-the-flag-or-the-last-report
  (let [inst #(java.time.Instant/parse %)]
    (is (= {:kind :flag :flag "2026-10-01" :at (inst "2026-09-30T18:30:00Z") :day "2026-10-01"}
           (where/since-from "2026-10-01" {:at "2026-10-15T09:00:00Z"} "Asia/Kolkata"))
        "--since: that day's 00:00 in the project's zone, before any report")
    (is (= {:kind :report :at (inst "2026-10-15T09:00:00Z") :day "2026-10-15"}
           (where/since-from nil {:at "2026-10-15T09:00:00Z"} "America/Chicago")))
    (is (= "2026-10-14" (:day (where/since-from nil {:at "2026-10-15T02:00:00Z"} "America/Chicago")))
        "the base's day is the project's: 02:00 UTC is the evening before in Chicago")
    (is (= {:kind :first} (where/since-from nil nil "UTC")) "no report before: the first")))

(deftest since-the-last-report-each-stage-once-with-its-state-now
  (let [inst #(java.time.Instant/parse %)
        base {:kind :report :at (inst "2026-10-26T18:00:00Z") :day "2026-10-26"}
        t (fn [at] {:at (inst at) :day (subs at 0 10) :day-only? false})
        rows [{:id 3 :state :closed :times {:closed (t "2026-10-26T17:00:00Z")}}
              {:id 4 :state :closed :times {:closed (t "2026-10-26T18:30:00Z")}}
              {:id 5 :state :closed :times {:approved (t "2026-10-20T10:00:00Z")
                                            :closed {:at (inst "2026-10-26T00:00:00Z") :day "2026-10-26" :day-only? true}}}
              {:id 6 :state :running :times {:approved (t "2026-10-27T09:00:00Z")}}
              {:id 7 :state :ahead :times {}}]
        settled {:dated [{:id "D7" :on "2026-10-25"} {:id "D8" :on "2026-10-26"} {:id "D9" :on "2026-10-27"}] :undated 1}
        run-rows [{:at (inst "2026-10-26T17:59:00Z") :status :merged :cost 1.0}
                  {:at (inst "2026-10-26T19:00:00Z") :status :merged :cost 2.5}
                  {:at (inst "2026-10-27T08:00:00Z") :status :stopped :cost 0.5}]
        s (where/since-row base rows settled run-rows)]
    (is (= [{:state "closed" :ids [4 5]} {:state "in progress" :ids [6]}] (:stages s))
        "stage 3 closed an hour before the report: not since it; stage 5's day-only gate on the report's day: counted")
    (is (= [{:id "D8" :on "2026-10-26" :same-day? true} {:id "D9" :on "2026-10-27"}] (:decisions-settled s))
        "the decision log carries days: the report's day counted, marked")
    (is (= [2 1 3.0] ((juxt :runs :merged :run-money) s)) "a run started a minute before the report is not since it")
    (is (not (:nothing? s)))
    (testing "made again with nothing new: nothing since"
      (is (:nothing? (where/since-row (assoc base :at (inst "2026-10-28T00:00:00Z") :day "2026-10-28")
                                      rows settled run-rows))))
    (testing "no report before: each stage with its state now, nothing counted"
      (let [f (where/since-row {:kind :first} rows settled run-rows)]
        (is (:first? f))
        (is (= [{:state "closed" :ids [3 4 5]} {:state "in progress" :ids [6]} {:state "ahead" :ids [7]}] (:stages f)))
        (is (not (contains? f :runs)) "not counted from the project's start")))
    (testing "the lines a person reads"
      (is (= ["Since 26 October 2026, 18:00 UTC (23:30 IST):" "- stage 4, stage 5: closed" "- stage 6: in progress"
              "- Decisions settled: D8 (same day), D9; 1 settled with no date, not counted"
              "- Runs: 2, 1 merged, $3.00 (runs only; a reading carries no time)"]
             (where/since-lines s "UTC" "Asia/Kolkata")))
      (is (= ["Nothing since 28 October 2026, 00:00 UTC."]
             (where/since-lines (where/since-row (assoc base :at (inst "2026-10-28T00:00:00Z") :day "2026-10-28") rows settled run-rows)
                                "UTC" nil)))
      (is (= ["First report - nothing to count from. Each stage now:" "- stage 3, stage 4, stage 5: closed"
              "- stage 6: in progress" "- stage 7: ahead"]
             (where/since-lines (where/since-row {:kind :first} rows settled run-rows) "UTC" nil))))))

(deftest what-waits-on-the-person
  (let [w (where/waits {:next {:step 10 :name "sign-off" :owner "the person" :run "set :blueprint/signed"}
                        :current {:blueprint "stage-1/stage-1-blueprint.md"
                                  :preconditions [{:id "HP-1" :prep "a key" :status "done"} {:id "HP-2" :prep "a font; its licence;  the subset" :status "open"}]}
                        :open {:decisions [{:id "D1" :section "2" :subject "Hosting" :owner "the person"}
                                           {:id "D2" :section "2" :subject "Search" :owner "stage 4"}]
                               :risks [{:id "R1" :subject "The deploy" :spike "no spike: Ada's"}]}
                        :names ["Ada"] :kit "KIT" :build "xyx-build"})]
    (is (= ["sign-off" "HP-2" "D1, Hosting" "R1, The deploy"] (mapv :what w)) "the gate in the next step, then the rest")
    (is (= {:what "HP-2" :detail "a font" :items ["its licence" "the subset"]} (select-keys (second w) [:what :detail :items]))
        "a prep row: its id, its first part, the rest split at the semicolons")
    (is (= (str "- **HP-2**: a font - the blueprint's human prep, not done (xyx-build/docs/stages/stage-1/stage-1-blueprint.md §0)"
                "\n  - its licence\n  - the subset")
           (where/wait-line (second w)))
        "the id bold, the rest of the cell as sub-bullets: a cell of nine parts read as one bold run of words")
    (is (= "- **D1, Hosting** - an open decision whose owner is the person: the person (xyx-build/docs/04-decision-log.md §2)"
           (where/wait-line (nth w 2))) "a wait with no parts, as before")
    (is (= ["KIT/workflow.md step 10" "xyx-build/docs/stages/stage-1/stage-1-blueprint.md §0"
            "xyx-build/docs/04-decision-log.md §2" "xyx-build/docs/00-overview.md §4a"]
           (mapv :where w))))
  (is (= [] (where/waits {:next {:step 8 :owner "the Architect's session"} :open {} :names []}))
      "nothing the person owes: an empty list"))

;; ---------------------------------------------------------------------------
;; the whole map, from a workspace on disk
;; ---------------------------------------------------------------------------

(def ^:private overview
  (str "# Overview\n\n## 4. Stage map\n\n"
       "| Stage | Kind | Name (optional) | Delivers | Risk it retires | Key decisions it closes | Cap | Status |\n"
       "|---|---|---|---|---|---|---|---|\n"
       "| **0** | spike | the proof | the loop proved | R1 | D1 | $20; spent $1.00 | **DONE** 2026-10-13 |\n"
       "| **1** | increment | the pages | FR-1 to FR-3 | - | D2 | $15 | IN PROGRESS |\n"
       "| **2** | pre-release | going live | the domain | R2 | D1 | — | NOT STARTED |\n\n"
       "## 4a. Risks\n\n"
       "| # | Risk | Prototype / spike that retires it | Runs in | Status |\n"
       "|---|---|---|---|---|\n"
       "| R1 | The loop | spike R1 | Stage 0 | RETIRED |\n"
       "| R2 | Publishing | no spike: Ada's, at the release | Stage 2 | OPEN |\n"))

(def ^:private decision-log
  (str "# Decisions\n\n## 2. Architecture decisions (D)\n\n"
       "| # | Decision | Status / Resolution |\n|---|---|---|\n"
       "| D1 | Hosting | **OPEN** - owner: the person. Before stage 2. |\n"
       "| D2 | Pages | **RESOLVED** 2026-10-14: one file each |\n"
       "| D3 | Fonts | **RESOLVED**: the system's |\n"))

(defn workspace-on-disk
  "A workspace with stage 0 closed and stage 1 approved, its KIT folder inside: the fixture this
  namespace and the roadmap page's read."
  []
  (let [ws (str (fs/real-path (fs/create-temp-dir {:prefix "where-"})))
        build (fs/path ws "xyx-build")
        docs (fs/path build "docs")
        s0 (fs/path docs "stages" "stage-0")
        s1 (fs/path docs "stages" "stage-1")
        runs (fs/path build "runs")]
    (doseq [d [s0 s1 (fs/path s1 "reviews") runs (fs/path ws "KIT")]] (fs/create-dirs d))
    (doseq [f ["method.md" "workflow.md"]] (spit (str (fs/path ws "KIT" f)) "# KIT\n"))
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/kit "KIT" :workspace/app "xyx-app" :workspace/build "xyx-build" :workspace/work "work"
                   :workspace/records "xyx-build/runs" :workspace/kit-version "0.6.8"}))
    (spit (str (fs/path docs "source.md")) "# Source\n\n| S-1 | the brief, received |\n")
    (spit (str (fs/path docs "00-overview.md")) overview)
    (spit (str (fs/path docs "04-decision-log.md")) decision-log)
    (spit (str (fs/path s0 "stage-0-plan.md")) "# Stage 0\n\n**Kind:** spike\n")
    (spit (str (fs/path s0 "stage-0-blueprint.md")) "# b\n\n```clojure\n{:task/id \"w0\"}\n```\n")
    (spit (str (fs/path s0 "stage-0-gates.edn"))
          (pr-str {:stage/id "0" :stage/approved {:date "2026-10-13" :cap "$20" :by "Ada"}
                   :blueprint/signed {:date "2026-10-13" :by "Ada"} :stage/closed {:date "2026-10-13"}}))
    (spit (str (fs/path runs "w0.edn"))
          (pr-str {:run/id "w0" :task/id "w0" :run/status :merged :run/wall-ms 120000
                   :run/started-at #inst "2026-10-13T10:00:00Z"
                   :run/steps [{:step/kind :dispatch :step/name "coder" :step/cost 1.0}] :run/events []}))
    (spit (str (fs/path s1 "stage-1-plan.md")) "# Stage 1\n\n**Kind:** increment\n")
    (spit (str (fs/path s1 "stage-1-gates.edn")) (pr-str {:stage/id "1" :stage/approved {:date "2026-10-14" :cap "$15" :by "Ada"}}))
    ;; with its time, as a reading has carried one since row 170: a time is an instant inside `bb next`
    (spit (str (fs/path s1 "reviews" "plan-review.edn")) (pr-str {:cost 0.25 :at "2026-10-14T09:00:00Z"}))
    (spit (str (fs/path s1 "reviews" "blueprint-review.edn")) (pr-str {:cost 0 :at "2026-10-14T10:00:00Z"}))
    ws))

(deftest the-map-is-read-from-the-builds-files
  (let [ws (workspace-on-disk)
        opts {:at "2026-10-17T12:00:00Z" :plan-commit "abc1234"}
        d (where/where-data (workspace/find-workspace ws) opts)
        [s0 s1 s2] (:stages d)]
    (testing "plain data: printed, it reads back equal, and the same files give the same map"
      (is (= d (edn/read-string (pr-str d))))
      (is (= d (where/where-data (workspace/find-workspace ws) opts))))
    (is (= {:dir "." :build "xyx-build" :app "xyx-app" :kit "KIT" :work "work"} (:workspace d)) "a KIT inside, relative")
    (is (= {:at "2026-10-17T12:00:00Z" :plan-commit "abc1234" :kit-version "0.6.8"} (:generated d)))
    (testing "the stages: the map's rows with their files and figures"
      (is (= {:id 0 :kind "spike" :state :closed :cap 20 :approved "2026-10-13" :closed "2026-10-13" :days 1
              :runs 1 :merged 1 :money 1.0 :run-minutes 2 :risks ["R1"] :decisions ["D1"]
              :plan "xyx-build/docs/stages/stage-0/stage-0-plan.md"}
             (select-keys s0 [:id :kind :state :cap :cap-note :approved :closed :days :runs :merged :money
                              :run-minutes :risks :decisions :plan]))
          "the gates record's cap wins over the map's cell, and its note with it")
      (is (= [:running 15 0.25 "readings only, no blueprint yet" nil] ((juxt :state :cap :money :money-note :days) s1))
          "a stage with no blueprint has its readings so far")
      (is (= [:ahead nil 0 ["D1"] ["R2"]] ((juxt :state :cap :runs :decisions :risks) s2)) "a dash in the Cap cell is no cap")
      (is (= "pre-release" (:kind s2))))
    (is (= [[:done []] [:done [0]] [:here [1]] [:ahead [2]] [:ahead []]] (mapv (juxt :status :stages) (:phases d))))
    (is (= [{:id "D1" :section "2" :status "OPEN" :subject "Hosting" :owner "the person"}] (get-in d [:open :decisions])))
    (is (= ["D1, Hosting" "R2, Publishing"] (mapv :what (:waits-on-the-person d)))
        "an owner \"the person\", and a spike naming a signer of the gates records")
    (is (= {:kind :first :first? true
            :stages [{:state "closed" :ids [0]} {:state "in progress" :ids [1]} {:state "ahead" :ids [2]}]}
           (:since d))
        "no report made yet: each stage with its state now, nothing counted")
    (is (= {:spent 1.25 :caps-approved 35 :caps-proposed 0 :by-stage [[0 1.0 20] [1 0.25 15]]} (:money d)))
    (testing "the last report made, from the reports log, is where since counts from"
      (let [w (workspace/find-workspace ws)]
        (is (= (str (fs/path ws "work" "where" "reports.edn")) (where/report! w {:at "2026-10-13T23:00:00Z"})))
        (where/report! w {:at "2026-10-14T09:30:00Z" :plan-commit "abc1234"})
        (is (= ["2026-10-13T23:00:00Z" "2026-10-14T09:30:00Z"] (mapv :at (where/reports w))) "lines added, none rewritten")
        (let [s (:since (where/where-data w opts))]
          (is (= "2026-10-14T09:30:00Z" (:from s)) "the last line's time, exact")
          (is (= [{:state "in progress" :ids [1]}] (:stages s))
              "stage 1's gate carries a day alone, the report's day: counted; stage 0 closed the day before: not")
          (is (= [{:id "D2" :on "2026-10-14" :same-day? true}] (:decisions-settled s)))
          (is (= 0 (:runs s)) "w0 started the day before"))))
    (is (= [8 "the Architect's session"] ((juxt :step :owner) (:next d))) "the next step is next-action's: stage 1's blueprint")))

(deftest a-run-whose-start-time-cannot-be-read-is-kept-and-not-counted-since
  (let [ws (workspace-on-disk)
        w (workspace/find-workspace ws)
        opts {:at "2026-10-17T12:00:00Z" :plan-commit "abc1234"}]
    (spit (str (fs/path ws "xyx-build" "runs" "w1.edn"))
          (pr-str {:run/id "w1" :task/id "w1" :run/status :merged :run/started-at "2026-10-14 morning"
                   :run/steps [{:step/kind :dispatch :step/name "coder" :step/cost 0.5}] :run/events []}))
    (where/report! w {:at "2026-10-13T23:00:00Z"})
    (is (= 0 (get-in (where/where-data w opts) [:since :runs])) "read, not thrown on; no time to count it by")))

(deftest a-money-total-is-rounded-once
  ;; four stages of 0.2925 each are $1.17, where the sum of their rounded cells said $1.16
  (let [rows [{:role :security-reviewer :model "m" :maker "OpenAI" :channel "OpenRouter" :cost 0.2925}]
        stages (for [id [1 2 3 4]] (assoc (where/breakdown rows) :id id :money 0.29))
        mb (where/money-by stages)]
    (is (= 1.17 (get (:totals mb) "OpenAI")))
    (is (= 1.17 (:total (first (:roles mb)))))
    (is (= 1.17 (:all mb)))))

(deftest the-fuller-account-says-the-map-in-seven-parts
  (let [ws (workspace-on-disk)
        d (where/where-data (workspace/find-workspace ws) {:at "2026-10-17T12:00:00Z" :plan-commit "abc1234"})
        lines (str/split-lines (where/render-full d))
        after (fn [heading n] (take n (rest (drop-while #(not= heading %) lines))))]
    (is (= ["# Where the project stands - xyx-build" "Generated 2026-10-17 from xyx-build at abc1234, KIT 0.6.8."] (take 2 lines)))
    (is (= ["## Where" "## The road" "## Money by provider" "## Money by role" "## Waits on you (2)" "## Since the last report"
            "## Open" "## Next" "## Read more"]
           (filter #(str/starts-with? % "## ") lines))
        "the seven parts in order, the money by provider and by role after the road")
    (is (= ["" (:headline d) ""
            "- A SETUP - done" "- B PROVE - done: stage 0 closed 2026-10-13"
            "- C BUILD - **you are here**: stage 1 running" "- D PRE-RELEASE - ahead: stage 2" "- E RELEASE - ahead"]
           (after "## Where" 8)))
    (is (= ["" "| Stage | Kind | Name | State | Cap | Spent | Days | Runs |" "|---|---|---|---|---|---|---|---|"
            "| 0 | spike | the proof | closed 2026-10-13 | $20 | $1.00 | 1 | 1, 1 merged |"
            "| 1 | increment | the pages | running, approved 2026-10-14 | $15 | $0.25 (readings only) | - | - |"
            "| 2 | pre-release | going live | ahead | - | - | - | - |"
            "" "Spent $1.25 against $35 of caps approved."]
           (after "## The road" 8)))
    (is (= ["" "- **D1, Hosting** - an open decision whose owner is the person: the person (xyx-build/docs/04-decision-log.md §2)"
            "- **R2, Publishing** - an open risk the person retires: no spike: Ada's, at the release (xyx-build/docs/00-overview.md §4a)"]
           (after "## Waits on you (2)" 3)))
    (is (= ["" "First report - nothing to count from. Each stage now:" "- stage 0: closed" "- stage 1: in progress" "- stage 2: ahead"]
           (after "## Since the last report" 5)))
    (is (= ["" "Decisions PROVISIONAL or OPEN (1) - xyx-build/docs/04-decision-log.md"
            "- **D1** Hosting - OPEN (§2), owner: the person"
            "" "Risks OPEN (1) - xyx-build/docs/00-overview.md §4a"
            "- **R2** Publishing - runs in Stage 2; no spike: Ada's, at the release"]
           (after "## Open" 6))
        "one line a decision and one a risk, under a line with the count and the file")
    (is (= "" (first (after "## Next" 2))))
    (is (str/starts-with? (second (after "## Next" 2)) "Step 8 - the blueprint; owner: the Architect's session; run: "))
    (is (= "- phase: KIT/method.md \"01 — The phase map\" · step n: KIT/workflow.md \"The steps, and what each produces\""
           (second (after "## Read more" 2))))
    (is (= "- the roadmap page, drawn: `bb roadmap-page`, then open work/where/roadmap.html" (last lines)))
    (testing "nothing waiting, and nothing since: each said"
      (let [s (where/render-full (assoc d :waits-on-the-person [] :since {:kind :report :from "2026-10-17T11:00:00Z" :nothing? true}))]
        (is (str/includes? s "## Waits on you (0)\n\nNothing: no step, gate, prep row, decision or risk is the person's now."))
        (is (str/includes? s "## Since the last report\n\nNothing since 17 October 2026, "))))))

(deftest a-stage-whose-records-name-no-model-says-so
  (is (= "gemini-4-argon (S3); not recorded (S4)"
         (where/models-by-stage [[3 {:models ["gemini-4-argon"]}] [4 {:models []}]]))))

;; THE PERSON'S SUMMARY. Stage by stage - `S0 claude-sonnet-5.5; S1 gemini-4-argon; S2
;; gemini-3.8-flash + gemini-4-argon; ...` - was hard to read; each model once, with its stages.
(deftest a-roles-models-are-summarized-each-once-with-its-stages
  (testing "the person's two rows"
    (is (= "claude-sonnet-5.5 (S0); gemini-4-argon (S1->S4); gemini-3.8-flash (S2)"
           (where/models-by-stage [[0 {:models ["claude-sonnet-5.5"]}] [1 {:models ["gemini-4-argon"]}]
                                   [2 {:models ["gemini-3.8-flash" "gemini-4-argon"]}] [3 {:models ["gemini-4-argon"]}]
                                   [4 {:models ["gemini-4-argon"]}]])))
    (is (= "gemini-3.8-flash (S0); claude-sonnet-5.5 (S1->S4)"
           (where/models-by-stage [[0 {:models ["gemini-3.8-flash"]}] [1 {:models ["claude-sonnet-5.5"]}]
                                   [2 {:models ["claude-sonnet-5.5"]}] [3 {:models ["claude-sonnet-5.5"]}]
                                   [4 {:models ["claude-sonnet-5.5"]}]]))))
  (testing "one model in every stage the role has: its name alone"
    (is (= "gpt-6.1-sol" (where/models-by-stage [[0 {:models ["gpt-6.1-sol"]}] [1 {:models ["gpt-6.1-sol"]}]
                                                 [3 {:models ["gpt-6.1-sol"]}]]))))
  (testing "a gap is listed, a single stage is no range"
    (is (= "gemini-4-argon (S1->S2, S4); claude-sonnet-5.5 (S3)"
           (where/models-by-stage [[1 {:models ["gemini-4-argon"]}] [2 {:models ["gemini-4-argon"]}]
                                   [3 {:models ["claude-sonnet-5.5"]}] [4 {:models ["gemini-4-argon"]}]]))))
  (testing "only unrecorded stages are still said as such"
    (is (= "not recorded (S0->S1)" (where/models-by-stage [[0 {:models []}] [1 {:models []}]])))))
