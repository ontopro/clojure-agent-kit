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
  declared under the application's name, which the boundary gate enforces - and
  the KIT's first page in place of the template's (`home-page`). Nothing else: the
  fork carries what an application needs on its own, and what exists because of
  the harness stays out of the application.

  THE FIRST PAGE IS THE KIT'S, AND THE FORK STAYS NEUTRAL. The template's own page
  said \"Welcome to Clojure Stack Lite\" and pointed at a file it does not generate;
  a person serving a new workspace's application learned nothing of the KIT or what
  to do next. The fork could carry a KIT page, at the price of being an ordinary
  Clojure Stack Lite application no longer; the page is written here instead, one
  form replaced in place, so a richer page is a KIT change and not a fork release."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.gates.boundary :as boundary]
   [harness.gates.forms :as forms]
   [harness.rules :as rules]
   [harness.setup.template :as template]))

(defn agents-md
  "The hand-written frame of the application's `AGENTS.md`, its marker block
  empty - `rules/sync!` fills it, and `bb rules-check` in the KIT guards it from
  then on (`workspace.edn`, `:workspace/rule-mirrors`).

  `build-folder` is the build repository's folder as the workspace names it, not
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
        "> names both) by `bb rules-sync`, run from the workspace folder. Everything outside the markers is\n"
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

(defn home-page
  "The KIT's first page, as the source of the `(def home-page ...)` form that replaces the
  template's in `src/<ns>/views.clj`: the template's look, its `base`, and what a person
  serving a new workspace's application needs - that it is a KIT workspace's, and the next
  step. Written already formatted for the application's own `fmt-check`, which is a loop
  gate. No folder name - a workspace can be moved - and no link out: it works offline."
  [app-name kit-version]
  (let [code-class "[\"bg-slate-100\" \"px-1\" \"rounded\"]"
        code #(str "[:code {:class " code-class "} \"" % "\"]")]
    (str/join
     "\n"
     ["(def home-page"
      "  ; THE KIT'S WELCOME, written by `bb init` over the template's own page. A placeholder: stage 0"
      "  ; replaces it with the project's first page."
      "  (base"
      "    [:div"
      "     {:class [\"text-slate-800\" \"min-h-screen\" \"flex\" \"flex-col\"]}"
      "     [:main {:class [\"flex-grow\" \"flex\" \"items-center\" \"justify-center\"]}"
      "      [:div {:class [\"container\" \"mx-auto\" \"px-4\" \"max-w-3xl\"]}"
      "       [:h1 {:class [\"text-5xl\" \"font-bold\" \"mb-6\" \"text-slate-900\" \"text-center\"]} \"Welcome to \""
      "        [:span {:class [\"bg-gradient-to-r\" \"from-emerald-400\" \"to-sky-400\" \"bg-clip-text\" \"text-transparent\"]}"
      "         \"Clojure Agent Kit\"]"
      "        \" (aka \\\"KIT\\\")\"]"
      "       [:p {:class [\"text-xl\" \"mb-10\" \"text-slate-600\" \"text-center\"]}"
      (str "        " (code app-name) " \" is the application of a KIT workspace - the folder above this one. The build has not started yet.\"]")
      "       [:h2 {:class [\"text-2xl\" \"font-semibold\" \"mb-4\" \"text-slate-900\"]} \"The next step\"]"
      "       [:ol {:class [\"list-decimal\" \"list-inside\" \"space-y-2\" \"text-lg\" \"mb-10\" \"text-slate-700\"]}"
      (str "        [:li \"In a terminal, go to the workspace folder: \" " (code "cd ..") "]")
      (str "        [:li \"Start a Claude Code session there - Opus 5.5, effort high: \" " (code "claude") "]")
      (str "        [:li \"In the session, type \" " (code "/kit-start") " \" - it says what the workspace holds and begins the project.\"]]")
      "       [:p {:class [\"text-base\" \"text-slate-500\"]}"
      "        \"This page is a placeholder: stage 0 replaces it with the project's first page.\"]]]"
      "     [:footer {:class [\"py-6\" \"text-center\" \"text-sm\" \"text-slate-500\"]}"
      (str "      [:p \"Made by KIT " (or kit-version "(no version)") " from the Clojure Stack Lite template\"]]]))")])))

(defn with-home-page
  "`views-text`, the application's `views.clj`, with its one top-level `(def home-page ...)`
  replaced by `page` (`home-page`'s source), every other line as it was. Throws, naming the
  file, when there is no such form or more than one: the pin's template has it, so a miss means
  a local template or a moved pin, and a page written beside it would be a broken view."
  [views-text page views-path]
  (let [hits (filter #(and (= "def" (:kind %)) (= "home-page" (:name %))) (forms/table views-text))
        lines (str/split-lines views-text)]
    (when-not (= 1 (count hits))
      (throw (ex-info (str views-path " has " (if (empty? hits) "no" (count hits)) " top-level (def home-page ...)"
                           " - the KIT writes its first page in place of the template's one; the template is not"
                           " the one the pin names")
                      {:app/error :no-home-page :path (str views-path) :found (count hits)})))
    (let [{:keys [from to]} (first hits)]
      (str (str/join "\n" (concat (take (dec from) lines) [page] (drop to lines)))
           (when (str/ends-with? views-text "\n") "\n")))))

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

  `p` is a `template/pin`. Opts: `:app-name`; `:build-folder` (the build
  repository's folder, for `agents-md`'s sentence; `<name>-build` when nil);
  `:local-root` (a local clone of the template instead of the pinned commit -
  `KIT_TEMPLATE_LOCAL`); `:kit-version`, for the first page's foot; `:git-env`, extra
  environment for git; `:run`, `(fn [dir env argv]) -> {:exit _ :out _}`, which a test
  replaces so that no JVM starts."
  [p {:keys [app-name build-folder local-root kit-version git-env run] :or {run run-process}}]
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
      ;; THE PAGE FIRST, BEFORE ANYTHING IS WRITTEN: a template with no home-page where the pin
      ;; puts one stops here, with the generated commit alone in the application
      (let [views (str "src/" (str/replace app-name "-" "_") "/views.clj")
            views-path (fs/path app-dir views)
            _ (when-not (fs/exists? views-path)
                (throw (ex-info (str "the template generated no " views
                                     " - the KIT writes its first page in place of the template's home-page there")
                                {:app/error :no-home-page :path (str views-path) :found 0})))
            page (with-home-page (slurp (str views-path)) (home-page app-name kit-version) views)]
        (spit (str (fs/path app-dir "AGENTS.md")) (agents-md app-name (or build-folder (str app-name "-build"))))
        (rules/sync! (str (fs/path app-dir "AGENTS.md")))
        (spit (str (fs/path app-dir "CLAUDE.md")) claude-md)
        (spit (str (fs/path app-dir boundary/file-name)) (layers-edn app-name (:layers p)))
        (spit (str views-path) page)
        (git "add" "AGENTS.md" "CLAUDE.md" boundary/file-name views)
        (git "commit" "-q" "-m" (str "AGENTS.md from the KIT's rule source, the CLAUDE.md stub that imports it, "
                                     "layers.edn for the boundary gate, and the KIT's first page in " views))))))
