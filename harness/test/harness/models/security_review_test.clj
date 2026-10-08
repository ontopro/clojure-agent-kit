(ns harness.models.security-review-test
  "The security review: what the reviewer is told, how its answer is read, and one whole review
  against a stub model and a stand-in sandbox. A review against a real model and a real
  container is `bb security-review`, never in the gates."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.models.security-review :as sr]
   [org.httpkit.server :as srv]))

(deftest the-prompts-ask-for-a-test-and-welcome-nothing
  (let [s sr/system-prompt]
    (is (str/includes? s "A finding is a test of yours that FAILS"))
    (is (str/includes? s "Finding nothing is a complete and welcome answer"))
    (is (str/includes? s "in a sandbox with no network"))
    (is (str/includes? s "with an empty list when you found nothing"))
    (testing "and it asks for the owner's review in plain terms"
      (is (not (re-find #"(?i)attack|exploit|weaponi[sz]e|hack|malicious|zero-day" s))))
    (doseq [[_ {:keys [task]}] sr/stances]
      (is (not (re-find #"(?i)attack|exploit|weaponi[sz]e|hack|malicious|zero-day" task))))))

(deftest the-opening-gives-the-tracer-the-files-and-the-change-reviewer-the-diff-too
  (let [facts {:files ["src/a.clj" "test/a_test.clj"] :diff "+(defn f [])"}
        tracer (sr/opening :tracer facts)
        diff (sr/opening :diff facts)]
    (is (str/includes? tracer "architecture tracer review"))
    (is (str/includes? tracer "src/a.clj\ntest/a_test.clj"))
    (is (not (str/includes? tracer "+(defn f [])")) "the tracer reads the repository, not a diff")
    (is (str/includes? diff "change reviewer review"))
    (is (str/includes? diff "```diff\n+(defn f [])\n```"))
    (testing "bounded"
      (let [many (sr/opening :tracer {:files (mapv #(str "f" %) (range 500))})
            big (sr/opening :diff {:files [] :diff (apply str (repeat (* 2 sr/max-diff) "x"))})]
        (is (str/includes? many "[300 more not listed"))
        (is (str/includes? big (str "[diff cut at " sr/max-diff " of ")))))
    (is (str/includes? (sr/opening :tracer (assoc facts :scans "deps: 1 ok")) "deps: 1 ok"))))

(deftest the-answer-is-read-from-its-last-json-block
  (let [text (str "I checked the routes.\n```json\n{\"findings\": [{\"title\": \"IDOR\", \"kind\": \"reproduced\", "
                  "\"test\": \"test/a/idor_test.clj\", \"where\": \"notebook/note-for\", \"why\": \"w\", \"extra\": 1}, "
                  "{\"title\": \"maybe\", \"kind\": \"unsure\"}]}\n```")]
    (is (= [{:title "IDOR" :kind :reproduced :test "test/a/idor_test.clj" :where "notebook/note-for" :why "w"}
            {:title "maybe" :kind :hypothesis}]
           (sr/parse-findings text))
        "anything but 'reproduced' is a hypothesis; unknown keys are dropped")
    (is (= [] (sr/parse-findings "Nothing.\n```json\n{\"findings\": []}\n```")) "an honest nothing is an empty vector")
    (is (nil? (sr/parse-findings "I found nothing wrong.")) "no block is not a clean review")
    (is (nil? (sr/parse-findings "```json\n{\"result\": 1}\n```")))))

;; ---------------------------------------------------------------------------
;; one whole review
;; ---------------------------------------------------------------------------

(defn- tool-reply [id nm args]
  {:id (str "gen-" id)
   :choices [{:message {:content nil
                        :tool_calls [{:id (str "call-" id) :type "function"
                                      :function {:name nm :arguments (json/generate-string args)}}]}}]
   :usage {:prompt_tokens 10 :completion_tokens 5}})

(defn- text-reply [s] {:id "gen-last" :choices [{:message {:content s}}] :usage {:prompt_tokens 10 :completion_tokens 5}})

(defn- with-stub [responses f]
  (let [n (atom -1)
        stop (srv/run-server (fn [_] (let [i (min (swap! n inc) (dec (count responses)))]
                                       {:status 200 :headers {"Content-Type" "application/json" "Connection" "close"}
                                        :body (json/generate-string (nth responses i))}))
                             {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (srv/server-port stop)))
         (finally @(srv/server-stop! stop)))))

(defn- clone! []
  (let [dir (str (fs/create-temp-dir {:prefix "kit-review"}))
        git (fn [& a] (apply p/shell {:dir dir :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@t" a))]
    (git "init" "-q")
    (fs/create-dirs (fs/path dir "src"))
    (fs/create-dirs (fs/path dir "test"))
    (spit (str (fs/path dir "src" "a.clj")) "(ns a)\n(defn find-note [id] id)\n")
    (git "add" "-A") (git "commit" "-q" "-m" "base") (git "tag" "base")
    (spit (str (fs/path dir "src" "a.clj")) "(ns a)\n(defn find-note [id] id)\n(defn more [] 1)\n")
    (git "commit" "-aq" "-m" "change")
    dir))

(def finding-test "(ns a-test\n  (:require [clojure.test :refer [deftest is]]))\n\n(deftest the-property (is (= 1 2)))\n")

(deftest a-whole-review-reads-writes-a-test-and-reports-it
  (let [clone (clone!)
        stopped (atom false)
        sandbox {:request (fn [_] {:status 200 :headers [] :body "OK"})
                 :run-tests (fn [_] {:exit 1 :out "FAIL in a-test/the-property\n1 failures"})
                 :stop! (fn [] (reset! stopped true))}
        answer (str "```json\n{\"findings\": [{\"title\": \"property does not hold\", \"kind\": \"reproduced\", "
                    "\"test\": \"test/a_test.clj\", \"where\": \"a/find-note\", \"why\": \"w\"}]}\n```")
        r (with-stub [(tool-reply 1 "search" {:pattern "find-note"})
                      (tool-reply 2 "write_test" {:path "test/a_test.clj" :content finding-test})
                      (tool-reply 3 "run_tests" {:namespace "a-test"})
                      (text-reply answer)]
            #(sr/review! {:family :stub :model "stub-1" :shape :openai :endpoint %} :diff clone {:sandbox sandbox :rounds 10}))]
    (is (= :done (:status r)))
    (is (= [:reproduced] (mapv :kind (:findings r))))
    (is (false? (:no-block? r)))
    (is (= ["search" "write_test" "run_tests"] (mapv first (:calls r))) "the three tools it called, in order")
    (is (= {"test/a_test.clj" finding-test} (:tests r)) "the file it wrote is collected from the clone")
    (is (= :diff (:stance r)))
    (is (false? @stopped) "a sandbox the caller started is the caller's to stop")
    (testing "the record: everything, and the tests beside it"
      (let [out (str (fs/create-temp-dir))
            f (sr/write-record! out "stub-diff" r)]
        (is (= ["test/a_test.clj"] (:tests (read-string (slurp f)))))
        (is (= finding-test (slurp (str (fs/path out "stub-diff-tests" "test" "a_test.clj")))))))))

(deftest an-answer-with-no-block-is-not-a-clean-review
  (let [clone (clone!)
        sandbox {:request (fn [_] {}) :run-tests (fn [_] {:exit 0 :out ""}) :stop! (fn [])}
        r (with-stub [(text-reply "I looked and all seems fine.")]
            #(sr/review! {:family :stub :model "stub-1" :shape :openai :endpoint %} :tracer clone {:sandbox sandbox}))]
    (is (true? (:no-block? r)))
    (is (nil? (:findings r)))))

(deftest the-clone-facts-are-its-files-and-its-change
  (let [clone (clone!) facts (sr/clone-facts clone "base")]
    (is (= ["src/a.clj"] (:files facts)))
    (is (str/includes? (:diff facts) "+(defn more [] 1)"))))
