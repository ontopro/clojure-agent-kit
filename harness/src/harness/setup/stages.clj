(ns harness.setup.stages
  "Where a stage's documents live in a build: a folder per stage,
  `docs/stages/stage-N/`, holding the stage plan, its blueprint, its gates
  record, the security pack's records and the security review's folder, and
  `reviews/` - the stage plan's and the blueprint's readings. The templates
  stay at `docs/stages/`, beside the folders. THE STAGE IS THE FOLDER'S: its id
  is read from the folder name, once, here - four namespaces had each read it
  from a file name, in four ways.

  The files inside keep the names they had (`stage-1-<slug>.md`,
  `stage-1-gates.edn`, `stage-1-blueprint.md`, `stage-1-security*.edn`,
  `stage-1-security-review/`), so a grep for a stage still finds its files and
  what keys on a suffix still reads. The whole plan's own reading is not a
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
  "The stage plan in stage folder `dir`: its one `.md` that is neither the
  blueprint nor a template, by name; nil when it has none."
  [dir]
  (->> (when (fs/directory? dir) (fs/list-dir dir))
       (filter fs/regular-file?)
       (map (comp str fs/file-name))
       (filter #(and (str/ends-with? % ".md")
                     (not (str/ends-with? % "-blueprint.md"))
                     (not (str/includes? % "-template."))))
       sort
       first))

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

(defn stage-infos
  "Every stage of the build with a blueprint, as `{:id :tasks :approved}`: the task ids its
  blueprint carries (`task-ids-of` reads them from the text) and the date of its approval in its
  gates record, or nil."
  [build task-ids-of]
  (vec (for [[id _] (folders (stages-dir build))
             :let [bp (document build id "-blueprint.md")
                   g (document build id "-gates.edn")]
             :when (fs/exists? bp)]
         {:id id
          :tasks (set (task-ids-of (slurp (str bp))))
          :approved (some-> (when (fs/exists? g) (try (edn/read-string (slurp (str g))) (catch Exception _ nil)))
                            :stage/approved :date str)})))

(defn record-stage
  "The stage a run record belongs to. Its `:task/stage` when it carries one - the spec's, written
  from the blueprint's folder. A record from before that is placed by date: of the stages whose
  blueprint names its task, the latest approved on or before the day the run started; the only one,
  when one alone names it; else nil - a task id two stages reuse, with no date to tell them
  apart, is counted in neither rather than in both."
  [record infos]
  (or (:task/stage record)
      ;; same-day: two stages approved on or before the day, the later wins - the gates carry dates,
      ;; not times
      (let [t (:task/id record)
            d (day (:run/started-at record))
            naming (filter #(contains? (:tasks %) t) infos)
            before (filter #(and d (:approved %) (<= (compare (:approved %) d) 0)) naming)]
        (:id (or (last (sort-by :id before))
                 (when (= 1 (count naming)) (first naming)))))))
