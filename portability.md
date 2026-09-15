# Portability — which seat, which models, and what follows

Can this kit be driven from an interactive harness other than Claude Code — the Antigravity
IDE, OpenCode 2, Pi — and with models other than Anthropic's, including local ones served by
LM Studio, MTPLX or oMLX?

Yes. The seat work is done; see `git log`. What remains is the dispatch side, which is
**designed and not built**, and most of this document is that design.

> **Client facts here were verified on 2026-09-11** against `claude` 2.1.268, `agy-ide`
> 1.107.0, `opencode` 1.16.2, `opencode2` 0.0.0-beta-19425 and `pi` 0.85.1 — read off the
> installed binaries and the docs they ship, not from the web. They will rot faster than
> anything else in this repository. Re-check before trusting them.

## 1 · The method is already multi-model by mandate

`method.md` §05 requires any agent verifying another agent's output to run on a *different
model family*. The rule source exists because of that rule — `resources/agent-rules.edn` says
so in its own header: a rules file reaches only clients that read files, the verifying agent
reads none, and rendering into the prompt is the only channel that reaches every family.

`method.md` names no vendor, no client and no model anywhere, and §11 makes discarding vendor
picks an explicit rule. This was never a port; it was finishing a job the design started.

## 2 · "Harness" means three things

These get collapsed constantly, and the distinction decides most of the design:

| | What | How many |
|---|---|---|
| **The interactive seat** | Where the human works: planning, design, architecture. Reads `AGENTS.md`. §05's seventh role, off the loop. | **Exactly one per project** |
| **The dispatch harness** | Babashka + `AgentRunner`, sending packets to Coder / Tester / Reviewer. Not a seat. | One |
| **The model per dispatched role** | A different family per role, per §05 | One per role |

**§05's independence rule lives in the dispatch harness, by model family — not in the seat.**
You do not open a second interactive client to review your own work; the Reviewer is a headless
dispatch with a packet. A project has one seat and one harness, and the harness varies the
*model* per role.

**The dispatch harness is not set up for anything.** The only `AgentRunner` implementation is
`ManualRunner`, which pprints a packet and blocks on `read-line`. `harness-seed/README.md` cut
headless runners deliberately — *"model- and CLI-coupled; they would be stale within months"* —
and `PROVENANCE.md` confirms `coder.clj` / `tester.clj` / `reviewer.clj`, including *"a 226-line
tool-call loop"*, were not extracted. There is an empty seam and a conformance check
(`runner-check/check-runner`) waiting for a first implementation.

## 3 · What each candidate seat offers

| | Antigravity IDE | OpenCode 2 | Pi | Claude Code |
|---|---|---|---|---|
| Rules file | **AGENTS.md** / GEMINI.md | **AGENTS.md** only | **AGENTS.md** > CLAUDE.md | CLAUDE.md (+ `@` imports) |
| Role definition | **none** — rules/skills/hooks only | `.opencode/agents/*.md` | `.pi/SYSTEM.md`, skills | `.claude/agents/*.md` |
| Per-role model | — | `model: provider/model#variant` | `--model` | — |
| Per-role permissions | presets | ordered `{action, resource, effect}` | `--tools` / `--exclude-tools` | `tools:` |
| Headless + JSON | **none** — an IDE launcher | `run --format json`; `serve` + SDK | `-p --mode json\|rpc` | `-p --output-format json` |
| Write-time hooks | `PreToolUse`/`PostToolUse` + `matcher` | plugin `setup()` + `ctx.tool.hook` | TS extension | `PreToolUse`/`PostToolUse` |

Three of four read `AGENTS.md`, which is why one generated mirror covers them all. All four
support write-time hooks.

**Antigravity has no custom-agent file format**, and this is easy to get wrong — widely
repeated claims say otherwise. Read from the `agy-customizations` skill shipped inside both the
IDE and the CLI, which are near-identical: the five customization types are Rules
(`GEMINI.md`/`AGENTS.md`, explicitly no frontmatter), Skills (`skills/<name>/SKILL.md`),
Plugins (`plugins/<name>/plugin.json`), Hooks (`.agents/hooks.json`) and MCP servers,
discovered from `.agents/` at the project root or `~/.gemini/config/`.

**`agy-ide` is not a dispatch target either.** The CLI on `PATH` is a VS Code-style launcher —
`--diff`, `--merge`, `--goto`, `--install-extension`, `--add-mcp`. No `-p`, no
`--output-format`, no `--agent`. The agent lives in the IDE; the CLI opens windows. That
removes Antigravity from the `AgentRunner` question entirely and leaves its whole integration
with this kit as one file: `AGENTS.md`.

Gemini CLI was retired in June 2026 and replaced by Antigravity. Advice referencing `gemini` is
stale.

### OpenCode 2, if a CLI-backed runner is ever wanted

Its permissions are ordered `{action, resource, effect}` rules, last match wins, with v2 action
names `shell`, `edit`, `subagent`. It is the only client that could express the method's access
rules as client rules rather than prose — Reviewer read-only, Tester never runs the suite,
nobody runs the gates, Coder writes only its target.

Two caveats. It is **beta** ("plugin and SDK contracts are still being finalized ahead of
stable 2.0"), so shell `opencode2 run` rather than bind `@opencode/client`. And v1→v2 renamed
nearly everything — `prompt`→`system`, `bash`→`shell`, `task`→`subagent`, `write`/`patch`→`edit`,
`agent`→`agents`, `provider`→`providers` — which is itself an argument for generating client
files from a source rather than hand-writing them.

### The `CLAUDE.md` stub, and its one hazard

Claude Code reads only `CLAUDE.md`, so the seed ships one that imports `AGENTS.md`. Pi is the
case that makes the shape matter: with **both** files present it loads both and concatenates,
and it does not resolve `@` imports. So the stub must read sensibly as literal text rather than
rely on the import alone. A symlink is the alternative — simpler, but Pi then loads the same
content twice, and symlinks are awkward on Windows checkouts.

**The caveat that matters:** all of this helps only agents that read *files*. It does nothing
for the headless verifier, which reads none. `bb rules-prompt` remains the load-bearing piece.

## 4 · Local model servers are not harnesses

LM Studio, MTPLX and oMLX expose OpenAI-compatible endpoints; MTPLX and oMLX additionally speak
Anthropic `/v1/messages`. They are **model endpoints** that sit *under* a harness — no tools, no
file access, no agent loop.

Seat, harness and model family are three separate things. "Run it on a local model" means
pointing a harness's provider at `http://localhost:<port>/v1`, not replacing the harness. Two
consequences:

- Because MTPLX and oMLX serve the Anthropic API, **Claude Code can itself be pointed at a local
  model** — so "Claude Code as the seat" and "a local model" are not exclusive. Whether that
  satisfies §05 is a judgement call: the rule is about model *family*, but §05 also says the
  client matters, and one client means one client-side blind spot.
- oMLX's EnginePool holds several models in memory at once — §05's independence rule on a single
  machine.

## 5 · Write-time hooks are an accelerant, not the mechanism

`bb repair` is gate 0 for work done beside the loop, and it runs on every client including
Claude Code. It replaced the hook-capable / not-hook-capable fork rather than being its second
branch.

Hooks remain worth porting where cheap — Antigravity uses the same `PreToolUse`/`PostToolUse`
event names and a `matcher` in `.agents/hooks.json`, wrapped one level deeper under a hook name,
so the existing Claude hook is close to a config-envelope translation. Skip where not: three
hook artifacts in three config languages tracking three upstream APIs is the same "stale within
months" category that justified cutting the runners. Antigravity's hooks also carry a live bug
report of an injected `PreToolUse` denying every call with `invalid_args`, and an open question
about whether the IDE executes them at all.

The real mitigation is neither: it is the `:align-forms` rule, which reaches every family
through the prompt rendering. §09 — *"Gates and rules are one system, not two."*

---

# The dispatch side — designed, not built

Nothing below exists in code. There is no HTTP anywhere in `harness-seed/src`, no provisioning
namespace, no profile file, and no `AgentRunner` beyond `ManualRunner`. §12 argues against
building the loop speculatively; this is the design to build *when* it is wanted, not a
description of something that runs.

## API-backed, not client-backed

`AgentRunner` does not care what sits behind it. `runner_check.clj` records that the source
project grew *"four runners — a manual one, a CLI, an HTTP endpoint, a second CLI."*

| | CLI-backed | **API-backed** |
|---|---|---|
| Spawns | an agent client (`opencode2 run --agent coder`) | nothing — HTTP to a model endpoint |
| Runs the tool loop | the client | the harness |
| Enforces permissions | the client's permission model | the tools the harness chooses to expose |
| Rules reach the agent via | `AGENTS.md` + the client's prompt | `bb rules-prompt`, in the request |
| Per-machine dependency | every role's client, installed and authenticated | a URL and a key |
| Reaches a local model | only if a client supports it | natively — an endpoint is all it is |

**The decision is API-backed**, for three reasons.

**It removes a per-machine dependency.** A machine with only the Antigravity IDE on it cannot
run a CLI-backed loop at all; it runs an API-backed one fine.

**It is what the design already assumed.** The rule source's rationale is that the verifying
agent *"reads no CLAUDE.md at all — it gets an HTTP request with a system prompt."* That
sentence describes an API-backed runner, and `bb rules-prompt` has no other consumer.

**It is why there is no role source.** With no third-party `--agent <name>` to resolve there are
no client role files to generate. The role *concept* survives where it already lives:
`:task/role` in the packet, `--audience` in `bb rules-prompt`.

## What permissions can and cannot do

Owning the tool loop turns permissions from a policy language into a function-call list:

| Role | Tools exposed | Enforced by absence? |
|---|---|---|
| Reviewer | `read_file` | **Yes.** `packet/reviewer-packet` does `(dissoc :repl/port)`, so there is no eval tool at all — read-only is structural |
| Coder | `read_file`, `write_file` (path-checked against `:files/target`), `nrepl_eval` | **Partly.** `(spit …)` from the REPL goes past the path check |
| Tester | `read_file`, `write_file`, `nrepl_eval` | **No** — and the tool list is the wrong place to look |

**Once you hand an agent a REPL, you have handed it the JVM.** Any rule permitting eval cannot
mechanically forbid a subset of eval, and `:no-gates` is exactly such a rule — it allows
*"evaluating individual forms or a single test"* while forbidding the suite. No tool list draws
that line. Filtering evaluated forms is not the answer either: `(eval (read-string …))` and
`(resolve 'clojure.test/run-tests)` defeat it, and a filter you can bypass invites trust nobody
is checking.

So a **`:scope` check stays necessary** — fail when what changed in a worktree falls outside
that role's `:files/target`. It belongs in assembly rather than in `gates/default-gate-seq`,
because it is a precondition on building the gate workspace.

## Provisioning: three worktrees per task

The Tester's independence is a *provisioning* property, not a prompt one. Why it matters that
the Tester not run the suite: test failures are the Coder's implementation talking back.
`packet/tester-packet` strips that implementation from the Tester's context — *"Tests derived
from an implementation only re-assert what the code already does"* — and a red test leaks it
through the back door. An agent staring at a test it just wrote going red will adjust the test
until it passes, deriving from behaviour instead of the contract. Correctness hazard, not
efficiency.

What prevents it is §07 Step 3's isolated workspaces: Coder and Tester run **concurrently** and
*"do not share each other's output."* `:no-gates` is belt-and-braces over that. Until
provisioning exists, nothing structural keeps the Tester independent.

The shape, decided:

- **Three worktrees per task** — coder, tester, and a gate workspace neither agent touched,
  assembled from each role's declared `:files/target`. The join is conflict-free by
  construction: `coder-packet` targets `:files/impl`, `tester-packet` targets `:files/test`.
- **Assembly is a filter, not a merge.** Only declared targets cross into the gate workspace, so
  anything written outside a packet cannot reach the gates — but it must **refuse loudly**
  rather than drop silently, naming the extras. That comparison is the `:scope` check.
- **All three survive an escalation**, provisionally. The interesting escalations are ones where
  Coder and Tester disagreed, and that is invisible in the assembled tree.
- Coder and tester workspaces carry an nREPL each (§03: *"one nREPL per workspace"*); the gate
  workspace needs none, so `:nrepl/port` should be optional on a `Session` schema — the
  precedent is `reviewer-packet`, which dissocs it.
- **Read the port from `.nrepl-port`** rather than choosing one and hoping. That is what the file
  is for, it is what `clj-nrepl-eval --discover-ports` reads, and it removes the
  bind-then-release race. Poll for readiness; never sleep.
- `provision!` should take the packet's file lists, not a bare base commit — see the hole below.

Cost: three worktrees and two JVMs per task in flight. §07 starts the WIP limit at one task.

### What the end-to-end runs found

The first five runs against `sandbox/` went through `ManualRunner` with the roles
played by hand; thirteen dispatched runs have followed. **[`RUNS.md`](RUNS.md) is the
record**; this section keeps only what the manual five changed about the design above.

- **The Tester cannot satisfy `:repl-first` on a greenfield task** — its
  worktree correctly holds no implementation, so it cannot `require` the
  namespace under test. `harness.stub` writes the Blueprint slice into that
  worktree as loadable source: shapes as real `def`s, every signature a body
  that throws.
- **Assembly needs an input for architecture.** A new namespace needs a
  `layers.edn` entry and no dispatched role can supply one — it is in neither
  packet's `:files/target`. `assemble!` takes `{:from _ :files [...]}` and
  reports it separately from agent output.
- **Anything the harness places in a workspace has to be recorded where the
  scope check can see it**, or the check blames the agent. `.nrepl-port`
  taught this; the generated stub reintroduced it; `:harness/wrote` closes it.
- **Scaffold reaches an agent worktree by base commit, never by copy.** A
  `deps.edn` copied in is a scope violation, correctly. Architecture goes to
  the *gate* workspace, which has no scope check.
- **The nREPL must see `test/`, not only `src/`**, or the Tester cannot load
  the test namespace it just wrote and `:repl-first` is unsatisfiable a second
  way. §03 step 2: the REPL starts *ready to work*.
- **A declared thing with no consumer is invisible to every tool.** True of
  `:shapes`, then `:deps-sigs`, then §05's independence rule. Each is now
  something code can fail on — `harness.stub`, `harness.sigs`,
  `harness.profile`.
- **A stub that throws `ex-info` does not stop a test passing against
  nothing.** `(is (thrown? Exception ...))` is satisfied by the stub itself.
  It throws an `AssertionError` now, which `thrown? Exception` cannot catch.

Two things the runs surfaced and nothing has fixed, both recorded rather than
designed around: a Coder that discovers an edge the Blueprint is silent about
has no channel to say so, and nothing tells a rewrite what else depends on the
namespace it is rewriting.

### A worktree is not a packet — closed by the stub

`packet/tester-packet` strips the Coder's implementation from `:files/context`,
but a worktree is a checkout of the whole repo at the base commit — so on a task
that *rewrites* an existing namespace, the previous implementation is sitting
there to be read. The packet-level exclusion holds; the filesystem-level one
does not.

**Closed.** `harness.stub` writes the contract OVER the implementation in the
Tester's worktree, so there is nothing stale to read — exercised for real in run
5. Deleting the implementation instead is the obvious move and is wrong: the
Tester could not then load the namespace at all, which is the problem this
started from. The cheaper option — **logging reads outside `:files/context`** —
is still worth having once the runner owns `read_file`, as evidence rather than
prevention.

## Provenance costs a second call

Verified against the OpenRouter API reference, because it changes the runner's shape.

A chat-completion response carries `id`, `model`, `system_fingerprint` and
`native_finish_reason` — **it does not name the provider that served it.** To learn which
backend answered (including which were tried during fallback), the exact cost, and native token
counts, you query separately:

```
GET https://openrouter.ai/api/v1/generation?id=<generation_id>
```

So **every dispatch is two calls**, and the runner must tolerate a generation record that is not
ready yet — retry briefly, or record the id and backfill. This is the only way to satisfy §10's
*"log which agent, model, and serving provider served every call"*, and the only way to get real
cost rather than tokens times list price. Where there is no record — direct to Anthropic — the
profile can carry `:pricing`, and the report shows usage × list price marked with a `~`.

Routing must be pinned request-side, or the provider you logged yesterday is not the one you get
today:

```clojure
:provider {:only ["deepinfra"] :allow_fallbacks false :quantizations ["fp8"]}
```

Without `allow_fallbacks false` a different backend can mean a different chat template — exactly
§10's failure, where one *"broke tool-call parsing outright and silently corrupted arguments."*

**The symmetry this creates.** For hosted models the generation endpoint *supplies* the serving
facts after the fact; for local models there is no such endpoint, so the profile's `:serving`
block *declares* them. Same column in the run log, two sources.

## Profiles — the runner's config

One seat, plus per dispatched role a family, model, endpoint, tool-call shape and pinned serving
parameters:

```clojure
;; resources/profiles/agy-ide.edn
{:seat :agy-ide                     ; exactly one — where the human works
 :roles {:coder    {:family :google :model "google/gemini-3.8-flash" :shape :openai
                    :endpoint "https://openrouter.ai/api/v1"
                    :key-env "OPENROUTER_API_KEY"
                    :params {:reasoning_effort "medium"
                             :provider {:only ["google-vertex"] :allow_fallbacks false}}}
         :tester   {:family :openai :model "openai/gpt-5.6-sol" :shape :openai
                    :endpoint "https://openrouter.ai/api/v1"
                    :key-env "OPENROUTER_API_KEY"
                    :params {:reasoning_effort "medium"
                             :provider {:only ["openai"] :allow_fallbacks false}}}
         :reviewer {:family :anthropic :model "claude-fable-5-1" :shape :anthropic
                    :endpoint "https://api.anthropic.com"
                    :key-env "ANTHROPIC_API_KEY"
                    :params {:output_config {:effort "low"} :max_tokens 16000}
                    ;; optional: list prices for an endpoint that reports no cost,
                    ;; USD per million tokens, with where and when they were read
                    :pricing {:per-mtok {:in 10 :out 50 :cache-write-5m 12.5
                                         :cache-write-1h 20 :cache-read 0.25}
                              :source "https://platform.claude.com/docs/en/about-claude/pricing"
                              :as-of "2026-09-14"}}}}
```

The seat is a Google client, so Gemini writes the code and Anthropic verifies
it. Getting that backwards — `claude-sonnet-5` as the Coder under `:seat
:agy-ide` — is what the first committed version of this file did.

`:shape` selects one of two tool-call adapters — OpenRouter normalises frontier models to the
OpenAI shape, and MTPLX and oMLX serve both shapes locally, so two adapters reach every model.
`:params` is request parameters; `:serving` is how a *local* model is served. Different things,
different keys.

**Built** — `harness.profile`, `resources/profiles/`, `bb profile`, and since
D3 its consumer too: `harness.runner/api-runner` dispatches each role to the
model its profile names. Every dispatched run, D3 to D15, has gone through it; see
[`RUNS.md`](RUNS.md) part 2.

**Two worked examples ship, one per seat**, and they are near mirror images:

| | seat `claude` | seat `agy-ide` |
|---|---|---|
| coder | `anthropic` · `claude-fable-5-1` (effort low) · direct · `:anthropic` | `google` · `gemini-3.8-flash` · OpenRouter · `:openai` |
| tester | `openai` · `gpt-5.6-sol` · OpenRouter · `:openai` | `openai` · `gpt-5.6-sol` · OpenRouter · `:openai` |
| reviewer | `google` · `gemini-3.8-flash` · OpenRouter · `:openai` | `anthropic` · `claude-fable-5-1` (effort low) · direct · `:anthropic` |

Anthropic writes and Google reviews, or the reverse. Between them both `:shape`
adapters have a worked example, which is why there are two rather than one —
and a single committed profile reads as *your* configuration, which is exactly
how the first version was misread.

**The Coder's family follows the seat, by convention and not by rule.** You are
already being helped by that family interactively, so letting it write the code
keeps one story and pushes the difference onto the VERIFIERS, where §05 wants
it. It is deliberately not checked: OpenCode 2 and Pi are bring-your-own-model
clients with no inherent family, so the relation is not expressible for half
the seats this kit supports.

This is where §05's independence rule stops being prose. *Verifier ≠ Coder
family* is recorded in method §05 and decision log P0-3 and had never been
anything a tool could fail on; `violations` fails on it, naming the role. It
is the same gap `:shapes` and `:deps-sigs` had, in the rule the method rests
on — the Tester and the Reviewer exist to disagree with the Coder, and two
roles from its family share its blind spots.

What is NOT checked is as deliberate. Two verifiers may share each other's
family: what §05 and P0-3 record is *verifier ≠ Coder*, and putting a stricter
rule in code than the decision log supports is how a check starts describing
its author instead of the method.

The checks run in three tiers, because they answer differently:

- **Structure** — shape, and independence. Pure data, offline, the same answer
  on every machine. Safe in `bb gates`, and the shipped profile is validated
  by the suite so the worked example cannot rot.
- **Environment** (`bb profile`) — is the seat installed, is each named key
  variable set. Machine-dependent: a gate that ran this would fail for a
  contributor who uses a different seat.
- **Reachability** (`bb profile --probe`) — does each endpoint answer. Network
  I/O; `bb gates` has to pass on a plane. Any HTTP response counts, 401
  included — whether the host is serving and whether you are authorised are
  different questions, and `:key-env` is the second one.

`:key-env` NAMES an environment variable and never holds a key. The profile is
committed; a field that could hold a secret would eventually be handed one,
and the violation reports the variable's name, never its value.

## What the runner needed — all of it built

Written up here before any of it existed; every line is now `harness.adapter`,
`harness.provenance`, `harness.tools`, `harness.agent` and
`harness.runner/api-runner`. Kept because the reasoning is what a reader
adapting this needs, and because what the runs then taught is in
[`RUNS.md`](RUNS.md) rather than here.

- **A tool loop.** Upstream's was *"a 226-line tool-call loop"*, deliberately not extracted.
  `read_file`, `write_file` (path-checked), `nrepl_eval` shelling `clj-nrepl-eval`. Per §10
  lesson 8, a tool-call failure returns to the model as data and never crashes the process.
  D3 found the docstring claiming this was *not* a shell to be false: a REPL is
  arbitrary code execution, and the containment is the worktree.
- **Two request adapters**, `:openai` and `:anthropic`.
- **The system prompt from `bb rules-prompt --audience <role>`** — that task's only consumer.
- **Derive `:files` from git**, not from the model's report. `runner-check`'s `:files-exist`
  already encodes the distrust: *"a human's word, not a filesystem fact."*
- **Do not resume sessions.** §10: a resumed retry costs several times a fresh one.
- **Everything provider-specific into `:runner/meta`**, which is why `AgentResult` is closed.

## Out of scope

The orchestration loop, triage and the run log were cut deliberately in
`harness-seed/README.md`, and §12 argues against building any of them speculatively. Nothing
above requires them.
