(ns harness.models.security-review-tools-test
  "The security reviewer's tools, tried on a small temporary tree. The tools that start a
  process take a sandbox, which these tests replace with a recording stand-in."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.models.security-review-tools :as rt]
   [harness.models.tools :as tools]))

(defn- tree!
  "A temporary folder holding `files` (path -> text); its path."
  [files]
  (let [dir (str (fs/create-temp-dir {:prefix "kit-security-review-tools"}))]
    (doseq [[rel text] files]
      (fs/create-dirs (fs/parent (fs/path dir rel)))
      (spit (str (fs/path dir rel)) text))
    dir))

(defn- snapshot
  "Every file under `dir` as path -> text."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**") :when (fs/regular-file? f)]
          [(str (fs/relativize dir f)) (slurp (str f))])))

(defn- call
  "Run tool `nm` with `args` in `dir`, or in the context map given in its place."
  [dir-or-ctx nm args]
  (let [ctx (if (map? dir-or-ctx) dir-or-ctx {:dir dir-or-ctx})]
    (tools/invoke rt/specs ctx {:id "c1" :name nm :args args})))

(deftest the-reviewer-has-its-own-registry-and-the-coders-is-untouched
  (is (= #{"read_file" "search" "write_test" "run_tests" "request"} (set (keys rt/specs))) "grows as the tools are added")
  (is (= #{"read_file" "write_file" "edit_file" "nrepl_eval" "note"} (set (keys tools/specs)))
      "no review tool leaks into the coder's registry")
  (is (nil? (get rt/specs "nrepl_eval")) "a reviewer has no REPL"))

(deftest a-registry-is-declared-to-a-model-in-either-shape
  (let [names (set (keys rt/specs))]
    (is (= (sort names) (map #(get-in % [:function :name]) (tools/declarations :openai names rt/specs))))
    (is (= (sort names) (map :name (tools/declarations :anthropic names rt/specs))))))

(deftest search-finds-lines-and-names-the-file-and-number
  (let [dir (tree! {"src/app/a.clj" "(ns app.a)\n(defn find-note [id] id)\n"
                    "src/app/b.clj" "(ns app.b)\n(find-note 1)\n"
                    "README.md" "nothing here\n"})
        r (call dir "search" {:pattern "find-note"})]
    (is (false? (:error? r)))
    (is (= ["src/app/a.clj:2: (defn find-note [id] id)" "src/app/b.clj:2: (find-note 1)"]
           (str/split-lines (:content r))))
    (testing "below a path"
      (is (= ["src/app/b.clj:2: (find-note 1)"]
             (str/split-lines (:content (call dir "search" {:pattern "find-note" :path "src/app/b.clj"}))))))
    (testing "a pattern is a regular expression"
      (is (= 2 (count (str/split-lines (:content (call dir "search" {:pattern "\\(find-note|\\[id\\]"})))))))
    (testing "nothing matching says so, and is not an error"
      (let [r (call dir "search" {:pattern "absent-thing"})]
        (is (false? (:error? r)))
        (is (str/includes? (:content r) "no line matches"))))))

(deftest search-skips-build-output-and-binary-files
  (let [dir (tree! {"src/a.clj" "needle\n"
                    "target/classes/a.clj" "needle\n"
                    ".git/config" "needle\n"
                    "node_modules/x/index.js" "needle\n"})]
    (spit (str (fs/path dir "image.png")) (str "needle" (char 0) "needle"))
    (is (= ["src/a.clj:1: needle"] (str/split-lines (:content (call dir "search" {:pattern "needle"})))))))

(deftest search-is-bounded
  (let [dir (tree! {"big.txt" (str/join "\n" (repeat 250 "match"))
                    "long.txt" (str "match " (apply str (repeat 1000 "x")))})
        r (call dir "search" {:pattern "match"})
        lines (str/split-lines (:content r))]
    (is (= (inc rt/max-matches) (count lines)) "the matches, then the line saying it stopped")
    (is (str/includes? (last lines) (str "stopped at " rt/max-matches " matches")))
    (is (every? #(<= (count %) (+ rt/max-line 80)) lines) "a long line is cut")))

(deftest search-refuses-what-is-not-a-search
  (let [dir (tree! {"a.clj" "x\n"})]
    (is (:error? (call dir "search" {})))
    (is (str/includes? (:content (call dir "search" {:pattern "("})) "not a valid regular expression"))
    (is (str/includes? (:content (call dir "search" {:pattern "x" :path "../.."})) "outside the workspace"))
    (is (str/includes? (:content (call dir "search" {:pattern "x" :path "nope"})) "does not exist"))))

;; ---------------------------------------------------------------------------
;; write_test
;; ---------------------------------------------------------------------------

(def a-test "(ns app.review-notes-test\n  (:require [clojure.test :refer [deftest is]]))\n\n(deftest a-property\n  (is (= 1 1)))\n")

(deftest write-test-writes-a-new-test-file-and-only-that
  (let [dir (tree! {"test/app/existing_test.clj" "(ns app.existing-test)\n" "src/app/core.clj" "(ns app.core)\n"})
        r (call dir "write_test" {:path "test/app/review_notes_test.clj" :content a-test})]
    (is (false? (:error? r)))
    (is (str/starts-with? (:content r) "wrote test/app/review_notes_test.clj"))
    (is (= a-test (slurp (str (fs/path dir "test" "app" "review_notes_test.clj")))))
    (testing "a folder that does not exist yet is made"
      (is (false? (:error? (call dir "write_test" {:path "test/app/review/deep_test.clj" :content a-test})))))))

(deftest write-test-refuses-everything-else
  (let [dir (tree! {"test/app/existing_test.clj" "(ns app.existing-test)\n" "src/app/core.clj" "(ns app.core)\n"})
        refused (fn [args] (call dir "write_test" args))]
    (testing "source, config, and anything outside test/: refused, and the tree is as it was"
      (let [before (snapshot dir)]
        (doseq [p ["src/app/core.clj" "deps.edn" "test_notes_test.clj" "../outside_test.clj" "test/../src/app/x_test.clj" "/etc/x_test.clj"]]
          (is (:error? (refused {:path p :content a-test})) p))
        (is (= before (snapshot dir)))
        (is (not (fs/exists? (fs/path dir ".." "outside_test.clj"))))))
    (testing "a file that is not a test namespace"
      (is (str/includes? (:content (refused {:path "test/app/helper.clj" :content a-test})) "does not end in _test.clj")))
    (testing "a file that exists: the application's test is not changed"
      (let [r (refused {:path "test/app/existing_test.clj" :content a-test})]
        (is (str/includes? (:content r) "already exists"))
        (is (= "(ns app.existing-test)\n" (slurp (str (fs/path dir "test" "app" "existing_test.clj")))))))
    (testing "a second write to the same path is refused too"
      (call dir "write_test" {:path "test/app/once_test.clj" :content a-test})
      (is (str/includes? (:content (refused {:path "test/app/once_test.clj" :content "changed"})) "already exists")))
    (testing "no content, and too much"
      (is (:error? (refused {:path "test/app/e_test.clj" :content ""})))
      (is (str/includes? (:content (refused {:path "test/app/big_test.clj" :content (apply str (repeat (inc rt/max-test-bytes) "x"))}))
                         "is not one test")))))

;; ---------------------------------------------------------------------------
;; run_tests
;; ---------------------------------------------------------------------------

(def bar "  3/15    20% [==========                                        ]  ETA: 00:03 ")
(def raw-run
  (str "Running task: test\n" bar "\r" bar "\r  8/15    53% [==========================                        ]  ETA: 00:01 "
       "\u001b[1;31mFAIL\u001b[0m in app.review-notes-test/a-property (review_notes_test.clj:6)\n"
       "expected: (= 1 2)\n  actual: (not (= 1 2))\n55 assertions, 1 failure, 0 errors.\n"))

(deftest a-test-runs-output-is-read-without-the-terminals-decoration
  (let [clean (rt/clean-output raw-run)]
    (is (not (str/includes? clean "ETA")))
    (is (not (str/includes? clean "\u001b")))
    (is (str/includes? clean "FAIL in app.review-notes-test/a-property"))
    (is (str/includes? clean "55 assertions, 1 failure, 0 errors."))
    (is (< (count clean) (/ (count raw-run) 2)))))

(defn- sandbox [exit out seen]
  {:run-tests (fn [ns-name] (swap! seen conj ns-name) {:exit exit :out out})})

(deftest run-tests-runs-all-or-one-namespace-and-says-how-it-ended
  (let [seen (atom [])
        failing {:dir "." :sandbox (sandbox 1 raw-run seen)}
        passing {:dir "." :sandbox (sandbox 0 "Ran 3 tests\n8 assertions, 0 failures, 0 errors.\n" seen)}]
    (let [r (call failing "run_tests" {})]
      (is (false? (:error? r)) "a failing test is a result, not a tool error")
      (is (str/includes? (:content r) "FAIL in app.review-notes-test/a-property"))
      (is (str/ends-with? (:content r) "[exit 1: a test failed or did not run]")))
    (let [r (call passing "run_tests" {:namespace "app.review-notes-test"})]
      (is (str/ends-with? (:content r) "[exit 0: the tests pass]")))
    (is (= [nil "app.review-notes-test"] @seen) "all by default, or the namespace named")))

(deftest run-tests-refuses-what-is-not-a-namespace-name-and-a-missing-sandbox
  (let [seen (atom [])
        ctx {:dir "." :sandbox (sandbox 0 "ok" seen)}]
    (doseq [bad ["x) (System/exit 1" "a b" "../x" "(slurp \"/etc/passwd\")"]]
      (is (str/includes? (:content (call ctx "run_tests" {:namespace bad})) "is not a namespace name") bad))
    (is (empty? @seen) "nothing was run")
    (is (str/includes? (:content (call {:dir "."} "run_tests" {})) "no sandbox"))))

;; ---------------------------------------------------------------------------
;; request
;; ---------------------------------------------------------------------------

(defn- app-sandbox
  "A stand-in application: records what it was sent, answers with `answer`."
  [seen answer]
  {:request (fn [req] (swap! seen conj req) answer)})

(def a-page {:status 200 :headers [["content-type" "text/html"] ["set-cookie" "ring-session=abc; HttpOnly"]] :body "<h1>hi</h1>"})

(deftest request-sends-one-request-and-shows-the-answer
  (let [seen (atom [])
        r (call {:dir "." :sandbox (app-sandbox seen a-page)} "request"
                {:method "post" :path "/notes?q=1" :headers {"Cookie" "ring-session=abc"} :body "title=x"})]
    (is (false? (:error? r)))
    (is (= [{:method "POST" :path "/notes?q=1" :headers {:Cookie "ring-session=abc"} :body "title=x"}]
           (mapv #(update % :headers update-keys keyword) @seen))
        "the method upper-cased, the rest as written")
    (is (= "HTTP 200\ncontent-type: text/html\nset-cookie: ring-session=abc; HttpOnly\n\n<h1>hi</h1>" (:content r)))
    (testing "a path is sent as written, a step out of a folder included"
      (call {:dir "." :sandbox (app-sandbox seen a-page)} "request" {:method "GET" :path "/export/download?name=../x.txt"})
      (is (= "/export/download?name=../x.txt" (:path (last @seen)))))))

(deftest request-refuses-what-is-not-one-well-formed-request
  (let [seen (atom [])
        ctx {:dir "." :sandbox (app-sandbox seen a-page)}
        refused (fn [args] (:content (call ctx "request" args)))]
    (is (str/includes? (refused {:method "TRACE" :path "/"}) "method must be one of"))
    (doseq [p ["notes" "/a b" "/a\r\nGET /admin HTTP/1.1" "/a\u0000" "http://elsewhere/"]]
      (is (str/includes? (refused {:method "GET" :path p}) "path must start with /") p))
    (is (str/includes? (refused {:method "GET" :path "/" :headers {"X\r\nY" "1"}}) "header name"))
    (is (str/includes? (refused {:method "GET" :path "/" :headers {"X-A" "1\r\nHost: evil"}}) "one line"))
    (is (str/includes? (refused {:method "GET" :path "/" :headers {"Host" "evil"}}) "are set for you"))
    (is (str/includes? (refused {:method "POST" :path "/" :body (apply str (repeat (inc rt/max-request-body) "x"))}) "a body over"))
    (is (empty? @seen) "nothing was sent")))

(deftest request-bounds-the-answer-and-the-reading
  (let [seen (atom [])
        big {:status 200 :headers [] :body (apply str (repeat (* 2 rt/max-response-body) "x"))}
        r (call {:dir "." :sandbox (app-sandbox seen big)} "request" {:method "GET" :path "/"})]
    (is (str/includes? (:content r) (str "[body cut at " rt/max-response-body " of ")))
    (is (< (count (:content r)) (+ rt/max-response-body 200))))
  (testing "the budget of a reading"
    (let [seen (atom [])
          ctx {:dir "." :sandbox (app-sandbox seen a-page) :request-budget (atom 2)}
          go #(call ctx "request" {:method "GET" :path "/"})]
      (is (false? (:error? (go))))
      (is (false? (:error? (go))))
      (is (str/includes? (:content (go)) "budget for this reading is spent"))
      (is (= 2 (count @seen)))))
  (testing "no application, and one that does not answer"
    (is (str/includes? (:content (call {:dir "."} "request" {:method "GET" :path "/"})) "no application is running"))
    (is (str/includes? (:content (call {:dir "." :sandbox (app-sandbox (atom []) {:error "connection refused"})} "request" {:method "GET" :path "/"}))
                       "no answer: connection refused"))))

(deftest a-review-may-rewrite-its-own-test-and-nothing-else
  ;; a wrong test had to be fixed in place: without this a reviewer wrote authz, authz2 … authz5
  (let [dir (tree! {"test/app/existing_test.clj" "(ns app.existing-test)\n"})
        ctx {:dir dir :written (atom #{})}]
    (is (false? (:error? (call ctx "write_test" {:path "test/app/mine_test.clj" :content a-test}))))
    (let [r (call ctx "write_test" {:path "test/app/mine_test.clj" :content (str/replace a-test "(= 1 1)" "(= 2 2)")})]
      (is (false? (:error? r)) "its own file, again")
      (is (str/includes? (slurp (str (fs/path dir "test" "app" "mine_test.clj"))) "(= 2 2)")))
    (testing "the application's test is still not its to change, nor a path spelled another way"
      (is (str/includes? (:content (call ctx "write_test" {:path "test/app/existing_test.clj" :content a-test})) "not one you wrote"))
      (is (str/includes? (:content (call ctx "write_test" {:path "test/app/../app/existing_test.clj" :content a-test})) "not one you wrote"))
      (is (= "(ns app.existing-test)\n" (slurp (str (fs/path dir "test" "app" "existing_test.clj"))))))
    (testing "a file only counts once the review has written it"
      (spit (str (fs/path dir "test" "app" "planted_test.clj")) "(ns app.planted-test)\n")
      (is (:error? (call ctx "write_test" {:path "test/app/planted_test.clj" :content a-test}))))
    (testing "the same path spelled another way is the same file"
      (is (false? (:error? (call ctx "write_test" {:path "test/app/../app/mine_test.clj" :content a-test})))))
    (testing "without the record of what was written, nothing is rewritable"
      (call {:dir dir} "write_test" {:path "test/app/solo_test.clj" :content a-test})
      (is (:error? (call {:dir dir} "write_test" {:path "test/app/solo_test.clj" :content a-test}))))))
