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

(deftest the-checklist-names-the-three-findings-of-a-stages-plan-step
  ;; The three the plan step is for, in the method and in the prompt's kinds:
  ;; what a plan that commits to one stage at a time gets wrong.
  (let [c (pr/checklist (slurp method-path))]
    (is (str/includes? c "Requirements written for a stage not yet pulled"))
    (is (str/includes? c "A risk with no owning stage"))
    (is (str/includes? c "A lesson of the last stage this document does not answer")))
  (is (= ["requirement-for-stage-not-pulled" "risk-without-owning-stage" "lesson-unanswered"] (take 3 pr/kinds)))
  (is (str/includes? pr/system-prompt "STAGE PLAN under review first") "a later stage's reading is the stage plan first")
  (is (str/includes? pr/system-prompt "The empty lessons document of a plan before stage 0 ends is not one")))

(deftest the-input-is-every-document-in-order-then-the-checklist
  (let [text (pr/review-input [["source.md" "the brief"] ["00-overview.md" nil]] "- check this")]
    (is (< (str/index-of text "=== source.md ===\nthe brief") (str/index-of text "=== 00-overview.md (not written) ===")))
    (is (str/ends-with? text "=== THE CHECKLIST (method.md §02, the plan-review pass) ===\n- check this"))))

(deftest a-stage-plan-is-read-first-and-keyed-by-its-stage
  (let [text (pr/review-input ["stage-1-skeleton.md" "the skeleton"] [["source.md" "the brief"]] "- check this")]
    (is (str/starts-with? text "=== stages/stage-1-skeleton.md (THE STAGE PLAN UNDER REVIEW) ===\nthe skeleton"))
    (is (< (str/index-of text "THE STAGE PLAN UNDER REVIEW") (str/index-of text "=== source.md ==="))))
  (is (= "stage-1" (pr/stage-key "docs/stages/stage-1-skeleton.md")) "as the blueprint review keys stage-1-blueprint.md")
  (is (= "stage-0" (pr/stage-key "stage-0-spike.md")))
  (is (= "stage-12" (pr/stage-key "stage-12-hosting.md")))
  (is (= "hosting" (pr/stage-key "hosting.md")) "a file not named by its stage keeps its name"))

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
  "A plan folder with five of the seven documents (no decision log, no lessons yet) and a
  profile whose :plan-reviewer is the stub."
  [endpoint]
  (let [plan (str (fs/create-temp-dir {:prefix "plan-review-"}))]
    (fs/create-dirs (fs/path plan "docs"))
    (doseq [nm (drop-last 2 pr/documents)]
      (spit (str (fs/path plan "docs" nm)) (str "# " nm "\n\nthe text of " nm "\n")))
    (spit (str (fs/path plan "profile.edn"))
          (pr-str {:seat :claude
                   :roles {:plan-reviewer {:family :stub :model "m" :shape :openai :endpoint endpoint
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
    (is (= (vec (drop-last 2 pr/documents)) (:documents written)) "the documents that were there")
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
        (is (str/includes? sent "=== 05-lessons.md (not written) ===") "the lessons, last")
        (is (str/includes? sent "Deferred decisions with no owner"))
        (is (str/includes? sent "GIVEN parts") "the system prompt went too")))
    (testing "a second review keeps the first in the history"
      (with-stub "```json\n{\"findings\": []}\n```"
        (fn [endpoint]
          (spit (str (fs/path plan "profile.edn"))
                (pr-str {:seat :claude :roles {:plan-reviewer {:family :stub :model "m" :shape :openai :endpoint endpoint}}}))
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

(deftest a-stages-reading-sends-the-stage-plan-first-and-writes-under-the-stages-folder
  ;; Every stage from 1 reads its stage plan cold before the blueprint is cut
  ;; from it: the stage plan first, the seven documents as the context it
  ;; revised, the record beside the stage's other reviews.
  (let [[[out plan stage-plan] seen]
        (with-stub answer
          (fn [endpoint]
            (let [plan (scratch-plan endpoint)
                  stage-plan (str (fs/path plan "docs" "stages" "stage-1-skeleton.md"))]
              (fs/create-dirs (fs/parent stage-plan))
              (spit stage-plan "# Stage 1 - the walking skeleton\n\nthe text of the stage plan\n")
              [(with-out-str (pr/review! plan (str (fs/path plan "profile.edn")) method-path stage-plan)) plan stage-plan])))
        written (edn/read-string (slurp (str (fs/path plan "reviews" "stage-1" "plan-review.edn"))))]
    (is (= 2 (:count written)))
    (is (= "stage-1-skeleton.md" (:stage written)) "the record says which stage plan it read")
    (is (= (vec (drop-last 2 pr/documents)) (:documents written)) "the seven documents are its context")
    (is (not (fs/exists? (fs/path plan "reviews" "plan-review.edn"))) "stage 0's record is not touched")
    (is (str/includes? out "reviews/stage-1/plan-review.edn"))
    (is (str/includes? out "and the stage plan"))
    (let [sent (json/generate-string (first seen))]
      (is (< (str/index-of sent "stages/stage-1-skeleton.md (THE STAGE PLAN UNDER REVIEW)") (str/index-of sent "=== source.md ==="))
          "the stage plan is read first")
      (is (str/includes? sent "the text of the stage plan"))
      (is (str/includes? sent "A lesson of the last stage this document does not answer") "the checklist went too"))
    (is (= plan (pr/plan-dir-of stage-plan)) "the plan is the folder above docs/stages/")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no stage plan at"
                          (pr/review! plan (str (fs/path plan "profile.edn")) method-path
                                      (str (fs/path plan "docs" "stages" "stage-2-missing.md")))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a stage plan lives at"
                          (pr/plan-dir-of (str (fs/path plan "docs" "source.md")))))))

(deftest a-plan-with-no-documents-and-a-profile-with-no-plan-reviewer-are-refused-by-name
  (let [plan (scratch-plan "http://127.0.0.1:1")]
    (spit (str (fs/path plan "no-reviewer.edn")) (pr-str {:seat :claude :roles {:coder {:family :anthropic}}}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :plan-reviewer role"
                          (pr/review! plan (str (fs/path plan "no-reviewer.edn")) method-path)))
    (fs/delete-tree (fs/path plan "docs"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"none of the plan's documents"
                          (pr/review! plan (str (fs/path plan "profile.edn")) method-path)))))
