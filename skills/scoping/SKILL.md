---
name: scoping
description: Step 1 of the KIT's workflow - the scoping conversation, the first in a new workspace. Use when a person has a workspace made by `bb init` and no brief yet, or says "scope", "start a project", "what are we building". Asks the method's six groups of questions, writes the brief to the workspace's `work/brief.md`, and `bb brief` files it.
---

# Scoping - the first conversation in the workspace

The method is the source: this skill asks the questions `method.md` asks, in its order, and
restates none of them. Open the section, then run the conversation from it.

## When it runs

Workflow step 1, the first thing in a workspace made by `bb init` (step 0, setup). Once per
project, in the session the person opens in the workspace folder - the skills are the `claude`
seat's, and `bb init` renders them into the workspace's `.claude/skills/`.

## Reads

| File | Heading |
|---|---|
| `method.md` | `Scope — the scoping conversation, the first in the workspace` |
| `build-docs/source.md` | `1. What was handed over` |

Read the method's section whole before the first question: it has the six groups, the two
rules (answers recorded as given; a question the person cannot answer becomes a risk or an open
decision, never a blocker), and what scoping does not ask.

## Refuses when

- No workspace: there is no `workspace.edn` in the session's folder. Say that setup is step 0 -
  `bb self-check` and `bb init` in KIT's clone, as the README's *How to use KIT* says - and stop.
- `<name>-build/docs/source.md` already carries a brief (§1's first row no longer says
  `<the brief>`): say so and stop. Scoping runs once; a change to the brief is a decision in the
  decision log, not a second scoping.
- The person wants a feature list, a data model or a stack choice from this conversation: the
  method's section says those come out of stage 0 and stage 1. Say so, and keep to the six
  groups.

## Asks

The six groups of the method's section, one group at a time, in its order; within a group, one
question at a time, and the next only after the answer. Record each answer as given, in the
person's words, numbered `1.1`, `1.2`, ... by group and question. When the person cannot answer,
write the question down as a risk (what would prove it, and which stage) or as an open decision
with an owner, and move on. Do not propose answers; do not summarise an answer into something
the person did not say.

## Writes

One file, the brief, at the workspace's `work/brief.md` - a Markdown file with the ask in the
person's words as one page, then the numbered answers under the six group names, then the risks
and open decisions the conversation produced, then the first thing worth seeing, named. Nothing
else is written: no plan document.

Then run `bb brief` from the workspace folder, which files the brief as `source.md`'s §1 row and
Appendix A (the method's section says so); the person commits it in the build repository; then
`bb next`.

## Done when

The brief is filed in `source.md`, the person has read it back and said it is theirs, every one of the six
groups has either answers or a named risk or open decision, and the first thing worth seeing is
named in one sentence.
