(ns harness.contract.shapes-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.shapes :as shapes]))

(deftest the-loop-s-keys-are-described-once-and-the-documents-hold-to-them
  (testing "every entry says its key, what it is for and who reads it; a file key says its type"
    (doseq [{:keys [key doc reader type derived]} shapes/loop-keys]
      (is (keyword? key))
      (is (and (string? doc) (seq doc)) (str key))
      (is (symbol? reader) (str key))
      (when-not derived (is (keyword? type) (str key)))))
  (testing "the defaults are the literal ones only, and a required key has none"
    (let [d (shapes/loop-defaults)]
      (is (= {:repo/allow-dirty? false :project/subdir nil :nrepl/cmd ["clojure" "-Srepro" "-M:nrepl"]
              :architecture nil :spec-review/run? true :spec-review/max 2 :plan-check/run? true :notes/pause? true}
             d))
      (is (not (contains? d :run/id)))
      (is (not (contains? d :gates)) "computed in resolve-config")))
  (testing "an unknown key is found by name, a derived one is known"
    (is (= [:spec-review?] (shapes/unknown-loop-keys {:run/id "x" :spec-review? true :records/dir "r"}))))
  (testing "the plan template's §13 names only keys the table describes"
    (let [text (slurp "../plan-template/03-method-and-tooling.md")
          named (map #(keyword (subs (second %) 1)) (re-seq #"`(:[a-z/?-]+)` in `loop.edn`" text))]
      (is (seq named) "§13 names at least one loop.edn key")
      (doseq [k named] (is (contains? shapes/loop-file-keys k) (str k)))))
  (testing "the header lines name every file key"
    (let [lines (shapes/loop-key-lines)]
      (is (= (count shapes/loop-file-keys) (count lines)))
      (doseq [k shapes/loop-file-keys] (is (some #(str/starts-with? % (str k " - ")) lines) (str k))))))

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
    (testing "and so may a coder packet — they are contract, declared on every packet"
      (is (shapes/valid-packet? (assoc base :property-targets ["diff(v,v) is empty"])))
      (is (not (shapes/valid-packet? (assoc base :property-targets "diff(v,v) is empty")))
          "declared, so a malformed one fails rather than riding along on an open map"))
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
          :gates/report [{:gate :fmt :status :pass :exit 0 :out "" :ms 12}
                         {:gate :lint :status :fail :exit 1 :out "boom" :ms 40}
                         {:gate :test :status :skipped :exit nil :out "" :ms nil}]})))
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

(deftest a-run-record-may-name-its-commits-and-a-repository-it-had-none-of
  (let [base {:run/id "r" :task/id "t" :run/attempts 1 :run/status :awaiting-merge :run/cost nil
              :run/started-at (java.util.Date.)}]
    (is (shapes/valid-run? (assoc base :run/kit-commit "abc" :run/app-commit "def" :run/plan-commit nil)))
    (is (not (shapes/valid-run? (assoc base :run/kit-commit 12))) "a hash is a string")))
