# <Project> — Requirements

**Status:** STABLE — revised only via a review pass
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>

> Every requirement gets a permanent ID (`FR-n`, `NFR-n`). Never renumber. Architecture,
> stage docs and exit criteria reference these IDs.

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

## 6. Functional requirements

> Group by capability area. One row per requirement, each with a permanent ID.

### 6.1 <Area>
| # | Requirement | Priority |
|---|---|---|
| FR-1 | | |

## 7. Non-functional requirements

> The four most commonly omitted are listed here on purpose — delete only deliberately.

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

## 10. Scope — MVP, post-MVP, non-goals

> Three lists, one decision: what ships first, what waits, and what is never in. Keep them
> together — a deferral and an exclusion look identical six months later unless you wrote
> down which one you meant.
>
> **MVP is the smallest *shippable* feature set that delivers real value to a user.** It is
> a scope decision and nothing else — not "the smallest thing that proves the design", which
> is a spike (see `method.md` §02). Reference requirements by ID; don't restate
> them.

**MVP — ships first**

| # | Requirement | Why it can't wait |
|---|---|---|
| FR-1 | | |

**Post-MVP — deferred, not dropped**

| # | Requirement | Why it can wait | Earliest stage |
|---|---|---|---|
| FR-n | | | |

**Non-goals — confirmed out, permanently**

> What this product is explicitly not, so nobody re-litigates it in stage 3. Different in
> kind from post-MVP: these have no "earliest stage".

| # | Non-goal | Why it is out |
|---|---|---|
| NG-1 | | |

> Which stage completes the MVP is recorded in the stage map (`00-overview.md` §4).
> De-risking prototypes are **not** scoped here — a post-MVP feature that needs an unproven
> datastore is as risky as an MVP one. Risks and their experiments live in the risk register
> (`00-overview.md`), and the decisions they gate live in `04-decision-log.md`.

## 11. Success criteria

> Checkable, not admirable.
