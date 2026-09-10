(ns harness.runner-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.packet :as packet]
   [harness.packet-test :as pt]
   [harness.runner :as runner]
   [harness.runner-check :as check]
   [harness.shapes :as shapes]))

(def coder-packet (packet/coder-packet pt/spec pt/session))

(defn- reader-of [& lines]
  (let [remaining (atom lines)]
    (fn [] (let [[l & more] @remaining] (reset! remaining more) l))))

(defn- quietly [f]
  (let [res (atom nil)]
    (with-out-str (reset! res (f)))
    @res))

(deftest manual-runner
  (testing "human signals done -> :done with the packet's target files"
    (let [out (with-out-str
                (let [res (runner/run-agent (runner/manual-runner (reader-of "done"))
                                            :coder coder-packet)]
                  (is (shapes/valid-result? res))
                  (is (= :done (:status res)))
                  (is (= ["src/app/service.clj"] (:files res)))
                  (is (nil? (:cost res)))))]
      (testing "the pause prints what the human needs to act"
        (is (str/includes? out "t-07-service-ops"))
        (is (str/includes? out "7807")))))
  (testing "human signals fail -> :failed, no files"
    (let [res (quietly #(runner/run-agent (runner/manual-runner (reader-of "fail"))
                                          :coder coder-packet))]
      (is (= :failed (:status res)))
      (is (= [] (:files res)))))
  (testing "garbage input reprompts; EOF fails instead of spinning"
    (is (= :done (:status (quietly #(runner/run-agent
                                     (runner/manual-runner (reader-of "wat" "done"))
                                     :coder coder-packet)))))
    (is (= :failed (:status (quietly #(runner/run-agent
                                       (runner/manual-runner (reader-of))
                                       :coder coder-packet)))))))

(deftest the-eval-hint-is-swappable
  (testing "the REPL bridge named in the pause is a string, not a dependency"
    (let [out (with-out-str
                (runner/run-agent
                 (runner/manual-runner (reader-of "done") (fn [p] (str "my-repl " p)))
                 :coder coder-packet))]
      (is (str/includes? out "my-repl 7807")))))

(deftest manual-runner-conforms
  (let [results (quietly #(check/check-runner
                           (runner/manual-runner (reader-of "done" "done"))
                           {:packet coder-packet}))]
    (is (check/conforms? results)
        (str "failures: " (pr-str (check/failures results))))))
