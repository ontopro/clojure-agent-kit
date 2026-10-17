---
name: kit-where-am-i
description: Any time - where the project stands. The block first; then, offered, the fuller account - the whole road, what each stage has cost and taken, what waits on the person, what changed since they last looked, what is open - and the roadmap page, the road drawn; then a guided reading into whichever line the person picks. Use when the person asks "where are we", "where am I", "what is next", "what is open", "what does FR-8 mean", or opens a session cold.
---

# kit-where-am-i - where the project stands, then the details

`bb next --where` is the source: it reads the build's files and prints one block, and this skill
shows that block as printed, restating nothing. The fuller account is the same source at length,
`bb next --where --full`, rendered by code so its figures are the files'; the roadmap page draws it.
Not a step of the workflow. It writes only what the person's look leaves: the page, when they ask
for it, and the date of their look, when they say they know where they stand.

## When it runs

At any time, in the Architect's session in the workspace folder: a session opened cold, a person
back after a break, a report that cited something they cannot place.

## Reads

| File | Heading |
|---|---|
| `workflow.md` | `The steps, and what each produces` |
| `method.md` | `01 — The phase map` |
| `method.md` | `A report to the person opens with where we are` |
| `method.md` | `The fuller account is offered, not given unasked` |
| `build-docs/00-overview.md` | `4. Stage map` |

The paths are KIT's, under the folder `workspace.edn`'s `:workspace/kit` names. Then the
workspace's own files, each only when the person picks its line: the stage plan, the decision log,
the overview, the file an ID's `read more` entry names.

## Refuses when

Nothing. Outside a workspace the block says setup is next, as `bb next` does; show it and stop.

## Asks

1. Run `bb next --where` from the workspace folder and show its block exactly as printed.
2. Offer the fuller account: "The fuller account - the whole road, what it has cost, what waits on
   you, what changed since you last looked - or a line of the block to read into?" On yes, run
   `bb next --where --full` and show it exactly as printed; restate nothing, add no figure.
3. Then one question - which line to open:
   - **the stage's goal**: its plan's §1 read, and its exit-criteria section - the heading that
     starts `Exit criteria`, whose number differs by the stage's kind - each criterion with
     whether it is met yet, as the plan says;
   - **what is done**: each closed stage's gates record, and the current stage's gates passed;
   - **what is next**: the step's row in `workflow.md`'s table - who acts, the question it answers,
     what exists afterwards - and the `run:` line to type;
   - **what is open**: each decision still PROVISIONAL or OPEN, read from its row in the decision log
     with the spike that gates it or the stage that owns it, and each risk OPEN, from the
     overview's §4a with the stage it runs in;
   - **an ID**: the file and section the `read more` lines name for its kind, and the row or
     point that defines it - never a file that only cites it;
   - **a phase, a step or the project plan**: the heading the `read more` lines name - the phase
     map in the KIT's `method.md`, the step's row in its `workflow.md`, the overview's section that
     lists the plan's documents;
   - **the stage map**: every row, with its kind and status;
   - **the roadmap page**: run `bb roadmap-page`, say the path it printed, and offer to open it
     (`open work/where/roadmap.html`).
4. Answer from the file named, pointing at the heading, every ID cited with the file and section
   that define it; then offer the next level down, or another line.

## Writes

Through the KIT's commands only, never by hand: `work/where/roadmap.svg` and `roadmap.html` when
the person picks the roadmap page (`bb roadmap-page`); `work/where/last-look.edn` when the person
says they know where the project stands (`bb last-look --by <their name>`, the name their sign-offs
carry) - say that it was recorded, and that "since you last looked" counts from it. Nothing is
committed.

## Done when

The person says they know where the project stands - the look recorded by `bb last-look` - or
names the step to run, the block's `next:` line.
