(ns harness.money.missing-figures-test
  "Money figures a provider leaves out - always, sometimes, at random - never throw, and a sum
  over what was priced says so. The first project's key had its limit unset between a run's start
  and its record, and `bb report` threw."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.models.agent :as agent]
   [harness.models.bake-off :as bo]
   [harness.money.balance :as balance]
   [harness.money.report :as report]
   [harness.money.reprice :as reprice]
   [harness.money.stage-report :as sr]
   [harness.setup.where :as where]))

(defn- at [ms] (java.util.Date. (long ms)))

(def ^:private whole-run
  {:run/id "r1" :task/id "t1" :run/status :merged
   :run/steps [{:step/name :provision :step/kind :provision :step/status :pass :step/ms 1000 :step/source :measured}
               {:step/name :coder :step/kind :dispatch :step/status :done :step/ms 30000 :step/cost 0.3 :step/tokens 1200
                :step/model "m-a" :step/provider "Anthropic API" :step/source :measured :step/cost-source :list-price
                :step/rounds 2 :step/generation-ids ["g1" "g2"]}
               {:step/name :tester :step/kind :dispatch :step/status :done :step/ms 20000 :step/cost 0.2 :step/tokens 900
                :step/model "m-g" :step/provider "Google" :step/source :measured :step/cost-source :reported}
               {:step/name :reviewer :step/kind :dispatch :step/status :done :step/ms 9000 :step/cost 0.1 :step/tokens 400
                :step/model "m-o" :step/provider "OpenAI" :step/source :measured :step/cost-source :reported}
               {:step/name :gate :step/kind :gate :step/status :pass :step/ms 4000 :step/source :measured}]
   :run/events [{:event/kind :spec-review :findings 2 :cost 0.02 :at (at 0)
                 :reviews [{:count 3 :cost 0.03} {:count 2 :cost 0.02}]}
                {:event/kind :balance :when :start :at (at 1000)
                 :openrouter {:limit 40.0 :usage 10.0 :remaining 30.0 :credits {:total 50.0 :usage 10.0 :remaining 40.0}}}
                {:event/kind :dispatch :role :coder :model "m-a" :at (at 10000) :usage {:in 100 :out 50 :cache-read 300 :cache-write 100}}
                {:event/kind :dispatch :role :tester :model "m-g" :at (at 40000) :usage {:in 2000 :out 300}}
                {:event/kind :dispatch :role :reviewer :model "m-o" :at (at 60000) :usage {:in 500 :out 80}}
                {:event/kind :balance :when :record :at (at 90000)
                 :openrouter {:limit 40.0 :usage 10.3 :remaining 29.7 :credits {:total 50.0 :usage 10.3 :remaining 39.7}}}
                {:event/kind :stopped :stop/kind :awaiting-merge :at (at 100000)}]})

(defn- drop-some
  "`m` with each of `ks` it has kept, left out or nil - at random, or (`always?`) never kept: what a
  provider that answers sometimes, or never, leaves in a record."
  [^java.util.Random rnd always? ks m]
  (reduce (fn [m k] (if (contains? m k)
                      (case (if always? (inc (.nextInt rnd 2)) (.nextInt rnd 3)) 0 m 1 (dissoc m k) 2 (assoc m k nil))
                      m))
          m ks))

(defn- damage
  "`whole-run` with its money figures dropped by `drop-some`; a balance event is also, at random,
  unavailable or not taken."
  [rnd always?]
  (let [d (partial drop-some rnd always?)]
    (-> whole-run
        (update :run/steps (partial mapv #(d [:step/cost :step/tokens :step/cost-source :step/provider :step/model
                                              :step/rounds :step/generation-ids] %)))
        (update :run/events
                (fn [es]
                  (vec (keep (fn [e]
                               (case (:event/kind e)
                                 :balance (case (.nextInt ^java.util.Random rnd 4)
                                            0 nil
                                            1 (assoc e :openrouter {:unavailable "HTTP 503"})
                                            (update e :openrouter #(d [:limit :usage :remaining]
                                                                      (update % :credits (partial d [:total :usage :remaining])))))
                                 :dispatch (if (zero? (.nextInt ^java.util.Random rnd 3))
                                             (dissoc e :usage)
                                             (update e :usage (partial d [:in :out :cache-read :cache-write])))
                                 :spec-review (-> (d [:cost] e) (update :reviews (partial mapv (partial d [:cost]))))
                                 e))
                             es)))))))

(deftest a-run-missing-any-money-figure-renders-everywhere-it-is-read
  (doseq [always? [false true] seed (range 150)]
    (let [rnd (java.util.Random. seed)
          r (damage rnd always?)
          b #(:openrouter (first (filter (fn [e] (and (= :balance (:event/kind e)) (= % (:when e)))) (:run/events r))))]
      (testing (str (if always? "every figure missing" "figures missing at random") ", seed " seed)
        (is (string? (report/render r)))
        (is (string? (report/balance-line (b :start) (b :record) 0.5)))
        (is (string? (balance/line {:roles {:coder {:endpoint "https://api.anthropic.com"}}} (:run/steps r))))
        (is (string? (sr/render (sr/stage-summary {:id 1 :packets ["t1"] :records [r (damage rnd always?)]
                                                   :readings [["stage plan review" 0.5 1 (.nextInt rnd 2)]]
                                                   :gates {:stage/approved {:date "2026-10-20" :cap "$20"}}}))))
        (let [recs (for [i (range 3)] (drop-some rnd always? [:cost] {:candidate "a" :count 1 :ms 1000 :cost (* 0.1 (inc i))}))
              judges {"c1" (drop-some rnd always? [:cost] {:rows [] :cost 0.05})}]
          (is (string? (bo/render {:candidates {"c1" recs} :judges judges} {}
                                  {:candidates [{:id "a" :model "m-a"}] :act :spec-reviewer :judge {:model "j"}} "bo-1"))))))))

(deftest a-whole-run-reads-as-it-always-did
  (let [out (report/render whole-run)]
    (is (str/includes? out "Spec reviews before start: 2 — 3 findings $0.030000; 2 findings $0.020000; $0.050000 in all."))
    (is (str/includes? out "Whole cost: ~$0.650000"))
    (is (str/includes? out "Anthropic API spend $0.300000 (its API has no balance endpoint; the console has it)."))
    (is (str/includes? out "  Tokens, per model (in / out / cache-read / cache-write):\n"))
    (is (not (str/includes? out "unpriced")))
    (is (not (str/includes? out "Tokens cover")))))

(deftest a-sum-over-what-was-priced-says-so
  (let [unpriced-coder (fn [r] (update r :run/steps (partial mapv #(if (= :coder (:step/name %)) (dissoc % :step/cost :step/cost-source :step/tokens) %))))]
    (testing "the run report: a reading with no cost, a direct step with none, a dispatch with no tokens or usage"
      (let [r (-> whole-run
                  unpriced-coder
                  (update :run/events (partial mapv #(cond (= :spec-review (:event/kind %)) (assoc-in % [:reviews 1 :cost] nil)
                                                           (= :tester (:role %)) (dissoc % :usage)
                                                           :else %))))
            out (report/render r)]
        (is (str/includes? out "Spec reviews before start: 2 — 3 findings $0.030000; 2 findings; $0.030000 for the 1 of 2 priced."))
        (is (not (str/includes? out "Whole cost")) "a whole cost over part of the readings is not said")
        (is (str/includes? out "Anthropic API spend $0.000000, 1 step unpriced (its API"))
        (is (str/includes? out "  Tokens cover 2 of 3 dispatches; the rest reported none."))
        (is (str/includes? out "over the 2 of 3 dispatches that carry usage:"))))
    (testing "a run's cost is nil when nothing was priced, never $0"
      (is (= 0.6 (report/run-cost (:run/steps whole-run))))
      (is (nil? (report/run-cost (map #(dissoc % :step/cost) (:run/steps whole-run))))))
    (testing "the stage report says the dispatches and readings with no cost, and only then"
      (let [facts {:id 1 :packets ["t1"] :readings [["stage plan review" 0.5 1 0]]}
            whole (sr/render (sr/stage-summary (assoc facts :records [whole-run])))
            part (sr/render (sr/stage-summary (assoc facts :records [(unpriced-coder whole-run)]
                                                     :readings [["stage plan review" 0 1 1]])))]
        (is (not (str/includes? whole "Unpriced")))
        (is (str/includes? part "  Unpriced: 1 dispatch and 1 reading with no cost - every money figure above is what was priced, short by theirs (`bb reprice` fills a run's late costs)"))))
    (testing "the roadmap's money cell"
      (is (= " (3 unpriced)" (where/unpriced-note {:unpriced 3})))
      (is (= "" (where/unpriced-note {}))))
    (testing "bb balance: a direct provider's unpriced steps beside its spend"
      (is (str/includes? (balance/line {:roles {:coder {:endpoint "https://api.anthropic.com"}}} (:run/steps (unpriced-coder whole-run)))
                         "Anthropic API $0.00 (1 unpriced)")))
    (testing "the bake-off: a candidate's unpriced reading said, and no per-dollar over part of its cost"
      (let [resolved {:candidates [{:id "a" :model "m-a"}] :act :spec-reviewer :judge {:model "j"}}
            recs {:candidates {"c1" [{:candidate "a" :count 1 :ms 1000 :cost 0.1} {:candidate "a" :count 1 :ms 1000 :cost nil}]}
                  :judges {"c1" {:rows [{:raised_by ["a"]}] :cost nil}}}
            md (bo/render recs {"c1" {0 true}} resolved "bo-1")]
        (is (str/includes? md "| $0.1000 (1 unpriced) |"))
        (is (str/includes? md "Judge: $0.0000 (1 unpriced) over 1 case; the whole bake-off $0.1000 (2 unpriced)."))
        (is (nil? (:real-per-dollar (first (bo/summary (:candidates recs) (:judges recs) {"c1" {0 true}} resolved)))))))))

(deftest a-reading-is-priced-only-whole
  (testing "a cost over some completions is no cost; the completions are kept"
    (is (= {:model "m" :cost nil :completions 2 :generation-ids ["g1" "g2"] :endpoint "e" :key-env "K"}
           (agent/call-record {:steps [{:model "m" :cost 0.1 :generation-id "g1"} {:model "m" :generation-id "g2"}]} {:endpoint "e" :key-env "K"})))
    (is (= 0.30000000000000004 (:cost (agent/call-record {:steps [{:cost 0.1} {:cost 0.2}]} {})))))
  (testing "a reprice of a call that kept fewer ids than completions leaves it unpriced, and says why"
    (let [{:keys [changed? line]} (reprice/reprice-reading {:cost nil :generation-ids ["g1"] :completions 2
                                                            :endpoint "https://openrouter.ai/api/v1" :key-env "K"}
                                                           {:getenv (constantly nil)})]
      (is (false? changed?))
      (is (= "1 of 2 completions kept a generation id - left unpriced, a sum of the rest would be part of the cost" line)))))
