(ns harness.contract.spec-review-test
  "The spec review: what it reads, what it parses, what the command writes and
  what `start` then records. The model is a local http-kit stub, as in
  harness.loop.triage-test."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.spec-review :as sr]
   [harness.loop.driver :as driver]
   [harness.money.report :as report]
   [org.httpkit.server :as srv]))

(def ^:private spec
  {:task/id "t-1"
   :blueprint/slice {:shapes '[Meta] :interfaces '[(path [meta])]}
   :files/impl ["src/app/routes.clj"]
   :files/context ["src/app/shapes.clj" "src/app/missing.clj"]
   :property-targets ["path returns \"index.html\" for the :home template"]})

(deftest the-input-is-the-spec-as-data-the-context-and-the-rules
  (let [text (sr/review-input spec "- rule one\n- rule two"
                              {"src/app/shapes.clj" "(ns app.shapes)" "src/app/missing.clj" nil})]
    (is (str/includes? text ":property-targets"))
    (is (str/includes? text "(ns app.shapes)"))
    (testing "a context file that does not exist yet is named as such, not silently empty"
      (is (str/includes? text "src/app/missing.clj (does not exist yet)")))
    (is (str/includes? text "- rule two"))
    (testing "the rules are labelled as the Reviewer's rendering, not as what every role reads"
      ;; The heading once claimed every role read this block; the review believed it and reported
      ;; the Reviewer's `you write nothing` as contradicting the Coder having a file to write.
      (is (str/includes? text "AS THE REVIEWER IS GIVEN THEM"))
      (is (not (str/includes? text "as every role reads them")))
      (is (str/includes? text "is not a finding")))))

(deftest findings-come-from-the-last-json-block
  (is (= [{:target 1 :kind "two-readings" :finding "f" :readings ["a" "b"]}]
         (sr/parse-findings "thinking...\n```json\n{\"findings\": [{\"target\": 1, \"kind\": \"two-readings\", \"finding\": \"f\", \"readings\": [\"a\", \"b\"], \"extra\": 1}]}\n```")))
  (testing "an empty list is an answer; no block, or a block without findings, is not"
    (is (= [] (sr/parse-findings "```json\n{\"findings\": []}\n```")))
    (is (nil? (sr/parse-findings "no block here")))
    (is (nil? (sr/parse-findings "```json\n{\"verdict\": \"approve\"}\n```")))))

(deftest the-prompt-carries-the-type-rule-and-allows-an-empty-list
  ;; With the rule only in the project's rules, every sample still called a vector's
  ;; order unspecified; in the review's own prompt, with the corollary, it stopped.
  (is (str/includes? sr/system-prompt "TYPES ARE FOLLOWED AS THE LANGUAGE DEFINES THEM"))
  (is (str/includes? sr/system-prompt "has the order of its construction"))
  (is (str/includes? sr/system-prompt "An empty list is a valid answer")))

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

(defn- scratch-run
  "A run directory with a spec and a profile whose :spec-reviewer is the stub."
  [endpoint]
  (let [root (str (fs/create-temp-dir {:prefix "spec-review-"}))
        run-dir (str (fs/path root "run"))
        project (str (fs/path root "project"))]
    (fs/create-dirs (fs/path project "src" "app"))
    (spit (str (fs/path project "src" "app" "shapes.clj")) "(ns app.shapes)")
    (fs/create-dirs run-dir)
    (spit (str (fs/path run-dir "spec.edn")) (str ";; a prediction nobody should see\n" (pr-str spec)))
    (spit (str (fs/path root "profile.edn"))
          (pr-str {:seat :claude
                   :roles {:spec-reviewer {:family :stub :model "m" :shape :openai :endpoint endpoint
                                           :retry {:attempts 1 :interval-ms 1}}}}))
    {:run-dir run-dir
     :config {:repo/root project :project/subdir nil :profile (str (fs/path root "profile.edn"))}}))

(deftest the-command-writes-the-review-and-start-records-its-count
  (let [answer "```json\n{\"findings\": [{\"target\": 1, \"kind\": \"two-readings\", \"finding\": \"home or not\", \"readings\": [\"x\", \"y\"]}, {\"target\": \"slice\", \"kind\": \"unmentioned-input\", \"finding\": \"nil meta\", \"readings\": [\"throw\", \"nil\"]}]}\n```"
        [[out ctx] seen] (with-stub answer (fn [endpoint]
                                             (let [ctx (scratch-run endpoint)]
                                               [(with-out-str (sr/spec-review! ctx)) ctx])))
        written (edn/read-string (slurp (str (fs/path (:run-dir ctx) "spec-review.edn"))))]
    (is (= 2 (:count written)))
    (is (= "home or not" (-> written :findings first :finding)))
    (is (str/includes? out "spec review: 2 findings"))
    (testing "the model saw the spec as data, the context file, and the rules - and not the Architect's comment"
      (let [sent (json/generate-string (first seen))]
        (is (str/includes? sent ":property-targets"))
        (is (str/includes? sent "(ns app.shapes)"))
        (is (str/includes? sent "does not exist yet"))
        (is (not (str/includes? sent "a prediction nobody should see")))))
    (testing "`start` records what it finds beside spec.edn, and the report prints the number"
      (let [e (sr/recorded (:run-dir ctx))]
        (is (= :spec-review (:event/kind e)))
        (is (= 2 (:findings e)))
        (is (str/includes? (report/render {:run/id "r" :task/id "t-1" :run/attempts 1 :run/status :done
                                           :run/cost nil :run/started-at (java.util.Date.)
                                           :run/steps [] :run/events [e]})
                           "Spec review before start: 2 findings"))))))

(deftest an-answer-with-no-block-writes-nothing
  ;; A count of zero from an answer that gave none would be recorded by `start` as
  ;; a clean review.
  (let [[ctx _] (with-stub "I have thoughts but no block." (fn [endpoint]
                                                             (let [ctx (scratch-run endpoint)]
                                                               (with-out-str (sr/spec-review! ctx))
                                                               ctx)))]
    (is (not (fs/exists? (fs/path (:run-dir ctx) "spec-review.edn"))))
    (is (nil? (sr/recorded (:run-dir ctx))))))

(deftest the-review-is-a-desk-step-and-refuses-a-started-run
  (let [ctx (scratch-run "http://127.0.0.1:1")]
    (spit (str (fs/path (:run-dir ctx) "state.edn")) "{}")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"desk step" (sr/spec-review! ctx)))))

;; ---------------------------------------------------------------------------
;; the loop reviews the spec before it dispatches it
;; ---------------------------------------------------------------------------

(deftest start-reviews-an-unreviewed-spec-and-stops-and-an-edited-spec-is-reviewed-again
  (let [answer "```json\n{\"findings\": [{\"target\": 1, \"kind\": \"two-readings\", \"finding\": \"f\", \"readings\": [\"a\", \"b\"]}]}\n```"]
    (with-stub answer
      (fn [endpoint]
        (let [ctx (assoc (scratch-run endpoint) :state (atom nil))
              spec-file (str (fs/path (:run-dir ctx) "spec.edn"))]
          (testing "no review yet: start runs one, prints it, and stops before provisioning anything"
            (let [e (try (with-out-str (driver/start! ctx)) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= :spec-reviewed (:run-loop/error (ex-data e))))
              (is (= 1 (:findings (ex-data e))))
              (is (fs/exists? (fs/path (:run-dir ctx) "spec-review.edn")))
              (is (not (fs/exists? (fs/path (:run-dir ctx) "state.edn"))) "nothing was started")))
          (testing "the review is of this spec"
            (is (sr/current? (:run-dir ctx) (edn/read-string (slurp spec-file)))))
          (testing "a comment edit changes nothing; a target edit makes it stale"
            (spit spec-file (str ";; a new comment\n" (pr-str spec)))
            (is (sr/current? (:run-dir ctx) (edn/read-string (slurp spec-file))))
            (spit spec-file (pr-str (assoc spec :files/context ["src/some/other_file.clj"])))
            (is (sr/current? (:run-dir ctx) (edn/read-string (slurp spec-file)))
                "which files a role is handed is not the contract: a context-only edit once bought a whole review")
            (spit spec-file (pr-str (assoc spec :property-targets ["something else"])))
            (is (not (sr/current? (:run-dir ctx) (edn/read-string (slurp spec-file))))))
          (testing "loop.edn can switch it off, for a deliberate re-dispatch or a test of what follows"
            (spit spec-file (pr-str (assoc spec :property-targets ["something else"])))
            (let [e (try (driver/start! (assoc-in ctx [:config :spec-review?] false)) nil
                         (catch Exception e e))]
              (is (not= :spec-reviewed (:run-loop/error (ex-data e)))
                  "whatever start fails on next, it is not the review"))))))))

(deftest a-spec-reviewed-twice-and-amended-again-stops-for-a-person-without-a-third-call
  (let [answer "```json\n{\"findings\": [{\"target\": 1, \"kind\": \"two-readings\", \"finding\": \"f\", \"readings\": [\"a\", \"b\"]}]}\n```"
        [_ seen] (with-stub answer
                   (fn [endpoint]
                     (let [ctx (assoc (scratch-run endpoint) :state (atom nil))
                           spec-file (str (fs/path (:run-dir ctx) "spec.edn"))
                           start (fn [] (try (with-out-str (driver/start! ctx)) nil
                                             (catch clojure.lang.ExceptionInfo e (:run-loop/error (ex-data e)))))]
                       (is (= :spec-reviewed (start)) "first wording: reviewed")
                       (spit spec-file (pr-str (assoc spec :property-targets ["amended once"])))
                       (is (= :spec-reviewed (start)) "second wording: reviewed")
                       (is (= 2 (sr/review-count (:run-dir ctx))) "the history keeps both")
                       (spit spec-file (pr-str (assoc spec :property-targets ["amended twice"])))
                       (is (= :spec-review-limit (start)) "third wording: a person's stop, not a third review"))))]
    (is (= 2 (count seen)) "the model was asked twice, not three times")))

(deftest a-review-that-found-nothing-neither-stops-the-loop-nor-counts-toward-the-limit
  ;; The stop exists so the list is read, and an empty list has no reader. And the limit is about
  ;; a contract that KEEPS drawing findings: a spec whose first reading was clean once reached it
  ;; on its first amendment.
  (testing "start carries on past a clean review"
    (with-stub "```json\n{\"findings\": []}\n```"
      (fn [endpoint]
        (let [ctx (assoc (scratch-run endpoint) :state (atom nil))
              e (try (with-out-str (driver/start! ctx)) nil (catch Exception e e))]
          (is (fs/exists? (fs/path (:run-dir ctx) "spec-review.edn")) "the review was made and written")
          (is (not= :spec-reviewed (:run-loop/error (ex-data e)))
              "whatever start fails on next in a scratch run, it is not a stop over an empty list")))))
  (testing "an answer with no findings block is not a clean review: it still stops"
    (with-stub "I have thoughts but no block."
      (fn [endpoint]
        (let [ctx (assoc (scratch-run endpoint) :state (atom nil))
              e (try (with-out-str (driver/start! ctx)) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= :spec-reviewed (:run-loop/error (ex-data e))))
          (is (str/includes? (ex-message e) "no findings block"))))))
  (testing "only reviews that found something count"
    (let [dir (str (fs/create-temp-dir))]
      (spit (str (fs/path dir "spec-review.edn"))
            (pr-str {:count 1 :reviews [{:count 0} {:count 3} {:count 1}]}))
      (is (= 3 (sr/review-count dir)))
      (is (= 2 (sr/reviews-with-findings dir))))))

(deftest the-recorded-event-carries-every-review-not-only-the-last
  ;; `start` records the desk step from the file beside spec.edn. It kept one review when a spec
  ;; could only have one; a spec read twice then went into its record with the second reading alone.
  (let [dir (str (fs/create-temp-dir))
        file (str (fs/path dir "spec-review.edn"))]
    (is (nil? (sr/recorded dir)) "no file, no event")
    (spit file (pr-str {:findings [{:finding "f"}] :count 1 :cost 0.08 :model "m" :at "t2" :spec-hash 2
                        :reviews [{:count 3 :cost 0.06 :model "m" :at "t1" :spec-hash 1 :no-block? false}
                                  {:count 1 :cost 0.08 :model "m" :at "t2" :spec-hash 2 :no-block? false}]}))
    (let [e (sr/recorded dir)]
      (is (= {:event/kind :spec-review :findings 1 :cost 0.08 :model "m" :at "t2"} (dissoc e :reviews))
          "the top-level keys are still the review that was current at dispatch")
      (is (= [{:count 3 :cost 0.06 :model "m" :at "t1"} {:count 1 :cost 0.08 :model "m" :at "t2"}] (:reviews e))
          "every reading, in order, without the hash or the findings"))
    (testing "a file written before the history was kept still records, without the key"
      (spit file (pr-str {:findings [] :count 0 :cost 0.05 :model "m" :at "t"}))
      (is (= {:event/kind :spec-review :findings 0 :cost 0.05 :model "m" :at "t"} (sr/recorded dir))))))
