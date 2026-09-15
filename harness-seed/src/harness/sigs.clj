;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.sigs
  "The Blueprint's `:deps-sigs`, checked against the source it describes.

  WHY THIS EXISTS. `:deps-sigs` is how a slice says *these are the upstream
  functions you may call* — the rule `:packet-scope` sends an agent to it and
  nowhere else, which is what keeps a task from reaching into an
  implementation it was never given. It is a CLAIM about code the agent cannot
  see the source of, and until this namespace nothing ever checked the claim.

  Run 3 supplied the evidence. Its spec declared `(sandbox.expr/operators [])`
  — a zero-arity call signature for something that is a map. It rode through
  packet validation, two dispatches, four gates and review, and nothing looked.
  A wrong signature is worse than a missing one: the agent is told a function
  exists, writes against it, and the failure surfaces as the Coder's fault two
  gates later. Run 4 put two such errors in a spec deliberately; both were
  caught in 56ms, before either agent was dispatched.

  THIS IS A PRECONDITION ON DISPATCH, not a gate. It runs once the worktree
  exists — the context files have to be on disk to be read — and before either
  role is dispatched, because its whole value is catching an Architect's error
  before two agents spend a cycle on it. `harness.provision/scope-violations`
  is the same shape at the other end of the loop: data out, and the caller
  decides whether to refuse.

  IT NEVER GUESSES. A check that cries wolf about a correct packet would be
  turned off within a week, so every case it cannot resolve with confidence —
  an unqualified name defined in two context files, a context file that did
  not parse — is silently not a violation. It reports what it is sure of.

  IT READS clj-kondo's ANALYSIS rather than parsing the source itself. The
  first version hand-rolled a reader over `defn`/`def`/`defprotocol` forms;
  clj-kondo already computes exactly this, is already a REQUIRED tool here
  (`bb doctor` checks it, the lint gate runs it), and knows about the forms a
  hand-rolled reader silently ignores — `defrecord`'s generated `->R` and
  `map->R`, `(def f (fn [x y]))`, `defmulti`, macros. Shelling out to a tool
  the loop already depends on beats sixty lines of parser with its own bugs.

  THIS IS A STACK-SPECIFIC NAMESPACE, the third of three. It reads Clojure
  source; `harness.repair` repairs it and `harness.stub` emits it. A reader on
  another stack rewrites those three files and nothing else."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [harness.doctor :as doctor]))

;; ---------------------------------------------------------------------------
;; what the context files define
;; ---------------------------------------------------------------------------

(defn- kondo
  "clj-kondo's analysis of `paths`, relative to `dir`.

  `:continue true` because clj-kondo exits 2 on warnings and 3 on errors, and
  a file with a lint warning still analyses fine — the exit code is about
  findings, which this does not read except for `:syntax`. One invocation for
  every file rather than one each: the analysis carries `:filename`, so there
  is nothing to gain by separating them."
  [dir paths]
  (doctor/require-tool! :clj-kondo)
  (let [{:keys [out]} (apply p/shell {:dir dir :out :string :err :string
                                      :continue true}
                             "clj-kondo" "--config"
                             "{:output {:format :edn :analysis true}}"
                             "--lint" paths)]
    (try
      (edn/read-string out)
      (catch Exception e
        (throw (ex-info "clj-kondo produced no readable analysis"
                        {:dir dir :paths (vec paths) :out out} e))))))

(defn- kind-of
  "Whether a var-definition can be CALLED, and with what.

  Derived from the analysis rather than from a list of known `def` forms: a
  var with arities is a function however it was defined, and `defmulti` takes
  whatever its methods take, so claiming a mismatch there would be a guess."
  [v]
  (cond
    (= 'clojure.core/defmulti (:defined-by v)) :open
    (or (:fixed-arities v) (:varargs-min-arity v)) :fn
    :else :value))

(defn definitions
  "What `paths` define, read out of clj-kondo's analysis.

  Returns `{:defs {ns {name info}} :ns-of {path ns} :unparsed #{path}}`.

  `:unparsed` is the files clj-kondo could not read. Their partial analysis is
  discarded rather than used: a var it never reached would be reported as
  undefined, which is exactly the false accusation this must not make."
  [dir paths]
  (let [{:keys [analysis findings]} (kondo dir paths)
        ;; BY PATH, NOT BY FILE NAME. The first version keyed this on the bare
        ;; file name, so two context files called `core.clj` in different
        ;; directories collapsed into one, and the namespace of the other was
        ;; reported as unknown. Once `sigs/dependents` began adding files to
        ;; :files/context, that could refuse a correct spec with nothing the
        ;; Architect wrote (review of experiments/d7-d15, R1). clj-kondo reports
        ;; each file as it was given, relative to `dir`; normalising both sides
        ;; covers `./src/...`, and an absolute report is made relative to `dir`.
        rel (fn [f]
              (let [n (fs/normalize f)]
                (str (if (fs/absolute? n) (fs/relativize (fs/absolutize dir) n) n))))
        file-of (into {} (map (juxt rel identity)) paths)
        norm (fn [f] (get file-of (rel f) f))
        unparsed (into #{} (comp (filter #(= :syntax (:type %)))
                                 (map #(norm (:filename %))))
                       findings)
        ns-of (into {} (keep (fn [{:keys [name filename]}]
                               (let [p (norm filename)]
                                 (when-not (unparsed p) [p name]))))
                    (:namespace-definitions analysis))
        live (set (vals ns-of))]
    {:ns-of ns-of
     :unparsed unparsed
     :defs (reduce (fn [acc v]
                     (if (contains? live (:ns v))
                       (assoc-in acc [(:ns v) (:name v)]
                                 {:kind (kind-of v)
                                  :private? (boolean (:private v))
                                  :fixed (:fixed-arities v)
                                  :variadic-min (:varargs-min-arity v)})
                       acc))
                   {}
                   (:var-definitions analysis))}))
(defn parse-sig
  "One `:deps-sigs` entry as data.

  Two forms, mirroring `:shapes`. `(ns/f [a b])` is a CALL SIGNATURE and its
  arity is checked. `(ns/f)` or a bare `ns/f` NAMES a var the slice grants
  read access to — `expr/operators` is a map and a legitimate thing to hand an
  agent — so only its existence and its privacy are checked.

  Returns nil for anything else, because an entry this cannot read is an entry
  it must not accuse."
  [form]
  (let [named (fn [sym argv]
                (when (symbol? sym)
                  {:sig/ns (some-> (namespace sym) symbol)
                   :sig/name (symbol (name sym))
                   :sig/argv argv
                   :sig/form form}))]
    (cond
      (symbol? form) (named form nil)
      (and (seq? form) (= 1 (count form))) (named (first form) nil)
      (and (seq? form) (vector? (second form))) (named (first form) (second form))
      :else nil)))

;; ---------------------------------------------------------------------------
;; the check
;; ---------------------------------------------------------------------------

(defn- accepts?
  [{:keys [fixed variadic-min kind]} n]
  (boolean (or (= :open kind)
               (contains? (or fixed #{}) n)
               (and variadic-min (>= n variadic-min)))))

(defn- check-sig
  "One signature against what the context files define, or nil when there is
  nothing it can say with confidence."
  [sig {:keys [defs unparsed]}]
  (let [{:keys [sig/ns sig/name sig/argv sig/form]} sig
        base {:sig form}
        candidates (if ns
                     (when-let [d (get defs ns)] [[ns d]])
                     (filterv (fn [[_ d]] (contains? d name)) defs))]
    (cond
      ;; A file that did not parse may define anything, so an unqualified name
      ;; cannot be resolved against the rest and a qualified one cannot be
      ;; declared unknown. Both are silence, and the unparsed file is reported
      ;; on its own account.
      (and (seq unparsed) (nil? (get defs ns))) nil

      (and ns (nil? candidates))
      (assoc base :violation :unknown-namespace
             :detail (str "no :files/context file declares " ns))

      ;; Ambiguous, so unresolvable without guessing — and guessing is how a
      ;; check earns its way into being switched off.
      (> (count candidates) 1) nil

      :else
      (let [[found-ns d] (first candidates)
            v (get d name)]
        (cond
          (nil? v)
          (assoc base :violation :unknown-var
                 :detail (str (or found-ns "no :files/context file") " defines no " name))

          (:private? v)
          (assoc base :violation :private-var
                 :detail (str found-ns "/" name " is private and cannot be called across the seam"))

          (and argv (= :value (:kind v)))
          (assoc base :violation :not-a-function
                 :detail (str found-ns "/" name " is a value, not a function — drop the arg vector"))

          (and argv (not (accepts? v (count argv))))
          (assoc base :violation :arity-mismatch
                 :detail (str found-ns "/" name " takes "
                              (str/join " or " (sort (:fixed v)))
                              (when (:variadic-min v) " or more")
                              ", not " (count argv)))
          :else nil)))))

(defn violations
  "Every `:deps-sigs` entry that its own `:files/context` contradicts.

  `dir` is the worktree; `context-files` and `deps-sigs` come from the spec.
  Returns a vector of `{:sig form :violation kw :detail str}`, empty when the
  slice tells the truth.

  A context file that is absent, or that clj-kondo could not parse, is
  reported on its own account. Silently not verifying is the failure this
  namespace exists to end, so a check that could not run has to say so."
  [dir context-files deps-sigs]
  (let [{missing false present true} (group-by #(fs/exists? (fs/path dir %)) context-files)
        analysis (if (seq present) (definitions dir present) {:defs {} :unparsed #{}})
        note (fn [kind detail] (fn [p] {:sig p :violation kind :detail (str p " " detail)}))]
    (-> []
        (into (map (note :missing-context-file
                         "is not in the worktree — nothing could be checked against it"))
              (sort missing))
        (into (map (note :unparsed-context-file
                         "does not parse — its definitions were not used"))
              (sort (:unparsed analysis)))
        (into (keep #(some-> (parse-sig %) (check-sig analysis)))
              deps-sigs))))

(defn undeclared-calls
  "Every call the implementation makes into a context namespace that its
  `:deps-sigs` did not grant — NOTES.md row 11.

  `violations` checks the slice against the source; this checks the source
  the Coder wrote against the slice. Only calls INTO the namespaces the
  context files define count: clojure.core, libraries and the impl's own
  namespace are not seams the packet governs, and a namespace outside the
  context altogether is gate 4's business. A call to a var no entry names is
  `:undeclared-call`; a call whose arity the entry's argv does not accept is
  `:arity-mismatch`. A missing impl file is reported, never skipped.

  Returns `[{:call sym :arity n :file path :line n :violation kw :detail str}]`."
  [dir {:keys [files/impl files/context blueprint/slice]}]
  (let [{missing false present true} (group-by #(fs/exists? (fs/path dir %)) impl)
        granted (into {} (keep (fn [s] (when-let [{:sig/keys [ns name argv]} (parse-sig s)]
                                         [[ns name] argv])))
                      (:deps-sigs slice))
        seams (set (vals (:ns-of (definitions dir context))))
        usages (when (seq present) (:var-usages (:analysis (kondo dir present))))
        accepts (fn [argv n]
                  (or (nil? argv)
                      (let [[fixed [_ & more]] (split-with #(not= '& %) argv)]
                        (if (seq more) (>= n (count fixed)) (= n (count fixed))))))]
    (-> []
        (into (map (fn [p] {:call nil :arity nil :file p :line nil :violation :missing-impl-file
                            :detail (str p " is not in the worktree — nothing could be checked")}))
              (sort missing))
        (into (comp (filter #(and (contains? seams (:to %)) (not= (:to %) (:from %))))
                    (keep (fn [{:keys [to name arity row filename]}]
                            (let [call (symbol (str to) (str name))
                                  base {:call call :arity arity :file filename :line row}]
                              (cond
                                (not (contains? granted [to name]))
                                (assoc base :violation :undeclared-call
                                       :detail (str call " is called and no :deps-sigs entry names it"))
                                (and arity (not (accepts (get granted [to name]) arity)))
                                (assoc base :violation :arity-mismatch
                                       :detail (str call " is called with " arity " args; the slice gives "
                                                    (pr-str (get granted [to name]))))))))
                    (distinct))
              (sort-by (juxt :filename :row) usages)))))

(defn impl-names
  "The vars the implementation defines that the Tester's packet never shows —
  NOTES.md row 5.

  Every var `:files/impl` defines, minus the names the slice's `:interfaces`
  declare: `(fold [e])` grants the Tester the name `fold`, so `fold` in its
  feedback is the contract, not a leak. What is left — private helpers and
  publics the slice never named — is exactly what the Tester was kept from
  reading, and feedback that names one of them was derived from the
  implementation. Returns a sorted vector of `{:ns sym :name sym}`. An impl
  file that is absent or does not parse contributes nothing (clj-kondo skips
  the one and `definitions` discards the other): this must not refuse
  feedback on a guess."
  [dir {:keys [files/impl blueprint/slice]}]
  (let [{:keys [defs]} (if (seq impl) (definitions dir impl) {:defs {}})
        granted (into #{} (keep #(:sig/name (parse-sig %))) (:interfaces slice))]
    (->> (for [[ns names] defs
               [nm _] names
               :when (not (contains? granted nm))]
           {:ns ns :name nm})
         (sort-by (juxt (comp str :ns) (comp str :name)))
         vec)))

(defn verify!
  "`violations`, but throwing. The dispatch-time counterpart to
  `provision/assemble!` refusing a scope violation: a packet whose signatures
  are wrong must not reach an agent, because the agent cannot tell the
  difference between a wrong signature and its own mistake."
  [dir context-files deps-sigs]
  (let [v (violations dir context-files deps-sigs)]
    (when (seq v)
      (throw (ex-info "the Blueprint's :deps-sigs do not match :files/context"
                      {:violations v})))
    v))

;; ---------------------------------------------------------------------------
;; what depends on the files a task will rewrite
;; ---------------------------------------------------------------------------

(defn dependents
  "The files under `scan-paths` that require a namespace defined in
  `impl-paths`, relative to `dir`, sorted, each once, the impl files excluded.

  WHY. Run D12 was the first dispatched rewrite. Its contract changed what
  `sandbox.render` emits, and `test/sandbox/property_test.clj` — which requires
  it — broke at the test gate, in a file no role had been shown. The gate caught
  it, but only after a full paid attempt, and no role could have seen it coming:
  nothing put the dependents in front of them. clj-kondo's analysis already
  records every `:require` as a namespace usage, so they are one query away.

  DIRECT dependents only. A file that requires a dependent is not listed: the
  point is the code that calls the rewritten namespace, and following the graph
  outward would hand a role most of the project.

  Empty when no impl file exists yet — a namespace being created has nothing
  requiring it — so a greenfield task is unaffected. `scan-paths` that do not
  exist are skipped."
  ([dir impl-paths] (dependents dir impl-paths ["src" "test"]))
  ([dir impl-paths scan-paths]
   (let [exists? #(fs/exists? (fs/path dir %))
         impl (filter exists? impl-paths)
         scan (filter exists? scan-paths)]
     (if (or (empty? impl) (empty? scan))
       []
       (let [norm #(str (fs/normalize %))
             impl-set (set (map norm impl))
             {:keys [analysis]} (kondo dir (distinct (concat scan impl)))
             rewritten (into #{} (keep (fn [{:keys [name filename]}]
                                         (when (impl-set (norm filename)) name)))
                             (:namespace-definitions analysis))]
         (->> (:namespace-usages analysis)
              (filter #(rewritten (:to %)))
              (map (comp norm :filename))
              (remove impl-set)
              distinct
              sort
              vec))))))

(defn task-dependents
  "The dependents of a task `spec`: what requires its `:files/impl`, minus its own
  `:files/test`.

  A task's test file is the Tester's target, not someone else's code to keep
  working. When a rewrite already had tests, `dependents` alone listed that file:
  the Coder was told it must keep working, the Reviewer to check it, the Tester
  saw its own target as read-only context, and a careful Coder noting the expected
  breakage would stop the run on the note pause for nothing (review of
  experiments/d7-d15, R2)."
  [dir spec]
  (let [own-tests (set (map #(str (fs/normalize %)) (:files/test spec)))]
    (vec (remove own-tests (dependents dir (:files/impl spec))))))

(defn with-dependents
  "`spec` with `deps` added to its `:files/context`: the Architect's entries
  first, then the dependents, each file once. Unchanged when there are none.

  Into `:files/context` rather than `:files/target`: a dependent is something
  a role must keep working and may read, not something it may edit. Every
  packet carries `:files/context`, so the Coder, the Tester and the Reviewer all
  see them, and `tester-packet` still strips the implementation."
  [spec deps]
  (if (seq deps)
    (update spec :files/context #(vec (distinct (concat % deps))))
    spec))
(defn -main
  "bb sigs — check a task spec's :deps-sigs against its :files/context.

  The spec is EDN on stdin; `--dir` is the project root to read the context
  files from, defaulting to the working directory. An Architect can run this
  on a Blueprint entry before anything is dispatched, which is the only point
  at which the error is cheap.

  Exits non-zero on any violation, so it composes into a shell the way the
  gates do — but it is NOT a gate. A gate judges an agent's output; this
  judges the packet the agent is about to be given."
  [& args]
  (let [dir (or (second (drop-while #(not= "--dir" %) args)) ".")
        spec (read-string (slurp *in*))
        v (violations dir (:files/context spec) (:deps-sigs (:blueprint/slice spec)))]
    (if (seq v)
      (do (println (str "sigs: " (count v) " violation(s) in " (:task/id spec)))
          (doseq [{:keys [sig violation detail]} v]
            (println (str "  " (pr-str sig) "\n    " (name violation) " — " detail)))
          (System/exit 1))
      (println (str "sigs: " (count (:deps-sigs (:blueprint/slice spec)))
                    " signature(s) check out against "
                    (count (:files/context spec)) " context file(s)")))))
