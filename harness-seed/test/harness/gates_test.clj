(ns harness.gates-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.gates :as gates]
   [harness.shapes :as shapes]))

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
        (is (= #{:gate :status :exit :out} (set (keys (first report)))))
        (is (= #{:gate :status :exit :out} (set (keys (second report)))))))))

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
