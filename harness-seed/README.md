# harness-seed

A small, working starting point for the orchestration harness described in
[`../method.md`](../method.md) §12 — the thing that assembles task packets,
dispatches them to agents, and runs the quality gates.

**This file is the authority on what the seed contains, how to adapt it, what is
deliberately left out, and the order to add the rest back.** Other documents link
here rather than restating it.

**It is a seed you copy and edit, not a framework you depend on.** About 4,000
lines of source, 650 of driver, and 3,500 of tests (`wc -l` over `src/`, `dev/` and
`test/`, 2026-09-14). Much of the source is commentary: nearly every
namespace says WHY it exists and what run taught it, because that is the part you
cannot reconstruct from the code. Delete what you don't need; the parts you keep
are meant to be changed.

```bash
bb doctor     # what your Clojure toolchain has, and what it's for
bb gates      # doctor -> format -> lint -> rules -> reports -> test
bb repair     # gate 0 over the Clojure files you changed (run before bb gates)
bb example    # the whole loop shape in one run: no model calls, no network
```

## Prerequisites

`bb`, `cljfmt`, `clj-kondo` to run the gates; `clj-paren-repair` (via `bbin`) for
gate 0. `bb doctor` tells you what's missing and why it matters — run it first.
The first `bb test` fetches malli into `~/.m2`; everything after that is offline.

## The pieces

| Namespace | What it is | The lesson it encodes |
|---|---|---|
| `harness.doctor` | Toolchain probe and report | A missing small binary doesn't fail the loop — it quietly spends the Coder's retry budget on parens. Check the toolchain; don't assume it. |
| `harness.shapes` | Malli schemas: packet, result, gate result, run record | A result contract that isn't enforced isn't a contract. `AgentResult` is **closed**. |
| `harness.packet` | Cuts a role-specific packet from a task spec | The Tester's context excludes the Coder's impl — **mechanically**, not by asking a Blueprint author to remember. |
| `harness.gates` + `harness.repair` | Ordered, short-circuiting gate runner; gate 0 | Cheap before expensive; a failing gate returns *what it said*, not just which one; a broken gate config fails the gate, not the run. |
| `harness.runner` + `harness.runner-check` | The `AgentRunner` seam, a `ManualRunner`, and a conformance check | Ship the mechanics first with a human at the invocation point. Then check every runner you add against the same contract. |
| `harness.rules` | One rule source, rendered into prompts *and* into `AGENTS.md`, with a gate on drift | Prompt rules beat retry feedback. A rule written in two places rots; a rule written only in a file never reaches a model family that doesn't read files. |
| `harness.provision` | Three worktrees per task, each with its own nREPL; assembly as a filter | Isolation asserted is isolation absent. Only a role's declared `:files/target` crosses into the gate workspace, and anything else is refused by name. |
| `harness.stub` | The Blueprint slice as code the Tester can load | A worktree is a checkout of the whole repo, so the previous implementation is sitting there to be read. Write the contract over it. |
| `harness.profile` | One seat, and a family/model/endpoint per dispatched role | §05's independence rule — *verifier ≠ Coder family* — had lived in prose since it was decided. This is the first thing that can fail on it. Two worked examples ship, because a single one reads as *your* configuration. |
| `harness.sigs` | The slice's `:deps-sigs`, checked against the source they describe; the implementation's calls, checked against the slice; and a rewrite's dependents, for `:files/context` | A wrong signature is worse than a missing one: the agent is told a function exists and the failure surfaces as *its* fault, two gates later. A dependent nobody was shown breaks at the test gate, after a paid attempt. |
| `harness.adapter` + `harness.provenance` | Two request shapes, and the second call that says who answered; for an endpoint with no such call, usage × the profile's list prices, marked as computed | A completion names no provider. One slug can be served by several hosts at several quantisations, and which one answered decides the chat template. A price table in the harness would rot; one in the profile carries its source and date. |
| `harness.tools` + `harness.agent` | `read_file`, `write_file`, `nrepl_eval`, and the loop that drives them, which keeps a transcript — what the model said and ran, per completion — and sends a request again on a rate limit or an overloaded host, counting how often | A tool failure returns to the model as data. `nrepl_eval` is a shell, unavoidably — the containment is the worktree, not the tool list. A dispatch that wrote nothing used to leave nothing to say why. |
| `harness.report` | Per-step time, cost, model and serving provider for a run, and the drift gate over the reports a document publishes | A number nobody measured must say so, in the cell. A total that silently omits three dispatches is worse than no total. And a published number whose record has gone missing is unverifiable, so `bb report-check` fails on it — the same treatment `AGENTS.md` gets, applied to the other kind of generated content this repository commits. |

## The rule source

> **Rule source** here means the single authoritative set of rule records — not a body of
> text for training. Every other place that holds rules is a *rendering* of it.

`resources/agent-rules.edn` is that file: every rule an agent is told to follow is stated
there once. Each rule carries an `:audience` — a subset of `#{:coder :tester :reviewer :human}` — and two
renderings derive from it:

- **into a headless agent's system prompt**, filtered by audience, with `{{placeholders}}`
  substituted per dispatch;
- **into `AGENTS.md`'s marker block** by `bb rules-sync`, drift-checked by `bb rules-check`
  inside `bb gates`.

The prompt rendering has a CLI: `bb rules-prompt --audience coder --repl-port 7807`
writes the block to stdout, or to `--out FILE`. Where it goes is the caller's
problem — Pi replaces its system prompt from a file, a headless runner interpolates
it into an HTTP request.

Edit rules there — never in a prompt string, never by hand inside `AGENTS.md`'s marker
block, which the gate reports as drift.

### Adding something by hand

Only the **block between the markers** is generated. Everything above and below it is
hand-written and survives `bb rules-sync` untouched — so there is a place for manual
content, and the only question is which bucket you are in:

| What you are adding | Where it goes |
|---|---|
| A rule for agents | `resources/agent-rules.edn`, then `bb rules-sync` |
| A rule only humans need | `agent-rules.edn` with `:audience #{:human}` — renders here, reaches no prompt |
| **Not a rule** — orientation, the task surface, pointers to docs | Hand-written in `AGENTS.md`, outside the markers |

Note that adding to the rule source *is* manual — you hand-write the record. The rule was
never "don't write rules by hand"; it is "write them in the source, not in a rendering."

The test for which bucket: **would a headless agent that reads no file at all need this?** If
yes, it must be in the rule source, because the prompt rendering is the only thing that
reaches it.

> **The trap.** A rule written outside the markers survives every sync, so nothing ever
> complains — and it reaches only agents that read `AGENTS.md`, which by the independence
> rule excludes your verifier. Silent, and exactly the failure this design exists to
> prevent.

**Known limitation.** `:text` renders as a single markdown bullet, so inline formatting
(`code`, *emphasis*) works and fenced code blocks, tables and sub-bullets do not. If a rule
needs those, extend `harness.rules/markdown` — do **not** hand-write it outside the markers.

**Why a rule source and not just `AGENTS.md`.** A rules file only reaches clients that read
files. The method requires the agent that verifies the Coder to be a *different model
family*, and that agent reads no file at all — it gets an HTTP request with a system
prompt. Rendering into the prompt is the only channel that reaches every family.

**Why `AGENTS.md` and not `CLAUDE.md`.** The Antigravity IDE, OpenCode and Pi read
`AGENTS.md` natively; Claude Code reads only `CLAUDE.md`. Rather than generate two mirrors that can each drift,
the seed generates `AGENTS.md` and ships a `CLAUDE.md` that imports it — one marker block,
one drift target, one place a rule can be hand-written outside the markers. Note that Pi
loads *both* files when both exist, so the stub is written to read sensibly as plain text
rather than relying on the `@` import alone.

**Three layers, and which one wins.** Rules arrive from a personal global file, from the
project file, and from the prompt. They merge silently, so they can contradict each other
without either side knowing — a personal *"run the tests after changing a namespace"* against
a project *"never run the gates yourself"* is a real collision, and obeying the wrong one
breaks the method's central separation. Sort them this way:

| Layer | Holds | When it loses |
|---|---|---|
| Personal / global | Your taste, across all projects | Always, to a project rule |
| Project `AGENTS.md` | This project's rules, in the generated block — the rest of the file is yours | Never, except to the rule source it came from |
| The rule source, in the prompt | Anything a gate enforces | Never — this is the only layer the project controls absolutely |

The rule source states its own precedence as rule `:precedence`, so whichever channel an agent
reads, it learns the ordering. That is the cheap mitigation; there is no mechanism that
stops the layers merging.

## Working beside the loop

`agents/interactive-programmer.md` defines the role for work that does *not* go through the
loop: building the harness, modelling sessions, and debugging the worktree an escalation
left behind. Copy it to your client's agent directory — `.claude/agents/` for Claude Code,
`.opencode/agents/` for OpenCode. Step 3 of `../portability.md` proposes generating these
per client from a role source rather than hand-maintaining one file per format.

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
- **Three namespaces are stack-specific and the rest are not.** `harness.repair`
  runs your language's mechanical fixups, `harness.stub` emits its source, and
  `harness.sigs` reads it. Add your stack's repairs to the first **and nowhere
  else** — that is what keeps `harness.gates` dependency-light and gives a reader
  on another stack exactly three files to rewrite.
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
- **Per-gate timeout** — a real hazard, but no run in the source project ever hung
  on a gate. `gates/run-gate` is the insertion point if you need one.

What *is* here is `dev/run_loop.clj` (`bb run-loop`): the loop's steps as commands —
start, continue, check, retry, amend, mutation, record, teardown — pausing wherever a role
leaves a note, and with a human writing a triage
decision before every retry. It is the manual version, committed after three runs
(D7–D9) went through one script. The loop that routes failures by itself is still left
out; what it does instead is the mechanical half of triage: on a red gate, `check` records
which role owns the files the gate named and what a retry would carry, as a proposal beside
the decision that follows, and `retry tester` refuses feedback that names the
implementation — the Reviewer's findings, an impl file, a var the slice never granted, a
code block — unless `--allow-leak` records the judgement to send it.

The build order for adding them back, once you have felt where the manual version
hurts. Provisioning and the first headless runner are done — see `../DEVLOG.md`;
what is left, in dependency order: triage and a capped retry → an append-only run
log → the two human pause points.

## Provenance

See [PROVENANCE.md](PROVENANCE.md). Short version: this is a **fork, not a
mirror**, of code that still lives elsewhere, and the divergences are listed
there with their reasons.
