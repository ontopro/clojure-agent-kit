(ns harness.provenance-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [harness.provenance :as prov]
   [org.httpkit.server :as srv]))

(defn- with-stub
  "Run `f` against a stub HTTP server, returning [result requests].

  A real server on a real socket rather than a mocked http/get: the thing most
  likely to be wrong is the request actually put on the wire — a query
  parameter, a header — and a mock asserts only what the mock was told to."
  [handler f]
  (let [seen (atom [])
        stop (srv/run-server (fn [req] (swap! seen conj req) (handler req))
                             {:port 0 :legacy-return-value? false})
        port (srv/server-port stop)]
    (try [(f (str "http://127.0.0.1:" port "/api/v1")) @seen]
         (finally (srv/server-stop! stop)))))

(defn- json-ok [m]
  {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string m)})

(def record
  {:data {:id "gen-abc" :model "deepseek/deepseek-v4-flash"
          :provider_name "DeepInfra" :total_cost 0.00021
          :native_tokens_prompt 120 :native_tokens_completion 45}})

;; ---------------------------------------------------------------------------
;; which endpoints have one at all
;; ---------------------------------------------------------------------------

(deftest only-an-openrouter-shaped-endpoint-has-a-generation-record
  ;; Keyed off the API path, not a hostname allow-list: a self-hosted proxy
  ;; speaking the same API is a real deployment.
  (is (= "https://openrouter.ai/api/v1/generation"
         (prov/generation-endpoint "https://openrouter.ai/api/v1")))
  (is (= "https://openrouter.ai/api/v1/generation"
         (prov/generation-endpoint "https://openrouter.ai/api/v1/"))
      "a trailing slash must not produce a double one")
  (is (nil? (prov/generation-endpoint "https://api.anthropic.com"))
      "direct Anthropic has none, which is why its cost is nil and not guessed")
  (is (nil? (prov/generation-endpoint nil))))

(deftest no-id-means-no-call
  (is (nil? (prov/fetch! "https://openrouter.ai/api/v1" nil "k"))))

;; ---------------------------------------------------------------------------
;; the call
;; ---------------------------------------------------------------------------

(deftest the-id-goes-in-the-query-and-the-key-in-the-header
  (let [[result reqs] (with-stub (fn [_] (json-ok record))
                        #(prov/fetch! % "gen-abc" "secret-key"))]
    (is (= "DeepInfra" (:provider_name result)))
    (is (= "/api/v1/generation" (:uri (first reqs))))
    (is (= "id=gen-abc" (:query-string (first reqs))))
    (is (= "Bearer secret-key" (get-in (first reqs) [:headers "authorization"])))))

(deftest a-lagging-record-is-retried-and-then-succeeds
  ;; The record is written asynchronously, so a query issued immediately can
  ;; 404 on an id that is perfectly valid.
  (let [n (atom 0)
        [result reqs] (with-stub (fn [_] (if (< (swap! n inc) 3)
                                           {:status 404 :body "{}"}
                                           (json-ok record)))
                        #(prov/fetch! % "gen-abc" "k" {:interval-ms 1}))]
    (is (= 0.00021 (:total_cost result)))
    (is (= 3 (count reqs)) "two failures then a success")))

(deftest a-record-that-never-arrives-yields-nil-not-a-throw
  ;; The dispatch already succeeded and was already paid for. Failing it now
  ;; because the bookkeeping call did not answer throws away the completion.
  (let [[result reqs] (with-stub (fn [_] {:status 404 :body "{}"})
                        #(prov/fetch! % "gen-abc" "k" {:attempts 2 :interval-ms 1}))]
    (is (nil? result))
    (is (= 2 (count reqs)) "it gives up rather than retrying forever")))

(deftest a-dead-endpoint-yields-nil-not-a-throw
  (is (nil? (prov/fetch! "http://127.0.0.1:1/api/v1" "gen-abc" "k"
                         {:attempts 1 :timeout-ms 300}))))

;; ---------------------------------------------------------------------------
;; what gets recorded
;; ---------------------------------------------------------------------------

(def parsed
  {:id "gen-abc" :usage {:in 10 :out 5} :raw {:model "deepseek/deepseek-v4-flash"}})

(deftest a-generation-record-supplies-cost-provider-and-native-tokens
  (let [p (prov/of parsed (:data record))]
    (is (= "DeepInfra" (:provider p)))
    (is (= 0.00021 (:cost p)))
    (is (= 165 (:tokens p)) "native counts, which are what the host actually billed")
    (is (= "gen-abc" (:generation-id p)))))

(deftest without-a-record-the-cost-is-nil-and-the-tokens-are-still-real
  ;; The direct-Anthropic path. §10 lesson 11: a computed cost would look
  ;; measured and would not be, so there is no price table anywhere here.
  (let [p (prov/of parsed nil)]
    (is (nil? (:cost p)))
    (is (nil? (:provider p)))
    (is (= 15 (:tokens p)) "from the completion's own usage — measured, just not billed")
    (is (= "deepseek/deepseek-v4-flash" (:model p))
        "the model falls back to what the completion reported")))

(deftest a-completion-that-reported-no-usage-reports-no-tokens
  (is (nil? (:tokens (prov/of {:id "x" :usage {}} nil)))
      "nil, not zero — zero is a measurement and this is an absence"))
