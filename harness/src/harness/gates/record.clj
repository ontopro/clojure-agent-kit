(ns harness.gates.record
  "The KIT's gates record and the commit check that reads it.

  `bb gates` runs its steps through `recorded!`, which writes
  `.local/gates/last.edn` at the repository root, green or red: the tree the
  gates ran on, HEAD, the exit, the step that failed and the tests that failed
  by name, the start and the end. The tree is the WORKING tree as a git tree
  id - a temporary index, `git add -A`, `git write-tree` - so an untracked file
  counts, and it is taken before and after the run, so a file edited while the
  gates ran is seen. Every run's record is also kept under `.local/gates/runs/`,
  so a test that failed on a tree and passed on the same tree later - flaky,
  since nothing changed - is named after the run that shows it.

  `bb commit-check`, which the clone's pre-commit hook runs, refuses a commit
  when there is no record, when it is red, or when the tree it names is not the
  tree being committed (`git write-tree` of the index the commit is made from).
  And it refuses code staged without a document: a change under `harness/src/`,
  `tools/`, `skills/` or `plan-template/` stages `DEVLOG.md` or `NOTES.md` too.
  And a staged `NOTES.md` must have bumped its `**Updated <date> <time>` stamp
  past HEAD's, to no later than the clock.
  And no added line may carry a name in the wording list (`.local/wording/names.txt`)
  as a whole word.
  So \"`bb repair && bb gates` green before committing\" is a fact the commit
  checks, not a step remembered. The KIT's own development only: nothing here
  is shipped to a workspace."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.test :as t]))

(def record-path
  "Where the record lives, from the repository root. Under `.local/`, so it is
  never committed and a container's clone writes its own."
  ".local/gates/last.edn")

;; ---------------------------------------------------------------- git

(defn- git
  "`git args...` in `dir`, trimmed out, or nil when it exits non-zero."
  ([dir args] (git dir {} args))
  ([dir extra-env args]
   (let [{:keys [exit out]} (apply p/shell {:dir (str dir) :out :string :err :string
                                            :continue true :extra-env extra-env}
                                   "git" args)]
     (when (zero? exit) (str/trim out)))))

(defn repo-root
  "The top of the git repository `dir` is in, or nil outside one."
  [dir]
  (git dir ["rev-parse" "--show-toplevel"]))

(defn head
  "HEAD's commit, or nil (a repository with no commit yet)."
  [root]
  (git root ["rev-parse" "--verify" "-q" "HEAD"]))

(defn working-tree
  "The working tree as a git tree id: the index copied to a temporary file (so
  the stat cache keeps it fast), `git add -A` into it, `git write-tree`. The
  real index is not touched; untracked files that are not ignored count."
  [root]
  (let [tmp (fs/create-temp-file {:prefix "kit-gates-index"})
        index (some->> (git root ["rev-parse" "--git-path" "index"]) (fs/path root))]
    (try
      (if (and index (fs/exists? index))
        (fs/copy index tmp {:replace-existing true})
        (fs/delete tmp))
      (let [env {"GIT_INDEX_FILE" (str tmp)}]
        (when (git root env ["add" "-A"])
          (git root env ["write-tree"])))
      (finally (fs/delete-if-exists tmp)))))

(defn staged-tree
  "The tree a commit would record: `git write-tree` of the index git names -
  the real one, or the temporary one `git commit -a` and `git commit <paths>`
  hand their hooks through GIT_INDEX_FILE."
  [root]
  (git root ["write-tree"]))

;; ---------------------------------------------------------------- tests by name

(defn- test-name []
  (if-let [v (first t/*testing-vars*)]
    (let [{:keys [ns name]} (meta v)]
      (str (ns-name ns) "/" name))
    "(outside a test: a fixture or a namespace load)"))

(defn run-tests!
  "Run clojure.test over `nses` and throw when anything failed or erred, the
  failing tests BY NAME in the exception's data (`:failed-tests`) for the
  record, `:babashka/exit 1` so `bb test` alone exits as it always did."
  [nses]
  (apply require nses)
  (let [failed (atom [])
        report t/report
        {:keys [fail error]}
        (binding [t/report (fn [m]
                             (when (#{:fail :error} (:type m))
                               (swap! failed conj (test-name)))
                             (report m))]
          (apply t/run-tests nses))]
    (when (pos? (+ fail error))
      (throw (ex-info (str (+ fail error) " test assertion(s) failed or erred")
                      {:babashka/exit 1
                       :failed-tests (vec (distinct @failed))})))))

;; ---------------------------------------------------------------- the record

(defn- exit-of [e]
  (let [d (ex-data e)]
    (or (:babashka/exit d) (:exit d) 1)))

(defn outcome
  "What one run of `steps` ended as: {:exit 0} or {:exit n :step name ...}.
  `steps` is [[name thunk] ...], run in order, stopping at the first throw -
  a `shell` that exits non-zero throws, and so does `run-tests!`."
  [steps]
  (reduce (fn [_ [step f]]
            (try (f) {:exit 0}
                 (catch Exception e
                   (reduced (cond-> {:exit (exit-of e) :step step}
                              (:failed-tests (ex-data e)) (assoc :failed-tests (:failed-tests (ex-data e)))
                              (not (ex-data e)) (assoc :error (str (class e) ": " (ex-message e))))))))
          {:exit 0}
          steps))

(defn record
  "The record written for one gates run. Pure."
  [{:keys [tree tree-after head started ended]} result]
  (merge {:tree tree :tree-after tree-after :head head
          :started started :ended ended}
         result))

(defn read-record
  "The record at `root`, or nil when there is none."
  [root]
  (let [f (fs/path root record-path)]
    (when (fs/exists? f)
      (edn/read-string (slurp (str f))))))

(def runs-dir
  "Every run's record, kept, one file per run named by its start, beside `last.edn`."
  ".local/gates/runs")

(defn- write-record!
  "`last.edn`, and the run's own copy under `runs/`, every one kept."
  [root rec]
  (let [f (fs/path root record-path)
        run (fs/path root runs-dir (str (str/replace (:started rec) ":" "-") ".edn"))
        text (with-out-str (pprint/pprint rec))]
    (fs/create-dirs (fs/parent run))
    (spit (str run) text)
    (spit (str f) text)
    (str f)))

(defn read-runs
  "Every kept run record at `root`; a file that does not read is skipped."
  [root]
  (let [dir (fs/path root runs-dir)]
    (when (fs/exists? dir)
      (keep (fn [f] (try (edn/read-string (slurp (str f))) (catch Exception _ nil)))
            (fs/glob dir "*.edn")))))

;; ---------------------------------------------------------------- flaky tests

(defn- tests-ran?
  "Whether a run reached the test step: green, or red at it."
  [rec]
  (or (zero? (:exit rec)) (= "test" (:step rec))))

(defn flaky
  "The tests that failed in one run and passed in a later run on the SAME tree:
  [{:test :tree :failed :passed}], the times of the failing run and the first
  later one it passed in. A fix changes the tree, so a fixed test is never
  here. Pure."
  [records]
  (let [ordered (sort-by :started (filter tests-ran? records))]
    (->> ordered
         (mapcat (fn [rec]
                   (for [t (:failed-tests rec)
                         :let [later (->> ordered
                                          (filter #(and (= (:tree %) (:tree rec))
                                                        (pos? (compare (:started %) (:started rec)))
                                                        (not (some #{t} (:failed-tests %)))))
                                          first)]
                         :when later]
                     {:test t :tree (:tree rec) :failed (:started rec) :passed (:started later)})))
         distinct
         vec)))

(defn flaky-now
  "The lines `bb gates` prints after run `rec`: each test that failed earlier on
  this tree and passed in this run. Pure."
  [rec records]
  (when (tests-ran? rec)
    (->> (flaky records)
         (filter #(and (= (:tree %) (:tree rec))
                       (not (some #{(:test %)} (:failed-tests rec)))))
         (map :test)
         distinct
         (map #(str "flaky: " % " failed earlier on this tree and passed in this run")))))

(defn recorded!
  "Run the gates' `steps` ([[name thunk] ...]) and write the record, red or
  green; then exit with the run's exit when it is not 0. Outside a git
  repository there is no tree to name, and nothing is written."
  [steps]
  (let [root (repo-root ".")
        tree (some-> root working-tree)
        started (str (java.time.Instant/now))
        result (outcome steps)]
    (if root
      (let [rec (record {:tree tree :tree-after (working-tree root) :head (head root)
                         :started started :ended (str (java.time.Instant/now))}
                        result)]
        (println (str "gates record: " (fs/relativize (fs/absolutize ".") (write-record! root rec))
                      (if (zero? (:exit result)) " (green)" (str " (RED at " (:step result) ")"))))
        (doseq [line (flaky-now rec (read-runs root))]
          (println line)))
      (println "gates record: not in a git repository, none written"))
    (when-not (zero? (:exit result))
      (some->> (:error result) (println "error:"))
      (System/exit (:exit result)))))

;; ---------------------------------------------------------------- the commit check

(def ^:private run-gates "run `bb repair && bb gates` in harness/ on exactly what is committed")

(defn refusal
  "Why a commit of `staged` (a tree id) is refused on `rec` (the gates record,
  or nil), or nil when it is not. Pure."
  [rec staged]
  (cond
    (nil? rec)
    (str "no gates record (" record-path "): " run-gates)

    (not (zero? (:exit rec)))
    (str "the last gates run was red, at " (:step rec)
         (when-let [ts (seq (:failed-tests rec))] (str " - " (str/join ", " ts)))
         ": fix it and " run-gates)

    (not= (:tree rec) (:tree-after rec))
    (str "the working tree changed while the gates ran (" (:started rec) "): " run-gates)

    (not= (:tree rec) staged)
    (str "the gates ran on tree " (some-> (:tree rec) (subs 0 7))
         " and this commit is tree " (some-> staged (subs 0 7))
         " - a file edited since, a partial commit, or an untracked file at gates time: "
         run-gates)))

(def code-paths
  "Where a staged change is code (or a skill, or the plan template) that a
  document has to carry: what changed and why in `DEVLOG.md`, or what is still
  open in `NOTES.md`."
  ["harness/src/" "tools/" "skills/" "plan-template/"])

(def documents #{"DEVLOG.md" "NOTES.md"})

(defn document-refusal
  "Why a commit of the staged `paths` (repo-relative) is refused for want of a
  document, or nil: a path under `code-paths` staged with neither of
  `documents`. Pure."
  [paths]
  (let [code (filter (fn [p] (some #(str/starts-with? p %) code-paths)) paths)]
    (when (and (seq code) (not (some documents paths)))
      (str "code is staged without a document - " (str/join ", " (take 3 code))
           (when (> (count code) 3) (str " and " (- (count code) 3) " more"))
           ": a finding is not finished until DEVLOG.md or NOTES.md carries it; stage the entry with it"))))

(def ^:private stamp-re #"(?m)^\*\*Updated (\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})")

(defn stamp
  "The `**Updated <date> <time>` stamp at the head of a `NOTES.md` text, as a
  local date-time, or nil when it has none."
  [text]
  (when-let [[_ d t] (some->> text (re-find stamp-re))]
    (java.time.LocalDateTime/parse (str d "T" t))))

(defn stamp-refusal
  "Why a staged `NOTES.md` is refused for its stamp, or nil: it has none, it is
  not later than HEAD's (`head-text` nil for a new file), or it is later than
  `now` (a local date-time; the stamp is read to the minute). Pure."
  [head-text staged-text now]
  (let [new (stamp staged-text)
        old (stamp head-text)]
    (cond
      (nil? new)
      "NOTES.md is staged with no `**Updated <date> <time>` stamp at its head"

      (and old (not (.isAfter new old)))
      (str "NOTES.md is staged and its stamp was not bumped (" new ", HEAD has " old
           "): every edit moves `**Updated` to the time of the edit")

      (.isAfter new (.truncatedTo now java.time.temporal.ChronoUnit/MINUTES))
      (str "NOTES.md's stamp " new " is later than the clock ("
           (.truncatedTo now java.time.temporal.ChronoUnit/MINUTES) ")"))))

(def names-path
  "The project names no committed line may carry, one per line, `#` a comment.
  Under `.local/`, so the list itself never names a project in a committed file."
  ".local/wording/names.txt")

(defn read-names
  "The names in the wording list at `root`, or nil when there is no list."
  [root]
  (let [f (fs/path root names-path)]
    (when (fs/exists? f)
      (->> (str/split-lines (slurp (str f)))
           (map str/trim)
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           vec))))

(defn name-pattern
  "A name as a whole word: case-insensitive, no letter or digit just before or
  after it, so `-`, `_`, `.` and `/` end a word and a digit does not. Pure."
  [nm]
  (re-pattern (str "(?i)(?<![\\p{L}\\p{N}])" (java.util.regex.Pattern/quote nm) "(?![\\p{L}\\p{N}])")))

(defn added-lines
  "The lines a unified diff with no context (`git diff -U0`) adds:
  [{:file :line :text}], the line numbered in the new file. Pure."
  [diff]
  (loop [[l & more] (str/split-lines (or diff "")) prev nil file nil n 0 acc []]
    (cond
      (nil? l) acc
      ;; a header only after its `---` line: an added line reading `++ x` is `+++ x` too
      (and (str/starts-with? l "+++ ") (some-> prev (str/starts-with? "--- ")))
      (recur more l (when-not (= l "+++ /dev/null")
                      (-> (subs l 4) (str/replace #"^\"|\"$" "") (str/replace #"^b/" "")))
             n acc)
      (str/starts-with? l "@@ ")
      (recur more l file (parse-long (second (re-find #"\+(\d+)" l))) acc)
      (and file (str/starts-with? l "+"))
      (recur more l file (inc n) (conj acc {:file file :line n :text (subs l 1)}))
      :else (recur more l file n acc))))

(defn wording-refusals
  "Every added line that names a listed project: one reason each, the file,
  the line and the name. `.local/` is never asked. Pure."
  [names added]
  (for [{:keys [file line text]} added
        :when (not (str/starts-with? file ".local/"))
        nm names
        :when (re-find (name-pattern nm) text)]
    (str file ":" line " names a listed project (" nm ") - the KIT's documents say \"the first project built with the KIT\", never its name")))

(defn staged-paths
  "The paths the commit changes, from the index git names."
  [root]
  (some-> (git root ["diff" "--cached" "--name-only" "-z"])
          (str/split #"\u0000")
          (->> (remove str/blank?))))

(defn commit-check-main
  "`bb commit-check`: the pre-commit hook's one command. Exit 1 with every
  reason when the commit is refused; `git commit --no-verify` skips it, which
  is said, not hidden."
  [& _]
  (let [root (or (repo-root ".") (do (println "commit-check: not in a git repository") (System/exit 1)))
        rec (read-record root)
        paths (staged-paths root)
        notes (when (some #{"NOTES.md"} paths) (git root ["show" ":NOTES.md"]))
        names (read-names root)
        whys (concat (keep identity [(refusal rec (staged-tree root))
                                     (document-refusal paths)
                                     ;; a NOTES.md the commit deletes has no stamp to bump
                                     (when notes
                                       (stamp-refusal (git root ["show" "HEAD:NOTES.md"]) notes
                                                      (java.time.LocalDateTime/now)))])
                     (when names
                       (wording-refusals names (added-lines (git root ["diff" "--cached" "-U0" "--no-color" "--no-ext-diff"])))))]
    (when-not names
      (println (str "commit-check: no wording list (" names-path "), names not checked")))
    (if (seq whys)
      (do (doseq [why whys] (println (str "commit refused: " why)))
          (System/exit 1))
      (println (str "commit-check: gates green on this tree (" (:ended rec) ")")))))
