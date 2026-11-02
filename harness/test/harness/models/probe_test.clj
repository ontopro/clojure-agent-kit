(ns harness.models.probe-test
  "The pre-run probe's verdict on canned calls: what stops a run, what is only said. The calls themselves
  are real model calls, made by `bb security-bake-off` and `bb security-review --kit`, never in the gates."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [harness.models.probe :as probe]))

(def pinned {:params {:reasoning_effort "xhigh" :provider {:only ["openai"] :allow_fallbacks false}}})
(def direct {:params {:reasoning_effort "high"}})

(def good {:status :answered :tool-called? true :answer 265 :host "OpenAI" :reasoning 812 :cost 0.002 :ms 3100})

(defn- outcomes [v] (into {} (map (juxt :check :outcome)) (:checks v)))

(deftest a-candidate-served-as-asked-goes-on
  (let [v (probe/verdict pinned [good])]
    (is (false? (:stop? v)))
    (is (= {:accepted :ok :tool-call :ok :host :ok :reasoning :ok :answer :info} (outcomes v)))))

(deftest a-refusal-stops-and-says-why
  (let [v (probe/verdict pinned [{:status :error :http 400 :error "reasoning_effort: unsupported value 'xhigh'" :ms 400}])]
    (is (true? (:stop? v)))
    (is (= [:accepted] (mapv :check (:checks v))) "nothing else to read from a refusal")
    (is (str/includes? (:line (first (:checks v))) "unsupported value 'xhigh'"))))

(deftest no-tool-call-in-two-tries-stops-one-flake-does-not
  (let [none (assoc good :tool-called? false :answer nil)]
    (is (true? (:stop? (probe/verdict pinned [none none]))) "the review is tool calls")
    (let [v (probe/verdict pinned [none good])]
      (is (false? (:stop? v)))
      (is (some #(= "a tool call on the second try" (:line %)) (:checks v))))))

(deftest the-pinned-host-is-confirmed-belt-and-braces
  (is (true? (probe/host-matches? "google-vertex" "Google")))
  (is (true? (probe/host-matches? "xai" "xAI")))
  (is (true? (probe/host-matches? "alibaba" "Alibaba")))
  (is (false? (probe/host-matches? "openai" "Azure")))
  (is (true? (:stop? (probe/verdict pinned [(assoc good :host "Azure")]))) "the pin stopped working")
  (is (= :info (:host (outcomes (probe/verdict direct [(assoc good :host "Google")])))) "a direct route has no pin"))

(deftest reasoning-reported-zero-stops-reasoning-not-reported-is-said
  (let [zero (probe/verdict pinned [(assoc good :reasoning 0)])]
    (is (true? (:stop? zero)))
    (is (str/includes? (str/join " " (map :line (:checks zero))) "a candidate at none is stopped here by design")))
  (let [absent (probe/verdict direct [(assoc good :reasoning nil)])]
    (is (false? (:stop? absent)) "absent is not zero: the host gives no count")
    (is (= :info (:reasoning (outcomes absent))))))

(deftest the-answer-is-information
  (is (false? (:stop? (probe/verdict pinned [(assoc good :answer 260)]))))
  (is (some #(= "answer 260, not 265" (:line %)) (:checks (probe/verdict pinned [(assoc good :answer 260)])))))

(deftest a-line-per-candidate
  (let [c {:id "gpt-6.1-sol-xhigh" :route "openrouter"}]
    (is (= "  probe gpt-6.1-sol-xhigh (openrouter): ok - accepted, a tool call, host OpenAI, reasoning 812 tokens, answer right; $0.0020, 3.1s"
           (probe/line c (probe/verdict pinned [good]) [good])))
    (is (str/starts-with? (probe/line c (probe/verdict pinned [(assoc good :reasoning 0)]) [good])
                          "  probe gpt-6.1-sol-xhigh (openrouter): STOP - 0 reasoning tokens")
        "a stop line names only what stopped it")))
