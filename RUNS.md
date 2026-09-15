# Runs

Every end-to-end run of the loop against [`sandbox/`](sandbox/), and what it
found. Method §03 step 5 and Foundation readiness criterion 6.

**These are the deliverable, not a green run.** §12 says to run the loop by
hand before automating it, *"and automate the orchestration only once you've
felt where the manual version actually hurts"* — so what each run is for is
the list of things that hurt. Every one of them found something, and most of
what `harness-seed/` has grown since exists because a run made it necessary.

**Twenty-four runs in three parts, and a bake-off.** Runs 1–5 went through `ManualRunner` with the
roles played by hand — no model was called and nothing was spent, which is why
their reports show `—` for model, provider and cost. D3–D18 were dispatched to
real models over HTTP through `harness.runner/api-runner`, and cost about
$2.00 between them. Every run gets three git worktrees from
`harness.provision`, torn down after. Part 3 is not a loop run: B1 put three Tester
candidates through identical conditions and judged their tests against a reference.
Part 4 is the first stage — tasks in dependency order, each merged before the next —
and D19–D21 are its three tasks. *(This paragraph said D3–D18 cost "about $1.64" until
2026-09-14 18:48; the records sum to $1.997, which the totals below already said.)*

The design conclusions these produced live in
[`portability.md`](portability.md); this file is the evidence. Most of what the
runs found was fixed and is explained below where it was found — **what is
still open is a table in [`NOTES.md`](NOTES.md)**, so you do not have to read
twenty-four runs to learn the standing state.

## At a glance

| | Task | When | Outcome | Steps | Cost |
|---|---|---|---|---|---|
| [Run 1](#run-1--t-01-clamp--the-first-end-to-end-run) | `t-01-clamp` | 2026-09-11 23:38 | completed, 2 attempts | — | none |
| [Run 2](#run-2--t-02-between--after-both-fixes) | `t-02-between` | 2026-09-11 23:55 | completed, 1 attempt | — | none |
| [Run 3](#run-3--t-03-analyze--the-first-with-shapes-emitted) | `t-03-analyze` | 2026-09-12 00:17 | completed, 2 attempts | 7.4s | none |
| [Run 4](#run-4--t-04-reduce--the-first-with-the-precondition) | `t-04-reduce` | 2026-09-12 00:47 | completed, 3 attempts | 1m21s | none |
| [Run 5](#run-5--t-05-render--the-first-rewrite) | `t-05-render` | 2026-09-12 01:15 | completed, 3 attempts | 59.5s | none |
| [D1](#part-2--dispatched) | *(smoke, not a run)* | 2026-09-12 08:54 | 2 completions | 3.0s | $0.000004 |
| [D2](#part-2--dispatched) | *(build step)* | 2026-09-12 09:10 | no model called | — | none |
| [D3](#d3--t-06-clamp--the-first-real-dispatch) | `t-06-clamp` | 2026-09-12 09:23 | completed, Coder only | 29.3s | not measurable |
| [D4](#d4--t-07-vars--the-whole-loop-and-it-failed-honestly) | `t-07-vars` | 2026-09-12 09:51 | **FAILED** at the test gate | 16m51s | $0.016014 |
| [D5](#d5--t-07-vars-again--the-loop-completes) | `t-07-vars` | 2026-09-12 14:56 | completed | 5m50s | $0.119274 |
| [D6](#d6--t-08-substitute--the-confirmation-run-that-refuted-a-finding) | `t-08-substitute` | 2026-09-12 15:33 | completed, 3 Tester attempts | 21m06s | $0.410322 |
| [D7](#d7--t-09-fold--the-return-channel-meets-a-model) | `t-09-fold` | 2026-09-13 23:33 | completed, 2 attempts per role | 17m31s | $0.447094 |
| [D8](#d8--t-10-bind--green-reviewed-and-wrong-about-its-own-boundary) | `t-10-bind` | 2026-09-14 01:28 | completed, 2 attempts per role | 3m52s | $0.123272 |
| [D9](#d9--t-11-rename--the-property-targets-reach-everyone-and-it-goes-green-honestly) | `t-11-rename` | 2026-09-14 01:55 | completed, 1 attempt | 2m11s | $0.058247 |
| [D10](#d10--t-12-measure--the-committed-drivers-first-run-and-a-quadratic-cost-only-the-task-could-have-removed) | `t-12-measure` | 2026-09-14 02:18 | completed, 1 attempt | 1m39s | $0.053432 |
| [D11](#d11--t-13-paths--the-sharpened-seam-rule-tested) | `t-13-paths` | 2026-09-14 02:43 | completed, 1 attempt | 1m40s | $0.061853 |
| [D12](#d12--t-14-render--the-first-dispatched-rewrite-and-a-contract-that-broke-its-dependents) | `t-14-render` | 2026-09-14 07:59 | completed, 2 attempts per role | 4m58s | $0.157561 |
| [D13](#d13--t-15-render--shown-the-dependents-and-did-nothing-different) | `t-15-render` | 2026-09-14 08:25 | **FAILED** at the test gate, not retried by design | 1m43s | $0.043124 |
| [D14](#d14--t-16-render--d13-again-with-a-fable-51-coder-and-the-conflict-is-named) | `t-16-render` | 2026-09-14 09:06 | **FAILED** at the test gate, not retried by design; the Coder flagged it first | 2m07s | $0.057597 |
| [D15](#d15--t-17-render--the-note-pause-live-amended-before-anything-was-wasted) | `t-17-render` | 2026-09-14 09:22 | completed: paused on the Coder's note, amended, one green gate run | 5m15s | $0.094443 |
| [D16](#d16--t-18-names--the-first-run-that-keeps-its-transcripts) | `t-18-names` | 2026-09-14 12:11 | completed, 1 attempt; the first record with transcripts | 2m09s | $0.057251 |
| [D17](#d17--t-19-render--d15-again-with-the-coder-at-low-effort-and-a-driver-hole-found-by-a-400) | `t-19-render` | 2026-09-14 12:29 | completed, 3 Coder attempts (one an API error); paused on the note, amended, one red gate the driver's | [5m34s] | [$0.099062] |
| [D18](#d18--t-20-names--the-first-cached-coder-and-the-first--in-the-column) | `t-20-names` | 2026-09-14 14:25 | completed, 1 attempt; the first cached Coder and the first computed Coder cost | 2m13s | ~$0.198439 |
| [D19](#d19--t-21-substitute--the-first-task-of-a-stage-and-a-record-that-is-not-what-the-gates-passed) | `t-21-substitute` | 2026-09-14 18:19 | completed, 1 attempt; stage S1 task 1 of 3; paused on a note that was not a conflict; merged onto the stage | 3m07s | ~$0.275703 |
| [D20](#d20--t-22-constants--a-red-gate-routed-by-the-driver-and-a-tool-that-hid-every-failed-evaluation) | `t-22-constants` | 2026-09-14 18:51 | completed; Tester 3 attempts; red twice, target 8 amended, `nrepl_eval` fixed before the last; merged onto the stage | 4m23s | ~$0.376136 |
| [D21](#d21--t-23-simplify--the-first-edit-to-an-existing-namespace-green-and-the-fixed-tool-read-live) | `t-23-simplify` | 2026-09-15 10:56 | completed, 1 attempt; stage S1 task 3 of 3; the first edit to an existing namespace; merged onto the stage | 2m25s | ~$0.285326 |
| [B1](#b1--tester-candidates-on-t-09-fold) | `t-09-fold`, Tester only | 2026-09-14 00:45 | bake-off: 2 of 3 candidates passed | 2m03s–2m17s each | $0.245960 |

Steps and cost are the totals from each run's own report, below. Steps is the time the
report's steps account for, which leaves out triage by hand; each report's last line
gives the wall time beside it. *(Corrected by the claim audit, 2026-09-14: this column
was headed "Wall", and for D7, D8, D12 and D15 the wall time is minutes longer.)* The manual runs
spent nothing because no model was called; D3's Coder went direct to Anthropic,
which reports tokens and not money. Runs 1 and 2 have no surviving record.
**$2.00 across D1–D18; B1's bake-off cost $0.25 more, D19 ~$0.28, D20 ~$0.38 and D21 ~$0.29.** D17's bracketed totals are the
report's mark for a run with a synthetic step — its failed Coder retry measured nothing.
Every Coder dispatch went direct to Anthropic, which reports usage and no cost, so through
D17 none of those figures includes a Coder: the total is Tester and Reviewer money. From D18
a Coder cost appears as `~$…`, computed from usage × the list prices the profile names
(`claude.edn` `:pricing`, with source and date), and the footer says so; a total with one in
it carries the same mark. D3–D17's Coder steps stay `—`: their records hold a single token
total, not the split a price needs.

**Every table below re-renders from [`runs/`](runs/)** — `bb report < ../runs/d6.edn`
from `harness-seed/`. The records are committed precisely so these figures can be
checked rather than taken on trust. From D7 on they also carry what the prose quotes: notes,
feedback, triage decisions, mutation checks, each red gate's output, and the code each run
produced (`:run/files`). Through D18 that is each file as its role wrote it, before gate 0
repaired and formatted it, so not always the bytes the gates passed: five records, D9–D13, hold a
file the format gate would reject (§D19, `NOTES.md` row 14). From that row's fix it is the gated
bytes, with `:run/files-as-written` for any file gate 0 changed; D19–D21 were re-recorded that way.

---

# Part 1 · By hand

`ManualRunner` prints the packet and waits. The roles were played from
`bb rules-prompt --audience <role>` output — the same rule block a dispatched
model would later be given as its system prompt.

## Run 1 · `t-01-clamp` — the first end-to-end run

**2026-09-11 23:38** (commit time — no run record survives) · **completed**, 2 attempts

No run record survives: runs 1 and 2 predate keeping one. The report rendered at the
time carried no brackets — every number in it was measured — and the Coder step showed
as absent because a broken timestamp command lost it. Nothing is reconstructed here.

**2026-09-11.** A `clamp` task, three worktrees, roles played by hand. §03 step
5 and readiness criterion 6 with it — neither of which the kit had ever been
able to close. Two findings, both now fixed: `harness.stub`, and a third
argument to `assemble!`.

**The Tester cannot satisfy `:repl-first` on a greenfield task.** Its worktree
correctly holds no implementation, so `(require (quote sandbox.clamp))` fails with
`FileNotFoundException` and it cannot evaluate its own tests before persisting
them. The REPL is alive, so `:repl-required` does not fire — it is the right
rule and it says nothing about this. Two rules and the isolation collide, and
the isolation is the one that must win.

`harness.stub` generates one from `:blueprint/slice` and writes it into the
Tester's worktree. Every body throws: a stub returning a plausible value
would let a test pass against nothing. It fixes the rewrite hole below at the
same time — written OVER an existing file, it leaves the contract where the
previous implementation was. One mechanism, two problems, and the second was
only visible by running it.

**Assembly has no input for architecture.** A new namespace needs a
`layers.edn` entry, gate 4 fails without it, and no dispatched role can supply
it: `layers.edn` is in neither packet's `:files/target`, and assembly refuses it
as a scope violation if a Coder writes it anyway. That is correct — the layer
table is architecture (§02), written before the code. `assemble!` now takes
`{:from dir :files [...]}` as a third argument and reports what it copied
separately from agent output, so a run log can tell the two apart.

This is also the loop working. Gate 4 caught a Blueprint defect rather than a
Coder defect, which is exactly §07's *route a failure to the role that owns it*.

## Run 2 · `t-02-between` — after both fixes

**2026-09-11 23:55** (commit time — no run record survives) · **completed**, 1 attempt

No run record survives, for the same reason as run 1.

A contract that stated its precondition as a shape. All four gates green on the
first attempt, no triage cycle. The Tester loaded the stub, prototyped against
it, and tested the inverted-range case *because the contract stated it* — run
1's finding did not recur. Four more things surfaced, all fixed:

**The stub was itself a scope violation.** The harness wrote it into the
Tester's worktree and the check blamed the Tester, so assembly refused and no
task using a stub could ever have assembled. `harness-artifacts` is a fixed set
and the stub's path depends on the task, so the session now records
`:harness/wrote`. Same class as `.nrepl-port`, reintroduced by the fix for the
previous finding and caught only by running it again.

**A src-only nREPL makes `:repl-first` unsatisfiable a second way.** The
Tester could load the stub but not the test namespace it had just written,
because the project's `:nrepl` alias put only `src` on the classpath. §03 step 2
says the REPL should start *ready to work*; for the Tester that means seeing
`test/`.

**A declared shape had no consumer anywhere.** `t-02-between`'s slice declared
the shape its precondition rested on; the implementation validated nothing
against it, and all four gates were green over that. `:shapes` was prose —
`grep :shapes harness-seed/src/` found a docstring and no code. Review caught
it, which is review working, but it is the cheapest possible catch to move
earlier. `harness.stub` now emits the slice's shapes as real `def`s beside the
throwing bodies, so the Tester loads the contract's own data shape and can
write a case against it. Bare symbols in `:shapes` NAME a shape defined
elsewhere and are not invented — they are listed in the generated docstring as
arriving through `:files/context`.

This is the `:repl-first` move applied to the other half of the slice: take a
packet field that was only prose and make it something the REPL can hold.
`:deps-sigs` is the one field still without a consumer.

**And scaffold reaches an agent worktree by base commit, never by copy** — a
`deps.edn` copied in is reported as a scope violation, correctly. Architecture
goes to the *gate* workspace, which has no scope check; anything an agent needs
has to be in the commit it was provisioned from.

## Run 3 · `t-03-analyze` — the first with shapes emitted

**2026-09-12 00:17** · **completed**, 2 attempts

```

Run r-03 · task t-03-analyze · done
  2 attempts

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  coder      dispatch      —                                                  —                     —          —         —
  tester     dispatch      —                                                  —                     —          —         —
  gate-0     gate-0        —                                                  —                 233ms          —         —
  fmt        gate          —                                                  —                 195ms          —         —
  lint       gate          —                                                  —                 162ms          —         —
  coder-r1   dispatch      —                                                  —                     —          —         —
  gate-0     gate-0        —                                                  —                 118ms          —         —
  fmt        gate          —                                                  —                 134ms          —         —
  lint       gate          —                                                  —                  77ms          —         —
  test       gate          —                                                  —                  2.2s          —         —
  deps       gate          —                                                  —                  45ms          —         —
  reviewer   dispatch      —                                                  —                     —          —         —
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                          7.4s          —         —

  No step reported a cost. Mechanical steps have none; ManualRunner has none;
  an endpoint with no generation record reports tokens but not money.
```

Depth, variable names and operator counts of an expression tree. Chosen because
the slice both *defines* a shape inline and *names* one defined elsewhere, so
both branches of `shape-def` ran for the first time. Green in two attempts. One
fix, and four things worth recording.

**The emitted schema arrived as one 161-character line.** `pr-str` puts a `:map`
of any size on a single line; cljfmt does not reflow long lines, so it passed
the fmt gate and was still unreadable — and reading it is the Tester's whole job
here. An emitted shape has to be loadable *and* legible or it does half the
work. `shape-def` now pretty-prints at a 72-column margin. This is the only
finding that changed code.

**The shape settled three questions the Tester would otherwise have guessed.**
Evaluating `Analysis` in its own REPL answered them without any prose:
`[:int {:min 1}]` on depth says a leaf is 1 and never 0; `[:int {:min 1}]` on
the operator counts says an unused operator is *absent* rather than zero;
`{:closed true}` says no extra keys. Three test cases derived by evaluating the
contract rather than by asking. That is §05's data-first claim doing visible
work, and it is only possible because the shape is now code.

**A cheap gate caught what an expensive one could not.** The Coder called
`malli.error/humanize` without requiring `malli.error`. The test gate was
**green** over it — `sandbox.shapes` requires that namespace, so it was loaded
at runtime anyway — and lint failed in 162ms. Cheap-first is not only about
speed; the cheap gate was the only one that could see it at all.

**Review earned its step by mutation, not by reading.** Every fixture in the
Tester's suite was right-leaning, so replacing `(inc (max l r))` with
`(inc r)` passed all 61 assertions. Replacing it with `(inc l)` failed — which
is what makes the hole invisible to anyone reading the test names. No gate can
find that; it is exactly the judgement step §07 pays for.

**`:deps-sigs` was the last slice field with no consumer, and this run proved
it.** The spec declared `(sandbox.expr/operators [])` — a zero-arity function
signature for something that is a map. It rode through packet validation, two
dispatches, four gates and review, and nothing looked.

`harness.sigs` closes it: it reads clj-kondo's analysis of the `:files/context`
files and checks every `:deps-sigs` entry against what they actually define —
unknown namespace, unknown var, private var, a call signature for a value, an
arity that does not exist. It runs after provisioning and BEFORE either dispatch, because a wrong
signature is worse than a missing one: the agent is told a function exists,
writes against it, and the failure surfaces as the Coder's fault two gates
later. It never guesses — an unqualified name defined in two context files is
silently not a violation, because a check that cries wolf about a correct packet
gets switched off within a week.

It reads clj-kondo's `--analysis` rather than parsing the source itself. The
first version hand-rolled a reader over `defn`/`def`/`defprotocol` forms, and
that was the wrong instinct: clj-kondo already computes exactly this, is
already a REQUIRED tool here — `bb doctor` checks it, the lint gate runs it —
and knows what a form expands to rather than only what it looks like.
`defrecord`'s generated `->R` and `map->R`, `(def f (fn [x y]))`, `defmulti`,
macros: all handled, none of them by the hand-rolled version. It also reports
`:syntax` errors per file, which is what lets an unreadable context file be
named rather than silently producing false accusations about every var in it.

Every field of `:blueprint/slice` now has a consumer.


## Run 4 · `t-04-reduce` — the first with the precondition

**2026-09-12 00:47** · **completed**, 3 attempts

```

Run r-04 · task t-04-reduce · done
  3 attempts

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                  56ms          —         —
  sigs       precondition  —                                                  —                  56ms          —         —
  coder      dispatch      —                                                  —                 34.0s          —         —
  tester     dispatch      —                                                  —                 36.0s          —         —
  gate-0     gate-0        —                                                  —                 219ms          —         —
  fmt        gate          —                                                  —                 161ms          —         —
  lint       gate          —                                                  —                 134ms          —         —
  tester-r1  dispatch      —                                                  —                     —          —         —
  gate-0     gate-0        —                                                  —                 215ms          —         —
  fmt        gate          —                                                  —                 163ms          —         —
  lint       gate          —                                                  —                 135ms          —         —
  test       gate          —                                                  —                  3.3s          —         —
  deps       gate          —                                                  —                  49ms          —         —
  reviewer   dispatch      —                                                  —                     —          —         —
  tester-r2  dispatch      —                                                  —                     —          —         —
  gate-0     gate-0        —                                                  —                 215ms          —         —
  fmt        gate          —                                                  —                 147ms          —         —
  lint       gate          —                                                  —                  86ms          —         —
  test       gate          —                                                  —                  2.2s          —         —
  deps       gate          —                                                  —                  43ms          —         —
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         1m21s          —         —

  No step reported a cost. Mechanical steps have none; ManualRunner has none;
  an endpoint with no generation record reports tokens but not money.
```

Substitute bound variables, fold constant subtrees, report what changed. Chosen
because it depends on the PROTOCOL seam, so `:deps-sigs` carried a protocol
method — the case `harness.sigs` most needed to get right. Green in three
attempts. Two findings changed code.

**The precondition paid for itself on its first run.** The spec was written
with two deliberate errors of the class run 3 shipped: `(bindings/lookup [nm])`
against a two-argument protocol method, and `(expr/operators [op])` against a
map. `bb sigs` caught both in 56ms, **before either agent was dispatched**, and
reported nothing against the five correct entries. In run 3 the same class of
error rode through two dispatches, four gates and review. That is the whole
argument for a precondition rather than a gate, run once rather than argued.

**The report had no step kind for it.** `:step/kind` was
`[:enum :provision :dispatch :gate-0 :gate]` — the precondition is none of
those: it does not build a workspace and it does not judge what an agent wrote.
`:precondition` was added. And adding it broke the table, because the kind
column was one character too narrow while the rule under it was a literal `92`
that stayed exactly as long as it had been. The rule is now derived from the
header, and an invariant test asserts every row in the table has one width —
red against the old format string, green against the new.

**Review found two holes, and the fix went to the Tester rather than the
Coder.** Mutation testing showed that dropping the implementation's `int?`
guard, or folding a division by zero to `0`, both passed all 69 assertions. The
Coder had handled two edges the contract never mentioned — `:div` of 7 by 2 is
a ratio and `:expr/value` is `:int`; `:div` by zero throws — and nothing
asserted either. Both are derivable from the contract, so the Tester could write
them without seeing the implementation, which is exactly the route
`:review-report` describes: *an edge case you surface returns to the Tester as a
new case*. After three added cases, both mutations fail.

The underlying gap is worth naming: **a Coder that discovers an edge the
Blueprint is silent about has no channel to say so.** The packet flows one way.
Here review caught it; on a task where review looks elsewhere, the decision
stays undocumented in one namespace and untested everywhere.

**Dispatch steps have real timings for the first time.** Run 3's report showed
`—` for every dispatch, which was the driver's failing and not the harness's:
`dispatch-step` has always taken an `ms`. Coder 34.0s, Tester 36.0s, against
81.4s total — the same shape §10 measured, where the agents dominate and every
gate together is under seven seconds.


## Run 5 · `t-05-render` — the first rewrite

**2026-09-12 01:15** · **completed**, 3 attempts

```

Run r-05 · task t-05-render · done
  3 attempts

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                 188ms          —         —
  coder      dispatch      —                                                  —                 25.0s          —         —
  tester     dispatch      —                                                  —                 24.0s          —         —
  gate-0     gate-0        —                                                  —                     —          —         —
  fmt        gate          —                                                  —                 139ms          —         —
  tester-r1  dispatch      —                                                  —                     —          —         —
  gate-0     gate-0        —                                                  —                     —          —         —
  fmt        gate          —                                                  —                 130ms          —         —
  lint       gate          —                                                  —                  82ms          —         —
  test       gate          —                                                  —                  3.1s          —         —
  deps       gate          —                                                  —                  48ms          —         —
  reviewer   dispatch      —                                                  —                     —          —         —
  tester-r2  dispatch      —                                                  —                     —          —         —
  gate-0     gate-0        —                                                  —                     —          —         —
  fmt        gate          —                                                  —                 136ms          —         —
  lint       gate          —                                                  —                  82ms          —         —
  test       gate          —                                                  —                  2.4s          —         —
  deps       gate          —                                                  —                  49ms          —         —
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         59.5s          —         —

  No step reported a cost. Mechanical steps have none; ManualRunner has none;
  an endpoint with no generation record reports tokens but not money.
```

Make `render` refuse a tree it cannot render faithfully, instead of emitting
text that reparses to a different one. Every earlier run was greenfield, so the
stub's second purpose — *written OVER an existing implementation* — had been a
design claim and nothing more. Green in three attempts. One finding changed the
seed, and it is the most serious thing any of these runs has turned up.

**The rewrite path works.** The Tester's worktree really does arrive holding
the previous `render.clj` — visible, readable, exactly the leak
`tester-packet`'s exclusion cannot reach — and `stub/write!` overwrote it. The
Tester then derived what "faithfully" means from `parse.clj`, which IS in its
context: tokens fold left with no precedence and no parentheses, so the only
renderable trees are left spines whose every right child is a leaf.

**A stub that throws `ex-info` does not stop a test passing against nothing.**
The Tester wrote `(is (thrown? Exception (render/render right-leaning)))` — an
ordinary case for a function whose job is to refuse some inputs — and it
**passed against the stub**, because `ex-info` is an `Exception`. Two of seven
cases were green before a line of the implementation existed. That is precisely
the failure `harness.stub` exists to make impossible, and it had been there
since the namespace was written.

The stub now throws an `AssertionError`. Being an `Error`, `thrown? Exception`
cannot catch it: the throw escapes the assertion and clojure.test reports an
error rather than a pass. Re-running the same suite against the regenerated
stub gave 0 passes where it had given 2. The test that pins this EVALUATES the
generated source rather than grepping it, because the guarantee is about what
the code does — a test that read the text would pass on a stub that merely
mentioned `AssertionError` in a comment.

It cost the `{:harness/stub true :fn _}` ex-data, which moves into the message.
That is the part a human reads in a failure anyway.

**Review found the same shape of hole as run 4, in a different place.** The
Tester's `a-nested-right-child-is-refused-at-any-depth` did not test depth: its
offending node was the outer one, so a `renderable?` checking only the top
right child passed it. Mutation showed it — dropping the recursion left all 54
assertions green. Twice now, a case named *at any depth* or *recursive* has
been satisfied by a fixture that is one level deep. Worth watching for.

**And the rewrite was backward-compatible without anyone checking.** The
pre-existing `property_test.clj` depends on `render` and is in neither role's
`:files/target`; it stayed green, because everything `parse` produces is a left
spine and therefore still renderable. *(Corrected 2026-09-14: this also named
`api_test.clj`, which requires only `sandbox.api` and calls `calculate`, which never
renders — it could not have gone red.)* That is luck
this time rather than design: nothing in the loop tells a rewrite what else
depends on the namespace it is rewriting.



---

# Part 2 · Dispatched

Runs 1–5 exhausted what a human playing the roles could teach. What follows
was dispatched over HTTP to three model families through
`harness.runner/api-runner`, from `resources/profiles/claude.edn` — the seat
is Claude Code, and the seat selects the profile.

**Two build steps came first and are not runs.** *D1* (**2026-09-12 08:54** ·
two smoke completions, no loop) wrote the two request adapters and the two-call
provenance, and proved them with two 20-token completions, one per adapter.

```

Run d1-smoke · task adapter-smoke · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  tester     dispatch      deepseek/deepseek-v4-flash-20260423                StreamLake         1.9s  $0.000004        35
  coder      dispatch      claude-sonnet-5                                    —                  1.1s          —        29
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                          3.0s  $0.000004        64

  Cost covers 1 of 2 steps; the rest reported none.
```
 Those cost about $0.00001 and found three
things: the OpenRouter generation record takes **8.6 seconds** to appear
against a retry budget of 1.5, so the first real cost came back `nil`; the
record names the *resolved* model (`deepseek-v4-flash-20260423` for a request
that said `deepseek/deepseek-v4-flash`), which is what belongs in a report;
and $0.000003642 rendered as `$0.0000`, a row asserting a run was free when it
was not. *D2* (**2026-09-12 09:10** · no model called, so no table) added the tool loop —
`read_file`, `write_file` path-checked against `:files/target`, `nrepl_eval`.

## D3 · `t-06-clamp` — the first real dispatch

**2026-09-12 09:23** · **completed**, 2 Coder dispatches · one role only, not a full loop

```

Run d3 · task t-06-clamp · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  coder      dispatch      claude-sonnet-5                                    —                 29.3s          —    29,131
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         29.3s          —    29,131

  No step reported a cost. Mechanical steps have none; ManualRunner has none;
  an endpoint with no generation record reports tokens but not money.
```

One Coder, to `claude-sonnet-5` direct. The first dispatch burned 24
iterations and 227k tokens walking the filesystem and wrote nothing. A
three-turn transcript showed two causes, both mine.

**`nrepl_eval` is a shell, and the docstring said it was not.** The model
evaluated a `clojure.java.shell/sh` call running `find`. "Not a shell, not a
search" was false and unavoidably so: a Clojure REPL is arbitrary code
execution, and there is no version of it that prototypes forms but cannot
spawn a process. The containment that is real is the git worktree; the rules
are instructions to something that may ignore them. The docstring says that
now.

**And nothing told it what finishing looked like.** The packet carries the
contract and the rules carry the constraints; neither says what the role is
here to *produce*, because across five manual runs a human knew. Told only to
"work through it", the Coder hunted for a `clamp` file that did not exist yet.
`runner/deliverables` states it per role now.

The second dispatch took 6 iterations and 29s and wrote a namespace that
passed gate 0, cljfmt and clj-kondo clean and worked. It also **declined a
signature it was given**: `:deps-sigs` offered `sandbox.shapes/check!`, which
validates expression trees, and the task's inputs are numbers. Rather than
misuse it, it wrote a local check in the same style and said why — which is
`:fix-the-cause` and `:final-message` both working unprompted, in a model the
rules were not written for.

## D4 · `t-07-vars` — the whole loop, and it failed honestly

**2026-09-12 09:51** · **FAILED** at the test gate · 3 Tester dispatches

The record's `:run/attempts 2` disagrees with its own steps, which show three Tester
dispatches — the driver set that field once and never updated it. Trust the steps.

```

Run d4 · task t-07-vars · failed
  2 attempts

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                 127ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-sonnet-5                                    —                 16.6s          —    22,685
  tester     dispatch      deepseek/deepseek-v4-flash-20260423                StreamLake        3m43s  $0.002160    56,056
  assemble   provision     —                                                  —                  44ms          —         —
  gate-0     gate-0        —                                                  —                 246ms          —         —
  fmt        gate          —                                                  —                 140ms          —         —
  lint       gate          —                                                  —                  73ms          —         —
  test       gate          —                                                  —                     —          —         —
  deps       gate          —                                                  —                     —          —         —
  tester-r1  dispatch      deepseek/deepseek-v4-flash-20260423                Baidu             3m39s  $0.004399   104,801
  assemble   provision     —                                                  —                  42ms          —         —
  gate-0     gate-0        —                                                  —                 233ms          —         —
  fmt        gate          —                                                  —                 124ms          —         —
  lint       gate          —                                                  —                  64ms          —         —
  test       gate          —                                                  —                  3.0s          —         —
  deps       gate          —                                                  —                     —          —         —
  tester-r2  dispatch      deepseek/deepseek-v4-flash-20260423                StreamLake        9m01s  $0.009455   226,683
  assemble   provision     —                                                  —                  41ms          —         —
  gate-0     gate-0        —                                                  —                 224ms          —         —
  fmt        gate          —                                                  —                 115ms          —         —
  lint       gate          —                                                  —                  59ms          —         —
  test       gate          —                                                  —                  2.2s          —         —
  deps       gate          —                                                  —                     —          —         —
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                        16m51s  $0.016014   410,225

  Wall time 16m54s for the whole run; the steps above account for 16m51s of it.
  Cost covers 3 of 25 steps; the rest reported none.
```

All three roles for the first time, with gates and a triage retry between
them. 16m54s, 410,225 tokens, $0.016014 measured across 3 of 25 steps.

**The run failed, and that is the result.** `claude-sonnet-5` as Coder wrote a
correct namespace in 5 iterations, first attempt. `deepseek-v4-flash` as Tester
hit its cap on all three attempts — 12, 12, then 25 — and never produced a
passing test namespace. Attempt 1 failed lint with `deftest` docstrings and
unused requires; attempts 2 and 3 failed the test gate on the same
`ClassCastException`, a generator used as a function. Both are conventions its
own system prompt states, and the gate output was handed back to it twice.
Raising the cap changed nothing but the bill.

**One confound, visible only because the provider column exists.** The three
attempts were served by StreamLake, then Baidu, then StreamLake. Same slug,
different hosts — and §10 records a community chat template corrupting
tool-call arguments silently. `:provider {:allow_fallbacks false}` disables
fallback *within* a request, not across them.

Two fixes the run forced: the Tester reported the generated stub as its own
output, because `:files` comes from git and git cannot tell a stub the harness
wrote from something the agent wrote — `:harness/wrote` now rides on the
packet as well as the session. And the report gained wall time and minutes,
because `541.3s` is a number you do arithmetic on before it means anything.

**Triage was done by hand, and that exposed the gap that matters for building
it: the packet has no field for *why you are being dispatched again*.** The
gate output had to go into the opening message.

## D5 · `t-07-vars` again — the loop completes

**2026-09-12 14:56** · **completed**, 1 dispatch per role · first green run end to end

`:run/attempts 1` is right in the sense that no role was re-dispatched; the gates ran
twice, because gate 0 was taught to format between them.

```

Run d5 · task t-07-vars · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                 128ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-sonnet-5                                    —                 17.5s          —    22,354
  tester     dispatch      openai/gpt-5.2-codex-20260114                      Azure             4m21s  $0.108748    52,938
  assemble   provision     —                                                  —                  47ms          —         —
  gate-0     gate-0        —                                                  —                 191ms          —         —
  fmt        gate          —                                                  —                 117ms          —         —
  lint       gate          —                                                  —                     —          —         —
  test       gate          —                                                  —                     —          —         —
  deps       gate          —                                                  —                     —          —         —
  gate-0     gate-0        —                                                  —                 315ms          —         —
  fmt        gate          —                                                  —                 105ms          —         —
  lint       gate          —                                                  —                  60ms          —         —
  test       gate          —                                                  —                  2.9s          —         —
  deps       gate          —                                                  —                  27ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            1m03s  $0.010526    10,214
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         5m50s  $0.119274    85,506

  Wall time 9m59s for the whole run; the steps above account for 5m50s of it.
  Cost covers 2 of 17 steps; the rest reported none.
```

Same task, three changes: `openai/gpt-5.2-codex` as Tester (a third family, so
independence is stronger rather than merely legal), providers pinned with
`:only`, and `reasoning_effort` down from `high` to `medium`. First green run
end to end.

**Gate 0 now runs `cljfmt`, and this is the change with the best ratio.** The
fmt gate failed on exactly one thing: the Coder's `:require` forms out of
alphabetical order. Run 5's Tester did the same. Re-running gate 0 with cljfmt
turned a failed run green **in 315ms with no further dispatch** — which is the
compounding effect `harness.repair`'s own docstring had been claiming and not
delivering.

Two things I got wrong while making that change, both corrected in place. I
blamed relative paths for cljfmt doing nothing and wrote that into a comment;
wrong — sorting is not a cljfmt default, `:sort-ns-references?` enables it and
cljfmt reads that config from the process directory. And the test fixture had
no `.cljfmt.edn`, so it would have passed against a gate 0 that never
formatted anything.

**`reasoning_effort` was wrong everywhere, and this is measured.** The Reviewer
at `high` produced 27,706 tokens, 4m09s and an **empty** final message; the
identical dispatch at `medium` answered in 63s for half the money with real
findings. The original sketch put `high` on every OpenRouter role and nobody
had asked.

## D6 · `t-08-substitute` — the confirmation run that refuted a finding

**2026-09-12 15:33** · **completed**, 3 Tester dispatches

```

Run d6 · task t-08-substitute · done
  3 attempts

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                 125ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-sonnet-5                                    —                 21.3s          —    24,682
  tester     dispatch      openai/gpt-5.2-codex-20260114                      Azure             4m34s  $0.098171    43,157
  assemble   provision     —                                                  —                  45ms          —         —
  gate-0     gate-0        —                                                  —                 308ms          —         —
  fmt        gate          —                                                  —                 106ms          —         —
  lint       gate          —                                                  —                  58ms          —         —
  test       gate          —                                                  —                  3.0s          —         —
  deps       gate          —                                                  —                  27ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            1m11s  $0.014406    17,336
  tester-r1  dispatch      openai/gpt-5.2-codex-20260114                      Azure             6m35s  $0.119624    77,729
  tester-r2  dispatch      openai/gpt-5.2-codex-20260114                      Azure             7m16s  $0.165697    93,457
  assemble   provision     —                                                  —                  38ms          —         —
  gate-0     gate-0        —                                                  —                 301ms          —         —
  fmt        gate          —                                                  —                 106ms          —         —
  lint       gate          —                                                  —                  55ms          —         —
  test       gate          —                                                  —                  2.1s          —         —
  deps       gate          —                                                  —                  23ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            56.3s  $0.012423    14,364
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                        21m06s  $0.410322   270,725

  Cost covers 5 of 21 steps; the rest reported none.
```

A different task on purpose — it returns an `Expr` rather than a collection —
because replaying `t-07-vars` would confirm nothing about anything but
`t-07-vars`.

**Confirmed:** gate 0 running cljfmt (fmt passed first time, no retroactive
fix) and `reasoning_effort "medium"` (the Reviewer answered twice in about a
minute with real findings).

**Refuted, and the framing was mine.** D5 read as *gpt-5.2-codex converges
where deepseek did not*. D6's Tester hit the cap at 15 having written nothing,
then again at 24. The transcript showed it working correctly — reading
context, discovering the test file did not exist, prototyping fixtures in the
REPL exactly as `:repl-first` demands. It never stopped prototyping. The Coder
converged in five iterations on both its tasks, and the difference was one
clause:

> **coder:** "…Prototype in the REPL first, **then write once**."
> **tester:** "…that is what makes your tests independent." *(no stop condition)*

`:repl-first` says to prototype BEFORE persisting and says nothing about when
to stop, which reads as an unbounded invitation to a role with nothing else to
stop it. With the clause added the Tester wrote the file and the run went
green. So D4's failure was never established as tier or family — the profile
change was still right, but what fixed the Tester was fourteen words of prompt.

**And the worse finding.** When the Tester produced nothing, assembly copied
the Coder's implementation, **silently skipped the absent test file, and all
four gates went green** — over the sandbox's fourteen existing tests, with
zero coverage of the new namespace. §07's exit criterion is
*independently-authored tests green*; there were none, and nothing looked.
`assemble!` already refused an EXTRA file loudly; the inverse was never
covered and is the more dangerous direction, because a scope violation fails
noisily and a missing deliverable passes. It now refuses, naming the role so
triage knows who to dispatch again, and assembles nothing.

One more, from the report: D6 printed *"Wall time 7m14s … the steps above
account for 21m06s of it"*, because the driver accumulated elapsed time across
separate invocations. A wall time below the sum is a broken record, not
overhead, and stating both as though they agreed lets a reader believe
whichever half they read first.

Final state: 21 tests and 56 assertions over the new namespace, all gates
green, Reviewer reporting no findings. $0.410322 across 5 of 21 measured
steps — most of it the two Tester attempts that wrote nothing.

## D7 · `t-09-fold` — the return channel meets a model

**2026-09-13 23:33** · **completed**, 2 attempts per role · a Reviewer finding routed back by hand

```

Run d7 · task t-09-fold · done
  2 attempts

  step        kind          model                                              provider           time       cost    tokens
  ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision   provision     —                                                  —                  4.4s          —         —
  sigs        precondition  —                                                  —                 128ms          —         —
  stub        provision     —                                                  —                   1ms          —         —
  coder       dispatch      claude-sonnet-5                                    —                 33.1s          —    40,859
  tester      dispatch      openai/gpt-5.2-codex-20260114                      Azure             5m49s  $0.136692    57,341
  assemble    provision     —                                                  —                  40ms          —         —
  gate-0      gate-0        —                                                  —                 296ms          —         —
  fmt         gate          —                                                  —                 103ms          —         —
  lint        gate          —                                                  —                  56ms          —         —
  test        gate          —                                                  —                  2.8s          —         —
  deps        gate          —                                                  —                  64ms          —         —
  reviewer    dispatch      google/gemini-3.8-flash-20260902                   Google            45.4s  $0.012672    14,280
  coder-r1    dispatch      claude-sonnet-5                                    —                 38.6s          —    52,888
  tester-r1   dispatch      openai/gpt-5.2-codex-20260114                      Azure             8m41s  $0.283939   124,645
  assemble    provision     —                                                  —                  39ms          —         —
  gate-0      gate-0        —                                                  —                 333ms          —         —
  fmt         gate          —                                                  —                 108ms          —         —
  lint        gate          —                                                  —                 104ms          —         —
  test        gate          —                                                  —                  2.2s          —         —
  deps        gate          —                                                  —                  58ms          —         —
  reviewer-r1 dispatch      google/gemini-3.8-flash-20260902                   Google            52.7s  $0.013791    16,556
  ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         17m31s  $0.447094   306,569

  Wall time 20m30s for the whole run; the steps above account for 17m31s of it.
  Cost covers 4 of 21 steps; the rest reported none.
```

Run to put the return channel in front of a model. No model had been offered the
`note` tool, and nothing had ever called `packet/for-retry`. **The slice's silence on
`:div` was deliberate**: `(div 7 2)` is not an `:int` and `(div 1 0)` throws, and the
contract said nothing about either. That silence is the one arranged thing in this run.
Who noticed it, and whether it reached a gate, was left to happen or not.

**Attempt 1 went green and was wrong.** The Coder (7 of 24 iterations) wrote a fold
that throws on both of those valid inputs. The Tester (16) wrote three tests, one
example each, none asserting a value, and all four gates passed. Of five mutants of
`fold.clj`, *every operator computes `+`* and *every fold is literal `0`* survived,
and *operands swapped* was caught only because 4/8 happens to be a ratio. Neither
author noticed the gap, and neither left a note.

**The Reviewer did, and it is the first model to use the `note` tool:**

> "In sandbox.fold, integer division producing non-integer ratios (e.g. (/ 1 2) =>
> 1/2) violates the Expr schema (:expr/value :int) at expr/literal construction,
> failing shapes/check!."

Its findings added the other half, `(div 1 0)`, and a second, real design finding:
`check!` at the top of a recursive function re-validates every subtree, which is
O(N²).

**It could only see any of that because of a fix made before the run.** The D5 and D6
drivers built the Reviewer's diff with `git diff HEAD`, which does not show untracked
files, and everything a task creates is untracked. Those two Reviewers were handed the
`layers.edn` change and not one line of the code or tests. Their final messages were
not kept, so whether they reached the files through `read_file` instead cannot now be
established, and neither can what D5's findings or D6's "no findings" were about.
`provision/review-diff` intent-adds first, and its test fails without that line.

**Triage was by hand, and each decision was written before its dispatch.** Both gaps
were the Architect's: no property target said what `:div` does, and all three
described shape, none value. So the spec was amended first, with a value property and
a never-throws property that decides `:div`. Then, per §07 step 4 and
`:review-read-only`, the finding went to the Coder for the fix and the edge case to
the Tester as a case. **The Tester got the Reviewer's note, not its findings.** The
findings quote the implementation line, and handing that to the Tester is the leak
provisioning exists to prevent. The note still names `expr/literal`, which is a smaller
leak, recorded as one.

**Both retries converged.** `coder-r1` fixed both division cases and moved the O(N²) check
in 7 iterations. *(Corrected after D9, and again after D10: this removed one of two quadratic sources,
the per-level `check!`. The other is in `sandbox.expr/binary`, which every rebuild
calls, and this `fold` still measures quadratic — see §D10.)* `tester-r1` asserted values and both division cases, and against
eight mutants (the first five, plus three on `:div`) **none survive**. It hit its
cap doing it, 24 of 24 with no final message; the file it had already written is
what went green.

**The triage prediction was wrong, in the more interesting direction.** It said the
amendment would reach both roles only as changed `:property-targets`, unannounced,
because `Feedback` has no `:architect` source. It reached the Tester that way, and the
Tester found it. **It never reached the Coder at all: `packet/coder-packet` does not
carry `:property-targets`.** The Coder's retry note says the `:div` rule *"wasn't
specified in the blueprint"*, and from where it stood that was true. It chose the same
rule anyway, which the Reviewer's finding had named as one option. A contract decision
written as a property target reaches only the role that tests it, and the slice has
nowhere else to put one.

**What `NOTES.md` rows 4 and 5 asked.** `for-retry` carried a Reviewer finding to the
Coder and a note to the Tester, and both acted on it. The `note` tool was offered to
six dispatches and used in three: both Reviewers and the Coder's retry. The Tester was
offered it twice and never used it. So a note did not push the Tester into its cap: it
made no note calls, and it capped on its retry anyway.

One more, from the report: `reviewer-r1` is eleven characters in a ten-character step
column, and its row ran one column right. The same mistake run 4 made in the kind
column, one column over; the width is derived from the steps now.

$0.447094 across the 4 of 21 steps that report money; both Coder dispatches went direct
to Anthropic, which reports tokens and not cost. The 2m59s between the steps' sum and
the wall time is almost all triage by hand: 2m57s between the first Reviewer
finishing and the amendment being recorded.

## D8 · `t-10-bind` — green, reviewed, and wrong about its own boundary

**2026-09-14 01:28** · **completed**, 2 attempts per role · the first run on the new Tester

```

Run d8 · task t-10-bind · done
  2 attempts

  step        kind          model                                              provider           time       cost    tokens
  ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision   provision     —                                                  —                  4.4s          —         —
  sigs        precondition  —                                                  —                 125ms          —         —
  stub        provision     —                                                  —                   2ms          —         —
  coder       dispatch      claude-sonnet-5                                    —                 22.9s          —    29,461
  tester      dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            58.2s  $0.045781    30,624
  assemble    provision     —                                                  —                  40ms          —         —
  gate-0      gate-0        —                                                  —                 313ms          —         —
  fmt         gate          —                                                  —                 108ms          —         —
  lint        gate          —                                                  —                  56ms          —         —
  test        gate          —                                                  —                  2.8s          —         —
  deps        gate          —                                                  —                  24ms          —         —
  reviewer    dispatch      google/gemini-3.8-flash-20260902                   Google            28.0s  $0.013898    16,922
  coder-r1    dispatch      claude-sonnet-5                                    —                 38.2s          —    50,753
  tester-r1   dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            57.4s  $0.048593    53,898
  assemble    provision     —                                                  —                  39ms          —         —
  gate-0      gate-0        —                                                  —                 320ms          —         —
  fmt         gate          —                                                  —                 108ms          —         —
  lint        gate          —                                                  —                  55ms          —         —
  test        gate          —                                                  —                  2.2s          —         —
  deps        gate          —                                                  —                  23ms          —         —
  reviewer-r1 dispatch      google/gemini-3.8-flash-20260902                   Google            17.1s  $0.015001    17,677
  ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                          3m52s  $0.123272   199,335

  Wall time 7m21s for the whole run; the steps above account for 3m52s of it.
  Cost covers 4 of 21 steps; the rest reported none.
```

The first loop run on `gpt-5.6-sol` as Tester (OpenAI's standard endpoint; B1 chose it),
the first to record the timing split per dispatch, and the first task across the
`Bindings` protocol seam. The layer table let `sandbox.bind` depend on the protocol and
not on `sandbox.memory`, and no packet's context contained `memory.clj`. **Nothing in
the contract was left silent on purpose**: D7's gaps were the Architect's, so six
property targets stated every case in view. Because `coder-packet` does not carry
`:property-targets` (`NOTES.md` row 4), the decisions the Coder needed were copied into
the title too.

**The Tester is fast now.** 58s and 8 iterations for $0.046 on the first attempt; D7's
took 5m49s and 16. The timing split says where the 58s went: 47.3s in the model, 1.4s in
tools, and 9.4s waiting at the end for generation records that had been fetching beside
the loop. Their own fetch time summed to 72.3s, which is what the blocking loop would
have added.

**Attempt 1 went green, the Reviewer found nothing, and it was wrong.** The Coder (5
iterations) wrote a clean walk that depended only on the protocol, and gate 4 agreed. It
validated the tree it *returned*, not the `e` it was given. Property target 6 says
`bind` throws `check!`'s ex-info when `e` does not satisfy `Expr`, and none of five
invalid inputs got it:

| input | attempt 1 | attempt 2 |
|---|---|---|
| an operator outside `Expr`'s enum | `expr/binary`'s ex-info, no `:sandbox/error` | `:invalid-expr` |
| `nil` | **StackOverflowError** | `:invalid-expr` |
| a `:var` with an empty name, which the Bindings resolve | **returned as a valid literal** | `:invalid-expr` |
| a `:var` with an extra key, which the Bindings resolve | **returned as a valid literal** | `:invalid-expr` |
| an `:add` over an empty-name `:var` | **returned as a valid tree** | `:invalid-expr` |

Of eight mutants, seven died. **Deleting the validation outright survived.** The Tester's
single invalid-input fixture is rejected by a constructor `bind` happens to call, so
target 6 was written down and never tested.

**Why nobody caught it, and the answer is the packet.** Checked after the fact: **only
the Tester's packet carries `:property-targets`**, and the Coder's and the Reviewer's do
not. The Coder never saw target 6. The title copy was meant to cover for row 4, and it
carried every decision except this one. The Reviewer never saw it either, so its "no
findings" was accurate against the contract it was given. Row 4 is wider than D7 showed:
a property target reaches one role in three.

**Triage was by hand, and it had nothing to speak as.** `Feedback`'s sources were
`:gate`, `:reviewer`, `:coder` and `:tester`; this finding came from none of them, and
sending it as `:reviewer` would have put words in the Reviewer's mouth. `:triage` was
added — the Orchestrator's voice in method §07, and it validates on a retry packet. Both
decisions were written before dispatch. The Coder got the requirement stated outright,
with the failing inputs. The Tester got the test gap: validation can be removed without a
failure. The cases it was given all derive from the contract, and it was not told what
the implementation does. That gap itself hints that the implementation has a check, and
that is recorded as a mild leak.

**Both retries converged in under a minute each.** `coder-r1` moved `check!` to the input.
`tester-r1` added nil, an unknown operator and resolved malformed `:vars`. All five probes
now throw `:invalid-expr`, all eight mutants die, and deleting the validation now produces 7 failures and 1 error. The second Reviewer again found nothing.

**Two things nothing caught, neither sent back.** `coder-r1` validates at *every*
recursive call, re-checking each subtree — the O(N²) shape D7's Reviewer flagged, and the
Coder's own summary calls it satisfying "at any depth". This time no Reviewer said so.
*(Corrected after D10: this call is one quadratic source. Moving it to the top halves
`bind`'s time, 7,351 to 3,692ms at 800 nodes. The other source is `sandbox.expr/binary`,
which validates its whole subtree on every rebuild and which no task can change; that is
why `bind` stays quadratic — see §D10.)*
And the Coder calls `expr/literal?`, which is not in its `:deps-sigs` (my omission from
the slice), against the rule to call only the signatures given. `harness.sigs` checks
the slice against the source, not the implementation against the slice, so nothing
looked. Neither breaks the contract, so neither was retried.

$0.123272 across the 4 of 21 steps that report money; both Coder dispatches went direct
to Anthropic. Wall time 7m21s against 3m52s of steps: the difference is triage by hand,
3m27s between the first Reviewer finishing and the first triage decision being recorded.

## D9 · `t-11-rename` — the property targets reach everyone, and it goes green honestly

**2026-09-14 01:55** · **completed**, 1 attempt per role · the first run after row 4 closed

```

Run d9 · task t-11-rename · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.4s          —         —
  sigs       precondition  —                                                  —                 129ms          —         —
  stub       provision     —                                                  —                   1ms          —         —
  coder      dispatch      claude-sonnet-5                                    —                 31.1s          —    45,451
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m16s  $0.047009    29,433
  assemble   provision     —                                                  —                  37ms          —         —
  gate-0     gate-0        —                                                  —                 330ms          —         —
  fmt        gate          —                                                  —                 105ms          —         —
  lint       gate          —                                                  —                  56ms          —         —
  test       gate          —                                                  —                  2.8s          —         —
  deps       gate          —                                                  —                  24ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            16.3s  $0.011238    13,280
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         2m11s  $0.058247    88,164

  Wall time 2m11s for the whole run; the steps above account for 2m11s of it.
  Cost covers 2 of 12 steps; the rest reported none.
```

The first run with `:property-targets` on every packet. **No decision was copied into the
title** this time, unlike D8. The title only names the task, and all seven decisions live
in the targets alone. Two of them are the kind a role gets wrong without reading them:
renaming is *simultaneous* (with `{"a" "b", "b" "c"}`, `a` becomes `b`, not `c`), and a
malformed map throws its own `:invalid-renaming` error, checked only *after* `e`. Before
dispatch, the three packets built from the spec were checked to carry all seven targets.
The two predictions written into the spec: no retry caused by a decision the Coder never
saw, and a Reviewer that names a broken target.

**Both held, and for the first time since D5 no role was dispatched twice.** By its own
account the Coder (8 iterations) checked every target in the REPL, including validation
order, passing a bogus `m` alongside an invalid `e` to confirm `e` failed first; the
mutants below are what verified it. **The Reviewer
walked all seven targets one by one** against the diff, which no Reviewer could do before:
none had been given them. It found nothing, and this time "nothing" was checked:

- **Ten mutants over five of the seven targets, all killed.** Targets 1 (the result
  satisfies `Expr`) and 5 (renaming and then inverse-renaming gives the tree back) had
  none. *(Corrected by the claim audit, 2026-09-14: this said "one or more per target";
  the record's `:target` fields are 2, 3, 4, 6 and 7.)* The table is in `runs/d9.edn`'s
  events, with each substitution. They include sequential rather than simultaneous
  renaming, `m` validated before `e`, an empty-string key or value accepted, and
  `:renaming` dropped from ex-data.
- **The Tester wrote to the targets**, including the ordering case: an invalid `e` passed
  with `m` set to `:not-a-map`, which only an implementation checking `e` first survives.

The Tester took 1m16s, and 69.8s of that was the model.

**And a correction to D7, found here.** Timing `rename` on a left-deep tree came out
quadratic: 65, 246, 911 and 3,688ms for 100, 200, 400 and 800 nodes, while one `check!`
over the same trees scaled linearly. The cause is not `rename`. `sandbox.expr/binary`
validates the whole subtree it is given, so every rebuild through the constructor re-checks
everything below it. D7's final `fold`, the retry credited above with fixing "the O(N²)
check", measured the same: 73, 256, 951 and 3,856ms. D7's Reviewer was right about the
cost, and its fix removed the source it named but left the constructor's. *(Corrected after
D10: this paragraph first said that fix "fixed nothing" and that no task could remove the
cost. There are two sources, and a task can remove one of them — see §D10.)* `sandbox.expr`
is in every task's context and in none of their targets. `NOTES.md` row 12 has both sources.

$0.058247 across the 2 of 12 steps that report money, and 2m11s of wall time with no
triage in it.

## D10 · `t-12-measure` — the committed driver's first run, and a quadratic cost only the task could have removed

**2026-09-14 02:18** · **completed**, 1 attempt per role · the first run through `bb run-loop`

```

Run d10 · task t-12-measure · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                 128ms          —         —
  stub       provision     —                                                  —                   1ms          —         —
  coder      dispatch      claude-sonnet-5                                    —                 22.3s          —    26,475
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            54.6s  $0.042128    28,121
  assemble   provision     —                                                  —                  41ms          —         —
  gate-0     gate-0        —                                                  —                 314ms          —         —
  fmt        gate          —                                                  —                 104ms          —         —
  lint       gate          —                                                  —                  55ms          —         —
  test       gate          —                                                  —                  2.7s          —         —
  deps       gate          —                                                  —                  24ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            15.1s  $0.011303    12,839
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         1m39s  $0.053432    67,435

  Wall time 1m40s for the whole run; the steps above account for 1m39s of it.
  Cost covers 2 of 12 steps; the rest reported none.
```

The first run through `harness-seed/dev/run_loop.clj`, and its purpose was the driver. Its
`record` had been verified by replaying D7–D9; its `start` had never dispatched. It
worked end to end. It snapshotted the config and profile into `state.edn`, then
provisioned, checked signatures, stubbed, dispatched all three roles, assembled, gated and
diffed. Its `mutation`, `record` and `teardown` commands wrote this record. The task was
chosen to read a tree and rebuild none, so that `NOTES.md` row 12 would stay out of it.

**Green on the first attempt, and the tests hold.** The Coder took 22s and the Tester 55s.
The Reviewer walked all six property targets and found nothing. Ten mutants covered
counts, depth, union, duplicates, exact keys and validation, and all ten were killed. Two
of them first matched a leaf map the Coder had also written into its docstring. That
changed no code and killed nothing, so they were re-run anchored to the code line. The
record carries both facts, so a later reader knows the substitution matters.

**But it did not keep row 12 out, and that corrects three things written after D9.**
`measure` calls `check!` at every recursive call, and each call validates the whole subtree
below it. It measures quadratic, 66ms at 100 nodes and 3,780ms at 800. The same walk
with `check!` once at the top takes 1.4ms to 10.4ms and returns equal results. **So there
are two independent quadratic sources, not one:**

| left-deep tree, 800 nodes | as written | `check!` once at the top |
|---|---|---|
| D10 `measure` (rebuilds nothing) | 3,780ms | **10ms** — linear |
| D8 `bind` (rebuilds through `expr/binary`) | 7,351ms | 3,692ms — still quadratic |

A task can remove the per-level `check!`, and `measure` shows what that is worth. No task
can remove the constructor's own validation. `bind` paid for both. D7's retry removed the
first from `fold` and left the second, so "that removed nothing" (§D7) was wrong: it
removed one of the two. "The quadratic cost does not come from this call" (§D8) was wrong
the same way. And D8's Reviewer *could* have flagged the fixable half. Both are corrected
in place. `NOTES.md` row 12 now names both sources.

**Nobody sent `measure` back.** No property target says anything about cost, so this is
not a contract failure. But it is the finding a Reviewer is told to make, "judge what the
gates cannot: design …", and D10's did not make it. So D7's Reviewer made it once, and
D8's and D10's did not.

$0.053432 across the 2 of 12 steps that report money, and 1m40s of wall time with no
triage in it.

## D11 · `t-13-paths` — the sharpened seam rule, tested

**2026-09-14 02:43** · **completed**, 1 attempt per role · a test of one rule change

```

Run d11 · task t-13-paths · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                 131ms          —         —
  stub       provision     —                                                  —                   1ms          —         —
  coder      dispatch      claude-sonnet-5                                    —                 23.2s          —    26,877
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            54.5s  $0.049439    24,086
  assemble   provision     —                                                  —                  40ms          —         —
  gate-0     gate-0        —                                                  —                 351ms          —         —
  fmt        gate          —                                                  —                 111ms          —         —
  lint       gate          —                                                  —                  57ms          —         —
  test       gate          —                                                  —                  2.8s          —         —
  deps       gate          —                                                  —                  25ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            14.9s  $0.012414    14,616
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         1m40s  $0.061853    65,579

  Wall time 1m40s for the whole run; the steps above account for 1m40s of it.
  Cost covers 2 of 12 steps; the rest reported none.
```

After D10, `:shapes-are-the-contract` was sharpened. It now says to validate at the seams
*once*, because a function that recurses into itself crosses its own seam on every call,
and to validate at the entry and recurse through a private helper. D7's, D8's and D10's
Coders had each validated at every recursive call. §10 lesson 3 says to test a prompt
change against a live run, and D11 is that test. The task is recursive and rebuilds no
tree, so the constructor's accepted cost (`NOTES.md` row 12, source 2) cannot hide the
result. Threading a path through the recursion also invites exactly the second arity that
would validate again. The prediction, written into the spec before dispatch: validate
once, green first time.

**It held.** The Coder (5 iterations) wrote `paths-to`, which calls `check!` once and then a
private `paths-to*` that "assumes `e` is a valid Expr already". Its own summary gives the
reason: "validated once at the seam, per the data-first rule". Measured on left-deep trees:

| nodes | `paths-to`, absent name | `paths-to`, match at the bottom | one `check!` |
|---|---|---|---|
| 100 | 4.97ms | 4.57ms | 4.69ms |
| 800 | **11.58ms** | 14.94ms | 11.15ms |

That is linear. With the name absent it is within a millisecond of a single validation at
every size; with the match at the bottom it is 3.8ms above one at 800 nodes. *(Corrected by
the claim audit, 2026-09-14: this said "within a millisecond … at every size" of both
columns, and the record's `:reading` still does.)* D10's `measure`,
with the same shape and before the rule changed, took 3,780ms at 800 nodes.

**What one run can and cannot say.** It is one sample, from the same Coder model that wrote
the quadratic version three times. The rule is the only thing that changed, and the Coder
cited it, so the rule is the likeliest cause. One run does not make it reliable. A later run
that validates per call would refute it.

**The rest was clean.** The Tester (6 iterations, 54.5s) wrote a generated property test
against its own reference traversal, plus the ordering and validation-order cases. The
Reviewer walked the targets and found nothing. **Twelve mutants covered all seven targets
and all twelve were killed**, including right-before-left order, a duplicated match, `nm`
checked before `e`, and an empty name accepted. They ran through a new mutation runner that
applies each mutant as an exact string that must match exactly once, so D10's docstring
mismatch cannot recur. The substitutions and timings are in `runs/d11.edn`.

$0.061853 across the 2 of 12 steps that report money, and 1m40s of wall time with no triage.

## D12 · `t-14-render` — the first dispatched rewrite, and a contract that broke its dependents

**2026-09-14 07:59** · **completed**, 2 attempts per role · the first model to modify existing code, and the first `:architect` retry

```

Run d12 · task t-14-render · done
  2 attempts

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                 130ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-sonnet-5                                    —                 21.1s          —    26,278
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m22s  $0.052330    31,020
  assemble   provision     —                                                  —                  41ms          —         —
  gate-0     gate-0        —                                                  —                 377ms          —         —
  fmt        gate          —                                                  —                 112ms          —         —
  lint       gate          —                                                  —                  62ms          —         —
  test       gate          —                                                  —                  2.6s          —         —
  deps       gate          —                                                  —                     —          —         —
  coder-r1   dispatch      claude-sonnet-5                                    —                 41.4s          —    64,994
  tester-r1  dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m53s  $0.086855    62,427
  assemble   provision     —                                                  —                  36ms          —         —
  gate-0     gate-0        —                                                  —                 338ms          —         —
  fmt        gate          —                                                  —                 110ms          —         —
  lint       gate          —                                                  —                  58ms          —         —
  test       gate          —                                                  —                  2.2s          —         —
  deps       gate          —                                                  —                  56ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            29.7s  $0.018376    20,681
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         4m58s  $0.157561   205,400

  Wall time 6m39s for the whole run; the steps above account for 4m58s of it.
  Cost covers 3 of 20 steps; the rest reported none.
```

Every dispatched run from D3 to D11 created a new namespace. D12 is the first to hand a model
**existing code to change**. It rewrote `sandbox.render`, which `sandbox.api/canonical` and
`test/sandbox/property_test.clj` depend on, and neither was in any packet. `NOTES.md` row 1 —
nothing tells a rewriting task what depends on the namespace it changes — had never met a
model. **The design was stated in the spec before dispatch:** contract version 1 asked for a
fully parenthesised render and ignored its dependents, the way a rewrite commonly does. What
was left open was whether anything would catch it, whether a role would notice, and how the
retry would go.

**The rewrite path works.** The stub overwrote the existing `render.clj` in the Tester's
worktree, and git shows it modified and recorded in `:harness/wrote`. Assembly and the
Reviewer's diff handled a modified file rather than a new one. The gate worktree ran the whole
suite, and that is what mattered next. One leak remains: the old implementation is still in
the Tester's worktree history, readable through its REPL. Nothing records whether it looked
(row 8), but its tests expected the new output, not the old.

**Attempt 1 met version 1 exactly and went red where predicted.** The Coder (21s) validated
once through a private helper, and the Tester's tests passed against it. Then `property_test.clj`,
a file no role owns, failed at the test gate: `parse-render-round-trips` on the token `"(((0"`
and `canonical-is-idempotent` on `"((0"`, because `sandbox.parse` has no parentheses. **No role
mentioned the dependents**, and nothing had told them to look.

**Triage owned it as the Architect's, and ran that path end to end for the first time.** Neither
role had broken its contract; the contract had broken its dependents. `spec.edn` went to
version 2, recorded with `bb run-loop amend`. Version 2 parenthesises only a right operand that
is itself an operator node, which leaves every left spine `parse` produces rendering as before.
It states round-trip as a target, adds `sandbox.parse` to the context, and says `render` must
not require it. Both roles were retried with **`:architect` feedback** announcing the change,
its first real use. The Coder also got an excerpt of the failing gate's output: a header,
the two `ERROR` lines with their tokens, and the test summary; the Tester did not, because that output quotes strings the
implementation rendered. *(Corrected 2026-09-14, found backfilling the records: this said the
Coder got the output. Triage sent a 370-character excerpt of it; the full output is now the
red `:gates` event's `:feedback` in `runs/d12.edn`.)*

**Both converged.** `coder-r1` read `parse.clj`, parenthesises right operator operands only,
and checked through `ns-aliases` that it does not require `parse`. Every gate passed, including
the dependents: `property_test.clj` re-run against the final `render` passes 4 of 4. The
Reviewer walked all seven targets and found nothing.

**One test cannot fail, and a gate covered for it.** Of eleven mutants, ten were killed. `render
requires sandbox.parse` survived. The Tester's `implementation-does-not-alias-parser` filters
alias names with `(namespace sym)`, which is `nil` for `sandbox.parse`, so the test passes
whatever `render` requires. Target 7 is still enforced: with that mutant applied, gate 4 fails
with *"sandbox.render [forbidden-dependency] requires sandbox.parse"*. A twelfth mutant was
discarded as invalid. It recursed through `render` from inside `render*`, which does not compile,
so it was "killed" by the compiler rather than a test. Its replacement, `check!` inside
`render*`, was killed by the Tester's call-count test. The record carries both, with reasons.

**The seam rule held a second time.** Both Coder attempts validated once. `render` measured
linear, 1.4ms at 100 nodes and 9.9ms at 800, and `render*` alone is under a quarter of a
millisecond. D11's result is now two samples.

$0.157561 across the 3 of 20 steps that report money. Of the 6m39s wall time, 1m39s passed by
hand between the red gate and `coder-r1` starting: the gate at 111.1s, the retry at 210.6s
(it ended at 251,994ms and took 41,392). *(Corrected by the claim audit, 2026-09-14: this
said 1m41s, which is wall time minus the steps, not the gap.)*

## D13 · `t-15-render` — shown the dependents, and did nothing different

**2026-09-14 08:25** · **FAILED** at the test gate, deliberately not retried · a controlled rerun of D12's first attempt

```

Run d13 · task t-15-render · failed

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                 136ms          —         —
  stub       provision     —                                                  —                   1ms          —         —
  coder      dispatch      claude-sonnet-5                                    —                 21.8s          —    32,184
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m14s  $0.043124    29,528
  assemble   provision     —                                                  —                  41ms          —         —
  gate-0     gate-0        —                                                  —                 324ms          —         —
  fmt        gate          —                                                  —                 106ms          —         —
  lint       gate          —                                                  —                  58ms          —         —
  test       gate          —                                                  —                  2.6s          —         —
  deps       gate          —                                                  —                     —          —         —
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         1m43s  $0.043124    61,712

  Wall time 1m44s for the whole run; the steps above account for 1m43s of it.
  Cost covers 1 of 11 steps; the rest reported none.
```

After D12, a rewrite's dependents were put into every packet's `:files/context`, and the Coder
was told they "must keep working". D13 tested whether that changes what a model does. It was
D12's attempt 1 again: the same contract version 1, which demands full parentheses; the same
models; the same unmerged `sandbox.render`. **The only difference was the dependents in the
packets.** Four predictions were written into the spec before dispatch.

| | prediction | result |
|---|---|---|
| P1 | the dependents are exactly `api.clj` and `property_test.clj` | **held** — the `:dependents` event lists both, and both packets carry them |
| P2 | a role flags the conflict before any gate runs | **refuted** — no note from either role, and neither summary mentions a dependent |
| P3 | otherwise the test gate goes red in `property_test.clj` | **held** — `parse-render-round-trips` failed on the token `"(z"`, as in D12 (the red `:gates` event in `runs/d13.edn` carries the output) |
| P4 | `render` validates once | **held** — the third sample of the seam rule |

**The Coder did not miss the conflict for lack of information.** Its new `render.clj` docstring
calls the namespace "the inverse of sandbox.parse". That phrase is nowhere in D13's contract; it
is the first line of the *original* `render.clj`, whose docstring also explains that render
"emits the flat form that re-parses to the same tree". So the Coder read the file it was
rewriting, which described round-trip. It had `property_test.clj` in its context and an
instruction to keep it working, and it implemented the contract's parentheses without saying
anything. How many of its 7 tool calls went to which file cannot be known (row 8).

**So visibility is not enough, and the gap is now specific.** Faced with a contract that
contradicts code it was shown, a model follows the contract, silently. That may even be the
right precedence, but it leaves no trace. The `note` tool, which is the channel for exactly
this, names three triggers: the Blueprint is silent, a `:deps-sigs` entry looks wrong, or an
assumption needs checking. **"The contract conflicts with a file in your context" is not one of
them**, and the Coder's instruction says dependents must keep working without saying what to do
when the contract says otherwise.

**Deliberately not retried.** D13's question was answered on the first attempt. The retry would
be D12's amendment again, answering nothing new. The run is recorded as failed, as D4 was, and
the test gate's output is in the record, on the red `:gates` event. *(The claim audit,
2026-09-14, found it was not: the output was only in the run's gitignored directory. The
record was backfilled the same day, and `runs/d13.edn` now carries it and the final
`render.clj` quoted above.)* What to change is `NOTES.md` row 1.

$0.043124 across the 1 of 11 steps that reports money, and 1m44s of wall time.

## D14 · `t-16-render` — D13 again, with a Fable 5.1 Coder, and the conflict is named

**2026-09-14 09:06** · **FAILED** at the test gate as designed, not retried · D13 with only the Coder's model changed

```

Run d14 · task t-16-render · failed

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.7s          —         —
  sigs       precondition  —                                                  —                 140ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-fable-5-1                                   —                 58.2s          —    55,013
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m01s  $0.057597    45,518
  assemble   provision     —                                                  —                  44ms          —         —
  gate-0     gate-0        —                                                  —                 319ms          —         —
  fmt        gate          —                                                  —                 116ms          —         —
  lint       gate          —                                                  —                  66ms          —         —
  test       gate          —                                                  —                  2.7s          —         —
  deps       gate          —                                                  —                     —          —         —
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         2m07s  $0.057597   100,531

  Wall time 2m08s for the whole run; the steps above account for 2m07s of it.
  Cost covers 1 of 11 steps; the rest reported none.
```

D13 showed that putting a rewrite's dependents in front of a `claude-sonnet-5` Coder changed
nothing: it had read the file explaining round-trip and implemented the conflicting contract
silently. The Coder was then switched to Claude Fable 5.1 at medium effort. D14 is D13 with
**only that change**. Before dispatch it was checked that the spec is identical to D13's but for
its task id (compared as data), that the sandbox source is clean, and that the profile names
`claude-fable-5-1`. The Tester, Reviewer, contract, sandbox and dependents computation are the
same. One caveat on "only": the Coder role's change is a model together with its settings —
effort medium and `max_tokens` 16000.

| | prediction | D13 (Sonnet 5 Coder) | D14 (Fable 5.1 Coder) |
|---|---|---|---|
| P1 | dependents are `api.clj` and `property_test.clj` | held | **held** |
| P2 | the Coder flags the conflict before any gate | **refuted** — no note, no mention | **held** — a note, and its summary |
| P3 | otherwise the test gate goes red in `property_test.clj` | held | **held** — it followed the contract, as it said it would |
| P4 | `render` validates once | held | **held** — and the Coder proved it with a call-count check on a 30-deep tree |
| P5 | no Coder dispatch is refused | — | **held** |

**The note is the whole result.** The Coder's first note, verbatim:

> *"Contract conflict: t-16-render's property targets require parenthesised output "(l op r)",
> but sandbox.parse currently rejects "(" tokens (parse "((1 + 2) * 3)" throws :bad-token "((1").
> So test/sandbox/property_test.clj's parse-render-round-trips and canonical-is-idempotent
> (parse -> render -> parse) will fail until parse accepts the parenthesised form. Either the
> parse task must add parentheses, or the round-trip property should be restated. I implemented
> the packet's parenthesised form as specified."*

It did not just notice. It **reported what `parse` throws** on parenthesised input, the exact
`:bad-token`, named both
properties that would fail, gave the Architect the two ways out, and stated which way it resolved
the conflict and why. That is D12's triage diagnosis, arriving a gate early and from inside the
loop. The Tester, still `gpt-5.6-sol` and given the same dependents, left no note, as in D13.

**What one pair of runs can say.** A single rerun, with a model and its settings changed
together. The Coder took 58.2s, 7 iterations and 55,013 tokens, against 21.8s, 5 and 32,184 in
D13, at five times the per-token price (Anthropic's pricing page, read 2026-09-14: Fable 5.1
$10 / $50, Sonnet 5 $2 / $10 per MTok, input / output), which the report cannot show. It makes the
contract-conflict change planned after D13 — a new `note` trigger and an explicit "contract wins,
say so" instruction — look unnecessary for this Coder. The change may still matter for a less
capable one.

**What the note did not do: stop anything.** It reached the run log and no one. The Tester was
dispatched anyway, and the gates ran into the failure the note had predicted. A loop that read
notes before dispatching the next role could have sent D14 straight to the Architect and saved
the Tester dispatch and the gate run. Nothing reads notes mid-run: triage, which would, is still
deliberately unbuilt.

Deliberately not retried, like D13: the question was answered on the first attempt. $0.057597
measured, the Tester only.

## D15 · `t-17-render` — the note pause, live: amended before anything was wasted

**2026-09-14 09:22** · **completed** with one green gate run · D14 with only `bb run-loop`'s note pause added

```

Run d15 · task t-17-render · done
  2 attempts

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.2s          —         —
  sigs       precondition  —                                                  —                 135ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-fable-5-1                                   —                 1m19s          —   107,465
  coder-r1   dispatch      claude-fable-5-1                                   —                 56.0s          —    63,718
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            2m27s  $0.070161    44,169
  assemble   provision     —                                                  —                  41ms          —         —
  gate-0     gate-0        —                                                  —                 351ms          —         —
  fmt        gate          —                                                  —                 123ms          —         —
  lint       gate          —                                                  —                 113ms          —         —
  test       gate          —                                                  —                  3.2s          —         —
  deps       gate          —                                                  —                  63ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            24.2s  $0.024282    33,865
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         5m15s  $0.094443   249,217

  Wall time 6m06s for the whole run; the steps above account for 5m15s of it.
  Cost covers 2 of 13 steps; the rest reported none.
```

D14's Fable 5.1 Coder named the contract conflict exactly, and the driver dispatched the Tester
and ran the gates into the failure the note predicted. `bb run-loop` then learned to pause after
a dispatch that leaves notes. D15 is D14 with that one change. Its spec was checked equal to
D14's as data, but for the task id, before dispatch.

| | prediction | result |
|---|---|---|
| P1 | the Coder notes the conflict | **held** — it reported that `parse` rejects `"(1"`, named both `property_test.clj` properties, and offered two ways out |
| P2 | the run pauses after the Coder: no Tester, no gate | **held** — `:paused {:after :coder :next :tester}`, the note printed, nothing more dispatched |
| P3 | from the note alone, amend → retry the Coder → `continue` → green | **held** |
| P4 | no red test gate anywhere in the run | **held** — one gate run, green |

**The pause turned a failure into a decision.** At the pause the Architect had one input, the
note, and no gate output, because no gate had run. It offered two ways out: teach `parse`
parentheses, or have the Blueprint drop them from `render`. The Architect took the second, as
D12's version 2, which D12 had already shown green; the spec was checked equal to D12's as data,
but for the task id. It was recorded with `amend`, and the Coder was retried with `:architect`
feedback answering its note. From the pause to the recorded amendment took 35 seconds. **The
Tester had never been dispatched, so it needed no retry**: on `continue` it saw version 2 first.
The Coder's retry left no note, so the run did not pause again. `continue` went on to the Tester,
one gate run, which passed with `property_test.clj` included, and the Reviewer, which found
nothing.

| same final contract | Tester dispatches | test-gate runs | red gates | Tester cost |
|---|---|---|---|---|
| D12 (no dependents shown, no pause) | 2 | 2 | 1 | $0.139186 |
| D15 (dependents shown, pause) | **1** | **1** | **0** | $0.070161 |

**The tests hold.** Eleven exact-match mutants covered all seven targets, and all eleven were
killed. That includes `render requires sandbox.parse`, which **survived in D12** because that
Tester's dependency test could not fail. This Tester's compares the exact alias set. The retried
Coder had also quick-checked round-trip 300 times with `property_test.clj`'s own generator
before writing.

**The limits.** One run, and the comparison with D12 is not controlled: D12 had a Sonnet 5 Coder
and no dependents in its packets. What D15 shows is the mechanism working end to end: a note,
a pause, an amendment from the note alone, a single green pass. It does not show the saving is
typical. The Coder's two dispatches used 171k tokens at Fable 5.1 prices, which the report
cannot show.

## D16 · `t-18-names` — the first run that keeps its transcripts

**2026-09-14 12:11** · **completed**, 1 attempt per role · a trivial task, run for its record

```

Run d16 · task t-18-names · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.5s          —         —
  sigs       precondition  —                                                  —                 254ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-fable-5-1                                   —                 31.7s          —    24,736
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m07s  $0.045390    26,623
  assemble   provision     —                                                  —                  54ms          —         —
  gate-0     gate-0        —                                                  —                 397ms          —         —
  fmt        gate          —                                                  —                 114ms          —         —
  lint       gate          —                                                  —                 129ms          —         —
  test       gate          —                                                  —                  3.0s          —         —
  deps       gate          —                                                  —                  25ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            21.4s  $0.011861    12,395
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         2m09s  $0.057251    63,754

  Wall time 2m10s for the whole run; the steps above account for 2m09s of it.
  Cost covers 2 of 12 steps; the rest reported none.

```

`NOTES.md` row 8 said a dispatch that produced nothing could not be diagnosed afterwards:
`converse!` kept every call and every message and `outcome` dropped them, so B1's `glm-5.3`
left 24 completions and no explanation, and the claim audit had to soften "evaluated `parse`
in the REPL" to "reported", because nothing showed what a model ran. `converse!` now keeps
`:turns`, one per completion — the model's text and its tool calls with arguments and results
— and `bb run-loop` writes the whole transcript to `<step>.transcript.edn` and puts a copy on
the dispatch event with every string cut to 500 characters, the cut announced. D16 is the
smallest task that exercises all three roles, dispatched to see the transcript exist and to
measure what it costs the record. Three predictions were written into the spec.

| | prediction | result |
|---|---|---|
| P1 | every role goes green on the first attempt | **held** |
| P2 | each dispatch event has one turn per completion, the last with no calls | **held** — 5, 7 and 3 turns for 5, 7 and 3 iterations; the last turn of each has `:calls []` |
| P3 | the record grows by well under 100 KB | **held** — `runs/d16.edn` is 23,208 bytes; the three capped transcripts are 4,071, 6,235 and 1,891 bytes of it (`count` of their `pr-str`), against 14,541 for D11, the nearest run without one |

**What a transcript shows.** The Coder's five turns, from the record: it read `shapes.clj` and
`expr.clj`; ran an `nrepl_eval` that defined a prototype and tried it; wrote `names.clj`; ran a
second `nrepl_eval` that `:reload`ed the namespace and called `var-names` on a tree and a literal,
getting `[["x" "y"] []]`; and answered. Its summary says *"Evaluated in the REPL"*, and for
the first time that is a claim the record can check rather than repeat. The whole transcripts
are 7,424, 21,491 and 6,253 bytes (`ls -l` on `.local/runs/d16/*.transcript.edn`); the Tester's
longest string, a tool result, is 10,607 characters, so the cap cut it to a twentieth.

**What it does not do.** B1, D3 and D6 stay undiagnosable: nothing kept their transcripts. And a
capped transcript shows the shape of what happened, not the content — a 500-character prefix of
a written file is not the file (that is `:run/files`), and a prefix of a REPL result may end
before the value that mattered. The full transcript is one `.local/` away, for as long as that
directory lasts. No mutation check was run: the task was chosen for its record, not its code,
and `names.clj` is fifteen lines.

$0.057251 across the 2 of 12 steps that report money, and 2m10s of wall time with no triage.

## D17 · `t-19-render` — D15 again with the Coder at low effort, and a driver hole found by a 400

**2026-09-14 12:29** · **completed**, 3 Coder attempts, one of them an API error · D15 with only the Coder's effort changed, medium to low

```

Run d17 · task t-19-render · done
  3 attempts

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.3s          —         —
  sigs       precondition  —                                                  —                 138ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-fable-5-1                                   —                 57.4s          —    70,834
  coder-r1   dispatch      —                                                  —               [405ms]          —         —
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            2m17s  $0.068046    41,623
  assemble   provision     —                                                  —                  45ms          —         —
  gate-0     gate-0        —                                                  —                 353ms          —         —
  fmt        gate          —                                                  —                 127ms          —         —
  lint       gate          —                                                  —                 108ms          —         —
  test       gate          —                                                  —                  2.7s          —         —
  deps       gate          —                                                  —                     —          —         —
  coder-r2   dispatch      claude-fable-5-1                                   —                 40.0s          —    41,905
  assemble   provision     —                                                  —                  52ms          —         —
  gate-0     gate-0        —                                                  —                 394ms          —         —
  fmt        gate          —                                                  —                 122ms          —         —
  lint       gate          —                                                  —                 130ms          —         —
  test       gate          —                                                  —                  2.4s          —         —
  deps       gate          —                                                  —                  26ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            1m28s  $0.031016    38,531
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                       [5m34s] [$0.099062] [192,893]

  [bracketed] = SYNTHETIC: 1 of 20 steps are made up, not measured.
  Wall time 26m34s for the whole run; the steps above account for 5m34s of it.
  Cost covers 2 of 20 steps; the rest reported none.

```

After a day on Fable 5.1, the Coder's effort was set to low (`claude.edn`, 12:29). D17 is D15 rerun
with only that changed: the same version-1 contract, whose parentheses break round-trip through
`sandbox.parse`; the same dependents in every packet; the note pause on. The spec was checked equal
to D15's version 1 as data, but for the task id, before dispatch. The question was whether a
low-effort Coder still flags the conflict, as medium did in D14 and D15, or goes silent, as Sonnet 5
did in D13. Five predictions went into the spec.

| | prediction | result |
|---|---|---|
| P1 | the Coder leaves a note naming the conflict with `property_test.clj` | **held** — one note, naming both properties, the `:bad-token` and the two ways out |
| P2 | the run pauses, the Architect amends to version 2 from the note alone, and `continue` ends green with no red gate | **held to the amendment, then refuted by the driver** — see below; the Coder's retry never ran |
| P3 | otherwise the test gate goes red in `property_test.clj` and is not retried | not reached |
| P4 | the Coder validates once | **held** — `render*` recurses unchecked, the every-level mutant was killed, the Reviewer confirmed it |
| P5 | the transcript shows whether it ran `parse` before deciding | **held** — it did, two turns before the note |

**Low effort flagged it, and the transcript shows the order.** From `runs/d17.edn`'s first dispatch
event: turn 1 read `shapes.clj`, `expr.clj`, `api.clj` and `property_test.clj`; turns 2–5 ran
`nrepl_eval`, parsing a sample and then `"((1 + x) * -3)"`, which returned `{:sandbox/error
:bad-token :token "((1"}`; turn 6 was the `note`; turns 7–9 prototyped, wrote and reloaded
`render.clj`; turn 10 answered. So the note was evidence, not a guess — the thing §D14 could only
say the model "reported", a day ago, is now on the record. The pause came at 62s, the amendment
at 107s, with D15's `:architect` feedback verbatim. Against D15's medium-effort Coder: 10 iterations,
57.4s and 70,834 tokens where D15 took 14, 79.6s and 107,465; on the retry, 5 iterations, 40.0s and
41,905 where D15's took 7, 56.0s and 63,718. One pair of runs; the direction is the same on both dispatches.

**Then the retry failed in 405ms, and the driver went on anyway.** `coder-r1` got HTTP 400 from
Anthropic — *"Your credit balance is too low to access the Anthropic API"* — 0 completions, recorded as
`:failed` with the message. `continue` checked only that the run was paused, dispatched the Tester
(2m17s, $0.068046) against the `render.clj` the retry had never rewritten, and the test gate went red on
round-trip, the token `"(((((z"`. That red gate is the driver's, not the model's: the Tester had
already seen version 2, so its tests were right and the implementation was version 1. `continue!` now
refuses while the last dispatch is `:failed` (`DEVLOG.md`, 12:34). The report shows the failed dispatch
as a bracketed synthetic step, since it measured nothing.

**Resumed 22 minutes later, once there was credit.** `retry coder` again, as `coder-r2` with the same
triage decision; the Tester needed no retry. Every gate passed, `property_test.clj` included. The
Reviewer walked all seven targets and found nothing. Eleven exact-match mutants, D15's set with the
finds adapted to this implementation, covered all seven targets and all eleven were killed, including
`render requires sandbox.parse` and validation at every level; the substitutions are in the record.

**The record is 75,980 bytes**, the largest yet: four capped transcripts (7,747, 7,291, 6,824 and
4,102 bytes as `pr-str`), the red gate's output, the final files and the mutants. The wall time,
26m34s, includes the wait for credit; the steps account for 5m34s.

$0.099062 across the 2 of 20 steps that report money, one of them the Tester dispatch the driver
should not have made.

## D18 · `t-20-names` — the first cached Coder, and the first `~$` in the column

**2026-09-14 14:25** · **completed**, 1 attempt per role · D16's task again, with caching on and a priced Coder

```

Run d18 · task t-20-names · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.5s          —         —
  sigs       precondition  —                                                  —                 132ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-fable-5-1                                   Anthropic API     30.5s ~$0.143756    19,423
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m15s  $0.044229    27,500
  assemble   provision     —                                                  —                  43ms          —         —
  gate-0     gate-0        —                                                  —                 309ms          —         —
  calls      gate          —                                                  —                 242ms          —         —
  fmt        gate          —                                                  —                 104ms          —         —
  lint       gate          —                                                  —                  54ms          —         —
  test       gate          —                                                  —                  2.7s          —         —
  deps       gate          —                                                  —                  24ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            19.1s  $0.010454    11,830
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         2m13s ~$0.198439    58,753

  Wall time 2m14s for the whole run; the steps above account for 2m13s of it.
  Cost covers 3 of 13 steps; the rest reported none.
  ~ = computed from list price for 1 of them (usage × the profile's :pricing), not reported by the endpoint.

```

D16's trivial task rerun, to measure two harness changes made since: automatic prompt caching on
direct Anthropic requests, and the Coder's list prices in the profile. The `:calls` check (row 11)
also ran for the first time. The spec was checked equal to D16's as data, but for the task id. Two
things differ besides the harness: the Coder is at low effort (D16's was medium), and it took
four completions to D16's five, so the token figures are not a controlled comparison.

| | prediction | result |
|---|---|---|
| P1 | the Coder's `:usage` has `cache-read` > 0 and its uncached `:in` is a fraction of D16's total | **held** — `{:in 10 :out 1,435 :cache-write 5,503 :cache-read 12,475}`: of 17,988 input tokens, 10 were billed at the base rate |
| P2 | the Coder row shows `~$` and `Anthropic API`, and the footer names the list price | **held** |
| P3 | the `:calls` step passes | **held** — 242ms, no violations |
| P4 | every role goes green on the first attempt | **held** |

**What the cache did.** The record's Coder event carries the counts and the rates, so the figure
re-derives by hand: (10 × $10 + 1,435 × $50 + 5,503 × $12.50 + 12,475 × $0.25) / 10⁶ =
**$0.143756**, which is what the row shows. The same usage with nothing cached — 17,988 input
tokens at $10 — would have been $0.251630. Input went from $0.179880 to $0.072006, a 60% cut;
the dispatch, output included, 43%. Less than the 85% input cut estimated from D17's transcript,
because in a four-completion conversation the cache *writes* (1.25× base, 5,503 tokens) are most
of the input bill; the reads (12,475 tokens at 0.025×) cost $0.003. Longer tool loops read more
and write proportionally less. Output is the larger half regardless: 1,435 tokens, thinking
included, at $50 is half the dispatch.

**A defect the first cached run exposed.** The report's tokens column first said 1,445 for this
dispatch: `provenance/of` summed uncached input and output only, so the 17,978 cached tokens the
request moved were not counted. Fixed before the record was written — the count now includes
cache writes and reads — and the Coder step in `state.edn` was recomputed from its own event's
usage (10 + 1,435 + 5,503 + 12,475 = 19,423) before `record`; the original state is kept locally
as `state.pre-tokens-fix.edn`. Older records are unaffected: they have no cache counts.

Against D16's Coder (medium effort, uncached): 4 iterations, 30.5s and 19,423 tokens against 5, 31.7s
and 24,736 — and a cost, $0.144, where D16 has a dash. No mutation check, as for D16: the task was
chosen for its record.

~$0.198439 across the 3 of 13 steps that report money, one of them computed; 2m14s of wall time.

# Part 3 · Bake-offs

A bake-off is not a loop run. It puts one role's candidates through **identical
conditions** and judges what they produce against a reference, so the only thing that
differs between rows is the model.

## B1 · Tester candidates on `t-09-fold`

**2026-09-14 00:45** · 3 candidates, 1 Tester dispatch each · 2 passed, 1 wrote nothing

`openai/gpt-5.2-codex` has been the Tester since D5, and the Tester is the seat that
failed or capped in D4, D6 and D7. *(Corrected by the claim audit, 2026-09-14: this said
"every dispatched run since D4"; D5's Tester ran once and did neither.)* Three replacements were proposed.
Each was pinned to one host and given the same packet: D7's *amended* `t-09-fold`
contract, a first attempt with no feedback, the harness's default cap of 24, and
`reasoning_effort "medium"`. Its test file was then assembled beside **D7's final
`fold.clj`**, the implementation that went green and killed all eight of D7's mutants.
So a red test gate would mean a wrong test, not a wrong implementation, and the same
eight mutants say whether the tests bite. No profile file was changed: each role map was
built in the driver.

| | `gpt-5.3-codex` · Azure | `gpt-5.6-sol` · OpenAI flex | `glm-5.3` · Z.AI |
|---|---|---|---|
| Tester time | 2m09s | **1m58s** | 2m15s |
| Iterations | 21 | **9** | 24, **capped** |
| Wrote its test file | yes | yes | **no** |
| Model time, summed · per completion | 119.1s · 5.7s | 107.8s · 12.0s | 123.8s · 5.2s |
| Tools, summed | 3.6s | 1.9s | 4.9s |
| Waiting for generation records at the end | 7.2s | 8.8s | 6.6s |
| Reasoning tokens | 5,533 | 2,529 | 494 |
| Cost | $0.174929 | **$0.035454** | $0.035577 |
| Gates against the reference | all pass | all pass | not reached |
| New tests · assertions | 11 · 16 | 7 · 11 | — |
| Of which generative (`defspec`) | 10 | 4 | — |
| Mutants killed | **8 of 8** | **8 of 8** | — |
| Notes left | 0 | 0 | 0 |

**Two candidates are good Testers on this task, and they differ mainly in cost.** Both
wrote tests that pass against the reference and kill every mutant. Both wrote
generative properties, where D7's `gpt-5.2-codex` wrote only examples on both attempts.
`gpt-5.6-sol` on flex did it in 9 iterations for $0.035, a fifth of `gpt-5.3-codex`'s
$0.175, with less than half the reasoning. Its completions are the slowest of the three
(12.0s each), and it made up for that by needing fewer of them.

**`glm-5.3` spent its whole budget and wrote nothing**, leaving no note and no final
message. Assembly refused it by name, which is the D6 fix doing its job: before that
change, this run would have gone green over the sandbox's existing tests. *Why* it wrote
nothing cannot be recovered. `AgentResult` carries no transcript, and the driver kept only
counts: 24 completions, 25 tool calls, 494 reasoning tokens. D6's Tester failed the same
way and was diagnosed by re-dispatching it; this one was not. That is `NOTES.md` row 8.

**The off-loop fetch, seen on real dispatches.** Summed, the three dispatches' own
generation-record fetches took 158.2s, 68.2s and 182.7s. The loop waited 7.2s, 8.8s and
6.6s, once, at the end. Under the blocking loop D7 ran with, that sum would have been
added to each Tester's time. Nearly all of what is left is the model.

**What this does not show.** One task, one sample per model, first attempt only. D7's
`gpt-5.2-codex` figures are not a baseline: its first attempt had the contract before
the amendment (5m49s, 16 iterations, examples only, 2 of 5 mutants survived), and its
retry had feedback on top (8m41s, capped, 8 of 8). Both ran under the blocking fetch. The
profile was switched to `gpt-5.6-sol` afterwards, as a separate decision, and then from
flex to OpenAI's standard endpoint, because flex refused most requests with an upstream
rate limit (`DEVLOG.md`, 2026-09-14). The results above are flex's.

```

Run b1-codex53 · task t-09-fold-b1-codex53 · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  2.3s          —         —
  stub       provision     —                                                  —                   2ms          —         —
  tester     dispatch      openai/gpt-5.3-codex-20260224                      Azure             2m09s  $0.174929   128,578
  assemble   provision     —                                                  —                  21ms          —         —
  gate-0     gate-0        —                                                  —                 361ms          —         —
  fmt        gate          —                                                  —                 118ms          —         —
  lint       gate          —                                                  —                 105ms          —         —
  test       gate          —                                                  —                  2.9s          —         —
  deps       gate          —                                                  —                  60ms          —         —
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         2m15s  $0.174929   128,578

  Wall time 2m18s for the whole run; the steps above account for 2m15s of it.
  Cost covers 1 of 9 steps; the rest reported none.
```

```

Run b1-solflex · task t-09-fold-b1-solflex · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  2.1s          —         —
  stub       provision     —                                                  —                   2ms          —         —
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m58s  $0.035454    36,917
  assemble   provision     —                                                  —                  24ms          —         —
  gate-0     gate-0        —                                                  —                 380ms          —         —
  fmt        gate          —                                                  —                 121ms          —         —
  lint       gate          —                                                  —                  64ms          —         —
  test       gate          —                                                  —                  2.5s          —         —
  deps       gate          —                                                  —                  63ms          —         —
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         2m03s  $0.035454    36,917

  Wall time 2m06s for the whole run; the steps above account for 2m03s of it.
  Cost covers 1 of 9 steps; the rest reported none.
```

```

Run b1-glm53 · task t-09-fold-b1-glm53 · failed

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  2.0s          —         —
  stub       provision     —                                                  —                   2ms          —         —
  tester     dispatch      z-ai/glm-5.3-20260816                              Z.AI              2m15s  $0.035577    87,472
  assemble   provision     —                                                  —                  20ms          —         —
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         2m17s  $0.035577    87,472

  Wall time 2m20s for the whole run; the steps above account for 2m17s of it.
  Cost covers 1 of 4 steps; the rest reported none.
```

The plumbing smoke that came first (a `read_file` round trip, twice each) passed for all
three and is kept in `.local/runs/smoke-tester/`, since no document here cites its
numbers. The three records are `runs/b1-*.edn`, and their `:run/events` carry the
timing split, the gate results and every mutant.

# Part 4 · Stage S1

Every run above is one task against the unchanged `sandbox/`, and nothing any of them wrote was
ever merged. A stage is what the method is for: tasks in dependency order, each built on the
previous task's merged code (method §07, Steps 2–5). **S1 adds `sandbox.api/simplify` in three
tasks** — t-21 `substitute`, t-22 `fold-constants`, t-23 `simplify`, which calls the other two —
dispatched from a `stage/s1` worktree, so each task's worktrees branch from the stage rather than
from this branch. Each task is merged onto `stage/s1` by hand after its gates and review.
`stage/s1` is not merged here: `sandbox/` stays the fixture every run above started from.

The stage document and Blueprint were filled in from `skeletons/stages/` and signed off by the
user before any dispatch (2026-09-14 18:19). They, the specs with their predictions, and each
task's working files are in the gitignored `.local/stage-s1/.local/runs/`. **The driver is
unchanged for the whole stage**, including where §D19 finds a defect in it. The REPL tool is not:
`nrepl_eval` was fixed before D20's last Tester attempt (§D20), so D21 and that attempt ran on a
different tool from D19 and D20's first two. The last section, §S1 · What the stage showed, is the
summary.

## D19 · `t-21-substitute` — the first task of a stage, and a record that is not what the gates passed

**2026-09-14 18:19** · **completed**, 1 attempt per role · stage S1, task 1 of 3 · paused after the Coder on a note

```

Run d19 · task t-21-substitute · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.3s          —         —
  sigs       precondition  —                                                  —                 135ms          —         —
  stub       provision     —                                                  —                   1ms          —         —
  coder      dispatch      claude-fable-5-1                                   Anthropic API     42.5s ~$0.206033    35,143
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            2m02s  $0.058306    34,197
  assemble   provision     —                                                  —                  48ms          —         —
  gate-0     gate-0        —                                                  —                 326ms          —         —
  calls      gate          —                                                  —                 220ms          —         —
  fmt        gate          —                                                  —                 115ms          —         —
  lint       gate          —                                                  —                  58ms          —         —
  test       gate          —                                                  —                  2.7s          —         —
  deps       gate          —                                                  —                  24ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            14.9s  $0.011365    13,573
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         3m07s ~$0.275703    82,913

  Wall time 3m52s for the whole run; the steps above account for 3m07s of it.
  Cost covers 3 of 13 steps; the rest reported none.
  ~ = computed from list price for 1 of them (usage × the profile's :pricing), not reported by the endpoint.

```

`(substitute e vars)` replaces each bound `:var` leaf with a literal. A new namespace over
`sandbox.expr` and `sandbox.shapes`, the size of D16's and D18's tasks: what it tests is the
stage's plumbing, not the models. `expr/binary` was deliberately left out of `:deps-sigs`, since
rebuilding a tree through it is quadratic (`NOTES.md` row 12), so the `:calls` check would refuse it.

| | prediction | result |
|---|---|---|
| P1 | every role goes green on the first attempt | **held** |
| P2 | the `:calls` step passes; operator nodes rebuilt as maps, not through `expr/binary` | **held** — 220ms; rebuilt with `update` |
| P3 | the Coder validates `e` once, at the entry | **held** — `check!` in `substitute`, recursion through a private `subst*` |
| P4 | no role leaves a note | **refuted** — below |
| P5 | a mutant that substitutes only at the top level is killed | **held** — both one-subtree mutants killed |

**The pause, on a note that was not a conflict.** The Coder noted that a non-integer binding is
rejected inside `expr/literal`, so the ex-info's `:where` names the constructor rather than
`substitute`. The contract promises only `:sandbox/error :invalid-expr`, which holds, so the run
was continued without an amendment. D15's and D17's notes were contract conflicts; this was an
observation, and pause-on-notes stopped the run 47s in, before the Tester, all the same. **A
decision at a pause that changes nothing leaves no reason in the record:** `continue` writes a
`:continued` event with `:after` and `:next` and nothing else. The reason was written before
`continue` ran, to the run's local `decision-continue.edn`, which `run.edn` does not carry
(`NOTES.md` row 15; since fixed, 2026-09-15: `continue` now takes that file and records it). The note itself names the implementation; it did not reach the Tester, whose
packet is built from the spec and its session only.

**The Tester tested the contract's examples, and not what lay beside them.** Target 4 covers any
value for which `int?` is false, "such as 1.5 or \"3\""; the Tester's test is
`(doseq [bad-value [1.5 "3"]] ...)`, and the one surviving mutant — a key bound to `nil` treated as
unbound — is exactly the case the examples did not name. The file has no `defspec`: every test is
an example, although its fixture is three levels deep and it tests validate-once by counting
`check!` calls under `with-redefs`. Nine of ten mutants killed; the substitutions are in the
record's `:mutation` event.

**The first merge found what no single-task run could.** `record` copies each role's files into
`final/`, and a merge from `final/` failed the stage's format gate. Gate 0 had re-indented lines
80–81 of the Tester's file in the gate worktree, where the gates ran; `record!` copies from each
role's *own* worktree, so `final/` and `:run/files` hold the bytes as the role wrote them, before
gate 0, and nothing in the record says gate 0 changed anything — its step is `:pass`. The merge
took the gate worktree's bytes instead: `stage/s1` `8447cc6`, `bb gates` green there, 21 tests over
10 namespaces. **The committed records have the same gap.** Of the twelve that carry `:run/files`,
five hold a file `cljfmt` rejects although that run's format gate passed:

```sh
T=$(mktemp -d) && bb -e '(doseq [f (babashka.fs/glob "runs" "d*.edn") :let [r (clojure.edn/read-string {:default tagged-literal} (slurp (str f)))] [p s] (:run/files r) :when (string? s)] (let [d (babashka.fs/path (first *command-line-args*) (str (:run/id r)) p)] (babashka.fs/create-dirs (babashka.fs/parent d)) (spit (str d) s)))' "$T" && (cd sandbox && for d in "$T"/*/; do cljfmt check "$d" >/dev/null 2>&1 || echo "$(basename "$d") fails"; done)
```

from the repository root prints `d9`–`d13` (2026-09-14, before `d19.edn` was added; D7, D8 and
D14–D18 pass). That reading assumes today's `cljfmt` formats as the one those runs used did.
`runs/d19.edn` was first the record as `record` wrote it, pre-gate-0 test file included. `final/` also
dropped directories — `final/<file-name>` — so a merge had to re-derive each path from the spec
(`NOTES.md` row 14). *(Both fixed after the stage, 2026-09-15: D19 is re-recorded with the gated
Tester file in `:run/files` and the one it wrote in `:run/files-as-written`, from bytes copied out
of the gate worktree before teardown; its report table is unchanged.)*

**Cost.** The Coder's `~$0.206033` re-derives from its event's usage: (14 × $10 + 2,330 × $50 +
6,628 × $12.50 + 26,171 × $0.25) / 10⁶. Against D18's Coder on a task of the same size, $0.143756:
six completions to four, 2,330 output tokens to 1,435, and 6,628 cache writes to 5,503. Output is
$0.1165 of it, more than half, where it was half of D18's.

~$0.275703 across the 3 of 13 steps that report money, one of them computed; 3m52s of wall time,
of which the pause is part.

## D20 · `t-22-constants` — a red gate routed by the driver, and a tool that hid every failed evaluation

**2026-09-14 18:51** · **completed**, Coder 1 attempt, Tester 3 · stage S1, task 2 of 3 · red at the test gate twice; target 8 amended; `nrepl_eval` fixed before the Tester's last attempt

```

Run d20 · task t-22-constants · done
  3 attempts

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.4s          —         —
  sigs       precondition  —                                                  —                 144ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-fable-5-1                                   Anthropic API     33.1s ~$0.207499    26,552
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m16s  $0.057002    42,653
  assemble   provision     —                                                  —                  44ms          —         —
  gate-0     gate-0        —                                                  —                 360ms          —         —
  calls      gate          —                                                  —                 222ms          —         —
  fmt        gate          —                                                  —                 131ms          —         —
  lint       gate          —                                                  —                  63ms          —         —
  test       gate          —                                                  —                  2.8s          —         —
  deps       gate          —                                                  —                     —          —         —
  tester-r1  dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            41.8s  $0.032197    30,432
  assemble   provision     —                                                  —                  48ms          —         —
  gate-0     gate-0        —                                                  —                 396ms          —         —
  calls      gate          —                                                  —                 247ms          —         —
  fmt        gate          —                                                  —                 140ms          —         —
  lint       gate          —                                                  —                  65ms          —         —
  test       gate          —                                                  —                  2.2s          —         —
  deps       gate          —                                                  —                     —          —         —
  tester-r2  dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            1m06s  $0.060099    37,483
  assemble   provision     —                                                  —                  60ms          —         —
  gate-0     gate-0        —                                                  —                 394ms          —         —
  calls      gate          —                                                  —                 259ms          —         —
  fmt        gate          —                                                  —                 150ms          —         —
  lint       gate          —                                                  —                  72ms          —         —
  test       gate          —                                                  —                  2.3s          —         —
  deps       gate          —                                                  —                  29ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            30.9s  $0.019340    30,547
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         4m23s ~$0.376136   167,667

  Wall time 944m57s for the whole run; the steps above account for 4m23s of it.
  Cost covers 5 of 29 steps; the rest reported none.
  ~ = computed from list price for 1 of them (usage × the profile's :pricing), not reported by the endpoint.

```

`(fold-constants e)` turns each operator node over two literals into a literal, except a division
by zero or with a remainder, which stays. The stage's riskiest task, and the first run to go red
since the driver began proposing a routing for a red gate and checking a Tester's feedback for
leaks (`NOTES.md` rows 1 and 5). It does not use D19's `substitute`, but it was dispatched from
the stage branch with that merged.

| | prediction | result |
|---|---|---|
| P1 | the Coder leaves a remainder division unfolded, with no note | **held** |
| P2 | the Tester's cross-surface property goes through `compute` and `memory`, over generated trees deeper than one level | **partly** — through both, over hand-built trees; the file has no `defspec`, the stage's second Tester file with none |
| P3 | gate 4 passes: no `sandbox.compute` require | **held** |
| P4 | green on the first attempt; if red, the test gate on division, with a `:routing` naming a file and an owner | **refuted** — red at the test gate over validation, not division; the `:routing` named the test file and the Tester |
| P5 | mutants that fold a remainder division, or fold only at the top, are killed | **held** — eleven of eleven killed |

**The first live routing proposal, over an ambiguous target.** `validates-only-once-at-entry`
expected one `sandbox.shapes/check!` call and saw four. The Coder called `check!` once, at the
entry; the other three were the one-node checks `expr/literal` makes on each literal it builds,
and `expr/literal` is in the slice. Target 8 said `fold-constants` "validates e once, at its entry,
not at every level of the tree". D19's Tester, on the same sentence, excluded constructor calls;
D20's counted them. The sentence allowed both, so the defect was the Architect's: target 8 was
amended with `amend` to say `check!` receives `e` exactly once and never a node taken from inside
`e`, and that a new literal's own check does not count. The driver's proposal said owner `:tester`,
sources `[:gate]`; the decision was the Tester with `[:architect :gate]`. They agree on the role for
a reason the proposal cannot see. Its rule gives the Tester any failure that names only a test file,
and an assertion failure in the task's own tests always names only that file, where method §07
makes the Coder the default owner of a failing test (`NOTES.md` row 17; since fixed, 2026-09-15, so the same gate would now propose the Coder). The leak check ran on both
Tester retries and found nothing.

**The retry that wrote nothing.** tester-r1's final message: "The live nREPL stopped returning
evaluation results while I was prototyping the revised identity-based validation fixture. Per
project rules, I stopped without editing the target file." Its dispatch still reported
`files=["test/sandbox/constants_test.clj"]` — attempt 1's file, from git status — and `:done`
(row 18, since fixed, 2026-09-15), and `check` was run on it without that message being read. That is the Orchestrator's
slip, and why the table has a second red test gate over an unchanged file. **The nREPL was alive.**
Replaying tester-r1's calls through `harness.tools/invoke` against the same nREPL: `clj-nrepl-eval`
exited 0 with the stub's `AssertionError` on stderr — the stub throws by design — and `nrepl_eval`
returned stdout alone, `""`. Attempt 1 was misled worse. The file it wrote had an unmatched `]` at
line 99; its `require` and `test-vars` came back `""`, and `(require 'sandbox.constants-test
:reload)` followed by `:loaded` came back `=> :loaded`. It finished believing its file loaded and
never ran a test. Gate 0 repaired the delimiter before the gates, and the record does not say so
(row 14). Blank evaluation results also appear in D17's and D18's transcripts.

**The tool was fixed before the last attempt** (`NOTES.md` row 16, fixed 2026-09-15, brought onto
`stage/s1` as `908a767`): the user chose to return stderr always and flag a failed evaluation,
with a failed form, a timeout and an unreachable nREPL worded apart. **So D20's third Tester
attempt ran on a different tool from its first two, and from D19**; `run_loop.clj` did not change.
tester-r2 got the amendment again, a `:triage` note naming the stub and the bracket, and the gate
output. It fixed line 99, rewrote the test to compare by identity, and reloaded its namespace with
ten test vars. None of its evaluations failed, so the fixed error path was not exercised in this
run. Every gate went green, gate 0 changed nothing, and the Reviewer found nothing. D20's
`final/` equals its gated bytes, and it was merged onto `stage/s1` as `c09c536`: 31 tests over
11 namespaces.

**Wall time.** 944m57s against 4m23s of steps. The run sat paused overnight between tester-r1's
`check` and the tool fix, while the blank results were investigated and the user chose a fix.

~$0.376136 across the 5 of 29 steps that report money, one of them computed. The Tester's three
attempts are $0.149298 of it.

## D21 · `t-23-simplify` — the first edit to an existing namespace, green, and the fixed tool read live

**2026-09-15 10:56** · **completed**, 1 attempt per role · stage S1, task 3 of 3 · the first task on the fixed `nrepl_eval` from its first dispatch

```

Run d21 · task t-23-simplify · done

  step       kind          model                                              provider           time       cost    tokens
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  provision  provision     —                                                  —                  4.3s          —         —
  sigs       precondition  —                                                  —                 139ms          —         —
  stub       provision     —                                                  —                   2ms          —         —
  coder      dispatch      claude-fable-5-1                                   Anthropic API     53.1s ~$0.216898    35,472
  tester     dispatch      openai/gpt-5.6-sol-20260709                        OpenAI            54.2s  $0.045871    26,394
  assemble   provision     —                                                  —                  45ms          —         —
  gate-0     gate-0        —                                                  —                 320ms          —         —
  calls      gate          —                                                  —                 238ms          —         —
  fmt        gate          —                                                  —                 146ms          —         —
  lint       gate          —                                                  —                  62ms          —         —
  test       gate          —                                                  —                  3.1s          —         —
  deps       gate          —                                                  —                  24ms          —         —
  reviewer   dispatch      google/gemini-3.8-flash-20260902                   Google            29.3s  $0.022557    28,016
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  total                                                                                         2m25s ~$0.285326    89,882

  Wall time 2m26s for the whole run; the steps above account for 2m25s of it.
  Cost covers 3 of 13 steps; the rest reported none.
  ~ = computed from list price for 1 of them (usage × the profile's :pricing), not reported by the endpoint.

```

`(simplify s vars)` parses `s`, substitutes what `vars` binds, folds, and renders — composing D19's
and D20's namespaces, both merged onto the stage, through `sandbox.api`. The first dispatched task
that **edits** a namespace rather than creating or rewriting one: `api.clj` keeps `calculate` and
`canonical`, which the slice does not name, because the stub the Tester is given can express one
arity per interface and `calculate` has two. They were left to the two files that require
`sandbox.api`.

| | prediction | result |
|---|---|---|
| P1 | `start` lists `api_test.clj` and `property_test.clj` as dependents; the `:deps-sigs` precondition passes against the merged `substitute.clj` and `constants.clj` | **held** — six signatures against six context files |
| P2 | the Coder keeps `calculate` and `canonical` and adds `simplify`, with no note | **held** |
| P3 | the `:calls` step passes over the whole of `api.clj` | **held** — 238ms |
| P4 | the Reviewer's diff shows an edit to `api.clj`, not a new file | **held** |
| P5 | every role goes green on the first attempt | **held** |

**The fixed tool, read by a model for the first time.** The Tester called `api/simplify` in its
worktree, where `api.clj` is the generated stub, and got back `ERROR: a form you evaluated failed.
The nREPL is reachable and answered …` with `Execution error (AssertionError) at
sandbox.api/simplify (api.clj:12). not implemented — generated stub: simplify`. Its final message:
"REPL evaluation confirmed the current `simplify` is an unimplemented stub". It carried on and
wrote its tests — where D20's second Tester attempt, on the old tool, saw the same kind of call
come back blank and stopped. One case, and a different task.

**The stage's first generative tests.** D19's and D20's test files had no `defspec`; D21's has two,
over generated expressions with division and zero divisors: evaluation agrees across `simplify`,
and `simplify` is idempotent and renders canonically. Gate 0 changed nothing, so `final/` is the
gated bytes; merged onto `stage/s1` as `b8d02ae`, 36 tests over 11 namespaces.

**Seven of eight mutants killed, and the survivor is not this run's.** Six mutants on `simplify`
all died. Of the two on the functions target 8 says must keep working, `calculate` ignoring its
bindings died on the dependents. `canonical` returning its input unchanged **survived**: no test
in the suite checks what `canonical` returns. `property_test.clj` checks only that it is
idempotent, which returning the input satisfies, and `api_test.clj` does not call it. The
Blueprint left `canonical` to its dependents, and they protect less of it than it assumed
(`NOTES.md` row 20).

**Cost.** The Coder's `~$0.216898` re-derives from its usage: (12 × $10 + 2,196 × $50 + 8,054 ×
$12.50 + 25,210 × $0.25) / 10⁶. ~$0.285326 across the 3 of 13 steps that report money, one of
them computed; 2m26s of wall time, with no pause.

## S1 · Step 5 — integration across the three tasks, and the exit criteria

**2026-09-15 11:15** · no model dispatched · written by the Orchestrator on `stage/s1` after the three merges

Method §07 Step 5 asks for tests "authored across task boundaries". Each task's tests stop at its
own namespace, and D21's cross-surface property binds only `x` and evaluates through `compute`.
`sandbox.simplify-integration-test` (`stage/s1` `0f13229`) tests what holds only when all three
namespaces, the parser and the renderer agree:

| test | what crosses a boundary |
|---|---|
| the stage document's exit-criteria examples | the four written in §11 before any dispatch |
| simplifying twice with the bindings split equals simplifying once | a folded, rendered result is re-parsed, substituted and folded again |
| `calculate` agrees before and after `simplify`, bindings split at random | through the public API rather than `compute`, with any of `x`, `y`, `z` on either side |
| no variable survives when every one is bound | `substitute` misses nothing that `fold-constants` then has to carry |

Each property was checked over 1,000 generated cases on the merged code before the file was
written, with division and zero divisors in the generator; the suite runs 300 of each. `bb gates` in
the stage's `sandbox/`: 40 tests over 11 namespaces, boundaries intact.

**Six mutants across the merged namespaces, run against this test namespace alone** so that a kill
is its own: five died — `substitute` never replacing, nothing ever folded, a remainder division
folded, `simplify` skipping the fold, `simplify` ignoring its bindings. **The survivor, `fold` not
recursing into the right subtree, cannot be reached from a string:** `sandbox.parse` folds left, so
every parsed tree's right operands are leaves. D20's own tests kill that mutant with nine failures;
an integration test through the text API is the wrong place to look for it. The script is local, in
the stage worktree's `.local/runs/s1/mutate-integration.bb`.

**The exit criteria**, from the stage document's §11, as written before dispatch:

| criterion | result |
|---|---|
| `(simplify "x + 1 + 2 * y" {"x" 3})` is `"6 * y"`; `(simplify "x * 2 - 1" {"x" 5})` is `"9"` | **met** — tested |
| `(simplify "y + 1 + 2" {})` is `"y + 1 + 2"`; nothing reassociated | **met** — tested |
| `(simplify "8 / 3 + x" {})` is `"8 / 3 + x"` | **met** — tested |
| `calculate` of a simplified string with the rest of the bindings equals `calculate` of the original with all of them | **met** — a property, 300 cases per run |
| `bb gates` green on `stage/s1` after each merge, and gate 4 knows both new namespaces | **met** — 21, 31 and 36 tests after D19, D20 and D21; 40 with Step 5 |
| each task recorded and written up | **met** — `runs/d19.edn`–`d21.edn`, §D19–§D21 |

**Human gate #2: accepted by the user, 2026-09-15 11:25, and `stage/s1` kept local** — not merged
into this branch, as decided before dispatch, so `sandbox/` here is still the fixture every run
started from. The branch and its worktree are kept as the stage's evidence, and its run directories
are copied into this checkout's gitignored `.local/runs/`.

## S1 · What the stage showed

**2026-09-15** · three tasks, eleven dispatches, an integration pass, both human gates

The stage was for the part of the method that twenty-one single-task runs never reached: tasks in
dependency order, each built on the previous task's merged code, then tests across them, with a
human gate before the first dispatch and after the last merge (method §07, Steps 2 and 5). Its three
tasks took 9m56s of steps and ~$0.94.

| | D19 · `substitute` | D20 · `fold-constants` | D21 · `simplify` |
|---|---|---|---|
| kind of change | new namespace | new namespace | edit to an existing one, with two dependents |
| attempts, Coder / Tester | 1 / 1 | 1 / 3 | 1 / 1 |
| red gates | 0 | 2, both the test gate | 0 |
| Reviewer findings | 0 | 0 | 0 |
| mutants killed | 9 of 10 | 11 of 11 | 7 of 8 |
| predictions | 4 held, 1 refuted | 3 held, 1 partly, 1 refuted | 5 held |
| steps / cost | 3m07s / ~$0.275703 | 4m23s / ~$0.376136 | 2m25s / ~$0.285326 |
| stage tests after the merge | 21 | 31 | 36, then 40 with Step 5 |

By role across the three: Coder ~$0.630429, Tester $0.253475, Reviewer $0.053261 — two thirds of
the money is the Coder, at list price. From the repository root:

```
bb -e '(let [rs (map #(clojure.edn/read-string {:default tagged-literal} (slurp (str "runs/" % ".edn"))) ["d19" "d20" "d21"]) steps (mapcat :run/steps rs) role (fn [s] (keyword (first (clojure.string/split (name (:step/name s)) #"-r")))) by (reduce (fn [m s] (if-let [c (:step/cost s)] (update m (role s) (fnil + 0) c) m)) {} steps)] (prn by (reduce + (vals by)) (reduce + (keep :step/ms steps))))'
```

prints the three role totals, their sum, and 595,792 ms of steps.

**What held.**

- **Dependency between tasks, checked by machine.** D21's `:deps-sigs` precondition and `:calls`
  check read `substitute.clj` and `constants.clj` as merged; `start` found `api.clj`'s two
  dependents; the Coder kept two functions the slice did not name, and the dependents' tests passed.
- **No driver change for the stage.** Run directories inside a `stage/s1` worktree made that branch
  every task's base, because the driver asks git for the repository root from the run directory.
  The stage document asked whether merging by hand would hurt enough to build `:base` into
  `loop.edn`. It did not: four commands a merge. What hurt was merging *from `final/`* (row 14).
- **The Coder, Fable 5.1 at low effort, was right first time in all three tasks.** No `:calls`
  violation; both transforms validated once at the entry; D20's red gates came from a test reading an
  ambiguous sentence, not from the implementation.
- **The Architect's side held where it was checked.** Every example in the Blueprint was run against
  a throwaway composition before sign-off, and none was wrong. Every exit criterion was met.
- **The pieces built for triage met live cases.** The leak check passed two real Tester retries; the
  routing proposal stood beside a real decision, agreeing on the role and not the sources.

**What the stage found that the single-task runs had not.** All of it in the harness, the
documents or the Architect's wording; none of it in a model's code.

- **`nrepl_eval` hid every failed evaluation**, and blank results were already in D17's and D18's
  transcripts; its unreachable-nREPL branch had never worked. Fixed partway through D20 (row 16).
- **A record holds each role's bytes, not the gated ones.** The first merge failed on it, and five
  committed records, D9–D13, carry a file their format gate did not pass as recorded (row 14).
- **One ambiguous sentence cost two Tester retries.** D19's and D20's Testers read the same
  validate-once wording two ways; the amendment was the Architect's to make.
- **Smaller gaps:** the routing proposal gives every red assertion in a task's own test to the
  Tester, against §07's default (row 17); a retry that wrote nothing still lists the previous
  attempt's file (18); `continue` records no reason (15); `bb report-check` misreads a labelled
  fence (19); nothing tests what `canonical` returns (20). And an Orchestrator's slip — `check` run
  on a dispatch whose final message said it had written nothing — is in §D20.

**What it could not show.**

- **Whether the Reviewer earns its dispatch.** Three reviews, no findings, and no defect in the
  Coder's code for one to find. The mutation checks did the verifying.
- **A routing proposal over an implementation bug.** The only red gates were a test's.
- **The fixed `nrepl_eval` beyond one live reading**, by D21's Tester, which read the stub's error
  and carried on.
- **Parallel tasks or an automated merge.** Work in progress stayed at one task and every merge was
  by hand.
- **A stage as a record.** Each task has one and `bb report-check` covers its table; Step 5, the
  two gates and the merges exist as prose here and as a local branch, and no gate checks what this
  document says about them.

**Where the kit stands** — a judgement from the above, not a measurement. The loop carries a small
dependent stage end to end, and on this evidence what limits it is not the Coder but what the harness
lets a person see afterwards: what a tool returned to a model, what gate 0 changed, why a run
continued. Of the seven register rows the stage added, row 16 was fixed during it. Rows 14, 15, 17, 18 and 19
were the next work, with D19–D21 re-recorded from their gated bytes once row 14 was fixed; row 20 is a
gap in the sandbox's own tests. `NOTES.md`'s register says where each stands now.
