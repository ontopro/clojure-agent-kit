;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/gates.clj @ 5df04ad (2026-09-05).
(ns harness.repair
  "Gate 0: mechanical repair, before the gates run.

  Purely mechanical fixups that no agent's retry budget should ever pay
  for. Gate 0 runs unconditionally and never depends on the agent having
  remembered to call it — an agent that could reliably be told to fix its
  own parens would not need a gate 0. A well-placed gate 0 makes gate 1
  nearly a no-op; design for that compounding effect.

  THIS IS THE STACK-SPECIFIC NAMESPACE. Everything Clojure-flavoured about
  the gates lives here and nowhere else, so harness.gates depends on
  nothing but a shell and a reader on another stack has exactly one file to
  rewrite. Add your language's mechanical repairs here.

  Both repairs below were learned live rather than designed: delimiter
  errors burned real retries, and the missing trailing newline was caught
  by a Reviewer — a frontier model doing judgement work — because no
  cheaper gate was looking for it."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [harness.doctor :as doctor]))

(defn- ensure-trailing-newline!
  "Append the final newline when a written file lacks one; returns the file
  when it changed. cljfmt does not enforce one and no later gate catches
  its absence."
  [dir file]
  (let [path (str (fs/path dir file))
        content (slurp path)]
    (when (and (seq content) (not (str/ends-with? content "\n")))
      (spit path (str content "\n"))
      file)))

(defn repair!
  "Run gate 0 over `files` (paths relative to `dir`). Files the agent never
  produced are reported as :missing, not treated as an error — an agent
  that wrote nothing is a dispatch failure, and that is the loop's call to
  make, not this function's.

  Returns {:repaired [...] :missing [...] :newlines [...] :exit _ :out _}."
  [dir files]
  (let [{existing true missing false}
        (group-by #(fs/exists? (fs/path dir %)) files)]
    (if (seq existing)
      (do
        (doctor/require-tool! :clj-paren-repair)
        (let [res (apply p/shell {:dir dir :out :string :err :string :continue true}
                         "clj-paren-repair" existing)
              ;; after the repair, so it cannot strip what we just added
              newlines (vec (keep #(ensure-trailing-newline! dir %) existing))]
          {:repaired (vec existing)
           :missing (vec missing)
           :newlines newlines
           :exit (:exit res)
           :out (str (:out res) (:err res))}))
      {:repaired [] :missing (vec missing) :newlines [] :exit 0 :out ""})))
