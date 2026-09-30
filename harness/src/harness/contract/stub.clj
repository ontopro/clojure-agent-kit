;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.contract.stub
  "The contract, as code the Tester can load.

  WHY THIS EXISTS. §07 step 3 gives the Tester a worktree holding no
  implementation, which is what makes its tests derive from the contract rather
  than from the code. Running the loop showed the cost: the Tester cannot
  `require` the namespace under test, so it cannot evaluate its own tests before
  persisting them, and `:repl-first` is unsatisfiable. The REPL is alive, so
  `:repl-required` does not fire — it is the right rule and says nothing about
  this.

  A stub generated from `:blueprint/slice` resolves it, and resolves a second
  problem at the same time. A worktree is a checkout of the whole repository, so
  on a task that *rewrites* a namespace the previous implementation is sitting
  there to be read: the packet-level exclusion holds and the filesystem-level one
  does not. Writing the stub OVER that file closes both — greenfield gets
  something to require, a rewrite gets the contract instead of the code.

  THE SLICE HAS TWO HALVES AND BOTH ARRIVE. The `:interfaces` become bodies
  that throw; the `:shapes` become real `def`s. Emitting the shapes closes a
  separate gap the same run exposed — a slice declared a shape, the
  implementation never validated against it, and every gate was green over
  that, because a declared shape with no consumer is invisible to every tool.
  A shape the Tester can load is a shape the Tester can write a case against.

  Deleting the implementation instead is the obvious move and is wrong: the
  Tester could not then load the namespace at all, which is the problem this
  started from.

  THIS IS A STACK-SPECIFIC NAMESPACE, the second of four. It emits Clojure;
  `harness.gates.repair` repairs it, `harness.contract.sigs` reads its definitions and
  `harness.gates.forms` its top-level forms. A reader on another stack rewrites those
  four files and nothing else."
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :as pp]
   [clojure.string :as str]))

(defn ns-for
  "The namespace a source path declares: `src/app/some_ns.clj` ->
  `app.some-ns`. The underscore-to-hyphen swap is the file/namespace
  convention, not a guess."
  [path]
  (-> path
      (str/replace #"^(src|test)/" "")
      (str/replace #"\.cljc?$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")
      symbol))

(defn- fn-form?
  "`(clamp [lo hi x])`: a function to implement, with its argument vector."
  [form]
  (and (seq? form) (symbol? (first form)) (some vector? (rest form)) true))

(defn- var-form?
  "`(routes)`: a VAR to define - route data, a config map, a schema. The same
  one-element form `harness.contract.sigs/parse-sig` reads under `:deps-sigs`
  for a var a slice grants, so the two halves of a contract say \"var\" the
  same way. The fourth project's routing task delivered a `def` and had no
  form for it: a bare symbol crashed provisioning with an error that named
  nothing, and an empty list was refused, so the spec went out with a
  placeholder signature and the truth in its title."
  [form]
  (and (seq? form) (= 1 (count form)) (symbol? (first form)) true))

(defn- arity
  "The arg vector of an interface form like `(clamp [lo hi x])`."
  [form]
  (or (first (filter vector? form))
      (throw (ex-info "interface has no argument vector"
                      {:sandbox/error :bad-interface :form form}))))

(defn- indented
  "`form` pretty-printed at a width that fits a source line, every line
  after the first indented two spaces to sit under a `def`.

  The margin is 72 rather than 80 because the two-space indent is added
  after pprint has already decided where to break."
  [form]
  (-> (with-out-str (binding [pp/*print-right-margin* 72] (pp/pprint form)))
      str/trim
      (str/replace "\n" "\n  ")))

(defn shape-def
  "One slice shape as a `def` form, or nil when the shape is only a name.

  Two forms are legitimate in `:blueprint/slice`'s `:shapes`, and they mean
  different things:

  - `[Range [:map [:lo :int] ...]]` — a shape this slice DEFINES. Emitted, so
    the Tester can load it and write cases against the contract's own data
    shape rather than against prose.
  - `Concept` — a shape defined elsewhere and merely NAMED here. Nothing to
    emit; it reaches the Tester through `:files/context`, which is what that
    field is for. The seed's own `packet/example-packet` uses this form.
  - `(def Range [:map [:lo :int] ...])` — the first form, written the way an
    Architect writes a def. The fourth project built with the KIT inlined every
    shape this way, and because it was a list and not a pair, every one fell
    through as \"named\" and was joined into the stub's docstring with its
    quotes: a shape carrying an `:error/message` string made the stub a syntax
    error, in six of eleven runs. Read as a definition, not refused.

  What is emitted is the schema DATA, not a call into any validation library.
  Which library reads it is the project's business (`:shapes-are-the-contract`
  tells a project to name its own), and the stub has no reason to know.

  PRETTY-PRINTED, not `pr-str`-ed. A `:map` of any size is one long line under
  `pr-str`; cljfmt does not reflow long lines, so it passes the fmt gate and is
  still unreadable — and reading it is the Tester's whole job here. An emitted
  shape has to be loadable AND legible or it only does half the work."
  [s]
  (cond
    (and (vector? s) (= 2 (count s)) (symbol? (first s)))
    (str "(def " (first s) "\n  " (indented (second s)) ")\n")

    (and (seq? s) (= 3 (count s)) (= 'def (first s)) (symbol? (second s)))
    (str "(def " (second s) "\n  " (indented (nth s 2)) ")\n")))

(defn check-slice
  "Every entry of a Blueprint slice the stub cannot read, as data —
  `[{:key :shapes :entry form :reason str} ...]` — and empty when it can read
  them all.

  CALLED BEFORE ANYTHING IS PAID OR PROVISIONED. `render` refuses the same
  entries, but by then `start` has reviewed the spec, cut three worktrees and
  started two nREPLs; the fourth project met one such refusal as a crash whose
  error named nothing and left all of that behind for a person to clean up.
  `driver/start!` runs this over `spec.edn` first, so the refusal names the
  entry and the key while there is nothing to tear down."
  [{:keys [shapes interfaces]}]
  (-> []
      (into (for [s shapes
                  :when (not (or (symbol? s) (shape-def s)))]
              {:key :shapes :entry s
               :reason "a shape is a symbol (named, defined elsewhere), `[Name schema]` or `(def Name schema)`"}))
      (into (when (empty? interfaces)
              [{:key :interfaces :entry nil
                :reason "empty — a task must name at least one function `(name [args])` or var `(name)` to deliver"}]))
      (into (for [f interfaces
                  :when (not (or (fn-form? f) (var-form? f)))]
              {:key :interfaces :entry f
               :reason "an interface is a function `(name [args])` or a var `(name)`; a bare `name` is neither"}))))

(defn check-files
  "The `:files/impl` entry of a task spec if the stub cannot write it, as
  data in `check-slice`'s shape - `[{:key :files/impl :entry paths :reason
  str}]` - and empty when it can.

  One implementation file per task, for `write!`'s reason: a slice is one
  namespace. The schema (`shapes/TaskSpec`) says `{:max 1}` too, but a schema
  error names a count and not the rule; the fifth project wrote a packet with
  `handlers.clj` and `routes.clj`, watched the extraction, `sigs` and two paid
  spec reviews accept it, and met `write!`'s refusal after three worktrees
  existed. `blueprint/spec-for` and `driver/start!` run this first."
  [{:files/keys [impl]}]
  (when (not= 1 (count impl))
    [{:key :files/impl :entry (vec impl)
      :reason (str (if (empty? impl) "empty" (str (count impl) " files"))
                   " — a task stubs exactly one implementation file, one namespace per packet; "
                   "a second file is a second task")}]))

(defn- escaped
  "`s` as the inside of a Clojure string literal. Whatever reaches the
  docstring — and after `check-slice` only symbols do — cannot end it early."
  [s]
  (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")))

(defn render
  "The stub source for `ns-sym` from a Blueprint `slice`.

  Every stubbed body throws. A stub that returned a plausible value would let a
  test pass against nothing, which is worse than no stub at all — and it would
  be the same failure as an unmarked synthetic number, one layer down.

  IT THROWS AN AssertionError, NOT AN ex-info, and that is the guarantee
  rather than a detail. One run's Tester wrote `(is (thrown? Exception (render e)))` —
  an ordinary case for a function whose job is to refuse some inputs — and
  it PASSED against the stub, because the stub's own `ex-info` is an
  Exception. Two of that suite's cases were green before a line of the
  implementation existed, which is exactly what this namespace is here to
  make impossible. An AssertionError is an Error, so `thrown? Exception`
  does not catch it, the throw escapes the assertion, and clojure.test
  reports an ERROR rather than a pass. The cost is the ex-data the old
  throw carried; the function name moves into the message, which is what a
  human reads in a failure anyway.

  (`AssertionError` is JVM. This namespace already emits Clojure for the
  JVM, so it is not a new assumption — but it is one to re-make rather than
  port, on a stack whose exception hierarchy is shaped differently.)

  SHAPES ARE EMITTED, signatures are stubbed. Running the loop end to end
  produced a slice that declared a shape and an implementation that never
  validated against it; every gate was green over that, because a declared
  shape with no consumer is invisible to every tool. Emitting it gives the
  Tester something loadable to write a case against — the same move that made
  `:repl-first` satisfiable, applied to the other half of the slice."
  [ns-sym {:keys [shapes interfaces]}]
  (when (empty? interfaces)
    (throw (ex-info "no interfaces to stub — the Blueprint slice is empty"
                    {:sandbox/error :empty-slice :ns ns-sym})))
  (when-let [bad (seq (check-slice {:shapes shapes :interfaces interfaces}))]
    (throw (ex-info (str "the slice holds " (count bad) " entr" (if (= 1 (count bad)) "y" "ies")
                         " the stub cannot read: "
                         (str/join "; " (map #(str (name (:key %)) " " (pr-str (:entry %)) " — " (:reason %)) bad)))
                    {:sandbox/error :bad-slice :ns ns-sym :entries (mapv :entry bad)})))
  (let [defs (keep shape-def shapes)
        named (remove shape-def shapes)
        doc (str "GENERATED STUB — not an implementation.\n\n"
                 "  Written into the Tester's worktree from the Blueprint slice so its tests\n"
                 "  can be loaded and evaluated. The shapes below are the contract's own and\n"
                 "  are real; every function body throws, because a stub that returned a\n"
                 "  plausible value would let a test pass against nothing."
                 (when (seq named)
                   (str "\n\n  Named by the slice but defined elsewhere, so not emitted here — they\n"
                        "  arrive as :files/context: " (str/join ", " named) ".")))]
    (str "(ns " ns-sym "\n"
         "  \"" (escaped doc) "\")\n"
         (when (seq defs) (str "\n" (str/join "\n" defs)))
         (str/join
          ""
          (for [form interfaces
                :let [nm (first form)]]
            (if (var-form? form)
              ;; A var the implementation defines. `declare` leaves it unbound, so a test
              ;; that uses it errors — the same guarantee a throwing body gives a function,
              ;; and no plausible value for a test to pass against.
              (str "\n;; a var the implementation defines — generated stub, unbound here\n"
                   "(declare " nm ")\n")
              (str "\n(defn " nm "\n"
                   "  " (pr-str (arity form)) "\n"
                   "  (throw (AssertionError. \"not implemented — generated stub: "
                   nm "\")))\n")))))))

(defn write!
  "Write the stub for `impl-paths`' single file into `dir`, returning the path.

  `slice` is the packet's `:blueprint/slice` verbatim — the shapes and the
  signatures arrive together because they are one contract, and taking them
  apart at this seam is how the shapes lost their consumer in the first place.

  One implementation file per task: `harness.contract.packet` says a Blueprint slice
  carries the shapes and signatures for ONE namespace, so a slice spanning two
  files has no way to say which interface belongs where. Throwing beats
  guessing."
  [dir impl-paths slice]
  (when-let [[{:keys [reason]}] (check-files {:files/impl impl-paths})]
    (throw (ex-info (str "a slice stubs exactly one implementation file: " reason)
                    {:sandbox/error :multi-impl :files (vec impl-paths)})))
  (let [path (first impl-paths)
        target (fs/path dir path)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) (render (ns-for path) slice))
    path))
