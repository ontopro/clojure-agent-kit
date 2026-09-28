(ns harness.gates.run-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.shapes :as shapes]
   [harness.gates.run :as gates]))

(deftest short-circuit-ordering
  (let [dir (str (fs/create-temp-dir))]
    (testing "all pass"
      (let [{:gates/keys [passed? failed report] :as res}
            (gates/run-gates! dir [[:a "true"] [:b "true"]])]
        (is passed?)
        (is (nil? failed))
        (is (= [:pass :pass] (map :status report)))
        (is (shapes/valid-gate-result? res))))
    (testing "first failure stops the run; later gates are skipped, not run"
      (let [{:gates/keys [passed? failed report] :as res}
            (gates/run-gates! dir [[:fmt "true"] [:lint "false"]
                                   [:test "true"] [:deps "true"]])]
        (is (not passed?))
        (is (= :lint failed))
        (is (= [[:fmt :pass] [:lint :fail] [:test :skipped] [:deps :skipped]]
               (map (juxt :gate :status) report)))
        (is (shapes/valid-gate-result? res))))
    (testing "failure output is captured for triage"
      (let [{:gates/keys [report]}
            (gates/run-gates! dir [[:x "sh -c \"echo broken-thing; exit 1\""]])]
        (is (str/includes? (-> report first :out) "broken-thing"))))
    (testing "every entry has the same keys, skipped ones included"
      (let [{:gates/keys [report]}
            (gates/run-gates! dir [[:a "false"] [:b "true"]])]
        (is (= #{:gate :status :exit :out :ms} (set (keys (first report)))))
        (is (= #{:gate :status :exit :out :ms} (set (keys (second report)))))))))

(deftest carriage-return-progress-is-collapsed-at-capture
  ;; A test runner rewrites one line hundreds of times with \r; captured, every
  ;; rewrite survives and the failure report at the end never reaches the
  ;; characters triage reads. Three red gates of one project were routed to a
  ;; person for that alone.
  (is (= "27/27 100%\nFAIL in (t)" (gates/tidy-output "0/27 0% [=>  ] ETA\r3/27 11% [==> ] ETA\r27/27 100%\nFAIL in (t)")))
  (is (= "plain\nlines" (gates/tidy-output "plain\nlines")) "no \\r, no change")
  (is (= "a\nb" (gates/tidy-output "a\r\nb")) "a CRLF line ending leaves the line, not an empty one")
  (is (= "" (gates/tidy-output "")))
  (testing "and the gate result carries the collapsed output, so every reader sees the report"
    (let [dir (str (fs/create-temp-dir))
          {:gates/keys [report]}
          (gates/run-gates! dir [[:test "sh -c \"printf 'progress 1\\rprogress 2\\nFAIL in (x)\\n'; exit 1\""]])]
      (is (= "progress 2\nFAIL in (x)\n" (-> report first :out))))))

(deftest missing-binary-is-a-failed-gate-not-a-crash
  ;; :continue true suppresses a non-zero exit, not a missing program.
  ;; A misconfigured gate must fail like a gate; losing an in-flight,
  ;; already-paid-for attempt to an uncaught IOException is the expensive
  ;; version of this bug.
  (let [dir (str (fs/create-temp-dir))
        {:gates/keys [passed? failed report] :as res}
        (gates/run-gates! dir [[:nope "definitely-not-a-real-binary-xyz"]])]
    (is (not passed?))
    (is (= :nope failed))
    (is (= 127 (-> report first :exit)) "the shell's own command-not-found code")
    (is (str/includes? (-> report first :out) "definitely-not-a-real-binary-xyz")
        "the message names the command, so the fix is obvious")
    (is (shapes/valid-gate-result? res))))

(deftest synthesized-failure
  (testing "a non-gate failure takes the gate-result shape, with a nil exit"
    (let [res (gates/failure :repl "nREPL on port 7807 is not answering")]
      (is (shapes/valid-gate-result? res))
      (is (= :repl (:gates/failed res)))
      (is (nil? (-> res :gates/report first :exit))))))

(deftest gates-are-timed
  ;; §10 measured the Tester at >80% of wall time. Nothing could have told you
  ;; that without per-step timing, so a gate that runs records how long it took
  ;; and one that is skipped records nil — the same required-but-nullable shape
  ;; :exit already uses.
  (let [dir (str (fs/create-temp-dir))
        res (gates/run-gates! dir [[:ok "true"] [:bad "false"] [:never "true"]])
        by-gate (into {} (map (juxt :gate identity)) (:gates/report res))]
    (is (nat-int? (:ms (:ok by-gate))) "a passing gate is timed")
    (is (nat-int? (:ms (:bad by-gate))) "a FAILING gate is timed too — slow failures matter")
    (is (nil? (:ms (:never by-gate))) "a skipped gate has no duration")
    (testing "a synthesized failure has no duration either — nothing ran"
      (is (nil? (-> (gates/failure :repl "dead") :gates/report first :ms))))))
