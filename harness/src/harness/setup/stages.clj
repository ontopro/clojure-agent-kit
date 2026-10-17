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
