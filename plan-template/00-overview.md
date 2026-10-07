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
2. **Primary stack.** Given by the KIT: the pinned template, server-rendered, no separate
   frontend build (`02-architecture.md` §1). State only what this project adds, and what of
   that is provisional.
3. **Datastore.** <selection> — mark PROVISIONAL if it is spike-gated, and name the fallback.
4. **Macro-architecture.** A modular monolith with enforced seams - modules wired by Integrant,
   boundaries enforced by the boundary gate, a protocol at every joint - is given
   (`02-architecture.md` §2–§3); <the seams this project adds, and the shape they make>.

---

## 3. How the plan is organized

| Document | Content | Change cadence |
|---|---|---|
| `source.md` | What the plan derives from: the brief, the material handed over, what was read from it, numbered | A record: appended to, never revised |
| `00-overview.md` (this doc) | Mission, governing decisions, stage map, review findings | Updated at stage boundaries |
| `01-requirements.md` | Users, use cases, constraints, assumptions, **the three scope lists**, success criteria; stage 1's requirements, and the index of every requirement by the stage that wrote it | The scope lists and the index revised at every stage's end; the rest slow-moving, via review |
| `02-architecture.md` | Layers, storage, cross-cutting concerns; provisional parts marked | Evolves; decisions referenced by ID |
| `03-method-and-tooling.md` | The build method: roles, protocols, quality gates, scaffold, harness | Stable once ready; runs under every stage |
| `04-decision-log.md` | **Every** decision with status: RESOLVED / PROVISIONAL (+ gating spike) / OPEN (+ owning stage) | **Living** |
| `05-lessons.md` | One section per stage, written at its end: what the roles did, what the person did by hand, what the next stage changes - each lesson ending as a rule, a line in the next stage plan, or a decision | Appended at every stage's end; read first when the next stage plan is written |
| `stages/stage-N-*.md` | One **stage plan** per stage: goal and kind, the requirements it adds, the decisions it exercises, what it proves and does not, its seams, its task list, its exit criteria and its cap | Created **just-in-time**, when the stage is pulled; approved by the person with its cap |
| `stages/stage-N-blueprint.md` | The stage's **blueprint**: shapes, signatures, namespaces, the task packets in dependency order; exactly one per stage | Written from the approved stage plan; signed off by the person |

**The decision log is how the iterative method and the architecture reconcile.** An aspect
under test lives as a PROVISIONAL entry naming the spike that confirms or reverses it; an
aspect not yet designed lives as OPEN naming the stage that will decide it. The
architecture doc stays readable because decision churn lives in the log, not the prose.

---

## 4. Stage map

Stages are numbered through, and each has a kind (`method.md` §04): stage 0 the **spike**,
stage 1 the **walking skeleton**, stages 2 to N the **increments**, then **pre-release** stages
and **release**. The **Foundation** (`03-method-and-tooling.md`) is built inside stage 0, before
its first packet dispatches, and runs continuously underneath every stage. Infrastructure and
operations work is **woven into stages**, not held in a phase of its own; what release needs
lands in the pre-release stages.

**Only the stage in progress has a cap.** The plan commits to stage 0 and nothing after it; each
later stage is approved with its own cap at its stage plan, and the rows below it are
indicative, re-ranked by the risks at every stage's end.

| Stage | Kind | Name | Delivers | Risk it retires | Key decisions it closes | Cap | Status |
|---|---|---|---|---|---|---|---|
| **0** | spike | <name> | Foundation built; the top risks proved against their pass criteria; running software, owed nothing - decisions confirmed or reversed | R1, R2 | <D-n …>, each pass or fallback | <$> | PLANNED |
| **1** | walking skeleton | <name> | <one small function end to end through every layer, on the smallest input a person can walk>; the seams established; the first owner's walk | <R-n> | <decision IDs> | — | NOT STARTED — plan written when pulled |
| **2** | increment | <name> | <deliverable> | <R-n> | <decision IDs> | — | NOT STARTED |
| **…** | increment | | | | | — | |
| **N** | pre-release | <name> | <host, publish, backup, restore, metrics, logging, auditing, the pre-publish gate - what of it this stage takes> | <R-n> | | — | NOT STARTED |
| **N+1** | release | <name> | packaged; deployed on the server or published for download | | | — | NOT STARTED |

**The MVP is named afterwards.** At every stage boundary ask "could we ship after this one?";
the first boundary where the answer is yes is the MVP - mark it in the Status column when it
comes, never before stage 0. Stages are **pulled when capacity frees, not scheduled** — there is
no timebox anywhere in this method.

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
| R1 | <the thing most likely to kill this> | <rewrite? relaunch? missed bar?> | <spike ID, and its pass criterion> | Stage 0 | OPEN |
| R2 | | | | | |

## 5. Review findings

> Filled by the plan-review pass (`method.md` §02), which runs in every stage's plan step: for
> stage 0 over the whole set, before Foundation; for every stage after, over the stage plan and
> the documents it revised, before the blueprint is cut. A different model or person from
> whoever wrote the plan; `bb plan-review` in the KIT's `harness/` is that pass, and writes its
> raw output to `../reviews/`. Requirement-level additions land in the stage plan that owns
> them and the index in `01-requirements.md`; decision-level items in `04-decision-log.md`.

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
  `AGENTS.md` — a gate fails on drift. Project rules override personal and global ones;
  where they conflict, the rule source wins (`03-method-and-tooling.md` §7.5).
- **Superseded plan documents are archived, not deleted.** They are historical records;
  nothing current depends on them.
