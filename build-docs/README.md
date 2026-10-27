# The build docs

The documents a project's plan is made of, blank. `bb init` copies them, this file
included, into `<name>-build/docs/` (or the folder `--build` names) and commits them as that
repository's first commit, the way it generates `<name>-app/` from the KIT's application template. Companion to
[`../method.md`](../method.md), the method these documents implement.

```
<name>-build/
  docs/
    source.md                 ← write it first; what the plan derives from, and never revised; the brief is its first row and Appendix A (`bb brief`)
    00-overview.md            ← start here; write it last, revise it at every stage's end
    01-requirements.md        ← the scope lists and the index, revised at every stage's end; stage 1's requirements
    02-architecture.md        ← evolves; references decisions by ID, never restates them
    03-method-and-tooling.md  ← the method, instantiated for this project
    04-decision-log.md        ← living; the mechanism by which everything else evolves
    05-lessons.md             ← one section per stage, written at its end, read first when the next stage plan is written
    stages/                   ← the stage templates, and a folder per stage, made when the stage is pulled
      stage-names.edn         ← this project's own names in a stage folder, beyond the KIT's (the table below)
      stage-0/
        stage-0-plan.md       ← the stage 0 plan, the spike's, from stage-0-spike-template.md; every stage's plan is stage-N-plan.md, its kind in its **Kind:** line
      stage-N/
        stage-N-plan.md       ← the stage plan, written just-in-time when the stage is pulled, from stage-N-template.md (pre-release and release from their own templates)
        stage-N-blueprint.md  ← the stage's blueprint, the Architect's output; signed off by the person
        stage-N-gates.edn     ← the stage's human gates as a record (a pre-release stage's fourth, its threat model signed), from stage-N-gates-template.edn; `bb next` reads it
        stage-N-security*.edn ← the security pack's records at the stage's end, and stage-N-security-review/ the security review's
        stage-N-browser/      ← the browser pack's outputs the stage's documents cite, copied there from where it writes
        reviews/              ← the stage plan's and the blueprint's readings
        notes/                ← the stage's working documents - a brief, a handout, a draft: stage-N-q<n>-<topic>.md for an amendment, stage-N-<topic>.md otherwise; read by no check, retired once the governing documents hold them
  reviews/                    ← the project plan review's raw material, outside the governing documents
  rules.edn                   ← this project's rules over the KIT's rule source: its placeholders, filled here
  profile.edn                 ← the models per role and the seat; bb profile checks it
  loop.edn                    ← the defaults a run directory copies
  runs/                       ← one record per run, copied by the loop's record; RUNS.md publishes them
  bake-offs/                  ← candidates for a role compared: bb bake-off's specs, records, tables and marks
```

Beside `docs/`, `bb init` writes `rules.edn`, `profile.edn`, `loop.edn`, `runs/` (empty until the
loop's `record` copies the first record there) and `RUNS.md` (its header; the reports come with
the records); `reviews/` appears with the project plan review and `bake-offs/` with the first
`bb bake-off new`. All of it is the rest of what a project decides or produces: the build
repository holds all of it, and the KIT's clone none.

**A stage folder's names.** What `docs/stages/stage-N/` holds at its top level is this table, `N` the
stage's number and a name ending `/` a folder; the KIT reads the same table
(`harness.setup.stages/folder-names`). A file of the project's own that belongs beside the stage plan
is added to `stages/stage-names.edn` in the same form (`"stage-N-rehearsal.md"`); anything else goes in
`notes/`, and `bb next` names one left at the top level beside its step.

| Name | Role | What |
|---|---|---|
| `stage-N-plan.md` | governing | the stage plan |
| `stage-N-blueprint.md` | governing | the blueprint |
| `stage-N-gates.edn` | governing | the gates record |
| `reviews/` | record | the stage plan's and the blueprint's readings |
| `stage-N-security.edn` | record | the security pack's checks |
| `stage-N-security-deps.edn` | record | the security pack's dependency scan |
| `stage-N-security-secrets.edn` | record | the security pack's secrets scan |
| `stage-N-security-review/` | record | the security review's records |
| `stage-N-browser/` | KIT pack | the browser pack's outputs a document cites |
| `notes/` | working | the stage's working documents |

**Order of writing.** Scoping first, before any of this is written: the brief, which the `kit-scoping`
skill writes to `work/brief.md` and `bb brief` files (`method.md` §03, scope). Then source → requirements (the scope lists; stage 1's
requirements, no more) → architecture (candidates, provisional) → method → decision log (each
candidate with the pass criterion stage 0 applies) → overview (the stage map by kind, a cap on
stage 0 alone), and the overlay's three placeholders in `rules.edn` with the method. Then the
plan-review pass (`method.md` §03; `bb plan-review` runs it) and `bb plan-check` (the gate), the
person's approval of stage 0 with its cap, then the stage 0 plan - whose first packet proves the loop - and
stage 0 itself. **The plan commits to stage 0 and nothing after it.** Every later stage's plan is
written when that stage is pulled, with the previous stage's §12 and `05-lessons.md` read first,
and the plan review reads it before its blueprint is cut; do not write stage 2's plan while stage
1 is running.

**What this plan derives from goes in `source.md`**, first: the brief, the material handed over
(a document, a conversation, an image), and what was read from it, numbered so that a requirement
cites its evidence instead of carrying it. It is a record, appended to and never revised, which
is what lets `01-requirements.md` stay the contract.

**Three parts in the two documents the KIT has already half-written.** `02-architecture.md` and
`03-method-and-tooling.md` are each in three parts: *Given* — what adopting the KIT fixed, stated
as references to where the KIT says it and not as questions; *Chosen* — what this project decides
once, in stage 0's plan, and records in the decision log; *Theirs* — the domain. Angle brackets appear
only in the last two. A given part is not edited; changing one leaves the certified pair, and
`bb plan-check` reads nothing in one as an instruction - a blockquote there is the KIT's prose,
kept as written.

**`reviews/` is the home for a review's raw material**: each reader's output from the plan-review
pass, the raw reads of a blueprint, any comparison of readers - everything that is about the
reading rather than the plan. The project plan review's is the build's `reviews/`; a stage's
readings - its stage plan's and its blueprint's - are its folder's `reviews/`, beside the stage's
other documents. The overview's findings table (`00-overview.md` §5) keeps the
findings and their resolutions and nothing else; a comparison of four readers that tripled one
project's overview belonged here. `bb plan-review` writes its findings here too.

**Who fills them: the Architect's session** - the person, or the model they work with in the
session started at the workspace root - in stage 0's plan step, before any loop exists. No dispatched role
ever writes a plan document. **Angle brackets** `<like this>` mark things to replace. Delete
every instructional blockquote once you've acted on it — a document left half-filled reads as a
decision, and an angle bracket left standing reaches whoever reads the plan next as literal text.
`bb plan-check`, run from the workspace folder, holds the plan to both (and to the rules overlay's
placeholders and the given parts), and `start` runs it once per workspace before its first
dispatch; this README, the templates under `stages/` and the readings under a stage's `reviews/` are not checked, everything else under `docs/` is.
