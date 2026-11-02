(ns harness.models.security-score
  "Scoring a security review against the fixture's answer key: `bb security-score <review.edn>
  <fixture-dir>`. No model reads anything here; the tests the security reviewer wrote are run.

  A TEST IS RUN ON ITS OWN, ON EACH BRANCH THAT MATTERS, AND JUDGED BY THE `deftest`. The faulted
  branch (all six faults), the careful one (none), and for each fault the variant with only that
  fault reverted. A test that fails on the faulted branch and passes on the careful one
  DISCRIMINATES; of the variants, the ones on which it passes are the faults it detects, because
  reverting just that fault was enough. That is a hit, and it needs nobody's opinion. The unit is
  the `deftest`, not the file: a security reviewer that bundles seven checks in one file has not made seven
  files' worth of mistakes, and no single revert makes a whole bundle pass.

  What does not discriminate is classified, not judged: a test that fails on both branches is
  UNPLANTED - a real flaw nobody planted, an invented one, or a test whose own setup was refused;
  the person marks which, and the test's own failure on the careful branch is quoted to help. A test that passes on the
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
   [harness.contained-path :as contained-path]
   [harness.models.security-review-sandbox :as sandbox]
   [harness.models.security-review-tools :as security-review-tools]))

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

(defn status
  "How one test namespace ended: `:passes`, `:fails`, `:does-not-compile` (it never ran), or
  `:no-tests` (a helper namespace with no test in it). `result` is `{:exit :out}`.

  BY WHETHER THE RUN RAN, NOT BY WHAT ITS OUTPUT SAYS. A namespace that loads and runs prints `Ran N
  tests`; one that does not load prints no such line. Scanning the output for compile-error text
  was wrong twice: a stack trace of an ordinary failure names `clojure.lang.Compiler`, and the
  application's own log lines in a run's output can say `Unable to resolve symbol` of a request,
  so tests that ran and failed were called not compiling."
  [{:keys [exit out]}]
  (let [ran (some-> (re-find #"Ran (\d+) tests" (str out)) second parse-long)]
    (cond
      (zero? (or exit 1)) (if (= 0 ran) :no-tests :passes)
      (and ran (pos? ran)) :fails
      :else :does-not-compile)))

(defn why
  "The first failure in a test run's output, as a few lines: what to show a person deciding what
  a failing test means. Cleaned of the runner's decoration and cut short. With `test-name`, the first
  failure OF THAT TEST - `FAIL in (<name>)`, or `FAIL in <ns>/<name>` - nil when the run names none:
  a namespace's run holds every test of the file, and one test's failure is not another's reason."
  ([out] (why out nil))
  ([out test-name]
   (let [lines (str/split-lines (security-review-tools/clean-output out))
         head (if test-name
                (re-pattern (str "^(FAIL|ERROR) in (\\(" (java.util.regex.Pattern/quote test-name) "\\)|\\S+/"
                                 (java.util.regex.Pattern/quote test-name) "[\\s(])"))
                #"^(FAIL|ERROR) in ")
         from (first (keep-indexed (fn [i l] (when (re-find head l) i)) lines))]
     (when from
       (let [s (str/join " | " (take 4 (cons (nth lines from) (take-while #(not (re-find #"^(FAIL|ERROR) in " %)) (drop (inc from) lines)))))]
         (if (> (count s) 300) (str (subs s 0 300) "...") s))))))

(defn unit-whys
  "Each failing test's reason, from a namespace's run: the test's own failure where the output names
  it, else the namespace's first (`ns-why`: it did not compile, or failed with no test named). A test
  that passed has none."
  [units out ns-why]
  (into {} (for [[v st] units :when (not= :passes st)] [v (or (why out v) ns-why)])))

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
  `branch` with the security reviewer's test files copied in: ns -> {:status _ :units {test status}
  :whys {test reason}}."
  [kit app-dir branch files nses]
  (let [scratch (str (fs/create-temp-dir {:prefix "kit-score"}))
        clone (str (fs/path scratch "app"))]
    (try
      (sh scratch "git" "clone" "-q" "--no-hardlinks" "--single-branch" "--branch" branch "--no-tags" app-dir clone)
      ;; written only inside the clone, as read only inside the record's folder (row 177)
      (doseq [[rel text] files
              :let [p (contained-path/inside clone rel)]
              :when p]
        (fs/create-dirs (fs/parent p))
        (spit (str p) text))
      (let [sb (sandbox/start! {:kit kit :clone clone})]
        (try
          (into (sorted-map)
                (for [n nses
                      :let [r ((:run-tests sb) n)
                            st (status r)
                            vars (deftest-names (some (fn [[path text]] (when (= n (test-ns path)) text)) files))]]
                  [n (let [units (unit-statuses st vars (:out r))]
                       {:status st
                        :units units
                        :whys (unit-whys units (:out r) (when (not= :passes st) (why (:out r))))})]))
          (finally ((:stop! sb)))))
      (finally (fs/delete-tree scratch)))))

(defn outcomes!
  "Run the security reviewer's tests where they matter. `cases` is the fixture's cases.edn, `files` the
  security reviewer's tests (path -> text). Returns `ns/test` -> `{:faulted s :clean s :variants {fault s}
  :why-faulted _ :why-clean _}`, one entry per `deftest`, each reason that test's own on that branch. The variants are run only for the namespaces with a test that
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
                                     :why-faulted (get-in faulted [n :whys v])
                                     :why-clean (get-in clean [n :whys v])}]))
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
        ;; a record's test paths are the security reviewer's words: a test file's path, read inside its
        ;; folder - one outside, or one whose namespace a command could not carry, is not scored
        files (into (sorted-map)
                    (for [rel (:tests review)
                          :let [p (security-review-tools/record-test-file (fs/parent (fs/absolutize tests-dir)) tests-dir rel)]
                          :when (and p (security-review-tools/test-path? rel) (fs/regular-file? p))]
                      [rel (slurp (str p))]))
        cases (edn/read-string (slurp (str (fs/path fixture-dir "cases.edn"))))
        t0 (System/currentTimeMillis)
        out (outcomes! kit (str (fs/path fixture-dir "notes")) cases files)
        ;; THE REASON SHOWN IS THE ONE THE CLASS ASKS ABOUT: a test failing on both branches is for the
        ;; person to mark, and what decides it is why it fails where nothing was planted - the careful branch's
        tests (into (sorted-map)
                    (for [[n o] out
                          :let [c (classify o)
                                w (if (= :fails-on-both (:class c)) (:why-clean o) (:why-faulted o))]]
                      [n (cond-> (merge c (select-keys o [:faulted :clean :why-faulted :why-clean])) w (assoc :why w))]))]
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
