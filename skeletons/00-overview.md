# <Project> — Plan Overview

**Status:** ACTIVE — the entry point to the project plan
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>

---

## 1. Mission

> One paragraph on what this is and who it serves. Then one on the friction it removes —
> the concrete, specific pain, not the category. Then name the surfaces (UI, API, CLI)
> and any quality bar that changes engineering decisions downstream (precision,
> auditability, latency, compliance).

Full requirements: `01-requirements.md`.

---

## 2. Governing decisions

> The three-to-five decisions everything else assumes. Each gets an ID in the decision
> log with its rationale; this list is the readable summary. Typical set:

1. **Methodology.** Delivered iteratively in **stages**, each producing a working,
   functional component. Requirements, architecture and design **evolve across stages**;
   some aspects are deliberately left OPEN, owned by the stage that first needs them.
2. **Primary stack.** <e.g. Clojure (JVM) end-to-end; server-rendered HTML from the
   backend; no separate frontend build.> State what is **final** and what is provisional.
3. **Datastore.** <selection> — mark PROVISIONAL if it is spike-gated, and name the fallback.
4. **Macro-architecture.** <e.g. modular monolith with enforced seams: modules wired by
   Integrant, boundaries enforced by clj-depend, stable protocol contracts at every joint.>

---

## 3. How the plan is organized

| Document | Content | Change cadence |
|---|---|---|
| `00-overview.md` (this doc) | Mission, governing decisions, stage map, review findings | Updated at stage boundaries |
| `01-requirements.md` | Users, use cases, FRs + NFRs, constraints, assumptions, **MVP / post-MVP / non-goals scope** | Slow-moving, but not frozen — the scope split shifts as stages teach you; revise via review |
| `02-architecture.md` | Layers, storage, cross-cutting concerns; provisional parts marked | Evolves; decisions referenced by ID |
| `03-method-and-tooling.md` | The build method: roles, protocols, quality gates, scaffold, harness | Stable once ready; runs under every stage |
| `04-decision-log.md` | **Every** decision with status: RESOLVED / PROVISIONAL (+ gating spike) / OPEN (+ owning stage) | **Living** |
| `stages/stage-N-*.md` | One doc per stage: goal, scope, task list, exit criteria | Created **just-in-time** |

**The decision log is how the iterative method and the architecture reconcile.** An aspect
under test lives as a PROVISIONAL entry naming the spike that confirms or reverses it; an
aspect not yet designed lives as OPEN naming the stage that will decide it. The
architecture doc stays readable because decision churn lives in the log, not the prose.

---

## 4. Stage map

The **Foundation** (`03-method-and-tooling.md`) is not a stage: it is configured before
Stage 1 dispatches and runs continuously underneath every stage. Infrastructure and
operations work is **woven into stages**, not held in a phase of its own.

| Stage | Name | Delivers | Risk it retires | Key decisions it closes | MVP? | Status |
|---|---|---|---|---|---|---|
| — | **Foundation** | Workflow, scaffold, quality gates, dispatch loop | — | P0-* | — | <NOT STARTED> |
| **1** | **Vertical slice (<smallest realistic input>)** | Full-depth slice: <ingest → model → store → service → surfaces>; seams established | R1, R2 | First validation of the provisional datastore(s) | — | PLANNED |
| **2** | <name> | <deliverable> | <R-n> | <decision IDs> | **◀ MVP** | NOT STARTED — doc written at stage start |
| **3** | <name> | <deliverable> | <R-n> | <decision IDs> | post | NOT STARTED |

The **◀ MVP** marker names the stage that completes the minimum shippable set (scoped in `01-requirements.md` §10); everything after it is post-MVP by construction. Stages are **pulled when capacity frees, not scheduled** — there is no timebox anywhere in this method.

Stage boundaries are **gates, not dates**: a stage closes when its exit criteria pass *and*
its decisions are recorded in the log. A gate can **loop back** — if a spike fails, the
documented fallback executes behind the protocol and the stage re-runs its exit criteria.
Stage content beyond the next stage is indicative and will be re-planned.

---

## 4a. Risk register

> Ranked by what would hurt most, highest first. **The stage order follows this table**, not
> dependency convenience — a wrong store discovered in stage 4 is a rewrite; the same choice
> discovered in stage 1 is a config change behind a protocol.
>
> Every top risk gets an experiment cheap enough to be worth running and specific enough to
> fail. De-risking is not confined to early stages: any design or architectural change
> raises a new risk and earns a new prototype (`method.md` §02, §08).

| # | Risk | What it would cost | Prototype / spike that retires it | Runs in | Status |
|---|---|---|---|---|---|
| R1 | <the thing most likely to kill this> | <rewrite? relaunch? missed bar?> | <spike ID, and its pass criterion> | Stage 1 | OPEN |
| R2 | | | | | |

## 5. Review findings

> Filled by the plan-review pass (`method.md` §02) — run this before Foundation
> starts, ideally with a different model or person from whoever wrote the plan. Requirement-
> level additions land in `01-requirements.md`; decision-level items in `04-decision-log.md`.

| # | Finding | Resolution |
|---|---|---|
| F1 | <gap or contradiction found> | <what changed, and where> |

---

## 6. Working agreements

- **Decision IDs are stable.** FR-n/NFR-n, D-n, S-n, P0-n keep their identifiers
  permanently; new items get new numbers. Traceability depends on this.
- **Every stage runs through the Foundation.** No code merges outside the workflow and
  quality gates in `03-method-and-tooling.md`.
- **Agent rules live in the rule source.** Never in a prompt string, never hand-edited into
  `CLAUDE.md` — a gate fails on drift. Project rules override personal and global ones;
  where they conflict, the rule source wins (`03-method-and-tooling.md` §7.5).
- **Superseded plan documents are archived, not deleted.** They are historical records;
  nothing current depends on them.
