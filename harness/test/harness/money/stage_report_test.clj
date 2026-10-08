(ns harness.money.stage-report-test
  "The stage report: one run as the stage counts it, the roll-up, the block
  in the stage plan and the check that holds it to the records. The costs
  are exact in binary on purpose, so the sums compare with `=`."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.money.stage-report :as sr]))

(defn- step [nm kind cost] {:step/name nm :step/kind kind :step/cost cost})

(def merged-run
  {:run/id "t1" :task/id "t1" :run/status :merged
   :run/steps [(step :provision :provision nil) (step :coder :dispatch 2.0) (step :tester :dispatch 0.5)
               (step :test :gate nil) (step :triage :dispatch 0.125) (step :tester-r1 :dispatch 0.25)
               (step :reviewer :dispatch 0.75)]
   :run/events [{:event/kind :spec-review :cost 0.25} {:event/kind :dispatch :role :coder :attempt 1}
                {:event/kind :triage :role :tester :attempt 2}
                {:event/kind :dispatch :role :reviewer :verdict {:verdict :approve}}
                {:event/kind :stopped :stop/kind :awaiting-merge :route nil :run/status :awaiting-merge}]})

(def escalated-run
  {:run/id "t2" :task/id "t2" :run/status :escalated
   :run/steps [(step :coder :dispatch 1.0) (step :triage :dispatch 0.125) (step :coder-r1 :dispatch 1.25)
               (step :triage-r1 :dispatch 0.125) (step :coder-r2 :dispatch 1.5) (step :reviewer :dispatch 0.5)]
   :run/events [{:event/kind :spec-review :cost 0.25} {:event/kind :spec-review :cost 0.25}
                {:event/kind :triage :role :coder :attempt 2} {:event/kind :triage :role :coder :attempt 3}
                {:event/kind :dispatch :role :reviewer :verdict {:verdict :reject :reasons ["x"]}}
                {:event/kind :stopped :stop/kind :spec-reviewed :route nil :run/status :dispatched}
                {:event/kind :stopped :stop/kind :capped :route :human :run/status :escalated}]})

(deftest one-run-as-the-stage-counts-it
  (let [s (sr/run-summary merged-run)]
    (is (= 3.625 (:cost s)) "every priced step")
    (is (= {:coder 2.0 :tester 0.75 :orchestrator 0.125 :reviewer 0.75} (:by-role s)) "retries under their role, triage the orchestrator's")
    (is (= 2 (:rounds s)) "one plus the triage events")
    (is (= 0.25 (:retry-cost s)) "the -rN steps")
    (is (= 0 (:rejections s)))
    (is (= {:person 1} (:stops s)) "awaiting-merge is the person's")
    (is (= 1 (:spec-reviews s)))
    (is (= 0.25 (:spec-review-cost s))))
  (let [s (sr/run-summary escalated-run)]
    (is (= 4.5 (:cost s)))
    (is (= 3 (:rounds s)))
    (is (= 2.875 (:retry-cost s)) "the coder's two retries and the second triage")
    (is (= 1 (:rejections s)))
    (is (= {:architect 1 :person 1} (:stops s)) "the spec review's stop is the Architect's, the cap's a person's")
    (is (= {:spec-reviewed 1 :capped 1} (:stop-kinds s)))
    (is (= 0.5 (:spec-review-cost s)) "two readings")))

(def facts
  {:id 1 :packets ["t1" "t2" "t3"] :records [merged-run escalated-run]
   :readings [["plan review" 0.5 1] ["blueprint review" 1.5 2]]
   :gates {:stage/id "1" :stage/approved {:date "2026-10-02" :cap "$20"}}
   :before {:id 0 :cost 3.25 :runs 2 :rounds 3 :stops 1}})

(deftest the-roll-up-and-its-block
  (let [s (sr/stage-summary facts)]
    (is (= [["t1" :merged] ["t2" :escalated] ["t3" :no-record]] (:packets s)) "in the blueprint's order, a packet without a record said")
    (is (= 2 (:run-count s)))
    (is (= 1 (:merged s)))
    (is (= 8.125 (:dispatch-money s)))
    (is (= [["plan review" 0.5 1] ["blueprint review" 1.5 2] ["spec reviews" 0.75 3]] (:readings s)) "the spec reviews counted from the runs")
    (is (= 2.75 (:reading-money s)))
    (is (= 10.875 (:money s)) "dispatch and readings")
    (is (= {:coder 5.75 :tester 0.75 :orchestrator 0.375 :reviewer 1.25} (:by-role s)))
    (is (= 5 (:rounds s)))
    (is (= 3.125 (:retry-cost s)))
    (is (= 1 (:rejections s)))
    (is (= {:person 2 :architect 1} (:stops s)))
    (is (= "$20" (:cap s)))
    (let [r (sr/render s)]
      (is (str/starts-with? r "Stage 1 report · 2 runs, 1 merged · $10.88 of cap $20 (approved 2026-10-02) · stage 0: $3.25, 2 runs, 3 rounds, 1 stop\n"))
      (is (str/includes? r "  Money by role: coder $5.75 · reviewer $1.25 · tester $0.75 · orchestrator $0.38 · readings $2.75 (plan review 1 $0.50, blueprint review 2 $1.50, spec reviews 3 $0.75)\n"))
      (is (str/includes? r "  Rounds: 5 over 2 runs (2.5 per run) · retries $3.13 (38% of the dispatch money) · 1 review rejected\n"))
      (is (str/includes? r "  Stops: architect 1 · person 2 — by kind: awaiting-merge 1, capped 1, spec-reviewed 1\n"))
      (is (str/ends-with? r "  Packets: t1 merged · t2 escalated · t3 no-record"))))
  (testing "the threat model's line: a pre-release stage's, or a signed one's, and no other's"
    (is (not (str/includes? (sr/render (sr/stage-summary (assoc facts :kind "increment"))) "Threat model"))
        "a block published before the line existed reads the same")
    (is (str/ends-with? (sr/render (sr/stage-summary (assoc facts :kind "pre-release")))
                        "\n  Threat model: not signed (a pre-release stage closes with :security/signed in its gates record)"))
    (is (str/ends-with? (sr/render (sr/stage-summary (-> facts (assoc :kind "pre-release")
                                                         (assoc-in [:gates :security/signed] {:date "2026-10-07" :by "the person"}))))
                        "\n  Threat model: signed 2026-10-07 by the person")))
  (testing "the security checks' line: a stage with the pack's record, or a pre-release stage, and no other"
    (let [checks {:security/at "2026-10-08T00:05:12.3" :counts {:ok 10 :warn 5 :fail 0 :skipped 3} :ok? true}]
      (is (not (str/includes? (sr/render (sr/stage-summary (assoc facts :kind "increment"))) "Security checks"))
          "a block published before the line existed reads the same")
      (is (str/ends-with? (sr/render (sr/stage-summary (assoc facts :kind "increment" :checks checks)))
                          "\n  Security checks: 10 ok, 5 warn, 0 fail, 3 skipped (2026-10-08)"))
      (is (str/includes? (sr/render (sr/stage-summary (assoc facts :kind "pre-release")))
                         "\n  Security checks: no record (the stage-end skill runs the security pack")
          "a pre-release stage says it has none")
      (is (str/includes? (sr/render (sr/stage-summary (assoc facts :kind "pre-release" :checks checks)))
                         (str "Security checks: 10 ok, 5 warn, 0 fail, 3 skipped (2026-10-08)\n"
                              "  Dependencies: no record (the stage-end skill runs the security pack's deps with --record docs/stages/stage-N-security-deps.edn)\n"
                              "  Secrets: no record (the stage-end skill runs the security pack's secrets with --record docs/stages/stage-N-security-secrets.edn)\n"
                              "  Threat model: not signed"))
          "before the signature, which reads it; a pre-release stage says which scan has no record")))
  (testing "the two scans' lines: a stage with their records, or a pre-release stage"
    (let [scan (fn [ok fail] {:security/at "2026-10-08T12:30:00" :counts {:ok ok :warn 0 :fail fail :skipped 0} :ok? (zero? fail)})
          r (sr/render (sr/stage-summary (assoc facts :kind "increment" :scans {:deps (scan 0 1) :secrets (scan 1 0)})))]
      (is (str/ends-with? r "\n  Dependencies: 0 ok, 0 warn, 1 fail, 0 skipped (2026-10-08)\n  Secrets: 1 ok, 0 warn, 0 fail, 0 skipped (2026-10-08)")))
    (is (not (str/includes? (sr/render (sr/stage-summary (assoc facts :kind "increment" :scans {:deps nil :secrets nil}))) "Dependencies"))
        "an increment without the records reads as before"))
  (testing "no cap, no stage before, no runs"
    (let [r (sr/render (sr/stage-summary {:id 0 :packets ["a"] :records [] :readings [] :gates nil :before nil}))]
      (is (str/starts-with? r "Stage 0 report · 0 runs, 0 merged · $0.00 · cap: not recorded (no :stage/approved in the gates record) · no stage before"))
      (is (str/includes? r "Money by role: no priced dispatch · readings $0.00"))
      (is (str/includes? r "Stops: none"))
      (is (str/includes? r "retries $0.00 (— of the dispatch money)")))))

(def plan-text
  "# Stage 1\n\n**Kind:** increment\n\n## 10. Dependency-ordered task list\n\n- t1\n\n## 11. Exit criteria (the stage's gate), and the cap\n\n**Cap:** $20.\n\n- a criterion\n\n## 12. Residual risks\n\nnone\n")

(deftest the-block-goes-into-the-exit-criteria-section-and-is-held-to-the-records
  (let [s (sr/stage-summary facts)
        block (sr/render s)
        text (sr/splice plan-text block)]
    (is (nil? (sr/published plan-text)))
    (is (= block (sr/published text)))
    (is (< (str/index-of text "- a criterion") (str/index-of text "```\nStage 1 report") (str/index-of text "## 12.")) "at the end of §11")
    (is (nil? (sr/drift "stage-1-x.md" text s)))
    (is (= "stage-1-x.md publishes no stage report and its packets have records: run `bb stage-report`" (sr/drift "stage-1-x.md" plan-text s)))
    (testing "re-rendered after a change: replaced in place, once"
      (let [s2 (sr/stage-summary (update facts :records conj (assoc merged-run :run/id "t3" :task/id "t3")))
            text2 (sr/splice text (sr/render s2))]
        (is (= 1 (count (re-seq #"Stage 1 report ·" text2))))
        (is (str/includes? (sr/published text2) "3 runs, 2 merged"))
        (is (str/includes? (sr/drift "stage-1-x.md" text s2) "does not match its records"))
        (is (nil? (sr/drift "stage-1-x.md" text2 s2)))))
    (testing "a plan with no exit-criteria heading takes the block at its end"
      (is (str/ends-with? (sr/splice "# Stage 1\n\nnothing\n" block) (str "```\n" block "\n```\n"))))))

;; ---------------------------------------------------------------------------
;; the facts, from a build on disk
;; ---------------------------------------------------------------------------

(deftest the-facts-are-read-from-the-builds-files
  (let [ws (str (fs/real-path (fs/create-temp-dir {:prefix "stage-report-"})))
        build (str (fs/path ws "xyx-build"))
        stages (fs/path build "docs" "stages")
        records (str (fs/path build "runs"))
        plan-0 (str (fs/path stages "stage-0-spike.md"))
        plan-1 (str (fs/path stages "stage-1-skeleton.md"))]
    (fs/create-dirs stages)
    (fs/create-dirs records)
    (fs/create-dirs (fs/path build "reviews" "stage-1"))
    (spit (str (fs/path stages "stage-N-template.md")) "# template\n")
    (spit plan-0 "# Stage 0\n")
    (spit (str (fs/path stages "stage-0-blueprint.md")) "# b\n\n```clojure\n{:task/id \"w0\"}\n```\n")
    (spit plan-1 plan-text)
    (spit (str (fs/path stages "stage-1-blueprint.md")) "# b\n\n```clojure\n{:task/id \"t1\"}\n```\n\n```clojure\n{:task/id \"t2\"}\n```\n")
    (spit (str (fs/path stages "stage-1-gates.edn")) (pr-str {:stage/id "1" :stage/approved {:date "d" :cap "$20"}}))
    (spit (str (fs/path records "t1.edn")) (pr-str merged-run))
    (spit (str (fs/path records "t2.edn")) (pr-str escalated-run))
    (spit (str (fs/path records "w0.edn")) (pr-str (assoc merged-run :run/id "w0" :task/id "w0")))
    (spit (str (fs/path build "reviews" "stage-1" "blueprint-review.edn")) (pr-str {:cost 1.0 :reviews [{:cost 1.0} {:cost 0.5}]}))
    (spit (str (fs/path build "reviews" "plan-review.edn")) (pr-str {:cost 0.75}))
    (spit (str (fs/path stages "stage-1-security-deps.edn"))
          (pr-str {:security/at "2026-10-08T12:30" :scan :deps :counts {:ok 1 :warn 0 :fail 0 :skipped 0} :ok? true :rows []}))
    (spit (str (fs/path stages "stage-1-security.edn"))
          (str ";; Written by the KIT's security pack. Do not edit.\n"
               (pr-str {:security/at "2026-10-08T00:05" :counts {:ok 9 :warn 2 :fail 0 :skipped 3} :ok? true :rows []})))
    (is (= [[0 "stage-0-spike.md"] [1 "stage-1-skeleton.md"]] (sr/stage-plans stages)))
    (is (nil? (sr/summary-for build records 2)) "no blueprint, nothing to roll up")
    (let [s (sr/summary-for build records 1)]
      (is (= ["t1" "t2"] (mapv :task/id (:runs s))) "the records of the blueprint's packets, in its order; w0 is stage 0's")
      (is (= [["blueprint review" 1.5 2] ["spec reviews" 0.75 3]] (:readings s)) "the history's readings; no plan review for this stage")
      (is (= "$20" (:cap s)))
      (is (= "increment" (:kind s)) "the plan's kind line")
      (is (= {:id 0 :cost 4.625 :runs 1 :rounds 2 :stops 1} (:before s)) "stage 0: its run, its spec review and the whole-set plan review")
      (is (= {:counts {:ok 9 :warn 2 :fail 0 :skipped 3} :date "2026-10-08"} (:security-checks s))
          "the security pack's record beside the gates record, its comment line read past")
      (is (= {:deps {:counts {:ok 1 :warn 0 :fail 0 :skipped 0} :date "2026-10-08"}} (:security-scans s))
          "the deps scan's record beside it; no secrets record, no entry"))
    (is (= ["stage-0-spike.md publishes no stage report and its packets have records: run `bb stage-report`"
            "stage-1-skeleton.md publishes no stage report and its packets have records: run `bb stage-report`"]
           (sr/check build records))
        "both stages have records and no block")
    (spit plan-1 (sr/splice (slurp plan-1) (sr/render (sr/summary-for build records 1))))
    (spit plan-0 (sr/splice (slurp plan-0) (sr/render (sr/summary-for build records 0))))
    (is (= [] (sr/check build records)))
    (spit (str (fs/path records "t3.edn")) (pr-str (assoc merged-run :run/id "t3" :task/id "t3")))
    (is (= [] (sr/check build records)) "a record for a task the blueprint does not name is not this stage's")
    (spit (str (fs/path records "t2.edn")) (pr-str (assoc escalated-run :run/status :merged)))
    (is (= ["stage-1-skeleton.md's stage report does not match its records: run `bb stage-report`"] (sr/check build records)))))
