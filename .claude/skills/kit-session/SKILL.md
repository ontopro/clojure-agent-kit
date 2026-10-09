---
name: kit-session
description: The KIT session's own routine for developing THIS repository (never shipped to a workspace) - where to start, how an item is committed, what an item's end looks at (`bb kit-status`), and the pre-tag order (`bb tag-check <tag> --run`, the documents, the person's merge, tag and push, `--landed`). Use at the start of a KIT session, when an item of the plan is done, or when the person says "item's end", "tag", "prepare the tag", "cut 0.x.y".
---

# The KIT session

The working rules are the root `CLAUDE.md`; this skill is the order they are applied in, with the
commands that check each step. Nothing here is shipped: `bb init` copies `skills/`, never
`.claude/skills/`, and the tasks it names are the root `bb.edn`'s, for this clone only.

## At the start

1. `git status`, `git log -1`, the branch. Work happens on the plan's branch (`plan-vN`), cut once
   from the last tag; never a branch per item.
2. The plan the branch names: `.local/plans/kit-plan-v<N>.md`. Read its `## Task Status` - the
   `**Next:**` line says what is next, since the order differs from the numbering - and the last
   entry of its Sessions log.
3. `bb hooks-install` once per clone if `git config core.hooksPath` is not `.githooks`.

## An item

- Design shown to the person where the plan leaves a choice; built without re-showing where it
  does not.
- Before committing: `bb repair && bb gates` in `harness/`, to a log, and read its exit -
  `(bb repair && bb gates) > ../.local/gates/<item>.log 2>&1; echo exit=$?`.
- The gates check the WHOLE working tree, untracked files included, and the pre-commit hook
  refuses a commit whose tree is not the gated one. So `git add -A` first, gate, commit. Work not
  being committed is set aside before the gates, not after.
- The document in the same commit: `DEVLOG.md` for what changed and why, `NOTES.md`'s register
  for what stays open (and its `**Updated` stamp moved). The hook refuses code without one, an
  unmoved stamp, and a name from `.local/wording/names.txt`.
- One finding per commit unless the person says otherwise. Never `--no-verify`: fix the cause.

## An item's end

1. `bb kit-status`, and act on each list:
   - **Health records stale** - expected while a plan changes harness code; they are renewed
     before the tag, not after every item.
   - **Flaky tests** - a row in `NOTES.md`'s register, or an existing row extended.
   - **Rows opened this plan still open or on watch** - each one decided with the person or
     left on purpose, said so.
   - **The Sessions log behind** - write the item's entry.
   - **A `.local/` folder or entry its README does not name** - name it.
2. The plan: tick the item in `## Task Status` with its commit and move the `**Next:**` line;
   an entry in the Sessions log saying what was built, how it was proved, what is next.
3. Run `bb kit-status` again: the log line should now say ok.

## Before a tag

The checklist at the head of `DEVLOG.md`, with `bb tag-check` as its mechanism. In order:

1. The plan's work committed, `bb kit-status` read, the tree clean.
2. `bb tag-check <tag> --run` - renews the stale health records (`bb health --record`,
   `bb docker-health`, `bb health-sync`) and stops: the containers clone HEAD, so the records
   must be committed first.
3. The tag's documents: its heading in `DEVLOG.md` (`## <tag> — <what the plan was>`, with the
   one line a workspace made before it needs) and the root README's sentence naming the current
   tag. Commit them with the records and the README block, through the gates as always.
4. `bb tag-check <tag> --run` again - the gates and `bb docker-gates` on that commit - then
   `bb tag-check <tag>`: every line `ok`, or no tag.
5. The person's bear-claw secrets scan, by hand: a second rule set the KIT's scan does not
   carry. `tag-check` names it and never runs it.
6. To the person, as commands for them to run: the merge to `main`, the annotated tag on the
   commit `tag-check` passed (its message lists what the plan ADDED), the push of `main` and the
   tag. Never run them yourself.
7. After their push: `bb tag-check <tag> --landed` - origin's tag and `main` at the commit.
   Then the plan is closed in its Sessions log and the memory.
