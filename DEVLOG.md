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

- **Squashed onto `main` (2026-09-15).** The branch `experiments/d7-d15` — 42 commits over
  `5c2df19` with this one, summarised in the 14:56 entry below — is squashed onto `main` as one
  commit and pushed, by the user's decision. The branch stays local as the history: the hashes
  this log's entries cite resolve there, not on `main`. `NOTES.md`'s register and `RUNS.md` name
  dates instead.
- **Nothing in `NOTES.md`'s register is *open*.** Row 20 (nothing tested `canonical`'s output)
  was fixed on 2026-09-15; row 1's remainder (the judgement routings) was decided the same day as a
  person's call and is *watch*. Row 2 (tier or family in D4) is *obsolete*, not to be run. Rows 3, 6,
  7, 10, 13 are `watch`; everything else is fixed, rows 14–20 included. *(This bullet listed rows 14,
  15, 17 and 18 as open through the commits that fixed them; corrected with row 19's.)*
- **Stage S1 is done** (`RUNS.md` Part 4): D19–D21 merged onto the local `stage/s1`, Step 5's
  integration tests on it (`0f13229`), every exit criterion met, accepted at Human gate #2 and kept
  local (2026-09-15 11:25). The driver was unchanged through the stage. The five driver gaps it
  found (rows 14, 15, 17, 18, 19) were fixed after it, one commit each, and D19–D21 re-recorded.
  The summary is `RUNS.md` §S1 · What the stage showed.
- **Exercised live by D20:** the `:routing` proposal beside a
  real decision (it agreed on the role, not the sources, and its Tester rule is row 17), and the
  leak check on two live Tester retries (clean both times; it has still never refused one).  D21's
  Tester was the first dispatch to read the fixed `nrepl_eval`'s `ERROR:` result, and carried on.
- **The contract-conflict prompt change stays optional.** Planned after D13's Sonnet 5 Coder
  stayed silent; D14, D15 and D17's Fable 5.1 Coders, at medium and at low, did not need it.
  Keep it in reserve for a less capable Coder.
- **The seam rule has held in every run since it changed** (D11–D18; D12–D15 and D17 are one
  task, D16 and D18 another). One Coder that validates per call would refute it.
- **Row 12, source 2 stays accepted** (02:37 below).
- **Push.** Held until the tree is in a state worth publishing.

---

## 2026-09-15

### 14:56 · What the branch amounted to

`experiments/d7-d15` is squashed onto `main` after this entry: 41 commits over two days, 60 files,
about 6,000 lines added, half of them in `harness-seed/`. The commit message points here. The
branch is kept locally as the history, and every hash the entries below cite still resolves on it.

**The harness gained**

- **A committed driver.** `bb run-loop` walks one task through the loop against real models, a
  command per step, and pauses when a role leaves a note. Every retry needs a written triage
  decision, and `continue` needs a stated reason.
- **Records that replay.** Each run's record carries its final files as gated, every red gate's
  output, every dispatch's transcript, and the cost computed from usage. Every table in `RUNS.md`
  re-renders from `runs/*.edn`, and `bb report-check` enforces it.
- **A shielded Tester.** Feedback that names the implementation is refused unless overridden, and a
  role is shown what depends on the namespace it rewrites.
- **Mechanical checks around the models.** Undeclared calls into a context namespace fail the run
  before any gate. A red gate gets a routing proposal recorded beside the human's decision. A
  transient API error is retried and counted.
- **Tool fixes the runs forced.** The REPL tool reports a failed evaluation instead of hiding it.
  Prompt caching is on and measured.

**What was run.** D7 to D21 and bake-off B1, all recorded in `RUNS.md`. D19 to D21 were the first
multi-task stage, S1: a Blueprint, integration tests and both human gates, kept on a local
`stage/s1`. One code review of the branch, four findings fixed. Two audits of the documents against
the records.

**What was decided.** The Coder and the `agy-ide` Reviewer run on Fable 5.1 at low effort. The
judgement routings stay a person's call, from the record: the spec was at fault in five of seven
retries. The D4 tier-or-family question is obsolete.

**Where it stands.** `NOTES.md`'s register has nothing *open*. What remains waits on evidence from
later runs: the leak check has never refused, the routing rule has met no real implementation bug,
the Reviewer's worth is unmeasured.

### 14:25 · Row 2 obsolete: tier or family in D4 is not to be run

Row 2 asked whether D4's Tester failed because of its tier or its family, a question D5 confounded
by changing the model and pinning the provider in one step, and D6 then answered in a third way:
the prompt lacked a stop condition. The experiment that would separate tier from family was never
run, and the user has decided it will not be: it would spend a run on a model the kit stopped
dispatching at D5, to learn nothing the profile needs. The register gains a fourth Status,
*obsolete*, for a question that no longer applies, with the reason in the row (the user's
term; the entry first said *closed*, which the register already uses for things fixed). What the row keeps is the
lesson: a run that swaps a model changes nothing else. No code changed. Fable 5.1 at medium.

### 14:17 · Row 20: `canonical`'s output is tested

D21's mutation left one survivor: `sandbox.api/canonical` returning its input passed the whole
suite, because the only test that called it checked idempotence, which the identity satisfies,
and the S1 Blueprint had left `canonical` to `api.clj`'s dependents. Two tests now say what the
normal form is rather than only that it is stable. `api_test.clj` pins examples — padded, tabbed
and newlined input comes back single-spaced, a lone operand trimmed, canonical input unchanged —
and `property_test.clj` adds a generator that disturbs every gap in a generated expression with
one to four spaces, tabs or newlines, and the property that `canonical` of that equals the tokens
joined by single spaces. Against these, D21's survivor and a trim-only `canonical` both die
(`bb .local/runs/d21/mutate.bb` over `.local/runs/row20/mutants.edn`, 2 of 2 killed, original
restored, suite green after). `sandbox/README.md`'s property table has the new row. The sandbox
fixture that every later run starts from is one deftest and one defspec bigger than S1 found it;
`sandbox/` gates green, 16 tests. The register has nothing *open*. Fable 5.1 at medium.

### 14:10 · Row 1's remainder: the judgement routings stay a person's call

Row 1 had one half open since `0ecb64d`: whether routing after a Reviewer's or triage's finding
should get mechanical help like the red-gate proposal. Decided from the record rather than argued.
Seven triggers on record led to a retry or a `continue` on a decision (`bb
.local/runs/triage/judgement.bb`, local, over `runs/*.edn`): five judgement — D7's Reviewer
finding, D8's hand probe, the notes at the pause in D15, D17 and D19 — and two the rule covers,
D12 and D20. At five of the seven the Architect amended the spec first; the exceptions were D8
(the Coder's defect, found from outside the loop) and D19 (an observation, continued). The
judgement at each point was the same question, *is the contract at fault?*, and nothing the driver
sees — gate output, a note's text, a finding — carries the answer; D20 showed both readings of
the sentence were allowed by it. What fits mechanically is already there: the pause, the leak
check over Reviewer text, the file-and-role proposal on a red gate. No rule over these seven
would have changed a decision, so none is built; row 1's Status carries the remainder as *watch*,
with the two things that reopen it. The more interesting reading of the same numbers is not about
routing: if the amend rate holds over more tasks, the judgement belongs on the Blueprint's
sentences before dispatch, not on routing after. Decided on Fable 5.1 at medium; no code changed.

### 13:50 · Row 19 names its commit; the flex price ratio and a session-model note come out

Row 19's Status now names `399addc`. The user dropped the one price ratio that named no source —
§B1's "standard lists at twice" flex — since the Tester stays on OpenAI's standard endpoint and flex
is history; the same unsourced ratio was also in `claude.edn`'s Tester comments ("half the price",
twice) and came out there too. And the Next list no longer suggests a model for deciding the merge:
the user switches this session's model as the work needs, and a committed document is not where
that preference lives.

### 12:42 · Row 19: a labelled fence no longer swallows the report after it

`bb report-check` found a block as three backticks and a newline anywhere, so a labelled block's
closing fence opened the next match; writing D20 up, its table was reported missing, and a table with
no record after such a fence would have raised nothing. Fences now start their lines, labels are
captured, and labelled blocks are consumed whole and publish nothing. `RUNS.md` parses to the same
26 reports as before, compared as data, and §D19's command block has its `sh` label back with the
check in sync — the case that failed. Three mutants; the one that survived showed the tests checking a report's key
rather than its content, and died once they checked drift. That closes the five driver gaps stage S1 found. The
Next list's "still open" bullet had gone on naming rows 14, 15, 17 and 18 through their own fixes,
and is corrected here.

### 12:33 · Row 18: a dispatch reports what it changed, and a driver that says when it changed nothing

`:files` came from git status, which lists a retry's inherited file whether or not the retry
touched it; D20's second Tester attempt wrote nothing and reported the first attempt's file, and
`check` ran over it. The runner now snapshots what git lists before the dispatch and reports only
what is new or different after (`written-since`). The driver also prints WROTE NOTHING, with the
start of the final message, when a Coder or Tester dispatch changed no file — the Orchestrator's
slip in D20 was not reading that message. Five mutants, every one killed, including the old rule
(`.local/runs/row18/`).

### 12:24 · Row 17: a red test in the task's own tests is proposed as the Coder's

The routing proposal, built from D7–D17's hand decisions, gave any failure naming only a test file to
the Tester. An assertion failure always names only its test file, so every red test was proposed as
the Tester's, where method §07 makes the Coder the default owner; D20's first live proposal was
right only by coincidence. Now the test gate's proposal is the Coder, with advice naming the other
way out as a triage decision, and the Tester keeps a test file that did not compile or failed format
or lint. Run over every recorded red gate, D12–D17's proposals are unchanged and D20's would now
disagree with the decision taken — which is what a proposal beside a decision is for. Four mutants, every one killed
(`.local/runs/row17/`).

### 12:12 · Row 15: a continue that decides nothing says why

D19 was continued on a Coder note that was an observation, and the record said `:continued` with
no reason; the reason sat in a local file. `continue` now takes `<decision.edn>`, `{:decision
"..."}` — like a retry's triage file, written before the dispatch — and refuses without it when no
`amend` or `retry` was recorded since the last pause (`decided-since-pause?`), since those carry
their own reasons. The decision goes on the `:continued` event. After an `amend` or `retry` nothing
changes, so D15's and D17's records replay unchanged; D7–D18 replay byte for byte. Six mutants: five
died at once; the sixth, `-main` dropping the decision file, survived until the command line got a
test of its own (`.local/runs/row15/`).

### 11:55 · Row 14: the record holds the bytes the gates passed, and says what gate 0 changed

The user chose to fix the stage's driver gaps on this branch, one commit per row. `record!` took
each role's files from its own worktree; gate 0 works in the gate worktree, which the gates, the
Reviewer and a merge all read. Now `record!` copies the gate worktree's bytes whenever that copy is
current — a gate run after every Coder and Tester dispatch, no refused assembly after it — into
`final/<path>`, keeps each file gate 0 changed as written in `final/as-written/<path>` and the record's
`:run/files-as-written`, and `check!` records a `:gate-0` event with the hunks of what it changed
(`git diff --no-index`, header dropped so no temporary path reaches a record). `final/` is by path
now; a flat one still reads, so D7–D18 replay byte for byte. D19–D21 were re-recorded from the
bytes copied out of their gate worktrees before teardown: D20 and D21 byte for byte, D19 with the
gated Tester file, which passes `cljfmt`, and the written one beside it. D9–D13 cannot be: their gate
worktrees are gone. Five tests; thirteen mutants, every one killed (`.local/runs/row14/`).

### 11:33 · S1's summary

`RUNS.md` Part 4 ends with what the stage showed: a per-task table, cost by role re-derived by a
command over `runs/d19.edn`–`d21.edn` (the Coder is two thirds of ~$0.94), what held, what the
stage found that single-task runs had not, what it could not show — the Reviewer's worth among
them, after three reviews with nothing to find — and where the kit stands, labelled a judgement.
Writing it corrected Part 4's intro, which still said the three tasks ran under one version:
the driver did, and `nrepl_eval` did not.

### 11:25 · S1 accepted at Human gate #2, and kept local

The user accepted the stage and kept `stage/s1` a local branch, as planned before dispatch: the
kit's `sandbox/` stays the fixture every run starts from. The branch and worktree are kept; the
stage's run directories (`d19`–`d21`, `s1`) were copied into this checkout's `.local/runs/`, checked
identical with `diff -r`. Both human gates of method §07 have now been taken in a run.

### 11:15 · S1 Step 5 — integration across the three tasks; every exit criterion met

Method §07's Step 5, the second step no run had exercised. A test namespace on `stage/s1` for
what only holds across the three merged namespaces: two passes with split bindings equal one,
`calculate` agreeing through `simplify` with bindings split at random, no variable surviving full
binding, and the stage document's §11 examples. Each property was checked over 1,000 cases before
it was written. Against six mutants across `substitute`, `constants` and `api`, run with only this
namespace, five died; the sixth — no fold into the right subtree — is unreachable from any string
the parser accepts, and D20's tests kill it. `bb gates` on the stage: 40 tests, 11 namespaces.
Every exit criterion in §11 is met. Human gate #2 has not been taken. `RUNS.md` has the section.

### 11:11 · D21 · `t-23-simplify` — green, and every prediction held

The stage's last task: `simplify` composing the two merged namespaces through `sandbox.api`, the
first dispatched task to edit a namespace rather than create or rewrite one. All five predictions
held — dependents listed, `calculate` and `canonical` kept with no note, `:calls` green over the
whole file, an edit in the Reviewer's diff, green first time. ~$0.285, 2m25s of steps. Its Tester
was the first model to read the fixed `nrepl_eval`: it called the stub, got the `ERROR:` result
naming it, and carried on where D20's second attempt had stopped — one case. It also wrote the
stage's first two `defspec`s. Seven of eight mutants died; the eighth, `canonical` returning its
input, survived the whole suite because nothing tests `canonical`'s output (row 20, open).
Merged onto `stage/s1` as `b8d02ae`: 36 tests over 11 namespaces. The stage's three tasks cost
~$0.94.

### 10:46 · D20 · `t-22-constants` — red twice, and green on the Tester's last attempt

The fix below went in, was brought onto `stage/s1` as `908a767`, and the Tester got its third and
last attempt with the amendment again, a note naming the stub and the unmatched bracket in its own
file, and the gate output. It fixed both, rewrote the validate-once test to compare by identity,
and every gate went green; the Reviewer found nothing and all eleven mutants were killed. None of
its evaluations failed, so the fixed error path went unexercised. Merged onto the stage as
`c09c536` from the gate worktree, which matched `final/` this time. `RUNS.md` §D20 has the
first live routing proposal — the Tester, for a reason its rule cannot see (row 17) — and the
replay behind row 16; the wall time, 944m57s against 4m23s of steps, is the overnight pause.

**Writing it up found row 19.** `bb report-check` reported D20's record as unpublished with the
table in place: §D19's command block had a labelled fence, and the report parser pairs fences so
that a labelled one's close opens the next block. Checked with `report/drift` on a made-up
document: a table with no record after such a fence raises nothing. The block is unlabelled now;
the parser is not changed. The Tester subtotal was also first typed as $0.149602; the record says
$0.149298.

### 10:26 · Row 16: `nrepl_eval` returns what a failed evaluation printed

D20's test gate went red on a Tester assertion that counted every `sandbox.shapes/check!` call,
including the one-node checks `expr/literal` makes on each literal it builds. Target 8 allowed
that reading (D19's Tester, on the same sentence, did not take it), so it was amended and the
Tester retried. The retry wrote nothing: its final message said the nREPL had stopped returning
results. It had not. Replaying its calls through `harness.tools/invoke` against the same nREPL
showed `clj-nrepl-eval` exiting 0 with the error on stderr — the stub's `AssertionError`, which
it throws by design — and the tool returning stdout alone, `""`. The first attempt had been
misled worse: its test file did not read (an unmatched `]`), the `require` failed silently, and
`:loaded` after it printed. It never ran a test. Gate 0 repaired the delimiter before the gates.

Three fixes were laid out — pass stderr through; that plus a flag; or talk to nREPL directly —
and the user chose the second, with the mitigations its risks called for: the full set of
`clojure.main/ex-str` prefixes rather than two (read from 1.12.5's source; a probe had already
shown "Error printing return value" missed by the obvious pair), a timeout detected on stdout
where it actually appears, a failed form worded so the rule that stops on a dead REPL cannot read
it as one, and both result paths clipped with stderr first. Writing it found the old non-zero
branch had never worked: a destructured `err` shadowed the `err` helper, so an unreachable nREPL
came back as "String cannot be cast to IFn" — and the first draft of the fix repeated the
shadowing, caught before any test ran. Nine tests; seventeen exact-match mutants, every one killed
(`.local/runs/row16/mutate.bb`). `NOTES.md` row 16 is *fixed*; D20's other two findings are rows
17 (the routing proposal's Tester rule contradicts §07 for a test assertion) and 18 (a dispatch
that wrote nothing reports the previous attempt's file), both *open*. The stage's driver is
unchanged; its tool is not, from D20's third Tester attempt on, and `RUNS.md` §D20 will say so.

## 2026-09-14

### 18:48 · Stage S1 begins; D19, and the first merge finds the record is not what was gated

The user asked for a full end-to-end run to see where the kit stands. Every run so far was one
task against an unchanged `sandbox/`, never merged, so the full run became a stage: three tasks
in dependency order, each merged by hand before the next, then an integration pass —
method §07's Steps 2 and 5, which no run had exercised. The user chose a three-task stage, the
`claude` seat, and a local `stage/s1` branch never merged into the kit. No driver change: the
driver asks git for the repository root from the run directory, so run directories inside a
`stage/s1` worktree make that branch every task's base. The stage document and Blueprint were
filled in from `skeletons/stages/`, their examples checked against a throwaway REPL composition,
and signed off at 18:19.

D19 (`t-21-substitute`) went green on the first attempt, ~$0.276, after pausing on a Coder note
that was an observation rather than a conflict; continued without amendment. Merging it from
`final/` failed the stage's format gate: `record!` copies from each role's worktree, and gate 0
had reformatted the Tester's file in the gate worktree. Merged from the gate worktree instead
(`stage/s1` `8447cc6`). Five committed records, D9–D13, carry a file their format gate cannot
have passed as recorded. Both that and the missing reason on `continue` are `NOTES.md` rows 14
and 15, left open until the stage ends so its tasks run on one driver. D19 was written up now
rather than at the stage's end, at the user's direction; `RUNS.md` gains Part 4. Its intro also
said D3–D18 cost "about $1.64"; the records sum to $1.997, which its totals already said.

### 17:19 · The `agy-ide` Reviewer moves to Fable 5.1 at low effort

`resources/profiles/agy-ide.edn`'s Reviewer was `claude-sonnet-5` with no `:params`. It is now
`claude-fable-5-1` with `claude.edn`'s Coder settings — `:output_config {:effort "low"}`,
`:max_tokens 16000`, and Fable 5.1's list prices under `:pricing` — for the reasons that file
gives. The reason for the switch is the user's: Fable 5.1 at low effort is far superior to
Sonnet 5 and Opus 5 at every effort level.

That is a judgement, and the record cannot back it or refute it yet. No run has used the
`agy-ide` profile, so no Anthropic model has ever been dispatched as Reviewer: every Reviewer
row in `RUNS.md`'s tables that names a model names `gemini-3.8-flash` (15 of them; the other 3
are the pre-dispatch stubs), and no Coder row names a Gemini model —

```sh
grep -E "^\s+[a-z]+(-r[0-9])?\s+dispatch" RUNS.md | awk '{print $1, $3}' | sed -E 's/-r[0-9]//' | sort | uniq -c
```

— and `grep -ci opus RUNS.md runs/*.edn` is 0 in every file. The one side-by-side of the
two Anthropic models is D13 against D14: one task, as Coder, where Sonnet 5 stayed silent on a
contract conflict that Fable 5.1 named. The profile's comment says the choice was not a run's.
`portability.md`'s copy of the profile and its seat table now show the new Reviewer; `bb profile
--seat agy-ide` passes.

### 16:48 · Rows 1 and 5: the driver proposes the mechanical routings and enforces the Tester's shield

Nine triage decisions were on record (D7, D8, D12, D15, D17). Three routed by rule — a red
gate in a file no role owns, a note at the pause — and every one of the three Tester retries
was shielded by hand from the Reviewer's findings and the implementation's names, once
imperfectly (D7's note still said `expr/literal`). Four options were laid out — write the rules
down, mechanise the mechanical part, a triage role, wait — and the second was chosen: build
only what the record shows being done the same way every time, leave the judgement where it is.

Two pieces. `check` records a `:routing` event on a red gate: the impl, test and dependent
files the output names, the owner that implies, the role and sources a retry would carry.
Naming the files needed one thing the plan did not foresee: a whole-suite test gate prints
`Testing <ns>` for every namespace, green ones too, and a failure's own line names the throw
site (`parse.clj:25` for D12's failure in `property_test.clj`), so a failure is attributed to
the `Testing` block above it, and that namespace to the spec's path by convention. `retry
tester` runs `leaks` over the feedback — a `:reviewer` source, an impl path, a var the impl
defines that `:interfaces` did not grant (`sigs/impl-names`), a fenced block — and refuses
unless `--allow-leak`, recording the leaks and the judgement on the `:triage` event either way.

Over the record (`.local/runs/triage/retro.bb`): D13, D14 and D17's red gates name the two
dependents and no owner, which is what D12 and D17 decided; D12 itself names nothing, its
record predating the dependents list. D8 and D12's Tester feedback is clean, D7's is refused
on the Reviewer source, and D7's findings would be refused twice over. Twenty-one mutants
(`triage/mutate.bb`); three survived the first pass — a Tester file beside a dependent and a
symbol character before the name, which got their tests, and a filter skipping absent impl
files, which clj-kondo already does, so the line was removed rather than tested. D7–D18
replay byte for byte (`triage/replay.bb`): no record changes shape until a run goes red or
retries the Tester. `method.md`'s triage stage now says what the Tester never receives.
`NOTES.md` row 5 is *fixed*; row 1 stays *partly fixed*, and says which half.

### 15:18 · Row 9: a transient error is sent again, and the record counts it

`post!` returned a 429 as a failed dispatch and the caller "owned the retry" — no caller ever
did, so on flex 3 of 4 requests inside 75s each cost a dispatch. Now `adapter/transient?`
names the errors a second request may not get (429, 500, 502, 503, 529, status 0) and
`post!` sends those again, up to four attempts in all, waiting `Retry-After` when the host
sends it and otherwise 2s × the attempts so far. A 400 — D17's "credit balance is too low" —
a 401, a refusal or a truncation is not sent again: the same request gets the same answer.
The count travels: `:retries` in `converse!`'s result and `:runner/meta`, and on the dispatch
event only when non-zero, so D7–D18 replay byte for byte.

Five tests (429 then 200; attempts exhausted; a 400 sent once; `Retry-After: 0` honoured;
retries summed across a tool loop's completions) and one through the runner. Ten mutants at
first, three of which survived: a redundant guard (removed rather than tested), the sum
across completions and the meta count, each of which then got its test. Not yet exercised by
a live rate limit — the Tester left flex before this existed. `NOTES.md` row 9 is *fixed*;
what stays open there is that a dispatch failing after four tries still fails the run.

### 14:57 · The register gets a Status column, and the stamp gets a rule

Rows 8 and 11 were closed today by rewriting their prose and flipping `Kind` to `watch`, and
the user could not see that anything had closed. `NOTES.md`'s register is now `# | Finding |
From | Status | What was done, and what remains`, on the pattern of the R1–R4 review table:
the finding stays the original gap, the status names the commit (`fixed`, `partly fixed`,
`open`, `accepted`, `watch`, `question`), and the closure text moves to the last column. Every
row's text was moved by a script, nothing dropped. The file's "Updated" stamp had also sat at
10:17 through six edits; it now carries its own rule, and the rule is in my memory too.

### 14:29 · D18 · `t-20-names` — the cache measured, and the tokens column corrected

D16's task rerun with caching on. Green first attempt, $0.198 measured, 2m14s. The Coder's
recorded usage: 10 uncached input tokens, 5,503 written to cache, 12,475 read back, 1,435 out
— of 17,988 input tokens, ten were billed at the base rate. The `~$0.143756` in the row
re-derives by hand from those counts and the profile's rates; uncached, the same usage would
have cost $0.251630: input down 60%, the dispatch 43%. Lower than the 85% input estimate
because a four-completion conversation is mostly cache *writes* at 1.25×; the reads cost
$0.003. Output, thinking included, is half the dispatch at $50/MTok. `RUNS.md` §D18.

**A defect the run exposed.** The tokens column first said 1,445: `provenance/of` counted
uncached input and output only, so the cached 17,978 tokens the request moved were invisible.
Fixed (`of` now adds cache writes and reads; one test), and the Coder step in D18's `state.edn`
was recomputed from its own event's usage before `record` — 19,423 — with the original kept
as `state.pre-tokens-fix.edn`. Older records have no cache counts and replay unchanged.

The `:calls` check ran live for the first time: 242ms, clean. Run on Claude Fable 5.1 at
medium effort.

### 14:17 · Row 11: the implementation's calls are checked against its slice

`harness.sigs` checked the slice against the source and never the other way; D8's `bind.clj`
called `expr/literal?` with no entry naming it and nothing noticed. `sigs/undeclared-calls`
now reads clj-kondo's `:var-usages` from the impl files and flags every call into a namespace
the context files define that no `:deps-sigs` entry names (`:undeclared-call`), or whose arity
the entry's argv rejects (`:arity-mismatch`); calls into `clojure.core`, libraries, the impl's
own namespace and namespaces outside the context are not its business — the last is gate 4's.
`bb run-loop check` runs it as the `:calls` step right after gate 0, cheap before expensive:
a hit records a `:calls` event with the violations, fails the run as a red gate with the calls
as feedback, and runs no shell gate. When the slice was wrong, `amend` then `check` again
recovers with no dispatch. The `:stay-inside-your-packet` rule now says a check enforces it
and to `note` a missing signature rather than call it (both mirrors re-synced).

**Retro-check** (`.local/runs/row11/retro.bb`): each of D7–D17's recorded implementations,
placed in a copy of today's sandbox source and checked against its own spec, gives exactly one
hit — D8's `expr/literal?` — and nothing else. Six tests; ten exact-match mutants, all killed
once a test for the own-namespace case was added. D7–D17 replay byte for byte. Not yet hit by
a live run; row 11 moves to `watch`.

**Prompt caching is on for direct Anthropic calls** (same commit). `adapter/request
:anthropic` sends a top-level `:cache_control {:type "ephemeral"}`, the automatic mode; a
profile `:params` entry can override it. Estimated from D17's transcript and prompts: a
10-completion Coder re-sent ~40–50k input tokens for a 12k-token conversation, so caching
should cut its input cost by ~85% and the dispatch by ~28% at Fable 5.1 rates (output,
thinking included, is the larger half). Estimate only — records before today hold no split;
the next run's `:usage` shows `cache-read` and prices it.

### 13:39 · A Coder cost, computed from usage and the profile's list prices

Every Coder dispatch since D3 has shown `—` for cost: direct to Anthropic there is no
generation record, and `harness.provenance` refused a price table on purpose — it would rot
and would read as measured (§10 lesson 11). The user wanted the number anyway, and the design
keeps both halves of that rule:

- **The table is in the profile, not the harness.** `RoleProfile` gains an optional `:pricing`
  — five USD-per-MTok rates (base input, output, 5-minute and 1-hour cache writes, cache
  reads) with a required `:source` and `:as-of`; `bb profile` refuses a price without its date.
  `claude.edn`'s Coder carries Fable 5.1's, read from
  platform.claude.com/docs/en/about-claude/pricing today: $10 / $50 / $12.50 / $20 / $0.25.
- **The number is marked as computed wherever it is shown.** `provenance/list-price` turns the
  adapter's four usage counts (`:in` is uncached input; cache reads and writes now parsed) into
  dollars; `provenance/of`'s new arity uses it only when the endpoint reported no cost, marks
  the step `:cost-source :list-price`, keeps `:usage`, and names the provider from the
  endpoint's host — `api.anthropic.com` is `Anthropic API`, a fact about where the request went.
  The report prefixes such a cost and any total containing one with `~` and adds a footer
  line saying how many steps are priced that way. The dispatch event carries the counts and
  the rates, so a record can re-derive its own figure.

Checked against the pricing page's two worked examples to the cent, in `provenance_test`.
Sixteen exact-match mutants over the new lines, all killed (`.local/runs/cost/mutate.bb`).
D7–D17 replay byte for byte: no source, no key. 230 tests.

Not backfilled: D3–D17's Coder steps hold one token total, not the split, so they stay `—`
and `RUNS.md` says so under the running total. The next run is the first with a `~$` Coder
cost; its write-up checks the figure by hand from the record's `:usage` and `:pricing`.

### 13:00 · D17 · `t-19-render` — low effort flags the conflict too; the run finishes green

Resumed after the credit top-up: `retry coder` as `coder-r2` with the same triage decision,
then `check`. Every gate passed with `property_test.clj`, the Reviewer walked all seven targets
and found nothing, and eleven exact-match mutants — D15's set, finds adapted — were all killed
(`.local/runs/d17/mutate.bb`, results in the record). The Tester needed no retry: it had seen
version 2 before the failed dispatch, which is also why the driver's red gate was red.

**The answer to D17's question is yes.** A Fable 5.1 Coder at low effort noted the contract
conflict as medium did in D14 and D15, and its transcript shows it ran `parse` on a
parenthesised string and saw `:bad-token` two turns before writing the note. It used fewer
iterations, seconds and tokens than D15's on both dispatches (10/57s/71k against 14/80s/107k;
5/40s/42k against 7/56s/64k). One pair of runs; direction consistent. The profile stays at low.

The record is 75,980 bytes, the largest yet — four capped transcripts, a red gate's output, the
files and the mutants. Wall time 26m34s includes 22 minutes waiting for credit; the steps
account for 5m34s. $0.099 measured, $0.068 of it the Tester dispatch the driver should not
have made. `RUNS.md` §D17 has the rest; the 12:34 entry has the driver fix.

### 12:34 · `continue` refuses to build on a failed dispatch — found by D17, mid-run

`claude.edn`'s Coder moved to `:effort "low"` (the user's trade after a day on medium), and
D17 is D15 rerun with only that changed. Its first half went as D15's did: the low-effort
Coder read `property_test.clj`, ran `parse` on a parenthesised string in the REPL, saw
`:bad-token`, and only then wrote the note — the transcript (row 8, a day old) is what
shows the order. The run paused, the Architect amended to version 2 with D15's feedback
verbatim, and the Coder was retried.

**The retry failed in 405ms: HTTP 400, "Your credit balance is too low to access the
Anthropic API."** 0 completions. Then `continue` did what it was told: dispatched the Tester
($0.05) against the `render.clj` the retry had never rewritten, and the test gate went red
on round-trip. `continue!` checked only that the run was paused, never that the dispatch it
followed had succeeded. Now it refuses while the last dispatch is `:failed`, names the step,
and leaves the pause standing (`last-dispatch-failed`, one test, the guard's mutant killed).
D17 resumes with `retry coder` once there is credit; the Tester saw version 2, so its tests
stand. `RUNS.md` §D17 will carry the run when it finishes.

### 12:15 · D16 · `t-18-names` — the dispatches keep their transcripts (row 8)

`converse!` already held every call and every message and `outcome` dropped both. It now
keeps `:turns` — one map per completion, the model's text and its tool calls with arguments,
results, errors and timing — on every exit, the failed one included, since the turns before a
failure are the diagnosis. `outcome` carries them as `:runner/meta :transcript`; `AgentResult`
stays closed. `bb run-loop` writes the whole transcript to `<step>.transcript.edn` beside the
packet and records a copy on the dispatch event with every string cut to 500 characters, the
cut announced (`cap-transcript`). Three new tests, one extended; nine exact-match mutants, one
of which survived until the boundary case (a string of exactly the cap) was added
(`.local/runs/row8/mutate.bb`). D7–D15 replay byte for byte: no transcript in their state, so
no key. 224 tests.

**D16 ran to see it.** A trivial new namespace, `var-names`, through all three roles: green
first attempt, $0.057, 2m10s. Every dispatch event has one turn per completion and the record
is 23,208 bytes, 8.7 KB more than D11's. The Coder's transcript shows the `nrepl_eval` behind
its *"Evaluated in the REPL"* — the kind of claim the 11:00 audit could only soften. `RUNS.md`
§D16 has the rest. Row 8 moves from `gap` to `watch`: B1, D3 and D6 stay blank, and the
capped copy is a shape, not the content.

Cap chosen by the user: 500. Run on Claude Fable 5.1 at medium effort.

### 11:43 · The rest of the documents, audited

The 11:00 audit covered `RUNS.md` §D7 on, `NOTES.md` and this file's next steps. This pass
took every other committed document except this file's dated entries and the `skeletons/`
templates. A script resolved every path, `bb` task, commit hash and NOTES row the documents
name; the pre-D7 records were compared with §Run 3–§D6 by hand. Seven claims were wrong or
stale, each fixed in place:

- `harness-seed/README.md`: the line counts (3,400 / 2,600) were 3,951 / 3,521 by `wc -l`,
  now stated with the command and date; "The six pieces" headed a thirteen-row table; the
  audience set omitted `:reviewer`.
- `portability.md`: "Five runs … all through `ManualRunner`" and "Four runs have gone
  through it" predate D7–D15.
- `RUNS.md` §Run 5 said `api_test.clj` depends on `render` and stayed green by luck. It
  requires only `sandbox.api` and never renders — the claim NOTES row 1 had already
  retracted, still standing where it was first made.
- `NOTES.md` said eighteen tasks; eighteen runs, seventeen task ids, and D3 was one role.
- `interactive-programmer.md` said `bb gates` reverts drift; it fails on it.

Pre-D7 records carry steps only, so the iteration caps and assertion counts §Run 3–§D6
quote cannot be checked from `runs/`; `runs/README.md` now says so. Everything else
checked held: every reference resolves, every per-step figure in §Run 3–§D6 matches its
record, and the branch's edits to `portability.md`, `PROVENANCE.md`, `CLAUDE.md` and
`method.md` match the code. The scripts are in `.local/runs/claim-audit/full/`. This pass
ran on Claude Fable 5.1 at medium effort, the fixes at low.

### 11:26 · The records carry the code and the red gate output

The 11:00 audit found claims that were true but checkable only in the gitignored
`.local/runs/`: D13's failing token, the docstrings §D11 and §D13 quote, the tests behind
"validates once" and "cannot fail", and B1's `defspec` counts. That was a gap in the record,
not in the documents, so it was closed first, ahead of a wider audit.

**`bb run-loop` now records two more things.** A red `:gates` event carries the failing gate's
output as `:feedback`, which is the same value `last-gate-feedback.edn` gets (`gates-event`). And
`record` puts the run's impl and test files in the record as `:run/files`, keyed by spec path. It
reads them back from `final/` rather than the worktrees, so a replay after `teardown` writes the
same record (`final-files`). Two paths with one file name are refused, because `final/` is
flat and one would overwrite the other. Two new tests and one extended test cover this, including `check!` itself on a red gate, and eight exact-match mutants of
the new lines were all killed (`.local/runs/record-gap/mutate.bb`). 222 tests.

**Backfilled.** Each of D7–D15 was replayed through the committed `record`, in a copy of its run
directory. D7–D9's state predates the driver and has no `:config`, so the copy got only its run
id. D12–D14's one red gate first got the output from their own `last-gate-feedback.edn`. Every
new record, with `:run/files` and that `:feedback` removed, equals the committed one as data and
byte for byte. B1's two passing records gained `:defspecs`, 10 and 4, counted from the test files
the candidates wrote. `bb report-check` still passes on all 20 tables, because the report reads
only steps.

**One more claim was wrong, and the backfill found it.** §D12 said the Coder's retry got the
failing gate's output. Triage sent a 370-character excerpt: a header, the two `ERROR` lines and
the test summary. Its failing tests and tokens match the full output pair for pair. The sentence
is corrected in place.

**A mistake of mine while doing this.** A first baseline "replay" of D7–D15 reported all nine
identical. For D7–D9, `record` had exited on the missing `loop.edn`, and `cmp` compared an old
`run.edn`. That result was worthless. The backfill's own assertions, which fail on a non-zero
exit, are the check that counts.

Still not in any record: the per-token price ratios in §D14, §B1 and the 09:02 entry.

### 11:00 · A claim audit of the documents against `runs/*.edn`

The code review could not check prose, so `RUNS.md` §D7–§D15 and §B1, `NOTES.md` and this
file were checked against the committed records. The at-a-glance totals and costs, the
headline sums, and every per-dispatch time, iteration, token, tool-call and cost figure
matched. Eight claims did not, and each is corrected in place with a dated note:

- **§D9** said ten mutants covered every target. They covered five of seven; targets 1 and 5
  had none.
- **§D11** said `paths-to` was within a millisecond of one `check!` at every size. That holds
  with the name absent; with the match at the bottom it is 3.8ms above at 800 nodes.
- **§D13** said the test gate's output is in the record. `runs/d13.edn` has only the failure;
  the output, and P3's `"(z"`, stayed in the run's gitignored directory.
- **At a glance** headed its time column "Wall". It was each report's steps total, which
  leaves out triage by hand: D7's is 17m31s against a wall time of 20m30s. It is now "Steps".
- **§B1** said the Tester failed or capped in every dispatched run since D4. D5's did neither.
- **`NOTES.md` row 12** still said the seam rule had two samples, and the list above said it
  had held twice. D13–D15 held too, on the same task as D12.
- **§D14, §D15 and `NOTES.md` row 1** said the Fable Coder confirmed `parse`'s failure in the
  REPL, as the 09:09 entry below does. Its summaries report the failure but do not list it
  among what they evaluated, and with no transcripts (row 8) nothing shows it. The three
  documents now say it reported it; the entry is left as written.
- **§D12** said 1m41s was triage by hand. That was wall time minus the steps. The red gate to
  `coder-r1` starting is 1m39s.

Some claims are true but rest on no committed record: D13's and D11's docstring quotations
and B1's `defspec` counts are in `.local/runs/` only. The per-token price ratios in §D14,
§B1 and the 09:02 entry name no source. The audit's scripts are in
`.local/runs/claim-audit/`. The at-a-glance row labels now link to their sections.

### 10:17 · A review of the branch, and its four findings fixed

Before a stable point, the uncommitted work was committed as a checkpoint on a local branch,
`experiments/d7-d15` (`4e6c4eb`), so that `/code-review high` could see untracked files
through the branch diff. Four bugs came back, all in this branch's new code and none tested.
Each was checked against the code, triaged *fix now* (`NOTES.md`, "Code review of
`experiments/d7-d15`") and fixed in its own commit with a regression test:

- **R1 `b2be718`** — `sigs/definitions` matched files by bare name, so two `core.clj` files
  collapsed and a valid spec could be refused. Matched by relative path now.
- **R2 `bb1bd7a`** — a task's own test file could be listed as its dependent. `sigs/task-dependents`
  leaves it out, and `start` uses it.
- **R3 `353b983`** — the Tester's stub went stale after an amendment. `amend` regenerates it
  when the slice changed.
- **R4 `864ccbd`** — a failed `start` orphaned worktrees and could not be restarted. Sessions
  are saved as they are provisioned, and `teardown` of a run that dispatched nothing resets it.

The `start` tests replace provisioning, packets and dispatch, so they run with no network.
Building them turned up two mistakes of mine, both in the test fixture rather than the fixes: a
missing `first-packet` replacement meant `start` threw before dispatch, and three tests failed
for that reason until it was read, not guessed. Across the four fixes, every mutant that reverts
or weakens one fails a test. R4 was also checked live against real git: a refused start,
teardown, start again, teardown — no branches or worktrees left. 220 tests.

### 09:31 · D15 · `t-17-render` — the note pause, live

D14 rerun with the note pause as the only change; its spec was checked equal to D14's as data.
All four predictions held. The Coder noted the conflict again. The run paused before the Tester.
From the note alone, the Architect amended to D12's version 2 in 35 seconds and retried the
Coder with `:architect` feedback. `continue` went on to the Tester, one gate run, which passed,
and the Reviewer. The Tester was never retried, because it first saw version 2. Eleven mutants
over all seven targets were killed, including the dependency mutant that survived in D12.

Against D12, which reached the same final contract: 1 Tester dispatch where D12 had 2, 1 test
gate run where it had 2, 0 red gates where it had 1, and $0.070 of Tester cost against $0.139.
That comparison is uncontrolled — D12 had a Sonnet 5 Coder and no dependents shown — so it
shows the mechanism end to end, not a typical saving.

### 09:20 · `bb run-loop` stops where a role left a note

D14's Coder noted exactly why the contract would break `property_test.clj`. The driver went on
and dispatched the Tester and ran the gates into that failure. Now `start`'s sequence pauses
after any dispatch that leaves notes: after the Coder, before the Tester, and after the Tester,
before `check`. It records a `:paused` event carrying the notes, prints them with the commands
to use, and dispatches nothing more. `bb run-loop continue` resumes with the pending step and
may pause again; in between, a human can `amend` and `retry`. `:pause-on-notes? false` in
`loop.edn` turns it off, and it is on by default.

This is the first piece of triage, and it is deliberately not automatic. The loop still decides
nothing about a note: it only stops being the thing that ignores one. The decision logic —
`pause?` and `next-step` — is pure and tested. The sequence itself is tested with the
dispatches stubbed: a Coder note stops before the Tester, `continue` goes on to the Tester and
the gates, a Tester note stops before the gates, off means off, and `continue` on an unpaused
run is refused. Seven mutants fail tests. On D14's recorded Coder result, `pause?` is true,
with the Tester next, and D7–D14 still replay byte for byte. It has not paused a live run yet.

### 09:09 · D14 · `t-16-render` — a Fable 5.1 Coder names the conflict

D13 rerun with only the Coder's model changed, to Claude Fable 5.1 at medium effort. The spec
was checked equal to D13's as data (but for the task id) before dispatch. **The prediction D13
refuted held.** The Coder confirmed in the REPL that `parse` rejects parentheses, and left a note
naming both `property_test.clj` properties that would fail and the two ways out. It then
implemented the contract as written, and said so. P1, P3, P4 and P5 held too: exact
dependents, the gate red as the note foretold, validation once, and no refusal. It was not
retried; the question was answered.

One pair of runs, and "only the model" means the Coder role's model *and* its settings. The
Fable Coder took 58s, 7 iterations and 55k tokens where Sonnet took 22s, 5 and 32k, and its cost
cannot be seen. What D14 exposes next is not about models: the note reached the run log and
nobody else, and the loop went on into a failure it had already been told about.

### 09:02 · The Coder is Claude Fable 5.1 at medium effort

`claude.edn`'s `:coder` moved from `claude-sonnet-5` to `claude-fable-5-1`, with
`:params {:output_config {:effort "medium"} :max_tokens 16000}`. The family is unchanged
(Anthropic), so verifier independence is too. `agy-ide.edn`'s Coder is Gemini and was not
touched. Three things about Fable 5.1 shaped the change:

- **Thinking is always on and counts against `max_tokens`.** The adapter's default was 4096,
  so 16000, the non-streaming ceiling the API guidance gives.
- **A 200 can be a refusal or a truncation.** `parse :anthropic` read every `stop_reason` but
  `tool_use` as a finished answer. `adapter/error` now returns an error for `refusal`,
  carrying its category and explanation, and for `max_tokens`. `converse!` ends such a
  dispatch `:failed`. Four mutants fail tests.
- **No fallback model,** by the user's choice. The API can re-route a refusal to another
  model, which would put an answer from a model the profile does not name into a record that
  says which model answered.

Checked live through the committed profile, about $0.05:

- A two-turn `read_file` round trip returned the token; effort and `max_tokens` were accepted.
- A three-turn probe returned `thinking` blocks on both tool-calling turns, replayed them with
  no 400, and computed the file name correctly.
- A real Coder dispatch through `api-runner`, with the full rules prompt, rendered as
  `claude-fable-5-1`, 2,868 tokens, cost "—".
- **The new refusal path fired on its first live chance.** The first version of the thinking
  probe was worded as "decoy files, only one holds the answer". The cyber classifier refused
  it, and the dispatch failed with the category and explanation. The old code would have
  shown an empty "done". The plain rewording went through. Whether real tasks draw refusals
  is unmeasured (`NOTES.md` row 9).

Cost is the other change nobody will see. Fable 5.1 lists at five times Sonnet 5's per-token
price, and Anthropic direct reports tokens, not cost, so the run report's Coder cost stays "—".

### 08:28 · D13 · `t-15-render` — shown the dependents, and did nothing different

This was the test of 08:18's change with a model: D12's attempt 1 rerun, identical except that
the dependents were in every packet. P1, the dependents computed exactly, held. P3, the test gate
red in `property_test.clj`, held. P4, validate once, held. **P2 was refuted: no role flagged
the conflict.** The telling detail is the Coder's docstring, which copies "the inverse of
sandbox.parse" from the original `render.clj`. It read the file that explains round-trip, had
the dependent in context and an instruction to keep it working, and implemented the conflicting
contract silently.

Deliberately not retried. The question was answered, and the retry would be D12's amendment
again. It is recorded as failed, like D4. The finding narrows row 1 from "roles are not shown
their dependents", now fixed, to "roles are not told what to do when the contract and what they
were shown disagree". The `note` tool's triggers do not cover it.

### 08:18 · A rewrite is shown what requires it

`NOTES.md` row 1, after D12 met it. `sigs/dependents` asks clj-kondo's analysis, which
`harness.sigs` already runs, which files require a namespace the task's `:files/impl`
defines. It returns direct dependents only, from `src` and `test`, never the implementation
files themselves, and nothing at all for a namespace not yet written. `sigs/with-dependents`
appends them to `:files/context` after the Architect's entries. `bb run-loop start` computes
them once, from a worktree no role has touched, records them as a `:dependents` event, and
`effective-spec` applies them to every packet, while `spec.edn` stays the Architect's own. They
go into context, not target: a dependent is kept working, not edited. The Coder is told that,
and the Reviewer is told to check them.

Checked: for `sandbox.render` it returns exactly `api.clj` and `property_test.clj`, the file
D12 broke. For D11's new `paths.clj` it returns nothing, and all three packets built from
D12's version-1 spec now list both. D7–D12 still replay byte for byte. Eleven mutants were
tried. Ten fail tests. One is equivalent and survives: clj-kondo reports a missing scan path
as a finding, not an error, so skipping it changes nothing. Two of the ten came from gaps the
first round exposed. My multi-file fixture had no implementation file requiring another, and
no test checked the new instruction text; both fixed.

Not yet shown: that a model given the dependents does anything different. That needs a
rewrite run.

### 08:09 · D12 · `t-14-render` — the first dispatched rewrite

This was the first time a model was handed existing code to change. The dependents of
`sandbox.render` were deliberately left out of every packet, and the spec said so before
dispatch. As predicted, attempt 1 met contract version 1 and turned the test gate red in
`property_test.clj`, a file no role owned, and no role mentioned the dependents. That is row 1,
meeting a model for the first time. The whole-suite gate is the safety net that caught it.

Triage made it the Architect's, and ran that path for the first time: amend `spec.edn` to
version 2 with `bb run-loop amend`, then retry both roles with `:architect` feedback. Version 2
parenthesises only right operator operands, so everything `parse` produces renders unchanged.
Both roles converged, and `property_test.clj` passes 4 of 4 against the final `render`.

Two things found checking it. The Tester's test that `render` does not require `parse` can
never fail, and gate 4 enforces that target instead (`NOTES.md` row 13). One of my mutants was
invalid: it was killed by a compile error, not a test. It was replaced, and the record says why.
Also fixed in passing: row 1 had claimed `api_test.clj` depends on `render`, and it does not.
The seam rule held for the second time.

### 02:47 · D11 · `t-13-paths` — the sharpened seam rule held

This was the test of the 02:32 rule change: a recursive task that rebuilds no tree, run
through `bb run-loop`. The Coder validated once and recursed through a private
`paths-to*`, and its summary cites the rule. `paths-to` measured linear, 11.6ms at 800
nodes, against one `check!`'s 11.2ms; D10's `measure` took 3,780ms. Every role went green
first time for $0.062. Twelve mutants covered all seven targets and all were killed.

The mutants ran through a new runner, `.local/runs/d11/mutate.bb`. It applies each mutant
as an exact literal string and refuses any that does not match exactly once, so D10's
regex-hit-the-docstring mistake cannot happen silently again. One run is one sample, so
`NOTES.md` row 12 says "supported, not established".

### 02:37 · The constructor's cost is accepted, for now

Row 12's source 2 is `sandbox.expr/binary` validating the whole subtree on every rebuild, which
makes any rebuilding task quadratic no matter what its Coder writes. It stays. For a task of its
own: it is the last quadratic source, and rewriting a namespace eight others require would
exercise row 1. Against: fixing it means weakening a deliberate design, validation at
construction, for speed that sandbox inputs never need. It would also move the baseline D1–D10
were measured on. The deciding point was D11. D11 exists to test the sharpened seam rule, and a
task rewriting `sandbox.expr` would test two things at once, answering neither. So D11 uses a
recursive task that rebuilds nothing, and this is revisited if a run ever needs linear rebuilds.

### 02:32 · The seam rule says "once"

`:shapes-are-the-contract` now tells the Coder to validate at the seams *once*. It says a
function recursing into itself crosses its own seam on every call, and to validate at the
entry and then recurse through a private helper. Both mirrors were regenerated. The Coder's
prompt carries the new text and the Tester's does not, which is correct for the rule's
audience.

The question asked was whether a *Reviewer* rule would help, and the answer was: not much.
A Reviewer rule makes the finding more reliable, but in D8 and D10 triage chose not to act
on this finding, because no property target mentions cost, so a more reliable finding would
change no code. It also acts late: the pattern starts with the Coder, which already had
"validate at the seams" and `check!`'s own "Seams throw; interiors assume" open, and
validated inside the recursion three times anyway. Method §10 lesson 12 says a rule already
read is not fixed by writing it again. What those two lacked was specificity, so the change
adds that rather than another copy.

It is a prompt change, and §10 lesson 3 says to test one against a live run. D11 will. Row 12
records it as unverified.

### 02:25 · D10 · `t-12-measure` — the committed driver works, and row 12 had two sources

The first run through `bb run-loop`, and the driver's first dispatch. It worked end to end,
with no hand edits: `start` snapshotted the config and profile, `mutation`, `record` and
`teardown` produced `runs/d10.edn`, and the report renders from it. Every role went green on
its first attempt for $0.053, the Reviewer walked all six targets, and ten mutants were all
killed. Two had first matched a docstring copy of the code; they were re-run on the code
line and the record says so.

The task was picked to keep row 12 out, since it rebuilds nothing, and it did not.
`measure` validates at every recursive call and measures quadratic. Validating once makes
it linear: 3,780ms becomes 10ms at 800 nodes. Timing D8's `bind` both ways separated the
two sources. The per-level `check!` is fixable in-task; the constructor's validation is
not, and `bind` paid for both. That falsifies three sentences written after D9: that D7's
retry "removed nothing", that D8's per-level check "does not" cause the cost, and that "no
task can fix it". All three are corrected in place in `RUNS.md`, marked as corrections,
and row 12 is rewritten. The D9 entry below is left as it was written.

### 02:13 · The driver is committed: `bb run-loop`

`harness-seed/dev/run_loop.clj`, from the script D7 introduced and D8 and D9 carried
forward. The changes from the throwaway:

- **Paths out, run directory in.** Everything hardcoded moved into `loop.edn`: the run id,
  the profile, the project subdirectory, the architecture files, the worktree directory, and
  optionally the gates and nREPL command. The run's spec, state and outputs live beside it.
- **The profile is read once, at `start`, and kept in `state.edn`.** The scripts re-read it
  every command, and during the Tester switch that nearly moved a role to a different
  model between attempts.
- **The pure half is separate and tested.** Config resolution refuses a missing `:run/id`
  or `:profile` instead of inventing one. The step names, the retry cap, and the dispatch
  event are pure functions, and so is the run record, which still stops the clock at the
  last dispatch or gate. Five mutants all fail tests. Refusals throw, and `-main` turns
  them into an exit code, where the scripts called `System/exit` mid-function.

**Verified without spending anything:** D7's, D8's and D9's `state.edn`, fed to the committed
`record`, reproduce `runs/d7.edn`, `d8.edn` and `d9.edn` byte for byte. That replay found a
bug first: `context` asked git for the repository root on every command, so a run
directory outside a repository could not even be recorded. Now it asks only when there is
no state yet, and says so when it cannot. **Not yet verified:** `start` and `retry` against
real models. Their calls are the ones D7–D9 made, but this wiring has not dispatched; D10
will.

It is still not the orchestration loop. A human routes every failure, so `README.md`'s
"deliberately left out" stands, and the section now says what is here instead.

### 02:02 · D9 · `t-11-rename` — the property targets reach everyone, and it goes green honestly

This was the first run after row 4 closed, with every decision in `:property-targets` and
none copied into the title. Both predictions written into the spec held. No role was
dispatched twice, which last happened in D5. And the Reviewer went through all seven
targets against the diff, which no earlier Reviewer could have done. Its "no findings"
was then checked: ten mutants, at least one per target, all killed, including sequential
renaming and `m` validated before `e`. The whole run cost $0.058 and took 2m11s.

Timing the result found something about the *sandbox*, and a claim `RUNS.md` had to take
back. Rebuilding a tree through `sandbox.expr/binary` is quadratic, because the
constructor validates its whole subtree. D9's `rename` and D7's final `fold` both measure
~4× per doubling. So D7's retry, credited with fixing an O(N²) check, fixed nothing, and
D8's Reviewer was not missing a fixable defect. `RUNS.md` §D7 and §D8 carry dated
corrections in place, and `NOTES.md` row 12 is rewritten from a Reviewer-consistency
watch into the gap it actually is.

### 01:53 · Row 4 closed: the property targets reach every role

`:property-targets` moved from `TesterPacket` to `PacketBase`, and from `tester-packet`
to `packet/base`, so the Coder's and the Reviewer's packets carry them too. Now the
Coder's instruction says to satisfy every entry, and the Reviewer's to judge whether the
implementation honours each one. `Feedback` gained `:architect`, so an amendment like
D7's can be announced rather than left for a role to notice.

Why this and not a field on the slice: the targets were already the right field, in
the right words, and in the right spec. They were attached to one role. `method.md` §05
already said Coder and Tester derive "independently from the same written contract";
the harness just was not handing one of them the whole contract. The Tester's
independence is unaffected. What makes tests independent is an implementation-free
context, and the test that asserts that stayed green.

The test that matters most checks the prompt, not the packet. A property target has to
reach the Coder's *model*, not just the map, which is the same lesson as D3's
deliverables. Four mutants were tried, one per change, and all four fail tests: `base`
dropping the targets, the schema no longer declaring them, `:architect` removed, and the
Coder's instruction reverted. Upstream still attaches them to the Tester alone, so this
is a `PROVENANCE.md` divergence and a back-port item.

### 01:39 · D8 · `t-10-bind` — green, reviewed, and wrong about its own boundary

The first loop run on `gpt-5.6-sol` as Tester, and it is fast: 58s and 8 iterations on
the first attempt, where D7's first attempt took 5m49s. Of those 58s, 47.3s was the
model. Attempt 1 went green, the Reviewer found nothing, and the implementation broke
property target 6 on all five invalid inputs tried, including a StackOverflowError on
`nil` and malformed `:vars` silently turned into valid literals. The one mutant that
survived deleted the validation.

The reason is row 4, wider than D7 showed. **Only the Tester's packet carries
`:property-targets`**, so neither the Coder nor the Reviewer ever saw target 6. The
workaround of copying decisions into the title missed that one.

Triage needed a voice, so `Feedback` gained `:triage`: a test, and a mutant that fails it.
Both roles retried and converged in under a minute each: all probes correct, 8 of 8
mutants killed. Two things were recorded rather than retried: the retry re-validates at
every recursion, the O(N²) shape D7's Reviewer caught and D8's did not (`NOTES.md` row 12);
and the Coder called a signature outside its slice, which nothing checks (row 11).

The driver's first write of the attempt-2 mutation record carried attempt-1's
substitution text for three mutants. It was corrected before `runs/d8.edn` was taken.
Evidence that says what ran, and not what nearly ran, is the point of the record.

### 01:20 · The Tester moves from flex to the standard endpoint

Both profiles' `:tester` now pin `:only ["openai"]`. Flex failed on capacity, not on
quality: 3 of 4 requests refused inside 75s and 4 of 5 dispatches in a row, each an
upstream 429, while the standard endpoint answered every request. A Tester that fails
for OpenRouter's capacity would make D8 measure the wrong thing.

**`"openai"` is also the provider's name**, so it was worth checking what the pin
selects before relying on it. The error for this morning's made-up tag listed the
model's providers as "openai, azure", with no tags. Six parallel requests through
`:only ["openai"]`, plus the one this morning, all reached the same endpoint id
(`a54c5de0…`) at service tier `default`. Their cost matched standard pricing: 24 in and
5 out came to $0.000098, which is $2/M in and $10/M out. That is observation, not a
guarantee that flex and fast are excluded, and the profile comment says so. If routing
ever picks another tier, the report's provider cell will show it.

The end-to-end check through the committed profile succeeded on its first attempt, with
provider `OpenAI` and tier `default`, rendered as plain `OpenAI`. `NOTES.md` row 9 is
narrowed to what stays open: nothing retries a rate limit, for any role.

### 01:13 · The Tester is gpt-5.6-sol on flex; a 200 can be an error

**The switch.** Both shipped profiles' `:tester` now name `openai/gpt-5.6-sol` with
`:only ["openai/flex"]`, on B1's result. Their comments were rewritten so each sentence
is true again. "This model has exactly one provider" no longer was, and "the Tester is
not the cheap seat" became "choose the Tester by its tests, not its price", which is
what B1 did. `bb profile --seat claude` and `--seat agy-ide` both pass. `portability.md`'s
quoted profile and seat table follow.

**The tier is in the report.** Flex and standard both report provider "OpenAI", so a
flex run's table would have read like a standard one. `:runner/meta` carries
`:service-tier`, `RunStep` accepts `:step/service-tier`, and `report/dispatch-step`
writes it only when it is known. The provider cell reads `OpenAI flex` for any tier but
`default`. The 12 published reports carry no such key and re-render unchanged. Five
mutants all fail tests.

**The first dispatch through the switched profile found a harness bug.** It came back
`:done` in 716ms, with no text, no tokens and no model, and its generation record never
appeared. The raw body was **HTTP 200** holding `{"error": {"code": 429, ...}}`:
OpenRouter streams keep-alive whitespace before it knows the outcome, so an upstream
rate limit arrives inside a success. `adapter/error` read only the status. It now treats
an `error` in the body as an error, and takes the body's code when it is a number. A
status-only mutant fails six tests. Before this fix, a rate-limited Tester in a real
loop would have been reported as a model that produced nothing, and assembly would
have refused it by name, blaming the model for OpenRouter's capacity.

**And flex is rate-limited, intermittently.** Four tiny flex requests over ~75s: three
429s and one answer. The standard `openai` endpoint answered at once. The end-to-end
check through the committed profile needed five attempts, and on the fifth rendered
`openai/gpt-5.6-sol-20260709 · OpenAI flex · $0.001618`. What to do about it is not
decided here — `NOTES.md` row 9.

### 00:51 · B1 · three Tester candidates, judged by their tests

Smoking a model only shows the plumbing works; D4's `deepseek-v4-flash` would have
passed that. So each candidate — `gpt-5.3-codex` (Azure), `gpt-5.6-sol` (OpenAI flex),
`glm-5.3` (Z.AI) — got one real Tester dispatch with the identical packet: D7's amended
contract, a first attempt. Its tests were then gated against D7's green `fold.clj` and
run against D7's eight mutants. The reference went in through `assemble!`'s
architecture argument, which exists for files that belong to no role.

Both OpenAI models passed every gate and killed all eight mutants, and both wrote
generative properties, which `gpt-5.2-codex` never did. `gpt-5.6-sol` on flex took 9
iterations and $0.035; `gpt-5.3-codex` took 21 and $0.175. `glm-5.3` capped at 24 having
written nothing, and assembly refused it by name. Why cannot be recovered: nothing keeps
a dispatch's transcript, which is `NOTES.md` row 8.

The same records confirm 00:33's fix on real dispatches. The summed fetch time was
158s, 68s and 183s; the loop waited 7–9s, once. `RUNS.md` §B1 has the table and the
reports, and `runs/b1-*.edn` the records. No profile file was changed.

### 00:33 · The loop stops waiting on bookkeeping

**Why the Tester is slow, measured.** In D7 the Tester took 21.8s an iteration and
the Coder 4.7s, and the Tester sent fewer tokens per call. `converse!` fetched the
OpenRouter generation record synchronously after every completion. That record is
written asynchronously and takes seconds to appear, and the Coder, going direct to
Anthropic, has none to wait for. Until this change nothing timed anything inside a
dispatch, so that stayed an inference.

It is measured now. Steps carry `:ms/completion` and `:ms/provenance`, and tool calls
carry `:ms`. `provenance/of` keeps `:reasoning-tokens`, `:generation-ms` and
`:service-tier`, and `:runner/meta` sums them. A two-completion tool round trip against
D7's Tester (`gpt-5.2-codex`, Azure) and Reviewer (`gemini-3.8-flash`, Vertex), run
twice each:

| | wall, before | completion | waiting on the record | tools | wall, after |
|---|---|---|---|---|---|
| Tester model | 21.4s, 17.8s | 2.6s, 2.9s | 18.8s, 14.9s | ~1ms | 11.4s, 10.9s |
| Reviewer model | 20.6s, 19.2s | 3.1s, 3.4s | 17.5s, 15.8s | ~1ms | 9.2s, 9.9s |

**6.8–10.5s of waiting per completion — 84–88% of the round trip.** Nothing in the loop
reads a generation record, so each fetch now starts in a `future` the moment its
completion returns, and one helper derefs them all on every exit: the answer, the cap
and an API error. The error exit matters because a failed dispatch has still paid for its
earlier completions. What remains at the end is one wait for the slowest pending record,
not one wait per iteration. Steps keep their shape and their order, and the existing
agent and runner tests pass unchanged. Two new tests fail under the two obvious mutants:
deref inside the loop, and no deref on the error path.

**What it should be worth on a real dispatch — an estimate, not a measurement.** At
~8.4s saved on every completion but the last, D7's Tester would have lost ~2m of its
5m49s, and its retry ~3m of 8m41s. That leaves ~14s an iteration of model time and tools
that the probe cannot explain: its prompts are trivial and produced no reasoning tokens.
D8 will show it, if its driver records the new fields.

### 00:15 · Two Tester candidates smoked, no profile touched

`openai/gpt-5.6-sol` and `z-ai/glm-5.3-flash`, through `harness.adapter` and
`harness.agent/converse!` with role maps built in the script. Each got a plain
completion, plus a tool round trip: `read_file` on a file holding a token nobody could
guess, which the model had to repeat exactly. Both passed every probe, with the token
returned exactly and arguments intact, in 2 iterations. The whole smoke test cost
$0.00064. The results and raw generation records are in `.local/runs/smoke-tester/`.

**`:only` accepts an endpoint tag, and validates it.** `["openai/flex"]` was served at
exactly half the standard endpoint's cost for identical native tokens (21 in, 5 out),
which is the listed flex discount. A made-up `["openai/no-such-tier"]` was refused
with a 404 rather than quietly served.

**The generation record tells flex apart; the harness drops it.** Of 45 fields, the
tier shows in `:service_tier` (`"flex"` against `"default"`) and in
`:provider_responses[].routed_service_tier`. `:provider_name` is `"OpenAI"` for both,
and that is the only field `provenance/of` keeps — so a report of a flex run would
look like a standard one.

**Unpinned GLM changes host from call to call.** Its plain completion went to Parasail
(fp8), and both completions of its tool probe went to Z.AI. It has 26 endpoints,
quantised from fp4 upward: D4's confound, available again on request.

### 00:00 · The step column sizes to its steps; a count nobody could re-derive

D7's report put `reviewer-r1`, eleven characters, in a ten-character column. Run 4 made
the same mistake in the kind column, and the fix then was to derive the rule from the
header — which left the column widths themselves as literals. Step names are data, so
that width is derived now, with ten as its floor so every table published before it
re-renders unchanged. `report-check` confirms that it does.

Updating the counts for D7 found one that could not be updated: `CLAUDE.md` said
`RUNS.md` published *22 cost and token figures*. Counting the `$` amounts and token
cells in the eight rendered reports gives 15 and 21. No reading that was tried gives
22. The sentence now names `bb report-check` for the count and types no number, and
`NOTES.md`'s *twenty-five findings fixed* went the same way. The 22 is left standing
in the 14:10 entry below, which is history; this is the correction.

## 2026-09-13

### 23:33 · D7 · `t-09-fold` — the return channel meets a model

The first run to offer the `note` tool and the first to call `packet/for-retry`, which
were `NOTES.md` rows 4 and 5 and are now answered. The contract's silence on `:div` was
deliberate; nothing else was.

Attempt 1 went green with a fold that throws on `(div 7 2)` and tests that checked no
value. The Reviewer found the gap and left the first note any model has left. Triage
was by hand and written down before each dispatch: amend the contract, finding to the
Coder, note (not finding, which quotes code) to the Tester. Both converged, and all
eight mutants of the second attempt die.

The prediction written into the triage file was wrong. It expected the amendment to
reach both roles unannounced; it never reached the Coder, because `coder-packet` does
not carry `:property-targets`. That is `NOTES.md` row 4 now. `RUNS.md` §D7 has the rest,
and `runs/d7.edn` is the first record to carry `:run/events`, so the notes, feedback and
triage decisions it quotes can be checked.

### 23:30 · The Reviewer is shown the files the task created

Found writing D7's driver, before anything was dispatched. Every driver since D4 built the
Reviewer's diff as `git diff HEAD`, which does not list an untracked file — and the loop
assembles new namespaces, so everything a task creates is untracked. D5's and D6's
Reviewers were given the `layers.edn` change and nothing else. Their replies were not
kept, so what they actually reviewed is not recoverable.

`provision/review-diff` intent-adds before diffing, so a driver cannot get this wrong
again by writing the obvious command. Its test fails with the intent-to-add removed.

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
