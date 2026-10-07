(ns harness.setup.app
  "The second part of `bb init`: generate the application from the pinned
  template, and give it what the KIT's agents read.

  THE ONLY PART THAT KNOWS A TEMPLATE, and it knows it as data: `harness.setup.template`
  turns a pin into an argv, and `app-fn` runs that argv. `harness.setup.init` takes the
  result as a function of one folder and never learns what made it.

  TWO COMMITS, SO THE KIT'S HAND IS VISIBLE. The first is the template's output,
  untouched: `git diff` against it is, for ever, everything that was done to the
  application after generation. The second adds `AGENTS.md` - a frame around a
  block generated from the KIT's rule source - and a `CLAUDE.md` stub importing
  it, and `layers.edn` - the pin's `:layers`, the template's require graph
  declared under the application's name, which the boundary gate enforces.
  Nothing else: the fork carries what an application needs on its own, and
  what exists because of the harness stays out of the application."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.gates.boundary :as boundary]
   [harness.rules :as rules]
   [harness.setup.template :as template]))

(defn agents-md
  "The hand-written frame of the application's `AGENTS.md`, its marker block
  empty - `rules/sync!` fills it, and `bb rules-check` in the KIT guards it from
  then on (`workspace.edn`, `:workspace/rule-mirrors`).

  `build-folder` is the plan repository's folder as the workspace names it, not
  derived from the name: the first real project named its folders with
  `bb init --plan` (as the option was then called), and this sentence - a reader's, outside the markers, so no
  sync ever corrects it - still pointed at a `<name>-build/` that did not exist."
  ([project] (agents-md project (str project "-build")))
  ([project build-folder]
   (str "# " project "\n\n"
        "The application. If you are reading this headlessly, you were dispatched here with a task packet.\n\n"
        "> The block below is generated from the KIT's rule source (`harness/resources/agent-rules.edn`\n"
        "> in the KIT's clone, never edited by this project) merged with this project's rules overlay\n"
        "> (`../" build-folder "/rules.edn`: the placeholders filled, project rules added; `../workspace.edn`\n"
        "> names both) by `bb rules-sync`, run in the KIT's `harness/`. Everything outside the markers is\n"
        "> hand-written and survives a sync.\n"
        "> **Rules never go outside the markers** - one written out there reaches only agents that read\n"
        "> this file, which by design excludes the agent verifying the Coder.\n\n"
        rules/begin-marker "\n" rules/end-marker "\n")))

(defn layers-edn
  "The application's `layers.edn`: the pin's `:layers` - tails under the root
  namespace - as full namespaces of `app-name`. A map, not a namespace's
  own list: gate 4 is run over the whole tree, and a namespace absent here fails
  it as undeclared, which is the point."
  [app-name layers]
  (let [full #(symbol (str app-name "." %))]
    (str ";; Gate 4's ruleset - method §09's architecture-boundary check, run by the KIT's `bb boundary`
"
         ";; (the :deps gate in the plan's loop.edn). Each namespace maps to the set of namespaces of
"
         ";; THIS application it may require; anything outside the tree is ignored - the gate is about
"
         ";; your layers, not your dependencies. A namespace under src/ absent from this map fails the
"
         ";; gate: declare a layer, do not discover it. An entry with no file fails it too: dead config.
"
         ";; Depend on protocols across a seam, never on an implementation; the composition root is the
"
         ";; one namespace allowed both sides. This is the template's graph as generated: yours to edit.
"
         (with-out-str
           (pp/pprint (into (sorted-map)
                            (map (fn [[k v]] [(full k) (into (sorted-set) (map full) v)]))
                            layers))))))

(def claude-md
  "Claude Code reads only `CLAUDE.md`: a stub that imports the one generated
  mirror, rather than a second generated copy."
  "@AGENTS.md\n\nThe rules for this project are in `AGENTS.md`. Read it.\n")

(defn- run-process [dir env argv]
  (let [{:keys [exit out err]} (apply p/shell {:dir (str dir) :out :string :err :string
                                               :continue true :extra-env env}
                                      argv)]
    {:exit exit :out (str out err)}))

(defn- run-or-throw [run dir env argv]
  (let [{:keys [exit out]} (run dir env argv)]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " (take 2 argv)) " … failed (exit " exit ") in " dir ":\n"
                           (str/join "\n" (take-last 15 (str/split-lines (str out)))))
                      {:app/error :command :argv argv :dir (str dir) :exit exit})))))

(defn- label [{:keys [template git/tag git/sha]} local-root]
  (if local-root
    (str template " from the local clone " local-root " - NOT the pinned commit")
    (str template " " tag " (" (subs sha 0 7) ")")))

(defn app-fn
  "The function `init/create!` calls with the application's folder.

  `p` is a `template/pin`. Opts: `:app-name`; `:build-folder` (the plan
  repository's folder, for `agents-md`'s sentence; `<name>-build` when nil);
  `:local-root` (a local clone of the template instead of the pinned commit -
  `KIT_TEMPLATE_LOCAL`); `:git-env`, extra environment for git; `:run`,
  `(fn [dir env argv]) -> {:exit _ :out _}`, which a test replaces so that no
  JVM starts."
  [p {:keys [app-name build-folder local-root git-env run] :or {run run-process}}]
  (fn [app-dir]
    (let [git (fn [& args] (run-or-throw run app-dir git-env (into ["git"] args)))
          from (label p local-root)]
      (run-or-throw run (fs/parent app-dir) nil
                    (template/create-command p {:app-name app-name :target-dir app-dir :local-root local-root}))
      (when-not (fs/directory? app-dir)
        (throw (ex-info (str "generation exited 0 and left no " app-dir)
                        {:app/error :nothing-generated :dir (str app-dir)})))
      (git "init" "-q" "-b" "main")
      (git "add" "-A")
      (git "commit" "-q" "-m" (str "The application as generated, untouched: " from))
      (doseq [f ["AGENTS.md" "CLAUDE.md" boundary/file-name]]
        (when (fs/exists? (fs/path app-dir f))
          (throw (ex-info (str "the template generated its own " f "; the KIT will not overwrite it")
                          {:app/error :exists :path (str (fs/path app-dir f))}))))
      (spit (str (fs/path app-dir "AGENTS.md")) (agents-md app-name (or build-folder (str app-name "-build"))))
      (rules/sync! (str (fs/path app-dir "AGENTS.md")))
      (spit (str (fs/path app-dir "CLAUDE.md")) claude-md)
      (spit (str (fs/path app-dir boundary/file-name)) (layers-edn app-name (:layers p)))
      (git "add" "AGENTS.md" "CLAUDE.md" boundary/file-name)
      (git "commit" "-q" "-m" (str "AGENTS.md from the KIT's rule source, the CLAUDE.md stub that imports it, "
                                   "and layers.edn for the boundary gate")))))
