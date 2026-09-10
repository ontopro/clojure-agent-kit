# <Project> — Decision Log

**Status:** LIVING — the single register of every project decision
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>

**How to read this log.** Every decision has a stable ID and one of three statuses:

- **RESOLVED** — decided; reversal would be a new decision, not an edit to this one.
- **PROVISIONAL** — a working selection the build proceeds on, **gated** by a named
  spike/stage; the fallback is documented and kept viable.
- **OPEN** — not yet decided; **owned** by the stage that first needs it.

This log is the mechanism by which the architecture evolves across stages: the
architecture and stage documents reference these IDs instead of carrying decision churn
in prose.

**ID families:** **D** = architecture · **S** = schema/data model · **P0** = method &
tooling · **RV** = plan-review decisions · <add your own>.

**Amendment procedure.** Anyone may propose a change, citing evidence (a spike result, a
stage learning). Status transitions are recorded **in place** — the row is updated with a
dated amendment note, the old resolution kept, never deleted. Reversing a PROVISIONAL
decision triggers its pre-named fallback. Any other document the decision materially
touches is updated in the same change. **IDs are never reused or renumbered.**
**Architectural changes need an impact analysis first.** When a **D** entry is added
mid-flight, reversed, or materially amended, run the analysis **before** committing to the
change and link it in the D-table's *Impact analysis* column. It must cover: requirements
touched (including ones already satisfied) · other decisions that depend on it, by ID ·
already-built stages, and whether the change is mechanical behind a protocol or a rewrite ·
data already stored, and whether the migration is reversible · tests encoding the old
assumption, which keep passing while being wrong · and whether the documented fallback is
still real or has rotted while unused.

This is the **Architect's or a human's** job, never the Coder's: an agent works from a
deliberately narrow slice and is structurally unable to see the blast radius of its own
change. Full checklist and rationale: `method.md` §08.


---

## 1. Plan-review decisions (RV)

| # | Decision | Status / Resolution |
|---|---|---|
| RV-1 | | |

## 2. Architecture decisions (D)

| # | Decision | Options considered | Status / Resolution | Impact analysis |
|---|---|---|---|---|
| D1 | <e.g. primary datastore> | <a · b · c> | **PROVISIONAL: <a>** — <rationale>. **Gate:** <spike ID, stage>. **Fallback:** <b> behind `<Protocol>`. | — (initial) |
| D2 | | | **OPEN** — owner: <stage that first needs it> | — |

## 3. Schema / data-model decisions (S)

| # | Decision | Options considered | Status / Resolution |
|---|---|---|---|
| S1 | | | |

## 4. Method & tooling decisions (P0)

| # | Decision | Options considered | Status / Resolution |
|---|---|---|---|
| P0-1 | Scaffold | clojure-stack-lite · hand-rolled | **RESOLVED: clojure-stack-lite**, generated once in the Foundation, adapted in Stage 1. |
| P0-2 | Dedicated Tester role | yes · no | |
| P0-3 | Model family per role | | **RESOLVED:** verifier ≠ Coder family. Specific models are dated selections. |
| P0-4 | Workspace isolation | one REPL per worktree · shared sessions | |
| P0-5 | Retry cap | | |
| P0-6 | Escalation semantics | discard · hand off partial output to the gates | |
| P0-7 | Orchestration | hand-run · built harness | |

## 5. Validation spikes (R)

> A spike is not "try it and see". Each has a stage that runs it, a pass criterion written
> **before** it runs, and a fallback that executes on failure.

| # | Spike | Gates decision | Runs in | Pass criterion | On failure |
|---|---|---|---|---|---|
| R1 | | D1 | Stage 1 | | Execute D1's fallback; re-run stage exit criteria |
