(ns harness.setup.next
  "What comes next in the workflow, read off the build's files: `bb next`.

  The loop's `next-action` (harness.loop.orchestrate) says what the loop does
  next from a run's state alone, pure and table-tested, because a control
  flow scattered through the steps that perform it can only be tested by
  performing them. This is the same shape one level up, over the workflow of
  `workflow.md`: which step is next, who owns it, and the command or skill
  that runs it - said by a command, so that no skill carries the hand-off to
  the next and a session opened cold can ask.

  FIVE FACTS, EVERY ONE A FILE. Is there a workspace; does the plan check
  pass; which stage is pulled and where its human gates stand
  (`docs/stages/stage-N-gates.edn`, the record the person writes); is its
  blueprint written, reviewed, signed; which of its packets have a merged
  record under the build's `runs/`. Before a workspace exists the next action
  is scoping, which is what the clone's own `scoping` skill is for.

  A first cut, and said so: it reads merged records and nothing of a run in
  flight beyond its last recorded status, it does not read the plan review's
  findings, and it takes the highest-numbered stage with any file as the one
  in progress. What it will not do is guess: a fact it cannot read is said as
  such in the `:because`.

  `next-action` is pure over `facts`; `facts` is the edge; `-main` prints."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [harness.contract.blueprint :as blueprint]
   [harness.setup.plan :as plan]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; pure: the table
;; ---------------------------------------------------------------------------

(def steps
  "The workflow's steps by number, as `workflow.md` numbers them."
  {0 "scoping" 1 "setup" 2 "the plan, for stage 0" 3 "the plan review" 4 "the plan check"
   5 "approval, for stage 0" 6 "stage 0" 7 "the stage plan" 8 "the blueprint"
   9 "the blueprint review" 10 "sign-off" 11 "a packet" 12 "the loop" 13 "the merge"
   14 "the stage's end"})

(defn- action [step owner run because]
  {:step step :name (steps step) :owner owner :run run :because because})

(def person "the person")
(def architect "the Architect's session")

(defn- gates-file [id] (str "docs/stages/stage-" id "-gates.edn"))

(defn- stage-action
  "The next action inside the stage `s` in progress: `{:id :plan :kind :gates
  :blueprint :reviewed? :packets :merged :runs}` - `:gates` the record's map or
  nil, `:plan` and `:blueprint` file names or nil, `:kind` the plan's kind
  line or nil, `:packets` the blueprint's task ids in order, `:merged` the set
  with a merged record, `:runs` task id → the latest record's status for the
  rest. A pre-release stage has a fourth gate, its threat model signed, which
  comes before its close."
  [{:keys [id plan kind gates blueprint reviewed? packets merged runs]}]
  (let [{:stage/keys [approved closed] :blueprint/keys [signed]} gates
        spike? (= 0 id)
        unsigned-threat-model? (and (= "pre-release" kind) (nil? (:security/signed gates)))
        sign-threat-model (str "every line of docs/02-architecture.md §15 answered with its evidence, then "
                               ":security/signed {:date :by} in " (gates-file id))]
    (cond
      (nil? gates)
      (action (if spike? 5 7) person
              (str "copy docs/stages/stage-N-gates-template.edn to " (gates-file id)
                   (if spike? " and set :stage/approved with the cap" (str " for stage " id)))
              (str "docs/stages/ has stage " id "'s plan and no gates record for it"))

      (and (nil? approved) spike?)
      (action 5 person (str "set :stage/approved {:date :cap :by} in " (gates-file id))
              (str "stage 0 is not approved (" (gates-file id) ")"))

      (and (nil? approved) (nil? plan))
      (action 7 architect "the stage-plan skill"
              (str "stage " id " is pulled (" (gates-file id) ") and docs/stages/ has no stage-" id " plan"))

      (nil? approved)
      (action 7 person (str "read docs/stages/" plan ", then set :stage/approved {:date :cap :by} in " (gates-file id))
              (str "stage " id "'s plan is written and not approved"))

      (nil? plan)
      (action 6 architect "the stage-plan skill (stage 0's plan, from the spike template)"
              (str "stage 0 is approved and docs/stages/ has no stage-0 plan"))

      (nil? blueprint)
      (action 8 architect (str "write docs/stages/stage-" id "-blueprint.md from stages/stage-N-blueprint-template.md"
                               ", from docs/stages/" plan)
              (str "stage " id " is approved and has no blueprint"))

      (not reviewed?)
      (action 9 architect (str "bb blueprint-review docs/stages/" blueprint)
              (str "docs/stages/" blueprint " has no reading under reviews/stage-" id "/"))

      (nil? signed)
      (action 10 person (str "resolve the review's findings in docs/stages/" plan ", then set :blueprint/signed {:date :by} in " (gates-file id))
              (str "stage " id "'s blueprint is reviewed and not signed"))

      (empty? packets)
      (action 8 architect (str "add the packets to docs/stages/" blueprint)
              (str "docs/stages/" blueprint " carries no packet (a fence whose first form has a :task/id)"))

      (some #(not (merged %)) packets)
      (let [t (first (remove merged packets))
            status (get runs t)]
        (case status
          :awaiting-merge (action 13 person (str "bb run-loop merge <run-dir> <decision.edn> for " t)
                                  (str "the run for " t " is stopped :awaiting-merge"))
          :escalated (action 12 person (str "read the run for " t " and decide; then bb run-loop run <run-dir>")
                             (str "the run for " t " is :escalated"))
          :abandoned (action 11 architect (str "bb spec-from-blueprint docs/stages/" blueprint " " t " <run-dir>/spec.edn, then bb run-loop start <run-dir>")
                             (str "the run for " t " was abandoned; no merged record"))
          nil (action 11 architect (str "bb spec-from-blueprint docs/stages/" blueprint " " t " <run-dir>/spec.edn, then bb run-loop start <run-dir>")
                      (str t " has no run record; " (count merged) " of " (count packets) " packets merged"))
          (action 12 architect (str "bb run-loop run <run-dir> for " t)
                  (str "the run for " t " is " status))))

      (nil? closed)
      (action 14 architect (if unsigned-threat-model?
                             (str "the stage-end skill; a pre-release stage's exit criteria include its threat model: "
                                  sign-threat-model ", before :stage/closed")
                             "the stage-end skill")
              (str "every packet of stage " id " is merged (" (count packets) ") and the stage is not closed"))

      unsigned-threat-model?
      (action 14 person sign-threat-model
              (str "stage " id " is pre-release and closed without its threat model signed"))

      :else
      (action 7 person (str "pull stage " (inc id) " by kind: copy docs/stages/stage-N-gates-template.edn to "
                            (gates-file (inc id)) ", then the stage-plan skill")
              (str "stage " id " is closed")))))

(defn next-action
  "The next step from `facts`: `:workspace?`; `:brief?` (source.md §1 names
  one); `:plan-problems` (what `bb plan-check` reports, or nil when it could
  not run); `:stage` the stage in progress as `stage-action` reads it, or nil
  when docs/stages/ has none. Returns `{:step :name :owner :run :because}`."
  [{:keys [workspace? brief? plan-problems stage]}]
  (cond
    (not workspace?)
    (action 0 (str person ", with an Architect session") "the scoping skill, then bb init <name> --brief <file>"
            "no workspace.edn at or above here")

    (not brief?)
    (action 2 architect "file the brief as source.md's §1 row and Appendix A (bb init --brief does it at init), then the plan skill"
            "docs/source.md §1 still says <the brief>")

    (seq plan-problems)
    (action 2 architect "the plan skill; bb plan-check lists what is left, bb plan-review before the approval"
            (str "bb plan-check: " (count plan-problems) " thing" (when (not= 1 (count plan-problems)) "s") " left"))

    (nil? stage)
    (action 5 person "copy docs/stages/stage-N-gates-template.edn to docs/stages/stage-0-gates.edn and set :stage/approved with the cap"
            "the plan passes its check and docs/stages/ has no stage yet")

    :else (stage-action stage)))

;; ---------------------------------------------------------------------------
;; the edge: facts
;; ---------------------------------------------------------------------------

(defn- stage-id [file-name]
  (some-> (re-find #"^stage-(\d+)-" file-name) second parse-long))

(defn- read-edn [path] (try (edn/read-string (slurp (str path))) (catch Exception _ nil)))

(defn stage-kind
  "A stage plan's kind, from its `**Kind:**` line (`spike`, `pre-release`, …), or nil."
  [text]
  (some-> (re-find #"(?m)^\*\*Kind:\*\*\s*(.*?)\s*$" text) second))

(defn stage-files
  "The stages under `docs/stages/`, by id: `{id {:plan name :blueprint name
  :gates map}}` - a plan file is `stage-N-<name>.md` that is neither a
  template nor the blueprint; the gates record is `stage-N-gates.edn`."
  [stages-dir]
  (reduce (fn [m f]
            (let [nm (fs/file-name f)
                  id (stage-id nm)]
              (cond
                (or (nil? id) (str/includes? nm "-template.")) m
                (str/ends-with? nm "-gates.edn") (assoc-in m [id :gates] (read-edn f))
                (str/ends-with? nm "-blueprint.md") (assoc-in m [id :blueprint] nm)
                (str/ends-with? nm ".md") (assoc-in m [id :plan] nm)
                :else m)))
          (sorted-map)
          (when (fs/directory? stages-dir) (fs/list-dir stages-dir))))

(defn run-statuses
  "Task id → the latest record's `:run/status` under `records-dir`, latest by
  `:run/started-at`; a merged record wins over any other."
  [records-dir]
  (->> (when (and records-dir (fs/directory? records-dir)) (fs/glob records-dir "*.edn"))
       (keep read-edn)
       (filter :task/id)
       (sort-by #(some-> (:run/started-at %) .getTime) (fnil compare 0 0))
       (reduce (fn [m {:task/keys [id] :run/keys [status]}]
                 (if (= :merged (get m id)) m (assoc m id status)))
               {})))

(defn facts
  "The five facts, read from the workspace `ws` (nil outside one)."
  [ws]
  (if-not ws
    {:workspace? false}
    (let [build (:workspace/build ws)
          source (fs/path build "docs" "source.md")
          stages (stage-files (fs/path build "docs" "stages"))
          [id files] (last stages)
          statuses (run-statuses (:workspace/records ws))
          packets (when-let [bp (:blueprint files)]
                    (mapv :task/id (blueprint/packets (slurp (str (fs/path build "docs" "stages" bp))))))]
      {:workspace? true
       :brief? (and (fs/exists? source) (not (str/includes? (slurp (str source)) "<the brief>")))
       :plan-problems (:problems (plan/check build ws))
       :stage (when id
                (merge files
                       {:id id
                        :kind (some->> (:plan files) (fs/path build "docs" "stages") str slurp stage-kind)
                        :reviewed? (fs/exists? (fs/path build "reviews" (str "stage-" id) "blueprint-review.edn"))
                        :packets (or packets [])
                        :merged (into #{} (filter #(= :merged (statuses %))) packets)
                        :runs (into {} (for [t packets :let [s (statuses t)] :when (and s (not= :merged s))] [t s]))}))})))

(defn render
  "The action as `bb next` prints it."
  [{:keys [step name owner run because]}]
  (str "next: step " step " - " name "\n"
       "  owner:   " owner "\n"
       "  run:     " run "\n"
       "  because: " because))

(defn -main
  "bb next [--workspace <dir>]

  The workflow's next step, its owner, and the command or skill to run, from
  the build's files. Information: exit 0 always."
  [& args]
  (let [ws (workspace/current-or-exit args)]
    (println (render (next-action (facts ws))))))
