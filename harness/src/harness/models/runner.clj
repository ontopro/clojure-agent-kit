;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/runner.clj @ 3dd2229 (2026-07-10).
(ns harness.models.runner
  "The AgentRunner seam.

  Agent invocation is the fiddliest, least-certain part of the loop — CLI
  flags, output formats, session resume, tool-call protocols, all of which
  change under you — so it sits behind a protocol.

  ManualRunner ships the *mechanics* end-to-end on day one: it prints the
  packet, pauses, and lets a human run the agent. That gets workspaces,
  packets, gates, triage and retry working and tested before you automate
  a single client, which is the opposite of the usual order and the reason
  to start here. Headless runners replace it one role at a time.

  Run every implementation you add through harness.models.runner-check."
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.gates.repair :as repair]
   [harness.models.agent :as agent]
   [harness.models.tools :as tools]
   [harness.rules :as rules]))

(defprotocol AgentRunner
  (run-agent [runner role packet]
    "Dispatch one task packet to the agent playing `role`.
     Returns a harness.contract.shapes/AgentResult:
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
  {:coder (str "Implement every signature in :blueprint/slice's :interfaces — "
               "`(name [args])` is a function to write, `(name)` is a var to define — "
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
   ;;
   ;; AND IT POINTED AT THE WRONG KEY. Until 2026-09-16 this said "tests derived
   ;; from :blueprint/slice — the contract", and :blueprint/slice is shapes,
   ;; interfaces and :deps-sigs. The BEHAVIOURAL contract is :property-targets,
   ;; a separate packet key this instruction never named — while the Coder's,
   ;; four lines up, has always said "satisfying every :property-targets entry".
   ;; One Tester had all eight targets in its packet, wrote tests for six,
   ;; skipped the validate-once one and went green through every gate; mutation
   ;; found it afterwards, and a Reviewer dispatched at the same code did not
   ;; cover the gap either. Another model on the same task and the same targets
   ;; covered it — so the targets were enough for one model and not another,
   ;; which is why `harness.contract.targets` checks the markers rather than trusting
   ;; this paragraph.
   :tester (str "Write tests derived from the contract your packet carries: "
                ":blueprint/slice for the shapes and signatures, and "
                ":property-targets for the behaviour. EVERY :property-targets "
                "entry is yours — write at least one test for each, and mark it "
                "with a `;; target N` comment on the line directly above the "
                "deftest or defspec (`;; targets 1, 2` when one test covers "
                "several). If a target is no test's job — a dependency rule a "
                "gate already checks, say — write `;; target N — not a test: "
                "<why>` instead, anywhere in the file. A check after assembly "
                "reads those markers and fails the run on a target that is "
                "neither. Write the tests to your :files/target with "
                "write_file. There is no implementation to read and you must "
                "not go looking for one; that is what makes your tests "
                "independent. Prototype a case or two in the REPL to check your "
                "fixtures, then WRITE THE FILE — you cannot finish without "
                "calling write_file. "
                ;; WHEN TO STOP, which the sentence above never said. Two Testers
                ;; in a row spent their budget AFTER they had tests worth keeping:
                ;; one re-ran its written file against the stub, whose bodies
                ;; throw by design, until the cap; the next never wrote at all —
                ;; it pasted its whole file into `(read-string "...")` to check
                ;; the brackets and fought its own string escaping for twenty
                ;; turns, which is the job gate 0 does for free after assembly.
                ;; "Prototype a case or two" bounds the start and nothing bounded
                ;; the end.
                "Call write_file EARLY — within your first few turns — and treat "
                "the written file as finished. Do NOT check the file's brackets "
                "by evaluating it as a string or reading it back through the "
                "REPL: a repair step fixes delimiters and formatting after you "
                "finish. Do NOT expect your tests to pass: the functions under "
                "test are stubs that throw on purpose, so a test that errors "
                "against them is correct, and re-running it proves nothing. "
                ;; A THIRD WAY TO THE CAP, and the general form of all three. A Tester
                ;; was given a target it had nothing to test with - the fixture the
                ;; target needed did not exist - and made nineteen REPL calls searching
                ;; the classpath for one. It had a `note` tool and wrote no note and no
                ;; file: a failed dispatch, and in the end a whole run. Each earlier
                ;; fix answered the last cause; this says what to do when the cause is
                ;; one nobody has met yet.
                "IF A TARGET CANNOT BE TESTED WITH WHAT YOU WERE GIVEN - a fixture, "
                "a resource or a helper it needs is not in your packet or your "
                "worktree - do NOT go searching for it. Write the tests for every "
                "other target, mark that one `;; target N — not a test: <what is "
                "missing>`, and say the same with `note`: what is missing is the "
                "contract's to supply, and a note is how it finds out. The same "
                "holds for anything else that stops you: write what you have, "
                "then leave a note saying what stopped you. A file with a note "
                "is worth a round; a search that ends at the turn limit with "
                "neither is worth nothing.")
   :reviewer (str "Report findings on the diff in :review/diff. You write "
                  "nothing and you have no REPL. Judge what the gates cannot: "
                  "design, idiom, logic, naming, edge cases — whether the "
                  "implementation honours every :property-targets entry, and "
                  "whether the files in :files/context that require it still "
                  "work with the change. "
                  ;; THE VERDICT IS A BLOCK, NOT A TONE. The findings are prose
                  ;; for a person; nothing can route on prose, so a review that
                  ;; read as a rejection and one that read as an approval left
                  ;; the loop in the same place. `reject` is reserved for what
                  ;; the contract rules out, because a rejection costs a paid
                  ;; round: a Reviewer that rejects on taste spends the cap.
                  ;;
                  ;; EVERY REASON NAMES WHAT IT BREAKS. The definition of `reject`
                  ;; below was in this text from the start and did not hold: one
                  ;; Reviewer rejected four times in eight tasks on findings that
                  ;; broke no target — a rule read strictly, a double validation
                  ;; pass, a broad `catch` — each true, each labelled blocking,
                  ;; and one task went to its raised cap on three reviews that each
                  ;; found something new. A definition in prose asks the model to
                  ;; remember it; a field makes the model APPLY it, per finding,
                  ;; and notice when the answer is "none".
                  "AFTER your findings, end your reply with exactly one fenced "
                  "JSON block giving your verdict:\n"
                  "```json\n"
                  "{\"verdict\": \"approve\" | \"reject\",\n"
                  " \"reasons\": [{\"finding\": \"...\", \"breaks\": \"target 3\" | \"slice\" | \"dependent\" | \"none\"}]}\n"
                  "```\n"
                  "For EACH finding say what it breaks: the number of the "
                  ":property-targets entry it violates (\"target 3\"), \"slice\" "
                  "when the code departs from :blueprint/slice, \"dependent\" "
                  "when a file in :files/context that requires it stops working, "
                  "or \"none\". `reject` means the change must not merge as it "
                  "stands, and ONLY a finding that breaks a target, the slice or "
                  "a dependent can justify it. A finding that breaks \"none\" — "
                  "robustness, idiom, a rule read strictly, an edge case no "
                  "target covers — is worth reporting and is reported under "
                  "`approve`: the person who merges reads it, and it costs no "
                  "paid round. If every finding you have breaks \"none\", your "
                  "verdict is `approve`. An approval with no findings has no "
                  "reasons. "
                  ;; A VALUE THAT CANNOT ARRIVE BREAKS NOTHING. One Reviewer rejected on
                  ;; `(keyword "")` - valid by its type, and impossible to write in the
                  ;; EDN file that was the project's only source of content. Triage routed
                  ;; the Coder, a round was paid, and the Architect - the only role that
                  ;; had been given the question *can ordinary input reach it?* - was never
                  ;; asked. NARROW ON PURPOSE: twice an Architect waved away a finding
                  ;; ordinary input COULD reach, so a role's own sense that an input is
                  ;; unlikely does not count. Only a source the rules name does.
                  "ONE MORE CASE BREAKS \"none\": a finding about an input that "
                  "cannot occur. The type decides what a value IS; where the "
                  "project's rules say its input COMES FROM decides whether a value "
                  "can OCCUR. An input cannot occur ONLY when those rules name the "
                  "source and that source cannot produce the value - never on your "
                  "own sense that an input is unlikely. Where the rules name no "
                  "source, every value the type admits can arrive. Report such a "
                  "finding, say which sentence of the rules puts it out of reach, "
                  "and mark it \"none\".")})

(defn review-verdict
  "The Reviewer's verdict from its final message: `{:verdict :approve|:reject
  :reasons [str ...]}`, or nil when there is no block, it does not parse, or
  its verdict is neither word.

  NIL IS AN ANSWER, NOT A FAILED DISPATCH. Upstream fails the dispatch when the
  block is missing, which throws away findings that were paid for and are
  usually fine: the prose is the review, and the block is only how the LOOP
  reads it. So the dispatch stays `:done`, the findings stay in `:stdout`, and
  the loop stops for a person with \"the Reviewer gave no verdict\"."
  [text]
  (let [block (agent/last-json-block text)
        v (some-> (:verdict block) str str/trim str/lower-case)
        ;; A REASON IS `{finding, breaks}`, AND A BARE STRING STILL READS. The
        ;; block asks for the map; a model that answers in the older shape has
        ;; given a reason and no `breaks`, which is recorded as nil rather than
        ;; refused — a verdict thrown away over its shape is a paid review lost.
        reasons (for [r (let [rs (:reasons block)] (if (sequential? rs) rs [rs]))
                      :let [m (if (map? r) r {:finding r})
                            finding (some-> (:finding m) str str/trim)
                            breaks (some-> (:breaks m) str str/trim str/lower-case not-empty)]
                      :when (not (str/blank? finding))]
                  {:finding finding :breaks breaks})]
    (when (#{"approve" "reject"} v)
      (cond-> {:verdict (keyword v)
               ;; STRINGS, as before, for the console, triage's prompt and the
               ;; record's readers: the finding with what it breaks after it.
               :reasons (mapv (fn [{:keys [finding breaks]}]
                                (if breaks (str finding " [breaks: " breaks "]") finding))
                              reasons)}
        (some :breaks reasons) (assoc :breaks (mapv :breaks reasons))))))

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
         ;; ONE SENTENCE ON HOW TO CHANGE A FILE, for the roles that write. With
         ;; write_file alone a fix to one lint warning was a whole rewrite, and
         ;; the tool's description alone did not stop it: a model reaches for
         ;; the tool the deliverable names.
         (when (contains? (tools/for-role role) "edit_file")
           (str "Once a file is written, change it with edit_file — the exact text "
                "to replace and its replacement — rather than sending the whole file "
                "again.\n\n"))
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
  git status reports that file whether or not this dispatch touched it, so a
  Tester retry whose final message said no file was written reported the first
  attempt's file and `:done`, and `check` ran over it unchanged. What a dispatch wrote is what changed while it ran."
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
        ;; stub from something the agent wrote, and one early run had the
        ;; Tester report the stub as its own output.
        placed (set (:harness/wrote packet))
        files (if (or (= :reviewer role) (= :failed status))
                []
                (vec (remove placed (written-since before (worktree-snapshot (:repl/worktree packet))))))
        ;; CAPPED AND WROTE NOTHING IS A FAILED DISPATCH, for a role whose whole
        ;; job is a file. A capped loop that DID produce files passes as `:done`
        ;; — the gates decide whether the output was any good, and discarding
        ;; salvageable work is the wrong default. But a Tester that spent every
        ;; turn in the REPL and wrote no test is not a dispatch the loop should
        ;; carry on from: assembly refuses the missing deliverable one step
        ;; later, with nothing to say why, and a run has gone green over a
        ;; namespace with no tests at all because the absent file was skipped.
        ;; The Reviewer is exempt: it writes nothing by design.
        capped-empty? (and (#{:coder :tester} role) (= :done status) capped? (empty? files))
        status (if capped-empty? :failed status)
        text (if capped-empty?
               (str "DISPATCH FAILED: the tool loop hit its iteration cap having written no file."
                    " What it said before the cap:\n\n" text)
               text)]
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
                            ;; The Reviewer's verdict, PRESENT EVEN WHEN NIL: a
                            ;; review that gave none is a fact the loop stops
                            ;; on, and an absent key would read the same as a
                            ;; role that was never asked for one.
                            (= :reviewer role) (assoc :verdict (when (= :done status)
                                                                 (review-verdict text)))
                            ;; What the model said and ran, per completion
                            ;; Runner-specific, so it lives here and not on
                            ;; the closed result.
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
                    ;; THE ROUNDS LIMIT IS THE ROLE'S, when the profile sets one. The loop
                    ;; builds this runner with no options, so until the profile carried the
                    ;; number nothing in a project reached `converse!`'s cap: a Tester on a
                    ;; contract of fifty targets ended on the harness's default three times.
                    (cond-> (assoc opts :tools (tools/for-role role))
                      (:max-rounds cfg) (assoc :max-iterations (:max-rounds cfg))))
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

  `opts` reach `harness.models.agent/converse!` — `:max-iterations`, `:timeout-ms`."
  ([profile] (api-runner profile nil))
  ([profile opts] (->ApiRunner profile opts)))
