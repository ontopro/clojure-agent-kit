---
name: kit-where-am-i
description: Any time, read-only - where the project stands. The phase and the stage with their files, the stage's goal, what is done and what is next, the decisions still open and the risks, and where each kind of ID is defined; then a guided reading into whichever the person picks. Use when the person asks "where are we", "where am I", "what is next", "what is open", "what does FR-8 mean", or opens a session cold.
---

# kit-where-am-i - where the project stands, then the details

`bb next --where` is the source: it reads the build's files and prints one block, and this skill
shows that block as printed, restating nothing, then reads further into the one line the person
picks. Not a step of the workflow; it writes nothing.

## When it runs

At any time, in the Architect's session in the workspace folder: a session opened cold, a person
back after a break, a report that cited something they cannot place.

## Reads

| File | Heading |
|---|---|
| `workflow.md` | `The steps, and what each produces` |
| `method.md` | `01 — The phase map` |
| `method.md` | `A report to the person opens with where we are` |
| `build-docs/00-overview.md` | `4. Stage map` |

The paths are KIT's, under the folder `workspace.edn`'s `:workspace/kit` names. Then the
workspace's own files, each only when the person picks its line: the stage plan, the decision log,
the overview, the file an ID's `read more` entry names.

## Refuses when

Nothing. Outside a workspace the block says setup is next, as `bb next` does; show it and stop.

## Asks

1. Run `bb next --where` from the workspace folder and show its block exactly as printed.
2. Then one question - which line to open:
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
   - **the stage map**: every row, with its kind and status.
3. Answer from the file named, pointing at the heading, every ID cited with the file and section
   that define it; then offer the next level down, or another line.

## Writes

Nothing.

## Done when

The person says they know where the project stands, or names the step to run - the block's `next:`
line.
