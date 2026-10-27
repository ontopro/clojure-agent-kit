(ns harness.setup.stages
  "Where a stage's documents live in a build: a folder per stage,
  `docs/stages/stage-N/`, holding the stage plan, its blueprint, its gates
  record, the security pack's records and the security review's folder, and
  `reviews/` - the stage plan's and the blueprint's readings. The templates
  stay at `docs/stages/`, beside the folders. THE STAGE IS THE FOLDER'S: its id
  is read from the folder name, once, here - four namespaces had each read it
  from a file name, in four ways.

  The files inside are named for their stage (`stage-1-plan.md`,
  `stage-1-gates.edn`, `stage-1-blueprint.md`, `stage-1-security*.edn`,
  `stage-1-security-review/`), so a grep for a stage still finds its files and
  what keys on a suffix still reads; `folder-names` is the one table of them. The whole plan's own reading is not a
  stage's: it stays at `reviews/plan-review.edn` in the build.

  A workspace made before this has its stage documents flat in `docs/stages/`
  and its readings under `reviews/stage-N/`; `bb upgrade` moves them
  (`harness.setup.stage-move`)."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn stages-dir [build] (fs/path build "docs" "stages"))

(defn folder-name [id] (str "stage-" id))

(defn folder-id
  "The id a stage folder's name carries - `stage-2` is 2 - or nil for any other name."
  [nm]
  (some-> (re-matches #"stage-(\d+)" (str nm)) second parse-long))

(defn stage-dir [build id] (fs/path (stages-dir build) (folder-name id)))

(defn rel
  "A stage document's path from the build, as messages and skills write it:
  `docs/stages/stage-2/stage-2-gates.edn` for `(rel 2 \"-gates.edn\")`."
  [id suffix]
  (str "docs/stages/" (folder-name id) "/" (folder-name id) suffix))

(defn document
  "The stage document `suffix` of stage `id`: `-gates.edn`, `-blueprint.md`, `-security.edn` …"
  [build id suffix]
  (fs/path (stage-dir build id) (str (folder-name id) suffix)))

(defn reviews-dir
  "Where stage `id`'s readings are written: its folder's `reviews/`."
  [build id]
  (fs/path (stage-dir build id) "reviews"))

(defn project-plan-review
  "The whole plan's reading, once a project, before stage 0 is approved: not a stage's."
  [build]
  (fs/path build "reviews" "plan-review.edn"))

(defn folders
  "The stage folders under `stages-dir`, as a sorted map of id to path; a
  template, a file, or a folder of another name is none."
  [stages-dir]
  (into (sorted-map)
        (for [d (when (fs/directory? stages-dir) (fs/list-dir stages-dir))
              :when (fs/directory? d)
              :let [id (folder-id (fs/file-name d))]
              :when id]
          [id d])))

(defn plan-file
  "The stage plan in stage folder `dir`, by its one name, `stage-N-plan.md` for every kind (the kind
  is its `**Kind:**` line): its file name, or nil when it is not written. A brief, a draft or a pack's
  output beside it is never taken for it, whatever its name sorts before."
  [dir]
  (let [nm (str (fs/file-name dir) "-plan.md")]
    (when (fs/regular-file? (fs/path dir nm)) nm)))

(defn plan-name?
  "Whether `path` carries a stage plan's one name, its folder's: `stage-N/stage-N-plan.md`."
  [path]
  (let [p (fs/absolutize path)]
    (= (str (fs/file-name p)) (str (fs/file-name (fs/parent p)) "-plan.md"))))

(def folder-names
  "THE ONE TABLE OF A STAGE FOLDER'S NAMES: what `docs/stages/stage-N/` holds at its top level, by
  role, `N` the stage's number and a name ending `/` a folder. The governing documents; the records
  the exit criteria cite; the KIT packs' own outputs, each pack in a folder of its own; the working
  documents' folder. A project adds its own names in `docs/stages/stage-names.edn`
  (`registered-names`). `build-docs/README.md`'s tree shows every one."
  [{:name "stage-N-plan.md" :role :governing :what "the stage plan"}
   {:name "stage-N-blueprint.md" :role :governing :what "the blueprint"}
   {:name "stage-N-gates.edn" :role :governing :what "the gates record"}
   {:name "reviews/" :role :record :what "the stage plan's and the blueprint's readings"}
   {:name "stage-N-security.edn" :role :record :what "the security pack's checks"}
   {:name "stage-N-security-deps.edn" :role :record :what "the security pack's dependency scan"}
   {:name "stage-N-security-secrets.edn" :role :record :what "the security pack's secrets scan"}
   {:name "stage-N-security-review/" :role :record :what "the security review's records"}
   {:name "stage-N-browser/" :role :pack :what "the browser pack's outputs a document cites, one folder per run"}
   {:name "notes/" :role :working :what "the stage's working documents"}])

(defn names-file
  "Where a project registers its own names in a stage folder: `docs/stages/stage-names.edn`."
  [build]
  (fs/path (stages-dir build) "stage-names.edn"))

(defn registered-names
  "A project's own names in a stage folder, from `names-file`: patterns as `folder-names` writes
  them (`stage-N-rehearsal.md`); [] when there is no file, or it is not a vector of strings."
  [build]
  (let [f (names-file build)
        v (when (fs/exists? f) (try (edn/read-string (slurp (str f))) (catch Exception _ nil)))]
    (if (and (vector? v) (every? string? v)) v [])))

(def ^:private run-stamp
  "What a run of a record after its first carries beside the bare name: `-yyyyMMdd-HHmmss`, and
  `-2`… for a second run in the same second (the security pack's `record-path`)."
  "-\\d{8}-\\d{6}(?:-\\d+)?")

(defn record-runs
  "The runs of stage `id`'s record `suffix` (`-security.edn`, `-security-deps.edn` …), in the
  order they were made: the first by its bare name, each later one beside it stamped - a record
  is only ever added to (plan 7.0, item 11.2)."
  [build id suffix]
  (let [dir (stage-dir build id)
        base (str (folder-name id) (str/replace suffix #"\.edn$" ""))
        re (re-pattern (str (java.util.regex.Pattern/quote base) "(" run-stamp ")?\\.edn"))]
    (->> (when (fs/directory? dir) (fs/list-dir dir))
         (keep #(when-let [[_ stamp] (re-matches re (str (fs/file-name %)))] [(or stamp "") (str %)]))
         (sort-by first)
         (mapv second))))

(defn latest-record
  "The current run of stage `id`'s record `suffix`: the last `record-runs` gives, or nil."
  [build id suffix]
  (last (record-runs build id suffix)))

(defn known-names
  "Every name stage `id`'s folder may hold at its top level, the table's and the project's, with
  its number in place of `N`: a set, a folder's name ending `/`."
  [build id]
  (into #{} (map #(str/replace % "stage-N" (folder-name id)))
        (concat (map :name folder-names) (registered-names build))))

(defn- later-run?
  "Is `nm` a later run of a record the folder's table names - `stage-2-security-20261027-120000.edn`
  beside `stage-2-security.edn`?"
  [known nm]
  (boolean (when-let [[_ base] (re-matches (re-pattern (str "(.+)" run-stamp "\\.edn")) nm)]
             (known (str base ".edn")))))

(defn strays
  "What a stage folder holds at its top level that `known-names` does not name, over every stage
  folder of the build: `stage-N/<name>` each, a folder's ending `/`, by stage then name. A dotfile
  is none - the operating system's, not the project's."
  [build]
  (vec (for [[id dir] (folders (stages-dir build))
             :let [known (known-names build id)]
             e (sort (map str (fs/list-dir dir)))
             :let [nm (str (fs/file-name e) (when (fs/directory? e) "/"))]
             :when (not (or (str/starts-with? nm ".") (known nm) (later-run? known nm)))]
         (str (folder-name id) "/" nm))))

(defn of-document
  "For a document at `<build>/docs/stages/stage-N/<file>`: `{:build :id :dir}`;
  nil for a path anywhere else."
  [path]
  (let [p (fs/absolutize path)
        dir (fs/parent p)
        id (some-> dir fs/file-name folder-id)
        stages (some-> dir fs/parent)
        docs (some-> stages fs/parent)]
    (when (and id
               (= "stages" (str (fs/file-name stages)))
               (= "docs" (str (fs/file-name docs))))
      {:build (fs/parent docs) :id id :dir dir})))

(defn day
  "A run's start as `YYYY-MM-DD` on this machine's calendar - the one the person wrote the gates
  record's dates in: an evening run in UTC-5 is the next day in UTC. An `#inst`, or a string that
  starts with the date."
  [x]
  (cond (inst? x) (str (.toLocalDate (.atZone (.toInstant ^java.util.Date x) (java.time.ZoneId/systemDefault))))
        (string? x) (subs x 0 (min 10 (count x)))
        :else nil))

;; A GATE IS PASSED AT A TIME, NOT ON A DAY. The gates record carried `:date "YYYY-MM-DD"`, and
;; two events on one day could not be ordered: a run on its stage's approval day went to the stage
;; before, and a reading could not be said to come after what asked for it. A gate now carries
;; `:at`, an ISO-8601 timestamp with its offset; a record from before keeps its `:date`, read as
;; that day's midnight. Every reader goes through these, so the two forms are read one way.

(defn zone
  "The zone a time is read in when it carries none: `z` - a `ZoneId`, or an IANA name such as the
  workspace's `:workspace/time-zone` - else this machine's. One rule for every zone choice."
  ^java.time.ZoneId [z]
  (cond (instance? java.time.ZoneId z) z
        (string? z) (try (java.time.ZoneId/of z) (catch Exception _ (java.time.ZoneId/systemDefault)))
        :else (java.time.ZoneId/systemDefault)))

(defn now-at
  "Now as an `:at`, to the minute, with the offset of `z` (`zone`'s): what a KIT command writes,
  and what `bb next` gives a person to paste."
  [z]
  (str (.truncatedTo (java.time.OffsetDateTime/now (zone z)) java.time.temporal.ChronoUnit/MINUTES)))

(defn parse-at
  "An `:at` as an `Instant`: an ISO-8601 string with its offset (`2026-10-21T09:30-05:00`, `...Z`),
  or an `#inst`. Nil for anything else - a bare date, a time with no offset, a typo."
  [x]
  (cond (instance? java.time.Instant x) x
        (instance? java.util.Date x) (.toInstant ^java.util.Date x)
        (string? x) (try (.toInstant (java.time.OffsetDateTime/parse x)) (catch Exception _ nil))
        :else nil))

(defn unreadable-at?
  "Whether gate `g` carries an `:at` that `parse-at` cannot read: said by name, never thrown."
  [g]
  (and (map? g) (contains? g :at) (nil? (parse-at (:at g)))))

(defn gate-instant
  "When gate `g` was passed, as an `Instant`: its `:at`; else its `:date` as that day's midnight in
  `z` (`zone`'s). Nil for no gate, or a time that cannot be read."
  [g z]
  (when (map? g)
    (if (contains? g :at)
      (parse-at (:at g))
      (some-> (try (java.time.LocalDate/parse (str (:date g))) (catch Exception _ nil))
              (.atStartOfDay (zone z))
              .toInstant))))

(defn gate-day
  "Gate `g`'s day, `YYYY-MM-DD`, for display: an `:at`'s in the offset it was written with (an
  `#inst`'s in `z`), else its `:date` as written. Nil for no gate."
  [g z]
  (when (map? g)
    (if (contains? g :at)
      (let [a (:at g)]
        (or (when (string? a) (try (str (.toLocalDate (java.time.OffsetDateTime/parse a))) (catch Exception _ nil)))
            (some-> (parse-at a) (.atZone (zone z)) .toLocalDate str)
            (str a)))
      (some-> (:date g) str))))

(defn stage-infos
  "Every stage of the build with a blueprint, as `{:id :tasks :approved :approved-at}`: the task
  ids its blueprint carries (`task-ids-of` reads them from the text), the day of its approval in its
  gates record, or nil, and the instant when the approval carries an `:at`."
  [build task-ids-of]
  (vec (for [[id _] (folders (stages-dir build))
             :let [bp (document build id "-blueprint.md")
                   g (document build id "-gates.edn")]
             :when (fs/exists? bp)]
         (let [approved (some-> (when (fs/exists? g) (try (edn/read-string (slurp (str g))) (catch Exception _ nil)))
                                :stage/approved)]
           {:id id
            :tasks (set (task-ids-of (slurp (str bp))))
            :approved (gate-day approved nil)
            :approved-at (when (and (map? approved) (contains? approved :at)) (parse-at (:at approved)))}))))

(defn record-stage
  "The stage a run record belongs to. Its `:task/stage` when it carries one - the spec's, written
  from the blueprint's folder. A record from before that is placed by date: of the stages whose
  blueprint names its task, the latest approved on or before the day the run started; the only one,
  when one alone names it; else nil - a task id two stages reuse, with no date to tell them
  apart, is counted in neither rather than in both."
  [record infos]
  (or (:task/stage record)
      ;; BY THE INSTANT WHEN BOTH CARRY ONE: an approval's `:at` against the run's start. A stage
      ;; whose approval has only a `:date` keeps the day comparison - same-day, the later stage
      ;; wins - so a build with both forms places a run on an approval day as it did
      (let [t (:task/id record)
            d (day (:run/started-at record))
            started (parse-at (:run/started-at record))
            naming (filter #(contains? (:tasks %) t) infos)
            before (filter #(if (and (:approved-at %) started)
                              (not (.isAfter ^java.time.Instant (:approved-at %) started))
                              (and d (:approved %) (<= (compare (:approved %) d) 0)))
                           naming)]
        (:id (or (last (sort-by :id before))
                 (when (= 1 (count naming)) (first naming)))))))

;; A STAGE AMENDED AFTER ITS PACKETS RAN is a pass of its own: the person's words as a Q row, the
;; stage plan amended and read again, re-approved with its cap, the blueprint amended and read
;; again, signed off again. The first approval and sign-off stay as they were - a gate once passed
;; is not unpassed - and each pass is an entry of `:stage/amendments`, opened by `bb stage-amend`.

(defn amendments
  "Stage gates `gates`' amendments, in the order they were opened."
  [gates]
  (vec (filter map? (:stage/amendments gates))))

(defn open-amendment
  "The stage's last amendment while it is not signed off: one pass at a time. Nil when there is
  none, or the last is signed."
  [gates]
  (let [a (last (amendments gates))]
    (when (and a (nil? (:signed a))) a)))

(defn current-signing
  "The threat model's signing that stands: the latest amendment pass's `:security/signed`, else the
  stage's own - a signing again after an amendment is added to its pass, never written over the
  first (plan 7.0, item 11.3). Nil when nothing is signed."
  [gates]
  (or (some :security/signed (rseq (amendments gates)))
      (:security/signed gates)))

(defn effective-approval
  "The approval the stage's cap stands on, the gate itself so its time comes with it: the latest
  re-approval carrying a `:cap`, else the first approval - a re-approval moves the cap, and one
  written without a `:cap` leaves the one before it."
  [gates]
  (or (some #(when (:cap %) %) (rseq (mapv :approved (amendments gates))))
      (:stage/approved gates)))

(defn effective-cap
  "The stage's cap: `effective-approval`'s. Never a sum of passes."
  [gates]
  (:cap (effective-approval gates)))

(defn gate-time
  "Gate `g`'s day and time, `YYYY-MM-DD HH:MM`, for a list where two events may share a day: an
  `:at`'s in the offset it was written with; a `:date` alone as `gate-day` has it."
  [g z]
  (let [a (when (map? g) (:at g))
        t (when (string? a) (try (java.time.OffsetDateTime/parse a) (catch Exception _ nil)))]
    (if t
      (.format t (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))
      (gate-day g z))))

(defn reading-times
  "When each reading in the readings file at `path` (`reviews/plan-review.edn`,
  `reviews/blueprint-review.edn`) was taken - its history's `:at`s and its own - as instants; empty
  for no file. What tells an amendment's readings from the first pass's."
  [path]
  (let [r (when (fs/exists? path) (try (edn/read-string (slurp (str path))) (catch Exception _ nil)))]
    (vec (distinct (keep parse-at (cons (:at r) (map :at (:reviews r))))))))

(defn stamp-instant
  "The instant a security review's file name carries, `...-yyyyMMdd-HHmmss.edn`, on this machine's
  calendar - the clock it was written with; nil for a name without one."
  [file-name]
  (when-let [[_ s] (re-find #"(\d{8}-\d{6})" (str file-name))]
    (try (.toInstant (.atZone (java.time.LocalDateTime/parse s (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
                              (java.time.ZoneId/systemDefault)))
         (catch Exception _ nil))))
