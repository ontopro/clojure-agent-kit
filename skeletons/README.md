# Project document skeletons

Copy this directory into a new project's plan repo and fill it in. Companion to
[`../method.md`](../method.md) — the method these documents implement.

```
plan/
  00-overview.md            ← start here; write it last, revise it at every stage boundary
  01-requirements.md        ← stable; the thing the plan review is run against
  02-architecture.md        ← evolves; references decisions by ID, never restates them
  03-method-and-tooling.md  ← the method, instantiated for this project
  04-decision-log.md        ← living; the mechanism by which everything else evolves
  stages/
    stage-N-<name>.md       ← written just-in-time, when the stage begins
    stage-N-blueprint.md    ← the Architect's output for that stage; human-gated
```

**Order of writing.** Requirements → architecture → method → decision log → overview.
Then the plan-review pass (`method.md` §02), then Foundation, then the first
stage document. Do not write stage 2's document while stage 1 is running.

**Angle brackets** `<like this>` mark things to replace. Delete every instructional
blockquote once you've acted on it — a skeleton left half-filled reads as a decision.
