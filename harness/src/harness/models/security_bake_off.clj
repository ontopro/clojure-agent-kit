(ns harness.models.security-bake-off
  "Models compared as security reviewers: `bb security-bake-off <fixture-dir> --model \"<model> [effort]\" ...`.

  Each model reviews the fixture twice, as the architecture tracer: once on the faulted branch,
  where there is something to find, and once on the careful one, where there is not. Every
  reading is scored by running the tests it wrote (`harness.models.security-score`), so the
  table is arithmetic: how many of the six planted faults each model found, what it claimed
  that its own tests did not show, what it left for a person to mark, and how many of its tests
  failed on the CAREFUL branch - the inventions. Nobody's opinion decides a row and no model
  judges: a model's answer is a list of claims and the tests are the evidence.

  It is not the plan-reading bake-off (`harness.models.bake-off`), which compares one-completion
  readings under a judge: a review is minutes of tool calls in a container, and its scoring is
  mechanical. It shares the candidate line (`harness.models.catalogue/expand-candidate`) and
  nothing else.

  A STOPPED RUN RESUMES: a reading whose record and score are both in the folder is not run
  again. Delete its files to read it again.

  PURE UP TO THE EDGE: `jobs`, `row` and `render` are functions of data; `run-readings!` runs them."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.models.catalogue :as catalogue]
   [harness.models.security-review :as review]
   [harness.models.security-score :as score]))

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn jobs
  "The readings: each candidate on the faulted branch and on the careful one. A job is
  `{:candidate :case :id}` with `:case` `:faulted` or `:clean`."
  [candidates]
  (vec (for [c candidates, k [:faulted :clean]]
         {:candidate c :case k :id (str (:id c) "-" (name k))})))

(defn row
  "One candidate's row from its two readings: `faulted` and `control`, each
  `{:review _ :score _}` (the record and its score)."
  [candidate faulted control]
  (let [{rf :review sf :score} faulted
        {rc :review sc :score} control
        counts (:counts sf)
        found (count (:found sf))
        planted (+ found (count (:missed sf)))]
    {:candidate (:id candidate)
     :model (or (:model rf) (:model candidate))
     :found found
     :planted planted
     :missed (mapv name (:missed sf))
     :claimed (get counts :findings 0)
     :hits (get counts :hit 0)
     :not-reproduced (get counts :not-reproduced 0)
     :to-mark (+ (get counts :fails-on-both 0) (get counts :unattributed 0))
     :hypotheses (get counts :hypothesis 0)
     :completions (:iterations rf)
     :minutes (some-> (:ms rf) (/ 60000.0))
     :cost (+ (or (:cost rf) 0) (or (:cost rc) 0))
     :cost-known? (and (some? (:cost rf)) (some? (:cost rc)))
     :refused? (boolean (or (:refused? rf) (:refused? rc)))
     :answered? (boolean (and (some? (:findings rf)) (not (:refused? rf))))
     :capped? (boolean (:capped? rf))
     :control-failing (count (filter #(= :fails (:clean %)) (vals (:tests sc))))
     :control-claimed (count (:findings rc))}))

(defn render
  "The table, as Markdown: one row per candidate, and a line under it for anything that qualifies a
  row - a refusal, a review that never answered, one stopped at its round limit."
  [rows]
  (str "| Candidate | Planted faults found | Claims: shown by its tests / not reproduced / for you to mark | Completions | Minutes | Cost | On the careful branch: tests that fail |\n"
       "|---|---|---|---|---|---|---|\n"
       (str/join "\n"
                 (for [{:keys [candidate found planted hits not-reproduced to-mark completions minutes cost cost-known? control-failing]} rows]
                   (str "| " candidate " | " found " of " planted " | " hits " / " not-reproduced " / " to-mark
                        " | " (or completions "—") " | " (if minutes (format "%.1f" (double minutes)) "—")
                        " | " (if cost-known? (format "$%.2f" (double cost)) "not yet reported")
                        " | " control-failing " |")))
       "\n"
       (str/join ""
                 (for [{:keys [candidate refused? answered? capped? missed]} rows
                       note [(when refused? "the model refused the review")
                             (when (and (not refused?) (not answered?)) "wrote no answer; scored on its tests alone")
                             (when capped? "stopped at its round limit")
                             (when (seq missed) (str "missed " (str/join ", " missed)))]
                       :when note]
                   (str "\n- " candidate ": " note)))
       "\n"))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn- read-edn [f] (when (fs/exists? f) (edn/read-string (slurp (str f)))))

(defn- score-reading!
  "Score the review in `rec`, write `sco`, and say how the reading came out. A review with no test
  of its own is scored as nothing found, without starting a container."
  [{:keys [kit fixture]} cases id rec sco r]
  (let [s (if (seq (:tests r))
            (score/score! kit (str rec) (str fixture))
            (assoc (score/summary {} (:findings r) (:faults cases)) :review (str (fs/file-name rec))))]
    (spit (str sco) (with-out-str (pp/pprint s)))
    (println (format "  %s: %s, %d completions, %ds; %d of %d planted faults found"
                     id (cond (:refused? r) "REFUSED" (:no-block? r) "no answer" :else "answered")
                     (or (:iterations r) 0) (quot (or (:ms r) 0) 1000)
                     (count (:found s)) (+ (count (:found s)) (count (:missed s)))))
    s))

(defn- reading!
  "One reading. Both of its files there: reused. Its record there and no score (a stopped run, or
  a scorer that changed): scored again, with no new model call. Neither: clone the branch, review
  it, write the record, score it. Returns `{:review _ :score _}`."
  [{:keys [kit out rounds] :as opts} cases {:keys [candidate case id]}]
  (let [rec (fs/path out (str id ".edn"))
        sco (fs/path out (str id "-score.edn"))]
    (cond
      (and (fs/exists? rec) (fs/exists? sco))
      (do (println (str "  " id ": reused from the last run"))
          {:review (read-edn rec) :score (read-edn sco)})

      (fs/exists? rec)
      (let [r (read-edn rec)]
        (println (str "  " id ": its review is there; scoring it again"))
        {:review r :score (score-reading! opts cases id rec sco r)})

      :else
      (let [scratch (str (fs/create-temp-dir {:prefix "kit-bakeoff"}))]
        (try
          (let [clone (review/review-clone! (fs/path (:fixture opts) "notes") (get cases case) (fs/path scratch "app"))
                r (review/review! (:profile candidate) :tracer clone (cond-> {:kit kit} rounds (assoc :rounds rounds)))]
            (review/write-record! out id r)
            {:review (read-edn rec) :score (score-reading! opts cases id rec sco r)})
          (finally (fs/delete-tree scratch)))))))

(defn run-readings!
  "Run every reading, `parallel` at a time, and write `bake-off.md` under `out`. Returns the rows."
  [{:keys [fixture out models parallel] :as opts}]
  (let [cases (read-edn (fs/path fixture "cases.edn"))
        listing (catalogue/fetch-listing)
        routes (catalogue/routes)
        cands (mapv #(catalogue/expand-candidate listing routes %) models)
        _ (fs/create-dirs out)
        results (->> (jobs cands)
                     (partition-all (max 1 (or parallel 2)))
                     (mapcat (fn [batch] (mapv deref (mapv #(future [% (reading! opts cases %)]) batch))))
                     vec)
        by (fn [cand k] (some (fn [[j r]] (when (and (= (:id cand) (:id (:candidate j))) (= k (:case j))) r)) results))
        rows (mapv (fn [c] (row c (by c :faulted) (by c :clean))) cands)]
    (spit (str (fs/path out "bake-off.md")) (render rows))
    rows))

(def usage
  (str "bb security-bake-off <fixture-dir> --model \"<model> [effort]\" --model \"<model> [effort]\" ...\n"
       "                         [--out <dir>] [--rounds N] [--parallel N]\n"
       "  <fixture-dir>  what bb security-fixture built (notes/ and cases.edn)\n"
       "  Each model reviews the faulted branch and the careful one as the tracer, and each reading is scored.\n"
       "  REAL MODEL CALLS: it spends money. A stopped run resumes from the folder's records."))

(defn parse-args [args]
  (loop [[a & more] args m {:models []}]
    (cond (nil? a) m
          (= a "--model") (recur (rest more) (update m :models conj (first more)))
          (= a "--out") (recur (rest more) (assoc m :out (first more)))
          (= a "--rounds") (recur (rest more) (assoc m :rounds (parse-long (first more))))
          (= a "--parallel") (recur (rest more) (assoc m :parallel (parse-long (first more))))
          (str/starts-with? a "--") (throw (ex-info (str "unknown argument " a "\n" usage) {}))
          :else (recur more (assoc m :fixture a)))))

(defn -main [& args]
  (let [{:keys [fixture models out rounds parallel]} (parse-args args)
        kit (str (fs/normalize (fs/absolutize "..")))]
    (when-not (and fixture (seq models))
      (println usage)
      (System/exit 2))
    (let [out (str (fs/absolutize (or out (fs/path kit ".local" "security-review"
                                                   (str "bake-off-" (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))))))
          fixture (str (fs/absolutize fixture))]
      (println (str "  " (count models) " models x faulted and careful, " (or parallel 2) " at a time, into " out))
      (let [rows (run-readings! {:kit kit :fixture fixture :out out :models models :rounds rounds :parallel parallel})]
        (println)
        (println (render rows))
        (println (str "  table: " (fs/path out "bake-off.md")))))))
