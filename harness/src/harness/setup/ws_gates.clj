(ns harness.setup.ws-gates
  "A workspace's own gates: `bb gates` at its front door, `bb workspace-gates` here.

  The KIT's `bb gates` checks the KIT; a workspace had no such command, and its checks were run
  one at a time by hand - the rule mirror, the skills' renderings, the front door, the plan, the
  stage reports and the run reports. This runs them in one, cheap first, the first red stopping
  it: one command for the Architect at a stage's end and for a session that asks whether the
  workspace is sound.

  THE PLAN CHECK IS INFORMATION IN A PLAN STEP. `plan-check` reads the build's plan documents,
  stage plans among them, and a plan being written carries its marks by design: while one is -
  before stage 0's approval, or a stage pulled and not yet approved (`next/plan-step?`) - its list
  is printed and does not fail the gates. Outside one, a mark left is a failure like any other.

  `steps` and `run-steps` are pure over a runner; `-main` runs each step as `bb <task>` in the
  KIT's `harness/`, the workspace named to it."
  (:require
   [babashka.process :as p]
   [harness.setup.next :as next]
   [harness.setup.workspace :as workspace]))

(defn steps
  "The checks, cheap first, as `{:name :task :args :info?}`; `:info?` on the plan check when
  `plan-step?` - a plan is being written."
  [plan-step?]
  [{:name "rules-check" :task "rules-check" :args []}
   {:name "skills-sync" :task "skills-sync" :args ["--check"]}
   {:name "front-door-sync" :task "front-door-sync" :args ["--check"]}
   {:name "plan-check" :task "plan-check" :args [] :info? (boolean plan-step?)}
   {:name "stage-report" :task "stage-report" :args ["--check"]}
   {:name "report-check" :task "report-check" :args []}])

(defn run-steps
  "Each of `steps` through `run!` (a step to its exit code), in order, stopping at the first
  that fails and is not `:info?`. Returns `{:ran [{:name :exit :info?}] :failed name-or-nil :of n}`."
  [steps run!]
  (reduce (fn [acc {:keys [info?] :as step}]
            (let [exit (run! step)
                  acc (update acc :ran conj {:name (:name step) :exit exit :info? info?})]
              (if (and (not (zero? exit)) (not info?))
                (reduced (assoc acc :failed (:name step)))
                acc)))
          {:ran [] :failed nil :of (count steps)}
          steps))

(defn verdict
  "The last line `bb gates` prints for `result`, `run-steps`'s."
  [{:keys [ran failed of]}]
  (if failed
    (str "gates: red at " failed " - " (count ran) " of " of " checks run, the rest not")
    (let [informed (filter #(and (:info? %) (not (zero? (:exit %)))) ran)]
      (str "gates: green - " (count ran) " checks"
           (when (seq informed)
             " (plan-check's list is information: a plan is being written, and carries its marks until it is approved)")))))

(defn -main
  "bb workspace-gates [--workspace <dir>] - `bb gates` at a workspace's front door.

  The workspace's checks, cheap first, the first red stopping it: exit 1 then, else 0."
  [& args]
  (let [ws (workspace/current-or-exit args)]
    (when-not ws
      (println "gates: no workspace here - this is a workspace's bb gates; the KIT's own is bb gates in its harness/")
      (System/exit 1))
    (let [dir (str (:workspace/dir ws))
          plan-step? (next/plan-step? (:stage (next/facts ws)))
          result (run-steps (steps plan-step?)
                            (fn [{:keys [task args info?] :as step}]
                              (println (str "gates: " (:name step) (when info? " (information: a plan is being written)")))
                              (:exit (apply p/shell {:continue true} "bb" task (concat args ["--workspace" dir])))))]
      (println (verdict result))
      (when (:failed result) (System/exit 1)))))
