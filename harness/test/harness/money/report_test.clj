(ns harness.money.report-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.shapes :as shapes]
   [harness.money.report :as report]
   [harness.setup.workspace :as workspace]))

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
  (testing "the completion ids travel on the step, for a later reprice"
    (let [s (report/dispatch-step :coder {:status :done :files [] :stdout nil :cost nil
                                          :runner/meta {:model "m" :generation-ids ["gen-1" "gen-2"]}} 1)]
      (is (= ["gen-1" "gen-2"] (:step/generation-ids s)))
      (is (shapes/valid-step? s)))
    (is (not (contains? (report/dispatch-step :coder {:status :done :runner/meta {:generation-ids []}} 1) :step/generation-ids))
        "none known, no key"))
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

(deftest a-dispatch-records-its-rounds-and-the-report-says-how-close-to-the-limit
  ;; The record held one generation id per round and the report never counted
  ;; them; the first real project counted by hand, and read "at the limit" off
  ;; the console's capped? alone.
  (let [s (report/dispatch-step :tester {:status :done :files [] :stdout nil :cost 0.1
                                         :runner/meta {:model "m" :iterations 24}} 1)]
    (is (= 24 (:step/rounds s)))
    (is (shapes/valid-step? s))
    (is (not (contains? (report/dispatch-step :tester {:status :done :runner/meta {:model "m"}} 1) :step/rounds))
        "none known, no key"))
  (let [step (fn [nm rounds] {:step/name nm :step/kind :dispatch :step/status :done :step/ms 1
                              :step/source :measured :step/cost 0.1 :step/rounds rounds})
        run {:run/steps [(step :coder 16) (step :tester 24) (step :tester-r2 24)]
             :run/roles {:coder {:model "m" :max-rounds 30} :tester {:model "t" :max-rounds 24}}}
        [line] (report/dispatch-round-lines run)]
    (is (= "  Dispatch rounds: coder 16 of 30 · tester 24 of 24, AT THE LIMIT · tester-r2 24 of 24, AT THE LIMIT" line)
        "a retry reads its role's limit too")
    (is (= ["  Dispatch rounds: coder 16 · tester 24 · tester-r2 24"]
           (report/dispatch-round-lines (dissoc run :run/roles)))
        "no limit on the record, no claim about one"))
  (testing "a record from before :step/rounds renders as it did"
    (let [old {:run/id "d9" :task/id "t-09" :run/status :awaiting-merge :run/attempts 1 :run/cost 0.5
               :run/started-at (java.util.Date.)
               :run/steps [{:step/name :coder :step/kind :dispatch :step/status :done :step/ms 1200
                            :step/model "m" :step/provider "p" :step/cost 0.5 :step/source :measured}]}]
      (is (nil? (report/dispatch-round-lines old)))
      (is (not (str/includes? (report/render old) "Dispatch rounds"))))))

(deftest a-repriced-step-with-no-provider-counts-in-the-openrouter-sum
  ;; Three steps priced later by `bb reprice`, each without a provider, summed to
  ;; $0.000000 beside the key's $0.17: `some->` over a nil provider dropped them.
  (let [step (fn [nm cost provider] {:step/name nm :step/kind :dispatch :step/status :done :step/ms 1
                                     :step/source :measured :step/cost cost :step/provider provider
                                     :step/cost-source :repriced})
        run {:run/steps [(step :coder 0.10 nil) (step :tester 0.05 nil) (step :reviewer 0.02 "Anthropic API")]
             :run/events [{:event/kind :balance :when :start :openrouter {:remaining 80.60}}
                          {:event/kind :balance :when :record :openrouter {:remaining 80.43}}]}
        out (str/join "\n" (report/money-lines run))]
    (is (str/includes? out "against $0.150000 summed from this run's OpenRouter steps")
        "the two steps nobody named a host for, not the one Anthropic's API served")
    (is (str/includes? out "Anthropic API spend $0.020000"))))

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
  ;; An early run: harness.contract.sigs rejected a task before either agent was dispatched,
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
  ;; An early run added :precondition, the kind column was one character too narrow,
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
  ;; A Reviewer's second dispatch is `reviewer-r1`, eleven characters in a
  ;; ten-character column, and its row ran one column right. The same mistake
  ;; as the kind column one column to the left — a width
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
  ;; The first real dispatch through harness.models.adapter cost $0.000003642 and
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
  ;; A run printed "Wall time 7m14s ... the steps above account for 21m06s of
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
  ;; The Tester once moved to OpenAI's flex endpoint. Flex and
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
  {:run/id "d9" :task/id "t-09" :run/status :awaiting-merge
   :run/attempts 1 :run/cost 0.5 :run/started-at #inst "2026-09-16T10:00:00.000-00:00"
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
  ;; A ```sh block's closing fence once opened the next match.
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
  (doseq [problem [:no-record :no-block :not-a-record :id-mismatch :unrenderable :drift
                   :invalid-record]]
    (let [line (report/problem-line {:run/id "d9" :problem problem :detail "x"}
                                    "RUNS.md" "runs")]
      (is (string? line) (str problem " has no message"))
      (is (str/includes? line "d9"))
      (testing (str problem " says where to look")
        (is (or (str/includes? line "RUNS.md") (str/includes? line "runs")))))))

(deftest a-record-that-fails-its-own-schema-is-a-gate-failure
  ;; `TaskRun` was in the seed from the start with no caller, and every
  ;; record committed in that time failed it. The gate that checks the tables
  ;; checks the records against the schema they claim to be.
  (is (empty? (report/invalid-records {"d9" a-run})) "a good record raises nothing")
  (testing "a status outside RunStatus — the vocabulary the old records used"
    (let [[{:keys [problem detail] rid :run/id}]
          (report/invalid-records {"d9" (assoc a-run :run/status :done)})]
      (is (= :invalid-record problem))
      (is (= "d9" rid))
      (is (contains? detail :run/status))))
  (testing "a missing cost or start time"
    (is (= [:invalid-record]
           (mapv :problem (report/invalid-records {"d9" (dissoc a-run :run/cost)}))))
    (is (= [:invalid-record]
           (mapv :problem (report/invalid-records {"d9" (dissoc a-run :run/started-at)})))))
  (testing "a file that is no record at all is left to `drift`, not reported twice"
    (is (empty? (report/invalid-records {"x" {:status :done :files []}})))))

;; ---------------------------------------------------------------------------
;; a call that failed at the provider is measured, not made up
;; ---------------------------------------------------------------------------

(deftest a-dispatch-that-failed-at-the-provider-is-not-synthetic
  ;; Two Reviewer dispatches refused with HTTP 402 had no model and no cost, and
  ;; were rendered "SYNTHETIC: made up, not measured". Each was a real request,
  ;; timed and answered.
  (let [failed {:status :failed :files [] :cost nil :stdout nil
                :runner/meta {:error {:harness/error :api-error :status 402 :message "requires more credits"}}}]
    (is (= :measured (:step/source (report/dispatch-step :reviewer failed 273))))
    (is (= :fail (:step/status (report/dispatch-step :reviewer failed 273)))))
  (testing "a result with no model, no cost and no error is still synthetic: that is a hand-made example"
    (is (= :synthetic (:step/source (report/dispatch-step :coder {:status :done :files [] :cost nil} 5))))))

;; ---------------------------------------------------------------------------
;; the performance section: every figure from a timestamp or a per-step measurement
;; ---------------------------------------------------------------------------

(defn- at [ms] (java.util.Date. (long ms)))

(def ^:private timed-run
  ;; 0s dependents · 10s dispatch · 20s stopped (a person reads for 100s) · 120s continued ·
  ;; 130s triage → tester-r1 · 200s stopped, nothing after
  {:run/id "p-1" :task/id "t-p" :run/attempts 2 :run/status :escalated :run/cost 0.5
   :run/started-at (at 0)
   :run/steps [{:step/name :provision :step/kind :provision :step/status :pass :step/ms 4000 :step/source :measured}
               {:step/name :coder :step/kind :dispatch :step/status :done :step/ms 30000 :step/cost 0.3
                :step/model "m-a" :step/provider "Anthropic API" :step/source :measured}
               {:step/name :fmt :step/kind :gate :step/status :pass :step/ms 500 :step/source :measured}
               {:step/name :tester-r1 :step/kind :dispatch :step/status :done :step/ms 20000 :step/cost 0.2
                :step/model "m-g" :step/provider "Google" :step/source :measured :step/cost-source :list-price}]
   :run/events [{:event/kind :dependents :at (at 0)}
                {:event/kind :balance :when :start :at (at 1000) :openrouter {:limit 40.0 :usage 10.0 :remaining 30.0}}
                {:event/kind :dispatch :role :coder :model "m-a" :at (at 10000)
                 :usage {:in 100 :out 50 :cache-read 300 :cache-write 100}}
                {:event/kind :stopped :stop/kind :paused :at (at 20000)}
                {:event/kind :continued :at (at 120000)}
                {:event/kind :triage :role :tester :attempt 2 :decision "the test over-constrains the contract" :at (at 130000)}
                {:event/kind :dispatch :role :tester :model "m-g" :at (at 160000) :usage {:in 2000 :out 300}}
                {:event/kind :balance :when :record :at (at 190000) :openrouter {:limit 40.0 :usage 10.25 :remaining 29.75}}
                {:event/kind :stopped :stop/kind :capped :at (at 200000)}]
   :run/roles {:coder {:model "m-a" :family :anthropic :effort "low"}
               :tester {:model "m-g" :family :google :effort "medium"}}})

(deftest time-is-derived-from-absolute-timestamps-and-waiting-is-the-gap-after-a-stop
  (let [out (report/render timed-run)]
    (is (str/includes? out "Time: 3m20s total, 1m40s waiting on a person over 2 stops, 1m40s active."))
    (is (str/includes? out "stop paused           waited 1m40s"))
    (is (str/includes? out "stop capped           no event after it"))
    (is (str/includes? out "the steps account for 54.5s"))
    (is (str/includes? out "4.5s is mechanical"))))

(deftest money-is-grouped-three-ways-and-the-balance-difference-sits-beside-the-summed-cost
  (let [out (report/render timed-run)]
    (is (str/includes? out "Money: $0.500000 over 2 priced steps, 1 of them computed from list price."))
    (is (str/includes? out "by provider  Anthropic API $0.300000 · Google $0.200000"))
    (is (str/includes? out "by role      coder $0.300000 · tester $0.200000"))
    (is (str/includes? out "OpenRouter balance $30.00 at start → $29.75 at record: $0.25 spent by the key, against $0.200000 summed"))
    (is (str/includes? out "differ by more than two cents"))
    (is (str/includes? out "Anthropic API spend $0.300000"))))

(deftest tokens-rounds-and-roles-come-from-the-record
  (let [out (report/render timed-run)]
    (is (str/includes? out "m-a                                  100 / 50 / 300 / 100  — 60% of the prompt tokens were cache reads"))
    (is (str/includes? out "Rounds: 2 — 1 retry:"))
    (is (str/includes? out "tester-r1    $0.200000  the test over-constrains the contract"))
    (is (str/includes? out "coder          anthropic    m-a                                  effort low"))))

(deftest a-record-from-before-the-fields-says-not-recorded-and-still-renders
  (let [old (-> timed-run
                (update :run/events (fn [es] (->> es (remove #(= :balance (:event/kind %))) (mapv #(dissoc % :at)))))
                (dissoc :run/roles))
        out (report/render old)]
    (is (str/includes? out "Time: not recorded"))
    (is (str/includes? out "OpenRouter balance: not recorded"))
    (is (str/includes? out "Roles: not recorded"))
    (is (str/includes? out "Rounds: 2 — 1 retry:") "what the record does carry is still derived")))

(deftest a-project-that-has-published-nothing-has-nothing-to-drift
  ;; A fresh copy of the seed has no RUNS.md and no runs/ beside it, and failed `bb gates` on that.
  ;; One half without the other is still the failure the gate was written for.
  (let [dir (str (fs/create-temp-dir))
        md (str (fs/path dir "RUNS.md"))
        runs (str (fs/path dir "runs"))]
    (is (= :nothing (report/published-state md runs)))
    (spit md "# Runs\n")
    (is (= :half (report/published-state md runs)) "a document with no records directory")
    (fs/create-dirs runs)
    (is (= :both (report/published-state md runs)))
    (fs/delete md)
    (is (= :half (report/published-state md runs)) "records nobody published")))

(deftest every-spec-review-is-printed-and-the-whole-cost-names-both-parts
  ;; A spec read twice was amended in between. The record once kept the last reading only, and the
  ;; total never included any: the first project with the review in the loop wrote a script to add them.
  (let [with-event (fn [e] (update timed-run :run/events #(into [e] %)))
        one {:event/kind :spec-review :findings 2 :cost 0.1 :model "m-r"}
        two (assoc one :findings 1 :cost 0.08
                   :reviews [{:count 3 :cost 0.06 :model "m-r"} {:count 1 :cost 0.08 :model "m-r"}])]
    (testing "no review, no lines"
      (is (nil? (report/spec-review-lines timed-run))))
    (testing "one review prints as it always did, then the whole cost"
      (let [[l1 l2 :as ls] (report/spec-review-lines (with-event one))]
        (is (= 2 (count ls)))
        (is (= "  Spec review before start: 2 findings, $0.100000." l1) "byte for byte what older reports say")
        (is (str/includes? l2 "Whole cost: ~$0.600000"))
        (is (str/includes? l2 "the loop's ~$0.500000"))
        (is (str/includes? l2 "$0.100000 of spec review"))))
    (testing "two reviews: each reading, the sum, and what the dispatched contract had drawn"
      (let [[l1 l2] (report/spec-review-lines (with-event two))]
        (is (str/includes? l1 "Spec reviews before start: 2"))
        (is (str/includes? l1 "3 findings $0.060000; 1 finding $0.080000"))
        (is (str/includes? l1 "$0.140000 in all"))
        (is (str/includes? l1 "had drawn 1 finding."))
        (is (str/includes? l2 "Whole cost: ~$0.640000") "the history's sum, not the last review's cost")))
    (testing "the ~ follows the loop's total: no computed price, no mark"
      (let [reported (update (with-event one) :run/steps (fn [ss] (mapv #(dissoc % :step/cost-source) ss)))
            [_ l2] (report/spec-review-lines reported)]
        (is (str/includes? l2 "Whole cost: $0.600000"))))
    (testing "a review with no cost says nothing about the whole cost rather than inventing one"
      (is (= ["  Spec review before start: 2 findings."]
             (vec (report/spec-review-lines (with-event (dissoc one :cost)))))))
    (testing "and the lines are in the rendered report"
      (let [out (report/render (with-event two))]
        (is (str/includes? out "Spec reviews before start: 2"))
        (is (str/includes? out "Whole cost:"))))))

(deftest the-attempts-line-says-it-is-not-the-rounds
  ;; attempts 2 and rounds 3 printed a few lines apart, and an analysis was drafted from the wrong one.
  (let [out (report/render timed-run)]
    (is (str/includes? out "attempt 2 was the highest any one role reached"))
    (is (not (str/includes? out "  2 attempts\n")))
    (is (not (str/includes? (report/render (assoc timed-run :run/attempts 1)) "was the highest"))
        "and a run with no retry says nothing")))

(deftest the-commits-a-record-was-taken-at-are-one-line-and-an-older-record-says-so
  (let [run {:run/id "r" :task/id "t" :run/attempts 1 :run/status :done :run/cost nil
             :run/started-at (java.util.Date.) :run/steps []}]
    (is (str/includes? (report/render run) "Commits: not recorded"))
    (is (str/includes? (report/render (assoc run :run/kit-commit "4e3fd8eef570" :run/app-commit "0123456789ab"
                                             :run/plan-commit nil))
                       "Commits: kit 4e3fd8e · app 0123456 · plan —")
        "abbreviated as git prints them; — for a repository the run had none of")))

(deftest report-check-takes-its-targets-from-the-arguments-or-the-workspace-and-never-guesses
  (is (= {:md-path "R.md" :dir "runs"} (report/check-targets ["R.md" "runs"] nil)))
  (is (:usage (report/check-targets ["R.md"] nil)) "one target is a usage error, not a guess at the other")
  (is (:skip (report/check-targets [] nil)) "no workspace, no arguments: nothing to check, said out loud")
  (is (= {:md-path "/ws/p/RUNS.md" :dir "/ws/p/runs"}
         (report/check-targets [] {:workspace/dir "/ws" :workspace/run-tables "/ws/p/RUNS.md" :workspace/records "/ws/p/runs"}))
      "the workspace's two, as bb init wrote them")
  (is (str/includes? (:skip (report/check-targets [] {:workspace/dir "/ws"})) "names no")
      "a workspace.edn from before the keys")
  (let [ws (str (fs/real-path (fs/create-temp-dir)))
        elsewhere (str (fs/create-temp-dir))]
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/plan "p" :workspace/run-tables "p/RUNS.md" :workspace/records "p/runs"}))
    (is (:skip (report/check-targets [] (workspace/current [] elsewhere)))
        "a KIT kept outside the workspace: nothing to check, as outside any")
    (is (= {:md-path (str (fs/path ws "p" "RUNS.md")) :dir (str (fs/path ws "p" "runs"))}
           (report/check-targets [] (workspace/current ["--workspace" ws] elsewhere)))
        "pointed at it, the workspace's two; the flag is not a target")))
