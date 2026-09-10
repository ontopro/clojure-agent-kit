(ns harness.shapes-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [harness.shapes :as shapes]))

(deftest example-packet-is-valid
  (testing "the packet the method doc quotes actually validates"
    (is (shapes/valid-packet? shapes/example-packet))
    (is (nil? (shapes/explain-packet shapes/example-packet)))))

(deftest role-deltas
  (let [base shapes/example-packet]
    (testing "a coder packet must name a target file"
      (is (not (shapes/valid-packet? (dissoc base :files/target)))))
    (testing "a tester packet may carry property targets"
      (is (shapes/valid-packet?
           (assoc base :task/role :tester
                  :files/target ["test/app/service_test.clj"]
                  :property-targets ["diff(v,v) is empty"]))))
    (testing "a reviewer packet has no target and needs no REPL port"
      (is (shapes/valid-packet?
           (-> base
               (dissoc :files/target :repl/port)
               (assoc :task/role :reviewer
                      :review/diff "diff --git a/src/app/service.clj ..."
                      :review/gate-report {:fmt :pass})))))
    (testing "an unknown role does not dispatch"
      (is (not (shapes/valid-packet? (assoc base :task/role :devops)))))))

(deftest agent-result-is-closed
  (let [ok {:status :done :files ["src/app/service.clj"] :stdout nil :cost nil}]
    (is (shapes/valid-result? ok))
    (testing "status is an enum, not a free string"
      (is (not (shapes/valid-result? (assoc ok :status "done"))))
      (is (not (shapes/valid-result? (assoc ok :status :ok)))))
    (testing "each of the five keys that accreted upstream is REJECTED at top level"
      ;; the whole point of the closed schema: these were invisible for two
      ;; months because malli maps are open by default
      (doseq [[k v] {:session-id "abc-123" :verdict "approve" :capped? true
                     :provider "some-host" :tokens 4096}]
        (is (not (shapes/valid-result? (assoc ok k v)))
            (str k " must not be settable at the top level"))
        (is (= {k ["disallowed key"]} (shapes/explain-result (assoc ok k v))))))
    (testing "and each is accepted under :runner/meta"
      (is (shapes/valid-result?
           (assoc ok :runner/meta {:session-id "abc-123" :verdict "approve"
                                   :capped? true :provider "some-host"
                                   :tokens 4096}))))))

(deftest gate-result-shape
  (testing "a pass entry and a skipped entry have the same keys"
    (is (shapes/valid-gate-result?
         {:gates/passed? false
          :gates/failed :lint
          :gates/report [{:gate :fmt :status :pass :exit 0 :out ""}
                         {:gate :lint :status :fail :exit 1 :out "boom"}
                         {:gate :test :status :skipped :exit nil :out ""}]})))
  (testing "a skipped entry that omits :exit/:out is rejected"
    (is (not (shapes/valid-gate-result?
              {:gates/passed? false
               :gates/failed :lint
               :gates/report [{:gate :test :status :skipped}]})))))

(deftest run-record
  (is (shapes/valid-run? {:run/id "r-1" :task/id "t-07" :run/attempts 2
                          :run/status :awaiting-merge :run/cost 0.75
                          :run/started-at (java.util.Date.)}))
  (testing "cost is nil when the client reports none"
    (is (shapes/valid-run? {:run/id "r-1" :task/id "t-07" :run/attempts 0
                            :run/status :dispatched :run/cost nil
                            :run/started-at (java.util.Date.)}))))
