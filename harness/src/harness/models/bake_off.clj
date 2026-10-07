(ns harness.models.bake-off
  "Candidates for a role, read against the same artifact, compared: `bb bake-off`.

  A bake-off is data - a role, candidates, a judge - and the mechanism is the
  same for every role. The person types three lines:

    {:role :blueprint-reviewer
     :candidates [\"anthropic/claude-opus-5.5 high\" \"openai/gpt-5.6-sol high\" \"grok medium\"]
     :judge \"openai/gpt-6-astra high\"}

  and the tool expands each candidate through the catalogue (`bb models`) and
  the routes into a full role block, runs every case with each candidate in
  the role - ONE pass each, as the build makes its decisions - and then a
  JUDGE, never a candidate, reads the candidates' answers BLIND (as A, B, C in
  a shuffled order) and maps consensus and disagreement: one row per distinct
  finding, which letters raised it, a note where they differ, and its own
  opinion labelled as its own. What every reader found is probably real; what
  one found alone is where the information is. Real or not is the PERSON's
  mark in `marks.edn`, and the per-dollar figures render from the marks, never
  from the judge. The generated spec, every record and the table live under
  `<plan>/bake-offs/<id>/`, and `bake-off-check` holds the table to the
  records as `report-check` holds the run tables.

  THE ONE PLACE THAT KNOWS THE ROLE is `acts`: how a case is found and how it
  is run. The reading acts reuse the review commands' `read!` with the
  candidate's role block in the seat; nothing else branches on the role.

  The pure parts: `parse-candidate`, `expand-candidate`, `expand`,
  `judge-input`, `parse-rows`, `summary`, `render`. `run-bake-off!`, `judge!`,
  `table!` and `check!` are the commands."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.contract.spec-review :as spec-review]
   [harness.loop.driver :as driver]
   [harness.models.agent :as agent]
   [harness.models.catalogue :as catalogue]
   [harness.money.reprice :as reprice]
   [harness.setup.blueprint-review :as blueprint-review]
   [harness.setup.plan :as plan]
   [harness.setup.plan-review :as plan-review]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; the acts a bake-off can compare candidates on
;; ---------------------------------------------------------------------------

(def acts
  "Act → the profile role it fills, how its cases are found in a plan, and how
  one case is run with a role block in the seat. The dispatched roles are not
  here yet: their case is a whole loop run, and that runner comes with the
  third step of the tool."
  {:plan-reviewer
   {:role :plan-reviewer
    ;; stage 0's reading of the whole set, then one case per stage plan on
    ;; disk, read first with the set as its context - the readings the pass
    ;; makes in a build, in the order it makes them
    :cases (fn [{:keys [plan]}]
             (let [d (fs/path plan "docs" "stages")]
               (into [{:id "plan" :path plan}]
                     (for [f (when (fs/exists? d) (sort (map str (fs/glob d "stage-*.md"))))
                           :when (not (or (str/ends-with? f "-template.md") (str/ends-with? f "-blueprint.md")))]
                       {:id (plan-review/stage-key f) :path plan :stage f}))))
    :run (fn [role {:keys [path stage]} {:keys [method]}] (plan-review/read! role path method stage))}
   :blueprint-reviewer
   {:role :blueprint-reviewer
    :cases (fn [{:keys [plan]}]
             (let [d (fs/path plan "docs" "stages")]
               (vec (for [f (when (fs/exists? d) (sort (map str (fs/glob d "*-blueprint.md"))))]
                      {:id (blueprint-review/stage-key f) :path f}))))
    :run (fn [role {:keys [path]} {:keys [method]}] (blueprint-review/read! role path method))}
   :spec-reviewer
   {:role :spec-reviewer
    :cases (fn [{:keys [work]}]
             (let [runs (fs/path work "runs")]
               (vec (for [d (when (fs/exists? runs) (sort (map str (fs/list-dir runs))))
                          :when (fs/exists? (fs/path d "spec.edn"))]
                      {:id (fs/file-name d) :path d}))))
    :run (fn [role {:keys [path]} _] (spec-review/read! role path (:config (driver/context path))))}})

(defn act-of
  "The act `role` names, or a refusal by name listing the acts there are."
  [role]
  (or (get acts role)
      (throw (ex-info (str "no bake-off runs " (pr-str role) " yet; the acts are "
                           (str/join ", " (map name (keys acts)))
                           " - the dispatched roles (coder, tester, reviewer) come with the loop runner")
                      {:bake-off/error :no-such-act :role role}))))

;; ---------------------------------------------------------------------------
;; expanding the three-line spec
;; ---------------------------------------------------------------------------

(defn parse-candidate
  "`\"anthropic/claude-opus-5.5 high\"` → `{:query \"anthropic/claude-opus-5.5\" :effort \"high\"}`;
  `\"grok\"` → `{:query \"grok\" :effort nil}`. A map is taken as already parsed."
  [line]
  (if (map? line)
    line
    (let [[q e] (str/split (str/trim (str line)) #"\s+" 2)]
      {:query q :effort (some-> e str/trim not-empty)})))

(defn candidate-id
  "A short id for records and columns: the slug's last segment and the effort."
  [{:keys [model effort]}]
  (str (last (str/split model #"/")) "-" effort))

(defn expand-candidate
  "One candidate line → `{:id :query :model :family :effort :profile}` where
  `:profile` is a `RoleProfile` block built from the catalogue's newest match
  and the family's route. Refuses by name: nothing matches, no route for the
  family, an effort the route does not know. The effort defaults to the
  route's last level - the highest - when the line gives none."
  [listing routes line]
  (let [{:keys [query effort]} (parse-candidate line)
        m (catalogue/newest (catalogue/matching listing query))
        _ (when-not m
            (throw (ex-info (str "no model in the listing matches \"" query "\" - bb models <query> shows what there is")
                            {:bake-off/error :no-such-model :query query})))
        family (:family m)
        route (catalogue/route routes family)
        levels (get-in route [:effort :levels])
        effort (or effort (last levels))
        _ (when-not (some #{effort} levels)
            (throw (ex-info (str "the family " family " takes an effort of " (str/join ", " levels) ", not \"" effort "\"")
                            {:bake-off/error :no-such-effort :family family :effort effort})))
        params (cond-> {(get-in route [:effort :param]) effort
                        :provider {:only [(:provider route)] :allow_fallbacks false}}
                 (:max_tokens route) (assoc :max_tokens (:max_tokens route))
                 (nil? (:provider route)) (dissoc :provider))]
    {:query query
     :model (:id m)
     :family family
     :effort effort
     :id (candidate-id {:model (:id m) :effort effort})
     :profile (cond-> {:family family
                       :model (:id m)
                       :shape (:shape route)
                       :endpoint (:endpoint route)
                       :params params}
                (:key-env route) (assoc :key-env (:key-env route)))}))

(def default-parallel
  "How many readings run at once when the spec says nothing: a reading is one
  completion with no tools and no shared state, so the pool is bounded by the
  providers' patience, not by the harness."
  4)

(defn expand
  "The typed spec → the resolved one: the act, each candidate expanded, the
  judge expanded and held to the rule (never a candidate; a spec with no judge
  is refused - the session's model would be the default and this tool cannot
  know it, so the person names one). Two candidates at least."
  [{:keys [role candidates judge parallel] :as spec} listing routes]
  (let [_ (act-of role)
        cands (mapv #(expand-candidate listing routes %) candidates)]
    (when (< (count cands) 2)
      (throw (ex-info "a bake-off needs at least two candidates" {:bake-off/error :too-few-candidates})))
    (when-not (or (nil? parallel) (pos-int? parallel))
      (throw (ex-info (str ":parallel must be a positive integer - how many readings run at once (" default-parallel " when left out); got " (pr-str parallel))
                      {:bake-off/error :bad-parallel :parallel parallel})))
    (when (str/blank? (str judge))
      (throw (ex-info (str "name a judge (:judge \"<model> <effort>\"): the session's model would be the default "
                           "and this tool cannot know it; it must not be one of the candidates")
                      {:bake-off/error :no-judge})))
    (let [j (expand-candidate listing routes judge)]
      (when (some #(= (:model %) (:model j)) cands)
        (throw (ex-info (str "the judge " (:model j) " is a candidate; a judge is never a candidate - name another")
                        {:bake-off/error :judge-is-candidate :judge (:model j)})))
      (when (some #(= (:family %) (:family j)) cands)
        (println (str "  note: the judge " (:model j) " shares a family with a candidate; a third family is better when the field allows one")))
      (assoc spec :resolved {:act role
                             :profile-role (:role (act-of role))
                             :candidates cands
                             :judge j
                             :parallel (or parallel default-parallel)
                             :at (str (java.time.Instant/now))}))))

;; ---------------------------------------------------------------------------
;; the judge
;; ---------------------------------------------------------------------------

(def judge-system-prompt
  (str "You are the judge of a bake-off. Several readers - you will know them only as A, B, C… - "
       "reviewed the same document independently, and you are given what they were asked and what each "
       "answered. You did not review the document yourself and you must not add findings of your own.\n\n"
       "Your job is the MATCHING: the same finding said in different words by different readers is one "
       "row. Produce one row per distinct finding raised by ANY reader, saying which readers raised it. "
       "Where readers disagree - one calls something a defect and another calls it fine, or they read the "
       "same sentence differently - say so in the row's note, in one sentence. Then give your own opinion "
       "of whether the row is a real defect a person should act on, as true or false; it will be shown as "
       "yours and decides nothing.\n\n"
       "Do not rank the readers. Do not reward length. A reader that raised one real finding others "
       "missed has done more than one that raised ten restatements of the rules.\n\n"
       "End with exactly one fenced json block:\n\n"
       "```json\n"
       "{\"rows\": [{\"finding\": \"one sentence, the finding itself\", \"where\": \"where in the document\",\n"
       "           \"raised_by\": [\"A\", \"C\"], \"note\": \"one sentence where they disagree, else empty\",\n"
       "           \"real\": true}]}\n"
       "```"))

(defn judge-input
  "What the judge reads: the input the candidates were given, then each
  candidate's answer under its letter, in the shuffled order given."
  [input lettered]
  (str "=== WHAT THE READERS WERE ASKED (their input, verbatim) ===\n" input
       "\n\n" (str/join "\n\n" (for [[letter findings] lettered]
                                 (str "=== READER " letter " ANSWERED ===\n"
                                      (json/generate-string {:findings findings} {:pretty true}))))))

(defn parse-rows
  "The rows vector from the judge's last json block; nil when there is no block."
  [text]
  (when-let [block (agent/last-json-block text)]
    (when (contains? block :rows)
      (mapv (fn [row]
              (-> (select-keys row [:finding :where :raised_by :note :real])
                  (update :raised_by (fn [letters] (mapv str letters)))))
            (:rows block)))))

(defn- shuffle-with [^java.util.Random rnd v]
  (let [al (java.util.ArrayList. ^java.util.Collection v)]
    (java.util.Collections/shuffle al rnd)
    (vec al)))

(defn letters
  "Candidate ids → letters in a shuffled order (seeded so a record can say
  which order it used), as `{\"A\" id …}`."
  [ids seed]
  (let [order (shuffle-with (java.util.Random. seed) (vec ids))]
    (into (sorted-map) (map-indexed (fn [i id] [(str (char (+ 65 i))) id]) order))))

;; ---------------------------------------------------------------------------
;; records and files
;; ---------------------------------------------------------------------------

(defn- write-edn! [path m]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (with-out-str (pp/pprint m))))

(defn- read-edn [path]
  (when (fs/exists? path) (edn/read-string (slurp (str path)))))

(defn record-path [dir case-id who]
  (fs/path dir "records" (str case-id "--" who ".edn")))

(defn read-records
  "Every record under `<dir>/records/`, split into the candidates' and the
  judges', by case."
  [dir]
  (let [rd (fs/path dir "records")
        all (when (fs/exists? rd) (for [f (fs/glob rd "*.edn")] (read-edn f)))]
    {:candidates (group-by :case (remove :judge? all))
     :judges (into {} (map (juxt :case identity)) (filter :judge? all))}))

;; ---------------------------------------------------------------------------
;; running
;; ---------------------------------------------------------------------------

(defn- plan-context
  "Where the plan, its work folder and the KIT's method are, for `dir` (the
  bake-off's folder under `<plan>/bake-offs/`) or an explicit `:plan`."
  [dir opts]
  (let [plan (or (:plan opts) (str (fs/parent (fs/parent dir))))
        ws (or (:workspace opts) (workspace/find-workspace plan))]
    {:plan plan
     :work (or (:workspace/work ws) (str (fs/path (fs/parent plan) "work")))
     :method (or (:method opts) (str (fs/path (plan/kit-dir ws) "method.md")))}))

(defn in-parallel
  "`f` over `xs`, at most `n` at a time, results in `xs`' order: each batch of
  `n` runs as futures and is waited for whole before the next starts."
  [n f xs]
  (vec (mapcat (fn [batch] (mapv deref (mapv #(future (f %)) batch)))
               (partition-all (max 1 (or n 1)) xs))))

(defn- reusable
  "The record at `path` when a stopped run already wrote it and it is an
  answer - not a failed call, not an answer with no block - else nil. A run
  resumes rather than repeats; delete `records/` to read everything again."
  [path]
  (when-let [rec (read-edn path)]
    (when-not (or (:failed rec) (:no-block? rec)) rec)))

(defn- say-record [case-id who {:keys [failed no-block? count cost ms reused?]} what]
  (println (format "  %-14s %-28s %s" case-id who
                   (cond failed (str "FAILED: " failed)
                         no-block? (str "no " what " block")
                         :else (format "%d %s%s, %ds%s" count what
                                       (if cost (format ", $%.4f" (double cost)) "")
                                       (quot (or ms 0) 1000)
                                       (if reused? " (reused from the last run)" ""))))))

(defn run-candidates!
  "Every case with every candidate, one record each, `parallel` readings at a
  time; returns the records in case-then-candidate order. A record a stopped
  run already wrote is reused, not read again. A candidate whose call fails is
  recorded as failed, with the error, and the bake-off goes on - the others'
  reads are still evidence."
  [{:keys [act cases run]} resolved dir ctx parallel]
  (in-parallel
   parallel
   (fn [[c cand]]
     (let [path (record-path dir (:id c) (:id cand))
           rec (if-let [old (reusable path)]
                 (assoc old :reused? true)
                 (let [rec (try
                             (let [r (run (:profile cand) c ctx)]
                               (merge {:case (:id c) :candidate (:id cand) :model-named (:model cand)
                                       :model (:model r) :cost (:cost r) :ms (:ms r)
                                       :findings (:findings r) :count (count (:findings r))
                                       :no-block? (nil? (:findings r)) :input (:input r)
                                       :at (str (java.time.Instant/now))}
                                      ;; what a late cost is fetched by: the ids, the endpoint, the key's NAME
                                      (select-keys r [:generation-ids :endpoint :key-env])))
                             (catch clojure.lang.ExceptionInfo e
                               {:case (:id c) :candidate (:id cand) :model-named (:model cand)
                                :failed (ex-message e) :at (str (java.time.Instant/now))}))]
                   (write-edn! path (assoc rec :act act))
                   rec))]
       (say-record (:id c) (:id cand) rec "findings")
       (dissoc rec :reused?)))
   (for [c cases, cand (:candidates resolved)] [c cand])))

(declare judge-fresh!)

(defn judge!
  "One judge call per case over the candidates' records: the letters shuffled
  (the order kept in the record), the rows parsed, the record written. A
  judge's record a stopped run already wrote is reused."
  [resolved dir case-id records seed]
  (if-let [old (reusable (record-path dir case-id "judge"))]
    (do (say-record case-id "judge" (assoc old :count (count (:rows old)) :reused? true) "rows")
        old)
    (judge-fresh! resolved dir case-id records seed)))

(defn- judge-fresh!
  [resolved dir case-id records seed]
  (let [judge (:judge resolved)
        ok (remove #(or (:failed %) (:no-block? %)) records)
        lettered-ids (letters (map :candidate ok) seed)
        by-id (into {} (map (juxt :candidate identity)) ok)
        input (:input (first ok))
        text (judge-input input (for [[l id] lettered-ids] [l (:findings (by-id id))]))
        t0 (System/currentTimeMillis)
        ;; no answers to judge is not a judge's failure, and not a call worth paying for
        r (if (seq ok)
            (agent/converse! (:profile judge) judge-system-prompt text {:dir dir} {:max-iterations 1 :tools #{}})
            {:status :failed :error {:harness/error :nothing-to-judge}})
        rows (when (= :done (:status r)) (parse-rows (:text r)))
        ;; what a late cost is fetched by, as a reading keeps it: the ids, the endpoint, the key's NAME
        fetch-by (select-keys (agent/call-record r (:profile judge)) [:generation-ids :endpoint :key-env])
        rec (merge {:case case-id :judge? true :judge (:model judge) :model (some :model (reverse (:steps r)))
                    :order lettered-ids :seed seed
                    :rows (when rows (mapv (fn [row] (update row :raised_by #(mapv lettered-ids %))) rows))
                    :cost (let [cs (keep :cost (:steps r))] (when (seq cs) (reduce + cs)))
                    :ms (- (System/currentTimeMillis) t0)
                    :failed (when (= :failed (:status r)) (pr-str (select-keys (:error r) [:harness/error :status])))
                    :no-block? (and (= :done (:status r)) (nil? rows))
                    :at (str (java.time.Instant/now))}
                   fetch-by)]
    (write-edn! (record-path dir case-id "judge") rec)
    (println (format "  %-14s %-28s %s" case-id "judge"
                     (cond (:failed rec) (str "FAILED: " (:failed rec))
                           (:no-block? rec) "no rows block"
                           :else (format "%d rows%s, %ds" (count rows)
                                         (if (:cost rec) (format ", $%.4f" (double (:cost rec))) "")
                                         (quot (:ms rec) 1000)))))
    rec))

;; ---------------------------------------------------------------------------
;; the table
;; ---------------------------------------------------------------------------

(defn- money [x] (if x (format "$%.4f" (double x)) "—"))

(defn summary
  "Per candidate, over every case: findings raised, rows it raised (by the
  judge), rows it raised alone, cost, time, and real findings per dollar from
  the marks - nil until a mark exists."
  [candidates judges marks resolved]
  (vec (for [cand (:candidates resolved)
             :let [id (:id cand)
                   recs (for [[_ rs] candidates r rs :when (= id (:candidate r))] r)
                   rows (for [[case-id j] judges row (or (:rows j) [])] [case-id row])
                   raised (filter (fn [[_ row]] (some #{id} (:raised_by row))) rows)
                   alone (filter (fn [[_ row]] (= [id] (:raised_by row))) raised)
                   real (filter (fn [[case-id row]]
                                  (let [i (.indexOf ^java.util.List (vec (:rows (judges case-id))) row)]
                                    (true? (get-in marks [case-id i]))))
                                raised)
                   cost (let [cs (keep :cost recs)] (when (seq cs) (reduce + cs)))
                   marked? (some (fn [[case-id _]] (seq (get marks case-id))) rows)]]
         {:candidate id :model (:model cand)
          :findings (reduce + (map #(or (:count %) 0) recs))
          :rows (count raised) :alone (count alone)
          :real (when marked? (count real))
          :cost cost :ms (reduce + (map #(or (:ms %) 0) recs))
          :real-per-dollar (when (and marked? cost (pos? cost)) (/ (count real) cost))
          :failed (count (filter :failed recs))})))

(defn render
  "`TABLE.md` from the records, the judges' rows, the marks and the resolved spec."
  [{:keys [candidates judges]} marks resolved id]
  (let [cands (:candidates resolved)
        cols (map :id cands)]
    (str "# Bake-off `" id "` — " (name (:act resolved)) ", " (count cands) " candidates, judge " (:model (:judge resolved)) "\n\n"
         "Rendered by `bb bake-off table`; `bb bake-off-check` holds it to `records/`. One pass per candidate. "
         "`real?` is the person's mark in `marks.edn` (a row index per case → true/false); `judge` is the judge's "
         "opinion, shown and never counted. Real findings per dollar render from the marks alone.\n\n"
         "## Per candidate\n\n"
         "| candidate | model | findings | rows raised | alone | real (marked) | cost | time | real / $ |\n|---|---|---|---|---|---|---|---|---|\n"
         (str/join "\n" (for [s (summary candidates judges marks resolved)]
                          (format "| %s | `%s` | %d | %d | %d | %s | %s | %ds | %s |"
                                  (:candidate s) (:model s) (:findings s) (:rows s) (:alone s)
                                  (if (:real s) (str (:real s)) "—") (money (:cost s)) (quot (:ms s) 1000)
                                  (if (:real-per-dollar s) (format "%.1f" (double (:real-per-dollar s))) "—"))))
         "\n\nJudge: " (money (reduce + (keep :cost (vals judges)))) " over " (count judges) " case" (if (= 1 (count judges)) "" "s")
         "; the whole bake-off " (money (reduce + (concat (keep :cost (vals judges)) (for [[_ rs] candidates r rs :when (:cost r)] (:cost r))))) ".\n\n"
         (str/join "\n\n"
                   (for [[case-id j] (sort-by key judges)]
                     (str "## Case `" case-id "`\n\n"
                          "| # | finding | where | " (str/join " | " cols) " | judge | real? | note |\n"
                          "|---|---|---|" (str/join (repeat (count cols) "---|")) "---|---|---|\n"
                          (str/join "\n" (map-indexed
                                          (fn [i {:keys [finding where raised_by note real]}]
                                            (format "| %d | %s | %s | %s | %s | %s | %s |" i
                                                    (str/replace (str finding) "|" "\\|") (str/replace (str where) "|" "\\|")
                                                    (str/join " | " (for [c cols] (if (some #{c} raised_by) "✓" "")))
                                                    (case real true "real" false "not" "—")
                                                    (case (get-in marks [case-id i]) true "real" false "not" "—")
                                                    (str/replace (str note) "|" "\\|")))
                                          (or (:rows j) []))))))
         "\n")))

(defn table!
  "Re-render `TABLE.md` for the bake-off at `dir` from its records and marks."
  [dir]
  (let [resolved (:resolved (read-edn (fs/path dir "resolved.edn")))
        _ (when-not resolved (throw (ex-info (str "no resolved.edn under " dir " - not a bake-off, or not run yet") {:bake-off/error :not-run})))
        marks (or (read-edn (fs/path dir "marks.edn")) {})
        md (render (read-records dir) marks resolved (fs/file-name dir))]
    (spit (str (fs/path dir "TABLE.md")) md)
    md))

(defn run-bake-off!
  "The whole bake-off: expand the spec at `spec-path`, write `resolved.edn`
  beside it, run every case with every candidate, judge each case, render the
  table. `opts` may carry `:listing`, `:routes`, `:plan`, `:method`, `:seed`
  (the tests do); otherwise the listing is fetched and the plan is the
  folder above the spec's `bake-offs/`."
  [spec-path opts]
  (let [dir (str (fs/parent (fs/absolutize spec-path)))
        spec (edn/read-string (slurp (str spec-path)))
        listing (or (:listing opts) (catalogue/fetch-listing))
        routes (or (:routes opts) (catalogue/routes))
        expanded (expand spec listing routes)
        resolved (:resolved expanded)
        act (act-of (:role spec))
        ctx (plan-context dir opts)
        cases (or (some->> (:cases spec) (mapv (fn [c] (if (map? c) c {:id (str (fs/strip-ext (fs/file-name c))) :path (str c)}))))
                  ((:cases act) ctx))
        _ (when (empty? cases)
            (throw (ex-info (str "no case for " (name (:role spec)) " under " (:plan ctx) " - name them under :cases")
                            {:bake-off/error :no-cases})))
        id (fs/file-name dir)]
    (write-edn! (fs/path dir "resolved.edn") expanded)
    (println (str "bake-off " id ": " (name (:role spec)) ", " (count cases) " case" (if (= 1 (count cases)) "" "s")
                  " × " (count (:candidates resolved)) " candidates, judge " (:model (:judge resolved))
                  ", " (:parallel resolved) " at a time"))
    (let [parallel (:parallel resolved)
          records (run-candidates! (assoc act :act (:role spec) :cases cases) resolved dir ctx parallel)
          seed (or (:seed opts) (hash id))
          judges (in-parallel parallel
                              (fn [c] (judge! resolved dir (:id c) (filter #(= (:id c) (:case %)) records) (+ seed (hash (:id c)))))
                              cases)]
      (table! dir)
      (println (str "written: " (fs/path dir "TABLE.md") " — mark rows real or not in marks.edn, then `bb bake-off table " dir "`"))
      {:dir dir :records records :judges judges})))

;; ---------------------------------------------------------------------------
;; the check
;; ---------------------------------------------------------------------------

(defn check!
  "Every `<plan>/bake-offs/*/TABLE.md` equal to its render from the records
  and marks; returns the drifted folders. Nothing to check when there is no
  plan or no bake-off, and says so."
  [plan]
  (let [root (when plan (fs/path plan "bake-offs"))
        ;; list-dir, not glob: a glob pattern matches files, and these are folders
        dirs (when (and root (fs/exists? root))
               (filter #(fs/exists? (fs/path % "resolved.edn")) (sort (map str (fs/list-dir root)))))]
    (vec (for [d dirs
               :let [resolved (:resolved (read-edn (fs/path d "resolved.edn")))
                     marks (or (read-edn (fs/path d "marks.edn")) {})
                     want (render (read-records d) marks resolved (fs/file-name d))
                     have (when (fs/exists? (fs/path d "TABLE.md")) (slurp (str (fs/path d "TABLE.md"))))]
               :when (not= want have)]
           d))))

(defn check-main
  "bb bake-off-check [--workspace <dir>]: exit 1 naming each drifted bake-off."
  [& args]
  (let [ws (workspace/current-or-exit args)
        plan (:workspace/build ws)]
    (cond
      (nil? ws) (println "bake-off-check: not in a workspace - nothing to check")
      (not (fs/exists? (fs/path plan "bake-offs"))) (println "bake-off-check: no bake-offs under the plan - nothing to check")
      :else (let [drifted (check! plan)]
              (if (empty? drifted)
                (println "bake-off-check: every TABLE.md matches its records")
                (do (doseq [d drifted] (println (str "bake-off-check: " d "/TABLE.md has drifted from its records - run `bb bake-off table " d "`")))
                    (System/exit 1)))))))

;; ---------------------------------------------------------------------------
;; new: the spec, asked for
;; ---------------------------------------------------------------------------

(defn- money2 [x] (if x (format "$%.2f" (double x)) "—"))

(defn new!
  "Write a bake-off's spec by asking, in order: the act, the candidates (each
  resolved through the catalogue and confirmed back), the judge (held to the
  rule: never a candidate - refused and asked again), the cases (all of what
  is on disk for the act, or a choice). `ask` is a function of a prompt
  returning the answer (a terminal's `read-line`, or a test's script); `say`
  prints. `opts` may carry `:listing` and `:routes`. Writes
  `<plan>/bake-offs/<id>/bake-off.edn` and returns its path."
  [plan {:keys [ask say listing routes id] :or {say println}}]
  (let [listing (or listing (catalogue/fetch-listing))
        routes (or routes (catalogue/routes))
        act-names (vec (map name (keys acts)))
        _ (say "A bake-off: candidates for one act, read against the same artifact, compared by a judge.")
        _ (say (str "Which act? " (str/join " " (map-indexed (fn [i n] (str (inc i) ") " n)) act-names))))
        role (loop []
               (let [a (str/trim (or (ask "act> ") ""))
                     n (try (Long/parseLong a) (catch Exception _ nil))
                     chosen (cond n (get act-names (dec n)) (some #{a} act-names) a)]
                 (if chosen (keyword chosen) (do (say "  one of the numbers, or the act's name") (recur)))))
        resolve-one (fn [line]
                      (try (let [c (expand-candidate listing routes line)]
                             (say (format "  → %s at %s (%s; %s in, %s out per M tokens)"
                                          (:model c) (:effort c) (name (:family c))
                                          (money2 (:in (catalogue/newest (catalogue/matching listing (:model c)))))
                                          (money2 (:out (catalogue/newest (catalogue/matching listing (:model c)))))))
                             c)
                           (catch clojure.lang.ExceptionInfo e (say (str "  " (ex-message e))) nil)))
        _ (say "Candidates, one per line - a model's slug or a word (\"grok\"), then an effort (low, medium, high; the highest if none). Blank to finish; at least two.")
        cands (loop [acc []]
                (let [line (str/trim (or (ask "candidate> ") ""))]
                  (cond (and (str/blank? line) (>= (count acc) 2)) acc
                        (str/blank? line) (do (say "  two at least") (recur acc))
                        :else (if-let [c (resolve-one line)]
                                (if (= "y" (str/lower-case (str/trim (or (ask "  keep it? [y/n] ") "y"))))
                                  (recur (conj acc (str (:model c) " " (:effort c))))
                                  (recur acc))
                                (recur acc)))))
        cand-models (set (map #(first (str/split % #"\s+")) cands))
        _ (say "The judge - never a candidate, and another family than theirs when the field allows. The session's model is the usual default; name it, since this tool cannot know it.")
        judge (loop []
                (let [line (str/trim (or (ask "judge> ") ""))]
                  (if (str/blank? line)
                    (do (say "  a judge is needed; name one") (recur))
                    (if-let [j (resolve-one line)]
                      (if (cand-models (:model j))
                        (do (say (str "  " (:model j) " is a candidate; a judge is never a candidate - name another")) (recur))
                        (str (:model j) " " (:effort j)))
                      (recur)))))
        act (act-of role)
        ctx (plan-context (fs/path plan "bake-offs" "x") {:plan plan})
        found ((:cases act) ctx)
        _ (say (if (seq found)
                 (str "Cases on disk for " (name role) ": "
                      (str/join " " (map-indexed (fn [i c] (str (inc i) ") " (:id c))) found))
                      " - all of them [Enter], or numbers separated by spaces")
                 (str "No case on disk for " (name role) " under " plan " - name paths under :cases in the file before running")))
        chosen (when (seq found)
                 (let [a (str/trim (or (ask "cases> ") ""))]
                   (if (str/blank? a)
                     nil
                     (vec (keep #(get found (dec %)) (map #(Long/parseLong %) (re-seq #"\d+" a)))))))
        id (or id (str (name role) "-" (subs (str (java.time.LocalDate/now)) 0 10)))
        id (let [a (str/trim (or (ask (str "id [" id "]> ")) ""))] (if (str/blank? a) id a))
        dir (fs/path plan "bake-offs" id)
        spec (cond-> {:role role :candidates cands :judge judge}
               chosen (assoc :cases (mapv :path chosen)))
        path (fs/path dir "bake-off.edn")]
    (fs/create-dirs dir)
    (spit (str path) (str ";; A bake-off, as typed; `bb bake-off run` expands it (resolved.edn beside) and runs it.\n"
                          (with-out-str (pp/pprint spec))))
    (say (str "written: " path "\nrun it: bb bake-off run " path))
    (str path)))

;; ---------------------------------------------------------------------------
;; the command
;; ---------------------------------------------------------------------------

(defn reprice!
  "Fill the cost of every record under `<dir>/records/` the generation record
  had not answered when it was written - a candidate's or the judge's, each
  keeping its ids, endpoint and key variable as a reading does - through
  `reprice/reprice-reading`, rewrite what changed, and re-render `TABLE.md`,
  so real findings per dollar come from fetched costs and not from a balance
  read by hand. Returns `{:changed n :lines [...]}`; a record from before the
  ids were kept says so and stays."
  ([dir] (reprice! dir {}))
  ([dir opts]
   (let [rd (fs/path dir "records")
         results (for [f (sort (when (fs/exists? rd) (fs/glob rd "*.edn")))
                       :let [{:keys [reading changed? line]} (reprice/reprice-reading (read-edn f) opts)]]
                   (do (when changed? (write-edn! f reading))
                       {:file (fs/file-name f) :changed? changed? :line line}))
         results (vec results)]
     (when (some :changed? results) (table! dir))
     {:changed (count (filter :changed? results))
      :lines (vec (for [{:keys [file changed? line]} results :when (or changed? line)]
                    (str "  " file ": " (or line "priced"))))})))

(def usage
  "bb bake-off new [<plan-dir>] | run <bake-off.edn> | table <dir> | reprice <dir> | check
  A bake-off's folder is <plan>/bake-offs/<id>/ with bake-off.edn in it (three lines: :role, :candidates, :judge).
  `new` asks for them at a terminal; `run` expands and runs a file written by hand or by `new`;
  `reprice` fetches the costs the generation records had not answered at the run and re-renders the table.")

(defn -main [& args]
  (let [[cmd arg] (:args (workspace/split-args args))]
    (try
      (case cmd
        "new" (let [plan (or arg (:workspace/build (workspace/current-or-exit args)))]
                (when-not (System/console)
                  (println "bake-off new: no terminal to ask at - write <plan>/bake-offs/<id>/bake-off.edn by hand (:role, :candidates, :judge) and `bb bake-off run` it")
                  (System/exit 2))
                (new! (str plan) {:ask (fn [prompt] (print prompt) (flush) (read-line))}))
        "run" (if arg (run-bake-off! arg {}) (do (println usage) (System/exit 2)))
        "table" (if arg (do (table! arg) (println (str "written: " (fs/path arg "TABLE.md")))) (do (println usage) (System/exit 2)))
        "reprice" (if arg
                    (let [{:keys [changed lines]} (reprice! arg)]
                      (doseq [l lines] (println l))
                      (println (if (pos? changed)
                                 (str "bake-off reprice: " changed " record" (when (not= 1 changed) "s") " priced; " (fs/path arg "TABLE.md") " re-rendered")
                                 "bake-off reprice: nothing changed")))
                    (do (println usage) (System/exit 2)))
        "check" (apply check-main args)
        (do (println usage) (System/exit 2)))
      (catch clojure.lang.ExceptionInfo e
        (println "bake-off:" (ex-message e))
        (System/exit 1)))))
