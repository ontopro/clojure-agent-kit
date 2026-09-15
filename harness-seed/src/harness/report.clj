;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.report
  "The per-run report: what each step cost in time and money, and which model
  and serving provider answered.

  Why this exists rather than a println at the end of a run: §10's two
  measured lessons are both about things nobody could see until they were
  recorded. The Tester turned out to be over 80% of wall time, which is
  invisible without per-step timing. And a community chat template corrupted
  tool-call arguments silently, which is why `:step/provider` is separate from
  `:step/model` — one OpenRouter slug can be answered by several different
  hosts, and which one answered decides the template.

  WHAT IS HONEST HERE. Time is measured. Cost, model and provider are reported
  only where something actually knew them: a mechanical step has none, and
  ManualRunner has none either, because a human's word about what they ran is
  not a measurement. The footer says how many steps had no cost rather than
  letting a total imply it covered everything — a total that silently omits
  three dispatches is worse than no total.

  AND FABRICATED NUMBERS SAY SO. `:step/source` is required on every step, with
  no default, so a caller who has not thought about provenance gets a
  validation failure rather than a silent `:measured`. Synthetic rows are
  marked on the row, on each number, and on the total — because the numbers are
  what get read alone, and a mark in a caption is read once while rows are read
  one at a time. Method §10 lesson 11; it was learned by writing an example
  report that was taken for a record of model calls that never happened."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn gate-steps
  "The steps of a GateResult, in the order they ran. Always :measured — a gate
  result can only come from a gate that ran."
  [gate-result]
  (mapv (fn [{:keys [gate status ms]}]
          {:step/name gate
           :step/kind :gate
           :step/status status
           :step/ms ms
           :step/source :measured})
        (:gates/report gate-result)))

(defn dispatch-step
  "A step for one dispatched role. `result` is an AgentResult; anything the
  runner learned about the call lives in its :runner/meta, which is where a
  closed AgentResult puts provider-specific facts."
  [role result ms]
  (let [meta* (:runner/meta result)]
    (cond-> {:step/name role
             :step/kind :dispatch
             :step/status (if (= :done (:status result)) :done :fail)
             :step/ms ms
             :step/model (:model meta*)
             :step/provider (:provider meta*)
             :step/cost (:cost result)
             :step/tokens (:tokens meta*)
             ;; A runner that reported neither a model nor a cost measured nothing.
             ;; Defaulting the other way is how a hand-made example becomes a record.
             :step/source (if (or (:model meta*) (:cost result)) :measured :synthetic)}
      ;; Only when known, so a record from a runner that never saw a tier is
      ;; byte-identical to one written before the field existed.
      (:service-tier meta*) (assoc :step/service-tier (:service-tier meta*))
      (:cost-source meta*) (assoc :step/cost-source (:cost-source meta*)))))

(defn synthetic
  "A step whose numbers were made up — an example, a mock, a sketch.

  Explicit rather than a flag on `dispatch-step`, because fabricating a step
  should take a deliberate call that names itself."
  [step]
  (assoc step :step/source :synthetic))

(defn totals
  "Summed wall time and cost, plus how much of the run each actually covers.
  `:cost-known` and `:step-count` are what stop the total being read as
  complete when it is not."
  [steps]
  {:ms (reduce + 0 (keep :step/ms steps))
   :cost (reduce + 0 (keep :step/cost steps))
   :cost-known (count (filter :step/cost steps))
   :list-priced (count (filter #(= :list-price (:step/cost-source %)) steps))
   :tokens (reduce + 0 (keep :step/tokens steps))
   :synthetic (count (filter #(= :synthetic (:step/source %)) steps))
   :step-count (count steps)})

(defn- fmt-ms
  "Milliseconds, seconds, or minutes — whichever a human can read at a
  glance. A dispatch that hit its iteration cap ran for 541292ms, and
  `541.3s` is a number you have to do arithmetic on before it means
  anything."
  [ms]
  (cond
    (nil? ms) "—"
    (< ms 1000) (str ms "ms")
    (< ms 60000) (format "%.1fs" (/ ms 1000.0))
    :else (format "%dm%02ds" (quot ms 60000) (quot (mod ms 60000) 1000))))

(defn- fmt-cost
  "Six decimals, not four.

  The first real dispatch cost $0.000003642 and rendered as `$0.0000` — a row
  asserting a run was free when it was not, which is the same class of lie as
  an unmarked fabricated number. Cheap models are the common case and their
  costs are microdollars; four decimals cannot show one."
  [c]
  (if (nil? c) "—" (format "$%.6f" (double c))))

(defn- fmt-tokens [t]
  (if (or (nil? t) (zero? t)) "—" (format "%,d" t)))

(defn- truncate
  "`s` cut to `n` characters, ending in an ellipsis when it was longer.

  IT IS A BACKSTOP, NOT A BUDGET. The table's one invariant is that every
  row has the same width — there is a test for it — and one long value would
  otherwise push every column after it out of line. So the column is wide
  enough that nothing real reaches this: 50 characters, against 35 for the
  longest resolved model slug seen in practice
  (`deepseek/deepseek-v4-flash-20260423`).

  It was 26, and the first real dispatch cut exactly the informative part —
  the `-20260423` that distinguishes the model that ANSWERED from the slug
  that was asked for."
  [s n]
  (let [s (str (or s "—"))]
    (if (<= (count s) n) s (str (subs s 0 (dec n)) "…"))))

(defn render
  "The run report as a string. `run` is a harness.shapes/TaskRun with
  :run/steps populated.

  Model and provider get their own columns rather than sharing one. They are
  different facts: the model is what you asked for, the provider is who
  answered — and one slug can be answered by several hosts, each with its own
  chat template. Collapsing them hides exactly the thing §10 says to log."
  [run]
  (let [steps (:run/steps run)
        {:keys [ms cost cost-known list-priced synthetic step-count] tok :tokens} (totals steps)
        ;; ONE PLACE. The format string and the truncation width are the same
        ;; fact, and when they were two the model column silently cut what the
        ;; format had room for. Run 4 taught this with the rule width; this is
        ;; the same mistake in the same function, found the same way.
        ;; :step IS DERIVED, because step names are data: a retry is named
        ;; `<role>-r<n>`, and run D7's `reviewer-r1` overran a literal 10. Ten
        ;; stays the floor so every report published before it re-renders
        ;; byte for byte.
        w {:step (apply max 10 (map (comp count name :step/name) steps))
           :kind 13 :model 50 :provider 14 :time 8 :cost 10 :tokens 9}
        row (fn [a b c d e f g]
              (format (str "  %-" (:step w) "s %-" (:kind w) "s %-" (:model w)
                           "s %-" (:provider w) "s %" (:time w) "s %" (:cost w)
                           "s %" (:tokens w) "s")
                      a b c d e f g))
        header (row "step" "kind" "model" "provider" "time" "cost" "tokens")
        ;; Derived from the header, not typed. Run 4 added :precondition to the
        ;; step kinds, the column was one character too narrow, and every row
        ;; after it lost its alignment — while the rule, a literal 92, stayed
        ;; exactly as long as it had been. Two places that must agree, and only
        ;; one of them was changed.
        rule (str "  " (str/join (repeat (- (count header) 2) "─")))]
    (str/join
     "\n"
     (remove
      nil?
      (concat
       [""
        (format "Run %s · task %s · %s"
                (:run/id run) (:task/id run) (name (:run/status run)))
        (when (> (:run/attempts run 1) 1)
          (format "  %d attempts" (:run/attempts run)))
        ""
        header
        rule]
       (for [s steps]
         ;; The mark goes in the ROW, not in a caption above the table. A
         ;; caption is read once and the rows are read one at a time, so a
         ;; plausible fabricated value in an unmarked row is a measurement.
         (let [fake? (= :synthetic (:step/source s))
               ;; EVERY FABRICATED VALUE IS BRACKETED, not just the row — a
               ;; label is missed when the values look plausible, and that is
               ;; the whole failure this guards against. A made-up provider is
               ;; the worst of them: "anthropic" reads as a fact about who
               ;; served the call.
               ;;
               ;; Brackets rather than a sigil because they need no legend:
               ;; [anthropic] reads as a placeholder on its own, wherever the
               ;; row is quoted or pasted. Absent values stay "—" unbracketed —
               ;; nil is missing, not fabricated, and conflating the two would
               ;; make the mark mean less everywhere.
               val (fn [v] (if (and fake? (not= v "—")) (str "[" v "]") v))
               cell (fn [v w] (if (and fake? v)
                                (str "[" (truncate v (- w 2)) "]")
                                (truncate v w)))]
           (row (name (:step/name s))
                (name (:step/kind s))
                (cell (:step/model s) (:model w))
                ;; The tier rides in the provider cell rather than a column
                ;; of its own: it qualifies who answered, and "default" —
                ;; what nearly every endpoint reports — says nothing.
                (cell (let [tier (:step/service-tier s)]
                        (cond-> (:step/provider s)
                          (and (:step/provider s) tier (not= "default" tier))
                          (str " " tier)))
                      (:provider w))
                (val (fmt-ms (:step/ms s)))
                ;; A computed cost carries its mark IN THE CELL, like a
                ;; synthetic one: a caption is read once, a row many times.
                (val (str (when (= :list-price (:step/cost-source s)) "~")
                          (fmt-cost (:step/cost s))))
                (val (fmt-tokens (:step/tokens s))))))
       [rule
        ;; A total of $0.0000 next to a footer saying nothing reported a cost
        ;; is a contradiction, and the zero is the half a reader believes.
        ;; A total computed from a fabricated input is fabricated. Marking the
        ;; rows and leaving the total clean is the same failure one line down.
        (let [val (fn [v] (if (and (pos? synthetic) (not= v "—")) (str "[" v "]") v))]
          (row "total" "" "" ""
               (val (fmt-ms ms))
               (if (zero? cost-known) "—" (val (str (when (pos? list-priced) "~") (fmt-cost cost))))
               (val (fmt-tokens tok))))
        ""
        (when (pos? synthetic)
          (format "  [bracketed] = SYNTHETIC: %d of %d steps are made up, not measured."
                  synthetic step-count))
        ;; The honest footer. A total over 3 of 8 steps is not a run total.
        ;; WALL TIME IS NOT THE SUM OF THE STEPS, and the gap is the point.
        ;; Everything the loop did not time lives in the difference —
        ;; orchestration, a human reading a diff, a retry decision. A report
        ;; that showed only the sum would quietly claim the run was nothing
        ;; but its steps.
        ;; A WALL TIME BELOW THE SUM IS A BROKEN RECORD, not overhead, and D6
        ;; printed one: 7m14s against 21m06s of steps, because the driver
        ;; accumulated elapsed time across separate invocations and lost some.
        ;; Stating both as though they agreed hands a reader a contradiction and
        ;; lets them believe whichever half they read first.
        (when-let [wall (:run/wall-ms run)]
          (if (< wall ms)
            (format (str "  Run record is inconsistent: wall time %s is less than the"
                         " %s its own steps took.\n  Trust the steps; the wall figure"
                         " was not measured end to end.")
                    (fmt-ms wall) (fmt-ms ms))
            (format "  Wall time %s for the whole run; the steps above account for %s of it."
                    (fmt-ms wall) (fmt-ms ms))))
        (if (zero? cost-known)
          ;; It named ManualRunner alone until an API dispatch produced this
          ;; line: claude-sonnet-5, direct to api.anthropic.com, 29,131 tokens
          ;; measured and no cost to be had, because that endpoint has no
          ;; generation record. A footer that explains an outcome by naming
          ;; the wrong cause is worse than one that lists the causes.
          (str "  No step reported a cost. Mechanical steps have none;"
               " ManualRunner has none;\n"
               "  an endpoint with no generation record reports tokens but not"
               " money.")
          (format "  Cost covers %d of %d steps; the rest reported none."
                  cost-known step-count))
        (when (pos? list-priced)
          (format "  ~ = computed from list price for %d of them (usage × the profile's :pricing), not reported by the endpoint."
                  list-priced))
        ""])))))

;; ---------------------------------------------------------------------------
;; The drift gate over published reports.
;;
;; `RUNS.md` publishes rendered reports, and the numbers in them are the
;; evidence for what the runs cost. That is generated content living in a
;; markdown file, which is exactly what `harness.rules` already gates for
;; AGENTS.md — so this is that mechanism a second time, not a new idea.
;;
;; It exists because the weaker check does not work. A document that says "you
;; can re-render this yourself" was true of the one example it named and false
;; of two of the eight, and running the command it published passes. What
;; catches that is re-rendering EVERY report and comparing, in both directions.

(def ^:private fenced
  "A fenced block whose fences start their lines, captured with its label and
  its body. Labelled blocks are matched too, so that they are consumed whole.

  THE LABEL IS WHY (NOTES.md row 19). The pattern was three backticks and a
  newline, anywhere. A labelled opening fence does not match that, so its closing
  fence opened the next block, and every block after it paired wrong: D20's table,
  the first published after a ```sh block, was reported missing, and a table
  with no record after one would have raised nothing."
  #"(?ms)^```([^\n`]*)\n(.*?)^```[ \t]*$")

(defn published-reports
  "Every report published in a markdown document, as {run-id block}.

  A report block identifies itself — `render` opens with `Run <id> · task …` —
  so nothing has to be maintained beside it to say which record it came from. A
  marker would be one more thing to keep in sync, and drifting markers are the
  failure this check exists to catch."
  [markdown]
  (into {}
        (keep (fn [[_ label body]]
                (when (str/blank? label)
                  (when-let [id (second (re-find #"Run (\S+) ·" body))]
                    [id (str/trim body)]))))
        (re-seq fenced markdown)))

(defn drift
  "Compare every published report against re-rendering its run record.

  `records` is {file-name-stem record}. Returns a seq of problems — each
  {:run/id _ :problem _ :detail _} — empty when the document and the records
  agree.

  Checked in BOTH directions on purpose. A published report with no record is a
  number nobody can re-derive, which is the case this was written for; a record
  with nothing published is the other half, and without it the gate could be
  satisfied by deleting the evidence rather than fixing the table."
  [markdown records]
  (let [reports (published-reports markdown)]
    (remove
     nil?
     (concat
      (for [[id block] (sort reports)]
        (if-let [record (get records id)]
          ;; A record that will not render is a gate failure with a cause, not
          ;; a stack trace — and it is the original failure exactly: the two
          ;; files that broke this were raw results, and `render` threw an NPE,
          ;; whose message is nil.
          (let [rendered (try {:ok (str/trim (render record))}
                              (catch Throwable t
                                {:err (or (ex-message t) (str (class t)))}))]
            (cond
              ;; The original failure: two files under .local/ were raw agent
              ;; results, not run records, and `render` threw an NPE on both —
              ;; whose message is nil, so say what is actually wrong instead.
              (nil? (:run/id record))
              {:run/id id :problem :not-a-record}

              (not= id (:run/id record))
              {:run/id id :problem :id-mismatch :detail (:run/id record)}

              (:err rendered)
              {:run/id id :problem :unrenderable :detail (:err rendered)}

              (not= (:ok rendered) block)
              {:run/id id :problem :drift}))
          {:run/id id :problem :no-record}))
      (for [id (sort (remove (set (keys reports)) (keys records)))]
        {:run/id id :problem :no-block})))))

(defn problem-line
  "One gate-failure line: what is wrong, and what to do about it."
  [{:keys [problem detail] rid :run/id} md-path dir]
  (case problem
    :no-record (str "  " rid ": published in " md-path " with no record in " dir
                    "/ — the numbers in it cannot be checked. Add " dir "/" rid ".edn.")
    :no-block (str "  " rid ": " dir "/" rid ".edn is not published in " md-path
                   " — evidence for a table nobody can see. Publish it, or delete the record.")
    :not-a-record (str "  " rid ": " dir "/" rid ".edn has no :run/id — it is not a run"
                       " record, so `bb report` cannot read it. Shape it into one, or drop it.")
    :id-mismatch (str "  " rid ": " dir "/" rid ".edn holds :run/id " (pr-str detail)
                      " — the file name is how a report finds its record; make them match.")
    :unrenderable (str "  " rid ": " dir "/" rid ".edn does not render — " detail
                       ". `bb report` has to read it, or \"check it yourself\" needs a script nobody has.")
    :drift (str "  " rid ": the report in " md-path " does not match " dir "/" rid ".edn"
                " — regenerate it with `bb report < " dir "/" rid ".edn`.")))

(defn check-main
  "bb report-check [markdown] [records-dir]   (default: ../RUNS.md ../runs)

  Gate: every report published in the document re-renders from the record of
  the same name, and every record is published. Takes its targets as arguments
  for the same reason `rules-sync` does — the mechanism belongs to the seed and
  the documents do not, so a project that copies this points it at its own."
  [& args]
  (let [[md-path dir] (remove #(str/starts-with? % "--") args)
        md-path (or md-path "../RUNS.md")
        dir (or dir "../runs")]
    (doseq [p [md-path dir]]
      (when-not (fs/exists? p)
        (println (str "report-check: " p " not found"))
        (System/exit 1)))
    (let [records (into {} (for [f (fs/glob dir "*.edn")]
                             [(str/replace (fs/file-name f) #"\.edn$" "")
                              (edn/read-string (slurp (fs/file f)))]))
          problems (drift (slurp md-path) records)]
      (if (seq problems)
        (do (println (str "report drift: " md-path " and " dir "/ disagree"))
            (doseq [p problems] (println (problem-line p md-path dir)))
            (System/exit 1))
        (println (str "reports in sync: " (count records) " in " md-path))))))

(defn -main
  "bb report — render a run record read from stdin as EDN."
  [& _]
  (println (render (read-string (slurp *in*)))))
