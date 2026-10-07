# Stage 0 — <Name>, the spike

**Status:** PLANNED — for the person's approval, with its cap (§7)
**Kind:** spike
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>
**Companion to:** `../00-overview.md` §4a · `../02-architecture.md` · `../04-decision-log.md`
**Builds:** the Foundation (`../03-method-and-tooling.md`), before its first packet dispatches

> The **stage 0 plan**. Stage 0 is a spike over the foundational choices (`method.md` §04):
> it proves the architecture's provisional decisions, the stack and the machine against pass
> criteria written before it runs, and it ends in running software. **What it owes is
> decisions confirmed or reversed, not code:** stage 1 keeps what passed its criterion and
> rewrites the rest with no obligation. The project plan commits to this stage and nothing after
> it. Delete this note.

---

## 1. Goal

> One paragraph. Which foundational choices this spike proves, over what input, and what
> "running" means at its end - a page served, a command answering, a function returning. Say
> plainly: "It is a spike. Its job is to answer the pass criteria cheaply. Nothing in it is owed
> to stage 1."

## 2. The risks it proves, and the decisions it gates

> From the risk register (`../00-overview.md` §4a) and the decision log: every PROVISIONAL
> entry stage 0 gates, with the pass criterion written **before** the spike runs and the
> fallback that executes if it fails. A risk with no criterion is a poke around, not a spike.

| Risk | Decision it gates | Pass criterion | Fallback | Answered |
|---|---|---|---|---|
| R1 | D-n *(PROVISIONAL: <selection>)* | <what must be shown, checkable> | <x> behind `<Protocol>` | <pass · fallback, YYYY-MM-DD> |
| R2 | | | | |

## 3. The Foundation

> Built here, inside stage 0: `bb doctor`, `bb health`, `bb init` done at setup; the profile
> and the rules overlay filled; the gate sequence read; the readiness checklist
> (`../03-method-and-tooling.md` §18) closed against a live run. **Stage 0's first packet is
> that run** - a small packet with a real question - and Foundation is ready when it dispatches,
> not before and not after.

| Readiness item | Closed by | Date |
|---|---|---|
| `bb doctor`, both verdicts yes | | |
| `bb plan-check` passes | | |
| `bb health` on this machine | | |
| the first packet through the loop with this project's models | | |

## 4. What runs at its end

> The software a person can run locally when the spike is done - served by the project's own
> command, or a REPL session with the functions that answer the criteria - and what it shows.
> Not a product and not the skeleton: the skeleton is stage 1's, built on what this settled.

## 5. Dependency-ordered task list

> Small packets, each a question from §2, through the loop - stage 0 proves the architecture
> and the machine at once. Trying two options against each other is faster in the Architect's
> REPL than through contracts: a spike may do that by hand, and what it keeps goes through the
> loop. Nothing here is owed to stage 1.

1. **<the first packet, the first criterion>** — <…>.
2. …

## 6. What is kept

> Decided at the end, criterion by criterion: what passed and stays as stage 1's starting
> point, and what is rewritten or dropped. The decision log carries the answers (§2, the last
> column); this section says what code survives them.

## 7. Exit criteria (the stage's gate), and the cap

**Cap:** <$ for stage 0>, approved by the person on <YYYY-MM-DD>; the stop: <what stops it
early>. Recorded in `stage-0-gates.edn` beside this plan.

- **Every pass criterion of §2 answered** - pass, or the fallback executed - and the decision
  log says which, dated
- **The Foundation's readiness checklist closed** against the live run (§3)
- **Running software at its end** (§4), served or evaluated by the project's own command
- **What is kept, said** (§6); the rest owed to nobody
- **The stage's end written:** stage 0's section of `../05-lessons.md`; the scope lists and the
  stage map revised; stage 1's plan pulled next, from this one's §8

**Gate behaviour:** a criterion that fails executes its fallback behind the protocol and is
answered as such; the spike does not loop until it passes - its job is the answer.

## 8. Residual risks / feeds into stage 1

> What stage 1, the walking skeleton, starts from: the decisions settled, the code kept, the
> risks still open and the stage that takes each. Read first when stage 1's plan is written.
