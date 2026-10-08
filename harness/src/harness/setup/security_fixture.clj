(ns harness.setup.security-fixture
  "`bb security-fixture <dir>`: the application a security reading is measured on - generated
  from the pin, with a small feature added twice, once clean and once with six faults planted
  (`health/security-fixture/`). Its answer key is a reference test per fault, which passes on
  the clean feature and fails on the faulted one; the build runs them and refuses a fixture
  whose key does not hold.

  THE APPLICATION CARRIES NOTHING OF THE KEY. The faults are data here (`faults/*.edn`, each a
  replacement made once in one file, as `bb health` plants its breakers), the reference tests
  live beside them and are copied into a clone only to be run, and the two feature branches
  have neutral names (`feature-1`, `feature-2`) whose assignment is drawn at the build and
  written to `cases.edn` OUTSIDE the repository. A reader handed a clone of either branch sees
  an application and a commit message, the same on both.

  PURE UP TO THE EDGE: `load-faults`, `apply-edits`, `failing-tests` and `problems` are
  functions of data; `build!` and `verify!` run git, deps-new and the application's tests."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.setup.template :as template]))

(def app-name "notes")

(def message
  "The feature's commit message, the same on both branches."
  "notes: accounts, private notes with a quota, an export, administration")

(defn fixture-dir
  "The fixture's sources in the KIT: the overlay, the faults, the reference tests."
  [kit]
  (str (fs/path kit "harness" "health" "security-fixture")))

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn load-faults
  "Every fault under `dir`'s `faults/`, sorted by id: `{:id :kind :what :reference :edits
  [{:file :find :replace}]}`."
  [dir]
  (->> (fs/glob (fs/path dir "faults") "*.edn")
       (map #(edn/read-string (slurp (str %))))
       (sort-by :id)
       vec))

(defn apply-edits
  "`files` (path -> text) with every edit of `faults` made. An edit whose text to replace does
  not occur exactly once is refused: a fault that lands twice, or nowhere, is not the fault
  the key describes."
  [files faults]
  (reduce (fn [acc {:keys [id edits]}]
            (reduce (fn [acc {:keys [file find replace]}]
                      (let [text (get acc file)
                            n (if text (count (re-seq (re-pattern (java.util.regex.Pattern/quote find)) text)) 0)]
                        (when-not (= 1 n)
                          (throw (ex-info (str "fault " (name id) ": its text occurs " n " times in " file) {:fault id :file file :n n})))
                        (assoc acc file (str/replace-first text find replace))))
                    acc edits))
          files faults))

(defn failing-tests
  "The tests a test run's output names as failed or in error, by their var's name."
  [output]
  (set (map second (re-seq #"(?:FAIL|ERROR) in (?:[\w.-]+/)?([\w?!*<>=+.-]+)" (str output)))))

(defn problems
  "Why the key does not hold, one sentence each: on the clean feature every reference test
  passes; on the faulted one exactly the faults' reference tests fail. `clean` and `faulted`
  are the failing test names of each run."
  [clean faulted faults]
  (let [expected (set (map :reference faults))]
    (concat
     (for [t (sort clean)] (str "the clean feature fails " t))
     (for [t (sort (remove faulted expected))] (str "the faulted feature passes " t ", so its fault is not reproduced"))
     (for [t (sort (remove expected faulted))] (str "the faulted feature fails " t ", which no fault names")))))

;; ---------------------------------------------------------------------------
;; the edge: generate, commit, verify
;; ---------------------------------------------------------------------------

(defn- sh [dir & argv]
  (let [r (apply p/shell {:dir dir :out :string :err :string :continue true} argv)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str (str/join " " argv) " failed: " (str/trim (str (:err r) (:out r)))) {:argv argv})))
    (:out r)))

(defn- git [dir & args]
  (apply sh dir "git" "-c" "user.name=kit" "-c" "user.email=kit@localhost" args))

(defn- overlay-files
  "The overlay as path -> text, paths relative to the application."
  [fix]
  (let [root (fs/path fix "overlay")]
    (into (sorted-map)
          (for [f (fs/glob root "**") :when (fs/regular-file? f)]
            [(str (fs/relativize root f)) (slurp (str f))]))))

(defn- write-files! [app files]
  (doseq [[rel text] files]
    (fs/create-dirs (fs/parent (fs/path app rel)))
    (spit (str (fs/path app rel)) text)))

(defn build!
  "Generate the application into `<dir>/notes` from the pin, commit it as `base`, and add the
  feature on two branches from it, clean and faulted, in an order drawn now. Writes
  `<dir>/cases.edn` - `{:clean branch :faulted branch :base \"base\" :faults [ids]}` - and
  returns it."
  [kit dir]
  (let [fix (fixture-dir kit)
        app (str (fs/path dir app-name))
        faults (load-faults fix)
        overlay (overlay-files fix)
        [clean-branch faulted-branch] (shuffle ["feature-1" "feature-2"])]
    (when (fs/exists? app) (throw (ex-info (str app " exists; build into an empty folder") {:app app})))
    (fs/create-dirs dir)
    (apply sh dir (template/create-command (template/pin (template/load-pins)) {:app-name app-name :target-dir app}))
    (git app "init" "-q")
    (git app "add" "-A")
    (git app "commit" "-q" "-m" "base: the application as the template generates it")
    (git app "tag" "base")
    (doseq [[branch files] [[clean-branch overlay]
                            [faulted-branch (apply-edits overlay faults)]]]
      (git app "checkout" "-q" "-b" branch "base")
      (write-files! app files)
      (git app "add" "-A")
      (git app "commit" "-q" "-m" message))
    (git app "checkout" "-q" "base")
    (let [cases {:base "base" :clean clean-branch :faulted faulted-branch :faults (mapv :id faults)}]
      (spit (str (fs/path dir "cases.edn")) (with-out-str (pp/pprint cases)))
      cases)))

(defn- run-reference
  "The reference tests run on a fresh clone of `branch`: the failing test names, and the output."
  [kit app branch]
  (let [scratch (str (fs/create-temp-dir {:prefix "kit-fixture"}))
        clone (str (fs/path scratch app-name))]
    (try
      (sh scratch "git" "clone" "-q" "--branch" branch app clone)
      (fs/copy (fs/path (fixture-dir kit) "reference" "reference_test.clj")
               (fs/path clone "test" app-name "reference_test.clj"))
      (let [r (p/shell {:dir clone :out :string :err :string :continue true} "bb" "test")
            out (str (:out r) (:err r))]
        {:failing (failing-tests out) :exit (:exit r) :output out})
      (finally (fs/delete-tree scratch)))))

(defn verify!
  "The key, held: the reference tests on each branch. Returns `{:clean :faulted :problems}`."
  [kit dir]
  (let [cases (edn/read-string (slurp (str (fs/path dir "cases.edn"))))
        app (str (fs/path dir app-name))
        clean (run-reference kit app (:clean cases))
        faulted (run-reference kit app (:faulted cases))]
    {:clean clean
     :faulted faulted
     :problems (vec (problems (:failing clean) (:failing faulted) (load-faults (fixture-dir kit))))}))

(defn -main
  "bb security-fixture <dir>: build the fixture into an empty <dir> and verify its key."
  [& args]
  (let [[dir] args
        kit (str (fs/normalize (fs/absolutize "..")))]
    (when-not dir
      (println "usage: bb security-fixture <dir> - an empty folder to build the fixture in")
      (System/exit 2))
    (let [dir (str (fs/normalize (fs/absolutize dir)))
          cases (build! kit dir)
          {:keys [clean faulted problems]} (verify! kit dir)]
      (println (str "security fixture in " dir "/" app-name ": base, and the feature on "
                    (:clean cases) " and " (:faulted cases) " (which is which: " dir "/cases.edn)"))
      (println (str "  clean:   " (count (:failing clean)) " reference tests failing"))
      (println (str "  faulted: " (count (:failing faulted)) " failing - " (str/join ", " (sort (:failing faulted)))))
      (if (seq problems)
        (do (doseq [pr problems] (println "  PROBLEM:" pr))
            (System/exit 1))
        (println "  the key holds: every fault reproduced, nothing else failing")))))
