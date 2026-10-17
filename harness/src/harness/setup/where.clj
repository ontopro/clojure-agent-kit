(ns harness.setup.where
  "Where the project stands, as one map: `bb next --where --edn`.

  `bb next --where` prints the block that opens the Architect's reports - terse, a step's
  boundary. A person back after a week needs more: the whole road with a mark where they are,
  what each stage has cost and taken, what waits on them, and what changed since they last
  looked. `where-data` reads all of it from the build's files, through the readers that already
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
   [harness.money.stage-report :as stage-report]
   [harness.setup.next :as next]
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

(defn- gate-date [g] (when (map? g) (some-> (:date g) str)))

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

(defn stage-row
  "One stage of the road, from its stage map row `row`, its extra cells `extra`, its files
  `files` (`next/stage-files`), its figures `figures` (`{:summary :readings :minutes}`), and the
  stage the person is at, `here`. Paths are the workspace's: `files`' `:build` before `docs/`."
  [id row extra files figures here]
  (let [{:keys [gates plan blueprint build]} files
        approved (gate-date (:stage/approved gates))
        closed (gate-date (:stage/closed gates))
        c (if-let [gc (:cap (:stage/approved gates))] (cap gc) (cap (:cap-cell extra)))
        {:keys [summary readings minutes]} figures]
    (merge {:id id :kind (or (not-empty (:kind row)) (:kind files)) :name (:name row) :delivers (:delivers row)
            :map-status (:status row) :state (state id files here)
            :plan (some->> plan (str build "/docs/stages/")) :blueprint (some->> blueprint (str build "/docs/stages/"))
            :approved approved :signed (gate-date (:blueprint/signed gates)) :closed closed
            :days (days approved closed)
            :risks (:risks extra []) :decisions (:decisions extra [])}
           (select-keys c [:cap :cap-note])
           (if summary
             {:runs (:run-count summary) :merged (:merged summary) :rounds (:rounds summary)
              :money (cents (:money summary)) :run-minutes minutes}
             (cond-> {:runs 0 :merged 0 :rounds 0 :run-minutes 0
                      :money (when (seq readings) (cents (reduce + 0 (map second readings))))}
               (seq readings) (assoc :money-note "readings only, no blueprint yet"))))))

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

(defn waits
  "What waits on the person, in order: the next step when it is theirs - it names the first
  gate not passed, so the gates are said there and nowhere else; the blueprint's §0 rows still
  open that the next step does not name; the open decisions whose owner names them; the open
  risks whose spike does. Each `{:what :why :where :run}`."
  [{nxt :next :keys [current open names kit build]}]
  (let [prep-open (filter #(= :open (next/precondition-state (:status %))) (:preconditions current))]
    (vec (concat
          (when (= next/person (:owner nxt))
            [{:what (:name nxt) :why "the next step is the person's"
              :where (str (or kit "KIT") "/workflow.md step " (:step nxt)) :run (:run nxt)}])
          (for [{:keys [id prep]} prep-open
                :when (not (str/includes? (str (:run nxt)) id))]
            {:what (str id ": " prep) :why "the blueprint's human prep, not done"
             :where (str build "/docs/stages/" (:blueprint current) " §0") :run nil})
          (for [{:keys [id subject owner section]} (:decisions open)
                :when (and owner (names-the-person? names owner))]
            {:what (str id ", " subject) :why (str "an open decision whose owner is the person: " owner)
             :where (str build "/docs/04-decision-log.md §" section) :run nil})
          (for [{:keys [id subject spike]} (:risks open)
                :when (names-the-person? names spike)]
            {:what (str id ", " subject) :why (str "an open risk the person retires: " spike)
             :where (str build "/docs/00-overview.md §4a") :run nil})))))

(defn since-row
  "What changed from the date `from`: the stages closed, approved and signed on or after it, the
  decisions settled with a date on or after it and those settled with none, and the runs started
  on or after it - `runs` as `[{:date :status :cost}]`. `:run-money` is the runs' alone: a
  reading carries no date to count it by, so it is less than the stages' `:money` for the same days,
  and a view says \"runs only\" beside it."
  [from why stage-rows {:keys [dated undated]} run-rows]
  (let [on-or-after #(and % from (>= (compare % from) 0))
        ids-where (fn [k] (vec (keep #(when (on-or-after (k %)) (:id %)) stage-rows)))
        rs (filter #(on-or-after (:date %)) run-rows)]
    {:from from :why why
     :stages-closed (ids-where :closed) :approved (ids-where :approved) :signed (ids-where :signed)
     :decisions-settled (vec (filter #(on-or-after (:on %)) dated)) :settled-undated undated
     :runs (count rs) :merged (count (filter #(= :merged (:status %)) rs))
     :run-money (cents (reduce + 0 (keep :cost rs)))}))

(defn since-from
  "The date \"since you last looked\" counts from, and why: the `--since` date; else the last look
  recorded (`work/where/last-look.edn`, `{:at :by}`); else the approval date of the last stage
  closed - its close date would show nothing of a stage approved and closed on one day; else nil."
  [flag last-look stage-rows]
  (let [last-closed (last (filter #(= :closed (:state %)) stage-rows))]
    (cond flag [flag "--since"]
          (:at last-look) [(subs (str (:at last-look)) 0 10) (str "your last look" (some->> (:by last-look) (str ", by ")))]
          (:approved last-closed) [(:approved last-closed) (str "stage " (:id last-closed) " approved")]
          :else [nil "no stage closed and no last look recorded"])))

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

(defn- money-cell [{:keys [money money-note]}]
  (cond (nil? money) "-" money-note (str (format "$%.2f" (double money)) " (readings only)") :else (format "$%.2f" (double money))))

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
            (when (pos? (:caps-proposed money)) (str "; " (dollars (:caps-proposed money)) " proposed")) ".")
       ""
       (str "## Waits on you (" (count waits) ")")
       ""]
      (if (seq waits)
        (for [{:keys [what why where run]} waits]
          (str "- **" what "** - " why (when run (str ". Run: " run)) " (" where ")"))
        ["Nothing: no step, gate, prep row, decision or risk is the person's now."])
      [""
       (str "## Since you last looked" (if (:from since) (str ": " (:from since) " (" (:why since) ")") ""))
       ""]
      (if-not (:from since)
        [(str "No date to count from: " (:why since) ".")]
        (let [ids (fn [xs] (if (seq xs) (str/join ", " (map #(str "stage " %) xs)) "none"))
              settled (:decisions-settled since)]
          [(str "- closed: " (ids (:stages-closed since)) " · approved: " (ids (:approved since))
                " · blueprint signed: " (ids (:signed since)))
           (str "- decisions settled: " (if (seq settled) (str/join ", " (map :id settled)) "none dated")
                (when (pos? (:settled-undated since)) (str "; " (:settled-undated since) " settled with no date, not counted")))
           (str "- runs: " (:runs since) ", " (:merged since) " merged, " (format "$%.2f" (double (:run-money since)))
                " (runs only; a reading carries no date)")]))
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
     :minutes (when f (Math/round (/ (reduce + 0 (keep :run/wall-ms (:records f))) 60000.0)))}))

(defn- runs
  "Every run record under `records`, as `{:date :status :cost}` - the cost as the stage report
  counts it, never `:run/cost`, which one record format carries as a map."
  [records]
  (vec (for [r (->> (when (and records (fs/directory? records)) (fs/glob records "*.edn"))
                    (keep read-edn) (filter :run/id))
             :let [s (stage-report/run-summary r)
                   at (:run/started-at r)]]
         {:date (when at (subs (str (.toInstant ^java.util.Date at)) 0 10)) :status (:status s) :cost (:cost s)})))

(defn where-data
  "Where the project in workspace `ws` stands, as one map of plain data (the design's Part A):
  `:workspace` `:generated` `:phases` `:stages` `:current` `:headline` (the block's first line)
  `:next` `:open` `:waits-on-the-person`
  `:since` `:money` `:terms`. `opts`: `:at` and `:plan-commit`, the clock and the build's commit,
  passed in; `:since`, the date `--since` named. Outside a workspace: `{:workspace nil :next ..}`."
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
                                   (figures build records id) here)))
            open {:decisions (if log (open-decisions log) []) :risks (if overview (open-risks overview) [])}
            names (vec (distinct (for [[_ {:keys [gates]}] files
                                       g (vals (or gates {}))
                                       :when (and (map? g) (string? (:by g)) (not (next/unnamed? g)))]
                                   (:by g))))
            [from why] (since-from since (read-edn (fs/path (:workspace/work ws) "where" "last-look.edn")) rows)
            stage (:stage wf)]
        {:workspace {:dir "." :build (:build wf) :app (rel ws :workspace/app) :kit (:kit wf) :work (rel ws :workspace/work)}
         :generated {:at at :plan-commit plan-commit :kit-version (:workspace/kit-version ws)}
         :phases (phase-rows rows here)
         :stages rows
         :current (when stage (cond-> (dissoc stage :preconditions)
                                (:stage/closed (:gates stage)) (assoc :between? true)))
         :headline (first (str/split-lines (next/render-where wf nxt)))
         :next nxt
         :open open
         :waits-on-the-person (waits {:next nxt :current stage :open open :names names :kit (:kit wf) :build (:build wf)})
         :since (since-row from why rows (if log (settled log) {:dated [] :undated 0}) (runs records))
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

(defn last-look!
  "Record the person's look at where the project stands - `{:at at :by by}`, `:by` left out when
  nil - in `<work>/where/last-look.edn`, which `since-from` reads: \"since you last looked\" then
  counts from it. Returns the file's path."
  [ws at by]
  (let [f (fs/path (:workspace/work ws) "where" "last-look.edn")]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str ";; The person's last look at where the project stands, written by `bb last-look`; `bb next\n"
                       ";; --where --full` and the roadmap page count \"since you last looked\" from it. Not committed.\n"
                       (pr-str (cond-> {:at at} (not (str/blank? by)) (assoc :by by))) "\n"))
    (str f)))

(defn last-look-main
  "bb last-look [--by <name>] [--workspace <dir>]

  Records that the person has looked at where the project stands, now - the `kit-where-am-i` skill
  runs it on their word. The one file it writes is `work/where/last-look.edn`."
  [& args]
  (let [i (.indexOf ^java.util.List (vec args) "--by")
        by (when (>= i 0) (get (vec args) (inc i)))
        rest-args (if (>= i 0) (concat (take i args) (drop (+ i 2) args)) args)]
    (when (and (>= i 0) (str/blank? by))
      (println "--by needs a name after it")
      (System/exit 1))
    (let [ws (workspace/current-or-exit rest-args)]
      (when-not ws
        (println "last-look: no workspace here - run it from the workspace folder")
        (System/exit 1))
      (let [at (str (java.time.Instant/now))
            f (last-look! ws at by)]
        (println (str "recorded " (fs/relativize (fs/absolutize (:workspace/dir ws)) (fs/absolutize f)) ": " at
                      (when by (str ", by " by)) " - since you last looked now counts from " (subs at 0 10)))))))

(defn -main
  "bb next [--where [--edn | --full] [--since <YYYY-MM-DD>]] [--workspace <dir>]

  With `--where --edn`, where the project stands as one EDN map (`where-data`); with `--where
  --full`, the same as the fuller account a person reads (`render-full`); `--since` names
  the date \"since you last looked\" counts from. Every other form is `harness.setup.next`'s.
  Information, and nothing written: exit 0, but 1 for a `--since` `parse-args` refuses."
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
