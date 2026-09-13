;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/packet.clj @ e6b40ed (2026-07-10).
(ns harness.packet
  "Task-packet assembly.

  A *task spec* is one entry of the Architect's dependency-ordered task list:

    {:task/id          \"t-07-service-ops\"
     :task/title       \"Service ops: ...\"
     :blueprint/slice  {:shapes [...] :interfaces [...] :deps-sigs [...]}
     :files/impl       [\"src/app/service.clj\"]
     :files/test       [\"test/app/service_test.clj\"]
     :files/context    [\"src/app/store.clj\" ...]
     :layer/name       :service
     :property-targets [...]}                       ; optional

  A *session* is a provisioned workspace: {:worktree/path _ :nrepl/port _}.

  The assembler cuts one role-specific packet from a spec plus a session,
  and validates it. This namespace is small and boring on purpose — its
  reason to exist is the four lines in `tester-packet`."
  (:require
   [harness.shapes :as shapes]))

(def default-gates
  {:retry-cap 3})

(defn- validate! [packet]
  (if-let [errors (shapes/explain-packet packet)]
    (throw (ex-info "invalid task packet" {:errors errors
                                           :task/id (:task/id packet)
                                           :task/role (:task/role packet)}))
    packet))

(defn- base [spec session]
  (cond-> {:task/id (:task/id spec)
           :task/title (:task/title spec)
           :blueprint/slice (:blueprint/slice spec)
           :repl/worktree (:worktree/path session)
           :repl/port (:nrepl/port session)
           :layer/name (:layer/name spec)
           :gates (or (:gates spec) default-gates)}
    (seq (:harness/wrote session))
    (assoc :harness/wrote (vec (:harness/wrote session)))))

(defn coder-packet
  "Coder: :files/target = the impl file(s); context = the dependency files,
  read-only."
  [spec session]
  (validate!
   (assoc (base spec session)
          :task/role :coder
          :files/target (:files/impl spec)
          :files/context (vec (:files/context spec)))))

(defn tester-packet
  "Tester: :files/target = the test namespace, and context EXCLUDES the
  Coder's impl files even when the spec lists them.

  That exclusion is the whole point of this namespace. Tests derived from
  an implementation only re-assert what the code already does; tests
  derived from the contract catch where the code and the contract
  disagree. Enforcing it here rather than asking a Blueprint author to
  remember is the difference between an invariant and an intention."
  [spec session]
  (let [impl (set (:files/impl spec))]
    (validate!
     (cond-> (assoc (base spec session)
                    :task/role :tester
                    :files/target (:files/test spec)
                    :files/context (vec (remove impl (:files/context spec))))
       (:property-targets spec)
       (assoc :property-targets (:property-targets spec))))))

(defn reviewer-packet
  "Reviewer: diff + slice + the green gate report. Read-only — no target,
  no REPL port. The diff and gate report come from the loop, not the spec."
  [spec session diff gate-report]
  (validate!
   (-> (base spec session)
       (dissoc :repl/port)
       (assoc :task/role :reviewer
              :files/context (vec (:files/context spec))
              :review/diff diff
              :review/gate-report gate-report))))

(def max-feedback
  "Characters of one feedback item that reach a packet.

  A retry's reason is re-sent on EVERY turn of that dispatch, so an unclipped
  test-failure dump is a bill that compounds — the same reasoning as
  `harness.tools/max-output`, which clips tool results at 20,000 for exactly
  this, and which I did not think to apply one layer up when feedback was
  added. Smaller than that limit because several items can arrive at once and
  because a gate's first lines are the ones that say what failed."
  4000)

(defn- clip
  "`s` cut to `max-feedback`, announcing the cut.

  Announced rather than silent, for the reason `harness.tools` gives: a model
  that cannot tell it got half a gate report will reason confidently about the
  half it did not get."
  [s]
  (let [s (str s)]
    (if (<= (count s) max-feedback)
      s
      (str (subs s 0 max-feedback)
           "\n\n[truncated at " max-feedback " characters of " (count s) "]"))))

(defn for-retry
  "`packet` again, saying why and carrying what the last attempt learned.

  THIS IS THE HINGE BETWEEN TWO GAPS THAT LOOKED SEPARATE. A role reporting
  something the Blueprint is silent about (run 4) and a role being told why it
  is back (D4) point in opposite directions — out of the loop and into it —
  and neither is worth anything alone. A note that reaches nobody is a diary;
  a retry with no reason is the same dispatch twice. This carries both.

  `feedback` is a vector of `harness.shapes/Feedback`. Build gate output with
  `gate-feedback` and a sibling's notes with `notes-feedback`, or hand-roll
  one — the schema is three keys and the validation is real.

  Attempts start at 2, because the first dispatch is not a retry and an
  `:task/attempt 1` in a run log would invite the reader to look for the
  attempt before it."
  [packet attempt feedback]
  (when (< attempt 2)
    (throw (ex-info "a retry starts at attempt 2"
                    {:harness/error :not-a-retry :attempt attempt})))
  (when (empty? feedback)
    (throw (ex-info "a retry must say why — dispatching the same packet twice is not a retry"
                    {:harness/error :no-feedback :task/id (:task/id packet)})))
  (validate! (assoc packet
                    :task/attempt attempt
                    ;; Clipped HERE, where feedback enters the packet, rather than
                    ;; where it is rendered: the packet is the contract and goes in
                    ;; the run log, and an unbounded one is a problem for both. The
                    ;; full gate output is still in the GateResult the loop holds.
                    :task/feedback (mapv #(update % :feedback/text clip) feedback))))

(defn gate-feedback
  "A failed gate's own output as feedback.

  Takes the GateResult the gates returned rather than a string, because the
  failing gate's `:out` is the thing worth sending and picking it out by hand
  at every call site is how a retry ends up carrying the wrong gate."
  [gate-result]
  (into []
        (comp (filter #(= :fail (:status %)))
              (map (fn [{:keys [gate out]}]
                     {:feedback/from :gate
                      :feedback/text (str (name gate) " failed:\n" out)})))
        (:gates/report gate-result)))

(defn notes-feedback
  "Another role's notes as feedback, tagged with who left them."
  [from notes]
  (mapv (fn [t] {:feedback/from from :feedback/text t}) notes))
