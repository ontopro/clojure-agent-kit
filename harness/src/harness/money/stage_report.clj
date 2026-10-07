(ns harness.money.stage-report
  "A stage's figures from its records: `bb stage-report <stage-plan.md>`.

  THE STAGE IS THE UNIT OF MONEY. The person approves a stage with its cap,
  and what the stage then costs was known one run at a time - a hand-kept
  rounds table, a ledger, a spend sheet, each re-added after every merge and
  each a number nobody could re-derive. This is the roll-up: over the run
  records of the stage's packets (the blueprint names them, the records name
  their task) and the stage's readings (the plan review's and the blueprint
  review's records under `reviews/<stage>/`, the spec reviews inside each run)
  - money by role, rounds, stops by owner, rework - set beside the stage's cap
  (its gates record) and the stage before it, and rendered into the stage
  plan's exit-criteria section as a fenced block that names itself, held to
  the records by `--check` as `RUNS.md` is held by `bb report-check`.

  SAID, NOT COUNTED: the cap stays the person's stop. The block says what was
  spent against what was approved; nothing here stops a run or a merge.

  The pure parts are `role-of`, `run-summary`, `stage-summary`, `render`,
  `published`, `splice` and `drift`; `facts` is the edge; `-main` prints,
  writes or checks."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [harness.contract.blueprint :as blueprint]
   [harness.loop.log :as log]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; pure: one run
;; ---------------------------------------------------------------------------

(defn role-of
  "The profile role a step name was dispatched under: `:coder-r2` the Coder's,
  `:triage` and `:triage-r1` the orchestrator's; nil for a step that is no
  role's (a gate, provisioning)."
  [step-name]
  (let [base (keyword (first (str/split (name step-name) #"-r\d+$")))]
    (case base
      :triage :orchestrator
      (:coder :tester :reviewer) base
      nil)))

(defn retry?
  "A step name with an attempt suffix - `:tester-r2` - is a retry: rework."
  [step-name]
  (boolean (re-find #"-r\d+$" (name step-name))))

(defn run-summary
  "One record as the stage counts it: its id, task and status; its cost and
  the cost by role (dispatch steps with a cost); its rounds (one plus the
  triage events, as the run report counts them); the cost of its retries;
  the reviews rejected; its stops by owner (`log/owner` over each
  `:stopped` event); the spec readings it carried and their cost."
  [{:run/keys [status steps events] :as record}]
  (let [dispatches (filter #(and (= :dispatch (:step/kind %)) (:step/cost %)) steps)
        by-role (->> dispatches
                     (keep (fn [{:step/keys [name cost]}] (when-let [r (role-of name)] [r cost])))
                     (reduce (fn [m [r c]] (update m r (fnil + 0) c)) {}))
        stops (filter #(= :stopped (:event/kind %)) events)
        reviews (filter #(= :spec-review (:event/kind %)) events)]
    {:run/id (:run/id record)
     :task/id (:task/id record)
     :status status
     :cost (reduce + 0 (keep :step/cost steps))
     :by-role by-role
     :rounds (inc (count (filter #(= :triage (:event/kind %)) events)))
     :retry-cost (reduce + 0 (keep :step/cost (filter #(retry? (:step/name %)) dispatches)))
     :rejections (count (filter #(and (= :dispatch (:event/kind %)) (= :reviewer (:role %))
                                      (= :reject (get-in % [:verdict :verdict])))
                                events))
     :stops (frequencies (map log/owner stops))
     :stop-kinds (frequencies (keep :stop/kind stops))
     :spec-reviews (count reviews)
     :spec-review-cost (reduce + 0 (keep :cost reviews))}))

;; ---------------------------------------------------------------------------
;; pure: one stage
;; ---------------------------------------------------------------------------

(defn- sum [k xs] (reduce + 0 (keep k xs)))

(defn- merge-sum [maps] (reduce (fn [m [k v]] (update m k (fnil + 0) v)) {} (mapcat seq maps)))

(defn stage-summary
  "The roll-up of a stage: `:id`; `:packets` in the blueprint's order with
  the status each has (`:no-record` for one without); the run summaries
  (`:runs`); the totals - runs, merged, money, money by role, rounds, retry
  cost, rejections, stops by owner and by kind; the readings (`:readings`:
  `[[label cost n] …]` for the plan review, the blueprint review and the spec
  reviews) and their money; the cap and its date from the gates record, or
  nil; `:before` the stage before's `{:id :cost :runs :rounds :stops}` or nil."
  [{:keys [id packets records readings gates before]}]
  (let [runs (mapv run-summary records)
        by-task (into {} (map (juxt :task/id identity)) runs)
        dispatch-money (sum :cost runs)
        spec-n (sum :spec-reviews runs)
        spec-cost (sum :spec-review-cost runs)
        readings (cond-> (vec readings)
                   (pos? spec-n) (conj ["spec reviews" spec-cost spec-n]))
        reading-money (reduce + 0 (map second readings))]
    {:id id
     :packets (mapv (fn [t] [t (or (:status (by-task t)) :no-record)]) packets)
     :runs runs
     :run-count (count runs)
     :merged (count (filter #(= :merged (:status %)) runs))
     :dispatch-money dispatch-money
     :reading-money reading-money
     :money (+ dispatch-money reading-money)
     :by-role (merge-sum (map :by-role runs))
     :rounds (sum :rounds runs)
     :retry-cost (sum :retry-cost runs)
     :rejections (sum :rejections runs)
     :stops (merge-sum (map :stops runs))
     :stop-kinds (merge-sum (map :stop-kinds runs))
     :readings readings
     :cap (:cap (:stage/approved gates))
     :approved (:date (:stage/approved gates))
     :before before}))

(defn- dollars [x] (format "$%.2f" (double (or x 0))))

(defn- pct [part whole] (if (pos? whole) (format "%.0f%%" (* 100.0 (/ part whole))) "—"))

(defn render
  "The stage's block, as published: its first line names the stage, so the
  check can find it without a marker."
  [{:keys [id run-count merged money cap approved before by-role readings reading-money
           rounds retry-cost dispatch-money rejections stops stop-kinds packets]}]
  (str/join
   "\n"
   [(str "Stage " id " report · " run-count " run" (when (not= 1 run-count) "s") ", " merged " merged · "
         (dollars money) (if cap (str " of cap " cap (when approved (str " (approved " approved ")")))
                             " · cap: not recorded (no :stage/approved in the gates record)")
         " · " (if before
                 (str "stage " (:id before) ": " (dollars (:cost before)) ", " (:runs before) " run"
                      (when (not= 1 (:runs before)) "s") ", " (:rounds before) " round"
                      (when (not= 1 (:rounds before)) "s") ", " (:stops before) " stop" (when (not= 1 (:stops before)) "s"))
                 "no stage before"))
    (str "  Money by role: "
         (if (seq by-role)
           (str/join " · " (for [[r c] (sort-by (comp - val) by-role)] (str (name r) " " (dollars c))))
           "no priced dispatch")
         " · readings " (dollars reading-money)
         (when (seq readings)
           (str " (" (str/join ", " (for [[label c n] readings] (str label " " n " " (dollars c)))) ")")))
    (str "  Rounds: " rounds " over " run-count " run" (when (not= 1 run-count) "s")
         (when (pos? run-count) (format " (%.1f per run)" (double (/ rounds run-count))))
         " · retries " (dollars retry-cost) " (" (pct retry-cost dispatch-money) " of the dispatch money)"
         " · " rejections " review" (when (not= 1 rejections) "s") " rejected")
    (str "  Stops: "
         (if (seq stops)
           (str (str/join " · " (for [[o n] (sort-by (comp name key) stops)] (str (name o) " " n)))
                " — by kind: " (str/join ", " (for [[k n] (sort-by (comp name key) stop-kinds)] (str (name k) " " n))))
           "none"))
    (str "  Packets: "
         (if (seq packets)
           (str/join " · " (for [[t s] packets] (str t " " (name s))))
           "none in the blueprint"))]))

;; ---------------------------------------------------------------------------
;; pure: the block in the stage plan
;; ---------------------------------------------------------------------------

(def ^:private fenced #"(?ms)^```([^\n`]*)\n(.*?)^```[ \t]*$")

(defn published
  "The stage report block a stage plan carries, trimmed, or nil."
  [text]
  (some (fn [[_ label body]]
          (when (and (str/blank? label) (re-find #"^Stage \S+ report ·" body))
            (str/trim body)))
        (re-seq fenced text)))

(defn splice
  "`text` with `block` as its stage report: the existing block replaced in
  place, or a new one put at the end of the exit-criteria section (before
  the heading that follows it), or at the end when the plan has no such
  section."
  [text block]
  (let [fence (str "```\n" block "\n```")]
    (if (published text)
      (str/replace-first text fenced
                         (fn [[whole label body]]
                           (if (and (str/blank? label) (re-find #"^Stage \S+ report ·" body))
                             fence
                             whole)))
      (let [lines (str/split-lines text)
            start (first (keep-indexed (fn [i l] (when (re-find #"^## \d+\. Exit criteria" l) i)) lines))
            end (when start (first (keep-indexed (fn [i l] (when (and (> i start) (str/starts-with? l "## ")) i)) lines)))]
        (if end
          (str/join "\n" (concat (subvec (vec lines) 0 end) [fence ""] (subvec (vec lines) end)))
          (str (str/trimr text) "\n\n" fence "\n"))))))

(defn drift
  "What is wrong between a stage plan's published block and the re-rendered
  `summary`: nil when they agree, else a sentence."
  [stage-plan-name text summary]
  (let [block (published text)
        now (render summary)]
    (cond
      (nil? block) (str stage-plan-name " publishes no stage report and its packets have records: run `bb stage-report`")
      (not= block now) (str stage-plan-name "'s stage report does not match its records: run `bb stage-report`"))))

;; ---------------------------------------------------------------------------
;; the edge: facts
;; ---------------------------------------------------------------------------

(defn- read-edn [path] (try (edn/read-string (slurp (str path))) (catch Exception _ nil)))

(defn stage-id [file-name] (some-> (re-find #"^stage-(\d+)-" file-name) second parse-long))

(defn stage-plans
  "The stage plans under `stages-dir`: `[[id file-name] …]` by id, templates,
  blueprints and gates records left out."
  [stages-dir]
  (->> (when (fs/directory? stages-dir) (fs/list-dir stages-dir))
       (map fs/file-name)
       (filter #(and (stage-id %) (str/ends-with? % ".md")
                     (not (str/includes? % "-template.")) (not (str/ends-with? % "-blueprint.md"))))
       (map (juxt stage-id identity))
       (sort-by first)
       vec))

(defn- reading
  "`[label cost n]` for a review record: every reading in its `:reviews`
  history, or the record alone when it has none; nil when no file."
  [label path]
  (when-let [m (read-edn path)]
    (let [rs (or (seq (:reviews m)) [m])]
      [label (reduce + 0 (keep :cost rs)) (count rs)])))

(defn- records-for
  "The run records under `records-dir` whose task is one of `packets`, in the
  packets' order; several records for one task all count."
  [records-dir packets]
  (let [all (->> (when (and records-dir (fs/directory? records-dir)) (fs/glob records-dir "*.edn"))
                 (keep read-edn)
                 (filter :run/id))
        wanted (set packets)
        order (into {} (map-indexed (fn [i t] [t i])) packets)]
    (->> all
         (filter #(wanted (:task/id %)))
         (sort-by (juxt #(order (:task/id %)) :run/id))
         vec)))

(defn stage-facts
  "What `stage-summary` needs for stage `id` of the build at `build` with the
  records under `records-dir`: nil when the stage has no blueprint (no
  packets, nothing to roll up)."
  [build records-dir id]
  (let [stages (fs/path build "docs" "stages")
        bp (fs/path stages (str "stage-" id "-blueprint.md"))]
    (when (fs/exists? bp)
      (let [packets (mapv :task/id (blueprint/packets (slurp (str bp))))
            reviews (fs/path build "reviews" (str "stage-" id))]
        {:id id
         :packets packets
         :records (records-for records-dir packets)
         :readings (vec (remove nil? [(reading "plan review" (if (= 0 id)
                                                               (fs/path build "reviews" "plan-review.edn")
                                                               (fs/path reviews "plan-review.edn")))
                                      (reading "blueprint review" (fs/path reviews "blueprint-review.edn"))]))
         :gates (read-edn (fs/path stages (str "stage-" id "-gates.edn")))}))))

(defn summary-for
  "The stage's summary with the stage before it folded in, or nil when the
  stage has no blueprint."
  [build records-dir id]
  (when-let [f (stage-facts build records-dir id)]
    (let [prev (when (pos? id) (some-> (stage-facts build records-dir (dec id)) stage-summary))]
      (stage-summary (assoc f :before (when prev {:id (dec id) :cost (:money prev) :runs (:run-count prev)
                                                  :rounds (:rounds prev) :stops (reduce + 0 (vals (:stops prev)))}))))))

(defn check
  "Every stage plan of the build whose packets have records, held to its
  published block: the sentences, empty when all agree. A stage with no
  record yet publishes nothing and is not asked to."
  [build records-dir]
  (vec (for [[id nm] (stage-plans (fs/path build "docs" "stages"))
             :let [s (summary-for build records-dir id)]
             :when (and s (pos? (:run-count s)))
             :let [problem (drift nm (slurp (str (fs/path build "docs" "stages" nm))) s)]
             :when problem]
         problem)))

(defn -main
  "bb stage-report [<stage-plan.md>] [--check] [--workspace <dir>]

  With a stage plan: the stage's block re-rendered from the records and
  written into the plan's exit-criteria section (replacing the one there),
  and printed. With `--check`: every stage plan of the workspace's build held
  to its records, exit 1 with the list; nothing to check outside a workspace."
  [& args]
  (let [ws (workspace/current-or-exit args)
        {:keys [args]} (workspace/split-args args)
        check? (boolean (some #{"--check"} args))
        plan-arg (first (remove #(str/starts-with? % "--") args))]
    (cond
      check?
      (if-not (:workspace/build ws)
        (println "stage-report: no workspace here, nothing to check")
        (let [problems (check (:workspace/build ws) (:workspace/records ws))]
          (if (seq problems)
            (do (doseq [p problems] (println (str "stage-report: " p)))
                (System/exit 1))
            (println "stage-report: every published stage report matches its records"))))

      (nil? plan-arg)
      (do (println "stage-report: which stage? bb stage-report <plan>/docs/stages/stage-N-<name>.md, or --check")
          (System/exit 2))

      :else
      (let [path (fs/absolutize plan-arg)
            nm (fs/file-name path)
            id (stage-id nm)
            build (some-> path fs/parent fs/parent fs/parent)
            ws (or (workspace/find-workspace (str build)) ws)
            records (:workspace/records ws)]
        (when-not (and (fs/exists? path) id (= "stages" (fs/file-name (fs/parent path))))
          (println (str "stage-report: " path " is not a stage plan at <plan>/docs/stages/stage-N-<name>.md"))
          (System/exit 2))
        (if-let [s (summary-for (str build) records id)]
          (let [block (render s)]
            (spit (str path) (splice (slurp (str path)) block))
            (println block)
            (println (str "written into " nm "'s exit-criteria section; `bb stage-report --check` holds it to the records")))
          (do (println (str "stage-report: stage " id " has no blueprint (docs/stages/stage-" id "-blueprint.md), so no packets to roll up"))
              (System/exit 1)))))))
