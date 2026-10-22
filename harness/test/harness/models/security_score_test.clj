(ns harness.models.security-score-test
  "The scorer's classification, on canned outcomes: what a test run on each branch means. Running
  a reviewer's tests in the sandbox is `bb security-score`, never in the gates."
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.test :refer [deftest is testing]]
   [harness.models.security-review-sandbox]
   [harness.models.security-score :as score]))

(deftest a-test-path-is-its-namespace
  (is (= "notes.review-admin-authorization-test" (score/test-ns "test/notes/review_admin_authorization_test.clj")))
  (is (= "a.b-c-test" (score/test-ns "test/a/b_c_test.clj"))))

(deftest how-a-run-ended-is-read-from-whether-it-ran
  (is (= :passes (score/status {:exit 0 :out "Ran 3 tests containing 8 assertions.\n0 failures, 0 errors."})))
  (is (= :no-tests (score/status {:exit 0 :out "Testing notes.review-support-test\nRan 0 tests containing 0 assertions."})))
  (is (= :fails (score/status {:exit 1 :out "FAIL in (a) (x.clj:5)\nexpected: 1\nRan 2 tests containing 3 assertions.\n1 failures, 0 errors."})))
  (is (= :does-not-compile (score/status {:exit 1 :out "Syntax error compiling at (notes/x_test.clj:16:9).\nUnable to resolve symbol: stored-hash"})))
  (is (= :does-not-compile (score/status {:exit 1 :out "Syntax error reading source at (notes/x_test.clj:6:68).\nUnmatched delimiter: ]"})))
  (testing "a test that fails with an exception is a failure, though its stack trace names the compiler"
    (is (= :fails (score/status {:exit 1 :out (str "ERROR in (b) (x.clj:9)\n\tat clojure.lang.Compiler.eval(Compiler.java:7700)\nRan 2 tests containing 5 assertions.\n1 failures, 1 errors.")}))))
  (testing "nor does text in the application's own log lines make a run that ran into one that did not"
    (is (= :fails (score/status {:exit 1 :out (str "2026-10-09 ERROR notes.server - Unable to resolve symbol: foo in this context\nFAIL in (c) (x.clj:9)\nRan 5 tests containing 9 assertions.\n3 failures, 0 errors.")})))))

(deftest the-first-failure-is-quoted-for-a-person-to-read
  (let [out (str "Running task: test\n  3/15    20% [==========     ]  ETA: 00:03 \rFAIL in notes.x-test/a (x.clj:5)\n"
                 "register ../outside\nexpected: (= 303 (:status response))\n  actual: (not (= 303 400))\nmore\n")]
    (is (= "FAIL in notes.x-test/a (x.clj:5) | register ../outside | expected: (= 303 (:status response)) |   actual: (not (= 303 400))"
           (score/why out))))
  (is (nil? (score/why "Ran 1 tests\n0 failures"))))

(deftest a-test-that-fails-on-the-faulted-branch-only-detects-the-fault-whose-revert-makes-it-pass
  (testing "a hit"
    (is (= {:class :hit :hits [:race]}
           (score/classify {:faulted :fails :clean :passes
                            :variants {:race :passes :idor :fails :raw-sql :fails}}))))
  (testing "a test that two reverts each fix detects both"
    (is (= {:class :hit :hits [:idor :raw-sql]}
           (score/classify {:faulted :fails :clean :passes :variants {:race :fails :idor :passes :raw-sql :passes}}))))
  (testing "discriminating, but no single revert is enough"
    (is (= {:class :unattributed :hits []}
           (score/classify {:faulted :fails :clean :passes :variants {:race :fails :idor :fails}}))))
  (testing "no variant ran"
    (is (= {:class :unattributed :hits []} (score/classify {:faulted :fails :clean :passes :variants {}})))))

(deftest what-does-not-discriminate-is-classified-not-judged
  (is (= {:class :fails-on-both} (score/classify {:faulted :fails :clean :fails})) "a real flaw nobody planted, an invention, or a refused setup: the person marks")
  (is (= {:class :did-not-hold} (score/classify {:faulted :passes :clean :passes})))
  (is (= {:class :did-not-hold} (score/classify {:faulted :passes :clean :fails})))
  (is (= {:class :does-not-compile} (score/classify {:faulted :does-not-compile :clean :does-not-compile})))
  (is (= {:class :no-tests} (score/classify {:faulted :no-tests :clean :no-tests}))))

(deftest only-a-discriminating-test-is-worth-a-variant-run
  (is (true? (score/discriminates? {:faulted :fails :clean :passes})))
  (is (false? (score/discriminates? {:faulted :fails :clean :fails})))
  (is (false? (score/discriminates? {:faulted :passes :clean :passes}))))

(deftest the-summary-names-what-was-found-what-was-missed-and-what-each-claim-came-to
  (let [tests {"notes.a-test/race" {:class :hit :hits [:race]}
               "notes.b-test/idor" {:class :hit :hits [:idor]}
               "notes.b-test/sql" {:class :hit :hits [:raw-sql]}
               "notes.c-test/setup" {:class :fails-on-both :why "register ../x"}
               "notes.d-test/idle" {:class :did-not-hold}
               "notes.e-test/broken" {:class :does-not-compile}}
        findings [{:title "race" :kind :reproduced :test "test/notes/a_test.clj"}
                  {:title "idor" :kind :reproduced :test "test/notes/b_test.clj"}
                  {:title "other" :kind :reproduced :test "test/notes/c_test.clj"}
                  {:title "claimed" :kind :reproduced :test "test/notes/d_test.clj"}
                  {:title "broken" :kind :reproduced :test "test/notes/e_test.clj"}
                  {:title "no file" :kind :reproduced :test "test/notes/gone_test.clj"}
                  {:title "maybe" :kind :hypothesis}]
        s (score/summary tests findings [:race :idor :raw-sql :admin-authz :csrf-get :path-escape])]
    (is (= {:idor ["notes.b-test/idor"] :race ["notes.a-test/race"] :raw-sql ["notes.b-test/sql"]} (:found s)))
    (is (= [:admin-authz :csrf-get :path-escape] (:missed s)))
    (is (= [:hit :hit :fails-on-both :not-reproduced :not-reproduced :not-reproduced :hypothesis] (mapv :class (:findings s))))
    (is (= {:hit 2 :fails-on-both 1 :not-reproduced 3 :hypothesis 1 :findings 7} (:counts s)))))

(deftest a-bundle-of-checks-in-one-file-is-scored-by-the-check
  ;; a reviewer that put seven checks in one file: no single revert makes the FILE pass, but each
  ;; check passes when its own fault is reverted - the unit is the deftest
  (let [tests {"notes.sec-test/admin" (score/classify {:faulted :fails :clean :passes :variants {:admin-authz :passes :race :fails}})
               "notes.sec-test/quota" (score/classify {:faulted :fails :clean :passes :variants {:admin-authz :fails :race :passes}})
               "notes.sec-test/idle" (score/classify {:faulted :passes :clean :passes})}
        s (score/summary tests [{:title "admin" :kind :reproduced :test "test/notes/sec_test.clj"}
                                {:title "quota" :kind :reproduced :test "test/notes/sec_test.clj"}]
                         [:admin-authz :race :idor])]
    (is (= {:admin-authz ["notes.sec-test/admin"] :race ["notes.sec-test/quota"]} (:found s)))
    (is (= [:idor] (:missed s)))
    (is (= [:hit :hit] (mapv :class (:findings s))) "both claims name the same file, which has hits")))

(deftest the-tests-in-a-file-and-the-ones-a-run-names-are-read
  (is (= ["a-first" "second-one" "with-meta"]
         (score/deftest-names "(ns x)\n(deftest a-first\n  (is true))\n(deftest second-one (is 1))\n(deftest ^:slow with-meta (is 2))\n(defn- helper [])")))
  (is (= [] (score/deftest-names "(ns x-support)\n(defn helper [])")))
  (is (= #{"a-first" "second-one"}
         (score/failing-vars "FAIL in (a-first) (x.clj:5)\nexpected\nERROR in (second-one) (FutureTask.java:122)\nFAIL in (a-first) (x.clj:9)"))))

(deftest a-namespaces-run-is-turned-into-the-status-of-each-of-its-tests
  (let [vars ["a" "b" "c"]]
    (is (= {"a" :passes "b" :passes "c" :passes} (score/unit-statuses :passes vars "")))
    (is (= {"a" :fails "b" :passes "c" :passes} (score/unit-statuses :fails vars "FAIL in (a) (x.clj:5)")))
    (is (= {"a" :passes "b" :fails "c" :fails} (score/unit-statuses :fails vars "FAIL in (b) (x.clj:5)\nERROR in (c) (y.java:1)")))
    (is (= {"a" :does-not-compile "b" :does-not-compile "c" :does-not-compile} (score/unit-statuses :does-not-compile vars "")))
    (is (= {"a" :fails "b" :fails "c" :fails} (score/unit-statuses :fails vars "Execution error at the top"))
        "a failure no test is named in fails them all: nothing says which held")
    (is (= {} (score/unit-statuses :no-tests [] "Ran 0 tests")))))

;; From the KIT's own security review (Gemini 4 Argon, 2026-10-21)
(deftest a-record-s-test-paths-are-read-only-inside-its-test-folder
  (let [dir (fs/create-temp-dir {:prefix "kit-score-outside"})
        record (fs/path dir "rev.edn")
        tests (fs/path dir "rev-tests")
        fixture (fs/path dir "fixture")
        seen (atom nil)]
    (try
      (fs/create-dirs (fs/path tests "test" "app"))
      (fs/create-dirs (fs/path fixture "notes"))
      (spit (str (fs/path dir "outside.txt")) "OUTSIDE THE TEST FOLDER")
      (spit (str (fs/path tests "test" "app" "ok_test.clj")) "(ns app.ok-test)")
      (spit (str (fs/path fixture "cases.edn")) (pr-str {:faulted "f" :clean "c" :faults [] :variants {}}))
      (spit (str record) (pr-str {:model "m" :findings []
                                  :tests ["../outside.txt" "test/app/ok_test.clj" "test/app/a\"$(id)\"_test.clj"]}))
      (with-redefs [score/outcomes! (fn [_ _ _ files] (reset! seen files) {})]
        (try (score/score! "." (str record) (str fixture)) (catch Exception _ nil)))
      (is (= ["test/app/ok_test.clj"] (keys @seen)) "only a test file's path, inside the folder, is scored")
      (finally (fs/delete-tree dir)))))

;; From the KIT's own security review (the security-review flow): the scorer wrote a review's tests into its
;; clone of the fixture without asking where they landed - a `test` link in the branch led them out
(deftest the-scorer-writes-a-reviews-tests-only-inside-its-clone
  (let [outer (fs/create-temp-dir {:prefix "kit-score-dest"})
        app (fs/path outer "app")
        elsewhere (fs/path outer "elsewhere")
        git! (fn [& args] (apply babashka.process/shell {:dir (str app) :out :string :err :string} "git" args))]
    (try
      (fs/create-dirs app)
      (fs/create-dirs elsewhere)
      (git! "init" "-q" "-b" "faulted")
      (fs/create-sym-link (fs/path app "test") elsewhere)
      (git! "add" "-A")
      (git! "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "a link")
      (with-redefs [harness.models.security-review-sandbox/start! (fn [_] {:run-tests (fn [_] {:exit 0 :out "Ran 0 tests"}) :stop! (fn [])})]
        (#'score/run-branch! "kit" (str app) "faulted" {"test/x/a_test.clj" "(ns x.a-test)"} ["x.a-test"]))
      (is (empty? (fs/list-dir elsewhere)) "nothing written through the link")
      (finally (fs/delete-tree outer)))))
