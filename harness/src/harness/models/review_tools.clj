(ns harness.models.review-tools
  "The tools a security reviewer works with, over a clone of the application under review.

  A reviewer reads the code, and checks a concern by writing a test for it and seeing the test
  fail. Its tools are those of a test harness and nothing more: read a file, search the tree,
  send one request to the application under test, write a test, run the tests. There is no
  REPL (`nrepl_eval` is a shell, and a reviewer needs none) and no tool that writes anywhere
  but `test/`.

  The tools are the same for every model that fills the role, so that a comparison of models
  measures the models and not their tools. They are a registry of the same shape as
  `harness.models.tools/specs` - name -> {:description :schema :fn} - handed to
  `harness.models.agent/converse!` as its `:registry` option, so the coder's tool set is
  untouched.

  `ctx` is `{:dir _ ...}`, the clone's root as the harness sees it. The tools that run anything
  (the request, the tests) take a `:sandbox` in it, built elsewhere; this namespace never starts
  a process of its own."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [harness.models.tools :as tools]))

;; ---------------------------------------------------------------------------
;; search
;; ---------------------------------------------------------------------------

(def ^:private skipped-dirs
  "Folders that are build output or tooling, not the application's source."
  #{".git" "target" ".cpcache" ".clj-kondo" ".lsp" "node_modules" ".idea" ".vscode" "classes"})

(def max-matches 100)
(def max-line 200)
(def max-file-bytes (* 1024 1024))

(defn- text-file?
  "A file small enough to read and without a NUL byte in its first 8 KB."
  [f]
  (and (<= (fs/size f) max-file-bytes)
       (with-open [in (java.io.FileInputStream. (str f))]
         (let [buf (byte-array 8192)
               n (.read in buf)]
           (not-any? zero? (take (max n 0) buf))))))

(defn- source-files
  "Every regular text file under `root`, sorted, outside the skipped folders."
  [root]
  (->> (fs/glob root "**" {:hidden true})
       (filter fs/regular-file?)
       (remove (fn [f] (some skipped-dirs (map str (fs/components (fs/relativize root f))))))
       (sort-by str)
       (filter text-file?)))

(defn- clip-line [s]
  (let [s (str/trim s)]
    (if (<= (count s) max-line) s (str (subs s 0 max-line) " [line cut]"))))

(defn- search
  "`pattern` (a regular expression) searched line by line through the clone, or only below
  `path`; `path:line: text` per match, at most `max-matches`."
  [{:keys [dir]} {:keys [pattern path]}]
  (let [root (fs/normalize (fs/absolutize (if (str/blank? path) dir (fs/path dir path))))
        base (fs/normalize (fs/absolutize dir))]
    (cond
      (str/blank? pattern) (tools/err "pattern is required")
      (not (str/starts-with? (str root) (str base))) (tools/err (str path " is outside the workspace"))
      (not (fs/exists? root)) (tools/err (str path " does not exist"))
      :else
      (let [re (try (re-pattern pattern) (catch Exception _ nil))]
        (if-not re
          (tools/err (str "not a valid regular expression: " pattern))
          (let [files (if (fs/regular-file? root) (filter text-file? [root]) (source-files root))
                hits (for [f files
                           [n line] (map-indexed vector (str/split-lines (slurp (str f))))
                           :when (re-find re line)]
                       (str (fs/relativize base f) ":" (inc n) ": " (clip-line line)))
                shown (take max-matches hits)
                more (- (count (take (inc max-matches) hits)) (count shown))]
            (tools/ok (if (empty? shown)
                        (str "no line matches " pattern)
                        (str (str/join "\n" shown)
                             (when (pos? more) (str "\n[stopped at " max-matches " matches; narrow the pattern or the path]")))))))))))

;; ---------------------------------------------------------------------------
;; write_test
;; ---------------------------------------------------------------------------

(def max-test-bytes (* 50 1024))

(defn- test-path-problem
  "Why `path` is not a place a reviewer may write, or nil: a NEW file named `*_test.clj`
  below `test/`. The application's own tests are not the reviewer's to change - a test
  changed to pass would hide the very thing it was written to show - and every file below
  `test/` is loaded as a namespace by the runner, so a file that is not a test namespace
  would break the whole suite."
  [dir path]
  (let [base (fs/normalize (fs/absolutize dir))
        p (fs/normalize (fs/absolutize (fs/path dir (str path))))
        under-test? (str/starts-with? (str p) (str (fs/path base "test") "/"))]
    (cond
      (str/blank? path) "path is required"
      (not under-test?) (str path " is not below test/ - a test is the only thing you may write")
      (not (str/ends-with? (str p) "_test.clj")) (str path " does not end in _test.clj")
      (fs/exists? p) (str path " already exists - write a new file; the application's tests and your earlier ones are not changed"))))

(defn- write-test
  "Write a test file below `test/` that did not exist, and say what the linter finds in it."
  [{:keys [dir]} {:keys [path content]}]
  (cond
    (test-path-problem dir path) (tools/err (test-path-problem dir path))
    (str/blank? content) (tools/err "content is required")
    (> (count (.getBytes (str content) "UTF-8")) max-test-bytes) (tools/err (str "a test over " max-test-bytes " bytes is not one test"))
    :else
    (let [p (str (fs/path dir path))]
      (fs/create-dirs (fs/parent p))
      (spit p content)
      (let [warnings (tools/lint-warnings dir path)]
        (tools/ok (str "wrote " path
                       (cond (nil? warnings) " (the linter could not be run)"
                             (empty? warnings) "; the linter finds nothing"
                             :else (str "; the linter finds:\n" (str/join "\n" warnings)))))))))

;; ---------------------------------------------------------------------------
;; run_tests
;; ---------------------------------------------------------------------------

(defn clean-output
  "A test run's output without what only a terminal wants: colour codes, and the coverage
  runner's progress bar, which rewrites one line with carriage returns and is most of the
  output when a test fails. What a person or a model reads is the failures and the totals."
  [text]
  (->> (str/split (str text) #"[\r\n]+")
       (map #(str/replace % #"\u001b\[[0-9;]*[A-Za-z]" ""))
       (map #(str/replace % #"^\s*\d+/\d+\s+\d+%\s+\[[= ]*\]\s+ETA:\s*\S+\s*" ""))
       (remove str/blank?)
       (str/join "\n")))

(def ^:private namespace-name #"[a-zA-Z][\w.\-*!?]*")

(defn- run-tests
  "The application's tests in the sandbox: all of them, or one test namespace."
  [{:keys [sandbox]} {ns-name :namespace}]
  (cond
    (nil? (:run-tests sandbox)) (tools/err "no sandbox to run the tests in")
    (and (not (str/blank? ns-name)) (not (re-matches namespace-name ns-name)))
    (tools/err (str ns-name " is not a namespace name"))
    :else
    (let [{:keys [exit out]} ((:run-tests sandbox) (when-not (str/blank? ns-name) ns-name))
          text (clean-output out)]
      (tools/ok (str (if (str/blank? text) "(no output)" text)
                     "\n[" (if (zero? exit) "exit 0: the tests pass" (str "exit " exit ": a test failed or did not run")) "]")))))

;; ---------------------------------------------------------------------------
;; the registry
;; ---------------------------------------------------------------------------

(def specs
  "Name -> {:description :schema :fn}: what a security reviewer is given."
  {"read_file" (get tools/specs "read_file")

   "search"
   {:description (str "Search the application's source for a regular expression, line by line. "
                      "Returns path:line: text for each match, at most " max-matches ". Use it to find "
                      "where a value is used, where a route is declared, where a query is built. "
                      "Build output and tooling folders are not searched.")
    :schema {:type "object"
             :properties {:pattern {:type "string"
                                    :description "A regular expression, matched against each line."}
                          :path {:type "string"
                                 :description "Optional: a folder or file below the root to search; default the whole tree."}}
             :required ["pattern"]}
    :fn #'search}

   "write_test"
   {:description (str "Write a NEW test file below test/ whose name ends in _test.clj. This is the only "
                      "thing you may write: a concern you raise is a test that fails on this code and "
                      "states the property that should hold, run with run_tests. An existing file, "
                      "the application's tests included, is never changed; write a new file instead. "
                      "The file must be a complete Clojure namespace whose name matches its path.")
    :schema {:type "object"
             :properties {:path {:type "string"
                                 :description "Path below test/, ending in _test.clj, e.g. test/app/review_notes_test.clj."}
                          :content {:type "string"
                                    :description "The complete contents of the new test namespace."}}
             :required ["path" "content"]}
    :fn #'write-test}

   "run_tests"
   {:description (str "Run the application's tests and return their output: every test by default, or "
                      "only the namespace you name (for example the one you wrote with write_test). "
                      "A test fails when the property it states does not hold. A property you believe "
                      "is broken should show here as a failing test of your own, and a test of yours "
                      "that passes is a concern that did not hold.")
    :schema {:type "object"
             :properties {:namespace {:type "string"
                                      :description "Optional: one test namespace, e.g. app.review-notes-test. Default: all tests."}}
             :required []}
    :fn #'run-tests}})
