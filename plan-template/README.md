# The plan template

The documents a project's plan is made of, blank. `bb init <name>` copies them, this file
included, into `<name>-plan/docs/` and commits them as that repository's first commit, the way it
generates `<name>-app/` from the KIT's application template. Companion to
[`../method.md`](../method.md), the method these documents implement.

```
<name>-plan/
  docs/
    source.md                 ← write it first; what the plan derives from, and never revised
    00-overview.md            ← start here; write it last, revise it at every stage boundary
    01-requirements.md        ← stable; the thing the plan review is run against
    02-architecture.md        ← evolves; references decisions by ID, never restates them
    03-method-and-tooling.md  ← the method, instantiated for this project
    04-decision-log.md        ← living; the mechanism by which everything else evolves
    stages/
      stage-N-<name>.md       ← written just-in-time, when the stage begins
      stage-N-blueprint.md    ← the Architect's output for that stage; human-gated
  reviews/                    ← a review's raw material, outside the governing documents
  rules.edn                   ← this project's rules over the KIT's rule source: its placeholders, filled here
  profile.edn                 ← the models per role and the seat; bb profile checks it
  loop.edn                    ← the defaults a run directory copies
  runs/                       ← one record per run, copied by the loop's record; RUNS.md publishes them
  bake-offs/                  ← candidates for a role compared: bb bake-off's specs, records, tables and marks
```

Beside `docs/`, `bb init` writes `rules.edn`, `profile.edn`, `loop.edn`, `runs/` (empty until the
loop's `record` copies the first record there) and `RUNS.md` (its header; the reports come with
the records); `reviews/` appears with the first `bb plan-review` and `bake-offs/` with the first
`bb bake-off new`. All of it is the rest of what a project decides or produces: the plan
repository holds all of it, and the KIT's clone none.

**Order of writing.** Source → requirements → architecture → method → decision log → overview,
and the overlay's three placeholders in `rules.edn` with the method. Then `bb plan-check` (the
gate) and the plan-review pass (`method.md` §02; `bb plan-review` runs it), then Foundation, then
the first stage document. Do not write stage 2's document while stage 1 is running.

**What this plan derives from goes in `source.md`**, first: the brief, the material handed over
(a document, a conversation, an image), and what was read from it, numbered so that a requirement
cites its evidence instead of carrying it. It is a record, appended to and never revised, which
is what lets `01-requirements.md` stay the contract.

**Three parts in the two documents the KIT has already half-written.** `02-architecture.md` and
`03-method-and-tooling.md` are each in three parts: *Given* — what adopting the KIT fixed, stated
as references to where the KIT says it and not as questions; *Chosen* — what this project decides
once, in Foundation, and records in the decision log; *Theirs* — the domain. Angle brackets appear
only in the last two. A given part is not edited; changing one leaves the certified pair, and
`bb plan-check` reads nothing in one as an instruction - a blockquote there is the KIT's prose,
kept as written.

**`reviews/` is the home for a review's raw material**: each reader's output from the plan-review
pass, the raw reads of a Blueprint, any comparison of readers - everything that is about the
reading rather than the plan. The overview's findings table (`00-overview.md` §5) keeps the
findings and their resolutions and nothing else; a comparison of four readers that tripled one
project's overview belonged here. `bb plan-review` writes its findings here too.

**Who fills them: the Architect's session** - the person, or the model they work with in the
session started at the workspace root - in Phase A, before any loop exists. No dispatched role
ever writes a plan document. **Angle brackets** `<like this>` mark things to replace. Delete
every instructional blockquote once you've acted on it — a document left half-filled reads as a
decision, and an angle bracket left standing reaches whoever reads the plan next as literal text.
`bb plan-check` in the KIT's `harness/` holds the plan to both (and to the rules overlay's
placeholders and the given parts), and `start` runs it once per workspace before its first
dispatch; this README and the two stage templates are not checked, everything else under `docs/` is.
