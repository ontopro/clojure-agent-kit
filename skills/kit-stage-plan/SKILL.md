---
name: kit-stage-plan
description: Steps 6 and 7 of the KIT's workflow - a stage's plan, written just-in-time when the stage is pulled, by its kind (stage 0 the spike, stage 1 the walking skeleton, an increment, a pre-release stage, the release), reviewed cold and approved with its cap. Use when the previous stage is closed and the next is pulled, or the person says "plan stage N", "write the stage plan".
---

# The stage plan, written when the stage is pulled

The method is the source: the stage plan's sections, what each kind of stage owes, and the
spike's rule are in `method.md` §04; the templates under `<xyx>-build/docs/stages/` lay them
out. This skill reads the lessons first, then fills the template for the stage's kind.

## When it runs

Workflow step 6 for stage 0 (its plan from the spike template, the first thing stage 0 does
after approval) and step 7 for every stage after, in the Architect's session, once per stage,
before the blueprint is cut.

**And when the stage is amended** - the person asks for more before the stage closes (`method.md` §04). Say
first whether it serves the stage's goal, fits the cap or needs it raised, and comes before the close; if not,
it is deferred, and the scope's deferred list says to which stage. If it is amended: the person's words as a Q
row of `docs/source.md` §3, then `bb stage-amend Q-n` - it opens the pass in the gates record, and is run
before anything is read again, so the readings come after it. A brief for it, if one is written, goes in
`docs/stages/stage-N/notes/stage-N-q<n>-<topic>.md`, never beside the stage plan. Then amend the stage plan (its requirements,
its packets, its cap) and run `bb plan-review` over it again; the person approves again with the cap
(`:approved` in the pass, `:by` their name); amend the blueprint and run `bb blueprint-review` again; the
person signs off again (`:signed`). `bb next` asks for each, with a ready `:at`. A whole new pass for a
change that touched only the blueprint too: the four gates are re-run whatever it touched. No reading
is skipped on a belief about what a command does - that it would overwrite the first record, that it
needs something not there: its doc line (`<kit>/harness/bb.edn`) or its source is read first. A reading
nearly skipped because it "would overwrite the first" appends; every review writes its own record.

## Reads

| File | Heading |
|---|---|
| `method.md` | `The stage plan, written just-in-time` |
| `method.md` | `A report to the person opens with where we are` |
| `method.md` | `Spike gates: how a PROVISIONAL decision gets settled` |
| `method.md` | `Pre-release and release` |
| `method.md` | `What a visitor submits: what a feature that takes it keeps is decided before it is built` |
| `build-docs/05-lessons.md` | `Stage <N>` |
| `build-docs/stages/stage-N-template.md` | `2. Requirements this stage adds` |
| `build-docs/stages/stage-N-template.md` | `11. Exit criteria (the stage's gate), and the cap` |
| `build-docs/stages/stage-0-spike-template.md` | `2. The risks it proves, and the decisions it gates` |
| `build-docs/stages/stage-prerelease-template.md` | `7. Exit criteria (the stage's gate), and the cap` |
| `build-docs/02-architecture.md` | `15. Threat model` |

And the workspace's own files, never the conversation, in this order: `docs/05-lessons.md`
(the previous stage's section, read before anything else), the previous stage plan's §12
(residual risks), `docs/00-overview.md` (the stage map and the risk register as the last
stage's end left them), `docs/04-decision-log.md`, `docs/01-requirements.md`'s scope lists and
index, and `docs/source.md`'s observations.

## Refuses when

- The previous stage's gates record (`docs/stages/stage-<N-1>/stage-<N-1>-gates.edn`) has `:stage/closed`
  nil: its exit criteria are unchecked. Say so and stop; the `kit-stage-end` skill closes a stage.
  Stage 0 has no previous stage; it needs `stage-0-gates.edn` with `:stage/approved` set.
- A stage plan for this stage already exists and is approved: a change to an approved plan is
  an amendment the person approves again, not a rewrite. Say so.
- The person wants requirements for a stage beyond this one: the method's §03 says a
  requirement belongs to the stage plan of the stage that builds it. Keep to this stage.

## Asks

Before anything else, run `bb next --where` from the workspace folder and show its block as printed;
the report to the person opens with it, and every ID the report cites carries the file and section
that define it (`method.md`, `A report to the person opens with where we are`). Then offer the
fuller account, `/kit-where-am-i`, and give it only on the person's yes (`method.md`, `The fuller account
is offered, not given unasked`).

Then, out loud: which lessons of the last stage this plan answers, and how (a rule in the
overlay, a line in this plan, a decision) - the plan review will ask for the one it does not.
Then the template's sections in order for the stage's kind: stage 0 from the spike template
(the risks it proves, each with its pass criterion and fallback; the loop proved by its first
packet; what runs at its end; what is kept); stage 1 from the stage template with breadth written
beside depth in §4; an increment from the stage template; a pre-release stage from its
template (what of release's needs it takes; the deploy path rehearsed on a throwaway host; the
threat model, every line of the architecture's §15 to be answered with its evidence and signed);
the release from its template (the three criteria; the changelog; the rollback). The
requirements this stage adds go in §2, each citing its observation or marked `(inferred)`.
A stage that changes what the application holds or who reaches it - a form, an editor,
accounts - says so, and the architecture's §8 decision and §15's lines are amended with it, and
the routes the security pack tries (`docs/security-routes.edn`) gain the stage's forms and the
routes that need a login.
A stage that adds a feature taking what a visitor submits - search, a contact form, feedback, a
box to be checked - answers the method's questions for it in this plan (what it takes, matching
and order as a contract, the interface, the submission as a request value) and decides what it
keeps, and in what shape, with the person, as a decision in the decision log, before the
blueprint; the method's defaults are a search's words counted by day and never who, and an
attestation kept on purpose as a record with its retention - never in the application's log
(`method.md`, `What a visitor submits: what a feature that takes it keeps is decided before it is
built`).
The exit criteria are things a person can check, "deployed locally" among them from stage 1
on, and the cap is proposed to the person - with the security review in it at the last stage's
figure or more (the readings line of the last stage's `bb stage-report`; the review's cost grows
with the application, and a pre-release stage's has every member read) and one fix packet after it,
since a reproduced finding is fixed in the stage that found it. Then run the stage plan review, `bb plan-review
<xyx>-build/docs/stages/stage-N/stage-N-plan.md`, and read the findings cold with the person, resolving each in
the overview's findings table and the stage plan - a finding of a review that rests on a claim about
what a library does answered after the claim is evaluated on the JVM, in the application's REPL, the
form and its result in the resolution; then `bb plan-check`.

## Writes

`docs/stages/stage-N/stage-N-plan.md` from the template for its kind, every mark replaced and every
instruction deleted, in the stage's folder; `docs/stages/stage-N/stage-N-gates.edn` copied from the gates template; the
overview's stage map with this stage's cap; the review's findings resolved. The plan's last section,
its progress, stays as the template has it: `bb stage-progress <the stage plan>` writes its upper part
from the records once the blueprint is signed, and the lines below its end marker are ticked by hand
as the stage runs. Then say the next
step: the person's approval with the cap written into the gates record, and `bb next`. An
approval names who gave it - the cap line, the status line and the gates record's `:by` - by
the name the brief gives for who signs off, or asked for; never "the person".

## Done when

The stage plan has every section its template has, filled for its kind; `bb plan-review` has
run over it and every finding is resolved; `bb plan-check` passes; the person has been shown
the inferred requirements and has approved the stage with its cap in `stage-N-gates.edn`, `:by`
their name.
