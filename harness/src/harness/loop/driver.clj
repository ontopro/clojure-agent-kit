(ns harness.loop.driver
  "`bb run-loop <command> <run-dir> [args]` — the loop's steps as commands, with a
  person doing triage between them; `harness.loop.orchestrate` runs the same steps
  with `harness.loop.triage` deciding instead, and stops wherever it cannot.

  THE STEPS, NOT THE LOOP. Provision, dispatch, assemble, gate, review — each
  one a command a person can run, with a written triage decision before any
  retry. `harness.loop.orchestrate` is the loop that composes them and routes what it
  can; these stay because they are how a person continues from any stop it
  reaches, and because the loop was built out of them rather than beside them.
  They were a throwaway script for four early runs and one script carried
  forward through three more, gaining only additive changes; that is the
  evidence they had a shape worth committing. `DEVLOG.md` has the history.

  A run directory holds everything one run needs and produces:

    spec.edn         the task spec — one entry of the Architect's task list
    loop.edn         {:run/id \"d10\" :profile \"resources/profiles/claude.edn\"
                      :repo/root \"../../xyx-app\"          ; optional - see `project-root`
                      :project/subdir \"sandbox\"            ; optional
                      :architecture {:from \"arch\" :files [\"layers.edn\"]} ; optional
                      :worktrees/dir \"/tmp/...\"            ; optional
                      :repo/allow-dirty? true                     ; optional - see `dirty-files`
                      :gates [[:fmt \"bb fmt-check\"] ...]   ; optional
                      :nrepl/cmd [\"clojure\" \"-Srepro\" \"-M:nrepl\"]}  ; optional
    state.edn        written by the commands; the run so far
    events.log       the same events, append-only (`harness.loop.log`); `record` refuses
                     when the two disagree
    run.edn          written by `record`; what `bb report` reads, with the files in final/
    <step>.packet.edn, <step>.transcript.edn, reviewer*.diff, final/
                     what each dispatch was given, said and ran, and made

  `:profile` resolves against the BUILD when the run is in a workspace whose
  `workspace.edn` names one (`bb init` writes `<name>-build/profile.edn` and points
  the build's `loop.edn` at it), and against the working directory otherwise (the
  shipped examples live in `resources/profiles/`); every other relative path in
  `loop.edn` resolves against the run directory. The profile is READ ONCE, at
  `start`, and kept in `state.edn`: the
  throwaway drivers re-read it on every command, and a profile edited mid-run would
  have switched a role's model between attempts without a trace.

  Commands:

    start     <run-dir>                       provision, precondition, stub, dispatch
                                              Coder and Tester, then `check` — pausing
                                              after a dispatch that left notes
    continue  <run-dir> [<decision.edn>]      carry on from a pause: the Tester, or `check`;
                                              {:decision \"...\"} is required when nothing
                                              was amended or retried since the pause
    check     <run-dir>                       assemble, gate 0, gates, Reviewer if green
    retry     <run-dir> <role> <triage.edn> [--allow-leak]
                                              re-dispatch one role; the file is
                                              {:decision \"...\" :feedback [Feedback ...]}.
                                              The Tester's feedback is refused if it
                                              names the implementation, unless the flag
                                              records the judgement to send it anyway
    amend     <run-dir> <spec-before.edn> <reason>   record a change to spec.edn
    mutation  <run-dir> <mutation.edn>        record a mutation check as an event
    record    <run-dir>                       copy the gated files to final/<path> (and
                                              what gate 0 changed to final/as-written/),
                                              write run.edn
    merge     <run-dir> <decision.edn>        commit the gate worktree, merge its branch
                                              --no-ff into the base checkout, then `record`
                                              and `teardown`; {:decision \"...\"} is required,
                                              and so is a run the loop stopped for the merge
    teardown  <run-dir> [--keep-branches]     remove the worktrees, and a recorded run's branches;
              [--discard]                     refused on a dispatched run that is not recorded, unless --discard

  EVERY DECISION IN HERE COST A RUN TO LEARN, and each is written where it is
  made rather than listed here. The shape of them: one state file and one clock,
  because a run that printed its wall time below its own steps could not be
  checked; the Reviewer shown a diff, because two reviews were dispatched with
  no code; notes, feedback and triage decisions kept as `:run/events`, so a
  document can quote them; a red gate's own output and the run's final files in
  the record, because an audit found both only in a gitignored directory; a
  triage decision written BEFORE its dispatch, so it cannot be fitted to the
  outcome; the gate worktree's bytes rather than the bytes a role wrote, because
  a merge failed on the difference and nobody had seen gate 0 make it."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [harness.contract.packet :as packet]
   [harness.contract.shapes :as shapes]
   [harness.contract.sigs :as sigs]
   [harness.contract.spec-review :as spec-review]
   [harness.contract.stub :as stub]
   [harness.contract.targets :as targets]
   [harness.gates.repair :as repair]
   [harness.gates.run :as gates]
   [harness.loop.log :as log]
   [harness.loop.provision :as prov]
   [harness.models.profile :as profile]
   [harness.models.runner :as runner]
   [harness.money.balance :as balance]
   [harness.money.report :as report]
   [harness.money.reprice :as reprice]
   [harness.rules :as rules]
   [harness.setup.plan :as plan]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; pure: configuration, names, the record
;; ---------------------------------------------------------------------------

(defn- git-toplevel [dir]
  (let [{:keys [exit out]} (p/shell {:out :string :err :string :dir (str dir) :continue true}
                                    "git" "rev-parse" "--show-toplevel")]
    (when (zero? exit) (str/trim out))))

(defn head-commit
  "`git rev-parse HEAD` in `dir`, or nil: no dir, not a repository, or a
  repository with no commit yet. Nil is the truth about those, not an error -
  a record says what it could see."
  [dir]
  (when (and dir (fs/directory? dir))
    (let [{:keys [exit out]} (p/shell {:out :string :err :string :dir (str dir) :continue true}
                                      "git" "rev-parse" "HEAD")]
      (when (zero? exit) (str/trim out)))))

(defn project-root
  "Which repository this run works on, and how that was decided:
  `{:repo/root path :repo/from :loop-edn|:workspace|:run-dir}`.

  THE PROJECT USED TO BE WHEREVER THE RUN DIRECTORY WAS: `git rev-parse` there,
  applied AFTER `loop.edn`, so nothing could say otherwise. That held while a
  harness was copied INTO the project it built. In a workspace the run
  directory is under `work/`, in no repository at all, or inside the KIT's
  clone - where the old rule would have cut worktrees of the KIT and called
  them the project. So, most explicit first: `:repo/root` in `loop.edn`
  (relative to the run directory); the workspace's `:workspace/app`; and only
  then the run directory's own repository, which is every run recorded so far.

  The answer must BE a repository. One that is not is named with where it came
  from - a typo in `workspace.edn` should not surface as a failed `worktree add`."
  [run-dir cfg ws]
  (let [[path from] (cond
                      (:repo/root cfg) [(str (fs/normalize (fs/absolutize (fs/path run-dir (:repo/root cfg))))) :loop-edn]
                      (:workspace/app ws) [(:workspace/app ws) :workspace]
                      :else [(str (fs/absolutize run-dir)) :run-dir])
        top (when (fs/directory? path) (git-toplevel path))]
    (cond
      (and top (= :run-dir from)) {:repo/root top :repo/from from}
      top {:repo/root (str (fs/normalize path)) :repo/from from}
      :else
      (throw (ex-info (case from
                        :loop-edn (str "loop.edn's :repo/root, " path ", is not a git repository")
                        :workspace (str "the workspace's application folder, " path " ("
                                        (fs/path (:workspace/dir ws) "workspace.edn")
                                        " :workspace/app), is not a git repository")
                        (str "the run directory is not inside a git repository, no workspace.edn was "
                             "found above it, and loop.edn names no :repo/root"))
                      {:run-loop/error :no-repo :run-dir run-dir :repo/from from :path path})))))

(defn resolve-config
  "`loop.edn` with its defaults filled in and its paths made absolute.

  `ws`, when the run is in a workspace (`workspace/find-workspace`), moves the worktrees
  from the system's temp folder to `<work>/worktrees/<run-id>`: scratch that a
  person can find, in no repository, and gone with the workspace - and resolves
  `:profile` against the workspace's BUILD, where `bb init` put the project's
  profile, so the clone carries nothing of it. Outside a workspace, or in one
  whose `workspace.edn` names no build (the health check's selfcheck), `:profile`
  resolves against the working directory, where the shipped examples are. An
  absolute `:profile` is taken as given either way.

  Throws rather than guessing when `:run/id` or `:profile` is missing: a run
  record with an invented id, or a dispatch to a profile nobody chose, is the
  kind of plausible default this repository keeps finding in its own history."
  [run-dir repo-root cfg & [ws]]
  (doseq [k [:run/id :profile]]
    (when-not (get cfg k)
      (throw (ex-info (str "loop.edn needs " k)
                      {:run-loop/error :missing-config :key k :run-dir run-dir}))))
  (let [in-run (fn [path] (str (fs/absolutize (fs/path run-dir path))))
        profile (str (if-let [plan (:workspace/build ws)]
                       (fs/normalize (fs/path plan (:profile cfg)))
                       (fs/absolutize (:profile cfg))))]
    ;; THE KEYS ARE DESCRIBED ONCE, in `shapes/loop-keys`: the literal defaults come from
    ;; there, the computed ones are filled here, and a key the table does not know is refused
    ;; by name - before this, a misspelt key was a default silently applied, and one of the
    ;; defaults spends money.
    (when-let [unknown (seq (shapes/unknown-loop-keys cfg))]
      (throw (ex-info (str "loop.edn has " (str/join ", " unknown) " - not a key the loop reads. The keys: "
                           (str/join ", " (sort (map str shapes/loop-file-keys)))
                           " (harness.contract.shapes/loop-keys says what each is for)")
                      {:run-loop/error :unknown-config :keys (vec unknown) :run-dir run-dir})))
    (cond-> (merge (shapes/loop-defaults)
                   {:gates gates/default-gate-seq
                    :worktrees/dir (str (if-let [work (:workspace/work ws)]
                                          (fs/path work "worktrees" (:run/id cfg))
                                          (fs/path (fs/temp-dir) "run-loop" (:run/id cfg))))}
                   cfg
                   {:repo/root repo-root
                    :profile profile
                    ;; where `record` copies run.edn, and the two other repositories whose
                    ;; commits it names - kept from `start`, like everything else here
                    :records/dir (:workspace/records ws)
                    :plan/root (:workspace/build ws)
                    :kit/root (or (:workspace/kit ws) (git-toplevel "."))})
      (:worktrees/dir cfg) (update :worktrees/dir in-run)
      (:architecture cfg) (update-in [:architecture :from] in-run))))

(defn retry-step-name
  "`:coder-r1` for the Coder's second attempt. Attempts count from 1 and a
  retry's suffix from 1, so the name says how many times the role came back."
  [role attempt]
  (keyword (str (name role) "-r" (dec attempt))))

(defn reviewer-step-name
  "`:reviewer` for the first review, `:reviewer-r1` after that."
  [prior-reviews]
  (if (zero? prior-reviews) :reviewer (keyword (str "reviewer-r" prior-reviews))))

(defn next-attempt
  "The attempt a retry of `role` would be. Per role, and only for the step
  name: `:coder-r1` should say how many times the Coder came back, not how many
  rounds the task has had. The CAP is `rounds`, below."
  [attempts role]
  (inc (get attempts role 1)))

(defn rounds
  "Dispatch rounds this task has had: the first, plus one for every triage
  decision recorded.

  THE CAP IS PER TASK, NOT PER ROLE, and this is the number it is spent
  against. A per-role cap of 3 lets one task be dispatched six times before
  anything escalates, which is not a cap anyone chose; and it counts a Coder
  retry and a Tester retry as unrelated when they are two attempts at the same
  failure. Counting `:triage` events rather than attempts also means a hand
  `retry` and the loop agree without either telling the other: the decision
  file is recorded before the dispatch, so it is the one thing both see."
  [state]
  (inc (count (filter #(= :triage (:event/kind %)) (:events state)))))

(defn retry-cap
  "The task's retry cap, from its spec, or the packet default."
  [spec]
  (:retry-cap (or (:gates spec) packet/default-gates)))

(def transcript-cap
  "Characters kept of each string in a recorded transcript. Enough to see what
  a model read, evaluated and wrote, in order; the files themselves are in
  :run/files and the full transcript stays in the run directory."
  500)

(defn cap-transcript
  "`turns` with every string cut to `transcript-cap`, the cut announced."
  [turns]
  (walk/postwalk (fn [x]
                   (if (and (string? x) (> (count x) transcript-cap))
                     (str (subs x 0 transcript-cap) "…[" (- (count x) transcript-cap) " more chars]")
                     x))
                 turns))

(defn dispatch-event
  "What a dispatch leaves in the run log, beyond its report step. The
  transcript is capped here and kept whole in `<step>.transcript.edn`."
  [step-name role pkt result]
  (let [m (:runner/meta result)]
    (cond-> {:event/kind :dispatch :event/step step-name :role role
             :attempt (:task/attempt pkt 1) :status (:status result)
             :iterations (:iterations m) :capped? (:capped? m)
             :tool-calls (:tool-calls m) :files (:files result)
             :notes (:notes result []) :stdout (:stdout result)
             :timing (select-keys m [:ms-completion :ms-provenance :ms-provenance-wait
                                     :ms-tools :reasoning-tokens :completions])
             :model (:model m) :provider (:provider m) :service-tier (:service-tier m)}
      ;; Only when a request was sent again, so older state replays unchanged.
      (pos? (:retries m 0)) (assoc :retries (:retries m))
      (:task/feedback pkt) (assoc :feedback (:task/feedback pkt))
      ;; The Reviewer's verdict, nil included: `orchestrate/next-action` routes
      ;; on it, and it reads events, not results.
      (= :reviewer role) (assoc :verdict (:verdict m))
      (:cost-partial m) (assoc :cost-partial (:cost-partial m))
      (:transcript m) (assoc :transcript (cap-transcript (:transcript m)))
      ;; THE USAGE IS THE COMPLETION'S, NOT THE COST'S. It travelled only beside a
      ;; cost-source, so a dispatch whose generation record lagged - cost nil,
      ;; source nil - lost its token counts too, and the report said no dispatch
      ;; carried usage over a table whose tokens column was filled.
      (:usage m) (assoc :usage (:usage m))
      ;; A list-price cost is re-derivable only if the rates travel with it.
      (:cost-source m) (assoc :cost-source (:cost-source m))
      (:pricing m) (assoc :pricing (:pricing m))
      ;; The error as text for a document, and its KIND for the loop: a credit
      ;; refusal is a stop of its own, and `next-action` reads events.
      (:error m) (assoc :error (str (:error m)) :error/kind (:harness/error (:error m)))
      (= :credit (:harness/error (:error m)))
      (assoc :credit (select-keys (:error m) [:status :endpoint :key-env])))))

(defn pause?
  "Whether `start` stops after a dispatch, before the next one.

  WHEN THE DISPATCH LEFT NOTES. A Coder once noted, exactly, that the contract
  would break a dependent's round-trip properties — and the loop dispatched the
  Tester and ran the gates into the failure it had been told about. A note is a role saying something about
  the contract that someone must decide on, and the next dispatch builds on the
  contract as it stands. So the run stops, prints the notes, and waits: `continue`
  to carry on, or `amend` and `retry` first. On unless `loop.edn` says
  `:notes/pause? false`."
  [cfg result]
  (boolean (and (not (false? (:notes/pause? cfg)))
                (seq (:notes result)))))

(defn next-step
  "What `start` does after a dispatch of `role`: the Coder is followed by the
  Tester, the Tester by `check`."
  [role]
  (case role
    :coder :tester
    :tester :check))

(defn effective-spec
  "The spec every packet is built from: `spec.edn` as the Architect wrote it,
  plus the dependents `start` computed, added to `:files/context`. Kept apart
  so `spec.edn` stays the Architect's own words and an amendment is a clean
  edit of them."
  [spec state]
  (sigs/with-dependents spec (:dependents state)))

(defn run-status
  "The record's `:run/status`, a `harness.contract.shapes/RunStatus`.

  FROM THE LOOP'S OWN OUTCOME. The loop writes a `:stopped` event carrying the
  status it is finishing with, so the record says what the run did rather than
  what its last gate run did — `:awaiting-merge` when the Reviewer has been and
  a person has the merge, `:escalated` for every stop that hands the task back,
  `:merged` once a merge has happened.

  The fallback is for a run driven entirely by hand, which has no `:stopped`
  event: green gates mean the same thing they would have meant to the loop.
  This vocabulary replaced a `:done`/`:failed` pair that was in no schema, and
  which is why every record this repository committed before it failed
  `TaskRun`."
  [state]
  (or (when (some #(= :merged (:event/kind %)) (:events state)) :merged)
      (some-> (last (filter #(= :stopped (:event/kind %)) (:events state))) :run/status)
      (if (:gates/passed? (:last-gates state)) :awaiting-merge :escalated)))

(defn role-settings
  "`{role {:model :family :effort}}` from a profile: the settings a run's
  figures were produced under. Effort is wherever the shape puts it."
  [profile]
  (into {} (for [[r {:keys [model family params endpoint key-env max-rounds]}] (:roles profile)]
             [r (cond-> {:model model :family family
                         :effort (or (get-in params [:output_config :effort]) (:reasoning_effort params))}
                  ;; where a later `bb reprice` fetches from; the variable's NAME, never a key
                  endpoint (assoc :endpoint endpoint)
                  key-env (assoc :key-env key-env)
                  ;; the rounds limit the dispatches ran under, so the report can say how close each came
                  max-rounds (assoc :max-rounds max-rounds))])))

(defn run-record
  "The run record `bb report` reads, from the state the commands built.

  Wall time runs to the end of the last dispatch or gate run, not to whenever
  `record` was typed: mutation checks and write-ups after the run are not the run.

  IT VALIDATES AGAINST `shapes/TaskRun`, which `record!` asserts. The schema had
  been in the seed since the extraction with nothing calling it, so the three
  keys it requires and this function did not supply — a `RunStatus`, a cost and
  a start time — went missing from every record for as long as the schema sat
  there looking enforced."
  [state]
  (cond-> {:run/id (:run/id (:config state))
           :task/id (:task/id (:spec state))
           :run/status (run-status state)
           ;; The whole run's money, where the report's own footer says how much
           ;; of the run it covers. A step that reported no cost contributes 0,
           ;; so this is a floor and the steps are where it is checked.
           :run/cost (:cost (report/totals (:steps state)))
           :run/started-at (java.util.Date. (:started-ms state))
           ;; THE HIGHEST ATTEMPT OF ANY ONE ROLE, which is what names a retry's step
           ;; (`next-attempt`). It is NOT the number of dispatch rounds - that is `rounds`,
           ;; the cap's count, and the report's `Rounds:` line derives it from the events.
           :run/attempts (apply max 1 (vals (:attempts state)))
           :run/wall-ms (apply max 0 (keep #(when (#{:dispatch :gates} (:event/kind %)) (:event/at-ms %))
                                           (:events state)))
           :run/steps (:steps state)
           :run/events (:events state)
           ;; which model, family and effort each role ran at, so a later comparison
           ;; of settings does not have to reconstruct it from the profile's history
           :run/roles (role-settings (:profile state))
           ;; the KIT, the application and the plan as they stood when the record was
           ;; taken, so a record of a project names the code and the plan it ran against;
           ;; nil where there is no such repository (a run outside a workspace has no plan)
           :run/kit-commit (get-in state [:commits :kit])
           :run/app-commit (get-in state [:commits :app])
           :run/plan-commit (get-in state [:commits :plan])}
    ;; the code the run produced, so a document can quote it from the record
    (seq (:final-files state)) (assoc :run/files (:final-files state))
    ;; and, where gate 0 changed a file, what the role wrote before it did
    (seq (:files-as-written state)) (assoc :run/files-as-written (:files-as-written state))))

(defn gated-current?
  "Whether the gate worktree holds the Coder's and Tester's latest files: a gate
  run came after every Coder or Tester dispatch, and no refused assembly after
  it. Only then is the gate worktree `record`'s source."
  [events]
  (let [relevant (filter #(or (#{:gates :assemble-refused} (:event/kind %))
                              (and (= :dispatch (:event/kind %)) (#{:coder :tester} (:role %))))
                         events)]
    (= :gates (:event/kind (last relevant)))))

(defn diff-hunks
  "A unified diff's hunks, without the header lines above the first: they name
  the files compared, which are temporary paths and say nothing."
  [diff-out]
  (->> (str/split-lines (str diff-out))
       (drop-while #(not (str/starts-with? % "@@")))
       (str/join "\n")))

(defn gates-event
  "The event a gate run leaves. A red one carries the failing gate's own output.
  A record that says only which gate failed is not evidence: the run whose token
  it failed on had that token in a gitignored file nobody else could read."
  [gate-result]
  (cond-> {:event/kind :gates :passed? (:gates/passed? gate-result) :failed (:gates/failed gate-result)}
    (not (:gates/passed? gate-result)) (assoc :feedback (packet/gate-feedback gate-result))))

(defn path-ns
  "The namespace a Clojure source path conventionally defines: the first
  directory dropped, `/` to `.`, `_` to `-`. `test/sandbox/property_test.clj`
  is `sandbox.property-test`. A convention, not a read of the file: it is
  used only to match a gate's output against paths the spec already names."
  [path]
  (-> (str/join "/" (rest (str/split (str path) #"/")))
      (str/replace #"\.clj[sc]?$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")))

(defn failing-namespaces
  "The namespaces clojure.test reported a FAIL or ERROR under. A whole-suite
  run prints `Testing <ns>` for every namespace, green ones included, and a
  failure's own line names the test var and the throw site, not the test
  file — one said `parse.clj:25` for a failure in `property_test.clj`. So a
  failure belongs to the `Testing` line above it.

  AND A RUNNER THAT PRINTS NO `Testing` LINE names the namespace on the
  failure itself: `FAIL in app.util-test/clamp-above-hi-spec (util_test.clj:46)`
  is how the pinned template's suite reports one. The fifth build's red gate
  on a merged property test read that way, and this saw no namespace at all:
  the proposal named no file and no owner, where the failing namespace was
  right there and was nobody's in the run."
  [out]
  (->> (str/split-lines (str out))
       (reduce (fn [[current found] line]
                 (if-let [[_ ns] (re-matches #"\s*Testing (\S+)\s*" line)]
                   [ns found]
                   (if-let [[_ _ ns] (re-find #"^\s*(FAIL|ERROR) in ([^\s/()]+)/\S+" line)]
                     [current (conj found ns)]
                     (if (and current (re-find #"^\s*(FAIL|ERROR) in " line))
                       [current (conj found current)]
                       [current found]))))
               [nil #{}])
       second))

(defn- names-in
  "The paths among `paths` that `out` mentions: by path, by file name, or as
  the namespace a failing test ran under."
  [out paths]
  (let [failing (failing-namespaces out)]
    (filterv (fn [p] (or (str/includes? out p)
                         (str/includes? out (fs/file-name p))
                         (contains? failing (path-ns p))))
             paths)))

(defn propose-routing
  "What a red gate suggests about who owns it.

  Nine hand triage decisions, read back off their records, routed three ways
  that are mechanical: the `:calls` check is the Coder's or the slice's; a
  failure in a file only the Tester owns is the Tester's; one in a file no role
  owns — a dependent of a rewritten namespace — is the contract's or the
  Coder's. This says which, from the failing gate's own
  output and the spec's ownership, and NOTHING is dispatched on it: `retry`
  still takes a written triage decision, and the two sit side by side in the
  record so a later reader can see where the proposal and the decision differ.
  A `:reviewer` or `:triage` routing is judgement and has no proposal.

  `:owner` is `:coder` when every file named is an impl file, nil when a
  dependent, both roles' files, or nothing is named, and — when every file named
  is a test file — the Tester only if that file failed to read or compile, or
  the gate is not the test gate (a format or lint failure in its own file).

  AND `:tooling` WHEN THE TEST GATE'S EVERY FAILING NAMESPACE IS NOBODY'S IN
  THE RUN - not an impl file's, not a test file's, not a dependent's. A merged
  property test that rounds onto its bound on some seeds, a suite the build
  ships: nothing this run dispatches can fix it, and routing it to a role
  buys a round for nothing. The fifth build met one and triage routed
  `human`, rightly, with no route that said what it saw; `:foreign-namespaces`
  names them and `:retry-role` is nil, since there is no role to retry.

  AN ASSERTION THAT FAILS IN THE TASK'S OWN TEST IS THE CODER'S BY DEFAULT.
  This proposed the Tester for any failure naming only a test file, and an
  assertion failure always names only its test file, so every red test was
  proposed as the Tester's — against method §07: \"Default ownership of a failing
  test is the Coder\". The first live proposal against a real red gate WAS the
  Tester, and was right, for a reason no rule sees: the test mis-encoded an
  ambiguous target. That stays a triage decision, and the advice says so."
  [{:keys [files/impl files/test]} dependents gate-result]
  (let [{:keys [gate out]} (first (filter #(= :fail (:status %)) (:gates/report gate-result)))
        out (str out)
        impl-hit (names-in out impl)
        test-hit (names-in out test)
        dep-hit (names-in out dependents)
        foreign (when (= :test gate)
                  (let [own (set (map path-ns (concat impl test dependents)))]
                    (vec (sort (remove own (failing-namespaces out))))))
        owner (cond
                (= :calls gate) :coder
                (and (seq foreign) (empty? impl-hit) (empty? test-hit) (empty? dep-hit)) :tooling
                ;; A red :targets gate is the Tester's with no ambiguity at all —
                ;; the check reads only the Tester's own files and says which
                ;; contract sentence they do not account for. It is named here
                ;; rather than left to the file rules below because its detail
                ;; text names a target and a sentence, not always a file.
                (= :targets gate) :tester
                (and (seq test-hit) (empty? impl-hit) (empty? dep-hit))
                (if (and (= :test gate) (not (re-find #"Syntax error" out))) :coder :tester)
                (and (seq impl-hit) (empty? test-hit) (empty? dep-hit)) :coder
                :else nil)]
    (cond-> {:gate gate
             :files-named (vec (concat impl-hit test-hit dep-hit))
             :owner owner
             :retry-role (when-not (= :tooling owner) (or owner :coder))
             :sources (if (or owner (= :calls gate)) [:gate] [:architect :gate])
             :advice (cond
                       (= :tooling owner)
                       "a failing test in a namespace no role in this run owns - a merged test, or the build's own: nothing this run dispatches can fix it. `check` again if the failure is seed-dependent; otherwise fix it outside this run (its own run through the loop), then `record`, `teardown` and `start` the task again - the worktrees cannot see a fix made after they were cut"
                       (= :calls gate)
                       "the slice's omission: `amend`, then `check` again; the Coder's: `retry coder` with the calls as :gate feedback"
                       (and (= :coder owner) (seq test-hit))
                       "a failing test in the task's own tests, which is the Coder's by default (method §07): `retry coder` with the gate output as :gate feedback. If the test mis-encodes the contract, that is a triage decision — `amend` where the wording allowed it, then `retry tester`"
                       (= :tester owner)
                       "a file only the Tester owns that did not read, compile or pass format or lint: `retry tester` with the gate output as :gate feedback — after checking it names no implementation"
                       (= :coder owner)
                       "a file only the Coder owns: `retry coder` with the gate output as :gate feedback"
                       :else
                       "a file no role owns, or none named: the contract or the Coder. `amend`, then `retry coder` with :architect and :gate feedback; or `retry coder` with :gate feedback alone")}
      (seq foreign) (assoc :foreign-namespaces foreign))))

(defn- token-re
  "`name` as a whole Clojure symbol: not preceded or followed by a symbol
  character, so `bind` matches `bind` and `expr/bind` but not `binding`."
  [nm]
  (re-pattern (str "(?<![\\w*+!?<>=$.\\-])" (java.util.regex.Pattern/quote (str nm)) "(?![\\w*+!?<>=$\\-])")))

(defn leaks
  "Where `feedback` for the Tester would carry the implementation.
  `impl-names` is `sigs/impl-names`. Four rules, each from a recorded Tester
  retry that had been shielded by hand:

    :impl-path        an impl file's path or name (the file the Tester was kept from)
    :impl-var         a var the impl defines that the slice's :interfaces did not
                      grant, as a whole symbol
    :code-block       a fenced ``` block — a Reviewer finding once quoted the impl
    :reviewer-source  :feedback/from :reviewer at all: the Reviewer read the diff,
                      so its text was derived from the implementation

  A WORD THE CONTRACT ITSELF USES IS NOT A LEAK. `:impl-var` matches a bare
  word, and an implementation's private helpers are named in English: `view`,
  `header`. Two projects' first Tester retries to meet this were both refused
  for guidance that described nothing but the contract — *the header carries
  the site title* is a property target's own sentence, and the Coder had a
  `header` helper. The Tester already holds the task's title and its targets,
  so a word that stands in them tells it nothing; a var name found there is
  skipped. A helper named for a word the contract does NOT use is still
  refused, and the detail quotes the word, because finding it in a paragraph
  of guidance was the slow part of telling a false refusal from a true one.

  Returns `[{:feedback/from kw :leak kw :detail str}]`, empty when clean. A
  rendered value in gate output — one retry withheld the string `\"(((0\"` by
  hand — is not a name, and this does not see it."
  [{:keys [files/impl task/title property-targets]} impl-names feedback]
  (let [contract (str/join "\n" (cons (str title) property-targets))
        contract-word? (fn [nm] (boolean (re-find (token-re nm) contract)))]
    (vec
     (for [{:keys [feedback/from feedback/text]} feedback
           :let [text (str text)]
           leak (concat
                 (when (= :reviewer from)
                   [{:leak :reviewer-source :detail "the Reviewer's text is derived from the diff it read"}])
                 (for [p impl :when (or (str/includes? text p) (str/includes? text (fs/file-name p)))]
                   {:leak :impl-path :detail (str "names " p)})
                 (for [{:keys [ns name]} impl-names
                       :when (and (re-find (token-re name) text) (not (contract-word? name)))]
                   {:leak :impl-var
                    :detail (str "the word `" name "` names " ns "/" name
                                 ", which the slice's :interfaces do not grant")})
                 (when (str/includes? text "```")
                   [{:leak :code-block :detail "carries a fenced code block"}]))]
       (assoc leak :feedback/from from)))))

(defn final-files
  "Each of the spec's impl and test paths, mapped to its content in `final-dir`,
  for the files there. `record` copies them there, so a replay after the
  worktrees are gone still finds them.

  BY PATH, `final/<path>`. `record` copied by file name once, so two paths with
  one name overwrote each other and a merge had to re-derive every path from the
  spec. A run directory written that way still reads — `final/<file-name>` when
  there is no `final/<path>` — so an older run replays unchanged, and two such
  paths with one name are refused rather than recorded as one file."
  [spec final-dir]
  (let [paths (concat (:files/impl spec) (:files/test spec))
        dupes (->> paths (group-by fs/file-name) (keep (fn [[n ps]] (when (next ps) n))) set)]
    (into (sorted-map)
          (for [path paths
                :let [by-path (fs/path final-dir path)
                      f (cond
                          (fs/exists? by-path) by-path
                          (contains? dupes (fs/file-name path))
                          (throw (ex-info (str "two files named " (fs/file-name path)
                                               " would overwrite each other in final/")
                                          {:run-loop/error :final-name-collision
                                           :names (vec dupes)}))
                          :else (fs/path final-dir (fs/file-name path)))]
                :when (fs/exists? f)]
            [path (slurp (str f))]))))

(defn files-as-written
  "The spec's paths for which `final/as-written/` holds the role's own bytes —
  the files gate 0 changed before the gates ran — mapped to those bytes."
  [spec final-dir]
  (into (sorted-map)
        (for [path (concat (:files/impl spec) (:files/test spec))
              :let [f (fs/path final-dir "as-written" path)]
              :when (fs/exists? f)]
          [path (slurp (str f))])))

;; ---------------------------------------------------------------------------
;; effects: the run directory's state
;; ---------------------------------------------------------------------------

(defn- now [] (System/currentTimeMillis))

(defn- timed [f] (let [t0 (now) v (f)] [v (- (now) t0)]))

(defn- gate-0-diff
  "The hunks between what a role wrote and what gate 0 left, by `git diff
  --no-index`: git is a tool the harness already requires."
  [before after]
  (let [a (fs/create-temp-file) b (fs/create-temp-file)]
    (try
      (spit (str a) before)
      (spit (str b) after)
      (diff-hunks (:out (p/shell {:out :string :err :string :continue true}
                                 "git" "diff" "--no-index" "--no-color" "-U0" (str a) (str b))))
      (finally
        (fs/delete-if-exists a)
        (fs/delete-if-exists b)))))

(defn- state-file [ctx] (str (fs/path (:run-dir ctx) "state.edn")))

(defn log-file
  "The run's append-only event log — `harness.loop.log`, beside `state.edn`."
  [ctx]
  (str (fs/path (:run-dir ctx) "events.log")))

(defn- persist! [ctx]
  (spit (state-file ctx) (with-out-str (pp/pprint @(:state ctx)))))

(defn step!
  "Record one report step. Public because the loop records its triage call
  through it, the same way a dispatch is recorded."
  [ctx m]
  (let [step (merge {:step/source :measured} m)]
    ;; ASSERTED, NOT HOPED FOR. `RunStep` is what the report renders and what a
    ;; published table is checked against; a step that does not validate is a
    ;; report column quietly reading nil, and the schema existed for months
    ;; with nothing calling it.
    (assert (shapes/valid-step? step) (pr-str (shapes/explain-step step) step))
    (swap! (:state ctx) update :steps conj step))
  (persist! ctx))

(defn event!
  "Record one event: into `state.edn`, and appended to `events.log`.

  BOTH, because `state.edn` is rewritten on every command and the log is only
  ever appended to. `record!` refuses when they disagree, which is the same
  treatment this repository gives every other pair of generated artifacts."
  [ctx m]
  ;; `:at` IS AN ABSOLUTE CLOCK READING, kept in the state as well as the log line,
  ;; so the record can derive the run's total and waiting time from timestamps.
  ;; `:event/at-ms` is an offset from a counter that accumulates across
  ;; invocations, and a wall time computed from it once came out below the sum
  ;; of the steps; the report trusts `:at` where it is present.
  (let [e (assoc m :event/at-ms (- (now) (:started-ms @(:state ctx))) :at (java.util.Date.))]
    (swap! (:state ctx) update :events conj e)
    (log/append! (log-file ctx) (dissoc e :at)))
  (persist! ctx))

(defn spec-of
  "The effective spec, read fresh each command: an Architect's amendment is
  an edit to spec.edn, recorded with `amend`. The profile is the one thing
  NOT re-read. The dependents are computed once, at `start`, and applied on
  every read."
  [ctx]
  (effective-spec (edn/read-string (slurp (str (fs/path (:run-dir ctx) "spec.edn"))))
                  @(:state ctx)))

(defn- sessions [ctx] (:sessions @(:state ctx)))

(defn- run-file [ctx nm] (str (fs/path (:run-dir ctx) nm)))

(defn- first-packet [ctx role]
  (case role
    :coder (packet/coder-packet (spec-of ctx) (:coder (sessions ctx)))
    :tester (packet/tester-packet (spec-of ctx) (:tester (sessions ctx)))))

(defn wrote-nothing?
  "A Coder or Tester dispatch that finished and changed no file. Said out loud,
  because a Tester retry once did exactly that, said so in its final message,
  and `check` was run over the unchanged file anyway."
  [role result]
  (boolean (and (#{:coder :tester} role) (= :done (:status result)) (empty? (:files result)))))

(defn repl-alive?
  "Whether the worktree's nREPL answers `(+ 1 1)`.

  THE RULE ONLY HAS TEETH IF THE HARNESS CHECKS TOO. `:no-repl-no-edits` tells
  a dispatched agent to stop rather than edit blind when the REPL is gone, and
  says the REPL was probed before it was dispatched — so this is what makes
  that sentence true, and what makes a dead REPL surface as a dead REPL rather
  than as a mysterious gate failure three paid attempts later.

  The seam is `:repl-probe` on the ctx; `harness.loop.orchestrate` passes a constant
  in tests, where the fixture worktrees have no nREPL."
  [worktree port]
  (let [{:keys [exit out]} (p/shell {:dir worktree :out :string :err :string :continue true}
                                    "clj-nrepl-eval" "-p" (str port) "(+ 1 1)")]
    (and (zero? exit) (str/includes? (str out) "2"))))

(defn- probe-repl!
  "Refuse the dispatch when the packet names a REPL that does not answer.
  Records the refusal as a `:repl-dead` event in `gates/failure`'s shape, so it
  reaches triage through the same seam as a red gate, and throws: nothing is
  dispatched, and the next role is not dispatched either."
  [ctx step-name role pkt]
  (when-let [port (:repl/port pkt)]
    (let [probe (get ctx :repl-probe repl-alive?)]
      (when-not (probe (:repl/worktree pkt) port)
        (let [gr (gates/failure :repl (str "the worktree nREPL on port " port
                                           " did not answer a probe — not dispatching an agent"
                                           " that would then edit files blind"))]
          (event! ctx {:event/kind :repl-dead :event/step step-name :role role :port port
                       :feedback (packet/gate-feedback gr)})
          (println (format "\n  REPL DEAD: %s's nREPL on port %s did not answer. Nothing was dispatched."
                           (name role) port))
          (throw (ex-info (str "the " (name role) "'s nREPL on port " port " did not answer a probe")
                          {:run-loop/error :repl-dead :role role :port port})))))))

(defn- dispatch! [ctx step-name role pkt]
  (probe-repl! ctx step-name role pkt)
  (spit (run-file ctx (str (name step-name) ".packet.edn")) (with-out-str (pp/pprint pkt)))
  (println (format "\n  dispatching %s (attempt %d)…" (name step-name) (:task/attempt pkt 1)))
  (let [[r ms] (timed #(runner/run-agent (runner/api-runner (:profile @(:state ctx))) role pkt))
        m (:runner/meta r)]
    (when-let [t (:transcript m)]
      (spit (run-file ctx (str (name step-name) ".transcript.edn")) (with-out-str (pp/pprint t))))
    (step! ctx (report/dispatch-step step-name r ms))
    (event! ctx (dispatch-event step-name role pkt r))
    (println (format "  %-12s %ss  %s  iters=%s capped?=%s files=%s notes=%d cost=%s%s"
                     (name step-name) (quot ms 1000) (:model m) (:iterations m) (:capped? m)
                     (pr-str (:files r)) (count (:notes r)) (:cost r)
                     (if-let [e (:error m)] (str "  ERROR " (pr-str e)) "")))
    (doseq [n (:notes r)] (println "    NOTE:" n))
    ;; THE REVIEW IS PRINTED, because the stop that follows tells a person to
    ;; read it. It said so for two milestones while only the summary line and
    ;; the notes reached the console — harmless at a stop that could only
    ;; `record`, and not at one that decides a merge.
    (when (and (= :reviewer role) (= :done (:status r)))
      (let [{:keys [verdict reasons]} (:verdict m)]
        (println (str "    VERDICT: " (if verdict (name verdict) "none given")))
        (doseq [reason reasons] (println "      -" reason))
        (println "    FINDINGS:")
        (doseq [line (str/split-lines (str (:stdout r)))] (println "     " line))))
    (when (wrote-nothing? role r)
      (println "    WROTE NOTHING: this dispatch changed no file. Read its final message before `check`:")
      (println "    " (subs (str (:stdout r)) 0 (min 400 (count (str (:stdout r)))))))
    r))

;; ---------------------------------------------------------------------------
;; commands
;; ---------------------------------------------------------------------------

(defn check! [ctx]
  (let [sp (spec-of ctx) s (sessions ctx) cfg (:config @(:state ctx))
        gate-dir (:worktree/path (:gate s))
        [asm ms] (timed #(try (prov/assemble! (:gate s)
                                              [[(:coder s) (:files/impl sp)]
                                               [(:tester s) (:files/test sp)]]
                                              (:architecture cfg))
                              (catch clojure.lang.ExceptionInfo e
                                {:refused (ex-message e) :data (ex-data e)})))]
    (step! ctx {:step/name :assemble :step/kind :provision :step/ms ms
                :step/status (if (:refused asm) :fail :done)})
    (if (:refused asm)
      (do (event! ctx {:event/kind :assemble-refused :message (:refused asm) :data (:data asm)})
          (println "\n  ASSEMBLY REFUSED:" (:refused asm) (pr-str (:data asm))))
      (let [paths (concat (:files/impl sp) (:files/test sp))
            ;; WHAT GATE 0 CHANGES IS RECORDED. It repairs and formats the
            ;; assembled files in place, and the gates and the Reviewer see its
            ;; result; a Tester once wrote a file that did not read, gate 0
            ;; removed the stray bracket, and nothing said so.
            before (into {} (for [p paths :let [f (fs/path gate-dir p)] :when (fs/exists? f)]
                              [p (slurp (str f))]))
            [r0 ms0] (timed #(repair/repair! gate-dir paths))
            _ (step! ctx {:step/name :gate-0 :step/kind :gate-0 :step/ms ms0
                          :step/status (if (zero? (:exit r0)) :pass :fail)})
            changed (into (sorted-map)
                          (for [[p b] before
                                :let [f (fs/path gate-dir p)
                                      a (when (fs/exists? f) (slurp (str f)))]
                                :when (not= a b)]
                            [p (gate-0-diff b (str a))]))
            _ (when (seq changed)
                (event! ctx {:event/kind :gate-0 :changed changed})
                (println "\n  gate 0 changed:" (str/join ", " (keys changed))))
            ;; THE CALLS CHECK, before the shell gates: does the implementation
            ;; call only the signatures its slice granted?
            ;; Cheap, harness-side, and a red here is either the Architect's
            ;; omission (`amend`, then `check` again — no dispatch) or the
            ;; Coder's (retry with this feedback).
            [calls msc] (timed #(sigs/undeclared-calls gate-dir sp))
            _ (step! ctx {:step/name :calls :step/kind :gate :step/ms msc
                          :step/status (if (empty? calls) :pass :fail)})
            _ (event! ctx {:event/kind :calls :violations (vec calls)})
            ;; THE TARGETS CHECK, on the same terms: did the Tester account for
            ;; every :property-targets entry? A Tester once skipped one of
            ;; eight and passed every other gate; only mutation found it, by
            ;; hand, afterwards. This checks ACCOUNTING and not
            ;; adequacy — a test may name a target and still not pin it — so it
            ;; raises the floor and replaces nothing.
            [cov mst] (timed #(targets/coverage gate-dir sp))
            _ (event! ctx (assoc (select-keys cov [:covered :exempt :uncovered :total])
                                 :event/kind :targets))
            ;; A TARGET DECLARED NO TEST'S JOB IS SHOWN, NOT SWALLOWED: it is a
            ;; judgement, and the point of writing it down is that a person sees it.
            _ (when (seq (:exempt cov))
                (println "\n  targets declared not a test's job:")
                (doseq [{:keys [target reason]} (:exempt cov)]
                  (println (str "    " target " — " reason))))
            red-targets? (seq (:uncovered cov))
            targets-gate {:gate :targets :ms mst
                          :status (if red-targets? :fail :pass)
                          :exit (if red-targets? 1 0)
                          :out (str/join "\n" (map :detail (:uncovered cov)))}
            gr (if (seq calls)
                 {:gates/passed? false :gates/failed :calls
                  :gates/report [{:gate :calls :status :fail :ms msc :exit 1
                                  :out (str/join "\n" (map :detail calls))}]}
                 ;; THE TARGETS CHECK RUNS LAST, and not beside :calls where it
                 ;; started. A red test gate is a defect; an unaccounted target is
                 ;; bookkeeping, and failing on the bookkeeping first would hide
                 ;; the defect behind it — an existing run-loop test caught that.
                 ;; It only decides anything when every other gate is green, which
                 ;; is precisely the hole it exists for.
                 (let [g (gates/run-gates! gate-dir (:gates cfg))
                       green? (:gates/passed? g)]
                   (assoc g
                          :gates/report (conj (vec (:gates/report g)) targets-gate)
                          :gates/passed? (and green? (not red-targets?))
                          :gates/failed (if (and green? red-targets?)
                                          :targets
                                          (:gates/failed g)))))]
        (doseq [{:keys [gate status ms]} (:gates/report gr)]
          (step! ctx {:step/name gate :step/kind :gate :step/status status :step/ms ms}))
        (swap! (:state ctx) assoc :last-gates gr)
        (event! ctx (gates-event gr))
        (println "\n  gates:" (str/join " " (for [{:keys [gate status]} (:gates/report gr)]
                                              (str (name gate) "=" (name status)))))
        (if (:gates/passed? gr)
          (let [diff (prov/review-diff (:gate s))]
            ;; GREEN GATES OVER NOTHING AT ALL. Every gate passes on an
            ;; unchanged tree, so a green run whose diff is empty says only that
            ;; the repository was already green. The Reviewer is not dispatched
            ;; to read nothing, and the run stops for a person.
            (if (= "(no diff)" diff)
              (do (event! ctx {:event/kind :empty-diff})
                  (println (str "\n  GREEN GATES BUT EMPTY DIFF: the gate worktree is unchanged against HEAD."
                                "\n  Nothing was reviewed. Read what the last dispatch said it did.")))
              (let [prior (count (filter #(and (= :dispatch (:event/kind %)) (= :reviewer (:role %)))
                                         (:events @(:state ctx))))
                    nm (reviewer-step-name prior)]
                (spit (run-file ctx (str (name nm) ".diff")) diff)
                (dispatch! ctx nm :reviewer (packet/reviewer-packet sp (:gate s) diff (:gates/report gr))))))
          (let [fb (:feedback (gates-event gr))
                routing (propose-routing sp (:dependents @(:state ctx)) gr)]
            (spit (run-file ctx "last-gate-feedback.edn") (with-out-str (pp/pprint fb)))
            (doseq [{:keys [feedback/text]} fb]
              (println "\n" (subs text 0 (min 3000 (count text)))))
            ;; PROPOSED, NOT DECIDED. Recorded beside the triage decision that
            ;; follows, so the record shows where they differ.
            (event! ctx (assoc routing :event/kind :routing))
            (println (str "\n  proposed routing: " (name (or (:retry-role routing) (:owner routing)))
                          (if (:owner routing) " (owner)" " (no owner)")
                          ", files named " (pr-str (:files-named routing))
                          ", sources " (pr-str (:sources routing))
                          "\n    " (:advice routing)))))))))

(defn- proceed!
  "After a dispatch of `role` in `start`'s sequence: pause if it left notes,
  otherwise take the next step."
  [ctx role result]
  (cond
    ;; A FAILED DISPATCH STOPS THE SEQUENCE. The next role would build on work
    ;; that was never done — the rule `continue` already enforces at a pause,
    ;; applied where `start` would otherwise walk straight past it into an
    ;; assembly that refuses a file nobody wrote.
    (= :failed (:status result))
    (println (str "\n  DISPATCH FAILED: the " (name role) " returned :failed. Nothing more is"
                  " dispatched; read its error above, then `retry`."))

    (pause? (:config @(:state ctx)) result)
    (let [nxt (next-step role)]
      (swap! (:state ctx) assoc :paused {:after role :next nxt})
      (event! ctx {:event/kind :paused :after role :next nxt :notes (:notes result)})
      (println (str "\n  PAUSED after the " (name role) ", which left "
                    (count (:notes result)) " note(s):"))
      (doseq [n (:notes result)] (println "   -" n))
      (println (str "\n  Nothing more is dispatched until you decide. To carry on to "
                    (if (= :check nxt) "the gates" "the Tester") ":\n"
                    "    bb run-loop continue " (:run-dir ctx) " <decision.edn>   ; {:decision \"why\"}\n"
                    "  or change the contract first with `amend`, and re-dispatch with `retry`.")))

    :else
    (case (next-step role)
      :tester (proceed! ctx :tester (dispatch! ctx :tester :tester (first-packet ctx :tester)))
      :check (check! ctx))))

(defn- balance-event!
  "Record what the OpenRouter key says it has, at `when` (:start or :record).
  A balance, not a spend: the record can then show start, end and the
  difference beside the summed OpenRouter costs. Anthropic's API has no such
  endpoint, so its half is spend, computed by the report. Never fails the run."
  [ctx when]
  (let [role (balance/openrouter-role (:profile @(:state ctx)))]
    (event! ctx {:event/kind :balance :when when
                 :openrouter (if role
                               (balance/openrouter-key-status (:endpoint role) (:key-env role))
                               {:unavailable "not in this profile"})})))

(defn dirty-files
  "What `git status --porcelain` lists in `repo-root` - one line per file, the
  status letters kept - minus anything under an `exclude` directory. Empty when
  the tree is clean, and empty when `repo-root` is not a repository: this is a
  warning's input, never a reason to fail a run.

  THE WORKTREES ARE CUT FROM THE LAST COMMIT, and nothing said so. An edit
  sitting in the checkout - a hand fix a session meant to commit, a document
  half written - is invisible to every role and every gate of the run, and
  the difference surfaces later, as a red gate on a file nobody in the run
  touched or as a merge that carries the committed version over it. `start`
  warns, and `:repo/allow-dirty? true` in loop.edn silences it - warns, not refuses,
  because a person may mean it. The run's own directory and the
  worktrees folder are excluded when they sit inside the repository: they are
  the loop's, not the application's."
  [repo-root exclude]
  (let [;; Canonical, both sides: git reports the real path and a run
        ;; directory under /var is /private/var on a Mac.
        root (fs/canonicalize repo-root)
        {:keys [exit out]} (try (p/shell {:dir (str root) :out :string :err :string :continue true}
                                         "git" "status" "--porcelain" "--untracked-files=all")
                                (catch Exception _ {:exit 1}))
        under (into [] (comp (map #(fs/canonicalize %))
                             (filter #(fs/starts-with? % root))
                             (map #(str (fs/relativize root %) "/")))
                    (remove nil? exclude))
        path-of (fn [line] (let [p (subs line (min 3 (count line)))]
                             (last (str/split p #" -> "))))]
    (if (zero? exit)
      (into [] (comp (remove str/blank?)
                     (remove (fn [line] (some #(str/starts-with? (path-of line) %) under))))
            (str/split-lines out))
      [])))

(defn- dirty-tree! [ctx]
  (let [cfg (:config ctx)
        dirty (dirty-files (:repo/root cfg) [(:run-dir ctx) (:worktrees/dir cfg)])]
    (when (seq dirty)
      (event! ctx {:event/kind :dirty-tree :count (count dirty) :files (vec (take 20 dirty))
                   :allowed? (boolean (:repo/allow-dirty? cfg))})
      (when-not (:repo/allow-dirty? cfg)
        (println (str "\n  WARNING: the worktrees are cut from the last commit of " (:repo/root cfg) "; "
                      (count dirty) " file" (when (> (count dirty) 1) "s") " there "
                      (if (> (count dirty) 1) "are" "is") " not in it:"))
        (doseq [line (take 20 dirty)] (println (str "    " line)))
        (when (> (count dirty) 20) (println (str "    … and " (- (count dirty) 20) " more")))
        (println "  commit or stash them first, or `:repo/allow-dirty? true` in loop.edn to say you mean it")))))

(defn plan-check!
  "THE PLAN IS READ ONCE PER WORKSPACE, before its first dispatch. `plan/check`
  over the workspace's plan - the marks, the template's instructions, the
  overlay, the given parts - and a throw with the list when anything is left:
  a mark left standing reaches the next reader as literal text, and the loop
  is that reader's first stop. Once is known by a hash of everything the check
  reads, kept at `<work>/plan-check.edn`: a plan that has not changed is not
  read again, and one that has is. Skipped, and nothing said, outside a
  workspace or in one with no plan (the health check's selfcheck), and with
  `:plan-check/run? false` in loop.edn - the health check's generated application
  runs the shipped template unfilled on purpose. Returns what it printed about."
  [ctx]
  (let [cfg (:config ctx)
        ws (or workspace/*of-run* (workspace/find-workspace (:run-dir ctx)))]
    (when (and (:plan-check/run? cfg true) (:workspace/build ws) (:workspace/work ws))
      (let [cache (fs/path (:workspace/work ws) "plan-check.edn")
            h (plan/inputs-hash (:workspace/build ws) ws)
            cached (when (fs/exists? cache) (edn/read-string (slurp (str cache))))]
        (if (= h (:hash cached))
          (do (println (str "  plan checked: unchanged since " (:at cached) " (" cache ")"))
              {:plan/checked :cached :at (:at cached)})
          (let [{:keys [problems documents]} (plan/check (:workspace/build ws) ws)]
            (when (seq problems)
              (throw (ex-info (str "the plan is not ready - " (count problems) " thing" (when (> (count problems) 1) "s")
                                   " left in " (:workspace/build ws) ":\n"
                                   (str/join "\n" (map #(str "    " %) problems))
                                   "\n  fill or fix, then `start` again (`bb plan-check` in the KIT's harness/ runs this check alone)")
                              {:run-loop/error :plan-check :problems problems :run-dir (:run-dir ctx)})))
            (fs/create-dirs (fs/parent cache))
            (spit (str cache) (pr-str {:hash h :at (str (java.time.Instant/now)) :documents documents}))
            (println (str "  plan checked: " documents " governing documents, the rules overlay, layers.edn and loop.edn"
                          " - nothing left to fill, the given parts intact (" cache ")"))
            {:plan/checked :now :documents documents}))))))

(defn start! [ctx]
  (when (fs/exists? (state-file ctx))
    (throw (ex-info (str "state.edn exists — this run has started. If nothing was dispatched "
                         "(a failed provision or precondition), `teardown` resets it for another `start`.")
                    {:run-loop/error :already-started :run-dir (:run-dir ctx)})))
  (let [cfg (:config ctx)
        sp (spec-of ctx)
        raw (edn/read-string {:default tagged-literal} (slurp (str (fs/path (:run-dir ctx) "spec.edn"))))]
    ;; THE PLAN BEFORE THE SPEC: a workspace's plan is checked once, before its first dispatch,
    ;; before the contract is read and before a spec review is paid for. `plan-check!` says when
    ;; it is skipped and why.
    (plan-check! ctx)
    ;; A SLICE THE STUB CANNOT READ IS REFUSED HERE, before a spec review is paid for and before
    ;; anything is provisioned. `stub/write!` refuses the same entries, but it runs after three
    ;; worktrees and two nREPLs exist; the fourth project met that as a crash whose error named
    ;; nothing, with all of it left behind for a person. Here the entry and its key are named and
    ;; there is nothing to tear down.
    ;; The same for a second implementation file (`stub/check-files`): the fifth project's
    ;; two-file packet paid two spec reviews and provisioned before `stub/write!` refused it.
    ;; And for a `:deps-sigs` entry the calls gate could never grant (`sigs/check-deps-sigs`).
    (when-let [bad (seq (concat (stub/check-files sp)
                                (stub/check-slice (:blueprint/slice sp))
                                (sigs/check-deps-sigs (:deps-sigs (:blueprint/slice sp)))))]
      (throw (ex-info (str "spec.edn has " (count bad) " entr" (if (= 1 (count bad)) "y" "ies")
                           " the stub cannot read:\n"
                           (str/join "\n" (map #(str "    " (subs (str (:key %)) 1) " " (pr-str (:entry %)) " — " (:reason %)) bad))
                           "\n  fix spec.edn, then `start` again (nothing was provisioned)")
                      {:run-loop/error :precondition :entries (mapv :entry bad)})))
    ;; A PLACEHOLDER STILL STANDING IN THE RULE SOURCE IS SAID BEFORE ANYONE READS IT - the spec
    ;; review included, which is shown the rules. Said, not refused: the seed's own sandbox runs
    ;; with them standing. `rules/unfilled` has the history.
    (when-let [left (seq (rules/unfilled (rules/load-rules)))]
      (println (str "\n  the rule source still has " (count left) " placeholder" (when (> (count left) 1) "s")
                    " that will reach every role as literal text — fill them in " (rules/fill-where)
                    ", then `bb rules-sync`:"))
      (doseq [{:keys [id placeholder]} left]
        (println (str "    " id "  " (subs placeholder 0 (min 70 (count placeholder))) (when (> (count placeholder) 70) "…")))))
    ;; THE SPEC IS REVIEWED BEFORE IT IS DISPATCHED, by the loop, not by a habit. When no
    ;; review of THIS spec.edn sits beside it, `start` runs one, prints the list and stops
    ;; here for the Architect: fix the contract or not, then `start` again. An amended spec
    ;; is a different spec and is reviewed again. `:spec-review/run? false` in loop.edn skips it
    ;; — for the seed's own tests and for a run that is deliberately re-dispatching a
    ;; reviewed contract.
    (when (and (:spec-review/run? cfg true) (not (spec-review/current? (:run-dir ctx) raw)))
      ;; A CONTRACT THAT KEEPS DRAWING FINDINGS IS A PERSON'S PROBLEM. Two reviews — the
      ;; spec as written and once amended — are the Architect's to act on alone; a
      ;; third means the amendments are not finding what the reviewer sees.
      ;; ONLY REVIEWS THAT FOUND SOMETHING COUNT: a clean first reading is not a contract
      ;; drawing findings (`spec-review/reviews-with-findings` has the run that taught it).
      (let [had (spec-review/reviews-with-findings (:run-dir ctx))
            max* (:spec-review/max cfg 2)]
        (when (>= had max*)
          (throw (ex-info (str "this spec has drawn findings in " had " reviews and is amended again — a person reads"
                               " the reviews (spec-review.edn) before it is reviewed a third time; `:spec-review/max` in loop.edn")
                          {:run-loop/error :spec-review-limit :reviews had :run-dir (:run-dir ctx)}))))
      ;; THE STOP EXISTS SO THE LIST IS READ, and an empty list has no reader: a review that
      ;; found nothing carries straight on. An answer with NO findings block is not that - nothing
      ;; was written, so it is not a clean review, and it still stops.
      (let [{:keys [count no-block?]} (spec-review/spec-review! ctx)]
        (when (or no-block? (pos? count))
          (throw (ex-info (if no-block?
                            "spec review: the answer had no findings block, so nothing was recorded — `start` again to ask again"
                            (str "spec reviewed: " count " finding" (if (= 1 count) "" "s")
                                 " — read them above (spec-review.edn); fix spec.edn or not, then `start` again"
                                 " (edit spec.edn in place - `amend` is for a started run)"))
                          {:run-loop/error :spec-reviewed :findings count :run-dir (:run-dir ctx)})))))
    (reset! (:state ctx) {:started-ms (now) :steps [] :events [] :attempts {:coder 1 :tester 1}
                          :config cfg :spec sp
                          :profile (profile/read-profile (:profile cfg))})
    (persist! ctx)
    ;; THE DESK STEP GOES INTO THE RECORD. `spec-review` runs before there is a state to
    ;; write an event into, so `start` records what it finds beside spec.edn: the count is
    ;; the readiness signal the report prints, and the cost is part of what the run cost.
    (when-let [e (spec-review/recorded (:run-dir ctx))]
      (event! ctx e))
    (balance-event! ctx :start)
    (dirty-tree! ctx)
    (let [o {:repo/root (:repo/root cfg) :worktrees/dir (:worktrees/dir cfg)
             :task/id (:task/id sp) :project/subdir (:project/subdir cfg)
             ;; the branches and worktrees are named by the RUN, so two runs of one
             ;; task - a trial of another model on the same spec - can exist at once
             :run/id (:run/id cfg)
             :nrepl/cmd (:nrepl/cmd cfg)}
          _ (fs/create-dirs (:worktrees/dir cfg))
          ;; EACH SESSION IS SAVED THE MOMENT IT EXISTS. They were saved together
          ;; after all three, so a third provision that threw left two worktrees and
          ;; their nREPLs running with no record `teardown` could find.
          [s ms] (timed #(reduce (fn [acc [k role extra]]
                                   (let [session (prov/provision! (merge (assoc o :task/role role) extra))]
                                     (swap! (:state ctx) assoc-in [:sessions k] session)
                                     (persist! ctx)
                                     (assoc acc k session)))
                                 {}
                                 [[:coder :coder {}] [:tester :tester {}] [:gate :reviewer {:nrepl? false}]]))]
      (swap! (:state ctx) assoc :sessions s)
      (step! ctx {:step/name :provision :step/kind :provision :step/ms ms :step/status :done})
      (println "  provisioned" (quot ms 1000) "s")
      ;; Before anything is dispatched, and from a worktree no role has touched:
      ;; what already requires the files this task will write. Empty for a new
      ;; namespace. A dispatched rewrite once broke a dependent no packet had shown.
      (let [[deps ms] (timed #(sigs/task-dependents (:worktree/path (:coder s)) sp))]
        (swap! (:state ctx) assoc :dependents deps)
        (event! ctx {:event/kind :dependents :files deps :ms ms})
        (println "  dependents:" (if (seq deps) (str/join ", " deps) "none")))
      (let [[v ms] (timed #(sigs/violations (:worktree/path (:coder s)) (:files/context (spec-of ctx))
                                            (:deps-sigs (:blueprint/slice sp))))]
        (step! ctx {:step/name :sigs :step/kind :precondition :step/ms ms
                    :step/status (if (seq v) :fail :pass)})
        (when (seq v)
          ;; NAME THE FILE AND THE REASON. This once said only that the signatures did not
          ;; match, over a context file that did not parse: the Architect had to run `bb sigs`
          ;; by hand to learn which file, and that no signature was wrong at all.
          (throw (ex-info (str "the slice's :deps-sigs do not match the source:\n"
                               (str/join "\n" (map #(str "    " (name (:violation %)) " — " (:detail %)) v))
                               "\n  fix spec.edn, then `bb run-loop teardown` (nothing was dispatched, so it "
                               "resets the run) and `start` again")
                          {:run-loop/error :precondition :violations v}))))
      (let [[path ms] (timed #(stub/write! (:worktree/path (:tester s)) (:files/impl sp) (:blueprint/slice sp)))]
        (step! ctx {:step/name :stub :step/kind :provision :step/ms ms :step/status :done})
        (swap! (:state ctx) assoc-in [:sessions :tester :harness/wrote] [path])
        (persist! ctx))
      (proceed! ctx :coder (dispatch! ctx :coder :coder (first-packet ctx :coder))))))

(defn last-dispatch-failed
  "The step name of the run's most recent dispatch, when it failed; else nil.
  A Coder retry once failed on an API error and `continue` went on to dispatch
  the Tester against the file the retry never rewrote."
  [state]
  (let [d (last (filter #(= :dispatch (:event/kind %)) (:events state)))]
    (when (= :failed (:status d)) (:event/step d))))

(defn decided-since-pause?
  "Whether an `amend` or a `retry` was recorded after the last pause — a decision
  that already carries its own reason."
  [events]
  (let [since (reverse (take-while #(not= :paused (:event/kind %)) (reverse events)))]
    (boolean (some #(#{:amend :triage} (:event/kind %)) since))))

(defn continue-with!
  "`continue!` with the decision as data: `decision` is the reason (nil when
  an `amend` or `retry` since the pause already carries one) and `extra` is
  merged onto the `:continued` event — the loop puts the triage verdict
  there, so a `continue` a model decided reads differently from one a
  person typed."
  [ctx decision extra]
  (let [{:keys [after next]} (:paused @(:state ctx))]
    (when-not next
      (throw (ex-info "this run is not paused — nothing to continue"
                      {:run-loop/error :not-paused})))
    (when-let [step (last-dispatch-failed @(:state ctx))]
      (throw (ex-info (str "the last dispatch, " (name step) ", failed — retry it before continuing")
                      {:run-loop/error :last-dispatch-failed :step step})))
    (when-not (or (not (str/blank? decision)) (decided-since-pause? (:events @(:state ctx))))
      (throw (ex-info (str "nothing was amended or retried since the pause — say why the run continues: "
                           "continue <run-dir> <decision.edn>, with {:decision \"...\"}")
                      {:run-loop/error :no-decision})))
    (swap! (:state ctx) dissoc :paused)
    (event! ctx (cond-> (merge extra {:event/kind :continued :after after :next next})
                  decision (assoc :decision decision)))
    (case next
      :tester (proceed! ctx :tester (dispatch! ctx :tester :tester (first-packet ctx :tester)))
      :check (check! ctx))))

(defn continue!
  "Carry on from a pause, with whatever `start` would have done next. Any
  `amend` or `retry` made during the pause has already happened; this only
  resumes the sequence, and may pause again. Refused while the last dispatch
  failed: the next role would build on work that was never done.

  A DECISION THAT CHANGES NOTHING STILL SAYS WHY. A run once paused on a Coder
  note that was an observation, not a conflict, and was continued with nothing
  amended; the record said `:continued` and nothing else, and the reason lived
  in a local file. When no `amend` or `retry` followed the pause, a
  decision file `{:decision \"...\"}` is required, written before the dispatch
  like a triage file, and recorded on the `:continued` event."
  [ctx & [decision-file]]
  (let [decision (some-> decision-file slurp edn/read-string :decision)]
    (when (and decision-file (str/blank? decision))
      (throw (ex-info (str decision-file " has no :decision — say why the run continues")
                      {:run-loop/error :no-decision})))
    (continue-with! ctx decision nil)))

(defn leak-check
  "The Tester's feedback against the implementation, as `retry` records it:
  `{:leaks [...] :allowed? bool}`, or nil for any other role. Throws unless
  `allow?` when there are leaks, naming each one."
  [role spec impl-names feedback allow?]
  (when (= :tester role)
    (let [ls (leaks spec impl-names feedback)]
      (when (and (seq ls) (not allow?))
        (throw (ex-info (str "this feedback would hand the Tester the implementation ("
                             (str/join "; " (map :detail ls))
                             ") — reword it, or pass --allow-leak to record the judgement and send it")
                        {:run-loop/error :tester-leak :leaks ls})))
      {:leaks ls :allowed? (boolean allow?)})))

(defn impl-names
  "`sigs/impl-names` over the Coder's worktree: what the Tester was kept from
  reading, for the leak check."
  [ctx]
  (sigs/impl-names (:worktree/path (:coder (sessions ctx))) (spec-of ctx)))

(defn gated-since-written?
  "Whether the gate worktree's copy of `role`'s files is the newest there is:
  among the gate runs and this role's dispatches that WROTE something, the last
  is a gate run. A dispatch that wrote nothing — a failed one, a capped one —
  changes no file, so it does not make the gate's copy stale."
  [events role]
  (= :gates (:event/kind (last (filter #(or (= :gates (:event/kind %))
                                            (and (= :dispatch (:event/kind %))
                                                 (= role (:role %))
                                                 (seq (:files %))))
                                       events)))))

(defn- sync-from-gate!
  "Before a retry, give `role` back the bytes the gates judged: copy the gate
  worktree's version of the role's OWN target files over the role's worktree.

  A RETRIED ROLE WAS STARTING FROM A FILE THAT DID NOT READ. Gate 0 repairs and
  formats in the gate worktree, and everything after it — the gates, the
  Reviewer, a merge, the record — reads that copy. The role's own worktree
  kept the bytes as written. A Tester whose first file had four bracket errors
  went green through gate 0, red on one assertion, and was sent back to fix the
  assertion: it opened its own copy, could not load it, and spent all 24 turns
  hunting a bracket that had been repaired an hour earlier somewhere it could
  not see. The gate output it had been handed cited line numbers in a file it
  did not have.

  ONLY THE ROLE'S OWN TARGETS, so the Tester never receives the
  implementation this way, and ONLY WHEN THE GATE'S COPY IS THE NEWEST
  (`gated-since-written?`): a hand `retry` typed before any `check` must not
  overwrite work the gates have not seen. Recorded as an event, because a
  file changing under a role between its attempts is exactly the kind of thing
  a record has to be able to say."
  [ctx role]
  (let [s (sessions ctx)
        sp (spec-of ctx)
        gate-dir (:worktree/path (:gate s))
        own-dir (:worktree/path (get s role))]
    (when (and gate-dir own-dir (gated-since-written? (:events @(:state ctx)) role))
      (let [synced (vec (for [path (case role :coder (:files/impl sp) :tester (:files/test sp))
                              :let [gated (fs/path gate-dir path)
                                    own (fs/path own-dir path)]
                              :when (and (fs/exists? gated)
                                         (or (not (fs/exists? own))
                                             (not= (slurp (str gated)) (slurp (str own)))))]
                          (do (fs/create-dirs (fs/parent own))
                              (fs/copy gated own {:replace-existing true})
                              path)))]
        (when (seq synced)
          (event! ctx {:event/kind :synced-from-gate :role role :files synced})
          (println (str "\n  synced from the gate worktree, so the " (name role)
                        " starts from the bytes the gates judged: " (str/join ", " synced))))))))

(defn retry-with!
  "`retry!` with the decision as data: `triage` is `{:decision \"...\"
  :feedback [Feedback ...]}`, `allow?` is `--allow-leak`, and `extra` is
  merged onto the `:triage` event — the loop puts the model's route, reason,
  guidance and cost there, so the record shows a routed retry and a typed one
  as the same kind of event with a different `:by`.

  ONE `:triage` EVENT PER ROUND, whoever decided. `rounds` counts them, so
  a retry the model routed and a retry a person typed spend the same cap."
  [ctx role {:keys [decision feedback]} allow? extra]
  (let [base (first-packet ctx role)
        cap (:retry-cap (:gates base))
        round (rounds @(:state ctx))
        _ (when (>= round cap)
            (throw (ex-info (str "retry cap " cap " reached — this task has had " round
                                 " dispatch rounds. Escalate; do not dispatch again.")
                            {:run-loop/error :retry-cap :cap cap :rounds round})))
        attempt (next-attempt (:attempts @(:state ctx)) role)
        checked (leak-check role (spec-of ctx)
                            (when (= :tester role) (impl-names ctx))
                            feedback allow?)]
    (event! ctx (cond-> (merge extra {:event/kind :triage :role role :attempt attempt :decision decision})
                  checked (assoc :leak-check checked)))
    (swap! (:state ctx) assoc-in [:attempts role] attempt)
    (persist! ctx)
    ;; AFTER the decision is recorded and BEFORE the dispatch: the decision
    ;; stands whatever this finds, and the role must see the synced file.
    (sync-from-gate! ctx role)
    (dispatch! ctx (retry-step-name role attempt) role (packet/for-retry base attempt feedback))))

(defn retry!
  "The triage file's decision is recorded BEFORE the dispatch, so it stands as a
  prediction and cannot be fitted to the outcome afterwards.

  THE TESTER'S FEEDBACK IS CHECKED FIRST. Every Tester retry on record had been
  shielded by hand from the Reviewer's findings and the implementation's names; `leaks` is that shield as a rule, and `--allow-leak`
  is the judgement to pass one anyway, recorded on the triage event either way."
  [ctx role-name triage-file & flags]
  (retry-with! ctx (keyword role-name) (edn/read-string (slurp triage-file))
               (boolean (some #{"--allow-leak"} flags)) nil))

(defn amend!
  "The Architect edited spec.edn. Record before and after, because a packet
  rebuilt from an edited spec otherwise carries the change with no trace of it.
  Announce it to the roles as `:architect` feedback in the next triage file."
  [ctx before-file reason]
  (let [before (edn/read-string (slurp before-file))
        after (spec-of ctx)
        slice-changed? (not= (:blueprint/slice before) (:blueprint/slice after))
        tester (:tester (sessions ctx))
        ;; A CHANGED SLICE MEANS A CHANGED STUB. The Tester prototypes against the
        ;; stub `start` wrote from the original slice; after an amendment that
        ;; adds an interface or changes an arity, that stub contradicts the packet
        ;; the Tester is dispatched with next.
        ;; Only the stub's own path is written, which the Tester never owns.
        restubbed (when (and slice-changed? tester)
                    (let [path (stub/write! (:worktree/path tester) (:files/impl after)
                                            (:blueprint/slice after))]
                      (swap! (:state ctx) update-in [:sessions :tester :harness/wrote]
                             #(vec (distinct (conj (vec %) path))))
                      path))]
    (swap! (:state ctx) assoc :spec after)
    (event! ctx (cond-> {:event/kind :amend :reason reason
                         :property-targets/before (:property-targets before)
                         :property-targets/after (:property-targets after)
                         :slice-changed? slice-changed?}
                  restubbed (assoc :stub-rewritten restubbed)))
    (println "recorded amendment"
             (if restubbed (str "— the slice changed, so the Tester's stub was regenerated at " restubbed) ""))))

(defn mutation!
  "Record a mutation check. A document that cites its result needs it in the
  record, and with each mutant's substitution, or nobody can re-run it."
  [ctx file]
  (event! ctx (assoc (edn/read-string (slurp file)) :event/kind :mutation))
  (println "recorded mutation check"))

(defn- copy-into! [from to]
  (fs/create-dirs (fs/parent to))
  (fs/copy from to {:replace-existing true}))

(defn record!
  "Copy the run's files into `final/` while the worktrees exist, then write
  `run.edn` with those files in it. Read back from `final/`, not the worktrees,
  so `record` replayed after `teardown` writes the same record.

  THE GATED BYTES, NOT THE WRITTEN ONES. This copied each role's own worktree,
  while gate 0 repairs and formats in the gate worktree that the gates, the
  Reviewer and a merge all read: one run's `final/` failed the very format gate
  the run had passed, and five committed records held a file their format gate
  had never seen. So `final/<path>` is the gate worktree's copy
  whenever that copy is current, and `final/as-written/<path>` keeps the role's
  own bytes for each file gate 0 changed."
  [ctx]
  (let [spec (spec-of ctx)
        state @(:state ctx)
        logged (mapv :event/kind (log/read-log (log-file ctx)))
        _ (when-not (= (mapv :event/kind (:events state)) logged)
            ;; THE DRIFT GATE, APPLIED TO THE RUN ITSELF. `state.edn` is
            ;; rewritten on every command and `events.log` is only appended to,
            ;; so they disagree only if one of them lost something. Writing a
            ;; record from whichever was read first is how a run's history
            ;; becomes unverifiable, which is the one thing this repository is
            ;; most against.
            (throw (ex-info (str "state.edn and events.log disagree — "
                                 (count (:events state)) " events in the state, "
                                 (count logged) " in the log. The log is append-only;"
                                 " read it before trusting the state.")
                            {:run-loop/error :log-drift
                             :state-events (count (:events state))
                             :logged-events (count logged)})))
        final-dir (run-file ctx "final")
        gate-wt (some-> (get (sessions ctx) :gate) :worktree/path)
        gated? (boolean (and gate-wt (fs/exists? gate-wt) (gated-current? (:events state))))]
    (fs/create-dirs final-dir)
    (doseq [[role paths] [[:coder (:files/impl spec)] [:tester (:files/test spec)]]
            path paths
            :let [existing (fn [p] (when (and p (fs/exists? p)) p))
                  written (existing (some-> (get (sessions ctx) role) :worktree/path (fs/path path)))
                  gated (when gated? (existing (fs/path gate-wt path)))
                  as-written (fs/path final-dir "as-written" path)]
            :when (or gated written)]
      (copy-into! (or gated written) (fs/path final-dir path))
      (if (and gated written (not= (slurp (str gated)) (slurp (str written))))
        (copy-into! written as-written)
        (fs/delete-if-exists as-written)))
    (balance-event! ctx :record)
    (let [state @(:state ctx)
          cfg (:config state)
          out (run-record (assoc state :spec spec
                                 :final-files (final-files spec final-dir)
                                 :files-as-written (files-as-written spec final-dir)
                                 ;; THREE HASHES, READ NOW: the record is what a document
                                 ;; cites, and "which commit was this against" was answered
                                 ;; by memory on every build before this
                                 :commits {:kit (head-commit (:kit/root cfg))
                                           :app (head-commit (:repo/root cfg))
                                           :plan (head-commit (:plan/root cfg))}))
          ;; THE LATE COSTS, FETCHED NOW. Every dispatch of the first real project's runs
          ;; was recorded at cost nil - the generation records had lagged past the
          ;; dispatch's wait - and `bb reprice` by hand filled them minutes later. At
          ;; `record` those minutes have passed: one request per id, no waiting, and a
          ;; step whose record is still not there stays unpriced for the command.
          once {:attempts 1 :interval-ms 0 :timeout-ms 5000}
          out (if (empty? (reprice/unpriced out))
                out
                (let [{:keys [record lines changed]} (reprice/reprice out {:fetch-opts once})]
                  (when (pos? changed)
                    (println (str "  priced at record (" changed " step" (when (not= 1 changed) "s") "):"))
                    (doseq [l lines] (println (str "  " l))))
                  record))
          ;; THE DESK STEP'S COST TOO: the spec review's event carries its ids since the
          ;; readings kept them, and its cost lagged the same way.
          out (update out :run/events
                      (fn [events]
                        (mapv (fn [e]
                                (if (and (= :spec-review (:event/kind e))
                                         (or (nil? (:cost e)) (some (comp nil? :cost) (:reviews e))))
                                  (let [{:keys [record changed]} (reprice/reprice-review e {:fetch-opts once})]
                                    (when (pos? changed) (println "  spec review priced at record"))
                                    record)
                                  e))
                              events)))
          ;; THE COPY IS THE RECORD'S HOME. Two builds copied run.edn into the build repository by
          ;; hand, by the convention this now keeps; the run directory is scratch.
          kept (when-let [dir (:records/dir cfg)]
                 (str (fs/path dir (str (:run/id out) ".edn"))))]
      (assert (shapes/valid-run? out) (pr-str (shapes/explain-run out)))
      (spit (run-file ctx "run.edn") (pr-str out))
      (when kept
        (fs/create-dirs (fs/parent kept))
        (spit kept (pr-str out)))
      (println "wrote run.edn —" (name (:run/status out)) "— wall"
               (quot (:run/wall-ms out) 1000) "s"
               (if kept (str "— copied to " kept) "")))))

(defn- delete-branch!
  "Delete a task branch, if it exists. Called for a run that dispatched nothing,
  so the branch holds no agent's work, and for a run that is recorded, so the
  record holds it."
  [repo-root branch]
  (let [{:keys [exit]} (p/shell {:dir repo-root :out :string :err :string :continue true}
                                "git" "branch" "-D" branch)]
    (zero? exit)))

(defn dispatched?
  "Whether any role was dispatched in this run — the line between a run that
  produced evidence and one that only provisioned."
  [state]
  (boolean (some #(= :dispatch (:event/kind %)) (:events state))))

(defn teardown!
  "Remove the run's worktrees, and the three task branches once the run is
  recorded.

  THE BRANCHES ARE EVIDENCE ONLY UNTIL THE RECORD IS TAKEN. `record` copies the
  gated bytes, the final files and the transcripts into run.edn, and a merged
  run's gate commit is on the base branch by the --no-ff merge; after that the
  branches hold nothing the record does not. They used to stay whatever
  happened, and one build closed with dozens of them in its application -
  rehearsal and merged alike - with nothing saying whether they were evidence
  or litter (register row 78). A run that dispatched and is NOT recorded keeps
  its branches and says so: `record` first. `:keep-branches?` keeps them for a
  run somebody means to reopen.

  A RUN THAT DISPATCHED NOTHING IS RESET INSTEAD. After a failed provision or a
  refused precondition there is no work to keep, and the kept branches and
  state.edn made `start` impossible: `start` refused on state.edn, and
  `git worktree add -b` refused on the branches. So such a run also loses its task branches and state.edn, and `start` can
  run again."
  ([ctx] (teardown! ctx {}))
  ([ctx {:keys [keep-branches? discard?]}]
   (let [state @(:state ctx)
         repo-root (:repo/root (:config state))
         recorded? (fs/exists? (run-file ctx "run.edn"))
         ;; REFUSED BEFORE ANYTHING IS REMOVED. The branches were called the evidence
         ;; until the record is taken, and that was never true: the roles' files sit
         ;; UNCOMMITTED in the worktrees, and `record` copies them from there. A
         ;; teardown typed before a record that had refused removed the worktrees,
         ;; said "branches kept", and the record then wrote an empty final/. The
         ;; order is the design: record, then teardown. `--discard` is for a run
         ;; nobody will record, and says what it loses.
         _ (when (and (dispatched? state) (not recorded?) (not discard?))
             (throw (ex-info (str "this run dispatched and is not recorded: the roles' files are in the worktrees, "
                                  "uncommitted, and `record` copies them from there. `record` first, then `teardown`; "
                                  "or `teardown --discard` to remove the worktrees and the branches and lose those files")
                             {:run-loop/error :not-recorded :run-dir (:run-dir ctx)})))
         ;; THE RUN'S BRANCHES: by the session's own name where a session exists, else
         ;; by the run id - never the task's, which another run of the task shares
         branch-of (fn [k role]
                     (or (:worktree/branch (get (sessions ctx) k))
                         (prov/branch-name (or (:run/id (:config state)) (:task/id (:spec state))) role)))
         delete-branches! (fn []
                            (doseq [[k role] [[:coder :coder] [:tester :tester] [:gate :reviewer]]
                                    :let [branch (branch-of k role)]]
                              (println "  branch" branch (if (delete-branch! repo-root branch) "deleted" "absent"))))]
     (doseq [k [:coder :tester :gate]
             :let [s (get (sessions ctx) k)]
             :when s]
       (println k (:torn-down? (prov/teardown! s repo-root))))
     (cond
       (not (dispatched? state))
       (do (delete-branches!)
           (fs/delete-if-exists (state-file ctx))
           ;; THE LOG GOES WITH THE STATE. It stayed, and the next `start` appended to
           ;; it, so `record` refused the finished run: 23 events in the state, 24 in
           ;; the log - the first start's balance event. The drift gate was right; the
           ;; reset was half done. A start that dispatched nothing left nothing in the
           ;; log that the stop did not already print.
           (fs/delete-if-exists (log-file ctx))
           (reset! (:state ctx) nil)
           (println "  nothing was dispatched: state.edn and events.log removed, so `start` can run again"))

       (and (not recorded?) discard?)
       (do (delete-branches!)
           (println "  discarded: this run was not recorded; its worktrees and branches are gone, and the roles' files with them"))

       keep-branches?
       (println "  branches kept (--keep-branches)")

       :else
       (do (delete-branches!)
           (println "  recorded: run.edn holds what the branches held (--keep-branches would have kept them)"))))))

;; ---------------------------------------------------------------------------
;; the merge
;; ---------------------------------------------------------------------------

(def ^:private after-the-merge-stop
  "What may follow the loop's `:awaiting-merge` stop and leave it standing: a
  mutation check someone recorded before deciding, and this command's own
  events from an attempt that did not finish. Anything else — a retry, an
  amendment, a gate run — means the run has moved on and `run` must say where
  it is now."
  #{:mutation :merge-commit :merge-failed})

(defn awaiting-merge?
  "Whether the loop's last word on this run is that it awaits a merge: its last
  `:stopped` event says `:awaiting-merge`, which `orchestrate/next-action` only
  says of a review that APPROVED, and nothing that changes the run came after."
  [events]
  (let [[after [stop]] (split-with #(not= :stopped (:event/kind %)) (reverse events))]
    (and (= :awaiting-merge (:run/status stop))
         (every? #(contains? after-the-merge-stop (:event/kind %)) after))))

(defn merge-refusal
  "Why `merge` will not run over `state` with `decision`, as `{:run-loop/error
  kw :message str}`, or nil when it may. Pure, so every refusal is a table row
  in a test rather than a scratch repository.

  THE LOOP NEVER MERGES, AND THIS IS WHERE THAT IS ENFORCED. The run must have
  been stopped BY THE LOOP for the merge — which takes green gates and a
  Reviewer's `approve` — the gate worktree must hold both roles' latest files,
  and a person must have written down why. A hand-driven run reaches here by
  typing `run`, which costs nothing over a finished review and puts the verdict
  logic in one place instead of two."
  [state decision]
  (let [events (:events state)]
    (cond
      (some #(= :merged (:event/kind %)) events)
      {:run-loop/error :already-merged :message "this run has already been merged"}

      (str/blank? decision)
      {:run-loop/error :no-decision
       :message "a merge is a person's decision — merge <run-dir> <decision.edn>, with {:decision \"why this merges\"}"}

      (not (awaiting-merge? events))
      {:run-loop/error :not-awaiting-merge
       :message (str "this run is not awaiting a merge — `bb run-loop run <run-dir>` must have stopped "
                     "with green gates and the Reviewer's approval, and nothing but a mutation check "
                     "may have happened since")}

      (not (gated-current? events))
      {:run-loop/error :not-gated-current
       :message (str "the gate worktree does not hold the roles' latest files — a dispatch or a refused "
                     "assembly came after the last gate run. `check`, then `run`, then merge")})))

(defn- git-in
  "git in `dir`, never throwing: `{:exit _ :out \"stdout and stderr\"}`."
  [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir dir :out :string :err :string :continue true} "git" args)]
    {:exit exit :out (str/trim (str out err))}))

(defn- commit-gate!
  "The one commit this run makes: everything in the gate worktree, on the gate
  branch. Returns the `:merge-commit` event's content.

  THE GATE WORKTREE, BECAUSE IT IS WHAT WAS JUDGED. It holds the assembled
  files after gate 0, plus the Architect's — the bytes every gate passed and
  the Reviewer read. A role's own worktree holds what the role wrote, which
  gate 0 may have changed; a merge taken from there once shipped a file its
  own format gate had never seen.

  AND ONLY IF IT IS STILL WHAT WAS REVIEWED. The Reviewer's diff is in the run
  directory; the gate worktree's diff is taken again here, and a difference
  refuses the merge. `gated-current?` reads events, and an edit made by hand in
  the gate worktree leaves none."
  [ctx gate decision]
  (let [state @(:state ctx)
        root (:worktree/git-root gate)
        review (last (filter #(and (= :dispatch (:event/kind %)) (= :reviewer (:role %))) (:events state)))
        reviewed-file (run-file ctx (str (name (:event/step review)) ".diff"))
        reviewed (when (fs/exists? reviewed-file) (slurp reviewed-file))
        now-diff (prov/review-diff gate)]
    (when-not (= reviewed now-diff)
      (throw (ex-info (str "the gate worktree is not what the Reviewer read — its diff against HEAD differs from "
                           (fs/file-name reviewed-file) ". `check` again, so the gates and a review see what would merge")
                      {:run-loop/error :changed-since-review :reviewed-diff reviewed-file})))
    (git-in root "add" "-A")
    (let [msg (str (:task/id (:spec state)) " — run " (:run/id (:config state)))
          {:keys [exit out]} (git-in root "commit" "-q" "-m" msg)]
      (when-not (zero? exit)
        (throw (ex-info "the gate worktree could not be committed" {:run-loop/error :commit-failed :out out})))
      {:branch (or (:worktree/branch gate) (prov/branch-name (:task/id gate) (:task/role gate)))
       :commit (:out (git-in root "rev-parse" "HEAD"))
       :files (str/split-lines (:out (git-in root "diff" "--name-only" "HEAD~1" "HEAD")))
       :message msg
       :decision decision})))

(defn merge!
  "Merge the run: one commit of the gate worktree on its branch, that branch
  merged `--no-ff` into whatever the base checkout has checked out, then
  `record!` and `teardown!`.

  BEHIND A DECISION FILE, LIKE EVERY OTHER JUDGEMENT HERE. `{:decision \"...\"}`
  is written before the command runs and goes on the `:merge-commit` event,
  which is recorded BEFORE the merge: the reason stands as it was given, not as
  it read once the outcome was known. Denying a merge is not a command — the run
  stays `:awaiting-merge` with its worktrees and branches in place.

  A MERGE THAT FAILS TEARS NOTHING DOWN and leaves the base checkout as it
  found it: the merge is aborted, the failure is an event with git's output on
  it, and the command can be run again — the commit is not made twice.

  ONE COMMIT, AT MERGE TIME. Nothing is committed on a task branch while the
  loop runs, so a task's history on the base branch is one commit and one
  merge commit however many rounds it took; the rounds are in the record."
  [ctx decision-file]
  (let [decision (some-> decision-file slurp edn/read-string :decision)
        state @(:state ctx)
        repo-root (:repo/root (:config state))
        gate (:gate (:sessions state))]
    (when-let [{:keys [message] :as r} (merge-refusal state decision)]
      (throw (ex-info message (dissoc r :message))))
    (when-not (some-> (:worktree/git-root gate) fs/exists?)
      (throw (ex-info "the gate worktree is gone — there is nothing to commit and merge"
                      {:run-loop/error :no-gate-worktree})))
    (let [committed (or (last (filter #(= :merge-commit (:event/kind %)) (:events state)))
                        (let [c (commit-gate! ctx gate decision)]
                          (event! ctx (assoc c :event/kind :merge-commit))
                          c))
          into-branch (:out (git-in repo-root "rev-parse" "--abbrev-ref" "HEAD"))
          _ (println (str "\n  merging " (:branch committed) " (" (subs (:commit committed) 0 7) ", "
                          (count (:files committed)) " file(s)) into " into-branch " at " repo-root))
          {:keys [exit out]} (git-in repo-root "merge" "--no-ff" "--no-edit" (:branch committed))]
      (when-not (zero? exit)
        (git-in repo-root "merge" "--abort")
        (event! ctx {:event/kind :merge-failed :branch (:branch committed) :into into-branch
                     :out (first (cap-transcript [out]))})
        (throw (ex-info (str "git could not merge " (:branch committed) " into " into-branch
                             " — the merge was aborted, nothing was torn down, and the commit stands on its branch")
                        {:run-loop/error :merge-failed :out out})))
      (event! ctx {:event/kind :merged :branch (:branch committed) :into into-branch
                   :commit (:commit committed)
                   :merge-commit (:out (git-in repo-root "rev-parse" "HEAD"))
                   :decision decision})
      (println (str "  merged. " decision))
      (record! ctx)
      (teardown! ctx))))

;; ---------------------------------------------------------------------------

(def usage
  "usage: bb run-loop spec-review <run-dir>                    the contract, read cold by the Reviewer's model — before start
       bb run-loop run <run-dir>                            the loop, to its next stop
       bb run-loop <start|check|record> <run-dir>
       bb run-loop teardown <run-dir> [--keep-branches|--discard]  the worktrees, and a recorded run's branches; record first
       bb run-loop continue <run-dir> [<decision.edn>]
       bb run-loop merge <run-dir> <decision.edn>              {:decision \"why this merges\"}
       bb run-loop retry <run-dir> <role> <triage.edn> [--allow-leak]
       bb run-loop amend <run-dir> <spec-before.edn> <reason>
       bb run-loop mutation <run-dir> <mutation.edn>")

(defn context
  "Everything a command needs. `start` resolves loop.edn; every later command
  uses the config `start` kept in state.edn, so editing loop.edn mid-run changes
  nothing."
  [run-dir]
  (let [run-dir (str (fs/absolutize run-dir))
        sf (str (fs/path run-dir "state.edn"))
        state (when (fs/exists? sf) (edn/read-string (slurp sf)))
        ;; ONLY WHEN THERE IS NO STATE YET. Asking git on every command made a
        ;; run directory copied out of the repository unreadable, found
        ;; replaying an older run's state through `record`.
        cfg (or (:config state)
                (let [raw (edn/read-string (slurp (str (fs/path run-dir "loop.edn"))))
                      ws (workspace/find-workspace run-dir)]
                  (resolve-config run-dir (:repo/root (project-root run-dir raw ws)) raw ws)))]
    {:run-dir run-dir :config cfg :state (atom state)}))

(defn- run-command!
  "One command against a run directory; every refusal is a sentence and exit 1."
  [cmd run-dir args]
  (try
    (let [ctx (context run-dir)
          need-state (fn [] (when-not @(:state ctx)
                                ;; The commonest place an Architect amends is the spec-review stop,
                                ;; which is BEFORE start; two projects met this refusal there.
                              (throw (ex-info (str "no state.edn — run `start` first. Before `start`, amend by editing "
                                                   "spec.edn in place (keep the previous file beside it if you want the diff); "
                                                   "the next `start` reviews the edited spec. `amend` records an amendment "
                                                   "against a started run.")
                                              {:run-loop/error :not-started}))))]
      (case cmd
        "spec-review" (spec-review/spec-review! ctx)
        "start" (start! ctx)
          ;; A `check` TYPED BY HAND DECIDES NOTHING, and says so. The verdict is read in
          ;; one place, `orchestrate/next-action`, and `merge` accepts only a run the loop
          ;; stopped for it — so an approval got this way is not yet mergeable, and the
          ;; refusal was the first a person heard of it, twice in one afternoon.
        "check" (do (need-state) (check! ctx)
                    (println (str "\n  checked by hand: the loop has not read this result. `bb run-loop run "
                                  run-dir "` reads it — an approval stops for the merge, anything else is routed.")))
        "retry" (do (need-state) (apply retry! ctx args))
        "amend" (do (need-state) (apply amend! ctx args))
        "mutation" (do (need-state) (apply mutation! ctx args))
        "continue" (do (need-state) (apply continue! ctx args))
        "record" (do (need-state) (record! ctx))
        "merge" (do (need-state) (apply merge! ctx args))
        "teardown" (do (need-state) (teardown! ctx {:keep-branches? (boolean (some #{"--keep-branches"} args))
                                                    :discard? (boolean (some #{"--discard"} args))}))
        (do (println usage) (System/exit 2))))
    (catch clojure.lang.ExceptionInfo e
      (println "run-loop:" (ex-message e))
      (when-let [d (not-empty (dissoc (ex-data e) :run-loop/error))] (pp/pprint d))
      (System/exit 1))))

(defn -main [& [cmd run-dir & args]]
  (if-not (and cmd run-dir)
    (do (println usage) (System/exit 2))
    ;; THE RUN'S WORKSPACE IS THE COMMAND'S, whatever folder the KIT is in: the rules
    ;; every role is rendered from are found through it (`rules/load-rules`), not by
    ;; walking up from harness/ - which from a KIT kept outside its workspace finds
    ;; nothing and reads the source alone, placeholders and all.
    (binding [workspace/*of-run* (workspace/find-workspace run-dir)]
      (run-command! cmd run-dir args))))
