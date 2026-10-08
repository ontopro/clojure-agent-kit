(ns harness.models.security-score-test
  "The scorer's classification, on canned outcomes: what a test run on each branch means. Running
  a reviewer's tests in the sandbox is `bb security-score`, never in the gates."
  (:require
   [clojure.test :refer [deftest is testing]]
   [harness.models.security-score :as score]))

(deftest a-test-path-is-its-namespace
  (is (= "notes.review-admin-authorization-test" (score/test-ns "test/notes/review_admin_authorization_test.clj")))
  (is (= "a.b-c-test" (score/test-ns "test/a/b_c_test.clj"))))

(deftest how-a-run-ended-is-read-from-its-exit-and-output
  (is (= :passes (score/status {:exit 0 :out "Ran 3 tests containing 8 assertions.\n0 failures, 0 errors."})))
  (is (= :no-tests (score/status {:exit 0 :out "Testing notes.review-support-test\nRan 0 tests containing 0 assertions."})))
  (is (= :fails (score/status {:exit 1 :out "FAIL in notes.x-test/a (x.clj:5)\nexpected: 1"})))
  (is (= :does-not-compile (score/status {:exit 1 :out "Syntax error compiling at (notes/x_test.clj:16:9).\nUnable to resolve symbol: stored-hash"}))))

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
  (let [tests {"notes.a-test" {:class :hit :hits [:race]}
               "notes.b-test" {:class :hit :hits [:idor :raw-sql]}
               "notes.c-test" {:class :fails-on-both :why "register ../x"}
               "notes.d-test" {:class :did-not-hold}
               "notes.e-test" {:class :does-not-compile}}
        findings [{:title "race" :kind :reproduced :test "test/notes/a_test.clj"}
                  {:title "idor" :kind :reproduced :test "test/notes/b_test.clj"}
                  {:title "other" :kind :reproduced :test "test/notes/c_test.clj"}
                  {:title "claimed" :kind :reproduced :test "test/notes/d_test.clj"}
                  {:title "broken" :kind :reproduced :test "test/notes/e_test.clj"}
                  {:title "no file" :kind :reproduced :test "test/notes/gone_test.clj"}
                  {:title "maybe" :kind :hypothesis}]
        s (score/summary tests findings [:race :idor :raw-sql :admin-authz :csrf-get :path-escape])]
    (is (= {:idor ["notes.b-test"] :race ["notes.a-test"] :raw-sql ["notes.b-test"]} (:found s)))
    (is (= [:admin-authz :csrf-get :path-escape] (:missed s)))
    (is (= [:hit :hit :fails-on-both :not-reproduced :not-reproduced :not-reproduced :hypothesis] (mapv :class (:findings s))))
    (is (= {:hit 2 :fails-on-both 1 :not-reproduced 3 :hypothesis 1 :findings 7} (:counts s)))))
