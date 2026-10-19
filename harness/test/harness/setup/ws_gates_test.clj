(ns harness.setup.ws-gates-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.ws-gates :as ws-gates]))

(defn- runner
  "A runner that fails the steps named in `red`, recording what it ran in `ran`."
  [ran red]
  (fn [{:keys [task]}]
    (swap! ran conj task)
    (if (red task) 1 0)))

(deftest the-workspaces-checks-run-cheap-first
  (is (= ["rules-check" "skills-sync" "front-door-sync" "plan-check" "stage-report" "stage-progress" "report-check"]
         (mapv :name (ws-gates/steps false))))
  (is (= ["--check"] (:args (second (ws-gates/steps false)))) "the syncs only compare")
  (is (= [false true] (keep :info? (ws-gates/steps false))) "outside a plan step, plan-check is a gate; a progress section behind is information")
  (is (= [true true] (keep :info? (ws-gates/steps true))) "in one, plan-check is information too"))

(deftest the-first-red-stops-them
  (let [ran (atom [])
        r (ws-gates/run-steps (ws-gates/steps false) (runner ran #{"skills-sync" "stage-report"}))]
    (is (= ["rules-check" "skills-sync"] @ran) "nothing after the first red runs")
    (is (= "skills-sync" (:failed r)))
    (is (= "gates: red at skills-sync - 2 of 7 checks run, the rest not" (ws-gates/verdict r))))
  (let [r (ws-gates/run-steps (ws-gates/steps false) (runner (atom []) #{}))]
    (is (nil? (:failed r)))
    (is (= "gates: green - 7 checks" (ws-gates/verdict r)))))

(deftest plan-check-fails-the-gates-only-outside-a-plan-step
  (testing "a stage under way: a mark left is red"
    (let [ran (atom [])
          r (ws-gates/run-steps (ws-gates/steps false) (runner ran #{"plan-check"}))]
      (is (= "plan-check" (:failed r)))
      (is (= 4 (count @ran)))))
  (testing "a plan being written: its list is said, and the gates go on"
    (let [ran (atom [])
          r (ws-gates/run-steps (ws-gates/steps true) (runner ran #{"plan-check"}))]
      (is (nil? (:failed r)))
      (is (= 7 (count @ran)))
      (is (str/includes? (ws-gates/verdict r) "plan-check's list is information")))))
