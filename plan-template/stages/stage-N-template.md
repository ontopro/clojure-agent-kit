# Stage <N> — <Name>

**Status:** PLANNED — for the person's approval, with its cap (§11)
**Kind:** <walking skeleton · increment> (stage 0 is the spike, `stage-0-spike-template.md`; pre-release and release have templates of their own)
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>
**Companion to:** `../02-architecture.md` · `../04-decision-log.md` · `../05-lessons.md`
**Runs through:** the Foundation (`../03-method-and-tooling.md`) — this plan is the
Architect's blueprint input

> The **stage plan**: the project plan's slice for this stage. Written **just-in-time**, when
> the stage is pulled — with the previous stage's §12 and `../05-lessons.md` read first, and
> nothing beyond this stage decided. Read cold by the plan review (`bb plan-review`) before the
> blueprint is cut from it; approved by the person with its cap before anything dispatches.

---

## 1. Goal

> One paragraph. What slice, over what input, proving what; the stage's kind. For stage 1, the
> **walking skeleton**: one small function end to end through every layer the architecture
> names, on the smallest input a person can walk in a browser or at a REPL — for a site, the
> home page and two pages; for a library, one public function with its test and its line in the
> README. **Full depth and the smallest breadth**, and both halves are the point. Say plainly
> what kind of thing it is: "It is the skeleton. Its job is to stand through every layer. It
> is **not** a scale test, and it shows little."

## 2. Requirements this stage adds

> Written here, when the stage is pulled (`method.md` §02) — not in `01-requirements.md`
> ahead of time. One row per requirement, each with a permanent ID that is never reused, the
> source observation it rests on (`S-n.m`) or `(inferred)` for the Architect's own, and its
> priority. The inferred ones are what the person is shown at this stage's approval. At the
> stage's end, each goes into the index in `../01-requirements.md` §6b.

| # | Requirement | Source | Priority |
|---|---|---|---|
| FR-n | | S-n.m | |
| NFR-n | | (inferred) | |

## 3. Decisions exercised by this stage

| Topic | Decision (see `../04-decision-log.md`) |
|---|---|
| <Datastore> | **<selection>** *(PROVISIONAL, D-n; gate R-n runs here)* — fallback: <x> |
| <Macro-architecture> | |
| <Scaffold> | Generated in the Foundation, adapted in stage 0 (P0-n) |

## 4. What the stage proves — and what it deliberately doesn't

**Proves (depth):**
- <capability> (FR-n)
- <the seams: which protocols get their first implementation>
- <the boundary gate passing against real layers>

**Shows (breadth):**
- <what a person sees when they walk it: which pages, which commands, which inputs>

**Does NOT prove, or show yet (deferred, tracked in the stage map — `../00-overview.md` §4):**
- **Scale** — <why this input is too small; which stage owns it>
- **Breadth** — <what it does not yet show, and which stage shows it>
- <feature deferred, and where it is tracked>

> Write every column. The last is what stops a skeleton from being mistaken for a scale
> test, or for the product, and it is where deferred work gets *tracked* rather than
> forgotten. A project that heard only the depth half made its first stage over every input it
> had, and the first packet cut from it cost a third of the build and merged nothing.

## 5. Architecture — the seams this stage establishes

> For stage 1 this is the heart of the plan: the protocols and contracts, and the
> layer/dependency rules that the boundary gate will enforce.

## 6. Data shapes

> The shapes this stage introduces, in Malli. These feed the blueprint directly and become
> the test contract.

## 7. Domain specifics

> Whatever is peculiar to this stage's input: source formats, quirks, licensing, size.

## 8. Tech stack (this stage)

| Concern | Selection | Status |
|---|---|---|

## 9. Local development environment, and local → deployed

> One command to run it. Container/runtime versions pinned and where. What changes between
> local and deployed, and what deliberately doesn't - the pre-release stages take it from here.

## 10. Dependency-ordered task list

> The Architect turns this into task packets of ten to twenty targets each, the fixture a test
> needs named in the input target that needs it (`method.md` §06). Each task: prototype in the
> REPL → persist → cheap gates → Reviewer. Sequence so each task builds only on what already
> exists. Make the first task small, to learn the roles' habits on this stage before the big ones.

1. **<the first task, small>** — <…>.
2. **Data shapes** — <the shapes from §6>.
3. **Protocols + boundary rules** — interfaces only.
4. …
N. **Containerize / CI** — <woven in here, not held for an infrastructure phase>.

## 11. Exit criteria (the stage's gate), and the cap

> Checkable, not admirable. A person follows these and gets a yes or a no. These are the
> stage's **definition of done**, and nothing closes the stage but them.

**Cap:** <$ for this stage>, approved by the person on <YYYY-MM-DD>; the stop: <what stops the
stage early - a figure, a stop named with the stage>. Recorded in `stage-N-gates.edn` beside
this plan, as `bb next` reads it. The stage's figures - money by role, rounds, stops by owner, retries
and rejections, the readings - against this cap and the stage before are `bb stage-report`'s: it
renders them into this section from the run records as a block, and `--check` holds the block
to the records, so the figures here are never typed by hand.

- <a concrete user-visible behaviour>
- <a concrete data behaviour, e.g. load v2, time-travel returns v1, diff lists changes>
- Boundary gate passes; each protocol has one implementation **and a documented fallback**
- **Deployed locally:** served by the project's own command from a clean checkout; the browser
  checks and the security pack run against it (`../03-method-and-tooling.md` §17), the pack's
  record `stage-N-security.edn` beside this plan with no failing row; and then **the owner's walk
  through every page the stage made**, with what they asked for done or written down - a
  first project's walk produced ten changes no target could have named
- **Decision-log updates recorded:** spike outcomes noted against the decisions they gate;
  provisional entries confirmed or reversed
- **The stage's end written:** this stage's section of `../05-lessons.md`, each lesson ending
  as a rule, a line in the next stage plan, or a decision; the requirements of §2 in the index
  (`../01-requirements.md` §6b); the scope lists revised against the source; the stage map
  re-ranked and the next stage named by kind - an increment, a pre-release stage, or the release

**Gate behaviour:** if <spike> fails here, the documented fallback executes behind the
protocol and the affected exit criteria re-run — the stage loops, it does not silently pass.

## 12. Residual risks / feeds into the next stage

> Read first when the next stage plan is written.
