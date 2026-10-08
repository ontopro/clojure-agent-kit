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
   :stage {:id 1 :plan "stage-1-skeleton.md" :gates {:stage/id "1" :stage/approved nil :blueprint/signed nil :stage/closed nil}
           :blueprint nil :reviewed? false :packets [] :merged #{} :runs {}}})

(defn- with-stage [m] (update ready :stage merge m))
(defn- gate [m k v] (assoc-in m [:stage :gates k] v))

(deftest before-a-workspace-the-next-action-is-scoping-from-the-clone
  (let [a (next/next-action {:workspace? false})]
    (is (= 0 (:step a)))
    (is (= "scoping" (:name a)))
    (is (str/includes? (:run a) "the scoping skill"))
    (is (str/includes? (:run a) "bb init <name> --brief"))
    (is (str/includes? (next/render a) "next: step 0 - scoping\n  owner:   the person"))))

(deftest the-plan-comes-before-any-stage
  (is (= [2 "the Architect's session"] (step {:workspace? true :brief? false :plan-problems ["x"]})) "no brief: say so first")
  (is (str/includes? (:because (next/next-action {:workspace? true :brief? false})) "<the brief>"))
  (let [a (next/next-action {:workspace? true :brief? true :plan-problems ["a" "b"]})]
    (is (= 2 (:step a)))
    (is (str/includes? (:run a) "the plan skill"))
    (is (= "bb plan-check: 2 things left" (:because a))))
  (let [a (next/next-action {:workspace? true :brief? true :plan-problems [] :stage nil})]
    (is (= [5 "the person"] ((juxt :step :owner) a)) "the plan passes and no stage exists: stage 0's approval")
    (is (str/includes? (:run a) "stage-0-gates.edn"))))

(deftest the-stages-three-gates-walk-the-table
  (testing "stage 0: approval before its plan"
    (let [s0 (with-stage {:id 0 :plan nil :gates nil})]
      (is (= [5 "the person"] (step s0)) "no gates record yet")
      (is (= [5 "the person"] (step (assoc-in s0 [:stage :gates] {:stage/id "0" :stage/approved nil}))))
      (let [a (next/next-action (assoc-in s0 [:stage :gates] {:stage/id "0" :stage/approved {:date "d"}}))]
        (is (= [6 "the Architect's session"] ((juxt :step :owner) a)))
        (is (str/includes? (:run a) "stage-plan skill")))))
  (testing "a later stage: its plan, then approval"
    (let [pulled (with-stage {:plan nil})]
      (is (= [7 "the Architect's session"] (step pulled)))
      (is (str/includes? (:run (next/next-action pulled)) "the stage-plan skill")))
    (is (= [7 "the person"] (step (with-stage {:gates nil}))) "a plan with no gates record: copy the template")
    (is (= [7 "the person"] (step ready)) "written, not approved")
    (is (str/includes? (:run (next/next-action ready)) ":stage/approved")))
  (testing "approved: the blueprint, its review, the sign-off"
    (let [approved (gate ready :stage/approved {:date "d" :cap "$5"})]
      (is (= [8 "the Architect's session"] (step approved)))
      (let [bp (with-stage {:gates (:gates (:stage approved)) :blueprint "stage-1-blueprint.md"})]
        (is (= [9 "the Architect's session"] (step bp)))
        (is (str/includes? (:run (next/next-action bp)) "bb blueprint-review docs/stages/stage-1-blueprint.md"))
        (let [reviewed (assoc-in bp [:stage :reviewed?] true)]
          (is (= [10 "the person"] (step reviewed)))
          (is (str/includes? (:run (next/next-action reviewed)) ":blueprint/signed"))))))
  (testing "signed: the packets, one at a time, by their records"
    (let [signed (-> (with-stage {:blueprint "stage-1-blueprint.md" :reviewed? true :packets ["t1" "t2" "t3"]})
                     (gate :stage/approved {:date "d"})
                     (gate :blueprint/signed {:date "d"}))]
      (is (= [8 "the Architect's session"] (step (assoc-in signed [:stage :packets] []))) "a blueprint with no packet")
      (let [a (next/next-action signed)]
        (is (= [11 "the Architect's session"] ((juxt :step :owner) a)))
        (is (str/includes? (:run a) "bb spec-from-blueprint docs/stages/stage-1-blueprint.md t1"))
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
        (is (str/includes? (:run (next/next-action all)) "the stage-end skill"))
        (let [closed (gate all :stage/closed {:date "d"})]
          (is (= [7 "the person"] (step closed)))
          (is (str/includes? (:run (next/next-action closed)) "stage-2-gates.edn"))
          (is (= "stage 1 is closed" (:because (next/next-action closed)))))))))

(deftest a-pre-release-stage-has-a-fourth-gate-its-threat-model-signed
  (let [all (-> (with-stage {:kind "pre-release" :blueprint "stage-1-blueprint.md" :reviewed? true
                             :packets ["t1"] :merged #{"t1"}})
                (gate :stage/approved {:date "d"})
                (gate :blueprint/signed {:date "d"}))]
    (let [a (next/next-action all)]
      (is (= [14 "the Architect's session"] ((juxt :step :owner) a)) "the stage-end skill still runs the stage's end")
      (is (str/includes? (:run a) "every line of docs/02-architecture.md §15 answered with its evidence"))
      (is (str/includes? (:run a) ":security/signed {:date :by} in docs/stages/stage-1-gates.edn, before :stage/closed")))
    (let [closed (gate all :stage/closed {:date "d"})]
      (is (= [14 "the person"] (step closed)) "closed without it: the person signs before the next stage is pulled")
      (is (= "stage 1 is pre-release and closed without its threat model signed" (:because (next/next-action closed))))
      (is (= [7 "the person"] (step (gate closed :security/signed {:date "d" :by "the person"}))) "signed: the next stage"))
    (testing "another kind is not asked, and a plan with no kind line is another kind"
      (is (= "the stage-end skill" (:run (next/next-action (assoc-in all [:stage :kind] "increment")))))
      (is (= [7 "the person"] (step (-> all (assoc-in [:stage :kind] nil) (gate :stage/closed {:date "d"}))))))))

(deftest a-stage-plans-kind-is-its-kind-line
  (is (= "pre-release" (next/stage-kind "# Stage 5\n\n**Status:** PLANNED\n**Kind:** pre-release  \n**Version:** 0.1\n")))
  (is (= "walking skeleton" (next/stage-kind "**Kind:** walking skeleton\n")))
  (is (nil? (next/stage-kind "# Stage 1\n\nno kind here\n"))))

(deftest every-action-names-a-step-an-owner-a-command-and-a-reason
  (doseq [f [{:workspace? false} {:workspace? true :brief? false} ready
             (gate ready :stage/approved {:date "d"}) (with-stage {:gates nil})]]
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
      (is (= 2 (:step (next/next-action f)))))
    (spit (str (fs/path build "docs" "source.md")) "# Source\n\n| S-1 | the brief, received |\n")
    (spit (str (fs/path stages "stage-N-template.md")) "# a template\n")
    (spit (str (fs/path stages "stage-N-gates-template.edn")) "{:stage/id \"<N>\"}")
    (spit (str (fs/path stages "stage-0-spike.md")) "# Stage 0\n")
    (spit (str (fs/path stages "stage-0-gates.edn")) (pr-str {:stage/id "0" :stage/approved {:date "d"} :stage/closed {:date "d"}}))
    (spit (str (fs/path stages "stage-1-skeleton.md")) "# Stage 1\n\n**Kind:** walking skeleton\n")
    (spit (str (fs/path stages "stage-1-gates.edn")) (pr-str {:stage/id "1" :stage/approved {:date "d"} :blueprint/signed {:date "d"}}))
    (spit (str (fs/path stages "stage-1-blueprint.md"))
          "# Blueprint\n\n```clojure\n{:task/id \"t1\"}\n```\n\n```clojure\n{:task/id \"t2\"}\n```\n")
    (fs/create-dirs (fs/path build "reviews" "stage-1"))
    (spit (str (fs/path build "reviews" "stage-1" "blueprint-review.edn")) "{:count 0}")
    (spit (str (fs/path build "runs" "r1.edn")) (pr-str {:run/id "r1" :task/id "t1" :run/status :merged :run/started-at (java.util.Date. 1000)}))
    (spit (str (fs/path build "runs" "r2.edn")) (pr-str {:run/id "r2" :task/id "t2" :run/status :escalated :run/started-at (java.util.Date. 2000)}))
    (spit (str (fs/path build "runs" "r3.edn")) (pr-str {:run/id "r3" :task/id "t2" :run/status :awaiting-merge :run/started-at (java.util.Date. 3000)}))
    (let [f (next/facts (workspace/find-workspace ws))
          s (:stage f)]
      (is (true? (:brief? f)))
      (is (= 1 (:id s)) "the highest-numbered stage with a file; templates are not stages")
      (is (= "stage-1-skeleton.md" (:plan s)))
      (is (= "walking skeleton" (:kind s)) "the plan's kind line")
      (is (= "stage-1-blueprint.md" (:blueprint s)))
      (is (true? (:reviewed? s)))
      (is (= ["t1" "t2"] (:packets s)) "in the blueprint's order")
      (is (= #{"t1"} (:merged s)))
      (is (= {"t2" :awaiting-merge} (:runs s)) "the latest record's status, by its start")
      (is (seq (:plan-problems f)) "the plan check ran, and this workspace has no overlay, no layers.edn, no loop.edn")
      (is (= 2 (:step (next/next-action f))) "so the plan comes first")
      (is (= 13 (:step (next/next-action (assoc f :plan-problems [])))) "with the plan passing: t2's run awaits its merge"))))
