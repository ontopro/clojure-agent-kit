(ns harness.models.security-bake-off-test
  "The security bake-off's arithmetic on canned readings: the jobs, a candidate's row from its two
  scored readings, the table. Running the readings - real models, a container - is `bb
  security-bake-off`, never in the gates."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.models.security-bake-off :as bo]))

(def astra {:id "gpt-6-astra-high" :model "openai/gpt-6-astra"})
(def glm {:id "glm-5.3-prime-high" :model "z-ai/glm-5.3-prime"})

(deftest each-candidate-reads-the-faulted-branch-and-the-careful-one
  (is (= [["gpt-6-astra-high-faulted" :faulted] ["gpt-6-astra-high-clean" :clean]
          ["glm-5.3-prime-high-faulted" :faulted] ["glm-5.3-prime-high-clean" :clean]]
         (mapv (juxt :id :case) (bo/jobs [astra glm])))))

(def astra-faulted
  {:review {:model "openai/gpt-6-astra" :iterations 15 :ms 372000 :cost 1.2 :findings [{} {} {}] :refused? false :capped? false}
   :score {:found {:race ["a"] :idor ["b"] :raw-sql ["c" "d"]}
           :missed [:admin-authz :csrf-get :path-escape]
           :counts {:findings 8 :hit 5 :not-reproduced 2 :fails-on-both 1}}})

(def astra-control
  {:review {:cost 0.9 :findings [{:title "x"}] :iterations 9}
   :score {:tests {"notes.a-test" {:clean :fails} "notes.b-test" {:clean :passes} "notes.c-test" {:clean :fails}}}})

(deftest a-candidates-row-is-arithmetic-over-its-two-readings
  (let [r (bo/row astra astra-faulted astra-control)]
    (is (= {:candidate "gpt-6-astra-high" :found 3 :planted 6 :missed ["admin-authz" "csrf-get" "path-escape"]
            :claimed 8 :hits 5 :not-reproduced 2 :to-mark 1 :completions 15 :control-failing 2 :control-claimed 1}
           (select-keys r [:candidate :found :planted :missed :claimed :hits :not-reproduced :to-mark :completions :control-failing :control-claimed])))
    (is (= 2.1 (:cost r)) "both readings' cost")
    (is (true? (:cost-known? r)))
    (is (== 6.2 (:minutes r)))
    (is (true? (:answered? r)))
    (is (false? (:refused? r)))))

(deftest a-refusal-a-missing-answer-and-a-missing-cost-are-said
  (let [refused (bo/row astra {:review {:refused? true :ms 60000 :cost nil} :score {:found {} :missed [:race] :counts {}}} astra-control)
        silent (bo/row glm {:review {:findings nil :capped? true :ms 1000 :cost 0.6 :iterations 60} :score {:found {} :missed [:race] :counts {}}} astra-control)]
    (is (true? (:refused? refused)))
    (is (false? (:answered? refused)))
    (is (false? (:cost-known? refused)) "a cost not yet reported is not a free review")
    (is (false? (:answered? silent)))
    (is (true? (:capped? silent)))
    (let [table (bo/render [refused silent])]
      (is (str/includes? table "- gpt-6-astra-high: the model refused the review"))
      (is (str/includes? table "- glm-5.3-prime-high: wrote no answer; scored on its tests alone"))
      (is (str/includes? table "- glm-5.3-prime-high: stopped at its round limit"))
      (is (str/includes? table "not yet reported")))))

(deftest the-table-has-a-row-per-candidate
  (let [table (bo/render [(bo/row astra astra-faulted astra-control)])]
    (is (str/includes? table "| gpt-6-astra-high | 3 of 6 | 5 / 2 / 1 | 15 | 6.2 | $2.10 | 2 |"))
    (is (str/includes? table "- gpt-6-astra-high: missed admin-authz, csrf-get, path-escape"))))

(def canned-review
  {:model "openai/gpt-6-astra" :iterations 12 :ms 243000 :sandbox-ms 25000 :cost 0.80 :findings [{} {}]
   :capped? false :refused? false
   :steps [{:usage {:in 10 :out 400 :cache-read 5000 :cache-write 1000} :reasoning-tokens 100}
           {:usage {:in 5 :out 600 :cache-read 7000 :cache-write 500} :reasoning-tokens 200}]
   :calls [["read_file" 10 false] ["read_file" 12 false] ["write_test" 5 false] ["run_tests" 900 true]]
   :turns [{:calls [{:tool "write_test" :args {:path "test/a_test.clj"}}]}
           {:calls [{:tool "write_test" :args {:path "test/a_test.clj"}} {:tool "write_test" :args {:path "test/b_test.clj"}}]}]})

(deftest a-readings-metrics-are-summed-from-its-records
  (let [m (bo/reading-metrics {:id "x-faulted" :candidate "x" :case :faulted :review canned-review
                               :score {:ms 182000 :found {:race ["t"] :idor ["u"]} :missed [:raw-sql]
                                       :counts {:findings 2 :hit 2} :tests {"a/t" {:clean :passes} "a/u" {:clean :fails}}}})]
    (is (= {:in 15 :cache-read 12000 :cache-write 1500 :out 1000 :reasoning 300} (:usage m)))
    (is (= 14515 (:tokens m)) "uncached + cache read + cache write + output; reasoning is part of output")
    (is (= 450000 (:work-ms m)) "sandbox start + conversation + scoring")
    (is (= {"read_file" 2 "write_test" 1 "run_tests" 1} (:by-tool m)))
    (is (= 1 (:call-errors m)))
    (is (= [2 3] [(:tests-written m) (:test-writes m)]) "three writes to two files")
    (is (== 4/12 (:calls-per-completion m)))
    (is (= [2 3 1] [(:found m) (:planted m) (:careful-failing m)]))
    (is (true? (:answered? m)))))

(deftest the-report-has-time-cost-tokens-and-the-result-and-says-what-it-lacks
  (let [reading (fn [cand k cost] {:id (str cand "-" (name k)) :candidate cand :case k
                                   :review (assoc canned-review :cost cost) :score {:ms 60000 :found {:race ["t"]} :missed [:idor] :counts {:findings 1 :hit 1} :tests {}}})
        run {:invocations [{:started "2026-10-09T01:00:00Z" :finished "2026-10-09T01:20:00Z" :elapsed-ms 1200000
                            :kit-commit "abc1234" :models ["openai/gpt-6-astra"] :rounds 60 :parallel 2}]}
        r (bo/report "T" run [(reading "astra" :faulted 0.80) (reading "astra" :clean 0.46)])]
    (is (str/includes? r "## Time")) (is (str/includes? r "## Cost")) (is (str/includes? r "## Tokens"))
    (is (str/includes? r "## How each reading went")) (is (str/includes? r "## Result against the answer key"))
    (is (str/includes? r "| astra faulted | 0:25 | 4:03 | 1:00 | 5:28 |"))
    (is (str/includes? r "**Work: 10:56** (the readings added up) · **elapsed: 20:00** wall-clock"))
    (is (str/includes? r "**Total: $1.26**"))
    (is (str/includes? r "**Total: 29,030 tokens**"))
    (is (str/includes? r "| astra | 1 of 2 |"))
    (is (str/includes? r "KIT abc1234"))
    (testing "a cost not reported is said and left out of the total"
      (let [r2 (bo/report "T" run [(reading "astra" :faulted 0.80) (reading "astra" :clean nil)])]
        (is (str/includes? r2 "not reported"))
        (is (str/includes? r2 "$0.80 so far"))))
    (testing "a run made before runs were recorded"
      (let [r3 (bo/report "T" {:invocations []} [(reading "astra" :faulted 0.80)])]
        (is (str/includes? r3 "made before runs were recorded"))
        (is (not (str/includes? r3 "wall-clock")))
        (is (not (str/includes? r3 "**Models:**")))))))
