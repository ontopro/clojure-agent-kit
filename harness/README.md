# harness

The orchestration harness described in [`../method.md`](../method.md) §12 — the thing
that checks the machine, generates the application, assembles task packets, dispatches
them to agents, runs the quality gates, and drives the loop to its next stop.

**This file is the authority on what the harness contains, how a project adopts it, what is
deliberately left out, and the order to add the rest back.** Other documents link
here rather than restating it.

**It is adopted, not copied.** A workspace holds a clone of the KIT beside the application it
builds; `git pull` upgrades it, and nothing of the project is written into it (`workspace.edn`
carries what the harness must know). About 10,800 lines of source and 8,800 of tests
(`cat $(find src -name '*.clj') | wc -l` and the same over `test/`, 2026-09-24). Much of the
source is commentary: nearly every namespace says WHY it exists and what run taught it, because
that is the part you cannot reconstruct from the code.

```bash
bb doctor     # every tool, its version, what fixes a miss; two verdicts: the gates, a loop
bb health     # the KIT, this machine, the pinned template - certified together (minutes, JVMs)
bb init xyx   # the workspace: the application generated from the pin, the build, work/
bb plan-check  # the filled plan, before Foundation: no mark, no instruction, the overlay filled, the given parts intact
bb plan-review # method.md §02's review pass, run: the plan read cold by the :plan-reviewer; a reading, not a gate
bb skills-sync # the fifth part, skills/, rendered to the clone's own .claude/skills/ and a workspace's; --check holds both to the source and every cited heading to the method (in gates)
bb next       # the workflow's next step, its owner and the command or skill, read off the build's files; scoping before a workspace exists
bb stage-report <stage-plan.md> # a stage's figures from its records, into its plan's exit-criteria section; --check holds the block to the records (in gates)
bb spec-from-blueprint <blueprint.md> <task-id> [<out.edn>]  # a task's spec.edn out of the Blueprint, its named shapes inlined from §1; pipes into bb sigs
bb gates      # the KIT's own gates: doctor -> format -> lint -> rules -> reports -> health block -> test; writes .local/gates/last.edn, which the pre-commit hook reads
bb repair     # gate 0 over the Clojure files you changed (run before bb gates)
bb example    # the whole loop shape in one run: no model calls, no network
```

## Prerequisites

`bb`, `git`, `cljfmt`, `clj-kondo` and `clj-paren-repair` (via `bbin`) to run the gates - no
JVM; JDK 21, the Clojure CLI and `clj-nrepl-eval` as well for a loop. `bb doctor` probes each one
(the bbin tools by running them), prints the command or page that fixes a miss, and gives one
verdict per tier - run it first, and `bb doctor --tier gates` is what `bb gates` runs.
The first `bb test` fetches malli into `~/.m2`; everything after that is offline.

The same list, installed and nothing else, is the `Dockerfile` at the root of the clone: a
Linux container with the toolchain alone, every version a build argument read from the
known-good set, in which `bb docker-gates` and `bb docker-health` (root tasks) clone HEAD from
a read-only mount and run the gates and the health check as a fresh machine would. Firefox ESR
and geckodriver are in it too, for the health check's browser check.

## The pieces

The inventory is rendered from the source between the markers, so the count and the list are never
typed by hand; the table after it is hand-written - what each namespace is for and the lesson it
encodes, in the order a run meets them, not by group - and `bb inventory-sync --check` holds it to
the source by name: a namespace the source has that the table never mentions fails the gates.

<!-- harness-inventory:begin -->

51 namespaces, rendered from `src/harness/` by `bb inventory-sync` (`--check` in `bb gates`): `harness.setup` (14: the machine and the workspace), `harness.loop` (6: the steps and the loop), `harness.contract` (7: the packet, the shapes, the checks on a spec), `harness.gates` (5: the runner, gate 0, the boundary gate), `harness.models` (14: runners, adapters, profiles, provenance), `harness.money` (4: balance, the reports, repricing), and `harness.rules` on its own. The group is the folder, what a namespace is about; the layer is its place in the dependency order `layers.edn` declares and `bb boundary` holds - 0 requires nothing of the harness, 8 is the top - computed as one more than the deepest layer of what it may require.

| Namespace | Group | Layer | The first line of its docstring |
|---|---|---|---|
| `harness.rules` | — | 1 | The agent-rule source and its two renderings. |
| `harness.setup.app` | setup | 2 | The second part of `bb init`: generate the application from the pinned |
| `harness.setup.blueprint-review` | setup | 5 | A stage's Blueprint, read cold before sign-off: `bb blueprint-review <blueprint.md>`. |
| `harness.setup.doctor` | setup | 0 | Toolchain doctor: what this stack needs, whether it is here, and what it does. |
| `harness.setup.health` | setup | 8 | `bb health` - the KIT's health check: does this KIT, on this machine, with the |
| `harness.setup.init` | setup | 3 | `bb init <name> [dir]` - create the workspace a project is built in. |
| `harness.setup.inventory` | setup | 2 | The harness's namespaces, rendered into its README from the source: |
| `harness.setup.next` | setup | 3 | What comes next in the workflow, read off the build's files: `bb next`. |
| `harness.setup.plan` | setup | 2 | What the harness knows about a plan's documents: the template the KIT ships |
| `harness.setup.plan-review` | setup | 4 | The plan-review pass of `method.md` §02, run: `bb plan-review [<plan-dir> | <stage-plan.md>]`. |
| `harness.setup.security-fixture` | setup | 1 | `bb security-fixture <dir>`: the application a security reading is measured on - generated |
| `harness.setup.skills` | setup | 1 | The KIT's fifth part, `skills/`, and the two places it is rendered to: |
| `harness.setup.template` | setup | 0 | The template the KIT brings with it: read the pin, and build the command that |
| `harness.setup.upgrade` | setup | 2 | What a pulled KIT expects that this workspace lacks - a report, never a |
| `harness.setup.workspace` | setup | 0 | Where things are: the workspace a path is in, and what its `workspace.edn` says. |
| `harness.loop.driver` | loop | 5 | `bb run-loop <command> <run-dir> [args]` — the loop's steps as commands, with a |
| `harness.loop.log` | loop | 0 | The run's append-only event log. One EDN map per line; never rewritten. |
| `harness.loop.orchestrate` | loop | 7 | The loop: `bb run-loop run <run-dir>` drives one task to its next stop. |
| `harness.loop.provision` | loop | 2 | Workspaces: a git worktree and its own nREPL, one per (task, role). |
| `harness.loop.security-routing` | loop | 7 | Who acts on what a security review reproduced: `bb security-route <review.edn> <clone>`, at a |
| `harness.loop.triage` | loop | 6 | Who owns a failure: one model call — on a red gate, a note, or a review |
| `harness.contract.blueprint` | contract | 2 | A task's `spec.edn`, pulled out of a Blueprint written to the template. |
| `harness.contract.packet` | contract | 1 | Task-packet assembly. |
| `harness.contract.shapes` | contract | 0 | Malli schemas for the loop: the task packet, what a runner returns, |
| `harness.contract.sigs` | contract | 1 | The Blueprint's `:deps-sigs`, checked against the source it describes. |
| `harness.contract.spec-review` | contract | 4 | A review of the CONTRACT before anyone works from it: `bb run-loop spec-review <run-dir>`. |
| `harness.contract.stub` | contract | 0 | The contract, as code the Tester can load. |
| `harness.contract.targets` | contract | 0 | The spec's `:property-targets`, checked against the tests the Tester wrote. |
| `harness.gates.boundary` | gates | 0 | Gate 4: the architecture-boundary check (method §09), against a project's |
| `harness.gates.forms` | gates | 0 | The top-level forms of a Clojure file, as a table: kind, name, line range, |
| `harness.gates.record` | gates | 0 | The KIT's gates record and the commit check that reads it. |
| `harness.gates.repair` | gates | 1 | Gate 0: mechanical repair, before the gates run. |
| `harness.gates.run` | gates | 0 | The gate runner. |
| `harness.models.adapter` | models | 0 | One request shape per model family, and one parsed shape out. |
| `harness.models.agent` | models | 3 | One model, one conversation, the tools it may call. |
| `harness.models.bake-off` | models | 6 | Candidates for a role, read against the same artifact, compared: `bb bake-off`. |
| `harness.models.catalogue` | models | 0 | The models a route can reach, from OpenRouter's public listing: `bb models <query>`. |
| `harness.models.profile` | models | 1 | Which model answers for which role, and the one client the human works in. |
| `harness.models.provenance` | models | 0 | Who actually answered, what it actually cost. |
| `harness.models.runner` | models | 4 | The AgentRunner seam. |
| `harness.models.runner-check` | models | 5 | A conformance check for AgentRunner implementations. |
| `harness.models.security-bake-off` | models | 5 | Models compared as security reviewers: `bb security-bake-off <fixture-dir> --model "<model> [effort]" ...`. |
| `harness.models.security-review` | models | 4 | A security review of an application: a model in the security reviewer's seat, a clone of the |
| `harness.models.security-review-sandbox` | models | 0 | The container a security review runs in: a clone of the application under review, the |
| `harness.models.security-review-tools` | models | 3 | The tools a security reviewer works with, over a clone of the application under review. |
| `harness.models.security-score` | models | 4 | Scoring a security review against the fixture's answer key: `bb security-score <review.edn> |
| `harness.models.tools` | models | 2 | The four things a dispatched agent may do in its workspace. |
| `harness.money.balance` | money | 2 | What the money looks like before and after a run: `bb balance`, and the two |
| `harness.money.report` | money | 1 | The per-run report: what each step cost in time and money, and which model |
| `harness.money.reprice` | money | 2 | `bb reprice <run.edn>`: fill the cost of every dispatch step a record left |
| `harness.money.stage-report` | money | 3 | A stage's figures from its records: `bb stage-report <stage-plan.md>`. |

<!-- harness-inventory:end -->

### The layers

> [!NOTE]
> **Folders say topic; layers say dependency order.**

The code has two axes, and a tree can show only one.

- **The folders are the topic axis.** `setup`, `loop`, `contract`, `gates`, `models`, `money`
  group the namespaces by what they are about, which is what a reader looking for a thing wants.
  It says nothing about who depends on whom: `setup.doctor` and `setup.workspace` are leaves
  everything stands on, `setup.health` is the root that requires nearly everything, and all three
  share a folder.
- **`layers.edn` is the dependency axis.** The harness's own ruleset for the boundary gate it
  ships to every project: each namespace maps to the set it may require - exactly what it requires
  today - ordered bottom-up: leaves, foundations, models and money, the plan and the readings, the
  loop, the roots.
- **`bb boundary` holds it, in `bb gates`.** A new upward require fails with the namespace named,
  so it is a conversation before it is a dependency.
- **The inventory shows both.** Its layer column is computed from the ruleset - one more than the
  deepest layer of what a namespace may require - and never typed.

Nothing moves a file for this. The tree keeps saying topic, the ruleset says order, and the
inventory shows both.

Who acts on them — every review, gate and dispatch in a build, the profile role behind each and
the model as shipped — is [`roster.md`](roster.md).

| Namespace | What it is | The lesson it encodes |
|---|---|---|
| `harness.loop.orchestrate` | The loop: `next-action` says what happens next from the run's state, `run-task!` does it until the answer is a stop | A loop is for the steps nobody needs to think about, and for noticing reliably when something has gone wrong. It is not for deciding what to do about it — every stop it cannot decide is handed to a person, with the run left where it stopped. It reads the Reviewer's verdict and **never merges**: an approval stops it `:awaiting-merge`. |
| `harness.contract.spec-review` | The contract, read cold by the `:spec-reviewer` role — its own role in the profile, checked by `bb profile` to be a different family from the seat's — before it is dispatched — every place a target can be read two ways, every input no target mentions. `start` runs it for any spec without a current review, prints the list and stops `:spec-reviewed`; an edited spec is reviewed again; `:spec-review/run? false` in `loop.edn` skips it. `bb run-loop spec-review <run-dir>` by hand. What a call costs depends on the spec's size and on the rule source: the prompt carries the Reviewer's rendering of it, so a long `:data-conventions` costs more. A project's own records give the figure - the report prints each review and their sum (`bb report`) |
| `harness.money.balance` | `bb balance <profile.edn> [records...]`, and the line `run-loop run` prints before and after the loop: OpenRouter's remaining credit from its API, and — only when a role is on Anthropic's API directly — that spend from the run's steps, since its API has no balance endpoint; an Anthropic model served through OpenRouter is in the key's figure and is not called Anthropic spend | The first adopter was stopped by a credit limit on each provider, mid-run, with nothing having said how much was left | Two roles that misread a sentence the same way disagree with nothing; a spec that draws ten findings is not ready, and the Architect who wrote it is the wrong reader to find that out |
| `harness.loop.driver` | The same steps as commands: `spec-review`, `start`, `check`, `retry`, `amend`, `continue`, `record`, `merge`, `teardown` | The loop was built out of these, not beside them, and they stay because they are how a person carries on from any stop. A triage decision is written down *before* the retry it justifies, and a merge decision before the merge. `merge` takes the **gate worktree** — the bytes the gates passed and the Reviewer read, not the bytes a role wrote — and refuses if they have changed since the review. |
| `harness.loop.log` | The run's append-only event log, beside `state.edn` | `state.edn` is rewritten on every command, so it is the wrong thing to trust about what happened. Two copies and a gate on their disagreement — the same treatment this repository gives its generated documents. |
| `harness.setup.health` | `bb health`: one piece of code, two subjects; `--record` writes a per-platform record (`health/records/`) and the known-good set the doctor shows, and `bb health-sync` renders the records into the root README between markers, drift-gated in `bb gates` - the sandbox (copied to scratch) for the harness's mechanics, an application generated from the pinned template for the certified pair. Per subject: gates green; each gate FAILED by a portable breaker (data, needs only the root namespace) at its own key; one trivial task through the whole loop with a scripted runner in the model's seat, real nREPLs and gates, to the merge stop, then RECORDED as a project's run is - the copy in the plan's `runs/` and the three commits held to each repository's HEAD, the rules and the profile read from the plan - and torn down; `bb serve` → `GET /` 200 → stopped, port free; then the KIT's browser pack (`bb --config <kit>/tools/browser/bb.edn check`) - served, a headless Firefox through geckodriver opening `/`, a screenshot as tall as the page, stopped - a probe that PERFORMS, since `geckodriver --version` passes on a machine where no Firefox can start (on macOS the permission is the terminal application's); skipped, and the record says so, where geckodriver or Firefox is absent; then the KIT's security pack (`bb --config <kit>/tools/security/bb.edn check`, `deps`, `secrets --all`) - served, its requests sent from outside, stopped; its libraries asked of OSV; its history read for a committed secret - so every claim of `02-architecture.md` §4 a request can try is tried against the application the pin generates and an advisory published since the pin fails here first, the warns and skips one line each in the detail | Three projects failed on the first hour for reasons an empty repository could not show; a suite that has only ever been green proves nothing; the health check is `bb init` into scratch, the path an adopter takes |
| `harness.setup.security-fixture` | `bb security-fixture <dir>`: the application a security reading is measured on - generated from the pin and committed as `base`, a feature (accounts, private notes with a quota, an export, administration) added on two neutrally named branches, once careful and once with six faults planted from `health/security-fixture/faults/` (two architectural: a race, an authorisation check on one method; four in the diff: raw SQL, a delete by GET, a path out of a folder, a note read by id), which is which in `cases.edn` outside the repository; the build runs the reference tests and refuses a fixture whose key does not hold. Never in `bb gates` | A reading measured on a real project cannot be scored: nobody knows what it missed. Planted faults with a test each are a known answer, and the careful branch is the control that counts what a reader invents |
| `harness.gates.boundary` | Gate 4, the architecture-boundary check: a project's `layers.edn` against its `src/`, three kinds of violation as data (undeclared, forbidden, orphan). `bb boundary [dir]`; a project runs it as `bb --config <kit>/harness/bb.edn boundary`, the command `bb init` writes into `loop.edn` | The only working boundary gate was in `sandbox/dev/`, outside what an adopter took (row 32); a placeholder that always exits 0 proves nothing about the loop that dispatches on its key |
| `harness.setup.init` + `harness.setup.app` + `harness.setup.workspace` | `bb init <name>`: the workspace as a layout first (data: every entry and what it is, `--dry-run` prints it), then written - the application generated from the pin and committed untouched, then the KIT's hand as a second commit; the plan repository with the documents, the rules overlay, the seat's profile and the run defaults; `work/`; `workspace.edn` naming which folder is which. `workspace/current` is the one lookup every command uses for its workspace: the run's, the flag's, the variable's, the walk-up's, in that order | A workspace is three sibling repositories in a plain folder, three lifecycles, none rewritten to change another; the state a project makes - its rules, its profile, its records - lives in the plan, never in the KIT's clone, which is why `git pull` is that clone's only change. Nothing here is generated by shelling the command: the health check calls the same functions, so a failure surfaces as data |
| `harness.setup.template` | The template the kit brings with it, as data (`resources/template-pins.edn`: coordinate, full commit, tag, deps-new version) and the pure function that turns a pin into the generation command | A template named in code is a template nobody can change without a release; a pin that is a tag or a branch is not a pin |
| `harness.setup.plan` + `harness.setup.plan-review` | `bb plan-check`: what a filled plan still carries - every mark, every line of the template's instructions still standing, every requirement that cites no observation of `source.md` and is not marked inferred, the overlay's placeholders - and whether its given parts are intact (the KIT's rules unedited, `layers.edn` not loosened against the pin's layers, the gate keys in the KIT's order); exit 1 with the list, and `start` runs it once per workspace, known by a hash of what it read. `bb plan-review`: `method.md` §02's pass, run - the plan's documents and §02's checklist, read from the method at the call, to the profile's `:plan-reviewer`, one completion; findings printed with the cost and written to `<plan>/reviews/` | The plan is the Architect's session's to fill, and nothing read it: a mark left standing reached the next reader as literal text, the rule source's failure one level up; and two projects ran the review pass as a throwaway script each with the checklist pasted in by hand. The check is the gate and is free; the reading is not a gate, because a review that found nothing proves nothing |
| `harness.setup.blueprint-review` | `bb blueprint-review <blueprint.md>`: a stage's Blueprint and the stage document it names, read whole by the profile's `:blueprint-reviewer` against `method.md` §07 step 2 (is it over-engineered?) and §06's rules for shapes and targets, both read from the method at the call; one completion, no tools; the findings printed with the cost and written to `<plan>/reviews/<stage>/blueprint-review.edn`, for the Architect to resolve in the stage document before the sign-off | The method promised this read and nothing ran it: the harness's orchestrator is triage, and a Blueprint's packets met a model one at a time in the spec review, which cannot see a layer with one use or a shape nothing needs. Two projects wrote the read as a throwaway script; the third measured which family reads a Blueprint best, and the answer set the role |
| `harness.setup.doctor` + `harness.setup.upgrade` | Toolchain probe and report; in a workspace, what a pulled KIT expects that it lacks | A missing small binary doesn't fail the loop — it quietly spends the Coder's retry budget on parens. Check the toolchain; don't assume it. |
| `harness.setup.skills` | `bb skills-sync [--check]`: the KIT's `skills/` - one skill per conversational step of the workflow - rendered to the clone's own `.claude/skills/` and a workspace's, each copy noting the KIT commit; the check compares everything but the commit, and holds every heading a skill's Reads table cites to the file it names. A project's own skill under another name is never touched | The method is the source: a skill that restated its section would drift, so each cites its headings and the gates hold them; and a workspace's copy of a skill was nobody's to update until the doctor and this check said so |
| `harness.setup.inventory` | `bb inventory-sync [--check]`: this README's inventory block - every namespace under `src/harness/`, its group, the first line of its docstring, the count - rendered from the source between markers; the check fails on drift and on a namespace this table never names | The count above was typed by hand and was wrong within a week of being right; a number nobody re-derives is the failure this repository is most against, and the first run of the check named this very namespace as unnamed here |
| `harness.setup.next` | `bb next`: the workflow's next step, its owner and the command or skill that runs it, from five facts that are files - a workspace, the plan check, the stage's gates record, its blueprint reviewed and signed, its packets' merged records; before a workspace exists, scoping. Pure over the facts and table-tested, as the loop's `next-action` is; information, never a gate | The hand-offs between the workflow's steps lived in prose and in whoever remembered them; a session opened cold had to find its place by reading everything. The loop answered the same question for a run from its state alone, and this is that shape one level up |
| `harness.money.stage-report` | `bb stage-report <stage-plan.md>`: a stage's roll-up over the run records of its blueprint's packets and its readings - money by role, rounds, stops by owner, retries and rejections - beside its cap and the stage before, with the security pack's counts and its two scans' from their records beside the stage plan, rendered into the stage plan's exit-criteria section as a block that names itself; `--check` (in `bb gates`) holds every published block to the records, both directions, as `report-check` holds `RUNS.md`. Said, not counted: the cap stays the person's stop | The stage is the unit of money and its figures were a hand-kept rounds table, a ledger and a spend sheet, re-added after every merge and re-derivable by nobody |
| `harness.contract.shapes` | Malli schemas: packet, result, gate result, run record | A result contract that isn't enforced isn't a contract. `AgentResult` is **closed**. |
| `harness.contract.packet` | Cuts a role-specific packet from a task spec | The Tester's context excludes the Coder's impl — **mechanically**, not by asking a Blueprint author to remember. |
| `harness.gates.run` + `harness.gates.repair` + `harness.contract.targets` | Ordered, short-circuiting gate runner; gate 0 | Cheap before expensive; a failing gate returns *what it said*, not just which one; a broken gate config fails the gate, not the run. |
| `harness.gates.forms` | A Clojure file's top-level forms as a table (kind, name, lines, hash) and the difference between two tables | A change to an existing file reports what it did at the level of forms - lost, gained, changed by name - while the author is still there; the same table is the check a MODIFY packet will need. Silent when a file does not parse. |
| `harness.gates.record` | The KIT's gates record and the commit check: `bb gates` runs its steps through it and writes `.local/gates/last.edn` at the repository root, green or red - the working tree as a git tree id (a temporary index, `git add -A`, `git write-tree`) before and after the run, HEAD, the exit, the step and the tests that failed by name, the start and the end - and a copy of every run under `.local/gates/runs/`, kept, from which a test that failed on a tree and passed later on the SAME tree is printed after the run as flaky; `bb commit-check` at the root, which the clone's pre-commit hook (`.githooks/pre-commit`, `bb hooks-install`) runs, refuses a commit with no record, a red one, or one for another tree than the index being committed. The KIT's own development; nothing of it reaches a workspace | "`bb repair && bb gates` green before committing" was a rule a session had to remember, and nothing held a commit to it. A record of the tree checked, read against the tree committed, makes it a fact the commit refuses on |
| `harness.models.runner` + `harness.models.runner-check` | The `AgentRunner` seam, a `ManualRunner`, and a conformance check | Ship the mechanics first with a human at the invocation point. Then check every runner you add against the same contract. |
| `harness.rules` | One rule source, rendered into prompts *and* into `AGENTS.md`, with a gate on drift | Prompt rules beat retry feedback. A rule written in two places rots; a rule written only in a file never reaches a model family that doesn't read files. |
| `harness.loop.provision` | Three worktrees per task, each with its own nREPL; assembly as a filter | Isolation asserted is isolation absent. Only a role's declared `:files/target` crosses into the gate workspace, and anything else is refused by name. |
| `harness.contract.stub` | The Blueprint slice as code the Tester can load | A worktree is a checkout of the whole repo, so the previous implementation is sitting there to be read. Write the contract over it. |
| `harness.loop.triage` | One model call on a red gate, a note, a rejected review or a security finding: `{route reason guidance}` over `coder · tester · architect · human` (+ `continue` for a note; a security finding offers `coder · architect · human`), with `driver/propose-routing` as the fallback on any failure of the call | A mechanical rule has to assume something — usually that the tests are right. A model shown the contract, the failing output and both files as written can say the contract is the problem, which no rule ever proposes. It degrades, never blocks; and guidance bound for the Tester goes through the same leak check a typed retry does, or the run stops. On a rejection the Reviewer's findings go to the Coder and never to the Tester: the Reviewer read the implementation, so the Tester gets triage's guidance alone. |
| `harness.loop.security-routing` | `bb security-route <review.edn> <clone>`: each finding a security review REPRODUCED goes to triage as a `:security-finding`, with the security reviewer's test, the files the finding names as merged, and the architecture's §4, §8 and §15; the prompt says the finding passed the per-task code reviews, the gates and the stage's tests and leans to `architect` for a missing or misplaced check. `coder` writes a fix packet DRAFT in the Blueprint's format (the file and the promise triage named, the slice left marked) to `security-fixes.md`; every route to `security-routing.edn`; a hypothesis is listed for 02 §15, never routed. Nothing is dispatched | A finding that reached the security reviewer is usually a gap in the design, and turning it into a fix task by itself would hide the gap; and the security reviewer's test was written from the code, so it is the fix's acceptance check and never the Tester's context |
| `harness.models.profile` | One seat, and a family/model/endpoint per dispatched role — eight of them: the plan reviewer's one call per stage in its plan step, the Blueprint reviewer's one call per stage before sign-off, the Spec reviewer's one call before `start`, the Coder, the Tester, the code reviewer, the Orchestrator's triage call, and the security reviewer's reading at every stage's end; after the checks, a line per role whose model has a newer model in its line in the catalogue, as information | §05's independence rule — *verifier ≠ Coder family* — had lived in prose since it was decided. This is the first thing that can fail on it. Two worked examples ship, because a single one reads as *your* configuration. |
| `harness.contract.sigs` | The slice's `:deps-sigs`, checked against the source they describe; the implementation's calls, checked against the slice; and a rewrite's dependents, for `:files/context` | A wrong signature is worse than a missing one: the agent is told a function exists and the failure surfaces as *its* fault, two gates later. A dependent nobody was shown breaks at the test gate, after a paid attempt. |
| `harness.contract.blueprint` | `bb spec-from-blueprint <blueprint.md> <task-id> [<out.edn>]`: the task's packet out of a Blueprint written to the template, every shape it names under `:shapes` replaced by §1's definition (verbatim, in §1's order; a def or a pair, both of which the stub reads), validated as `shapes/TaskSpec` - the part of the packet the Architect writes - and written as the `spec.edn` a run starts from, or to stdout for `bb sigs`; a shape §1 does not define and a task id the Blueprint does not carry are refused by name | Every build copied the packet out of the Blueprint by hand and pasted in the shapes it named, each a little differently, while the template's own packet had drifted from what the harness reads - `:contract`, `:workspace`, `:gates {:cmd}`, no title, no targets. A template a test reads as the schema cannot drift again, and an extraction that is a command is done the same way every time |
| `harness.models.catalogue` | `bb models <query>`: OpenRouter's public listing as rows - the models a word or a slug names, newest first, the family from the slug's prefix, prices per million, whether reasoning is taken, the providers serving one; and `resources/routes.edn`, how each family is reached (endpoint, key variable, shape, provider pin, effort levels), read by `route`. No key is sent | Every brief verified its model slugs, provider tags and prices by hand against this listing; a model named by a word ("grok") had no way to become a role block. The route table is what the two shipped profiles already said, written once so a slug can be expanded without copying a block |
| `harness.models.bake-off` | `bb bake-off new \| run \| table \| check`: candidates for an act, read against the same artifact, compared. A three-line spec (the act, the candidates as "model effort", the judge) expanded through the catalogue and the routes into role blocks; every case run with every candidate ONCE through that review's `read!`; a judge that is never a candidate reads the answers blind (A, B, C in a recorded shuffle) and maps consensus and disagreement into one table; the person marks rows real or not in `marks.edn` and the per-dollar figures render from the marks alone. `bake-off-check` holds `TABLE.md` to the records in a workspace's gates. The reading acts today; the dispatched roles' runner is row 46's residue | Three bake-offs were run as throwaway scripts scored by hand, and none survived. The build decides on one pass, so a bake-off measures one pass; the consensus across candidates is the only repetition, and a singleton is where the information is. A judge that decides what is real is a fourth voice to argue with; one that only matches findings is the step a rubric cannot do |
| `harness.models.adapter` + `harness.models.provenance` | Two request shapes, and the second call that says who answered; for an endpoint with no such call, usage × the profile's list prices, marked as computed | A completion names no provider. One slug can be served by several hosts at several quantisations, and which one answered decides the chat template. A price table in the harness would rot; one in the profile carries its source and date. |
| `harness.models.tools` + `harness.models.agent` | `read_file`, `write_file`, `edit_file` (a text that occurs once, replaced), `nrepl_eval`, and the loop that drives them, which keeps a transcript — what the model said and ran, per completion — and sends a request again on a rate limit or an overloaded host, counting how often | A tool failure returns to the model as data. `nrepl_eval` is a shell, unavoidably — the containment is the worktree, not the tool list. A dispatch that wrote nothing used to leave nothing to say why. |
| `harness.models.security-review-tools` | The tools a security reviewer is given over a clone of the application under review: read a file, search the tree, write a new test file below `test/` (the only thing it may write; an existing file, the application's tests included, is refused), send one request to the application under test (a method, a path as written, headers, a body, bounded by a budget), and run the application's tests (all, or one namespace) - both through a sandbox it is handed as functions. The same set for every model that fills the role, as a registry of `tools/specs`'s shape handed to `converse!`'s `:registry` option, so the coder's five are untouched. No REPL | A comparison of models must measure the models and not their tools, and a security reviewer that checks a concern by writing a test that fails needs no tool that runs arbitrary code |
| `harness.models.security-review-sandbox` | The container a security review runs in: the dependency cache warmed in a first container that has the network, then a second with `--network none`, the cache read-only, memory, process and processor limits, all capabilities dropped, a clone of the application mounted and the application started in it; the security reviewer's request and test run are `docker exec`s into it - the tests in a pristine copy of the application made for each run (what git tracks plus the security reviewer's new files, never `target/`, `db/` or `.cpcache/`), so what a test sees is what a fresh clone has - the request through `resources/security-review/request.bb`, one JSON argument and no shell. Commands are built as data and the steps take the function that runs them, so their order is tested without Docker | A security reviewer is given tools that make requests and run code it wrote; the isolation has to be the container's and not the tools' good manners. Tried: a test that reaches for the internet from inside fails, and the dependencies resolve offline once warmed |
| `harness.models.security-review` | `bb security-review <clone> --stance tracer|diff --model "<model> [effort]"`: a security review of the application cloned in `<clone>` - a model in the security reviewer's seat, the five review tools, the sandbox. Two stances with the same tools: the tracer follows requests through the repository's routes, middleware and shared state; the change stance is given the stage's diff. The answer is a JSON block of findings, each `reproduced` (a test of the security reviewer's own failed on this code) or a `hypothesis`; no block is not a clean review. The tests it wrote are collected from the clone and kept beside the record. A real model call, never in `bb gates` | A finding that is a failing test needs no one's opinion to be checked, and a security reviewer told only to find problems finds some - so the prompt says that finding nothing is a complete answer |
| `harness.models.security-score` | `bb security-score <review.edn> <fixture-dir>`: a review scored against the fixture's answer key. Each test the security reviewer wrote is run ALONE in the sandbox on the faulted branch, the careful one, and - for the tests that discriminate - on each variant with one fault reverted; a test that fails on the faulted branch only detects the fault whose revert makes it pass, which is a hit and needs nobody's opinion. A test that fails on both branches is unplanted (a real flaw, an invention, or a refused setup - quoted, for the person to mark); one that passes on the faulted branch is a concern that did not hold. No model reads anything. Never in `bb gates` | A whole-suite run hides every test behind one that does not compile, and a count of findings says nothing about which faults were found. The variants make the attribution mechanical |
| `harness.models.security-bake-off` | `bb security-bake-off <fixture-dir> --model "<model> [effort]" ...`: models compared as security reviewers. Each reviews the fixture twice as the tracer - the faulted branch, where there is something to find, and the careful one, where there is not - and every reading is scored by running the tests it wrote. The table is arithmetic: planted faults found, claims its tests did not show, what is left for a person to mark, tests that fail on the careful branch (inventions), completions, minutes, cost. No judge; a stopped run resumes. Beside the table it writes `report.md`, itself two tables: one by model (the metrics as rows, a column per candidate - faults found, claims, time split into sandbox start, review and scoring, cost, tokens, completions, tool calls, tests written, how it ended, cost and time and tokens per fault found) and one by reading, with a line for the whole run and its wall-clock; `--report <dir>` writes it again from a folder's records. Not the plan-reading bake-off, which compares one-completion readings under a judge. Never in `bb gates` | A review is minutes of tool calls in a container and its scoring is mechanical, so the generic tool's judge and marks add a model and an opinion to a question tests already answer |
| `harness.money.report` + `harness.money.reprice` | Per-step time, cost, model and serving provider for a run; below it the performance section — total/waiting/active time from absolute timestamps, money by provider, role and model, the OpenRouter balance at start and record, tokens with the cache share, rounds, roles as run — and the drift gate over the reports a document publishes | A number nobody measured must say so, in the cell. A total that silently omits three dispatches is worse than no total. And a published number whose record has gone missing is unverifiable, so `bb report-check` fails on it — the same treatment `AGENTS.md` gets, applied to the other kind of generated content this repository commits. |

## The rule source

> **Rule source** here means the single authoritative set of rule records — not a body of
> text for training. Every other place that holds rules is a *rendering* of it.

`resources/agent-rules.edn` is that file: every rule an agent is told to follow is stated
there once. Each rule carries an `:audience` — a subset of `#{:coder :tester :reviewer :human}` — and two
renderings derive from it:

- **into a headless agent's system prompt**, filtered by audience, with `{{placeholders}}`
  substituted per dispatch;
- **into every mirror listed in `resources/rule-mirrors.edn`** (the harness's own `AGENTS.md` marker
  block and the selfcheck project's), **in `../rule-mirrors.edn`** when a repository around the harness has one (its own mirrors,
  as paths relative to the harness) **and in a workspace's `workspace.edn`** (`:workspace/rule-mirrors`,
  where `bb init` records the application's) by `bb rules-sync`, drift-checked by `bb rules-check` inside `bb gates`.

The prompt rendering has a CLI: `bb rules-prompt --audience coder --repl-port 7807`
writes the block to stdout, or to `--out FILE`. Where it goes is the caller's
problem — Pi replaces its system prompt from a file, a headless runner interpolates
it into an HTTP request.

Edit rules there — never in a prompt string, never by hand inside `AGENTS.md`'s marker
block, which the gate reports as drift.

**Three rules ship as `<placeholders>` and are yours to fill before the first dispatch**:
`:layer-boundaries` (your layers and their direction), `:shapes-are-the-contract` (your validation
library and boundary error), and `:data-conventions` — what this project's recurring data IS, for
every role: what a tree is and how it is walked, what collection each recurring argument is, what
your targets' recurring words mean, what each seam guarantees to everything past it. The third is
the one two projects needed most; a question two specs would both have to answer belongs there and
in neither spec. An unfilled placeholder reaches every agent as literal text with every gate green,
so `bb run-loop start` lists the ones still standing (`harness.rules/unfilled`). It does not refuse:
the harness runs its own selfcheck project with them standing.

**Where they are filled: in the project's rules OVERLAY, never in the clone.** `bb init` writes
`<name>-build/rules.edn` with the three as shipped, and `workspace.edn` names it
(`:workspace/rules-overlay`); `harness.rules/overlay` merges it over the source by id - a placeholder
rule's text replaced, a rule of a new id added, any other rule of the source refused by name, which is
what holds the KIT's own rules against an edit made by accident. Every rendering reads the merged
set: each role's prompt, the spec review's, triage's data conventions, `bb rules-prompt`, and the
application's `AGENTS.md`, which `bb rules-sync` re-renders after an edit. The KIT's own mirrors,
inside the same workspace, render from the source alone (`harness.rules/rules-for-mirror`), so
`bb rules-check` in the clone stays green whatever the project fills. `resources/agent-rules.edn` is
upgraded by `git pull` and edited by nobody else.

### Adding something by hand

Only the **block between the markers** is generated. Everything above and below it is
hand-written and survives `bb rules-sync` untouched — so there is a place for manual
content, and the only question is which bucket you are in:

| What you are adding | Where it goes |
|---|---|
| A rule for agents | the rule source - a project's `<name>-build/rules.edn` (the overlay), this repository's `resources/agent-rules.edn` - then `bb rules-sync` |
| A rule only humans need | the same, with `:audience #{:human}` — renders here, reaches no prompt |
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
the harness generates `AGENTS.md` and ships a `CLAUDE.md` that imports it — one marker block,
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

## Versions: floors, one pin, and a known-good set

The harness ships no `.mise.toml` of its own — its gates run entirely under `bb` and need no
JVM, so a pin it does not have would fail `bb gates` on your machine for no reason. The generated
application ships one, dated, the versions it was last run with:

```toml
# .mise.toml - a KNOWN-GOOD SET, not a set of requirements
[tools]
java = "temurin-21"
clojure = "1.12.6.1673"
babashka = "1.13.223"        # bbin needs >= 1.12.212
clj-kondo = "2026.08.04"
cljfmt = "0.16.5"
```

`bb doctor` reads the `[tools]` table of the nearest one (walking up) as FLOORS: older is *too
old*, newer is *newer than the pin - fine*, and a calendar version is a date. The one exception is
the JDK, held at major 21 by the doctor itself (XTDB v2's early releases failed at class-load on
newer JDKs); a `.mise.toml` pinning another major is told *change the pin, not the JDK*. Beside
the machine's versions the doctor shows the KIT's known-good set, `resources/known-good.edn`,
written by `bb health --record` from a run that passed - *newer than tested* is information.
The known-good set is also what the fresh-machine container is built from: `bb docker-gates`
reads each version out of it into the `Dockerfile`'s build arguments, so a recorded health run
moves the container with it, and there is no second list to keep.

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

The harness is ADOPTED, not copied: a workspace holds a clone of the KIT beside the application it
builds, `bb init` wires the two through `workspace.edn`, and `git pull` upgrades the clone. Nothing
of a project is written into it - which is why the list below is short.

- **What the harness needs of a project** - and all it needs, whatever the framework: a git
  repository; gate commands and an nREPL command named in the build's `loop.edn` (`bb init` writes
  the pinned template's); Clojure source; and a `layers.edn` if the boundary gate is in the
  sequence. Two of three projects built with it were bare-`deps.edn` projects and the loop ran.
  Supported is narrower than possible: the health check certifies the pinned pair only.
- **The application's rule mirror** is the application folder's `AGENTS.md` (`<name>-app/` unless
  `bb init --app` named the folder), listed in `workspace.edn` under
  `:workspace/rule-mirrors`, and `bb rules-sync` / `bb rules-check` here read it from there. Fill
  the rule source's placeholders, and add rules of your own, in `<name>-build/rules.edn` - the overlay
  `bb init` wrote and `workspace.edn` names; the clone's source is never edited. `start` prints the
  ones still standing, and `bb plan-check` holds the overlay to none; `bb rules-sync` re-renders
  the mirror after an edit.
- **The plan is read before Foundation, by two commands.** `bb plan-check` is the gate: no mark and
  none of the template's instructions left in `<name>-build/docs/`, the overlay's placeholders
  filled, the given parts intact - the KIT's rules unedited in the overlay, the application's
  `layers.edn` not loosened against the pin's layers, the gate keys in `loop.edn` in the KIT's
  order. Exit 1 with the list, one sentence each; `start` runs it once per workspace before its
  first dispatch and knows once by a hash of what it read (`work/plan-check.edn`). `bb plan-review`
  is `method.md` §02's review pass, run in every stage's plan step: in stage 0 the plan's seven
  documents, and in every stage after its stage plan first (`bb plan-review
  <stage-plan.md>`) with the seven as the context it revised, sent with §02's checklist to the
  profile's `:plan-reviewer` - a model of another family than the seat's - one completion, the
  findings printed with the cost and written to `<name>-build/reviews/plan-review.edn`, or
  `reviews/<stage>/plan-review.edn` for a stage plan; the Architect resolves them in the
  overview's table. The checklist asks the plan step's three questions by name - a requirement
  written for a stage not pulled, a risk with no owning stage, a lesson of the last stage the plan
  does not answer - before the faults that recur in any plan. A reading, not a gate. Both take the
  workspace's plan by default, a plan path otherwise. **And each stage's Blueprint is read the same way before
  sign-off**: `bb blueprint-review <blueprint.md>` sends the stage document and the Blueprint to the
  profile's `:blueprint-reviewer` with `method.md` §07 step 2's question and §06's rules, and writes
  the findings to `<name>-build/reviews/<stage>/`; the spec review still reads every packet after.
- **Before choosing a role's model, a bake-off.** `bb bake-off new` asks for an act, the candidates
  (a slug or a word - `bb models grok` shows what a word names) and a judge, and writes
  `<name>-build/bake-offs/<id>/bake-off.edn`; `bb bake-off run` reads every case on disk for the act
  with each candidate once, has the judge map where they agree and disagree, blind, and renders
  `TABLE.md`; you mark the rows real or not in `marks.edn` and `bb bake-off table` re-renders.
  The winner's block is pasted into `profile.edn` as it is. A record whose cost had not arrived when it was written
  keeps its generation ids, and `bb bake-off reprice <dir>` fetches the costs later and re-renders the table. `bb gates` here holds the table to the
  records.
- **A KIT kept outside its workspace is pointed at it.** Every command here finds the workspace by
  walking up from `harness/`, which from a clone kept elsewhere (`bb init <name> <dir>`, or one
  clone serving several projects) finds nothing: the rule tasks would miss the application's
  mirror and every rendering would read the source alone. So `bb rules-sync`, `bb rules-check`,
  `bb profile` and `bb report-check` take `--workspace <dir>` (the workspace, or any folder under
  it), and every command reads the `KIT_WORKSPACE` variable - set it once in the shell. A folder
  named that has no `workspace.edn` at or above it is refused by name. A loop command needs
  neither: the run directory is under the workspace's `work/`, and the run's workspace is the
  command's, whatever the shell says.
- **Your profile is `<name>-build/profile.edn`.** `bb init` copies the shipped example for your
  seat there (`--seat <name>`, default `claude`) and points the build's `loop.edn` at it: in a
  workspace, `:profile` resolves against the build. Edit it there; `bb profile` here checks it, and
  `bb balance` with no profile argument reads it. `resources/profiles/` is the shipped examples'
  folder, held by the KIT's own tests to exactly those files - nothing of a project goes in the clone.
  The doctor lists five seats and the KIT ships examples for two: `claude`, the seat every build on
  the KIT has run from and the one the health check's profile names - its example routes every role
  through OpenRouter, the Anthropic ones pinned to the `anthropic` provider with the cache asked for
  on the Coder alone, the route every build has run on (since 2026-09-25 in the file, not only in
  the builds; its comments say why: one key and one balance, the cost reported, the credit stop,
  the generation record) - and `agy-ide`, written and
  checked by `bb profile` but never yet in the seat of a build, its Reviewer, spec reviewer and plan reviewer
  direct to Anthropic, which is where the `:anthropic` shape's worked example now is; `bb init --seat` refuses the other
  three by name until an example exists. Portability across seats is the design, proved one seat
  at a time - `portability.md` has the mechanics per seat.
- **Run records live in the build repository.** The driver's `record` writes `run.edn` in the run directory
  and copies it to `<name>-build/runs/<run-id>.edn` - the folder `workspace.edn` names
  (`:workspace/records`); every record names the KIT, application and plan commits it was taken
  at, and `bb report` prints them. `<name>-build/RUNS.md` (`:workspace/run-tables`) publishes the
  tables, and `bb report-check` with no arguments holds the two to each other - `bb gates` in the
  workspace's clone runs it, so a record nobody published, or a table whose record is gone, fails
  there. `record` fetches, once and without waiting, the cost, provider and tokens of every step
  whose generation record had not been written when its dispatch ended; `bb reprice <run.edn>`
  fills what was still missing then, and the table is re-rendered after. A reading's record -
  `plan-review.edn`, `blueprint-review.edn`, `spec-review.edn` - keeps its ids the same way, and
  `bb reprice <review.edn>` fills its cost and its history's. The KIT publishes none of its own - its evidence
  is `health/records/`.
- Put your project's commands in `gates/default-gate-seq`. **The gate keys are a
  public contract** — whatever routes a failure dispatches on them, and the run
  log is read by key months later.
- Wire the architecture-boundary gate (§09's gate 4) with a placeholder ruleset
  before you know your real layers. Retrofitting it later is a much worse job.
- **Four namespaces are stack-specific and the rest are not.** `harness.gates.repair`
  runs your language's mechanical fixups, `harness.contract.stub` emits its source,
  `harness.contract.sigs` reads its definitions and `harness.gates.forms` its top-level
  forms. Add your stack's repairs to the first **and nowhere else** — that is what keeps
  `harness.gates.run` dependency-light and gives a reader on another stack exactly four
  files to rewrite.
- Add a headless runner by implementing one method, then run it through
  `check-runner`. For testing one, the technique that works is a generated
  executable stub script that records its argv — make the executable name an
  option so tests point at the stub and only the live run spends quota.
- **The skills.** `bb init` renders the KIT's `skills/` into the workspace's `.claude/skills/`,
  each copy noting the KIT commit; `bb skills-sync` here re-renders them after a `git pull`, and
  `bb skills-sync --check` (in `bb gates`) and `bb doctor`'s workspace report say when a copy
  no longer matches. A skill of the project's own goes beside them under another name and is
  never touched.
- **What is open, and what is closed.** Open - the project's, written in its workspace: the
  rules overlay's placeholders and its own rules; the profile's models; `loop.edn`'s values
  within the keys the shape names; the gates after the KIT's four, in order; a skill of its
  own; the plan's documents past their given parts; `layers.edn`'s added layers. Closed - the
  KIT's, upgraded by `git pull` and never edited in the clone: the rule source's text; the gate
  keys and their order; the profile shape and the family rule; `loop.edn`'s key set; the
  template's layers; the given parts of the plan; the shipped skills; the method.

## Deliberately left out

Each of these was considered and cut, not forgotten:

- **Parallel dispatch.** §07 step 3 dispatches Coder and Tester concurrently; the loop
  here runs them in sequence. The independence that matters is the Tester's worktree
  holding no implementation, which is provisioning's job and is real either way.
  Concurrency buys wall time and costs a much harder failure mode.
- **Per-gate timeout** — a real hazard, but no run has hung on a gate yet.
  `gates/run-gate` is the insertion point if you need one.

**The orchestration loop is in, and it was left out for two milestones before that.**
§12's advice is not to build it speculatively: run the loop by hand until you have felt
where the manual version hurts. That is the order this followed, and `src/harness/loop/driver.clj`
is what the manual version left behind — the steps as commands, pausing wherever a role
leaves a note, with a triage decision written down before every retry.
`src/harness/loop/orchestrate.clj` composes those steps and hands every branch it cannot decide
back to a person. The leakage that made the loop unpleasant upstream (session-resume
branching, a retry key in no schema, per-runner cost semantics) is answered rather than
ignored: this runner does not resume sessions, and `AgentResult` is closed with a
`:runner/meta` hatch.

Triage is a model call, and the loop dispatches on its answer. On a red gate under the
cap, on a note at the pause, and on a review that rejected, `harness.loop.triage` shows the profile's `:orchestrator`
the contract, the failing output and both roles' files as written, and asks who owns
it. A `coder` or `tester` route is a retry through the same `retry-with!` a typed
decision goes through, the guidance first and the gate's output after it; `architect`
and `human` stop the loop with the reason; `continue` on a note carries on with the
reason recorded; `tooling` — the machine is at fault, not the run: a merged file no role
here owns, the build, the harness — stops the loop naming it, and is offered on every
note and on a red gate only when the proposal below says the failing namespaces are
nobody's. The mechanical half is still `check`'s own — on a red gate it records
which role owns the files the gate named, as a proposal beside the verdict — and it is
what triage falls back to when the call fails, the answer has no verdict, or the route
is one the trigger does not offer. And feedback bound for the Tester that names the
implementation — an impl file, a var the slice never granted, a code block — is refused
the same way whoever wrote it: a typed `retry tester` throws unless `--allow-leak`
records the judgement, and a routed one stops the run for a person to make it.

**The Reviewer ends with a verdict, and the merge is a command.** The Reviewer's
deliverable asks for a trailing fenced JSON block, `{"verdict": "approve" | "reject",
"reasons": [...]}`, and the runner parses it into `:runner/meta :verdict`. A missing or
unreadable block does not fail the dispatch — the findings were paid for and are kept —
it stops the loop for a person with *the Reviewer gave no verdict*. `approve` stops it
`:awaiting-merge`; `reject` goes to triage under the same retry cap as a red gate. Then:

```bash
bb run-loop merge <run-dir> <decision.edn>    # {:decision "why this merges"}
```

One commit of the gate worktree on its branch, made at merge time and at no other; that
branch merged `--no-ff` into whatever the base checkout has checked out; then `record`
(status `:merged`) and `teardown`. It is refused unless the loop's last stop was
`:awaiting-merge`, the gate run is current, the decision is not blank, and the gate
worktree's diff is byte-for-byte the one the Reviewer was shown. A merge git refuses is
aborted, recorded as an event with git's output, and tears nothing down. Denying a merge
is not a command: the run stays where it is, worktrees and branches in place. Upstream
asks `approve? [y/n]` on stdin from inside the loop; a question that cannot be answered
tomorrow, or recorded, is not a gate.

## Provenance

See [PROVENANCE.md](PROVENANCE.md). Short version: this is a **fork, not a
mirror**, of code that still lives elsewhere, and the divergences are listed
there with their reasons.
