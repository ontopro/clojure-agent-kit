(ns harness.setup.stage-progress-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.stage-progress :as sp]))

(defn- run [id task status & {:keys [cost at]}]
  {:run/id id :task/id task :run/status status :run/started-at (or at #inst "2026-10-19T10:00:00Z")
   :run/app-commit "190299aabbcc" :run/steps [{:step/name :coder :step/kind :dispatch :step/cost (or cost 0.5)}]
   :run/events [{:event/kind :spec-review :cost 0.25}]})

(def ^:private facts
  {:kind "increment"
   :gates {:stage/approved {:date "2026-10-19" :cap "$10" :by "Ada"} :blueprint/signed {:date "2026-10-19" :by "Ada"}}
   :reviewed? true
   :preconditions [{:id "HP-1" :prep "the rules synced" :blocks "t-01" :status "done 2026-10-19"}
                   {:id "HP-2" :prep "the balance" :blocks "every task" :status "per run"}
                   {:id "HP-3" :prep "a file placed" :blocks "t-02" :status ""}
                   {:id "HP-4" :prep "a report command" :blocks "the stage's end" :status "open"}]
   :packets ["t-01-search" "t-02-counts" "t-03-views"]
   :records [(run "s4-t01" "t-01-search" :merged)
             (run "s4-t02" "t-02-counts" :abandoned)
             (run "s4-t02-b" "t-02-counts" :merged :cost 1.0)]
   :checks {:security/at "2026-10-20T00:05" :counts {:ok 9 :warn 2 :fail 0}}
   :scans {}
   :review-records []})

(deftest the-records-tick-what-they-prove-in-the-blueprints-order
  (let [lines (str/split-lines (sp/render facts))
        idx (fn [s] (first (keep-indexed (fn [i l] (when (str/includes? l s) i)) lines)))]
    (is (some #{"- [x] Stage plan approved - Ada, 2026-10-19 (cap $10)"} lines))
    (is (some #{"- [x] Blueprint signed off - Ada, 2026-10-19"} lines))
    (testing "each prep row before the first packet it blocks; every task's first; the rest at the end"
      (is (< (idx "HP-2") (idx "HP-1") (idx "t-01-search") (idx "HP-3") (idx "t-02-counts") (idx "t-03-views")))
      (is (> (idx "HP-4") (idx "Security review"))))
    (testing "a merged packet: its run, the run's whole money, rounds and commit; several runs said"
      (is (some #{"- [x] t-01-search - merged, run `s4-t01` 2026-10-19, $0.75, 1 round, app `190299a`"} lines))
      (is (str/includes? (nth lines (idx "t-02-counts")) "run `s4-t02-b`"))
      (is (str/ends-with? (nth lines (idx "t-02-counts")) "; 2 runs")))
    (is (some #{"- [ ] t-03-views - not run"} lines))
    (is (some #{"- [x] Security pack: 9 ok, 2 warn, 0 fail (2026-10-20)"} lines))
    (is (some #{"- [ ] Dependency scan: no record"} lines))
    (is (some #{"- [ ] Stage closed"} lines))
    (is (not-any? #(str/includes? % "Threat model") lines) "not a pre-release stage")))

(deftest the-security-review-is-its-records-or-its-skip
  (is (str/includes? (sp/render (assoc facts :review-records [{:member "gpt-6-astra" :findings [{:kind :reproduced} {:kind :hypothesis}]}]))
                     "- [x] Security review: gpt-6-astra - 1 reproduced"))
  (is (str/includes? (sp/render (assoc-in facts [:gates :security/skipped] {:date "2026-10-20" :by "Ada" :reason "content only"}))
                     "- [x] Security review: skipped - Ada, 2026-10-20: content only"))
  (is (str/includes? (sp/render (assoc facts :kind "pre-release")) "- [ ] Threat model signed")))

(deftest a-per-run-row-has-no-box-and-a-refusal-ticks-nothing
  (let [lines (str/split-lines (sp/render facts))]
    (is (some #(str/starts-with? % "- HP-2 - the balance (every task; per run)") lines) "never closes, so no box"))
  (is (str/includes? (sp/render (assoc facts :review-records [{:member "fable" :refused? true :findings []}]))
                     "- [ ] Security review: no reviewer answered; fable refused or left no answer"))
  (is (str/includes? (sp/render (assoc facts :review-records [{:member "astra" :findings [{:kind :reproduced}]}
                                                              {:member "fable" :no-block? true :findings []}]))
                     "- [x] Security review: astra - 1 reproduced; fable refused or left no answer")))

(deftest the-section-is-placed-and-the-persons-lines-kept
  (let [r (sp/render facts)]
    (testing "a plan with no section gains it, the hand lines with it"
      (let [t (sp/place "# Stage 4\n\n## 12. Residual risks\n" r)]
        (is (str/includes? t sp/heading))
        (is (str/includes? t "- [ ] The owner's walk"))
        (is (= r (sp/rendered-in t)))))
    (testing "a re-render replaces only what is between the markers"
      (let [t (sp/place (str "# Stage 4\n\n" sp/heading "\n\n" sp/start-marker "\nold\n" sp/end-marker "\n\n- [x] My own line\n") r)]
        (is (= r (sp/rendered-in t)))
        (is (str/includes? t "- [x] My own line"))
        (is (not (str/includes? t "\nold\n")))))
    (testing "a section written by hand, with no markers: the rendered part goes above the person's lines"
      (let [t (sp/place (str "# Stage 4\n\n" sp/heading "\n\n- [x] Plan approved by hand\n") r)]
        (is (= r (sp/rendered-in t)))
        (is (str/includes? t "- [x] Plan approved by hand"))
        (is (< (str/index-of t sp/end-marker) (str/index-of t "Plan approved by hand")))))))

(deftest a-gate-and-a-scan-show-their-day-in-either-form
  (let [old (str/split-lines (sp/render facts))
        new (str/split-lines (sp/render (-> facts
                                            (assoc :gates {:stage/approved {:at "2026-10-19T21:00-05:00" :cap "$10" :by "Ada"}
                                                           :blueprint/signed {:at "2026-10-19T22:10-05:00" :by "Ada"}})
                                            (assoc :checks {:security/at "2026-10-20T00:05:11.5-05:00" :counts {:ok 9 :warn 2 :fail 0}}))))]
    (is (= old new) "an :at renders the day a :date did; a scan's offset time the day its local time did")
    (is (some #{"- [x] Stage plan approved - Ada, 2026-10-19 (cap $10)"} new))))
