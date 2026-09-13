(ns harness.profile-test
  (:require
   [babashka.fs :as fs]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.profile :as profile]
   [harness.shapes :as shapes]))

(def base
  {:seat :claude
   :roles {:coder {:family :anthropic :model "m" :shape :anthropic
                   :endpoint "https://example.test"}
           :tester {:family :deepseek :model "m" :shape :openai
                    :endpoint "https://example.test"}
           :reviewer {:family :google :model "m" :shape :openai
                      :endpoint "https://example.test"}}})

(defn- ok-probe [_] {:status :ok})
(defn- errors [profile & [opts]] (mapv :profile/error (profile/violations profile opts)))

;; ---------------------------------------------------------------------------
;; the profile this repository ships
;; ---------------------------------------------------------------------------

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
  ;; Run D4: three Tester attempts on one slug were served by StreamLake,
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

(deftest every-shipped-example-names-a-seat-the-doctor-can-probe
  ;; Only the SEAT part of the environment tier is a fact about the file.
  ;; Whether ANTHROPIC_API_KEY is set is a fact about the machine, and both
  ;; examples legitimately name it — so this asserts the absence of the seat
  ;; findings rather than the absence of all of them.
  (doseq [[nm p] (profile/examples)]
    (let [found (set (map :profile/error
                          (profile/violations p {:env? true :probe-tool ok-probe})))]
      (is (empty? (set/intersection found #{:seat-unknown :seat-unavailable}))
          (str nm ": its seat is in harness.doctor/toolchain")))))

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

(deftest a-role-the-loop-does-not-dispatch-is-rejected
  ;; :roles is closed too, not only the profile around it. An :architect
  ;; entry silently ignored would read as configured and do nothing — and
  ;; the Architect is a human step, not a dispatched one.
  (is (= [:invalid]
         (errors (assoc-in base [:roles :architect]
                           {:family :openai :model "m" :shape :openai
                            :endpoint "https://example.test"})))))

(deftest every-role-is-required
  (doseq [r [:coder :tester :reviewer]]
    (is (= [:invalid] (errors (update base :roles dissoc r)))
        (str "a profile without a " (name r) " describes a loop that cannot run"))))

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
  (let [[seat coder] (profile/summary base)]
    (is (= "seat  claude" seat))
    (is (re-find #"^coder\s+anthropic\s+m\s" coder))))
