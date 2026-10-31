(ns harness.setup.self-check
  "`bb self-check` - the one setup command, run once in a fresh clone, an adopter's or the KIT's own
  (plan 7.0, item 8.3d): the project folder named, the clone's git hooks, the project's `.env`
  template, the doctor with its offer, the health check, the next steps.

  THE STEPS THAT NEED NO TOOL COME FIRST (From Opus 5.5's read of the 8.3d design): the hooks and the
  template need neither the doctor nor the health check, so a person who answers N to the offer, or
  whose doctor says no, or whose health check fails minutes in, still leaves with both; a re-run
  redoes only what stopped.

  A HARNESS TASK, which the clone's root `bb.edn` hands on to: the template is read from a profile,
  and reading a profile takes the harness's classpath, which the root's has not. Run in `harness/`;
  the clone is the folder above."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [harness.models.profile :as profile]
   [harness.setup.doctor :as doctor]
   [harness.setup.env-file :as env-file]
   [harness.setup.workspace :as workspace]))

(def steps
  "The order, as data a test reads."
  [:place :hooks :env :doctor :health :next])

(def hooks-path ".githooks")

(defn place-line
  "The project folder named, from the toolchain pack's `status` (`doctor/folder-status`) - pure:
  `{:line}`, or `{:stop}` for a refusal or a pack that did not answer."
  [status clone]
  (cond
    (nil? status) {:stop (str "the toolchain pack's status did not answer - `bb --config " (fs/path clone "tools" "toolchain" "bb.edn")
                              " status` says why")}
    (:refusal status) {:stop (:refusal status)}
    (not (:cache status)) {:stop "the toolchain pack named no toolchain folder"}
    :else {:line (str "the project folder: " (:folder status) " - its toolchain " (:cache status) ", its keys " (:env status))}))

(defn hooks!
  "The clone's git hooks read from `.githooks/` (`core.hooksPath`), as `bb hooks-install` sets them -
  idempotent: a line when it changed or could not, nil when it was already so. In an adopter's clone,
  which never commits, harmless: it is the same clone before `bb init` decides which kind it is."
  [clone]
  (let [git #(apply p/shell {:dir (str clone) :out :string :err :string :continue true} "git" %&)]
    (cond
      (not (zero? (:exit (git "rev-parse" "--git-dir"))))
      (str clone " is not a git clone: its hooks not set")
      (= hooks-path (str/trim (:out (git "config" "--get" "core.hooksPath"))))
      nil
      (zero? (:exit (git "config" "core.hooksPath" hooks-path)))
      (str "core.hooksPath = " hooks-path ": every commit in this clone now runs `bb commit-check`")
      :else (str "core.hooksPath could not be set in " clone " - `bb hooks-install` there says why"))))

(defn env!
  "The project's `.env` at `path`, written when there is none: the default seat's names, empty, mode
  600 - created with that mode before anything is in it. A file that exists is never touched. Says
  which, in a line."
  [path clone]
  (if (fs/exists? path)
    (str path " is there - not touched")
    (let [seat env-file/default-seat
          text (env-file/env-template (profile/read-profile (env-file/shipped-profile clone seat)) seat)]
      (if-not text
        (str "the " seat " seat's profile names no key: no .env written")
        (do (fs/create-dirs (fs/parent path))
            (fs/create-file path {:posix-file-permissions "rw-------"})
            (spit (str path) text)
            (str "wrote " path " - the names the " seat " seat needs, empty; fill them"))))))

(defn- run [path-env & argv]
  (:exit (apply p/shell {:continue true :extra-env {"PATH" path-env}} "bb" argv)))

(defn -main
  "bb self-check [--keep] [--selfcheck-only]: the steps in order, the first stop its exit."
  [& args]
  (when (some #{"--record"} args)
    (println (str "self-check does not record: the health records are the KIT's own evidence, written by "
                  "`bb health --record` in harness/ by whoever certifies a machine for the KIT, never by a "
                  "workspace's clone. Run `bb self-check` without it."))
    (System/exit 1))
  (let [clone (str (fs/parent (fs/normalize (fs/absolutize "."))))
        status (doctor/folder-status clone)
        {:keys [stop line]} (place-line status clone)
        say #(println (str "self-check: " %))]
    (when stop
      (say stop)
      (System/exit 1))
    (say line)
    (some-> (hooks! clone) say)
    (say (env! (fs/path (:env status)) clone))
    (let [path-env (workspace/tools-path (:cache status) (System/getenv "PATH"))]
      (let [exit (run path-env "doctor")]
        (when-not (zero? exit)
          (println (str "\nself-check: the doctor says this machine is not ready - the table above says "
                        "what to fix; then run `bb self-check` again. `bb health` was not run."))
          (System/exit exit)))
      (let [exit (apply run path-env "health" args)]
        (when (zero? exit)
          (println (workspace/self-check-next clone (:env status))))
        (System/exit exit)))))
