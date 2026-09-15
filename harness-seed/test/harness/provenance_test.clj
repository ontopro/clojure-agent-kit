(ns harness.provenance-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
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

(deftest a-generation-record-also-says-how-it-was-served
  ;; Reasoning tokens and generation time say whether a slow turn was the model
  ;; thinking. The tier is the only field that tells OpenAI's flex endpoint from
  ;; its standard one: the smoke on 2026-09-14 found :provider_name "OpenAI" on
  ;; both, and :service_tier "flex" against "default".
  (let [p (prov/of parsed (assoc (:data record) :native_tokens_reasoning 30
                                 :generation_time 856 :service_tier "flex"))]
    (is (= 30 (:reasoning-tokens p)))
    (is (= 856 (:generation-ms p)))
    (is (= "flex" (:service-tier p))))
  (let [p (prov/of parsed nil)]
    (is (every? nil? ((juxt :reasoning-tokens :generation-ms :service-tier) p))
        "without a record, absent — not zero, not a guess")))

(deftest without-a-record-the-cost-is-nil-and-the-tokens-are-still-real
  ;; The direct-Anthropic path without a priced role. §10 lesson 11: a computed
  ;; cost would look measured and would not be, so with no rates there is nil.
  (let [p (prov/of parsed nil)]
    (is (nil? (:cost p)))
    (is (nil? (:provider p)))
    (is (= 15 (:tokens p)) "from the completion's own usage — measured, just not billed")
    (is (= "deepseek/deepseek-v4-flash" (:model p))
        "the model falls back to what the completion reported")))

(def opus-5 {:in 5 :out 25 :cache-write-5m 6.25 :cache-write-1h 10 :cache-read 0.5})

(deftest list-price-reproduces-the-pricing-pages-worked-examples
  ;; platform.claude.com/docs/en/about-claude/pricing, read 2026-09-14: Opus 5,
  ;; 50,000 input + 15,000 output = $0.625; with 40,000 of the input as cache
  ;; reads = $0.445.
  (is (== 0.625 (prov/list-price {:in 50000 :out 15000} opus-5)))
  (is (< (Math/abs (- 0.445 (prov/list-price {:in 10000 :out 15000 :cache-read 40000} opus-5))) 1e-12))
  (is (== 0.0 (prov/list-price {:in 0 :out 0} opus-5)))
  (testing "cache writes at the 5-minute rate, or split by duration when the usage says"
    (is (< (Math/abs (- 0.000625 (prov/list-price {:in 0 :out 0 :cache-write 100} opus-5))) 1e-12))
    (is (< (Math/abs (- (+ 0.000375 0.0004) (prov/list-price {:in 0 :out 0 :cache-write 100 :cache-write-5m 60 :cache-write-1h 40} opus-5))) 1e-12)))
  (testing "Fable 5.1's cache read is 0.025x, and the rate table says so, not the code"
    (is (< (Math/abs (- 0.0001 (prov/list-price {:in 0 :out 0 :cache-read 400} (assoc opus-5 :cache-read 0.25)))) 1e-12)))
  (testing "nil, not a guess, when a count or a rate is missing"
    (is (nil? (prov/list-price {:in 10} opus-5)))
    (is (nil? (prov/list-price {:in 10 :out 5} (dissoc opus-5 :cache-read))))))

(deftest with-a-priced-role-a-direct-endpoint-gets-a-computed-cost-marked-as-such
  (let [role {:endpoint "https://api.anthropic.com" :pricing {:per-mtok opus-5 :source "s" :as-of "d"}}
        p (prov/of parsed nil role)]
    (is (< (Math/abs (- 0.000175 (:cost p))) 1e-12) "10 in at $5 + 5 out at $25, per million")
    (is (= :list-price (:cost-source p)))
    (is (= "Anthropic API" (:provider p)) "the endpoint's host — where the request went, not a guess")
    (is (= {:in 10 :out 5} (:usage p)) "kept, so the record can re-derive the number"))
  (testing "a reported cost wins and is marked reported"
    (let [p (prov/of parsed (:data record) {:endpoint "https://openrouter.ai/api/v1" :pricing {:per-mtok opus-5 :source "s" :as-of "d"}})]
      (is (= 0.00021 (:cost p)))
      (is (= :reported (:cost-source p)))
      (is (= "DeepInfra" (:provider p)))))
  (testing "no pricing, no cost — and an unknown host is no provider"
    (let [p (prov/of parsed nil {:endpoint "https://127.0.0.1:9/v1"})]
      (is (nil? (:cost p)))
      (is (nil? (:cost-source p)))
      (is (nil? (:provider p))))))

(deftest cached-input-counts-as-tokens-moved
  ;; D18: 10 uncached in, 5,503 written to cache, 12,475 read back, 1,435 out
  ;; — the report said 1,445 tokens for a 19,423-token dispatch.
  (is (= 19423 (:tokens (prov/of {:id "x" :usage {:in 10 :out 1435 :cache-write 5503 :cache-read 12475}} nil))))
  (is (= 15 (:tokens (prov/of {:id "x" :usage {:in 10 :out 5 :cache-write nil :cache-read nil}} nil)))
      "absent cache counts add nothing"))

(deftest a-completion-that-reported-no-usage-reports-no-tokens
  (is (nil? (:tokens (prov/of {:id "x" :usage {}} nil)))
      "nil, not zero — zero is a measurement and this is an absence"))
