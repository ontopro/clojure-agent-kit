(ns harness.packet-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.packet :as packet]
   [harness.shapes :as shapes]))

(def spec
  {:task/id "t-07-service-ops"
   :task/title "Service ops: lookup, children/descendants, search"
   :blueprint/slice {:shapes '[Concept Release]
                     :interfaces '[(lookup [store cs code opts])]
                     :deps-sigs '[(query [store q opts])]}
   :files/impl ["src/app/service.clj"]
   :files/test ["test/app/service_test.clj"]
   ;; deliberately includes the impl file — the assembler must strip it
   ;; from the Tester's context
   :files/context ["src/app/store.clj" "src/app/service.clj" "src/app/model.clj"]
   :layer/name :service
   :property-targets ["diff(v,v) is empty"]})

(def session
  {:worktree/path "/work/app-wt-07"
   :worktree/branch "task/t-07-service-ops"
   :nrepl/port 7807})

(deftest coder
  (let [p (packet/coder-packet spec session)]
    (is (= :coder (:task/role p)))
    (is (= ["src/app/service.clj"] (:files/target p)))
    (is (= (:files/context spec) (:files/context p))
        "the Coder may read everything the spec lists")
    (is (= 7807 (:repl/port p)))
    (is (= packet/default-gates (:gates p)))))

(deftest tester-invariant
  ;; The single most important assertion in this package: the Tester reads
  ;; the contract, never the code, and that is enforced here rather than
  ;; asked of whoever wrote the Blueprint.
  (let [p (packet/tester-packet spec session)]
    (is (= :tester (:task/role p)))
    (is (= ["test/app/service_test.clj"] (:files/target p)))
    (testing "impl files are stripped from context even when the spec lists them"
      (is (= ["src/app/store.clj" "src/app/model.clj"] (:files/context p))))
    (is (= ["diff(v,v) is empty"] (:property-targets p))))
  (testing "property targets are omitted when the spec has none"
    (is (not (contains? (packet/tester-packet (dissoc spec :property-targets) session)
                        :property-targets)))))

(deftest reviewer
  (let [p (packet/reviewer-packet spec session
                                  "diff --git a/src/app/service.clj ..."
                                  {:fmt :pass :lint :pass :test :pass :deps :pass})]
    (is (= :reviewer (:task/role p)))
    (is (not (contains? p :files/target)) "read-only: nothing to write")
    (is (not (contains? p :repl/port)) "no eval: no REPL")
    (is (= {:fmt :pass :lint :pass :test :pass :deps :pass} (:review/gate-report p)))))

(deftest every-role-gets-the-property-targets
  ;; They were the Tester's alone. D7's amendment never reached the Coder; in
  ;; D8 target 6 reached neither the Coder, who broke it, nor the Reviewer, who
  ;; therefore could not see it broken. A property target is contract.
  (let [diff "diff --git a/src/app/service.clj ..."
        gates {:fmt :pass :lint :pass :test :pass :deps :pass}
        packets {:coder (packet/coder-packet spec session)
                 :tester (packet/tester-packet spec session)
                 :reviewer (packet/reviewer-packet spec session diff gates)}]
    (doseq [[role p] packets]
      (testing (str (name role) " carries them, and its packet still validates")
        (is (= ["diff(v,v) is empty"] (:property-targets p)))
        (is (shapes/valid-packet? p))))
    (testing "and none of them carries the key when the spec has no targets"
      (let [bare (dissoc spec :property-targets)]
        (is (not-any? #(contains? % :property-targets)
                      [(packet/coder-packet bare session)
                       (packet/tester-packet bare session)
                       (packet/reviewer-packet bare session diff gates)]))))
    (testing "the Tester's independence is untouched: still no implementation in its context"
      (is (not-any? #{"src/app/service.clj"} (:files/context (:tester packets)))))))

(deftest the-architect-can-announce-an-amendment
  ;; D7 amended the property targets between attempts, and nothing could say so.
  (let [r (packet/for-retry (packet/coder-packet spec session) 2
                            [{:feedback/from :architect
                              :feedback/text "property-targets amended: :div folds only when exact"}])]
    (is (shapes/valid-packet? r))
    (is (= [:architect] (mapv :feedback/from (:task/feedback r))))))

(deftest validation
  (testing "a spec missing its impl file fails coder assembly loudly"
    (is (thrown-with-msg? Exception #"invalid task packet"
                          (packet/coder-packet (dissoc spec :files/impl) session))))
  (testing "a session without a port fails coder assembly"
    (is (thrown-with-msg? Exception #"invalid task packet"
                          (packet/coder-packet spec (dissoc session :nrepl/port)))))
  (testing "a gates override on the spec replaces the default"
    (is (= {:retry-cap 1}
           (:gates (packet/coder-packet (assoc spec :gates {:retry-cap 1}) session))))))

;; ---------------------------------------------------------------------------
;; the return channel
;; ---------------------------------------------------------------------------

(deftest a-retry-must-say-why
  ;; D4 ran triage by hand and nothing in TaskPacket could carry the gate
  ;; output. Dispatching the same packet twice is not a retry — it is the same
  ;; dispatch, and it will produce the same answer.
  (let [p (packet/coder-packet spec session)]
    (is (thrown-with-msg? Exception #"must say why" (packet/for-retry p 2 [])))
    (is (thrown-with-msg? Exception #"starts at attempt 2"
                          (packet/for-retry p 1 [{:feedback/from :gate
                                                  :feedback/text "x"}])))))

(deftest a-retry-carries-the-reason-and-validates
  (let [r (packet/for-retry (packet/coder-packet spec session) 2
                            [{:feedback/from :gate :feedback/text "lint failed"}])]
    (is (= 2 (:task/attempt r)))
    (is (= [:gate] (mapv :feedback/from (:task/feedback r))))
    (is (shapes/valid-packet? r))
    (is (thrown? Exception
                 (packet/for-retry (packet/coder-packet spec session) 2
                                   [{:feedback/from :nobody :feedback/text "x"}]))
        "the source is an enum, so a typo fails rather than riding along")))

(deftest gate-feedback-takes-only-what-failed
  ;; Picking the failing gate's :out by hand at every call site is how a retry
  ;; ends up carrying the output of a gate that passed.
  (let [fb (packet/gate-feedback
            {:gates/report [{:gate :fmt :status :pass :out ""}
                            {:gate :lint :status :fail :out "unused binding x"}
                            {:gate :test :status :skipped :out ""}]})]
    (is (= 1 (count fb)))
    (is (= :gate (:feedback/from (first fb))))
    (is (str/includes? (:feedback/text (first fb)) "lint failed"))
    (is (str/includes? (:feedback/text (first fb)) "unused binding x"))))

(deftest a-siblings-notes-become-feedback-tagged-with-who-left-them
  ;; The other half of the circuit: run 4's Coder handled two cases the
  ;; contract never mentioned and could only say so in prose nothing read.
  (let [fb (packet/notes-feedback :coder ["7/2 is a ratio and :expr/value is :int"])]
    (is (= [:coder] (mapv :feedback/from fb)))
    (is (shapes/valid-packet?
         (packet/for-retry (packet/tester-packet spec session) 3 fb))
        "and it validates on the packet it is carried into")))

(deftest triage-can-say-why-a-role-is-back
  ;; D8: green gates, no Reviewer findings, and a contract broken on every
  ;; invalid input — found by triage, which had no source to send it as.
  (let [r (packet/for-retry (packet/coder-packet spec session) 2
                            [{:feedback/from :triage
                              :feedback/text "bind validates its output, not its input"}])]
    (is (shapes/valid-packet? r))
    (is (= [:triage] (mapv :feedback/from (:task/feedback r))))))

(deftest feedback-is-clipped-because-a-retry-resends-it-every-turn
  ;; `harness.tools/max-output` clips tool results for exactly this reason and
  ;; says so; feedback was added a layer up without it. A failing test suite's
  ;; whole output as a retry reason is re-sent on every turn of that dispatch.
  (let [huge (apply str (repeat 9000 "x"))
        r (packet/for-retry (packet/coder-packet spec session) 2
                            [{:feedback/from :gate :feedback/text huge}])
        text (:feedback/text (first (:task/feedback r)))]
    (is (< (count text) 4200) "clipped")
    (is (str/includes? text "truncated at 4000 characters of 9000")
        "and announced — a model that cannot tell it got half a gate report will
         reason confidently about the half it did not get")
    (is (shapes/valid-packet? r))))

(deftest short-feedback-is-left-exactly-as-it-was
  (let [r (packet/for-retry (packet/coder-packet spec session) 2
                            [{:feedback/from :gate :feedback/text "lint failed"}])]
    (is (= "lint failed" (:feedback/text (first (:task/feedback r)))))))
