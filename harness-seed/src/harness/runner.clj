;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/runner.clj @ 3dd2229 (2026-07-10).
(ns harness.runner
  "The AgentRunner seam.

  Agent invocation is the fiddliest, least-certain part of the loop — CLI
  flags, output formats, session resume, tool-call protocols, all of which
  change under you — so it sits behind a protocol.

  ManualRunner ships the *mechanics* end-to-end on day one: it prints the
  packet, pauses, and lets a human run the agent. That gets workspaces,
  packets, gates, triage and retry working and tested before you automate
  a single client, which is the opposite of the usual order and the reason
  to start here. Headless runners replace it one role at a time.

  Run every implementation you add through harness.runner-check."
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.agent :as agent]
   [harness.repair :as repair]
   [harness.rules :as rules]
   [harness.tools :as tools]))

(defprotocol AgentRunner
  (run-agent [runner role packet]
    "Dispatch one task packet to the agent playing `role`.
     Returns a harness.shapes/AgentResult:
       {:status :done|:failed :files [..] :stdout _ :cost _}
     plus an optional :runner/meta map for anything only this runner knows.

     `role` is redundant with (:task/role packet) and most implementations
     ignore it — it is kept because this signature survived four
     implementations and two months without changing, and a seam whose
     stability is its main credential is not worth tidying."))

(defn- prompt-loop [read-fn]
  (loop []
    (let [line (some-> (read-fn) str/trim str/lower-case)]
      (case line
        nil :failed                     ; EOF — treat as failure, don't spin
        "done" :done
        "fail" :failed
        (do (println "please type `done` or `fail`:")
            (recur))))))

(defrecord ManualRunner [read-fn eval-hint]
  AgentRunner
  (run-agent [_ role packet]
    (println "\n════ MANUAL DISPATCH ════")
    (println "Role:    " (name role))
    (println "Task:    " (:task/id packet) "—" (:task/title packet))
    (println "Worktree:" (:repl/worktree packet))
    (when-let [port (:repl/port packet)]
      (println "REPL:    " port (when eval-hint (eval-hint port))))
    (println "Packet:")
    (pp/pprint packet)
    (println "Run the agent yourself, then type `done` or `fail` + Enter.")
    (let [status (prompt-loop read-fn)]
      {:status status
       ;; a human's word, not a filesystem fact — see runner-check's
       ;; :files-exist, which ManualRunner legitimately cannot pass
       :files (if (= :done status) (vec (:files/target packet)) [])
       :stdout nil
       :cost nil})))

(def default-eval-hint
  "How to tell a human to reach the worktree's REPL. Swap it for your own
  bridge; it is a string, not a dependency."
  (fn [port] (str "(clj-nrepl-eval -p " port " \"<code>\")")))

(defn manual-runner
  "A ManualRunner reading from *in* (or a custom read-fn, for tests)."
  ([] (manual-runner read-line default-eval-hint))
  ([read-fn] (manual-runner read-fn default-eval-hint))
  ([read-fn eval-hint] (->ManualRunner read-fn eval-hint)))

;; ---------------------------------------------------------------------------
;; the API-backed runner
;; ---------------------------------------------------------------------------

(def deliverables
  "What each role is here to produce.

  THE PACKET DOES NOT SAY THIS, and the first real dispatch showed what that
  costs. Told only to \"work through it\", the Coder spent 24 turns and 227k
  tokens walking the filesystem looking for a `clamp` file that did not exist
  yet, and wrote nothing. The packet carries the CONTRACT — shapes, signatures,
  which files may be touched — and the rules carry the CONSTRAINTS. Neither
  says what finishing looks like, because in the manual runs a human knew."
  {:coder (str "Implement every signature in :blueprint/slice's :interfaces, "
               "satisfying every :property-targets entry your packet carries, and "
               "write the complete namespace to your :files/target with "
               "write_file. THE FILE MAY NOT EXIST YET — create it; do not go "
               "looking for it. If it already exists, :files/context also lists "
               "the files that require it, and they must keep working. "
               "Prototype in the REPL first, then write once.")
   ;; "THEN WRITE ONCE" IS THE LOAD-BEARING CLAUSE, and the Tester did not
   ;; have it. The Coder converged in five iterations on both tasks it was
   ;; given; the Tester churned to its cap on two — at 15 and again at 24 —
   ;; prototyping in the REPL and never persisting. `:repl-first` says to
   ;; prototype BEFORE persisting and says nothing about when to stop, which
   ;; reads as an unbounded invitation to a role with no other stop condition.
   :tester (str "Write tests derived from :blueprint/slice — the contract — and "
                "write them to your :files/target with write_file. There is no "
                "implementation to read and you must not go looking for one; "
                "that is what makes your tests independent. Prototype a case or "
                "two in the REPL to check your fixtures, then WRITE THE FILE — "
                "you cannot finish without calling write_file.")
   :reviewer (str "Report findings on the diff in :review/diff. You write "
                  "nothing and you have no REPL. Judge what the gates cannot: "
                  "design, idiom, logic, naming, edge cases — whether the "
                  "implementation honours every :property-targets entry, and "
                  "whether the files in :files/context that require it still "
                  "work with the change.")})

(defn packet-prompt
  "The opening message for a dispatched role.

  The packet VERBATIM — §06 makes it the context-passing contract, and
  paraphrasing it here would create a second, undeclared one that drifts from
  the schema — preceded by orientation and followed by the deliverable."
  [packet]
  (let [role (:task/role packet)
        ;; FIRST, not last. A retry reason buried under the packet is a retry
        ;; reason that competes with the packet for attention, and the whole
        ;; point of :task/feedback is that this attempt differs from the last.
        retry (when-let [fb (seq (:task/feedback packet))]
                (str "THIS IS ATTEMPT " (:task/attempt packet)
                     ". The previous attempt did not finish the task. Address"
                     " each of these, then do the work again:\n\n"
                     (str/join "\n\n"
                               (for [{:keys [feedback/from feedback/text]} fb]
                                 (str "— from the " (name from) ":\n" text)))
                     "\n\n"))]
    (str "You are the " (name role) " on task "
         (:task/id packet) " — " (:task/title packet) ".\n\n"
         retry
         "Your workspace is " (:repl/worktree packet)
         " and every path below is relative to it.\n\n"
         (get deliverables role) "\n\n"
         "Your task packet:\n\n"
         (with-out-str (pp/pprint packet))
         "\nWhen you are done, reply with a short summary: what you evaluated, "
         "what came back, and what you wrote.")))

(defn worktree-snapshot
  "Each file git reports changed or untracked in `dir`, mapped to its content —
  what is already on disk before a dispatch starts."
  [dir]
  (if dir
    (into {} (for [p (repair/changed-files dir)] [p (slurp (str (fs/path dir p)))]))
    {}))

(defn written-since
  "The paths in `after` whose content is new, or different from `before`; both
  are `worktree-snapshot`s.

  A RETRY STARTS IN A WORKTREE THAT ALREADY HOLDS THE LAST ATTEMPT'S FILE
  (NOTES.md row 18). git status reports that file whether or not this dispatch
  touched it, so D20's second Tester attempt, whose final message said no file
  was written, reported the first attempt's file and `:done`, and `check` ran
  over it unchanged. What a dispatch wrote is what changed while it ran."
  [before after]
  (vec (keep (fn [[p content]] (when (not= content (get before p)) p)) (sort after))))

(defn- outcome
  "An AgentResult from a finished conversation.

  `:files` COMES FROM GIT, never from the model's reply, and is only what changed
  while the dispatch ran (`written-since`). A model that says it
  wrote a file is making a claim; `repair/changed-files` is looking. The two
  disagree often enough — a refused write it did not notice, a file it meant to
  write and did not — and the loop feeds `:files` straight to gate 0.

  `:cost` is nil unless EVERY completion reported one. A partial sum presented
  as the cost of a dispatch is the same understatement the report's footer
  exists to prevent, one layer down; the partial goes in `:runner/meta` where
  it cannot be mistaken for the total."
  [role packet {:keys [status text steps calls turns iterations capped? error ms-provenance-wait retries]} cfg before]
  (let [;; The `note` tool writes nothing; its whole purpose is to be CAPTURED.
        ;; `converse!` already keeps every call it made, so collecting them is
        ;; a filter rather than new machinery.
        notes (into [] (comp (filter #(= "note" (:name %)))
                             (map #(get-in % [:args :text]))
                             (filter (complement str/blank?)))
                    calls)
        costs (keep :cost steps)
        complete? (and (seq steps) (= (count costs) (count steps)))
        ;; Minus what the HARNESS put there. git cannot tell a generated
        ;; stub from something the agent wrote, and run D4 had the Tester
        ;; report the stub as its own output.
        placed (set (:harness/wrote packet))
        files (if (or (= :reviewer role) (= :failed status))
                []
                (vec (remove placed (written-since before (worktree-snapshot (:repl/worktree packet))))))]
    (cond-> {:status status
             :files files
             :stdout text
             :cost (when complete? (reduce + costs))
             :runner/meta (cond-> {:model (some :model (reverse steps))
                                   :provider (some :provider (reverse steps))
                                   ;; Chosen like :provider, and needed beside it:
                                   ;; OpenAI's flex and standard endpoints both
                                   ;; report provider "OpenAI".
                                   :service-tier (some :service-tier (reverse steps))
                                   :tokens (when (seq steps) (reduce + 0 (keep :tokens steps)))
                                   :iterations iterations
                                   :capped? (boolean capped?)
                                   :completions (count steps)
                                   ;; Requests sent again on a transient error
                                   ;; (429, 5xx, no connection): a fact about
                                   ;; the endpoint the record keeps.
                                   :retries (or retries 0)
                                   :cost-known (count costs)
                                   :tool-calls (count calls)
                                   :generation-ids (vec (keep :generation-id steps))
                                   ;; Where the dispatch's time went. The three
                                   ;; do not sum to its wall time — the rest is
                                   ;; the harness's own work between them.
                                   :ms-completion (reduce + 0 (keep :ms/completion steps))
                                   :ms-provenance (reduce + 0 (keep :ms/provenance steps))
                                   ;; What the dispatch actually WAITED for them —
                                   ;; once, at the end, since the fetches run beside
                                   ;; the loop. :ms-provenance is their own summed
                                   ;; duration and no longer adds to wall time.
                                   :ms-provenance-wait (or ms-provenance-wait 0)
                                   :ms-tools (reduce + 0 (keep :ms calls))
                                   :reasoning-tokens (when-let [rs (seq (keep :reasoning-tokens steps))]
                                                       (reduce + rs))
                                   ;; Where the cost came from, and the counts
                                   ;; and rates behind it, so a record can
                                   ;; re-derive a list-price figure.
                                   :cost-source (cond (empty? costs) nil
                                                      (some #(= :list-price (:cost-source %)) steps) :list-price
                                                      :else :reported)
                                   :usage (when (seq steps)
                                            (reduce (fn [acc u] (merge-with + acc (into {} (filter (comp some? val)) u)))
                                                    {} (keep :usage steps)))}
                            (:pricing cfg) (assoc :pricing (:pricing cfg))
                            ;; What the model said and ran, per completion
                            ;; (NOTES.md row 8). Runner-specific, so it lives
                            ;; here and not on the closed result.
                            (seq turns) (assoc :transcript turns)
                            (not complete?) (assoc :cost-partial (when (seq costs) (reduce + costs)))
                            error (assoc :error error))}
      (seq notes) (assoc :notes notes))))

(defrecord ApiRunner [profile opts]
  AgentRunner
  (run-agent [_ role packet]
    ;; NOTHING ESCAPES. runner-check's first assertion is that a runner returns
    ;; rather than throws, and the reason is money: an uncaught exception after
    ;; four tool round-trips loses a run that has already been paid for.
    (try
      (if-let [cfg (get-in profile [:roles role])]
        (let [before (when-not (= :reviewer role) (worktree-snapshot (:repl/worktree packet)))]
          (outcome role packet
                   (agent/converse!
                    cfg
                    (rules/rule-block role (rules/prompt-substitutions
                                            {:repl-port (:repl/port packet)
                                             :layer (some-> (:layer/name packet) name)}))
                    (packet-prompt packet)
                    {:dir (:repl/worktree packet)
                     :targets (vec (:files/target packet))
                     :port (:repl/port packet)}
                    (assoc opts :tools (tools/for-role role)))
                   cfg
                   before))
        {:status :failed :files [] :cost nil
         :stdout (str "the profile has no " (name role) " role")
         :runner/meta {:error {:harness/error :no-such-role :role role}}})
      (catch Exception e
        {:status :failed :files [] :cost nil :stdout (ex-message e)
         :runner/meta {:error {:harness/error :dispatch-threw
                               :message (ex-message e)
                               :data (ex-data e)}}}))))

(defn api-runner
  "An AgentRunner that calls a model over HTTP, one per role from `profile`.

  The whole profile rather than one role, because `run-agent` is handed the
  role and the point of a profile is that each role gets its own family. One
  runner serves all three.

  `opts` reach `harness.agent/converse!` — `:max-iterations`, `:timeout-ms`."
  ([profile] (api-runner profile nil))
  ([profile opts] (->ApiRunner profile opts)))
