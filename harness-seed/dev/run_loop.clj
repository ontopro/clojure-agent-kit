(ns run-loop
  "`bb run-loop <command> <run-dir> [args]` — the loop's steps as commands, with a
  human doing triage between them.

  NOT THE ORCHESTRATION LOOP, and that is deliberate. §12 says not to build the
  loop speculatively, and `README.md` still lists it as left out. This is what the
  loop IS when a human routes every failure: provision, dispatch, assemble, gate,
  review — each a command — and a written triage decision before any retry. It
  was a throwaway script for runs D3 to D6 and one script carried forward through
  D7, D8 and D9, gaining only additive changes. That is the evidence it has a
  shape worth committing; `DEVLOG.md` has the history.

  A run directory holds everything one run needs and produces:

    spec.edn         the task spec — one entry of the Architect's task list
    loop.edn         {:run/id \"d10\" :profile \"resources/profiles/claude.edn\"
                      :project/subdir \"sandbox\"            ; optional
                      :architecture {:from \"arch\" :files [\"layers.edn\"]} ; optional
                      :worktrees/dir \"/tmp/...\"            ; optional
                      :gates [[:fmt \"bb fmt-check\"] ...]   ; optional
                      :nrepl/cmd [\"clojure\" \"-M:nrepl\"]}  ; optional
    state.edn        written by the commands; the run so far
    run.edn          written by `record`; what `bb report` reads, with the files in final/
    <step>.packet.edn, <step>.transcript.edn, reviewer*.diff, final/
                     what each dispatch was given, said and ran, and made

  `:profile` resolves against the working directory (the profiles live in
  `resources/`); every other relative path in `loop.edn` resolves against the run
  directory. The profile is READ ONCE, at `start`, and kept in `state.edn`: the
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
    teardown  <run-dir>                       remove the worktrees; branches stay

  WHAT EACH DECISION IN HERE COST TO LEARN — the runs are in `RUNS.md`:
    one state file and one clock (D6 printed a wall time below its own steps);
    the Reviewer's diff from `provision/review-diff` (D5 and D6 were shown no code);
    notes, feedback and triage kept as `:run/events` (D7, so a document can quote them);
    a red gate's output and the final files in the record too (the claim audit
      found D13's gate output and every quoted docstring only in `.local/`);
    dispatch steps from `report/dispatch-step`, which carries the service tier (flex);
    the timing split in every dispatch event (the Tester's slowness, 2026-09-14);
    a triage decision written before its dispatch, so it cannot be fitted afterwards;
    a red gate's proposed routing recorded beside that decision, and the Tester's
      feedback checked for the implementation's names (nine hand decisions, D7–D17);
    the gate worktree's bytes in the record, and what gate 0 changed (D19's merge
      failed on the written bytes; D20's gate 0 removed a bracket nobody saw)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [harness.gates :as gates]
   [harness.packet :as packet]
   [harness.profile :as profile]
   [harness.provision :as prov]
   [harness.repair :as repair]
   [harness.report :as report]
   [harness.runner :as runner]
   [harness.sigs :as sigs]
   [harness.stub :as stub]))

;; ---------------------------------------------------------------------------
;; pure: configuration, names, the record
;; ---------------------------------------------------------------------------

(defn resolve-config
  "`loop.edn` with its defaults filled in and its paths made absolute.

  Throws rather than guessing when `:run/id` or `:profile` is missing: a run
  record with an invented id, or a dispatch to a profile nobody chose, is the
  kind of plausible default this repository keeps finding in its own history."
  [run-dir repo-root cfg]
  (doseq [k [:run/id :profile]]
    (when-not (get cfg k)
      (throw (ex-info (str "loop.edn needs " k)
                      {:run-loop/error :missing-config :key k :run-dir run-dir}))))
  (let [in-run (fn [path] (str (fs/absolutize (fs/path run-dir path))))]
    (cond-> (merge {:project/subdir nil
                    :pause-on-notes? true
                    :gates gates/default-gate-seq
                    :nrepl/cmd ["clojure" "-M:nrepl"]
                    :worktrees/dir (str (fs/path (fs/temp-dir) "run-loop" (:run/id cfg)))}
                   cfg
                   {:repo/root repo-root
                    :profile (str (fs/absolutize (:profile cfg)))})
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
  "The attempt a retry of `role` would be, or a throw once the packet's retry
  cap is spent — at which point the task escalates to a human rather than being
  dispatched again."
  [attempts role retry-cap]
  (let [attempt (inc (get attempts role 1))]
    (when (> attempt retry-cap)
      (throw (ex-info (str "retry cap " retry-cap " reached for " (name role)
                           " — escalate, do not dispatch")
                      {:run-loop/error :retry-cap :role role :cap retry-cap})))
    attempt))

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
      (:cost-partial m) (assoc :cost-partial (:cost-partial m))
      (:transcript m) (assoc :transcript (cap-transcript (:transcript m)))
      ;; A list-price cost is re-derivable only if the counts and the rates
      ;; travel with it.
      (:cost-source m) (assoc :cost-source (:cost-source m) :usage (:usage m))
      (:pricing m) (assoc :pricing (:pricing m))
      (:error m) (assoc :error (str (:error m))))))

(defn pause?
  "Whether `start` stops after a dispatch, before the next one.

  WHEN THE DISPATCH LEFT NOTES. D14's Coder noted, exactly, that the contract would
  break `property_test.clj` — and the loop dispatched the Tester and ran the gates
  into the failure it had been told about. A note is a role saying something about
  the contract that someone must decide on, and the next dispatch builds on the
  contract as it stands. So the run stops, prints the notes, and waits: `continue`
  to carry on, or `amend` and `retry` first. On unless `loop.edn` says
  `:pause-on-notes? false`."
  [cfg result]
  (boolean (and (not (false? (:pause-on-notes? cfg)))
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

(defn run-record
  "The run record `bb report` reads, from the state the commands built.

  Wall time runs to the end of the last dispatch or gate run, not to whenever
  `record` was typed: mutation checks and write-ups after the run are not the run."
  [state]
  (cond-> {:run/id (:run/id (:config state))
           :task/id (:task/id (:spec state))
           :run/status (if (:gates/passed? (:last-gates state)) :done :failed)
           :run/attempts (apply max 1 (vals (:attempts state)))
           :run/wall-ms (apply max 0 (keep #(when (#{:dispatch :gates} (:event/kind %)) (:event/at-ms %))
                                           (:events state)))
           :run/steps (:steps state)
           :run/events (:events state)}
    ;; the code the run produced, so a document can quote it from the record
    (seq (:final-files state)) (assoc :run/files (:final-files state))
    ;; and, where gate 0 changed a file, what the role wrote before it did
    (seq (:files-as-written state)) (assoc :run/files-as-written (:files-as-written state))))

(defn gated-current?
  "Whether the gate worktree holds the Coder's and Tester's latest files: a gate
  run came after every Coder or Tester dispatch, and no refused assembly after
  it. Only then is the gate worktree `record`'s source (NOTES.md row 14)."
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
  "The event a gate run leaves. A red one carries the failing gate's own output:
  D13's record said only that the test gate failed, and the token it failed on
  was in a gitignored file nobody else could read."
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
  file (D12's said `parse.clj:25` for a failure in `property_test.clj`). So a
  failure belongs to the `Testing` line above it."
  [out]
  (->> (str/split-lines (str out))
       (reduce (fn [[current found] line]
                 (if-let [[_ ns] (re-matches #"\s*Testing (\S+)\s*" line)]
                   [ns found]
                   (if (and current (re-find #"^\s*(FAIL|ERROR) in " line))
                     [current (conj found current)]
                     [current found])))
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
  "What a red gate suggests about who owns it — NOTES.md row 1.

  Nine hand triage decisions over D7–D17 route three ways that are mechanical:
  the `:calls` check is the Coder's or the slice's; a failure in a file only the
  Tester owns is the Tester's; one in a file no role owns (D12's dependent) is
  the contract's or the Coder's. This says which, from the failing gate's own
  output and the spec's ownership, and NOTHING is dispatched on it: `retry`
  still takes a written triage decision, and the two sit side by side in the
  record so a later reader can see where the proposal and the decision differ.
  A `:reviewer` or `:triage` routing is judgement and has no proposal.

  `:owner` is `:coder` when every file named is an impl file, nil when a
  dependent, both roles' files, or nothing is named, and — when every file named
  is a test file — the Tester only if that file failed to read or compile, or
  the gate is not the test gate (a format or lint failure in its own file).

  AN ASSERTION THAT FAILS IN THE TASK'S OWN TEST IS THE CODER'S BY DEFAULT
  (NOTES.md row 17). This proposed the Tester for any failure naming only a test
  file, and an assertion failure always names only its test file, so every red
  test was proposed as the Tester's — against method §07: \"Default ownership of a
  failing test is the Coder\". D20's first live proposal was the Tester, and right,
  for a reason no rule sees: the test mis-encoded an ambiguous target. That stays
  a triage decision, and the advice says so."
  [{:keys [files/impl files/test]} dependents gate-result]
  (let [{:keys [gate out]} (first (filter #(= :fail (:status %)) (:gates/report gate-result)))
        out (str out)
        impl-hit (names-in out impl)
        test-hit (names-in out test)
        dep-hit (names-in out dependents)
        owner (cond
                (= :calls gate) :coder
                (and (seq test-hit) (empty? impl-hit) (empty? dep-hit))
                (if (and (= :test gate) (not (re-find #"Syntax error" out))) :coder :tester)
                (and (seq impl-hit) (empty? test-hit) (empty? dep-hit)) :coder
                :else nil)]
    {:gate gate
     :files-named (vec (concat impl-hit test-hit dep-hit))
     :owner owner
     :retry-role (or owner :coder)
     :sources (if (or owner (= :calls gate)) [:gate] [:architect :gate])
     :advice (cond
               (= :calls gate)
               "the slice's omission: `amend`, then `check` again; the Coder's: `retry coder` with the calls as :gate feedback"
               (and (= :coder owner) (seq test-hit))
               "a failing test in the task's own tests, which is the Coder's by default (method §07): `retry coder` with the gate output as :gate feedback. If the test mis-encodes the contract, that is a triage decision — `amend` where the wording allowed it, then `retry tester`"
               (= :tester owner)
               "a file only the Tester owns that did not read, compile or pass format or lint: `retry tester` with the gate output as :gate feedback — after checking it names no implementation"
               (= :coder owner)
               "a file only the Coder owns: `retry coder` with the gate output as :gate feedback"
               :else
               "a file no role owns, or none named: the contract or the Coder. `amend`, then `retry coder` with :architect and :gate feedback (D12); or `retry coder` with :gate feedback alone")}))

(defn- token-re
  "`name` as a whole Clojure symbol: not preceded or followed by a symbol
  character, so `bind` matches `bind` and `expr/bind` but not `binding`."
  [nm]
  (re-pattern (str "(?<![\\w*+!?<>=$.\\-])" (java.util.regex.Pattern/quote (str nm)) "(?![\\w*+!?<>=$\\-])")))

(defn leaks
  "Where `feedback` for the Tester would carry the implementation — NOTES.md
  row 5. `impl-names` is `sigs/impl-names`. Four rules, each from a recorded
  Tester retry that was shielded by hand:

    :impl-path        an impl file's path or name (the file the Tester was kept from)
    :impl-var         a var the impl defines that the slice's :interfaces did not
                      grant, as a whole symbol
    :code-block       a fenced ``` block — D7's Reviewer finding quoted the impl
    :reviewer-source  :feedback/from :reviewer at all: the Reviewer read the diff,
                      so its text was derived from the implementation

  Returns `[{:feedback/from kw :leak kw :detail str}]`, empty when clean. A
  rendered value in gate output (D12 withheld `\"(((0\"`) is not a name, and
  this does not see it."
  [{:keys [files/impl]} impl-names feedback]
  (vec
   (for [{:keys [feedback/from feedback/text]} feedback
         :let [text (str text)]
         leak (concat
               (when (= :reviewer from)
                 [{:leak :reviewer-source :detail "the Reviewer's text is derived from the diff it read"}])
               (for [p impl :when (or (str/includes? text p) (str/includes? text (fs/file-name p)))]
                 {:leak :impl-path :detail (str "names " p)})
               (for [{:keys [ns name]} impl-names :when (re-find (token-re name) text)]
                 {:leak :impl-var :detail (str "names " ns "/" name ", which the slice's :interfaces do not grant")})
               (when (str/includes? text "```")
                 [{:leak :code-block :detail "carries a fenced code block"}]))]
     (assoc leak :feedback/from from))))

(defn final-files
  "Each of the spec's impl and test paths, mapped to its content in `final-dir`,
  for the files there. `record` copies them there, so a replay after the
  worktrees are gone still finds them.

  BY PATH, `final/<path>`, from NOTES.md row 14. Before that `record` copied by
  file name, so two paths with one name overwrote each other and a merge had to
  re-derive every path from the spec. A run directory written that way still
  reads — `final/<file-name>` when there is no `final/<path>` — so D7–D21 replay
  unchanged, and two such paths with one name are still refused rather than
  recorded as one file."
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

(defn- persist! [ctx]
  (spit (state-file ctx) (with-out-str (pp/pprint @(:state ctx)))))

(defn- step! [ctx m]
  (swap! (:state ctx) update :steps conj (merge {:step/source :measured} m))
  (persist! ctx))

(defn- event! [ctx m]
  (swap! (:state ctx) update :events conj
         (assoc m :event/at-ms (- (now) (:started-ms @(:state ctx)))))
  (persist! ctx))

(defn- spec-of [ctx]
  ;; Read fresh each command: an Architect's amendment is an edit to spec.edn,
  ;; recorded with `amend`. The profile is the one thing NOT re-read. The
  ;; dependents are computed once, at `start`, and applied on every read.
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
  because D20's second Tester attempt did exactly that, its final message said
  so, and `check` was run over the unchanged file anyway (NOTES.md row 18)."
  [role result]
  (boolean (and (#{:coder :tester} role) (= :done (:status result)) (empty? (:files result)))))

(defn- dispatch! [ctx step-name role pkt]
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
            ;; WHAT GATE 0 CHANGES IS RECORDED (NOTES.md row 14). It repairs and
            ;; formats the assembled files in place, and the gates and the
            ;; Reviewer see its result; D20's Tester wrote a file that did not
            ;; read, gate 0 removed the stray bracket, and nothing said so.
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
            ;; call only the signatures its slice granted (NOTES.md row 11)?
            ;; Cheap, harness-side, and a red here is either the Architect's
            ;; omission (`amend`, then `check` again — no dispatch) or the
            ;; Coder's (retry with this feedback).
            [calls msc] (timed #(sigs/undeclared-calls gate-dir sp))
            _ (step! ctx {:step/name :calls :step/kind :gate :step/ms msc
                          :step/status (if (empty? calls) :pass :fail)})
            _ (event! ctx {:event/kind :calls :violations (vec calls)})
            gr (if (empty? calls)
                 (gates/run-gates! gate-dir (:gates cfg))
                 {:gates/passed? false :gates/failed :calls
                  :gates/report [{:gate :calls :status :fail :ms msc :exit 1
                                  :out (str/join "\n" (map :detail calls))}]})]
        (doseq [{:keys [gate status ms]} (:gates/report gr)]
          (step! ctx {:step/name gate :step/kind :gate :step/status status :step/ms ms}))
        (swap! (:state ctx) assoc :last-gates gr)
        (event! ctx (gates-event gr))
        (println "\n  gates:" (str/join " " (for [{:keys [gate status]} (:gates/report gr)]
                                              (str (name gate) "=" (name status)))))
        (if (:gates/passed? gr)
          (let [diff (prov/review-diff (:gate s))
                prior (count (filter #(and (= :dispatch (:event/kind %)) (= :reviewer (:role %)))
                                     (:events @(:state ctx))))
                nm (reviewer-step-name prior)]
            (spit (run-file ctx (str (name nm) ".diff")) diff)
            (dispatch! ctx nm :reviewer (packet/reviewer-packet sp (:gate s) diff (:gates/report gr))))
          (let [fb (:feedback (gates-event gr))
                routing (propose-routing sp (:dependents @(:state ctx)) gr)]
            (spit (run-file ctx "last-gate-feedback.edn") (with-out-str (pp/pprint fb)))
            (doseq [{:keys [feedback/text]} fb]
              (println "\n" (subs text 0 (min 3000 (count text)))))
            ;; PROPOSED, NOT DECIDED. Recorded beside the triage decision that
            ;; follows, so the record shows where they differ (NOTES.md row 1).
            (event! ctx (assoc routing :event/kind :routing))
            (println (str "\n  proposed routing: " (name (:retry-role routing))
                          (if (:owner routing) " (owner)" " (no owner)")
                          ", files named " (pr-str (:files-named routing))
                          ", sources " (pr-str (:sources routing))
                          "\n    " (:advice routing)))))))))

(defn- proceed!
  "After a dispatch of `role` in `start`'s sequence: pause if it left notes,
  otherwise take the next step."
  [ctx role result]
  (if (pause? (:config @(:state ctx)) result)
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
    (case (next-step role)
      :tester (proceed! ctx :tester (dispatch! ctx :tester :tester (first-packet ctx :tester)))
      :check (check! ctx))))

(defn start! [ctx]
  (when (fs/exists? (state-file ctx))
    (throw (ex-info (str "state.edn exists — this run has started. If nothing was dispatched "
                         "(a failed provision or precondition), `teardown` resets it for another `start`.")
                    {:run-loop/error :already-started :run-dir (:run-dir ctx)})))
  (let [cfg (:config ctx)
        sp (spec-of ctx)]
    (reset! (:state ctx) {:started-ms (now) :steps [] :events [] :attempts {:coder 1 :tester 1}
                          :config cfg :spec sp
                          :profile (profile/read-profile (:profile cfg))})
    (persist! ctx)
    (let [o {:repo/root (:repo/root cfg) :worktrees/dir (:worktrees/dir cfg)
             :task/id (:task/id sp) :project/subdir (:project/subdir cfg)
             :nrepl/cmd (:nrepl/cmd cfg)}
          _ (fs/create-dirs (:worktrees/dir cfg))
          ;; EACH SESSION IS SAVED THE MOMENT IT EXISTS. They were saved together
          ;; after all three, so a third provision that threw left two worktrees and
          ;; their nREPLs running with no record `teardown` could find (review of
          ;; experiments/d7-d15, R4).
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
      ;; namespace. D12 broke a dependent no packet had shown.
      (let [[deps ms] (timed #(sigs/task-dependents (:worktree/path (:coder s)) sp))]
        (swap! (:state ctx) assoc :dependents deps)
        (event! ctx {:event/kind :dependents :files deps :ms ms})
        (println "  dependents:" (if (seq deps) (str/join ", " deps) "none")))
      (let [[v ms] (timed #(sigs/violations (:worktree/path (:coder s)) (:files/context (spec-of ctx))
                                            (:deps-sigs (:blueprint/slice sp))))]
        (step! ctx {:step/name :sigs :step/kind :precondition :step/ms ms
                    :step/status (if (seq v) :fail :pass)})
        (when (seq v)
          (throw (ex-info (str "the slice's :deps-sigs do not match the source — fix spec.edn, then "
                               "`bb run-loop teardown` (nothing was dispatched, so it resets the run) "
                               "and `start` again")
                          {:run-loop/error :precondition :violations v}))))
      (let [[path ms] (timed #(stub/write! (:worktree/path (:tester s)) (:files/impl sp) (:blueprint/slice sp)))]
        (step! ctx {:step/name :stub :step/kind :provision :step/ms ms :step/status :done})
        (swap! (:state ctx) assoc-in [:sessions :tester :harness/wrote] [path])
        (persist! ctx))
      (proceed! ctx :coder (dispatch! ctx :coder :coder (first-packet ctx :coder))))))

(defn last-dispatch-failed
  "The step name of the run's most recent dispatch, when it failed; else nil.
  D17's Coder retry failed on an API error (no credit) and `continue` went on
  to dispatch the Tester against the file the retry never rewrote."
  [state]
  (let [d (last (filter #(= :dispatch (:event/kind %)) (:events state)))]
    (when (= :failed (:status d)) (:event/step d))))

(defn decided-since-pause?
  "Whether an `amend` or a `retry` was recorded after the last pause — a decision
  that already carries its own reason (NOTES.md row 15)."
  [events]
  (let [since (reverse (take-while #(not= :paused (:event/kind %)) (reverse events)))]
    (boolean (some #(#{:amend :triage} (:event/kind %)) since))))

(defn continue!
  "Carry on from a pause, with whatever `start` would have done next. Any
  `amend` or `retry` made during the pause has already happened; this only
  resumes the sequence, and may pause again. Refused while the last dispatch
  failed: the next role would build on work that was never done.

  A DECISION THAT CHANGES NOTHING STILL SAYS WHY (NOTES.md row 15). D19 paused on
  a Coder note that was an observation, not a conflict, and was continued with
  nothing amended; the record said `:continued` and nothing else, and the reason
  lived in a local file. When no `amend` or `retry` followed the pause, a
  decision file `{:decision \"...\"}` is required, written before the dispatch
  like a triage file, and recorded on the `:continued` event."
  [ctx & [decision-file]]
  (let [{:keys [after next]} (:paused @(:state ctx))
        decision (some-> decision-file slurp edn/read-string :decision)]
    (when-not next
      (throw (ex-info "this run is not paused — nothing to continue"
                      {:run-loop/error :not-paused})))
    (when-let [step (last-dispatch-failed @(:state ctx))]
      (throw (ex-info (str "the last dispatch, " (name step) ", failed — retry it before continuing")
                      {:run-loop/error :last-dispatch-failed :step step})))
    (when (and decision-file (str/blank? decision))
      (throw (ex-info (str decision-file " has no :decision — say why the run continues")
                      {:run-loop/error :no-decision})))
    (when-not (or decision (decided-since-pause? (:events @(:state ctx))))
      (throw (ex-info (str "nothing was amended or retried since the pause — say why the run continues: "
                           "continue <run-dir> <decision.edn>, with {:decision \"...\"}")
                      {:run-loop/error :no-decision})))
    (swap! (:state ctx) dissoc :paused)
    (event! ctx (cond-> {:event/kind :continued :after after :next next}
                  decision (assoc :decision decision)))
    (case next
      :tester (proceed! ctx :tester (dispatch! ctx :tester :tester (first-packet ctx :tester)))
      :check (check! ctx))))

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

(defn retry!
  "The triage file's decision is recorded BEFORE the dispatch, so it stands as a
  prediction and cannot be fitted to the outcome afterwards.

  THE TESTER'S FEEDBACK IS CHECKED FIRST (NOTES.md row 5). Every Tester retry
  in D7, D8 and D12 was shielded by hand from the Reviewer's findings and the
  implementation's names; `leaks` is that shield as a rule, and `--allow-leak`
  is the judgement to pass one anyway, recorded on the triage event either way."
  [ctx role-name triage-file & flags]
  (let [role (keyword role-name)
        {:keys [decision feedback]} (edn/read-string (slurp triage-file))
        base (first-packet ctx role)
        attempt (next-attempt (:attempts @(:state ctx)) role (:retry-cap (:gates base)))
        checked (leak-check role (spec-of ctx)
                            (when (= :tester role)
                              (sigs/impl-names (:worktree/path (:coder (sessions ctx))) (spec-of ctx)))
                            feedback (some #{"--allow-leak"} flags))]
    (event! ctx (cond-> {:event/kind :triage :role role :attempt attempt :decision decision}
                  checked (assoc :leak-check checked)))
    (swap! (:state ctx) assoc-in [:attempts role] attempt)
    (persist! ctx)
    (dispatch! ctx (retry-step-name role attempt) role (packet/for-retry base attempt feedback))))

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
        ;; the Tester is dispatched with next (review of experiments/d7-d15, R3).
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

  THE GATED BYTES, NOT THE WRITTEN ONES (NOTES.md row 14). This copied each
  role's own worktree, while gate 0 repairs and formats in the gate worktree
  that the gates, the Reviewer and a merge all read: D19's `final/` failed the
  stage's format gate, and five committed records hold a file their format gate
  did not pass as recorded. So `final/<path>` is the gate worktree's copy
  whenever that copy is current, and `final/as-written/<path>` keeps the role's
  own bytes for each file gate 0 changed."
  [ctx]
  (let [spec (spec-of ctx)
        state @(:state ctx)
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
    (let [out (run-record (assoc state :spec spec
                                 :final-files (final-files spec final-dir)
                                 :files-as-written (files-as-written spec final-dir)))]
      (spit (run-file ctx "run.edn") (pr-str out))
      (println "wrote run.edn — wall" (quot (:run/wall-ms out) 1000) "s"))))

(defn- delete-branch!
  "Delete a task branch, if it exists. Only ever called for a run that dispatched
  nothing, so the branch holds no agent's work."
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
  "Remove the run's worktrees; their branches stay, so the work is reachable.

  A RUN THAT DISPATCHED NOTHING IS RESET INSTEAD. After a failed provision or a
  refused precondition there is no work to keep, and the kept branches and
  state.edn made `start` impossible: `start` refused on state.edn, and
  `git worktree add -b` refused on the branches (review of experiments/d7-d15,
  R4). So such a run also loses its task branches and state.edn, and `start` can
  run again."
  [ctx]
  (let [state @(:state ctx)
        repo-root (:repo/root (:config state))]
    (doseq [k [:coder :tester :gate]
            :let [s (get (sessions ctx) k)]
            :when s]
      (println k (:torn-down? (prov/teardown! s repo-root))))
    (when-not (dispatched? state)
      (let [task-id (:task/id (:spec state))]
        (doseq [role [:coder :tester :reviewer]
                :let [branch (prov/branch-name task-id role)]]
          (println "  branch" branch (if (delete-branch! repo-root branch) "deleted" "absent")))
        (fs/delete-if-exists (state-file ctx))
        (reset! (:state ctx) nil)
        (println "  nothing was dispatched: state.edn removed, so `start` can run again")))))

;; ---------------------------------------------------------------------------

(def usage
  "usage: bb run-loop <start|check|record|teardown> <run-dir>
       bb run-loop continue <run-dir> [<decision.edn>]
       bb run-loop retry <run-dir> <role> <triage.edn> [--allow-leak]
       bb run-loop amend <run-dir> <spec-before.edn> <reason>
       bb run-loop mutation <run-dir> <mutation.edn>")

(defn- context
  "Everything a command needs. `start` resolves loop.edn; every later command
  uses the config `start` kept in state.edn, so editing loop.edn mid-run changes
  nothing."
  [run-dir]
  (let [run-dir (str (fs/absolutize run-dir))
        sf (str (fs/path run-dir "state.edn"))
        state (when (fs/exists? sf) (edn/read-string (slurp sf)))
        ;; ONLY WHEN THERE IS NO STATE YET. Asking git on every command made a
        ;; run directory copied out of the repository unreadable, found replaying
        ;; D7–D9's state through `record`.
        repo-root (fn []
                    (let [{:keys [exit out]} (p/shell {:out :string :err :string :dir run-dir
                                                       :continue true}
                                                      "git" "rev-parse" "--show-toplevel")]
                      (when-not (zero? exit)
                        (throw (ex-info "the run directory is not inside a git repository"
                                        {:run-loop/error :no-repo :run-dir run-dir})))
                      (str/trim out)))
        cfg (or (:config state)
                (resolve-config run-dir (repo-root)
                                (edn/read-string (slurp (str (fs/path run-dir "loop.edn"))))))]
    {:run-dir run-dir :config cfg :state (atom state)}))

(defn -main [& [cmd run-dir & args]]
  (if-not (and cmd run-dir)
    (do (println usage) (System/exit 2))
    (try
      (let [ctx (context run-dir)
            need-state (fn [] (when-not @(:state ctx)
                                (throw (ex-info "no state.edn — run `start` first"
                                                {:run-loop/error :not-started}))))]
        (case cmd
          "start" (start! ctx)
          "check" (do (need-state) (check! ctx))
          "retry" (do (need-state) (apply retry! ctx args))
          "amend" (do (need-state) (apply amend! ctx args))
          "mutation" (do (need-state) (apply mutation! ctx args))
          "continue" (do (need-state) (apply continue! ctx args))
          "record" (do (need-state) (record! ctx))
          "teardown" (do (need-state) (teardown! ctx))
          (do (println usage) (System/exit 2))))
      (catch clojure.lang.ExceptionInfo e
        (println "run-loop:" (ex-message e))
        (when-let [d (not-empty (dissoc (ex-data e) :run-loop/error))] (pp/pprint d))
        (System/exit 1)))))
