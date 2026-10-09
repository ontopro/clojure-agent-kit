(ns harness.gates.kit-status
  "`bb kit-status`: what an item's end should look at, printed and never
  refused - an item's end is a judgement, not a gate.

  Five reports, each `ok` or a list: the health records stale (item 6, the
  check `bb tag-check` makes); the tests that failed and later passed on
  the same tree since the last version tag (flaky, from the gates' kept run
  records); the register's rows opened since the last tag that are still open
  or on watch; the plan's Sessions log older than the last commit (the plan
  named by the branch: `plan-v6.2` -> `.local/plans/kit-plan-v6.2.md`); and a
  `.local/` folder without its `README.md`, or an entry in one that its README
  never names. Exit 0 always. The KIT's own development only."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [harness.gates.record :as record]
   [harness.gates.tag-check :as tag-check]))

(defn- git [root & args]
  (let [{:keys [exit out]} (apply p/shell {:dir (str root) :out :string :err :string :continue true} "git" args)]
    (when (zero? exit) (str/trim out))))

;; ---------------------------------------------------------------- pure

(defn register-rows
  "The rows of `NOTES.md`'s register: [{:row n :title :status}], the title the
  finding's bold opening, the status its cell as written."
  [notes]
  (for [line (str/split-lines (or notes ""))
        :let [[_ n] (re-find #"^\| (\d+) \|" line)]
        :when n
        :let [cells (map str/trim (str/split line #"(?<!\\)\|"))
              finding (nth cells 2 "")]]
    {:row (parse-long n)
     :title (or (second (re-find #"^\*\*(.+?)\*\*" finding)) (subs finding 0 (min 80 (count finding))))
     :status (nth cells 4 "")}))

(defn open-rows-since
  "The rows `notes` has that `before` did not, still open or on watch."
  [before notes]
  (let [known (set (map :row (register-rows before)))]
    (->> (register-rows notes)
         (remove #(known (:row %)))
         (filter #(re-find #"(?i)open|watch" (:status %))))))

(defn unnamed-entries
  "The entries of a `.local/` folder its README never names - as `name/` or
  `name` between word boundaries; a dotfile (`.DS_Store`) is not asked."
  [readme entries]
  (remove (fn [e] (or (= e "README.md") (str/starts-with? e ".")
                      (re-find (re-pattern (str "(?<![\\w.-])" (java.util.regex.Pattern/quote e) "(?![\\w.-])"))
                               (or readme ""))))
          entries))

(defn flaky-since
  "Item 2's flaky tests, those whose failing run started at or after `since`
  (an instant string, or nil for every run)."
  [records since]
  (filter #(or (nil? since) (not (neg? (compare (:failed %) since))))
          (record/flaky records)))

;; ---------------------------------------------------------------- facts

(defn local-dirs
  "Every `.local` folder in the clone, not descending into one or into `.git`."
  [root]
  (let [found (atom [])]
    (fs/walk-file-tree root
                       {:pre-visit-dir (fn [d _]
                                         (let [n (str (fs/file-name d))]
                                           (cond (= n ".git") :skip-subtree
                                                 (= n ".local") (do (swap! found conj d) :skip-subtree)
                                                 :else :continue)))})
    @found))

(defn plan-file [root branch]
  (when-let [[_ v] (some->> branch (re-matches #"plan-v(.+)"))]
    (fs/path root ".local/plans" (str "kit-plan-v" v ".md"))))

(defn- report [title lines]
  (println (str "\n" title))
  (if (seq lines)
    (doseq [l lines] (println (str "  - " l)))
    (println "  ok")))

(defn -main [& _]
  (let [root (or (git "." "rev-parse" "--show-toplevel")
                 (do (println "kit-status: not in a git repository") (System/exit 0)))
        since-tag (tag-check/last-tag root nil)
        since-time (some->> since-tag (git root "log" "-1" "--format=%cI") java.time.OffsetDateTime/parse .toInstant str)
        branch (git root "branch" "--show-current")
        plan (plan-file root branch)
        head-time (some-> (git root "log" "-1" "--format=%ct") parse-long (* 1000))]
    (println (str "kit-status on " branch " at " (git root "rev-parse" "--short" "HEAD")
                  (when since-tag (str ", since " since-tag))))
    (report "Flaky tests (failed, then passed on the same tree):"
            (for [{:keys [test tree failed passed]} (flaky-since (record/read-runs root) since-time)]
              (str test " - tree " (subs tree 0 7) ", failed " failed ", passed " passed)))
    (report "Health records (stale when code `bb health` runs changed since the commit each names):"
            (let [{:keys [ok? detail]} (tag-check/health-check (tag-check/read-records root))]
              (when-not ok? [detail])))
    (report (str "Register rows opened since " (or since-tag "the start") ", still open or on watch:")
            (for [{:keys [row title status]} (open-rows-since (when since-tag (git root "show" (str since-tag ":NOTES.md")))
                                                              (slurp (str (fs/path root "NOTES.md"))))]
              (str "row " row " (" status "): " title)))
    (report "The plan's Sessions log:"
            (cond (nil? plan) [(str "no plan for `" branch "` (a `plan-v*` branch names one)")]
                  (not (fs/exists? plan)) [(str "no plan file at " (fs/relativize root plan))]
                  (< (.toMillis (fs/last-modified-time plan)) head-time)
                  [(str (fs/relativize root plan) " was last written before HEAD's commit - log the item's end in it")]))
    (report "`.local/` folders and their README.md:"
            (mapcat (fn [d]
                      (let [readme (fs/path d "README.md")
                            rel (str (fs/relativize root d))]
                        (if-not (fs/exists? readme)
                          [(str rel "/ has no README.md")]
                          (for [e (unnamed-entries (slurp (str readme))
                                                   (sort (map #(str (fs/file-name %)) (fs/list-dir d))))]
                            (str rel "/" e " is not named in " rel "/README.md")))))
                    (local-dirs root)))))
