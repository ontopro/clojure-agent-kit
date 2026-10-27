(ns harness.setup.where
  "Where the project stands, as one map: `bb next --where --edn`.

  `bb next --where` prints the block that opens the Architect's reports - terse, a step's
  boundary. A person back after a week needs more: the whole road with a mark where they are,
  what each stage has cost and taken, what waits on them, and what changed since the last
  report was made. `where-data` reads all of it from the build's files, through the readers that already
  exist - `next/where-facts`, `next/next-action`, the stage report's figures - and returns plain
  data, every view a rendering of it: the fuller account and the roadmap page are drawn from
  this map, never from the documents a second time.

  ONE MAP, PLAIN EDN. No path object, no regex, no function: a test prints it and reads it back
  equal. The clock and the build's commit are passed in, so the same files give the same map.

  `bb next` stays read-only in every form; the writers of `work/where/` are tasks of their own."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [harness.money.stage-report :as stage-report]
   [harness.setup.next :as next]
   [harness.setup.stages :as stages]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; pure: the readings of a cell
;; ---------------------------------------------------------------------------

(def phases
  "`method.md`'s phase map, in its order: letter and word, as `next/phase` names them."
  [["A" "SETUP"] ["B" "PROVE"] ["C" "BUILD"] ["D" "PRE-RELEASE"] ["E" "RELEASE"]])

(defn- plain [s] (str/trim (str/replace (or s "") #"[*`]" "")))

(defn cap
  "A cap cell read: `{:cap n :cap-note rest}` - `$` and a number, the rest of the cell kept
  (`\"$12 proposed\"` → 12, \"proposed\"); a cell with no `$` number is `{:cap nil :cap-note
  \"not read: <cell>\"}`; a blank or a dash is `{}`."
  [cell]
  (let [c (plain cell)]
    (if (or (str/blank? c) (re-matches #"[—–-]+" c))
      {}
      (if-let [[m n] (re-find #"\$\s*(\d+(?:\.\d+)?)" c)]
        (let [note (str/trim (str/replace (str/replace-first c m "") #"^[;,\s]+" ""))]
          (cond-> {:cap (let [v (parse-double n)] (if (== v (Math/floor v)) (long v) v))}
            (seq note) (assoc :cap-note note)))
        {:cap nil :cap-note (str "not read: " c)}))))

(defn days
  "Calendar days from the date `from` to the date `to`, both counted - approved and closed on one
  day is 1; nil when either is missing or not a date."
  [from to]
  (try (when (and from to)
         (inc (.between java.time.temporal.ChronoUnit/DAYS
                        (java.time.LocalDate/parse from) (java.time.LocalDate/parse to))))
       (catch Exception _ nil)))

(defn cents
  "`x` dollars rounded to the cent, once, here - so every view of the map says the same figure."
  [x]
  (when x (/ (Math/round (* 100.0 (double x))) 100.0)))

(defn- gate-date [g] (stages/gate-day g nil))

(defn- gate-time
  "When gate `g` was passed, to count it since a report: `{:at <Instant> :day \"YYYY-MM-DD\"
  :day-only? bool}` - `:day-only?` a gate written with `:date` before gates carried a time, which
  is counted by its day. Nil for no gate."
  [g z]
  (when (map? g)
    {:at (stages/gate-instant g z) :day (stages/gate-day g z) :day-only? (not (contains? g :at))}))

(defn ids-in
  "The decision and risk IDs a stage map cell names - `D7`, `S3`, `P0-8`, `RV-2`, `R5` - in the
  cell's order, once each; the cell's prose around them is not one."
  [cell]
  (vec (distinct (re-seq #"(?<![\w-])(?:RV-\d+|P0-\d+|[DSR]\d+)(?![\w-])" (plain cell)))))

(defn owner-of
  "The text after `owner:` in a Status cell, to the sentence's end - a full stop that follows a
  word of more than two letters, so `Dr.` is not one - or nil."
  [status]
  (some-> (re-find #"(?i:owner):\s*(.*?)(?:(?<![A-Z][a-z]?)\.(?:\s|$)|$)" (plain status)) second str/trim))

(defn names-the-person?
  "Does `cell` name the person: the words \"the person\", or one of `names` - the `:by` of a
  sign-off in the gates records - as a word, in any case?"
  [names cell]
  (let [c (str/lower-case (plain cell))]
    (boolean (or (str/includes? c "the person")
                 (some #(re-find (re-pattern (str "\\b" (java.util.regex.Pattern/quote (str/lower-case %)) "\\b")) c)
                       names)))))

(defn open-decisions
  "The decision log's entries PROVISIONAL or OPEN, `{:id :section :status :subject :owner}` in the
  log's order: `:owner` the text after `owner:` in the Status cell, or nil."
  [log]
  (vec (for [s ["1" "2" "3" "4" "5"]
             r (next/table-rows log s)
             :let [cell (some (fn [[k v]] (when (str/includes? k "status") v)) r)
                   st (str/upper-case (plain cell))
                   status (cond (str/starts-with? st "PROVISIONAL") "PROVISIONAL" (str/starts-with? st "OPEN") "OPEN")]
             :when status]
         {:id (plain (get r "#")) :section s :status status
          :subject (plain (or (get r "decision") (get r "spike"))) :owner (owner-of cell)})))

(defn settled
  "The log's RESOLVED entries: `{:dated [{:id :on}] :undated n}` - dated when the Status cell
  carries a date after RESOLVED, `**RESOLVED** 2026-10-13`."
  [log]
  (let [rs (for [s ["1" "2" "3" "4" "5"]
                 r (next/table-rows log s)
                 :let [cell (plain (some (fn [[k v]] (when (str/includes? k "status") v)) r))]
                 :when (str/starts-with? (str/upper-case cell) "RESOLVED")]
             {:id (plain (get r "#")) :on (second (re-find #"^(?i)RESOLVED\W*(\d{4}-\d{2}-\d{2})" cell))})]
    {:dated (vec (filter :on rs)) :undated (count (remove :on rs))}))

(defn open-risks
  "The overview's §4a risks OPEN, `{:id :subject :runs-in :spike}` in the register's order."
  [overview]
  (vec (for [r (next/table-rows overview "4a")
             :let [st (some (fn [[k v]] (when (str/includes? k "status") v)) r)]
             :when (str/starts-with? (str/upper-case (plain st)) "OPEN")]
         {:id (plain (get r "#")) :subject (plain (get r "risk")) :runs-in (plain (get r "runs in"))
          :spike (plain (some (fn [[k v]] (when (or (str/starts-with? k "prototype") (str/includes? k "spike")) v)) r))})))

(defn- map-rows
  "The stage map's cells `where-data` needs beyond `next/stage-map`'s: the cap, the risks it
  retires, the decisions it closes - `{id {:cap-cell :risks :decisions}}`."
  [overview]
  (into {} (for [r (next/table-rows overview "4")
                 :let [id (plain (get r "stage"))]
                 :when (re-matches #"\d+" id)]
             [(parse-long id) {:cap-cell (get r "cap" "")
                               :risks (ids-in (some (fn [[k v]] (when (str/starts-with? k "risk") v)) r))
                               :decisions (ids-in (some (fn [[k v]] (when (str/includes? k "decision") v)) r))}])))

;; ---------------------------------------------------------------------------
;; pure: the map from what was read
;; ---------------------------------------------------------------------------

(defn- current-id
  "The stage the person is at: the one in progress, the next when it is closed, 0 before any."
  [stage]
  (cond (nil? stage) 0
        (:stage/closed (:gates stage)) (inc (:id stage))
        :else (:id stage)))

(defn- state [id {:keys [plan gates]} here]
  (cond (:stage/closed gates) :closed
        (:stage/approved gates) :running
        plan :planned
        (= id here) :next
        :else :ahead))

(defn breakdown
  "A stage's money two ways, from its cost rows (`stage-report/cost-rows`): `:by-maker` (the
  models' makers), `:channels` (how each maker was reached) and `:by-role` (each role's models and
  money), the money in cents as `:money` is."
  [rows]
  (when (seq rows)
    (let [exact #(reduce + 0 (map :cost %))]
      {:by-maker (into (sorted-map) (for [[m rs] (group-by :maker rows)] [m (cents (exact rs))]))
       :channels (into (sorted-map) (for [[m rs] (group-by :maker rows)] [m (vec (sort (distinct (map :channel rs))))]))
       :by-role (into {} (for [[r rs] (group-by :role rows)]
                           [r {:models (vec (sort (distinct (keep :model rs)))) :money (cents (exact rs))}]))
       ;; THE UNROUNDED FIGURES, so a total over stages is rounded once, as a stage's own is: a sum of
       ;; rounded cells said $5.29 where the money was $5.28
       :exact {:by-maker (into {} (for [[m rs] (group-by :maker rows)] [m (exact rs)]))
               :by-role (into {} (for [[r rs] (group-by :role rows)] [r (exact rs)]))}})))

(def role-order
  "The roles in the order a build meets them, each with the name a table gives it."
  [[:coder "coder"] [:tester "tester"] [:reviewer "code reviewer"] [:orchestrator "orchestrator"]
   [:spec-reviewer "spec reviewer"] [:plan-reviewer "plan reviewer"] [:blueprint-reviewer "blueprint reviewer"]
   [:security-reviewer "security reviewer"] [:security-routing "security routing"] [:other "other"]])

(defn money-by
  "The two tables' data over `stages` (the road's rows): `{:makers [..] :stages [{:id :by-maker
  :total}] :totals {maker cents} :all cents :channels [[maker [[id [channel ..]] ..]] ..] :roles
  [{:role :label :by-stage {id {:models :money}} :total}]}`, or nil when no stage has money rows."
  [stages]
  (let [with (filter :by-maker stages)]
    (when (seq with)
      (let [makers (vec (sort (distinct (mapcat (comp keys :by-maker) with))))
            sum #(cents (reduce + 0 (keep identity %)))
            exact (fn [k x] (map #(get-in % [:exact k x]) with))]
        {:makers makers
         ;; a stage's total is its own money, as its stage report has it - not the sum of rounded cells
         :stages (vec (for [s with] {:id (:id s) :by-maker (:by-maker s) :total (:money s)}))
         :totals (into {} (for [m makers] [m (sum (exact :by-maker m))]))
         :all (sum (mapcat (comp vals :by-maker :exact) with))
         :channels (vec (for [m makers] [m (vec (for [s with :let [c (get-in s [:channels m])] :when c] [(:id s) c]))]))
         :roles (vec (for [[r label] role-order
                           :let [by (into (sorted-map) (for [s with :let [x (get-in s [:by-role r])] :when x] [(:id s) x]))]
                           :when (seq by)]
                       {:role r :label label :by-stage by :total (sum (exact :by-role r))}))}))))

(defn stage-row
  "One stage of the road, from its stage map row `row`, its extra cells `extra`, its files
  `files` (`next/stage-files`), its figures `figures` (`{:summary :readings :minutes}`), and the
  stage the person is at, `here`, the project's zone `z`. Paths are the workspace's: `files`'
  `:build` before `docs/`. `:times` keeps each gate's instant (`gate-time`), which `since-row`
  counts by: a day alone counted a stage approved an hour before a report as after it."
  [id row extra files figures here z]
  (let [{:keys [gates plan blueprint build]} files
        approved (gate-date (:stage/approved gates))
        closed (gate-date (:stage/closed gates))
        c (if-let [gc (stages/effective-cap gates)] (cap gc) (cap (:cap-cell extra)))
        {:keys [summary readings minutes costs]} figures]
    (merge {:id id :kind (or (not-empty (:kind row)) (:kind files)) :name (:name row) :delivers (:delivers row)
            :map-status (:status row) :state (state id files here)
            :plan (some->> plan (str build "/docs/stages/")) :blueprint (some->> blueprint (str build "/docs/stages/"))
            :approved approved :signed (gate-date (:blueprint/signed gates)) :closed closed
            :days (days approved closed)
            :times (into {} (for [[k g] {:approved :stage/approved :signed :blueprint/signed :closed :stage/closed}
                                  :let [t (gate-time (get gates g) z)]
                                  :when t]
                              [k t]))
            :risks (:risks extra []) :decisions (:decisions extra [])}
           (select-keys c [:cap :cap-note])
           (if summary
             {:runs (:run-count summary) :merged (:merged summary) :rounds (:rounds summary)
              :money (cents (:money summary)) :run-minutes minutes}
             (cond-> {:runs 0 :merged 0 :rounds 0 :run-minutes 0
                      :money (when (seq readings) (cents (reduce + 0 (map second readings))))}
               (seq readings) (assoc :money-note "readings only, no blueprint yet")))
           ;; THE CALLS WITH NO COST, as the stage report counts them: the money is what was priced
           (let [n (if summary
                     (reduce + 0 (vals (:unpriced summary)))
                     (reduce + 0 (keep #(get % 3) readings)))]
             (when (pos? n) {:unpriced n}))
           (breakdown costs))))

(defn phase-rows
  "The five phases, each `{:letter :word :status :stages}`: SETUP done once there is a workspace;
  another `:done` when it holds stages and every one is closed, `:here` when it holds the stage the
  person is at, else `:ahead`."
  [stage-rows here]
  (let [by-phase (group-by #(some-> (next/phase (:kind %)) (str/split #" ") first) stage-rows)]
    (vec (for [[letter word] phases
               :let [ss (get by-phase letter)
                     ids (mapv :id ss)]]
           {:letter letter :word word :stages ids
            :status (cond (= "A" letter) :done
                          (some #{here} ids) :here
                          (and (seq ss) (every? #(= :closed (:state %)) ss)) :done
                          :else :ahead)}))))

(defn prep-parts
  "A blueprint §0 prep cell as its parts, split at \"; \", each trimmed: the first is the line a
  view shows, the rest its sub-bullets. A cell of nine parts was shown whole, in bold, and read as
  one run of words."
  [prep]
  (vec (remove str/blank? (map str/trim (str/split (str prep) #";\s+")))))

(defn waits
  "What waits on the person, in order: the next step when it is theirs - it names the first
  gate not passed, so the gates are said there and nowhere else; the blueprint's §0 rows still
  open that the next step does not name; the open decisions whose owner names them; the open
  risks whose spike does. Each `{:what :why :where :run}`; a §0 row's `:what` is its id, with
  `:detail` its prep's first part and `:items` the rest (`prep-parts`)."
  [{nxt :next :keys [current open names kit build]}]
  (let [prep-open (filter #(= :open (next/precondition-state (:status %))) (:preconditions current))]
    (vec (concat
          (when (= next/person (:owner nxt))
            [{:what (:name nxt) :why "the next step is the person's"
              :where (str (or kit "KIT") "/workflow.md step " (:step nxt)) :run (:run nxt)}])
          (for [{:keys [id prep]} prep-open
                :when (not (str/includes? (str (:run nxt)) id))
                :let [[line & items] (prep-parts prep)]]
            {:what id :detail line :items (vec items) :why "the blueprint's human prep, not done"
             :where (str build "/docs/stages/" (:blueprint current) " §0") :run nil})
          (for [{:keys [id subject owner section]} (:decisions open)
                :when (and owner (names-the-person? names owner))]
            {:what (str id ", " subject) :why (str "an open decision whose owner is the person: " owner)
             :where (str build "/docs/04-decision-log.md §" section) :run nil})
          (for [{:keys [id subject spike]} (:risks open)
                :when (names-the-person? names spike)]
            {:what (str id ", " subject) :why (str "an open risk the person retires: " spike)
             :where (str build "/docs/00-overview.md §4a") :run nil})))))

(defn wait-line
  "One wait as `render-full` lists it: the id or name bold, a §0 row's first part after it, its
  other parts as sub-bullets under the line."
  [{:keys [what detail items why where run]}]
  (str "- **" what "**" (when detail (str ": " detail)) " - " why (when run (str ". Run: " run)) " (" where ")"
       (apply str (for [i items] (str "\n  - " i)))))

(def state-words
  "A stage's state as the section says it, in the order it lists them."
  (array-map :closed "closed" :running "in progress" :planned "planned" :next "next" :ahead "ahead"))

(defn- by-state
  "`stage-rows` as `[{:state \"closed\" :ids [4 5]} ...]`, each state once, in `state-words`' order."
  [stage-rows]
  (vec (for [[k word] state-words
             :let [ids (vec (keep #(when (= k (:state %)) (:id %)) stage-rows))]
             :when (seq ids)]
         {:state word :ids ids})))

(defn since-row
  "What changed since `base` (`since-from`'s): each stage that passed a gate since, once, with its
  state now; the decisions settled on or after the base's day - the decision log carries a day
  alone, so one settled on that day is counted and marked `:same-day?` - and those settled with no
  date; the runs started since - `run-rows` as `[{:at :status :cost}]`. A stage's gate counts by
  its instant, a gate written with a day alone by its day. `:run-money` is the runs' alone: a
  reading carries no time to count it by, and a view says \"runs only\" beside it.

  NO REPORT BEFORE THIS ONE: `:first?`, each stage with its state now, and nothing counted - not
  counted from the project's start."
  [{:keys [at day] :as base} stage-rows {:keys [dated undated]} run-rows]
  (let [since? (fn [{t :at d :day day-only? :day-only?}]
                 (if day-only?
                   (boolean (and d (>= (compare d day) 0)))
                   (boolean (and t (not (.isBefore ^java.time.Instant t ^java.time.Instant at))))))
        changed (filter #(some since? (vals (:times %))) stage-rows)
        rs (filter #(and (:at %) (since? {:at (:at %)})) run-rows)
        settled (vec (for [d dated :when (>= (compare (:on d) day) 0)]
                       (cond-> d (= (:on d) day) (assoc :same-day? true))))]
    (if-not at
      {:kind (:kind base) :first? true :stages (by-state stage-rows)}
      (cond-> {:kind (:kind base) :from (str at) :day day
               :stages (by-state changed)
               :decisions-settled settled :settled-undated undated
               :runs (count rs) :merged (count (filter #(= :merged (:status %)) rs))
               :run-money (cents (reduce + 0 (keep :cost rs)))}
        (:flag base) (assoc :flag (:flag base))
        (and (empty? changed) (empty? settled) (empty? rs)) (assoc :nothing? true)))))

(defn since-from
  "Where \"since the last report\" counts from (the person, 2026-10-26: what happened since the
  report was last generated; a look recorded or not was \"mighty confusing\"): `--since`'s date,
  that day's 00:00 in the project's zone `z`; else the last report's `:at`, from the reports log
  (`reports`); else none - the first report. `{:kind :flag|:report|:first :at <Instant> :day}`,
  `:day` the base's day in `z`, which a date-only record is counted by."
  [flag last-report z]
  (let [zid (stages/zone z)
        day-of #(str (.toLocalDate (.atZone ^java.time.Instant % zid)))]
    (if-let [inst (or (when flag (some-> (try (java.time.LocalDate/parse flag) (catch Exception _ nil))
                                         (.atStartOfDay zid) .toInstant))
                      (stages/parse-at (:at last-report)))]
      (cond-> {:kind (if flag :flag :report) :at inst :day (if flag flag (day-of inst))}
        flag (assoc :flag flag))
      {:kind :first})))

;; ---------------------------------------------------------------------------
;; the reports log: when each roadmap page was made, which the next counts from
;; ---------------------------------------------------------------------------

(defn reports-file
  "The workspace's reports log, `<work>/where/reports.edn`: one line per roadmap page made."
  [ws]
  (fs/path (:workspace/work ws) "where" "reports.edn"))

(defn reports
  "The reports log's entries, oldest first, `[{:at :plan-commit :kit-version :files}]`; a line that
  does not read is passed over. [] when there is none."
  [ws]
  (let [f (reports-file ws)]
    (if (fs/exists? f)
      (vec (keep #(when-not (str/blank? %) (try (edn/read-string %) (catch Exception _ nil)))
                 (str/split-lines (slurp (str f)))))
      [])))

(defn report!
  "Append `entry` - `{:at :plan-commit :kit-version :files}` - to the reports log: a line added,
  never one rewritten. A report is what was true at a time; its baseline is the line before."
  [ws entry]
  (let [f (reports-file ws)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str (pr-str entry) "\n") :append true)
    (str f)))

(defn instant-text
  "An instant for a person: `26 October 2026, 18:08 CDT`, in the zone `local`, and the project's
  own zone after it when `project-zone` (an IANA name) reads the clock differently - `(04:38
  IST)`. `at` is an `Instant` or its text. Nil without one."
  [at local project-zone]
  (when-let [inst (stages/parse-at (str at))]
    (let [in (fn [z] (.atZone ^java.time.Instant inst (stages/zone z)))
          here (in local)
          day (java.time.format.DateTimeFormatter/ofPattern "d MMMM yyyy, HH:mm zzz" java.util.Locale/ENGLISH)
          clock (java.time.format.DateTimeFormatter/ofPattern "HH:mm zzz" java.util.Locale/ENGLISH)
          there (when (not-empty project-zone) (in project-zone))]
      (str (.format here day)
           (when (and there (not= (.getOffset here) (.getOffset there)))
             (str " (" (.format there clock) ")"))))))

(defn since-lines
  "The section's lines under its heading, for a person - one view for the fuller account and the
  roadmap page: the base, then each changed stage's state now, the decisions settled, the runs.
  `local` the reader's zone, `project-zone` the workspace's."
  [{:keys [kind first? nothing? from flag stages decisions-settled settled-undated runs merged run-money]} local project-zone]
  (let [state-line (fn [{:keys [state ids]}] (str (str/join ", " (map #(str "stage " %) ids)) ": " state))
        base (if (= :flag kind)
               (str flag " (--since: 00:00, " (stages/zone project-zone) ")")
               (instant-text from local project-zone))]
    (cond
      first? (cons "First report - nothing to count from. Each stage now:" (map #(str "- " (state-line %)) stages))
      nothing? [(str "Nothing since " base ".")]
      :else (concat
             [(str "Since " base ":")]
             (map #(str "- " (state-line %)) stages)
             [(str "- Decisions settled: "
                   (if (seq decisions-settled)
                     (str/join ", " (map #(str (:id %) (when (:same-day? %) " (same day)")) decisions-settled))
                     "none dated")
                   (when (pos? (or settled-undated 0)) (str "; " settled-undated " settled with no date, not counted")))
              (str "- Runs: " runs ", " merged " merged, " (format "$%.2f" (double run-money)) " (runs only; a reading carries no time)")]))))

(defn- money [stage-rows]
  (let [approved (filter :approved stage-rows)
        proposed (remove :approved stage-rows)]
    {:spent (cents (reduce + 0 (keep :money stage-rows)))
     :caps-approved (reduce + 0 (keep :cap approved))
     :caps-proposed (reduce + 0 (keep :cap proposed))
     :by-stage (vec (for [s stage-rows :when (or (:money s) (:cap s))] [(:id s) (:money s) (:cap s)]))}))

;; ---------------------------------------------------------------------------
;; pure: the fuller account, `bb next --where --full`
;; ---------------------------------------------------------------------------

(defn- dollars [x] (if (and (number? x) (== x (Math/floor x)) (< (Math/abs (double x)) 1e9)) (str "$" (long x)) (format "$%.2f" (double x))))

(defn unpriced-note
  "` (n unpriced)` beside a stage's money when calls in it carried no cost, else empty."
  [{:keys [unpriced]}]
  (if (pos? (or unpriced 0)) (str " (" unpriced " unpriced)") ""))

(defn- money-cell [{:keys [money money-note] :as s}]
  (cond (nil? money) "-"
        money-note (str (format "$%.2f" (double money)) " (readings only)" (unpriced-note s))
        :else (str (format "$%.2f" (double money)) (unpriced-note s))))

(defn- usd [x] (if x (format "$%.2f" (double x)) "-"))

(defn share
  "`part` of `whole` as a whole percent, or nil."
  [part whole]
  (when (and part whole (pos? whole)) (Math/round (* 100.0 (/ part whole)))))

(defn channels-sentence
  "How each maker was reached, stage by stage, as one sentence."
  [{:keys [channels]}]
  (str "The maker is the model's. How each was reached - "
       (str/join "; " (for [[m per] channels]
                        (str m ": " (str/join ", " (for [[id cs] per] (str "stage " id " " (str/join " and " cs)))))))
       "."))

(def not-counted
  "What the money tables say they leave out."
  (str "From the run records' priced steps and spec reviews and the stage folders' readings, as the stage "
       "reports count them. Not counted: the interactive sessions - the Architect's and any other - which no record holds."))

(defn- stage-ranges
  "Stage ids as the table says them: a run of consecutive stages `S1->S4`, the rest one by one,
  `S1->S2, S4`. An id that is no number stands alone."
  [ids]
  (->> ids
       (reduce (fn [runs id]
                 (let [prev (peek (peek runs))]
                   (if (and (integer? id) (integer? prev) (= id (inc prev)))
                     (conj (pop runs) (conj (peek runs) id))
                     (conj runs [id]))))
               [])
       (map #(if (= 1 (count %)) (str "S" (first %)) (str "S" (first %) "->S" (peek %))))
       (str/join ", ")))

(defn models-by-stage
  "A role's models, summarized: each model once, the stages it ran in after it, in the order the
  models were first used - `claude-sonnet-5.5 (S0); gemini-4-argon (S1->S4); gemini-3.8-flash (S2)`
  (the person, 2026-10-26: the stage-by-stage list was hard to read). One model across every stage
  the role has is its name alone. A stage whose records name no model - a reading written before
  readings kept theirs - is `not recorded (S3)`."
  [by-stage]
  (let [uses (for [[id {:keys [models]}] by-stage
                   m (if (seq models) models ["not recorded"])]
               [m id])
        order (distinct (map first uses))
        stages (reduce (fn [acc [m id]] (update acc m (fnil conj []) id)) {} uses)]
    (if (and (= 1 (count order)) (not= "not recorded" (first order)))
      (first order)
      (str/join "; " (for [m order] (str m " (" (stage-ranges (distinct (stages m))) ")"))))))

(defn- money-by-md
  "The fuller account's two tables, `## Money by provider` and `## Money by role`, from `money-by`."
  [{:keys [makers stages totals all roles] :as mb}]
  (when mb
    (let [ids (mapv :id stages)]
      (concat
       ["" "## Money by provider" ""
        (str "| Stage | " (str/join " | " makers) " | Total |")
        (str "|---|" (str/join "" (repeat (inc (count makers)) "---|")))]
       (for [{:keys [id by-maker total]} stages]
         (str "| " id " | " (str/join " | " (map #(usd (get by-maker %)) makers)) " | " (usd total) " |"))
       [(str "| All | " (str/join " | " (for [m makers] (str (usd (get totals m)) " (" (share (get totals m) all) "%)")))
             " | " (usd all) " |")
        "" (channels-sentence mb)
        "" "## Money by role" ""
        (str "| Role | Models, by stage | " (str/join " | " (map #(str "S" %) ids)) " | Total |")
        (str "|---|---|" (str/join "" (repeat (inc (count ids)) "---|")))]
       (for [{:keys [label by-stage total]} roles]
         (str "| " label " | " (models-by-stage by-stage)
              " | " (str/join " | " (map #(usd (get-in by-stage [% :money])) ids)) " | " (usd total) " |"))
       [(str "| All | | " (str/join " | " (map #(usd (:total %)) stages)) " | " (usd all) " |")
        "" not-counted]))))

(defn- cap-cell [{:keys [cap cap-note]}]
  (cond cap (str (dollars cap) (when cap-note (str " " cap-note))) cap-note (str "(cap " cap-note ")") :else "-"))

(defn- state-cell [{:keys [state closed approved]}]
  (case state
    :closed (str "closed " closed)
    :running (str "running, approved " approved)
    :planned "planned, not approved"
    :next "next - not pulled yet"
    :ahead "ahead"))

(defn- stages-said
  "A phase's stages in a few words: each closed, running, planned or next one by itself, the
  stages ahead together."
  [rows]
  (let [{ahead true others false} (group-by #(= :ahead (:state %)) rows)]
    (str/join "; " (concat (for [{:keys [id state closed]} others]
                             (str "stage " id " " (case state :closed (str "closed " closed) :running "running"
                                                        :planned "planned" :next "next")))
                           (when (seq ahead)
                             [(str (if (next ahead) "stages " "stage ")
                                   (str/join ", " (map :id ahead)) " ahead")])))))

(defn render-full
  "The fuller account, from `where-data`'s map `d`: Where, The road, Waits on you, Since you last
  looked, Open, Next, Read more - in that order, Markdown a person reads in a terminal or a chat,
  every figure the map's. A fact the map does not have is said as such."
  [{nxt :next :keys [workspace generated phases stages headline open since money terms] :as d}]
  (let [{:keys [build kit]} workspace
        kit (or kit "KIT")
        by-id (into {} (map (juxt :id identity) stages))
        waits (:waits-on-the-person d)
        {:keys [decisions risks]} open]
    (str/join
     "\n"
     (concat
      [(str "# Where the project stands - " build)
       (str "Generated " (some-> (:at generated) (subs 0 (min 10 (count (:at generated)))))
            (when (:plan-commit generated) (str " from " build " at " (:plan-commit generated)))
            (when (:kit-version generated) (str ", KIT " (:kit-version generated))) ".")
       ""
       "## Where"
       ""
       headline
       ""]
      (for [{:keys [letter word status] ids :stages} phases
            :let [rows (keep by-id ids)]]
        (str "- " letter " " word " - " (case status :done "done" :here "**you are here**" :ahead "ahead")
             (when (seq rows) (str ": " (if (= :ahead status)
                                          (str (if (next rows) "stages " "stage ") (str/join ", " (map :id rows)))
                                          (stages-said rows))))))
      [""
       "## The road"
       ""
       "| Stage | Kind | Name | State | Cap | Spent | Days | Runs |"
       "|---|---|---|---|---|---|---|---|"]
      (for [{:keys [id kind days runs merged] nm :name :as s} stages]
        (str "| " id " | " (or (not-empty kind) "-") " | " (or (not-empty nm) "-") " | " (state-cell s) " | " (cap-cell s)
             " | " (money-cell s) " | " (or days "-") " | " (if (pos? (or runs 0)) (str runs ", " merged " merged") "-") " |"))
      [""
       (str "Spent " (format "$%.2f" (double (:spent money))) " against " (dollars (:caps-approved money)) " of caps approved"
            (when (pos? (:caps-proposed money)) (str "; " (dollars (:caps-proposed money)) " proposed")) ".")]
      (money-by-md (money-by stages))
      [""
       (str "## Waits on you (" (count waits) ")")
       ""]
      (if (seq waits)
        (map wait-line waits)
        ["Nothing: no step, gate, prep row, decision or risk is the person's now."])
      ["" "## Since the last report" ""]
      (since-lines since (java.time.ZoneId/systemDefault) (:time-zone workspace))
      [""
       "## Open"
       ""
       (str "Decisions PROVISIONAL or OPEN (" (count decisions) ") - " build "/docs/04-decision-log.md")]
      (for [{:keys [id section status subject owner]} decisions]
        (str "- **" id "** " subject " - " status " (§" section ")" (when owner (str ", owner: " owner))))
      [""
       (str "Risks OPEN (" (count risks) ") - " build "/docs/00-overview.md §4a")]
      (for [{:keys [id subject runs-in spike]} risks]
        (str "- **" id "** " subject " - runs in " (or (not-empty runs-in) "(not said)")
             (when (seq spike) (str "; " spike))))
      [""
       "## Next"
       ""
       (str "Step " (:step nxt) " - " (:name nxt) "; owner: " (:owner nxt) "; run: " (:run nxt))]
      (when (:also nxt) [(str "Also: " (:also nxt))])
      [""
       "## Read more"
       ""
       (str "- " (str/join " · " (for [{:keys [id file heading]} (:kit terms)] (str id ": " kit "/" file " \"" heading "\""))))
       (str "- the project plan: " build "/docs/" (get-in terms [:project-plan :file])
            " (§" (get-in terms [:project-plan :section]) " lists its documents)")
       (str "- the IDs, in " build "/docs/: "
            (str/join " · " (for [{:keys [id file section shown]} (:ids terms)] (str id " " (or shown file) " §" section))))]
      [(str "- the roadmap page, drawn: `bb roadmap-page`, then open " (or (:work workspace) "work") "/where/roadmap.html")]))))

;; ---------------------------------------------------------------------------
;; the edge: reading the workspace
;; ---------------------------------------------------------------------------

(defn- read-edn [path] (try (edn/read-string (slurp (str path))) (catch Exception _ nil)))

(defn- text [docs f] (let [p (fs/path docs f)] (when (fs/exists? p) (slurp (str p)))))

(defn- rel [ws k] (some->> (get ws k) fs/absolutize (fs/relativize (fs/absolutize (:workspace/dir ws))) str))

(defn- figures
  "Stage `id`'s figures: the stage report's summary (nil before a blueprint), its readings, and
  the minutes its runs took."
  [build records id]
  (let [f (stage-report/stage-facts build records id)]
    {:summary (some-> f stage-report/stage-summary)
     :readings (stage-report/stage-readings build id)
     :costs (stage-report/cost-rows build records id)
     :minutes (when f (Math/round (/ (reduce + 0 (keep :run/wall-ms (:records f))) 60000.0)))}))

(defn- runs
  "Every run record under `records`, as `{:date :at :status :cost}` - `:at` the instant it started,
  which `since-row` counts by, nil when the record's time cannot be read (the run is kept, not counted); the cost as the stage report counts it, never `:run/cost`, which one
  record format carries as a map."
  [records]
  (vec (for [r (->> (when (and records (fs/directory? records)) (fs/glob records "*.edn"))
                    (keep read-edn) (filter :run/id))
             :let [s (stage-report/run-summary r)
                   at (stages/parse-at (:run/started-at r))]]
         {:date (some-> at str (subs 0 10)) :at at :status (:status s) :cost (:cost s)})))

(defn- plain-edn
  "`m` with every `Instant` in it as its ISO-8601 text. The readers below keep times as instants to
  compare them - a stage's readings, since row 170 - and an `Instant` prints as `#object[...]`, which
  no reader takes back: the map is plain EDN at its edge, whatever a reader adds."
  [m]
  (walk/postwalk #(if (instance? java.time.Instant %) (str %) %) m))

(declare where-map)

(defn where-data
  "Where the project in workspace `ws` stands, as one map of plain data (the design's Part A):
  `:workspace` `:generated` `:phases` `:stages` `:current` `:headline` (the block's first line)
  `:next` `:open` `:waits-on-the-person`
  `:since` `:money` `:terms`. `opts`: `:at` and `:plan-commit`, the clock and the build's commit,
  passed in; `:since`, the date `--since` named. Outside a workspace: `{:workspace nil :next ..}`."
  [ws opts]
  (plain-edn (where-map ws opts)))

(defn- where-map
  "`where-data`'s map, before its times are text."
  [ws {:keys [at plan-commit since]}]
  (let [wf (next/where-facts ws)
        nxt (next/next-action wf)]
    (if-not (:workspace? wf)
      {:workspace nil :generated {:at at} :next nxt}
      (let [build (:workspace/build ws)
            docs (fs/path build "docs")
            records (:workspace/records ws)
            overview (text docs "00-overview.md")
            log (text docs "04-decision-log.md")
            smap (or (:map wf) {})
            extra (if overview (map-rows overview) {})
            files (:stages wf)
            here (current-id (:stage wf))
            ids (sort (distinct (concat (keys smap) (keys files))))
            kinds (into {} (for [[id {:keys [plan]}] files :when plan]
                             [id (some-> (fs/path docs "stages" plan) str slurp next/stage-kind)]))
            rows (vec (for [id ids]
                        (stage-row id (get smap id) (get extra id {})
                                   (assoc (get files id {}) :kind (get kinds id) :build (:build wf))
                                   (figures build records id) here (:workspace/time-zone ws))))
            open {:decisions (if log (open-decisions log) []) :risks (if overview (open-risks overview) [])}
            names (vec (distinct (for [[_ {:keys [gates]}] files
                                       g (vals (or gates {}))
                                       :when (and (map? g) (string? (:by g)) (not (next/unnamed? g)))]
                                   (:by g))))
            base (since-from since (peek (reports ws)) (:workspace/time-zone ws))
            stage (:stage wf)]
        {:workspace (cond-> {:dir "." :build (:build wf) :app (rel ws :workspace/app) :kit (:kit wf) :work (rel ws :workspace/work)}
                      (:workspace/display-name ws) (assoc :display-name (:workspace/display-name ws))
                      (:workspace/time-zone ws) (assoc :time-zone (:workspace/time-zone ws)))
         :generated {:at at :plan-commit plan-commit :kit-version (:workspace/kit-version ws)}
         :phases (phase-rows rows here)
         :stages rows
         :current (when stage (cond-> (dissoc stage :preconditions)
                                (:stage/closed (:gates stage)) (assoc :between? true)))
         :headline (first (str/split-lines (next/render-where wf nxt)))
         :next nxt
         :open open
         :waits-on-the-person (waits {:next nxt :current stage :open open :names names :kit (:kit wf) :build (:build wf)})
         :since (since-row base rows (if log (settled log) {:dated [] :undated 0}) (runs records))
         :money (money rows)
         :terms {:kit next/kit-terms :project-plan next/project-plan :ids next/id-kinds}}))))

(defn build-commit
  "The build repository's HEAD, short, or nil."
  [build]
  (try (let [{:keys [exit out]} (p/shell {:dir (str build) :out :string :err :string :continue true}
                                         "git" "rev-parse" "--short" "HEAD")]
         (when (zero? exit) (str/trim out)))
       (catch Exception _ nil)))

(defn parse-args
  "`bb next`'s command line: `{:edn? :full? :since :args}` - `:args` what `harness.setup.next` takes - or
  `{:error sentence}`: a `--since` with no date after it, one that is not a date of the calendar
  (`2026-13-99`), or one without `--edn` or `--full` to count for."
  [args]
  (let [i (.indexOf ^java.util.List (vec args) "--since")
        d (when (>= i 0) (get (vec args) (inc i)))
        date? (fn [s] (try (java.time.LocalDate/parse s) true (catch Exception _ false)))
        edn? (boolean (some #{"--edn"} args))
        full? (boolean (some #{"--full"} args))
        kept (loop [[a & more] args kept []]
               (cond (nil? a) kept
                     (#{"--edn" "--full"} a) (recur more kept)
                     (= "--since" a) (recur (rest more) kept)
                     :else (recur more (conj kept a))))]
    (cond (neg? i) {:edn? edn? :full? full? :since nil :args kept}
          (not (and d (date? d))) {:error (str "--since needs a date after it, YYYY-MM-DD" (when d (str "; got " d)))}
          (not (or edn? full?)) {:error "--since needs --edn or --full: the block does not count since a date"}
          :else {:edn? edn? :full? full? :since d :args kept})))

(defn -main
  "bb next [--where [--edn | --full] [--since <YYYY-MM-DD>]] [--workspace <dir>]

  With `--where --edn`, where the project stands as one EDN map (`where-data`); with `--where
  --full`, the same as the fuller account a person reads (`render-full`). \"Since the last
  report\" counts from the last roadmap page made (the reports log, which only `bb roadmap-page`
  adds to), or `--since`'s date, its 00:00 in the project's zone. Every other form is
  `harness.setup.next`'s. Information, and nothing written: exit 0, but 1 for a `--since`
  `parse-args` refuses."
  [& args]
  (let [{:keys [edn? full? since error] rest-args :args} (parse-args args)
        data #(let [ws (workspace/current-or-exit (remove #{"--where"} rest-args))]
                (where-data ws {:at (str (java.time.Instant/now))
                                :plan-commit (some-> ws :workspace/build build-commit)
                                :since since}))]
    (cond
      error (do (println error) (System/exit 1))
      edn? (binding [*print-namespace-maps* false] (pprint/pprint (data)))
      full? (let [d (data)] (println (if (:workspace d) (render-full d) (next/render (:next d)))))
      :else (apply next/-main rest-args))))
