(ns harness.agent-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.adapter :as adapter]
   [harness.agent :as agent]
   [org.httpkit.server :as srv]))

(defn- with-stub
  "A stub model server. `responses` is a vector of bodies returned in order;
  the last one repeats. Returns [result requests-seen]."
  [responses f]
  (let [seen (atom [])
        n (atom -1)
        stop (srv/run-server
              (fn [req]
                (let [body (json/parse-string (slurp (:body req)) true)]
                  (swap! seen conj (assoc (select-keys req [:uri]) :body body)))
                (let [i (min (swap! n inc) (dec (count responses)))]
                  {:status 200
                   :headers {"Content-Type" "application/json"}
                   :body (json/generate-string (nth responses i))}))
              {:port 0 :legacy-return-value? false})
        port (srv/server-port stop)]
    (try [(f (str "http://127.0.0.1:" port)) @seen]
         (finally (srv/server-stop! stop)))))

(defn- role [endpoint] {:model "m" :shape :openai :endpoint endpoint})

(defn- workspace []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "src"))
    (spit (str (fs/path dir "src" "in.clj")) "CONTENTS")
    dir))

(defn- text-reply [s]
  {:id "gen-1" :choices [{:message {:content s}}] :usage {:prompt_tokens 1 :completion_tokens 1}})

(defn- tool-reply [nm args]
  {:id "gen-2"
   :choices [{:message {:content nil
                        :tool_calls [{:id "call-1" :type "function"
                                      :function {:name nm :arguments (json/generate-string args)}}]}}]
   :usage {:prompt_tokens 1 :completion_tokens 1}})

;; ---------------------------------------------------------------------------
;; the loop
;; ---------------------------------------------------------------------------

(deftest a-plain-answer-ends-in-one-iteration
  (let [[r reqs] (with-stub [(text-reply "done")]
                   #(agent/converse! (role %) "RULES" "go" {:dir "."}))]
    (is (= :done (:status r)))
    (is (= "done" (:text r)))
    (is (= 1 (:iterations r)))
    (is (= [] (:calls r)))
    (is (= 1 (count reqs)))
    (is (= "RULES" (get-in (first reqs) [:body :messages 0 :content]))
        "the rules reached the model")))

(deftest a-tool-call-is-run-and-its-result-goes-back
  (let [dir (workspace)
        [r reqs] (with-stub [(tool-reply "read_file" {:path "src/in.clj"})
                             (text-reply "I read it")]
                   #(agent/converse! (role %) "RULES" "go" {:dir dir}))]
    (is (= :done (:status r)))
    (is (= 2 (:iterations r)))
    (is (= ["read_file"] (mapv :name (:calls r))))
    (is (= "CONTENTS" (:content (first (:calls r)))))
    (testing "and the second request carries the assistant turn plus the tool result"
      (let [msgs (get-in (second reqs) [:body :messages])]
        (is (= 4 (count msgs)) "system, user, assistant, tool")
        (is (= "assistant" (:role (nth msgs 2))))
        (is (= "tool" (:role (nth msgs 3))))
        (is (= "call-1" (:tool_call_id (nth msgs 3)))
            "matched by id, or the model cannot tell which call this answers")
        (is (= "CONTENTS" (:content (nth msgs 3))))))))

(deftest a-failing-tool-still-continues-the-conversation
  ;; The point of §10 lesson 8: the model gets to read the error and try again.
  (let [[r reqs] (with-stub [(tool-reply "read_file" {:path "nope.clj"})
                             (text-reply "ah, it is missing")]
                   #(agent/converse! (role %) "R" "go" {:dir (workspace)}))]
    (is (= :done (:status r)))
    (is (true? (:error? (first (:calls r)))))
    (is (str/starts-with? (get-in (vec (get-in (second reqs) [:body :messages])) [3 :content])
                          "ERROR: "))))

(deftest the-conversation-keeps-a-turn-per-completion
  ;; NOTES.md row 8: B1's glm-5.3 made 24 completions and left nothing to say
  ;; why. Each completion's text and calls are kept, in order.
  (let [dir (workspace)
        [r _] (with-stub [(tool-reply "read_file" {:path "src/in.clj"})
                          (text-reply "I read it")]
                #(agent/converse! (role %) "RULES" "go" {:dir dir}))]
    (is (= 2 (count (:turns r))))
    (is (= ["read_file"] (mapv :name (:calls (first (:turns r))))))
    (is (= "CONTENTS" (:content (first (:calls (first (:turns r)))))))
    (is (= {:text "I read it" :calls []} (last (:turns r))) "the answer is a turn with no calls"))
  (testing "a capped conversation keeps every turn it made"
    (let [[r _] (with-stub [(tool-reply "read_file" {:path "src/in.clj"})]
                  #(agent/converse! (role %) "R" "go" {:dir (workspace)} {:max-iterations 3}))]
      (is (= 3 (count (:turns r))))))
  (testing "a failed completion keeps the turns before it"
    (let [[r _] (with-stub [(tool-reply "read_file" {:path "src/in.clj"})
                            {:error {:message "boom" :code 500}}]
                  #(agent/converse! (role %) "R" "go" {:dir (workspace)}))]
      (is (= :failed (:status r)))
      (is (= 1 (count (:turns r)))))))

(deftest the-tools-are-declared-on-every-request
  (let [[_ reqs] (with-stub [(text-reply "hi")]
                   #(agent/converse! (role %) "R" "go" {:dir "."}))]
    (is (= 4 (count (get-in (first reqs) [:body :tools]))))))

;; ---------------------------------------------------------------------------
;; the cap
;; ---------------------------------------------------------------------------

(deftest a-model-that-never-stops-is-capped-not-failed
  ;; A capped run may still have written usable files, and the gates are about
  ;; to look at them. Whether that is a failure is the orchestrator's policy.
  (let [dir (workspace)
        [r reqs] (with-stub [(tool-reply "read_file" {:path "src/in.clj"})]
                   #(agent/converse! (role %) "R" "go" {:dir dir}
                                     {:max-iterations 3}))]
    (is (= :done (:status r)) "capped, not failed")
    (is (true? (:capped? r)))
    (is (= 3 (:iterations r)))
    (is (= 3 (count reqs)) "and it really stopped calling")
    (is (= 3 (count (:calls r))))))

;; ---------------------------------------------------------------------------
;; failure
;; ---------------------------------------------------------------------------

(def no-retry {:retry {:attempts 1}})

(deftest an-api-error-ends-the-conversation-with-what-it-said
  (let [stop (srv/run-server (fn [_] {:status 429 :body (json/generate-string
                                                         {:error {:message "slow down"}})})
                             {:port 0 :legacy-return-value? false})
        port (srv/server-port stop)]
    (try
      (let [r (agent/converse! (role (str "http://127.0.0.1:" port)) "R" "go" {:dir "."} no-retry)]
        (is (= :failed (:status r)))
        (is (= 429 (get-in r [:error :status])))
        (is (= "slow down" (get-in r [:error :message]))))
      (finally (srv/server-stop! stop)))))

(deftest an-error-inside-a-200-ends-the-conversation
  ;; Found live: an upstream rate limit as HTTP 200. The loop took it for an
  ;; empty answer and the dispatch reported :done having done nothing.
  (let [[r _] (with-stub [{:id "gen-1" :error {:message "temporarily rate-limited upstream" :code 429}}]
                #(agent/converse! (role %) "R" "go" {:dir "."} no-retry))]
    (is (= :failed (:status r)))
    (is (= 429 (get-in r [:error :status])))))

;; ---------------------------------------------------------------------------
;; retrying a transient error (NOTES.md row 9)
;; ---------------------------------------------------------------------------

(defn- with-statuses
  "A server answering each request with the next of `responses`, each
  `[status body headers]`; the last repeats. Returns [result request-count]."
  [responses f]
  (let [n (atom -1)
        stop (srv/run-server
              (fn [_] (let [[status body headers] (nth responses (min (swap! n inc) (dec (count responses))))]
                        {:status status :headers (merge {"Content-Type" "application/json"} headers)
                         :body (json/generate-string body)}))
              {:port 0 :legacy-return-value? false})]
    (try [(f (str "http://127.0.0.1:" (srv/server-port stop))) (inc @n)]
         (finally (srv/server-stop! stop)))))

(def limited [429 {:error {:message "slow down"}} nil])

(deftest a-rate-limit-is-sent-again-and-the-record-says-how-many-times
  ;; Flex refused 3 of 4 requests inside 75s and each one was a failed
  ;; dispatch. A second request usually gets through.
  (let [[r n] (with-statuses [limited limited [200 (text-reply "ok") nil]]
                #(agent/converse! (role %) "R" "go" {:dir "."} {:retry {:attempts 4 :interval-ms 1}}))]
    (is (= :done (:status r)))
    (is (= "ok" (:text r)))
    (is (= 3 n))
    (is (= 2 (:retries r)) "two requests were sent again; the record keeps that"))
  (testing "retries are summed across the conversation's completions"
    (let [dir (workspace)
          [r n] (with-statuses [limited [200 (tool-reply "read_file" {:path "src/in.clj"}) nil]
                                limited [200 (text-reply "ok") nil]]
                  #(agent/converse! (role %) "R" "go" {:dir dir} {:retry {:attempts 2 :interval-ms 1}}))]
      (is (= :done (:status r)))
      (is (= 4 n))
      (is (= 2 (:retries r)) "one per completion, both counted")))
  (testing "a clean conversation reports zero, not nil"
    (let [[r _] (with-stub [(text-reply "ok")] #(agent/converse! (role %) "R" "go" {:dir "."}))]
      (is (= 0 (:retries r))))))

(deftest attempts-run-out-and-the-last-error-is-the-one-reported
  (let [[r n] (with-statuses [limited] #(agent/converse! (role %) "R" "go" {:dir "."} {:retry {:attempts 3 :interval-ms 1}}))]
    (is (= :failed (:status r)))
    (is (= 429 (get-in r [:error :status])))
    (is (= 3 n) "attempts is the total, not the extra")
    (is (= 2 (:retries r)))))

(deftest a-400-is-not-sent-again
  ;; No credit, a bad request, a refusal: the same request gets the same
  ;; answer, and sending it again spends money on it.
  (let [[r n] (with-statuses [[400 {:error {:message "Your credit balance is too low"}} nil]]
                #(agent/converse! (role %) "R" "go" {:dir "."} {:retry {:attempts 4 :interval-ms 1}}))]
    (is (= :failed (:status r)))
    (is (= 1 n))
    (is (= 0 (:retries r))))
  (is (false? (adapter/transient? {:status 200 :stop-reason "refusal"})))
  (is (true? (adapter/transient? {:status 529})))
  (is (true? (adapter/transient? {:status 0}))))

(deftest retry-after-is-honoured-when-the-host-sends-it
  (is (= 3000 (agent/retry-wait-ms {"retry-after" "3"} 2000 2)))
  (is (= 2000 (agent/retry-wait-ms {} 2000 2)) "else the interval, times the attempts so far")
  (is (= 6000 (agent/retry-wait-ms {} 2000 4)))
  (testing "and the header reaches the wait: a Retry-After of 0 makes the second try immediate"
    (let [t0 (System/currentTimeMillis)
          [r _] (with-statuses [[429 {:error {:message "slow"}} {"Retry-After" "0"}] [200 (text-reply "ok") nil]]
                  #(agent/converse! (role %) "R" "go" {:dir "."} {:retry {:attempts 2 :interval-ms 5000}}))]
      (is (= :done (:status r)))
      (is (< (- (System/currentTimeMillis) t0) 4000) "did not wait the 5s interval"))))

(deftest a-refused-completion-ends-the-conversation-failed
  (let [[r _] (with-stub [{:id "msg_1" :content [] :stop_reason "refusal"
                           :stop_details {:category "cyber"}}]
                #(agent/converse! {:model "m" :shape :anthropic :endpoint %} "R" "go" {:dir "."}))]
    (is (= :failed (:status r)))
    (is (= "refusal" (get-in r [:error :stop-reason])))))

(deftest an-unreachable-endpoint-fails-rather-than-throwing
  (let [r (agent/converse! (role "http://127.0.0.1:1") "R" "go" {:dir "."}
                           {:timeout-ms 300})]
    (is (= :failed (:status r)))
    (is (some? (get-in r [:error :message])))))

;; ---------------------------------------------------------------------------
;; provenance per completion
;; ---------------------------------------------------------------------------

(deftest one-provenance-step-per-completion-not-per-conversation
  ;; A tool loop makes several calls and they can be served by different
  ;; backends. Summing them here would throw away the thing §10 says to log.
  (let [[r _] (with-stub [(tool-reply "read_file" {:path "src/in.clj"})
                          (text-reply "done")]
                #(agent/converse! (role %) "R" "go" {:dir (workspace)}))]
    (is (= 2 (count (:steps r))))
    (is (every? #(= 2 (:tokens %)) (:steps r)) "1 in + 1 out, from the stub")
    (is (every? #(nil? (:cost %)) (:steps r))
        "no generation endpoint on this stub, so no cost — not a zero")))

;; ---------------------------------------------------------------------------
;; where the time goes
;; ---------------------------------------------------------------------------

(defn- with-openrouter
  "A stub shaped like OpenRouter: completions under /api/v1, and a generation
  record for each that 404s `lag` times before it appears — the asynchronous
  write D1 measured at 8.6s. `responses` are bodies in order, the last one
  repeating; an entry `{::status n ::body m}` is returned with that status.
  Each completion gets its own id, so each record lags on its own."
  [responses lag f]
  (let [n (atom -1)
        polls (atom {})
        stop (srv/run-server
              (fn [req]
                (if (str/ends-with? (:uri req) "/generation")
                  (let [id (second (re-find #"id=([^&]+)" (str (:query-string req))))]
                    (if (<= (get (swap! polls update id (fnil inc 0)) id) lag)
                      {:status 404 :body "{}"}
                      {:status 200 :headers {"Content-Type" "application/json"}
                       :body (json/generate-string
                              {:data {:id id :model "m-resolved" :provider_name "P"
                                      :total_cost 0.001 :native_tokens_prompt 3
                                      :native_tokens_completion 4 :native_tokens_reasoning 2
                                      :generation_time 5 :service_tier "flex"}})}))
                  (let [i (swap! n inc)
                        r (nth responses (min i (dec (count responses))))]
                    (if (::status r)
                      {:status (::status r) :body (json/generate-string (::body r))}
                      {:status 200 :headers {"Content-Type" "application/json"}
                       :body (json/generate-string (assoc r :id (str "gen-" i)))}))))
              {:port 0 :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (srv/server-port stop) "/api/v1"))
         (finally (srv/server-stop! stop)))))

(deftest waiting-on-the-generation-record-is-not-time-in-the-model
  ;; D7's Tester spent 21.8s an iteration against the Coder's 4.7s, sending
  ;; fewer tokens, and the dispatch's total was the only number there was.
  (let [dir (workspace)
        r (with-openrouter [(tool-reply "read_file" {:path "src/in.clj"}) (text-reply "done")] 3
            #(agent/converse! (role %) "R" "go" {:dir dir} {:interval-ms 100}))]
    (is (= 2 (count (:steps r))))
    (doseq [s (:steps r)]
      (is (>= (:ms/provenance s) 300) "three 404s, 100ms apart, are provenance time")
      (is (< (:ms/completion s) (:ms/provenance s)) "and are not counted as the model's"))
    (is (nat-int? (:ms (first (:calls r)))) "a tool call is timed too")
    (is (every? #(= "flex" (:service-tier %)) (:steps r)))))

(deftest generation-records-are-fetched-beside-the-loop-not-in-it
  ;; Measured 2026-09-14: 6.8–10.5s waiting per completion, 84–88% of a tool
  ;; round trip. Three completions whose records each lag 600ms took at least
  ;; 1800ms when the loop waited on each; fetched beside it they overlap.
  (let [dir (workspace)
        t0 (System/currentTimeMillis)
        r (with-openrouter [(tool-reply "read_file" {:path "src/in.clj"})
                            (tool-reply "read_file" {:path "src/in.clj"})
                            (text-reply "done")]
            3
            #(agent/converse! (role %) "R" "go" {:dir dir} {:interval-ms 200}))
        wall (- (System/currentTimeMillis) t0)]
    (is (= 3 (count (:steps r))))
    (is (< wall 1200) (str "the three 600ms lags overlapped rather than queued — took " wall "ms"))
    (testing "and nothing about the steps changed"
      (is (every? #(= "P" (:provider %)) (:steps r)))
      (is (every? #(= 0.001 (:cost %)) (:steps r)))
      (is (= ["gen-0" "gen-1" "gen-2"] (mapv :generation-id (:steps r)))
          "in completion order, not in the order the records arrived"))
    (is (pos? (:ms-provenance-wait r)) "the end still waits for the records, once")))

(deftest a-conversation-that-fails-still-reports-what-it-paid-for
  ;; The early return on an API error is an exit too. Without settling there,
  ;; a completion already paid for would lose its cost, and its fetch would
  ;; still be polling after the dispatch was over.
  (let [dir (workspace)
        r (with-openrouter [(tool-reply "read_file" {:path "src/in.clj"})
                            {::status 500 ::body {:error {:message "boom"}}}]
            2
            #(agent/converse! (role %) "R" "go" {:dir dir} {:interval-ms 100}))]
    (is (= :failed (:status r)))
    (is (= 1 (count (:steps r))) "the one completion that succeeded")
    (is (= 0.001 (:cost (first (:steps r)))) "with its cost")))
