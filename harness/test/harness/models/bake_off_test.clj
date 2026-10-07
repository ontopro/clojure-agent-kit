(ns harness.models.bake-off-test
  "The bake-off: the spec expanded through a canned listing and test routes,
  the candidates and the judge against one stub model server that answers
  by who is asking, the records, the table, the marks, the check."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.shapes :as shapes]
   [harness.models.bake-off :as bo]
   [harness.models.catalogue-test :as cat-test]
   [malli.core :as m]
   [org.httpkit.server :as srv]))

(def kit-dir (str (fs/parent (fs/real-path "."))))
(def method-path (str (fs/path kit-dir "method.md")))

(defn- test-routes [endpoint]
  {:x-ai {:endpoint endpoint :shape :openai :provider "xai"
          :effort {:param :reasoning_effort :levels ["low" "medium" "high"]}}
   :anthropic {:endpoint endpoint :shape :openai :provider "anthropic"
               :effort {:param :reasoning_effort :levels ["low" "medium" "high"]} :max_tokens 16000}})

;; ---------------------------------------------------------------------------
;; expanding
;; ---------------------------------------------------------------------------

(deftest a-candidate-line-is-a-query-and-an-effort
  (is (= {:query "anthropic/claude-opus-5.5" :effort "high"} (bo/parse-candidate "anthropic/claude-opus-5.5 high")))
  (is (= {:query "grok" :effort nil} (bo/parse-candidate "  grok  ")))
  (is (= {:query "x" :effort "low"} (bo/parse-candidate {:query "x" :effort "low"}))))

(deftest a-candidate-expands-to-a-role-block-through-the-catalogue-and-the-routes
  (let [routes (test-routes "http://stub")
        c (bo/expand-candidate cat-test/listing routes "grok medium")]
    (is (= "x-ai/grok-4.7" (:model c)) "the newest grok")
    (is (= "grok-4.7-medium" (:id c)))
    (is (= :x-ai (:family c)))
    (is (m/validate shapes/RoleProfile (:profile c)) "a RoleProfile a profile could carry")
    (is (= "medium" (get-in c [:profile :params :reasoning_effort])))
    (is (= {:only ["xai"] :allow_fallbacks false} (get-in c [:profile :params :provider])))
    (is (= "high" (:effort (bo/expand-candidate cat-test/listing routes "grok"))) "no effort: the route's highest")
    (is (= 16000 (get-in (bo/expand-candidate cat-test/listing routes "opus") [:profile :params :max_tokens]))))
  (testing "refusals by name"
    (let [routes (test-routes "http://stub")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no model in the listing matches \"nope\""
                            (bo/expand-candidate cat-test/listing routes "nope high")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no route is written for the family :mistralai"
                            (bo/expand-candidate cat-test/listing routes "mistral")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes an effort of low, medium, high, not \"max\""
                            (bo/expand-candidate cat-test/listing routes "grok max"))))))

(deftest the-spec-expands-and-the-judge-is-held-to-the-rule
  (let [routes (test-routes "http://stub")
        spec {:role :blueprint-reviewer :candidates ["grok high" "opus high"] :judge "x-ai/grok-4.6 low"}
        r (:resolved (bo/expand spec cat-test/listing routes))]
    (is (= :blueprint-reviewer (:act r)))
    (is (= :blueprint-reviewer (:profile-role r)))
    (is (= ["grok-4.7-high" "claude-opus-5.5-high"] (mapv :id (:candidates r))))
    (is (= "x-ai/grok-4.6" (get-in r [:judge :model])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is a candidate; a judge is never a candidate"
                          (bo/expand (assoc spec :judge "x-ai/grok-4.7 low") cat-test/listing routes)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name a judge"
                          (bo/expand (dissoc spec :judge) cat-test/listing routes)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least two candidates"
                          (bo/expand (assoc spec :candidates ["grok"]) cat-test/listing routes)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no bake-off runs :coder yet"
                          (bo/expand (assoc spec :role :coder) cat-test/listing routes)))))

(deftest the-judge-reads-blind-in-a-recorded-order
  (let [l (bo/letters ["a" "b" "c"] 7)]
    (is (= ["A" "B" "C"] (vec (keys l))))
    (is (= #{"a" "b" "c"} (set (vals l))))
    (is (= l (bo/letters ["a" "b" "c"] 7)) "the same seed, the same order"))
  (let [text (bo/judge-input "the input" [["A" [{:finding "f1"}]] ["B" []]])]
    (is (str/starts-with? text "=== WHAT THE READERS WERE ASKED"))
    (is (< (str/index-of text "=== READER A ANSWERED") (str/index-of text "=== READER B ANSWERED")))
    (is (not (str/includes? text "grok")) "no model name reaches the judge"))
  (is (= [{:finding "f" :where "w" :raised_by ["A" "B"] :note "" :real true}]
         (bo/parse-rows "```json\n{\"rows\": [{\"finding\": \"f\", \"where\": \"w\", \"raised_by\": [\"A\", \"B\"], \"note\": \"\", \"real\": true}]}\n```")))
  (is (nil? (bo/parse-rows "no block"))))

;; ---------------------------------------------------------------------------
;; the run, against a stub that answers by who is asking
;; ---------------------------------------------------------------------------

(def ^:private candidate-answer
  "```json\n{\"findings\": [{\"kind\": \"over-engineered\", \"where\": \"§3\", \"finding\": \"a cache no task needs\", \"evidence\": \"cache\"}]}\n```")

(def ^:private judge-answer
  "```json\n{\"rows\": [{\"finding\": \"a cache no task needs\", \"where\": \"§3\", \"raised_by\": [\"A\", \"B\"], \"note\": \"\", \"real\": true}, {\"finding\": \"only one saw it\", \"where\": \"§1\", \"raised_by\": [\"B\"], \"note\": \"A read it as fine\", \"real\": false}]}\n```")

(defn- with-stub [f]
  (let [seen (atom [])
        stop (srv/run-server
              (fn [req]
                (let [body (json/parse-string (slurp (:body req)) true)
                      judge? (str/includes? (json/generate-string body) "You are the judge")]
                  (swap! seen conj body)
                  {:status 200 :headers {"Content-Type" "application/json" "Connection" "close"}
                   :body (json/generate-string
                          {:id "gen-1" :model (if judge? "judge-served" "cand-served")
                           :choices [{:message {:content (if judge? judge-answer candidate-answer)}}]
                           :usage {:prompt_tokens 5 :completion_tokens 3}})}))
              {:port 0 :legacy-return-value? false})]
    (try [(f (str "http://127.0.0.1:" (srv/server-port stop))) @seen]
         (finally @(srv/server-stop! stop)))))

(defn- scratch-plan-with-bake-off
  "A plan with one Blueprint and its stage document, and a bake-off folder
  with a three-line spec."
  []
  (let [plan (str (fs/create-temp-dir {:prefix "bake-off-"}))
        stages (fs/path plan "docs" "stages")
        dir (fs/path plan "bake-offs" "readers-1")]
    (fs/create-dirs stages)
    (fs/create-dirs dir)
    (spit (str (fs/path stages "stage-1-slice.md")) "# Stage 1\n\nthe stage\n")
    (spit (str (fs/path stages "stage-1-blueprint.md")) "# Stage 1 Blueprint\n\n**Input:** `stage-1-slice.md`\n\n## 1. Data shapes\n\nthe shapes\n")
    (spit (str (fs/path dir "bake-off.edn"))
          (pr-str {:role :blueprint-reviewer :candidates ["grok high" "opus high"] :judge "x-ai/grok-4.6 low"}))
    {:plan plan :dir (str dir) :spec (str (fs/path dir "bake-off.edn"))}))

(deftest the-run-records-every-candidate-judges-each-case-and-renders-the-table
  (let [[{:keys [plan dir]} seen]
        (with-stub (fn [endpoint]
                     (let [{:keys [plan dir spec]} (scratch-plan-with-bake-off)]
                       (with-out-str
                         (bo/run-bake-off! spec {:listing cat-test/listing :routes (test-routes endpoint)
                                                 :plan plan :method method-path :seed 3}))
                       {:plan plan :dir dir})))
        recs (bo/read-records dir)]
    (testing "resolved.edn beside the spec"
      (let [r (:resolved (edn/read-string (slurp (str (fs/path dir "resolved.edn")))))]
        (is (= ["grok-4.7-high" "claude-opus-5.5-high"] (mapv :id (:candidates r))))))
    (testing "one record per candidate per case, the model as served, the cost, the findings"
      (let [rs (get-in recs [:candidates "stage-1"])]
        (is (= #{"grok-4.7-high" "claude-opus-5.5-high"} (set (map :candidate rs))))
        (is (every? #(= "cand-served" (:model %)) rs))
        (is (every? #(= 1 (:count %)) rs))
        (is (every? #(= :blueprint-reviewer (:act %)) rs))))
    (testing "the judge's record: blind letters mapped back, rows, its order kept"
      (let [j (get-in recs [:judges "stage-1"])]
        (is (= "judge-served" (:model j)))
        (is (= #{"grok-4.7-high" "claude-opus-5.5-high"} (set (vals (:order j)))))
        (is (= 2 (count (:rows j))))
        (is (= 2 (count (:raised_by (first (:rows j))))) "both raised the first row")
        (is (= 1 (count (:raised_by (second (:rows j))))) "one raised the second")))
    (testing "the judge saw letters and no model names, after the candidates were called"
      (let [judge-req (last seen)
            sent (json/generate-string judge-req)]
        (is (str/includes? sent "READER A ANSWERED"))
        (is (not (str/includes? sent "grok-4.7")))
        (is (= 3 (count seen)) "two candidate calls, one judge call")))
    (testing "the table: per candidate, the case's rows, nothing marked yet"
      (let [md (slurp (str (fs/path dir "TABLE.md")))
            resolved (:resolved (edn/read-string (slurp (str (fs/path dir "resolved.edn")))))
            s (bo/summary (:candidates recs) (:judges recs) {} resolved)]
        (is (str/includes? md "# Bake-off `readers-1` — blueprint-reviewer, 2 candidates"))
        (is (= #{[1 2 1] [1 1 0]} (set (map (juxt :findings :rows :alone) s)))
            "each raised one finding; the judge put one on both rows and the other on one; one row raised alone")
        (is (every? #(nil? (:real %)) s) "real is unknown until the person marks")
        (is (str/includes? md "## Case `stage-1`"))
        (is (str/includes? md "| 0 | a cache no task needs | §3 | ✓ | ✓ | real | — |"))
        (is (str/includes? md "| 1 | only one saw it | §1 |"))))
    (testing "marks: the person marks the rows, the table re-renders with real counts"
      (spit (str (fs/path dir "marks.edn")) (pr-str {"stage-1" {0 true 1 false}}))
      (let [md (bo/table! dir)
            resolved (:resolved (edn/read-string (slurp (str (fs/path dir "resolved.edn")))))
            s (bo/summary (:candidates recs) (:judges recs) {"stage-1" {0 true 1 false}} resolved)]
        (is (str/includes? md "| 0 | a cache no task needs | §3 | ✓ | ✓ | real | real |"))
        (is (str/includes? md "| 1 | only one saw it | §1 | "))
        (is (every? #(= 1 (:real %)) s) "row 0 is real and both raised it; row 1 is not")
        (is (every? #(nil? (:real-per-dollar %)) s) "the stub reports no cost, so no per-dollar figure")))
    (testing "the check: a table that matches passes; an edited table or a lost record drifts"
      (is (= [] (bo/check! plan)))
      (spit (str (fs/path dir "TABLE.md")) "edited by hand")
      (is (= [dir] (bo/check! plan)))
      (bo/table! dir)
      (fs/delete (bo/record-path dir "stage-1" "grok-4.7-high"))
      (is (= [dir] (bo/check! plan)) "a record gone is drift too"))))

(deftest a-candidate-whose-call-fails-is-recorded-and-the-bake-off-goes-on
  (let [{:keys [plan dir spec]} (scratch-plan-with-bake-off)]
    (with-stub (fn [endpoint]
                 ;; the first candidate's family routes to a dead port, the second to the stub
                 (with-out-str
                   (bo/run-bake-off! spec {:listing cat-test/listing
                                           :routes (assoc-in (test-routes endpoint) [:x-ai :endpoint] "http://127.0.0.1:1")
                                           :plan plan :method method-path :seed 1}))))
    (let [rs (get-in (bo/read-records dir) [:candidates "stage-1"])
          failed (first (filter :failed rs))]
      (is (= "grok-4.7-high" (:candidate failed)))
      (is (str/includes? (:failed failed) "model call failed"))
      (is (= 1 (count (remove :failed rs))) "the other read is still evidence")
      (is (fs/exists? (fs/path dir "TABLE.md"))))))

;; ---------------------------------------------------------------------------
;; new: the spec asked for, on a scripted terminal
;; ---------------------------------------------------------------------------

(deftest new-asks-in-order-confirms-each-candidate-and-holds-the-judge-to-the-rule
  (let [{:keys [plan]} (scratch-plan-with-bake-off)
        answers (atom ["2"                      ; the act, by number: blueprint-reviewer
                       "grok" "y"               ; a word, resolved and kept
                       "nope" "opus high" "n"   ; nothing matches; then resolved and NOT kept
                       "opus high" "y"          ; kept
                       ""                       ; two candidates: finished
                       "x-ai/grok-4.7 low"      ; the judge is a candidate: refused
                       ""                       ; blank: a judge is needed
                       "x-ai/grok-4.6 low"      ; a judge
                       ""                       ; all cases
                       "readers-new"])          ; the id
        said (atom [])
        path (bo/new! plan {:ask (fn [_] (let [a (first @answers)] (swap! answers rest) a))
                            :say (fn [s] (swap! said conj s))
                            :listing cat-test/listing :routes (test-routes "http://stub")})
        spec (edn/read-string (slurp path))]
    (is (str/ends-with? path "/bake-offs/readers-new/bake-off.edn"))
    (is (= :blueprint-reviewer (:role spec)))
    (is (= ["x-ai/grok-4.7 high" "anthropic/claude-opus-5.5 high"] (:candidates spec)) "as resolved, effort filled in")
    (is (= "x-ai/grok-4.6 low" (:judge spec)))
    (is (nil? (:cases spec)) "all of what is on disk: nothing to name")
    (let [out (str/join "\n" @said)]
      (is (str/includes? out "→ x-ai/grok-4.7 at high"))
      (is (str/includes? out "no model in the listing matches \"nope\""))
      (is (str/includes? out "x-ai/grok-4.7 is a candidate; a judge is never a candidate"))
      (is (str/includes? out "a judge is needed"))
      (is (str/includes? out "Cases on disk for blueprint-reviewer: 1) stage-1"))
      (is (str/includes? out "run it: bb bake-off run")))
    (testing "the file `run` accepts: it expands without a call"
      (is (= ["grok-4.7-high" "claude-opus-5.5-high"]
             (mapv :id (:candidates (:resolved (bo/expand spec cat-test/listing (test-routes "http://stub"))))))))))
