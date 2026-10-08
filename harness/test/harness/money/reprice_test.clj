(ns harness.money.reprice-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.shapes :as shapes]
   [harness.models.provenance :as provenance]
   [harness.money.reprice :as reprice]
   [org.httpkit.server :as srv]))

(defn- with-stub
  "A stub generation endpoint: `records` is {id generation-map}; an unknown id
  is a 404, as OpenRouter answers one that has not been written yet."
  [records f]
  (let [seen (atom [])
        stop (srv/run-server
              (fn [req]
                (let [id (second (re-find #"id=([^&]+)" (str (:query-string req))))]
                  (swap! seen conj {:id id :auth (get-in req [:headers "authorization"])})
                  (if-let [g (get records id)]
                    {:status 200 :headers {"Content-Type" "application/json" "Connection" "close"}
                     :body (json/generate-string {:data (assoc g :id id)})}
                    {:status 404 :body "{}"})))
              {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        port (srv/server-port stop)]
    (try [(f (str "http://127.0.0.1:" port "/api/v1")) @seen]
         (finally @(srv/server-stop! stop)))))

(def fast {:attempts 1 :interval-ms 1 :timeout-ms 2000})

(defn- record [endpoint]
  {:run/id "w7" :task/id "t" :run/attempts 1 :run/status :merged :run/started-at (java.util.Date.)
   :run/cost 0.2
   :run/roles {:coder {:model "m" :family :anthropic :endpoint endpoint :key-env "REPRICE_TEST_KEY"}
               :tester {:model "t" :family :google :endpoint endpoint :key-env "REPRICE_TEST_KEY"}}
   :run/steps [{:step/name :provision :step/kind :provision :step/status :done :step/ms 10 :step/source :measured}
               {:step/name :coder :step/kind :dispatch :step/status :done :step/ms 500 :step/source :measured
                :step/model "m" :step/provider "Anthropic" :step/cost nil :step/cost-source :reported
                :step/tokens 100 :step/generation-ids ["gen-a" "gen-b"]}
               {:step/name :tester :step/kind :dispatch :step/status :done :step/ms 400 :step/source :measured
                :step/model "t" :step/provider "Google" :step/cost 0.2 :step/cost-source :reported
                :step/tokens 50 :step/generation-ids ["gen-c"]}]})

(deftest an-unpriced-step-is-priced-from-every-one-of-its-generation-records
  (let [[{:keys [record changed lines]} seen]
        (with-stub {"gen-a" {:total_cost 0.25} "gen-b" {:total_cost 0.125} "gen-c" {:total_cost 99}}
          #(reprice/reprice (record %) {:fetch-opts fast :getenv {"REPRICE_TEST_KEY" "sk-test"}}))
        coder (nth (:run/steps record) 1)]
    (is (= 1 changed))
    (is (= 0.375 (:step/cost coder)) "the sum over both ids")
    (is (= :repriced (:step/cost-source coder)) "the endpoint's word, fetched later - not a list price")
    (is (= 0.575 (:run/cost record)) "and the run's cost is the steps' sum again")
    (is (= 0.2 (:step/cost (nth (:run/steps record) 2))) "a priced step is not touched")
    (is (= ["gen-a" "gen-b"] (map :id seen)) "only the unpriced step's ids were fetched")
    (is (every? #(= "Bearer sk-test" (:auth %)) seen) "with the key the role's variable names")
    (is (shapes/valid-run? record) (pr-str (shapes/explain-run record)))
    (is (= 1 (count lines)))
    (is (str/includes? (first lines) "coder: 2 generation records fetched, cost $0.375000 (was —)"))))

(deftest a-repriced-step-gets-its-provider-and-tokens-where-it-had-none
  ;; A dispatch whose record lagged has no provider and no tokens either; the
  ;; generation record names who served it and what it billed. A value the
  ;; dispatch did record is never replaced.
  (let [bare (fn [ep] (-> (record ep)
                          (assoc-in [:run/steps 1 :step/provider] nil)
                          (assoc-in [:run/steps 1 :step/tokens] nil)))
        [{:keys [record]} _]
        (with-stub {"gen-a" {:total_cost 0.25 :provider_name "Anthropic" :native_tokens_prompt 1000 :native_tokens_completion 200}
                    "gen-b" {:total_cost 0.125 :provider_name "Google Vertex" :native_tokens_prompt 500 :native_tokens_completion 50}}
          #(reprice/reprice (bare %) {:fetch-opts fast :getenv {}}))
        coder (nth (:run/steps record) 1)]
    (is (= "Anthropic / Google Vertex" (:step/provider coder)) "both hosts, since the completions were split")
    (is (= 1750 (:step/tokens coder)) "the native counts, summed")
    (is (shapes/valid-run? record) (pr-str (shapes/explain-run record))))
  (testing "a step that recorded a provider keeps it"
    (let [[{:keys [record]} _]
          (with-stub {"gen-a" {:total_cost 0.1 :provider_name "Other" :native_tokens_prompt 1}
                      "gen-b" {:total_cost 0.1 :provider_name "Other" :native_tokens_prompt 1}}
            #(reprice/reprice (record %) {:fetch-opts fast :getenv {}}))]
      (is (= "Anthropic" (:step/provider (nth (:run/steps record) 1))))
      (is (= 100 (:step/tokens (nth (:run/steps record) 1))))))
  (testing "a step priced by the old command - a cost, no provider, no tokens - is a target, and its cost is left as it was"
    (let [old (fn [ep] (-> (record ep)
                           (assoc-in [:run/steps 1 :step/cost] 0.3)
                           (assoc-in [:run/steps 1 :step/provider] nil)
                           (assoc-in [:run/steps 1 :step/tokens] nil)))]
      (is (= [:coder] (map :step/name (reprice/unpriced (old "http://x/api/v1")))))
      (let [[{:keys [record changed lines]} _]
            (with-stub {"gen-a" {:total_cost 0.25 :provider_name "Anthropic" :native_tokens_prompt 10}
                        "gen-b" {:total_cost 0.125 :provider_name "Anthropic" :native_tokens_prompt 10}}
              #(reprice/reprice (old %) {:fetch-opts fast :getenv {}}))
            coder (nth (:run/steps record) 1)]
        (is (= 1 changed))
        (is (= 0.3 (:step/cost coder)) "the cost it had, not the fetched sum")
        (is (= :reported (:step/cost-source coder)))
        (is (= "Anthropic" (:step/provider coder)))
        (is (= 20 (:step/tokens coder)))
        (is (str/includes? (first lines) "provider Anthropic, 20 tokens (was —)")))))
  (testing "no native counts on one record, no tokens filled"
    (let [bare (fn [ep] (assoc-in (record ep) [:run/steps 1 :step/tokens] nil))
          [{:keys [record]} _]
          (with-stub {"gen-a" {:total_cost 0.1 :native_tokens_prompt 1} "gen-b" {:total_cost 0.1}}
            #(reprice/reprice (bare %) {:fetch-opts fast :getenv {}}))]
      (is (nil? (:step/tokens (nth (:run/steps record) 1))))
      (is (= 0.2 (:step/cost (nth (:run/steps record) 1))) "the cost is still filled"))))

(deftest a-step-with-one-record-missing-is-left-exactly-as-it-was
  (let [[{:keys [record changed lines]} _]
        (with-stub {"gen-a" {:total_cost 0.25}}
          #(reprice/reprice (record %) {:fetch-opts fast :getenv {}}))]
    (is (zero? changed))
    (is (nil? (:step/cost (nth (:run/steps record) 1))) "a partial sum is not a cost")
    (is (= 0.2 (:run/cost record)) "the run's cost stays")
    (is (str/includes? (first lines) "1 of 2 generation records answered; gen-b did not - left unpriced"))))

(deftest a-record-that-kept-no-endpoint-takes-one-from-a-profile
  (let [bare (fn [ep] (update (record ep) :run/roles (fn [rs] (into {} (for [[k v] rs] [k (dissoc v :endpoint :key-env)])))))]
    (let [[{:keys [changed lines]} _] (with-stub {} #(reprice/reprice (bare %) {:fetch-opts fast}))]
      (is (zero? changed))
      (is (str/includes? (first lines) "no endpoint on the record's :run/roles for coder - pass --profile")))
    (let [[{:keys [changed]} _]
          (with-stub {"gen-a" {:total_cost 0.1} "gen-b" {:total_cost 0.1}}
            #(reprice/reprice (bare %) {:fetch-opts fast :getenv {}
                                        :profile {:roles {:coder {:endpoint % :key-env "X"}}}}))]
      (is (= 1 changed)))))

(deftest an-endpoint-with-no-generation-record-is-said-not-fetched
  (let [{:keys [changed lines]} (reprice/reprice (record "https://api.anthropic.com") {:fetch-opts fast :getenv {}})]
    (is (zero? changed))
    (is (str/includes? (first lines) "has no generation record to fetch"))))

(deftest nothing-to-reprice-when-no-unpriced-step-carries-an-id
  (let [r (update (record "http://x/api/v1") :run/steps (fn [ss] (mapv #(dissoc % :step/generation-ids) ss)))]
    (is (empty? (reprice/unpriced r)) "a record from before the ids were kept")
    (is (empty? (reprice/unpriced (assoc-in (record "http://x/api/v1") [:run/steps 1 :step/cost] 0.3)))
        "priced, with its provider and tokens: nothing to do")))

(deftest a-readings-record-is-priced-by-its-own-ids-with-nothing-but-the-file
  ;; The plan review, the Blueprint review and the spec review each wrote :cost nil on
  ;; the first real project and kept no id; the figure came off the account balance.
  (let [reading (fn [ep] {:findings [] :count 0 :model "m" :cost nil :at "t2"
                          :generation-ids ["gen-r2"] :endpoint ep :key-env "REPRICE_TEST_KEY"
                          :reviews [{:count 3 :cost nil :model "m" :at "t1" :generation-ids ["gen-r1"] :endpoint ep :key-env "REPRICE_TEST_KEY"}
                                    {:count 0 :cost nil :model "m" :at "t2" :generation-ids ["gen-r2"] :endpoint ep :key-env "REPRICE_TEST_KEY"}]})
        [{:keys [record changed lines]} seen]
        (with-stub {"gen-r1" {:total_cost 0.19} "gen-r2" {:total_cost 0.21}}
          #(reprice/reprice-review (reading %) {:fetch-opts fast :getenv {"REPRICE_TEST_KEY" "sk-test"}}))]
    (is (= 3 changed) "the reading and both entries of its history")
    (is (= 0.21 (:cost record)))
    (is (= :repriced (:cost-source record)))
    (is (= [0.19 0.21] (mapv :cost (:reviews record))))
    (is (every? #(= "Bearer sk-test" (:auth %)) seen))
    (is (= 3 (count lines)))
    (is (str/includes? (first lines) "the reading: cost $0.210000 (was —)")))
  (testing "a reading with a cost, or without ids, is left alone and says so"
    (is (= {:reading {:cost 0.1} :changed? false} (reprice/reprice-reading {:cost 0.1} {:getenv {}})))
    (is (str/includes? (:line (reprice/reprice-reading {:cost nil :endpoint "http://x/api/v1"} {:getenv {}}))
                       "no generation id")))
  (testing "one id still missing leaves the reading unpriced"
    (let [[{:keys [reading changed?]} _]
          (with-stub {} #(reprice/reprice-reading {:cost nil :generation-ids ["gen-z"] :endpoint % :key-env "K"}
                                                  {:fetch-opts fast :getenv {}}))]
      (is (false? changed?))
      (is (nil? (:cost reading))))))

(deftest main-takes-a-review-file-as-well-as-a-run-record
  (let [dir (str (fs/create-temp-dir))
        path (str (fs/path dir "plan-review.edn"))
        [out _] (with-stub {"gen-p" {:total_cost 0.22}}
                  (fn [ep]
                    (spit path (pr-str {:findings [{:finding "f"}] :count 1 :model "m" :cost nil :at "t"
                                        :generation-ids ["gen-p"] :endpoint ep :key-env "REPRICE_TEST_KEY"}))
                    (with-redefs [provenance/defaults fast]
                      (with-out-str (try (reprice/-main path) (catch Exception _ nil))))))]
    (is (str/includes? out "1 reading priced"))
    (is (= 0.22 (:cost (edn/read-string (slurp path)))) "the file is rewritten with the cost")))

(deftest role-key-reads-the-role-off-a-retry-or-triage-step-name
  (is (= :coder (reprice/role-key :coder)))
  (is (= :coder (reprice/role-key :coder-r2)))
  (is (= :orchestrator (reprice/role-key :triage)))
  (is (= :orchestrator (reprice/role-key :triage-r1)))
  (is (= :reviewer (reprice/role-key :reviewer-r3))))

(deftest main-rewrites-the-file-and-says-so
  (let [dir (str (fs/create-temp-dir))
        path (str (fs/path dir "w7.edn"))
        [out _] (with-stub {"gen-a" {:total_cost 0.25} "gen-b" {:total_cost 0.125}}
                  (fn [ep]
                    (spit path (pr-str (record ep)))
                    (with-redefs [provenance/defaults fast]
                      (with-out-str (reprice/-main path)))))
        after (read-string (slurp path))]
    (is (str/includes? out "1 step repriced"))
    (is (str/includes? out "fails `bb report-check` until it is re-rendered"))
    (is (= 0.375 (:step/cost (nth (:run/steps after) 1))))
    (is (= 0.575 (:run/cost after)))
    (testing "run again: nothing left to do, and the file is not touched"
      (let [before (slurp path)
            out (with-out-str (reprice/-main path))]
        (is (str/includes? out "nothing to reprice"))
        (is (= before (slurp path)))))))
