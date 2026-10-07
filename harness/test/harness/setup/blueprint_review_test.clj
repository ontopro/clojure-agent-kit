(ns harness.setup.blueprint-review-test
  "The Blueprint review: what it sends, where it writes, what it refuses. The
  model is a local http-kit stub, as in harness.setup.plan-review-test."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.blueprint-review :as br]
   [org.httpkit.server :as srv]))

(def kit-dir (str (fs/parent (fs/real-path "."))))
(def method-path (str (fs/path kit-dir "method.md")))

(deftest the-checklist-is-step-2-and-section-06s-rules-read-from-the-method
  (let [c (br/checklist (slurp method-path))]
    (is (str/starts-with? c "### Step 2 · Strategic planning"))
    (is (str/includes? c "over-engineered"))
    (is (not (str/includes? c "### Step 2½")) "the step ends before the spec review's")
    (is (str/includes? c "### Data shapes first"))
    (is (str/includes? c "One promise per target"))
    (is (not (str/includes? c "### Per-role deltas")) "§06 ends before the per-role deltas")
    (is (< (str/index-of c "### Step 2") (str/index-of c "### Data shapes first")) "the question, then the rules"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no '### Step 2 · Strategic planning'"
                        (br/checklist "# a method without it\n### Data shapes first\n### Per-role deltas")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no '### Data shapes first'"
                        (br/checklist "### Step 2 · Strategic planning\n### Step 2½\n"))))

(deftest the-stage-document-is-the-one-the-blueprints-header-names
  (is (= "stage-1-vertical-slice.md"
         (br/stage-document-name "# Stage 1 Blueprint\n\n**Input:** `stage-1-vertical-slice.md` · **Method:** `../03.md`\n")))
  (is (nil? (br/stage-document-name "# a Blueprint with no Input line"))))

(deftest the-reviews-folder-is-the-stage
  (is (= "stage-1" (br/stage-key "/p/docs/stages/stage-1-blueprint.md")))
  (is (= "stage-2-search" (br/stage-key "/p/docs/stages/stage-2-search-blueprint.md")))
  (is (= "odd-name" (br/stage-key "/p/docs/stages/odd-name.md"))))

(deftest the-input-is-the-stage-document-then-the-blueprint-then-the-checklist
  (let [text (br/review-input "stage-1-x.md" "the stage" "stage-1-blueprint.md" "the blueprint" "- the rules")]
    (is (< (str/index-of text "=== stage-1-x.md ===\nthe stage")
           (str/index-of text "=== stage-1-blueprint.md ===\nthe blueprint")
           (str/index-of text "=== THE CHECKLIST")))
    (is (str/ends-with? text "- the rules")))
  (is (str/starts-with? (br/review-input "stage-1-x.md" nil "b.md" "b" "c") "=== stage-1-x.md (not found) ===")
      "a named stage document that is not there is said so, and the read goes on"))

(deftest the-prompt-asks-both-questions-and-names-the-given-parts
  (is (str/includes? br/system-prompt "OVER-ENGINEERED"))
  (is (str/includes? br/system-prompt "one promise per target"))
  (is (str/includes? br/system-prompt "GIVEN"))
  (is (str/includes? br/system-prompt "An empty list is a valid answer"))
  (doseq [k br/kinds] (is (str/includes? br/system-prompt k))))

;; ---------------------------------------------------------------------------
;; the command, against a stub model
;; ---------------------------------------------------------------------------

(defn- with-stub [body f]
  (let [seen (atom [])
        stop (srv/run-server
              (fn [req]
                (swap! seen conj (json/parse-string (slurp (:body req)) true))
                {:status 200 :headers {"Content-Type" "application/json" "Connection" "close"}
                 :body (json/generate-string
                        {:id "gen-1" :model "m-served" :choices [{:message {:content body}}]
                         :usage {:prompt_tokens 5 :completion_tokens 3}})})
              {:port 0 :legacy-return-value? false})]
    (try [(f (str "http://127.0.0.1:" (srv/server-port stop))) @seen]
         (finally @(srv/server-stop! stop)))))

(defn- scratch-plan
  "A plan with one stage document and its Blueprint under docs/stages/, and a
  profile whose :blueprint-reviewer is the stub. Returns the plan and the
  Blueprint's path."
  [endpoint]
  (let [plan (str (fs/create-temp-dir {:prefix "blueprint-review-"}))
        stages (fs/path plan "docs" "stages")]
    (fs/create-dirs stages)
    (spit (str (fs/path stages "stage-1-slice.md")) "# Stage 1 — Slice\n\nthe stage's goal and its task list\n")
    (spit (str (fs/path stages "stage-1-blueprint.md"))
          "# Stage 1 Blueprint — Slice\n\n**Input:** `stage-1-slice.md` · **Method:** `../03.md`\n\n## 1. Data shapes\n\nthe shapes\n")
    (spit (str (fs/path plan "profile.edn"))
          (pr-str {:seat :claude
                   :roles {:blueprint-reviewer {:family :stub :model "m" :shape :openai :endpoint endpoint
                                                :retry {:attempts 1 :interval-ms 1}}}}))
    [plan (str (fs/path stages "stage-1-blueprint.md"))]))

(def ^:private answer
  "```json\n{\"findings\": [{\"kind\": \"over-engineered\", \"where\": \"§3 app.cache\", \"finding\": \"a cache no task needs\", \"evidence\": \"cache the whole site\"}, {\"kind\": \"target-two-promises\", \"where\": \"t-02 target 1\", \"finding\": \"two promises\", \"evidence\": \"renders and escapes\"}]}\n```")

(deftest the-command-sends-the-stage-document-and-the-blueprint-and-writes-under-the-stage
  (let [[[out plan] seen] (with-stub answer
                            (fn [endpoint]
                              (let [[plan bp] (scratch-plan endpoint)]
                                [(with-out-str (br/review! bp (str (fs/path plan "profile.edn")) method-path)) plan])))
        written (edn/read-string (slurp (str (fs/path plan "reviews" "stage-1" "blueprint-review.edn"))))]
    (is (= 2 (:count written)))
    (is (= "a cache no task needs" (-> written :findings first :finding)))
    (is (= ["stage-1-slice.md" "stage-1-blueprint.md"] (:documents written)))
    (is (= "m-served" (:model written)))
    (is (= 1 (count (:reviews written))))
    (is (str/includes? out "blueprint review: 2 findings"))
    (is (str/includes? out "[over-engineered] §3 app.cache — a cache no task needs"))
    (is (str/includes? out "then sign the Blueprint off"))
    (testing "the model saw the stage document, then the Blueprint, then both sections of the method"
      (let [sent (json/generate-string (first seen))]
        (is (< (str/index-of sent "the stage's goal and its task list")
               (str/index-of sent "the shapes")
               (str/index-of sent "Strategic planning")))
        (is (str/includes? sent "One promise per target"))
        (is (str/includes? sent "OVER-ENGINEERED") "the system prompt went too")))
    (testing "a second read keeps the first in the history"
      (with-stub "```json\n{\"findings\": []}\n```"
        (fn [endpoint]
          (spit (str (fs/path plan "profile.edn"))
                (pr-str {:seat :claude :roles {:blueprint-reviewer {:family :stub :model "m" :shape :openai :endpoint endpoint}}}))
          (with-out-str (br/review! (str (fs/path plan "docs" "stages" "stage-1-blueprint.md"))
                                    (str (fs/path plan "profile.edn")) method-path))))
      (let [again (edn/read-string (slurp (str (fs/path plan "reviews" "stage-1" "blueprint-review.edn"))))]
        (is (= 0 (:count again)))
        (is (= [2 0] (mapv :count (:reviews again))))))))

(deftest a-missing-stage-document-is-named-and-the-read-goes-on
  (let [[[written] seen] (with-stub answer
                           (fn [endpoint]
                             (let [[plan bp] (scratch-plan endpoint)]
                               (fs/delete (fs/path plan "docs" "stages" "stage-1-slice.md"))
                               [(with-out-str (br/review! bp (str (fs/path plan "profile.edn")) method-path))])))]
    (is (str/includes? written "blueprint review: 2 findings"))
    (is (str/includes? (json/generate-string (first seen)) "=== stage-1-slice.md (not found) ==="))))

(deftest an-answer-with-no-block-writes-nothing
  (let [[[r plan] _] (with-stub "I have thoughts but no block."
                       (fn [endpoint]
                         (let [[plan bp] (scratch-plan endpoint)]
                           [(with-out-str (br/review! bp (str (fs/path plan "profile.edn")) method-path)) plan])))]
    (is (str/includes? r "no findings block"))
    (is (not (fs/exists? (fs/path plan "reviews" "stage-1" "blueprint-review.edn"))))))

(deftest a-blueprint-outside-docs-stages-and-a-profile-without-the-role-are-refused-by-name
  (let [[plan bp] (scratch-plan "http://127.0.0.1:1")
        elsewhere (str (fs/path plan "stage-1-blueprint.md"))]
    (spit elsewhere "# a Blueprint in the wrong place")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lives at <plan>/docs/stages/"
                          (br/review! elsewhere (str (fs/path plan "profile.edn")) method-path)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not exist"
                          (br/plan-dir-of (str (fs/path plan "docs" "stages" "no-such-blueprint.md")))))
    (spit (str (fs/path plan "no-role.edn")) (pr-str {:seat :claude :roles {:coder {:family :anthropic}}}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :blueprint-reviewer role"
                          (br/review! bp (str (fs/path plan "no-role.edn")) method-path)))))
