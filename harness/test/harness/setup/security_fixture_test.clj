(ns harness.setup.security-fixture-test
  "The security fixture's pure parts, and its committed files held to each other: every fault
  lands exactly once on the overlay, and names a reference test that exists. Building the
  fixture and running its key - a generated application, its tests run twice - is
  `bb security-fixture <dir>`, never in the gates."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.security-fixture :as fixture]))

(def kit (str (fs/normalize (fs/absolutize ".."))))

(deftest an-edit-lands-exactly-once-or-is-refused
  (let [fault (fn [find] {:id :f :edits [{:file "a.clj" :find find :replace "Y"}]})]
    (is (= {"a.clj" "xYz"} (fixture/apply-edits {"a.clj" "xyz"} [(fault "y")])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"occurs 2 times" (fixture/apply-edits {"a.clj" "yy"} [(fault "y")])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"occurs 0 times" (fixture/apply-edits {"a.clj" "x"} [(fault "y")])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"occurs 0 times in b.clj"
                          (fixture/apply-edits {"a.clj" "y"} [{:id :f :edits [{:file "b.clj" :find "y" :replace "Y"}]}]))
        "a file the overlay does not have")))

(deftest the-test-runs-failures-are-read-by-name
  (is (= #{"the-quota-holds" "a-note-is-read"}
         (fixture/failing-tests (str "  8/15 53% [===]  FAIL in notes.reference-test/the-quota-holds (reference_test.clj:97)\n"
                                     "expected: ...\nERROR in notes.reference-test/a-note-is-read (x.clj:1)\n55 assertions, 1 failure")))))

(deftest the-key-holds-only-when-clean-passes-and-each-fault-is-reproduced
  (let [faults [{:id :a :reference "ta"} {:id :b :reference "tb"}]]
    (is (empty? (fixture/problems #{} #{"ta" "tb"} faults)))
    (is (= ["the clean feature fails ta"] (fixture/problems #{"ta"} #{"ta" "tb"} faults)))
    (is (= ["the faulted feature passes tb, so its fault is not reproduced"] (fixture/problems #{} #{"ta"} faults)))
    (is (= ["the faulted feature fails tc, which no fault names"] (fixture/problems #{} #{"ta" "tb" "tc"} faults)))))

(deftest the-committed-faults-land-on-the-committed-overlay
  (let [dir (fixture/fixture-dir kit)
        faults (fixture/load-faults dir)
        root (fs/path dir "overlay")
        overlay (into {} (for [f (fs/glob root "**") :when (fs/regular-file? f)]
                           [(str (fs/relativize root f)) (slurp (str f))]))
        ref-text (slurp (str (fs/path dir "reference" "reference_test.clj")))]
    (is (= [:admin-authz :csrf-get :idor :path-escape :race :raw-sql] (mapv :id faults)))
    (is (= #{:architectural :diff-local} (set (map :kind faults))))
    (testing "each alone, and all six together, land exactly once"
      (doseq [f faults] (is (map? (fixture/apply-edits overlay [f])) (name (:id f))))
      (is (map? (fixture/apply-edits overlay faults))))
    (testing "each names a reference test that exists"
      (doseq [{:keys [id reference]} faults]
        (is (str/includes? ref-text (str "(deftest " reference "\n")) (name id))))
    (testing "the application carries nothing of the key"
      (doseq [[path text] overlay]
        (is (not (re-find #"(?i)\b(fault|planted|vulnerab|exploit)" text)) path)))))
