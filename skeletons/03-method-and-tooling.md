# <Project> — Method & Tooling (the Foundation)

**Status:** <IN PROGRESS | READY — human sign-off YYYY-MM-DD>
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>
**Runs under:** every delivery stage (`00-overview.md` §4)
**Decisions:** P0-n in `04-decision-log.md`
**Method reference:** `method.md`

> This document is `method.md` **instantiated for this project**. The method
> doc explains *why*; this one records *what we picked*. Keep it short — every paragraph
> here that merely restates the method is a paragraph that will drift out of date.

---

## 0. Why a Foundation

The workflow, the scaffold it operates in, and the quality gates it runs through are **not
a delivery stage** — they are the method and the toolchain the stages are built *with*.
Configured once, before Stage 1 dispatches, then running continuously underneath every stage.

**Core principles:** REPL-first · cheap gates before expensive ones · independent
verification (the author of the tests is never the author of the code) · failure escalates
to the role that owns it · humans gate exactly two moments · every loop has a circuit breaker.

## 1. Roles and model assignment (P0-n)

> The **family assignment is the binding contract**; the model column is a dated selection.
> Re-selecting is a new log entry, not an edit. Any agent verifying the Coder's output must
> be a different model *family* — not merely a different model from the same vendor.

| Role | Family / client | Constraint | Model (as of <date>) | Notes |
|---|---|---|---|---|
| **Coder** | <family A> | — | | |
| **Tester** | | **≠ A** | | |
| **Reviewer** | | **≠ A** | | |
| **Architect** | | — | | Does not verify Coder output |
| **Orchestrator** | software; model for triage only | — | | |
| **DevOps** | | — | | |

**Dedicated Tester: <yes / no>** — <the correctness bar or generative-property argument
that justifies the extra call, or the reason it isn't justified here>.

## 2. Context passing — the task packet (P0-n)

| Role | Receives |
|---|---|
| Architect | The requirement + requirements/architecture/stage docs |
| Coder | Its Blueprint slice + read-only dependency files + a REPL connection |
| Tester | The **same slice** — **not** the Coder's implementation — + a REPL for authoring |
| Reviewer | The diff + the slice + the green gate report (read-only) |
| Orchestrator | Full Blueprint + task DAG + accumulated error context |

> Packet shape: `method.md` §06. **Key invariant:** the Tester reads the
> *contract*, never the *code* — enforce it in the packet assembler, not by author discipline.

**Tool & REPL access per role** (the eval bridge itself is §7.2):

| Role | Access |
|---|---|
| Coder | nREPL read/eval/write via `clj-nrepl-eval`; persists files itself |
| Tester | nREPL eval for **authoring only** (prototype a generator, confirm a property's shape); persists test namespaces. **Never runs the full suite** — that is the gate's job (§4) |
| Reviewer | Read-only: diff + files. No eval, no write |
| Architect | No REPL — produces the Blueprint artifact |
| Orchestrator | Dispatch + triage. No direct code or eval |
| Gates | Run in the worktree's already-warm nREPL where possible (§3) |
| **Interactive programmer** *(off the loop)* | Full read/eval/write, by hand. Harness development, modelling sessions, and debugging the worktree an escalation left behind. No packet, no retry cap — inherits the rule source (§7.5) and **does** run the gates itself, since nothing else is gating it |

## 3. Workspace isolation (P0-n)

**One nREPL server per git worktree** — not one server with several nREPL *sessions*.
Sessions share a JVM, so two supposedly isolated agents can redefine the same var.
Separate worktrees also keep the Coder's and Tester's file edits from colliding.

Per task the orchestrator: creates the worktree → starts its nREPL (`clj -M:nrepl`) →
records the port → hands that port to the agent in its packet (§2). An agent can also find
one itself with `clj-nrepl-eval --discover-ports` (§7.2).

Gates run in that worktree's **already-warm** REPL — no cold JVM per attempt, a cost a
retrying loop otherwise pays over and over.

This is also what makes auto-approving an agent's edits safe: it never touches the main
checkout. Any agent that writes through an orchestrator rather than directly must have its
writes confined to the worktree by that orchestrator.

## 4. Quality gates (P0-n)

| Order | Gate | Tool | Catches |
|---|---|---|---|
| **0** | Delimiter repair + format-on-write | `clj-paren-repair-claude-hook --cljfmt` / `clj-paren-repair` (§7.3) | mismatched parens/brackets; style drift — **zero tokens**, runs outside the LLM |
| 1 | Format | cljfmt | |
| 2 | Lint (**fail on warning**) | clj-kondo | |
| 3 | Tests (**independently authored**) | eftest + cloverage | |
| 4 | Boundaries | clj-depend | |
| 5 | Review | frontier model, ≠ Coder family | |

```clojure
;; bb.edn :tasks
{gates {:doc  "Quality gates, cheap first"
        :task (do (run 'fmt-check) (run 'lint) (run 'test) (run 'deps-check))}}
```

## 5. Retry cap and triage (P0-n)

Cap: **N = <n>** attempts per task. On failure, route to the owning role — implementation
bug → Coder · design flaw → Architect · defective test → Tester. On hitting the cap,
escalate to a human and **hand off whatever was produced** — the gates judge it; the
workspace is left in place for inspection.

## 6. Property targets

> What the Tester generates against. Fill in the ones your domain actually has.

- **Round-trip:** <parse → canonical → store → read> preserves <fields>.
- **Identity & disjointness:** `diff(v, v)` is empty; added and removed sets are disjoint.
- **Transitivity / closure:** <…>
- **Cross-surface consistency:** <two paths to the same answer agree>.

## 7. Scaffold & tooling

### 7.1 Generate the scaffold — once, here

```bash
neil new io.github.abogoyavlensky/clojure-stack-lite <name> :db <sqlite|postgres> :auth <bool>
cd <name> && mise trust && mise install
git init && git add -A && git commit -m "Scaffold: untouched clojure-stack-lite"
```

Generation, boot and gate wiring happen **here, once**. Project-specific adaptation —
stripping the example domain, defining protocols, real boundary rules, the datastore swap —
is **Stage 1's first task**.

**Boot check (readiness #2):** `bb clj-repl` (brings up `docker compose` + `clj -A:dev:test`),
then `(reset)` → <http://localhost:8000>.

### 7.2 Make the REPL reachable by agents

The scaffold's `dev/user.clj` gives a *human* a REPL. Agents need one they can reach **over
the shell**, uniformly, whatever model family they run on — that is what makes the
independence rule in §1 affordable rather than a per-client integration project.

Add an `:nrepl` alias to `deps.edn` so a headless server can be started per workspace:

```clojure
;; deps.edn :aliases
{:nrepl {:extra-deps {nrepl/nrepl {:mvn/version "<version>"}}
         :main-opts  ["-m" "nrepl.cmdline"]}}
```

Then install **clojure-mcp-light** — CLI tools, *not* an MCP server (needs Babashka + bbin,
both already in the stack):

```bash
# 1. CLI nREPL eval bridge — uniform REPL access across model families
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag <tag> \
  --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'

# 2. zero-token delimiter-repair hook (hook-capable clients, e.g. Claude Code)
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag <tag>

# 3. on-demand delimiter repair (every other agent, any shell)
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag <tag> \
  --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
```

The surface agents are told about in their packet (§2):

```bash
clj-nrepl-eval --discover-ports              # find nREPL servers under this directory
clj-nrepl-eval -p <port> "<clojure-code>"    # eval — prefer a heredoc on stdin to dodge shell escaping
clj-nrepl-eval -p <port> --timeout 5000 "…"  # default 120000 ms
```

Sessions are **persistent per host:port** — vars, namespaces and loaded libs survive
between invocations until the server restarts. That is what makes the Coder's inner loop
cheap, and it is also why §3's isolation rule is load-bearing: persistence is per *server*,
so two agents pointed at one server share state whether you meant them to or not.

> **Tell agents to `:reload` when requiring a namespace they have edited.** A persistent
> session will happily keep serving the old definition.

### 7.3 Delimiter repair — gate 0, and why it isn't optional

LLMs introduce mismatched parens and brackets when editing Clojure. Left unhandled that
burns the Coder's capped retries (§5) on something no model should be paying to fix.
Which mechanism applies follows the model-family split in §1:

- **Hook-capable client (Claude Code)** → `clj-paren-repair-claude-hook` repairs delimiters
  **before the write reaches disk, at zero tokens**, preserving the native diff UI. With
  `--cljfmt` the file also lands formatted, which collapses gate 1 to a near-no-op:

  ```json
  { "hooks": {
      "PreToolUse":  [{ "matcher": "Write|Edit",
                        "hooks": [{ "type": "command",
                                    "command": "clj-paren-repair-claude-hook --cljfmt" }] }],
      "PostToolUse": [{ "matcher": "Edit|Write",
                        "hooks": [{ "type": "command",
                                    "command": "clj-paren-repair-claude-hook --cljfmt" }] }] }
  }
  ```

- **Every other agent** → the on-demand `clj-paren-repair` command. Do **not** depend on the
  agent remembering to call it: have the orchestrator run it over the files the agent
  produced, before the gates. A pre-gate that only fires when the model chooses to fire it
  is not a pre-gate.

**Keep both, even for hook-capable clients.** Hooks cover Write/Edit; an agent can still
edit through the shell (`sed`, `awk`) and bypass them entirely.

### 7.4 Check the toolchain, do not assume it

A missing small binary does not fail loudly — a loop with no `clj-paren-repair`
keeps running and quietly spends the Coder's retry budget on parens. So the
toolchain is **probed and reported**, and that report is a readiness artifact.

`harness-seed/`'s `bb doctor` (see `method.md` §12) reports every tool
above — version, whether it is reachable on PATH, and what it is for — and exits
non-zero when a required one is not usable. Run it first in your composed gate
task: it is the cheapest check there is.

```bash
bb doctor         # the table
bb doctor --edn   # a pins map, for the decision log
```

> One trap worth inheriting: a `bbin`-installed tool can be **installed but not
> on PATH** — `bbin` itself is reachable while its shim directory is not, so
> `bbin ls` lists the tool and a naive check calls it fine. Verify reachability,
> not just installation.

### 7.5 The agent-rule source (P0-n)

> Conventions are true of every task, so they do not belong in a packet — and the loop's
> measured lesson is that up-front rules beat retry feedback. Keep them in **one** rule
> source — the authoritative set of rule records — and render it outward. Everything else
> that holds rules is a *rendering* of it. Working implementation: `harness-seed/`
> (`resources/agent-rules.edn` + `harness.rules`).

- Rule source: `<path>/agent-rules.edn` — records of `:id :group :audience :title :text`
- Rendered into: <which agents' system prompts>, filtered by `:audience`
- Mirrored into: `<repo>/CLAUDE.md`, between `<!-- agent-rules:begin/end -->` markers
- Regenerate with `bb rules-sync`; **`bb rules-check` runs inside `bb gates`** and fails on drift
- Edit rules in the rule source only — never in a prompt string, never by hand inside the
  mirror's marker block
- **Hand-written content goes outside the markers** and survives sync — orientation, the task
  surface, pointers. **Rules never do:** one written out there reaches only clients that read
  the file, silently skipping the verifier (`method.md` §06)
- Known limitation: `:text` renders as one markdown bullet — inline formatting only, no
  fenced blocks or sub-bullets. Extend the renderer rather than working around it

**Rule layers, and which wins.** Rules merge silently from three places, so write the
precedence down — nothing else enforces it:

| Layer | Holds | Reaches |
|---|---|---|
| `~/.claude/CLAUDE.md` (personal) | One person's taste, across all their projects | Only clients that read it |
| `<repo>/CLAUDE.md` (project) | This project's rules, in a generated block — the rest of the file is hand-written and survives sync | Only clients that read it |
| The rule source, in the system prompt | Anything a gate enforces | **Every model family** |

> Two facts decide the split. A `CLAUDE.md` is one vendor's client convention, and §1's
> independence rule puts the verifier on a **different family** — which reads no such file.
> And because the layers merge, a personal preference can contradict a project
> non-negotiable silently. <Check for this: a global "run the tests after changing a
> namespace" directly contradicts a project "never run the gates yourself".> Anything a
> gate enforces therefore belongs in the rule source, and it should carry a
> `:precedence` rule stating that it outranks personal and global instructions.

### 7.6 Pins and verified notes

- **Pin tool versions in `.mise.toml`** — the scaffold already ships one. `bb doctor`
  reads the `[tools]` table and verifies the **major** version of each pinned tool, so the
  pin lives in one place and is checked rather than restated:

  ```toml
  [tools]
  java = "temurin-21.0.2+13.0.LTS"   # <your JDK constraint, and why>
  clojure = "<version>"
  babashka = "<version>"
  ```

  <Name your JDK constraint here if you have one. XTDB v2, for example, needs 21 exactly —
  it fails at class-load on newer JDKs.> Watch for `JAVA_HOME` disagreeing with the `java`
  on PATH; several version managers on one machine routinely give different answers, and
  `bb doctor` flags the divergence.
- Paste `bb doctor --edn` output here at Foundation sign-off, and again whenever a tool moves
- Verified API notes for young dependencies: `docs/api-notes/<lib>.md`
- For any locally-served model: sampling profile **and chat template** version-controlled at `<path>`

## 8. Orchestration

<Hand-run for now / harness at `<repo>`.> See `method.md` §12 before building
anything here. If built: `AgentRunner` seam, `ManualRunner` first, headless runners
incrementally, append-only run log recording task · attempts · status · cost · provider ·
**the failing gate's actual output**.

## 9. Readiness criteria

The Foundation is **ready** (not "done") when:

1. **`bb doctor` reports green** — every required tool installed, reachable, and
   its version recorded in §7.5. Not asserted: run.
2. The §1–§5 protocols are concrete and the method decisions are closed.
3. The scaffold boots from a clean checkout: REPL starts, dev server serves, assets build.
4. All gates run green on the **untouched** scaffold.
5. The composed gate task and a dispatch mechanism exist.
6. **The whole loop has run end to end on one deliberately trivial task**, observed.

Then Stage 1 dispatches against it.
