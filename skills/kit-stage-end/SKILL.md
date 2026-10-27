---
name: kit-stage-end
description: Step 14 of the KIT's workflow - a stage's end, once its packets are merged. Deploy locally, run the browser pack, walk the owner through the result, check the exit criteria, write the lessons, revise the scope lists and the stage map, close the stage, pull the next. Use when the last packet of a stage is merged or the person says "close the stage", "end stage N", "the stage is done".
---

# The stage's end

The method is the source: what a stage's end checks and writes is in `method.md` §04 - the
exit criteria, the browser checks, the owner's walk, the decision log's updates - and the
lessons document and the scope lists say where the writing goes. This skill runs the checks,
prompts the walk, and writes the records so that the next stage can be planned in a fresh
session from files alone.

## When it runs

Workflow step 14, once per stage, in the Architect's session, after the stage's last packet is
merged and before the next stage is pulled. Stage 0's end answers its pass criteria instead of
exit criteria and says what is kept.

## Reads

| File | Heading |
|---|---|
| `method.md` | `Exit criteria, and what happens when a gate fails` |
| `method.md` | `A report to the person opens with where we are` |
| `method.md` | `Scope is three lists, revised at every stage's end` |
| `method.md` | `Rank risks, then order the stages by them` |
| `method.md` | `Security is a decision in stage 0 and a signature before release, not a gate` |
| `build-docs/05-lessons.md` | `Stage <N>` |
| `build-docs/01-requirements.md` | `6b. The index — every requirement, by the stage that wrote it` |
| `build-docs/01-requirements.md` | `10. Scope — three lists, revised at every stage's end` |
| `build-docs/stages/stage-N-template.md` | `11. Exit criteria (the stage's gate), and the cap` |
| `build-docs/stages/stage-N-template.md` | `12. Residual risks / feeds into the next stage` |
| `build-docs/02-architecture.md` | `15. Threat model` |
| `tools/browser/README.md` | `Three things to know` |
| `tools/security/README.md` | `The routes file` |
| `tools/security/README.md` | `The two scans` |

And the workspace's own files, never the conversation: this stage's plan (its exit criteria and residual risks: §11 and §12 in an
increment's, §7 and §8 in the spike's and a pre-release stage's, §6 and §7 in the release's), its
gates record, the run records under `<xyx>-build/runs/` for the stage, `docs/00-overview.md`,
`docs/01-requirements.md`, `docs/04-decision-log.md`, `docs/05-lessons.md`.

## Refuses when

- A run of this stage is not merged (`bb next` or the records say so): the stage's end is after
  the last merge. Say which run and stop.
- The gates record's `:blueprint/signed` is nil: the stage was never signed off; something is
  out of order. Say so and stop.
- The application does not start from a clean checkout (`bb serve` from a fresh clone of the
  merged tip does not answer on its port): "deployed locally" is the first exit criterion
  from stage 1 on, and nothing below it is checked until it holds. Say what failed.

## Asks

Before anything else, run `bb next --where` from the workspace folder and show its block as printed;
the report to the person opens with it, and every ID the report cites carries the file and section
that define it (`method.md`, `A report to the person opens with where we are`). Then offer the
fuller account, `/kit-where-am-i`, and give it only on the person's yes (`method.md`, `The fuller account
is offered, not given unasked`).

Then the exit criteria of the stage plan - the section whose heading starts `Exit criteria` - one at a time, each with its evidence: the command
and its result, the browser pack's screenshots (run `bb --config <kit>/tools/browser/bb.edn
check` against the local server, and copy what a criterion cites into
`docs/stages/stage-N/stage-N-browser/`; the README's three things say what it does and does not
prove), the security pack's table (run `bb --config <kit>/tools/security/bb.edn check --routes
docs/security-routes.edn --record docs/stages/stage-N/stage-N-security.edn` from the application's
folder, the routes file where the project has one; a pre-release stage runs it against the
rehearsed host with `--running --base <its url>`, so TLS is tried), and its two scans from the
same folder - `deps --routes docs/security-routes.edn --record
docs/stages/stage-N/stage-N-security-deps.edn` and `secrets --from stage-<N-1> --routes
docs/security-routes.edn --record docs/stages/stage-N/stage-N-security-secrets.edn` (`--all` at the first
stage's end, which has no tag before it) - no failing row in any of the three and every warn
read with the person; an advisory accepted or a hit allowed goes in the routes file with its
reason, never around it, and a secret found is rotated, not only removed. Then the security reviewer panel - the
profile's `:security-panel`, or its one `:security-reviewer`. First, from the workspace folder, `bb security-panel
--history` (who read which stage, what each reproduced, what it cost) and, in the application, `git diff --stat
stage-<N-1>..HEAD` (what this stage changed: code or content, and which of requests, forms, stored data and logins).
Then one offer, the option you recommend from those two named and why, in these words:

```
Who reads this stage's security review?
1. the next member in rotation (<the name bb security-panel would print>)
2. another member (<the eligible names>)
3. two of them
4. all of them
5. skip the audit
```

A member of the Coder's family is left out of every option and said. Each is a real model call per member (on
the KIT's small fixture a reading cost $0.11 to $0.47 with Gemini 4 Argon and $1.19 to $1.35 with GPT-6 Astra, more
for a larger application). On 1 to 4: on a fresh clone of the application's merged tip, `bb security-panel <clone>
[--members <names> | --all] --stance tracer --base stage-<N-1> --out
<xyx>-build/docs/stages/stage-N/stage-N-security-review` (no flag for the next in rotation; the application's first
commit for `--base` at the first stage's end), then `bb security-route
<xyx>-build/docs/stages/stage-N/stage-N-security-review/security-panel.edn <clone> --out` that folder, which asks
triage about each row. On 5: `:security/skipped {:at :by :reason}` in the gates record, the person's name and
their reason in a sentence - nothing runs. A stage amended after its review read or its skip was decided
(`bb stage-progress` says "before the amendment Q-n") is read again - the next member in rotation, its own
record - or the skip is decided again over what the stage now changes. A pre-release stage offers 1 to 4 with all of them the default and
never 5; say whether fewer would do, from the history and what changed since each member last read, and the person
chooses. A member that declined or left no answer is said as that and is not a clean review; a finding counts by
its own failing test, never by how many members found it; a member that read twice has a column per reading. Read `security-panel.md` and the routing with the
person, and put one decision on each row to them - fix and retest, the Architect, or their own review - triage's
route being its proposal: a `coder` route's draft in the run's `security-fixes-<stamp>.md` is completed into this stage's Blueprint as a packet,
signed and run, and the stage does not close until the security reviewer's test kept beside it passes on the merged
tip; an `architect` route is a change to the design - `docs/02-architecture.md`, the Blueprint, a decision - and
then a packet, or a risk accepted in its words; a `human` route is a line of §15 the person accepts or declines; and
every hypothesis is read and written into §15, answered or open with the stage that answers it. Then the serve command for "deployed locally". Then the owner's walk, page by page or
function by function: ask the person for every page or function the stage made what is wrong,
record each thing said as one line, and settle each now - fixed before the stage closes, or
moved to the deferred list with the stage that takes it; an open fault carried to the next stage
has been read in the code first, and the reading is beside it on the list - a deferral says what is
known. Then the security deferrals: `bb plan-check` lists each choice of `docs/02-architecture.md` §8
put off, with the stage that owns it. For each, what this stage added against it, counted in the
stage's diff by what its line says grows - for the content-security policy, the lines adding an
Alpine attribute, `git diff stage-<N-1>..HEAD -- src/ | grep -c '^+.*:x-'` from the application's
folder (at the first stage's end, which has no tag before it, the count in the tree) - and the
person keeps it, its line revised to today's growth and cost, or pulls it into the next stage plan;
a deferral this stage owns is decided now, its row the policy and its decision in the log. For a pre-release stage, then the
threat model: every line of `docs/02-architecture.md` §15 read with the person, each with its
answer and the evidence for it, a line that cannot be answered this release a decision that
says so; the person signs it. For any stage that changed what the application holds or who
reaches it, §15's lines revised with it. Then the lessons: what this stage
taught, each ending as a rule in the overlay, a line in the next stage plan, or a decision,
and which; a lesson that ends as none is a remark for §12. Then the decisions this stage
exercised: each provisional entry confirmed or reversed with the evidence; stage 0's pass
criteria answered, pass or fallback. Then the scope lists against the source (`bb plan-check`
prints the observations nothing cites) and the stage map re-ranked by what remains; the next
stage pulled by kind - an increment, a pre-release stage, or the release - and, at the first
boundary where "could we ship after this one?" is yes, the MVP named. Finally the cap: what
the stage cost against what was approved: run `bb stage-report <xyx>-build/docs/stages/stage-N/stage-N-plan.md`, which renders the
stage's figures into the exit-criteria section from the run records and its security checks and scans from the pack's records,
and read it with the person; the lessons section's
cost and runs are its first line. Then the plan's progress section: `bb stage-progress
<xyx>-build/docs/stages/stage-N/stage-N-plan.md` writes its upper part from the records again - the
packets, the security pack and scans, the security review or its skip, the stage closed - and the
lines below its end marker are ticked with the person, each with its date and the commit or note
that shows it.

## Writes

In this order, each to its template's place: the stage plan's exit-criteria section with each
criterion checked and its evidence, and the residual risks in the section after it; the walk's record, one line per thing said and
its resolution, under the stage plan; `docs/05-lessons.md`'s section for the stage, closed with
its date, cost and runs; `docs/04-decision-log.md` with the stage's entries settled and the
walk's decisions added; `docs/02-architecture.md` §8 with each deferral's line revised, or the choice
made; `docs/01-requirements.md` with the scope lists revised and the index
appended; `docs/00-overview.md` with the stage closed in the stage map, the risk register and
the map re-ranked, the next stage named with its cap proposed; for a pre-release stage, the
gates record's `:security/signed` with the time (`:at`, as `bb next` gives it) and the signer's name (`:by`, never "the
person"), written by the person, before its
`:stage/closed` with the time, written by the person; the tag `stage-N` on the application's
merged tip. Not this skill's: the run records and `RUNS.md` (`record` writes them per run) and
the screenshots (the work folder, uncommitted); the security review's folder
`docs/stages/stage-N/stage-N-security-review/` - its record, the tests it wrote, a `security-routing-<stamp>.edn`
and any `security-fixes-<stamp>.md` per routing run, never one over another - is the commands', committed with the stage; the security pack's records
`docs/stages/stage-N/stage-N-security.edn`, `-security-deps.edn` and `-security-secrets.edn` are the
pack's, committed with the stage. Then say the next step: `bb next`, and "Type `/kit-stage-plan`
to plan stage N+1" for the stage pulled, in a fresh session if the person likes.

## Done when

Every exit criterion has its evidence written beside it; the security pack's three
records are beside the stage plan with no failing row; every reproduced finding of the security review is
fixed with its test passing, or decided in the design or in §15, and every hypothesis is a line of §15; the walk's record has no line
without a resolution; the lessons section is closed with its date, cost and runs; the decision
log has no provisional entry this stage gated; a pre-release stage's gates record says
`:security/signed`; the gates record says `:stage/closed`; `bb gates`, run from the workspace folder, is green; the tag
is on the merged tip; and the next stage is named in the stage map with a kind and a proposed
cap.
