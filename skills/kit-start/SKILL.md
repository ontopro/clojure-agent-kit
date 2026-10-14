---
name: kit-start
description: The first session in a KIT workspace - an orientation before step 1. Walks the person through the workspace's folders, KIT's approach, the steps in order, and the roles and the models they run on, with the model and effort recommended for this session; then hands over to `scoping`, or to `bb next` once a brief is filed. Use when the person types `/kit-start`, opens a first session in a workspace made by `bb init`, or asks "where do I start", "what is this", "walk me through KIT".
---

# kit-start - the orientation, in the workspace

KIT's documents are the source: this skill reads them and walks the person through them in
order, a part at a time, restating nothing it can point at. It is not a step of the workflow and
writes nothing; it ends where step 1, scoping, begins.

## When it runs

The first session in a workspace made by `bb init` - the workspace's `CLAUDE.md` routes a first
message here while no brief is filed - and whenever the person asks for the orientation again.
In the session the person opens in the workspace folder.

## Reads

| File | Heading |
|---|---|
| `README.md` | `How to use KIT` |
| `method.md` | `Clojure Agent Kit (aka "KIT")` |
| `workflow.md` | `The steps, and what each produces` |
| `harness/roster.md` | `The roster — who acts in a build, from which role, on which model` |

The paths are KIT's, under the folder `workspace.edn`'s `:workspace/kit` names (`KIT/` when the
README's steps were followed). Read too, in the workspace: `workspace.edn` and `README.md` for
the folders, and the build's `docs/source.md` §1 for whether a brief is filed.

## Refuses when

- No workspace: there is no `workspace.edn` in the session's folder. Say that setup is step 0 -
  `bb self-check` and `bb init` in KIT's clone, as the README's *How to use KIT* says - and stop.

## Asks

One part at a time, and the next only when the person is ready; a question answered from the
document it cites, by section, not from memory. Every part ends the same way: the four parts as a
list, each DONE or NEXT, then one instruction - nothing about scoping until the handover, and
`bb next` not run before it (its "next" is the project's step, not the orientation's part):

```
1 of 4: the workspace - DONE
2 of 4: the approach - NEXT
3 of 4: the steps in order
4 of 4: the roles and their models

Type next, or ask a question about the workspace.
```

1. **The workspace.** Each folder and what it is for - KIT's clone (never written to; `git pull`
   upgrades it), the application, the build (the plan, the settings, the records), `work/`,
   `workspace.edn`, and `bb.edn`: every command is run from this folder.
2. **The approach.** `method.md` from its opening to the "Distilled from…" callout: the stages
   and their kinds, the four steps every stage has, the plan thin at the start and revised at
   every stage's end. The phases as a table with a Status column, filled from the workspace's files
   as `bb next` reads them: A · SETUP Done (a workspace exists); B · PROVE In progress until stage
   0's gates record says closed, then C · BUILD; the others Later. In the person's terms; the
   section is quoted where it is clearer.
3. **The steps in order.** `workflow.md`'s table of steps - setup done, scoping next, then the
   plan, its review and check, the approval, stage 0, the stages - and that after the orientation
   `bb next` says which one is next. Named, not run: its output belongs to the handover.
4. **The roles and their models.** From the roster: who acts at each kind of act - this session,
   the Architect's; the dispatched roles; the readings; the person - and the model each runs on,
   one line each; `harness/roster.md` for when and why. Then this session: the roster recommends
   **Opus 5.5, effort high** for it, because its acts become the contract every dispatched role
   follows; in Claude Code, `/model` and `/effort` set it. The person decides.

## Writes

Nothing. After part 4, the list with all four DONE, then the one next step: no brief filed (§1's
first row still says `<the brief>`) - "Type scoping to begin step 1, the brief", and hand over to
`scoping`; a brief filed - the project is under way, and `bb next` names the next step.

## Done when

The person has been through the four parts, has chosen the session's model and effort, and
scoping has begun - or `bb next` has been named.
