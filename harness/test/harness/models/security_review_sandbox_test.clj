(ns harness.models.security-review-sandbox-test
  "The review sandbox's commands and the order of its steps, with a recording stand-in for the
  function that runs a command. Starting a real container is tried by hand and by
  `bb security-review`, never in the gates."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.models.security-review-sandbox :as sb]))

(def kit (str (fs/normalize (fs/absolutize ".."))))

(deftest the-image-is-the-one-docker-gates-builds
  (is (re-matches #"clojure-agent-kit-toolchain:\d{4}-\d{2}-\d{2}" (sb/image-tag kit))))

(deftest the-isolated-container-has-no-network-a-read-only-cache-and-limits
  (let [argv (sb/run-argv {:image "img" :clone "/c" :name "n" :review "/r"})
        has (fn [& xs] (some #(= xs (take (count xs) %)) (partition (count xs) 1 argv)))]
    (is (has "--network" "none"))
    (is (has "-v" "kit-review-m2:/root/.m2:ro") "the cache cannot be written once the network is off")
    (is (has "-v" "/r:/review:ro"))
    (is (has "--cap-drop" "ALL"))
    (is (has "--security-opt" "no-new-privileges"))
    (is (has "--memory" "4g"))
    (is (has "--pids-limit" "512"))
    (is (not (some #{"--privileged" "--network=host"} argv)))))

(deftest the-warming-container-is-the-only-one-with-a-network
  (let [argv (sb/warm-argv {:image "img" :clone "/c"})]
    (is (not-any? #{"none"} argv) "no --network none: it fetches")
    (is (some #(= "kit-review-m2:/root/.m2" %) argv) "and the cache is writable")
    (is (str/includes? (last argv) "-X:test"))
    (is (str/includes? (last argv) "bb tasks") "the application's own bb dependencies too")))

(deftest tests-run-in-a-pristine-copy-all-of-them-or-one-namespace-and-a-request-is-one-json-argument
  (let [all (sb/tests-argv "n" nil)
        one (sb/tests-argv "n" "app.review-notes-test")]
    (is (= ["bash" "-c"] (subvec (vec all) 5 7)))
    (is (str/ends-with? (last all) "exec clojure -Srepro -X:test"))
    (is (str/includes? (last one) "exec clojure -Srepro -M:test -e"))
    (is (str/includes? (last one) "(clojure.test/run-tests 'app.review-notes-test)"))
    (is (str/includes? (last one) "System/exit"))
    (testing "each starts from the files a fresh clone has"
      (doseq [script [(last all) (last one)]]
        (is (str/includes? script "rm -rf /tmp/run"))
        (is (str/includes? script "ls-files -co --exclude-standard") "tracked, plus untracked and not ignored: the reviewer's tests, not target/ or db/")
        (is (str/includes? script "cd /tmp/run")))))
  (let [argv (sb/request-argv "n" {:method "POST" :path "/a b?x='1" :headers {"X-A" "1"} :body "t\"x"})]
    (is (= ["bb" "/review/request.bb"] (subvec (vec argv) 5 7)))
    (is (= {"method" "POST" "path" "/a b?x='1" "headers" {"X-A" "1"} "body" "t\"x"} (json/parse-string (last argv)))
        "whatever the text holds, it is one argument and no shell reads it")))

(deftest an-answer-is-read-and-a-bad-one-is-an-error
  (is (= {:status 200 :headers [["a" "b"]] :body "x"}
         (sb/parse-answer "noise\n{\"status\":200,\"headers\":[[\"a\",\"b\"]],\"body\":\"x\",\"extra\":1}\n")))
  (is (= {:error "ConnectException: refused"} (sb/parse-answer "{\"error\":\"ConnectException: refused\"}")))
  (is (str/includes? (:error (sb/parse-answer "")) "not readable"))
  (is (str/includes? (:error (sb/parse-answer "Error building classpath")) "not readable")))

(defn- recorder
  "A `run` that records every argv and answers from `script`: a function of the argv."
  [seen script]
  (fn [argv _opts] (swap! seen conj argv) (script argv)))

(defn- health [status] (str "{\"status\":" status ",\"headers\":[],\"body\":\"OK\"}"))

(deftest starting-warms-then-isolates-then-serves-then-waits-for-health
  (let [seen (atom [])
        n (atom 0)
        run (recorder seen (fn [argv]
                             (cond (some #{"request.bb"} (map #(last (str/split (str %) #"/")) argv))
                                   {:exit 0 :out (if (< (swap! n inc) 3) "{\"error\":\"ConnectException\"}" (health 200)) :err ""}
                                   :else {:exit 0 :out "" :err ""})))
        slept (atom [])
        s (sb/start! {:kit kit :clone "/c" :image "img" :run run :sleep #(swap! slept conj %)})
        _ (is (= sb/stale-argv (first @seen)) "what earlier reviews left exited is looked for first")
        seen' (rest @seen)
        steps (mapv #(vec (take 3 %)) seen')]
    (is (= ["docker" "run" "--rm"] (first steps)) "warm first, with a network")
    (is (= ["docker" "run" "-d"] (second steps)) "then the isolated container")
    (is (= ["docker" "exec" "-d"] (nth steps 2)) "then the application")
    (is (= 3 (count (filter #(some #{"bb"} %) (drop 3 seen')))) "polled /health until it answered")
    (is (= [2000 2000] @slept) "two waits before the third poll answered")
    (is (= (:name s) (nth (second seen') 4)) "the container's name is the one every later command uses")
    (testing "the tools' two functions"
      (is (= {:status 200 :headers [] :body "OK"} ((:request s) {:method "GET" :path "/health"})))
      (is (= {:exit 0 :out ""} ((:run-tests s) nil))))
    (testing "stopping removes the container"
      ((:stop! s))
      (is (= ["docker" "rm" "-f" (:name s)] (last @seen))))))

(deftest a-failing-step-removes-the-container-and-says-which
  (let [seen (atom [])
        run (recorder seen (fn [argv] (if (= "-d" (nth argv 2 nil))
                                        {:exit 125 :out "" :err "docker: no space left"}
                                        {:exit 0 :out "" :err ""})))
        e (try (sb/start! {:kit kit :clone "/c" :image "img" :run run :sleep (fn [_])}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= "starting the container" (:step (ex-data e))))
    (is (str/includes? (ex-message e) "no space left"))
    (is (= "rm" (second (last @seen))) "the container was removed")))

(deftest an-application-that-never-answers-is-given-up-on-and-removed
  (let [seen (atom [])
        run (recorder seen (fn [argv] (if (some #(str/ends-with? (str %) "request.bb") argv)
                                        {:exit 0 :out "{\"status\":503,\"headers\":[],\"body\":\"\"}" :err ""}
                                        {:exit 0 :out "" :err ""})))
        e (try (sb/start! {:kit kit :clone "/c" :image "img" :run run :sleep (fn [_]) :timeout-ms 4000}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "waiting for the application" (:step (ex-data e))))
    (is (str/includes? (ex-message e) "did not answer /health in 4s: status 503"))
    (is (= "rm" (second (last @seen))))))

(deftest containers-an-earlier-review-left-exited-are-removed-and-said
  ;; A killed review reaches no `finally`: two were found two days on, holding the cache's volume
  (let [seen (atom [])
        run (recorder seen (fn [argv] (if (= sb/stale-argv argv)
                                        {:exit 0 :out "kit-review-aaaa1111\nkit-review-bbbb2222\n" :err ""}
                                        {:exit 0 :out "" :err ""})))
        out (with-out-str (is (= ["kit-review-aaaa1111" "kit-review-bbbb2222"] (sb/clear-stale! run))))]
    (is (= [["docker" "rm" "-f" "kit-review-aaaa1111"] ["docker" "rm" "-f" "kit-review-bbbb2222"]] (vec (rest @seen))))
    (is (str/includes? out "removed 2 exited container(s) an earlier review left"))
    (is (some #{"status=exited"} sb/stale-argv) "a review running beside this one is left alone")
    (reset! seen [])
    (is (= [] (sb/clear-stale! (recorder seen (fn [_] {:exit 0 :out "" :err ""})))) "none, nothing said")))

;; ---------------------------------------------------------------------------
;; the KIT as the target
;; ---------------------------------------------------------------------------

(deftest the-kit-s-container-is-the-same-sandbox-with-both-caches-read-only
  (let [argv (sb/kit-run-argv {:image "img" :clone "/c" :name "n" :review "/r"})
        has (fn [& xs] (some #(= xs (take (count xs) %)) (partition (count xs) 1 argv)))]
    (is (has "--network" "none"))
    (is (has "-v" "kit-review-m2:/root/.m2:ro") "a test runs as root in it, and every project's review mounts this cache after")
    (is (has "-v" "kit-review-depsclj:/root/.deps.clj:ro"))
    (is (has "--cap-drop" "ALL"))
    (is (has "--security-opt" "no-new-privileges"))
    (is (has "--pids-limit" "512")))
  (let [warm (sb/kit-warm-argv {:image "img" :clone "/c"})]
    (is (not-any? #{"none"} warm) "the warming container alone fetches")
    (is (some #{"kit-review-m2:/root/.m2"} warm))
    (is (str/includes? (last warm) "bb tasks")))
  (is (str/starts-with? (sb/kit-container-name) "kit-review-") "so a killed one is cleared as any review's is"))

(deftest the-kit-s-tests-run-by-bb-the-namespace-an-argument-never-a-shell-s
  (is (= ["docker" "exec" "-w" "/work/kit/harness" "n" "timeout" "600" "bb" "/review/run-one.clj" "harness.review.x-test"]
         (sb/kit-tests-argv "n" "harness.review.x-test")))
  (is (= ["bb" "test"] (take-last 2 (sb/kit-tests-argv "n" nil))))
  (is (not-any? #{"bash"} (sb/kit-tests-argv "n" "a.b-test"))))

(deftest starting-the-kit-s-sandbox-clears-warms-isolates-and-serves-nothing
  (let [seen (atom [])
        run (recorder seen (fn [_] {:exit 0 :out "" :err ""}))
        s (sb/start-kit! {:kit kit :clone "/c" :image "img" :run run})]
    (is (= sb/stale-argv (first @seen)))
    (is (= [["docker" "run" "--rm"] ["docker" "run" "-d"] ["docker" "exec" (:name s)]]
           (mapv #(vec (take 3 %)) (rest @seen))))
    (is (not (contains? s :request)) "nothing is served")
    ((:stop! s))
    (is (= ["docker" "rm" "-f" (:name s)] (last @seen)))))

(deftest the-clone-s-git-is-read-only-in-both-containers
  ;; its configuration is what the host's git reads; `core.fsmonitor` written inside had run on the host
  (let [has (fn [argv & xs] (some #(= xs (take (count xs) %)) (partition (count xs) 1 argv)))]
    (is (has (sb/run-argv {:image "img" :clone "/c" :name "n" :review "/r"}) "-v" "/c/.git:/work/app/.git:ro"))
    (is (has (sb/kit-run-argv {:image "img" :clone "/c" :name "n" :review "/r"}) "-v" "/c/.git:/work/kit/.git:ro"))))
