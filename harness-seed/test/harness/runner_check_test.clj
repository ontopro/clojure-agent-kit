(ns harness.runner-check-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [harness.packet :as packet]
   [harness.packet-test :as pt]
   [harness.runner :as runner]
   [harness.runner-check :as check]))

(def packet (packet/coder-packet pt/spec pt/session))

(defn- ok-result [] {:status :done :files ["src/app/service.clj"]
                     :stdout nil :cost nil})

(defn- failing-check
  "The single check id that did not pass, or the whole set if that is not
  exactly one — each saboteur below should trip exactly one."
  [results]
  (let [bad (map :check (check/failures results))]
    (if (= 1 (count bad)) (first bad) (set bad))))

(deftest an-honest-runner-conforms
  (let [r (reify runner/AgentRunner
            (run-agent [_ role _] (if (= :reviewer role)
                                    (assoc (ok-result) :files [])
                                    (ok-result))))]
    (is (check/conforms? (check/check-runner r {:packet packet})))))

(deftest undeclared-keys-are-caught
  ;; The lesson, executable. Each of these five rode on the result map in
  ;; the source project for weeks; an open schema could not see any of them.
  (doseq [[k v] {:session-id "abc-123" :verdict "approve" :capped? true
                 :provider "some-host" :tokens 4096}]
    (let [r (reify runner/AgentRunner
              (run-agent [_ role _] (cond-> (ok-result)
                                      (= :reviewer role) (assoc :files [])
                                      true (assoc k v))))]
      (is (= :result-shape (failing-check (check/check-runner r {:packet packet})))
          (str k " at the top level must fail exactly the shape check"))))
  (testing "the same values under :runner/meta conform"
    (let [r (reify runner/AgentRunner
              (run-agent [_ role _]
                (cond-> (assoc (ok-result) :runner/meta {:session-id "abc-123"
                                                         :provider "some-host"})
                  (= :reviewer role) (assoc :files []))))]
      (is (check/conforms? (check/check-runner r {:packet packet}))))))

(deftest a-runner-that-throws-is-caught
  (let [r (reify runner/AgentRunner
            (run-agent [_ _ _] (throw (ex-info "connection reset" {}))))
        results (check/check-runner r {:packet packet})]
    (is (some #(and (= :no-throw (:check %)) (not (:pass? %))) results))
    (is (re-find #"already-paid-for" (:detail (first (check/failures results)))))))

(deftest a-failed-result-must-report-no-files
  (let [r (reify runner/AgentRunner
            (run-agent [_ role _] (if (= :reviewer role)
                                    (assoc (ok-result) :files [])
                                    {:status :failed :files ["src/app/service.clj"]
                                     :stdout "boom" :cost nil})))]
    (is (= :failed-produces-nothing (failing-check (check/check-runner r {:packet packet}))))))

(deftest the-reviewer-must-write-nothing
  (let [r (reify runner/AgentRunner
            (run-agent [_ _ _] (ok-result)))]   ; returns files for EVERY role
    (is (= :reviewer-writes-nothing (failing-check (check/check-runner r {:packet packet}))))))

(deftest a-bad-status-is-caught
  (let [r (reify runner/AgentRunner
            (run-agent [_ _ _] (assoc (ok-result) :status :ok)))
        bad (set (map :check (check/failures (check/check-runner r {:packet packet}))))]
    (is (contains? bad :status-enum))))

(deftest files-exist-is-opt-in
  (let [dir (str (fs/create-temp-dir))
        wt-packet (assoc packet :repl/worktree dir)
        phantom (reify runner/AgentRunner
                  (run-agent [_ role _] (if (= :reviewer role)
                                          (assoc (ok-result) :files [])
                                          (ok-result))))]
    (testing "a runner reporting files it never wrote passes by default"
      (is (check/conforms? (check/check-runner phantom {:packet wt-packet}))))
    (testing "and fails once you claim it really writes"
      (let [results (check/check-runner phantom {:packet wt-packet :expect-writes? true})]
        (is (= :files-exist (failing-check results)))))
    (testing "a runner that really writes passes with the check on"
      (fs/create-dirs (fs/path dir "src/app"))
      (spit (str (fs/path dir "src/app/service.clj")) "(ns app.service)\n")
      (is (check/conforms?
           (check/check-runner phantom {:packet wt-packet :expect-writes? true}))))))
