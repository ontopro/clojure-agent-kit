(ns harness.adapter-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [harness.adapter :as adapter]))

(def openai-role
  {:family :deepseek :model "deepseek/deepseek-v4-flash" :shape :openai
   :endpoint "https://openrouter.ai/api/v1"
   :params {:reasoning_effort "high" :provider {:allow_fallbacks false}}})

(def anthropic-role
  {:family :anthropic :model "claude-sonnet-5" :shape :anthropic
   :endpoint "https://api.anthropic.com"})

(def msgs [{:role "user" :content "hello"}])

;; ---------------------------------------------------------------------------
;; requests
;; ---------------------------------------------------------------------------

(deftest the-system-prompt-goes-where-each-api-expects-it
  ;; The difference that makes one adapter impossible. Anthropic ACCEPTS a
  ;; message with role "system" and ignores it, so getting this wrong means the
  ;; rules silently never reach the model — the worst available failure.
  (testing "openai: a message"
    (let [{:keys [body]} (adapter/request openai-role "RULES" msgs nil)]
      (is (= {:role "system" :content "RULES"} (first (:messages body))))
      (is (= 2 (count (:messages body))))
      (is (nil? (:system body)))))
  (testing "anthropic: a top-level parameter"
    (let [{:keys [body]} (adapter/request anthropic-role "RULES" msgs nil)]
      (is (= "RULES" (:system body)))
      (is (= msgs (:messages body)) "and the messages are untouched")
      (is (not-any? #(= "system" (:role %)) (:messages body))))))

(deftest each-api-gets-its-own-auth-header-and-url
  ;; PATH stands in for a key variable: it is always set, so this tests the
  ;; header shape without depending on a real key being present.
  (let [o (adapter/request (assoc openai-role :key-env "PATH") "s" msgs nil)
        a (adapter/request (assoc anthropic-role :key-env "PATH") "s" msgs nil)]
    (is (= "https://openrouter.ai/api/v1/chat/completions" (:url o)))
    (is (= "https://api.anthropic.com/v1/messages" (:url a)))
    (is (contains? (:headers a) "anthropic-version")
        "required, and pinned rather than left to drift")
    (is (not (contains? (:headers o) "anthropic-version")))))

(deftest an-unset-key-variable-throws-naming-the-variable-not-the-key
  (let [e (try (adapter/request (assoc openai-role :key-env "HARNESS_DEFINITELY_UNSET")
                                "s" msgs nil)
               (catch Exception e (ex-data e)))]
    (is (= :key-env-unset (:harness/error e)))
    (is (= "HARNESS_DEFINITELY_UNSET" (:key-env e)))
    (is (not (contains? e :key)) "an ex-info gets logged; a key must never be in one")))

(deftest a-role-with-no-key-variable-sends-no-auth
  ;; A local model server needs none, and inventing an empty Bearer header for
  ;; one is how a working local endpoint starts returning 401.
  (is (= {"Content-Type" "application/json"}
         (:headers (adapter/request openai-role "s" msgs nil)))))

(deftest profile-params-ride-into-the-body
  (let [{:keys [body]} (adapter/request openai-role "s" msgs nil)]
    (is (= "high" (:reasoning_effort body)))
    (is (= {:allow_fallbacks false} (:provider body))
        "request-side pinning: the provider you logged yesterday is not the one
         you get today without it")))

(deftest anthropic-requires-max-tokens-so-it-always-has-one
  ;; The API rejects a request without it. A default that can be overridden from
  ;; :params beats a required key every profile must remember.
  (is (= 4096 (:max_tokens (:body (adapter/request anthropic-role "s" msgs nil)))))
  (is (= 99 (:max_tokens (:body (adapter/request (assoc anthropic-role :params {:max_tokens 99})
                                                 "s" msgs nil)))))
  (is (not (contains? (:body (adapter/request (assoc anthropic-role :params {:max_tokens 99})
                                              "s" msgs nil))
                      :params))
      "params are merged, not nested"))

(deftest tools-are-omitted-when-there-are-none
  ;; Some endpoints reject an empty tools array rather than ignoring it.
  (is (not (contains? (:body (adapter/request openai-role "s" msgs [])) :tools)))
  (is (contains? (:body (adapter/request openai-role "s" msgs [{:name "f"}])) :tools)))

;; ---------------------------------------------------------------------------
;; responses
;; ---------------------------------------------------------------------------

(def openai-text
  {:id "gen-abc"
   :model "deepseek/deepseek-v4-flash"
   :choices [{:message {:content "hi" :tool_calls nil}}]
   :usage {:prompt_tokens 10 :completion_tokens 5}})

(def openai-tools
  {:id "gen-def"
   :choices [{:message {:content nil
                        :tool_calls [{:id "call_1"
                                      :function {:name "read_file"
                                                 :arguments "{\"path\":\"a.clj\"}"}}]}}]
   :usage {:prompt_tokens 20 :completion_tokens 8}})

(def anthropic-tools
  {:id "msg_xyz"
   :stop_reason "tool_use"
   :content [{:type "text" :text "let me look"}
             {:type "tool_use" :id "toolu_1" :name "read_file"
              :input {:path "a.clj"}}]
   :usage {:input_tokens 30 :output_tokens 12}})

(deftest both-shapes-parse-to-one-internal-shape
  ;; harness.tools must never learn which family it is serving.
  (let [o (adapter/parse :openai openai-tools)
        a (adapter/parse :anthropic anthropic-tools)]
    (is (= :tool-use (:stop o)))
    (is (= :tool-use (:stop a)))
    (is (= ["read_file"] (mapv :name (:tool-calls o))))
    (is (= ["read_file"] (mapv :name (:tool-calls a))))
    (is (= {:in 20 :out 8} (:usage o)))
    (is (= {:in 30 :out 12} (:usage a)))))

(deftest a-plain-answer-is-not-a-tool-use
  (let [o (adapter/parse :openai openai-text)]
    (is (= :end (:stop o)))
    (is (= "hi" (:text o)))
    (is (= [] (:tool-calls o)))
    (is (= "gen-abc" (:id o)) "the id the generation call needs")))

(deftest anthropic-text-and-tool-blocks-are-interleaved-not-separate
  ;; The shape OpenRouter's normalisation hides: one content array carrying
  ;; both, rather than a message plus a tool_calls array.
  (let [a (adapter/parse :anthropic anthropic-tools)]
    (is (= "let me look" (:text a)) "text blocks concatenated, tool blocks skipped")
    (is (= {:path "a.clj"} (:args (first (:tool-calls a))))
        "already-decoded input, passed through untouched")))

(deftest tool-arguments-are-passed-through-in-whatever-shape-they-arrived
  ;; OpenAI hands back a JSON STRING, Anthropic a decoded map. Decoding here
  ;; would mean this namespace owning a failure mode that belongs to the tool.
  (is (string? (:args (first (:tool-calls (adapter/parse :openai openai-tools))))))
  (is (map? (:args (first (:tool-calls (adapter/parse :anthropic anthropic-tools)))))))

(deftest an-api-error-is-data-not-a-throw
  ;; A 429 is a normal event in a loop that retries; the caller decides.
  (is (nil? (adapter/error 200 {})))
  (let [e (adapter/error 429 {:error {:message "rate limited"}})]
    (is (= :api-error (:harness/error e)))
    (is (= 429 (:status e)))
    (is (= "rate limited" (:message e))))
  (is (= "no message" (:message (adapter/error 500 {})))))
