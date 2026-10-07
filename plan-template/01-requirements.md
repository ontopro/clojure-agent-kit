# <Project> — Requirements

**Status:** LIVING at §10 and the index, revised at every stage's end; the rest slow-moving, revised via a review pass (§12 says how)
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>
**Derived from:** `source.md` — cite it by observation (`S-n.m`), do not restate it

> Every requirement gets a permanent ID (`FR-n`, `NFR-n`). Never renumber. Architecture,
> stage plans and exit criteria reference these IDs. A requirement that rests on a reading of
> the source rather than a statement in it says so: *FR-3 (S-1.4)*; one that rests on neither
> is marked as the Architect's inference, *FR-7 (inferred)*, and is shown to the person at the
> stage's approval.
>
> **Requirements are written per stage, when the stage is pulled** (`method.md` §02): the
> stage plan that builds a requirement writes it, in its §2, and this document keeps the index
> of every requirement by the stage that wrote it (§6b). Stage 1's are written here as well,
> since the plan is read before stage 1's plan exists. A requirement written here for a stage
> not yet pulled is a finding of the plan review.

---

## Executive summary

> Three to five sentences a stakeholder could read alone.

## 1. Core purpose

## 2. Guiding design principles

> The handful of principles that settle arguments later — e.g. "one canonical model, many
> projections"; "integrity over convenience"; "every seam is a protocol".

## 3. Background & context

## 4. Target users & roles

| Role | Who they are | What they need |
|---|---|---|

## 5. Primary use cases

| # | Use case | Actor |
|---|---|---|

## 6. Functional requirements — stage 1's

> The walking skeleton's: one small function end to end through every layer, the smallest
> breadth a person can walk. Group by capability area. One row per requirement, each with a
> permanent ID and its source observation or `(inferred)`. Nothing here for a stage not yet
> pulled: that stage writes its own, in its stage plan.

### 6.1 <Area>
| # | Requirement | Source | Priority |
|---|---|---|---|
| FR-1 | | S-n.m | |

### 6b. The index — every requirement, by the stage that wrote it

> Appended at each stage boundary, by the stage's end: one line per requirement, where it is
> written. This is the one place every ID is findable.

| # | Stage | Written in | One line |
|---|---|---|---|
| FR-1 | 1 | this document, §6.1 | |
| FR-n | <N> | `stages/stage-N-<name>.md` §2 | |

## 7. Non-functional requirements

> The four most commonly omitted are listed here on purpose — delete only deliberately. Most
> of these are written when the pre-release stages are pulled, as any requirement is; a target
> a stage must meet earlier (a performance figure measured from the first stage that measures)
> is written by that stage. Until then a row names the stage that will write it.

| # | Requirement | Target | Priority |
|---|---|---|---|
| NFR-1 | Scale | <numeric target, or "set from Stage N measurements, enforced at Stage M"> | |
| NFR-2 | Performance | <same> | |
| NFR-3 | Modularity — enforced seams, no cross-layer reach-through | boundary gate passes | |
| NFR-4 | <domain-specific correctness bar> | | |
| NFR-5 | **Availability & recoverability** — uptime, backup, restore, RPO/RTO | | |
| NFR-6 | **Observability** — logs, metrics, traces; what must be answerable post-hoc | | |
| NFR-7 | **Security engineering** — authn/authz model, dependency scanning, secret handling | | |
| NFR-8 | **Accessibility** — <e.g. WCAG 2.1 AA> if there is a UI | | |

## 8. Constraints

> Budget, hardware, licensing, data-source terms, regulatory, team size.

## 9. Assumptions

> Write down what the system deliberately does **not** hold or touch. It is load-bearing
> for scoping and compliance, and it is the thing most often left implicit.

| # | Assumption |
|---|---|
| A-1 | |

## 10. Scope — three lists, revised at every stage's end

> Three lists, one decision: what ships first, what is deferred and to which stage, and what
> is out of scope and why. Keep them together — a deferral and an exclusion look identical six
> months later unless you wrote down which one you meant; they differ by one thing only, that a
> deferral has a stage that will take it. Reference requirements by ID; don't restate them.
> Revised at every stage's end, against the source: the observations of `source.md` that no
> requirement, deferral or exclusion claims are the backlog nobody decided on, and the stage's
> end is where they are decided.
>
> **The MVP is named afterwards, not here** (`method.md` §02): the first stage boundary where
> "could we ship after this one?" is yes, marked in the stage map when it comes. A spike is
> not scope either — "the smallest thing that proves the design" is stage 0's job.

**Ships first**

| # | Requirement | Why it can't wait |
|---|---|---|
| FR-1 | | |

**Deferred — with the stage that takes it**

| # | Requirement | Why it can wait | Stage that takes it |
|---|---|---|---|
| FR-n | | | <N> |

**Out of scope — with the reason**

> An exclusion is a decision: it has an ID in the decision log, a reason, and is reversed only
> by a later decision that cites it - never reargued in passing, and never sealed. The brief is
> a starting point, not a contract.

| # | Out of scope | Why | Decision |
|---|---|---|---|
| X-1 | | | D-n |

> Risks and their experiments live in the risk register (`00-overview.md` §4a), and the
> decisions they gate in `04-decision-log.md`.

## 11. Success criteria

> Checkable, not admirable.

## 12. Revisions

**This document is stable, not frozen.** A requirement a stage teaches - one discovered, one
that split, a priority that moved - enters through the mechanism and not around it: a
decision-log entry (`04-decision-log.md`, with the evidence), then a review pass over this
document, then the overview's revision at the stage boundary. The row below is written by the
review pass; nothing else edits a requirement in place.

| Version | Date | Stage | Changed | Via |
|---|---|---|---|---|
| 0.1 | <YYYY-MM-DD> | — | initial cut | plan review (`00-overview.md` §5) |
| <0.2> | | <N> | <FR-n split into FR-n and FR-m; NFR-2 target set from measurements> | <D-n, RV-n> |
