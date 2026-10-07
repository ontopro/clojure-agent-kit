;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/log.clj @ 5df04ad (2026-09-05).
(ns harness.loop.log
  "The run's append-only event log. One EDN map per line; never rewritten.

  WHY IT SITS BESIDE `state.edn` RATHER THAN REPLACING IT. `state.edn` is the
  run's current state and is rewritten on every command — it is what the next
  command reads, and it has to be. That makes it exactly the wrong thing to
  trust about the run's history: a command that writes a bad state, or a
  process killed mid-write, takes the history with it. This file is only ever
  appended to, so the history survives its own writer.

  Which gives the run the same treatment this repository gives its documents:
  two copies of the same facts, and a gate on their disagreement. `bb run-loop
  record` refuses when the state's `:events` and this log do not tell the same
  story, rather than writing a record from whichever happened to be read.

  Timestamps are `java.util.Date`, so entries round-trip through `clojure.edn`
  as `#inst`."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(defn append!
  "Append one event (a map) to the log at `path`, stamped `:at` now."
  [path event]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (spit (str path) (prn-str (assoc event :at (java.util.Date.))) :append true))

(defn read-log
  "Every event in order; `[]` when the log does not exist yet.

  Line by line rather than one `read-string` over the file: a run killed
  mid-append leaves a partial last line, and reading the whole file as one form
  would lose every event before it too."
  [path]
  (if (fs/exists? path)
    (with-open [r (io/reader (str path))]
      (mapv #(edn/read-string {:default tagged-literal} %) (line-seq r)))
    []))

(defn owner
  "Who a stop is for, read off a stop as the log records it (`:stop/kind`,
  `:route`). THE ARCHITECT'S: a stop whose answer is an amendment to the
  contract - the spec review's list, and any stop triage routed `architect`.
  The Architect is the seat, so a session driving the loop handles these
  itself: read, `amend` (before and after are recorded) or leave it, carry
  on. THE PERSON'S: everything else - the cap, a missing verdict, a merge, a
  `human` route, a dead REPL, a refused provider, a spec that keeps drawing
  findings. The line between them is what lets a run be automated without a
  person reading every stop, and still stop for the ones only a person should
  decide. Here, beside the events, because the loop decides it and the stage
  report reads it back: the one place both can require."
  [{:keys [stop/kind route]}]
  (if (or (= :architect route) (= :spec-reviewed kind)) :architect :person))
