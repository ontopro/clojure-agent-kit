;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/gates.clj @ 5df04ad (2026-09-05).
(ns harness.repair
  "Gate 0: mechanical repair, before the gates run.

  Purely mechanical fixups that no agent's retry budget should ever pay
  for. Gate 0 runs unconditionally and never depends on the agent having
  remembered to call it — an agent that could reliably be told to fix its
  own parens would not need a gate 0. A well-placed gate 0 makes gate 1
  nearly a no-op; design for that compounding effect.

  THIS IS A STACK-SPECIFIC NAMESPACE, the first of three — harness.stub
  emits Clojure and harness.sigs reads it. Everything Clojure-flavoured about
  THE GATES lives here and nowhere else, so harness.gates depends on nothing
  but a shell. Add your language's mechanical repairs here.

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

(def ^:private clojure-file
  #"\.clj[scx]?$")

(defn- git
  "A git subcommand in `dir`, as its NUL-separated fields. A non-zero exit
  yields no fields rather than throwing: `-z` output is unambiguous, and the
  cases that fail here — no repository, no commit yet — mean `nothing
  changed`, not `something broke`."
  [dir args]
  (let [{:keys [exit out]} (apply p/shell {:dir dir :out :string :err :string
                                           :continue true}
                                  "git" args)]
    (if (zero? exit)
      (remove str/blank? (str/split out #"\u0000"))
      [])))

(defn repo-root
  "The work-tree root containing `dir`, or nil when there is none.

  No `-z` here: `rev-parse` does not take it and echoes it back as a second
  line, which would silently produce a root path with `-z` glued onto it."
  [dir]
  (some-> (first (git dir ["rev-parse" "--show-toplevel"])) str/trim not-empty))

(defn changed-files
  "Every file changed against HEAD or not yet tracked, relative to `root`.

  Untracked files are included deliberately. `git diff` alone misses a newly
  created file, and for gate 0 that is the namespace most likely to carry an
  unbalanced form, since nothing has ever read it back. Paths are filtered by
  existence, so a delete or a rename's old name does not arrive as a phantom.

  Unfiltered on purpose: the scope check in `harness.provision` must see every
  file an agent wrote, not only the Clojure ones. Gate 0 takes the narrow view
  through `changed-clojure-files`."
  [root]
  ;; --relative is load-bearing. Without it `git diff` reports paths from the
  ;; REPOSITORY root while `ls-files --others` reports them from the current
  ;; directory, and the two disagree the moment the project is a subdirectory
  ;; of its worktree. The existence filter below then silently drops every
  ;; modified tracked file, so only NEW files were ever seen — which is the
  ;; half a scope check least needs.
  (->> (concat (git root ["diff" "--name-only" "--relative" "-z" "HEAD"])
               (git root ["ls-files" "--others" "--exclude-standard" "-z"]))
       (filter #(fs/exists? (fs/path root %)))
       distinct
       vec))

(defn changed-clojure-files
  "`changed-files`, narrowed to what gate 0 can repair."
  [root]
  (filterv #(re-find clojure-file %) (changed-files root)))

(defn- format!
  "Run cljfmt over `files`, once per directory, FROM that directory.

  cljfmt searches ancestors for its config, so running it from beside a file
  finds whatever that file's project declares — and finds it by whatever name
  cljfmt itself accepts, which is more than one: `.cljfmt.edn` and
  `cljfmt.edn` both work, and a hand-rolled search for the dotted name alone
  silently misses the other.

  DELEGATING IS THE POINT, not a shortcut. The invariant this gate owes is
  agreement with the fmt gate — whatever that gate enforces, gate 0 should
  already have applied — and the fmt gate runs cljfmt from the project
  directory and lets it search. Any rule of our own for locating config is a
  way for the two to disagree.

  One call per directory rather than per file: a process start is the
  expensive part, and files in one directory share an answer.

  What this replaced: a single call from the repository root, which in a repo
  whose projects are subdirectories formatted everything by the root's rules —
  here, none. `bb repair` reported success and `bb gates` then failed on the
  file it had just repaired."
  [dir files]
  (doseq [[parent group] (group-by #(fs/parent (fs/absolutize (fs/path dir %))) files)]
    (apply p/shell {:dir (str parent) :out :string :err :string :continue true}
           "cljfmt" "fix" (map #(str (fs/file-name %)) group))))

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
        (doctor/require-tool! :cljfmt)
        (let [res (apply p/shell {:dir dir :out :string :err :string :continue true}
                         "clj-paren-repair" existing)
              ;; THEN cljfmt, in that order: balance the brackets before asking
              ;; a formatter to reindent, or it reindents around the wrong ones.
              ;;
              ;; This is here because two runs lost a cycle to it. D5's Coder
              ;; wrote its `:require` forms out of alphabetical order and the
              ;; fmt gate failed on nothing else; run 5's Tester did the same.
              ;; Neither is a judgement a model should spend a round trip on —
              ;; "a well-placed gate 0 makes gate 1 nearly a no-op" is this
              ;; namespace's own claim, and it was not true of the one
              ;; mechanical difference that kept arising.
              ;; Once per directory, from that directory — see `format!`.
              _ (format! dir existing)
              ;; after both, so neither can strip what we just added
              newlines (vec (keep #(ensure-trailing-newline! dir %) existing))]
          {:repaired (vec existing)
           :missing (vec missing)
           :newlines newlines
           :exit (:exit res)
           :out (str (:out res) (:err res))}))
      {:repaired [] :missing (vec missing) :newlines [] :exit 0 :out ""})))

(defn -main
  "bb repair — gate 0 over the Clojure files you have changed.

  The loop runs gate 0 for a dispatched agent, over that agent's
  `:files/target`. Nobody runs it for work done BESIDE the loop, and telling
  that role to remember is the discipline this namespace exists because
  nobody keeps. So: same `repair!`, a different way in — the file list comes
  from git instead of from a packet.

  Deliberately not part of `bb gates`. Gate 0 runs before the gate sequence,
  not inside it, and a task that mirrors `gates/default-gate-seq` should keep
  mirroring it."
  [& _]
  (if-let [root (repo-root ".")]
    (let [files (changed-clojure-files root)]
      (if (seq files)
        (let [{:keys [repaired newlines exit out]} (repair! root files)]
          (println (str "repair: " (count repaired) " file(s)"
                        (when (seq newlines)
                          (str ", " (count newlines) " missing trailing newline"))))
          (doseq [f repaired] (println (str "  " f)))
          (when-not (str/blank? out) (println out))
          (System/exit (if (zero? exit) 0 1)))
        (println "repair: no changed Clojure files")))
    (do (println "repair: not inside a git work tree — gate 0 needs one to find your changes")
        (System/exit 1))))
