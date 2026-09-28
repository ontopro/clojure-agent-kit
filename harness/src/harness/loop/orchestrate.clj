;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Reshaped from src/thub/harness/orchestrate.clj @ 5df04ad (2026-09-05).
(ns harness.loop.orchestrate
  "The loop: `bb run-loop run <run-dir>` drives one task to its next stop.

  WHAT A LOOP IS FOR, AND WHAT IT IS NOT FOR. It is for the steps nobody needs
  to think about — provision, dispatch, assemble, gate, review — and for
  noticing, reliably, when something has gone wrong that a person must decide
  about. The one judgement it makes itself is TRIAGE: on a red gate under the
  cap, on a note at the pause, and on a review that rejected, it asks
  `harness.loop.triage` who owns the problem and dispatches on the answer when the
  answer is a role. Every other
  branch — and every triage answer that is not a role — ends the same way: an
  event recorded, a reason printed, and the run left exactly where it stopped
  with its worktrees in place. The commands in `harness.loop.driver` are how a
  person carries on from there, and running `run` again picks the loop back
  up.

  So the whole design is in two functions. `next-action` is PURE over the run's
  state and says what should happen next; `run-task!` executes that, one step
  at a time, until the answer is `:stop`. Nothing about which step comes next
  is buried inside a step.

  Stops, all of them a person's: a dispatch that failed · a provider refusing
  for credit · an nREPL that did not answer · assembly refusing a file · green
  gates over an empty diff · the retry cap · a review with no verdict · triage
  routing a red gate, a note or a rejection to the architect or to a person, or
  routing the Tester feedback that would hand it the implementation · and
  triage saying the machine is at fault, not the run (`tooling`). And the one stop that is not
  a failure: THE MERGE GATE. A review that approved stops the loop
  `:awaiting-merge`, and `bb run-loop merge <run-dir> <decision.edn>` is a
  command a person types — upstream asks y/n on stdin from inside the loop,
  which cannot be re-entered, recorded, or answered tomorrow.

  Injectable, as `deps`: `:repl-probe`, a `(fn [worktree port])`, and
  `:triage-fn`, a `(fn [trigger])` — the fixture worktrees in the tests have
  no nREPL and no budget, and these two seams are what let the rest of the
  loop be exercised without either."
  (:require
   [clojure.string :as str]
   [harness.loop.driver :as driver]
   [harness.loop.triage :as triage]
   [harness.models.profile :as profile]
   [harness.money.balance :as balance]
   [harness.money.report :as report]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; pure: what happens next
;; ---------------------------------------------------------------------------

(defn- stop
  ([kind route reason] (stop kind route reason :escalated))
  ([kind route reason status]
   {:action :stop :stop/kind kind :route route :reason reason :run/status status}))

(defn- credit-reason
  "The `:credit` stop's reason, from the failed dispatch's event: who answered
  402 to which dispatch, and which variable holds the key. A credit limit is
  not something a retry can fix, and the first project built on the KIT met
  it as a `:dispatch-failed` whose next step was `retry` - which failed the
  same way, for the same reason, with the same text."
  [{:keys [event/step credit]}]
  (let [host (try (.getHost (java.net.URI. (str (:endpoint credit))))
                  (catch Exception _ nil))]
    (str (or host "the provider") " answered 402 to the " (name step) " dispatch: the key in "
         (or (:key-env credit) "the role's key variable")
         " has no credit left — nothing sent again will do better until the account is topped up")))

(defn- current-review
  "The Reviewer's dispatch event, when it is the last word on the run's code:
  among the gate runs and ALL the dispatches, the last one is a review. A
  Coder sent back after a rejection makes the review before it history —
  without that, the loop would read the same rejection again and route it
  again, a paid round each time.

  AN AMENDMENT MAKES IT HISTORY TOO. A review judges the code against the
  contract it was shown; once `amend` has changed that contract the verdict is
  about a document that no longer exists. The first adopter to answer a
  rejection with an amendment typed `run` and watched the loop send the OLD
  rejection to triage again — which said, correctly and for money, that the
  finding no longer applied, and routed `human`: an Architect's stop turned into
  a person's, and still no verdict on the amended contract. With the review set
  aside the state falls through to `:check` — the gates again, and a fresh
  review — which is what the person then had to type by hand."
  [events]
  (let [e (last (filter #(#{:gates :dispatch :amend} (:event/kind %)) events))]
    (when (= :reviewer (:role e)) e)))

(defn- amended-since-gates?
  "Whether an `amend` came after the last gate run, which makes that gate result
  HISTORY for the same reason an amendment makes a review history: the gates
  judged the files against a contract that has since changed. A slice that
  granted nothing fails the `calls` gate; the amendment that adds the grant is
  the fix, and the recorded red result is now about a spec that no longer
  exists. `run` once sent that stale result to triage - which said, correctly
  and for money, that the gate's output no longer matched the spec, and routed
  `human`: an Architect's stop turned into a person's. The gates cost nothing,
  so they run again first. NOT `driver/gated-current?`, which answers a
  different question - whether the gate worktree holds the latest FILES, which
  an amendment does not change - and is what `record` and `merge` read."
  [events]
  (= :amend (:event/kind (last (filter #(#{:gates :amend} (:event/kind %)) events)))))

(defn next-action
  "What the loop should do next, from the run's state alone.

  PURE, AND TABLE-TESTED, because this is the whole control flow. A loop whose
  routing decisions are scattered through the steps that perform them can only
  be tested by performing them, which means provisioning worktrees and calling
  models to find out whether a `cond` is right.

  Returns `{:action :start|:continue|:check|:triage|:stop}`; for a triage also
  `:trigger` (`:red-gate`, `:note` or `:rejection`), and for a stop also `:stop/kind`,
  `:route` (whose decision it is), `:reason`, and the `shapes/RunStatus` the
  record will carry. A triage is not a stop: the loop asks, and what it does
  next depends on the answer."
  [state]
  (let [events (vec (:events state))
        last-event (last events)
        last-kind (:event/kind last-event)
        failed-step (driver/last-dispatch-failed state)
        failed-event (when failed-step (last (filter #(= :dispatch (:event/kind %)) events)))
        review (current-review events)
        capped (fn [what]
                 (let [cap (driver/retry-cap (:spec state))
                       round (driver/rounds state)]
                   (when (>= round cap)
                     (stop :capped :human
                           (str "the retry cap of " cap " is spent — " round " dispatch rounds, " what)))))]
    (cond
      (empty? (:sessions state))
      {:action :start}

      ;; Merged is finished. `merge` records and tears down itself, so this is
      ;; only ever read by a `run` typed afterwards, which must not walk into a
      ;; review of worktrees that are gone.
      (some #(= :merged (:event/kind %)) events)
      (stop :merged :human "this run is merged, recorded and torn down" :merged)

      ;; NOTHING HAS HAPPENED SINCE THE LAST STOP, so it is the same stop. A
      ;; triage verdict costs money and is not deterministic; re-running `run`
      ;; over an untouched run must not buy a second opinion. The commands
      ;; each leave an event, so any action a person took clears this.
      (= :stopped last-kind)
      (stop (:stop/kind last-event) (:route last-event) (:reason last-event) (:run/status last-event))

      ;; BEFORE `:not-dispatched`, which is otherwise also true: a refused
      ;; dispatch leaves no dispatch event, and "nothing was dispatched" is the
      ;; symptom where the dead REPL is the cause.
      (= :repl-dead last-kind)
      (stop :repl-dead :human
            "the worktree nREPL did not answer a probe; nothing was dispatched into it")

      ;; Provisioned, and nothing dispatched: `start` threw after the worktrees
      ;; existed — a failed precondition, most likely. `check` from here would
      ;; assemble files no role has written.
      (not (driver/dispatched? state))
      (stop :not-dispatched :human
            (str "the worktrees exist and nothing was dispatched — fix the spec, then "
                 "`teardown` (which resets a run that dispatched nothing) and `start` again"))

      ;; BEFORE `:dispatch-failed`, which is otherwise also true: a 402 is a
      ;; failed dispatch, but the answer is money, not a retry.
      (and failed-step (= :credit (:error/kind failed-event)))
      (stop :credit :human (credit-reason failed-event))

      failed-step
      (stop :dispatch-failed :human
            (str "the " (name failed-step) " dispatch failed — retry it before anything builds on it"))

      (:paused state)
      (if (driver/decided-since-pause? events)
        {:action :continue}
        {:action :triage :trigger :note})

      (= :assemble-refused last-kind)
      (stop :assemble-refused :human "a role wrote outside its packet, and assembly refused it")

      (= :empty-diff last-kind)
      (stop :empty-diff :human "green gates but an empty diff — the gate worktree is unchanged")

      ;; THE VERDICT IS READ HERE AND NOWHERE ELSE. Approve is the only way to
      ;; `:awaiting-merge`, which is the only state `merge` accepts; a rejection
      ;; is a failure somebody owns, so it goes where a red gate goes, under the
      ;; same cap; and a review with no verdict block is a person's, with the
      ;; findings it did give printed above the stop.
      review
      (case (:verdict (:verdict review))
        :approve (stop :reviewed :human "the Reviewer approved; the merge is a person's" :awaiting-merge)
        :reject (or (capped "and the Reviewer rejected")
                    {:action :triage :trigger :rejection})
        (stop :no-verdict :human "the Reviewer gave no verdict — its findings are above, and the loop cannot read them"))

      ;; A dispatch has happened since the last gate run — a hand `retry`, or
      ;; the Tester after a `continue`.
      (not (driver/gated-current? events))
      {:action :check}

      ;; An amendment answered the last gate run: gate again before anyone is asked.
      (amended-since-gates? events)
      {:action :check}

      (not (:gates/passed? (:last-gates state)))
      (or (capped "still red")
          {:action :triage :trigger :red-gate})

      :else {:action :check})))

;; ---------------------------------------------------------------------------
;; what to tell the person at each stop
;; ---------------------------------------------------------------------------

(def next-steps
  "What a person does from each stop. Printed, because a loop that stops
  without saying what it is waiting for is a loop that has to be read."
  {:not-dispatched ["bb run-loop teardown <run-dir>   ; resets a run that dispatched nothing"
                    "then fix spec.edn and `start` again"]
   :repl-dead ["check the nREPL in the role's worktree, then:"
               "bb run-loop run <run-dir>   ; the probe runs again before any dispatch"]
   :dispatch-failed ["bb run-loop retry <run-dir> <role> <triage.edn>   ; {:decision \"...\" :feedback [...]}"
                     "bb run-loop check <run-dir>                       ; if it was the Reviewer: gates and review again"]
   :credit ["a credit limit, not a model error: read the reason above, add credit to that account, then"
            "bb balance                                        ; the key's limit and the account's credit, as the provider reports them"
            "bb run-loop retry <run-dir> <role> <triage.edn>   ; {:decision \"credit added\"}; the same packet, sent again"]
   :paused ["triage read the note and did not continue the run; read both above, then one of:"
            "bb run-loop amend <run-dir> <spec-before.edn> <reason>   ; the contract was wrong"
            "bb run-loop retry <run-dir> <role> <triage.edn>          ; the dispatch was"
            "bb run-loop continue <run-dir> <decision.edn>            ; {:decision \"why it stands\"}"]
   :assemble-refused ["read the refusal above; the file is in the role's worktree"
                      "bb run-loop retry <run-dir> <role> <triage.edn>"]
   :empty-diff ["read the last dispatch's final message — it wrote nothing that reached the gates"
                "bb run-loop retry <run-dir> <role> <triage.edn>"]
   :red-gate ["triage routed this red gate away from both roles; its reason is above, the gate's output before it"
              "bb run-loop amend <run-dir> <spec-before.edn> <reason>   ; if the contract is what is wrong"
              "bb run-loop retry <run-dir> <role> <triage.edn>          ; the decision is written first"
              "bb run-loop run <run-dir>                                ; after either, the loop carries on"]
   :tester-leak ["triage routed the Tester with feedback that names the implementation; the leaks are listed above"
                 "bb run-loop retry <run-dir> tester <triage.edn>                ; reworded, naming only the contract"
                 "bb run-loop retry <run-dir> tester <triage.edn> --allow-leak   ; or record the judgement to send it"]
   :tooling ["triage says the fault is outside this run - a merged file no role here owns, the build, the harness; its name is above"
             "bb run-loop check <run-dir>                       ; on a red gate whose failure is seed-dependent: the gates again, as they are"
             "otherwise fix it outside this run (its own run through the loop, or by hand), then:"
             "bb run-loop continue <run-dir> <decision.edn>     ; on a note: {:decision \"the machine is fixed because…\"}"
             "bb run-loop record <run-dir> && bb run-loop teardown <run-dir> && bb run-loop start <run-dir>   ; on a red gate: the worktrees cannot see a fix made after they were cut"]
   :capped ["nothing more is dispatched for this task. Read the run, then:"
            "bb run-loop record <run-dir> && bb run-loop teardown <run-dir>"]
   :reviewed ["read the Reviewer's findings above. To merge — the gate worktree, one commit, --no-ff into the base checkout:"
              "bb run-loop merge <run-dir> <decision.edn>   ; {:decision \"why this merges\"}; then records and tears down"
              "To deny it, type nothing: the run stays :awaiting-merge with its worktrees and branches in place. Or:"
              "bb run-loop record <run-dir> && bb run-loop teardown <run-dir>   ; keep the record, merge nothing"]
   :rejected ["the Reviewer rejected and triage routed it away from both roles; both are above"
              "bb run-loop amend <run-dir> <spec-before.edn> <reason>   ; if the contract is what is wrong — `run` then gates and reviews afresh, against the amended contract"
              "bb run-loop retry <run-dir> <role> <triage.edn>          ; the decision is written first"
              "bb run-loop check <run-dir>                              ; or disagree with the review: gates and a fresh one"
              "bb run-loop run <run-dir>                                ; after any of them, the loop carries on"]
   :no-verdict ["read the findings above; the loop could not. Then one of:"
                "bb run-loop check <run-dir>                       ; gates and a fresh review, which may give one"
                "bb run-loop retry <run-dir> <role> <triage.edn>   ; if the findings name a defect"
                "bb run-loop run <run-dir>                         ; after either"]
   :merged ["nothing: the record is run.edn, the worktrees and the task branches are gone, the merge commit holds the work"]})

(defn owner
  "Who a stop is for. THE ARCHITECT'S: a stop whose answer is an amendment to the
  contract — the spec review's list, and any stop triage routed `architect`. The
  Architect is the seat, so a session driving the loop handles these itself: read,
  `amend` (before and after are recorded) or leave it, carry on. THE PERSON'S:
  everything else — the cap, a missing verdict, a merge, a `human` route, a dead
  REPL, a refused provider, a spec that keeps drawing findings. The line between
  them is what lets a run be automated without a person reading every stop, and
  still stop for the ones only a person should decide."
  [{:keys [stop/kind route]}]
  (if (or (= :architect route) (= :spec-reviewed kind)) :architect :person))

(defn cut-off-writers
  "The Coder's and the Tester's LATEST dispatches that ended at the iteration
  cap having written their file: `[{:step kw :files [...] :iterations n}]`.

  A CAPPED DISPATCH THAT WROTE IS `:done`, AND NOTHING ELSE SAID IT WAS CUT OFF.
  Capped-and-wrote-nothing is a failed dispatch and stops the loop. Capped after
  writing carries on to the gates, which is right - the file may be complete -
  but the cap is then one field on one console line, and the person deciding
  the merge was never told the author had been stopped mid-task. One project
  merged thirteen runs and learned only from counting its records afterwards
  that two Testers had ended this way. The `targets` gate catches a target with
  no test; it does not catch a test cut short inside one. Only the latest
  dispatch of each role counts: a retry that finished replaces the file."
  [events]
  (vec (for [role [:coder :tester]
             :let [d (last (filter #(and (= :dispatch (:event/kind %)) (= role (:role %))) events))]
             :when (and (:capped? d) (= :done (:status d)))]
         {:step (:event/step d) :files (:files d) :iterations (:iterations d)})))

(defn reviews-bought
  "How many Reviewer dispatches this run has paid for, and what they cost:
  `{:count n :cost total :cost-known k}` - the count from the dispatch events
  with the Reviewer's role, the cost from the report steps of the same names
  (`:cost-known` says how many of those had a figure).

  THE CAP COUNTS ROUNDS, NOT REVIEWS. A `check` after a rule-source or
  Blueprint change buys a fresh review each time and none of them is a round,
  so one run took five reviews for most of its money and nothing said so
  (register row 16). Said at every stop that follows a review, so the person
  deciding the next `check` knows what the last ones cost."
  [events steps]
  (let [names (into #{} (comp (filter #(and (= :dispatch (:event/kind %)) (= :reviewer (:role %))))
                              (map :event/step))
                    events)
        costs (keep :step/cost (filter #(contains? names (:step/name %)) steps))]
    {:count (count (filter #(and (= :dispatch (:event/kind %)) (= :reviewer (:role %))) events))
     :cost (reduce + 0 costs)
     :cost-known (count costs)}))

(defn- announce! [{:keys [stop/kind route reason] :as action} run-dir {:keys [events steps]}]
  (println (str "\n  ══ STOP · " (name kind) " ══"))
  (println (str "  " reason))
  (println (str "  whose: " (name route) " · owner: " (name (owner action))))
  (let [{:keys [count cost cost-known]} (reviews-bought events steps)]
    (when (pos? count)
      (println (str "  reviews this run has bought: " count
                    (if (pos? cost-known) (format " ($%.2f)" (double cost)) " (cost not yet known)")
                    " — every `check` after a change buys another, and none counts against the cap"))))
  (doseq [{:keys [step files iterations]} (cut-off-writers events)]
    (println (str "  NOTE: " (name step) " ended at its iteration cap (" iterations " turns) after writing "
                  (str/join ", " files) " — the gates ran over what it had written; read the end of that file")))
  (doseq [line (get next-steps kind ["bb run-loop record <run-dir>"])]
    (println (str "    " (str/replace line "<run-dir>" run-dir)))))

;; ---------------------------------------------------------------------------
;; triage: ask, record, and act on the answer
;; ---------------------------------------------------------------------------

(defn- trigger
  "What triage is shown, from the run: the effective spec, the dependents,
  both role worktrees (the files AS WRITTEN — gate 0 has not touched them),
  and the trigger's own payload."
  [ctx kind]
  (let [state @(:state ctx)
        s (:sessions state)
        events (:events state)]
    {:trigger kind
     :spec (driver/spec-of ctx)
     :dependents (:dependents state)
     :worktrees {:coder (:worktree/path (:coder s)) :tester (:worktree/path (:tester s))}
     :payload (case kind
                :red-gate {:gate-result (:last-gates state)}
                :note (select-keys (last (filter #(= :paused (:event/kind %)) events))
                                   [:after :next :notes])
                ;; THE NOTES TOO, not only the verdict. A Reviewer's note is
                ;; about the contract, and a rejection whose real finding is
                ;; "the targets do not say" is the architect's — which triage
                ;; can only see if it is shown the note.
                :rejection (let [r (current-review events)]
                             {:verdict (:verdict r) :findings (:stdout r) :notes (:notes r)}))}))

(defn triage-step-name
  "`:triage` for the first call, `:triage-r1` after that — the same shape as
  the roles' step names, so a report reads the rounds off the names."
  [prior]
  (if (zero? prior) :triage (keyword (str "triage-r" prior))))

(defn- on-event
  "What a triage verdict leaves on the event that acts on it — the retry's
  `:triage` event, the `:continued` event, or the `:stopped` event."
  [v]
  (cond-> {:by (if (:triage/fallback v) :fallback :model)
           :route (:route v)
           :reason (:reason v)
           :guidance (:guidance v)}
    (:routed v) (assoc :routed (:routed v))
    (:triage/fallback v) (assoc :triage/fallback (:triage/fallback v))
    (:proposal v) (assoc :proposal (:proposal v))
    (:cost (:result v)) (assoc :cost (:cost (:result v)))
    (:model (:runner/meta (:result v))) (assoc :model (:model (:runner/meta (:result v))))
    (:answer v) (assoc :answer (first (driver/cap-transcript [(:answer v)])))))

(defn- record-call!
  "The triage call as a report step, when there was one: a constant
  triage-fn in a test measures nothing and leaves no step. The prompt and
  the answer go in the run directory whole, as a dispatch's packet and
  transcript do."
  [ctx v]
  (when (:prompt v)
    (let [prior (count (filter #(str/starts-with? (name (:step/name %)) "triage") (:steps @(:state ctx))))
          nm (triage-step-name prior)]
      (spit (str (:run-dir ctx) "/" (name nm) ".prompt.txt") (:prompt v))
      (when (:answer v) (spit (str (:run-dir ctx) "/" (name nm) ".answer.txt") (:answer v)))
      (when (:ms v)
        (driver/step! ctx (report/dispatch-step nm (:result v) (:ms v)))))))

(defn- say! [kind v]
  (println (str "\n  triage (" (name kind) "): → " (name (:route v))
                (when (:routed v) (str " (routed " (name (:routed v)) ", shielded)"))
                (when (:triage/fallback v) (str "  [fallback: " (:triage/fallback v) "]"))
                (when-let [c (:cost (:result v))] (str "  cost=" c))))
  (println (str "    " (:reason v)))
  (when (:guidance v) (println (str "    guidance: " (:guidance v)))))

(defn- triage!
  "Ask the triage-fn about `kind`, record the call, and act: a role is
  dispatched through the same `retry-with!` a person's `retry` uses, a
  `continue` goes through `continue-with!`, and anything else is returned as
  the stop to record. Returns nil when it dispatched, so the loop carries on."
  [ctx deps kind]
  (let [trg (trigger ctx kind)
        f (or (:triage-fn deps) (triage/model-triage (:profile @(:state ctx))))
        v (f trg)
        feedback (when (#{:coder :tester} (:route v)) (triage/feedback-for v trg))
        v (if (= :tester (:route v))
            (triage/shield v (:spec trg) (driver/impl-names ctx) feedback)
            v)]
    (record-call! ctx v)
    (say! kind v)
    (case (:route v)
      (:coder :tester)
      (do (driver/retry-with! ctx (:route v) {:decision (:reason v) :feedback feedback} false (on-event v))
          nil)

      :continue
      (do (driver/continue-with! ctx (:reason v) (on-event v))
          nil)

      ;; THE MACHINE'S, NOT THE RUN'S. Named by the proposal's foreign namespaces on a
      ;; red gate; by the model's reason alone on a note, where nothing mechanical read
      ;; the file. A person fixes it outside this run and carries on with the commands.
      :tooling
      (let [foreign (when (= :red-gate kind)
                      (:foreign-namespaces (driver/propose-routing (:spec trg) (:dependents trg)
                                                                   (:gate-result (:payload trg)))))]
        (assoc (stop :tooling :tooling
                     (str "triage: " (:reason v)
                          (when (seq foreign) (str " — failing outside this run: " (str/join ", " foreign)))))
               :triage (on-event v)))

      ;; :architect, :human — and the Tester shielded to :human
      (assoc (stop (if (:routed v) :tester-leak (case kind :note :paused :red-gate :red-gate :rejection :rejected))
                   (:route v)
                   (str "triage: " (:reason v)))
             :triage (on-event v)))))

;; ---------------------------------------------------------------------------
;; the loop
;; ---------------------------------------------------------------------------

(def max-actions
  "How many actions one `run` may take before it refuses to take another.

  Not a policy — the retry cap is the policy. This is the insurance against a
  `next-action` that returns a step which does not change the state it reads,
  which is a loop that spends money forever. It is deliberately far above any
  real run: three rounds of two dispatches, a triage and a gate run is nowhere
  near it."
  40)

(defn run-task!
  "Drive the run in `run-dir` to its next stop, and return what that stop was.

  `{:status :escalated|:awaiting-merge :stop/kind _ :route _ :reason _
    :attempts _ :cost _}` — `:status` is the `RunStatus` the record will carry,
  so `record` and this agree by construction.

  RE-ENTRANT, on purpose. Every stop leaves the run exactly where it is, with
  its worktrees and its state; a person amends, retries or continues with the
  commands, and runs this again. That is what makes the loop's routing and a
  person's routing the same mechanism rather than two — and it is why the retry
  cap counts rounds recorded in the state rather than iterations of this
  function, and why a triage verdict is acted on through the same `retry-with!`
  a typed decision goes through."
  ([run-dir] (run-task! run-dir nil))
  ([run-dir deps]
   (let [ctx (merge (driver/context run-dir) (select-keys deps [:repl-probe]))
         finish! (fn [action]
                   (let [state @(:state ctx)]
                     (driver/event! ctx (assoc (select-keys action [:stop/kind :route :reason :run/status :triage])
                                               :event/kind :stopped))
                     (announce! action run-dir state)
                     {:status (:run/status action)
                      :stop/kind (:stop/kind action)
                      :owner (owner action)
                      :route (:route action)
                      :reason (:reason action)
                      :attempts (driver/rounds state)
                      :cost (:cost (report/totals (:steps state)))}))]
     (loop [n 0]
       (when (> n max-actions)
         (throw (ex-info (str "the loop took " max-actions " actions without stopping")
                         {:run-loop/error :loop-guard :run-dir run-dir})))
       (let [action (next-action @(:state ctx))
             action (if (= :triage (:action action))
                      (triage! ctx deps (:trigger action))
                      action)]
         (cond
           ;; triage dispatched or continued; the state says what is next
           (nil? action) (recur (inc n))

           (= :stop (:action action)) (finish! action)

           :else
           (do
             (try
               (case (:action action)
                 :start (driver/start! ctx)
                 :continue (driver/continue! ctx)
                 :check (driver/check! ctx))
               (catch clojure.lang.ExceptionInfo e
                 ;; A refused dispatch is a stop, not a crash: the `:repl-dead`
                 ;; event is already recorded, and `next-action` reads it.
                 (when-not (= :repl-dead (:run-loop/error (ex-data e)))
                   (throw e))))
             (recur (inc n)))))))))

(defn run-with-review!
  "`run-task!`, with the spec review's stop treated as what it is: the loop
  stopping for the Architect before anything was provisioned. There is no state
  yet and so no event; the review itself is beside spec.edn, and the next `run`
  records it."
  [run-dir]
  (try (run-task! run-dir)
       (catch clojure.lang.ExceptionInfo e
         (case (:run-loop/error (ex-data e))
           ;; THE PLAN'S STOP, before the spec's: the workspace's plan still carries a mark, an
           ;; instruction, a placeholder, or an edit to a given part. The Architect's, like the
           ;; spec review's - the plan is that session's to fill.
           :plan-check
           (do (println (str "\n  ══ STOP · plan-check ══\n  " (ex-message e) "\n  owner: architect"))
               {:status :not-started :stop/kind :plan-check :owner :architect :problems (:problems (ex-data e))})
           :spec-reviewed
           (do (println (str "\n  ══ STOP · spec-reviewed ══\n  " (ex-message e) "\n  owner: architect"))
               {:status :not-started :stop/kind :spec-reviewed :owner :architect :findings (:findings (ex-data e))})
           :spec-review-limit
           (do (println (str "\n  ══ STOP · spec-review-limit ══\n  " (ex-message e) "\n  owner: person"))
               {:status :not-started :stop/kind :spec-review-limit :owner :person :reviews (:reviews (ex-data e))})
           (throw e)))))

(defn -main
  "`bb run-loop`'s entry point: `run` is the loop, everything else is a step."
  [& [cmd run-dir :as args]]
  (if-not (= "run" cmd)
    (apply driver/-main args)
    (if-not run-dir
      (do (println driver/usage) (System/exit 2))
      ;; The run's workspace is the command's - see `driver/-main`.
      (binding [workspace/*of-run* (workspace/find-workspace run-dir)]
        (try
        ;; MONEY BEFORE AND AFTER. The first project built on this kit was stopped by a
        ;; credit limit twice, mid-run, with nothing having said how much was left.
          (let [ctx (driver/context run-dir)
                prof (or (:profile @(:state ctx)) (profile/read-profile (:profile (:config ctx))))
                steps #(:steps @(:state (driver/context run-dir)))]
            (println (balance/line prof (steps)))
            (run-with-review! run-dir)
            (println (balance/line prof (steps))))
          (catch clojure.lang.ExceptionInfo e
            (println "run-loop:" (ex-message e))
            (System/exit 1)))))))
