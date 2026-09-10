(ns harness.packet-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [harness.packet :as packet]))

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
