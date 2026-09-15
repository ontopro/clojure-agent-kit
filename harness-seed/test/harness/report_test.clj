(ns harness.report-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.report :as report]
   [harness.shapes :as shapes]))

(def ^:private gate-result
  {:gates/passed? false
   :gates/failed :lint
   :gates/report [{:gate :fmt :status :pass :exit 0 :out "" :ms 400}
                  {:gate :lint :status :fail :exit 2 :out "warn" :ms 600}
                  {:gate :test :status :skipped :exit nil :out "" :ms nil}]})

(deftest gate-steps-carry-timing-and-nothing-else
  (let [steps (report/gate-steps gate-result)]
    (is (= [:fmt :lint :test] (mapv :step/name steps)))
    (is (= [400 600 nil] (mapv :step/ms steps)))
    (testing "a gate calls no model, so it claims no model, provider or cost"
      (is (every? #(nil? (:step/model %)) steps))
      (is (every? #(nil? (:step/cost %)) steps)))
    (testing "every step validates"
      (is (every? #(shapes/valid-step? %) steps)))))

(deftest dispatch-step-reads-provenance-from-runner-meta
  (let [result {:status :done :files ["a.clj"] :stdout nil :cost 0.0312
                :runner/meta {:model "claude-sonnet-5" :provider "anthropic"}}
        s (report/dispatch-step :coder result 18400)]
    (is (= :coder (:step/name s)))
    (is (= "claude-sonnet-5" (:step/model s)))
    (is (= "anthropic" (:step/provider s)) "provider is separate from model on purpose")
    (is (= 0.0312 (:step/cost s)))
    (is (shapes/valid-step? s))
    (is (not (contains? s :step/cost-source)) "no source known, no key — older records replay unchanged"))
  (testing "a computed cost says where it came from"
    (let [s (report/dispatch-step :coder {:status :done :files [] :stdout nil :cost 0.05
                                          :runner/meta {:model "m" :cost-source :list-price}} 1)]
      (is (= :list-price (:step/cost-source s)))
      (is (shapes/valid-step? s))))
  (testing "ManualRunner knows none of it, and the step says so rather than guessing"
    (let [s (report/dispatch-step :coder {:status :done :files [] :stdout nil :cost nil} 900)]
      (is (nil? (:step/model s)))
      (is (nil? (:step/cost s)))
      (is (= 900 (:step/ms s))))))

(deftest totals-say-how-much-they-cover
  (let [steps [{:step/name :a :step/kind :gate :step/status :pass :step/ms 100}
               {:step/name :b :step/kind :dispatch :step/status :done
                :step/ms 200 :step/cost 0.5}]
        t (report/totals steps)]
    (is (= 300 (:ms t)))
    (is (= 0.5 (:cost t)))
    (testing "the counts are what stop a partial total reading as complete"
      (is (= 1 (:cost-known t)))
      (is (= 2 (:step-count t))))))

(deftest render-is-honest-about-what-it-does-not-know
  (let [run {:run/id "r-1" :task/id "t-07" :run/attempts 1 :run/status :done
             :run/cost nil :run/started-at (java.util.Date.)
             :run/steps (report/gate-steps gate-result)}
        out (report/render run)]
    (is (str/includes? out "t-07"))
    (testing "a run where nothing reported cost says so, rather than printing $0.0000 as fact"
      (is (str/includes? out "No step reported a cost")))
    (testing "a skipped step renders as absent, not as zero"
      (is (str/includes? out "—")))))

(deftest source-is-required-and-has-no-default
  ;; Rule :label-fabricated says label what you made up. This is what makes
  ;; forgetting impossible rather than merely forbidden: a caller who has not
  ;; thought about provenance gets a validation failure, not a silent
  ;; :measured.
  (is (not (shapes/valid-step? {:step/name :a :step/kind :gate
                                :step/status :pass :step/ms 1})))
  (is (shapes/valid-step? {:step/name :a :step/kind :gate :step/status :pass
                           :step/ms 1 :step/source :measured})))

(deftest a-precondition-is-its-own-kind-of-step
  ;; Run 4: harness.sigs rejected a task before either agent was dispatched,
  ;; and the enum had nowhere to put it. Not :provision, which builds a
  ;; workspace; not :gate, which judges what an agent wrote.
  (is (shapes/valid-step? {:step/name :sigs :step/kind :precondition
                           :step/status :pass :step/ms 56
                           :step/source :measured}))
  (is (not (shapes/valid-step? {:step/name :sigs :step/kind :precheck
                                :step/status :pass :step/ms 56
                                :step/source :measured}))
      "and the enum is still closed"))

(deftest the-table-stays-aligned-whatever-the-longest-kind-is
  ;; Run 4 added :precondition, the kind column was one character too narrow,
  ;; and every row after it lost its alignment while the rule — a literal 92 —
  ;; stayed exactly as long as it had been. The rule is now derived from the
  ;; header, and this is the invariant that says so.
  (let [step (fn [k] {:step/name :s :step/kind k :step/status :pass
                      :step/ms 10 :step/source :measured})
        out (report/render {:run/id "r" :task/id "t" :run/status :done
                            :run/steps (mapv step [:provision :precondition
                                                   :dispatch :gate-0 :gate])})
        lines (vec (str/split-lines out))
        rules (keep-indexed #(when (str/includes? %2 "───") %1) lines)
        ;; header, both rules, every row between them, and the total line
        table (subvec lines (dec (first rules)) (inc (inc (last rules))))
        widths (set (map count table))]
    (is (= 5 (count (filter #(str/starts-with? % "  s ") table)))
        "every kind produced a row")
    (is (= 1 (count widths))
        (str "the table has ragged rows: " (sort widths)))))

(deftest the-table-stays-aligned-whatever-the-longest-step-name-is
  ;; Run D7: a Reviewer's second dispatch is `reviewer-r1`, eleven characters
  ;; in a ten-character column, and its row ran one column right. The same
  ;; mistake as the kind column in run 4, one column to the left — a width
  ;; typed as a literal for data that varies.
  (let [step (fn [n] {:step/name n :step/kind :dispatch :step/status :done
                      :step/ms 10 :step/source :measured})
        out (report/render {:run/id "r" :task/id "t" :run/status :done
                            :run/steps (mapv step [:coder :reviewer-r1 :a-much-longer-step-name])})
        lines (vec (str/split-lines out))
        rules (keep-indexed #(when (str/includes? %2 "───") %1) lines)
        table (subvec lines (dec (first rules)) (inc (inc (last rules))))]
    (is (= 1 (count (set (map count table))))
        (str "the table has ragged rows: " (sort (set (map count table)))))
    (is (some #(str/includes? % "a-much-longer-step-name dispatch") table)
        "a long name is widened for, not truncated")))

(deftest a-computed-cost-is-marked-in-the-cell-and-the-footer
  ;; §10 lesson 11: a computed number in a real frame reads as measured. The
  ;; mark goes on the value, on the total, and in the footer.
  (let [step (fn [nm src] (cond-> {:step/name nm :step/kind :dispatch :step/status :done :step/ms 10
                                   :step/source :measured :step/model "m" :step/cost 0.5}
                            src (assoc :step/cost-source src)))
        out (report/render {:run/id "r" :task/id "t" :run/attempts 1 :run/status :done
                            :run/steps [(step :coder :list-price) (step :tester :reported)]})]
    (is (str/includes? out "~$0.500000"))
    (is (str/includes? out " $0.500000") "the reported one carries no mark")
    (is (str/includes? out "~$1.000000") "a total with a computed part is marked too")
    (is (str/includes? out "~ = computed from list price for 1 of them"))
    (testing "a step with no source renders exactly as before"
      (let [out (report/render {:run/id "r" :task/id "t" :run/attempts 1 :run/status :done
                                :run/steps [(step :coder nil)]})]
        (is (not (str/includes? out "~")))))))

(deftest a-real-microdollar-cost-does-not-render-as-free
  ;; The first real dispatch through harness.adapter cost $0.000003642 and
  ;; four decimals showed it as $0.0000. A row asserting a run was free when
  ;; it was not is the same class of lie as an unmarked fabricated number.
  (let [out (report/render
             {:run/id "r" :task/id "t" :run/status :done
              :run/steps [{:step/name :coder :step/kind :dispatch
                           :step/status :done :step/ms 1862
                           :step/cost 3.642E-6 :step/source :measured}]})]
    (is (str/includes? out "$0.000004"))
    (is (not (str/includes? out "$0.0000 ")))))

(deftest a-real-resolved-model-name-is-not-truncated
  ;; The model column was 26 and the truncation width was a separate literal,
  ;; so the first real dispatch cut `-20260423` — the part that distinguishes
  ;; the model that ANSWERED from the slug that was asked for, which is the
  ;; one thing the column exists to show.
  (let [out (report/render
             {:run/id "r" :task/id "t" :run/status :done
              :run/steps [{:step/name :coder :step/kind :dispatch
                           :step/status :done :step/ms 1
                           :step/model "deepseek/deepseek-v4-flash-20260423"
                           :step/source :measured}]})]
    (is (str/includes? out "deepseek/deepseek-v4-flash-20260423"))
    (is (not (str/includes? out "…")))))

(deftest a-wall-time-below-the-sum-is-called-a-broken-record
  ;; D6 printed "Wall time 7m14s ... the steps above account for 21m06s of
  ;; it", because the driver accumulated elapsed time across separate
  ;; invocations and lost some. Stating both as though they agreed hands a
  ;; reader a contradiction and lets them believe whichever half they read
  ;; first. The steps were measured; the wall figure was not.
  (let [run (fn [wall] {:run/id "r" :task/id "t" :run/status :done
                        :run/wall-ms wall
                        :run/steps [{:step/name :a :step/kind :gate
                                     :step/status :pass :step/ms 9000
                                     :step/source :measured}]})]
    (is (str/includes? (report/render (run 1000)) "inconsistent"))
    (is (str/includes? (report/render (run 1000)) "Trust the steps"))
    (is (str/includes? (report/render (run 20000)) "Wall time 20.0s"))
    (is (not (str/includes? (report/render (run 20000)) "inconsistent")))))

(deftest a-gate-is-always-measured
  (is (every? #(= :measured (:step/source %)) (report/gate-steps gate-result))))

(deftest a-dispatch-reporting-nothing-is-synthetic-not-measured
  (testing "a runner that knew a model measured something"
    (is (= :measured (:step/source
                      (report/dispatch-step :coder
                                            {:status :done :files [] :stdout nil :cost nil
                                             :runner/meta {:model "m"}} 10)))))
  (testing "ManualRunner reports neither model nor cost, so it measured nothing"
    (is (= :synthetic (:step/source
                       (report/dispatch-step :coder
                                             {:status :done :files [] :stdout nil :cost nil} 10)))))
  (testing "synthetic marks a step explicitly, whatever it was built from"
    (is (= :synthetic (:step/source (report/synthetic {:step/source :measured}))))))

(deftest fabricated-numbers-are-marked-where-they-are-shown
  ;; The failure this guards: plausible hand-written values under an accurate
  ;; caption read as a measurement. So the mark goes on the row AND on each
  ;; number, and on the total, which is fabricated the moment an input is.
  (let [fake (report/synthetic
              (report/dispatch-step :coder
                                    {:status :done :files [] :stdout nil :cost 0.0312
                                     :runner/meta {:model "claude-sonnet-5"
                                                   :provider "anthropic"
                                                   :tokens 18432}}
                                    18400))
        out (report/render {:run/id "r" :task/id "t" :run/attempts 1 :run/status :done
                            :run/cost nil :run/started-at (java.util.Date.)
                            :run/steps [fake]})
        table (->> (str/split-lines out)
                   (filter #(str/includes? % "coder"))
                   first)]
    (testing "every fabricated value is bracketed"
      (is (str/includes? table "[claude-sonnet-5]") "a made-up model")
      (is (str/includes? table "[anthropic]") "a made-up provider reads as a fact")
      (is (str/includes? table "[18.4s]"))
      (is (str/includes? table "[$0.031200]"))
      (is (str/includes? table "[18,432]") "tokens too"))
    (testing "the total is bracketed — it was computed from a made-up input"
      (is (str/includes? out "[18.4s]")))
    (testing "and the footer says what the brackets mean"
      (is (str/includes? out "[bracketed] = SYNTHETIC: 1 of 1 steps")))))

(deftest the-provider-cell-says-which-tier-answered
  ;; The Tester moved to OpenAI's flex endpoint after bake-off B1. Flex and
  ;; standard both report provider "OpenAI", at different prices, so without
  ;; the tier a flex run's report reads exactly like a standard one.
  (let [result (fn [tier] {:status :done :files [] :stdout nil :cost 0.0354
                           :runner/meta (cond-> {:model "openai/gpt-5.6-sol-20260709"
                                                 :provider "OpenAI" :tokens 36917}
                                          tier (assoc :service-tier tier))})
        row (fn [step]
              (->> (report/render {:run/id "r" :task/id "t" :run/status :done
                                   :run/steps [step]})
                   str/split-lines
                   (filter #(str/includes? % "tester "))
                   first))]
    (testing "dispatch-step carries the tier, and the step is still a valid RunStep"
      (let [s (report/dispatch-step :tester (result "flex") 118000)]
        (is (= "flex" (:step/service-tier s)))
        (is (shapes/valid-step? s))))
    (testing "a non-default tier is shown beside the provider"
      (is (str/includes? (row (report/dispatch-step :tester (result "flex") 118000))
                         "OpenAI flex")))
    (testing "\"default\" says nothing, and nothing is added for it"
      (let [r (row (report/dispatch-step :tester (result "default") 118000))]
        (is (str/includes? r "OpenAI "))
        (is (not (str/includes? r "default")))))
    (testing "a runner that saw no tier writes no key, so older records render as they did"
      (is (not (contains? (report/dispatch-step :tester (result nil) 118000)
                          :step/service-tier))))
    (testing "a fabricated step brackets the provider and tier as one value"
      (is (str/includes? (row (report/synthetic (report/dispatch-step :tester (result "flex") 118000)))
                         "[OpenAI flex]")))))

(deftest a-fully-measured-run-carries-no-marks
  (let [out (report/render {:run/id "r" :task/id "t" :run/attempts 1 :run/status :done
                            :run/cost nil :run/started-at (java.util.Date.)
                            :run/steps (report/gate-steps gate-result)})]
    (is (not (str/includes? out "SYNTHETIC")))
    (is (not (str/includes? out "[")))))

(deftest absent-is-not-the-same-as-fabricated
  ;; A synthetic step with no model has nothing to mark there. Marking the "—"
  ;; would say a missing value was made up, which makes the mark mean less
  ;; everywhere else.
  (let [out (report/render
             {:run/id "r" :task/id "t" :run/attempts 1 :run/status :done
              :run/cost nil :run/started-at (java.util.Date.)
              :run/steps [(report/synthetic
                           {:step/name :provision :step/kind :provision
                            :step/status :done :step/ms 1200 :step/source :measured})]})
        table (->> (str/split-lines out) (filter #(str/includes? % "provision")) first)]
    (is (str/includes? table "[1.2s]") "the fabricated number is bracketed")
    (is (not (str/includes? table "[—]")) "absent is not fabricated")))

(deftest tokens-come-from-runner-meta
  ;; :tokens lived in :runner/meta upstream — one of the five undeclared keys
  ;; that motivated closing AgentResult (PROVENANCE.md). It reads from the same
  ;; hatch as :model and :provider rather than getting a top-level key.
  (let [s (report/dispatch-step :coder
                                {:status :done :files [] :stdout nil :cost 0.01
                                 :runner/meta {:model "m" :tokens 1234}} 100)]
    (is (= 1234 (:step/tokens s)))
    (is (shapes/valid-step? s)))
  (testing "a runner reporting no tokens leaves the column absent, not zero"
    (let [s (report/dispatch-step :coder
                                  {:status :done :files [] :stdout nil :cost 0.01
                                   :runner/meta {:model "m"}} 100)]
      (is (nil? (:step/tokens s))))))

;; ---------------------------------------------------------------------------
;; The drift gate. These tests exist because the gate is the thing standing
;; between RUNS.md's published figures and nobody checking them, and a gate that
;; passes for the wrong reason is worse than no gate — it closes the question.

(def ^:private a-run
  {:run/id "d9" :task/id "t-09" :run/status :done
   :run/steps [{:step/name :coder :step/kind :dispatch :step/status :done
                :step/ms 1200 :step/model "m" :step/provider "p"
                :step/cost 0.5 :step/tokens 100 :step/source :measured}]})

(defn- doc
  "A markdown document publishing `runs`' reports, the way RUNS.md does."
  [& runs]
  (str "# Runs\n\nProse that mentions Run d9 · outside a block.\n\n"
       (str/join "\n" (for [r runs] (str "```\n" (report/render r) "\n```\n")))))

(deftest published-reports-are-found-by-their-own-first-line
  (let [found (report/published-reports (doc a-run))]
    (is (= #{"d9"} (set (keys found))))
    (testing "prose naming a run is not a published report"
      (is (= 1 (count found))))
    (testing "a fenced block that is not a report is ignored"
      (is (empty? (report/published-reports "```\nbb gates\n```"))))))

(deftest a-labelled-fence-does-not-swallow-the-report-after-it
  ;; NOTES.md row 19: a ```sh block's closing fence opened the next match.
  (let [labelled (str "```sh\nbb report-check\n```\n\nProse.\n\n" (doc a-run))]
    ;; Drift against the record, not just the key: mis-paired, a block can
    ;; swallow prose that mentions the run and still yield a d9 key.
    (is (empty? (report/drift labelled {"d9" a-run})) "found, and it is the report")
    (testing "so a table with no record after one is drift, not silence"
      (is (= [:no-record] (mapv :problem (report/drift labelled {})))))
    (testing "a labelled block that looks like a report is not one"
      (is (empty? (report/published-reports
                   (str "```text\n" (report/render a-run) "\n```\n")))))
    (testing "a line inside a block that ends in backticks closes nothing"
      (is (empty? (report/drift (str "```text\nwrap code in ```\n```\n\n" (doc a-run))
                                {"d9" a-run}))))
    (testing "backticks inside a line open nothing"
      (is (empty? (report/drift (str "Inline ``` fences ``` in prose.\n\n" (doc a-run))
                                {"d9" a-run}))))))

(deftest a-document-matching-its-records-has-no-drift
  (is (empty? (report/drift (doc a-run) {"d9" a-run}))))

(deftest drift-is-caught-in-both-directions
  (testing "a published figure that no record backs cannot be checked"
    (is (= [{:run/id "d9" :problem :no-record}]
           (report/drift (doc a-run) {}))))
  (testing "a record nobody publishes is the other half — without this the gate
            could be satisfied by deleting the evidence"
    (is (= [{:run/id "d9" :problem :no-block}]
           (report/drift "# Runs\n" {"d9" a-run})))))

(deftest a-changed-number-is-drift
  (let [tampered (str/replace (doc a-run) "$0.5000" "$0.1000")]
    (is (not= tampered (doc a-run)) "the mutation has to actually change the document")
    (is (= [{:run/id "d9" :problem :drift}]
           (report/drift tampered {"d9" a-run})))))

(deftest a-record-that-is-not-a-record-says-so
  (testing "the original failure: a raw agent result filed as a record. render
            throws an NPE whose message is nil, which is no use to anyone"
    (is (= [{:run/id "d9" :problem :not-a-record}]
           (report/drift (doc a-run) {"d9" {:status :done :files [] :cost nil}}))))
  (testing "a real record under the wrong file name is a different fault"
    (is (= [{:run/id "d9" :problem :id-mismatch :detail "d4"}]
           (report/drift (doc a-run) {"d9" (assoc a-run :run/id "d4")})))))

(deftest a-record-that-will-not-render-reports-the-cause
  (let [broken (assoc-in a-run [:run/steps 0 :step/ms] "not-a-number")
        [{:keys [problem detail]}] (report/drift (doc a-run) {"d9" broken})]
    (is (= :unrenderable problem))
    (is (string? detail) "a nil message tells the reader nothing")
    (is (seq detail))))

(deftest every-problem-renders-a-line-that-names-the-fix
  (doseq [problem [:no-record :no-block :not-a-record :id-mismatch :unrenderable :drift]]
    (let [line (report/problem-line {:run/id "d9" :problem problem :detail "x"}
                                    "RUNS.md" "runs")]
      (is (string? line) (str problem " has no message"))
      (is (str/includes? line "d9"))
      (testing (str problem " says where to look")
        (is (or (str/includes? line "RUNS.md") (str/includes? line "runs")))))))
