;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/gates.clj @ 5df04ad (2026-09-05).
(ns harness.gates
  "The gate runner.

  Gates run cheapest-first and short-circuit at the first failure, each one
  individually, so triage receives {which gate, its output} as data instead
  of having to parse a composite log. Nothing here parses tool output:
  (zero? exit) is the entire pass/fail rule, which is why this namespace
  has stayed still while everything around it changed.

  Gate 0 — the mechanical repair that runs BEFORE any of this — lives in
  harness.repair, deliberately: it is the stack-specific part, and keeping
  it out of here is what lets this namespace depend on nothing but a shell."
  (:require
   [babashka.process :as p]))

(def default-gate-seq
  "Cheap first (method §09). Each entry is [gate-key shell-command]; the
  commands are your project's task surface, so edit them.

  The gate KEYS are a public contract, not labels. Whatever routes a
  failure dispatches on them, and the run log is read by key months later —
  so adding or renaming a gate means teaching the router about it.

  :deps is §09's gate 4, the architecture-boundary check. Wire it with a
  placeholder ruleset before you know your real layers: retrofitting a
  boundary check onto a codebase that has been violating boundaries for
  three stages is a different and much worse job."
  [[:fmt "bb fmt-check"]
   [:lint "bb lint"]
   [:test "bb test"]
   [:deps "bb deps-check"]])

(defn- run-gate [dir cmd]
  (try
    (let [res (p/shell {:dir dir :out :string :err :string :continue true} cmd)]
      {:exit (:exit res) :out (str (:out res) (:err res))})
    (catch java.io.IOException e
      ;; :continue true suppresses a non-zero exit, NOT a missing program —
      ;; that throws. An unconfigured gate must fail like any other gate
      ;; rather than blow up the loop mid-attempt and lose the work. 127 is
      ;; the shell's own "command not found", so triage on :exit still works.
      {:exit 127
       :out (str "could not run " (pr-str cmd) ": " (ex-message e))})))

(defn failure
  "A non-gate failure in the gate-result shape, so a dead REPL or a failed
  dispatch flows through the same triage seam as a red test suite. :exit is
  nil because nothing was executed."
  [gate out]
  {:gates/passed? false
   :gates/failed gate
   :gates/report [{:gate gate :status :fail :exit nil :out out}]})

(defn run-gates!
  "Run `gate-seq` in `dir`, stopping at the first failure. Gates after the
  failure are reported :skipped rather than run.

  Returns a harness.shapes/GateResult:
    {:gates/passed? bool
     :gates/failed  gate-key | nil
     :gates/report  [{:gate _ :status :pass|:fail|:skipped :exit _ :out _} ...]}

  The 2-arity is the injection point — pass your own sequence rather than
  reaching for configuration you do not have yet."
  ([dir] (run-gates! dir default-gate-seq))
  ([dir gate-seq]
   (loop [remaining gate-seq
          report []]
     (if-let [[gate cmd] (first remaining)]
       (let [{:keys [exit out]} (run-gate dir cmd)]
         (if (zero? exit)
           (recur (rest remaining)
                  (conj report {:gate gate :status :pass :exit exit :out out}))
           {:gates/passed? false
            :gates/failed gate
            :gates/report (into (conj report {:gate gate :status :fail
                                              :exit exit :out out})
                                (map (fn [[g _]] {:gate g :status :skipped
                                                  :exit nil :out ""}))
                                (rest remaining))}))
       {:gates/passed? true
        :gates/failed nil
        :gates/report report}))))
