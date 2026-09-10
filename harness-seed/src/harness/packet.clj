;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/packet.clj @ e6b40ed (2026-07-10).
(ns harness.packet
  "Task-packet assembly.

  A *task spec* is one entry of the Architect's dependency-ordered task list:

    {:task/id          \"t-07-service-ops\"
     :task/title       \"Service ops: ...\"
     :blueprint/slice  {:shapes [...] :interfaces [...] :deps-sigs [...]}
     :files/impl       [\"src/app/service.clj\"]
     :files/test       [\"test/app/service_test.clj\"]
     :files/context    [\"src/app/store.clj\" ...]
     :layer/name       :service
     :property-targets [...]}                       ; optional

  A *session* is a provisioned workspace: {:worktree/path _ :nrepl/port _}.

  The assembler cuts one role-specific packet from a spec plus a session,
  and validates it. This namespace is small and boring on purpose — its
  reason to exist is the four lines in `tester-packet`."
  (:require
   [harness.shapes :as shapes]))

(def default-gates
  {:retry-cap 3})

(defn- validate! [packet]
  (if-let [errors (shapes/explain-packet packet)]
    (throw (ex-info "invalid task packet" {:errors errors
                                           :task/id (:task/id packet)
                                           :task/role (:task/role packet)}))
    packet))

(defn- base [spec session]
  {:task/id (:task/id spec)
   :task/title (:task/title spec)
   :blueprint/slice (:blueprint/slice spec)
   :repl/worktree (:worktree/path session)
   :repl/port (:nrepl/port session)
   :layer/name (:layer/name spec)
   :gates (or (:gates spec) default-gates)})

(defn coder-packet
  "Coder: :files/target = the impl file(s); context = the dependency files,
  read-only."
  [spec session]
  (validate!
   (assoc (base spec session)
          :task/role :coder
          :files/target (:files/impl spec)
          :files/context (vec (:files/context spec)))))

(defn tester-packet
  "Tester: :files/target = the test namespace, and context EXCLUDES the
  Coder's impl files even when the spec lists them.

  That exclusion is the whole point of this namespace. Tests derived from
  an implementation only re-assert what the code already does; tests
  derived from the contract catch where the code and the contract
  disagree. Enforcing it here rather than asking a Blueprint author to
  remember is the difference between an invariant and an intention."
  [spec session]
  (let [impl (set (:files/impl spec))]
    (validate!
     (cond-> (assoc (base spec session)
                    :task/role :tester
                    :files/target (:files/test spec)
                    :files/context (vec (remove impl (:files/context spec))))
       (:property-targets spec)
       (assoc :property-targets (:property-targets spec))))))

(defn reviewer-packet
  "Reviewer: diff + slice + the green gate report. Read-only — no target,
  no REPL port. The diff and gate report come from the loop, not the spec."
  [spec session diff gate-report]
  (validate!
   (-> (base spec session)
       (dissoc :repl/port)
       (assoc :task/role :reviewer
              :files/context (vec (:files/context spec))
              :review/diff diff
              :review/gate-report gate-report))))
