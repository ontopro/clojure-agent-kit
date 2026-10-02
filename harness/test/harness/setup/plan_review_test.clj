(ns harness.setup.plan-review-test
  "The plan review: what it sends, what it parses, what the command writes.
  The model is a local http-kit stub, as in harness.contract.spec-review-test."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.plan-review :as pr]
   [org.httpkit.server :as srv]))

(def kit-dir (str (fs/parent (fs/real-path "."))))
(def method-path (str (fs/path kit-dir "method.md")))

(deftest the-checklist-is-section-02s-plan-review-pass-read-from-the-method
  (let [c (pr/checklist (slurp method-path))]
    (is (str/starts-with? c "### The plan-review pass"))
    (is (str/includes? c "Direct contradictions between documents"))
    (is (str/includes? c "Decided too much, too early"))
    (is (str/includes? c "Exit criteria for Phase A"))
    (is (not (str/includes? c "## 03")) "it ends with the section"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no '### The plan-review pass'" (pr/checklist "# a method without it"))))

(deftest the-input-is-every-document-in-order-then-the-checklist
  (let [text (pr/review-input [["source.md" "the brief"] ["00-overview.md" nil]] "- check this")]
    (is (< (str/index-of text "=== source.md ===\nthe brief") (str/index-of text "=== 00-overview.md (not written) ===")))
    (is (str/ends-with? text "=== THE CHECKLIST (method.md §02, the plan-review pass) ===\n- check this"))))

(deftest findings-come-from-the-last-json-block
  (is (= [{:kind "contradiction" :where "01 §4" :finding "f" :evidence "e"}]
         (pr/parse-findings "reasoning\n```json\n{\"findings\": [{\"kind\": \"contradiction\", \"where\": \"01 §4\", \"finding\": \"f\", \"evidence\": \"e\", \"extra\": 1}]}\n```")))
  (is (= [] (pr/parse-findings "```json\n{\"findings\": []}\n```")))
  (is (nil? (pr/parse-findings "no block")))
  (is (nil? (pr/parse-findings "```json\n{\"verdict\": \"fine\"}\n```"))))

(deftest the-prompt-names-the-given-parts-and-allows-an-empty-list
  (is (str/includes? pr/system-prompt "GIVEN parts"))
  (is (str/includes? pr/system-prompt "An empty list is a valid answer"))
  (doseq [k pr/kinds] (is (str/includes? pr/system-prompt k))))

;; ---------------------------------------------------------------------------
;; the command, against a stub model
;; ---------------------------------------------------------------------------

(defn- with-stub [body f]
  (let [seen (atom [])
        stop (srv/run-server
              (fn [req]
                (swap! seen conj (json/parse-string (slurp (:body req)) true))
                {:status 200 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string
                        {:id "gen-1" :model "m-served" :choices [{:message {:content body}}]
                         :usage {:prompt_tokens 5 :completion_tokens 3}})})
              {:port 0 :legacy-return-value? false})]
    (try [(f (str "http://127.0.0.1:" (srv/server-port stop))) @seen]
         (finally (srv/server-stop! stop)))))

(defn- scratch-plan
  "A plan folder with five of the six documents (no decision log yet) and a
  profile whose :spec-reviewer is the stub."
  [endpoint]
  (let [plan (str (fs/create-temp-dir {:prefix "plan-review-"}))]
    (fs/create-dirs (fs/path plan "docs"))
    (doseq [nm (butlast pr/documents)]
      (spit (str (fs/path plan "docs" nm)) (str "# " nm "\n\nthe text of " nm "\n")))
    (spit (str (fs/path plan "profile.edn"))
          (pr-str {:seat :claude
                   :roles {:spec-reviewer {:family :stub :model "m" :shape :openai :endpoint endpoint
                                           :retry {:attempts 1 :interval-ms 1}}}}))
    plan))

(def ^:private answer
  "```json\n{\"findings\": [{\"kind\": \"contradiction\", \"where\": \"01-requirements.md §4; 02-architecture.md §12\", \"finding\": \"the store disagrees\", \"evidence\": \"SQLite / Postgres\"}, {\"kind\": \"deferred-without-owner\", \"where\": \"04-decision-log.md D3\", \"finding\": \"OPEN with no stage\", \"evidence\": \"decide later\"}]}\n```")

(deftest the-command-sends-the-documents-and-the-checklist-and-writes-the-review
  (let [[[out plan] seen] (with-stub answer
                            (fn [endpoint]
                              (let [plan (scratch-plan endpoint)]
                                [(with-out-str (pr/review! plan (str (fs/path plan "profile.edn")) method-path)) plan])))
        written (edn/read-string (slurp (str (fs/path plan "reviews" "plan-review.edn"))))]
    (is (= 2 (:count written)))
    (is (= "the store disagrees" (-> written :findings first :finding)))
    (is (= (vec (butlast pr/documents)) (:documents written)) "the documents that were there")
    (is (= "m-served" (:model written)))
    (is (= 1 (count (:reviews written))))
    (testing "the call's ids, endpoint and key variable are kept, so a late cost can be fetched by `bb reprice`"
      (is (= ["gen-1"] (:generation-ids written)))
      (is (string? (:endpoint written)))
      (is (= ["gen-1"] (:generation-ids (first (:reviews written))))))
    (is (str/includes? out "plan review: 2 findings"))
    (is (str/includes? out "[contradiction] 01-requirements.md §4; 02-architecture.md §12 — the store disagrees"))
    (is (str/includes? out "00-overview.md §5's table"))
    (testing "the model saw every document in order, the missing one named, and the checklist"
      (let [sent (json/generate-string (first seen))]
        (is (< (str/index-of sent "=== source.md ===") (str/index-of sent "=== 03-method-and-tooling.md ===")))
        (is (str/includes? sent "the text of 01-requirements.md"))
        (is (str/includes? sent "=== 04-decision-log.md (not written) ==="))
        (is (str/includes? sent "Deferred decisions with no owner"))
        (is (str/includes? sent "GIVEN parts") "the system prompt went too")))
    (testing "a second review keeps the first in the history"
      (with-stub "```json\n{\"findings\": []}\n```"
        (fn [endpoint]
          (spit (str (fs/path plan "profile.edn"))
                (pr-str {:seat :claude :roles {:spec-reviewer {:family :stub :model "m" :shape :openai :endpoint endpoint}}}))
          (with-out-str (pr/review! plan (str (fs/path plan "profile.edn")) method-path))))
      (let [again (edn/read-string (slurp (str (fs/path plan "reviews" "plan-review.edn"))))]
        (is (= 0 (:count again)))
        (is (= [2 0] (mapv :count (:reviews again))))))))

(deftest an-answer-with-no-block-writes-nothing
  (let [[[r plan] _] (with-stub "I have thoughts but no block."
                       (fn [endpoint]
                         (let [plan (scratch-plan endpoint)]
                           [(with-out-str (pr/review! plan (str (fs/path plan "profile.edn")) method-path)) plan])))]
    (is (str/includes? r "no findings block"))
    (is (not (fs/exists? (fs/path plan "reviews" "plan-review.edn"))))))

(deftest a-plan-with-no-documents-and-a-profile-with-no-reviewer-are-refused-by-name
  (let [plan (scratch-plan "http://127.0.0.1:1")]
    (spit (str (fs/path plan "no-reviewer.edn")) (pr-str {:seat :claude :roles {:coder {:family :anthropic}}}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :spec-reviewer role"
                          (pr/review! plan (str (fs/path plan "no-reviewer.edn")) method-path)))
    (fs/delete-tree (fs/path plan "docs"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"none of the plan's documents"
                          (pr/review! plan (str (fs/path plan "profile.edn")) method-path)))))
