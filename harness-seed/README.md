# harness-seed

A small, working starting point for the orchestration harness described in
[`../method.md`](../method.md) §12 — the thing that assembles
task packets, dispatches them to agents, and runs the quality gates.

**It is a seed you copy and edit, not a framework you depend on.** About 500 lines
of source. Delete what you don't need; the parts you keep are meant to be changed.

```bash
bb doctor     # what your Clojure toolchain has, and what it's for
bb gates      # doctor -> format -> lint -> rules -> test
bb example    # the whole loop shape in one run: no model calls, no network
```

## Prerequisites

`bb`, `cljfmt`, `clj-kondo` to run the gates; `clj-paren-repair` (via `bbin`) for
gate 0. `bb doctor` tells you what's missing and why it matters — run it first.
The first `bb test` fetches malli into `~/.m2`; everything after that is offline.

## The six pieces

| Namespace | What it is | The lesson it encodes |
|---|---|---|
| `harness.doctor` | Toolchain probe and report | A missing small binary doesn't fail the loop — it quietly spends the Coder's retry budget on parens. Check the toolchain; don't assume it. |
| `harness.shapes` | Malli schemas: packet, result, gate result, run record | A result contract that isn't enforced isn't a contract. `AgentResult` is **closed**. |
| `harness.packet` | Cuts a role-specific packet from a task spec | The Tester's context excludes the Coder's impl — **mechanically**, not by asking a Blueprint author to remember. |
| `harness.gates` + `harness.repair` | Ordered, short-circuiting gate runner; gate 0 | Cheap before expensive; a failing gate returns *what it said*, not just which one; a broken gate config fails the gate, not the run. |
| `harness.runner` + `harness.runner-check` | The `AgentRunner` seam, a `ManualRunner`, and a conformance check | Ship the mechanics first with a human at the invocation point. Then check every runner you add against the same contract. |
| `harness.rules` | One rule source, rendered into prompts *and* into `CLAUDE.md`, with a gate on drift | Prompt rules beat retry feedback. A rule written in two places rots; a rule written only in a file never reaches a model family that doesn't read files. |

## The rule source

> **Rule source** here means the single authoritative set of rule records — not a body of
> text for training. Every other place that holds rules is a *rendering* of it.

`resources/agent-rules.edn` is that file: every rule an agent is told to follow is stated
there once. Each rule carries an `:audience` — `#{:coder :tester :human}` — and two
renderings derive from it:

- **into a headless agent's system prompt**, filtered by audience, with `{{placeholders}}`
  substituted per dispatch;
- **into `CLAUDE.md`'s marker block** by `bb rules-sync`, drift-checked by `bb rules-check`
  inside `bb gates`.

Edit rules there — never in a prompt string, never by hand inside `CLAUDE.md`'s marker
block, which the gate reports as drift.

### Adding something by hand

Only the **block between the markers** is generated. Everything above and below it is
hand-written and survives `bb rules-sync` untouched — so there is a place for manual
content, and the only question is which bucket you are in:

| What you are adding | Where it goes |
|---|---|
| A rule for agents | `resources/agent-rules.edn`, then `bb rules-sync` |
| A rule only humans need | `agent-rules.edn` with `:audience #{:human}` — renders here, reaches no prompt |
| **Not a rule** — orientation, the task surface, pointers to docs | Hand-written in `CLAUDE.md`, outside the markers |

Note that adding to the rule source *is* manual — you hand-write the record. The rule was
never "don't write rules by hand"; it is "write them in the source, not in a rendering."

The test for which bucket: **would a headless agent on a non-Claude family need this?** If
yes, it must be in the rule source, because the prompt rendering is the only thing that
reaches it.

> **The trap.** A rule written outside the markers survives every sync, so nothing ever
> complains — and it reaches only agents that read `CLAUDE.md`, which by the independence
> rule excludes your verifier. Silent, and exactly the failure this design exists to
> prevent.

**Known limitation.** `:text` renders as a single markdown bullet, so inline formatting
(`code`, *emphasis*) works and fenced code blocks, tables and sub-bullets do not. If a rule
needs those, extend `harness.rules/markdown` — do **not** hand-write it outside the markers.

**Why a rule source and not just `CLAUDE.md`.** A `CLAUDE.md` file is specific to one vendor's
client. The method requires the agent that verifies the Coder to be a *different model
family*, and that agent reads no `CLAUDE.md` at all — it gets an HTTP request with a system
prompt. Rendering into the prompt is the only channel that reaches every family.

**Three layers, and which one wins.** Rules arrive from a personal global file, from the
project file, and from the prompt. They merge silently, so they can contradict each other
without either side knowing — a personal *"run the tests after changing a namespace"* against
a project *"never run the gates yourself"* is a real collision, and obeying the wrong one
breaks the method's central separation. Sort them this way:

| Layer | Holds | When it loses |
|---|---|---|
| Personal / global | Your taste, across all projects | Always, to a project rule |
| Project `CLAUDE.md` | This project's rules, in the generated block — the rest of the file is yours | Never, except to the rule source it came from |
| The rule source, in the prompt | Anything a gate enforces | Never — this is the only layer the project controls absolutely |

The rule source states its own precedence as rule `:precedence`, so whichever channel an agent
reads, it learns the ordering. That is the cheap mitigation; there is no mechanism that
stops the layers merging.

## Working beside the loop

`agents/interactive-programmer.md` defines the role for work that does *not* go through the
loop: building the harness, modelling sessions, and debugging the worktree an escalation
left behind. Copy it to `.claude/agents/` at your repository root.

It inherits the same rules, with one deliberate inversion — they tell dispatched
agents *not* to run the gates, because authoring and running are separate jobs inside the
loop. Nobody is gating this role, so it runs them itself.

## Pinning tool versions

The seed ships no `.mise.toml` of its own — it runs entirely under `bb` and needs no
JVM, so a pin it does not have would fail `bb gates` on your machine for no reason.

Your project should have one, and `bb doctor` verifies it:

```toml
# .mise.toml
[tools]
java = "temurin-21.0.2+13.0.LTS"   # XTDB v2 needs 21 exactly: it fails at
clojure = "1.12.1.1550"            # class-load on newer JDKs
babashka = "1.12.206"              # bbin needs >= 1.12.212
```

The doctor reads the `[tools]` table and checks the **major** version. mise enforces the
exact pin; the doctor catches the class of breakage that actually bites — a JDK 25 where
21 was pinned. Flagging 21.0.12 against a 21.0.2 pin would be noise.

It also warns when `JAVA_HOME` is on a different major than the `java` on PATH. A pin that
only checks PATH is defeated by any launcher that honours `JAVA_HOME`, and the two
routinely disagree once more than one version manager is installed — mise, jenv and
macOS's own `java_home` will happily give you three different answers.

## The `:runner/meta` convention

`AgentResult` is a **closed** schema. Anything client-specific goes in
`:runner/meta`:

```clojure
{:status :done :files ["src/app/service.clj"] :stdout nil :cost 0.12
 :runner/meta {:session-id "abc-123"}}     ; not (:session-id result)
```

In the project this came from, five such keys — `:session-id`, `:verdict`,
`:capped?`, `:provider`, `:tokens` — accreted onto the top level over two months
and were never declared. The orchestrator branched on all five. Because malli's
`:map` is open by default, the validator could never have caught them, and it was
only ever called from tests anyway.

Extras are legitimate; that was never the problem. Invisibility was. The honest
cost of the fix: callers write `(get-in r [:runner/meta :session-id])` instead of
`(:session-id r)` — five characters at each read site, in exchange for a validator
that can actually fail, and a conformance check that catches a typo'd
`:sessionId` instead of silently disabling session resume.

## Adapting it

- Rename the `harness.*` root to your own, or keep it.
- Put your project's commands in `gates/default-gate-seq`. **The gate keys are a
  public contract** — whatever routes a failure dispatches on them, and the run
  log is read by key months later.
- Wire the architecture-boundary gate (§09's gate 4) with a placeholder ruleset
  before you know your real layers. Retrofitting it later is a much worse job.
- Add your stack's mechanical repairs to `harness.repair` **and nowhere else**.
  That is what keeps `harness.gates` dependency-light and gives a reader on
  another stack exactly one file to rewrite.
- Add a headless runner by implementing one method, then run it through
  `check-runner`. For testing one, the technique that works is a generated
  executable stub script that records its argv — make the executable name an
  option so tests point at the stub and only the live run spends quota.

## Deliberately left out

Each of these was considered and cut, not forgotten:

- **The orchestration loop** — §12's own advice is not to build it speculatively.
  It is also where client-specific leakage collects: session-resume branching, a
  retry key that appears in no schema, per-runner cost semantics.
- **Triage** — routing a failure to the role that owns it is a real seam, but any
  implementation hardcodes your gate keys and encodes one project's opinion about
  who is usually at fault.
- **Workspace provisioning** — git-worktree-and-nREPL specific, and short enough
  to write against your own layout.
- **Headless runners** — model- and CLI-coupled; they would be stale within months.
  `check-runner` is the more durable half of that knowledge.
- **Per-gate timeout** — a real hazard, but no run in the source project ever hung
  on a gate. `gates/run-gate` is the insertion point if you need one.

The build order for adding them back, once you have felt where the manual version
hurts: provisioning → triage and capped retry → the first headless runner → the
rest → an append-only run log → the two human pause points.

## Provenance

See [PROVENANCE.md](PROVENANCE.md). Short version: this is a **fork, not a
mirror**, of code that still lives elsewhere, and the divergences are listed
there with their reasons.
