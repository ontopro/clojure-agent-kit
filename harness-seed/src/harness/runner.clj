;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/runner.clj @ 3dd2229 (2026-07-10).
(ns harness.runner
  "The AgentRunner seam.

  Agent invocation is the fiddliest, least-certain part of the loop — CLI
  flags, output formats, session resume, tool-call protocols, all of which
  change under you — so it sits behind a protocol.

  ManualRunner ships the *mechanics* end-to-end on day one: it prints the
  packet, pauses, and lets a human run the agent. That gets workspaces,
  packets, gates, triage and retry working and tested before you automate
  a single client, which is the opposite of the usual order and the reason
  to start here. Headless runners replace it one role at a time.

  Run every implementation you add through harness.runner-check."
  (:require
   [clojure.pprint :as pp]
   [clojure.string :as str]))

(defprotocol AgentRunner
  (run-agent [runner role packet]
    "Dispatch one task packet to the agent playing `role`.
     Returns a harness.shapes/AgentResult:
       {:status :done|:failed :files [..] :stdout _ :cost _}
     plus an optional :runner/meta map for anything only this runner knows.

     `role` is redundant with (:task/role packet) and most implementations
     ignore it — it is kept because this signature survived four
     implementations and two months without changing, and a seam whose
     stability is its main credential is not worth tidying."))

(defn- prompt-loop [read-fn]
  (loop []
    (let [line (some-> (read-fn) str/trim str/lower-case)]
      (case line
        nil :failed                     ; EOF — treat as failure, don't spin
        "done" :done
        "fail" :failed
        (do (println "please type `done` or `fail`:")
            (recur))))))

(defrecord ManualRunner [read-fn eval-hint]
  AgentRunner
  (run-agent [_ role packet]
    (println "\n════ MANUAL DISPATCH ════")
    (println "Role:    " (name role))
    (println "Task:    " (:task/id packet) "—" (:task/title packet))
    (println "Worktree:" (:repl/worktree packet))
    (when-let [port (:repl/port packet)]
      (println "REPL:    " port (when eval-hint (eval-hint port))))
    (println "Packet:")
    (pp/pprint packet)
    (println "Run the agent yourself, then type `done` or `fail` + Enter.")
    (let [status (prompt-loop read-fn)]
      {:status status
       ;; a human's word, not a filesystem fact — see runner-check's
       ;; :files-exist, which ManualRunner legitimately cannot pass
       :files (if (= :done status) (vec (:files/target packet)) [])
       :stdout nil
       :cost nil})))

(def default-eval-hint
  "How to tell a human to reach the worktree's REPL. Swap it for your own
  bridge; it is a string, not a dependency."
  (fn [port] (str "(clj-nrepl-eval -p " port " \"<code>\")")))

(defn manual-runner
  "A ManualRunner reading from *in* (or a custom read-fn, for tests)."
  ([] (manual-runner read-line default-eval-hint))
  ([read-fn] (manual-runner read-fn default-eval-hint))
  ([read-fn eval-hint] (->ManualRunner read-fn eval-hint)))
