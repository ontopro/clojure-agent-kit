(ns harness.models.runner-test
  (:require
   [babashka.fs :as fs]
   [babashka.http-client]
   [babashka.process]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.packet :as packet]
   [harness.contract.packet-test :as pt]
   [harness.contract.shapes :as shapes]
   [harness.loop.driver :as driver]
   [harness.models.runner :as runner]
   [harness.models.runner-check :as check]))

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

;; From the KIT's own security review (GPT-6 Astra, the tool-calls flow, 2026-10-21): a provider that
;; echoed the key in a SUCCESSFUL answer - its text, a `note` call's arguments - put it into the history
;; sent back, the notes, the transcript and the event the run's record keeps
(deftest a-key-echoed-in-an-answer-reaches-nothing-the-kit-keeps
  ;; PATH stands in for a key, as in agent-test: always set, and harmless to read
  (let [dir (str (fs/create-temp-dir))
        secret (System/getenv "PATH")
        seen (atom [])
        reply (fn [n auth]
                {:id (str "g" n) :usage {:prompt_tokens 1 :completion_tokens 1 :cost 0}
                 :choices [{:message (if (= n 1)
                                       {:content (str "provider echoes " auth)
                                        :tool_calls [{:id "c1" :type "function"
                                                      :function {:name "note" :arguments (json/generate-string {:text auth})}}]}
                                       {:content "```json\n{\"verdict\":\"approve\",\"reasons\":[]}\n```"})}]})
        cfg {:model "stub" :shape :openai :endpoint "https://provider.invalid" :key-env "PATH"}
        pkt {:task/id "t-secret" :task/title "Review" :task/role :reviewer :repl/worktree dir :files/context []
             :layer/name :service :blueprint/slice {:shapes [] :interfaces []} :review/diff "diff" :review/gate-report {}}]
    (try
      (with-redefs [babashka.http-client/post
                    (fn [_ opts]
                      (let [auth (subs (get-in opts [:headers "Authorization"]) 7)
                            n (count (swap! seen conj (json/parse-string (:body opts) true)))]
                        {:status 200 :body (json/generate-string (reply n auth))}))]
        (let [result (runner/run-agent (runner/api-runner {:roles {:reviewer cfg}}) :reviewer pkt)
              event (driver/dispatch-event :reviewer :reviewer pkt result)]
          (is (= :done (:status result)))
          (is (not (str/includes? (pr-str (second @seen)) secret)) "the history sent back")
          (is (not (str/includes? (pr-str (:notes result)) secret)) "the notes")
          (is (not (str/includes? (pr-str (get-in result [:runner/meta :transcript])) secret)) "the transcript")
          (is (not (str/includes? (pr-str event) secret)) "the event the run's record keeps")))
      (finally (fs/delete-tree dir)))))

;; Argon direct's hypotheses (the tool-calls flow): the snapshot, the triage file section and the record's
;; final/ were read wherever a path or a link led
(deftest a-worktree-snapshot-reads-only-inside-the-worktree
  (let [outer (fs/create-temp-dir {:prefix "kit-snapshot"})
        wt (fs/path outer "wt")]
    (try
      (fs/create-dirs wt)
      (spit (str (fs/path outer "host-secret")) "HOST")
      (babashka.process/shell {:dir (str wt) :out :string :err :string} "git" "init" "-q")
      (spit (str (fs/path wt "mine.clj")) "(ns mine)")
      (fs/create-sym-link (fs/path wt "leak.clj") (fs/path outer "host-secret"))
      (let [snap (runner/worktree-snapshot (str wt))]
        (is (= "(ns mine)" (get snap "mine.clj")))
        (is (not (contains? snap "leak.clj"))))
      (finally (fs/delete-tree outer)))))
