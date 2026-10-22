(ns harness.money.reprice-test
  (:require
   [babashka.fs :as fs]
   [babashka.http-client]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [harness.contract.shapes :as shapes]
   [harness.models.provenance :as provenance]
   [harness.money.report :as report]
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

;; THE STUB IS A ROUTE THESE TESTS TRUST. `bb reprice` takes a key only for an endpoint the profile or
;; routes.edn names (register row 181); a stub on the loopback is neither, so these tests name it, with its key
(use-fixtures :each
  (fn [t]
    (let [real reprice/key-env-for]
      (with-redefs [reprice/key-env-for (fn [ep p r] (if (str/starts-with? (str ep) "http://127.0.0.1:") "REPRICE_TEST_KEY" (real ep p r)))]
        (t)))))

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

(deftest a-step-is-short-of-ids-by-its-completions-never-its-rounds
  (let [run (fn [coder] #(reprice/reprice (update-in (record %) [:run/steps 1] merge coder) {:fetch-opts fast :getenv {}}))
        gens {"gen-a" {:total_cost 0.25} "gen-b" {:total_cost 0.125}}
        priced? (fn [[{:keys [record changed]} _]] (and (= 1 changed) (= 0.375 (:step/cost (nth (:run/steps record) 1)))))]
    (testing "a dispatch that failed after 2 completions has 3 rounds, 2 ids, and is priced"
      (is (priced? (with-stub gens (run {:step/status :fail :step/rounds 3 :step/completions 2})))))
    (testing "so is one recorded before the completions were kept: a failed step's rounds are not its completions"
      (is (priced? (with-stub gens (run {:step/status :fail :step/rounds 3})))))
    (testing "a capped dispatch: as many rounds as completions"
      (is (priced? (with-stub gens (run {:step/status :fail :step/rounds 2 :step/completions 2})))))
    (testing "a completion with no id is still short, by the completions or a finished step's rounds"
      (doseq [coder [{:step/completions 3 :step/rounds 3} {:step/rounds 3}]]
        (let [[{:keys [changed lines]} seen] (with-stub gens (run coder))]
          (is (= 0 changed))
          (is (empty? seen) "nothing fetched")
          (is (str/includes? (first lines) "coder: 2 of 3 completions kept a generation id - left unpriced")))))))

(deftest a-dispatch-step-keeps-its-completions-beside-its-rounds
  (let [s #(report/dispatch-step :coder {:status :failed :files [] :stdout nil :runner/meta %} 100)]
    (is (= [3 2] ((juxt :step/rounds :step/completions) (s {:model "m" :iterations 3 :completions 2 :error {}}))))
    (is (= 0 (:step/completions (s {:model "m" :iterations 1 :completions 0 :error {}}))) "a first request refused")
    (is (not (contains? (s {:model "m" :iterations 2}) :step/completions)) "a runner that never counted them")))

;; From the KIT's own security review (GPT-6 Astra, 2026-10-21): a run record is a project's file, and
;; it had named both the host and the key's variable that `bb reprice` sent the key to
(deftest a-record-never-names-the-key-nor-sends-it-to-a-host-nobody-trusts
  (let [sent (atom [])
        asked (atom [])
        rec (fn [roles] {:run/id "r" :run/roles roles
                         :run/steps [{:step/name :coder :step/kind :dispatch :step/status :done
                                      :step/generation-ids ["g1"] :step/cost nil}]})
        trusted {:roles {:coder {:endpoint "https://openrouter.ai/api/v1" :key-env "OPENROUTER_API_KEY"}}}
        getenv (fn [v] (swap! asked conj v) "the-key")]
    (with-redefs [babashka.http-client/get (fn [url opts]
                                             (swap! sent conj {:url url :auth? (boolean (get-in opts [:headers "Authorization"]))})
                                             {:status 200 :body "{\"data\":{\"total_cost\":0.1}}"})]
      (testing "an endpoint neither the profile nor routes.edn names is not fetched from"
        (let [{:keys [changed lines]} (reprice/reprice (rec {:coder {:endpoint "https://collector.invalid/api/v1" :key-env "OPENROUTER_API_KEY"}})
                                                       {:profile trusted :fetch-opts fast :getenv getenv})]
          (is (= 0 changed))
          (is (empty? @sent) "no request at all, so no key")
          (is (str/includes? (first lines) "collector.invalid/api/v1 for coder is not an endpoint the profile or routes.edn names"))))
      (testing "a known endpoint takes the key the profile pairs with it, never the record's variable"
        (reset! asked [])
        (reprice/reprice (rec {:coder {:endpoint "https://openrouter.ai/api/v1" :key-env "ANTHROPIC_API_KEY"}})
                         {:profile trusted :fetch-opts fast :getenv getenv})
        (is (= ["OPENROUTER_API_KEY"] (distinct @asked))))
      (testing "and a reading the same"
        (reset! sent [])
        (is (str/includes? (:line (reprice/reprice-reading {:cost nil :generation-ids ["g"] :endpoint "https://collector.invalid/api/v1"
                                                            :key-env "OPENROUTER_API_KEY"}
                                                           {:fetch-opts fast :getenv getenv}))
                           "not an endpoint the profile or routes.edn names"))
        (is (empty? @sent))))))

;; From the KIT's own security review (GPT-6 Astra, the money-and-records flow, 2026-10-21): a generation
;; record echoing the key it was sent put it in `:step/provider`, the record and the console
(deftest a-generation-record-echoing-its-key-is-redacted
  (let [secret "sk-review-only-dummy-secret-65187"
        record {:run/id "r" :run/roles {:coder {:endpoint "https://openrouter.ai/api/v1"}}
                :run/steps [{:step/name :coder :step/kind :dispatch :step/status :done
                             :step/generation-ids ["g"] :step/cost nil}]}
        result (with-redefs [babashka.http-client/get
                             (fn [_ opts]
                               {:status 200 :body (json/generate-string
                                                   {:data {:total_cost 0.1 :native_tokens_prompt 1
                                                           :provider_name (get-in opts [:headers "Authorization"])}})})]
                 (reprice/reprice record {:getenv (constantly secret) :fetch-opts {:attempts 1 :interval-ms 1 :timeout-ms 2000}}))]
    (is (= 0.1 (get-in result [:record :run/steps 0 :step/cost])) "the cost is still taken")
    (is (= "Bearer <key>" (get-in result [:record :run/steps 0 :step/provider])))
    (is (not (str/includes? (pr-str result) secret)))))
