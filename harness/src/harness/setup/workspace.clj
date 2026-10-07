(ns harness.setup.workspace
  "Where things are: the workspace a path is in, and what its `workspace.edn` says.

  A WORKSPACE IS A PLAIN FOLDER HOLDING SIBLING REPOSITORIES - the KIT, the
  application, the build - and `workspace.edn`, written by `bb init`, says which
  is which:

    {:workspace/kit \"clojure-agent-kit\"  :workspace/app \"xyx-app\"
     :workspace/build \"xyx-build\"          :workspace/work \"work\"
     :workspace/rule-mirrors [\"xyx-app/AGENTS.md\"]
     :workspace/rules-overlay \"xyx-build/rules.edn\"
     :workspace/records \"xyx-build/runs\"  :workspace/run-tables \"xyx-build/RUNS.md\"}

  every path relative to the file (or absolute: a KIT kept elsewhere). The
  overlay is the project's rules over the KIT's source (`harness.rules/overlay`);
  the records folder is where `record` copies every `run.edn`, and the tables
  file the document that publishes them, held to the folder by `bb report-check`.
  A `workspace.edn` without a key, from before it, reads as if the thing were
  not there: the source alone, no copy, nothing to check. It is its own
  namespace because things that must not depend on each other read it: the
  driver, to find the project and the records; the rule tasks, to find the
  application's mirror and the overlay it renders from; the report's gate.

  A run directory finds its workspace by walking up (`find-workspace`), and
  the loop binds it for every command it runs. A COMMAND run from the KIT's
  `harness/` walks up too when the clone is inside the workspace, and is
  otherwise POINTED at it - `--workspace <dir>`, or the `KIT_WORKSPACE`
  variable (`current`): a KIT kept elsewhere, or one KIT serving several."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def file-name "workspace.edn")

(defn find-workspace
  "The workspace `start` is in, or nil: the nearest `workspace.edn` at or above
  it, with its folders made absolute.

  It is found by walking UP, so it answers the same from a run directory under
  `work/` and from one inside the KIT's clone."
  [start]
  (loop [dir (fs/absolutize start)]
    (when dir
      (let [f (fs/path dir file-name)]
        (if (fs/exists? f)
          (let [ws (edn/read-string (slurp (str f)))
                abs #(some->> % (fs/path dir) fs/normalize str)]
            (-> ws
                (assoc :workspace/dir (str dir))
                (update :workspace/kit abs)
                (update :workspace/app abs)
                (update :workspace/build abs)
                (update :workspace/work abs)
                (update :workspace/rules-overlay abs)
                (update :workspace/records abs)
                (update :workspace/run-tables abs)
                (update :workspace/rule-mirrors #(mapv abs %))))
          (recur (fs/parent dir)))))))

;; ---------------------------------------------------------------------------
;; the workspace a COMMAND runs in: pointed at, or walked up to
;; ---------------------------------------------------------------------------

(def env-var
  "Names the workspace for a KIT kept outside it: a folder at or under which
  `workspace.edn` is found. Set once in a shell; every command reads it."
  "KIT_WORKSPACE")

(def flag
  "The same, for one command: `--workspace <dir>`."
  "--workspace")

(def ^:dynamic *of-run*
  "The workspace of the run a loop command is driving, bound by `run-loop`
  from the run directory. It outranks the flag and the variable: a run's
  rules are its own workspace's whatever the shell says."
  nil)

(defn split-args
  "`args` with `--workspace <dir>` taken out: `{:workspace dir-or-nil :args rest}`.
  The flag with no value after it is an error by name, not a silent nil."
  [args]
  (loop [[a & more] args ws nil kept []]
    (cond (nil? a) {:workspace ws :args kept}
          (= a flag) (if (some? (first more))
                       (recur (rest more) (first more) kept)
                       (throw (ex-info (str flag " needs a folder after it") {:workspace/error :flag-without-value})))
          :else (recur more ws (conj kept a)))))

(defn pointed-at
  "Where a command is POINTED rather than run: the flag's folder from `args`,
  else the variable's value `env`, as `{:dir _ :from :flag|:env}`; nil when
  neither is set. Pure: `env` is passed in, so the tests need no environment."
  [args env]
  (let [{:keys [workspace]} (split-args args)]
    (cond workspace {:dir workspace :from :flag}
          (not (str/blank? env)) {:dir env :from :env}
          :else nil)))

(defn named
  "The workspace at or above `dir`, which something NAMED - the flag, the
  variable - so its absence is a fault said by name (`ex-info`), where the
  walk-up from a working directory answers nil. `from` says which named it."
  [dir from]
  (or (some-> (find-workspace dir) (assoc :workspace/from from))
      (throw (ex-info (str (case from :flag flag :env env-var :run "the run directory") " names " dir
                           ", and no " file-name " is at or above it")
                      {:workspace/error :not-found :dir dir :from from}))))

(defn current
  "The workspace a command runs in, in order: the run's (`*of-run*`), the one
  `--workspace` in `args` points at, the one `KIT_WORKSPACE` points at, else
  the one `dir` (`.`: the harness, in a workspace's clone) is in by walking
  up - or nil outside one. `:workspace/from` says which answered. A pointed-at
  folder with no `workspace.edn` at or above it throws (`named`); a KIT kept
  outside its workspace, or one KIT serving several, is otherwise indistinguishable
  from a KIT in none, and the rule source's placeholders would reach every
  role as literal text with nothing saying why."
  ([] (current nil))
  ([args] (current args "."))
  ([args dir]
   (if *of-run*
     (assoc *of-run* :workspace/from :run)
     (if-let [p (pointed-at args (System/getenv env-var))]
       (named (:dir p) (:from p))
       (some-> (find-workspace dir) (assoc :workspace/from :walk-up))))))

(defn current-or-exit
  "`current` for a command line: a pointed-at workspace that is not there is
  printed and exits 1, not a stack trace."
  [args]
  (try (current args)
       (catch clojure.lang.ExceptionInfo e
         (if (:workspace/error (ex-data e))
           (do (println (str "workspace: " (ex-message e)))
               (System/exit 1))
           (throw e)))))
