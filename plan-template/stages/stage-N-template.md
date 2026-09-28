# Stage <N> — <Name>

**Status:** PLANNED — for human sign-off (workflow Step 2 gate)
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>
**Companion to:** `../02-architecture.md` · `../04-decision-log.md`
**Runs through:** the Foundation (`../03-method-and-tooling.md`) — this doc is the
Architect's Blueprint input

> Written **just-in-time**, when the stage begins — not months ahead. Everything beyond
> the stage in progress is provisional.

---

## 1. Goal

> One paragraph. What slice, over what input, proving what. For a first stage: a **thin but
> full-depth vertical slice** — end to end through every layer on the smallest realistic
> input, chosen to prove the seams and de-risk the provisional decisions. Say plainly what
> kind of thing it is: "It is a prototype. Its job is to de-risk decisions cheaply. It is
> **not** a scale test."

## 2. Decisions exercised by this stage

| Topic | Decision (see `../04-decision-log.md`) |
|---|---|
| <Datastore> | **<selection>** *(PROVISIONAL, D-n; gate R-n runs here)* — fallback: <x> |
| <Macro-architecture> | |
| <Scaffold> | Generated in the Foundation, adapted here (P0-n) |

## 3. What the slice proves — and what it deliberately doesn't

**Proves:**
- <capability> (FR-n)
- <the seams: which protocols get their first implementation>
- <the boundary gate passing against real layers>

**Does NOT prove (deferred, tracked in the stage map — `../00-overview.md` §4):**
- **Scale** — <why this input is too small; which stage owns it>
- <feature deferred, and where it is tracked>

> Write both columns. The second is what stops a prototype from being mistaken for a
> scale test, and it is where deferred work gets *tracked* rather than forgotten.

## 4. Architecture — the seams this stage establishes

> For a first stage this is the heart of the document: the protocols and contracts, and
> the layer/dependency rules that the boundary gate will enforce.

## 5. Data shapes

> The shapes this stage introduces, in Malli. These feed the Blueprint directly and become
> the test contract.

## 6. Domain specifics

> Whatever is peculiar to this stage's input: source formats, quirks, licensing, size.

## 7. Tech stack (this stage)

| Concern | Selection | Status |
|---|---|---|

## 8. Local development environment

> One command to run it. Container/runtime versions pinned and where.

## 9. Local → deployed portability

> What changes between local and deployed, and what deliberately doesn't.

## 10. Dependency-ordered task list

> The Architect turns this into task packets. Each task: prototype in the REPL → persist →
> cheap gates → Reviewer. Sequence so each task builds only on what already exists.

1. **Adapt the scaffold** — strip the example domain; confirm it still boots.
2. **Data shapes** — <the shapes from §5>.
3. **Protocols + boundary rules** — interfaces only.
4. …
N. **Containerize / CI** — <woven in here, not held for an infrastructure phase>.

## 11. Exit criteria (stage gate)

> Checkable, not admirable. A person follows these and gets a yes or a no.

- <a concrete user-visible behaviour>
- <a concrete data behaviour, e.g. load v2, time-travel returns v1, diff lists changes>
- Boundary gate passes; each protocol has one implementation **and a documented fallback**
- Runs locally with one command
- **Decision-log updates recorded:** spike outcomes noted against the decisions they gate;
  provisional entries confirmed or reversed

**Gate behaviour:** if <spike> fails here, the documented fallback executes behind the
protocol and the affected exit criteria re-run — the stage loops, it does not silently pass.

## 12. Residual risks / feeds into the next stage
