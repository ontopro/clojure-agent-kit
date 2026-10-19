(ns harness.loop.run-lock-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.loop.run-lock :as run-lock]))

(defn- run-dir [] (str (fs/create-temp-dir)))

(deftest a-second-command-is-refused-while-the-first-holds-the-run
  (let [d (run-dir)]
    (run-lock/with-lock d "run"
      (fn []
        (let [e (try (run-lock/acquire! d "retry") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :locked (:run-loop/error (ex-data e))))
          (testing "the refusal names the holder and says nothing was written"
            (is (str/includes? (ex-message e) "`run` (pid "))
            (is (str/includes? (ex-message e) "Nothing was written"))))))
    (testing "released when the command returns: the next one takes it"
      (is (not (fs/exists? (run-lock/lock-path d))))
      (is (= 1 (run-lock/with-lock d "record" (constantly 1)))))))

(deftest the-lock-is-released-when-the-command-throws
  (let [d (run-dir)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (run-lock/with-lock d "retry" #(throw (ex-info "boom" {})))))
    (is (not (fs/exists? (run-lock/lock-path d))))))

(deftest a-lock-left-behind-is-taken-over-and-said
  (testing "its process is gone"
    (let [d (run-dir)]
      (spit (run-lock/lock-path d) (pr-str {:pid 999999999 :start "2026-01-01T00:00:00Z" :cmd "run" :at "earlier"}))
      (let [out (with-out-str (is (= :ran (run-lock/with-lock d "retry" (constantly :ran)))))]
        (is (str/includes? out "`run` (pid 999999999, since earlier) left its lock behind")))
      (is (not (fs/exists? (run-lock/lock-path d))))))
  (testing "its pid is alive but started at another instant - a reused pid"
    (let [d (run-dir)
          me (run-lock/this-process)]
      (spit (run-lock/lock-path d) (pr-str (assoc me :start "1999-01-01T00:00:00Z" :cmd "run")))
      (is (not (run-lock/alive? (run-lock/holder d))))
      (is (= :ran (run-lock/with-lock d "retry" (constantly :ran))))))
  (testing "a live holder is never taken over"
    (let [d (run-dir)]
      (spit (run-lock/lock-path d) (pr-str (assoc (run-lock/this-process) :cmd "run")))
      (is (run-lock/alive? (run-lock/holder d)))
      (is (thrown? clojure.lang.ExceptionInfo (run-lock/acquire! d "retry")))
      (is (fs/exists? (run-lock/lock-path d)) "the holder's lock is left as it was"))))

(deftest release-leaves-a-lock-another-process-holds
  (let [d (run-dir)
        {:keys [path me]} (run-lock/acquire! d "run")]
    (spit path (pr-str {:pid 1 :start "x" :cmd "other"}))
    (run-lock/release! path me)
    (is (fs/exists? path))))
