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
      (is (str/includes? l "Anthropic API spend this run $0.25"))
      (is (str/includes? l "no balance endpoint"))))
  (testing "every role through OpenRouter: an Anthropic model's serving provider is not Anthropic API spend"
    ;; The line once summed every provider containing "Anthropic" and called it Anthropic
    ;; spend, on a project whose direct Anthropic spend was $0.00.
    (let [l (balance/line {:roles {:coder {:endpoint "http://127.0.0.1:9/api/v1" :key-env "NOPE_KEY"}}}
                          [{:step/provider "Anthropic" :step/cost 0.18}])]
      (is (not (str/includes? l "Anthropic API spend")))
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
