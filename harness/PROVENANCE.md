# Provenance

Extracted 2026-09-10 from `thub-harness`, a private multi-agent orchestration
harness that ran this loop across dozens of real dispatches.

*"The seed" below is what this folder was called from the extraction until 2026-09-22, when
it was a starting point an adopter copied into a project. It is `harness/` now, and it is
adopted rather than copied - a workspace holds a clone of the KIT beside the application - but
the history under that name is left as written.*

| seed file | source file | source commit |
|---|---|---|
| `src/harness/contract/shapes.clj` | `src/thub/harness/shapes.clj` | `978ff9e` (2026-07-10) |
| `src/harness/contract/packet.clj` | `src/thub/harness/packet.clj` | `e6b40ed` (2026-07-10) |
| `src/harness/gates/run.clj` | `src/thub/harness/gates.clj` | `5df04ad` (2026-09-05) |
| `src/harness/gates/repair.clj` | `src/thub/harness/gates.clj` | `5df04ad` (2026-09-05) |
| `src/harness/models/runner.clj` | `src/thub/harness/runner.clj` | `3dd2229` (2026-07-10) |
| `src/harness/rules.clj` | `src/thub/harness/rules.clj` | `5df04ad` (2026-09-05) |
| `resources/agent-rules.edn` | `resources/agent-rules.edn` | `5df04ad` (2026-09-05) |
| `agents/interactive-programmer.md` | `.claude/agents/clojure-interactive-programming.md` | working tree |
| `src/harness/setup/doctor.clj` | — | written for the seed |
| `src/harness/models/runner_check.clj` | — | written for the seed |
| `src/harness/loop/driver.clj` | — | written for the seed, from the drivers of three early runs |
| `src/harness/contract/blueprint.clj` | — | written for the seed |
| `src/harness/loop/orchestrate.clj` | `src/thub/harness/orchestrate.clj` | `5df04ad` (2026-09-05) — reshaped, see below |
| `src/harness/loop/log.clj` | `src/thub/harness/log.clj` | `5df04ad` (2026-09-05) |
| `src/harness/loop/triage.clj` | `src/thub/harness/triage_model.clj` | `5df04ad` (2026-09-05) — reshaped, see below |

## This is a fork, not a mirror

**"Sync with upstream" is not a supported operation.** The seed has deliberately
diverged, and each divergence has a reason:

- **`AgentResult` is closed, with a `:runner/meta` hatch.** Upstream it is open
  and carries five keys it never declared (`:session-id`, `:verdict`, `:capped?`,
  `:provider`, `:tokens`).
- **`GateEntry` / `GateResult` schemas exist here and not upstream**, so
  `:skipped` entries now carry `:exit nil :out ""` instead of omitting the keys.
  Upstream, two test files build that shape by hand as map literals
  (`grep -rl ':gates/report' test/` in that checkout at `5df04ad`).
- **`run-gate` catches `IOException`** and returns exit `127`. Upstream a missing
  gate binary throws — `:continue true` suppresses a non-zero exit, not a missing
  program.
- **`gates/failure` is new**, because upstream synthesizes that exact map by hand
  in two places.
- **Gate 0 moved to its own namespace** (`harness.gates.repair`), so `harness.gates.run`
  depends on nothing but a shell and the stack-specific part has one home. Two
  more stack-specific namespaces were added here and exist nowhere upstream:
  `harness.contract.stub` (the slice as loadable source) and `harness.contract.sigs` (the slice's
  `:deps-sigs`, checked against the source they describe).
- **`harness.models.profile` is new**, and so is `resources/profiles/`. Upstream ran
  one model family for every role and had nothing to configure; the seat/role
  split and the independence check only became expressible once the kit had to
  work from a client other than the one it was written in.
- **`Gates` drops `:cmd`.** Dead upstream: one writer, zero readers.
- **`:property-targets` is on every packet, not only the Tester's.** Upstream declares
  it on `TesterPacket` and `tester-packet` alone attaches it, so a contract decision
  reaches the one role that tests it. Two early runs each lost a decision that way. The
  field moved to `PacketBase` and `packet/base`. The Coder is told to satisfy the
  targets and the Reviewer to judge against them.
- **`Feedback` has sources upstream lacks: `:triage` and `:architect`.** A retry's reason
  can come from whoever routed the failure, or from a change to the contract, without
  being misattributed to a gate or the Reviewer.
- **`example-packet` was promoted** from a test fixture into `src`.
- **The rule source is genericised** — upstream's layer names, project-specific `ex-info` type
  and datastore references are replaced with `<angle-bracket>` prompts. A `:precedence`
  rule is **added**, which upstream does not have: it states that the rules outrank any
  personal or global instruction, because rule files merge silently and upstream currently
  has a live collision between a global *"run `bb test`"* and its own *"never run the
  gates"*. `:shapes-are-the-contract` also says to validate at the seams **once**, and that a
  function recursing into itself crosses its own seam on every call. Three dispatched Coders
  validated at every recursive call under the shorter wording upstream still has, so the
  gap is a back-port item.
- **Vocabulary: upstream says "corpus", the seed says "rule source".** Renamed because
  "corpus" collides with its NLP meaning (a body of text for training) in a document
  entirely about LLM agents. Same artifact, same shape — do not "fix" it back.
- **`ManualRunner`'s REPL hint is injectable** rather than hardcoding one bridge.
- **A role may carry `:pricing`, and `provenance/of` has a third arity that uses it.** Upstream
  ran everything through OpenRouter, whose generation record reports cost. Direct to Anthropic
  there is none, so the profile may name list prices with their source and date, and a cost
  computed from them is marked `:list-price` and rendered with a `~`. The table is in the
  profile, not the harness, on purpose (2026-09-14).
- **`rules/-main` defaults to `AGENTS.md` in the cwd** rather than a sibling checkout, so
  the seed's drift gate needs nothing outside itself. Upstream mirrors into `CLAUDE.md`;
  the seed generates `AGENTS.md` — which the Antigravity IDE, OpenCode and Pi read natively
  — and ships a hand-written `CLAUDE.md` that imports it, so there is one marker block and
  one drift target rather than one per client.
- **`rules/prompt-main` and `bb rules-prompt` are new.** Upstream renders the prompt block
  from inside its runners, which were not extracted; without a CLI the seed shipped
  `rule-block` with no caller at all — the rendering its own docstring calls the one that
  must never be skipped.
- **`repair/-main` and `bb repair` are new**, along with `changed-clojure-files` and
  `repo-root`. Upstream has no entry point for gate 0 outside the loop, so work done beside
  the loop depended on the agent remembering to run the repair — the exact discipline
  `harness.gates.repair`'s docstring says not to rely on. Untracked files are included
  deliberately: `git diff` alone misses a newly created namespace.
- **`doctor/toolchain` lists the interactive clients** (`claude`, `agy-ide`, `opencode`,
  `opencode2`, `pi`) and demotes `clj-paren-repair-claude-hook` from `:recommended` to
  `:optional`. A write-time hook is one client's accelerant; `bb repair` is the mechanism.
- **`orchestrate.clj` was reshaped, not ported.** Upstream it is one `run-task!` that
  holds the whole loop, with the routing decisions inside the steps that perform them;
  here `next-action` is pure over the run's state and `run-task!` only executes it, so
  the control flow can be table-tested without provisioning a worktree. It drives the
  seed's run directory rather than a spec in memory, composes `harness.loop.driver`'s
  commands, and stops — rather than merging — at every branch a person owns. The retry
  cap counts ROUNDS recorded in the state, not iterations of a function, which is what
  lets a hand `retry` and the loop spend the same cap.
- **Triage is an HTTP call to the profile's fourth role, on three triggers.** Upstream
  `triage_model.clj` shells out to a CLI, scrapes its JSON, fires on a red gate only and
  falls back to a deterministic classifier that hardcodes gate keys. Here `harness.loop.triage`
  is one `agent/converse!` with no tools on the `:orchestrator` role — the same runner and
  provenance as the other three — fires on a red gate, on a note and on a rejected review,
  offers `continue` on a note, and falls back to `driver/propose-routing` on a red gate and
  to a person on the other two, which have no gate output for a rule to read.
  Two things upstream leaves to the routed role's prompt are policy here: guidance bound
  for the Tester passes the leak check or the run stops, and a route the trigger does not
  offer is a fallback rather than a dispatch. The prompt frame — routes, "route to
  whichever diverges from the slice", the files as written capped at 8000 characters —
  is upstream's.
- **A rejection is routed, not escalated; and a missing verdict does not fail the
  dispatch.** Upstream's Reviewer runner returns `:failed` when its reply has no verdict
  block, and its loop escalates both that and any `reject` to a person. Here the verdict
  is parsed into `:runner/meta :verdict` — `AgentResult` stays closed — and a reply with
  no block is still `:done`: the findings were paid for, and what is missing is only how
  the loop reads them, so the loop stops with *the Reviewer gave no verdict*. A `reject`
  goes to triage under the same per-task cap as a red gate. The Reviewer's findings reach
  a retried Coder and never a retried Tester — the Reviewer read the implementation — so
  a Tester routed on a rejection is sent triage's guidance alone, through the leak check.
  The parser is upstream's `reviewer/parse-verdict`, moved to `harness.models.agent` because
  triage and the runner both read a model's answer that way and sit on opposite sides of
  the driver.
- **The merge is a command behind a decision file, not a y/n inside the loop.** Upstream's
  `run-task!` asks `approve? [y/n]` on stdin through `:human-gate-fn` and merges in the
  same call; a loop that blocks on stdin cannot be re-entered, and its answer is
  recorded nowhere. Here an approval stops the loop `:awaiting-merge`, and
  `bb run-loop merge <run-dir> <decision.edn>` is typed by a person, refused unless that
  stop stands, the gate run is current and the decision is not blank. Three further
  differences, each from the three-worktree layout upstream does not have: the commit is
  of the GATE worktree — the assembled bytes after gate 0, not a role's own; it is made
  at merge time and at no other, where upstream commits the single worktree before the
  review because its diff is `main...HEAD` (the seed's is `git diff HEAD` over
  intent-to-add); and the merge is refused when the gate worktree's diff is no longer the
  one the Reviewer was shown. A merge git refuses is aborted and recorded, and nothing is
  torn down. Teardown keeps the task branches, where upstream's removes the one it made.
- **A dispatch is refused when the worktree's nREPL does not answer.** Upstream the
  probe lives in the loop; here it is inside `driver/dispatch!`, so the manual commands
  are covered by it too — a `retry` typed by hand goes into a dead worktree otherwise.
- **The nREPL's output is kept, beside the worktree.** Upstream writes it next to the
  worktree too; the seed had `:out :discard`, so a REPL that refused to start reported
  nothing but a missing port file. Beside rather than inside, because a failed launch
  removes the worktree before rethrowing.
- **A capped dispatch that wrote no file is `:failed`** for the Coder and the Tester.
  Upstream fails a capped dispatch outright; the seed passed one as `:done` whatever it
  produced. Both are wrong in one direction: a capped dispatch that DID write files is
  work the gates should judge, and one that wrote nothing is a missing deliverable that
  assembly refuses a step later with nothing to say why.
- **A retry is synced from the gate worktree first** (`driver/sync-from-gate!`). Upstream repairs
  delimiters in the gate worktree and retries the role in its own, where the unrepaired file still
  sits; a Tester spent a whole dispatch hunting a bracket that no longer existed where the gates
  looked. A fix, found by the first project to copy the seed.
- **A provider-refused dispatch is `:measured`.** Upstream's report calls any step without a model
  or a cost synthetic; a refusal has neither and is not made up.
- **`write_file` repairs and lints Clojure on write** (`tools/after-write`), and the rule source says
  so. Upstream lints only at the gate, after the author has gone; each warning cost a round.
- **`edit_file` exists** (a text that occurs exactly once, replaced; the same refusals and the same
  after-write). Upstream's roles could only replace a whole file, and did, to fix one warning.
- **The rule mirrors are data** (`resources/rule-mirrors.edn`), not paths in `bb.edn`'s task body —
  the seed's own only; the repository around a copy lists its own in `../rule-mirrors.edn`.
- **`report-check` passes when nothing is published**, and fails when only half is.
- **The record carries every spec review, and the report a whole cost** beside the loop's total.
- **`parse :openai` reads cached tokens** (`prompt_tokens_details`), and `:in` excludes them on both
  shapes. Upstream records prompt and completion tokens only.
- **A third rule-source placeholder, `:data-conventions`**, for every audience, and `start` lists the
  placeholders still standing. Upstream's rule source is a filled one and has neither.
- **A word the contract uses is not a Tester leak**, an amendment makes a review history, and a writer
  cut off at its cap is named at the stop. None of the three exists upstream.
- **A fifth dispatched role, `:spec-reviewer`**, and `harness.contract.spec-review` behind it: the contract is
  read cold by a model of a different family from the seat's before `start` dispatches it. Upstream
  has four roles and no review of the spec.
- **The seed's docstrings cite no run ids and no register rows.** They keep the lesson
  each decision cost and drop the citation, because this repository does not hold the runs
  they named. Checked, from the
  repository root: `grep -rn 'NOTES.md row' harness/src harness/dev harness/test`
  and `grep -rnE '\b(D[0-9]{1,2}|B[123][abc]?|S[12](-[0-9A-Z])?)\b' harness/src harness/dev harness/test`
  both print nothing; `grep -rn 'RUNS.md'` over the same three finds only
  `harness.money.report` and its test, which name the file `bb report-check` reads; and
  `grep -rnE '\b[Rr]un [0-9]\b'`, for the numbered manual runs, finds one line of code
  in `report.clj` — `(:run/attempts run 1)` — and no comment.

## Third-party provenance

Rules marked `:from :awesome-copilot` in `resources/agent-rules.edn` are adapted from
[github/awesome-copilot](https://github.com/github/awesome-copilot) (MIT) —
`instructions/clojure.instructions.md` and `agents/clojure-interactive-programming.agent.md`.
**Adapted, not copied:** that source assumes Calva and `add-libs`, both wrong here, since
this method runs a per-worktree nREPL and pins dependencies behind a gate.

## Why the ancestry is recorded at all

The risk is not that you have a stale copy — you are *meant* to edit this
immediately. The risk is that someone later finds both copies, tries to
reconcile them, and re-introduces the defects listed above.

If you do have the source checkout and want to see what else changed:

```bash
git -C ../../thub/thub-harness show 978ff9e:src/thub/harness/shapes.clj \
  | diff - src/harness/contract/shapes.clj
```

## Not extracted, deliberately

`coder.clj` / `tester.clj` / `reviewer.clj` (client-specific: CLI flags, JSON
scraping, a 226-line tool-call loop — stale within months) · `worktree.clj` and
`llm.clj` (project infrastructure; provisioning here is `harness.loop.provision`, written
for the seed).

`orchestrate.clj`, `log.clj` and `triage_model.clj` were on this list until 2026-09-16
and are now in the table above — the loop after twenty-five runs by hand, which is the
order §12 recommends. `triage.clj`, the deterministic classifier, stays out: it hardcodes
gate keys and encodes one project's opinion about who is usually at fault. Its place as
the fallback is taken by `driver/propose-routing`, which reads the spec's file ownership
rather than a gate key.
