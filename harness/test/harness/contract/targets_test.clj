(ns harness.contract.targets-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.targets :as targets]))

(defn- tree!
  "A temp dir holding `files`, a map of relative path -> source."
  [files]
  (let [dir (str (fs/create-temp-dir))]
    (doseq [[p src] files]
      (fs/create-dirs (fs/path dir (fs/parent p)))
      (spit (str (fs/path dir p)) src))
    dir))

(def ^:private eight
  "Eight property targets, standing in for t-21-substitute's."
  (mapv #(str "target " % " says something") (range 1 9)))

(defn- cover
  "`coverage` over one test file holding `src`, against `n` targets."
  [src n]
  (targets/coverage (tree! {"test/t.clj" src})
                    {:files/test ["test/t.clj"] :property-targets (subvec eight 0 n)}))

(deftest a-comment-above-a-form-covers-its-target
  (let [r (cover ";; target 1\n(deftest one [] nil)\n" 1)]
    (is (empty? (:uncovered r)))
    (is (= [1] (mapv :target (:covered r))))
    (is (= 1 (:total r)))))

(deftest one-comment-may-name-several-targets
  (testing "commas and prose between the numbers are both fine"
    (doseq [line [";; targets 1, 2" ";; targets 1 and 2" ";; targets 1,2"]]
      (let [r (cover (str line "\n(deftest both [] nil)\n") 2)]
        (is (empty? (:uncovered r)) line)
        (is (= #{1 2} (set (mapv :target (:covered r)))) line)))))

(deftest defspec-closes-a-block-as-deftest-does
  (let [r (cover ";; target 1\n(defspec prop 100 (prop/for-all [x gen/int] true))\n" 1)]
    (is (empty? (:uncovered r)))))

(deftest a-target-no-test-names-is-uncovered
  (let [r (cover ";; target 1\n(deftest one [] nil)\n" 2)]
    (is (= [2] (mapv :target (:uncovered r))))
    (is (= [:uncovered-target] (mapv :violation (:uncovered r))))
    (testing "the detail quotes the target, so the failure says what is missing"
      (is (re-find #"target 2 says something" (:detail (first (:uncovered r))))))))

(deftest s2-1s-actual-shape-fails
  (testing "six targets covered, target 7 skipped, target 8 the deps gate's — the run that
            prompted this namespace, and it is two short rather than one"
    (let [src (apply str (for [n (range 1 7)] (str ";; target " n "\n(deftest t" n " [] nil)\n\n")))
          r (cover src 8)]
      (is (= [7 8] (mapv :target (:uncovered r)))))))

(deftest a-declared-exemption-satisfies-a-target
  (let [r (cover ";; target 1\n(deftest one [] nil)\n\n;; target 2 — not a test: gate 4 checks it\n" 2)]
    (is (empty? (:uncovered r)))
    (is (= [2] (mapv :target (:exempt r))))
    (testing "the reason is kept, because the point is that a person sees the judgement"
      (is (= "gate 4 checks it" (:reason (first (:exempt r))))))))

(deftest an-exemption-with-no-reason-declares-nothing
  (let [r (cover ";; target 1 — not a test:\n" 1)]
    (is (= [1] (mapv :target (:uncovered r))))
    (is (empty? (:exempt r)))))

(deftest an-exemption-never-reads-as-coverage-of-the-form-below-it
  (let [r (cover ";; target 1 — not a test: gate 4 checks it\n(deftest unrelated [] nil)\n" 2)]
    (is (= [1] (mapv :target (:exempt r))))
    (is (= [2] (mapv :target (:uncovered r))))
    (is (empty? (:covered r)))))

(deftest a-blank-line-breaks-the-block
  (testing "attribution is contiguity, not proximity — unambiguous beats clever"
    (let [r (cover ";; target 1\n\n(deftest one [] nil)\n" 1)]
      (is (= [1] (mapv :target (:uncovered r)))))))

(deftest code-between-the-comment-and-the-form-breaks-the-block
  (let [r (cover ";; target 1\n(def fixture 1)\n(deftest one [] nil)\n" 1)]
    (is (= [1] (mapv :target (:uncovered r))))))

(deftest a-plain-comment-line-continues-the-block
  (let [r (cover ";; target 1\n;; and why it matters\n(deftest one [] nil)\n" 1)]
    (is (empty? (:uncovered r)))))

(deftest a-marker-the-spec-has-no-target-for-is-reported
  (testing "target 1 covered, 2 named by nothing, 9 named by a marker the spec has no target for"
    (let [r (cover ";; target 9\n(deftest nine [] nil)\n;; target 1\n(deftest one [] nil)\n" 2)]
      (is (= #{:unknown-target :uncovered-target} (set (mapv :violation (:uncovered r)))))
      (testing "and it says what the spec actually has"
        (is (re-find #"the spec has 2"
                     (:detail (first (filter #(= :unknown-target (:violation %)) (:uncovered r))))))))))

(deftest a-reasons-digits-are-not-target-numbers
  (testing "\"gate 4 checks it\" was read as target 4 until this test"
    (let [r (cover ";; target 1\n(deftest one [] nil)\n\n;; target 2 — not a test: gate 4 checks it\n" 2)]
      (is (= [2] (mapv :target (:exempt r))))
      (is (empty? (:uncovered r))))))

(deftest a-missing-test-file-is-reported-never-skipped
  (let [r (targets/coverage (tree! {"test/other.clj" ""})
                            {:files/test ["test/t.clj"] :property-targets ["only target"]})]
    (is (some #(= :missing-test-file (:violation %)) (:uncovered r)))))

(deftest no-targets-is-nothing-to-check
  (let [r (targets/coverage (tree! {"test/t.clj" "(deftest one [] nil)\n"})
                            {:files/test ["test/t.clj"] :property-targets []})]
    (is (empty? (:uncovered r)))
    (is (zero? (:total r))))
  (testing "not even a missing file: with no targets this check has no opinion, and
            assembly already refuses a role that wrote no :files/target"
    (let [r (targets/coverage (tree! {"test/other.clj" ""})
                              {:files/test ["test/t.clj"] :property-targets []})]
      (is (empty? (:uncovered r))))))

(deftest targets-spread-over-several-test-files-are-pooled
  (let [dir (tree! {"test/a.clj" ";; target 1\n(deftest one [] nil)\n"
                    "test/b.clj" ";; target 2\n(deftest two [] nil)\n"})
        r (targets/coverage dir {:files/test ["test/a.clj" "test/b.clj"]
                                 :property-targets (subvec eight 0 2)})]
    (is (empty? (:uncovered r)))
    (is (= #{1 2} (set (mapv :target (:covered r)))))))

(deftest the-marker-is-case-and-spacing-tolerant
  (doseq [line [";; Target 1" ";;target 1" ";;;  TARGETS  1" "   ;; target 1"]]
    (is (empty? (:uncovered (cover (str line "\n(deftest one [] nil)\n") 1))) line)))

(defn- based!
  "A repo whose HEAD holds `before` - what an earlier packet merged - and whose
  working tree holds `after`, as the gate worktree does before the merge."
  [before after]
  (let [dir (str (fs/create-temp-dir))
        git! #(apply p/shell {:dir dir :out :string :err :string} "git" %&)
        put! (fn [files]
               (doseq [[path src] files]
                 (fs/create-dirs (fs/path dir (fs/parent path)))
                 (spit (str (fs/path dir path)) src)))]
    (git! "init" "-q")
    (git! "config" "user.email" "t@example.com")
    (git! "config" "user.name" "T")
    (put! before)
    (git! "add" "-A")
    (git! "commit" "-qm" "earlier packet")
    (put! after)
    dir))

(def ^:private thirteen
  "An earlier packet's test file: thirteen targets, one test each."
  (apply str (for [n (range 1 14)] (str ";; target " n "\n(deftest old-" n " [] nil)\n\n"))))

(deftest an-earlier-packets-markers-are-not-this-packets
  (testing "numbers past this spec are not unknown targets: the first project met eight of them"
    (let [dir (based! {"test/t.clj" thirteen}
                      {"test/t.clj" (str thirteen ";; targets 1, 2\n(deftest new-one [] nil)\n")})
          r (targets/coverage dir {:files/test ["test/t.clj"] :property-targets (subvec eight 0 2)})]
      (is (empty? (:uncovered r)))
      (is (= [[1 "(deftest new-one [] nil)"] [2 "(deftest new-one [] nil)"]]
             (mapv (juxt :target :form) (:covered r))))
      (is (= 13 (count (:earlier r))))))
  (testing "numbers inside this spec cover nothing of it: a Tester that added no test is caught"
    (let [dir (based! {"test/t.clj" thirteen} {})
          r (targets/coverage dir {:files/test ["test/t.clj"] :property-targets (subvec eight 0 2)})]
      (is (= [1 2] (mapv :target (:uncovered r))))
      (is (empty? (:covered r)))))
  (testing "an earlier declaration is the earlier packet's too"
    (let [dir (based! {"test/t.clj" ";; target 1 — not a test: gate 4 checks it\n"} {})
          r (targets/coverage dir {:files/test ["test/t.clj"] :property-targets (subvec eight 0 1)})]
      (is (= [1] (mapv :target (:uncovered r))))
      (is (empty? (:exempt r))))))

(deftest an-old-test-marked-anew-counts-for-this-packet
  (let [dir (based! {"test/t.clj" ";; target 1\n(deftest old [] nil)\n"}
                    {"test/t.clj" ";; target 1\n;; target 2\n(deftest old [] nil)\n"})
        r (targets/coverage dir {:files/test ["test/t.clj"] :property-targets (subvec eight 0 2)})]
    (is (= [2] (mapv :target (:covered r))))
    (is (= [1] (mapv :target (:uncovered r)))))
  (testing "with the number the earlier packet used: the markers are counted, not matched"
    (let [dir (based! {"test/t.clj" ";; target 2\n(deftest foo [] nil)\n"}
                      {"test/t.clj" ";; target 2\n;; target 2\n(deftest foo [] nil)\n"})
          r (targets/coverage dir {:files/test ["test/t.clj"] :property-targets (subvec eight 0 2)})]
      (is (= [2] (mapv :target (:covered r))))
      (is (= [2] (mapv :target (:earlier r))))
      (is (= [1] (mapv :target (:uncovered r)))))))

(deftest a-target-named-only-by-an-earlier-marker-says-so
  ;; "named by no test" sent the Architect and the triage to the comment's formatting first
  (testing "over a test"
    (let [dir (based! {"test/t.clj" thirteen} {})
          r (targets/coverage dir {:files/test ["test/t.clj"] :property-targets (subvec eight 0 1)})]
      (is (re-find #"property target 1 of 1 is named only by a marker the base file already had, over \(deftest old-1 \[\] nil\) \(an earlier packet's\): mark it over a test this packet adds, or mark that test anew"
                   (:detail (first (:uncovered r)))))))
  (testing "as a declaration"
    (let [dir (based! {"test/t.clj" ";; target 1 — not a test: gate 4 checks it\n"} {})
          r (targets/coverage dir {:files/test ["test/t.clj"] :property-targets (subvec eight 0 1)})]
      (is (re-find #"declaring it no test's job \(gate 4 checks it\)" (:detail (first (:uncovered r)))))))
  (testing "no marker at all: as before"
    (let [r (cover "(deftest one [] nil)\n" 1)]
      (is (re-find #"is named by no test and declared by no" (:detail (first (:uncovered r))))))))

(deftest a-wrong-number-this-tester-wrote-still-fails
  (let [dir (based! {"test/t.clj" thirteen}
                    {"test/t.clj" (str thirteen ";; target 9\n(deftest new-nine [] nil)\n"
                                       ";; target 1\n(deftest new-one [] nil)\n")})
        r (targets/coverage dir {:files/test ["test/t.clj"] :property-targets (subvec eight 0 1)})]
    (is (= [[9 :unknown-target]] (mapv (juxt :target :violation) (:uncovered r))))))

(deftest a-file-this-run-created-reads-as-before
  (let [dir (based! {"src/seed.clj" "(ns seed)\n"}
                    {"test/t.clj" ";; target 1\n(deftest one [] nil)\n"})
        r (targets/coverage dir {:files/test ["test/t.clj"] :property-targets (subvec eight 0 1)})]
    (is (empty? (:uncovered r)))
    (is (empty? (:earlier r)))))
