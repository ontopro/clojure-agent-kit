;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.models.security-panel
  "A run of the security reviewer panel: `bb security-panel <clone>`, at a stage's end.

  WHY A PANEL. One security reviewer at every stage's end was slow and costly for what it found,
  and one model's blind spots were every stage's. The profile names a panel of security reviewers
  (`:security-panel`, `harness.models.profile/security-panel`), and a stage's end runs one, some or
  all of them, each as `bb security-review` runs one, into the stage's security review folder.

  WHO READS BY DEFAULT. The next member in rotation: the member after the one that read the
  latest stage read, in the panel's order, among the members eligible - a member of the Coder's
  family reads nothing while the Coder is of it, and is said, never run. The rotation keeps no file
  of its own: who read which stage is in the stage folders' review records (`:member`), so a
  second record cannot drift from them.

  THE COMBINED TABLE, AND WHAT IT IS NOT. A finding counts by its own failing test, never by
  agreement: one member's reproduced finding is a finding, and two members agreeing without a test
  is nothing. The table only puts the members' answers side by side for the session to read, keyed
  by the PLACE a finding names - the files its `where` names and the functions after each file's
  colon - so two members' findings of one place are one row, saying who reproduced it and who ran
  and did not. The key's limit is said, not hidden: one flaw named at two places is two rows, and
  two flaws in one function are one row with two titles; the reader has every title and both
  records. A finding naming no file is a row of its own. Not taken: a model judging sameness - a
  wrong merge hides a finding - or one member's test run against another's claim.

  `bb security-route` takes the table (`security-panel.edn`) as it takes one review's record: one
  triage call per row."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.loop.security-routing :as routing]
   [harness.models.profile :as profile]
   [harness.models.security-review :as review]
   [harness.money.stage-report :as sr]
   [harness.setup.stages :as stages]
   [harness.setup.workspace :as workspace]))

(def panel-file
  "The combined table's data, beside the members' records in a stage's security review folder."
  "security-panel.edn")

(def table-file "security-panel.md")

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn functions-in
  "The function names a finding's `where` gives after each file's colon, sorted:
  `src/app/web.clj: logout!, login; src/app/db.clj` -> [\"login\" \"logout!\"]."
  [where]
  (->> (str/split (str where) #";")
       (mapcat (fn [part]
                 (when-let [[_ after] (re-find #"\.(?:clj|cljc|cljs|edn|sql|html|js|css)\b\s*:\s*(.*)" part)]
                   (re-seq #"[A-Za-z*+!?<>=_][A-Za-z0-9*+!?<>=_./-]*" after))))
       distinct
       sort
       vec))

(defn row-key
  "What makes two members' reproduced findings one row: the files AND the functions their `where`
  names. A finding that names no function - or no file - is its own row (`own`, unique per
  finding): splitting never hides a finding, and merging two flaws at the same files did - an
  insecure direct object reference and an SQL injection in one file pair read as one row, the
  second's title in no table a person read."
  [finding own]
  (let [files (vec (sort (routing/named-files (:where finding))))
        fns (functions-in (:where finding))]
    (if (and (seq files) (seq fns)) [files fns] [:own own])))

(def record-member
  "The member a review record was run under (`stage-report/record-member`)."
  sr/record-member)

(defn said-nothing?
  "A review that refused or gave no findings block said nothing, and counts for nothing."
  [record]
  (boolean (or (:refused? record) (:no-block? record) (= :failed (:status record)))))

(defn combine
  "The combined table from `records` - `[{:file :record}]`, in panel order: `{:rows [...] :hypotheses
  [...]}`. A row: `:key`, `:by` (the members that reproduced it), `:not-by` (members that ran,
  answered and did not), `:titles` every finding's `[member title]`, the first member's `:finding` and its `:record`."
  [records]
  (let [answered (remove (comp said-nothing? :record) records)
        names (mapv (comp record-member :record) answered)
        reproduced (for [[i {:keys [file record]}] (map-indexed vector answered)
                         [j f] (map-indexed vector (:findings record))
                         :when (= :reproduced (:kind f))]
                     {:member (record-member record) :file file :finding f :key (row-key f [i j])})
        grouped (reduce (fn [m r] (update m (:key r) (fnil conj []) r)) (array-map) reproduced)]
    {:rows (vec (for [[k rs] grouped
                      :let [by (vec (distinct (map :member rs)))]]
                  {:key k
                   :title (:title (:finding (first rs)))
                   :by by
                   :not-by (vec (remove (set by) names))
                   ;; every finding's title, a member's two at one place included
                   :titles (mapv (fn [r] [(:member r) (:title (:finding r))]) rs)
                   :finding (:finding (first rs))
                   :record (:file (first rs))}))
     :hypotheses (vec (for [{:keys [record]} answered
                            f (:findings record)
                            :when (not= :reproduced (:kind f))]
                        {:member (record-member record) :finding f}))}))

(defn next-member
  "The member a stage's end reads with by default: in `eligible` (names, panel order), the one after
  the last of them to read the latest stage any of them read; the first when none has read one.
  `history` is `[[stage-id #{names}] ...]` in stage order. A skipped stage has no records and moves
  nothing."
  [eligible history]
  (when (seq eligible)
    (let [idx (into {} (map-indexed (fn [i n] [n i]) eligible))
          last-read (->> history
                         (keep (fn [[_ names]] (seq (keep idx names))))
                         last)]
      (if last-read
        (nth eligible (mod (inc (apply max last-read)) (count eligible)))
        (first eligible)))))

(defn cost-of [record] (:cost record))

(defn render
  "The combined table as `security-panel.md`."
  [{:keys [members left-out rows hypotheses]}]
  (let [names (mapv :name members)
        status (into {} (map (juxt :name :status) members))
        cell (fn [row n] (cond (some #{n} (:by row)) "✓"
                               (some #{n} (:not-by row)) "–"
                               (#{:refused :no-answer :failed} (status n)) (str/replace (name (status n)) "-" " ")
                               :else "not run"))
        title (fn [r] (if (> (count (:titles r)) 1)
                        (str/join " / " (for [[m t] (:titles r)] (str t " (" m ")")))
                        (:title r)))
        money (fn [{:keys [cost cost-source]}]
                (if cost (str (when (= :list-price cost-source) "~") (format "$%.2f" (double cost))) "not reported"))]
    (str "# Security reviewer panel\n\n"
         "A finding counts by its own failing test, never by agreement: a row one member reproduced is a"
         " finding. ✓ reproduced it, – ran and did not, `not run` did not read.\n\n"
         (if (seq rows)
           (str "| Finding | " (str/join " | " names) " | Test |\n|---|" (str/join "" (repeat (count names) "---|")) "---|\n"
                (str/join "\n" (for [r rows]
                                 (str "| " (title r) " | " (str/join " | " (map #(cell r %) names)) " | `"
                                      (:test (:finding r)) "` (" (:record r) ") |")))
                "\n")
           "No member reproduced a finding.\n")
         "\n| Member | Ran | Findings reproduced | Cost | Minutes |\n|---|---|---|---|---|\n"
         (str/join "\n" (for [{nm :name :keys [status] :as m} members]
                          (str "| " nm " | " (name status) " | " (or (:reproduced m) "—") " | " (money m) " | "
                               (if (:ms m) (format "%.1f" (/ (:ms m) 60000.0)) "—") " |")))
         "\n"
         (when (seq left-out)
           (str "\nLeft out: " (str/join "; " (for [{nm :name :keys [reason]} left-out] (str nm ", " reason))) ".\n"))
         (when (seq hypotheses)
           (str "\nHypotheses - claims no test showed, for the threat model, never routed:\n"
                (str/join "\n" (for [{:keys [member finding]} hypotheses] (str "- " (:title finding) " (" member ")")))
                "\n"))
         "\nThe rows are keyed by the files and functions each finding names: one flaw named at two"
         " places is two rows, two flaws in one function one row with two titles - every title and"
         " both records are here. A cost marked ~ is computed from list prices.\n")))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn- read-edn [f] (try (edn/read-string {:default tagged-literal} (slurp (str f))) (catch Exception _ nil)))

(def folder-records
  "The members' review records directly in a security review folder (`stage-report/security-review-records`)."
  sr/security-review-records)

(defn table!
  "Write the combined table of the records in `dir` - `security-panel.edn` and `.md` - in the panel's
  order, members not in it after. Returns the table."
  [dir panel left-out extra]
  (let [order (into {} (map-indexed (fn [i m] [(:name m) i]) panel))
        recs (sort-by (fn [{:keys [record file]}] [(get order (record-member record) 99) file]) (folder-records dir))
        {:keys [rows hypotheses]} (combine recs)
        members (vec (for [{:keys [file record]} recs]
                       {:name (record-member record) :model (:model record) :record file
                        :status (cond (:refused? record) :refused (:no-block? record) :no-answer
                                      (= :failed (:status record)) :failed :else :answered)
                        :cost (cost-of record) :cost-source (:cost-source record) :ms (:ms record)
                        :reproduced (count (filter #(= :reproduced (:kind %)) (:findings record)))}))
        old (read-edn (fs/path dir panel-file))
        t (merge (select-keys old [:stance :clone-tip]) extra
                 {:at (str (java.time.Instant/now)) :members members :left-out left-out
                  :rows rows :hypotheses hypotheses})]
    (spit (str (fs/path dir panel-file)) (with-out-str (pp/pprint t)))
    (spit (str (fs/path dir table-file)) (render t))
    t))

(defn history
  "Who read which stage: `[[id #{member names}] ...]` from every stage folder's security review
  folder in the build at `build`, in stage order."
  [build]
  (vec (for [[id _] (stages/folders (stages/stages-dir build))]
         [id (into #{} (map (comp record-member :record))
                   (folder-records (stages/document build id "-security-review")))])))

(defn history-lines
  "Per stage of the build at `build`: which members read it, what they reproduced and what it cost -
  `--history`, which the stage-end skill reads before it recommends who reads next. What changed since
  a member last read is git's to say (`git diff --stat <that stage's tag>..HEAD`), not this one's."
  [build]
  (vec (for [[id _] (stages/folders (stages/stages-dir build))
             :let [recs (map :record (folder-records (stages/document build id "-security-review")))
                   cost (reduce + 0 (keep :cost recs))
                   unpriced (count (remove :cost recs))
                   computed? (some #(= :list-price (:cost-source %)) recs)]]
         (if (seq recs)
           (str "  stage " id ": " (str/join ", " (sort (distinct (map record-member recs))))
                " - " (count (filter #(= :reproduced (:kind %)) (mapcat :findings recs))) " reproduced, "
                (when computed? "~") (format "$%.2f" (double cost))
                (when (pos? unpriced) (str " (" unpriced " unpriced)")))
           (str "  stage " id ": no review")))))

(defn choose
  "The members a run reads with: `--all` every eligible one, `--members` those named (a left-out or
  unknown name refused, with the reason), else the next in rotation. `{:members [...] :why str}`."
  [{:keys [eligible left-out]} {:keys [all members]} hist]
  (let [by-name (into {} (map (juxt :name identity) eligible))
        out (into {} (map (juxt :name :reason) left-out))]
    (cond
      all {:members eligible :why "all of them"}
      (seq members)
      (do (doseq [n members]
            (cond (out n) (throw (ex-info (str n " is left out of the panel: " (out n)) {:member n}))
                  (not (by-name n)) (throw (ex-info (str n " is not on the panel - it has " (str/join ", " (keys by-name)))
                                                    {:member n}))))
          {:members (mapv by-name members) :why "named"})
      :else
      (let [n (next-member (mapv :name eligible) hist)
            prev (some (fn [[id names]] (when (seq names) [id names])) (reverse hist))]
        {:members (if n [(by-name n)] [])
         :why (str "the next in rotation"
                   (when prev (str " - " (str/join ", " (sort (second prev))) " read stage " (first prev))))}))))

(defn- head-of [dir]
  (let [r (p/shell {:dir (str dir) :out :string :err :string :continue true} "git" "rev-parse" "HEAD")]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn run-panel!
  "Each of `members` reviews its own copy of `clone` in the stance `stance`, `parallel` at a time,
  each record written into `out` under the member's name; then the combined table, with this run's
  own outcomes as `:ran` (the table reads every record in the folder). Returns the table."
  [{:keys [kit clone stance base rounds parallel out panel left-out]} members]
  (fs/create-dirs out)
  (let [stamp (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
        one (fn [m]
              (let [scratch (str (fs/create-temp-dir {:prefix "kit-panel"}))
                    copy (str (fs/path scratch "app"))]
                (try
                  ;; LINKS STAY LINKS: copy-tree followed them, so a link in a project's repository became a
                  ;; regular file holding a host file's text in the member's copy, and `inside` then passed it
                  (fs/copy-tree clone copy {:nofollow-links true})
                  (println (str "  " (:name m) ": reading (" (name stance) ")"))
                  (let [r (review/review! (dissoc m :name) stance copy (cond-> {:kit kit} base (assoc :base base) rounds (assoc :rounds rounds)))
                        r (assoc r :member (:name m))
                        f (review/write-record! out (str (:name m) "-" (name stance) "-" stamp) r)]
                    (println (str "  " (:name m) ": " (cond (:refused? r) "REFUSED" (:no-block? r) "no answer" :else "answered")
                                  ", " (count (filter #(= :reproduced (:kind %)) (:findings r))) " reproduced - " f))
                    r)
                  (finally (fs/delete-tree scratch)))))
        ran (vec (mapcat (fn [batch] (mapv deref (mapv #(future (one %)) batch)))
                         (partition-all (max 1 (or parallel 2)) members)))]
    (assoc (table! out panel left-out {:stance stance :clone-tip (head-of clone)})
           :ran (mapv #(if (said-nothing? %) :said-nothing :answered) ran))))

(def usage
  (str "bb security-panel <clone> [--members <name>,<name> | --all] [--stance tracer|diff] [--base <rev>]\n"
       "                  [--out <stage-N-security-review>] [--parallel N] [--rounds N] [--profile <profile.edn>]\n"
       "bb security-panel --table <dir>   the combined table again from a folder's records (no model calls)\n"
       "bb security-panel --history       per stage, which members read it\n"
       "  With neither --members nor --all: the next member in rotation, said before anything runs.\n"
       "  A member of the Coder's family is left out and said. --parallel defaults to 2.\n"
       "  REAL MODEL CALLS: each member spends money."))

(defn parse-args [args]
  (loop [[a & more] args m {}]
    (cond (nil? a) m
          (= a "--all") (recur more (assoc m :all true))
          (= a "--history") (recur more (assoc m :history true))
          (= a "--members") (if (first more)
                              (recur (rest more) (assoc m :members (str/split (first more) #",")))
                              (throw (ex-info (str "--members needs names, comma-separated\n" usage) {})))
          (= a "--stance") (recur (rest more) (assoc m :stance (keyword (first more))))
          (#{"--base" "--out" "--profile" "--table"} a) (recur (rest more) (assoc m (keyword (subs a 2)) (first more)))
          (#{"--parallel" "--rounds"} a) (recur (rest more) (assoc m (keyword (subs a 2)) (parse-long (first more))))
          (str/starts-with? a "--") (throw (ex-info (str "unknown argument " a "\n" usage) {}))
          :else (recur more (update m :positional (fnil conj []) a)))))

(defn -main [& args]
  (try
    (let [{:keys [positional] :as opts} (parse-args (:args (workspace/split-args args)))
          ws (workspace/current)
          profile-path (or (:profile opts) (profile/project-profile))
          p (some-> profile-path profile/read-profile)
          split (when p (profile/panel-split p))
          hist (if-let [b (:workspace/build ws)] (history b) [])]
      (cond
        (:history opts)
        (doseq [l (if-let [b (:workspace/build ws)] (history-lines b) [])] (println l))

        (:table opts)
        (let [t (table! (:table opts) (profile/security-panel p) (:left-out split) {})]
          (println (str "  " (count (:rows t)) " row(s); wrote " (fs/path (:table opts) table-file))))

        (not (first positional))
        (do (println usage) (System/exit 2))

        (not p)
        (do (println "security-panel: no profile - pass --profile, or run it in a workspace") (System/exit 2))

        :else
        (let [{:keys [members why]} (choose split opts hist)
              stance (or (:stance opts) :tracer)
              kit (str (fs/normalize (fs/absolutize "..")))
              out (or (:out opts) (str (fs/path kit ".local" "security-review")))]
          (doseq [{nm :name :keys [reason]} (:left-out split)] (println (str "  left out: " nm ", " reason)))
          (when (empty? members)
            (println "security-panel: no member to run") (System/exit 1))
          (println (str "  reading with " (str/join ", " (map :name members)) " - " why))
          (let [t (run-panel! {:kit kit :clone (str (fs/absolutize (first positional))) :stance stance :base (:base opts)
                               :rounds (:rounds opts) :parallel (:parallel opts) :out out
                               :panel (profile/security-panel p) :left-out (:left-out split)}
                              members)]
            (println (str "  " (count (:rows t)) " reproduced finding row(s), " (count (:hypotheses t)) " hypothesis(es); "
                          "wrote " (fs/path out table-file)))
            (when (every? #(not= :answered %) (:ran t)) (System/exit 1))))))
    (catch clojure.lang.ExceptionInfo e
      (println "security-panel:" (ex-message e))
      (System/exit 1))))
