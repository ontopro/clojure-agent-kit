(ns harness.models.security-bake-off
  "Models compared as security reviewers: `bb security-bake-off <fixture-dir> --model \"<model> [effort]\" ...`.

  A candidate is a `--model` line, reached on OpenRouter by its family's route, or a `--profile`: the
  profile's first eligible security reviewer, as `bb security-review --profile` takes it - the way to a
  direct route (Gemini at Google's API), which no `--model` line reaches.

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
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.models.catalogue :as catalogue]
   [harness.models.probe :as probe]
   [harness.models.security-review :as review]
   [harness.models.security-score :as score]))

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn reading-key
  "What a candidate's readings are named by: its id, and its route after `_` - the same model on two
  routes is one id and two readings' worth of files. A candidate with no route is its id alone. An id
  carrying `_` is refused: the folder's reader splits a file name at the first one, and a route never
  has one (`openrouter`, `<x>-direct`, a host)."
  [{:keys [id route model]}]
  (when (str/includes? (str id) "_")
    (throw (ex-info (str "the candidate " id " (model " model ") has a `_` in its id, which separates the id from the route in a reading's files - rename the model in its profile")
                    {:bake-off/error :underscore-in-id :id id})))
  (if route (str id "_" route) id))

(defn jobs
  "The readings: each candidate on the faulted branch and on the careful one. A job is
  `{:candidate :case :id}` with `:case` `:faulted` or `:clean`."
  [candidates]
  (vec (for [c candidates, k [:faulted :clean]]
         {:candidate c :case k :id (str (reading-key c) "-" (name k))})))

(defn token-usage
  "The tokens of a review, summed over its completions' steps: uncached input, input read from the
  cache, input written to it, output, and reasoning where the host reports it - nil, not 0, when no
  step reports it (row 129)."
  [steps]
  (let [sum (fn [k] (reduce + 0 (keep #(get-in % [:usage k]) steps)))]
    {:in (sum :in) :cache-read (sum :cache-read) :cache-write (sum :cache-write) :out (sum :out)
     :reasoning (when-let [rs (seq (keep :reasoning-tokens steps))] (reduce + 0 rs))}))

(defn reading-tokens
  "A review's tokens, all of them: uncached input, cache read, cache write and output, summed over its
  steps - the report's \"Tokens: all\"; nil for a review with no steps, not 0."
  [review]
  (when (seq (:steps review))
    (let [u (token-usage (:steps review))] (+ (:in u) (:cache-read u) (:cache-write u) (:out u)))))

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
     :route (:route candidate)
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
     :tokens (let [t (keep reading-tokens [rf rc])] (when (seq t) (reduce + 0 t)))
     :tokens-known? (every? some? (map reading-tokens [rf rc]))
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
  (str "| Candidate | Route | Planted faults found | Claims: shown by its tests / not reproduced / for you to mark | Completions | Minutes | Cost | Tokens | On the careful branch: tests that fail |\n"
       "|---|---|---|---|---|---|---|---|---|\n"
       (str/join "\n"
                 (for [{:keys [candidate route found planted hits not-reproduced to-mark completions minutes cost cost-known? tokens tokens-known? control-failing]} rows]
                   (str "| " candidate " | " (or route "—") " | " found " of " planted " | " hits " / " not-reproduced " / " to-mark
                        " | " (or completions "—") " | " (if minutes (format "%.1f" (double minutes)) "—")
                        " | " (if cost-known? (format "$%.2f" (double cost)) "not yet reported")
                        " | " (cond (nil? tokens) "—"
                                    tokens-known? (format "%,d" (long tokens))
                                    :else (format "%,d so far, one reading none" (long tokens)))
                        " | " control-failing " |")))
       "\n"
       (str/join ""
                 (for [{:keys [candidate route refused? answered? capped? missed]} rows
                       note [(when refused? "the model refused the review")
                             (when (and (not refused?) (not answered?)) "wrote no answer; scored on its tests alone")
                             (when capped? "stopped at its round limit")
                             (when (seq missed) (str "missed " (str/join ", " missed)))]
                       :when note]
                   (str "\n- " candidate (when route (str " (" route ")")) ": " note)))
       "\n"))

(defn distinct-ids
  "The candidates, refused when two share an id AND a route: the two name a reading's files
  (`reading-key`), so the two would read and score into the same ones. One model on two routes is two
  candidates."
  [cands]
  (let [dups (keep (fn [[k n]] (when (> n 1) k)) (frequencies (map reading-key cands)))]
    (when (seq dups)
      (throw (ex-info (str "two candidates share the id and the route " (str/join ", " dups) " - a reading's files are named by them")
                      {:bake-off/error :duplicate-id :ids (vec dups)})))
    cands))

(defn- read-edn [f] (when (fs/exists? f) (edn/read-string (slurp (str f)))))

;; ---------------------------------------------------------------------------
;; the report: time, cost, tokens, behaviour
;; ---------------------------------------------------------------------------

(defn clock
  "Milliseconds as `m:ss` (or `h:mm:ss`); `—` when not recorded."
  [ms]
  (if (nil? ms)
    "—"
    (let [s (quot (long ms) 1000) h (quot s 3600) m (quot (rem s 3600) 60) sec (rem s 60)]
      (if (pos? h) (format "%d:%02d:%02d" h m sec) (format "%d:%02d" m sec)))))

(defn grouped
  "A count with thousands separators."
  [n]
  (if (nil? n) "—" (format "%,d" (long n))))

(defn dollars [x] (if (nil? x) "not reported" (format "$%.2f" (double x))))

(defn reading-metrics
  "Everything the report says of one reading, from its review record and its score. Its start is the
  record's `:started` (before its clone), its end the score's `:scored` - or, for a reading scored again
  in a later run (`:scored-again?`), the record's `:ended`, its review done, so the span never crosses the
  gap between the runs. A reading recorded before they were, its `file-times` - read off the files by
  `read-folder` - and `:started-from-files?` and `:ended-from-files?` say which, each of its own."
  [{:keys [id candidate route case review score file-times]}]
  (let [calls (:calls review)
        by-tool (frequencies (map first calls))
        writes (for [t (:turns review) c (:calls t) :when (= "write_test" (:tool c))] (get-in c [:args :path]))
        n (or (:iterations review) 0)
        u (token-usage (:steps review))
        found (count (:found score))
        planted (+ found (count (:missed score)))]
    {:id id :candidate candidate :route route :case case
     :started (or (:started review) (:started file-times))
     :ended (if (:scored-again? score)
              (or (:ended review) (:reviewed file-times))
              (or (:scored score) (:ended file-times)))
     :started-from-files? (boolean (and file-times (not (:started review))))
     :ended-from-files? (boolean (and file-times (not (if (:scored-again? score) (:ended review) (:scored score)))))
     :review-ms (:ms review) :sandbox-ms (:sandbox-ms review) :score-ms (:ms score)
     :work-ms (when (:ms review) (+ (:ms review) (or (:sandbox-ms review) 0) (or (:ms score) 0)))
     :usage u :tokens (reading-tokens review)
     :cost (:cost review)
     :completions n :capped? (boolean (:capped? review)) :refused? (boolean (:refused? review))
     :answered? (boolean (and (some? (:findings review)) (not (:refused? review))))
     :calls (count calls) :by-tool by-tool
     :calls-per-completion (when (pos? n) (/ (count calls) (double n)))
     :call-errors (count (filter #(nth % 2 nil) calls))
     :tests-written (count (distinct writes)) :test-writes (count writes)
     :claims (count (:findings review))
     :found found :planted planted :counts (:counts score)
     :careful-failing (count (filter #(= :fails (:clean %)) (vals (:tests score))))}))

(defn- instant [s] (java.time.Instant/parse s))

(defn local-time
  "An instant's time of day in `zone`, `HH:mm:ss`; nil for none."
  [zone s]
  (when s (.format (.atZone (instant s) zone) (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss"))))

(defn- span-ms [started ended] (when (and started ended) (.toMillis (java.time.Duration/between (instant started) (instant ended)))))

(defn- sum-by [k ms] (reduce + 0 (keep k ms)))

(defn- sum-or-nil
  "The sum, or nil when no reading has the figure: a total of nothing is not zero."
  [k ms]
  (when (some some? (map k ms)) (sum-by k ms)))

(defn- all-known? [k ms] (every? #(some? (k %)) ms))

(defn model-totals
  "One candidate's readings added up."
  [ms]
  (let [cost-known? (all-known? :cost ms)
        found (sum-by :found (filter #(= :faulted (:case %)) ms))
        faulted (filter #(= :faulted (:case %)) ms)
        ;; the first reading in and the last one out, each cell marked by its own reading
        first-in (first (sort-by (comp instant :started) (filter :started ms)))
        last-out (last (sort-by (comp instant :ended) (filter :ended ms)))]
    {:candidate (:candidate (first ms)) :route (:route (first ms))
     :review-ms (sum-or-nil :review-ms ms) :sandbox-ms (sum-or-nil :sandbox-ms ms) :score-ms (sum-or-nil :score-ms ms)
     :work-ms (sum-or-nil :work-ms ms)
     :in (sum-by #(get-in % [:usage :in]) ms) :cache-read (sum-by #(get-in % [:usage :cache-read]) ms)
     :cache-write (sum-by #(get-in % [:usage :cache-write]) ms) :out (sum-by #(get-in % [:usage :out]) ms)
     :reasoning (sum-or-nil #(get-in % [:usage :reasoning]) ms)
     :tokens (sum-by :tokens ms)
     :cost (sum-by :cost ms) :cost-known? cost-known?
     :completions (sum-by :completions ms) :calls (sum-by :calls ms)
     :found found :planted (sum-by :planted faulted)
     :faulted-work-ms (sum-by :work-ms faulted)
     :started (:started first-in) :started-from-files? (boolean (:started-from-files? first-in))
     :ended (:ended last-out) :ended-from-files? (boolean (:ended-from-files? last-out))}))

(defn- per-fault [x found] (when (and x (pos? found)) (/ x (double found))))

(defn- table [head rows]
  (str "| " (str/join " | " head) " |\n|" (str/join "|" (repeat (count head) "---")) "|\n"
       (str/join "\n" (for [r rows] (str "| " (str/join " | " r) " |"))) "\n"))

(defn invocation-models
  "What an invocation of `run.edn` says ran: each candidate as `<id> (<route>)` from its `:candidates`,
  or - an invocation from before they were recorded - its `:models`."
  [{:keys [candidates models]}]
  (if (seq candidates)
    (mapv (fn [{:keys [id route]}] (if route (str id " (" route ")") id)) candidates)
    models))

(defn report
  "The report, as Markdown, in tables: a header, one table by model (the metrics as rows, a column
  per candidate), one by reading, a line for the whole run, and what the report lacks. `run` is
  `{:invocations [{:started :finished :elapsed-ms :kit-commit :models :candidates :rounds :parallel :fixture}
  ...]}` (empty for a run made before they were recorded) and `readings` the folder's readings as
  `reading-metrics` takes them. Every figure is from a record; a figure no record carries is `—`
  or `not reported`, never an estimate. `zone` is the times' (the machine's when not given)."
  ([title run readings] (report title run readings (java.time.ZoneId/systemDefault)))
  ([title run readings zone]
   (let [ms (mapv reading-metrics readings)
         ckey (juxt :candidate :route)
         by-cand (group-by ckey ms)
         totals (mapv model-totals (vals by-cand))
         tot (into {} (map (juxt ckey identity)) totals)
         cands (vec (sort (keys tot)))
         inv (:invocations run)
         elapsed (when (seq inv) (sum-by :elapsed-ms inv))
         work (sum-by :work-ms ms)
         all-cost-known? (all-known? :cost ms)
         of (fn [c k] (first (filter #(and (= c (ckey %)) (= k (:case %))) ms)))
         pair (fn [c f] (str (f (of c :faulted)) " / " (f (of c :clean))))
         ended (fn [m] (cond (nil? m) "—" (:refused? m) "refused" (:capped? m) "round limit" (:answered? m) "answered" :else "no answer"))
         bold (fn [s] (str "**" s "**"))
         cost-cell (fn [t] (if (:cost-known? t) (dollars (:cost t)) (str (dollars (:cost t)) " so far, some not reported")))
         row (fn [label f] (into [label] (map f) cands))
         per (fn [c f] (let [m (of c :faulted)] (if-let [x (per-fault (f m) (:found (tot c)))] x nil)))
         at (fn [m k] (if-let [t (local-time zone (k m))] (str (when (get m (keyword (str (name k) "-from-files?"))) "≈") t) "—"))]
     (str "# " title "\n\n"
          "- **Run:** " (if (seq inv)
                          (str (str/join "; " (for [i inv] (str (:started i) " → " (:finished i)))) "; KIT " (str/join ", " (distinct (keep :kit-commit inv))))
                          "made before runs were recorded")
          (when (seq inv)
            (str "\n- **Models:** " (str/join ", " (distinct (mapcat invocation-models inv))) " · tracer · rounds " (str/join "/" (distinct (keep :rounds inv)))
                 " · " (str/join "/" (distinct (keep :parallel inv))) " readings at a time"))
          "\n\n## By model\n\n"
          "Totals over each model's faulted and careful readings; the per-fault rows use the faulted reading.\n\n"
          (table (into [""] (map first) cands)
                 [(row "Route" #(or (second %) "—"))
                  (row "Started" #(at (tot %) :started))
                  (row "Ended" #(at (tot %) :ended))
                  (row "**Time: start to end**" #(bold (clock (span-ms (:started (tot %)) (:ended (tot %))))))
                  (row "**Planted faults found**" #(bold (str (:found (tot %)) " of " (:planted (tot %)))))
                  (row "Claims: shown by its tests / not reproduced / to mark"
                       #(let [c (:counts (of % :faulted))] (str (get c :hit 0) " / " (get c :not-reproduced 0) " / " (+ (get c :fails-on-both 0) (get c :unattributed 0)))))
                  (row "Tests failing on the careful branch" #(:careful-failing (of % :clean)))
                  (row "**Time: review**" #(clock (:review-ms (tot %))))
                  (row "**Time: sandbox start**" #(clock (:sandbox-ms (tot %))))
                  (row "**Time: scoring**" #(clock (:score-ms (tot %))))
                  (row "**Time: total work**" #(bold (clock (:work-ms (tot %)))))
                  (row "**Cost**" #(bold (cost-cell (tot %))))
                  (row "**Tokens: all**" #(bold (grouped (:tokens (tot %)))))
                  (row "Tokens: uncached input" #(grouped (:in (tot %))))
                  (row "Tokens: cache read" #(grouped (:cache-read (tot %))))
                  (row "Tokens: cache write" #(grouped (:cache-write (tot %))))
                  (row "Tokens: output (reasoning included)" #(grouped (:out (tot %))))
                  (row "Tokens: reasoning" #(if-let [x (:reasoning (tot %))] (grouped x) "not reported"))
                  (row "Reasoning tokens per completion (faulted / careful)"
                       #(pair % (fn [m] (if-let [x (get-in m [:usage :reasoning])]
                                          (if (pos? (or (:completions m) 0)) (grouped (Math/round (/ x (double (:completions m))))) "—")
                                          "not reported"))))
                  (row "Completions (faulted / careful)" #(pair % :completions))
                  (row "Tool calls per completion (faulted / careful)" #(pair % (fn [m] (if-let [x (:calls-per-completion m)] (format "%.1f" x) "—"))))
                  (row "Tests written (file writes), faulted" #(let [m (of % :faulted)] (str (:tests-written m) " (" (:test-writes m) ")")))
                  (row "How it ended (faulted / careful)" #(pair % ended))
                  (row "Cost per fault found" #(if-let [x (per % :cost)] (dollars x) "—"))
                  (row "Work per fault found" #(if-let [x (per % :work-ms)] (clock x) "—"))
                  (row "Tokens per fault found" #(if-let [x (per % :tokens)] (grouped (Math/round (double x))) "—"))])
          "\n## By reading\n\n"
          (table ["Reading" "Started" "Ended" "Start to end" "Sandbox start" "Review" "Scoring" "Cost" "Tokens" "Completions" "How it ended"]
                 (for [m (sort-by (juxt :candidate :route :case) ms)]
                   [(str (:candidate m) (when (:route m) (str " (" (:route m) ")")) ", " (name (:case m)))
                    (at m :started) (at m :ended) (clock (span-ms (:started m) (:ended m))) (clock (:sandbox-ms m)) (clock (:review-ms m)) (clock (:score-ms m))
                    (dollars (:cost m)) (grouped (:tokens m)) (:completions m) (ended m)]))
          "\n**Whole run: " (if all-cost-known? (dollars (sum-by :cost ms)) (str (dollars (sum-by :cost ms)) " so far, some costs not reported (`bb reprice` fetches them)"))
          ", " (grouped (sum-by :tokens ms)) " tokens, " (clock work) " of work"
          (when elapsed (str ", " (clock elapsed) " wall-clock"
                             (when (pos? work) (format " (%.1f minutes of work per minute of waiting)" (/ (/ work 60000.0) (max 0.01 (/ elapsed 60000.0)))))))
          ".**\n\n"
          "Notes: a dash is a figure no record carries. Started and ended are local time (" zone "): a reading starts before its clone and ends when its score is written - one scored again in a later run when its review ended, its scoring left out - a model's span from its first reading's start to its last one's end; ≈ is a time read from the files' modification times, for a reading recorded before readings carried their own. \"Total work\" adds a reading's sandbox start, review and scoring; readings run in parallel, so wall-clock is shorter. "
          "Tokens: all = uncached input + cache read + cache write + output; reasoning tokens are part of output, and \"not reported\" where no completion's host reported them; reasoning per completion, over a reading's completions, is where a level run lower than asked shows. A reading with no tests has no scoring time. "
          "A reading reused from an earlier run keeps the time and cost it was recorded with; wall-clock covers only this run's invocations. "
          "A cost a host has not yet reported is left out of the totals.\n"))))

(defn- read-folder
  "The folder's readings: each `<id>.edn` that has a `<id>-score.edn`, with the candidate, its route
  and the branch read from the id (`<candidate>_<route>-faulted` or `-clean`; a folder from before the
  route has `<candidate>-faulted`, its route nil), and the files' times: the record's modification time
  less its review and sandbox start, the record's own, the score's - the start, the review's end and the
  end of a reading recorded before readings carried their own."
  [out]
  (vec (for [f (sort (map str (fs/glob out "*.edn")))
             :let [nm (str (fs/file-name f))
                   id (str/replace nm #"\.edn$" "")]
             :when (and (not (str/ends-with? id "-score")) (not= id "run")
                        (fs/exists? (fs/path out (str id "-score.edn"))))
             :let [[_ cand route kind] (re-matches #"([^_]+)(?:_(.+))?-(faulted|clean)" id)]
             :when cand]
         (let [review (read-edn f)
               sco (fs/path out (str id "-score.edn"))
               mtime (fn [p] (.toInstant (fs/last-modified-time p)))]
           {:id id :candidate cand :route route :case (keyword kind) :review review :score (read-edn sco)
            :file-times {:started (str (.minusMillis (mtime f) (+ (or (:ms review) 0) (or (:sandbox-ms review) 0))))
                         :reviewed (str (mtime f))
                         :ended (str (mtime sco))}}))))

(defn report!
  "Write `report.md` in `out` from the run's `run.edn` and its readings. Returns the path."
  [out]
  (let [run (or (read-edn (fs/path out "run.edn")) {:invocations []})
        file (str (fs/path out "report.md"))]
    (spit file (report (str "Security bake-off report - " (fs/file-name out)) run (read-folder out)))
    file))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn- score-reading!
  "Score the review in `rec`, write `sco`, and say how the reading came out. A review with no test
  of its own is scored as nothing found, without starting a container. `again?` - a record from an
  earlier run - is said in the score (`:scored-again?`), so the report ends the reading at its review."
  [{:keys [kit fixture]} cases id rec sco r again?]
  (let [s (if (seq (:tests r))
            (score/score! kit (str rec) (str fixture))
            (assoc (score/summary {} (:findings r) (:faults cases)) :review (str (fs/file-name rec))))
        s (cond-> (assoc s :scored (str (java.time.Instant/now))) again? (assoc :scored-again? true))]
    (spit (str sco) (with-out-str (pp/pprint s)))
    (println (format "  %s: %s, %d completions, %ds, %s, %s; %d of %d planted faults found"
                     id (cond (:refused? r) "REFUSED" (:no-block? r) "no answer" :else "answered")
                     (or (:iterations r) 0) (quot (or (:ms r) 0) 1000)
                     (if-let [c (:cost r)] (format "$%.2f" (double c)) "cost not yet reported")
                     (if-let [t (reading-tokens r)] (format "%,d tokens" (long t)) "no tokens reported")
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
        {:review r :score (score-reading! opts cases id rec sco r true)})

      :else
      (let [started (str (java.time.Instant/now))
            scratch (str (fs/create-temp-dir {:prefix "kit-bakeoff"}))]
        (try
          (let [clone (review/review-clone! (fs/path (:fixture opts) "notes") (get cases case) (fs/path scratch "app"))
                r (assoc (review/review! (:profile candidate) :tracer clone (cond-> {:kit kit} rounds (assoc :rounds rounds)))
                         :started started :ended (str (java.time.Instant/now)))]
            (review/write-record! out id r)
            {:review (read-edn rec) :score (score-reading! opts cases id rec sco r false)})
          (finally (fs/delete-tree scratch)))))))

(defn- kit-commit [kit]
  (let [r (p/shell {:dir kit :out :string :err :string :continue true} "git" "rev-parse" "--short" "HEAD")]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn- record-invocation!
  "Append this invocation - when it started and finished, the models, the settings, the KIT commit - to
  the folder's `run.edn`, which the report reads its elapsed time from. A run that resumes adds a
  second entry; the report adds them up."
  [out invocation]
  (let [f (fs/path out "run.edn")
        old (or (read-edn f) {:invocations []})]
    (spit (str f) (with-out-str (pp/pprint (update old :invocations conj invocation))))))

(defn run-readings!
  "Run every reading, `parallel` at a time, and write `bake-off.md` and `report.md` under `out`.
  Returns the rows."
  [{:keys [fixture out models profiles parallel kit rounds] :as opts}]
  (let [started (java.time.Instant/now)
        ;; THE COMMIT THE RUN RUNS ON, read as it starts: the code is loaded now, and a commit made
        ;; while the readings run is not the code that ran them (register row 238)
        commit (kit-commit kit)
        t0 (System/currentTimeMillis)
        cases (read-edn (fs/path fixture "cases.edn"))
        cands (distinct-ids
               (into (if (seq models)
                       (let [listing (catalogue/fetch-listing) routes (catalogue/routes)]
                         (mapv #(catalogue/expand-candidate listing routes %) models))
                       [])
                     (map review/role-from-profile)
                     profiles))
        _ (fs/create-dirs out)
        ;; THE PRE-RUN PROBE, every candidate, before the first paid reading: a candidate the host will
        ;; not serve as asked stops the run here, for cents, not after minutes of a reading
        p (probe/probe! cands {:out out :max-tokens review/default-max-tokens})
        _ (when (:stop? p)
            (throw (ex-info (str "the pre-run probe stopped the run - fix the line or the profile and run again: " (:file p))
                            {:probe/stop true :file (:file p)})))
        results (->> (jobs cands)
                     (partition-all (max 1 (or parallel 2)))
                     (mapcat (fn [batch] (mapv deref (mapv #(future [% (reading! opts cases %)]) batch))))
                     vec)
        by (fn [cand k] (some (fn [[j r]] (when (and (= (reading-key cand) (reading-key (:candidate j))) (= k (:case j))) r)) results))
        rows (mapv (fn [c] (row c (by c :faulted) (by c :clean))) cands)]
    (spit (str (fs/path out "bake-off.md")) (render rows))
    (record-invocation! out {:started (str started) :finished (str (java.time.Instant/now))
                             :elapsed-ms (- (System/currentTimeMillis) t0)
                             :kit-commit commit :models (mapv :model cands)
                             :candidates (mapv #(select-keys % [:id :route :model :member]) cands)
                             :rounds (or rounds review/default-rounds) :parallel (or parallel 2)
                             :fixture (str fixture)})
    (report! out)
    rows))

(def usage
  (str "bb security-bake-off <fixture-dir> --model \"<model> [effort]\" ... --profile <profile.edn> ...\n"
       "                         [--out <dir>] [--rounds N] [--parallel N]\n"
       "bb security-bake-off --report <dir>   write report.md again from a folder's records (no model calls)\n"
       "  <fixture-dir>  what bb security-fixture built (notes/ and cases.edn)\n"
       "  --model    a model on OpenRouter, by its family's route; --profile  a profile's first eligible security reviewer (a direct route)\n"
       "  Each model reviews the faulted branch and the careful one as the tracer, and each reading is scored.\n"
       "  REAL MODEL CALLS: it spends money. A stopped run resumes from the folder's records."))

(defn parse-args [args]
  (loop [[a & more] args m {:models [] :profiles []}]
    (cond (nil? a) m
          (= a "--model") (recur (rest more) (update m :models conj (first more)))
          (= a "--profile") (recur (rest more) (update m :profiles conj (first more)))
          (= a "--out") (recur (rest more) (assoc m :out (first more)))
          (= a "--rounds") (recur (rest more) (assoc m :rounds (parse-long (first more))))
          (= a "--parallel") (recur (rest more) (assoc m :parallel (parse-long (first more))))
          (= a "--report") (recur (rest more) (assoc m :report (first more)))
          (str/starts-with? a "--") (throw (ex-info (str "unknown argument " a "\n" usage) {}))
          :else (recur more (assoc m :fixture a)))))

(defn -main [& args]
  (let [{:keys [fixture models profiles out rounds parallel report]} (parse-args args)
        kit (str (fs/normalize (fs/absolutize "..")))]
    (when report
      (println (str "  report: " (report! (str (fs/absolutize report)))))
      (System/exit 0))
    (when-not (and fixture (or (seq models) (seq profiles)))
      (println usage)
      (System/exit 2))
    (let [out (str (fs/absolutize (or out (fs/path kit ".local" "security-review"
                                                   (str "bake-off-" (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))))))
          fixture (str (fs/absolutize fixture))]
      (println (str "  " (+ (count models) (count profiles)) " models x faulted and careful, " (or parallel 2) " at a time, into " out))
      (let [rows (try (run-readings! {:kit kit :fixture fixture :out out :models models :profiles (mapv #(str (fs/absolutize %)) profiles) :rounds rounds :parallel parallel})
                      (catch clojure.lang.ExceptionInfo e
                        (if (:probe/stop (ex-data e))
                          (do (println (str "  " (ex-message e))) (System/exit 1))
                          (throw e))))]
        (println)
        (println (render rows))
        (println (str "  table: " (fs/path out "bake-off.md")))
        (println (str "  report: " (fs/path out "report.md")))))))
