;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/shapes.clj @ 978ff9e (2026-07-10).
(ns harness.contract.shapes
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

(def Session
  "A provisioned workspace, as data.

  :worktree/path is the PROJECT root — what a packet's :repl/worktree means and
  where gates run. :worktree/git-root is what git was told to create and what
  teardown removes; they differ whenever the project sits in a subdirectory of
  the repository.

  :nrepl/port is optional because the gate workspace has none — the precedent
  is ReviewerPacket, which dissocs it. :nrepl/pid rather than a process handle,
  because a session has to survive being written to a run log."
  [:map
   [:worktree/path [:string {:min 1}]]
   [:worktree/git-root [:string {:min 1}]]
   ;; The branch the worktree is on, `<run-id>-<role>`. Optional: a session
   ;; from before the branch was named by the run has none, and derives it.
   [:worktree/branch {:optional true} [:string {:min 1}]]
   [:task/id [:string {:min 1}]]
   [:task/role Role]
   [:nrepl/port {:optional true} [:int {:min 1024 :max 65535}]]
   [:nrepl/pid {:optional true} pos-int?]
   ;; Where that nREPL's own output went. A session that cannot say where the
   ;; REPL's stderr is leaves "no port file appeared" as the whole diagnosis of
   ;; a bad alias or a missing dependency.
   [:nrepl/log {:optional true} [:string {:min 1}]]
   ;; Paths the HARNESS placed in this workspace — a generated stub, anything
   ;; else it writes. Excluded from the scope check, because blaming an agent
   ;; for a file the harness put there fails every task that uses one.
   [:harness/wrote {:optional true} [:vector :string]]
   [:torn-down? {:optional true} :boolean]])

(def Feedback
  "One thing a previous step said, carried into the next dispatch.

  `:feedback/from` is the SOURCE, not the author's opinion of it: `:gate` is a
  gate's own output, `:reviewer` a finding, and `:coder`/`:tester` a note the
  other role left with the `note` tool. A role reads a gate failure and a
  sibling's observation differently, and flattening them to prose loses that.

  `:triage` is whoever routes a failure — method §07's Orchestrator, and so
  far a human. One run went green with a Reviewer reporting no findings, and
  triage found the implementation breaking the contract on every invalid input
  it tried. There was no source to send that as: labelling it `:reviewer`
  would have put words in the Reviewer's mouth, which is the flattening this
  enum exists to prevent.

  `:architect` announces a change to the contract. A run once amended its
  property targets between attempts and nothing could say so: a role had to notice the
  packet had changed. An amendment is not a finding and not a routing
  decision, so it is neither `:reviewer` nor `:triage`."
  [:map {:closed true}
   [:feedback/from [:enum :gate :reviewer :coder :tester :triage :architect]]
   [:feedback/text [:string {:min 1}]]])

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
   ;; The properties the contract promises, in words. On EVERY packet: the Coder
   ;; must satisfy them and the Reviewer judge against them, as much as the
   ;; Tester must test them. They were declared on the Tester's packet alone,
   ;; and two runs each lost a decision to that. See `harness.contract.packet`.
   [:property-targets {:optional true} [:vector :string]]
   ;; Paths the HARNESS placed in this workspace, copied from the Session.
   ;; The runner derives :files from git, and git cannot tell a generated
   ;; stub from something the agent wrote — an early run had the Tester
   ;; report the stub as its own output. `provision/scope-violations` already knew
   ;; this; it just had the Session and the runner has only the packet.
   [:harness/wrote {:optional true} [:vector :string]]
   ;; WHY YOU ARE BEING DISPATCHED AGAIN. Absent on a first attempt, which is
   ;; why `:task/attempt` starts at 2. An early run ran triage by hand and
   ;; found nothing in this map could carry the gate output — it went into the
   ;; opening message, which meant the one thing a retry most needs was the
   ;; one thing the context-passing contract did not describe.
   [:task/attempt {:optional true} [:int {:min 2}]]
   [:task/feedback {:optional true} [:vector {:min 1} Feedback]]
   [:gates Gates]])

(def CoderPacket
  (mu/merge
   PacketBase
   [:map [:files/target [:vector {:min 1} :string]]]))

(def TesterPacket
  "Same shape as the Coder's, but :files/target is the test namespace and
  :files/context must omit the Coder's impl. Enforced by the assembler
  (harness.contract.packet), not by this schema — a schema cannot tell which of the
  listed paths is the implementation."
  (mu/merge
   PacketBase
   [:map
    [:files/target [:vector {:min 1} :string]]]))

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

(def TaskSpec
  "What a Blueprint carries for ONE task, and what `spec.edn` holds: the part
  of `PacketBase` the Architect writes. The driver adds the rest at dispatch -
  `:task/role`, `:files/target` (`:files/impl` for the Coder, `:files/test` for
  the Tester), `:repl/worktree`, `:repl/port`, `:harness/wrote` - and
  `harness.contract.packet` cuts one packet per role from this plus a Session.

  Written down because the Blueprint template's packet had drifted from what
  the harness reads (`:contract`, `:workspace`, `:gates {:cmd}`; no title, no
  property targets) and every build re-derived the extraction by hand.
  `harness.contract.blueprint` validates what it pulls out against this, and
  a test reads the template's own packet as one."
  [:map
   [:task/id [:string {:min 1}]]
   [:task/title [:string {:min 1}]]
   [:blueprint/slice BlueprintSlice]
   ;; ONE implementation file: a slice is the shapes and signatures of one
   ;; namespace, and `stub/write!` has always refused a second file - but the
   ;; schema admitted it, so a two-file packet passed the extraction, `sigs`
   ;; and two paid spec reviews before `start` threw. `stub/check-files` says
   ;; it by name where those run.
   [:files/impl [:vector {:min 1 :max 1} :string]]
   [:files/test [:vector {:min 1} :string]]
   [:files/context [:vector :string]]
   [:layer/name :keyword]
   [:property-targets {:optional true} [:vector :string]]
   [:gates {:optional true} Gates]])

(def example-packet
  "A real packet, kept in src rather than a test fixture so it can be quoted
  verbatim by the method doc and validated by `bb test` at the same time.
  If this and the doc's §06 block ever disagree, this one is right."
  {:task/id "t-07-service-ops"
   :task/title "Service ops: lookup, children/descendants, search"
   :task/role :coder
   :blueprint/slice {:shapes '[[Concept [:map [:id :string] [:label :string]]]
                               Release]
                     :interfaces '[(lookup [store cs code opts])
                                   (children [store iri])
                                   (descendants [store iri opts])
                                   (default-opts)]
                     ;; qualified: the calls gate grants by ns/name, and an
                     ;; unqualified entry grants nothing (sigs/check-deps-sigs)
                     :deps-sigs '[(app.store/query [store q opts])]}
   :files/target ["src/app/service.clj"]
   :files/context ["src/app/store.clj" "src/app/model.clj"]
   :repl/worktree "/work/app-wt-07"
   :repl/port 7807
   :layer/name :service
   :gates {:retry-cap 3}})

;; ---------------------------------------------------------------------------
;; The profile: which model answers for which role
;; ---------------------------------------------------------------------------

(def RoleProfile
  "One dispatched role's model and how to reach it.

  `:shape` selects a tool-call adapter, not a vendor: OpenRouter normalises
  frontier models to the OpenAI shape, and MTPLX and oMLX serve both shapes
  locally, so two adapters reach every model this is likely to name.

  `:key-env` NAMES an environment variable; it never holds a key. A profile is
  committed, and a schema that made room for a secret would eventually be
  handed one.

  `:params` and `:serving` are different things and are kept apart on purpose:
  `:params` is what goes in the request body, `:serving` is how a LOCAL model
  is served — quantisation, context length, the rest. Collapsing them loses
  which of the two changed when an answer changes."
  [:map {:closed true}
   [:family :keyword]
   [:model [:string {:min 1}]]
   [:shape [:enum :openai :anthropic]]
   [:endpoint [:string {:min 1}]]
   [:key-env {:optional true} [:string {:min 1}]]
   [:params {:optional true} [:map-of :keyword :any]]
   [:serving {:optional true} [:map-of :keyword :any]]
   ;; How many rounds - completions, each answering the last tool result - one
   ;; dispatch of this role may take before the harness stops it. A PROJECT'S
   ;; POLICY, like the retry cap, and per role: a Tester writing a test per
   ;; target takes more rounds than a Coder writing one namespace. Absent, the
   ;; harness's own default applies (`harness.models.agent/defaults`), which the
   ;; first real project could not change from anywhere in its plan.
   [:max-rounds {:optional true} pos-int?]
   ;; List prices, for an endpoint that reports usage and no cost (Anthropic
   ;; direct). A cost computed from these is marked as computed wherever it is
   ;; shown. :source and :as-of are REQUIRED with the rates: a price with no
   ;; date is the rot harness.models.provenance's docstring warned a price table would
   ;; suffer, and the profile is where the user, not the harness, keeps it.
   [:pricing {:optional true}
    [:map {:closed true}
     [:per-mtok [:map {:closed true}
                 [:in number?] [:out number?]
                 [:cache-write-5m number?] [:cache-write-1h number?]
                 [:cache-read number?]]]
     [:source [:string {:min 1}]]
     [:as-of [:string {:min 1}]]]]])

(def Profile
  "One seat, and one model per dispatched role.

  EXACTLY ONE SEAT, and it is a scalar rather than a collection so that it
  cannot grow a second. A project has one interactive client — the one the
  human is sitting in — and the three dispatched roles are the harness's
  business, reached over HTTP and not through anyone's editor. Working in one
  client while a reviewer runs in another is the arrangement this shape is
  built to make unrepresentable.

  All six roles are required. §07 dispatches the Coder, the Tester and the
  Reviewer on every task; the loop dispatches the Orchestrator's triage call on
  every red gate and every note it has to route; `start` sends every spec to
  the spec reviewer before it dispatches; and `bb blueprint-review` sends each
  stage's Blueprint to the Blueprint reviewer before the human signs it off. A
  profile that omits one describes a build that cannot run.

  THE ORCHESTRATOR IS NOT A VERIFIER. It reads the failing gate against the
  contract and both roles' files and says who owns the failure; it writes no
  code and judges no diff, so §05's family rule does not reach it, and
  `harness.models.profile/verifiers` does not list it. It MAY share the Coder's
  family, and the shipped `claude.edn` does — accepted by decision on
  2026-09-16 and watched, not checked.

  THE BLUEPRINT REVIEWER IS THE SECOND SUCH EXCEPTION. It reads the seat's own
  Blueprint, as the spec reviewer reads the seat's spec, and by the same
  reasoning would be held to another family; it is not, by decision on
  2026-09-25 from a measurement (the shipped example's comment has it), and
  `verifiers` does not list it either. Watched, not checked."
  [:map {:closed true}
   [:seat :keyword]
   [:roles [:map {:closed true}
            [:coder RoleProfile]
            [:tester RoleProfile]
            [:reviewer RoleProfile]
            [:spec-reviewer RoleProfile]
            [:blueprint-reviewer RoleProfile]
            [:orchestrator RoleProfile]]]])

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
  read site, for a validator that can actually fail.

  AND :notes IS DECLARED RATHER THAN PUT IN :runner/meta, which the paragraph
  above would otherwise send it to. The test is whether the LOOP has to branch
  on it, and it does: a note is what a role says about the CONTRACT, and it
  reaches the next dispatch as `:task/feedback`. Nothing about it is
  runner-specific — a ManualRunner could fill it in from a human.

  It exists because the channel was not missing, it was PROSE. One early Coder
  said plainly that the contract was silent about dividing 7 by 2; another
  said why it declined a `:deps-sigs` entry it had been given. Both went into
  :stdout, which nothing reads, so both observations died with the dispatch."
  [:map {:closed true}
   [:status [:enum :done :failed]]
   [:files [:vector :string]]
   [:stdout [:maybe :string]]
   [:cost [:maybe number?]]
   [:notes {:optional true} [:vector [:string {:min 1}]]]
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
   [:out :string]
   ;; Wall time. Required-but-nullable for the same reason as :exit — a
   ;; skipped entry must not hand a consumer a different shape than a passing
   ;; one did. §10 measured the Tester at >80% of wall time; nothing could
   ;; have told you that without this field.
   [:ms [:maybe nat-int?]]])

(def RunStep
  "One reportable step of a run: a gate, a dispatch, or a mechanical phase.

  :step/source says whether the numbers were measured or made up. The rest —
  :step/model, :step/provider, :step/cost — are nil for anything that did not
  call a model — and for anything ManualRunner ran, because a human's word is
  not a measurement. §10: log which agent, model and SERVING PROVIDER served
  every call. Provider is separate from model deliberately: one OpenRouter
  slug can be answered by several different hosts, and which one answered
  decides the chat template."
  [:map {:closed true}
   [:step/name :keyword]
   ;; :precondition is a check that can REJECT A TASK BEFORE DISPATCH —
   ;; harness.contract.sigs verifying the slice's :deps-sigs against the source they
   ;; describe. An early run found it missing: it is not provisioning, which builds
   ;; a workspace, and it is not a gate, which judges what an agent wrote.
   ;; Folding it into either would hide the cheapest step in the run.
   [:step/kind [:enum :provision :precondition :dispatch :gate-0 :gate]]
   [:step/status [:enum :pass :fail :skipped :done]]
   [:step/ms [:maybe nat-int?]]
   ;; Where the numbers came from. REQUIRED, and with no default: a caller who
   ;; has not thought about it gets a validation failure rather than a silent
   ;; :measured. Rule :label-fabricated says to label what you made up; this is
   ;; what makes forgetting impossible rather than merely forbidden — the
   ;; renderer marks the row from the data, not from the caller's discipline.
   [:step/source [:enum :measured :synthetic]]
   [:step/model {:optional true} [:maybe :string]]
   [:step/provider {:optional true} [:maybe :string]]
   ;; Which of a provider's tiers answered. OpenAI's flex and standard
   ;; endpoints both report provider "OpenAI" at different prices, so without
   ;; this a flex run's report is indistinguishable from a standard one.
   [:step/service-tier {:optional true} [:maybe :string]]
   [:step/cost {:optional true} [:maybe number?]]
   ;; Where the cost came from: the endpoint's own record, or usage times the
   ;; profile's list prices. The renderer marks the second with ~, in the cell.
   [:step/cost-source {:optional true} [:maybe [:enum :reported :list-price :repriced]]]
   ;; The completion ids behind the step, so a cost the endpoint had not
   ;; written when the record was taken can be fetched by `bb reprice`
   ;; later. Only when known: a record written before this key is unchanged.
   [:step/generation-ids {:optional true} [:vector :string]]
   ;; The rounds the dispatch took - its completions - so a report says how
   ;; close to the role's limit it came. Only when known: an older record is unchanged.
   [:step/rounds {:optional true} pos-int?]
   ;; Total native tokens. A single number because this is a report column;
   ;; the prompt/completion split belongs in :runner/meta, which is where
   ;; :tokens already lived upstream (see PROVENANCE.md) before it was one of
   ;; the five undeclared keys that motivated closing AgentResult.
   [:step/tokens {:optional true} [:maybe nat-int?]]])

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
  "Append-only run-log record. :run/cost is nil when the client reports none.

  `:run/cost` IS THE LOOP'S COST: the sum of the steps. A spec review is a desk
  step before `start`, not a step, and its cost is in the `:spec-review` event
  (`:reviews`, every reading); `bb report` prints the two added together.

  `:run/attempts` IS THE HIGHEST ATTEMPT OF ANY ONE ROLE, not the dispatch
  rounds. A run that sent the Tester back once and the Coder back once has
  attempts 2 and rounds 3. The rounds are derivable from the events, and the
  report's `Rounds:` line derives them."
  [:map
   [:run/id [:string {:min 1}]]
   [:task/id [:string {:min 1}]]
   [:run/attempts nat-int?]
   [:run/status RunStatus]
   [:run/cost [:maybe number?]]
   [:run/started-at inst?]
   [:run/steps {:optional true} [:vector RunStep]]
   [:run/events {:optional true} [:vector :any]]
   ;; The endpoint and the NAME of the key variable per role, so `bb reprice`
   ;; can fetch a generation record from the record alone. Never a key.
   [:run/roles {:optional true} [:map-of :keyword [:map [:model {:optional true} :any] [:family {:optional true} :any] [:effort {:optional true} :any]
                                                   [:endpoint {:optional true} :any] [:key-env {:optional true} :any]
                                                   [:max-rounds {:optional true} :any]]]]
   ;; The KIT, the application and the plan at `record`, as `git rev-parse HEAD` gave
   ;; them; nil where there is no such repository. Optional: records before them have none.
   [:run/kit-commit {:optional true} [:maybe :string]]
   [:run/app-commit {:optional true} [:maybe :string]]
   [:run/plan-commit {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------

(defn valid-packet? [packet] (m/validate TaskPacket packet))
(defn valid-spec? [spec] (m/validate TaskSpec spec))
(defn valid-result? [result] (m/validate AgentResult result))
(defn valid-gate-result? [result] (m/validate GateResult result))
(defn valid-run? [run] (m/validate TaskRun run))
(defn valid-step? [step] (m/validate RunStep step))
(defn valid-session? [session] (m/validate Session session))
(defn valid-profile? [profile] (m/validate Profile profile))

(defn explain-packet
  "Humanized validation errors for a packet, or nil when valid."
  [packet]
  (some-> (m/explain TaskPacket packet) me/humanize))

(defn explain-spec
  "Humanized validation errors for a task spec, or nil when valid."
  [spec]
  (some-> (m/explain TaskSpec spec) me/humanize))

(defn explain-result
  [result]
  (some-> (m/explain AgentResult result) me/humanize))

(defn explain-run
  [run]
  (some-> (m/explain TaskRun run) me/humanize))

(defn explain-step
  [step]
  (some-> (m/explain RunStep step) me/humanize))

(defn explain-profile
  [profile]
  (some-> (m/explain Profile profile) me/humanize))

;; ---------------------------------------------------------------------------
;; The loop's configuration: loop.edn, described once
;; ---------------------------------------------------------------------------

(def loop-keys
  "Every key `loop.edn` may hold, described once: its type, its default, what it
  is for, and the namespace that reads it. `bb init` renders the file's header
  from this; `resolve-config` fills the defaults from it and refuses a key not
  in it by name; the plan template's §13 names only keys that are here. A key's
  namespace names the thing it configures (`:spec-review/max` beside
  `:spec-review/run?`), which is also what reads it; `:architecture`, a map
  with keys of its own, is the one bare key and keeps its name.

  `:derived` keys are set by `resolve-config` from the workspace and are never
  written in the file; they are here so a resolved config is described too.
  A `:computed` default depends on the run or the workspace and is filled in
  `resolve-config`; the doc says from what. Before this table the defaults
  lived in `resolve-config`'s merge, the keys were documented in the driver's
  docstring, the header `bb init` writes and the plan template's §13, and a
  key misspelt in `loop.edn` was a default silently applied - one of them,
  the spec review, spends money."
  [{:key :run/id :type :string :required true :reader 'harness.loop.driver
    :doc "this run's identity: its branches, worktrees and record are named by it"}
   {:key :profile :type :string :required true :reader 'harness.loop.driver
    :doc "the profile to dispatch with, a path: relative to the build repository in a workspace, else to the working directory"}
   {:key :repo/root :type :string :default :computed :reader 'harness.loop.driver
    :doc "the application's repository; absent, the workspace's application, else the repository the run directory is in"}
   {:key :repo/allow-dirty? :type :boolean :default false :reader 'harness.loop.driver
    :doc "start with uncommitted changes in the base checkout; off, start warns and names the files"}
   {:key :project/subdir :type :string :default nil :reader 'harness.loop.provision
    :doc "the project's folder inside the repository, when it is not the root"}
   {:key :gates :type :vector :default :computed :reader 'harness.gates.run
    :doc "the gate sequence, [[key \"command\"] ...], cheap first; absent, the harness's own (harness.gates.run/default-gate-seq)"}
   {:key :nrepl/cmd :type :vector :default ["clojure" "-Srepro" "-M:nrepl"] :reader 'harness.loop.provision
    :doc "the command that starts a role's nREPL in its worktree"}
   {:key :worktrees/dir :type :string :default :computed :reader 'harness.loop.provision
    :doc "where the roles' worktrees go: the workspace's work/worktrees/<run-id>, else the system's temp folder"}
   {:key :architecture :type :map :default nil :reader 'harness.loop.provision
    :doc "{:from \"arch\" :files [\"layers.edn\"]}: files assembly copies into the gate worktree beside the roles' - a task that adds a namespace adds its layers.edn entry this way, since no dispatched role writes that file"}
   {:key :spec-review/run? :type :boolean :default true :reader 'harness.loop.driver
    :doc "read the spec cold before the first dispatch; off only where the spec was reviewed another way (the health check)"}
   {:key :spec-review/max :type :int :default 2 :reader 'harness.loop.driver
    :doc "spec reviews that found something before a person reads them"}
   {:key :plan-check/run? :type :boolean :default true :reader 'harness.setup.plan
    :doc "check the plan once per workspace before the first dispatch; off only where there is no plan (the health check)"}
   {:key :notes/pause? :type :boolean :default true :reader 'harness.loop.driver
    :doc "stop after a dispatch that left a note for the Architect, before the gates run"}
   {:key :records/dir :derived true :reader 'harness.loop.driver
    :doc "where record copies run.edn: the workspace's records folder"}
   {:key :plan/root :derived true :reader 'harness.loop.driver
    :doc "the build repository, for the commits a record names"}
   {:key :kit/root :derived true :reader 'harness.loop.driver
    :doc "the KIT's clone, for the commits a record names"}])

(def loop-file-keys
  "The keys `loop.edn` may hold: every described key that is not derived."
  (into #{} (comp (remove :derived) (map :key)) loop-keys))

(def loop-known-keys
  "Every described key, derived ones included: what a resolved config may hold."
  (into #{} (map :key) loop-keys))

(defn loop-defaults
  "The literal defaults as a map - what `resolve-config` merges under the file
  before the computed ones. A required key has no default and is not here."
  []
  (into {} (for [{:keys [key default derived required]} loop-keys
                 :when (and (not derived) (not required) (not= :computed default))]
             [key default])))

(defn unknown-loop-keys
  "The keys of `cfg` that no entry of `loop-keys` describes, in the file's order."
  [cfg]
  (vec (remove loop-known-keys (keys cfg))))

(defn loop-key-lines
  "One line per file key, for a header: the key, what it is for, and its default
  or that it is required."
  []
  (for [{:keys [key default required doc derived]} loop-keys :when (not derived)]
    (str key " - " doc (cond required " (required)"
                             (= :computed default) ""
                             :else (str " (default " (pr-str default) ")")))))
