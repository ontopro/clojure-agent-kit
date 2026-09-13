# Devlog

What changed, when, and why — newest first. A running record, appended to as
work happens.

The three documents around it each keep one job, and this one does not repeat
them: [`RUNS.md`](RUNS.md) is the analysis of every end-to-end run against
`sandbox/`, [`NOTES.md`](NOTES.md) is what is still open *now*, and
[`portability.md`](portability.md) is the standing design analysis. What lives
only here is the **sequence** — the order things were tried in, and every place
a later change corrected an earlier one. That is the part no other document
keeps, and on this evidence it is the part worth keeping.

**These entries were 32 commits.** They were collapsed into one on 2026-09-13 so the
published history is six commits rather than thirty-seven, and this file is what
they left behind — which is why it carries the reasoning rather than a list of
subjects. The original commits are kept on a local `history/pre-squash` branch
and are not published, so they are deliberately not cited here: a reference no
reader can resolve is worse than none.

Entries before 2026-09-11 21:56 are in `git log`: the repository's first five
commits are unsquashed and describe themselves.

---

## Next

- **D7** — the return channel has never met a model. Nothing calls
  `packet/for-retry` and no dispatch has been offered the `note` tool
  (`NOTES.md` rows 4 and 5). A run settles both.
- **The loop drivers are outside the repository.** Every dispatched run was
  driven by a throwaway script, now kept in `.local/runs/` so D7 does not rebuild
  one from nothing. That is the right home for a throwaway; what is still missing
  is the thing that stops it being throwaway — a real driver at
  `harness-seed/dev/run-loop.clj`, committed, once enough runs agree on its shape.
- **Push.** Held deliberately until the tree is in a state worth publishing.

---

## 2026-09-13

### 14:48 · A second drift gate, over the reports RUNS.md publishes

Committing the records made the figures checkable by hand. Nothing checked them.

`bb report-check` closes that, and it is deliberately not a new idea: `rules-check`
already gates `AGENTS.md`'s generated block against `agent-rules.edn`, and `RUNS.md`'s
report tables are the same kind of thing — generated content living in a markdown file.
Every published report re-renders from `runs/<id>.edn` and must match byte for byte. It
runs in `bb gates` between `rules-check` and `test`, in-process, in milliseconds.

**Checked in both directions**, which is the half worth arguing for. A published table with
no record is the failure that prompted all this. A record nothing publishes is the other
half, and without it the gate could be satisfied by deleting the evidence instead of fixing
the table. The bidirectional check is also what caught the `run3`/`r-03` filename mismatch
by hand earlier today.

**The obvious gate was the wrong gate.** "Run every command the documents tell a reader to
run" is what suggested itself first, and it would not have caught this: the command
`.local/README.md` published named `d6`, and `d6` renders. What was false was the claim
around it. `NOTES.md` said otherwise for half an hour and now says this instead — a command
runner catches path rot, not a true example supporting an untrue sentence.

Six mutations of `harness.report` confirm the seven new tests bite: drift silenced, the
no-block direction dropped, a changed number accepted, every fenced block counted as a
report, a non-record undetected, an unrenderable record swallowed. All six caught.

### 14:10 · The run records committed; only the scaffolding stays local

Writing the `.local/` convention up as a *general* rule for `~/.claude/CLAUDE.md`
exposed that the specific case here was half wrong. `.local/` held two unlike
things: 253 KB of scaffolding — drivers, specs, session files, all hardcoding
paths and superseded by the next run — and 13 KB of **run records**, which are
what `RUNS.md`'s eight tables and 22 cost and token figures were rendered from.
Keeping the second half where no reader can reach it means this repository
publishes precise numbers nobody can re-derive, which is the exact failure its
own rules exist to prevent: `:step/source` is required with no default and
synthetic rows are bracketed, and then the evidence was put out of reach.

So `runs/` is committed, named by `:run/id`, beside the document it is evidence
for. The drivers stay in `.local/`.

**Two of the eight were not records at all.** `bb report` threw a
`NullPointerException` on `d1/smoke-out.edn` and `d3/result.edn` — both raw
results, from before there was a run record and from a single-role dispatch.
Their tables had been rendered by a shaping script that was then thrown away,
so "re-render it yourself" would have needed a script that is not there. Both
were reconstructed from their own fields, and the general rule grew a third
bullet: **commit the evidence in the shape the committed tool reads**, not the
raw output it was shaped from.

Verified by diffing, not by reading: all eight rendered reports are byte-identical
to the fenced blocks published in `RUNS.md`, and the set is closed in both
directions — every block has a record and every record has a block. Neither
`/tmp`, `/Users` nor any key name appears in the committed files.

### 13:32 · Documents given one job each, and this file written

The session ended with 32 unpushed commits carrying 1,360 lines of message — a
lot of reasoning living somewhere nobody greps. This file is where it went, and
the commits were collapsed to one.

Auditing the documents while extracting them found the same failure twice more.
**Three forward-looking lists existed and two had drifted** — `harness-seed/README.md`
and `method.md` §12 both still said "the first headless runner" was to come,
a week after the seed had one. They were duplicates of each other, which is why
both were wrong: nobody updates a list they think another file owns.

So each document now states its job at the top, and the root README carries a
map of which is which. The seed's README is the authority on what the seed
contains and what is left out; `method.md` §12 argues the question and points at
it; `NOTES.md` is the state right now; this file is the history. The `LICENSE`
story was in two files verbatim and is now in `CLAUDE.md` alone, where it is a
working rule rather than a limitation.

`RUNS.md` gained a timestamp, an outcome and its report table for every run —
**regenerated from the surviving run records, never retyped.** Runs 1 and 2 have
no record and say so rather than carrying reconstructed numbers. Doing that
turned up a hand-abbreviated D5 table I had written into the file earlier, which
is the same failure as an unmarked synthetic number and is now deleted.

**No TODO file.** There were already three places carrying forward-looking work
and two were stale; a fourth is that pattern by construction. The immediate
queue is the `## Next` block above.

---

## 2026-09-12

### 23:44 · Gate 0 delegates config discovery; feedback is clipped

Two issues the previous two commits had introduced.

`config-root` searched for `.cljfmt.edn`, and cljfmt also reads `cljfmt.edn`
without the dot — the bug fixed 9 minutes earlier, wearing a different
filename. Adding the second name would have patched the wrong idea: **cljfmt
searches ancestors for its config**, verified rather than assumed, so the
hand-rolled search was never needed. And the invariant gate 0 owes is not
"find the config" but *agreement with the fmt gate*, which delegates. So
`format!` now runs cljfmt once per directory, from that directory, and
`config-root` is gone along with the repository-root guard it needed.

The test changed with it and is better for it: it asserted *where a config
file was found* — a fact about our own search — and now runs `cljfmt check`
the way `bb gates` does and asserts there is nothing left to say.

`:task/feedback` had no size limit while `harness.tools/max-output` clips tool
results at 20,000 characters and explains why: output is charged as input
tokens on every subsequent turn. A retry's reason has exactly that property. I
wrote that reasoning in one namespace and did not apply it one layer up.
Clipped at 4,000 and announced, at the point feedback *enters* the packet
rather than where it is rendered.

### 23:35 · Gate 0 runs cljfmt under each sub-project's config

`bb repair` reported success and `bb gates` then failed on the file it had just
repaired. cljfmt reads `.cljfmt.edn` from the process directory and
`repair/-main` runs from the git toplevel; this repository keeps its config in
`harness-seed/` and `sandbox/` and has none at the root. D5 and D6 never saw it
because a provisioned worktree's project root *is* `sandbox/`.

Mutation caught a test of mine holding for the wrong reason — "falls back to
the root rather than escaping it" asserted the fallback with no config above
the root to escape *to*. Third time this session.

### 23:30 · A return channel on the packet

Two open findings pointing in **opposite** directions turned out to be two
halves of one circuit. A Coder with no way to report an edge the Blueprint is
silent about (outbound, run 4); a packet with no field for why a role is being
dispatched again (inbound, D4). Neither is worth anything alone: a note that
reaches nobody is a diary, a retry with no reason is the same dispatch twice.

**The outbound channel was never missing — it was prose.** Run 4's Coder did
say the contract was silent about dividing 7 by 2; D3's did say why it declined
a `:deps-sigs` entry. Both into `:stdout`, which nothing reads.

A `note` tool, available to every role including the Reviewer. It writes
nothing — `converse!` already keeps every tool call, so capture is a filter.
`:notes` is *declared* on `AgentResult` against that map's own standing advice
to use `:runner/meta`, because the test is whether the loop must branch on it.
Inbound: `:task/attempt` and `:task/feedback`, set by `packet/for-retry`, which
refuses empty feedback and refuses attempt 1. Rendered **first** in the prompt.

A test found a real bug: the OpenAI shape delivers tool arguments as a JSON
string and my extraction stringified it, so a note came back as its own JSON.
`harness.tools/invoke` owns that decoding, so it now returns what it decoded.

*Opened:* `NOTES.md` rows 4–5 (nothing calls `for-retry`; no model has seen
`note`). *Closed:* the two return-channel findings from run 4 and D4.

### 23:12 · The five open findings as a table

`NOTES.md` carried three of them as a run-on sentence and omitted two. Now a
table with what each costs. Two of the five say plainly that they are not
defects: the tier-or-family question is an experiment nobody ran, and the
one-level fixture is a habit for review to watch for.

The 23 fixed findings are deliberately **not** duplicated from `RUNS.md`. A
second copy of a list is the drift these runs kept catching in the code.

### 16:10 · Bring the documents back to what is true

`RUNS.md` was created one commit before D1, and six dispatch runs then left
their findings only in commit messages. Its front matter asserted something
false: *"All five … no model was called and no money was spent."*

Five more claims had gone stale the same way: `portability.md` still showed the
disproven profile *and* said the runner was unbuilt; `NOTES.md` still said no
request is ever made to a model; the seed README was missing four of seventeen
namespaces and still listed headless runners as deliberately left out; the line
count was wrong in three places.

The line count is now stated in **one** place. It had been wrong twice, and
three copies of a hand-maintained number is two too many.

Root `CLAUDE.md` gained the working rule that was meant to stop the
recurrence — and did not, see 23:12 the next evening.

### 15:34 · D6 · `t-08-substitute` — the confirmation run that refuted a finding

A different task on purpose, because replaying `t-07-vars` would confirm
nothing about anything but `t-07-vars`. Detail in [`RUNS.md`](RUNS.md).

Confirmed: gate 0 running cljfmt, and `reasoning_effort "medium"`.

**Refuted, and the framing was mine.** D5 read as *gpt-5.2-codex converges
where deepseek did not*. D6's Tester hit the cap at 15 having written nothing,
then again at 24. The transcript showed it working correctly and never stopping
prototyping. The difference from the Coder was one clause — the Coder's
deliverable ends *"then write once"* and the Tester's had no stop condition.
Fourteen words of prompt, not a model.

**And the worse finding:** when the Tester produced nothing, assembly copied
the Coder's implementation, silently skipped the absent test file, and all four
gates went green over zero new tests. `assemble!` already refused an *extra*
file loudly; the inverse was never covered and is the more dangerous direction.

### 14:57 · D5 · `t-07-vars` again — the loop completes

First green run end to end. Stronger Tester, providers pinned,
`reasoning_effort` down to medium.

**Gate 0 running cljfmt is the change with the best ratio** — it turned a
failed run green in 315ms with no further dispatch.

**`reasoning_effort` was wrong everywhere, and this is measured:** the Reviewer
at `high` produced 27,706 tokens, 4m09s and an **empty** final message; the
identical dispatch at `medium` answered in 63s for half the money with real
findings. The original sketch put `high` on every OpenRouter role and nobody
had asked.

Two things I got wrong while making the gate-0 change, corrected in place: I
blamed relative paths for cljfmt doing nothing and wrote that into a comment
(sorting is not a default; `:sort-ns-references?` enables it), and the test
fixture had no `.cljfmt.edn`, so it would have passed against a gate 0 that
never formatted anything.

### 09:54 · D4 · `t-07-vars` — the whole loop, and it failed honestly

All three roles dispatched for the first time. 16m54s, 410,225 tokens,
$0.016014 across 3 of 25 steps. **The run failed, and that is the result.**

`claude-sonnet-5` as Coder wrote a correct namespace in 5 iterations, first
attempt. `deepseek-v4-flash` as Tester hit its cap on all three attempts and
never produced a passing test namespace, breaking two conventions its own
system prompt states.

**One confound, visible only because the provider column exists:** the three
attempts were served by StreamLake, then Baidu, then StreamLake.

Fixes it forced: `:harness/wrote` now rides on the packet as well as the
session, because the Tester reported the generated stub as its own output; and
the report gained wall time and minutes.

*Opened:* the packet has no field for why a role is dispatched again — found by
running triage by hand.

### 09:25 · D3 · `t-06-clamp` — the first real dispatch

`harness.runner/api-runner`. `:files` comes from `repair/changed-files`, never
from the model's reply — a model that says it wrote a file is making a claim,
git is looking.

**The first dispatch failed and both causes were mine.** `nrepl_eval` IS a
shell — the model ran `clojure.java.shell/sh` calling `find` — and the
docstring claimed it was not. A Clojure REPL is arbitrary code execution; the
containment is the worktree, not the tool list. And nothing told the role what
*finishing* looked like: told only to "work through it", the Coder hunted for a
file that did not exist yet. `runner/deliverables` states it per role now.

The second dispatch took 6 iterations and wrote a namespace that passed
everything. It also **declined a signature it was given** and said why — which
is `:fix-the-cause` and `:final-message` working unprompted, in a model the
rules were not written for.

### 09:10 · D2 · the tool loop

`harness.tools` and `harness.agent`. A tool failure returns to the model as
data, never a throw. The cap does not throw either: a capped run may have
written usable files.

Message accumulation turned out to be shape-specific, so two more adapter
methods were needed — OpenAI wants the `tool_calls` array echoed and one
message per result; Anthropic wants the original content blocks verbatim.

**Mutation found two of my own tests passing for the wrong reason.** Deleting
the path-containment check reddened nothing, because the escape test used a
non-existent path that later checks caught anyway.

Also from D1's findings: the generation poll became a steady one second, and
the model column went to 50 with its width derived from the same table as the
format string — the two-places-that-must-agree bug run 4 found, in the same
function.

### 08:56 · D1 · two adapters and two-call provenance

Two real calls, 20 tokens each, ~$0.00001. Three findings:

- The generation record took **8.6 seconds** against a 1.5s budget, so the
  first real cost came back `nil` — the exact thing the namespace exists to
  produce. The new budget was measured by polling a fresh id, not guessed.
- The record returns the **resolved** model: `deepseek-v4-flash-20260423` for
  a request that said `deepseek/deepseek-v4-flash`.
- $0.000003642 rendered as `$0.0000`. A row asserting a run was free when it
  was not is the same class of lie as an unmarked fabricated number.

The `:anthropic` adapter exists because OpenRouter's normalisation hides three
real differences, of which one is dangerous: the system prompt is a top-level
parameter, and sent as a message it is **accepted and ignored** — the rules
would silently never reach the model.

### 08:33 · Split RUNS.md out of portability.md

`portability.md` had grown to 605 lines, 246 of them a chronological account of
five runs — and it had been cut once already for exactly that. A diary belongs
somewhere else.

`NOTES.md` was not stale but *wrong* in three places, including listing
provisioning as unbuilt.

### 08:20 · Fix the profile: the Coder's family follows the seat

Shipped with `:seat :agy-ide` and the Coder on `claude-sonnet-5`. The seat is
the client the human drives, so the Coder's family follows it; the roles were
internally consistent and attached to the wrong seat.

Two worked examples now, near mirror images, because **a single committed
profile reads as *your* configuration** — which is how this was misread, and
the misreading landed on a real mistake.

---

## 2026-09-12 (early hours)

### 01:31 · Profiles

Three things were being conflated: the interactive **seat**, the dispatch
harness, and the model **family** per role.

**§05's independence rule can now fail.** *Verifier ≠ Coder family* had lived
in prose since it was decided — the same gap `:shapes` and `:deps-sigs` had, in
the rule the whole method rests on.

Deliberately *not* checked: two verifiers may share each other's family.
Putting a stricter rule in code than the decision log supports is how a check
starts describing its author rather than the method.

Mutation-tested on my own tests: removing the independence check reddened four,
but opening the `:roles` map reddened **none** until a case was added.

### 01:16 · Run 5 · `t-05-render` — the first rewrite

The stub's second purpose — written *over* an existing implementation — had
been a design claim and nothing more. It works.

**The finding, and the most serious of the runs.** `(is (thrown? Exception
(render e)))` PASSED against the stub, because `ex-info` is an `Exception`. Two
of seven cases were green before any implementation existed — precisely the
failure `harness.stub` exists to make impossible, present since the namespace
was written. It now throws an `AssertionError`, which `thrown? Exception`
cannot catch. The test that pins it *evaluates* the generated source rather
than grepping it.

*Opened:* nothing tells a rewrite what else depends on the namespace
(`NOTES.md` row 1).

### 01:00 · harness.sigs reads clj-kondo's analysis

Hand-rolling a reader was the wrong instinct. The right reflex was *parse,
never evaluate* — but clj-kondo already computes exactly this and is already a
**required** tool here. It knows what a form expands to: `defrecord`'s
generated `->R`, `(def f (fn [x y]))`, `defmulti`, macros — none of them
handled by the hand-rolled version.

It also reports `:syntax` errors per file, closing a hole the first version had
no way to see: partial analysis of an unreadable file would report every var it
never reached as undefined.

### 00:50 · Run 4 · `t-04-reduce` — the first with the precondition

**The precondition paid for itself on its first run.** Two deliberate errors of
the class run 3 shipped, caught in 56ms before either agent was dispatched.

`:step/kind` gained `:precondition` — and adding it broke the table, because
the column width and the rule under it were two literals that had to agree.

*Opened:* a Coder that discovers an edge the Blueprint is silent about has no
channel to say so.

### 00:32 · Give `:deps-sigs` a consumer

The last slice field no code read. Run 3 supplied the evidence: a zero-arity
call signature for something that is a map, which rode through packet
validation, two dispatches, four gates and review with nothing looking.

**It runs before dispatch, not as a gate.** A gate judges an agent's output;
this judges the packet the agent is about to be given. **And it never guesses**
— a check that cries wolf about a correct packet is switched off within a week.

### 00:19 · Run 3 · `t-03-analyze` — the first with shapes emitted

The emitted schema arrived as a single 161-character line. cljfmt does not
reflow long lines, so it passed the fmt gate and was still unreadable — and
reading it is the Tester's whole job. Loadable *and* legible, or it does half
the work.

The shape settled three questions the Tester would otherwise have guessed,
answered by evaluating it rather than asking. §05's data-first claim doing
visible work.

### 00:08 · Give `:shapes` a consumer

`grep :shapes harness-seed/src/` found a docstring and nothing else. Run 2
showed the cost: a declared shape with no consumer is invisible to every tool,
so only review could catch it.

Two forms are legitimate and mean different things: `[Name schema]` defines and
is emitted; a bare symbol names one defined elsewhere and is **not invented**.

---

## 2026-09-11

### 23:55 · Run 2 · `t-02-between`

All four gates green on the first attempt, where run 1 needed a triage cycle —
the evidence that a more precise Blueprint produces a cleaner run.

**The stub was itself a scope violation.** The harness writes it into the
Tester's worktree and the check blamed the Tester, so no task using a stub
could ever have assembled. Same class as `.nrepl-port`, reintroduced by the fix
for the previous run's finding, and caught only by running the loop again.

A src-only nREPL made `:repl-first` unsatisfiable a second way.

### 23:48 · Close both gaps the first run found

`harness.stub`: the contract as code the Tester can load. Every stubbed body
throws — a stub returning a plausible value would let a test pass against
nothing, the same failure as an unmarked synthetic number one layer down.
Written *over* an existing file it also closes the rewrite hole: one mechanism,
two problems.

`assemble!` takes architecture as a third argument, reported separately from
agent output.

### 23:38 · Run 1 · `t-01-clamp` — the first end-to-end run

§03 step 5 and readiness criterion 6, neither of which the kit had ever closed.

**The bug it found:** `changed-files` mixed two path bases — `git diff` reports
from the repository root, `ls-files --others` from the current directory — and
the existence filter then silently dropped every *modified tracked* file. An
agent editing a file it was never given was invisible. Every fixture until then
had the project at the worktree root, where the two agree; **unit tests could
not have found this.**

What worked is the other half of the evidence: gate 4 caught a Blueprint defect
rather than a Coder defect, and the Reviewer found a branch the gates could
not.

### 23:24 · Provisioning: three worktrees per task

The Tester's independence was a prompt rule with nothing behind it. Assembly
into the third workspace is a **filter**, so a file written outside a packet
cannot reach the gates at all, and it refuses loudly rather than dropping
silently.

Details that came from running it rather than designing it: `:worktree/path` is
the project root and `:worktree/git-root` is what git made, and conflating them
works until the first monorepo; a failed launch used to leave the branch, so
the next attempt died naming the wrong cause; and `.nrepl-port` was being
reported as a scope violation, invisible only because every project gitignores
it.

### 23:07 · §10 lesson 12: a rule already read is not fixed by writing it again

From using `python3` for file surgery across a session working on a Babashka
kit, against a standing instruction to use `bb`. The instruction had been read
and then not applied, which is a different failure from not having one.

**No gate could have caught it.** No `.py` file was produced and `bb gates` was
green throughout, correctly. The artifacts were clean and the process was
wrong — which is why the Reviewer reads the diff rather than the result.

No rule was added to the rule source, per lesson 3.

### 22:40 · Cut portability.md to what is still true

Six of ten entries in its coupling table no longer described anything. **A
document asserting facts about code that has since changed is worse than no
document.** 469 → 316 lines; `NOTES.md` 106 → 66.

Client facts gained a dated verification line naming the binary versions they
were read from — they rot faster than anything else here.

### 22:32 · Bracket synthetic values, and add a tokens column

Brackets rather than a sigil, because they need no legend: `[anthropic]` reads
as a placeholder wherever the row is quoted or pasted out of the table.

### 22:26 · Mark fabricated model and provider cells too

A made-up provider is the worst cell on the row — "anthropic" reads as a fact
about who served the call — and it was unmarked while the numbers beside it
were not.

### 22:23 · Make fabricated data label itself

**The incident this repository's honesty rules come from.** An example report
rendered from hand-written values was read as a record of three model calls
that had never happened. The prose above it said three times that cost and
model were unpopulated. It still misled: *a plausible number in a real-looking
frame is a measurement whatever the caption says.* The caption was accurate and
the table lied.

Rule `:label-fabricated`, and `:step/source` required on every step with no
default, so a caller who has not thought about provenance gets a validation
failure rather than a silent `:measured`. The renderer marks the row, every
number in it, **and the total** — a total is fabricated the moment one of its
inputs is.

It took three passes to get right (22:23 → 22:26 → 22:32), which is itself the
lesson: the first fix marked the row, and the row is not what gets read alone.

### 21:57 · Add `sandbox/`

Temp-directory fixtures prove the functions work; they do not prove the harness
works against a project with a `deps.edn`, a JVM, a real nREPL and four gates
that actually run.

`red/` proves the gates discriminate — five fixtures each failing exactly one
gate. **A suite that has only ever been green proves nothing.** Building them
found two bugs in the fixtures and one in the kit.

### 21:57 · Record per-step time, cost, model and serving provider

§10 has two measured lessons that nothing in the seed could reproduce. Model
and provider get separate columns because they are different facts: one slug
can be answered by several hosts, and which one answered decides the chat
template.

**What the report will not do is imply it knows more than it does.** A
mechanical step has no cost, so it renders as absent rather than zero, and the
footer states how many steps the total actually covers.

### 21:56 · Make the interactive seat harness-neutral

The kit read as a Claude Code template and was not: §05 requires the agent
verifying the Coder to run on a different model family, and the rule source
exists precisely because a client-specific rules file cannot reach one.

`AGENTS.md` became the single generated mirror; `bb rules-prompt` shipped the
prompt rendering, which had been tested, called by its own docstring *"the one
that must never be skipped"*, and had **no caller**. The rule source gained a
`:reviewer` audience — the Reviewer was first-class in `shapes/Role` and
received no rules at all.
