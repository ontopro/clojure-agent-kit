# <Project> — Method & Tooling (the Foundation)

**Status:** <IN PROGRESS | READY — human sign-off YYYY-MM-DD>
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>
**Runs under:** every delivery stage (`00-overview.md` §4)
**Decisions:** P0-n in `04-decision-log.md`
**Method reference:** `method.md`

> This document is `method.md` **instantiated for this project**. The method doc explains
> *why*; this one records *what we picked*. It is in three parts. **Given** (§0–§10) is what
> adopting the KIT fixed: the roles, the packet, the worktrees, the gates, triage, the tooling -
> stated as references to the KIT, not as questions, and not edited; changing a given part
> leaves the certified pair. **Chosen** (§11–§15) is what this project decides once, here, and
> records in the decision log - the only fill-ins. **Theirs** (§16–§18) is the domain's: its
> property targets, its stage-end checks, its readiness record. Keep it short — every paragraph
> here that merely restates the method is a paragraph that will drift out of date. Delete this note.

---

# Part 1 — Given: fixed by adopting the KIT

## 0. Why a Foundation

The workflow, the scaffold it operates in, and the quality gates it runs through are **not
a delivery stage** — they are the method and the toolchain the stages are built *with*.
Configured once, before Stage 1 dispatches, then running continuously underneath every stage.

**Core principles:** REPL-first · cheap gates before expensive ones · independent
verification (the author of the tests is never the author of the code) · failure escalates
to the role that owns it · humans gate exactly two moments · every loop has a circuit breaker.

## 1. The six roles, and the independence rule

`method.md` §05. Six roles are dispatched with packets and held to gates; a seventh works beside
the loop. The constraints are the contract and are fixed; which model fills each seat is §12.

| Role | Constraint | Dispatched? | Access |
|---|---|---|---|
| **Coder** | family A | yes, every task | Full read/eval/write, in its own worktree |
| **Tester** | **≠ A** | yes, every task | Eval for authoring only; never runs the full suite |
| **Reviewer** | **≠ A** | yes, on green | Read-only: a diff, the slice, the gate report |
| **Spec reviewer** | ≠ the Architect's family | yes, before every dispatch (`start` runs it) | Read-only: the spec, its context, the rules; no tools |
| **Orchestrator** | none — it verifies nothing | software; a model for triage only, on a red gate or a note | Dispatch and triage; no code, no eval |
| **Architect** | none | no — the seat, at the workspace root | Produces the Blueprint; no REPL |
| **DevOps** | none | no — the seat | Whatever a task needs, scoped narrowly |
| **Interactive programmer** *(off the loop)* | none | no | Full read/eval/write, by hand: harness work, modelling, the worktree an escalation left behind. No packet, no retry cap; inherits the rule source and **runs the gates itself**, since nothing else is gating it |

Any agent verifying the Coder's output is a different model *family* — not merely a different
model from the same vendor. `bb profile` checks the profile for it and refuses one that breaks it.

## 2. The task packet

`method.md` §06; the schema is `harness/src/harness/contract/shapes.clj`'s `example-packet`,
and the assembler (`harness.contract.packet`) cuts each role's from the task spec:

| Role | Receives |
|---|---|
| Architect | The requirement + requirements/architecture/stage docs |
| Coder | Its Blueprint slice + read-only dependency files + a REPL connection |
| Tester | The **same slice** — **not** the Coder's implementation — + a REPL for authoring |
| Reviewer | The diff + the slice + the green gate report (read-only) |
| Orchestrator | Full Blueprint + task DAG + accumulated error context |

**Key invariant:** the Tester reads the *contract*, never the *code* — enforced in the packet
assembler, not by author discipline. Upstream namespaces arrive as `:deps-sigs`, call signatures
only, and a call into a context namespace the signatures did not grant fails the run before any
gate.

## 3. Workspace isolation

**Three worktrees per task, each with its own nREPL server** — not one server with several
nREPL *sessions*, which share a JVM, so two supposedly isolated agents can redefine the same var.
The harness provisions them (`harness.loop.provision`): per task it creates the worktrees under
the workspace's `work/`, starts one nREPL each (`clojure -Srepro -M:test:nrepl`, the command the
plan's `loop.edn` names), records the ports and hands each role its own in the packet. Only a
role's declared `:files/target` crosses into the gate worktree; anything else is refused by name.

Gates run in that worktree's **already-warm** REPL — no cold JVM per attempt. And this is what
makes auto-approving an agent's edits safe: it never touches the main checkout.

## 4. The gates, in order

`method.md` §09. Gate 0 is run by the harness over what a role wrote, before the sequence; the
sequence is `loop.edn`'s `:gates`, as `bb init` wrote it for the generated application - three
of the template's own tasks and the KIT's boundary gate; gate 5 is the Reviewer, dispatched only
on green.

| Order | Gate | Command | Catches |
|---|---|---|---|
| **0** | Delimiter repair | `clj-paren-repair`, run by the harness (§8) | mismatched delimiters — **zero tokens**, outside the LLM |
| 1 | `:fmt` | `bb fmt-check` (cljfmt) | style drift |
| 2 | `:lint` | `bb lint` (clj-kondo, **fail on warning**) | style and correctness smells |
| 3 | `:test` | `bb test` (eftest + cloverage; **independently authored** tests) | behaviour against the contract |
| 4 | `:deps` | `bb --config <kit>/harness/bb.edn boundary` | a layer requiring what `layers.edn` forbids, or a namespace it does not declare |
| 5 | Review | the Reviewer, ≠ Coder family | what no machine gate catches |

**The gate keys are a contract.** Triage dispatches on them and the run log is read by key
months later; a project that changes the sequence keeps the keys.

## 5. Triage, the retry cap, and the stops

`method.md` §07. On a red gate or a note, the Orchestrator's triage call reads the gate's own
output and routes to the owning role — `coder` (an implementation bug), `tester` (a defective
test), `architect` (a design flaw or an ambiguous contract), `human`, or `tooling` (the machine is
at fault, not the run: a merged file no role owns, the build, the harness) — with a mechanical
proposal as its fallback. The decision is written **before** the retry it justifies. A run has a
retry cap per role (the number is §13); on hitting it the loop stops, hands off whatever was
produced, and leaves the worktrees in place for inspection. The spec review runs before every
dispatch and stops for the Architect when it finds something (§13 caps how often).

**Every stop has an owner, and the loop says which.** A stop whose answer is an amendment to the
contract is the Architect's: the seat session amends or leaves it and carries on. Everything else
is the person's: the cap, a merge (the loop never merges; an approval stops it `:awaiting-merge`),
a `human` or `tooling` route, a dead REPL, a provider refusing for credit.

## 6. The workspace — once, here

```bash
cd <the folder this project lives in> && git clone https://github.com/ontopro/clojure-agent-kit
cd clojure-agent-kit && bb doctor      # follow it, run it again, until both verdicts say yes
bb health                              # the KIT, this machine, the pinned template: certified together
bb init <name>                         # <name>-app (generated, committed untouched), <name>-plan, work/
cd ../<name>-app && bb serve           # answers on localhost:8000
```

Three sibling repositories in a plain folder - the KIT, the application, the plan - and
`workspace.edn` naming which is which. Nothing of this project is written into the KIT's clone:
the rules overlay, the profile, the loop defaults and the run records are all in `<name>-plan/`,
and `git pull` in the clone is the upgrade. Project-specific adaptation — stripping the example
domain, defining protocols, the datastore swap — is **Stage 1's first task**; the boundary gate is
already live with the template's own graph in `<name>-app/layers.edn`.

**Boot check:** `bb serve` in the application answers on its port - `bb health` did it once; do it
yourself after any change to the system's wiring.

## 7. The REPL, reachable by agents

The application's `dev/user.clj` gives a *human* a REPL. Agents need one they can reach **over
the shell**, uniformly, whatever model family they run on — that is what makes the
independence rule in §1 affordable rather than a per-client integration project.

The generated `deps.edn` has the headless `:nrepl` alias (port 0, written to `.nrepl-port`), and
the harness starts one per worktree. What the machine needs is **clojure-mcp-light** — CLI tools,
*not* an MCP server — which `bb doctor` checks by running each one and names the install command
when one is missing. The surface agents are told about in their packet:

```bash
clj-nrepl-eval --discover-ports              # find nREPL servers under this directory
clj-nrepl-eval -p PORT "(+ 1 2)"             # eval — prefer a heredoc on stdin to dodge shell escaping
clj-nrepl-eval -p PORT --timeout 5000 "…"    # default 120000 ms
```

Sessions are **persistent per host:port** — vars, namespaces and loaded libs survive between
invocations until the server restarts. That is what makes the Coder's inner loop cheap, and it
is also why §3's isolation rule is load-bearing: persistence is per *server*, so two agents
pointed at one server share state whether you meant them to or not. The rule source tells every
agent to `:reload` a namespace it has edited; a persistent session will happily keep serving the
old definition.

## 8. Delimiter repair — gate 0, and why it isn't optional

LLMs introduce mismatched parens and brackets when editing Clojure. Left unhandled that burns
the Coder's capped retries on something no model should be paying to fix. There is one mechanism
and one accelerant.

**The mechanism, everywhere:** the harness runs `clj-paren-repair` over the files a role
produced, before the gates. It does not depend on the agent remembering to call it — a pre-gate
that only fires when the model chooses to fire it is not a pre-gate. For work done *beside* the
loop, where no orchestrator is watching, `bb repair` is the same pass driven off `git status`
instead of a packet.

**The accelerant, per client:** a write-time hook repairs delimiters **before the write reaches
disk, at zero tokens**, preserving the native diff UI, and with `--cljfmt` the file also lands
formatted — which collapses gate 1 to a near-no-op. Every current client can do this under its
own name; which one this project wired is §14. Keep the mechanism even where you have the
accelerant: a hook covers the client's own write tools, and an agent can still edit through the
shell (`sed`, `awk`) and bypass it.

## 9. The toolchain, probed and not assumed

A missing small binary does not fail loudly — a loop with no `clj-paren-repair` keeps running
and quietly spends the Coder's retry budget on parens. So the toolchain is **probed and
reported**: `bb doctor` reports every tool — version, whether it is reachable on PATH, what it
is for — and exits non-zero when a required one is not usable; `bb doctor --edn` is the pins map
the readiness record (§18) pastes. One trap it was built for: a `bbin`-installed tool can be
**installed but not on PATH**, so it checks reachability, not installation.

## 10. The rule source — where a convention lives

Conventions are true of every task, so they do not belong in a packet — and the loop's measured
lesson is that up-front rules beat retry feedback. They live in **one** rule source — the KIT's
`harness/resources/agent-rules.edn`, records of `:id :group :audience :title :text` — and render
outward: into each dispatched role's system prompt, filtered by `:audience`, and into the
application's `AGENTS.md` between `<!-- agent-rules:begin/end -->` markers (`CLAUDE.md` there is
a stub that imports it - one marker block, one drift target). `bb rules-sync` regenerates the
mirror; **`bb rules-check` runs inside `bb gates`** and fails on drift.

This project never edits the source. It fills the source's placeholders and adds rules of its own
in `<name>-plan/rules.edn`, the overlay (§11), which every rendering reads merged over the source;
an overlay entry that would change one of the KIT's own rules is refused by name. Hand-written
content in `AGENTS.md` goes outside the markers and survives sync — orientation, the task surface,
pointers. **Rules never do:** one written out there reaches only clients that read the file,
silently skipping the verifier. A rule's `:text` renders as one markdown bullet: inline
formatting only.

**Rule layers, and which wins.** Rules merge silently from three places, so the precedence is
written down — the rule source's own first rule says it outranks the others:

| Layer | Holds | Reaches |
|---|---|---|
| Personal rules file (`~/.claude/CLAUDE.md`, `~/.pi/agent/AGENTS.md`, …) | One person's taste, across all their projects | Only clients that read it |
| `<name>-app/AGENTS.md` | This project's rules, in a generated block — the rest of the file is hand-written and survives sync | Only clients that read it |
| The rule source, in the system prompt | Anything a gate enforces | **Every model family** |

> A rules file reaches only clients that read files, and §1's independence rule puts the
> verifier on a **different family** — which reads none at all. And because the layers merge, a
> personal preference can contradict a project non-negotiable silently: a global "run the tests
> after changing a namespace" directly contradicts the project's "never run the gates yourself".
> Anything a gate enforces therefore belongs in the rule source, where the precedence rule covers it.

---

# Part 2 — Chosen: decided once, in Foundation, recorded in the log (P0-n)

## 11. The rules overlay — `<name>-plan/rules.edn`

> `bb init` wrote it with the rule source's three placeholders, text as shipped; `start` lists any
> still standing, and an unfilled one reaches every role as literal text. Fill them **before the
> first dispatch**, then `bb rules-sync`. Record here what each says, or where; the text itself
> lives in the overlay.

- **`:layer-boundaries`** — the layers and their allowed direction: the template's six
  (`02-architecture.md` §2) and this project's own (§4 there). <One sentence, or "as `layers.edn`
  declares".>
- **`:shapes-are-the-contract`** — the validation library and the error a boundary failure
  throws. <Malli as shipped; `ex-info` carrying which type, named here.>
- **`:data-conventions`** — what this project's recurring data IS, for every role: what a tree
  or document is and how it is walked, what collection each recurring argument is, what the
  targets' recurring words mean, where input comes from, what each seam guarantees to everything
  past it (`02-architecture.md` §5, last column), who owns presentation and which design tokens
  the views draw on, and whether the template's own test suite is the pattern. <Filled in the
  overlay; summarise its headings here.>
- **Project rules of its own** — <none yet, or one line per rule, by its id>. A rule of a new id is
  added whole (`:group`, `:audience`, `:title`, `:text`).

## 12. Models per role — `<name>-plan/profile.edn`

> The **family assignment is the binding contract**; the model column is a dated selection.
> Re-selecting is a new log entry, not an edit, and it is measured, not preferred: `bb bake-off`
> in the KIT's `harness/` reads what this plan already holds with each candidate once, a judge
> maps where they agree, and you mark what is real. `bb init` copied the shipped example for the seat
> (`--seat`, default `claude`); `bb profile` in the KIT's `harness/` checks it - the shape, the
> verifiers' independence, the seat, the key variables - and `bb balance` reads the credit
> behind it. The seat is where the human works, with no packet. The shipped `claude` example
> serves every role through OpenRouter, the Anthropic ones pinned to the `anthropic` provider
> with prompt caching asked for on the Coder alone: one key holds the balance, every cost is
> the endpoint's word, and a 402 is one stop. A role direct to Anthropic instead needs
> `:pricing` (its cost is computed and marked `~`) and its spend is counted from the records;
> the example's comments carry the reasons, and the choice is a row here.

**Seat:** <claude | agy-ide | opencode | opencode2 | pi> — <the client, and its version>

| Role | Family | Model (as of <date>) | Served by | Notes |
|---|---|---|---|---|
| Coder | <family A> | | <OpenRouter / direct> | |
| Tester | <≠ A> | | | |
| Reviewer | <≠ A> | | | |
| Spec reviewer | <≠ the Architect's> | | | |
| Orchestrator (triage) | <any> | | | may share family A — it verifies nothing — but say so |
| Architect | the seat | | — | |

**Dedicated Tester: <yes / no>** — <the correctness bar or generative-property argument that
justifies the extra call, or the reason it isn't justified here>.

## 13. The numbers

> Each is a key the loop reads or a line a person holds to, with a default; the value is this
> project's and its reason is one sentence.

| What | Where | Default | This project | Why |
|---|---|---|---|---|
| Retry cap, attempts per role per task | `:gates {:retry-cap n}` in a task spec (`packet/default-gates`) | 3 | <n> | <…> |
| Rounds per dispatch - completions before the harness stops a role | `:max-rounds n` in the role's block in `profile.edn` | the harness's 24 | <Coder n / Tester n> | <…; the Tester writes a test per target and takes more> |
| Spec reviews that found something before a person reads them | `:spec-review/max` in `loop.edn` | 2 | <n> | <…> |
| Money cap for a stage, and the floor a run must not start below | held by the person; `bb balance` before and after every run | — | <$cap / $floor> | <…> |

## 14. The write-time hook, and the stage-end driver

- **The hook (§8's accelerant):** <the client and the hook wired — Claude Code's
  `PreToolUse`/`PostToolUse` on `Write|Edit` running `clj-paren-repair-claude-hook --cljfmt`;
  Antigravity's `.agents/hooks.json`; OpenCode's plugin hook; Pi's extension — or "the mechanism
  alone">. Claude Code's, as the worked example:

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

- **The stage-end driver (§17):** <the KIT's browser pack, `bb --config <kit>/tools/browser/bb.edn
  check`, run from the application's folder: Etaoin under Babashka driving a headless Firefox
  through geckodriver - screenshots with measurements, an axe-core scan, and the serve-check-stop
  skeleton (`--serve`, `--health`, `--base` name this project's server); the project's own
  interaction checks are scripts in the project built on the pack's `driver.clj`; or yours>.
  geckodriver and Firefox are hand steps, recorded in the stage document that first needs them;
  `bb doctor` has the row, and `bb health` starts a Firefox once because `geckodriver --version`
  passes on a machine where none can start (the permission on macOS is the terminal
  application's; a shell under a daemon is refused silently).

## 15. Pins and verified notes

- **Tool versions are pinned in `<name>-app/.mise.toml`**, which the template ships dated.
  `bb doctor` reads its `[tools]` table as floors and the JDK's major as the one pin, so a pin
  lives in one place and is checked rather than restated. <Name your JDK constraint here if you
  have one beyond the KIT's 21: XTDB v2, for example, documents a minimum of 21, and its early
  releases failed at class-load on newer JDKs.> Watch for `JAVA_HOME` disagreeing with the `java`
  on PATH; `bb doctor` flags the divergence.
- **Verified API notes for young dependencies:** `<name>-plan/docs/api-notes/<lib>.md` — the API
  actually smoke-tested at the pinned version. Models hallucinate confidently about new libraries.
- **For any locally-served model:** sampling profile **and chat template** version-controlled at `<path>`.

---

# Part 3 — Theirs: the domain

## 16. Property targets

> What the Tester generates against, and what every other role is handed too — the Coder has
> to satisfy them and the Reviewer judges against them. Fill in the ones your domain actually has.

- **Round-trip:** <parse → canonical → store → read> preserves <fields>.
- **Identity & disjointness:** `diff(v, v)` is empty; added and removed sets are disjoint.
- **Transitivity / closure:** <…>
- **Cross-surface consistency:** <two paths to the same answer agree>.

## 17. Stage-end checks — outside the loop, recorded like the gates

> What a stage's end checks that no gate can (`method.md` §04): the person looks at the built
> thing, and anything the tests verify only as an HTTP contract is exercised in a real browser.
> Neither is a gate or a test in the suite - they start a server and a browser, which the
> Testers' rule keeps out - so they are scripts, run at the stage's end, their output kept
> beside the screenshots. Fill in what yours are; the driver is §14. Delete this note.

- **Screenshots** at the widths that matter (`<1440 and 390>`), from a real viewport: a
  headless browser driven by a script, not a window whose floor is wider than the narrow width.
  Kept under `<screenshots/stage-N/>` with a measurement file (`scrollWidth` against
  `innerWidth` at each width says whether anything overflows). The KIT's browser pack does this
  (`check --width 1440 --width 390 <paths>`); a WebDriver screenshot is of the viewport, so the
  window is made as tall as the page first.
- **An interaction check per contract-only behaviour**: `<the search swap: load /, type a query,
  submit, confirm the results region changed and the URL did not>`. Its script, its output and a
  screenshot of the result, kept beside the screenshots. A key action left by one check has been
  seen to change the focus behaviour of the next, even after `release-actions`: run each
  interaction check in a browser of its own.
- **An accessibility scan** of the stage's pages, by rule (axe-core, which the pack's
  `accessibility` task fetches once and never commits), and a keyboard walk of what matters
  <`the header, a menu, the search`> by hand or by script: the scan finds what a rule can find,
  not what a person finds by using the page. Its report kept beside the screenshots.
- **Serving for the check**: a task that serves starts the JVM as a grandchild, and a tree kill
  has been seen to miss it - a server from one check answered the next. The pack's `server.clj`
  (`with-server`) serves by the command given, waits for the health path, runs the checks, stops,
  and kills whatever still listens on the port when the tree kill did not reach it; stand a
  stage-end script of your own on it rather than on the serve task and a kill.

## 18. Readiness — the record

The Foundation is **ready** (not "done") when each line below is a fact with a date, not an
intention. Record them here; `method.md` §03's checklist is what they close.

1. **`bb doctor` reports green**, both verdicts — the `bb doctor --edn` map pasted into the
   decision log on <YYYY-MM-DD>.
2. **`bb health` passed on this machine** on <YYYY-MM-DD>: this KIT at `<commit>` with the template
   `<tag>` at `<commit>` (`bb init` printed the pair) — the certified pair this project was
   generated from.
3. **§11–§15 are filled and closed** — the overlay's placeholders gone (`start` lists none), the
   profile checked (`bb profile` green), the numbers in the log as P0-n.
4. **The loop has run end to end on one deliberately trivial task with this project's models**,
   run `<id>` on <YYYY-MM-DD>, to `:awaiting-merge` — and **torn down, not merged**: `record`
   first, so `<name>-plan/runs/<id>.edn` keeps the evidence, then `teardown`. The machine is
   proved and nothing of the rehearsal is left in the application.

Then Stage 1 dispatches against it.
