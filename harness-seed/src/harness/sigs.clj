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
        file-of (into {} (map (juxt fs/file-name identity)) paths)
        norm (fn [f] (get file-of (fs/file-name f) f))
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
