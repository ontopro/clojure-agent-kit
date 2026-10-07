(ns harness.models.profile-test
  (:require
   [babashka.fs :as fs]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.shapes :as shapes]
   [harness.models.profile :as profile]
   [harness.setup.workspace :as workspace]))

(def base
  {:seat :claude
   :roles {:coder {:family :anthropic :model "m" :shape :anthropic
                   :endpoint "https://example.test"}
           :tester {:family :deepseek :model "m" :shape :openai
                    :endpoint "https://example.test"}
           :reviewer {:family :google :model "m" :shape :openai
                      :endpoint "https://example.test"}
           :spec-reviewer {:family :openai :model "m" :shape :openai
                           :endpoint "https://example.test"}
           :plan-reviewer {:family :openai :model "m" :shape :openai
                           :endpoint "https://example.test"}
           :blueprint-reviewer {:family :anthropic :model "m" :shape :openai
                                :endpoint "https://example.test"}
           :orchestrator {:family :anthropic :model "m" :shape :anthropic
                          :endpoint "https://example.test"}}})

(defn- ok-probe [_] {:status :ok})
(defn- errors [profile & [opts]] (mapv :profile/error (profile/violations profile opts)))

;; ---------------------------------------------------------------------------
;; the profile this repository ships
;; ---------------------------------------------------------------------------

(deftest a-role-may-set-its-rounds-limit-and-it-must-be-a-positive-integer
  ;; The rounds a dispatch may take are the project's policy, per role, in the
  ;; profile; the first real project could set them from nowhere.
  (let [claude (get (profile/examples) "claude")]
    (is (shapes/valid-profile? (assoc-in claude [:roles :tester :max-rounds] 40)))
    (is (not (shapes/valid-profile? (assoc-in claude [:roles :tester :max-rounds] 0))))
    (is (not (shapes/valid-profile? (assoc-in claude [:roles :tester :max-rounds] "40"))))))

(deftest every-shipped-example-is-a-valid-profile
  ;; resources/profiles/ holds worked examples, like resources/agent-rules.edn
  ;; and shapes/example-packet. An example nobody validates is an example that
  ;; rots, and these are the only statement of the independence rule that is
  ;; not prose.
  (let [all (profile/examples)]
    (is (= ["agy-ide" "claude"] (vec (keys all)))
        "one per seat this kit has a doctor probe for and a story about")
    (doseq [[nm p] all]
      (is (shapes/valid-profile? p) nm)
      (is (= [] (profile/violations p))
          (str nm ": structurally sound, verifiers independent of its coder"))
      (is (= nm (name (:seat p)))
          "the file name is the seat, so a listing needs no parsing"))))

(deftest the-shipped-pair-covers-both-tool-call-shapes
  ;; The stated reason there are two rather than one. Collapsing them onto a
  ;; single adapter would leave the other with no worked example at all, and
  ;; the runner has to build requests for both.
  (is (= #{:openai :anthropic}
         (set (for [[_ p] (profile/examples)
                    [_ r] (:roles p)]
                (:shape r))))))

(deftest every-openrouter-role-pins-its-serving-provider
  ;; One run: three Tester attempts on one slug were served by StreamLake,
  ;; then Baidu, then StreamLake. That model has sixteen providers at
  ;; differing quantisations down to fp4, and §10 records a community chat
  ;; template corrupting tool-call arguments silently — so "the model
  ;; failed" was never a safe reading of that run.
  ;;
  ;; :allow_fallbacks false disables fallback WITHIN a request; only :only
  ;; fixes the host across them. A direct endpoint does no routing and has
  ;; nothing to pin.
  (doseq [[nm prof] (profile/examples)
          [role {:keys [endpoint params]}] (:roles prof)
          :when (str/includes? endpoint "openrouter")]
    (is (seq (get-in params [:provider :only]))
        (str nm "/" (name role) " reaches a router and does not pin a provider"))
    (is (false? (get-in params [:provider :allow_fallbacks]))
        (str nm "/" (name role) " allows fallback"))))

(deftest the-claude-examples-anthropic-roles-go-through-openrouter
  ;; The route every build re-applied by hand while the example shipped the
  ;; direct one (register row 68): one key and one balance, the cost the
  ;; endpoint's word, the credit stop and the generation record all live on
  ;; that side of the money tooling. Caching is asked for on the Coder alone -
  ;; the :openai shape sends none unless the profile says so, and a triage
  ;; call with no tools would pay the cache-write premium on a prompt nobody
  ;; reads again.
  (let [{:keys [coder orchestrator]} (:roles (get (profile/examples) "claude"))]
    (doseq [[nm r] {"coder" coder "orchestrator" orchestrator}]
      (is (= :anthropic (:family r)) nm)
      (is (= :openai (:shape r)) (str nm " reaches OpenRouter on the shape it serves"))
      (is (str/includes? (:endpoint r) "openrouter") nm)
      (is (= "OPENROUTER_API_KEY" (:key-env r)) (str nm " is on the key the balance reads"))
      (is (= ["anthropic"] (get-in r [:params :provider :only]))
          (str nm " is pinned to Anthropic's own serving, where the cache never missed"))
      (is (nil? (:pricing r))
          (str nm ": the cost is reported, so no list price to compute a second figure from")))
    (is (= {:type "ephemeral"} (get-in coder [:params :cache_control])) "the Coder asks for the cache")
    (is (nil? (get-in orchestrator [:params :cache_control])) "triage does not")))

(deftest every-shipped-example-names-a-seat-the-doctor-can-probe
  ;; Only the SEAT part of the environment tier is a fact about the file.
  ;; Whether ANTHROPIC_API_KEY is set is a fact about the machine, and both
  ;; examples legitimately name it — so this asserts the absence of the seat
  ;; findings rather than the absence of all of them.
  (doseq [[nm p] (profile/examples)]
    (let [found (set (map :profile/error
                          (profile/violations p {:env? true :probe-tool ok-probe})))]
      (is (empty? (set/intersection found #{:seat-unknown :seat-unavailable}))
          (str nm ": its seat is in harness.setup.doctor/toolchain")))))

;; ---------------------------------------------------------------------------
;; the independence rule — method §05, decision log P0-3
;; ---------------------------------------------------------------------------

(deftest a-verifier-may-not-share-the-coders-family
  (testing "the Tester"
    (is (= [:verifier-shares-coder-family]
           (errors (assoc-in base [:roles :tester :family] :anthropic)))))
  (testing "the Reviewer"
    (is (= [:verifier-shares-coder-family]
           (errors (assoc-in base [:roles :reviewer :family] :anthropic)))))
  (testing "the Spec reviewer: the Architect is the seat, and the seat's family is the Coder's by convention"
    (is (= [:verifier-shares-coder-family]
           (errors (assoc-in base [:roles :spec-reviewer :family] :anthropic)))))
  (testing "the Plan reviewer, by the spec reviewer's route: the plan and every stage plan are the seat's writing"
    (is (= [:verifier-shares-coder-family]
           (errors (assoc-in base [:roles :plan-reviewer :family] :anthropic)))))
  (testing "both at once are two findings, named by role"
    (let [v (profile/violations (-> base
                                    (assoc-in [:roles :tester :family] :anthropic)
                                    (assoc-in [:roles :reviewer :family] :anthropic)))]
      (is (= [:tester :reviewer] (mapv :role v))))))

(deftest verifiers-sharing-each-others-family-is-allowed
  ;; Deliberately NOT a violation. What §05 and P0-3 record is *verifier ≠
  ;; Coder family* — two verifiers from one family still both differ from the
  ;; code's author, and inventing a stricter rule here would put a constraint
  ;; in code that no decision log entry supports.
  (is (= [] (errors (assoc-in base [:roles :reviewer :family] :deepseek)))))

(deftest the-orchestrator-may-share-the-coders-family
  ;; Triage is not a verifier: it writes no code and judges no diff, so §05
  ;; does not reach it. `base` already has it on the Coder's family, which is
  ;; the shipped claude.edn's arrangement, accepted by decision on 2026-09-16.
  (is (= :anthropic (get-in base [:roles :orchestrator :family]) (get-in base [:roles :coder :family])))
  (is (= [] (errors base)))
  (is (= [] (errors (assoc-in base [:roles :orchestrator :family] :google)))
      "and it may equally be any other family — there is no constraint either way"))

;; ---------------------------------------------------------------------------
;; structure
;; ---------------------------------------------------------------------------

(deftest a-malformed-profile-is-one-finding-not-many
  ;; A profile that is not a Profile has no roles to ask questions about, so
  ;; every later check would report noise derived from the same fault.
  (is (= [:invalid] (errors (dissoc base :roles))))
  (is (= [:invalid] (errors (assoc-in base [:roles :coder :shape] :grpc)))
      "and :shape is closed — two adapters reach every model this names")
  (is (= [:invalid] (errors (assoc base :seats [:claude :pi])))
      "a second seat cannot be expressed, which is the point of the shape"))

(deftest a-price-without-its-source-and-date-is-refused
  ;; A price table rots; one that names where and when it was read at least
  ;; says how stale it is.
  (let [pricing {:per-mtok {:in 10 :out 50 :cache-write-5m 12.5 :cache-write-1h 20 :cache-read 0.25}
                 :source "https://example.test/pricing" :as-of "2026-09-14"}
        with (fn [p] (assoc-in base [:roles :coder :pricing] p))]
    (is (= [] (errors (with pricing))))
    (is (= [:invalid] (errors (with (dissoc pricing :as-of)))))
    (is (= [:invalid] (errors (with (dissoc pricing :source)))))
    (is (= [:invalid] (errors (with (update pricing :per-mtok dissoc :cache-read))))
        "every rate, or the arithmetic silently drops a term")))

(deftest a-role-the-loop-does-not-dispatch-is-rejected
  ;; :roles is closed too, not only the profile around it. An :architect
  ;; entry silently ignored would read as configured and do nothing — and
  ;; the Architect is a human step, not a dispatched one.
  (is (= [:invalid]
         (errors (assoc-in base [:roles :architect]
                           {:family :openai :model "m" :shape :openai
                            :endpoint "https://example.test"})))))

(deftest every-role-is-required
  (is (= [:plan-reviewer :blueprint-reviewer :spec-reviewer :coder :tester :reviewer :orchestrator] profile/roles)
      "seven: the plan reviewer meets a build first, in stage 0's plan step; the Blueprint reviewer once per stage; the spec reviewer before every start; the Orchestrator last")
  (doseq [r profile/roles]
    (is (= [:invalid] (errors (update base :roles dissoc r)))
        (str "a profile without a " (name r) " describes a build that cannot run"))))

(deftest the-blueprint-reviewer-may-share-the-seats-family-by-decision
  ;; The rule reaches it - it reads the seat's Blueprint as the spec reviewer
  ;; reads the seat's spec - and the decision of 2026-09-25 is the exception,
  ;; from a measurement; the register watches it. So `violations` says nothing
  ;; about its family either way, and the shipped claude example is on it.
  (is (= [] (errors base)) "the fixture has it on the coder's family")
  (is (= [] (errors (assoc-in base [:roles :blueprint-reviewer :family] :openai))))
  (let [r (get-in (get (profile/examples) "claude") [:roles :blueprint-reviewer])]
    (is (= :anthropic (:family r)))
    (is (str/includes? (:model r) "opus"))
    (is (= "high" (get-in r [:params :reasoning_effort])))
    (is (nil? (get-in r [:params :cache_control])) "one completion, read once")
    (is (nil? (:pricing r)) "the endpoint reports the cost")))

;; ---------------------------------------------------------------------------
;; environment
;; ---------------------------------------------------------------------------

(deftest a-seat-the-doctor-cannot-probe-is-a-finding
  (is (= [:seat-unknown]
         (errors (assoc base :seat :emacs) {:env? true :probe-tool ok-probe}))))

(deftest a-seat-that-is-not-installed-is-a-finding
  (is (= [:seat-unavailable]
         (errors base {:env? true :probe-tool (fn [_] {:status :missing})}))))

(deftest an-unset-key-variable-is-named-but-never-read
  (let [v (-> base
              (assoc-in [:roles :tester :key-env] "HARNESS_TEST_DEFINITELY_UNSET")
              (profile/violations {:env? true :probe-tool ok-probe}))]
    (is (= [:key-env-unset] (mapv :profile/error v)))
    (is (= :tester (:role (first v))))
    (is (re-find #"HARNESS_TEST_DEFINITELY_UNSET" (:detail (first v))))
    (is (not (contains? (first v) :value))
        "the variable's NAME is the finding; its value never enters one")))

(deftest environment-checks-are-off-by-default
  ;; They answer differently on different machines, so `bb gates` must not run
  ;; them — a contributor with another seat would fail a gate for having one.
  (is (= [] (errors (assoc base :seat :emacs)))))

;; ---------------------------------------------------------------------------
;; reading, and refusing
;; ---------------------------------------------------------------------------

(deftest a-missing-profile-is-not-a-violation-it-is-a-throw
  ;; A project that has not written one yet is not a project with a bad one.
  (is (thrown-with-msg? Exception #"no profile"
                        (profile/read-profile "does/not/exist.edn")))
  (let [f (str (fs/path (fs/create-temp-dir) "p.edn"))]
    (spit f "{:seat :claude")
    (is (thrown-with-msg? Exception #"not readable EDN" (profile/read-profile f)))))

(deftest verify-throws-so-a-wrong-profile-cannot-dispatch-anyone
  (is (= [] (profile/verify! base)))
  (is (thrown-with-msg? Exception #"invalid profile"
                        (profile/verify! (assoc-in base [:roles :tester :family]
                                                   :anthropic)))))

(deftest an-endpoint-nothing-is-serving-is-unreachable
  ;; Not mocked: a closed local port is the one network fact that is the same
  ;; on every machine and needs no internet.
  (is (false? (profile/reachable? "http://127.0.0.1:1"))))

(deftest the-summary-puts-family-before-model
  ;; Independence is what a human is eyeballing in this output, so the column
  ;; that decides it comes first.
  (let [[seat plan-reviewer blueprint-reviewer spec-reviewer coder & more] (profile/summary base)]
    (is (= "seat  claude" seat))
    (is (re-find #"^plan-reviewer\s+openai\s+m\s" plan-reviewer) "first: a build meets it in stage 0's plan step")
    (is (re-find #"^blueprint-reviewer\s+anthropic\s+m\s" blueprint-reviewer))
    (is (re-find #"^spec-reviewer\s+openai\s+m\s" spec-reviewer))
    (is (re-find #"^coder\s+anthropic\s+m\s" coder))
    (is (= 7 (count (rest (profile/summary base)))) "one line per role, the Orchestrator's last")
    (is (re-find #"^orchestrator\s+anthropic" (last more)))))

(deftest the-shipped-plan-reviewer-is-the-spec-reviewers-selection
  ;; Its own role from 2026-10-06, so a project can set it apart; the shipped
  ;; examples set the two the same, block for block, and say why in the
  ;; comment - the two readings differ in the checklist sent, not in the
  ;; reader - so the cost is what it was when the block was borrowed.
  (doseq [[nm p] (profile/examples)]
    (is (= (get-in p [:roles :spec-reviewer]) (get-in p [:roles :plan-reviewer]))
        (str nm ": the plan reviewer is the spec reviewer's block"))))

(deftest a-projects-profile-is-the-plans-and-there-is-none-outside-a-workspace
  ;; `bb init` writes <build>/profile.edn; `bb profile` and `bb balance` with no argument read
  ;; it from wherever they are run in the workspace. The KIT's own development folder is in
  ;; no workspace, so there is none there - by design, not by omission.
  (let [ws (str (fs/real-path (fs/create-temp-dir)))
        plan (fs/path ws "xyx-build")]
    (is (nil? (profile/project-profile ws)) "no workspace.edn: nothing")
    (spit (str (fs/path ws "workspace.edn")) (pr-str {:workspace/app "xyx-app" :workspace/build "xyx-build"}))
    (is (nil? (profile/project-profile ws)) "a plan with no profile yet: nothing, not an error")
    (fs/create-dirs plan)
    (spit (str (fs/path plan "profile.edn")) (pr-str base))
    (is (= (str (fs/path plan "profile.edn")) (profile/project-profile ws)))
    (is (= (str (fs/path plan "profile.edn")) (profile/project-profile (str (fs/path ws "kit" "harness"))))
        "found by walking up, so from the clone's harness/ too")
    (let [elsewhere (str (fs/create-temp-dir))]
      (is (nil? (profile/project-profile elsewhere)) "a KIT kept outside the workspace walks up to nothing")
      (is (= (str (fs/path plan "profile.edn"))
             (profile/plan-profile (workspace/current ["--workspace" ws] elsewhere)))
          "pointed at the workspace, the plan's profile is found from anywhere"))
    (is (= base (profile/read-profile (profile/project-profile ws))))))
