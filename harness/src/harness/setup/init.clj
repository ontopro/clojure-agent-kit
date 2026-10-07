(ns harness.setup.init
  "`bb init <name> [dir]` - create the workspace a project is built in.

  A workspace is a plain folder, NOT a repository, holding sibling repositories:
  the KIT's clone, the application, the build - and `work/`, scratch that belongs
  to none of them. `harness.setup.workspace` reads what this writes.

  TWO SEPARABLE PARTS, AND THIS NAMESPACE IS THE FIRST. Creating the workspace -
  the folders, `workspace.edn`, the build repository with the plan from `plan-template/`, the agent file, the
  wiring - knows nothing about any template. Generating the application is the
  only part that knows about one, and it arrives as `:app-fn`, a function of the
  application's folder. With none, the workspace names an application folder it
  did not create: bringing your own application is this part plus a repository
  put there, not a different code path. `-main`, at the bottom, is the one place
  the two parts meet; nothing above it requires the template.

  A PLAN, THEN A WRITER. `layout` is pure: every path and every file's content,
  as data, from a name and two folders. `refusals` is pure over `survey`'s facts.
  `create!` writes what `layout` said and nothing else - so what `bb init` is
  about to do can be printed before it is done, and tested without doing it.

  IT WRITES NOTHING INTO THE KIT'S CLONE. The clone is upgraded with `git pull`;
  a project's file list in it would be a local change to carry for ever. What
  the harness must know about the project goes in `workspace.edn`.

  IT NEVER OVERWRITES. Every path is checked before the first is written, and
  one that exists is a refusal that names it."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [harness.contract.shapes :as shapes]
   [harness.models.profile :as profile]
   [harness.rules :as rules]
   [harness.setup.app :as app]
   [harness.setup.doctor :as doctor]
   [harness.setup.template :as template]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; pure: the layout
;; ---------------------------------------------------------------------------

(def name-pattern
  "A project name becomes folder names and, in the application, a namespace
  segment: lowercase, starting with a letter, digits and single hyphens after."
  #"[a-z][a-z0-9]*(-[a-z0-9]+)*")

(defn- inside? [dir path]
  (fs/starts-with? (fs/normalize path) (fs/normalize dir)))

(defn- kit-ref
  "How the workspace names the KIT: relative when the clone is inside the
  workspace - the folder can then be moved whole - and absolute when the KIT
  is kept elsewhere, which an explicit `dir` allows."
  [ws-dir kit-dir]
  (if (inside? ws-dir kit-dir)
    (str (fs/relativize (fs/normalize ws-dir) (fs/normalize kit-dir)))
    (str (fs/normalize kit-dir))))

(defn- workspace-edn [{:workspace/keys [kit app build work rule-mirrors rules-overlay records run-tables kit-commit]}]
  (str ";; Which folder is which, for the KIT's harness (`harness.setup.workspace` reads it). Written by\n"
       ";; `bb init`. A relative path is relative to this file. In no repository: if it is lost, write it again.\n"
       "{:workspace/kit " (pr-str kit) "\n"
       " :workspace/app " (pr-str app) "\n"
       " :workspace/build " (pr-str build) "\n"
       " :workspace/work " (pr-str work) "\n"
       " ;; generated rule mirrors outside the KIT, checked by the KIT's `bb rules-check`\n"
       " :workspace/rule-mirrors " (pr-str rule-mirrors) "\n"
       " ;; this project's rules over the KIT's rule source, which every rendering reads merged\n"
       " :workspace/rules-overlay " (pr-str rules-overlay) "\n"
       " ;; where `record` copies every run's run.edn, and the document that publishes their tables -\n"
       " ;; held to each other by the KIT's `bb report-check`\n"
       " :workspace/records " (pr-str records) "\n"
       " :workspace/run-tables " (pr-str run-tables) "\n"
       " ;; the KIT commit this workspace was made at: `bb doctor` run here says what a later KIT expects\n"
       " ;; that this workspace lacks (harness.setup.upgrade); never rewritten by a pull\n"
       " :workspace/kit-commit " (pr-str kit-commit) "}\n"))

(defn- rules-overlay-edn
  "`<build>/rules.edn` as `bb init` writes it: the rule source's placeholder rules,
  text as shipped, for the adopter to fill HERE - `harness.rules/overlay` merges
  the file over the source by id, so the clone's source is never edited by a
  project and `start` still lists what stands unfilled."
  [{:workspace/keys [kit]}]
  (let [source (rules/shipped)
        fillable (rules/placeholder-ids source)]
    (str ";; This project's rules, merged by :id over the KIT's rule source (" kit "/harness/resources/agent-rules.edn,\n"
         ";; which no project edits: `git pull` upgrades the clone). Every rendering reads the merged set: each\n"
         ";; role's system prompt, and the application's AGENTS.md - `bb rules-sync` in the KIT's harness/\n"
         ";; re-renders it after an edit here, and `bb rules-check` there fails until it is run.\n"
         ";;   - an entry whose :id is a PLACEHOLDER rule of the source replaces that rule's :text: the entries\n"
         ";;     below, as shipped. Fill the <angle brackets> and keep the rest; `bb run-loop start` lists any\n"
         ";;     still standing (they reach every role as literal text, with every gate green);\n"
         ";;   - an entry with a NEW :id adds a project rule - :id :group :audience :title :text, as the source's;\n"
         ";;   - an entry with any other :id of the source is refused by name: those rules are the KIT's.\n"
         "["
         (str/join "\n\n "
                   (for [{:keys [id title text]} source :when (fillable id)]
                     (str ";; " title "\n {:id " id "\n  :text " (pr-str text) "}")))
         "]\n")))

(def default-seat
  "The seat `bb init` assumes when `--seat` is not given: the one the KIT is
  proved in first. The others are `resources/profiles/`' other examples."
  "claude")

(defn shipped-profile
  "Where the KIT at `kit-dir` ships the worked example for `seat`, whether or
  not it is there - `survey` says whether, and `refusals` names the ones that are."
  [kit-dir seat]
  (str (fs/path kit-dir "harness" profile/examples-dir (str seat ".edn"))))

(defn- profile-edn
  "`<build>/profile.edn` as `bb init` writes it: the seat's shipped example,
  whole - its header explains the pair of examples and the independence rule,
  and stays - under a line saying what this copy is. Nil when the KIT ships no
  example for the seat, which `refusals` turns into a sentence before anything
  is written."
  [{:workspace/keys [kit]} kit-dir seat]
  (let [source (shipped-profile kit-dir seat)]
    (when (fs/exists? source)
      (str ";; THIS PROJECT'S PROFILE - the models per role, and the seat. Copied by `bb init` from the KIT's\n"
           ";; example for seat " seat " (" kit "/harness/" profile/examples-dir "/" seat ".edn); edit it HERE. The build's\n"
           ";; loop.edn names it, `bb profile` in the KIT's harness/ checks it, and nothing of it goes in the clone.\n"
           ";;\n"
           (slurp source)))))

(defn- workspace-readme [project {:workspace/keys [kit app build work]}]
  (str "# " project " - a workspace, not a repository\n\n"
       "Written by the KIT's `bb init`. This folder holds sibling git repositories and is not one\n"
       "itself; each has its own history and none is rewritten to change another.\n\n"
       "| Folder | What it is |\n|---|---|\n"
       "| `" kit "` | the KIT (the Clojure Agent Kit): the method and the harness. Upgraded with `git pull`; nothing of this project is written into it |\n"
       "| `" app "/` | the application. No harness code and no planning documents |\n"
       "| `" build "/` | the build: the plan's documents in `docs/` from the KIT's template, `rules.edn` this project's rules over the KIT's rule source, `profile.edn` the models per role, `loop.edn` defaults for runs, and `runs/` the run records with `RUNS.md` their published tables. Read before the first dispatch by `bb plan-check` in the KIT's `harness/` |\n"
       "| `" work "/` | scratch, in no repository: `runs/<id>/` and `worktrees/`. Safe to delete between runs |\n"
       "| `workspace.edn` | which folder is which, for the harness |\n\n"
       "Start sessions HERE, not inside one of the repositories. One workspace is one project.\n"))

(defn- workspace-claude-md
  "ORIENTATION ONLY, AND NO IMPORT. The KIT's own `CLAUDE.md` is the working rules
  for an agent CHANGING the KIT - run its gates, write its logs - and an import of
  it would hand those to every session of every project. The rules a project's
  agents follow are in the application's `AGENTS.md`, from the rule source, and
  this file does not restate them: the source is the only place a rule is written."
  [project {:workspace/keys [kit app build work]}]
  (str "# " project "/ - a workspace, not a repository\n\n"
       "Sessions start here, and a session here is the Architect's: it plans, writes task specs and\n"
       "drives the loop; dispatched agents write the code. This folder holds sibling git repositories\n"
       "and is not one itself.\n\n"
       "- `" kit "/` - the KIT (the Clojure Agent Kit): the method and the harness. Nothing of this\n"
       "  project is written into it. Its own `CLAUDE.md` is the KIT's development rules, not this project's.\n"
       "- `" app "/` - the application. Its `AGENTS.md` is the rules every agent working in it follows,\n"
       "  generated from the KIT's rule source and this project's `" build "/rules.edn`; `CLAUDE.md` there imports it.\n"
       "- `" build "/` - the build repository: the plan in `docs/`, and beside it `rules.edn`, `profile.edn` and `loop.edn` defaults for runs. THE PLAN IS FILLED HERE, in this\n"
       "  session, before any loop runs: `docs/README.md` gives the order (`docs/source.md` first - what the plan\n"
       "  derives from - then requirements, `docs/00-overview.md` last, and it is the entry point once written),\n"
       "  `method.md` §02 is the review at the end, and a `<placeholder>` left standing is not a decision.\n"
       "  TWO COMMANDS IN THE KIT'S `harness/` READ THE FILLED PLAN: `bb plan-check`, the gate (no mark and no instruction\n"
       "  of the template left in `docs/`, `rules.edn` filled, the given parts intact; `start` runs it once before the\n"
       "  first dispatch), and `bb plan-review`, §02's review pass by the profile's `:plan-reviewer`, its findings in `reviews/`; per stage, `bb plan-review <stage-plan.md>` reads the stage plan cold before the blueprint is cut, and `bb blueprint-review <blueprint.md>` reads the blueprint whole before you sign it off.\n"
       "  THE RULES ARE FILLED HERE TOO: `rules.edn` holds the rule source's three placeholders and any rule of this\n"
       "  project's own, merged over the KIT's source, never edited; `bb rules-sync` in `harness/` re-renders `AGENTS.md`.\n"
       "  THE PROFILE (`profile.edn`, the models per role; `bb profile` in the KIT's `harness/` checks it) AND THE\n"
       "  RECORDS (`runs/<id>.edn`, copied there by `record`; `RUNS.md` publishes them, `bb report-check` holds the\n"
       "  two to each other) are here too. The clone carries nothing of this project.\n"
       "- `" work "/` - scratch, in no repository: `runs/<id>/`, `worktrees/`.\n"
       "- `workspace.edn` - which folder is which, for the harness.\n\n"
       "Where to read: the method is `" kit "/method.md`; the loop's commands are in\n"
       "`" kit "/harness/README.md` and are run from that folder; Clojure written by hand, outside\n"
       "the loop, is the role in `.claude/agents/interactive-programmer.md`.\n"))

(defn- plan-readme [project {:workspace/keys [kit]}]
  (str "# " project " - the build\n\n"
       "The reasoning, the contracts and the money for `" project "`, in its own repository so that it can\n"
       "stay private while the application is public.\n\n"
       ;; WHAT THE NAME LEAVES OUT. The first real project's person, meeting profile.edn
       ;; beside the requirements, took it for an application setting and asked whether it
       ;; belonged in the application's repository. It did not - it configures the build,
       ;; the roles must not see it, a model change is not a commit in the application -
       ;; but nothing said so where they were looking.
       "THIS REPOSITORY IS THE BUILD'S SIDE OF THE PROJECT, which is why it is called the build. Beside the\n"
       "plan's documents are the build's settings - `rules.edn`, `profile.edn`, `loop.edn` - and its\n"
       "records - `runs/`, `RUNS.md`, `reviews/`. They are here and not in the application because they\n"
       "configure and record the BUILD, not the thing built: the dispatched roles never see them, and a\n"
       "change of model or rule is a commit here, never in the application's history.\n\n"
       "- `docs/` - the KIT's plan template, as shipped. Fill them in the order `docs/README.md`\n"
       "  gives; angle brackets mark what to replace. The method they implement is `method.md` in the KIT\n"
       "  (`" kit "`). `reviews/`, beside it, is for a review's raw material - each reader's output, any\n"
       "  comparison of readers - outside the governing documents. Two commands in the KIT's `harness/` read\n"
       "  the filled plan: `bb plan-check`, the gate (no mark, no instruction of the template, `rules.edn` filled,\n"
       "  the given parts intact; `start` runs it once before the first dispatch) and `bb plan-review`, §02's\n"
       "  review pass by the profile's `:plan-reviewer`, its findings written to `reviews/plan-review.edn`. Per\n"
       "  stage, `bb plan-review <stage-plan.md>` reads the stage plan cold before the blueprint is cut, and\n"
       "  `bb blueprint-review <blueprint.md>` reads the blueprint whole before sign-off, both with their findings\n"
       "  to `reviews/<stage>/`.\n"
       "- `rules.edn` - this project's rules over the KIT's rule source: its three placeholders, to fill here,\n"
       "  and any rule of this project's own. Every role's prompt and the application's `AGENTS.md` read the\n"
       "  merged set; the KIT's own rules are not this file's to change.\n"
       "- `profile.edn` - the models per role and the seat, copied by `bb init` from the KIT's example for\n"
       "  the seat. Edit it here; `bb profile` in the KIT's `harness/` checks it.\n"
       "- `loop.edn` - this project's DEFAULTS for a run. Not read from here: copy it into each run\n"
       "  directory and set `:run/id`.\n"
       "- `runs/` - one record per run, `<run-id>.edn`, copied here by the loop's `record` (the run's own\n"
       "  directory under `../work/runs/` is scratch). Each names the KIT, application and plan commits it was\n"
       "  taken at. `RUNS.md` publishes their tables (`bb report < runs/<id>.edn` renders one), and the KIT's\n"
       "  `bb report-check` holds the two to each other. `bb init` made the folder and the document's header;\n"
       "  git tracks the folder from the first record.\n"))

(defn- runs-md
  "`RUNS.md` as `bb init` leaves it: the header only, no report. A report is a
  plain fenced block opening `Run <id> ·`, which is what `report/published-reports`
  finds, so this prose publishes nothing and `bb report-check` holds an empty
  `runs/` to it from the first gate run."
  [project {:workspace/keys [kit]}]
  (str "# " project " - the run records, published\n\n"
       "Every run the loop records is copied to `runs/<id>.edn` by `bb run-loop record` in the KIT's\n"
       "`harness/` (`" kit "/harness/`), and its tables are published here: `bb report < runs/<id>.edn`\n"
       "renders one report; paste it into a plain fenced block (three backticks, no label) under a heading\n"
       "of your own, newest first or oldest first as you like. `bb report-check` there - run by `bb gates`\n"
       "in the clone - holds this document to `runs/`: every report re-renders from its record, and every\n"
       "record is published.\n\n"
       "No run yet.\n"))

(defn- loop-edn
  "`loop.edn` defaults. `extra` is what only the application's generator knows -
  its gate commands, its nREPL command; without one the harness's own defaults apply.
  `{{kit}}` in any string becomes the KIT's absolute path: a gate the KIT ships
  runs in a worktree under `work/`, from where no relative path to the KIT holds."
  [extra kit-dir]
  (str ";; DEFAULTS for this project's runs. NOT read from here: copy into each run directory\n"
       ";; (../work/runs/<id>/loop.edn) and set :run/id. The harness finds the application through\n"
       ";; ../workspace.edn, so :repo/root is not needed. :profile is relative to THIS folder, the build,\n"
       ";; wherever the run directory is: profile.edn beside this file is the seat's shipped example as\n"
       ";; `bb init` copied it, and the KIT's clone holds nothing of this project's.\n"
       ";; THE KEYS, described once in harness/src/harness/contract/shapes.clj (loop-keys); a key not\n"
       ";; there is refused by name when the run starts:\n"
       (apply str (for [line (shapes/loop-key-lines)] (str ";;   " line "\n")))
       ";; A task that adds a namespace also adds its layers.edn entry, and no dispatched role writes that\n"
       ";; file: put the edited layers.edn in the run directory's arch/ and add\n"
       ";;   :architecture {:from \"arch\" :files [\"layers.edn\"]}\n"
       ";; to that run's loop.edn - assembly copies it into the gate worktree beside the roles' files.\n"
       (with-out-str
         (pp/pprint (walk/postwalk #(if (string? %) (str/replace % "{{kit}}" (str kit-dir)) %)
                                   (merge {:run/id "<one per run>"
                                           :profile "profile.edn"}
                                          extra))))))

(def brief-row
  "The row of `source.md` §1 that names the brief, as the template ships it."
  "| S-1 | <the brief> | <text / file / link> | <YYYY-MM-DD> | <who> |")

(defn source-with-brief
  "`source.md` as `bb init --brief` writes it: the template's text with §1's
  first row naming the brief - received `today`, from the person, as Appendix A -
  and the brief appended verbatim as that appendix. Everything else the
  template leaves to fill is left: the appendix is the record, §2 is the
  Architect's reading of it. Throws when the template has no such row: a
  brief filed where nothing cites it is the thing this exists to prevent."
  [template-text brief today]
  (when-not (str/includes? template-text brief-row)
    (throw (ex-info "plan-template/source.md has no S-1 row for the brief" {:init/error :no-brief-row})))
  (str (str/replace template-text brief-row
                    (str "| S-1 | the brief | text, Appendix A below | " today " | the person |"))
       "\n---\n\n## Appendix A — The brief, as given\n\n" (str/trim brief) "\n"))

(defn layout
  "Everything `bb init` would create, as data - nothing is touched.

  `:dir` nil means the DEFAULT target, the folder the KIT's clone is in: the
  adopter cloned the KIT into the folder they mean to work in. An explicit
  `:dir` is consent to use exactly that folder, and the KIT may then be
  anywhere. `:plan-template` are the plan documents to copy, relative to
  `<kit>/plan-template`. `:seat` names which shipped profile the build gets
  (`default-seat` when nil). `:rule-mirrors` and `:loop/defaults` come from
  whatever generates the application.

  `:kit-commit`, when given, is the KIT's commit, recorded in `workspace.edn` so
  that `bb doctor` run in the workspace can say what a later KIT expects of it
  (`harness.setup.upgrade`). `:brief`, when given, is the brief's text, written into `source.md` as §1's
  first row and Appendix A (`source-with-brief`), received `:today`; without it
  `source.md` is copied as shipped, for the brief to be filed by hand.

  `:app` and `:build` name the two repositories' folders; nil means
  `<name>-app` and `<name>-build`. THE FOLDER IS NOT THE NAME: the name is the
  application's root namespace and stays short; the folders are what a real
  project calls its repositories, and the experiments never needed to call
  them anything. Everything downstream reads the folders from `workspace.edn`,
  so the choice is made here once and nothing else knows the suffixes.

  Returns `:workspace/dir`, `:default?`, the `:workspace` map as written, and
  `:entries` in creation order - each `{:path rel :what text}` plus one of
  `:dir? true`, `:content string`, `:copy-from abs`, or `:app? true` (the
  application's folder: created only by an `:app-fn`). `:repos` are the folders
  that become repositories."
  [{:keys [kit-dir dir plan-template rule-mirrors seat brief today kit-commit] project :name defaults :loop/defaults
    app-folder :app build-folder :build}]
  (let [ws-dir (str (fs/normalize (or dir (fs/parent kit-dir))))
        seat (or seat default-seat)
        app (or app-folder (str project "-app"))
        build (or build-folder (str project "-build"))
        ws (cond-> {:workspace/kit (kit-ref ws-dir kit-dir)
                    :workspace/app app
                    :workspace/build build
                    :workspace/work "work"
                    :workspace/rule-mirrors (vec rule-mirrors)
                    :workspace/rules-overlay (str build "/rules.edn")
                    :workspace/records (str build "/runs")
                    :workspace/run-tables (str build "/RUNS.md")}
             kit-commit (assoc :workspace/kit-commit kit-commit))
        kit-path #(str (fs/path kit-dir %))]
    {:workspace/dir ws-dir
     :default? (nil? dir)
     :workspace ws
     :repos [build]
     ;; THE BUILD BEFORE THE APPLICATION: the application's AGENTS.md is rendered from the
     ;; rules overlay at generation, and the overlay is the build's.
     :entries
     (vec (concat
           [{:path "workspace.edn" :what "which folder is which, for the harness"
             :content (workspace-edn ws)}
            {:path "README.md" :what "one screen on the folders"
             :content (workspace-readme project ws)}
            {:path "CLAUDE.md" :what "orientation for a session started here (no rules, no import)"
             :content (workspace-claude-md project ws)}
            {:path ".claude/agents/interactive-programmer.md"
             :what "the off-loop role, copied from the KIT"
             :copy-from (kit-path "harness/agents/interactive-programmer.md")}
            {:path build :what "the build (its own repository): the plan's docs/ from the KIT's template, the settings, the records" :dir? true}
            {:path (str build "/README.md") :what "what the build repository is"
             :content (plan-readme project ws)}
            {:path (str build "/rules.edn") :what "this project's rules over the KIT's rule source: the placeholders, to fill"
             :content (rules-overlay-edn ws)}
            {:path (str build "/profile.edn") :what (str "this project's profile: the KIT's example for seat " seat ", to edit")
             :content (profile-edn ws kit-dir seat)}
            {:path (str build "/loop.edn") :what "this project's defaults for a run"
             :content (loop-edn defaults (fs/normalize (fs/absolutize kit-dir)))}
            ;; THE RECORDS' HOME AND THEIR DOCUMENT, so the build holds what four documents
            ;; said it held. `record` made the folder on its first copy and nobody made the
            ;; document: the fifth project wrote RUNS.md by hand before `bb report-check`
            ;; would pass. The folder is empty until the first record and git tracks it from
            ;; then; the document is tracked from the build's first commit.
            {:path (str build "/runs") :what "one record per run, copied here by the loop's record" :dir? true}
            {:path (str build "/RUNS.md") :what "the records' tables, published; bb report-check holds it to runs/"
             :content (runs-md project ws)}]
           (for [s (sort plan-template)]
             (if (and brief (= s "source.md"))
               {:path (str build "/docs/" s) :what "the source: the brief filed as §1 and Appendix A"
                :content (source-with-brief (slurp (kit-path "plan-template/source.md")) brief today)}
               {:path (str build "/docs/" s) :what "a plan document, from the template"
                :copy-from (kit-path (str "plan-template/" s))}))
           [{:path app :what "the application (its own repository)" :app? true}
            {:path "work" :what "scratch, in no repository: runs/<id>/, worktrees/" :dir? true}
            {:path "work/runs" :what "one folder per run" :dir? true}]))}))

;; ---------------------------------------------------------------------------
;; pure: refusing
;; ---------------------------------------------------------------------------

(defn refusals
  "Why `bb init` will not run, as sentences - empty when it will. `facts` is
  `survey`'s map: `:existing` (planned paths already there), `:other-repos`
  (repositories in the target besides the KIT), `:doctor/ok?`, `:kit?` (is
  `kit-dir` a clone of the KIT), `:seats` (the seats the KIT ships a profile for).

  THE DEFAULT TARGET IS A GUESS, so it is refused where the guess looks wrong:
  a KIT cloned into a folder of unrelated repositories would have a project
  scattered among them. An explicit `dir` is not second-guessed - except one
  inside the KIT's clone, where nothing of a project is ever written."
  [{project :name :keys [kit-dir seat]} {:keys [default?] ws-dir :workspace/dir {:workspace/keys [app build]} :workspace} facts]
  (cond-> []
    (not (and project (re-matches name-pattern project)))
    (conj (str "the name " (pr-str project) " cannot be used: lowercase letters, digits and single "
               "hyphens, starting with a letter (it becomes folder names and a namespace)"))

    ;; A chosen folder is held to the same pattern as the name it replaces: a path, a
    ;; space or a capital in `workspace.edn` reaches every task that reads it.
    (not (and app build (re-matches name-pattern app) (re-matches name-pattern build)))
    (conj (str "a folder name cannot be used (--app " (pr-str app) ", --build " (pr-str build)
               "): lowercase letters, digits and single hyphens, starting with a letter"))

    (and app (= app build))
    (conj (str "--app and --build name the same folder " (pr-str app) ": the application and the "
               "build are two repositories"))

    (not (:kit? facts))
    (conj "this is not a clone of the KIT (no plan-template/ beside harness/): run `bb init` at the root of one")

    ;; The build's profile is a copy of a shipped example, so a seat the KIT has no example
    ;; for has nothing to copy - and an empty profile.edn would fail at the first `start`.
    (and (:kit? facts) (not (contains? (set (:seats facts)) (or seat default-seat))))
    (conj (str "no shipped profile for seat " (pr-str (or seat default-seat)) ": the KIT has "
               (str/join ", " (:seats facts)) " in harness/" profile/examples-dir
               "/ (`--seat <name>`, default " default-seat ")"))

    (inside? kit-dir ws-dir)
    (conj (str ws-dir " is inside the KIT's clone (" kit-dir "), and `bb init` writes nothing there: "
               "name a folder outside it"))

    (seq (:existing facts))
    (conj (str "already there, and `bb init` never overwrites: "
               (str/join ", " (map #(str (fs/path ws-dir %)) (:existing facts)))))

    (and default? (seq (:other-repos facts)))
    (conj (str ws-dir " looks like a shared folder, not a workspace - it holds other repositories ("
               (str/join ", " (:other-repos facts)) "). Either clone the KIT into an empty folder "
               "and run `bb init " project "` there, or name the workspace: `bb init " project " <dir>`"))

    ;; The KIT's own development folder holds a clone of the KIT and, beside it, the
    ;; workspaces its experiments run in. `bb init` from THAT clone would make the folder a
    ;; workspace too, and nothing else about it says no: one repository, nothing planned exists.
    (and default? (seq (:workspaces-below facts)))
    (conj (str ws-dir " already holds workspaces (" (str/join ", " (:workspaces-below facts))
               "): this looks like a development folder, not a workspace. Make the workspace folder, "
               "clone the KIT INTO it, and run `bb init " project "` from that clone"))

    (false? (:doctor/ok? facts))
    (conj "`bb doctor` says a loop cannot run here: run it, follow what it says, run it again")))

;; ---------------------------------------------------------------------------
;; the edge: facts in, files out
;; ---------------------------------------------------------------------------

(defn plan-template-files
  "The plan documents the KIT ships, relative to `<kit>/plan-template`, its README
  included: that is where the order of writing and who writes them is said, and
  it travels with them."
  [kit-dir]
  (let [root (fs/path kit-dir "plan-template")]
    (when (fs/directory? root)
      (->> (fs/glob root "**")
           (filter fs/regular-file?)
           (map #(str (fs/relativize root %)))
           sort
           vec))))

(defn- subfolders-with
  "Names of `dir`'s subfolders holding `marker`, the KIT's own clone left out."
  [dir marker kit-dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter #(fs/exists? (fs/path % marker)))
         (remove #(fs/same-file? % kit-dir))
         (map #(str (fs/file-name %)))
         sort
         vec)
    []))

(defn survey
  "What is true of the machine that `refusals` needs to know. `doctor-ok?` is a
  thunk, so a test does not probe a toolchain."
  [{:keys [kit-dir]} {ws-dir :workspace/dir :keys [entries]} doctor-ok?]
  {:kit? (fs/directory? (fs/path kit-dir "plan-template"))
   :existing (->> entries
                  (map :path)
                  (filter #(fs/exists? (fs/path ws-dir %)))
                  ;; a folder that exists is the finding; its contents would only repeat it
                  (reduce (fn [acc path]
                            (if (some #(str/starts-with? path (str % "/")) acc) acc (conj acc path)))
                          []))
   :other-repos (subfolders-with ws-dir ".git" kit-dir)
   :workspaces-below (subfolders-with ws-dir workspace/file-name kit-dir)
   :seats (let [dir (fs/path kit-dir "harness" profile/examples-dir)]
            (if (fs/directory? dir) (vec (keys (profile/examples (str dir)))) []))
   :doctor/ok? (doctor-ok?)})

(defn kit-commit
  "The KIT clone's commit, or nil where `kit-dir` is not a repository (a test's
  scratch copy): `workspace.edn` then records none, and the doctor says so."
  [kit-dir]
  (let [{:keys [exit out]} (p/shell {:dir (str kit-dir) :out :string :err :string :continue true}
                                    "git" "rev-parse" "HEAD")]
    (when (zero? exit) (str/trim out))))

(defn- git! [dir env & args]
  (let [{:keys [exit err]} (apply p/shell {:dir (str dir) :out :string :err :string
                                           :continue true :extra-env env}
                                  "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed in " dir ": " (str/trim err))
                      {:init/error :git :dir (str dir) :args args})))))

(defn create!
  "Write what `layout` said. `:app-fn`, when given, is called with the
  application's absolute folder and makes it - the one part that knows a
  template. `:git-env` is extra environment for git (a test's identity).
  Returns the layout with `:app/created?`."
  [{ws-dir :workspace/dir :keys [entries repos] :as lay} & [{:keys [app-fn git-env]}]]
  (doseq [{:keys [path content copy-from dir? app?]} entries
          :let [target (fs/path ws-dir path)]]
    (when (fs/exists? target)
      (throw (ex-info (str target " appeared while `bb init` was running; nothing more was written")
                      {:init/error :exists :path (str target)})))
    (cond
      dir? (fs/create-dirs target)
      app? (when app-fn (app-fn (str target)))
      :else (do (fs/create-dirs (fs/parent target))
                (if copy-from
                  (fs/copy copy-from target)
                  (spit (str target) content)))))
  (doseq [repo repos
          :let [dir (fs/path ws-dir repo)]]
    (git! dir git-env "init" "-q" "-b" "main")
    (git! dir git-env "add" "-A")
    (git! dir git-env "commit" "-q" "-m" "The plan's documents, as the KIT ships them"))
  (assoc lay :app/created? (boolean app-fn)))

;; ---------------------------------------------------------------------------
;; the command
;; ---------------------------------------------------------------------------

(defn render-layout [{ws-dir :workspace/dir :keys [entries]} app?]
  (let [shown (remove #(str/includes? (:path %) "/docs/") entries)
        w (apply max (map #(count (:path %)) shown))]
    (str "\nThe workspace: " ws-dir "\n\n"
         (str/join "\n" (for [{:keys [path what] is-app :app?} shown]
                          (format (str "  %-" w "s  %s%s") path what
                                  (if (and is-app (not app?)) " - NAMED, NOT CREATED" ""))))
         "\n  " (count (filter #(str/includes? (:path %) "/docs/") entries))
         " plan documents under " (some #(when (:dir? %) (:path %)) entries) "/docs/\n")))

(def ^:private valued-flags
  "The flags that take the next argument as their value, each to its key."
  {"--seat" :seat "--app" :app "--build" :build "--brief" :brief})

(defn parse-args
  "`bb init`'s arguments as a map: `:positional` (name, dir), `:seat`, `:app`
  `:build` and `:brief` (the value after each flag), `:dry-run?`. The valued flags are
  parsed here and not by looking for a leading `--`."
  [args]
  (loop [[a & more] args, m {:positional []}]
    (cond
      (nil? a) m
      (valued-flags a) (recur (rest more) (assoc m (valued-flags a) (first more)))
      (= a "--dry-run") (recur more (assoc m :dry-run? true))
      :else (recur more (update m :positional conj a)))))

(defn -main
  "bb init <name> [dir] [--seat <name>] [--app <folder>] [--build <folder>] [--brief <file>] [--dry-run]

  With no `dir` the workspace is the folder the KIT's clone is in. Run from
  `harness/`, a relative `dir` is relative to `harness/`; the KIT's
  root `bb.edn` makes it absolute first, so there it means what was typed.
  `--seat` picks which shipped profile the build gets (default `claude`).
  `--app` and `--build` name the two repositories' folders (default `<name>-app`
  and `<name>-build`); the name stays the application's namespace. `--brief <file>` files
  the brief scoping wrote (`method.md` §02, step 0) as `source.md`'s §1 row and Appendix A.
  `--dry-run` prints what would be created, and any refusal, and writes nothing.

  THE TWO PARTS MEET HERE AND NOWHERE ELSE: the pin's `:loop/defaults`, the
  application's rule mirror and `app/app-fn` are handed to the part that knows
  no template. `KIT_TEMPLATE_LOCAL`, a local clone of the template, replaces the
  pinned commit for someone developing it."
  [& args]
  (let [{:keys [positional seat dry-run?] app-folder :app build-folder :build brief-file :brief} (parse-args args)
        [project dir] positional
        kit-dir (str (fs/parent (fs/normalize (fs/absolutize "."))))]
    (when-not project
      (println "usage: bb init <name> [dir] [--seat <name>] [--app <folder>] [--build <folder>] [--brief <file>] [--dry-run]")
      (System/exit 1))
    (when (and brief-file (not (fs/regular-file? brief-file)))
      (println (str "bb init: --brief " brief-file " is not a file; nothing was written"))
      (System/exit 1))
    (let [pin (template/pin (template/load-pins))
          local-root (some-> (System/getenv "KIT_TEMPLATE_LOCAL") not-empty fs/absolutize fs/normalize str)
          app-folder (or app-folder (str project "-app"))
          req {:name project
               :kit-dir kit-dir
               :dir (some-> dir fs/absolutize fs/normalize str)
               :seat seat
               :app app-folder
               :build build-folder
               :brief (some-> brief-file slurp)
               :today (str (java.time.LocalDate/now))
               :kit-commit (kit-commit kit-dir)
               :plan-template (plan-template-files kit-dir)
               ;; THE MIRROR FOLLOWS THE FOLDER, not the name: it is a path in workspace.edn.
               :rule-mirrors [(str app-folder "/AGENTS.md")]
               :loop/defaults (:loop/defaults pin)}
          lay (layout req)
          no (refusals req lay (survey req lay #(doctor/ok? (doctor/report) :loop)))]
      (println (render-layout lay true))
      (cond
        (seq no) (do (println "bb init: nothing was written.\n")
                     (doseq [r no] (println (str "  - " r)))
                     (System/exit 1))
        dry-run? (println "bb init --dry-run: nothing was written.")
        :else
        (let [_ (println (str "Generating the application from " (:template pin) " "
                              (if local-root (str "- LOCAL CLONE " local-root) (:git/tag pin))
                              ": a JVM starts, and the first run fetches the template…"))
              {ws-dir :workspace/dir {:workspace/keys [app]} :workspace}
              ;; A folder that cannot be written, a git with no identity, a generation that
              ;; failed: a sentence, not a stack trace - and the truth about what is left behind.
              (try (create! lay {:app-fn (app/app-fn pin {:app-name project
                                                          :build-folder (get-in lay [:workspace :workspace/build])
                                                          :local-root local-root})})
                   (catch Exception e
                     (println (str "bb init: stopped - " (.getSimpleName (class e)) ": " (ex-message e)
                                   "\nWhat was written before that is still in " (:workspace/dir lay)
                                   "; remove it before running `bb init` again."))
                     (System/exit 1)))]
          (println (str "Created. " (fs/path ws-dir workspace/file-name) " says which folder is which.\n"
                        "Start sessions in " ws-dir ". To see the application:\n\n"
                        "  cd " (fs/path ws-dir app) " && bb serve     # http://localhost:8000")))))))
