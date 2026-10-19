;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.loop.run-lock
  "One `bb run-loop` command at a time on a run directory.

  WHY. Every command reads `state.edn` once, keeps the run's state in memory and
  writes all of it back on every change, while `events.log` is only appended to.
  Two commands on one run at once therefore lose whatever the other wrote: a
  `retry` typed while a backgrounded `run` was going appended its triage and its
  dispatch to the log and wrote the state, and the loop then wrote its own state
  over it. The log kept both events, the state lost them, and `record` refused a
  run that no command could repair. So the commands take turns.

  THE LOCK IS A FILE, `run-loop.lock` in the run directory, naming the process
  that holds it: its pid, the instant that process started, the command and when
  it was taken. It is made complete under a name of its own and then linked into
  place, which the file system does at once or not at all, so a second command
  never reads a half-written lock. A command that finds the lock held refuses
  with a sentence and writes nothing.

  A LOCK LEFT BEHIND - a command killed with no chance to clean up - is one whose
  process is gone, or whose pid now belongs to a process that started at another
  instant. It is taken over, and said. A live holder is never taken over.

  WHAT HOLDS IT. `bb run-loop`'s entry, `orchestrate/-main`, for every command
  there, `run` included, released however the command ends: returned, thrown,
  `System/exit` or Ctrl-C (a shutdown hook). `bb template-upgrade` and `bb health`
  drive a run directory from their own code, not through that entry, and take no
  lock: the one makes its run and stops, the other's runs are its own scratch.

  AT THE COMMAND, NOT AT EACH WRITE. A lock around `event!` or `persist!` would guard
  nothing: the loss was between two whole commands, each holding its own copy of the
  state in memory. `merge` records and tears down under the one lock it took."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(def file-name "run-loop.lock")

(defn lock-path [run-dir] (str (fs/path run-dir file-name)))

(defn- start-of
  "The instant a process started, as a string, or nil where the platform does not say."
  [^java.lang.ProcessHandle h]
  (some-> (.orElse (.startInstant (.info h)) nil) str))

(defn this-process
  "`{:pid :start}` of the running process."
  []
  (let [h (java.lang.ProcessHandle/current)] {:pid (.pid h) :start (start-of h)}))

(defn alive?
  "Is the process a lock names still the one that took it - the same pid, alive,
  started at the same instant?"
  [{:keys [pid start]}]
  (let [h (when pid (.orElse (java.lang.ProcessHandle/of (long pid)) nil))]
    (boolean (and h (.isAlive ^java.lang.ProcessHandle h) (= start (start-of h))))))

(defn holder
  "The lock's contents, or nil when there is no lock or it does not read."
  [run-dir]
  (let [p (lock-path run-dir)]
    (when (fs/exists? p)
      (try (edn/read-string (slurp p)) (catch Exception _ nil)))))

(defn- link!
  "Put a complete lock for `me` in place, or return false when one is already there."
  [run-dir me]
  (let [tmp (str (fs/path run-dir (str file-name "." (:pid me))))]
    (spit tmp (pr-str me))
    (try
      (java.nio.file.Files/createLink (fs/path (lock-path run-dir)) (fs/path tmp))
      true
      (catch java.nio.file.FileAlreadyExistsException _ false)
      (finally (fs/delete-if-exists tmp)))))

(defn held-sentence
  "What a refused command is told."
  [{:keys [cmd pid at]}]
  (str "`" (or cmd "a command") "` (pid " pid (when at (str ", since " at)) ") holds this run: "
       "one run-loop command at a time, since each writes the whole state. Wait for it to stop, "
       "then run this again. Nothing was written. A `run` stops by itself when it needs a decision, "
       "and retries what its own triage decides: a hand `retry` is for after it stops."))

(defn acquire!
  "Take the lock on `run-dir` for `cmd`: `{:path :stale holder-or-nil}`, `:stale` the
  lock left behind that was taken over. Throws `:run-loop/error :locked` while a live
  process holds it. `alive?` is injectable for the tests."
  ([run-dir cmd] (acquire! run-dir cmd alive?))
  ([run-dir cmd alive-fn]
   (let [me (assoc (this-process) :cmd cmd :at (java.util.Date.))
         path (lock-path run-dir)]
     (if (link! run-dir me)
       {:path path :me me}
       (let [h (holder run-dir)]
         (when (and h (alive-fn h))
           (throw (ex-info (held-sentence h) {:run-loop/error :locked :holder h})))
         ;; left behind: taken over only if it is still the lock just read, so two
         ;; commands finding the same stale lock do not both take it
         (when (= h (holder run-dir)) (fs/delete-if-exists path))
         (if (link! run-dir me)
           {:path path :me me :stale (or h {:unreadable true})}
           (throw (ex-info (held-sentence (holder run-dir)) {:run-loop/error :locked :holder (holder run-dir)}))))))))

(defn release!
  "Remove the lock at `path` if `me` still holds it."
  [path me]
  (let [h (try (edn/read-string (slurp path)) (catch Exception _ nil))]
    (when (and h (= (select-keys h [:pid :start]) (select-keys me [:pid :start])))
      (fs/delete-if-exists path))))

(defn stale-sentence
  "What a command that took over a lock left behind says."
  [{:keys [cmd pid at unreadable]}]
  (if unreadable
    "  run-loop: a lock that did not read was left in this run; taken over"
    (str "  run-loop: `" cmd "` (pid " pid (when at (str ", since " at)) ") left its lock behind "
         "and is no longer running; taken over")))

(defn with-lock
  "`(f)` holding the lock on `run-dir` for `cmd`, released however `f` ends - a
  shutdown hook covers `System/exit` and Ctrl-C, which skip a `finally`."
  [run-dir cmd f]
  (let [{:keys [path me stale]} (acquire! run-dir cmd)
        release #(release! path me)
        hook (Thread. ^Runnable release)]
    (when stale (println (stale-sentence stale)))
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try (f)
         (finally
           (release)
           (try (.removeShutdownHook (Runtime/getRuntime) hook)
                (catch IllegalStateException _ nil))))))
