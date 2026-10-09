;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Reshaped from src/thub/harness/triage_model.clj @ 5df04ad (2026-09-05).
(ns harness.loop.triage
  "Who owns a failure: one model call — on a red gate, a note, or a review
  that rejected — answering `{route reason guidance}`.

  WHAT A MECHANICAL PROPOSAL CANNOT DO. `driver/propose-routing` reads the
  failing gate's output against the spec's file ownership, and it has to
  assume something — that a failure in the task's own test is the Coder's.
  It cannot judge WHOSE reading of the contract is wrong. A model shown the
  slice, the targets, the failing output and both roles' files as written can
  tell a faithful implementation from a test whose generator violates the
  contract's own preconditions, and can say that the contract itself is the
  problem — which is the route no rule ever proposes.

  THE ROUTE IS DISPATCHED ON, from the first run. The safety is not a person
  reading every verdict: it is that `architect` and `human` STOP the loop, that
  the retry cap still counts rounds, and that guidance bound for the Tester
  goes through the same leak check a hand `retry tester` does. A wrong route
  costs one paid attempt, not a merge.

  DEGRADES, NEVER BLOCKS (method §10). Any failure of the call — an API error,
  an answer with no JSON block, a route the trigger does not offer — falls back
  to the mechanical proposal on a red gate and to a person on a note or a
  rejection, and the record says which and why in `:triage/fallback`.

  THE ORCHESTRATOR IS THE PROFILE'S FOURTH ROLE, reached over HTTP like the
  other three, with no tools: it reads what the prompt carries and nothing
  else, so a triage verdict is reproducible from the prompt in the run
  directory. Upstream shelled out to a CLI and scraped its JSON."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [harness.contract.packet :as packet]
   [harness.loop.driver :as driver]
   [harness.models.agent :as agent]
   [harness.rules :as rules]))

;; ---------------------------------------------------------------------------
;; pure: the verdict
;; ---------------------------------------------------------------------------

(defn parse-verdict
  "The LAST fenced ```json block in `text` as a map, or nil — see
  `agent/last-json-block`, which is where the parser lives: the Reviewer's
  verdict is read the same way, by a namespace this one sits above."
  [text]
  (agent/last-json-block text))

(defn open-routes
  "The routes a trigger offers. A red gate goes to whoever owns it. A note
  can also be CONTINUED — it was an observation, and the run stands — and
  the only role it can send back is the one that left it: the other role
  either has not been dispatched yet or was not the one talking. A
  rejected review offers what a red gate does: the gates were green, so the
  finding is something no gate sees, and any of the four may own it.

  AND `tooling`, WHERE THE FAULT CAN BE THE MACHINE'S. On a note always: a
  Coder once used `note` to report a harness defect, triage `continue`d,
  rightly - the contract was fine - and the defect cost the next role a third
  of its turns, because nothing could say *fix the machine before the next
  dispatch*. On a red gate only when the driver's proposal says the failing
  namespaces are nobody's in the run: offering it on every red gate would
  hand the model an exit from every hard call. A rejection never offers it -
  the gates were green.

  A SECURITY FINDING offers three: the stage is merged and no Tester is in a run, so
  `coder` here is a fix packet drafted for the Architect to sign, not a dispatch;
  `architect` the contract or the authorisation model; `human` a risk the owner
  accepts or declines in the threat model."
  [{:keys [trigger payload spec dependents]}]
  (case trigger
    :security-finding #{:coder :architect :human}
    :rejection #{:coder :tester :architect :human}
    :red-gate (cond-> #{:coder :tester :architect :human}
                (= :tooling (:owner (driver/propose-routing spec dependents (:gate-result payload))))
                (conj :tooling))
    :note (cond-> #{:continue :architect :tooling :human}
            (#{:coder :tester} (:after payload)) (conj (:after payload)))))

(defn verdict
  "`parsed`, the JSON block as a map, as `{:route kw :reason str :guidance
  str|nil}` — or `{:triage/fallback why}` when it is not a verdict the
  trigger can act on. A security finding routed `coder` also keeps `:impl`
  (the one file a fix changes) and `:target` (the promise it must keep),
  which the fix packet's draft is written from. Pure, so the shape of a
  refusal is testable without a model."
  [trigger parsed]
  (let [route (some-> (:route parsed) str str/trim str/lower-case not-empty keyword)
        text (fn [k] (not-empty (str/trim (str (get parsed k)))))]
    (cond
      (nil? parsed) {:triage/fallback "no JSON verdict in the answer"}
      (nil? route) {:triage/fallback "the verdict names no route"}
      (not (contains? (open-routes trigger) route))
      {:triage/fallback (str "route " (name route) " is not open on this "
                             (name (:trigger trigger)) " — one of "
                             (str/join ", " (sort (map name (open-routes trigger)))))}
      :else (cond-> {:route route
                     :reason (str/trim (str (:reason parsed)))
                     :guidance (text :guidance)}
              (and (= :security-finding (:trigger trigger)) (= :coder route))
              (assoc :impl (text :impl) :target (text :target))))))

;; ---------------------------------------------------------------------------
;; pure: the prompt
;; ---------------------------------------------------------------------------

(def max-file-chars
  "Characters of each role's file the prompt carries. A triage that reads a
  whole 30k namespace to route a one-line failure is paying for context it
  does not use; the failing output says where to look."
  8000)

(def max-output-chars
  "Characters of the failing gate's output. The first lines say what failed;
  a test suite's full dump repeats itself."
  6000)

(def head-chars
  "Of `max-output-chars`, how many come from the START of a long output; the
  rest come from its END. A test runner prints its failure report and summary
  LAST, after every line of progress; a clip that kept only the head handed
  triage the progress and not the report, on three red gates of one project."
  1500)

(defn clipped
  "`s` cut to at most `n` characters: its first `head-chars` and its last
  `(- n head-chars)`, with a marker saying how much was left out between them.
  Public because the shape of the cut is a contract with whoever reads it."
  [s n]
  (let [s (str s)
        total (count s)]
    (if (<= total n)
      s
      (let [head (min head-chars n)
            tail (- n head)]
        (str (subs s 0 head)
             "\n;; [" (- total head tail) " of " total " characters omitted here — the head and the tail are kept]\n"
             (subs s (- total tail)))))))

(defn- file-section [worktree label paths]
  (str/join "\n"
            (for [rel paths
                  :let [f (when worktree (fs/path worktree rel))]]
              (str "--- " label ": " rel " ---\n"
                   (if (and f (fs/exists? f))
                     (clipped (slurp (str f)) max-file-chars)
                     "[not on disk — this role has not written it]")))))

(defn- failing-output [gate-result]
  (->> (:gates/report gate-result)
       (filter #(= :fail (:status %)))
       (map (fn [{:keys [gate out]}] (str "[" (name gate) "]\n" out)))
       (str/join "\n")))

(def ^:private route-lines
  {:coder "- coder: the implementation is at fault — the tests faithfully encode the contract"
   :tester "- tester: the tests are at fault — they misread the contract, or the failure is local to the test file"
   :architect (str "- architect: the contract itself is flawed, ambiguous or unimplementable; a person amends it. "
                   "An ambiguous contract is FIXED, not interpreted: a role that resolves an ambiguity, however "
                   "cheaply it could, has interpreted it once, and the next role interprets it again")
   :tooling (str "- tooling: the fault is the machine's, not this run's — a merged file no role here owns, the "
                 "build, the harness itself — and no dispatch of this run can fix it; the loop stops and names it. "
                 "Prefer this to human when that is what you see")
   :human "- human: none of the above fits — a systemic problem, or you cannot tell"
   :continue "- continue: the note is an observation; the contract stands and the run carries on unchanged"})

(defn data-conventions
  "The project's `:data-conventions` rule text, once the project has FILLED it;
  nil while the placeholder stands. Triage is otherwise shown no rules - it reads
  a contract, it does not write - but whether a rejected input can occur is
  decided by where the project says its input comes from, and that is said here
  and nowhere else."
  ([] (data-conventions (rules/load-rules)))
  ([rs]
   (let [r (first (filter #(= :data-conventions (:id %)) rs))]
     (when (and r (empty? (rules/unfilled [r])))
       (:text r)))))

(defn- trigger-section
  [{:keys [trigger payload]}]
  (case trigger
    :red-gate
    (str "WHAT HAPPENED: the files were assembled and a quality gate failed.\n"
         "Failed gate: " (pr-str (:gates/failed (:gate-result payload))) "\n"
         "Failing gate output:\n"
         (clipped (failing-output (:gate-result payload)) max-output-chars))

    :note
    (let [{:keys [after next notes]} payload]
      (str "WHAT HAPPENED: the " (name after) " finished and left "
           (count notes) " note(s) about the contract, and the loop paused before "
           (case next :tester "dispatching the Tester" :check "running the gates" (str next)) ".\n"
           "A note is a role saying something about the CONTRACT that someone must decide "
           "on before the next dispatch builds on it.\n"
           "The " (name after) "'s note(s):\n"
           (str/join "\n" (map #(str "- " %) notes))))

    :rejection
    (let [{:keys [verdict findings notes]} payload]
      (str "WHAT HAPPENED: every gate went GREEN — format, lint, the Tester's own tests, the "
           "layer check — and the Reviewer, who read the assembled diff, REJECTED the change.\n"
           "The tests pass, so whatever is wrong is something they do not catch. A defect in "
           "the implementation that the contract rules out is the coder's; a test that is "
           "wrong, cannot fail, or misses a case the targets require is the tester's; a "
           "finding about the contract itself is the architect's; and a finding you judge "
           "mistaken, or a matter of taste the contract does not decide, is human — a person "
           "reads the review and decides, and nothing is dispatched.\n"
           ;; THE QUESTION ONLY THE ARCHITECT HAD BEEN GIVEN. A rejection on a value the
           ;; project's one input format cannot express was routed `coder`, and a round was paid
           ;; for code to handle what can never arrive. The Architect decides whether to state
           ;; the domain or leave the finding; a Coder sent to absorb it has been given the
           ;; Architect's decision to make.
           "BEFORE YOU ROUTE A ROLE, ask whether the input the finding turns on CAN OCCUR. The "
           "type decides what a value is; where the project says its input comes from decides "
           "whether it can arrive. A finding about a value that source cannot produce is the "
           "architect's - who states the domain or leaves the finding - and never the coder's: "
           "code hardened against an input that cannot arrive is a paid round for nothing. This "
           "holds ONLY where the conventions below name the source. Where they name none, or you "
           "are merely unsure the input is likely, every value the type admits can arrive, and "
           "you route as usual.\n"
           (if-let [dc (data-conventions)]
             (str "This project's data conventions, as every role was given them:\n" dc "\n")
             "This project has stated no data conventions, so no input is out of reach.\n")
           "The Reviewer's reasons for rejecting:\n"
           (str/join "\n" (map #(str "- " %) (:reasons verdict)))
           (when (seq notes)
             (str "\nThe Reviewer's note(s) about the contract:\n"
                  (str/join "\n" (map #(str "- " %) notes))))
           "\nThe Reviewer's findings, in full:\n"
           (clipped findings max-output-chars)
           "\n\nIF YOU ROUTE THE TESTER, your guidance is ALL it will be sent: the Reviewer "
           "read the implementation, so none of its text may reach the Tester. Say which "
           "target or which case of the contract is untested, in the contract's own words."))))

(def ^:private security-route-lines
  {:coder (str "- coder: the contract already requires what the test shows broken - the architecture's "
               "security sections or the stage's Blueprint say it - and the code departs from it. A fix "
               "packet is drafted from your answer for the Architect to sign; nothing is dispatched")
   :architect (str "- architect: the contract or the authorisation model is wrong or silent - a check no "
                   "target asked for, a rule placed on one route, method or path and not on another that "
                   "reaches the same data, an input the design never limited. The Architect amends the design")
   :human (str "- human: a risk for the owner to accept or decline, written into the threat model; or a "
               "finding you judge mistaken, which a person reads and decides")})

(defn- render-security-prompt
  [{:keys [payload]}]
  (let [{:keys [finding test-text files architecture]} payload]
    (str
     "You are the Orchestrator's triage step in a multi-agent Clojure build. A stage has ended, its "
     "packets merged, and the security reviewer - a model that read the merged application with tools - "
     "reported a finding and REPRODUCED it: a test of its own that fails on this code. Decide who acts next.\n\n"
     "Routes:\n"
     (str/join "\n" (map security-route-lines [:coder :architect :human]))
     "\n\n"
     ;; THE PERSON'S DECISION, 2026-10-08: a finding that reaches the security reviewer has passed
     ;; the per-task code reviews, the gates and the stage's tests, so it is rarely a slip a Coder
     ;; patches. Routing it straight to a fix task would hide the gap in the design it came through.
     "WHERE THIS FINDING CAME FROM matters to the route. Every packet of the stage passed its gates, "
     "its tests and a code reviewer's reading of its diff before it was merged, and the stage's own "
     "tests pass. A defect that survived all of that is usually not a slip in one function: it is a "
     "gap in the contract or the design - a check nobody asked for, or one placed where it does not "
     "cover every way in. Lean to architect for a missing or misplaced check. Route coder only when "
     "you can point to the sentence of the contract below that the code breaks.\n\n"
     (if-let [dc (data-conventions)]
       (str "This project's data conventions, as every role was given them:\n" dc "\n\n")
       "This project has stated no data conventions.\n\n")
     "The architecture's security sections (what the template gives, what this project chose, the threat model):\n"
     (if (str/blank? architecture) "[not given]" (clipped architecture max-output-chars))
     "\n\nThe finding:\n"
     "Title: " (:title finding) "\n"
     "Where: " (:where finding) "\n"
     "Why: " (:why finding) "\n\n"
     "--- the security reviewer's test: " (:test finding) " ---\n"
     (if (str/blank? test-text) "[not found]" (clipped test-text max-file-chars))
     "\n\n"
     (str/join "\n" (for [[rel text] files]
                      (str "--- " rel ", as merged ---\n"
                           (if text (clipped text max-file-chars) "[not in the application]"))))
     "\n\nIF YOU ROUTE CODER, name the one source file a fix changes, and the promise the fix must "
     "keep as one checkable sentence in the contract's own words. A Tester who has not seen the code "
     "will write a test from that sentence, so it may name routes, requests, responses and data, never "
     "a function, a variable or how the code is written.\n\n"
     "Reply with exactly one fenced JSON block and nothing else:\n"
     "```json\n"
     "{\"route\": \"architect|coder|human\","
     " \"reason\": \"one sentence\","
     " \"guidance\": \"for the Architect or the person: what is wrong in the design or what the risk is, <= 120 words\","
     " \"impl\": \"coder only: the source file a fix changes\","
     " \"target\": \"coder only: the promise the fix must keep, one checkable sentence\"}\n"
     "```")))

(defn- render-task-prompt
  "The triage prompt for a trigger inside a run:

    {:trigger    :red-gate | :note | :rejection
     :spec       the effective task spec
     :dependents files that require the implementation (no role owns them)
     :worktrees  {:coder path :tester path} — the files AS WRITTEN, before gate 0
     :payload    :red-gate → {:gate-result GateResult}
                 :note     → {:after role :next :tester|:check :notes [str]}
                 :rejection → {:verdict {:verdict :reject :reasons [str]}
                               :findings str :notes [str]}}

  Everything the verdict rests on is in this string, and the string is in the
  run directory, so a routing can be re-read without re-asking."
  [{:keys [trigger spec dependents worktrees payload] :as trg}]
  (let [routes (open-routes trg)
        noting (when (= :note trigger) (:after payload))]
    (str
     "You are the Orchestrator's triage step in a multi-agent Clojure build loop. "
     "A Coder wrote the implementation and a Tester wrote the tests, each from the "
     "same Blueprint slice and property targets, in separate worktrees, and neither "
     "saw the other's file. Decide who must act next.\n\n"
     "Routes:\n"
     (str/join "\n" (for [r [:coder :tester :architect :tooling :human :continue] :when (routes r)]
                      (route-lines r)))
     "\n"
     (when noting
       (str "Only the " (name noting) " may be routed on its own note; the other role is not the one talking.\n"))
     "\nThe Blueprint slice is the single source of truth: when the tests and the "
     "implementation disagree, route to whichever DIVERGES FROM THE SLICE. The property "
     "targets are the contract's behaviour; a target the slice cannot satisfy, or two "
     "targets that contradict each other, is the architect's.\n\n"
     "GUIDANCE FOR THE TESTER MAY NAME ONLY THE CONTRACT. The Tester has not seen the "
     "implementation and must not: never name an implementation file, a var the slice "
     "does not grant, or paste a code block. Such guidance is refused and the run stops. "
     ;; NAMES ARE WHAT THE LEAK CHECK CAN SEE; FACTS ARE WHAT IT CANNOT. A triage
     ;; verdict once told a Tester to assert that an element "has exactly three
     ;; elements". No target said so — an attribute map would have made it four —
     ;; it was how the Coder had written it, which triage reads and the Tester
     ;; must not. It named no file and no var, passed the check, and went into a
     ;; merged test. Nothing mechanical catches a derived fact, so the prompt asks.
     "And never DESCRIBE the implementation either: you can see how the Coder wrote it, "
     "the Tester cannot, and a test must hold for EVERY implementation the targets allow. "
     "Tell the Tester what a target requires, in the target's own terms — never a count, "
     "a position, an ordering or a structure that you know only from reading the code.\n\n"
     "Task: " (:task/id spec) " — " (:task/title spec) "\n"
     "Blueprint slice (contract): " (pr-str (:blueprint/slice spec)) "\n"
     "Property targets: " (pr-str (:property-targets spec)) "\n"
     "Impl files (the Coder's): " (pr-str (:files/impl spec)) "\n"
     "Test files (the Tester's): " (pr-str (:files/test spec)) "\n"
     "Files that require the implementation (dependents; no role may edit them): "
     (pr-str (vec dependents)) "\n\n"
     (trigger-section trg) "\n\n"
     (file-section (:coder worktrees) "impl, as written" (:files/impl spec)) "\n"
     (file-section (:tester worktrees) "test, as written" (:files/test spec)) "\n\n"
     "Reply with exactly one fenced JSON block and nothing else:\n"
     "```json\n"
     "{\"route\": \"" (str/join "|" (sort (map name routes))) "\","
     " \"reason\": \"one sentence\","
     " \"guidance\": \"concrete, actionable instructions for the routed role, <= 120 words;"
     " empty for architect, tooling, human or continue\"}\n"
     "```")))

(defn render-prompt
  "The whole triage prompt for one trigger: a run's red gate, note or rejection (`render-task-prompt`
  says their shape), or a security finding at a stage's end:

    {:trigger :security-finding
     :payload {:finding      {:title :where :why :test}  ; as the security review's record has it
               :test-text    the security reviewer's test
               :files        [[path text-or-nil]]       ; the files the finding names, as merged
               :architecture the architecture's security sections}}

  Everything the verdict rests on is in this string."
  [trg]
  (if (= :security-finding (:trigger trg))
    (render-security-prompt trg)
    (render-task-prompt trg)))

(def system-prompt
  "Minimal on purpose: the rules are for roles that write, and this one reads
  a prompt and answers with a block. The rule block's placeholders assume a
  REPL port it does not have."
  (str "You are a triage step in an automated Clojure build loop. You have no tools "
       "and write nothing. Read what you are given and answer with exactly one fenced "
       "JSON block, as the prompt asks.\n\n"
       ;; THE TYPE RULE, IN TRIAGE'S OWN VOICE. Every reader asked - two roles live, a blind judge,
       ;; a review nine times over - once called a vector's order "unstated" and routed on it. It was
       ;; not unstated; the language states it. The first sentence alone changed nothing when tested;
       ;; the corollary is what did. General on purpose: no type is named.
       "TYPES ARE FOLLOWED AS THE LANGUAGE DEFINES THEM. A type named in the contract means exactly "
       "what the language specification says it means - no more, no less - and that meaning is part "
       "of the contract without being written out. A finding that asks for a sentence the type already "
       "provides is not a finding; a role that departs from what the type defines has changed the "
       "contract. In doubt, the language specification is the referee. An ordered collection built "
       "from ordered inputs has the order of its construction: that order is given, not left open, "
       "and a role that imposes another has changed the contract."))

;; ---------------------------------------------------------------------------
;; the leak policy
;; ---------------------------------------------------------------------------

(defn feedback-for
  "What the retried role is told, for the verdict `v` on `trigger`: the
  guidance FIRST, as `:triage` feedback, then the evidence it interprets.
  Guidance first because `packet-prompt` renders feedback in order and the
  interpretation is what moves a model that the raw output alone did not.

    :red-gate   the failing gate's own output, as `:gate` feedback
    :note       nothing — the guidance stands in for a decision the role
                could not make itself
    :rejection  the Reviewer's findings, as `:reviewer` feedback — TO THE
                CODER ONLY. The Reviewer read the diff, so its text is derived
                from the implementation and `driver/leaks` refuses every word
                of it for the Tester. A Tester routed on a rejection is sent
                the guidance alone, which `shield` then checks like any other."
  [{:keys [route guidance reason]} {:keys [trigger payload]}]
  (into (if-let [g (or guidance reason)]
          [{:feedback/from :triage :feedback/text g}]
          [])
        (case trigger
          :red-gate (packet/gate-feedback (:gate-result payload))
          :rejection (when (and (= :coder route) (not (str/blank? (:findings payload))))
                       [{:feedback/from :reviewer :feedback/text (:findings payload)}])
          nil)))

(defn shield
  "The leak policy, as a rule and not a habit: a verdict that routes the
  Tester is checked against `driver/leaks` over EVERYTHING the retry would
  carry — the guidance and the gate output — and any leak turns it into a
  `:human` route with the leaks listed.

  ESCALATE, DO NOT REWORD. A hand `retry tester` is refused on the same
  check and a person rewords or passes `--allow-leak`; the loop has no
  judgement to record, so it stops and hands over both the verdict and what
  it would have sent. `impl-names` is `sigs/impl-names` over the Coder's
  worktree. Any other route passes through untouched."
  [verdict spec impl-names feedback]
  (if-not (= :tester (:route verdict))
    verdict
    (let [ls (driver/leaks spec impl-names feedback)]
      (if (empty? ls)
        (assoc verdict :leak-check {:leaks [] :allowed? false})
        (assoc verdict
               :route :human
               :routed :tester
               :leak-check {:leaks ls :allowed? false}
               :reason (str "triage routed the Tester — " (:reason verdict)
                            " — but the feedback it would carry names the implementation ("
                            (str/join "; " (map :detail ls))
                            "); reword it, or `retry tester --allow-leak` to send it anyway"))))))

;; ---------------------------------------------------------------------------
;; the call
;; ---------------------------------------------------------------------------

(defn fallback
  "What triage says when the model could not: on a red gate, the driver's
  own proposal — the mechanical half, which was the whole of triage before
  this namespace — with `:triage/fallback` naming why; on a note or a
  rejection or a security finding, a person, because the mechanical proposal
  reads a gate's output and none of those has one."
  [{:keys [trigger spec dependents payload]} why]
  (case trigger
    :red-gate (let [p (driver/propose-routing spec dependents (:gate-result payload))]
                {:route (or (:retry-role p) (:owner p))
                 :reason (str "fallback to the driver's proposal: " (:advice p))
                 :guidance nil
                 :triage/fallback why
                 :proposal (dissoc p :advice)})
    :note {:route :human
           :reason "a note needs a decision, and the triage model gave none"
           :guidance nil
           :triage/fallback why}
    :rejection {:route :human
                :reason "the Reviewer rejected, and the triage model did not say whose it is"
                :guidance nil
                :triage/fallback why}
    :security-finding {:route :human
                       :reason "a reproduced security finding, and the triage model did not say whose it is"
                       :guidance nil
                       :triage/fallback why}))

(defn- measured
  "The call's cost and provenance, shaped as an AgentResult so the loop can
  make a report step of it with `report/dispatch-step`, exactly as it does
  for a dispatched role."
  [{:keys [status steps text error retries]} ms]
  (let [costs (keep :cost steps)
        complete? (and (seq steps) (= (count costs) (count steps)))]
    {:ms ms
     :status status
     :result {:status status
              :files []
              :stdout text
              :cost (when complete? (reduce + costs))
              :runner/meta (cond-> {:model (some :model (reverse steps))
                                    :provider (some :provider (reverse steps))
                                    :service-tier (some :service-tier (reverse steps))
                                    :tokens (when (seq steps) (reduce + 0 (keep :tokens steps)))
                                    :completions (count steps)
                                    ;; THE IDS, as a dispatch keeps them: a triage call whose
                                    ;; generation record lagged was recorded at cost nil with
                                    ;; nothing to fetch by, and the first real project's spend
                                    ;; sheet reconciled every triage call off the balance.
                                    :generation-ids (vec (keep :generation-id steps))
                                    :retries (or retries 0)
                                    :cost-source (cond (empty? costs) nil
                                                       (some #(= :list-price (:cost-source %)) steps) :list-price
                                                       :else :reported)
                                    :usage (when (seq steps)
                                             (reduce (fn [acc u] (merge-with + acc (into {} (filter (comp some? val)) u)))
                                                     {} (keep :usage steps)))}
                             error (assoc :error error))}}))

(defn model-triage
  "A triage-fn from `profile`: `(fn [trigger])` → the verdict, with what the
  call cost.

    {:route :coder|:tester|:architect|:human|:continue
     :reason _  :guidance _
     :triage/fallback why      ; only when the model's answer was not used
     :prompt _  :answer _      ; what was asked and what came back
     :ms _  :status _  :result AgentResult}   ; for the report step

  ONE COMPLETION, NO TOOLS. `converse!` is given `:tools #{}`, so the request
  declares none and the model cannot read past what the prompt carries;
  `:max-iterations 1` is the belt to that brace. `opts` reach `converse!`
  (tests pass a retry policy of one attempt).

  `:orchestrator` is required: a profile without one is refused here rather
  than falling back silently, because a loop that never asked the model
  while a profile appeared to configure one is the misreading this
  repository keeps finding in its own history."
  ([profile] (model-triage profile nil))
  ([profile opts]
   (let [role (get-in profile [:roles :orchestrator])]
     (when-not role
       (throw (ex-info "the profile has no :orchestrator role — triage has nobody to ask"
                       {:harness/error :no-orchestrator})))
     (fn [trigger]
       (let [prompt (render-prompt trigger)]
         (try
           (let [t0 (System/currentTimeMillis)
                 r (agent/converse! role system-prompt prompt
                                    {:dir (or (get-in trigger [:worktrees :coder]) ".")}
                                    (merge {:max-iterations 1} opts {:tools #{}}))
                 m (measured r (- (System/currentTimeMillis) t0))
                 v (cond
                     (= :failed (:status r))
                     (fallback trigger (str "the triage call failed: "
                                            (pr-str (select-keys (:error r) [:status :message :stop-reason]))))
                     ;; A REFUSAL IS SAID AS ONE. Opus 5.5 declined one of the first four security
                     ;; findings it was asked to route, partway through a verdict it had begun to
                     ;; write; read as an answer with no JSON block, it looked like a malformed reply.
                     (agent/refused? (:steps r))
                     (assoc (fallback trigger "the triage model declined to answer (finish reason content_filter / refusal) - its own safeguard, not a failure of the call")
                            :refused? true)
                     :else
                     (let [v (verdict trigger (parse-verdict (:text r)))]
                       (if (:triage/fallback v)
                         (fallback trigger (:triage/fallback v))
                         v)))]
             (merge v m {:prompt prompt :answer (:text r)}))
           (catch Exception e
             (assoc (fallback trigger (str "triage error: " (ex-message e)))
                    :prompt prompt))))))))
