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

(deftest the-output-ceiling-reaches-the-request-and-an-empty-answer-is-flagged
  ;; a first review's last completion came back with no text at all: cut off at the route's 16,000
  ;; output tokens, thinking. The review asks for room, and says so when the answer is empty.
  (let [clone (clone!)
        seen (atom [])
        sandbox {:request (fn [_] {}) :run-tests (fn [_] {:exit 0 :out ""}) :stop! (fn [])}
        stop (srv/run-server (fn [req]
                               (swap! seen conj (json/parse-string (slurp (:body req)) true))
                               {:status 200 :headers {"Content-Type" "application/json" "Connection" "close"}
                                :body (json/generate-string (text-reply nil))})
                             {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        role {:family :stub :model "stub-1" :shape :openai :endpoint (str "http://127.0.0.1:" (srv/server-port stop))
              :params {:max_tokens 16000 :reasoning_effort "high"}}]
    (try
      (let [r (sr/review! role :tracer clone {:sandbox sandbox})
            r2 (sr/review! role :tracer clone {:sandbox sandbox :max-tokens 1234})]
        (is (= 64000 (get-in (first @seen) [:max_tokens])) "the review's own ceiling replaces the route's")
        (is (= 1234 (get-in (last @seen) [:max_tokens])))
        (is (= "high" (get-in (first @seen) [:reasoning_effort])) "the rest of the role's params stay")
        (is (true? (:empty-answer? r)))
        (is (true? (:empty-answer? r2)))
        (is (vector? (:turns r)))
        (is (vector? (:steps r))))
      (finally @(srv/server-stop! stop)))))

(deftest a-refusal-is-reported-as-a-refusal
  (is (true? (sr/refused? [{:finish-reason "tool_calls"} {:finish-reason "content_filter" :native-finish-reason "refusal"}])))
  (is (true? (sr/refused? [{:native-finish-reason "refusal"}])))
  (is (true? (sr/refused? [{:finish-reason "content_filter"}])))
  (is (false? (sr/refused? [{:finish-reason "stop"} {:finish-reason "length"} {}])))
  (is (false? (sr/refused? [])))
  (testing "at Anthropic's own API the refusal is the call's error, with no step to read it from"
    (is (true? (sr/refused? [] {:harness/error :api-error :status 200 :stop-reason "refusal"
                                :message "the model refused (cyber)"})))
    (is (false? (sr/refused? [] {:harness/error :api-error :status 200 :stop-reason "max_tokens"})))))

(deftest the-security-reviewer-can-come-from-a-profile
  (let [dir (fs/create-temp-dir {:prefix "kit-sr-profile"})
        with (str (fs/path dir "with.edn"))
        without (str (fs/path dir "without.edn"))]
    (spit with (pr-str {:seat :claude
                        :roles {:security-reviewer {:family :openai :model "openai/gpt-6-astra" :shape :openai
                                                    :endpoint "https://example.test"
                                                    :params {:reasoning_effort "high"}}}}))
    (spit without (pr-str {:seat :claude :roles {}}))
    (let [c (sr/role-from-profile with)]
      (is (= "gpt-6-astra" (:id c)) "the record is named by the model, as with --model")
      (is (= "high" (:effort c)))
      (is (= :openai (get-in c [:profile :family])) "the role block is what reads"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no :security-reviewer"
                          (sr/role-from-profile without)))))

(deftest a-review-of-the-kit-is-pointed-at-its-source-and-told-what-an-earlier-pass-fixed
  (let [files ["harness/src/harness/a.clj" "harness/test/harness/a_test.clj" "tools/security/check.bb"
               ".githooks/pre-commit" "bb.edn" "method.md"]
        o (sr/kit-opening {:head "abc1234" :files files
                           :after [{:model "gemini-4-argon" :findings [{:title "T one" :where "a.clj: f"} {:title "T two"}]}]})]
    (is (= ["harness/src/harness/a.clj" "tools/security/check.bb" ".githooks/pre-commit" "bb.edn"] (sr/kit-scope files)))
    (is (str/includes? o "at abc1234"))
    (is (str/includes? o "(4 files)"))
    (is (str/includes? o "EARLIER PASSES (gemini-4-argon) FOUND THESE, AND THEY ARE FIXED"))
    (is (str/includes? o "- T one (a.clj: f)\n- T two"))
    (is (not (str/includes? (sr/kit-opening {:head "h" :files files}) "EARLIER PASSES")) "no earlier pass, nothing said"))
  (is (str/includes? sr/kit-system-prompt "harness/test/"))
  (is (str/includes? sr/kit-system-prompt "Finding nothing is a complete and welcome answer"))
  (is (= {:kit true :after ["r.edn"] :model "m"} (sr/parse-args ["--kit" "--after" "r.edn" "--model" "m"]))))

;; From the KIT's own security review (GPT-6 Astra, 2026-10-21): the clone was mounted writable in a
;; container where a model's test ran as root, and the host collected the tests from it with git
(defn- git! [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string} "git" args))

(deftest collecting-the-tests-follows-no-link-left-in-the-clone
  (doseq [[test-dir collect] [["test" sr/reproductions] ["harness/test" sr/kit-reproductions]]]
    (let [outer (fs/create-temp-dir {:prefix "kit-review-collect-link"})
          clone (fs/path outer "clone")
          secret (fs/path outer "host-secret")
          out (fs/path outer "records")
          rel (str test-dir "/leak_test.clj")]
      (try
        (fs/create-dirs (fs/path clone test-dir))
        (git! clone "init" "-q")
        (spit (str secret) "HOST-ONLY-SECRET")
        (fs/create-sym-link (fs/path clone rel) secret)
        (spit (str (fs/path clone test-dir "mine_test.clj")) "(ns mine-test)")
        (let [tests (collect clone [])]
          (sr/write-record! out "review" {:tests tests})
          (is (= [(str test-dir "/mine_test.clj")] (keys tests)) "the test it wrote, and nothing through a link")
          (is (not (fs/exists? (fs/path out "review-tests" rel)))))
        (finally (fs/delete-tree outer))))))

(deftest collecting-the-tests-runs-no-git-in-the-clone
  (doseq [[test-dir collect] [["test" sr/reproductions] ["harness/test" sr/kit-reproductions]]]
    (let [outer (fs/create-temp-dir {:prefix "kit-review-collect-git"})
          clone (fs/path outer "clone")
          marker (fs/path outer "host-command-ran")]
      (try
        (fs/create-dirs (fs/path clone test-dir))
        (git! clone "init" "-q")
        (spit (str (fs/path clone "seed.txt")) "seed")
        (git! clone "add" "seed.txt")
        (spit (str (fs/path clone ".git" "config"))
              (str "\n[core]\n\tfsmonitor = \"touch " marker "; echo token\"\n") :append true)
        (collect clone ["seed.txt"])
        (is (not (fs/exists? marker)) "the clone's core.fsmonitor never runs on the host")
        (finally (fs/delete-tree outer))))))

;; Fable 5.1's read of `baccaeb`: a test running as root can write any file under test/
(deftest only-a-test-file-of-a-test-s-size-is-collected
  (doseq [[test-dir collect] [["test" sr/reproductions] ["harness/test" sr/kit-reproductions]]]
    (let [outer (fs/create-temp-dir {:prefix "kit-review-collect-shape"})
          clone (fs/path outer "clone")]
      (try
        (fs/create-dirs (fs/path clone test-dir "app"))
        (spit (str (fs/path clone test-dir "app" "mine_test.clj")) "(ns app.mine-test)")
        (spit (str (fs/path clone test-dir "app" ".DS_Store")) "finder")
        (spit (str (fs/path clone test-dir "app" "notes.txt")) "an editor's")
        (spit (str (fs/path clone test-dir "app" "huge_test.clj")) (apply str (repeat (* 60 1024) "x")))
        (let [out (with-out-str (is (= [(str test-dir "/app/mine_test.clj")] (keys (collect clone [])))))]
          (is (str/includes? out "huge_test.clj is over") "the one over the size is named"))
        (finally (fs/delete-tree outer))))))

;; ---------------------------------------------------------------------------
;; the KIT in flows (plan 6.11, item 10)
;; ---------------------------------------------------------------------------

(deftest the-kit-s-flows-are-six-and-the-seams
  (let [flows (sr/kit-flows)]
    (is (= ["tool-calls" "contract" "money-and-records" "security-review" "setup-and-upgrade" "tool-packs" "seams"]
           (mapv :name flows)))
    (is (every? #(and (seq (:reads %)) (string? (:asks %))) flows))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no flow named nope - the flows: tool-calls" (sr/kit-flow "nope")))))

(deftest a-flow-is-the-scope-and-the-rest-is-context
  (let [files ["harness/src/harness/models/tools.clj" "harness/src/harness/models/agent.clj"
               "harness/src/harness/money/reprice.clj" "tools/security/check.bb" "method.md"]
        flow (sr/kit-flow "tool-calls")
        o (sr/kit-opening {:head "abc" :files files :flow flow})]
    (is (= ["harness/src/harness/models/tools.clj" "harness/src/harness/models/agent.clj"] (sr/kit-scope files flow)))
    (is (str/includes? o "one flow of the KIT at abc, `tool-calls`:"))
    (is (str/includes? o "Its files (2):"))
    (is (str/includes? o "report only what is wrong inside this flow's files"))
    (is (not (str/includes? (sr/kit-opening {:head "abc" :files files}) "report only")) "the whole pass is not narrowed")))

(deftest every-earlier-pass-is-told-once
  (let [o (sr/kit-opening {:head "h" :files []
                           :after [{:model "a" :findings [{:title "One"} {:title "Two"}]}
                                   {:model "b" :findings [{:title "Two"} {:title "Three" :where "x.clj"}]}]})]
    (is (str/includes? o "EARLIER PASSES (a, b) FOUND THESE"))
    (is (str/includes? o "- One\n- Two\n- Three (x.clj)") "merged, each finding once")
    (is (= {:after ["r1.edn" "r2.edn"] :flow "seams" :force true :table true}
           (sr/parse-args ["--after" "r1.edn" "--after" "r2.edn" "--flow" "seams" "--force" "--table"])))))

(deftest the-passes-side-by-side-by-flow
  (let [out (fs/create-temp-dir {:prefix "kit-review-table"})]
    (try
      (spit (str (fs/path out "a.edn")) (pr-str {:target "KIT abc" :flow "contract" :model "m1" :status :done :cost 2.5 :ms 120000
                                                 :findings [{:kind :reproduced :title "T" :where "w" :test "harness/test/t_test.clj"}
                                                            {:kind :hypothesis :title "H"}]
                                                 :tests ["harness/test/t_test.clj"]}))
      (spit (str (fs/path out "b.edn")) (pr-str {:target "KIT abc" :model "m2" :status :failed :no-block? true :tests ["x" "y"]}))
      (spit (str (fs/path out "other.edn")) (pr-str {:stance :tracer :model "not the KIT's"}))
      (let [t (sr/kit-table! out)
            md (slurp (str (fs/path out "kit-review.md")))]
        (is (= [["whole" :no-answer 0 0 2] ["contract" :answered 1 1 1]]
               (mapv (juxt :flow :status :reproduced :hypotheses :tests) (:passes t))) "a project's record is not the KIT's")
        (is (= ["T" "H"] (mapv :title (filter #(= "contract" (:flow %)) (:findings t)))))
        (is (str/includes? md "| contract | m1 | answered | $2.50 | 2.0 | 1 | 1 | 1 | a.edn |"))
        (is (str/includes? md "- **contract**, reproduced: T (w) - `harness/test/t_test.clj`")))
      (finally (fs/delete-tree out)))))

(deftest a-pass-is-not-started-under-the-margin
  (is (:go? (sr/preflight {:remaining 41.08} :flow 13.66 false)))
  (let [{:keys [go? line]} (sr/preflight {:remaining 41.08} :whole 13.66 false)]
    (is (not go?))
    (is (= "key $41.08 remaining, the last pass cost $13.66, the margin for the whole KIT is $100.00 - not started; top up, or run with --force" line)))
  (testing "the lower of the key's and the account's binds, and is the one named"
    (let [st {:remaining 341.08 :credits {:remaining 51.08}}]
      (is (not (:go? (sr/preflight st :whole 13.66 false))))
      (is (:go? (sr/preflight st :flow 13.66 false)))
      (is (= "account $51.08 remaining (the key has $341.08), the last pass cost $13.66, the margin for a flow is $20.00"
             (:line (sr/preflight st :flow 13.66 false)))))
    (is (str/starts-with? (:line (sr/preflight {:remaining 10.0 :credits {:remaining 60.0}} :flow nil false))
                          "key $10.00 remaining (the account has $60.00)")))
  (is (:go? (sr/preflight {:remaining 5.0} :flow nil true)) "--force runs it, and says so")
  (is (str/includes? (:line (sr/preflight {:remaining 5.0} :flow nil true)) "under it, run by --force"))
  (is (not (:go? (sr/preflight {:unavailable "HTTP 500"} :flow nil false))) "a balance nobody can read is not taken for enough")
  (is (:go? (sr/preflight {:limit nil :credits {:remaining 60.0}} :flow nil false)) "a key with no limit is read by the account's credit")
  (is (not (:go? (sr/preflight {} :flow nil false))) "no figure at all is not enough"))

(deftest every-source-file-is-in-a-flow-beside-the-seams
  ;; Fable 5.1's read of `680f598`: two files had been in no flow but the seams
  (let [flows (remove #(= "seams" (:name %)) (sr/kit-flows))
        files (map #(str "harness/" %) (map str (fs/glob "." "src/**.clj")))]
    (is (empty? (remove (fn [f] (some #(some (fn [p] (str/starts-with? f p)) (:reads %)) flows)) files)))))
