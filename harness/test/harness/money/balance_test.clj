(ns harness.money.balance-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.money.balance :as balance]
   [org.httpkit.server :as srv]))

(deftest spend-is-summed-per-provider-and-a-costless-step-counts-for-nothing
  (is (= {"Anthropic API" 0.75 "OpenAI" 0.1}
         (balance/spend-by-provider [{:step/provider "Anthropic API" :step/cost 0.25}
                                     {:step/provider "Anthropic API" :step/cost 0.5}
                                     {:step/provider "OpenAI" :step/cost 0.1}
                                     {:step/provider "Google" :step/cost nil}
                                     {:step/kind :gate}]))))

(deftest the-line-says-which-half-is-a-balance-and-which-is-spend
  (testing "no OpenRouter role in the profile"
    (let [l (balance/line {:roles {:coder {:endpoint "https://api.anthropic.com"}}}
                          [{:step/provider "Anthropic API" :step/cost 0.25}])]
      (is (str/includes? l "OpenRouter not in this profile"))
      (is (str/includes? l "direct spend this run: Anthropic API $0.25"))
      (is (str/includes? l "no balance endpoint"))))
  (testing "every direct provider the roles reach is named, each with its spend"
    ;; a project's Coder went to Gemini direct and the line named Anthropic's spend alone
    (let [l (balance/line {:roles {:coder {:endpoint "https://generativelanguage.googleapis.com/v1beta/openai"}
                                   :reviewer {:endpoint "https://api.anthropic.com"}}}
                          [{:step/provider "Google AI API" :step/cost 0.4} {:step/provider "Anthropic API" :step/cost 0.1}])]
      (is (str/includes? l "direct spend this run: Anthropic API $0.10, Google AI API $0.40"))))
  (testing "a security reviewer panel's member is a block like any role's"
    (let [l (balance/line {:roles {:coder {:endpoint "https://api.anthropic.com"}}
                           :security-panel [{:model "g" :endpoint "https://generativelanguage.googleapis.com/v1beta/openai"}]}
                          [{:step/provider "Google AI API" :step/cost 0.4}])]
      (is (str/includes? l "Google AI API $0.40"))))
  (testing "every role through OpenRouter: an Anthropic model's serving provider is not Anthropic API spend"
    ;; The line once summed every provider containing "Anthropic" and called it Anthropic
    ;; spend, on a project whose direct Anthropic spend was $0.00.
    (let [l (balance/line {:roles {:coder {:endpoint "http://127.0.0.1:9/api/v1" :key-env "NOPE_KEY"}}}
                          [{:step/provider "Anthropic" :step/cost 0.18}])]
      (is (not (str/includes? l "direct spend")))
      (is (str/includes? l "the key's usage is the whole spend"))))
  (testing "an OpenRouter key that answers, and the account's credit beside it"
    ;; Two endpoints, two facts: the key's limit and the account's credit. A key limited
    ;; to more than the account holds stops at the account's figure, and the line once
    ;; showed the key's alone and called it the balance.
    (let [stop (srv/run-server (fn [req]
                                 {:status 200 :headers {"Content-Type" "application/json" "Connection" "close"}
                                  :body (json/generate-string
                                         (if (str/ends-with? (:uri req) "/credits")
                                           {:data {:total_credits 30.0 :total_usage 12.5}}
                                           {:data {:limit 40.0 :usage 16.78 :limit_remaining 23.22}}))})
                               {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
      (try
        (let [ep (str "http://127.0.0.1:" (srv/server-port stop) "/openrouter/api/v1")
              status (balance/openrouter-key-status ep "PATH")]
          ;; PATH is set in every environment; the value is never printed, only sent
          (is (= {:limit 40.0 :usage 16.78 :remaining 23.22
                  :credits {:total 30.0 :usage 12.5 :remaining 17.5}} status))
          (let [l (balance/line {:roles {:coder {:endpoint ep :key-env "PATH"}}} [])]
            (is (str/includes? l "key $23.22 remaining of its $40.00 limit"))
            (is (str/includes? l "account credit $17.50 remaining of $30.00 bought"))))
        (finally @(srv/server-stop! stop)))))
  (testing "the key answers and the credits endpoint does not: the key's figures alone, and said so"
    (let [stop (srv/run-server (fn [req]
                                 (if (str/ends-with? (:uri req) "/credits")
                                   {:status 404 :body ""}
                                   {:status 200 :headers {"Content-Type" "application/json" "Connection" "close"}
                                    :body (json/generate-string {:data {:limit 40.0 :usage 16.78 :limit_remaining 23.22}})}))
                               {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
      (try
        (let [ep (str "http://127.0.0.1:" (srv/server-port stop) "/openrouter/api/v1")
              status (balance/openrouter-key-status ep "PATH")]
          (is (= {:limit 40.0 :usage 16.78 :remaining 23.22} status) "no :credits key at all")
          (is (str/includes? (balance/line {:roles {:coder {:endpoint ep :key-env "PATH"}}} []) "account credit unavailable")))
        (finally @(srv/server-stop! stop)))))
  (testing "an OpenRouter key that does not answer is unavailable, not an error"
    (is (:unavailable (balance/openrouter-key-status "http://127.0.0.1:1/api/v1" "PATH")))
    (is (= {:unavailable "NO_SUCH_VAR_HERE is unset"} (balance/openrouter-key-status "http://x" "NO_SUCH_VAR_HERE")))))

(deftest the-profile-argument-is-optional-and-told-from-a-record-by-its-shape
  ;; A stop's next-steps line says `bb balance` alone; from a workspace's clone that must
  ;; mean the project's profile, and a record given first must not be read as one.
  ;; The workspace's profile is passed in, not looked up: this test used to call the
  ;; one-argument form and assert "no profile outside a workspace", which held in the KIT's
  ;; development folder and failed in every workspace's clone - the fifth project ran
  ;; `bb gates` there, as the README says to, and the test gate was red on nothing.
  (let [dir (fs/create-temp-dir)
        prof (str (fs/path dir "profile.edn"))
        ws-prof (str (fs/path dir "ws-profile.edn"))
        rec (str (fs/path dir "r1.edn"))]
    (spit prof (pr-str {:seat :claude :roles {}}))
    (spit rec (pr-str {:run/id "r1" :run/steps []}))
    (is (= [prof [rec]] (balance/split-args [prof rec] ws-prof)) "a profile first, records after, whatever the workspace has")
    (is (= [nil [rec]] (balance/split-args [rec] nil))
        "a record alone is a record, and with no workspace profile there is nothing to fall back on")
    (is (= [ws-prof [rec]] (balance/split-args [rec] ws-prof)) "and with one, it is the profile")
    (is (= [nil []] (balance/split-args [] nil)))
    (is (= [ws-prof []] (balance/split-args [] ws-prof)) "`bb balance` alone, from a workspace's clone")))

(deftest a-provider-figure-left-out-is-named-and-never-computed-with
  (testing "an answer is read as its data, or as unavailable with the reason"
    (is (= {:limit 1} (balance/read-data {:status 200 :body "{\"data\":{\"limit\":1}}"})))
    (is (= {:unavailable "HTTP 200, a body that is not JSON"} (balance/read-data {:status 200 :body "<html>busy</html>"})))
    (is (= {:unavailable "HTTP 200, no data in the answer"} (balance/read-data {:status 200 :body "{\"error\":\"x\"}"})))
    (is (= {:unavailable "HTTP 200, no data in the answer"} (balance/read-data {:status 200 :body nil})))
    (is (= {:unavailable "HTTP 503"} (balance/read-data {:status 503 :body "<html/>"})))
    (is (= {:unavailable "no answer: request timed out"} (balance/read-data {:status 0 :reason "request timed out"})))
    (is (= {:unavailable "no answer"} (balance/read-data {}))))
  (testing "a key with no limit says so, never \"remaining of its — limit\""
    (is (= "key with no limit ($142.45 used)" (balance/key-text {:limit nil :usage 142.45 :remaining nil})))
    (is (= "key $7.70 remaining of its $150.00 limit ($142.30 used)"
           (balance/key-text {:limit 150 :usage 142.3 :remaining 7.7})))
    (is (= "key with no limit (— used)" (balance/key-text {}))))
  (testing "a key that answers with no limit and figures that are not numbers"
    (let [stop (srv/run-server (fn [req]
                                 {:status 200 :headers {"Content-Type" "application/json" "Connection" "close"}
                                  :body (json/generate-string
                                         (if (str/ends-with? (:uri req) "/credits")
                                           {:data {:total_credits "170" :total_usage 142.45}}
                                           {:data {:limit nil :usage 142.45 :limit_remaining nil}}))})
                               {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
      (try
        (let [ep (str "http://127.0.0.1:" (srv/server-port stop) "/openrouter/api/v1")]
          (is (= {:limit nil :usage 142.45 :remaining nil :credits {:total nil :usage 142.45 :remaining nil}}
                 (balance/openrouter-key-status ep "PATH")))
          (is (str/includes? (balance/line {:roles {:coder {:endpoint ep :key-env "PATH"}}} [])
                             "OpenRouter key with no limit ($142.45 used); account credit — remaining of — bought")))
        (finally @(srv/server-stop! stop)))))
  (testing "a key endpoint answering a page that is not JSON is unavailable, and the line still prints"
    (let [stop (srv/run-server (fn [_] {:status 200 :headers {"Connection" "close"} :body "<html>gateway</html>"})
                               {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
      (try
        (let [ep (str "http://127.0.0.1:" (srv/server-port stop) "/openrouter/api/v1")]
          (is (= {:unavailable "HTTP 200, a body that is not JSON"} (balance/openrouter-key-status ep "PATH")))
          (is (str/includes? (balance/line {:roles {:coder {:endpoint ep :key-env "PATH"}}} [])
                             "OpenRouter unavailable (HTTP 200, a body that is not JSON)")))
        (finally @(srv/server-stop! stop))))))
