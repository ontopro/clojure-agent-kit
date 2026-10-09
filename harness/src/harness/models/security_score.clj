(ns harness.models.security-score
  "Scoring a security review against the fixture's answer key: `bb security-score <review.edn>
  <fixture-dir>`. No model reads anything here; the tests the reviewer wrote are run.

  A TEST IS RUN ON ITS OWN, ON EACH BRANCH THAT MATTERS, AND JUDGED BY THE `deftest`. The faulted
  branch (all six faults), the careful one (none), and for each fault the variant with only that
  fault reverted. A test that fails on the faulted branch and passes on the careful one
  DISCRIMINATES; of the variants, the ones on which it passes are the faults it detects, because
  reverting just that fault was enough. That is a hit, and it needs nobody's opinion. The unit is
  the `deftest`, not the file: a reviewer that bundles seven checks in one file has not made seven
  files' worth of mistakes, and no single revert makes a whole bundle pass.

  What does not discriminate is classified, not judged: a test that fails on both branches is
  UNPLANTED - a real flaw nobody planted, an invented one, or a test whose own setup was refused;
  the person marks which, and the first failure is quoted to help. A test that passes on the
  faulted branch is a concern that did not hold; one that does not compile is nothing. A
  whole-suite run would hide all of this behind one broken file, which is why each test
  namespace is run alone.

  PURE UP TO THE EDGE: `test-ns`, `status`, `why`, `classify` and `summary` are functions of data;
  `outcomes!` clones the fixture's branches, starts the sandbox and runs the tests."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.models.review-sandbox :as sandbox]
   [harness.models.review-tools :as review-tools]))

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn test-ns
  "`test/notes/review_x_test.clj` -> `notes.review-x-test`."
  [path]
  (-> (str path)
      (str/replace #"^test/" "")
      (str/replace #"\.clj$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")))

(def compile-error
  "What a namespace that will not load says. NOT the word `Compiler`: a test that fails with an
  exception has `clojure.lang.Compiler` frames in its stack trace, and a first scoring took such a
  run for one that does not compile and threw away four findings that held."
  #"(?i)syntax error|Unable to resolve symbol|Could not locate .* on classpath|No such namespace|No such var|Unable to resolve classname")

(defn status
  "How one test namespace ended: `:passes`, `:fails`, `:does-not-compile`, or `:no-tests` (a
  helper namespace with no test in it). `result` is `{:exit :out}`."
  [{:keys [exit out]}]
  (let [out (str out)]
    (cond
      (and (zero? (or exit 1)) (re-find #"Ran 0 tests" out)) :no-tests
      (zero? (or exit 1)) :passes
      (re-find compile-error out) :does-not-compile
      :else :fails)))

(defn why
  "The first failure in a test run's output, as a few lines: what to show a person deciding what
  a failing test means. Cleaned of the runner's decoration and cut short."
  [out]
  (let [lines (str/split-lines (review-tools/clean-output out))
        from (first (keep-indexed (fn [i l] (when (re-find #"^(FAIL|ERROR) in " l) i)) lines))]
    (when from
      (let [s (str/join " | " (take 4 (drop from lines)))]
        (if (> (count s) 300) (str (subs s 0 300) "...") s)))))

(defn deftest-names
  "The names of the tests a test file defines, in order: its `deftest` forms, metadata skipped."
  [text]
  (vec (map second (re-seq #"\(deftest\s+(?:\^\S+\s+)*([^\s()\[\]]+)" (str text)))))

(defn failing-vars
  "The tests a run reports as failed or in error, by name: `FAIL in (a-test) (file.clj:5)`."
  [out]
  (set (map second (re-seq #"(?:FAIL|ERROR) in \(([^)\s]+)\)" (str out)))))

(defn unit-statuses
  "Each test of a namespace's status from how the namespace's run ended: a namespace that does not
  compile fails to compile every test in it; one that fails with no test named (an error outside
  any) fails them all, since nothing says which held; a namespace with no tests has none. `vars` are
  the names the file defines, `out` the run's output."
  [ns-status vars out]
  (let [failing (failing-vars out)]
    (case ns-status
      :no-tests {}
      :does-not-compile (zipmap vars (repeat :does-not-compile))
      :passes (zipmap vars (repeat :passes))
      :fails (let [named (filter failing vars)]
               (if (seq named)
                 (into {} (for [v vars] [v (if (failing v) :fails :passes)]))
                 (zipmap vars (repeat :fails)))))))

(defn classify
  "What one test namespace shows, from its outcomes: `{:faulted s :clean s :variants {fault s}}`.
  Returns `{:class _ :hits [fault ...]}`:
    :hit            fails on the faulted branch, passes on the careful one, passes with one
                    fault reverted - `:hits` names it (or them)
    :unattributed   discriminates, but no single revert makes it pass
    :fails-on-both  unplanted: for the person to mark real, invented or a refused setup
    :did-not-hold   passes on the faulted branch: the concern was not a defect
    :does-not-compile / :no-tests"
  [{:keys [faulted clean variants]}]
  (cond
    (= :no-tests faulted) {:class :no-tests}
    (= :does-not-compile faulted) {:class :does-not-compile}
    (= :passes faulted) {:class :did-not-hold}
    (= :fails clean) {:class :fails-on-both}
    (= :does-not-compile clean) {:class :does-not-compile}
    :else (let [hits (vec (sort (for [[fault s] variants :when (= :passes s)] fault)))]
            (if (seq hits) {:class :hit :hits hits} {:class :unattributed :hits []}))))

(defn discriminates?
  "Whether the outcomes so far make a variant run worth doing."
  [{:keys [faulted clean]}]
  (and (= :fails faulted) (= :passes clean)))

(defn summary
  "The score: which planted faults were found (each with the tests that found it), which were
  missed, every test's class, and every finding's class:
    :hit            its test detects a planted fault
    :unattributed / :fails-on-both  its test fails where the answer key says it should not or
                    cannot say - for the person to mark
    :not-reproduced it says `reproduced` and its test passes on the faulted branch, does not
                    compile, or does not exist
    :hypothesis     it says so, and claims no test
  `tests` is `ns/test` -> classify's map; `findings` the review's; `faults` the fixture's ids. A
  finding names a FILE, which may hold many tests, so it is given the best class of the tests in
  that file (hit, then unattributed, then fails-on-both): its claim is as good as the best thing
  its file shows. The count of planted faults found is by test and does not depend on it."
  [tests findings faults]
  (let [found (reduce (fn [m [ns-name {:keys [hits]}]]
                        (reduce #(update %1 %2 (fnil conj []) ns-name) m hits))
                      {} (sort-by key tests))
        in-file (fn [test] (let [prefix (str (test-ns test) "/")]
                             (keep (fn [[k v]] (when (str/starts-with? k prefix) v)) tests)))
        finding-class (fn [{:keys [kind test]}]
                        (let [cs (set (map :class (in-file test)))]
                          (cond (= :hypothesis kind) :hypothesis
                                (cs :hit) :hit
                                (cs :unattributed) :unattributed
                                (cs :fails-on-both) :fails-on-both
                                :else :not-reproduced)))
        classed (mapv #(assoc (select-keys % [:title :kind :test]) :class (finding-class %)) findings)]
    {:found (into (sorted-map) found)
     :missed (vec (remove found faults))
     :tests tests
     :findings classed
     :counts (assoc (frequencies (map :class classed)) :findings (count findings))}))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn- sh [dir & argv]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true} argv)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str (str/join " " argv) " failed: " (str/trim (str (:err r) (:out r)))) {:argv argv})))
    (:out r)))

(defn- run-branch!
  "The test namespaces `nses` run one at a time in the sandbox, on a fresh single-branch clone of
  `branch` with the reviewer's test files copied in: ns -> {:status _ :why _ :units {test status}}."
  [kit app-dir branch files nses]
  (let [scratch (str (fs/create-temp-dir {:prefix "kit-score"}))
        clone (str (fs/path scratch "app"))]
    (try
      (sh scratch "git" "clone" "-q" "--single-branch" "--branch" branch "--no-tags" app-dir clone)
      (doseq [[rel text] files]
        (fs/create-dirs (fs/parent (fs/path clone rel)))
        (spit (str (fs/path clone rel)) text))
      (let [sb (sandbox/start! {:kit kit :clone clone})]
        (try
          (into (sorted-map)
                (for [n nses
                      :let [r ((:run-tests sb) n)
                            st (status r)
                            vars (deftest-names (some (fn [[path text]] (when (= n (test-ns path)) text)) files))]]
                  [n {:status st
                      :why (when (not= :passes st) (why (:out r)))
                      :units (unit-statuses st vars (:out r))}]))
          (finally ((:stop! sb)))))
      (finally (fs/delete-tree scratch)))))

(defn outcomes!
  "Run the reviewer's tests where they matter. `cases` is the fixture's cases.edn, `files` the
  reviewer's tests (path -> text). Returns `ns/test` -> `{:faulted s :clean s :variants {fault s}
  :why _}`, one entry per `deftest`. The variants are run only for the namespaces with a test that
  discriminates."
  [kit app-dir cases files]
  (let [nses (vec (sort (distinct (map test-ns (keys files)))))
        faulted (run-branch! kit app-dir (:faulted cases) files nses)
        clean (run-branch! kit app-dir (:clean cases) files nses)
        base (into (sorted-map)
                   (for [n nses
                         v (distinct (concat (keys (get-in faulted [n :units])) (keys (get-in clean [n :units]))))]
                     [(str n "/" v) {:ns n
                                     :faulted (get-in faulted [n :units v])
                                     :clean (get-in clean [n :units v])
                                     :why (or (get-in faulted [n :why]) (get-in clean [n :why]))}]))
        worth (vec (distinct (for [[_ o] base :when (discriminates? o)] (:ns o))))
        variants (into {} (when (seq worth)
                            (for [[fault branch] (:variants cases)]
                              [fault (run-branch! kit app-dir branch files worth)])))]
    (into (sorted-map)
          (for [[k o] base]
            [k (-> o
                   (dissoc :ns)
                   (assoc :variants (into {} (for [[fault results] variants :when (contains? results (:ns o))]
                                               [fault (get-in results [(:ns o) :units (subs k (inc (count (:ns o))))])]))))]))))

(defn score!
  "Score the review recorded in `review-file` (a `security-review` record, its tests beside it in
  `<record>-tests/`) against the fixture built in `fixture-dir`. Returns the summary, with the
  review's identity and the time the scoring took."
  [kit review-file fixture-dir]
  (let [review (edn/read-string (slurp review-file))
        tests-dir (fs/path (str/replace review-file #"\.edn$" "-tests"))
        files (into (sorted-map)
                    (for [rel (:tests review)]
                      [rel (slurp (str (fs/path tests-dir rel)))]))
        cases (edn/read-string (slurp (str (fs/path fixture-dir "cases.edn"))))
        t0 (System/currentTimeMillis)
        out (outcomes! kit (str (fs/path fixture-dir "notes")) cases files)
        tests (into (sorted-map) (for [[n o] out] [n (merge (classify o) (select-keys o [:why :faulted :clean]))]))]
    (assoc (summary tests (:findings review) (:faults cases))
           :review (str (fs/file-name review-file))
           :model (:model review)
           :stance (:stance review)
           :ms (- (System/currentTimeMillis) t0))))

(defn print-score [{:keys [found missed tests findings counts review model stance]}]
  (println (str "\n  " review " - " model ", " (some-> stance name)))
  (println "\n  planted faults found:")
  (doseq [[fault nses] found] (println (str "    " (name fault) " - " (str/join ", " nses))))
  (doseq [fault missed] (println (str "    " (name fault) " - MISSED")))
  (println "\n  tests (each namespace run alone, each `deftest` judged):")
  (doseq [[n {:keys [class hits why]}] tests]
    (println (format "    %-62s %-16s %s" n (name class) (cond (seq hits) (str "detects " (str/join ", " (map name hits)))
                                                               (= :fails-on-both class) (str "for you to mark: " why)
                                                               :else ""))))
  (println "\n  findings:")
  (doseq [{:keys [title kind class]} findings]
    (println (format "    %-16s %-11s %s" (name class) (name (or kind :hypothesis)) title)))
  (println (str "\n  " (count found) " of " (+ (count found) (count missed)) " planted faults found; "
                (get counts :fails-on-both 0) " finding(s) for you to mark; "
                (get counts :not-reproduced 0) " claimed but not reproduced; "
                (get counts :hypothesis 0) " hypotheses.")))

(defn -main
  "bb security-score <review.edn> <fixture-dir>"
  [& args]
  (let [[review fixture] args
        kit (str (fs/normalize (fs/absolutize "..")))]
    (when-not (and review fixture)
      (println "usage: bb security-score <review.edn> <fixture-dir>\n  <review.edn> a bb security-review record, its tests beside it in <record>-tests/\n  <fixture-dir> the folder bb security-fixture built (notes/ and cases.edn)")
      (System/exit 2))
    (let [s (score! kit (str (fs/absolutize review)) (str (fs/absolutize fixture)))
          out (str/replace (str (fs/absolutize review)) #"\.edn$" "-score.edn")]
      (print-score s)
      (spit out (with-out-str (pp/pprint s)))
      (println (str "  score: " out)))))
