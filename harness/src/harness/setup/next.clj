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
  (`docs/stages/stage-N/stage-N-gates.edn`, the record the person writes); is its
  blueprint written, reviewed, signed, and its §0's human prep done; which of
  its packets have a merged record under the build's `runs/`. Before a workspace exists the next action
  is setup (`bb self-check`, `bb init` in KIT's clone); in a workspace whose
  `source.md` names no brief yet, scoping - `/kit-scoping`, then `bb brief`.

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
   [harness.setup.stages :as stages]
   [harness.setup.upgrade :as upgrade]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; pure: the table
;; ---------------------------------------------------------------------------

(def steps
  "The workflow's steps by number, as `workflow.md` numbers them."
  {0 "setup" 1 "scoping" 2 "the plan, for stage 0" 3 "the plan review" 4 "the plan check"
   5 "approval, for stage 0" 6 "stage 0" 7 "the stage plan" 8 "the blueprint"
   9 "the blueprint review" 10 "sign-off" 11 "a packet" 12 "the loop" 13 "the merge"
   14 "the stage's end"})

(defn- action [step owner run because]
  {:step step :name (steps step) :owner owner :run run :because because})

(def person "the person")
(def architect "the Architect's session")

(defn- gates-file [id] (stages/rel id "-gates.edn"))

(defn- pull
  "How stage `id` is pulled: its folder, and the gates template copied into it."
  [id]
  (str "mkdir -p docs/stages/stage-" id ", copy docs/stages/stage-N-gates-template.edn to " (gates-file id)))

(def ^:private names-no-one
  "What a sign-off's `:by` may not be, in lower case: the gates template's own words, and the
  role it once gave as its example. A record carrying either names no one."
  #{"name of who signed" "the person"})

(defn unnamed?
  "Is the gate `g` passed - a map - with a `:by` that names no one: missing, blank, not a
  string, the template's words or the role? No code reads `:by`'s value, so this is the one
  check that a sign-off says who signed."
  [g]
  (and (map? g)
       (let [by (:by g)]
         (or (not (string? by))
             (str/blank? by)
             (contains? names-no-one (str/lower-case (str/trim by)))))))

;; ---------------------------------------------------------------------------
;; the blueprint's §0: what a person does before a packet is dispatched
;; ---------------------------------------------------------------------------

(defn- cells [line]
  (mapv str/trim (str/split (str/replace (str/trim line) #"^\||\|$" "") #"\|" -1)))

(defn preconditions
  "The rows of a blueprint's §0, *Dispatch preconditions* (the template's `# | Prep | Blocks |
  Status` table), as `{:id :prep :blocks :status}` in document order: every row whose `#` is
  `HP-n` and whose Prep is written - the template's empty `HP-0` row is not one."
  [text]
  (let [section (->> (str/split-lines text)
                     (drop-while #(not (re-find #"^## 0\." %)))
                     rest
                     (take-while #(not (re-find #"^#{1,2} " %))))
        [header & rows] (filter #(str/starts-with? (str/trim %) "|") section)
        col (when header (into {} (map-indexed (fn [i c] [(str/lower-case c) i]) (cells header))))]
    (vec (for [r rows
               :let [c (cells r)
                     at #(get c (get col % -1) "")
                     id (str/replace (at "#") #"[*`]" "")]
               :when (and (re-matches #"HP-\d+" id) (not (str/blank? (at "prep"))))]
           {:id id :prep (at "prep") :blocks (at "blocks") :status (at "status")}))))

(defn precondition-state
  "`:done`, `:per-run` or `:open`, from a §0 row's Status: one that begins `done` is settled,
  one that begins `per run` is done again before every run of what it blocks, and anything
  else - blank, `open`, `green`, a note - is open."
  [status]
  (let [s (-> status str/lower-case (str/replace #"[*_`]" "") str/trim)]
    (cond (str/starts-with? s "done") :done
          (str/starts-with? s "per run") :per-run
          :else :open)))

(defn blocks?
  "Does a §0 row's Blocks name the packet `task-id` - the id as a word, or `every task`?"
  [blocks task-id]
  (or (boolean (re-find #"(?i)\bevery task\b" blocks))
      (boolean (re-find (re-pattern (str "(?<![\\w-])" (java.util.regex.Pattern/quote task-id) "(?![\\w-])")) blocks))))

(defn- prep-line [{:keys [id prep]}]
  (let [p (str/replace prep #"\s+" " ")]
    (str id " (" (if (> (count p) 90) (str (subs p 0 90) "…") p) ")")))

(def ^:private signed-gates
  "The gates that are a sign-off, in the order they are passed; `:stage/closed` names no signer."
  [:stage/approved :blueprint/signed :security/signed])

(defn- stage-action
  "The next action inside the stage `s` in progress: `{:id :plan :kind :gates
  :blueprint :reviewed? :packets :merged :runs}` - `:gates` the record's map or
  nil, `:plan` and `:blueprint` file names or nil, `:kind` the plan's kind
  line or nil, `:packets` the blueprint's task ids in order, `:merged` the set
  with a merged record, `:runs` task id → the latest record's status for the
  rest. A pre-release stage has a fourth gate, its threat model signed, which
  comes before its close. A sign-off whose `:by` names no one (`unnamed?`) is
  said before anything that follows it. Before a packet's run is started, the blueprint's §0
  (`:preconditions`): a row still open that blocks it comes first, the person's; a `per run` row
  is named with the packet's step."
  [{:keys [id plan kind gates blueprint reviewed? packets merged runs preconditions]}]
  (let [{:stage/keys [approved closed] :blueprint/keys [signed]} gates
        spike? (= 0 id)
        unsigned-threat-model? (and (= "pre-release" kind) (nil? (:security/signed gates)))
        sign-threat-model (str "every line of docs/02-architecture.md §15 answered with its evidence, then "
                               ":security/signed {:date :by <name>} in " (gates-file id))
        unnamed-gate (first (filter #(unnamed? (get gates %)) signed-gates))
        next-packet (first (remove merged packets))
        prep-for (fn [state] (filter #(and (= state (precondition-state (:status %))) (blocks? (:blocks %) next-packet))
                                     preconditions))
        open-prep (when (and next-packet (contains? #{nil :abandoned} (get runs next-packet))) (prep-for :open))
        per-run (when next-packet (seq (map :id (prep-for :per-run))))
        start-packet (fn [t because]
                       (action 11 architect (str (when per-run (str "per run, first " (str/join ", " per-run) " (docs/stages/" blueprint " §0); then "))
                                                 "bb spec-from-blueprint docs/stages/" blueprint " " t " <run-dir>/spec.edn, then bb run-loop start <run-dir>")
                               because))]
    (cond
      (nil? gates)
      (action (if spike? 5 7) person
              (str (pull id)
                   (if spike? " and set :stage/approved with the cap" (str " for stage " id)))
              (str "docs/stages/stage-" id "/ has the stage's plan and no gates record"))

      unnamed-gate
      (let [by (:by (get gates unnamed-gate))]
        (action ({:stage/approved (if spike? 5 7) :blueprint/signed 10 :security/signed 14} unnamed-gate) person
                (str "set :by in " unnamed-gate " to the name of who signed, in " (gates-file id))
                (str "stage " id "'s " unnamed-gate
                     (if (and (string? by) (not (str/blank? by))) (str " has :by " (pr-str by)) " has no :by")
                     " - a sign-off names who signed")))

      (and (nil? approved) spike?)
      (action 5 person (str "set :stage/approved {:date :cap :by <name>} in " (gates-file id))
              (str "stage 0 is not approved (" (gates-file id) ")"))

      (and (nil? approved) (nil? plan))
      (action 7 architect "/kit-stage-plan"
              (str "stage " id " is pulled (" (gates-file id) ") and docs/stages/stage-" id "/ has no plan"))

      (nil? approved)
      (action 7 person (str "read docs/stages/" plan ", then set :stage/approved {:date :cap :by <name>} in " (gates-file id))
              (str "stage " id "'s plan is written and not approved"))

      (nil? plan)
      (action 6 architect "/kit-stage-plan (stage 0's plan, from the spike template)"
              "stage 0 is approved and docs/stages/stage-0/ has no plan")

      (nil? blueprint)
      (action 8 architect (str "write " (stages/rel id "-blueprint.md") " from docs/stages/stage-N-blueprint-template.md"
                               ", from docs/stages/" plan)
              (str "stage " id " is approved and has no blueprint"))

      (not reviewed?)
      (action 9 architect (str "bb blueprint-review docs/stages/" blueprint)
              (str "docs/stages/" blueprint " has no reading under docs/stages/stage-" id "/reviews/"))

      (nil? signed)
      (action 10 person (str "resolve the review's findings in docs/stages/" plan ", then set :blueprint/signed {:date :by <name>} in " (gates-file id))
              (str "stage " id "'s blueprint is reviewed and not signed"))

      (empty? packets)
      (action 8 architect (str "add the packets to docs/stages/" blueprint)
              (str "docs/stages/" blueprint " carries no packet (a fence whose first form has a :task/id)"))

      (seq open-prep)
      (let [[hp & more] open-prep]
        (action 11 (str person " (with the Architect's session)")
                (str "do " (prep-line hp) ", then set its Status to done in docs/stages/" blueprint " §0")
                (str (:id hp) " is open in docs/stages/" blueprint " §0 and blocks " next-packet
                     (when (seq more) (str "; open before it too: " (str/join ", " (map :id more)))))))

      (some #(not (merged %)) packets)
      (let [t next-packet
            status (get runs t)]
        (case status
          :awaiting-merge (action 13 person (str "bb run-loop merge <run-dir> <decision.edn> for " t)
                                  (str "the run for " t " is stopped :awaiting-merge"))
          :escalated (action 12 person (str "read the run for " t " and decide; then bb run-loop run <run-dir>")
                             (str "the run for " t " is :escalated"))
          :abandoned (start-packet t (str "the run for " t " was abandoned; no merged record"))
          nil (start-packet t (str t " has no run record; " (count merged) " of " (count packets) " packets merged"))
          (action 12 architect (str "bb run-loop run <run-dir> for " t)
                  (str "the run for " t " is " status))))

      (nil? closed)
      (action 14 architect (if unsigned-threat-model?
                             (str "/kit-stage-end; a pre-release stage's exit criteria include its threat model: "
                                  sign-threat-model ", before :stage/closed")
                             "/kit-stage-end")
              (str "every packet of stage " id " is merged (" (count packets) ") and the stage is not closed"))

      unsigned-threat-model?
      (action 14 person sign-threat-model
              (str "stage " id " is pre-release and closed without its threat model signed"))

      :else
      (action 7 person (str "pull stage " (inc id) " by kind: " (pull (inc id)) ", then /kit-stage-plan")
              (str "stage " id " is closed")))))

(defn plan-step?
  "Is a plan being written - the plan, a stage's plan or its blueprint - so that a mark
  plan-check finds is one still to fill, not one left behind? Before any stage; and in a stage
  until its blueprint is signed. Both are written from a marked template, and neither order
  closes the window early: stage 0 is approved before its plan is written, a later stage after,
  and every blueprint after the approval. `stage` as `stage-action` reads it."
  [stage]
  (let [{:stage/keys [closed] :blueprint/keys [signed]} (:gates stage)]
    (or (nil? stage)
        (and (nil? closed) (nil? signed)))))

(defn- plan-first?
  "Does plan-check's list send the workflow back to step 2: before any stage, or while stage 0
  is not approved - the plan passes its check before stage 0's approval."
  [stage]
  (or (nil? stage)
      (and (zero? (:id stage)) (nil? (get-in stage [:gates :stage/approved])))))

(defn- plan-beside
  "The line said beside a stage's own step when plan-check finds something: in a plan step, the
  plan being written; outside one, a plan document to fix - never step 2 of a stage that is past it."
  [{:keys [id] :as stage} problems]
  (let [n (count problems)]
    (str "bb plan-check: " n " thing" (when (not= 1 n) "s") " left - "
         (if (plan-step? stage)
           (str "stage " id "'s plan and blueprint are being written - their marks stand until the blueprint is signed")
           (str (first problems) " - fix it before stage " (inc id) "'s plan")))))

(defn- step-action
  [{:keys [workspace? brief? plan-problems stage upgrade]}]
  (cond
    (not workspace?)
    (action 0 person "in KIT's clone: bb self-check, then bb init; then a session in the workspace folder"
            "no workspace.edn at or above here")

    (not brief?)
    (action 1 (str person ", with an Architect session") "/kit-start, then /kit-scoping - it writes work/brief.md - then bb brief files it"
            "docs/source.md §1 still says <the brief>")

    (and (seq plan-problems) (plan-first? stage))
    (action 2 architect "/kit-plan; bb plan-check lists what is left, bb plan-review before the approval"
            (str "bb plan-check: " (count plan-problems) " thing" (when (not= 1 (count plan-problems)) "s") " left"))

    (nil? stage)
    (action 5 person (str (pull 0) " and set :stage/approved with the cap")
            "the plan passes its check and docs/stages/ has no stage yet")

    :else (let [left (when upgrade (upgrade/upgrade-lines upgrade))]
            (cond-> (stage-action stage)
              (seq plan-problems) (assoc :also (plan-beside stage plan-problems))
              ;; WHAT THE LAST UPGRADE LEFT - the branch to merge, hunks and .new files to take by hand -
              ;; said beside the step, the Architect's, until it is resolved
              (seq left) (update :also #(str/join "\n           " (remove nil? (cons % left))))))))

(defn- in-build
  "`s` with every `docs/` path put under the build folder `build`, so a command
  printed runs from the workspace folder, where every command is run; `s` as is
  when `build` is nil."
  [build s]
  (if build (str/replace s #"(?<![\w./<>-])docs/" (str build "/docs/")) s))

(defn next-action
  "The next step from `facts`: `:workspace?`; `:brief?` (source.md §1 names
  one); `:plan-problems` (what `bb plan-check` reports, or nil when it could
  not run); `:stage` the stage in progress as `stage-action` reads it, or nil
  when docs/stages/ has none; `:build` the build folder as the workspace names
  it, which every `docs/` path printed is put under. Returns `{:step :name
  :owner :run :because}`, and `:also` when plan-check finds something once stage 0
  is approved: said beside the stage's own step, never in its place."
  [{:keys [build] :as facts}]
  (let [a (step-action facts)]
    (cond-> (-> a
                (update :run (partial in-build build))
                (update :because (partial in-build build)))
      (:also a) (update :also (partial in-build build)))))

;; ---------------------------------------------------------------------------
;; the edge: facts
;; ---------------------------------------------------------------------------

(defn- read-edn [path] (try (edn/read-string (slurp (str path))) (catch Exception _ nil)))

(defn stage-kind
  "A stage plan's kind, from its `**Kind:**` line (`spike`, `pre-release`, …), or nil."
  [text]
  (some-> (re-find #"(?m)^\*\*Kind:\*\*\s*(.*?)\s*$" text) second))

(defn stage-files
  "The stages under `docs/stages/`, by id - a folder `stage-N/` each
  (`harness.setup.stages`): `{id {:plan path :blueprint path :gates map}}`,
  the paths from `docs/stages/` (`stage-1/stage-1-plan.md`). The plan is the
  folder's `.md` that is not the blueprint; the gates record is
  `stage-N-gates.edn`. A folder with none of them is a stage with nothing yet."
  [stages-dir]
  (into (sorted-map)
        (for [[id dir] (stages/folders stages-dir)
              :let [in-dir #(str (stages/folder-name id) "/" %)
                    gates (fs/path dir (str (stages/folder-name id) "-gates.edn"))
                    blueprint (str (stages/folder-name id) "-blueprint.md")]]
          [id (cond-> {}
                (fs/exists? gates) (assoc :gates (read-edn gates))
                (fs/exists? (fs/path dir blueprint)) (assoc :blueprint (in-dir blueprint))
                (stages/plan-file dir) (assoc :plan (in-dir (stages/plan-file dir))))])))

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
          bp-text (some->> (:blueprint files) (fs/path build "docs" "stages") str slurp)
          packets (when bp-text (mapv :task/id (blueprint/packets bp-text)))]
      {:workspace? true
       :build (str (fs/relativize (fs/absolutize (:workspace/dir ws)) (fs/absolutize build)))
       :kit (some->> (:workspace/kit ws) fs/absolutize (fs/relativize (fs/absolutize (:workspace/dir ws))) str)
       :brief? (and (fs/exists? source) (not (str/includes? (slurp (str source)) "<the brief>")))
       :plan-problems (:problems (plan/check build ws))
       :stage (when id
                (merge files
                       {:id id
                        :kind (some->> (:plan files) (fs/path build "docs" "stages") str slurp stage-kind)
                        :reviewed? (fs/exists? (fs/path (stages/reviews-dir build id) "blueprint-review.edn"))
                        :packets (or packets [])
                        :preconditions (if bp-text (preconditions bp-text) [])
                        :merged (into #{} (filter #(= :merged (statuses %))) packets)
                        :runs (into {} (for [t packets :let [s (statuses t)] :when (and s (not= :merged s))] [t s]))}))
       :upgrade (upgrade/open-after-upgrade ws (read-edn (fs/path (:workspace/dir ws) workspace/file-name)))})))

(defn render
  "The action as `bb next` prints it."
  [{:keys [step name owner run because also]}]
  (str "next: step " step " - " name "\n"
       "  owner:   " owner "\n"
       "  run:     " run "\n"
       "  because: " because
       (when also (str "\n  also:    " also))))

;; ---------------------------------------------------------------------------
;; where we are: `bb next --where`
;; ---------------------------------------------------------------------------

(def id-kinds
  "Every kind of ID a project's documents carry, and the file (under the build's `docs/`; the
  blueprint's as its template is named in `build-docs/`) and section that DEFINE it - the one place
  to read one, however many files cite it. The templates say the same at each section, in a
  sentence opening `**IDs.**`; a test holds the two together."
  [{:id "FR-n" :file "01-requirements.md" :section "6b" :note "the index names the document that wrote it"}
   {:id "NFR-n" :file "01-requirements.md" :section "7"}
   {:id "S-n.m" :file "source.md" :section "2" :note "heading S-n, point m"}
   {:id "Q-n" :file "source.md" :section "3"}
   {:id "RV-n" :file "04-decision-log.md" :section "1"}
   {:id "Dn" :file "04-decision-log.md" :section "2"}
   {:id "Sn" :file "04-decision-log.md" :section "3" :note "a schema decision"}
   {:id "P0-n" :file "04-decision-log.md" :section "4"}
   {:id "Rn" :file "00-overview.md" :section "4a" :note "the risk"}
   {:id "Rn" :file "04-decision-log.md" :section "5" :note "the risk's spike"}
   {:id "stage N" :file "00-overview.md" :section "4"}
   {:id "Fn" :file "00-overview.md" :section "5"}
   {:id "threat n" :file "02-architecture.md" :section "15"}
   {:id "HP-n" :file "stages/stage-N-blueprint-template.md" :section "0" :shown "the stage's blueprint"}
   {:id "t-NN" :file "stages/stage-N-blueprint-template.md" :section "4" :shown "the stage's blueprint"}])

(def kit-terms
  "The KIT's own words the block uses, each with the file at the KIT's root and the heading that
  defines it - a phase and a step are the method's and the workflow's, not the project's. A test
  holds each heading to its file."
  [{:id "phase" :file "method.md" :heading "01 — The phase map"}
   {:id "step n" :file "workflow.md" :heading "The steps, and what each produces"}])

(def project-plan
  "Where the project plan starts: the overview, whose section lists the plan's documents."
  {:file "00-overview.md" :section "3" :heading "How the plan is organized"})

(defn section-lines
  "The lines of `text`'s section `section` (`\"4a\"`: the heading `## 4a.` or `### 4a.`), up to the
  next heading of any level; nil when there is no such heading."
  [text section]
  (let [head (re-pattern (str "^#{2,3} " (java.util.regex.Pattern/quote section) "\\."))
        [h & more] (drop-while #(not (re-find head %)) (str/split-lines text))]
    (when h (take-while #(not (re-find #"^#{1,6} " %)) more))))

(defn table-rows
  "The rows of the first Markdown table in `text`'s section `section`, as maps from each header
  cell, in lower case, to the row's cell; the separator row is not one."
  [text section]
  (let [[header & rows] (->> (section-lines text section)
                             (drop-while #(not (str/starts-with? (str/trim %) "|")))
                             (take-while #(str/starts-with? (str/trim %) "|")))
        ks (when header (mapv str/lower-case (cells header)))]
    (vec (for [r rows
               :let [c (cells r)]
               :when (not (every? #(re-matches #":?-+:?" %) c))]
           (zipmap ks (concat c (repeat "")))))))

(defn- plain [s] (str/trim (str/replace (or s "") #"[*`]" "")))

(defn- status-cell [row] (some (fn [[k v]] (when (str/includes? k "status") v)) row))

(defn- name-cell [row] (some (fn [[k v]] (when (str/starts-with? k "name") v)) row))

(defn open-decisions
  "The decision log's entries not settled - the Status cell begins PROVISIONAL or OPEN - as
  `{:id :section}`, in the log's order."
  [log]
  (vec (for [s ["1" "2" "3" "4" "5"]
             r (table-rows log s)
             :let [st (str/upper-case (plain (status-cell r)))]
             :when (or (str/starts-with? st "PROVISIONAL") (str/starts-with? st "OPEN"))]
         {:id (plain (get r "#")) :section s})))

(defn open-risks
  "The overview's §4a risks whose Status is OPEN, by id, in the register's order."
  [overview]
  (vec (for [r (table-rows overview "4a")
             :when (str/starts-with? (str/upper-case (plain (status-cell r))) "OPEN")]
         (plain (get r "#")))))

(defn stage-map
  "The overview's §4 stage map: `{id {:kind :name :delivers :status}}` for every row whose Stage
  cell is a number; the name from the column whose header starts `Name`."
  [overview]
  (into (sorted-map)
        (for [r (table-rows overview "4")
              :let [id (plain (get r "stage"))]
              :when (re-matches #"\d+" id)]
          [(parse-long id) {:kind (plain (get r "kind")) :name (plain (name-cell r))
                            :delivers (str/trim (get r "delivers" "")) :status (plain (get r "status"))}])))

(def ^:private phases
  "A stage's kind → its phase, `method.md` §01; pre-release is tried before release."
  [[#"(?i)pre-?release" "D PRE-RELEASE"] [#"(?i)release" "E RELEASE"] [#"(?i)spike" "B PROVE"]
   [#"(?i)skeleton|increment" "C BUILD"]])

(defn phase
  "The phase of a stage of kind `kind`, letter and word, or nil for a kind not one of §01's."
  [kind]
  (when kind (some (fn [[re p]] (when (re-find re kind) p)) phases)))

(defn where-facts
  "`facts`, and what the block adds, from the build's documents: `:stages` every stage's files
  (`stage-files`), `:map` the stage map, `:decisions` those PROVISIONAL or OPEN, `:risks` the OPEN
  ones - each nil when its document is not there."
  [ws]
  (let [f (facts ws)]
    (if-not (:workspace? f)
      f
      (let [docs (fs/path (:workspace/build ws) "docs")
            text #(let [p (fs/path docs %)] (when (fs/exists? p) (slurp (str p))))
            overview (text "00-overview.md")
            log (text "04-decision-log.md")]
        (assoc f
               :stages (stage-files (fs/path docs "stages"))
               :map (some-> overview stage-map)
               :decisions (some-> log open-decisions)
               :risks (some-> overview open-risks))))))

(defn- cut
  "`s` cut to `n` characters at the last space before them, and `…`; `s` as is when it fits."
  [n s]
  (if (> (count s) n)
    (let [t (subs s 0 n) i (str/last-index-of t " ")]
      (str (if (and i (pos? i)) (subs t 0 i) t) "…"))
    s))

(defn- more [xs] (when (> (count xs) 6) (str " and " (- (count xs) 6) " more")))

(defn- gate-date [g] (if (and (map? g) (:date g)) (str " " (:date g)) ""))

(defn- done-line [stages {:keys [id gates]}]
  (let [ended (for [[k {g :gates}] stages :when (:stage/closed g)] (str "stage " k " closed" (gate-date (:stage/closed g))))
        {:stage/keys [approved closed] :blueprint/keys [signed]} gates
        now (when-not closed
              (seq (remove nil? [(when approved (str "approved" (gate-date approved)))
                                 (when signed (str "blueprint signed" (gate-date signed)))])))]
    (str/join "; " (or (seq (concat ended (when now [(str "stage " id " " (str/join ", " now))])))
                       ["nothing closed yet"]))))

(defn- open-lines [decisions risks]
  [(cond (nil? decisions) "decisions: (no docs/04-decision-log.md)"
         (empty? decisions) "decisions: none PROVISIONAL or OPEN"
         :else (str "decisions PROVISIONAL or OPEN: "
                    (str/join ", " (for [ds (partition-by :section (take 6 decisions)) :let [s (:section (first ds))]]
                                     (str (str/join ", " (map :id ds)) " (§" s ")")))
                    (more decisions) " - docs/04-decision-log.md"))
   (cond (nil? risks) "risks: (no docs/00-overview.md)"
         (empty? risks) "risks: none OPEN"
         :else (str "risks OPEN: " (str/join ", " (take 6 risks)) (more risks) " - docs/00-overview.md §4a"))])

(defn- read-more-lines
  "`id-kinds` as lines of about a hundred characters."
  []
  (->> id-kinds
       (map (fn [{:keys [id file section note shown]}]
              (str id " " (or shown file) " §" section (when note (str " (" note ")")))))
       (reduce (fn [lines e]
                 (let [l (peek lines)]
                   (if (and l (< (+ (count l) (count e)) 100)) (conj (pop lines) (str l " · " e)) (conj lines e))))
               [])))

(defn render-where
  "The block `bb next --where` prints, from `where-facts` and its `next-action`: fixed lines, a
  fact that cannot be read said as such. The phase and `next:` before a stage exists; the rest
  once one does. Every `docs/` path is put under the build, as `next-action`'s are."
  [{:keys [workspace? build kit stage stages decisions risks] smap :map} {:keys [step name owner run also]}]
  (let [line (fn [label s] (str "  " label (subs "             " (min 13 (count label))) s))
        nxt (line "next:" (str "step " step " - " name "; owner: " owner "; run: " run))
        also* (when also (str/replace also #"\s*\n\s*" "; "))
        alsos (when also* [(line "also:" (str (cut 160 also*) (when (> (count also*) 160) " (bb next prints it whole)")))])
        out (fn [ls] (in-build build (str/join "\n" ls)))]
    (cond
      (not workspace?)
      (out (concat ["where: phase A SETUP - no workspace yet" nxt] alsos))

      (nil? stage)
      (out (concat [(str "where: " build " - phase B PROVE, before stage 0: the brief, then the plan") nxt] alsos))

      :else
      ;; A CLOSED STAGE IS BEHIND US: the block is about the next one, not pulled yet - the moment
      ;; a person asks where we are is between a stage's end and the next plan
      (let [{:keys [gates]} stage
            closed (:stage/closed gates)
            {:keys [id plan blueprint reviewed? packets merged]} (if closed {:id (inc (:id stage))} stage)
            row (get smap id)
            kind (or (when-not closed (:kind stage)) (:kind row) (when (zero? id) "spike"))
            last-id (some-> smap keys last)
            indent (str "  " (subs "             " 0 13))]
        (out (concat
              [(str "where: " build " - phase " (or (phase kind) "(not read: the stage's kind)")
                    (when closed (str ", stage " (:id stage) " closed" (gate-date closed) "; next"))
                    ", stage " id (when last-id (str " of 0-" last-id))
                    (when kind (str ": " kind)) (when (seq (:name row)) (str ", \"" (:name row) "\""))
                    (when closed " - not pulled yet"))
               (line "stage map:" (str "docs/00-overview.md §4"
                                       (cond (nil? smap) " (not there)"
                                             (nil? row) (str " (no row for stage " id ")")
                                             :else (str " - status there: " (cut 60 (:status row))))))
               (line "stage goal:" (if (seq (:delivers row)) (cut 100 (:delivers row)) "(no Delivers cell on the stage map)"))
               (str indent (if plan (str "docs/stages/" plan " §1") (str "(no plan yet in docs/stages/stage-" id "/)")))
               (line "blueprint:" (if blueprint
                                    (str "docs/stages/" blueprint " - " (if reviewed? "reviewed" "not reviewed") ", "
                                         (if (:blueprint/signed gates) (str "signed" (gate-date (:blueprint/signed gates))) "not signed")
                                         "; " (count packets) " packet" (when (not= 1 (count packets)) "s") ", " (count merged) " merged")
                                    "(none yet)"))
               (line "done:" (done-line stages stage))
               nxt]
              alsos
              (let [[d r] (open-lines decisions risks)] [(line "open:" d) (str indent r)])
              (let [[l & ls] (read-more-lines)]
                (concat [(line "read more:" (str/join " · " (for [{:keys [id file heading]} kit-terms]
                                                              (str id " " (or kit "KIT") "/" file " \"" heading "\""))))
                         (str indent "the project plan docs/" (:file project-plan) " (§" (:section project-plan)
                              " lists its documents)")
                         (str indent "in docs/: " l)]
                        (map #(str indent %) ls)))))))))

(defn -main
  "bb next [--where] [--workspace <dir>]

  The workflow's next step, its owner, and the command or skill to run, from
  the build's files; with `--where`, the block that says where the project
  stands first - the phase, the stage and its goal, what is done, what is
  open, where a phase, a step, the project plan and each kind of ID are defined. Information:
  exit 0 always."
  [& args]
  (let [ws (workspace/current-or-exit (remove #{"--where"} args))]
    (if (some #{"--where"} args)
      (let [wf (where-facts ws)]
        (println (render-where wf (next-action wf))))
      (println (render (next-action (facts ws)))))))
