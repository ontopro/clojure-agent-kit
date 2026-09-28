;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.money.report
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
   [clojure.string :as str]
   [harness.contract.shapes :as shapes]
   [harness.setup.workspace :as workspace]))

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
             ;;
             ;; EXCEPT A CALL THAT FAILED AT THE PROVIDER, which has neither and is
             ;; as real as any other. Two Reviewer dispatches refused with HTTP 402
             ;; (a spent credit limit) were rendered as "SYNTHETIC: made up, not
             ;; measured" - each a request that was sent, timed at about 300ms, and
             ;; answered. An :error in :runner/meta is what a real failed call
             ;; leaves, and a hand-made example does not have one.
             :step/source (if (or (:model meta*) (:cost result) (:error meta*)) :measured :synthetic)}
      ;; Only when known, so a record from a runner that never saw a tier is
      ;; byte-identical to one written before the field existed.
      (:service-tier meta*) (assoc :step/service-tier (:service-tier meta*))
      (:cost-source meta*) (assoc :step/cost-source (:cost-source meta*))
      ;; The handle a later `bb reprice` fetches by. The runner always had
      ;; them and the record never did: one step in twelve runs of one build
      ;; arrived unpriced, and nothing on disk could name its completions.
      (seq (:generation-ids meta*)) (assoc :step/generation-ids (vec (:generation-ids meta*))))))

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
   :repriced (count (filter #(= :repriced (:step/cost-source %)) steps))
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

(defn spec-review-lines
  "The report's lines about the spec review, or nil when the run had none.

  ONE REVIEW PRINTS AS IT ALWAYS DID, so a record written before the history
  re-renders byte for byte. More than one prints each reading - its findings
  and its cost - and what they came to, because a contract read twice was
  amended in between, and a report that showed only the last reading hid both
  the amendment and a call that was paid for.

  THEN THE WHOLE COST, whenever a review's cost is known. `:run/cost` and the
  table's total are the LOOP's: the review is a desk step, not a step of the
  loop, and not a row. But it is part of what the task cost, this document's
  own README says so, and the first project to run with the review in the loop
  had to write a script to add the two together. The line names both parts so
  neither total can be read as the other. The `~` follows the loop's total: a
  sum that includes a computed price is computed."
  [run]
  (when-let [e (some #(when (= :spec-review (:event/kind %)) %) (:run/events run))]
    (let [reviews (:reviews e)
          counted (fn [n] (format "%d finding%s" n (if (= 1 n) "" "s")))
          review-cost (if (seq reviews)
                        (let [cs (keep :cost reviews)] (when (seq cs) (reduce + cs)))
                        (:cost e))
          {:keys [cost cost-known list-priced]} (totals (:run/steps run))]
      (remove
       nil?
       [(if (> (count reviews) 1)
          (format "  Spec reviews before start: %d — %s%s. The contract that was dispatched had drawn %s."
                  (count reviews)
                  (str/join "; " (for [{:keys [count cost]} reviews]
                                   (str (counted count) (when cost (str " " (fmt-cost cost))))))
                  (if review-cost (str "; " (fmt-cost review-cost) " in all") "")
                  (counted (:findings e)))
          (format "  Spec review before start: %s%s."
                  (counted (:findings e))
                  (if-let [c (:cost e)] (str ", " (fmt-cost c)) "")))
        (when (and review-cost (pos? cost-known))
          (format "  Whole cost: %s%s — the loop's %s%s above and %s of spec review, which that total does not include."
                  (if (pos? list-priced) "~" "") (fmt-cost (+ cost review-cost))
                  (if (pos? list-priced) "~" "") (fmt-cost cost)
                  (fmt-cost review-cost)))]))))

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

;; ---------------------------------------------------------------------------
;; The performance section: what the run cost in time and money, derived from
;; the record and nothing else.
;;
;; EVERY FIGURE HERE COMES FROM A TIMESTAMP OR A PER-STEP MEASUREMENT. Nothing is
;; accumulated across invocations — a wall time built that way once printed
;; 7m14s against 21m06s of steps. Where a record predates a field (events with
;; no `:at`, no `:balance` event, no `:run/roles`) the line says "not recorded"
;; rather than computing from what is there.
;; ---------------------------------------------------------------------------

(defn- at-ms [e] (some-> ^java.util.Date (:at e) .getTime))

(defn- role-of
  "The role a step name belongs to: `:coder-r2` → :coder, `:triage-r1` → :triage."
  [step-name]
  (keyword (str/replace (name step-name) #"-r\d+$" "")))

(defn- pct [part whole]
  (if (and whole (pos? whole)) (format "%.0f%%" (* 100.0 (/ part whole))) "—"))

(defn time-lines
  "Total, waiting and active time from the events' absolute `:at` stamps.
  Waiting is the gap after each `:stopped` event until the next event — a person
  reading and deciding. A final stop with nothing after it is not a wait the
  record can measure, and the line says so."
  [{:keys [run/events run/steps]}]
  (let [stamped (filter :at events)]
    (if (< (count stamped) 2)
      ["  Time: not recorded — the events carry no absolute timestamps (a record from before they did)."]
      (let [total (- (at-ms (last stamped)) (at-ms (first stamped)))
            stops (keep-indexed (fn [i e]
                                  (when (= :stopped (:event/kind e))
                                    (let [next (first (filter :at (drop (inc i) events)))]
                                      {:kind (:stop/kind e) :wait (when next (- (at-ms next) (at-ms e)))})))
                                events)
            waited (reduce + 0 (keep :wait stops))
            unmeasured (count (remove :wait stops))
            active (- total waited)
            in-steps (reduce + 0 (keep :step/ms steps))
            mechanical (reduce + 0 (keep :step/ms (filter #(#{:provision :precondition :gate-0 :gate} (:step/kind %)) steps)))]
        (into [(format "  Time: %s total, %s waiting on a person over %d stop%s, %s active."
                       (fmt-ms total) (fmt-ms waited) (count stops) (if (= 1 (count stops)) "" "s") (fmt-ms active))
               (format "        Of the active time the steps account for %s (%s); %s is mechanical (provisioning, gate 0, gates, preconditions); the rest is orchestration, assembly and a person typing a command."
                       (fmt-ms in-steps) (pct in-steps active) (fmt-ms mechanical))]
              (concat
               (for [{:keys [kind wait]} stops]
                 (format "        stop %-16s %s" (name (or kind :?)) (if wait (str "waited " (fmt-ms wait)) "no event after it — the wait is not in the record")))
               (when (pos? unmeasured)
                 [(format "        (%d stop%s with nothing after — the run ended there; that wait is not counted)" unmeasured (if (= 1 unmeasured) "" "s"))])))))))

(defn money-lines
  "Cost by provider, by role and by model, from the steps; the OpenRouter balance
  at start and at record from the `:balance` events, with the difference beside
  the summed OpenRouter cost; Anthropic as spend, since its API has no balance."
  [{:keys [run/events run/steps]}]
  (let [priced (filter :step/cost steps)
        by (fn [f] (->> priced (group-by f) (map (fn [[k ss]] [k (reduce + (map :step/cost ss))])) (sort-by (comp - second))))
        total (reduce + 0 (map :step/cost priced))
        list-priced (count (filter #(= :list-price (:step/cost-source %)) priced))
        balances (filter #(= :balance (:event/kind %)) events)
        bal (fn [when] (:openrouter (first (filter #(= when (:when %)) balances))))
        b0 (bal :start) b1 (bal :record)
        openrouter-cost (reduce + 0 (map :step/cost (filter #(some-> (:step/provider %) (not= "Anthropic API")) priced)))
        anthropic-cost (reduce + 0 (map :step/cost (filter #(= "Anthropic API" (:step/provider %)) priced)))
        money (fn [x] (if x (format "$%.2f" (double x)) "—"))]
    (into [(format "  Money: %s over %d priced step%s%s."
                   (fmt-cost total) (count priced) (if (= 1 (count priced)) "" "s")
                   (if (pos? list-priced) (format ", %d of them computed from list price" list-priced) ""))
           (str "        by provider  " (str/join " · " (for [[k v] (by :step/provider)] (str (or k "unknown") " " (fmt-cost v)))))
           (str "        by role      " (str/join " · " (for [[k v] (by (comp role-of :step/name))] (str (name k) " " (fmt-cost v)))))
           (str "        by model     " (str/join " · " (for [[k v] (by :step/model)] (str (or k "—") " " (fmt-cost v)))))]
          (cond
            (empty? balances)
            ["        OpenRouter balance: not recorded (a record from before `start` and `record` wrote it)."]
            (or (:unavailable b0) (:unavailable b1))
            [(format "        OpenRouter balance: unavailable (%s)." (or (:unavailable b0) (:unavailable b1)))]
            :else
            (cond-> [(format "        OpenRouter balance %s at start → %s at record: %s spent by the key, against %s summed from this run's OpenRouter steps%s."
                             (money (:remaining b0)) (money (:remaining b1))
                             (money (- (:remaining b0) (:remaining b1))) (fmt-cost openrouter-cost)
                             (if (> (Math/abs (- (- (:remaining b0) (:remaining b1)) openrouter-cost)) 0.02)
                               " — they differ by more than two cents: other calls on the same key, or costs that arrived late" ""))]
              ;; Only when a step went to Anthropic's API directly. A run with every role
              ;; through OpenRouter printed "Anthropic spend $0.00" under a provider line
              ;; that said "Anthropic $0.18" - the serving provider - and read as a contradiction.
              (pos? anthropic-cost)
              (conj (format "        Anthropic API spend %s (its API has no balance endpoint; the console has it)." (fmt-cost anthropic-cost))))))))

(defn token-lines
  "Tokens per model from the dispatch events' `:usage`, with the cache share for
  models that report cache reads: a cache read is a quarter of the price."
  [{:keys [run/events]}]
  (let [ds (filter #(and (= :dispatch (:event/kind %)) (:usage %)) events)]
    (if (empty? ds)
      ["  Tokens: not recorded (no dispatch carries usage)."]
      (into ["  Tokens, per model (in / out / cache-read / cache-write):"]
            (for [[model us] (group-by :model ds)
                  :let [sum (fn [k] (reduce + 0 (keep k (map :usage us))))
                        in (sum :in) out (sum :out) cr (sum :cache-read) cw (sum :cache-write)
                        prompt (+ in cr cw)]]
              (format "        %-36s %s / %s / %s / %s%s" (or model "—")
                      (fmt-tokens in) (fmt-tokens out) (fmt-tokens cr) (fmt-tokens cw)
                      (if (pos? cr) (format "  — %s of the prompt tokens were cache reads" (pct cr prompt)) "")))))))

(defn round-lines
  "The rounds after the first: each `:triage` event, who was retried and why, and
  what the dispatches it caused cost."
  [{:keys [run/events run/steps]}]
  (let [ts (filter #(= :triage (:event/kind %)) events)
        cost-of (fn [step-name] (some #(when (= step-name (:step/name %)) (:step/cost %)) steps))]
    (if (empty? ts)
      ["  Rounds: 1 — no retry."]
      (into [(format "  Rounds: %d — %d retr%s:" (inc (count ts)) (count ts) (if (= 1 (count ts)) "y" "ies"))]
            (for [{:keys [role attempt decision]} ts
                  :let [step (keyword (str (name role) "-r" (dec attempt)))
                        c (cost-of step)]]
              (format "        %-12s %s%s" (name step)
                      (if c (str (fmt-cost c) "  ") "")
                      (truncate (str/replace (str decision) #"\s+" " ") 110)))))))

(defn role-lines
  "Model, family and effort per role, as the run ran them."
  [{:keys [run/roles]}]
  (if (empty? roles)
    ["  Roles: not recorded (a record from before the profile's settings were copied in)."]
    (into ["  Roles, as run:"]
          (for [[r {:keys [model family effort]}] (sort-by (comp name key) roles)]
            (format "        %-14s %-12s %-36s effort %s" (name r) (some-> family name) (or model "—") (or effort "—"))))))

(defn commit-lines
  "The KIT, the application and the plan the record was taken at, on one line,
  abbreviated the way git prints them; `—` for a repository the run had none of
  (a plan, outside a workspace). A record from before they were written says so."
  [run]
  (if (not-any? #(contains? run %) [:run/kit-commit :run/app-commit :run/plan-commit])
    ["  Commits: not recorded (a record from before `record` named them)."]
    [(str "  Commits: "
          (str/join " · " (for [[label k] [["kit" :run/kit-commit] ["app" :run/app-commit] ["plan" :run/plan-commit]]]
                            (str label " " (if-let [sha (get run k)] (subs sha 0 (min 7 (count sha))) "—")))))]))

(defn performance
  "The section `render` prints below the footer."
  [run]
  (str/join "\n" (concat ["" "  ── performance ──"]
                         (time-lines run) [""] (money-lines run) [""] (token-lines run) [""]
                         (round-lines run) [""] (role-lines run) [""] (commit-lines run))))

(defn render
  "The run report as a string. `run` is a harness.contract.shapes/TaskRun with
  :run/steps populated.

  Model and provider get their own columns rather than sharing one. They are
  different facts: the model is what you asked for, the provider is who
  answered — and one slug can be answered by several hosts, each with its own
  chat template. Collapsing them hides exactly the thing §10 says to log."
  [run]
  (let [steps (:run/steps run)
        {:keys [ms cost cost-known list-priced repriced synthetic step-count] tok :tokens} (totals steps)
        ;; ONE PLACE. The format string and the truncation width are the same
        ;; fact, and when they were two the model column silently cut what the
        ;; format had room for. An early run taught this with the rule width; this is
        ;; the same mistake in the same function, found the same way.
        ;; :step IS DERIVED, because step names are data: a retry is named
        ;; `<role>-r<n>`, and a run's `reviewer-r1` overran a literal 10. Ten
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
        ;; Derived from the header, not typed. An early run added :precondition to the
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
        ;; NOT THE ROUNDS. :run/attempts is the highest attempt any ONE role reached; a run
        ;; that retried the Tester once and the Coder once has 2 of these and 3 rounds. It
        ;; printed as "2 attempts" above a section saying "Rounds: 3", and an analysis was
        ;; drafted from the wrong one. The line says which it is.
        (when (> (:run/attempts run 1) 1)
          (format "  attempt %d was the highest any one role reached — for dispatch rounds see Rounds, below"
                  (:run/attempts run)))
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
        ;; A WALL TIME BELOW THE SUM IS A BROKEN RECORD, not overhead, and one
        ;; run printed 7m14s against 21m06s of steps, because the driver
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
        (when (pos? repriced)
          (format "  %d of them repriced after the run by `bb reprice`, from the endpoint's generation records."
                  repriced))
        (performance run)
        ;; THE DESK STEP, IF THERE WAS ONE. `spec-review` runs before `start` and is not a
        ;; step of the loop, so it is not a row; `start` records it as an event, and the
        ;; footer prints the one number the Architect acted on. A spec that drew ten
        ;; findings was not ready; a report that hid the ten would hide why the run cost what it did.
        ;; THE DESK STEP, IF THERE WAS ONE: not a row, and not left out (`spec-review-lines`).
        (some->> (spec-review-lines run) seq (str/join "\n"))
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

  THE LABEL IS WHY. The pattern was three backticks and a newline, anywhere. A
  labelled opening fence does not match that, so its closing fence opened the
  next block, and every block after it paired wrong: the first table published
  after a ```sh block was reported missing, and a table with no record after one
  would have raised nothing."
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

(defn invalid-records
  "Every record that does not match `harness.contract.shapes/TaskRun`, as problems.

  THE SCHEMA WAS NEVER RUN. `TaskRun` sat in `harness.contract.shapes` with no caller
  for as long as the seed existed, and every record committed in that time
  failed it — a `:run/status` outside the enum, no `:run/cost`, no
  `:run/started-at` — because nothing ever looked. A schema nothing runs is a
  comment; this is what runs it, in the same gate that checks the tables.

  A file that is not a record at all is `drift`'s `:not-a-record`, so anything
  without a `:run/id` is left to it rather than reported twice."
  [records]
  (for [[id r] (sort-by key records)
        :let [errors (shapes/explain-run r)]
        :when (and (:run/id r) errors)]
    {:run/id id :problem :invalid-record :detail errors}))

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
    :invalid-record (str "  " rid ": " dir "/" rid ".edn does not match harness.contract.shapes/TaskRun — "
                         (pr-str detail) ". A record that fails its own schema is not evidence"
                         " of the shape it claims; fix the record, or the schema if the schema is wrong.")
    :drift (str "  " rid ": the report in " md-path " does not match " dir "/" rid ".edn"
                " — regenerate it with `bb report < " dir "/" rid ".edn`.")))

(defn published-state
  "`:nothing` when neither the document nor the records directory exists,
  `:half` when exactly one does, `:both` otherwise.

  NOTHING PUBLISHED IS NOT DRIFT. The gate exists for a document that publishes
  reports; a project that has recorded no run yet has neither half, and a fresh
  copy of the seed failed `bb gates` on exactly that. One half without the
  other is still a failure, and it is the failure the gate was written for: a
  published number whose record is gone, or a record nobody published."
  [md-path dir]
  (case (count (filter fs/exists? [md-path dir]))
    0 :nothing
    1 :half
    :both))

(defn check-targets
  "What `bb report-check` holds to what: `{:md-path _ :dir _}` from the two
  arguments when given, else from the workspace `ws` (`:workspace/run-tables`
  and `:workspace/records`, as `bb init` writes them); `{:skip reason}` when
  there is neither - no workspace around this clone, or one from before the
  keys - since a check with no targets has nothing to fail on and says so
  rather than guessing a document."
  [[md-path dir] ws]
  (cond
    (and md-path dir) {:md-path md-path :dir dir}
    (or md-path dir) {:usage "one target given; it takes two (bb report-check <markdown> <records-dir>) or none (the workspace's)"}
    (nil? ws) {:skip "not in a workspace and no targets given - nothing to check (bb report-check <markdown> <records-dir> names them)"}
    (and (:workspace/run-tables ws) (:workspace/records ws))
    {:md-path (:workspace/run-tables ws) :dir (:workspace/records ws)}
    :else {:skip (str (fs/path (:workspace/dir ws) workspace/file-name)
                      " names no :workspace/run-tables and :workspace/records (from before they were written) - nothing to check")}))

(defn check-main
  "bb report-check [<markdown> <records-dir>]

  Gate: every report published in the document re-renders from the record of
  the same name, and every record is published. Takes its targets as arguments
  for the same reason `rules-sync` does — the mechanism belongs to the harness
  and the documents do not. The KIT publishes no run records of its own
  (its evidence is the health records),
  so nothing calls this without arguments; a project points it at its own.

  A project with NEITHER target passes, and is told so (`published-state`).

  With no arguments, the targets are the workspace's (`check-targets`): what
  `bb gates` runs in a workspace's clone - the workspace this runs in, or the
  one `--workspace <dir>` / `KIT_WORKSPACE` points at, for a KIT kept outside
  it. Outside one there is nothing to hold to anything, and it says so and
  passes - the KIT's own gates run there."
  [& args]
  (let [ws (workspace/current-or-exit args)
        given (remove #(str/starts-with? % "--") (:args (workspace/split-args args)))
        {:keys [md-path dir skip usage]} (check-targets given ws)]
    (when usage
      (println (str "usage: " usage))
      (System/exit 2))
    (when skip
      (println (str "report-check: " skip))
      (System/exit 0))
    (case (published-state md-path dir)
      :nothing
      (println (str "report-check: no " md-path " and no " dir "/ — nothing is published, so nothing can have drifted. "
                    "Create both when the first run is recorded."))

      :half
      (do (doseq [p [md-path dir] :when (not (fs/exists? p))]
            (println (str "report-check: " p " not found, and its other half exists — a published report "
                          "needs its record and a record needs publishing")))
          (System/exit 1))

      (let [records (into {} (for [f (fs/glob dir "*.edn")]
                               [(str/replace (fs/file-name f) #"\.edn$" "")
                                (edn/read-string {:default tagged-literal} (slurp (fs/file f)))]))
            problems (concat (drift (slurp md-path) records) (invalid-records records))]
        (if (seq problems)
          (do (println (str "report drift: " md-path " and " dir "/ disagree"))
              (doseq [p problems] (println (problem-line p md-path dir)))
              (System/exit 1))
          (println (str "reports in sync: " (count records) " in " md-path)))))))

(defn -main
  "bb report — render a run record read from stdin as EDN."
  [& _]
  (println (render (read-string (slurp *in*)))))
