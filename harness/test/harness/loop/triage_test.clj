(ns harness.loop.triage-test
  "Model triage against a stub model server — the verdict, the prompt, every
  fallback path, and the leak policy. Zero quota: the one call it makes goes
  to a local http-kit server that answers with what the test says."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.loop.triage :as triage]
   [org.httpkit.server :as srv]))

;; ---------------------------------------------------------------------------
;; the fixture
;; ---------------------------------------------------------------------------

(defn- with-stub
  "A stub model server answering every request with `body` at `status`.
  Returns `[result requests-seen]`, the requests decoded."
  [status body f]
  (let [seen (atom [])
        stop (srv/run-server
              (fn [req]
                (swap! seen conj (assoc (select-keys req [:uri]) :body (json/parse-string (slurp (:body req)) true)))
                {:status status
                 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string body)})
              {:port 0 :legacy-return-value? false})]
    (try [(f (str "http://127.0.0.1:" (srv/server-port stop))) @seen]
         (finally (srv/server-stop! stop)))))

(defn- text-reply [s]
  {:id "gen-1" :model "m-served" :choices [{:message {:content s}}]
   :usage {:prompt_tokens 5 :completion_tokens 3}})

(defn- profile [endpoint]
  {:seat :claude
   :roles {:orchestrator {:family :stub :model "m" :shape :openai :endpoint endpoint}}})

(def ^:private no-retry {:retry {:attempts 1 :interval-ms 1}})

(def ^:private spec
  {:task/id "t-42"
   :task/title "clamp"
   :blueprint/slice {:shapes [] :interfaces ['(clamp [x lo hi])]}
   :property-targets ["result within [lo, hi]"]
   :files/impl ["src/scratch/x.clj"]
   :files/test ["test/scratch/x_test.clj"]})

(def ^:private impl-names
  "What `sigs/impl-names` would find in the Coder's file below: the one var
  the slice does not grant."
  [{:ns 'scratch.x :name 'widen}])

(def ^:private failing-test-gate
  {:gates/passed? false
   :gates/failed :test
   :gates/report [{:gate :fmt :status :pass :exit 0 :out "" :ms 1}
                  {:gate :test :status :fail :exit 1 :ms 1
                   :out "Testing scratch.x-test\nFAIL in (clamp-test) (x_test.clj:9)\nexpected 3, got nil"}]})

(defn- worktrees
  "Two role worktrees with the files as each role wrote them."
  []
  (let [coder (str (fs/create-temp-dir)) tester (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path coder "src/scratch"))
    (fs/create-dirs (fs/path tester "test/scratch"))
    (spit (str (fs/path coder "src/scratch/x.clj")) "(ns scratch.x)\n(defn- widen [x] x)\n(defn clamp [x lo hi] (widen x))")
    (spit (str (fs/path tester "test/scratch/x_test.clj")) "(ns scratch.x-test)")
    {:coder coder :tester tester}))

(defn- red-gate []
  {:trigger :red-gate :spec spec :dependents [] :worktrees (worktrees)
   :payload {:gate-result failing-test-gate}})

(defn- note
  "A pause after `after`; on a Coder note the Tester has written nothing yet."
  [after]
  {:trigger :note :spec spec :dependents [] :worktrees (cond-> (worktrees) (= :coder after) (assoc :tester (str (fs/create-temp-dir))))
   :payload {:after after :next (if (= :coder after) :tester :check)
             :notes ["the targets say nothing about NaN"]}})

(defn- on-gate
  "The least of a red-gate trigger `feedback-for` reads."
  [gate-result]
  {:trigger :red-gate :payload {:gate-result gate-result}})

(defn- rejection
  "A review that rejected, with findings that quote the implementation."
  []
  {:trigger :rejection :spec spec :dependents [] :worktrees (worktrees)
   :payload {:verdict {:verdict :reject :reasons ["clamp ignores hi when x is NaN"]}
             :findings "FINDING 1: `(widen x)` in src/scratch/x.clj never compares against hi."
             :notes ["the targets say nothing about NaN"]}})

(defn- block [m]
  (str "```json\n" (json/generate-string m) "\n```"))

(defn- ask
  "One triage call over a stub answering `body`. Returns `[verdict requests]`."
  ([trigger body] (ask trigger 200 body))
  ([trigger status body]
   (with-stub status body
     (fn [endpoint] ((triage/model-triage (profile endpoint) no-retry) trigger)))))

;; ---------------------------------------------------------------------------
;; the verdict
;; ---------------------------------------------------------------------------

(deftest the-model-s-verdict-is-routed-and-the-prompt-carried-what-it-needed
  (let [[r reqs] (ask (red-gate) (text-reply (str "The generator violates the contract.\n"
                                                  (block {:route "tester"
                                                          :reason "generator emits lo > hi"
                                                          :guidance "Constrain the generator to lo <= hi"}))))]
    (is (= :tester (:route r)))
    (is (= "generator emits lo > hi" (:reason r)))
    (is (= "Constrain the generator to lo <= hi" (:guidance r)))
    (is (nil? (:triage/fallback r)))
    (testing "the call is recorded like a dispatch"
      (is (= :done (:status r)))
      (is (nat-int? (:ms r)))
      (is (= "m-served" (get-in r [:result :runner/meta :model])))
      (is (= 1 (get-in r [:result :runner/meta :completions])) "one completion")
      (is (str/includes? (:answer r) "generator violates")))
    (testing "the prompt carries the contract, the gate output, and both files as written"
      (let [body (:body (first reqs))
            prompt (get-in body [:messages 1 :content])]
        (is (= 1 (count reqs)) "one request, no tool round trips")
        (is (= "system" (get-in body [:messages 0 :role])))
        (is (not (contains? body :tools)) "no tools declared: the model reads the prompt and nothing else")
        (is (= prompt (:prompt r)) "and what was asked is returned, for the run directory")
        (is (str/includes? prompt "clamp [x lo hi]") "contract slice")
        (is (str/includes? prompt "result within [lo, hi]") "property targets")
        (is (str/includes? prompt "expected 3, got nil") "gate output")
        (is (str/includes? prompt "(defn clamp [x lo hi] (widen x))") "impl as written")
        (is (str/includes? prompt "(ns scratch.x-test)") "tests as written")
        (is (str/includes? prompt "\"route\": \"architect|coder|human|tester\"") "the routes a red gate offers")
        (is (not (str/includes? prompt "- continue:")) "and not the one it does not")))))

(deftest an-answer-with-no-verdict-falls-back-to-the-driver-s-proposal
  (let [[r _] (ask (red-gate) (text-reply "I think the coder should fix it."))]
    (is (= :coder (:route r)) "the proposal's default for a failure in the task's own test")
    (is (= "no JSON verdict in the answer" (:triage/fallback r)))
    (is (str/starts-with? (:reason r) "fallback to the driver's proposal:"))
    (is (= :coder (get-in r [:proposal :retry-role])) "and the proposal itself travels with it")
    (is (nil? (:guidance r)) "a proposal has no guidance to give the role")
    (is (= "I think the coder should fix it." (:answer r)) "what the model said is still kept")))

(deftest a-route-the-trigger-does-not-offer-falls-back
  (let [[r _] (ask (red-gate) (text-reply (block {:route "wizard" :reason "?"})))]
    (is (= :coder (:route r)))
    (is (str/starts-with? (:triage/fallback r) "route wizard is not open on this red-gate")))
  (testing "`continue` is a note's route, not a red gate's"
    (let [[r _] (ask (red-gate) (text-reply (block {:route "continue" :reason "fine"})))]
      (is (= :coder (:route r)))
      (is (str/includes? (:triage/fallback r) "route continue is not open")))))

(deftest an-api-error-falls-back-and-says-so
  (let [[r _] (ask (red-gate) 500 {:error {:message "boom"}})]
    (is (= :coder (:route r)))
    (is (str/starts-with? (:triage/fallback r) "the triage call failed:"))
    (is (str/includes? (:triage/fallback r) "boom"))
    (is (= :failed (:status r)) "and the report step will say the call failed")))

(deftest an-unreachable-endpoint-falls-back-rather-than-throwing
  (let [r ((triage/model-triage (profile "http://127.0.0.1:1") (assoc no-retry :timeout-ms 300)) (red-gate))]
    (is (= :coder (:route r)))
    (is (some? (:triage/fallback r)))))

(deftest the-architect-route-passes-through
  ;; The route no mechanical rule ever proposes, and the reason the model is asked.
  (let [[r _] (ask (red-gate) (text-reply (block {:route "architect"
                                                  :reason "the slice is ambiguous about lo > hi"
                                                  :guidance "Specify the behaviour for inverted bounds"})))]
    (is (= :architect (:route r)))
    (is (nil? (:triage/fallback r)))))

(deftest a-profile-without-an-orchestrator-is-refused-up-front
  (is (thrown-with-msg? Exception #"no :orchestrator role"
                        (triage/model-triage {:seat :claude :roles {}}))))

;; ---------------------------------------------------------------------------
;; notes
;; ---------------------------------------------------------------------------

(deftest a-note-can-be-continued-or-sent-to-the-architect
  (let [[r reqs] (ask (note :coder) (text-reply (block {:route "continue"
                                                        :reason "an observation; the targets stand"})))]
    ;; THE FALLBACK REASON IS THE MESSAGE. This assertion failed once in a full `bb gates`
    ;; run and passed on the next two: the stub saw no request and triage fell back to
    ;; :human, and nothing printed why. The next time it happens, it will.
    (is (= :continue (:route r)) (str "fell back: " (pr-str (:triage/fallback r))))
    (testing "the prompt says who left the note, what it said, and what the loop paused before"
      (let [prompt (get-in (first reqs) [:body :messages 1 :content])]
        (is (str/includes? prompt "the coder finished and left 1 note(s)"))
        (is (str/includes? prompt "paused before dispatching the Tester"))
        (is (str/includes? prompt "- the targets say nothing about NaN"))
        (is (str/includes? prompt "- continue:"))
        (is (str/includes? prompt "\"route\": \"architect|coder|continue|human|tooling\"") "the noting role, and not the other")
        (is (str/includes? prompt "[not on disk — this role has not written it]") "the Tester's file does not exist yet"))))
  (let [[r _] (ask (note :coder) (text-reply (block {:route "architect" :reason "the targets contradict"})))]
    (is (= :architect (:route r)))))

(deftest tooling-is-offered-on-a-note-always-and-on-a-red-gate-only-when-the-failure-is-nobody-s
  ;; Row 12's gap on a note, row 61's on a gate: a merged file no role owns, the build, the
  ;; harness. On a red gate the offer follows the driver's proposal, so the model is not
  ;; handed an exit from every hard call.
  (let [foreign-gate {:gates/passed? false :gates/failed :test
                      :gates/report [{:gate :test :status :fail :exit 1 :ms 1
                                      :out "FAIL in scratch.util-test/clamp-above-hi-spec (util_test.clj:46)\nexpected true"}]}
        foreign (assoc (red-gate) :payload {:gate-result foreign-gate})]
    (is (not (contains? (triage/open-routes (red-gate)) :tooling)) "the run's own test failing: not offered")
    (is (contains? (triage/open-routes foreign) :tooling))
    (is (not (contains? (triage/open-routes (rejection)) :tooling)) "the gates were green")
    (let [[r reqs] (ask foreign (text-reply (block {:route "tooling" :reason "a merged property test no role here owns"})))]
      (is (= :tooling (:route r)) (str "fell back: " (pr-str (:triage/fallback r))))
      (let [prompt (get-in (first reqs) [:body :messages 1 :content])]
        (is (str/includes? prompt "- tooling: the fault is the machine's"))
        (is (str/includes? prompt "\"route\": \"architect|coder|human|tester|tooling\""))))
    (let [[r reqs] (ask (red-gate) (text-reply (block {:route "tooling" :reason "nope"})))]
      (is (= :coder (:route r)) "not offered, so the verdict falls back to the proposal")
      (is (str/includes? (:triage/fallback r) "route tooling is not open"))
      (is (not (str/includes? (get-in (first reqs) [:body :messages 1 :content]) "- tooling:"))))
    (testing "the fallback on a foreign failure is the tooling stop itself"
      (is (= :tooling (:route (triage/fallback foreign "no verdict")))))))

(deftest only-the-role-that-left-the-note-can-be-routed-on-it
  (is (= #{:continue :architect :tooling :human :tester} (triage/open-routes (note :tester))))
  (let [[r _] (ask (note :tester) (text-reply (block {:route "coder" :reason "the impl is wrong"})))]
    (is (= :human (:route r)) "a note falls back to a person, not to the proposal")
    (is (str/includes? (:triage/fallback r) "route coder is not open on this note"))))

;; ---------------------------------------------------------------------------
;; a rejected review
;; ---------------------------------------------------------------------------

(deftest a-rejection-is-routed-and-the-prompt-carries-the-review
  (let [[r reqs] (ask (rejection) (text-reply (block {:route "coder"
                                                      :reason "the defect is ruled out by target 1"
                                                      :guidance "compare against hi as well as lo"})))]
    (is (= :coder (:route r)))
    (is (nil? (:triage/fallback r)))
    (let [prompt (get-in (first reqs) [:body :messages 1 :content])]
      (is (str/includes? prompt "every gate went GREEN"))
      (is (str/includes? prompt "REJECTED the change"))
      (is (str/includes? prompt "- clamp ignores hi when x is NaN") "the verdict's reasons")
      (is (str/includes? prompt "FINDING 1:") "the findings in full")
      (is (str/includes? prompt "- the targets say nothing about NaN") "and the Reviewer's note about the contract")
      (is (str/includes? prompt "(defn clamp [x lo hi] (widen x))") "impl as written")
      (is (str/includes? prompt "your guidance is ALL it will be sent"))
      (is (str/includes? prompt "\"route\": \"architect|coder|human|tester\""))
      (is (not (str/includes? prompt "- continue:")) "a rejection cannot be continued past"))))

(deftest a-rejection-the-model-cannot-route-is-a-persons
  ;; The mechanical proposal reads a gate's output, and a rejection has none.
  (let [[r _] (ask (rejection) (text-reply "The coder, probably."))]
    (is (= :human (:route r)))
    (is (= "no JSON verdict in the answer" (:triage/fallback r)))
    (is (nil? (:proposal r))))
  (let [[r _] (ask (rejection) (text-reply (block {:route "continue" :reason "taste"})))]
    (is (= :human (:route r)))
    (is (str/includes? (:triage/fallback r) "route continue is not open on this rejection"))))

(deftest a-rejection-s-findings-reach-the-coder-and-never-the-tester
  (let [trg (rejection)]
    (testing "the Coder: guidance first, then the Reviewer's own text"
      (let [fb (triage/feedback-for {:route :coder :reason "r" :guidance "compare against hi"} trg)]
        (is (= [:triage :reviewer] (mapv :feedback/from fb)))
        (is (str/starts-with? (:feedback/text (second fb)) "FINDING 1:"))))
    (testing "the Tester: the guidance alone, which then passes the shield on its own words"
      (let [v {:route :tester :reason "r" :guidance "no test passes NaN; the contract grants clamp"}
            fb (triage/feedback-for v trg)]
        (is (= [:triage] (mapv :feedback/from fb)))
        (is (= :tester (:route (triage/shield v spec impl-names fb))))))
    (testing "and guidance that repeats the finding is escalated like any other leak"
      (let [v {:route :tester :reason "r" :guidance "widen never compares against hi"}
            r (triage/shield v spec impl-names (triage/feedback-for v trg))]
        (is (= :human (:route r)))
        (is (= [:impl-var] (mapv :leak (:leaks (:leak-check r)))))))))

;; ---------------------------------------------------------------------------
;; the leak policy
;; ---------------------------------------------------------------------------

(deftest a-rejection-asks-whether-the-input-can-occur-and-shows-the-project-s-conventions
  ;; Triage is shown no rules - except, on a rejection, the one that says where input comes from.
  (let [filled [{:id :data-conventions :text "Content comes from an EDN file and nowhere else."}]
        standing [{:id :data-conventions :text "<Say once, for every role, what this data is.>"}]]
    (testing "the rule's text once it is filled; nothing while its placeholder stands"
      (is (= "Content comes from an EDN file and nowhere else." (triage/data-conventions filled)))
      (is (nil? (triage/data-conventions standing)))
      (is (nil? (triage/data-conventions []))))
    (testing "a filled rule reaches the rejection prompt, with the question before it"
      (let [p (with-redefs [triage/data-conventions (fn [& _] (:text (first filled)))]
                (triage/render-prompt (rejection)))]
        (is (str/includes? p "ask whether the input the finding turns on CAN OCCUR"))
        (is (str/includes? p "is the architect's"))
        (is (str/includes? p "Content comes from an EDN file and nowhere else."))))
    (testing "with none stated, triage is told no input is out of reach"
      (let [p (with-redefs [triage/data-conventions (fn [& _] nil)]
                (triage/render-prompt (rejection)))]
        (is (str/includes? p "stated no data conventions, so no input is out of reach"))))
    (testing "a red gate is not asked the question: its failure is observed, not argued"
      (is (not (str/includes? (triage/render-prompt (red-gate)) "CAN OCCUR"))))))

(deftest tester-guidance-that-names-the-implementation-is-escalated-not-sent
  (let [v {:route :tester :reason "the test misreads the contract"
           :guidance "widen is applied before the bounds are checked; test the raw x"}
        fb (triage/feedback-for v (on-gate failing-test-gate))
        r (triage/shield v spec impl-names fb)]
    (is (= :human (:route r)))
    (is (= :tester (:routed r)) "what triage said is kept beside what the loop did")
    (is (= [:impl-var] (mapv :leak (:leaks (:leak-check r)))))
    (is (str/includes? (:reason r) "names the implementation"))
    (is (str/includes? (:reason r) "scratch.x/widen"))
    (is (str/includes? (:reason r) "the test misreads the contract") "the model's reason is still there")))

(deftest tester-guidance-that-names-only-the-contract-passes
  (let [v {:route :tester :reason "the generator violates the precondition" :guidance "Constrain lo <= hi; clamp is the granted name"}
        r (triage/shield v spec impl-names (triage/feedback-for v (on-gate failing-test-gate)))]
    (is (= :tester (:route r)))
    (is (= [] (:leaks (:leak-check r))) "clean, and the check is recorded as run")
    (is (nil? (:routed r)))))

(deftest the-gate-output-is-shielded-too-not-only-the-guidance
  ;; The retry carries both, and a hand `retry tester` is refused on both.
  (let [leaky (assoc-in failing-test-gate [:gates/report 1 :out] "Exception in scratch.x/widen (x.clj:2)")
        v {:route :tester :reason "r" :guidance "name only the contract"}
        r (triage/shield v spec impl-names (triage/feedback-for v (on-gate leaky)))]
    (is (= :human (:route r)))
    (is (= #{:impl-path :impl-var} (set (map :leak (:leaks (:leak-check r))))))))

(deftest other-routes-pass-shield-untouched
  (let [v {:route :coder :reason "r" :guidance "widen is wrong"}]
    (is (= v (triage/shield v spec impl-names (triage/feedback-for v (on-gate failing-test-gate))))
        "the Coder wrote widen; naming it to the Coder leaks nothing")))

(deftest a-long-gate-output-keeps-its-tail-where-the-report-is
  ;; A test runner prints its failure report LAST. The clip once kept the first
  ;; 6000 characters only, so a long run handed triage the progress and not the
  ;; report; three red gates of one project were routed to a person for it.
  (let [filler (apply str (repeat 20000 "x"))
        out (str "Testing app.core\n" filler "\nFAIL in (renders) (core_test.clj:12)\nexpected: 1\n  actual: 2\n")
        c (triage/clipped out triage/max-output-chars)]
    (is (<= (count c) (+ triage/max-output-chars 120)) "the marker is the only excess")
    (is (str/starts-with? c "Testing app.core") "the head survives")
    (is (str/ends-with? c "  actual: 2\n") "the tail survives, report included")
    (is (str/includes? c "FAIL in (renders)"))
    (is (str/includes? c "characters omitted here"))
    (is (= out (triage/clipped out (inc (count out)))) "nothing is cut when it fits")))

(deftest feedback-puts-the-guidance-before-the-gate-output
  (let [fb (triage/feedback-for {:route :coder :reason "r" :guidance "do X"} (on-gate failing-test-gate))]
    (is (= [:triage :gate] (mapv :feedback/from fb)))
    (is (= "do X" (:feedback/text (first fb))))
    (is (str/starts-with? (:feedback/text (second fb)) "test failed:")))
  (testing "a fallback has no guidance, so the reason stands in and the gate output follows"
    (is (= [:triage :gate] (mapv :feedback/from (triage/feedback-for {:route :coder :reason "why"} (on-gate failing-test-gate))))))
  (testing "a note has no gate output"
    (is (= [:triage] (mapv :feedback/from (triage/feedback-for {:route :coder :guidance "g"} (note :coder)))))))

;; ---------------------------------------------------------------------------
;; parsing
;; ---------------------------------------------------------------------------

(deftest parse-verdict-takes-the-last-block-and-nil-for-anything-else
  (is (= {:route "coder" :reason "x"}
         (triage/parse-verdict (str "first:\n" (block {:route "tester"}) "\nthen:\n" (block {:route "coder" :reason "x"})))))
  (is (nil? (triage/parse-verdict "no block here")))
  (is (nil? (triage/parse-verdict "```json\n{not json\n```")))
  (is (nil? (triage/parse-verdict "```json\n[1, 2]\n```")) "an array is not a verdict")
  (is (nil? (triage/parse-verdict nil))))

(deftest verdict-normalises-the-route-and-refuses-what-it-cannot-act-on
  (let [trg (red-gate)]
    (is (= {:route :coder :reason "r" :guidance "g"} (triage/verdict trg {:route " Coder " :reason "r" :guidance "g"})))
    (is (= {:route :coder :reason "r" :guidance nil} (triage/verdict trg {:route "coder" :reason "r" :guidance ""}))
        "empty guidance is nil, not an empty string the packet would carry")
    (is (= "the verdict names no route" (:triage/fallback (triage/verdict trg {:reason "r"}))))
    (is (= "no JSON verdict in the answer" (:triage/fallback (triage/verdict trg nil))))))

;; ---------------------------------------------------------------------------
;; what the leak check cannot see
;; ---------------------------------------------------------------------------

(deftest the-prompt-asks-for-what-no-check-can-enforce
  ;; `leaks` sees names: a path, a var, a code block. A verdict once told a Tester
  ;; to assert "exactly three elements" - a fact read off the Coder's file, in no
  ;; target - and it named nothing, passed, and went into a merged test.
  (let [prompt (triage/render-prompt (red-gate))]
    (is (str/includes? prompt "never DESCRIBE the implementation either"))
    (is (str/includes? prompt "must hold for EVERY implementation the targets allow"))
    (is (str/includes? prompt "that you know only from reading the code")))
  (testing "and such guidance does pass the mechanical check, which is why the prompt has to ask"
    (let [v {:route :tester :reason "r" :guidance "assert that the article has exactly three elements"}]
      (is (= :tester (:route (triage/shield v spec impl-names (triage/feedback-for v (note :tester)))))))))

;; ---------------------------------------------------------------------------
;; the two rules a person set, which no check can enforce either
;; ---------------------------------------------------------------------------

(deftest the-prompt-carries-the-two-rules-a-person-set
  ;; An ambiguous contract is fixed, not interpreted: on a genuinely ambiguous target,
  ;; five models asked five times each split between "architect" and "coder" on policy,
  ;; not on evidence - so the policy is stated. And types are read as the language
  ;; defines them: every reader of one contract called a vector's order "unstated" and
  ;; routed on it, and a Coder that sorted the vector had changed the contract.
  (let [prompt (triage/render-prompt (red-gate))]
    (is (str/includes? prompt "An ambiguous contract is FIXED, not interpreted"))
    (is (str/includes? triage/system-prompt "TYPES ARE FOLLOWED AS THE LANGUAGE DEFINES THEM"))
    (is (str/includes? triage/system-prompt "has the order of its construction"))))
