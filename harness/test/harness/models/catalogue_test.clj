(ns harness.models.catalogue-test
  "The catalogue: a canned listing in the shape OpenRouter's `/models` answers
  (checked live 2026-09-25), no network."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [harness.models.catalogue :as cat]))

(def listing
  [{:id "x-ai/grok-4.7" :name "xAI: Grok 4.7" :created 1790007541 :context_length 500000
    :pricing {:prompt "0.0000016" :completion "0.0000048"} :supported_parameters ["reasoning" "max_tokens"]}
   {:id "x-ai/grok-4.6" :name "xAI: Grok 4.6" :created 1786548957 :context_length 500000
    :pricing {:prompt "0.000002" :completion "0.000006"} :supported_parameters ["reasoning"]}
   {:id "~x-ai/grok-latest" :name "xAI: Grok (latest)" :created 1783519360 :context_length 500000
    :pricing {:prompt "0.0000016" :completion "0.0000048"} :supported_parameters ["reasoning"]}
   {:id "anthropic/claude-opus-5.5" :name "Anthropic: Claude Opus 5.5" :created 1789000000 :context_length 1000000
    :pricing {:prompt "0.000004" :completion "0.00002"} :supported_parameters ["reasoning" "tools"]}
   {:id "mistralai/mistral-large" :name "Mistral Large" :created 1780000000 :context_length 128000
    :pricing {:prompt "0.000002" :completion "0.000006"} :supported_parameters ["tools"]}])

(deftest a-word-names-the-models-that-carry-it-newest-first-aliases-dropped
  (let [m (cat/matching listing "grok")]
    (is (= ["x-ai/grok-4.7" "x-ai/grok-4.6"] (mapv :id m)) "the alias ~x-ai/grok-latest is not a model to name")
    (is (= "x-ai/grok-4.7" (:id (cat/newest m))))
    (is (= :x-ai (:family (first m))))
    (is (= "2026-09-21" (:date (first m))) "the listing's epoch as a date")
    (is (true? (:reasoning? (first m))))
    (is (= 1.6 (:in (first m))))
    (is (= 4.8 (:out (first m))))))

(deftest a-full-slug-names-exactly-that-model-and-case-does-not-matter
  (is (= ["x-ai/grok-4.6"] (mapv :id (cat/matching listing "x-ai/grok-4.6"))))
  (is (= ["x-ai/grok-4.6"] (mapv :id (cat/matching listing "X-AI/GROK-4.6"))))
  (is (= ["anthropic/claude-opus-5.5"] (mapv :id (cat/matching listing "Opus")))))

(deftest nothing-matching-is-an-empty-answer
  (is (= [] (cat/matching listing "no-such-model")))
  (is (= [] (cat/matching listing ""))))

(deftest the-family-is-the-slugs-prefix-and-the-route-is-written-per-family
  (is (= :anthropic (cat/family-of "anthropic/claude-opus-5.5")))
  (is (= :x-ai (cat/family-of "x-ai/grok-4.7")))
  (is (nil? (cat/family-of "claude-fable-5-1")) "a direct model name has no prefix")
  (let [r (cat/routes)]
    (is (= #{:anthropic :openai :google :x-ai :anthropic-direct} (set (keys r))))
    (doseq [[fam route] r]
      (is (and (:endpoint route) (:key-env route) (:shape route) (get-in route [:effort :param]) (seq (get-in route [:effort :levels])))
          (str (name fam) ": endpoint, key variable, shape, effort parameter and levels"))
      (is (not (re-find #"(?i)sk-|key-[a-z0-9]{8}" (pr-str route))) "no key, ever"))
    (is (= "xai" (:provider (cat/route r :x-ai))))
    (is (= :anthropic (:shape (cat/route r :anthropic-direct))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no route is written for the family :mistralai"
                          (cat/route r :mistralai)))))

(deftest prices-are-per-million-from-the-listings-per-token-figures
  (is (= 1.6 (cat/per-million "0.0000016")))
  (is (= 2.0 (cat/per-million 0.000002)))
  (is (nil? (cat/per-million nil)))
  (is (nil? (cat/per-million "free"))))

(deftest the-table-marks-the-newest-and-says-whether-a-route-is-written
  (let [lines (cat/table (cat/matching listing "grok") (cat/routes))]
    (is (str/includes? (second lines) "x-ai/grok-4.7"))
    (is (str/includes? (second lines) "← newest"))
    (is (not (str/includes? (nth lines 2) "← newest")))
    (is (re-find #"\$1\.60\s+\$4\.80\s+yes\s+yes" (second lines)) "prices per million, reasoning, a route"))
  (let [lines (cat/table (cat/matching listing "mistral") (cat/routes))]
    (is (re-find #"no\s+none written" (second lines)) "no reasoning, no route for its family")))

(deftest an-endpoint-row-carries-the-provider-and-its-tag
  (is (= {:provider "xAI" :tag "xai/zdr" :quantization nil :in 1.6 :out 4.8 :reasoning? true}
         (cat/endpoint-row {:provider_name "xAI" :tag "xai/zdr" :quantization nil
                            :pricing {:prompt "0.0000016" :completion "0.0000048"}
                            :supported_parameters ["reasoning"]}))))
