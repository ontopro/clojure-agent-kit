(ns harness.models.security-bake-off-test
  "The security bake-off's arithmetic on canned readings: the jobs, a candidate's row from its two
  scored readings, the table. Running the readings - real models, a container - is `bb
  security-bake-off`, never in the gates."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
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
