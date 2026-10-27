;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.setup.stage-progress
  "A stage plan's last section, its progress: `bb stage-progress <stage-plan.md>`.

  WHY. The first project ended every stage plan with a checklist, a line per step of the plan's
  task list and exit criteria, ticked with its date and the commit or record that proves it, so a
  person sees at a glance where the stage stands. Ticked by hand, most of its lines repeat what the
  gates record and the run records already hold, and a hand tick can disagree with them - the drift
  this repository gates against everywhere else.

  SO THE LINES A RECORD PROVES ARE RENDERED FROM IT: the plan approved and the blueprint signed
  (the gates record), the blueprint reviewed (its review file), each §0 prep row (its Status in the
  blueprint), each packet in the blueprint's order (its run records), the security pack, its two
  scans and the security review (their records, or the skip in the gates record), a pre-release
  stage's threat model, the stage closed. They are written between two marker comments; the
  person's own lines below the end marker - the interaction checks, the browser pack, the owner's
  walk, the written records, the tag - are ticked by hand and kept across every re-render.

  NOT A GATE. A rendered line is ticked only by a record, so the section is never wrong, only
  behind: a merge since the last render leaves a line unticked that could be. `--check` says which
  stage plans are behind, as information; a gate would go red after every merge."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [harness.contract.blueprint :as blueprint]
   [harness.money.stage-report :as sr]
   [harness.setup.next :as next]
   [harness.setup.stages :as stages]
   [harness.setup.workspace :as workspace]))

(def heading "## Progress — checked off as the stage runs")
(def start-marker "<!-- progress: written by bb stage-progress from the stage's records; your own lines go below the end marker -->")
(def end-marker "<!-- progress: end -->")

(def hand-lines
  "What no record shows, ticked by hand below the end marker: a new section starts with them."
  ["- [ ] Interaction checks"
   "- [ ] The browser pack"
   "- [ ] The owner's walk, every note done or written down"
   "- [ ] The records: the exit criteria's evidence, the residual risks, the lessons, the decisions, the stage map"
   "- [ ] The stage's tag"])

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn- box [done?] (if done? "- [x] " "- [ ] "))

(defn- cut [s n] (let [s (str/replace (str s) #"\s+" " ")] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- day
  "`YYYY-MM-DD` of an instant (a record's `#inst`) or of a string that starts with one."
  [d]
  (cond (instance? java.util.Date d) (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") ^java.util.Date d)
        d (let [s (str d)] (subs s 0 (min 10 (count s))))))

(defn- short-sha [s] (some-> s str (subs 0 (min 7 (count (str s))))))

(defn- signed [label g extra]
  (str (box g) label (when g (str " - " (:by g) ", " (stages/gate-day g nil))) extra))

(defn prep-line
  "A §0 row's line. A `per run` row is done again before every run and never closes, so it has no
  box - a box nothing can tick left the section looking unfinished for good."
  [{:keys [id prep blocks status]}]
  (let [st (next/precondition-state status)]
    (str (if (= :per-run st) "- " (box (= :done st))) id " - " (cut prep 90) " (" (str/trim blocks) "; "
         (case st :done (cut status 60) :per-run "per run" "open") ")")))

(defn packet-line
  "A packet's line from its records - `summaries` the stage report's run summaries of its task, in
  run order - the merged one's when there is one."
  [task records]
  (let [merged (last (filter #(= :merged (:run/status %)) records))
        r (or merged (last records))
        s (when r (sr/run-summary r))]
    (str (box merged) task
         (cond
           merged (str " - merged, run `" (:run/id merged) "` " (day (:run/started-at merged))
                       ;; the run's whole money, its spec readings with it, as `bb report` gives it
                       (format ", $%.2f%s, %d round%s" (double (+ (:cost s) (:spec-review-cost s)))
                               ;; a call with no cost leaves the money short, said beside it
                               (let [n (+ (or (:unpriced s) 0) (count (remove :cost (:spec-readings s))))]
                                 (if (pos? n) (str " (" n " unpriced)") ""))
                               (:rounds s) (if (= 1 (:rounds s)) "" "s"))
                       (when-let [c (short-sha (:run/app-commit merged))] (str ", app `" c "`"))
                       (when (> (count records) 1) (str "; " (count records) " runs")))
           r (str " - " (name (:run/status r)) ", run `" (:run/id r) "`")
           :else " - not run"))))

(defn- scan-line [label rec]
  (let [{:keys [ok warn fail]} (:counts rec)]
    (str (box (and rec (zero? (or fail 0)))) label
         (if rec (str ": " ok " ok, " warn " warn, " fail " fail (" (stages/run-time (:security/at rec)) ")") ": no record"))))

(defn- said-nothing? [r] (boolean (or (:refused? r) (:no-block? r) (= :failed (:status r)))))

(declare review-line*)

(defn- before-amendment
  "` - before the amendment Q-n ...` when the last of `times` (instants) comes before `amended`
  (`{:q :signed}`, the last amendment's sign-off as an instant): the stage's code changed after the
  review read it, or after the skip was decided over less."
  [times amended]
  (let [t (last (sort (remove nil? times)))]
    (when (and t (:signed amended) (.isBefore ^java.time.Instant t (:signed amended)))
      (str " - before the amendment " (:q amended) ": the panel reads again, or the skip is decided again"))))

(defn review-line
  "The security review's line: who read (from the folder's records), what they reproduced, or the
  skip. Ticked only when a reviewer answered: a review declined or left without an answer is not a
  clean one, and is said as that. `amended` (`{:q :signed}`) marks a review or a skip from before the
  stage's last amendment was signed off."
  ([recs skipped] (review-line recs skipped nil))
  ([recs skipped amended]
   (str (review-line* recs skipped)
        (if (seq recs)
          (before-amendment (map ::at recs) amended)
          (when skipped (before-amendment [(stages/gate-instant skipped nil)] amended))))))

(defn- review-line*
  [recs skipped]
  (cond
    (seq recs) (let [{quiet true answered false} (group-by said-nothing? recs)
                     names #(str/join ", " (sort (distinct (map sr/record-member %))))]
                 (str (box (seq answered)) "Security review: "
                      (if (seq answered)
                        (str (names answered) " - " (count (filter #(= :reproduced (:kind %)) (mapcat :findings answered))) " reproduced")
                        "no reviewer answered")
                      (when (seq quiet) (str "; " (names quiet) " refused or left no answer"))))
    skipped (str (box true) "Security review: skipped - " (:by skipped) ", " (stages/gate-day skipped nil) ": " (:reason skipped))
    :else (str (box false) "Security review")))

(defn- amendment-lines
  "A stage amendment's lines: opened, its plan read again, approved again with its cap, its blueprint
  read again, signed off again - each read off the gates record and the readings' times."
  [{:keys [q opened approved signed]} plan-readings blueprint-readings]
  (let [since? (fn [readings t] (boolean (and t (some #(not (.isBefore ^java.time.Instant % t)) readings))))
        o (stages/parse-at opened)]
    ["" (str "**Amendment " q "** (opened " (stages/gate-time {:at opened} nil) ")") ""
     (str (box (since? plan-readings o)) "Stage plan read again")
     (str (box approved) "Approved again"
          (when approved (str " - " (:by approved) ", " (stages/gate-time approved nil)))
          (when-let [c (:cap approved)] (str " (cap " c ")")))
     (str (box (since? blueprint-readings (stages/gate-instant approved nil))) "Blueprint read again")
     (str (box signed) "Signed off again" (when signed (str " - " (:by signed) ", " (stages/gate-time signed nil))))]))

(defn render
  "The rendered part of the section, between the markers, from `facts`: `{:kind :gates :reviewed?
  :preconditions :packets :records :checks :scans :review-records :plan-readings
  :blueprint-readings}`."
  [{:keys [kind gates reviewed? preconditions packets records checks scans review-records plan-readings blueprint-readings]}]
  (let [by-task (group-by :task/id records)
        ;; each §0 row before the first packet it blocks; `every task` rows first; the rest at the end
        every (filter #(re-find #"(?i)\bevery task\b" (:blocks %)) preconditions)
        rest-rows (remove (set every) preconditions)
        [placed lines] (reduce (fn [[placed acc] t]
                                 (let [rows (remove placed (filter #(next/blocks? (:blocks %) t) rest-rows))]
                                   [(into placed rows) (-> acc (into (map prep-line rows)) (conj (packet-line t (by-task t))))]))
                               [#{} []] packets)
        tail (remove placed rest-rows)
        passes (stages/amendments gates)
        last-signed (let [a (last passes)] (when (:signed a) {:q (:q a) :signed (stages/gate-instant (:signed a) nil)}))]
    (str/join "\n"
              (concat
               ["**Plan and blueprint**" ""
                (signed "Stage plan approved" (:stage/approved gates)
                        (when-let [c (:cap (:stage/approved gates))] (str " (cap " c ")")))
                (str (box reviewed?) "Blueprint reviewed")
                (signed "Blueprint signed off" (:blueprint/signed gates) nil)]
               (mapcat #(amendment-lines % plan-readings blueprint-readings) passes)
               ["" "**Prep and packets** (the blueprint's §0 and §4; a packet's rounds are its first dispatch and each triage after it, as `bb report` counts them)" ""]
               (map prep-line every)
               (if (seq lines) lines ["- [ ] No packet in the blueprint yet"])
               ["" "**The stage's end**" ""
                (scan-line "Security pack" checks)
                (scan-line "Dependency scan" (:deps scans))
                (scan-line "Secrets scan" (:secrets scans))
                (review-line review-records (:security/skipped gates) last-signed)]
               (map prep-line tail)
               (when (= "pre-release" kind)
                 (let [stale (stages/stale-signing gates (keep stages/merged-at records))]
                   [(if (seq stale)
                      (str "- [ ] Threat model signed - " (str/join ", " ((juxt :by #(stages/gate-day % nil)) (stages/current-signing gates)))
                           ", before " (str/join " and " stale) " - signed again at the close")
                      (signed "Threat model signed" (stages/current-signing gates) nil))]))
               [(str (box (:stage/closed gates)) "Stage closed" (when-let [c (:stage/closed gates)] (str " - " (stages/gate-day c nil))))]))))

(defn place
  "`text` with `rendered` between the markers: replaced where the markers are; under the section's
  heading where the heading is and the markers are not (the person's lines there kept, below);
  else the whole section appended, the hand lines with it."
  [text rendered]
  (let [region (str start-marker "\n" rendered "\n" end-marker)
        q #(java.util.regex.Pattern/quote %)]
    (cond
      (and (str/includes? text start-marker) (str/includes? text end-marker))
      (str/replace text (re-pattern (str "(?s)" (q start-marker) ".*?" (q end-marker)))
                   (java.util.regex.Matcher/quoteReplacement region))

      (str/includes? text heading)
      (str/replace-first text heading (str heading "\n\n" region))

      :else
      (str (str/trimr text) "\n\n" heading "\n\n" region "\n\n" (str/join "\n" hand-lines) "\n"))))

(defn rendered-in
  "What is between the markers in `text`, or nil."
  [text]
  (some->> (re-find (re-pattern (str "(?s)" (java.util.regex.Pattern/quote start-marker) "\n(.*?)\n"
                                     (java.util.regex.Pattern/quote end-marker)))
                    text)
           second))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn- read-edn [path] (when (fs/exists? path) (try (edn/read-string (slurp (str path))) (catch Exception _ nil))))

(defn facts
  "What `render` needs for stage `id` of the build at `build`, the run records under `records-dir`."
  [build records-dir id]
  (let [doc #(stages/document build id %)
        bp (doc "-blueprint.md")
        bp-text (when (fs/exists? bp) (slurp (str bp)))
        f (sr/stage-facts build records-dir id)
        plan (some-> (stages/plan-file (stages/stage-dir build id)) (#(fs/path (stages/stage-dir build id) %)))]
    {:kind (or (:kind f) (some-> plan slurp next/stage-kind))
     :gates (read-edn (doc "-gates.edn"))
     :reviewed? (fs/exists? (fs/path (stages/reviews-dir build id) "blueprint-review.edn"))
     :preconditions (if bp-text (next/preconditions bp-text) [])
     :packets (if bp-text (mapv :task/id (blueprint/packets bp-text)) [])
     :records (:records f)
     :checks (some-> (stages/latest-record build id "-security.edn") read-edn)
     :scans {:deps (some-> (stages/latest-record build id "-security-deps.edn") read-edn) :secrets (some-> (stages/latest-record build id "-security-secrets.edn") read-edn)}
     ;; each record with the time its file name carries, to tell one from before an amendment
     :review-records (mapv (fn [{:keys [file record]}] (assoc record ::at (stages/stamp-instant file)))
                           (sr/security-review-records (doc "-security-review")))
     :plan-readings (stages/reading-times (fs/path (stages/reviews-dir build id) "plan-review.edn"))
     :blueprint-readings (stages/reading-times (fs/path (stages/reviews-dir build id) "blueprint-review.edn"))}))

(defn- stage-id-of [build plan]
  (some (fn [[id p]] (when (= (str (fs/canonicalize p)) (str (fs/canonicalize plan))) id))
        (sr/stage-plans (stages/stages-dir build))))

(defn behind
  "The stage plans of the build whose rendered progress is not what the records say now: their
  file names. A plan with no section is none of them."
  [build records-dir]
  (vec (for [[id path] (sr/stage-plans (stages/stages-dir build))
             :let [now (rendered-in (slurp path))]
             :when (and now (not= now (render (facts build records-dir id))))]
         (str (fs/file-name path)))))

(defn -main
  "bb stage-progress <stage-plan.md> | --check [--workspace <dir>]"
  [& args]
  (let [ws (workspace/current-or-exit args)
        {:keys [args]} (workspace/split-args args)
        build (:workspace/build ws)]
    (cond
      (nil? build)
      (println "stage-progress: no workspace here, nothing to do")

      (some #{"--check"} args)
      (let [b (behind build (:workspace/records ws))]
        (if (seq b)
          (println (str "stage-progress: behind its records - " (str/join ", " b)
                        "; bb stage-progress <stage-plan.md> writes it again (information, never a failure)"))
          (println "stage-progress: every progress section matches its records")))

      (first args)
      (let [plan (str (fs/absolutize (first args)))
            id (stage-id-of build plan)]
        (if-not id
          (do (println (str "stage-progress: " plan " is no stage plan of this workspace's build - a stage plan is docs/stages/stage-N/stage-N-plan.md")) (System/exit 1))
          (let [text (slurp plan)]
            (spit plan (place text (render (facts build (:workspace/records ws) id))))
            (println (str "stage-progress: wrote the progress section of " (fs/file-name plan))))))

      :else
      (do (println "usage: bb stage-progress <stage-plan.md> | --check") (System/exit 2)))))
