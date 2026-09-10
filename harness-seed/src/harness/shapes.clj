;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/shapes.clj @ 978ff9e (2026-07-10).
(ns harness.shapes
  "Malli schemas for the loop: the task packet, what a runner returns,
  what a gate run returns, and the run-log record.

  `TaskPacket` is the context-passing contract (method §06): one slice per
  task, dispatched to one role. The per-role deltas are a :multi schema on
  :task/role — the Reviewer receives a diff and a gate report and has no
  :files/target; Coder and Tester must name one."
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.util :as mu]))

;; ---------------------------------------------------------------------------
;; The task packet
;; ---------------------------------------------------------------------------

(def Role
  [:enum :coder :tester :reviewer])

(def BlueprintSlice
  "Shapes + signatures for ONE namespace. Dependencies arrive as signatures
  only (:deps-sigs) — never upstream implementation source. That keeps the
  slice small and makes it mechanically impossible for a task to reach into
  an implementation detail it was never given."
  [:map
   [:shapes [:vector :any]]
   [:interfaces [:sequential :any]]
   [:deps-sigs {:optional true} [:sequential :any]]])

(def Gates
  "Only :retry-cap. The upstream version also carried a :cmd string naming
  the gate command — one writer, zero readers; it rode in every packet doing
  nothing. The gate command belongs to the gate runner, not the packet."
  [:map
   [:retry-cap pos-int?]])

(def PacketBase
  [:map
   [:task/id [:string {:min 1}]]
   [:task/title [:string {:min 1}]]
   [:task/role Role]
   [:blueprint/slice BlueprintSlice]
   [:files/context [:vector :string]]
   [:repl/worktree [:string {:min 1}]]
   [:repl/port [:int {:min 1024 :max 65535}]]
   [:layer/name :keyword]
   [:gates Gates]])

(def CoderPacket
  (mu/merge
   PacketBase
   [:map [:files/target [:vector {:min 1} :string]]]))

(def TesterPacket
  "Same shape as the Coder's, but :files/target is the test namespace and
  :files/context must omit the Coder's impl. Enforced by the assembler
  (harness.packet), not by this schema — a schema cannot tell which of the
  listed paths is the implementation."
  (mu/merge
   PacketBase
   [:map
    [:files/target [:vector {:min 1} :string]]
    [:property-targets {:optional true} [:vector :string]]]))

(def ReviewerPacket
  "Read-only: diff + slice + the green gate report. No :files/target, and no
  REPL — the Reviewer does not eval and does not write."
  (-> PacketBase
      (mu/dissoc :repl/port)
      (mu/merge
       [:map
        [:repl/port {:optional true} [:int {:min 1024 :max 65535}]]
        [:review/diff [:string {:min 1}]]
        [:review/gate-report :any]])))

(def TaskPacket
  [:multi {:dispatch :task/role}
   [:coder CoderPacket]
   [:tester TesterPacket]
   [:reviewer ReviewerPacket]])

(def example-packet
  "A real packet, kept in src rather than a test fixture so it can be quoted
  verbatim by the method doc and validated by `bb test` at the same time.
  If this and the doc's §06 block ever disagree, this one is right."
  {:task/id "t-07-service-ops"
   :task/title "Service ops: lookup, children/descendants, search"
   :task/role :coder
   :blueprint/slice {:shapes '[Concept Release]
                     :interfaces '[(lookup [store cs code opts])
                                   (children [store iri])
                                   (descendants [store iri opts])]
                     :deps-sigs '[(query [store q opts])]}
   :files/target ["src/app/service.clj"]
   :files/context ["src/app/store.clj" "src/app/model.clj"]
   :repl/worktree "/work/app-wt-07"
   :repl/port 7807
   :layer/name :service
   :gates {:retry-cap 3}})

;; ---------------------------------------------------------------------------
;; What a runner returns
;; ---------------------------------------------------------------------------

(def AgentResult
  "What every AgentRunner returns.

  CLOSED, on purpose. In the project this was extracted from, five keys
  accreted onto this map over two months and were never declared —
  :session-id (to resume a CLI session), :verdict (the reviewer's),
  :capped? (a tool-loop that hit its iteration cap but still produced
  usable files), :provider (which upstream host actually served the call),
  and :tokens. The orchestrator branched on all five. malli's :map is open
  by default, so the validator could never have caught them, and it was
  only ever called from tests anyway.

  Extras like those are legitimate — they are how a runner tells the loop
  something only that runner knows. Put them in :runner/meta, where they
  are visible in one place and where a typo (:sessionId) fails a
  conformance check instead of silently disabling session resume.

  The cost is real: callers read (get-in r [:runner/meta :session-id])
  rather than (:session-id r). That is the trade — five characters at each
  read site, for a validator that can actually fail."
  [:map {:closed true}
   [:status [:enum :done :failed]]
   [:files [:vector :string]]
   [:stdout [:maybe :string]]
   [:cost [:maybe number?]]
   [:runner/meta {:optional true} [:map-of :keyword :any]]])

;; ---------------------------------------------------------------------------
;; What a gate run returns
;; ---------------------------------------------------------------------------

(def GateEntry
  "One gate's outcome. Closed so a :skipped entry cannot quietly omit
  :exit/:out and hand a consumer a different shape than a :pass entry did."
  [:map {:closed true}
   [:gate :keyword]
   [:status [:enum :pass :fail :skipped]]
   ;; nil when nothing ran: a skipped gate, or a failure synthesized by the
   ;; loop (a dead REPL, a failed dispatch) to flow through the same seam.
   [:exit [:maybe :int]]
   [:out :string]])

(def GateResult
  [:map {:closed true}
   [:gates/passed? :boolean]
   [:gates/failed [:maybe :keyword]]
   [:gates/report [:vector GateEntry]]])

;; ---------------------------------------------------------------------------
;; The run log
;; ---------------------------------------------------------------------------

(def RunStatus
  "Lifecycle of one task through the loop.
  :awaiting-merge = green and approved, paused at the human merge gate."
  [:enum :dispatched :gates-running :gates-failed :in-review :retrying
   :awaiting-merge :escalated :merged :abandoned])

(def TaskRun
  "Append-only run-log record. :run/cost is nil when the client reports none."
  [:map
   [:run/id [:string {:min 1}]]
   [:task/id [:string {:min 1}]]
   [:run/attempts nat-int?]
   [:run/status RunStatus]
   [:run/cost [:maybe number?]]
   [:run/started-at inst?]
   [:run/events {:optional true} [:vector :any]]])

;; ---------------------------------------------------------------------------

(defn valid-packet? [packet] (m/validate TaskPacket packet))
(defn valid-result? [result] (m/validate AgentResult result))
(defn valid-gate-result? [result] (m/validate GateResult result))
(defn valid-run? [run] (m/validate TaskRun run))

(defn explain-packet
  "Humanized validation errors for a packet, or nil when valid."
  [packet]
  (some-> (m/explain TaskPacket packet) me/humanize))

(defn explain-result
  [result]
  (some-> (m/explain AgentResult result) me/humanize))

(defn explain-run
  [run]
  (some-> (m/explain TaskRun run) me/humanize))
