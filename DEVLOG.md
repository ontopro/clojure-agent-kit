# Devlog

What changed, when, and why — newest first. A running record, appended to as
work happens.

The documents around it each keep one job, and this one does not repeat
them: [`NOTES.md`](NOTES.md) is what is still open *now*, and
[`portability.md`](portability.md) is the standing design analysis. What lives
only here is the **sequence** — the order things were tried in, and every place
a later change corrected an earlier one. That is the part no other document
keeps.

**This log starts on 2026-09-16, when the loop was rebuilt.** The entries before
that date - the extraction, the first runs by hand, the first bake-offs - are in
this file's history on `main`, and nothing here cites a run id or a register row
from them: a reference a reader cannot resolve is worse than none.

**How it names things.** The KIT was tried by building projects with it. They are
named here by their order - the first project built with the KIT, the second, and
so on - and their records are theirs, not this repository's; so are the records
of the KIT's own early runs (`a1`-`a4`). Figures measured on them are not quoted,
because nothing here can re-derive them: what was learned is said in words. The
development branch reached `main` as one commit, so its commits are not cited
either; an entry's date is its reference.

---

## 2026-10-03

### A run's branches are named by the run, so two runs of one task can exist

Row 83. The loop's identity is `loop.edn`'s `:run/id` - one run directory each, unique by
construction - but the branches and worktrees a run provisions were named from the spec's
`:task/id`, which every run of a task shares. The first real project ran a second Tester on the
same spec as a trial, with its own run id and profile: the dispatched-roles bake-off that row
46 has waited for, done by hand. The second run stopped at provisioning on the first run's
branch, with git's one useful line left in the exception's data where the stop did not print
it; then `teardown` of the failed run, deriving names from the task, reported the first run's
branches absent while they stood. The project gave the trial its own task id and went on.

`provision!` takes `:run/id` and names the branch and the worktree `<run-id>-<role>`; the
session records its branch; `teardown` and `merge` read it from the session, or derive it from
the run id when a session is gone; with no run id the task id names them, so the tests and any
session from before are unchanged. A git failure's message now carries git's words. Tests: two
runs of one task provisioned side by side, the task's name when no run id is given, and the
message on a branch that exists.

### `bb reprice` fills a priced step's provider and tokens too

Row 80's residue, and a sentence of this log's own that was wrong. The entry below said
`bb reprice` on an older record "fills what the old one lacked"; the command selected steps
with no cost, so a step priced by the old command - a cost, no provider, no tokens - was never
looked at, and the project's two published tables kept *by provider unknown* after a reprice
that answered "nothing to reprice". The claim came from the test fixture, not from the command
run on a record of that shape - the failure `CLAUDE.md` names, in the KIT session's own
message to the project.

`reprice/unpriced` takes a dispatch step with ids and no cost, or no provider, or no tokens;
`reprice-step` fills only what is missing and leaves a cost the step has as its own source;
the line says what was filled. Test: the old shape is a target, its cost untouched, its
provider and tokens filled.

## 2026-10-02

### The rounds a dispatched role may take are the project's to set

Row 82, the fourth finding of the first real project's first day, and the one that stopped a
task. A dispatched role works in rounds - one completion, one tool result, again - and the
harness stopped a role after 24 of them, a number the seed carried with no reason recorded.
The runner's docstring said its options reach `converse!`; the loop built the runner with none;
so nothing in a plan, a profile or a `loop.edn` reached the cap. The method calls the retry cap
a project's own policy, and this was a second cap beside it, fixed in the clone. A Tester on a
contract of fifty targets finished on its 24th round, then ended at the limit on its two
retries - once having written nothing, a failed dispatch - and the retry cap counted each, so
the task stopped `capped` one assertion short of green. The project counted the rounds by
hand from the generation ids and kept the table in its stage document.

`:max-rounds`, optional, per role, in the profile's role block; `bb profile` checks it through
the schema; the API runner passes it to `converse!`, and the default applies where the profile
says nothing - the default's comment now says it is the harness's and not a policy. The record
keeps each dispatch's rounds (`:step/rounds`, from the runner's iteration count) and the limit
the role ran under (`:run/roles`), and the report prints a *Dispatch rounds* line - `tester 24
of 24, AT THE LIMIT` - only when a step recorded its rounds, so every published report
re-renders unchanged. The shipped example sets no number and says why; the plan template's §13
table has the row beside the retry cap. The number itself is the project's to measure.

### A reading keeps what a late cost is fetched by

Row 81. The three reading acts - the plan review, the Blueprint review, the spec review - are
one completion each through the same `converse!` the dispatches use, and on the day the
generation records lagged, each wrote `:cost nil` with nothing beside it: no id, no endpoint.
The dispatches had kept their ids since the fifth build for exactly this; the readings had
been written before that and never caught up. The person took the three figures off the
account balance, and said so in the plan's spend sheet.

`agent/call-record` is what a reading records about its call - the model as served, the cost,
the completion ids, the endpoint and the NAME of the key's variable - and the three readings
write it, their `:reviews` history keeping it per entry, the spec review's event in the run
carrying it too. `bb reprice` takes a reading's file as well as a run record and fills the
reading's cost and its history's; `record` does the same for the run's `:spec-review` event
with the one-request fetch the previous entry describes. Tests: a reading repriced through
its history by its own ids, a reading with a cost or without ids left alone and told why, a
missing record leaving it unpriced, the command on a review file, and the three readings'
records carrying the handles.

### The record after a late cost: priced at `record`, whole when repriced, summed whatever the provider

Row 80. The first real project's first run was recorded with `cost=null` on every dispatch:
the generation records had lagged past the dispatch's twenty-second wait, as the provenance
namespace's docstring allows for, and `bb reprice` by hand put the three costs in minutes
later. The report then contradicted itself three times over - *by provider unknown* over steps
whose records named the host, *no dispatch carries usage* beside a filled tokens column, and a
sum of $0.000000 over OpenRouter steps beside a total of fourteen cents. Each had its own
cause, and the project found all three in one reading.

Four changes. `record!` prices its own unpriced steps before writing, one request per id with
no waiting: by then the last completion is minutes old, so most records are whole when
written, and the command remains for the ones that are not. `reprice-step` fills the provider
and the tokens from the generation records where the step has none, the providers joined when
one step's completions were served by two hosts, and never over a value the dispatch did record.
`dispatch-event` keeps the completion's `:usage` whether or not a cost came with it; it had
travelled only beside a `:cost-source`. And the OpenRouter sum takes every priced step not
served by Anthropic's API, provider known or not - a `some->` over a nil provider had dropped
the repriced ones. Tests for each; `bb reprice` on a record written before this fills what the
old one lacked.

### The `AGENTS.md` sentence names the plan folder the workspace has

Row 79's residue, the first real project's first finding after `bb init`. The application's
`AGENTS.md` has a hand-written part above the markers - a reader's orientation, which `bb
rules-sync` never touches - and its sentence on the rules overlay was built from the project's
name: `../<name>-plan/rules.edn`. With the plan folder named by `--plan`, that path pointed at
nothing, while `workspace.edn` had the right one and every rendering read it. The project left
the sentence alone, since the text was the KIT's, and wrote the finding down.

`app/agents-md` takes the plan folder; `app-fn` hands it on from `init`, whose layout already
knew it. The default stays the name's, so a workspace made without `--plan` is unchanged. A
workspace made before this edits the one sentence by hand, once; the markers' block is not
involved. Tests: the sentence with a chosen folder, with the default, and through `app-fn`.

## 2026-10-01

### A project names its repositories; `bb init` takes the names

Row 79, the first finding of the first real project, before its first line of code. `bb init
<name>` made `<name>-app/` and `<name>-plan/`, the name being also the application's root
namespace. The project keeps its source material, its site and its plan as three repositories
under one folder, named as a set; the namespace stays short. There was no way to say so, and
renaming the folders after `init` would have been the silent workaround the plan rules out.

`--app <folder>` and `--plan <folder>`, defaults unchanged. The folders were already data -
`workspace.edn` names them and every later command reads them from there - so `init` is the one
place that knew the suffixes, and the rule mirror now follows the folder it is in. A folder is
held to the name's pattern (a path or a space in `workspace.edn` would reach every task) and the
two must differ. `init_test` covers the layout, the orientation files naming the chosen folders,
and the three refusals.

In the same commit, carried since the scrub: `method.md` §10 lesson 1 loses the three figures
measured on the source project (a share of wall time, two durations, a run count) and keeps the
point in words, the one place in `method.md` a figure from outside this repository remained.

## 2026-09-30

### A change to an existing file says what it did to the forms

Row 10's enforcement half, built before the deliverable it will enforce. `harness.gates.forms`
is the fourth stack-specific namespace beside `repair`, `stub` and `sigs`: a Clojure file's
top-level forms as a table - kind, name, line range, a hash - and the difference between two
tables, as lost, gained and changed names. Edamame reads, which Babashka ships and
`clj-paren-repair` already parses with: reader conditionals as `:clj` with splices flattened,
tagged literals it has no reader for, syntax-quote, `#_` discards dropped. A `defmethod` is named
with its dispatch value so two methods are two rows. The hash is of the form, not the bytes, so
what gate 0 reindents is not a change. Nil and silent when a file does not parse, the rule
`sigs` follows: gate 0 has already named the bracket.

The first use is one line in the after-write report, for `edit_file` and for `write_file` over
a file that existed: *forms 3 -> 2, lost: helper; changed: f.* The row's opening case - one
expression asked for, helpers renamed and a stylesheet's hooks dropped, every target holding -
would have been three names in that line, in front of the Coder that did it. What it is not yet:
the check that a MODIFY packet's unnamed forms are untouched, which waits on a packet that can
name them. Tests: a plain file, a `.cljc` with a conditional, a splice, a discard and a bare
value, an unparsable file, the delta, and that reformatting is not a change. 526 tests.

### A dispatched role can edit a file it wrote

Row 10, the half of it that was an incentive rather than a design. A dispatched role had one way
to change a file: `write_file`, the whole file again. The transcripts of the builds that tried the
KIT were read for what that cost: a role calling `write_file` twice or more on its one target was
common, not rare - each time a complete rewrite paid in output tokens, usually to fix a lint
warning or a bracket the after-write report had just named. The same tool is the incentive
behind the row's opening case, a Coder asked to change one expression rewriting the namespace
around it: when *replace the file* is the only tool, that is what a model reaches for.

`edit_file` (`harness.models.tools`): a path, an old text and a new text. The old text must occur
exactly once; zero matches and several matches both change nothing and say why. Zero matches is
usually gate 0's doing - the file on disk was repaired and reformatted as it was written, and the
model's old text is what it remembers sending - so that refusal carries the file as it is now when
it is small, and says `read_file` when it is not. The same refusals as `write_file` (outside the
workspace, not in `:files/target`) and one more: the file must exist, since the first version is
`write_file`'s. The same after-write follows an edit: repair, lint, report. The Reviewer is not
offered it, as before. One sentence in the packet prompt, for the roles that write, says to change
a written file by editing it rather than resending it; the tool's description alone was not
trusted to change a habit the deliverable's own wording (*write the complete namespace*) feeds.

What this does not do: say *this file is right except for X*. That is the MODIFY deliverable
row 10 still waits for, and the check that goes with it - the forms a packet did not name left
byte-identical - is the next entry. The measurement is the real project's transcripts: calls of
`write_file` per role transcript, counted the same way as before. 519 tests.

## 2026-09-28

### The documents prepared for `main`: what the KIT ships says only what it can show

The person's decision: the builds that tried the KIT are over, the next project built with it
is a real one, and it should stand on `main`. So the development branch's documents were read
for everything a reader of `main` could not resolve, and four kinds of thing went.

**Names and paths.** A project built to try the KIT is named by its order and nothing else; no
document, comment or test carries a project's name, a path on the machine the KIT was developed
on, or where the older records are kept. Two tests and a docstring used a project's namespace as
their example; they use `app.` now. The workflow's diagram names its example project `xyx`, as the
README does.

**Figures.** A cost, a count or a share measured on one of those builds is not quoted, because
nothing in this repository can re-derive it - the rule `CLAUDE.md` has always stated, applied to
the figures that had been let through as evidence. What was learned stays, in words: the register,
the two profile examples' comments, the rule source's comment on `:data-conventions`, and this
log. One sentence had to change its claim and not only its wording: the harness README quoted
what a spec review costs; it now says what the cost depends on and that a project's own records
give the figure (row 42).

**Commits of the development branch.** The branch reaches `main` as one commit, so a hash from
it would point at nothing. The register's Status column keeps its dates; this log's entries are
referred to by date. Hashes of the template fork and of the upstream harness stay: those
repositories hold them.

**Plan labels.** Step names from the working plans (a letter and a number) meant something only
beside plans that are not in the repository. Each is replaced by what the step did.

**The register** is cut where it could be: a fixed row whose lesson lives in code, a test or a
document is one line; a row that is open, watched, provisional or written keeps what remains to
be done, since that text is the only specification of the work. 78 rows before and after.

**Sentences that had stopped being true.** `portability.md` still said the dispatch harness was
set up for nothing and that the loop, triage and the run log were out of scope; it says what was
built, and keeps the design's reasoning. `harness/AGENTS.md`'s hand-written frame told a reader
it was probably a dispatched agent; the file is the harness's own mirror, for work on the
harness by hand and as the first file `bb rules-check` holds, and says so. `harness/CLAUDE.md`
said to copy the off-loop role by hand, which `bb init` does.

Checked: `git grep` over the tracked files for the development machine's home path, the
projects' name, and the name of the folder and the branch the older records are kept in prints
nothing; for the word that folder is named by, one line, the plan template's own sentence about
superseded plan documents; every line naming a run table or the ignored working folder read, each
a project's report or the KIT's own folder; `bb repair && bb gates` green, 513 tests, after the code and test edits and again after
this entry.

### Teardown deletes a recorded run's branches

Row 78, from a build's closing note: dozens of task branches left in its application,
rehearsal and merged alike, because `teardown` removed the worktrees and kept every dispatched
run's three branches for ever, so the work stayed reachable. It is reachable elsewhere: `record`
copies the gated bytes, the final files and the transcripts into run.edn, and a merged run's gate
commit is on the base branch by the --no-ff merge. After the record, the branches hold nothing
it does not, and nothing said whether they were evidence or litter.

The line is the record. `teardown` on a run that dispatched and is recorded deletes its three
branches and says so; on a run that dispatched and is not recorded it keeps them and says
`record` first; `--keep-branches` keeps them for a run somebody means to reopen; a run that
dispatched nothing is reset as before. `merge` records and tears down, so a merged run's
branches go with the merge. The happy-path merge test, which asserted the branches stay, now
asserts they are gone and the work is on main; a new test covers the three cases. The `:merged`
next-steps line and the usage say it. 513 tests.

### Row 62 measured: the CSS watcher does not outlive the JVM on the known-good set

The person asked what could be fixed before the next build and chose row 62 among three: the template's
`bb serve`, stopped by killing its JVM, leaves its Tailwind watcher behind, from the fifth
build's note. Measured before touching the fork: an application generated from the fork at
`kit-v1` through `KIT_TEMPLATE_LOCAL`, served, its four processes listed by pid, and four stops
tried - SIGTERM and SIGKILL to the JVM, SIGTERM and SIGINT to `bb serve` alone. Nothing survives
any of them; the watcher is the JVM's child through the library's process component and dies
with the JVM's pipes. The fifth build's stop command matches no process on this machine, so its
observation cannot be reconstructed.

Two things worth keeping from the way there. The first pass reported SIGINT to bb alone as a leak
that left everything alive, and a one-line hardening (`:shutdown p/destroy-tree` on the serve
task's shell call) was applied to the fork's working tree, an application generated from it, and
the four stops run again - and the SIGINT case was unchanged. The harness was the leak: a job
started with `nohup … &` from a non-interactive shell inherits SIGINT ignored, so bb never saw
the signal. With the disposition reset before `bb serve` starts, the unfixed application is
clean on SIGINT too. The hardening was reverted; no fork commit, no `kit-v2`, no pin bump. The
row goes to watch with the table and what reopens it. The second: a measurement that contradicts
a note is not the last word until the harness that took it has been checked, which cost one
extra cycle here and would have cost a template version otherwise.

### The stop says how many reviews the run has bought

Row 16, open since the first project: the retry cap counts rounds, and a `check` typed after a
rule-source or Blueprint change buys a fresh Reviewer dispatch that is not a round, so one run
took five reviews for most of its money with nothing saying so. `orchestrate/reviews-bought`
counts the Reviewer's dispatch events and prices them from the report steps of the same names
(`:reviewer`, `:reviewer-r1`, …), and `announce!` prints the line at every stop that follows a
review - the count, the cost when the steps have one, and that none of it counts against the
cap. The cap itself is unchanged: each review is a person's choice, and the number in front of
them at the moment of choosing is what the row asked for. Two tests: the count and the pricing
as data, and the line in the reviewed stop's output.

### Row 47 closed: the plan template knows what the KIT decided, and the check over the given parts had landed

From the person's question of what could be fixed before the next build: the register's
open rows read in full, and row 47 turned out to be done. Its three halves landed on three days -
the rules overlay (2026-09-24), the three-part `02` and `03` (the same day) and the check
over `layers.edn` not loosened and the gate keys in order (`plan/given-problems`, tested as
"the given parts: layers loosened, gates reordered") - and the row's last sentence still said the
check was left to do. Status to fixed, the closing sentence written; no code changed. What the
three parts cost was row 76, fixed on 2026-09-27.

## 2026-09-27

### The `layers.edn` entry's route to a run is written where an adopter reads

Row 77, the proof build's third finding and the last of the six to land. The KIT's rules keep
every dispatched role inside its target, the boundary gate fails a namespace nobody declared,
and the way out - the Architect's `layers.edn` in the run directory's `arch/`, named as
`:architecture {:from "arch" :files ["layers.edn"]}` in the run's `loop.edn`, copied by assembly
into the gate worktree - was in the driver's docstring, the provisioning code and the health
check's one task, and in none of the documents that tell an adopter to declare the namespace.
The adopter's plan review asked where the executable sequence was; the first resolution was
wrong; the right one came from reading `health.clj`.

The sentence now sits in three places an adopter meets in order: the architecture template's §4
note (one row here, one entry there, and this is how the entry travels), `method.md` §03 step 3
right after the gate that makes it necessary, and the header comment of the `loop.edn` that
`bb init` writes into the plan, which every run directory copies - with the key to add. No code
changed; `init_test` asserts the header carries it.

With this the six findings of the proof build are ported, one commit each.

### The plan check leaves a given part's blockquotes alone

Row 76, the proof build's second finding. `bb plan-check` took every blockquote line of
every template document as an instruction - the template's notes to the reader, which a filled
plan deletes once acted on - and the template's rewrite had put `02` and `03` in three parts, the first of which,
Given, is the KIT's own prose and is not edited. `03` §10 carries a five-line blockquote on rule
layers inside that part. The adopter kept it, as the README says to, ran the check, and was told
five lines of the template's instructions were still standing; the check gates the first
dispatch, so the lines lost their `>` markers and the given part was edited to satisfy the rule
that says not to edit it.

`plan/without-given-part` cuts a template document from `# Part 1 — Given` to the line before
`# Part 2` before `instruction-lines` reads it, so a given part's blockquote is prose and the
opening note before Part 1 and every blockquote in the chosen and domain parts are still
instructions. The test reads the shipped template: `03` §10's line is not an instruction, `02`
§4's and `03`'s opening note are, and the cut is checked on its own for a document with no Part 1
or no Part 2. The plan template's README says it in one sentence. 511 tests.

### `bb init` writes the records' folder and their document

Row 75, the proof build's first finding, seen when its workspace was made and left
for the adopter to find. Four documents - the method's §03, the plan template's README, and the
workspace and plan READMEs that `bb init` writes - said the plan holds `runs/` and `RUNS.md` from
`bb init`; `workspace.edn` pointed at both; and `bb init` wrote neither. Row 65 (records in the
plan) gave `record` the copy and `report-check` the hold, and the documents were written as if
the folder and the document came with them. The adopter found no folder, watched `record` make
it on the first copy, and wrote `RUNS.md` by hand with `bb report`'s output pasted in before the
gate would pass.

`bb init` now writes both: `runs/`, empty until the first record (git tracks it from then), and
`RUNS.md` with a header that says how a report gets in and that publishes none - a report is a
plain fenced block opening `Run <id> ·`, which is what `published-reports` finds - so
`report-check` holds an empty folder to it from the first gate run. Asserted in `init_test`
(`:both`, no published report, no drift) and checked live in a scratch clone: `bb init n1check`
makes both, the plan's first commit tracks the document, `bb report-check` from that clone says
"reports in sync: 0", and nothing of the project is in the clone. The plan template's README stops counting "the
four beside `docs/`" - its tree lists six - and says which `bb init` writes and which command
makes `reviews/` and `bake-offs/`.

### The KIT's gates pass in a workspace's clone

Row 74, the proof build's sixth finding and the one that failed the build's own
verification. The adopter ran `bb gates` in its clone at the build's end, as `harness/README.md` says a
workspace does, and the test gate was red on two assertions of one test: `balance/split-args`
given a record alone was expected to return no profile, "outside a workspace" - which the KIT's
development folder is, and a workspace's clone is not. The seven other gates were green; the
KIT's clone stayed clean. Every gate run before had been in the development folder, so a test
that was true only there had never been false.

`split-args` now takes the workspace's profile as a second argument, and the one-argument form
supplies `profile/project-profile` as before; the test passes nil and a path and asserts both
ways, including `bb balance` alone from a clone meaning the project's profile. Verified where it
failed rather than where it had always passed: a clone of the previous commit inside a
scratch workspace (a `workspace.edn`, a plan folder with the shipped `claude` profile and the
overlay `bb init` writes) failed the same two assertions; the same clone with the fix copied in
runs 510 tests green, and `bb gates` there is green. A first attempt to reproduce by exporting
`KIT_WORKSPACE` was not the adopter's situation: the variable wins over the walk-up and four
workspace tests that probe the walk-up with an explicit directory fail under it. Noted in the row
and left - it is a hazard of the development folder, not of a clone.

### A `:deps-sigs` entry is qualified, and both readers of the slice say so

Row 73, the proof build's second finding. Its home-page task wrote `:deps-sigs [(base [title
content]) (header [...])]` the way `method.md` §06's example packet and the Blueprint template's
packet showed it - unqualified - and `start` passed it: `sigs/violations` resolved an unqualified
name by searching every context file for a definition, and a test asserted that a truthful slice
may hold one. But `sigs/undeclared-calls`, the `calls` gate, grants by `[ns name]`, so the same
entry granted nothing, and the Coder's first `views/base` was a red gate after a Coder and a
Tester had been paid and triage had routed it to the Architect. Two readers of one key, two
rules; the documents taught the one that fails late.

One rule now: an entry is `ns/f`. `violations` reports an unqualified entry as `:unqualified`
with the form to write, in place of the search; `sigs/check-deps-sigs` is the same as pure data in
`stub/check-slice`'s shape (an entry `parse-sig` cannot read is named too), and
`blueprint/spec-for` and `driver/start!` run it beside `check-slice` and `check-files`, before
the schema, the spec review and provisioning. The example packet, the method's sentence on
`:deps-sigs`, the template's packet and a new note in its §4, and the Blueprint reviewer's rule
text all say qualified. The ambiguity case (two context files defining one name, once skipped
"rather than guessed") is refused as unqualified now and resolves when qualified, which the same
test still checks. 510 tests.

### A two-file packet is refused before a review is paid for

Row 72, from the proof build: an adopter session that built a two-stage project on the KIT
after the state move and wrote nothing into its clone. Its fourth task named
`handlers.clj` and `routes.clj` under one `:files/impl`. `shapes/TaskSpec` said `[:vector {:min 1}
:string]`, so the extraction validated it, `sigs` passed it, and `start` ran two spec reviews
and provisioned three worktrees before `stub/write!` threw the one-file rule; the run was
torn down and the task split, one run more than planned. The rule was always right - a slice is
one namespace's shapes and signatures, so a second file has no way to say which interface is
whose - but only the last reader enforced it, which is the same shape as the slice entries the
stub could not read (the fourth project's crash, `stub/check-slice`).

The fix follows that precedent: `stub/check-files` returns the rule as data in `check-slice`'s
shape; `blueprint/spec-for` runs it first and refuses by name (`:blueprint/error :multi-impl`,
"split it into one packet per namespace"); `driver/start!` runs it beside `check-slice`, before
the spec review and before anything is provisioned, so the refusal has nothing to tear down. The
schema now says `{:min 1 :max 1}` as well, for a reader that validates without the rule; the
Blueprint template's packet comment and `method.md` §06 say one file per task. Three tests:
the check and `write!`'s message; the extraction refusing a two-file packet from the template
fixture; `start` refusing it with no provisioning, no signature check, no `state.edn`.

The build's other five findings follow, one commit each; the adopter's own records stay in its
workspace.

## 2026-09-25

### Bake-offs are a tool: `bb models`, `bb bake-off`, and a judge that reads blind

Row 46, deferred since the first bake-offs were run as throwaway scripts
and scored by hand. Decided with the person before the proof build: a bake-off is data - an act,
candidates, a judge - and the mechanism is the same for every act; the person types three lines
and the tool generates the rest; the judge maps consensus and disagreement rather than deciding;
one pass per candidate, no samples.

`bb models <query>` (`harness.models.catalogue`) is the lookup every brief did by hand: OpenRouter's
public listing as rows, newest first, the family read off the slug's prefix, prices per million
through BigDecimal (0.0000016 × 1e6 in doubles prints 1.5999999999999999), whether reasoning is
taken, the providers serving one model; no key is sent. `resources/routes.edn` is how each family
is reached - endpoint, key variable, shape, provider pin, effort parameter and levels, `max_tokens`
- what the two shipped profiles already said, written once so a slug can become a role block.

`bb bake-off run <plan>/bake-offs/<id>/bake-off.edn` (`harness.models.bake-off`) expands the spec -
`{:role :blueprint-reviewer :candidates ["anthropic/claude-opus-5.5 high" "grok medium"] :judge
"openai/gpt-6-astra high"}` - through the catalogue and the routes into `RoleProfile` blocks,
written beside as `resolved.edn`; refuses by name a model the listing lacks, a family with no route,
an effort the route does not know, fewer than two candidates, no judge, a judge that is a candidate.
The act names the profile role, how its cases are found on disk (the plan; each Blueprint under
`docs/stages/`; each `spec.edn` under `work/runs/`) and how one case is run: the three review
namespaces gained a `read!` that takes a role block instead of a profile file and returns the
findings, the model as served, the cost, the time and the text sent, so a candidate sits in the
seat without touching the plan's real review files. One record per case per candidate under
`records/`; a candidate whose call fails is recorded and the others go on. Then the judge, one call
per case, given the candidates' input and their answers as A, B, C in a shuffled order the record
keeps, asked for one row per distinct finding with the letters that raised it, a note where they
disagree and its own opinion; the letters are mapped back only in the record. `TABLE.md`: per
candidate the findings raised, rows raised, rows raised alone, cost, time, and real findings per
dollar from the person's `marks.edn` alone (a row index per case → true/false); per case the
cross-reader table. `bb bake-off table` re-renders after marks; `bb bake-off-check` holds every
table to its records and is in `bb gates`' workspace half beside `report-check`. `bb bake-off new`
asks in order at a terminal - the act by number, each candidate resolved and confirmed back, the
judge held to the rule and asked again, the cases on disk - and writes the spec; without a
terminal it says to write the file. The dispatched roles' runner - a loop run per candidate - is
the row's residue.

Checked: `bb gates`; the catalogue on a canned listing in the listing's shape (a word → the newest
slug, an alias skipped, an exact slug, an empty word names nothing, prices, the family, the routes
file's shape and a family with no route refused); the expansion and every refusal; the letters'
seeded shuffle; the run end to end against a stub that answers by who is asking (records, the
judge's record with its order and rows, the table before and after marks, the check catching an
edited table and a lost record; a dead endpoint for one candidate recorded and the others judged);
`new` on a scripted terminal. Live, free: `bb models grok` and `--endpoints` for one model. Live,
paid: one bake-off on an earlier build's stage 1 Blueprint - three readers and a fourth model
as judge: every row raised by all three was judged real, and most rows raised by one reader
alone were the slowest reader's literal readings of the one-promise rule; what the calls
reported as their cost is what the balance moved by once the last call had posted. The table
and the records are kept outside the repository. `fs/glob` matches files and not
folders, found by the check's test. Row 46 fixed with its residue.

### The Blueprint is read before sign-off: `bb blueprint-review`, and a sixth role in the profile

Row 70, opened the same morning by the roster, closed. `method.md` §07 step 2 promised a read of
the Blueprint - the Orchestrator asks whether it is over-engineered, then the human signs off - and
nothing ran it: the harness's orchestrator role is triage, and a Blueprint's packets met a model
one at a time, in the spec review, which cannot see a layer with one use, a shape nothing needs, or
a packet that builds for a later stage. Two projects wrote the read as a throwaway script each.

Now `bb blueprint-review <blueprint.md>` (`harness.setup.blueprint-review`) sends the stage
document the Blueprint's header names as its input, then the Blueprint, then two sections read
from `method.md` at the call - §07's strategic-planning step and §06 from its data-shapes-first
rule through the rules for property targets - to a new profile role, `:blueprint-reviewer`: one
completion, no tools, the prompt saying which parts are given. The findings are parsed as the plan
review's are, printed with the cost, and written to `<plan>/reviews/<stage>/blueprint-review.edn`
with the history of earlier reads; the Architect resolves them in the stage document, then signs
off. Not a gate. A Blueprint not under `docs/stages/`, a profile without the role, a stage document
the header does not name (the read goes on, saying so) and a missing method heading are each said
by name.

The role: `harness.contract.shapes/Profile` closes over six roles now, `profile/roles` lists it
first (a build meets it before any `start`), both shipped examples carry it - Claude Opus 5.5 at
high effort over OpenRouter pinned to Anthropic, no cache, no pricing. In the `claude` example that
is the seat's family, which §05's reasoning forbids as it forbids it for the spec reviewer; the
decision (the person's, 2026-09-25) is an accepted exception from a measurement - on one build
three families read both Blueprints cold and this one alone found each round's load-bearing
defect - written in the profile's comment, left unchecked by `bb profile` on purpose, and watched
as row 71 with what would reverse it. In the other example it is not the seat's family and needs
no exception.

Checked: `bb gates`; the checklist (both sections, their ends, each missing heading refused by
name); the stage document from the header; the reviews folder from the file name; the input's
order; the prompt; the command against the stub model server (the write and its history, the
missing stage document named, an answer with no block, the two refusals); the profile schema, the
role order, the exception's test; `bb profile --seat claude` and `--seat agy-ide` green. Then ONE
LIVE CALL, on a real stage 1 Blueprint and its stage document from an earlier build, copied to
scratch with the shipped profile; the cost the call reported is what `bb balance` showed
before and after. It found name divergences between the stage document and the
Blueprint, a boundary error the seam does not name, bare-name vars, targets requiring a
var no interface asks for, one target that is a description, and several findings reading a target as
two promises; no over-engineering finding, which on a tight stage is the right answer. The written record
is kept outside the repository. `method.md`
§05 (a row) and §07 step 2, `harness/roster.md`, `workflow.md`, `portability.md`'s role table,
`harness/README.md` (the pieces table, the profile row's count, "Adapting it"), `harness/AGENTS.md`'s
task table and the workspace `CLAUDE.md` and plan README `bb init` writes name the command.

### The roster and the workflow: who acts, and in what order

Two documents, written from a question about how many reviews a build has. Nothing in the KIT
mapped every act in a build - each review, gate, dispatch and human gate - to the role behind it:
`method.md` §05 has the roles and `portability.md` the roles' models, and the answer was a table
neither held. `harness/roster.md` is that table: Act, When, Kind, Profile role, Model (the shipped
`claude` example, dated; `bb profile` is the live answer), Notes - and writing it found the row with
nothing behind it, the Blueprint review, opened as row 70. `workflow.md`, at the root beside the
method, is the sequence the roster's acts happen in: one Mermaid diagram in five parts - setup, the
plan, Foundation, a stage, the loop per task - the diamonds the human gates, and a table of the
fourteen steps with who, the question each answers, what runs and what exists afterwards. The root
README's document map, the harness README, `method.md` §05 and `portability.md` point at them.

Checked: the diagram rendered (the machine's Chrome behind the Mermaid CLI): every node in its
part, the loop its own box, the return edge to the next stage. Row 70 written open.

### The Blueprint's packet is the spec the harness reads, and a command pulls it out

Row 69, written and fixed in this commit.

§4 of the Blueprint template wrote a packet with keys the harness does not read - `:contract` for
the slice, `:workspace {:repl/port :dir}`, `:gates {:cmd "bb gates" :retry-cap <n>}` - and without
two it does, `:task/title` and `:property-targets`. `PacketBase` reads `:blueprint/slice {:shapes
:interfaces :deps-sigs}`, `:files/target`, `:files/context`, `:layer/name`, `:property-targets`,
`:task/title` and `:gates {:retry-cap}`; the worktree and the port are the driver's, bound at
dispatch from what it provisions. The method's §06 block has been held to the schema since the
seed - it is `shapes/example-packet`, and `bb test` validates it - but the template, the document
an Architect actually fills, was held to nothing, and the step from a Blueprint to a run's
`spec.edn` was a hand copy: open the Blueprint, take the fence, paste in every shape it named from
§1, rename the keys. Every build did it, each a little differently.

Now the template's packet is a `TaskSpec` - a new schema in `harness.contract.shapes`, the part of
`PacketBase` the Architect writes: `:files/impl` and `:files/test` where a role's packet has its
`:files/target`, no role, no worktree, no port, `:gates` optional - and the note under it says what
the driver adds at `start`. `blueprint_test` reads the fence out of the template, placeholders
standing, validates it as a spec, checks its slice with `stub/check-slice` and cuts the Coder's,
the Tester's and the Reviewer's packets from it with a fake session, so the template and the
schema cannot drift apart again. The placeholders are single tokens for that reason - `<Shape>`,
`<schema>`, `<Named>`, `[<args>]` - and the quotes are gone, since the driver reads `spec.edn` as
EDN and EDN has none. The mark count `plan_test` holds the template to is unchanged: a fence is
not counted, and the note carries no angle bracket.

`bb spec-from-blueprint <blueprint.md> <task-id> [<out.edn>]` is the copy as a command
(`harness.contract.blueprint`): every fence whose first form is a map with a `:task/id` is a
packet; the one whose id matches is taken; every shape it names under `:shapes` is replaced by
§1's definition of it, verbatim - a `(def Name schema)` stays a def, a `[Name schema]` a pair,
both of which the stub reads - the named ones first in §1's order and the packet's own inline
entries after; the result is validated as a `TaskSpec`, checked as a slice, and written
pretty-printed in the template's key order, or printed to stdout with the report on stderr so it
pipes into `bb sigs`. A shape §1 does not define, a task id the Blueprint does not carry, a
Blueprint with no `## 1.` section, a packet that is not a spec (in the schema's own words) and a
slice the stub cannot read are each refused by name, exit 1. The tests derive their Blueprint
from the template inside the test, so a placeholder the template drops breaks the derivation and
a test says so: found and inlined in §1's order, the two refusals, the written spec valid and
read back equal, the command's stdout read back as the spec. Run by hand: the template itself is
refused on `<Named>`; the fixture piped into `bb sigs` against a one-file context checks out.
`method.md` §07 says where a packet comes from, the template's note says what the harness reads
and what the driver adds, `harness/README.md` and `harness/AGENTS.md` carry the command.

### The `claude` example ships the route every build ran on

Row 68, written and fixed in this commit; row 25 extended.

The shipped `claude.edn` had its Coder and Orchestrator direct to Anthropic, with `:pricing`
tables and a comment explaining why the cost was computed. No build ran that way. The routing
decided on 2026-09-18 from the probe row 25 records - the Anthropic roles through OpenRouter,
pinned to `anthropic` (Vertex's cache missed intermittently, Anthropic's never), `:cache_control`
asked for on the Coder and not on the triage call, no `:pricing` - was applied by hand to the
plan's profile in each of the three builds since, while `bb init` went on copying the direct
example. The example contradicted the money tooling built alongside it: `bb balance` and the two
lines around a run read OpenRouter's key and account, and Anthropic's API has no balance
endpoint; the report prints a reported cost as the endpoint's word and a computed one marked `~`;
a 402 stops the loop by name, and with every role on one key there is one account to stop it.

Now the file ships that routing, and its comments say why in those terms - one key and one
balance, the cost reported not computed, the credit stop, the generation record - with the
dated decision and the three builds named as builds. The header no longer says this file
exercises the `:anthropic` adapter; it says the pair still covers both tool-call shapes because
`agy-ide.edn`'s Reviewer and spec reviewer are direct, which is where that shape's worked
example now is. The Tester, Reviewer and spec reviewer are as they were. `profile_test`'s
shipped-pair test holds because of `agy-ide`; its provider-pin test now covers two more roles;
a new test holds the example to the row (the route, the pin, the key, no `:pricing`, the cache
on the Coder alone). `harness/README.md`'s profile bullet, `portability.md`'s seat table and
role table and the plan template's §12 say what the example now is, and what a direct role
needs instead. Nothing in the harness changed: the health check's loop copies this profile
and calls no model, and its balance line reads *no role is on Anthropic's API directly*.

### The workspace test holds the sync to what it did, not the clone to being clean

Row 67, written and fixed in this commit.

`app_test`'s *the two parts meet in one workspace* ends by proving that an overlay edit in a
workspace's plan re-renders the application's mirror and reaches nothing in the KIT's clone. It
proved the second half by asserting the clone's `git status --porcelain` for `harness/AGENTS.md`
and `harness/resources/` was empty. That is a claim about the tree the test runs on, not about
the sync: any uncommitted edit under those paths failed it, and the repo's rule is to run
`bb gates` before committing - exactly when such an edit stands. It failed once, when the health
record was written, on the known-good set `bb health --record` had just written; a rule-source
edit before its commit would have failed it the same way, and the message would have said the
sync leaked.

Now the test reads the clone's state before the sync - the status of the two paths, the
mirror's contents, the rule source's contents - and asserts the state after is equal. A dirty
tree stays dirty and passes; a sync that wrote into the clone changes the mirror's contents or the
status and fails. Checked with an untracked file standing under `harness/resources/`: the
namespace green, 4 tests and 26 assertions; then `bb repair` and `bb gates` on the dirty tree
with this entry and the row standing.

## 2026-09-24

### The documents say what the state move did, and the health check records its loop

Stage 2's last step before its proof: the documents written against everything the state move
changed, the doctor's seat rows made honest, and one extension to `bb health`. Rows 49 (its
residue), 65 and 66.

**The documents.** After the six steps before this one, a project's state - its rules overlay,
its profile, its run records - lives in the plan repository, `bb plan-check` reads the filled plan
before Foundation and `bb plan-review` runs §02's pass; and the documents an adopter meets first
still described the clone as the place the profile went and the plan as a folder of documents.
Now: the workspace `CLAUDE.md` `bb init` writes names the two commands in the plan's own bullet
(the file is held to one screen, its line bound raised by three for them); the workspace README's
plan row and the plan's README name `bb plan-check` as what reads the plan before the first
dispatch; `harness/README.md`'s "Adapting it" gains the bullet for the two commands, its pieces
table the rows for `harness.setup.init` + `app` + `workspace` and for `plan` + `plan-review`
(thirty-three namespaces now, and the size sentence re-measured); the root README's three-part
table says the plan template is half-written on purpose and that `bb init` copies it beside the
overlay, the profile and the records; `method.md` §03 step 1 lists what the plan repository holds
and its readiness checklist's item 2 now ends with `bb plan-check` passing; the plan template's
README shows the four files beside `docs/` in its tree, since an adopter reads it from inside the
plan. Nothing in the KIT's own words changed meaning; the words caught up with the code.

**The seats.** All five stay, by decision. So each seat's doctor entry says where it stands:
`claude` is the seat every build has run from and the one the health check's profile names;
`agy-ide` has its profile example and no build yet; `opencode`, `opencode2` and `pi` have no
example, so `bb init --seat` refuses them by name - which was true since the profile moved into the plan and said nowhere an
adopter would read before being refused. `harness/README.md`'s profile bullet and
`portability.md`'s seat table say the same. Row 66 holds the open half: an example per seat and a
build from each, the person's, after the KIT is proved in `claude`.

**The health check records its loop.** The records' move left the health loop stopping `:awaiting-merge` and
tearing down without `record`, so the copy in the plan's `runs/` and the three commits were held by
a hand step and the unit tests. `check-loop` now runs `record` before the teardown, as `method.md`
§03 step 5 tells an adopter to, and holds the record to what it claims
(`harness.setup.health/loop-problems`, pure, one sentence per breach, tested): `run.edn` written
with status `:awaiting-merge`; `:run/kit-commit`, `:run/app-commit` and `:run/plan-commit` each
equal to that repository's HEAD as the check reads it now, nil only where the workspace has no such
repository; the copy at `<plan>/runs/health.edn` present and byte-equal to `run.edn`; and the
loop's rules overlay and profile resolved under the plan, since a loop that read the clone's
would prove the wrong thing. The selfcheck subject has no plan and is held to a nil plan commit and
no copy. The detail line says which files the loop read and where the record went. Still seven
checks: the record is the loop's last step, not a check of its own, and the README's health block
is unchanged - its row is a dated claim about the run it names. Run on the working tree: healthy,
7 checks, 58 s; the kept workspace's record, read by hand, carried the three HEADs, and `bb report`
printed them on its `Commits:` line. One correction to the loop check's docstring while there: it
said *no network*, and `start` has asked the OpenRouter key's status since the balance line was
added, when a key is in the shell - never failing the run; `record` asks once more. Said so.

### A filled plan is read before Foundation: `bb plan-check` is the gate, `bb plan-review` the reading

Row 49. The plan is the Architect's session's to fill, and nothing read it: a mark left standing
reached the next reader as literal text - the rule source's failure, one level up - and
`method.md` §02 described a review pass that two projects ran as a throwaway script each, with the
checklist pasted in by hand. Two commands now, in `harness.setup.plan` and
`harness.setup.plan-review`, and one hook.

**`bb plan-check [<plan-dir>]`** is the gate, and it is cheap. Over the governing documents - everything
under `docs/` except the template's own `README.md`, which explains the marks and so carries one, and
the two stage templates, copied per stage with their marks standing by design - it reports every
`<mark>` left (`plan/placeholders`) and every line of the template's instructions
still standing. An instruction is known by its content, a set of every blockquote line the shipped
template carries, so a project's own quotation passes and a stage document filled from the stage
template is read under its new name. Then, when the plan is in a workspace: the overlay's
placeholders (`rules/unfilled` over the merged set), and the given parts - the overlay's refusal of a
KIT rule, said by name; the application's `layers.edn` against the pin's layers, where a template
namespace may not require a template namespace the pin did not let it (a layer the project adds,
and a template layer that uses it, are the chosen part and pass); the plan's `loop.edn` gate keys
in the KIT's order, a project's gate after them. Exit 1 with the list, one sentence each. On the
template as `bb init` writes it, fifteen sentences; on a filled fixture, none, and each way of
breaking it is one.

**`start` runs it once per workspace**, before the contract's slice is read and before a spec
review is paid for. Once is known by a hash of everything the check reads - the governing
documents, the overlay, `layers.edn`, `loop.edn` - kept at `work/plan-check.edn`; a plan that has
not changed prints *unchanged since* and is not read again, one that has is read again, and a
failure does not move the hash on. The stop is `:plan-check`, the Architect's, like the spec
review's; `:plan-check? false` in `loop.edn` switches it off, which the health check's generated
application sets - its plan is the template as shipped, unfilled on purpose, and the health check
proves the machine, not a plan.

**`bb plan-review [<plan-dir>]`** is §02's pass, run: the six Phase A documents in the order they are
written, a missing one named as such, and §02's checklist - read from `method.md` at the call, from
its heading to the section's end, so the method and the review cannot drift apart - to the
profile's `:spec-reviewer`, one completion, no tools. The prompt says which parts are given and
not the project's to have decided, that PROVISIONAL-with-a-spike and OPEN-with-an-owner are the
method working, and that RESOLVED-on-no-evidence is a finding. Findings are printed with the
call's cost and written, with the history of every earlier review of the plan, to
`<plan>/reviews/plan-review.edn`; the person resolves them in the overview's table. Not a gate: a
review that found nothing proves nothing. Tested against the same stub model server the spec
review's tests use.

**Under the check, the template.** Two marks in `03-method-and-tooling.md` had a mark nested inside
(`<… with \`<type>\`.>`): the fixture counted the inner one, and an adopter who filled it met the
outer one as a new mark. Both un-nested; `03` counts 37 now. `method.md` §02, the template's README
and the overview's §5 note name the two commands; the workspace documents `bb init` writes follow in the next step.

### The plan template knows what the KIT decided: given, chosen, theirs

Rows 47 (the template half), 48 and 59. The plan template predated the inversion: `02-architecture.md`
asked which datastore and `03-method-and-tooling.md` asked the adopter to fill in roles, packet,
isolation and gates as if choosing them, when adopting the KIT had fixed all of it. Both are now in
three parts. GIVEN states what adopting fixed, as references to where the KIT says it and not as
questions - the stack as pinned, the six layers as `layers.edn` declares them, the roles and the
independence rule, the packet, the three worktrees, the five gates with their keys, triage and the
routes, the stops and their owners, the tooling, the rule source and the precedence of its layers -
and is not edited; changing a given part leaves the certified pair. CHOSEN is decided once, in
Foundation, and recorded in the log - the layers above the template's, the seams, the datastore, the
overlay's three placeholders, the models per role, the numbers with their keys and defaults
(`:retry-cap` 3, `:spec-review/max` 2, the money cap and floor a person holds), the write-time hook
and the stage-end driver, the pins. THEIRS is the domain: the diagram, the surfaces, the property
targets, the stage-end checks, the readiness record - whose fourth line now says the trivial task is
run to `:awaiting-merge`, recorded and torn down, never merged, as `method.md` §03 step 5 and its
checklist now say too (two projects merged theirs; one paid a run for the flaky test it left).
`01-requirements.md` gains a *Derived from* line and a *Revisions* section naming the mechanism a
stage's lesson enters through. A new `source.md`, written first, is the record of what the plan
derives from - handed over, read, asked - so a requirement cites evidence by number instead of
carrying it; `reviews/` is named as the home for a review's raw material. The README, the overview's
document table, `method.md` §02's document set and the orientation `bb init` writes say all of this.

A namespace to read a plan by: `harness.setup.plan/placeholders` is the definition of a mark a
project fills - an angle bracket outside a fenced block, not an autolink or a comment, and not
`<name>` or `<kit>`, which the workspace knows - and `placeholder-counts` over the shipped template
is the fixture the check over a filled plan is written against.

Checked: `bb gates`; `bb init --dry-run` into a scratch folder counts nine plan documents where the last commit had eight; the definition of a mark (the two known tokens, a fence, an autolink, a comment,
inline code counting); the counts per shipped document, and that they cover exactly what `bb init`
lists; the given part of each of the two documents carrying no mark past its header and opening
note. Rows 48 and 59 fixed; row 47 extended, open for the check over `layers.edn` and the gate keys.

### A KIT kept outside its workspace is pointed at it, and a run's workspace is the command's

Row 45, open since `bb init` first allowed `bb init <name> <dir>`: every command in `harness/`
found its workspace by walking up from the working directory, so a clone kept outside the
workspace - or one clone serving several projects - found nothing. The rule tasks then missed the
application's mirror (a generated file nothing checked), and since the overlay landed the same
lookup carried the project's rules, so from such a clone every rendering read the source alone,
placeholders and all, with nothing saying why. Now one function answers where a command runs,
`workspace/current`, in order: the run's workspace, when a loop command is running - `run-loop`
binds it from the run directory, which walks up as before, so a run's rules are its own workspace's
whatever the shell says; the folder `--workspace <dir>` names; the folder `KIT_WORKSPACE` names;
else the walk-up from `harness/`, unchanged. The flag is on `rules-sync`, `rules-check`, `profile`
and `report-check`, taken out of the arguments before the rest are read (the mirror list is now
computed inside `rules/-main`, after the flag, rather than in `bb.edn` before it); the variable is
read by every command, so `bb balance` and `bb rules-prompt` follow with no change of their own. A
named folder with no `workspace.edn` at or above it is refused by name, flag or variable, exit 1 -
a KIT pointed at nothing is otherwise indistinguishable from a KIT in no workspace. The walk-up
answers nil as it did. `:workspace/from` in the map says which of the four answered.

Checked: `bb gates`; the flag taken out of the arguments and refused without a value; the flag
over the variable over nothing; `current` from inside a workspace (walk-up), from outside (nil),
pointed by the flag from outside (found, paths absolute), pointed at a folder under the workspace,
pointed at nothing (refused, naming the flag or the variable), and under the run's binding (the run's,
over the flag); `mirror-paths`, `plan-profile` and `check-targets` each from a folder outside the
workspace, bare and pointed. Then from this clone against a scratch workspace kept elsewhere, with
its overlay filling one placeholder: `bb rules-check --workspace <ws>` reported the application's
mirror drifted, `bb rules-sync --workspace <ws>` rendered it with the overlay's text and the clone's
own two mirrors untouched, `bb rules-check` pointed by the flag and by the variable then passed;
`bb profile --workspace <ws>` printed the plan's profile; `bb report-check` pointed both ways found
the records folder and failed on the missing table, as it should; the flag with a folder in no
workspace, and with no folder, each refused in a sentence. Row 45 fixed.

### A run record has a home in the plan, and names the commits it was taken at

The third part of stage 2's state move, and the last file of a project's that had nowhere to go.
`record` wrote `run.edn` into the run directory - scratch under `work/` - and two builds copied
it into their plan by hand, by a convention nothing enforced; which KIT, application and plan
commit a record ran against was answered from memory. Now `workspace.edn` names
`:workspace/records` (`<name>-plan/runs`) and `:workspace/run-tables` (`<name>-plan/RUNS.md`),
`bb init` writes both, and `find-workspace` makes them absolute like the rest. `resolve-config`
keeps the records folder and the three repositories in the run's config at `start`, as it keeps
everything; `record` copies `run.edn` to `<records>/<run-id>.edn` and writes `:run/kit-commit`,
`:run/app-commit` and `:run/plan-commit` from `git rev-parse HEAD` in each - nil where there is no
such repository, and the report then prints `—`; a record from before them prints *not recorded*,
as the roles line does. The schema has the three as optional strings. `bb report-check` takes its
two targets from `workspace.edn` when given none, and `bb gates` now runs it: in a workspace's
clone a record nobody published, or a table whose record is gone, fails the gates there; in the
KIT's own folder there is no workspace and it says so and passes. One target given is a usage
error, not a guess at the other.

Checked: `bb gates`; `find-workspace` with and without the keys; `resolve-config` in a workspace
and bare (the KIT then the repository this runs from); `record` in a workspace fixture with a
commit in each of the three repositories - the copy by run id, the same record in it, the three
hashes - and outside one (no copy, the application's hash, nil for a folder that is not there
and for the plan it has none of); the schema; the report's line and the older record's; the
targets from arguments, from a workspace, from one without the keys, and with one argument. Then
end to end: `bb health --keep` green (7 checks, 62 s), `bb run-loop record` on its kept run
copied the record into the generated workspace's `hc-plan/runs/health.edn` with the three hashes
equal to `git rev-parse HEAD` in the KIT, the application and the plan; `bb report-check` on that
plan failed with the record unpublished and passed once `RUNS.md` carried the rendered report.
Row 65 written and fixed.

### A project's profile lives in its plan, and the driver reads it there

The second part of stage 2's state move. The profile - which model answers for which role, and
the seat - was the one file the README still sent into the clone: `resources/profile.edn` on a
branch of the KIT, the destination row 55 named a day ago because the folder beside it is held
to the shipped examples by the KIT's own tests. Now `bb init` writes `<name>-plan/profile.edn`
- the shipped example for the seat, whole, its header kept, under a line saying what the copy is
- and the plan's `loop.edn` says `:profile "profile.edn"`. `--seat <name>` picks another example
(default `claude`; a seat the KIT ships no example for is refused by name, before anything is
written). The driver's `resolve-config` resolves `:profile` against the workspace's PLAN when
`workspace.edn` names one, and against the working directory otherwise - the health check's
selfcheck has no plan and keeps the shipped path - so `start`, `run-loop run` and `bb reprice`
read the plan's file with no change of their own. `bb profile` with no argument checks the plan's
profile of the workspace this clone is in (`profile/project-profile`, found by walking up) and
falls back to the shipped examples, structurally, outside one; `bb balance` no longer requires
the profile argument - a first argument that reads as a profile (a map with `:roles`) is one,
anything else is a record and the profile is the workspace's - so the stop text's bare
`bb balance` now works from a workspace's clone. The shipped examples' headers say where the
copy goes. The clone now carries nothing of a project's configuration: rules and profile
are both in the plan.

Checked: `bb gates`; `resolve-config` in a workspace with a plan, without one, and with an
absolute path; `context` from a run directory under `work/` with the plan's copy of the shipped
example; the layout's file (the default seat's example, `--seat agy-ide`'s, the refusal for a
seat with none) and the parsed arguments; `project-profile` outside a workspace, in one with no
profile yet, and from the clone's `harness/`; `bb balance`'s argument split; `bb health` green
(the generated application's loop ran from the plan's profile). Row 55 extended.

### A project's rules live in its plan, and the KIT's clone is never edited for them

The first half of stage 2's state move. Until now the rule source's three placeholders were filled
on a branch of the KIT's clone, so `git pull` was a merge and a project's rules sat in a repository
that was not its own - row 47's *nothing holds the given parts*. Now `bb init` writes
`<name>-plan/rules.edn` - the three placeholder rules, text as shipped - and `workspace.edn` names it
(`:workspace/rules-overlay`). `harness.rules/overlay` merges it over the source by id: an entry for a
placeholder rule replaces its text, an entry with a new id is a project rule and must be whole, and
an entry for any other rule of the source is refused by name, every problem at once - the check that
holds the KIT's own rules against an edit made by accident. `load-rules` returns the merged set, so
every rendering reads it: each role's prompt, the spec review's, triage's data conventions,
`bb rules-prompt`, and `start`'s list of what still stands, which now names the overlay as the place
to fill. A mirror renders from the project's rules only when its workspace lists it
(`rules-for-mirror`): the KIT's own `AGENTS.md` and the selfcheck's sit inside the workspace by
default and keep rendering from the source, so `bb rules-check` in the clone is green whatever the
project fills, and the application's mirror drifts when the overlay changes until `bb rules-sync`
runs. The plan is now written before the application, so the application's mirror is rendered from
the overlay at generation. A `workspace.edn` without the key, from before, reads the source alone;
one that names an overlay it does not have is a fault, not the source.

Checked: `bb gates`; the merge (replace, add, an empty overlay, the refusals by name, a rule that is
not whole, a duplicate id); a workspace fixture where the application's mirror renders the fill and
the KIT's mirror inside it does not; `bb init`'s layout (the file's ids and text are the source's
placeholders; the plan before the application); a created workspace whose mirror follows an edit to
the overlay with nothing reaching the clone; `bb health` green. Rows 45 and 47 extended; row 47 stays
open for the template's rewrite and the check over `layers.edn` and the gate keys.

### A `tooling` route: the loop can now say the machine is at fault, not the run

Two gaps, one namespace apart. On a note, the routes were contract-shaped - `continue`,
`architect`, `human`, the noting role - so a Coder's note that the harness itself was broken could
only be `continue`d, rightly, and the defect cost the next role a third of its turns (row 12, open
since the first project). On a red gate, a merged property test that rounds onto its bound on
some seeds failed a later task's gate in the fifth build; no role in that run owned the file,
triage routed `human`, rightly, and the fix went through the loop as a run of its own (row 61).
Now `tooling` is a route. On a note it is always offered. On a red gate it is offered only when
the driver's proposal says so: `propose-routing` proposes `:tooling` when the test gate's every
failing namespace is none of the impl, test or dependent files' - `:foreign-namespaces` names
them, `:retry-role` is nil - so the model is not handed an exit from every hard call. The loop
stops `:tooling`, person-owned; on a red gate the reason names the namespaces; the next steps are
`check` again for a seed-dependent failure, else a fix outside the run and, on a red gate, the
task started again (the worktrees cannot see a fix made after they were cut), or on a note a
`continue` with the decision that the machine is fixed. The fallback on a foreign failure is the
same stop.

The finding under it: `failing-namespaces` read only clojure.test's `Testing <ns>` lines, and the
pinned template's runner prints none - it names the namespace on the failure, `FAIL in
app.util-test/clamp-above-hi-spec (util_test.clj:46)`. The fifth build's proposal therefore named
no file and no owner where the namespace was on the line. It reads both shapes now.

Checked: `bb gates`; the proposal (foreign alone, foreign beside the run's own, a lint gate), the
offer (a note always, a red gate only on the proposal, never a rejection; a verdict of `tooling`
where it is not offered falls back), and both stops in the loop, with a `continue` after the
note's. One paid replay of the fifth build's red-gate triage prompt with the route spliced in as
`render-prompt` now emits it, against the run's own orchestrator model: it routed `tooling` and
named the namespace and the mechanism. Rows 61 and 12 fixed.

### `bb reprice` fills the cost a generation record was too late to give

A dispatch whose generation record lagged past the fetch budget is written with no cost; the
footer says how many steps the total covers, the sum sits under the key's counter by that
step, and nothing could put a later-fetched number in the record (row 17, open since the first
project and confirmed on every build since). Now `bb reprice <run.edn>` fetches the records of
every dispatch step with no cost and fills it - marked `:cost-source :repriced`, the endpoint's
word fetched later, and only when every one of the step's ids answered, since a partial sum is
the understatement the footer exists to prevent - recomputes `:run/cost`, rewrites the record
and says that a document publishing the table now fails `bb report-check` until re-rendered.
The report's footer counts the repriced steps.

The finding under the fix: the completion ids the runner always had never reached the record.
`dispatch-step` kept model, provider, cost and tokens from the runner's meta and not the ids,
and the provenance docstring said they were recorded "so a run log can be backfilled" - true of
a map that lived for the length of one dispatch. The step now keeps `:step/generation-ids`, the
record's `:run/roles` keep each role's endpoint and the NAME of its key variable (never a key),
and the schema admits both, optional, so older records are unchanged. Which means nothing
written before this commit can be repriced: the fifth build's one unpriced Coder step included.
`bb reprice` on a scratch copy of that record says so in one line and touches nothing; the
planned live check on it is therefore not possible, and the first record this can fix is the
next build's. Row 17 fixed.

Checked: `bb gates`; the stub-server tests (both ids answer, one does not, no endpoint on the
roles and a `--profile` supplying it, an endpoint with no generation record, `-main` rewriting
a temp file and a second run leaving it alone); the live run on the copy, above.

### `start` says what the checkout holds that the worktrees will not see

The worktrees are cut from the last commit of the application, and nothing said so: an edit
sitting in the checkout - a hand fix a session meant to commit, a document half written - is
invisible to every role and every gate of the run, and the difference surfaces later, as a red
gate on a file nobody in the run touched or as a merge that carries the committed version over
it. `start` now runs `git status --porcelain` in the application's root before provisioning,
leaving out the run directory and the worktrees folder when they sit inside the repository (the
loop's own files, not the application's), and warns with the count and the lines; `:allow-dirty
true` in `loop.edn` silences the warning. Warns, not refuses: a person may mean it. Either way
a `:dirty-tree` event carries the count, the first twenty lines and whether it was allowed, so
the record says what the run was cut from. A checkout that is not a repository, or no `git`,
yields nothing and never fails the run. Row 64 written and fixed.

Checked: `bb gates`; the unit on a scratch repository (a modified file, an untracked one, an
ignored one, the run directory inside it), and the loop test with and without the key.

### A provider refusing for credit is a stop of its own, and its text stays out of the record

A 402 was a failed dispatch like any other: the stop said *retry it*, and the retry was refused
for the same reason with the same text - which, from OpenRouter, is a message carrying a dashboard
URL with the key's id, and the first project built on the KIT had it in the console, `state.edn`
and the run log before anyone read it (row 13, open since then). Now the adapter reads a 402 -
the status, or the code inside a 200 body - as `:harness/error :credit` and cuts its message to
the first line, before any URL, before anything keeps it; the agent adds the role's endpoint and
the NAME of its key variable; the dispatch event carries `:error/kind`; and `next-action` stops
`:credit`, person-owned, ahead of `:dispatch-failed`, saying who answered 402 to which dispatch
and which variable holds the key. Its next steps are `bb balance` and a `retry` after the top-up,
not a retry. The spec review's own 402 says the same in its exception. Every other status is what
it was. Tests: the adapter on a 402 with a URL and a second line, the agent against a stub server
answering 402, the event's kind, and the loop stopping `:credit` with the Tester never
dispatched and a second `run` the same stop. Row 13 fixed.

Checked: `bb gates`.

### A stage's end checks what no gate can: the interaction check, named in the method

Two builds of one project: the fourth accepted its search swap unverified, since its tests call
a handler as a function of a request map and can prove the fragment comes back with the right
header but not that the page swapped it in; the fifth verified it with a ten-line script driving
headless Chrome at both widths, outside the loop, for one `npm install`. The method's §04 exit
criteria now ask for that check for every behaviour the tests verify only as an HTTP contract -
not a gate and not a test in the suite, because it starts a server and a browser, which the
Testers' rule keeps out; recorded beside the screenshots with the driver named. The plan
template's `03-method-and-tooling.md` gains §7.7, stage-end checks: real-viewport screenshots
with a measurement file (the fourth build had shot 390 px through an iframe because headless
Chrome's window floor is wider), one script per contract-only behaviour, the driver the
adopter's with `puppeteer-core` as the worked example, and the process-group kill until the
template's serve stops cleanly (row 62). Row 63, written and closed.

### `bb balance` reads the account's credit beside the key's limit

`/key` gives a key's spending limit and what is left of it; `/credits` gives the account's
purchased credit and its usage, and they are not the same number: a key limited to $80 on an
account holding $30 stops at $30. The line showed the key's figure alone and called it the
balance (row 26, open since the second project). `openrouter-key-status` now reads both and the
line names each - the account's as *the number a cap is really against* - or says the account's
is unavailable when that endpoint does not answer. Stub-server tests for both cases. Row 26
fixed.

Checked: `bb gates`; one live read of both endpoints on the key in use, the figures never
printed.

### Row 14 to watch: an application is never an empty `src/` since `bb init`

The row (a greenfield project's empty `src/` dropped from the classpath for the first task's
REPLs) came from a bare-`deps.edn` project. Every application since is generated from the pinned
template with its `src/` populated; the case survives only on the bring-your-own-application
path, which no build has taken. Watched, not fixed; the first of a batch of small fixes made before stage 2.

### The fifth build: the same contracts on the fixed KIT, and what the register learned from it

A fifth build reran the fourth's tasks on the KIT with the day's fixes, the plan and
Blueprints reused as resolved and no model reads: every run merged (one a fix of Foundation's own test
through the loop), no rejection, for less money than the fourth's runs.
Where the fourth paid a person stop per task on the stub and read its red test gates by hand,
the fifth met neither: its red gates were read whole by triage and routed to the Tester
against the mechanical proposal, right each time; rework fell to a small share of the loop.
That is the measurement the fixes were made for; its figures are kept outside this repository.

Two things the register did not have. **Row 61:** a property test merged in Foundation's trivial
run built a value that rounded onto its bound and failed a later task's gate at random on the
seed; no role in that run owned the file, triage routed `human`, rightly, and the fix went
through the loop as a new run - the loop has no route for *a merged file, or the tooling, is at
fault*, on a gate as on a note (row 12). **Row 62:** the template's `bb serve`, stopped by killing
its JVM, leaves its CSS watcher behind - the template fork's to fix. Five rows extended with what
the build confirmed: 2 (triage on the seat's family: every route agreed with the reader, the
first live evidence), 10 (a Tester's own parse helper cost a round twice before it became a
sentence), 17 (an unpriced step, again), 36 (a second read of an amended spec drew many
findings of one shape the rule source already decides), 42 (a long conventions rule did not
price the reviews out). No code in this entry; the one-line fix is the next.

### A clean spec review says it carries on

`start` reviews a spec and, on zero findings, carries on - the stop exists so a list is read,
and an empty list has no reader (2026-09-21). The line printed beside a clean review still read
*fix the contract or not, then `start`*, the stop's instruction, above a loop that had already
started: on the fifth build, in most runs. It now says *0 findings, so the loop carries
on*. One conditional in `spec-review!`.

Checked: `bb gates`.

### The data-conventions placeholder asks whether the template's own suite is the pattern

*Nothing in a test starts a server* is the guidance the placeholder gives for Testers, and the
pinned template ships a system smoke test that boots Jetty and the database under `bb test`,
on purpose - which caught a closed component schema before a merge on the fourth project,
where five merged tasks had once failed to start on the third. The project worded its rule as
*nothing a Tester writes starts a server* and named the smoke test as the one exception; the
placeholder now asks every adopter to do the same: say whether the template's suite is the
pattern, name the exception, keep the rule for what a Tester writes. One sentence in the
placeholder, mirrors re-synced. Row 60, opened this morning, closes; the rerun's Foundation is
its first check, since that fills the placeholders again.

Checked: `bb rules-sync`, `bb gates`.

### The register, read against the fourth project

Seven rows the build touched get a sentence saying so: 3 and 4 (the Tester's slips and the
cap - one capped run whose cause was the harness's, row 50), 17 (the counter
lags, every run), 23 (`amend` before `start`, met on the first run), 25 (caching through
OpenRouter: most of the Coder's prompt tokens were cache reads), 38 (a closed component schema, this time caught by
the template's smoke test inside the gate, before a merge) and 39 (its neighbour, row 54). Two
rows open: 59, the plan template's missing slots - for what a plan is derived from, and for a
review's raw material - which is stage 2's beside rows 47-49; and 60, the template's own smoke
test booting a server inside the test gate against the Testers' rule, which earned its place
and needs one sentence in the `:data-conventions` placeholder. No code.

### Four one-sentence fixes from the fourth project, one commit each

Each was a row in that project's findings that cost minutes or a round and needed a sentence:

- **The profile's destination** (register row 55). `resources/profiles/` is held by
  `profile_test` to exactly the shipped examples; the shipped profile's header said to copy it to
  `resources/profile.edn`, but the README and the `loop.edn` that `bb init` writes never named
  that path, and the project's profile placed in the folder failed the clone's `bb test`. The
  README's adoption list and the `loop.edn` comment now name `resources/profile.edn` on the
  project's branch and say the folder is reserved.
- **What to do at the spec-review stop** (row 56). `amend` needs a started run, and the stop
  where an Architect most often amends is before `start`; two projects met the refusal there.
  Both messages - the refusal and the stop - now say to edit `spec.edn` in place, keep the
  previous file beside it for the diff, and `start` again, which reviews the edited spec.
  Recording that amendment as an event is still row 23's.
- **What the balance line calls Anthropic spend** (row 57). It summed every provider whose
  name contained *Anthropic*, the serving provider behind OpenRouter included, and printed
  "Anthropic spend" with a sum on a project where no role was on Anthropic's API. It now counts only
  `Anthropic API` steps and prints the clause only when a role is on that API directly;
  otherwise it says the key's usage is the whole spend. The report's line prints only when there
  is such spend. A test covers the OpenRouter-only case.
- **A generator is a value** (row 58). Testers called a generator as a
  function and failed their file at load, a round each; the rule said only *def, not defn*.
  `:generators-are-values` gains the clause with the example; the mirrors are re-synced.

Checked: `bb rules-sync`, `bb repair`, `bb gates` once over the four together (each changes a
different file), then one commit per row.

### Where the ask for presentation lives, and a packet example that shows what the roles receive

Two method gaps the fourth project paid for. Rule 11 of §06 kept every review and test off
styling, as designed, and said nothing about where the ASK for styling lives; a Coder's packet
holds targets and rules, neither says *style it*, and the project's first four merged views
arrived with no classes at all. One sentence in `:data-conventions` naming the theme tokens
fixed it for the next stage with no hand change, so rule 11 now says that: the ask is a
conventions sentence every role reads - the Reviewer included, so it knows the classes are
expected and not review material - or a packet instruction labelled as a working instruction,
never contract. And the packet example showed `:shapes [Concept Release]`, names; the project
copied the form, the spec review reported the schemas undefined, and every role would have
received bare symbols. `shapes/example-packet`, the method's §06 block and the Blueprint
template's packet now define one shape inline and name one, with the function and the var
interface forms beside them (previous entry), and a note under the block says the roles receive
exactly what the packet holds: inline what the task defines, name what arrives as context, and
a convention true of one Blueprint only - which has no place in the rule source - goes into
every target that uses it, in full. Register rows 53 and 54.

Checked: `bb gates` (the example packet still validates against the schema).

### A var is a deliverable, and a slice the stub cannot read is refused by name before provisioning

The fourth project's routing task delivered a `def` of route data. The packet had no form for
that: `:interfaces` was read only as `(name [args])`, a bare symbol threw *Don't know how to
create ISeq from Symbol* from the stub generator - after three worktrees and two nREPLs
existed, with an error pointing at the `let` in `-main` and nothing to say which key - and an
empty `:interfaces` was refused outright. The spec went out with a placeholder signature
`(routes [])` and the truth in its title. Now `(name)` is a var to define: the one-element form
`sigs/parse-sig` already read under `:deps-sigs` for a var a task may read, so the two halves
of a contract say "var" the same way. The stub emits `(declare name)` for it - unbound, so a
test that uses it errors, the guarantee a throwing body gives a function. `stub/check-slice`
(previous entry) now covers `:interfaces` too: empty, a bare symbol, a list with no argument
vector, each named with its reason, and `start` refuses on it before the spec review and
before a worktree exists. The Coder's deliverable and the Blueprint template say which form
is which. Register row 52.

Checked: `bb repair`, `bb gates`. New tests: a bare symbol and an argless list are refused by
name while the function and the var pass; `(routes)` renders a `declare`, the stub loads, the
var is unbound.

### A gate's output is what a terminal would show, and triage reads its tail

The fourth project's test gate is cloverage over eftest, which draws a progress bar by
rewriting one line with a carriage return, hundreds of times. Captured to a string, every
rewrite survived: a red test gate's output was kilobytes of `0/27 0% […] ETA`, and the clip triage
receives kept the first 6000 characters - the progress, never the failure report the runner
prints last. Three red test gates were routed to a person with the assertion unseen and read by
hand in the gate worktree; each was a one-line route to the Coder had the output been whole,
and each cost a triage call for the wrong answer. Two changes, one commit. `gates/tidy-output`
collapses carriage-return rewrites at capture, in `run-gate`, so every reader - triage, the
routing proposal's scan for file names, the record - sees what a terminal would have left;
and `triage/clipped` keeps the head AND the tail of a long output, 1,500 and 4,500 of the
6,000 characters, with the omission marked between them, because a runner's report is at the
end. Register row 51.

Checked: `bb repair`, `bb gates`. New tests: a synthetic progress-bar capture collapses to its
last line and the report, through `run-gates!` as well as the function; a 20 KB output whose
report is last reaches the clip's tail with its head intact.

### The stub reads a `(def …)` shape, escapes its docstring, and refuses what it cannot read before anything is provisioned

The fourth project built with the KIT inlined every shape into its packets - as the method asks,
so the roles receive the contract and not a name - and wrote them as `(def Name schema)` forms.
`stub/shape-def` read only `[Name schema]` pairs, so every one fell through as a shape merely
*named* and was `str/join`-ed into the stub's docstring; the first shape carrying an
`:error/message` string put an unescaped quote inside that docstring and the Tester's namespace
would not load. More than half the runs paid a Tester note, a triage call and a person stop for it,
and one Tester spent its whole iteration budget on the unloadable stub and wrote no file - the
project's one failed dispatch. Three changes, one commit: `shape-def` reads the def form as the
pair it is; the docstring is escaped whatever reaches it; and a new `stub/check-slice` returns
every `:shapes` entry the stub cannot read, as data, which `driver/start!` runs over `spec.edn`
before the spec review is paid for and before a worktree exists - the refusal names the entry
and the key, and there is nothing to tear down. `render` refuses the same entries, so a slice
that reaches it by another path fails the same way. Register row 50.

Checked: `bb repair`, `bb gates`. New tests load the rendered stub with a quoted string in both
shape forms and check the refusal names its entries. One `bb gates` run failed a single
assertion and the two after it passed unchanged (419 tests, 2201 assertions); the first run's
output was not kept, so it joins the register's timing-dependent test as noted, not explained.

---

## 2026-09-23

### Who fills the plan, said where it is read

The plan template's README told a reader to delete the instructional blockquotes once acted on,
without saying who that reader was. It is the Architect's session - the person, or the model they
work with in the session started at the workspace root - in Phase A, before any loop exists; no
dispatched role writes a plan document, and nothing in the KIT had said so. Two sentences: the
template's README says it, and the workspace `CLAUDE.md` `bb init` writes says the plan is filled
in that session, in the README's order, reviewed by `method.md` §02, and that a `<placeholder>`
left standing is not a decision - the failure the rule source's own placeholders had (row 15),
one level up. For that the README now travels with the documents into `<name>-plan/docs/`
(eight files, not seven): the order of writing belongs beside what is written in that order.

Checked: gates; a workspace generated from the working tree has `docs/README.md` and the
`CLAUDE.md` sentence. Not built, noted for stage 2: a plan review read cold the way `spec-review`
reads a spec, and a check that no angle bracket remains before Foundation.

### `skeletons/` is `plan-template/`

The KIT ships two templates and `bb init` generates from both: the application template - the
pinned fork - and the plan template, the seven documents that were `skeletons/`. The old name
said what the files looked like; the new one says what they are for, in the vocabulary the KIT
already uses (`template-pins.edn`, `harness.setup.template`). Not `plan/`: in a workspace the
KIT's clone sits beside `<name>-plan/`, which IS the plan, and a `plan/` inside the KIT would read
as the KIT's own. The person's choice, 2026-09-23. `init/plan-template-files`, the request key
`:plan-template`, the refusal's wording, the READMEs and `method.md`'s links follow; the folder's
own README no longer says to copy it by hand, which `bb init` had made stale.

Checked: `bb gates` from `harness/` and the root (the root once exited 1 in a chained command
and 0 on two plain re-runs; the log of the failing run was not kept, so it is noted, not
explained); `bb init zz --dry-run` found 7 plan documents (8 once the README travelled with them, next entry).

### The KIT publishes no run records of its own

`runs/` (`a1`–`a4`) and `RUNS.md` are gone from the KIT. They were the loop with real models
against what was then `sandbox/`, on 2026-09-16/17 - the KIT's own runs, not a project's - and
the KIT carried them to every adopter's clone, naming `harness-seed` and `sandbox/`, both gone,
while `report-check` guarded two files that would never change again. They are not lost: they are
kept outside this repository, with the runs before them and the first bake-offs. The person's
decision, 2026-09-23, on that basis.

What replaces them as the KIT's committed evidence is `harness/health/records/`: re-run on every
machine, rendered into the README, drift-gated. What stays is the mechanism a project needs -
`record` writes `run.edn`, `bb report` renders it, `bb report-check <markdown> <records-dir>`
holds a publishing document to its records - now with its two targets required rather than
defaulting to files the KIT no longer has; `bb gates` no longer runs it. `CLAUDE.md`'s rule about
committed records is rewritten to say this; the README's document table, the harness README and
`portability.md` say the same. Where a project's records live is stage 2's question.
This log's entries of those dates still cite the run ids; they are history and were left.

Checked: `bb gates` from `harness/` and the root; `bb report-check` with no arguments prints
usage and exits 2; the report tests run the check with explicit targets and pass unchanged.

---

## 2026-09-22

### `sandbox/` is `harness/health/selfcheck/`

The checked-in project the harness is run against had one job left - the first subject of
`bb health`, the harness's mechanics shown in seconds before an application is generated - and a
name that said a place to play. The person chose `selfcheck` (2026-09-22), and it moved inside the
harness under `health/`, which now holds everything the health check owns: the project and the
records (`health/records/`, from `resources/health/`). The project's root namespace is `selfcheck`;
its `deps-check` reaches the harness by `../../bb.edn`; its mirror is listed in the harness's own
`resources/rule-mirrors.edn`, and the root `rule-mirrors.edn` - which existed only to list a mirror
from outside - is gone. `--sandbox-only` is `--selfcheck-only`. The harness's tests that use
`sandbox.x` as example namespace names, and the docstrings that tell what the sandbox taught, are
left as they are: those are fixtures and history, not the folder.

One slip on the way: a rewrite script changed `:subject :sandbox` in the committed health
RECORD, which is exactly the hand edit a record must never get; restored from `HEAD`, and the record
below is a new run after the move, citing its commit.

Checked: the harness's gates and the root's, exit 0; the selfcheck's own `bb gates` green, red at
`:deps` after `bb break seam`; `bb doctor` from `harness/` shows no `[pin …]` - the project's
`.mise.toml` is below the harness, not above; `bb health --record` after the move (next entry's
record).

### The namespaces, in six groups

Thirty flat namespaces became six groups - `harness.setup` (workspace, init, app, template,
doctor, health), `harness.loop` (orchestrate, driver, triage, log, provision), `harness.contract`
(shapes, packet, stub, sigs, targets, spec-review), `harness.gates` (`run`, which was
`harness.gates` itself; repair; boundary), `harness.models` (runner, runner-check, agent, adapter,
tools, profile, provenance), `harness.money` (balance, report) - with `harness.rules` left where
it was, since every document names the rule source by that name. Files moved with `git mv`, every
reference rewritten longest-name-first (so `runner-check` went before `runner`), test namespaces
with them, path mentions in the documents too; history (`DEVLOG.md`, `RUNS.md`, `runs/`) left as
written. The grouping was the person's observation on 2026-09-22 and was deferred to here so the
sweep would be one commit a reader can skip.

Checked: gate 0 over the moved files, `bb gates` from `harness/` and from the root, the sandbox's
own gates, `bb example`, `bb health --sandbox-only`; a grep for any flat name left outside
history found one docstring, fixed.

### Step E: five rows closed against the health check, and the documents say what the KIT now is

`harness-seed/` is `harness/` (the path replaced everywhere but history). The documents
that still described a seed an adopter copied now describe the KIT an adopter clones: the root
README opens with *clone it and build beside it* and the three installs; `method.md` §03's
Foundation is `bb doctor` → `bb health` → `bb init`, its steps 2–5 say what the template and the
harness already do, and its readiness checklist is four items, one of them the health check;
§04's example exit criteria include *the system boots*, with the third project's story; §12's
step 1 exits on the two verdicts and the health check, and its checklist and closing paragraph
match; `skeletons/03` §7.1–7.2 are the workspace commands and the record to keep; the harness
README's opening, *Adapting it* (what the harness needs of a project, where the mirror is, where
records stay) and *Versions* (floors, one pin, the known-good set, the fork's pins as the
example) are rewritten, its line counts re-counted; `CLAUDE.md`'s bb-only rule says what is
JVM-free and what is not; `PROVENANCE.md` says what "the seed" below it meant; `NOTICE` carries
the fork's upstream MIT notice; the agent file's comment says who copies it where. Plan v1's
sentence that `neil new` would be pointed at the fork is superseded: nothing points `neil`
anywhere, and `neil` left the toolchain in step C.

Register rows 20, 38, 41, 43 and 44 are FIXED against the health check - each row says what was
observed: a generated application's gates green untouched with its own lint and format config
(20); `bb serve` held to 200 (38); the Foundation wording rewritten (41); `babashka = 1.13.223`
seen in a generated `.mise.toml` (43); no licence file in a generated application (44). Row 32
was closed in B.9.

Not done here, on purpose: the namespace regrouping (six groups over 30 flat namespaces) - a
second mechanical sweep, kept apart from this one so each can be read; and the retirement of
`runs/` and `RUNS.md`, which are the evidence behind sentences this log and the README still
make (run `a4`), and whose home after stage 2 is `<name>-plan/` - the person's call.

Checked: `bb gates` from `harness/`, from the root, and the sandbox's own; `bb health
--sandbox-only` after the rename; the NOTICE licence text diffed against the fork's `LICENSE`
(identical but for trailing blanks). NOT checked: every rewritten sentence against a fresh
reader - the first build in a workspace is that reading.

### Step D, second half: the record, the README block, and the first record

`bb health --record`, on a healthy full run, writes `resources/health/<platform>.edn`: when, the OS
and architecture and nothing else of the host, the KIT commit (and whether the tree was dirty),
the template pin, the required tools' versions the doctor saw, and every check's outcome and time -
never its detail, which carries scratch paths. The same run rewrites `resources/known-good.edn`,
so what the doctor shows as known-good is what a health run saw and not what someone typed (the
hand-written set of step C is gone). `bb health-sync` renders every record into the root README
between `<!-- health:begin -->` markers - one row per platform, and the sentence that a platform
not in the table has none - and `--check`, in `bb gates`, fails on drift, the `rules-sync` pattern;
`rules/splice` now takes its markers. The date is the claim: nothing says it still holds today.

The first commit of this half was made on a red lint gate - three unresolved namespaces - because
the command that committed did not read the gate's exit; amended. Then `--record` wrote the
known-good set beside `bb.edn` and not into `resources/`: the resource NAME was spit to, and the
test had masked it with an absolute path. Fixed, and the record below was made after that fix.

Checked, by running it: `bb health --record` on a clean tree - seven checks, 57s, the
record and the known-good set written; `bb health-sync` renders the block, `--check` in sync,
`bb doctor` shows the set with the record's date and platform; the README carries the first row.
Gates exit 0, 417 tests. NOT checked: a second platform (the table's sentence about them is
rendered, not observed); the age of a record shown anywhere (the date is shown; age is the
reader's arithmetic).

### Step D, first half: `bb health` - one piece of code, two subjects

The health check the plan asked for, and the shape decided before B.9: `harness.health` takes a
SUBJECT - a project directory, its gate commands, its nREPL command, its root namespace - and runs
the same checks against two. The sandbox, COPIED to a scratch workspace and made a repository (the
checked-in one is never written to, and the loop's branches land where the check's cleanup deletes
them, not in the KIT's clone; the KIT's `.gitignore` goes with the copy, or `.nrepl-port` is a
file a role wrote). And an application `bb init` generates from the pinned template into scratch,
by the same code an adopter's command runs. Per subject, each recorded as data: every gate green
untouched; every gate FAILED by a breaker at its own key - the breakers are harness data needing
only the root namespace (mis-indented form, unused binding, false assertion, undeclared namespace),
one form per line because the generated application's `.cljfmt.edn` lays out an `ns` form its
own way and the first multi-line test breaker failed at `:fmt`; one trivial task - §03's `clamp`,
in a new namespace declared through `:architecture`, the case that mechanism exists for - through
the whole loop with a scripted runner in every model's seat and everything else real: three
worktrees, two nREPLs by the subject's command, the REPL probe, gate 0, the gates, the review, to
`:awaiting-merge`, then torn down; and, for the application, `bb serve` until `GET /` is 200, the
process tree stopped, the port free again. A busy port is reported, not tried around: the
template's port is configuration, not a flag (a fork-side `PORT` override would change that).
Never part of `bb gates`; `--sandbox-only` skips generation; `--keep` leaves the scratch.

Checked, by running it: `bb health` here - seven checks, 57s, every one ok, both scratch folders
gone afterwards; `--sandbox-only` 16s; from the KIT's root the same. Before the fix, the
application's test breaker failed at `:fmt` and the sandbox's loop stopped `:assemble-refused` -
both recorded above, both by running it. Unit tests cover the breakers' mechanics against shell
gates that fail exactly when a breaker is present, the trivial task, and the scratch sandbox. NOT
yet: the record and the README block (the second half of step D); a machine with a slow first
fetch (the template was cached here); `--keep`'s folder read by a person.

### Step C: the doctor guides, holds one version, and judges twice

Three projects failed `bb doctor` on a version RULE, never on a missing tool: a calendar version
read as a major, a patch level read as a break, a host's pin read as this project's. The rules are
now: every pin - the nearest `.mise.toml`, walking up, or an entry's `:min` - is a FLOOR, compared
over as many segments as the pin names (`temurin-21` is a major; `21.0.12.1` neither exceeds nor
falls short of it), and newer is said and fine. The one exception is the JDK, `:major 21` on its
own entry, the KIT's constraint rather than any file's: another major is *wrong version* with or
without a pin, and a pin on another major with 21 installed is *change the pin, not the JDK*. The
three clojure-mcp-light tools are probed by RUNNING them - `--help`, empty stdin, five seconds -
because in `bbin ls` and on PATH was reported ok for a tool that could not run; each was checked
to answer `--help` in under a second and write nothing before the probe was written.

Two verdicts - *the KIT's gates can run here* (the Babashka tier) and *a loop can run here* (plus
JDK 21, the Clojure CLI, `clj-nrepl-eval`) - and `--tier` says which decides the exit code:
`bb gates` runs `bb doctor --tier gates`, so the KIT's own gates stay JVM-free; `bb init` refuses
without the loop verdict. Every unusable tool prints its fix: a documented command where there is
one (the three `bbin install` lines are from clojure-mcp-light's README, read 2026-09-22), else the
official page. The KIT guides and installs nothing. Beside what is installed the table shows the
KIT's dated KNOWN-GOOD SET, `resources/known-good.edn` - written by hand today from this machine,
which ran the gates, `bb init` and the generated application's gates on these versions; step D's
health check is to write it from its record - and a newer version is *newer than tested*, never a
fault. `clj-depend` and `neil` left the toolchain: the KIT ships its own boundary gate, and `bb init`
replaces scaffolding by hand. The root README opens with the three installs (JDK 21, the Clojure
CLI, Babashka ≥ 1.12.212), one route each, then the doctor.

Checked, by running it: `bb doctor` here, both verdicts yes, exit 0; `--tier gates` exit 0; against
the template's own `.mise.toml` (comment lines, prefix pin, `[alias]`), every pin read, no notes;
against `java = temurin-21, clj-kondo = 2025.06.05, babashka = 1.12.206` (row 20's case) all ok
with *newer than the pin* notes, exit 0; against `java = temurin-25, clj-kondo = 2027.01.01`: the
JDK ok with *change the pin*, clj-kondo TOO OLD with its fix, both verdicts NO, exit 1; with a
failing shim and a hanging shim ahead on PATH, both DOES NOT RUN, the hang cut at five seconds.
Gates exit 0, 408 tests. NOT checked: a machine with a tool actually missing (every fix line is a
documented command or page, none was run), and the README's install routes on a clean machine.

### Row 32: the boundary gate ships with the KIT, and a generated application has layers

Method §09's gate 4 existed once, as `sandbox/dev/deps_check.clj` - outside what an adopter
took - and the pin's `loop.edn` defaults listed three gates because no generated project had a
`bb deps-check`. `harness.boundary` is that check, ported and generalised: a namespace's OWN tree
is whatever shares its first segment, so no ruleset names a root; and a third violation kind,
`:orphan` - an entry with no file - closes the limitation the sandbox README had recorded. That
kind caught the sandbox's own `sandbox.red-seam` entry, declared for a file that exists only while
`bb break seam` is in place; the ruleset's one keyword key, `:boundary/fixtures`, now says so.

THE GATE RUNS AGAINST THE PROJECT, the way `cljfmt` does: `bb --config <kit>/harness-seed/bb.edn
boundary` from the project's directory. A generated application's `bb.edn` carries nothing of the
KIT (the fork stays app-level); the plan's `loop.edn` gains `[:deps "bb --config
{{kit}}/harness-seed/bb.edn boundary"]` with `{{kit}}` made the KIT's ABSOLUTE path at `bb init`,
because the gate runs in a worktree under `work/` and no relative path to the KIT holds from there.
The application's `layers.edn` is data beside the pin - the template's require graph at the pinned
commit, as tails under the root namespace - written into the second commit with `AGENTS.md` and
`CLAUDE.md`; a template shipping its own is kept. `sandbox/` keeps `layers.edn` and `red/` as the
proof the gate discriminates; its `bb deps-check` delegates to the harness and `dev/deps_check.clj`
is gone. `default-gate-seq` keeps `bb deps-check` - the sandbox's key contract and every recorded
run - and its docstring says what the pin writes instead.

Decided before this step, and recorded for STEP E rather than done here: the health check is one
piece of code with two subjects (`sandbox/` for the harness's mechanics, with red breakers becoming
harness data applied to any project; the generated application for the certified pair);
`harness-seed/` is a misnomer since the design inverted and becomes `harness/`; the 27 flat
namespaces group six ways (workspace & setup, the loop, the contract, gates, models, rules & money)
- one sweep with the document rewrite, not two. B.9 stays flat.

Checked, by running it: in `sandbox/`, `bb deps-check` green (9 namespaces), red at `:deps` after
`bb break seam` (forbidden) and after `bb break deps` (undeclared), `bb gates` green after
`bb restore`. The adopter's way in a scratch folder cloning this working tree: `bb init demo` exit 0,
`layers.edn` in commit two; the gate against `demo-app` green - 6 namespaces, so the pin's declared
graph matches the template's - and red on one added `demo.views` requiring `demo.db`; the exact
string written to `demo-plan/loop.edn` run with `:dir` the application, from the application and
from `/tmp`, exits 0. Gates exit 0, 404 tests. NOT checked: the gate inside a full loop run against
a generated application (no run has yet been made against one); red breakers as harness data (D).

---

## 2026-09-21

### Three projects compared; the kit gets its own template and a health check; the register takes the findings

A new line of work, starting from the harness the third project ran on.

The three projects built on the kit were compared, from their records. What the comparison says
about this repository: rework fell from project to project while the money lost to runs that
never merged rose; two rows this register
had marked *fixed* recurred (20, and 19 one branch over); one defect was made by a port (the
tests of row 27); and the question *can ordinary input reach this?* cost a rejection in every project.

**Decided by the person, 2026-09-20/21, and the reason for the branch:**

- The kit is an opinionated framework on Clojure Stack Lite, and the harness is meant to work with
  the template the kit PROVIDES. Copying `harness-seed/` into an arbitrary project stops being the
  supported path. The template will be a fork the kit controls (MIT, as upstream is), with
  `neil new` / `clojure -Tnew create` pointed at the fork — because six of the third project's
  findings were the upstream template's own and cannot be fixed from outside it.
- The first step on a machine is a health check: prerequisites checked by running them; for anything
  missing, a helpful message and the command that installs it — **the kit guides and does not
  install**; then the check again, certifying the kit ready as of that run.
- A health-check project ships with the harness, separate from `sandbox/`: an instance of the kit's
  template, stub models, ending in a server a person opens at `http://localhost:8000`. It is first
  of all this repository's own regression test — row 20 recurred three times because it was checked
  in an empty repository — and an adopter re-runs it after any JDK, Clojure or tool upgrade, or
  points it at the project they are building.
- Versions: JDK 21 is the one constraint with a reason; everything else is a minimum, plus one dated
  known-good set that is the KIT's own, the template's pins compared against it at each refresh. The
  README carries that report, rendered from a committed record.

**In this commit:** register rows 4, 10, 17, 19, 20, 21 and 23 extended; rows 27–44 added. The XTDB
claim is scoped to what was verified: its documentation states a MINIMUM of 21 (read 2026-09-20), and
*fails at class-load on newer JDKs* was seen on 2.0.0 and 2.1.0 and has not been re-checked — three
sites said "needs 21 exactly".

**Tried and NOT established:** that a bbin-installed tool can sit on the PATH and be unable to run.
One machine's write hook failed repeatedly over five days with *Clojure tools not yet in expected
location*, and stopped the day that folder appeared. Two attempts to reproduce it —
`DEPS_CLJ_TOOLS_DIR` pointed at an empty folder, then the same with a cold `CLJ_CACHE` — both ran
`clj-paren-repair --help` to exit 0 without fetching anything. So the cause is still inferred, the
advice *run `bb clojure -Sdescribe` to fetch the jar* is unverified (with an empty install dir it
printed its map and fetched nothing), and no register row was written. The doctor probing tools by
RUNNING them stands on its own merits; this incident is not yet evidence for it.

**Order of work**: the fixes the
template cannot affect, rows 27 and 28 first, one finding a commit so the upstream backport can take
them alone; then the fork, the health-check project, the doctor and the report; then rows 20, 32, 38
and 41 and the rewrite of the documents that still say *copy the seed*; then the design-sized rows
(23's remainder, 31, 10).

### Row 27: the seed's tests no longer need its placeholders standing

The README tells an adopter to fill the three rule-source placeholders; two tests added with the
placeholder check itself asserted the SHIPPED file still had three, one of them by reading
the live file through `start`. Filled, the seed's test gate went red: 3 failures, 1 error. Both now test
the mechanism on a fixture - `orchestrate_test` by redefining `rules/load-rules`, so `start` is shown
a source with three fixture placeholders and then a filled one. Reproduced before and after in a
scratch copy with every placeholder replaced (`bb rules-sync && bb test`): old tests exit 1, new exit 0.
This had to precede any health check, whose project fills its placeholders by construction.

### Row 33: a worktree's REPL ignores the personal `deps.edn`; `CLAUDE.md` names the driver that exists

The Clojure CLI merges `~/.clojure/deps.edn` into a project's, so a personal alias with a project
alias's name contributes its `:main-opts`. The default `:nrepl/cmd` gains `-Srepro` at both sites
that state it. `sandbox/` defines its own `:nrepl` alias and resolves under it. `CLAUDE.md` said the
driver was `harness-seed/dev/run_loop.clj`, a file promoted to `src/harness/driver.clj` long ago.
Row 28's suspect test was checked too: it already prints its fallback reason, so nothing to change.

### Row 19, the other branch: an amendment makes a GATE result history too

The fix of 2026-09-18 made an amendment set a rejected review aside. A red gate answered by an
amendment took the same wrong path one `cond` clause lower: `gated-current?` was still true - no
role had written since - so `next-action` sent the recorded red result to triage, against a spec
that no longer said what the gate had judged. `amended-since-gates?` now sends it to `:check` first.
`gated-current?` keeps its meaning (the gate worktree holds the latest files), because `record` and
`merge` depend on it and an amendment changes no file. Replayed on the recorded events of the run
that met it: `:triage` before, `:check` after. The lesson is about the first fix, not this one: it
was written to the example in front of it, and the register said *fixed*.

### Row 29: what the spec-review limit counts, when a review stops the loop, what makes a spec new

Three decisions, the person's. The limit counts reviews that FOUND something: a spec whose first
reading was clean had reached it on its first amendment. A review with nothing on its list no longer
stops the loop - the stop exists so the list is read - though an answer with no findings block at all
still does, since nothing was recorded. And a review is of the spec WITHOUT `:files/context`: removing
two paths from that list had made *a different spec* and bought a review. That last one has a price,
written into the register: a context file can change what a target means. `method.md` section 07 says
all three.

### Row 34: the Reviewer and triage are given the question only the Architect had

Two rules met on one finding and pointed opposite ways. *Types are followed as the language defines
them* put `(keyword "")` in the domain; *can ordinary input reach it* put it out, because the
project's content came only from a file format that cannot write it. The Reviewer held the first rule
and rejected; triage routed the Coder; a round was paid; the Architect, who alone held the second, was
never asked. The person approved one sentence for where they meet - **the type decides what a value
is; where the project says its input comes from decides whether it can occur** - and it went to the
Reviewer's deliverable, triage's rejection prompt, the types rule, and `method.md`. It is narrow by
design: this rule was born from an Architect twice calling a reachable input unreachable, so only a
source the rule source names counts, and absent one every value the type admits can arrive.

Triage had never been shown a rule. It is now shown one, on a rejection only: the project's
`:data-conventions`, once filled. Nothing here has been run against a model.

### Row 4, a third time: what a Tester does with a target it cannot test

Capped Tester dispatches had three causes in three projects, and the deliverable had answered two:
tests re-run against a stub that throws, and a file bracket-checked as a string. The third was a target
whose fixture did not exist - turn after turn searching the classpath, no file, no note, a failed
dispatch. The deliverable now gives the general form as well as the case: do not search; write the
rest; mark the target *not a test* with what is missing; say so with `note`. The exemption keeps the
`targets` gate honest and the note pauses the loop, so the gap reaches the Architect for the price of
a triage call instead of a run.

### Row 30: a context file need not be Clojure

`:files/context` is whatever a role should be handed, and the signature check read all of it as
Clojure: a Markdown note on a library - which `method.md` recommends - and a fixture that is
unreadable on purpose each refused a correct spec, under a message that named neither the file nor
the reason. Only source files are analysed now, in `violations` and in the `calls` gate, and the
precondition failure prints every violation.

### Rows 35, 36, 37, 39, 40, 42: six things the method and the skeletons now say

Words, no code. A value handed into a function crosses a seam, and what its caller guarantees is
written like any other guarantee (rule 4; the architecture skeleton's seams note). A spec review's
count is one sample, and a zero is not a pass (section 07). The project's own gates run before any
commit a worktree is cut from (rule 7). Naming a shape does not grant it (the blueprint skeleton). A
project decides who owns presentation after a merge (a new rule 11 - at the end, because rules 4, 6,
7 and 10 are cited by number elsewhere; the first draft of this edit renumbered them and was undone).
And the seed README's line on what a spec review costs was rewritten (since 2026-09-28 it quotes
no price: a project's own records give the figure).

That ends the fixes the kit's own template cannot affect, except the design-sized ones. Rows 20, 32,
38 and 41 wait for the template; rows 10, 23 and 31 want design.

### The design turned over: the kit is what is adopted, and it brings a pinned template

Superseding this day's first entry where they differ. The person's decisions, reached in discussion:

- **The kit is the thing an adopter takes**, and it brings a specific, versioned, tested
  `ontopro/clojure-stack-lite` with it - a PINNED dependency. The fork (public, MIT, default branch
  `kit`, first tag `kit-v1`; its `master` stays an exact mirror of upstream) carries only what the
  application needs on its own: nothing kit-specific, no copy of the harness. The earlier sentence
  *`neil new` / `clojure -Tnew create` pointed at the fork* is withdrawn; so is any idea of the
  template scaffolding the harness. There is one harness, here, and nothing to keep in sync.
- **The adopter's sequence:** clone the kit, install the README's prerequisites, `bb doctor` until
  it certifies the kit ready, `bb init <name>`, `bb serve`.
- **A workspace is three sibling git repositories inside a plain folder** - the kit, the
  application, the plan - as the private harness this one was extracted from already does. The
  person's three reasons: separation of concerns; an adopter may use another framework or none
  (the harness never looks inside one - supported is not the same as possible, and only the pinned
  pair is certified); and three independent lifecycles. So `bb init` is two separable parts -
  create the workspace, generate the application - and the template is named in data.
- **Moving a project's state into its plan folder is a later stage.** Until then it stays beside
  the harness on the adopter's own branch of the clone. One part cannot wait: the harness has to
  be told where the project is.

### A root `bb.edn`: the kit's front door

`bb doctor` and `bb gates` run from the root of the clone. Each delegates to `harness-seed/`;
nothing is implemented twice. ONLY tasks without a path argument are offered there, because a
relative path typed at the root would be read from inside `harness-seed/` and mean another file -
`run-loop`, `balance`, `report` and the rest stay where they were. Checked: `bb doctor` prints
the same report from both places, `bb doctor --edn` passes its flag through, and `bb gates` exits
0 from the root and, unchanged, from `harness-seed/`.

### The template pin, as data

`harness-seed/resources/template-pins.edn` names the template the kit brings:
`io.github.ontopro/clojure-stack-lite` at the full commit behind the fork's tag `kit-v1`, with the
deps-new version that generates from it. `harness.template` reads it and builds - purely, as an
argv - the command `bb init` will run: `clojure -Srepro -Sdeps {…} -X org.corfield.new/create`,
with deps-new and the template as plain dependencies, so an adopter installs no Clojure tool
first. A second entry, or a different default, is a data change; an unknown name is an error that
lists the names, never a fallback. `KIT_TEMPLATE_LOCAL` will point generation at a local clone for
someone developing the template (the function takes `:local-root`; nothing reads the variable
yet - that is `bb init`'s).

Checked, not assumed: the argv the function returns for the shipped pin was RUN, from a directory
with no clone in it, and produced a project carrying the fork's two test namespaces and its dated
pin file. That answers the plan's first open question - deps-new does generate non-interactively
from a pinned git sha. What it needs cached to run OFFLINE is still not known: each run printed
*Resolving … as a git dependency*.

### A name: the KIT

The template fork's working branch is `kit` and its tags are `kit-v1`, `kit-v2` …, and a project's
workspace keeps its clone in `kit/`. So the product gets a written name of its own: **the KIT**,
short for *the Clojure Agent Kit* - the person's decision. The root `README.md` defines it and
lists the three lowercase uses, the fork's README says the same from its side, and `CLAUDE.md`
makes it vocabulary. The one confusion worth heading off is the tag: `kit-v1` is version 1 of THE
TEMPLATE as the KIT pins it, not version 1 of the KIT. Existing prose is not swept - this log is
history, the register is summarised before `main`, and the adopter-facing documents are due a
rewrite anyway.

### The harness is told where the project is

Until now the project was wherever the run directory was: `git rev-parse --show-toplevel` there,
applied AFTER `loop.edn`, so nothing could say otherwise. That was true of every run recorded so
far, because the harness had always been copied into the project it built. In a workspace - the
KIT, the application and the plan as sibling repositories in a plain folder - a run directory
under `work/` is in no repository, and one inside the KIT's clone would have had worktrees of
the KIT cut and called the project.

`driver/project-root` decides, most explicit first: `:repo/root` in `loop.edn`, relative to the run
directory; the application named by the nearest `workspace.edn` at or above the run directory
(`driver/find-workspace`); and only then the run directory's own repository, which keeps every
existing run and test as it was. Whatever is chosen must BE a repository, and an error says which
of the three it came from - a typo in `workspace.edn` should not surface as a failed
`git worktree add`. In a workspace the worktrees also move from the system's temp folder to
`<work>/worktrees/<run-id>`.

This is the one part of moving a project's state out of the KIT's clone that could not wait; the
rest - rule source, profile, `loop.edn`, records into the plan folder - is a later stage.

Checked: a table of the three sources and the three errors; and one whole loop, to the merge
stop, with the run directory in no repository and the application beside it - which passes, and
errors on the driver as it was before this change (`{:pass 3}` against `{:error 1}`, run both ways).

### `bb init`, the first of two parts: the workspace, and nothing about any template

`bb init <name> [dir] [--dry-run]`, at the KIT's root or in `harness-seed/`, creates the workspace:
`workspace.edn`, a `README.md`, a `CLAUDE.md` that imports the KIT's, the off-loop agent file under
`.claude/agents/`, `<name>-plan/` - its own repository, one commit: `docs/` copied from `skeletons/`
with the tree kept so the skeletons' relative links still resolve, a README, `loop.edn` defaults -
and `work/runs/`. **It does not generate the application yet**, and says so when it finishes: the
workspace NAMES `<name>-app` and the harness reports, as before, that the folder is not a
repository. That is deliberate, not a stub left in: `harness.init` takes the application as
`:app-fn`, a function of one folder, and what only a generator knows (`:rule-mirrors`,
`:loop/defaults`) as arguments. Bringing your own application is this part plus a repository put
there. The second part supplies the function for the pinned template.

`layout` is pure - every path and every file's content as data - `refusals` is pure over `survey`'s
facts, and `create!` writes what `layout` said. So the command prints every path before it writes
one, and refuses, giving every reason at once, when: the name cannot be a folder and a namespace; it
is not run from a clone of the KIT; the target is inside the KIT's clone; any planned path exists
(a folder is named once, not once per file; a bare `.claude/` is no conflict); the DEFAULT target -
the folder the clone is in - holds other repositories (an explicit `dir` is consent and is not
second-guessed); or `bb doctor` has a required tool unusable. The KIT is recorded relatively when it
is inside the workspace, so the folder can be moved whole, and absolutely when it is kept elsewhere.

It writes nothing into the KIT's clone, so the application's rule mirror cannot go in
`../rule-mirrors.edn`. It goes in `workspace.edn` as `:workspace/rule-mirrors`, and
`rules/mirror-paths` - which replaces the function `harness-seed/bb.edn` kept in its `:init` - reads
the seed's list, the repository's, and the workspace's. For that the workspace lookup moved out of
the driver into `harness.workspace`: the driver requires `harness.rules`, so the rules could not
have required the driver. It now also makes `:workspace/kit` and the mirrors absolute.

Checked, by running it: `bb init xyx --dry-run` at this clone's root refuses, because
a `CLAUDE.md` is already in the folder the clone sits in; `bb init demo <scratch dir>` from
`harness-seed/` exits 0 and leaves 20 paths, a plan repository with one commit and a clean tree, and
an absolute `:workspace/kit`; a second run exits 1 naming what exists; an unwritable target gives a
sentence and not a stack trace; `../ws-rel-check --dry-run` means `KIT/ws-rel-check` at the root and
`clojure-agent-kit/ws-rel-check` in `harness-seed/`, as the docstring says - and the second is then
refused as inside the clone. `harness.init-test` does the same in a temp folder, and checks
`git status` of the KIT is unchanged by a creation. NOT checked: a machine whose git has no
identity (the plan's first commit would fail, with git's message); a session actually started in a
created workspace reading the imported `CLAUDE.md`.

### KIT development has two parts; the workspace `CLAUDE.md` orients and imports nothing

Decided by the person, 2026-09-21, after `bb init` was whole. **Two parts, not options.** The
DEVELOPER session starts in the development folder - `KIT/`, a plain folder holding the
development clone `clojure-agent-kit/` - and is aware of every experiment beside it: it reads their
records and findings and ports what it learns. An EXPERIMENT is a real adopter workspace made the
adopter's way, `KIT/xyx/`, replacing the hand-built experiment folders of the three projects so far:
`mkdir KIT/xyx && cd KIT/xyx && git clone ../clojure-agent-kit && cd clojure-agent-kit && bb doctor
&& bb init xyx`, then an ADOPTER session started in `KIT/xyx/` in another window. It reads what an
adopter reads and nothing else; its findings stay in the experiment; a fix lands in the development
clone and `git pull` in the experiment's clone is the upgrade path, tested each time.

Two consequences. **The development folder has no `CLAUDE.md`.** Claude Code loads a parent
folder's `CLAUDE.md` at start, so one in `KIT/` would reach every experiment session below it with
the KIT's own development rules; `clojure-agent-kit/CLAUDE.md` loads on the first file read in the
repository, which comes before any edit. And **the workspace `CLAUDE.md` `bb init` writes imports
nothing.** It had imported the KIT's `CLAUDE.md` - the working rules for an agent CHANGING the KIT:
run its gates, write its logs - which would have handed those to a project's Architect. It is now
orientation only: whose session this is, the four folders in a line each, where to read (the method,
the loop's commands, the plan, the application's `AGENTS.md`, the off-loop role), and one sentence
that the KIT's own `CLAUDE.md` is the KIT's rules and not this project's. It restates no rule: the
rule source stays the only place a rule is written.

One guard, from the discussion: `bb init xyx` run from the DEVELOPMENT clone would have made `KIT/`
itself a workspace, and nothing refused it - one repository, nothing planned exists. The default
target is now refused when the folder already holds workspaces (a subfolder with `workspace.edn`),
saying to make the workspace folder and clone the KIT into it. An explicit `dir` is consent, as
before. Also closed here: the root `README.md` name table and `CLAUDE.md`'s vocabulary rule said a
workspace's KIT folder is `kit/`; it is `clojure-agent-kit/`, the name `git clone` gives it.

Checked, by running it: in `KIT/` with a throwaway `zz/workspace.edn` beside the clone, `bb init xyx
--dry-run` refuses naming `zz` and the development folder; without it, after `KIT/CLAUDE.md` was
deleted, the dry run passes. The adopter's four lines in a scratch folder, cloning this working tree:
exit 0; the generated `CLAUDE.md` has no `@` line, and each of the six paths it names exists
(`test -e`, all six). Gates exit 0, 401 tests. NOT checked: a Claude Code session actually started in
a generated workspace, and what it loads.

### `bb init`, the second part: the application, from the pin

`harness.app/app-fn` is the function the first part was waiting for. It runs the argv
`harness.template` builds from the pin, beside the application's folder; commits the result
UNTOUCHED, the message naming the template, its tag and its commit; then writes `AGENTS.md` - a
hand-written frame whose marker block `rules/sync!` fills from the KIT's rule source - and a
`CLAUDE.md` stub importing it, as a second commit of exactly those two files. `git diff` against
the first commit is, for ever, everything done to the application after generation. A template
that ships its own `AGENTS.md` or `CLAUDE.md` keeps it and the command stops; a generation that
fails carries the last fifteen lines of its output; one that exits 0 and leaves nothing is not a
success. `KIT_TEMPLATE_LOCAL` replaces the pinned commit with a local clone, and the first commit's
message then says *NOT the pinned commit*.

What a run must be told about this template's projects is DATA beside the pin, `:loop/defaults` in
`template-pins.edn`: the three gates a generated `bb.edn` has, which replaces the harness's default
sequence and with it `bb deps-check`, a task no generated project has until the boundary gate ships;
and `clojure -Srepro -M:test:nrepl`. `bb init` writes them into `<name>-plan/loop.edn`, and the
application's mirror into `workspace.edn`. `harness.init` still requires no template: its `-main`
is the one place the two parts meet. The command's ending is now `cd <name>-app && bb serve`.

Checked, by running it: `bb init demo <scratch dir>` from the KIT's root, generating from the
pinned GitHub commit - exit 0, under four seconds with the template already fetched (a first fetch
was not timed); the application has the two commits and a clean tree; in it `bb fmt-check`,
`bb lint` and `bb test` each exit 0, untouched. And the DEFAULT layout, in a scratch folder holding
only a clone of this repository with the working files copied in: `bb init demo` exits 0, records
`:workspace/kit "clojure-agent-kit"`, leaves the clone's `git status` as it was, and from that
clone `bb rules-check` lists `demo-app/AGENTS.md` in sync - then exits 1 naming it after one word of
its block is changed. `harness.app-test` replaces the JVM with a function that writes files, and
runs git for real. NOT checked: `bb serve` on THIS generated application (the fork's own check of
it is the evidence so far; step D makes it part of the health check); a run of the loop against a
generated application with these `loop.edn` defaults; a machine with nothing fetched and no network.

One finding, register row 45: a KIT kept OUTSIDE its workspace - which an explicit `dir` allows -
walks up from `harness-seed/` and never finds `workspace.edn`, so its rule tasks do not see the
application's mirror. `bb rules-sync <path>` works there and was run; the check does not.

---

## 2026-09-18

### The `:openai` shape reads cached tokens

A paid probe of serving the `claude` seat's Anthropic roles through OpenRouter found that a profile edit alone turns prompt caching off, that the record could not have
said so either way, and that Vertex's cache misses intermittently where Anthropic's does not. One
thing from it is ported here, because it is small, tested, and everything downstream of a record
depends on it: `parse :openai` now reads the cached-token fields. The rest of what the probe found is
in `NOTES.md` row 25, not done.

It goes in before the next project copies the seed, so that project's baseline has it rather than
carrying it as a divergence from its first commit.

### Port from the second project, tier 3: a place for what the project's data is, and three things about reading a contract

The most productive thing written during the second project was not code and was not in the seed:
a rule saying what a hiccup tree is there and how it is walked. Many of that project's first
spec-review findings were that one question, asked a different way by each spec. The seed had two
placeholders — layers, and the validation library — and neither was for it; and the one the
project's brief pointed at for the seam's guarantee does not reach the Tester. So there is a third,
`:data-conventions`, for every role (`NOTES.md` row 24), and the architecture skeleton's seams
table gains the column it is filled from.

With three placeholders, the first project's finding that an unfilled one reaches agents as literal
text (row 15) could not stay open. Its proposed fix — `rules-check` fails — does not work: the seed
ships with them standing and must pass its own gates. `start` lists them instead.

`method.md` §06 gains two rules on writing targets and an extension to two more, and §07's spec
review says what it buys beyond rounds and what its cap costs. Rules 9 and 10 are both about the
Architect, who in both projects was the session at the seat: *emphasis is a promise* — a person
deleted three words where the Architect had proposed a bound and a rule — and *a finding is dismissed
by asking whether ordinary input can reach it*, which the Architect got wrong in both projects on
the same finding.

### Port from the second project, tier 2: the record tells what a reviewed run cost

Asked whether the first port had covered the run record, the answer was *partly, and then it was
outrun*. Tier 3 of that port put the spec review into the record as one event — correct for a command
typed once before `start`. Two commits later `start` ran the review itself, stopped, and reviewed an
amended spec again, and the record-keeping stayed where it was: the last reading only, and no review
cost in any total. Nothing new had to be collected — `spec-review.edn` kept every reading already,
to enforce the two-review limit — only carried over and printed. `NOTES.md` row 23.

Kept: `runs/README.md`'s line that a review is a desk step and not a row, and the meaning of
`:run/cost`, so that no published report changes. Not done, deliberately: events for the
`:spec-reviewed` stops that happen before a run has state.

A design outrun by the next two commits is the same shape as the rule-mirror list in tier 1: the
mechanism was right and nobody re-ran the case it was for once the ground had moved.

### Port from the second project, tier 1: five fixes a second project's stops paid for

The seed was copied a second time and built the same small
website again, this time with the spec review, stop owners and the first port's fixes. Its
evidence stays with that project, as
the first project's does. It stopped for a person far less often than the first — and two of
its problem stops had a cause the first project had already written down. This tier
is the mechanical half of what it found; `NOTES.md` rows 11 and 19–22 have each one.

- **The leak check no longer refuses the contract's own words** (row 11). Open since the first
  project, where it was `view`; it cost the second a person-owned stop over `header`, which is a
  property target's own word. The row had asked for a design and a word list. The design is one
  sentence — the Tester already holds the title and the targets, so a word that stands in them
  discloses nothing — and needs no list.
- **An amendment makes the review before it history** (row 19). The loop re-triaged a rejection of a
  contract that had just been changed to answer it.
- **A copy of the seed passes its gates as copied** (row 20). Moving the mirror list to data after the
  first project fixed the mechanism and left the defect: the data still shipped this repository's
  sandbox. A fix is not finished until the failing case has been run again, and this one had not been.
- **The spec review's rule block is labelled** (row 21) — changed, not measured.
- **A writer cut off at its cap is named at the stop** (row 22).

Each was checked against the second project's own records rather than a constructed example: the
refused retry replayed through `leaks`, a run's events through `next-action`, its records through
`cut-off-writers`, and the working tree's seed copied into an empty repository and gated there.

### Stops have owners; a spec that keeps drawing findings stops for a person

The user wants the retest as automated as possible: a spec review's findings go to the Architect, who
amends and continues, and a person is asked only when a problem is beyond an amendment. The Architect
is the seat, so this is mostly a protocol between the person and the session driving the loop — but
two things belong in the harness so the protocol is visible and bounded. `orchestrate/owner`: a stop
is the Architect's when its answer is an amendment (the spec review, any `architect` route) and the
person's otherwise; the announce line and `run-task!`'s return carry it. And `spec-review.edn` keeps
the history of reviews of a run's spec; when a spec has drawn findings twice and is amended again,
`start` stops `:spec-review-limit` — a person's stop — rather than review it a third time
(`:spec-review/max`, default 2). The amendments themselves were already recorded by `amend`; the
person reviews them in a batch at the end of a stage.

### The performance section: time and money, derived from the record

The user asked for a report after every run with total time, ACTIVE time (not waiting on a person),
the OpenRouter funds, and time and cost per model and provider — accurately — and agreed six additions:
cost by role, rounds and their causes, tokens with the Anthropic cache share, effort per role, measured
vs list-priced, and the mechanical share. It is a second section below the footer, so the step table
keeps its shape and a record from before a field says "not recorded".

- **Instrumentation, three things:** `event!` stamps `:at` (absolute) into the state's copy of every
  event, not only the log line — the record's `:event/at-ms` is an offset from a counter that
  accumulates across invocations, which is the figure that once read below the sum of the steps;
  `start` and `record` write a `:balance` event from OpenRouter's key endpoint; `record` copies each
  role's model, family and effort into `:run/roles`.
- **Derivation** (`report/performance`): waiting is the gap after each `:stopped` event until the next
  event — a person reading and deciding — so active = total − waiting, per stop. Anthropic stays
  spend, since its API has no balance endpoint, and the line says so.
- **The four published reports were re-rendered** (71 lines added, none changed above the footer);
  `report-check` is green. Their sections say "not recorded" for time, balance and roles, which is
  the truth about them.

Prompted by my telling the user the triage measurement "took 25 minutes" when the timestamps said 18,
and the calls under two: an estimate repeated after the fact as if it were the measurement. Method §10
now has that as its thirteenth item.

### Triage runs at low effort

`claude.edn`'s `:orchestrator` goes from high to low, on a measurement over the first adopter's
saved triage prompts: re-asked at low, blind, they gave the same route on all but one
uncontested call, the same split on the contested one, and on the single disagreement the better
route under the ambiguity rule — for a little less. Not half: triage's cost is its prompt, and effort
changes only what the model writes. The comment that said "high, because this is judgement work"
now says what was measured. `portability.md`'s two tables updated (and the agy-ide Reviewer's medium,
the spec-reviewer rows, which the two-seat table had not caught up with).

### The spec reviewer is a role of its own

The review borrowed the `:reviewer` role. That hid which model reads specs, tied its effort to the
Reviewer's, and let nothing check its independence from the spec's writer. The user decided it should
be explicit: `:spec-reviewer` is the fifth role in `Profile` — required, no fallback to `:reviewer` —
and a verifier in `harness.profile/verifiers`: the Architect who writes the spec is the seat, the
seat's family is the Coder's by convention, so `bb profile` checks it against the Coder's family as
it does the Tester's and the Reviewer's. Both shipped profiles have the entry (claude: the Reviewer's
model, the configuration measured; agy-ide: its Reviewer's, unmeasured); `portability.md`'s table
has the row; method §05's role table has the role.

The agy-ide entry first said effort high with an "unmeasured" note; the user caught it. Fable 5.1 WAS
measured at both efforts on the claude seat, and high cost nearly twice as much a call for no more
recall than low — so the entry is low, and the comment says what was measured and what was not.

Same file, same day: the Reviewer (Fable 5.1) goes from high to MEDIUM effort, the user judging high
overkill for a review; the comment records the one measurement behind it (high found nothing more than
low on the spec review, at twice the price) and that medium is untried on this seat.

### The spec review joins the loop; the money is printed around every run

Two changes the user asked for before the second project:

- **`start` reviews the spec itself.** A hand-run command is a habit, and the point was to have the
  loop enforce it: `start` now runs the review for any `spec.edn` that has no review of THIS spec beside
  it (the hash is of the spec as data, so a comment edit does not re-review and a target edit does),
  prints the list, and stops `:spec-reviewed` before anything is provisioned; the next `start`
  proceeds and records the count. `run` treats that stop as a stop. `:spec-review? false` in
  `loop.edn` skips it — the seed's own tests, and a deliberate re-dispatch.
- **`bb balance`, and two lines around `run`.** OpenRouter's key status is a balance (`/api/v1/key`);
  Anthropic's API has none, so its half is SPEND from the run's steps, and the line says which is which.
  The first adopter was stopped by each provider's credit once, mid-run, with no warning.

**A cost this change incurred.** Two `driver_test` fixtures build their config by hand, without
`resolve-config`, so they had no `:spec-review?` key and the default is on: the first test run after
the change made two live spec-review calls through the shipped profile, a few cents in all, before the
fixtures were given `:spec-review? false`. A default that spends money when a key is absent is the
kind of default this repository keeps finding; it is on because the user wants the review in the
loop, and the tests now say so explicitly.

### Port from the first adopter, tier 3 · the spec review as a step, and the documents

- **`bb run-loop spec-review <run-dir>`** (`harness.spec-review`): the Architect's desk step. One
  call, no tools, to the profile's `:reviewer` role — a different family from the Architect who
  wrote the spec, on purpose — asking for every two-readings place and every unmentioned input.
  Writes `spec-review.edn`; refuses once `state.edn` exists; `start` records what it finds as a
  `:spec-review` event, and the report footer prints the count. An answer with no findings block
  writes nothing, so a silent failure cannot be recorded as a clean review. The prompt carries the
  type rule in the review's own voice, because in the rules alone it changed nothing. Tested
  against a stub model, including that the Architect's comments in `spec.edn` never reach it.
- **`method.md`**: §05 gains what independence does not protect against — a shared misreading —
  and why the spec's reviewer is another family; §06 gains the eight target-writing rules and the
  type rule as the user stated it; §07 gains Step 2½, the spec review. The Blueprint skeleton asks
  what each seam guarantees, and records the spec review's count per packet.
- **`NOTES.md`** rows 10–17, open, one per finding that wants design rather than a patch; row 18,
  watch, for the majority-of-three proposal. `runs/README.md` states the footer line and that
  "whether a reader would have agreed" names the reader.

**Where the evidence is.** The first project is a repository of its own — by the user's decision,
nothing from it is brought in: near-term it is there for reference, and as the harness matures it
becomes noise. The register's rows therefore say what was found in the KIT's own words.

The three tiers are the whole port. Not ported: that project's own rule text and mirror path,
the majority-of-three (a row, not code), and every change to the loop's design (rows 10–17).

### Port from the first adopter, tier 2 · two prompt changes, provisional; two rules a person set

- **The Reviewer says what each finding breaks** (row 7): `target N`, `slice`, `dependent` or `none`, per
  reason; `runner/review-verdict` parses the maps and a bare string still reads. A few reviews of
  evidence, all the right way — provisional.
- **Triage may not describe the implementation to the Tester** (row 8): the leak check sees names,
  and a fact is not a name. A couple of clean guidances of evidence — provisional.
- **Two rules, set by the user from reading verdicts** (row 9). *An ambiguous contract is fixed, not
  interpreted* goes on triage's `architect` line, because blind samples of one rejection divided on
  exactly that policy. *Types are followed as the language defines them*, with its corollary about
  construction order, goes into the rule source for every writing role and into triage's own prompt —
  every reader of one contract, this analyst included, had called a vector's order "unstated". It was
  tested before it came here: the sentence alone did nothing, the corollary did. General on purpose;
  the vector was the instance, not the rule.

Tier 3 — the contract review as a step, and the documents — follows.

### Port from the first adopter, tier 1 · three plain bugs and two evidenced behaviour changes

The kit's harness was copied, untouched, into a throwaway project — a small static
website, in a repository of its own (nothing about it was written here until now, by the user's
rule). The copy's diff against the harness as copied is the mechanical record of what
the project had to change. This commit ports the part with the strongest evidence, with
the tests written there.

- **Three plain bugs** (rows 5, 6, and `bb.edn`): a retried role dispatched into a worktree whose file
  gate 0 had repaired somewhere else; a provider-refused dispatch published as "synthetic"; and
  `rules-check` hardcoding this repository's sandbox mirror, which made the untouched copy fail its own
  gates on the first command. The mirrors are now data, `resources/rule-mirrors.edn`, and `bb rules-sync`
  with no arguments syncs every one.
- **Two behaviour changes with counts behind them** (rows 3, 4): `write_file` repairs and lints on write
  — rounds lost to lint before it, none after — and a Tester deliverable
  that says when to stop, confounded with the sync fix and marked so.

**Not ported, on purpose:** that project's own rule text (its layers, its one seam, its page-set
guarantee) and its mirror path. Those are what an adopter writes into the placeholders; what they
taught about the placeholders themselves is tier 3.

Tiers 2 and 3 — the two prompt changes, the two rules the user set, the contract review as a step,
and the documents — follow in their own commits.

## 2026-09-17

### Milestone 3 · The verdict, a rejection routed, and a merge a person types

The loop now reads the Reviewer's verdict, routes a rejection the way it routes a red
gate, and stops for a merge it never performs. `bb run-loop merge` performs it.

**The Reviewer ends with a block, and a missing block is not a failed dispatch.**
`runner/deliverables` asks for a trailing fenced JSON `{"verdict": "approve" | "reject",
"reasons": [...]}` and says what `reject` is for — a broken target, slice or dependent —
because a rejection costs a paid round and a Reviewer that rejects on taste spends the
cap. `runner/review-verdict` parses it into `:runner/meta :verdict` as `{:verdict
:approve|:reject :reasons [...]}`; `AgentResult` stays closed. The key is present on
every Reviewer result and nil when there was no block, a block that does not parse, or
a verdict that is neither word. Upstream fails the dispatch in that case, which throws
away findings that were paid for; here the dispatch is `:done` and the LOOP stops, with
`:no-verdict`, for a person.

**`next-action` reads the verdict, and nowhere else does.** `approve` is the only way to
`:awaiting-merge`. `reject` under the cap is a third triage trigger, `:rejection`; at the
cap it is the same `:capped` stop a red gate gets, with nothing asked. Triage is shown
the verdict's reasons, the findings in full, and the Reviewer's NOTES — register row 1
said the note would matter once rejection routing existed, and a rejection whose real
finding is "the targets do not say" is the architect's only if triage can see it. A
`coder` route retries with `[:triage :reviewer]` feedback, guidance first. An
`architect` or `human` route is a new stop kind, `:rejected`, and the model's failure to
answer falls back to a person: `propose-routing` reads a gate's output and a rejection
has none.

**Four things the plan did not say, found while building.**

- *The plan's wiring was a dependency cycle.* It had `runner/outcome` call
  `triage/parse-verdict`; `triage` requires `driver`, which requires `runner`. The parser
  moved to `harness.agent/last-json-block` — the one namespace both sides already sit
  above — and `triage/parse-verdict` delegates to it.
- *A Tester routed on a rejection gets the guidance and nothing of the review.* The plan
  said "tester only via `leaks`, else `:human`". `leaks` refuses ANY `:reviewer`-sourced
  feedback, because the Reviewer read the diff — so sending the findings would have
  escalated every Tester route without exception, and the route would have been
  decoration. `triage/feedback-for` now takes the trigger: on a rejection the Coder gets
  the findings and the Tester gets triage's guidance alone, which `shield` then checks
  like any other. The prompt tells the model that its guidance is all the Tester will
  be sent.
- *The old `reviewed?` would have routed one rejection for ever.* It looked at the gate
  runs and the Reviewer's dispatches only, so after a routed Coder retry the last of
  those was still the rejection: triage again, another paid round, until the cap.
  `current-review` looks at every dispatch, so a role sent back makes the review before
  it history and the next action is `check`. Table-tested, and the mutant that restores
  the old filter is killed.
- *The stop has said "read the Reviewer's findings above" since milestone 1, and the
  loop never printed them* — only the dispatch's one-line summary and its notes. Nobody
  needed them at a stop that could only `record`; a person deciding a merge does.
  `dispatch!` now prints the Reviewer's verdict, its reasons and its findings.

**The merge is `driver/merge!`, and the refusals are a pure function.**
`merge-refusal` over the state and the decision: already merged · no decision · the
loop's last `:stopped` event is not `:awaiting-merge`, or anything but a mutation check
(or this command's own unfinished attempt) followed it · the gate run is not current.
A hand-driven run reaches a merge by typing `run`, which costs nothing over a finished
review and keeps the verdict logic in one place. Then, in order: the gate worktree's
diff is taken again and compared with the `.diff` the Reviewer was shown — not in the
plan, and the reason is that `gated-current?` reads events and an edit by hand in the
gate worktree leaves none; one commit of the gate worktree on `<task>-reviewer`,
`<task-id> — run <id>`; a `:merge-commit` event carrying the decision, recorded BEFORE
the merge like every other decision here; `git merge --no-ff --no-edit` in the base
checkout; `:merged`; `record!` (status `:merged`, from the event, whatever the last stop
said); `teardown!`, which keeps the branches. A merge git refuses is aborted, recorded
as `:merge-failed` with git's output, and tears nothing down; run again, it reuses the
commit it already made. Denying a merge is not a command. `run` typed after a merge is a
`:merged` stop rather than a review of worktrees that are gone.

**What upstream does differently, for the backport.** Its human gate is `approve? [y/n]`
on stdin inside `run-task!`; it commits before the review because its diff is
`main...HEAD`; its one worktree means there is no question of WHICH bytes merge; a
`reject` escalates to a person unrouted; and its teardown deletes the branch.
`PROVENANCE.md` has the two new divergence entries.

**Tests.** `orchestrate_test.clj`, in upstream's shape against a scratch repository
with real worktrees, real assembly and real gates: the happy path merged onto the
scratch repo's `main` as one commit under a two-parent merge commit, recorded `:merged`
and torn down with the branches kept · a merge nobody decides awaits, branch and worktree
in place · a rejection routed to the Coder, retried with the findings, reviewed again and
approved · routed to the Tester with the guidance alone · with leaky guidance, stopped ·
routed to the architect, stopped, and refused by `merge` · no verdict, then a fresh
review by `check` · a gate worktree edited after the review, refused · a run that moved
on after the stop, refused until `run` reviews it again · a merge git refuses, aborted
and re-run · the review printed above the stop. `next-action`'s table gains approve,
reject under and at the cap, no verdict, a role sent back, a failed Reviewer and merged;
`driver_test.clj` has `merge-refusal` and `awaiting-merge?` as tables;
`api_runner_test.clj` the verdict's parsing; `triage_test.clj` the rejection prompt, its
fallback and who gets the findings. `bb gates`: 341 tests, 1,691 assertions, green, and
`bb example` exits 0. Twenty-four exact-match mutants over the new code — every refusal
removed, `--no-ff` dropped, the abort dropped, the old `reviewed?` restored, the
findings sent to the Tester, the first block instead of the last — were run against the
four test namespaces with a local mutation script: 24 of 24 killed. Comment-only edits for
the citation sweep were made in other files while it ran.

**Run a4**, after this entry's code was committed: the clamp
task a third time, unchanged, through the merge. Green in one round; the
Reviewer ended with `{"verdict":"approve","reasons":[]}` and left its `NaN` note again
rather than rejecting on something the targets do not decide, which is the deliverable's
definition of `reject` holding on its first live use. `merge`, behind a decision file the
user approved before it ran, made one commit of the gate worktree (the three
predicted files) and one `--no-ff` merge commit on a throwaway branch;
the record says `:merged` and validates; ten events in the state and the same ten in the
log; the sandbox's own gates on the merged tree ran 19 tests against the untouched
tree's 16. The scratch worktree and the branch were removed once the record was taken.
Everything the spec predicted held. Not exercised live: a rejection, a missing verdict, a
refused or failed merge — tests and mutants only. **Readiness criterion 6 is closed for
the aligned loop on a4**, and the documents of the day said so; a4
reached no triage, which a2 and a3 had already taken live.

**What the backport to thub will need** — the next piece of work, and not this branch's.
Thub's `run-task!` holds its routing inside its steps and its state in memory, so the
first thing to carry back is the SHAPE, not a feature: a run directory with `state.edn`
and the append-only log it already has, a pure `next-action` over that state, a
re-entrant `run`, every stop an event with a reason and a route, and the retry cap
counted as rounds (`:triage` events) so a hand retry and the loop spend one cap. On that
shape: triage on three triggers through one seam, with a fallback that reads file
ownership instead of `triage.clj`'s hardcoded gate keys, and the Tester leak check
(`driver/leaks`, `triage/shield`) — which needs the Tester kept from the implementation,
so either the three-worktree layout with assembly as a filter or an honest statement that
thub's single worktree cannot give it; the Reviewer's verdict with no-block as a stop
rather than a failed dispatch, a rejection routed rather than escalated, and the
findings kept from the Tester; the merge as a command behind a decision file instead of
`console-human-gate`'s y/n on stdin, with the commit at merge time, the
changed-since-review refusal, the abort on a failed merge, and a decision about whether
teardown keeps the branch. Thub's callers read `:verdict`, `:session-id`, `:capped?`,
`:provider` and `:tokens` off the top of an open `AgentResult`; closing it behind
`:runner/meta` touches every runner and the loop. Its runners are CLIs with session
resume, and the kit's is HTTP with none, so the backport must decide per role whether to
keep the CLI behind the same `AgentRunner` seam (the verdict parser and the capped-and-
wrote-nothing rule port either way) or move to the profile and the HTTP runner, which
brings provenance, `:pricing` and the `:orchestrator` role with it. Smaller pieces that
port as they are: `gates/failure` and the `GateEntry` schema, `run-gate` catching a
missing binary, `:property-targets` on every packet, `Feedback` with `:triage` and
`:architect`, `for-retry`, the pause on notes, the `:calls` and `:targets` checks,
dependents in `:files/context`, gate 0's hunks in the record, the nREPL log kept beside
the worktree and the process watched, the report and its drift gate, and the two rule
changes (`:precedence`, and `:shapes-are-the-contract` validating once). Thub keeps its
own vocabulary ("corpus") and its own project rules; and its `orchestrate_test.clj`,
whose `deps-for` shape this branch's tests were modelled on, will need the run-directory
fixture in return. `harness-seed/PROVENANCE.md` is the list of divergences with reasons,
and is the document to work from.

**The citation sweep.** The seed's `src/`, `dev/` and `test/` no longer name a register
row, a D-run, a bake-off, a stage or a numbered manual run — each such comment, and one
test name (`a-call-signature-for-a-value-is-a-violation`), reworded to keep the lesson
and drop the pointer. `PROVENANCE.md` states the greps and what each prints.

### Milestone 2 · Model triage on red gates and notes, dispatched on

The loop now asks a model who owns a red gate or a note, and acts on the answer.
Proved live by run `a2`: the Coder left a note, triage routed
`continue` for the reason a person would have given, and the run went green in one
round — the triage call a small part of what a dispatch costs.

**The Orchestrator is the profile's fourth role.** `shapes/Profile` requires it,
`bb profile` prints it, and `harness.profile/verifiers` deliberately does not list
it: triage writes no code and judges no diff, so §05's family rule has nothing to say
about it. `claude.edn` runs it on the Coder's family — Fable 5.1, effort high — by the
decision of 2026-09-16, written into the profile comment, the schema's docstring,
`portability.md`'s six-row role table and `NOTES.md` register row 2, which is what
watches it: a `coder` route over a Tester that had the contract right is the failure
the shared family would produce, and every verdict is recorded with whether the
reader agreed. `agy-ide.edn` gets Gemini at high, unmeasured, with the flash-at-high
caveat its own Reviewer comment records and the reason it does not apply to one
completion with no tools.

**`harness.triage` is thub's `triage_model.clj` reshaped, not ported.** Upstream shells
out to a CLI and scrapes its JSON, fires on a red gate only, and falls back to a
classifier that hardcodes gate keys. Here it is one `agent/converse!` on the
`:orchestrator` role with `:tools #{}` — the same runner, adapter and provenance as the
other three roles, so the call is a report step with a model, a cost and a token count
like any dispatch — on two triggers, `:red-gate` and `:note` (a rejected review joins
them in milestone 3), and it falls back to `driver/propose-routing`, which reads the
spec's file ownership rather than a gate key. The prompt frame is upstream's: the
routes, "route to whichever diverges from the slice", the files as written capped at
8,000 characters. Added to it: the property targets as the contract's behaviour, the
dependents, a `continue` route on a note, the rule that only the role that left a note
may be routed on it, and a paragraph telling the model that Tester-bound guidance may
name only the contract — cheaper than escalating a leak after the fact.

**The routing is dispatched on from the first run, and the safeties are structural.**
An `architect` or `human` route stops the loop with the verdict on the `:stopped`
event. A `coder` or `tester` route goes through `driver/retry-with!` — `retry!` split so
the decision can arrive as data — and leaves the same one `:triage` event a typed retry
does, with `:by :model`, so `rounds` counts routed and typed retries against the same
cap without either telling the other. A `continue` goes through `continue-with!` with
the model's reason as the decision `continue` by hand would have required. Guidance
bound for the Tester, together with the gate output it travels with, goes through
`driver/leaks` in `triage/shield`; any leak turns the route into `:human` with the leaks
listed and a new stop kind, `:tester-leak`, whose next steps are the reworded or
`--allow-leak` retry a person types. Any failure of the call — an API error, no JSON
block, a route the trigger does not offer — is the fallback with `:triage/fallback`
naming why.

**Two things the plan did not say, found while building.** First, `next-action`
needed one more clause: **a last event of `:stopped` is the same stop again.** Without
it, `run` typed twice over an untouched red gate would buy a second triage verdict,
which costs money and is not deterministic; with it, the loop is idempotent on re-run
and any command a person types clears it, because each one leaves an event. Second,
the leak policy applies to the WHOLE feedback a Tester retry would carry, not to the
guidance alone as the plan wrote it: the gate's own output travels with the guidance,
a typed `retry tester` is refused on both, and a routed retry that checked less than a
typed one would be a hole in the shield rather than a shield.

**What a2 exercised and what it did not.** Live: the `:note` trigger, the `continue`
route, the `:continued` event with the model's reason as the decision, the triage step
in the report and in `:run/cost`, the prompt and answer kept whole in the run directory,
and the record validating with a triage step in it. Not live: a red gate, and so the
`coder`/`tester` routes, guidance-first feedback, the leak policy, the `architect` stop
and the fallback. Those are `orchestrate_test.clj` (a routed retry going green, the
architect and human stops, a note continued, sent back and escalated, the shielded
Tester, the cap asking nothing, the measured call as a step) and `triage_test.clj`
(the verdict and prompt against a stub server, every fallback, the note routes, the
leak policy). 320 tests, 1,531 assertions. a3, the plan's task worded to conflict with
a dependent, is the live test of the red-gate route and was not run: the plan reserves
it for a run that triggers nothing, and a2 triggered triage.

**a3, run after all**, at the user's request, so the red-gate route is on
record. The dependent-conflict contract produced three verdicts: `architect` on the
Coder's note, `continue` on a Tester note that described its own test, and `architect`
on the red gate against the driver's `coder` proposal — the reader agreed with all
three, and the third is the first live case of the verdict a rule cannot reach. The
person's `continue` past the first stop is in the record as its own event, which is
also the first live exercise of the re-entrancy the milestone added: the loop resumed
from the stop, did not stop there twice, and did not ask about the same note twice.
Still not taken live: a `coder` or `tester` route, and so the guidance-first retry and
the leak policy.

One thing not resolved: while committing the run, one `bb gates` invocation exited 1
in its test step with the output discarded, and `bb test` alone plus two further
full `bb gates` runs, output kept, were green. Which test failed is not known. The
suite has two wall-clock assertions in `agent_test.clj` (a 1,200ms and a 4,000ms
bound on the generation-record fetches) that a busy machine could trip, and nothing
in this milestone touched them; that is a guess, not a finding, and it is written
here so the next transient failure is caught with its output rather than without.

**Documents.** `portability.md` gains the six-row role table (Role · Constraint ·
Family · Model · Dispatched? · Notes) with the Architect and DevOps as the seat's dated
selections, and the fourth row in the two-seat table; `skeletons/03-method-and-tooling.md`'s
§1 table gains the Dispatched? column and §5 asks who routes; `harness-seed/README.md`'s
pieces table gains `harness.triage` and its triage paragraph says what is dispatched
on; `PROVENANCE.md` gains the row and the divergence; `method.md` §10's closing
paragraph says the seed carries the seam and which two safeties the prompt alone does
not give.

## 2026-09-16

### Milestone 1 · The loop, with every stop handed to a person

`bb run-loop run <run-dir>` drives one task to its next stop. Proved live by run
`a1`: green in one round, `:awaiting-merge`, and the
record validates against `shapes/TaskRun` — which no record in this repository's
history ever had.

**The driver moved to `src/` first, mechanically**, in its own commit, so the
behaviour changes after it read as behaviour changes: `dev/run_loop.clj` became
`src/harness/driver.clj`. `bb run-loop` is still the command a person types, and
`:run-loop/error` is still the ex-data key.

**The loop is two functions.** `orchestrate/next-action` is pure over the run's
state and says what should happen next; `run-task!` executes that, one step at a
time, until the answer is `:stop`. Nothing about which step comes next is buried
inside a step, which is why the control flow is table-tested rather than discovered
by provisioning worktrees and calling models. Upstream's version holds the routing
inside the steps that perform it, and can only be tested by performing them.

**Every stop is a person's, and `run` is re-entrant.** A failed dispatch, a dead
nREPL, a dispatch that left notes with nothing decided, assembly refusing a file,
green gates over an empty diff, a red gate, the retry cap, and the Reviewer having
reported. Each records a `:stopped` event with its reason and route, prints what to
do next, and leaves the run exactly where it is. A person amends, retries or
continues with the commands, and runs `run` again — which is what makes the loop's
routing and a person's the same mechanism rather than two.

**What came back from thub, and what each cost to be without:**

- **The REPL probe**, in `driver/dispatch!` rather than in the loop, so a `retry`
  typed by hand is covered by it too. `gates/failure` gets its first caller after
  sitting in the seed unused since the extraction.
- **`harness.log`**, the append-only event log beside `state.edn`. `state.edn` is
  rewritten on every command, so it is the wrong thing to trust about what
  happened; `record` refuses when the two disagree. That is the drift gate this
  repository already applies to `AGENTS.md` and to a published report, applied to a run.
- **The record asserted against `TaskRun`** — a `RunStatus` taken from the loop's
  own outcome, `:run/cost`, `:run/started-at`. The schema had been there with no
  caller since 2026-09-10, and every record written before this failed it.
  `bb report-check` now
  validates every record as well as every published table, and `step!` asserts
  `RunStep`.
- **The nREPL's own output**, kept rather than discarded. It goes BESIDE the
  worktree, not inside it — the plan said inside, and the first test of it showed
  why that is wrong: a failed launch removes the worktree before rethrowing, which
  deletes the log at exactly the moment it is the only thing that can say what went
  wrong. `wait-for-port` watches the process too, so a dead nREPL fails at once and
  names its log instead of waiting out a sixty-second timeout.
- **The empty-diff guard**, and **capped-and-wrote-nothing as a failed dispatch**
  for the Coder and the Tester. Both are wrong in one direction alone: a capped
  dispatch that DID write files is work the gates should judge, and one that wrote
  nothing is a missing deliverable that assembly refuses a step later with nothing
  to say why.

**The retry cap moved from per role to per task**, counted as rounds recorded in
the state rather than iterations of a function. A per-role cap of 3 dispatches one
task six times before anything escalates, which is not a cap anyone chose; counting
`:triage` events means a hand `retry` and the loop spend the same cap without
either telling the other. And **a failed dispatch now stops `start`'s sequence**,
which is the rule `continue` already enforced at a pause, applied where `start`
would otherwise walk into an assembly that refuses a file nobody wrote.

**Documents.** `harness-seed/README.md`'s "Deliberately left out" loses the loop
and triage and keeps parallel dispatch and the per-gate timeout; its pieces table
gains `orchestrate`, `driver` and `log`. `PROVENANCE.md` gains rows for the two
ported namespaces, five divergence entries, a corrected path to the upstream
checkout, and a corrected count — upstream builds the gate-result shape by hand in
two test files, not three (`grep -rl ':gates/report' test/` at `5df04ad`).
`method.md` §12 says the loop is in the seed and that the order which put it there
is the order §12 recommends. Every file touched lost its `NOTES.md row N` and run-id
citations and kept the lesson.

**What a1 did not exercise:** a retry, a red gate, the cap, a dead REPL, an empty
diff. Those are in `orchestrate_test.clj` against a scratch git repo with real
worktrees, real assembly and real gates — only the dispatch is faked, because it is
the only step that costs money. 295 tests, 1371 assertions.

### Entry 1 · The harness re-aligns to the loop it was extracted from

**What this branch is for.** The kit's harness gets thub's §8 loop shape back —
one function that chains probe → dispatch → gates → triage → retry-to-a-cap →
review → merge gate — while keeping every piece the kit grew that thub does not
have. Then the result is back-ported to thub. That last step is not in this
branch's scope.

**Why now, and why it was right to wait.** The kit was extracted from
`thub-harness` on 2026-09-10 and deliberately left the loop behind:
`harness-seed/README.md` listed *the orchestration loop* and *triage* under
"Deliberately left out", and `method.md` §12 says not to build a harness
speculatively — *"run the loop by hand for the first several tasks … and
automate the orchestration only once you've felt where the manual version
actually hurts."* Twenty-five runs by hand is that
condition met, and they are also what makes the rebuild cheap: the manual driver
is the loop's steps, already written, already exercised.

**What the move lost, which is what this branch puts back.** A comparison of the
two harnesses on 2026-09-16 found seven things the kit has no version of:

- **The REPL probe.** The rule source tells an agent never to edit blind when
  the REPL is gone. Nothing checked, so a dead nREPL would have surfaced as a
  mysterious gate failure several paid attempts later.
- **The record asserted against its schema.** `harness.shapes/TaskRun` has been
  in the seed since the extraction and nothing ever validated a record against
  it. Every record committed before this fails it —
  `:run/status` outside `RunStatus`, `:run/cost` and `:run/started-at` missing.
  A schema nothing calls is a comment.
- **The human merge gate.** Four merges were done by hand in the earlier
  runs and one of them was wrong — it merged the bytes a role wrote rather
  than the bytes the gates passed.
- **The append-only log.** `state.edn` is rewritten in place on every command,
  so a run's history is only as good as its last write.
- **The Reviewer's verdict.** The Reviewer returns prose; nothing parses an
  approve or a reject out of it, so nothing can route on one.
- **The empty-diff guard.** Green gates over no change at all is a pass that
  means nothing, and the loop had no reason to notice.
- **Capped-and-wrote-nothing as a failure.** A dispatch that exhausted its
  iteration budget having written no file reported `:done`.

**The settled design** (decided in discussion on 2026-09-16; not re-opened
during the work):

- **The loop.** `harness.orchestrate/run-task!` composes the driver's existing
  steps behind thub's seams — runners, triage-fn, human-gate-fn, repl-probe,
  `provision!`/`teardown!`. The manual commands stay, because they are how a
  person continues from any stop; `merge` joins them.
- **Kept from the kit**, all of it: three worktrees and assembly-as-a-filter;
  the HTTP runner with no session resume; the `:calls` and `:targets` gates;
  gate 0's hunks in the record; the `Feedback` shapes; the Tester leak check;
  the pause on notes; the report and its drift gate; `propose-routing` as
  triage's fallback.
- **Taken from thub**: the REPL probe before each dispatch; the record asserted
  against `TaskRun`; an append-only event log beside `state.edn`; an nREPL
  process watch and log while waiting for the port; the Reviewer's verdict
  block; the empty-diff guard; capped-and-wrote-nothing as a failed dispatch;
  and a **retry cap of 3 per task** — rounds, not per role, so a hand `retry`
  and the loop count the same thing.
- **Triage is one model call** on three triggers (a red gate, a note at the
  pause, a rejected review), returning `{route reason guidance}` over
  `coder | tester | architect | human`, plus `continue` for an observation note.
  It is **dispatched on from day one**, with escalation as the safety and
  `propose-routing` as the fallback on any model failure; guidance bound for the
  Tester passes the leak check or escalates. It runs as the profile's
  `:orchestrator` role — Fable 5.1, effort high — which shares the Coder's
  family. That is accepted by decision, written into the profile, and watched.
- **Every stop is a person's.** Architect route, human route, cap reached, dead
  REPL, failed dispatch, empty diff, no verdict, and the merge gate. The loop
  records the stop with its reason and prints what to do next; nothing merges by
  itself. `merge` runs from the gate worktree, behind a decision file, and makes
  one commit at merge time.
- **Four dispatched roles** in the profile. The Architect and DevOps stay
  seat-side, as dated selections in `portability.md`.

**What it reverses.** `harness-seed/README.md`'s "Deliberately left out" loses
its first two entries: the orchestration loop and triage are in. The cut reasons
were real and are answered rather than ignored — the client-specific leakage
that made the loop unpleasant upstream (session resume, an undeclared retry key,
per-runner cost semantics) does not arise here, because the seed's runner does
not resume sessions and `AgentResult` is closed with a `:runner/meta` hatch; and
triage hardcoding one project's routing opinion is answered by making the
routing a model call with the mechanical proposal as its fallback. §12's
recommendation is not reversed at all — it says to build the loop *after* the
manual version has hurt, which is the order this followed.

**How it runs.** One plan, three milestones, each ending in a live trivial run
against `sandbox/` and a written record: the loop with every stop handed to a
person; then model triage on red gates and notes; then the verdict, rejection
routing, merge and teardown.

### Step 0 · The branch and its documents

A new line of work from the tip of the earlier one. The run tables, the records, `NOTES.md` and
`DEVLOG.md` start clean; the earlier records are dropped rather than
carried, so no table here is published without the record beside it.
`bb report-check` prints `reports in sync: 0`, which is the empty case of the
gate rather than an exemption from it. The profiles keep their dated selections
and their `:params`, `:pricing` and pins, and lose the bake-off narrative, which
now reads as one line. Documents outside the
seed that cited a run id or a section of the run tables now state the
lesson instead; the seed's own docstrings are swept per file as each milestone
touches them.
