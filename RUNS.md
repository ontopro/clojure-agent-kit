# Runs

Every end-to-end run of the loop against [`sandbox/`](sandbox/), and what it
found. Method §03 step 5 and Foundation readiness criterion 6.

**These are the deliverable, not a green run.** §12 says to run the loop by
hand before automating it, *"and automate the orchestration only once you've
felt where the manual version actually hurts"* — so what each run is for is
the list of things that hurt. Every one of them found something, and most of
what `harness-seed/` has grown since exists because a run made it necessary.

**Nine runs in two parts.** Runs 1–5 went through `ManualRunner` with the
roles played by hand — no model was called and nothing was spent, which is why
their reports show `—` for model, provider and cost. D3–D6 were dispatched to
real models over HTTP through `harness.runner/api-runner`, and cost about
$0.55 between them. Every run gets three git worktrees from
`harness.provision`, torn down after.

The design conclusions these produced live in
[`portability.md`](portability.md); this file is the evidence. Most of what the
runs found was fixed and is explained below where it was found — **what is
still open is a table in [`NOTES.md`](NOTES.md)**, so you do not have to read
nine runs to learn the standing state.

## At a glance

| | Task | When | Outcome | Wall | Cost |
|---|---|---|---|---|---|
| Run 1 | `t-01-clamp` | 2026-09-11 23:38 | completed, 2 attempts | — | none |
| Run 2 | `t-02-between` | 2026-09-11 23:55 | completed, 1 attempt | — | none |
| Run 3 | `t-03-analyze` | 2026-09-12 00:17 | completed, 2 attempts | 7.4s | none |
| Run 4 | `t-04-reduce` | 2026-09-12 00:47 | completed, 3 attempts | 1m21s | none |
| Run 5 | `t-05-render` | 2026-09-12 01:15 | completed, 3 attempts | 59.5s | none |
| D1 | *(smoke, not a run)* | 2026-09-12 08:54 | 2 completions | 3.0s | $0.000004 |
| D2 | *(build step)* | 2026-09-12 09:10 | no model called | — | none |
| D3 | `t-06-clamp` | 2026-09-12 09:23 | completed, Coder only | 29.3s | not measurable |
| D4 | `t-07-vars` | 2026-09-12 09:51 | **FAILED** at the test gate | 16m51s | $0.016014 |
| D5 | `t-07-vars` | 2026-09-12 14:56 | completed | 5m50s | $0.119274 |
| D6 | `t-08-substitute` | 2026-09-12 15:33 | completed, 3 Tester attempts | 21m06s | $0.410322 |

Wall and cost are the totals from each run's own report, below. The manual runs
spent nothing because no model was called; D3's Coder went direct to Anthropic,
which reports tokens and not money. Runs 1 and 2 have no surviving record.
**$0.55 across every dispatched run.**

**Every table below re-renders from [`runs/`](runs/)** — `bb report < ../runs/d6.edn`
from `harness-seed/`. The records are committed precisely so these figures can be
checked rather than taken on trust; the drivers that produced them are not.

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
pre-existing `property_test.clj` and `api_test.clj` depend on `render` and are
in neither role's `:files/target`; both stayed green, because everything
`parse` produces is a left spine and therefore still renderable. That is luck
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
