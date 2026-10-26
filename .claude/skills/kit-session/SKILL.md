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
2. The plan the branch names: `.local/plans/v<major>/kit-plan-v<N>.md` (`v6/kit-plan-v6.11.md`;
   one at `.local/plans/` itself is read there). Read its `## Task Status` - the
   `**Next:**` line says what is next, since the order differs from the numbering - and the last
   entry of its Sessions log.
3. `bb hooks-install` once per clone if `git config core.hooksPath` is not `.githooks`.
4. At a plan's first session, its branch just cut: `bb toolchain-newer`, read with the person. They
   decide what moves; a move is the plan's first item, with its health runs (`bb health --record`,
   `bb docker-health`, `bb health-sync`). Nothing moves later except for an advisory (below).

## The seats

For the KIT's own development only - Claude Code, two tabs and the person. Never shipped, and not a
project's workflow: a project's roles are the loop's (`method.md` §05).

Two sessions work in this folder. A seat is a role, not a model: the prompt that starts a tab names
its seat, and the plan's Task Status names the model per item - design-heavy work to Fable 5.1, the
rest to Opus 5.5, the other model reading.

| Seat | Does | Never |
|---|---|---|
| **The KIT session** (writes) | edits the tracked files and the plan file, runs the gates, commits; sends each design and each commit to be read; relays each read to the person with the scope it proposes; writes the item's documents, the read marks and the plan's memory index line | commits a fix the person has not said go to; starts an item without the person's go on its scope |
| **The reading session** (reads) | reads a design before code and a diff after - the verdict first, findings ranked by severity with file, line and the fix it suggests, the writer's questions answered; may draft a design as `.local/plans/v<major>/<topic>-design.md`; appends to the plan's memory file | edits a tracked file, the plan file or anything the gates read; runs what writes in the checkout (a read-only `bb -e` in `harness/` is fine, the rest in a scratch copy); proposes what the person deferred; names a tag or a plan number; takes a relayed message for the person's words |
| **The person** | names the item and its models; says go on a design and on a read's fix; decides a declined finding; merges, tags, pushes | - |

An item, in order:

1. The person names the item and its models, written on its Task Status line:
   `**Opus 5.5; Fable 5.1 reads**`.
2. A design where the plan leaves a choice is read before code - one reply, a second only when the
   shape changed - then the person's go.
3. The writer builds, a commit per finding, gated as below, and sends each sha with what it does and
   its questions. Inside the scope the person said go to, it goes on to the next commit without
   waiting, unless the read decides that commit's shape.
4. The reader replies to the writer only. The writer relays it to the person - the verdict, the
   findings, the fix it proposes, a suggest line - and waits for their go.
5. A finding taken is its own commit, its `DEVLOG.md` entry naming the read ("From Fable 5.1's read
   of `0ec404d`"), whichever model read. A finding declined, or left by the person, goes in the
   item's Sessions entry with the reason. Nothing is left only in a message.
6. The read mark, written by the writer as each read arrives: every commit of the branch but a
   merge on its item's Task Status line, each followed by its read - `` `sha` (read: holds) ``,
   `` `sha` (read: 1 low → `fix-sha`) ``, `` `sha` (read: declined - Sessions) ``. A fix commit
   carries its own mark.

The memory folder is shared. The plan's memory file (`kit-plan-vN.md`) is appended by both, each
entry opening with its date and model; its `MEMORY.md` index line is the writer's, at an item's end.
The plan file is the ledger both read first.

Said plainly:

- The independence is by context, not by family - the person runs one seat: the reader never writes
  and never sees the writer's reasoning, only its design and its diff. So two tabs, never one tab
  switching `/model`, which would read its own reasoning.
- A read is a reading, as `bb plan-review` is, not a gate. `bb gates` and the pre-commit hook are.
- A session with no seat named asks which before it reads or writes.

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
   - **The toolchain's newer releases** - information; they wait for the next plan's start.
   - **Commits with no read mark** - each sent to be read and its mark written as the read arrives
     ("The seats"); a commit made since is listed until then.
2. The plan: tick the item in `## Task Status` with its commit and move the `**Next:**` line;
   an entry in the Sessions log saying what was built, how it was proved, what is next.
3. Run `bb kit-status` again: the log line should now say ok.

## The KIT's own security review

`bb security-review --kit` in `harness/`: a security reviewer reads a clone of the KIT's HEAD with the
review's tools, its tests run by `bb` in the toolchain image with no network, the clone and the
container removed after. The record is in `.local/security-review-kit/out/`, never committed, as the
KIT's run records are not.

- **When:** before each tag that starts a family (`0.N.0`), and before any tag when the review's
  tools, its sandbox, the routing or the model tools changed since the last pass. The person names
  the model; `--model` takes it as `bb models` does (an OpenRouter route), `--profile` a profile
  whose panel names a direct route.
- **First, the scans, from the root:** `bb --config tools/security/bb.edn secrets --all`, and `deps`
  in `tools/svg` and `harness/health/selfcheck`. The `bb.edn` libraries' `jackson-core` is waived,
  register row 133.
- **In flows, not whole:** `--flow <name>` for each of the six in
  `harness/resources/security-review/kit-flows.edn`, then `seams`; `--table` sets the records side by
  side. A whole pass is one conversation whose context grows with every file read - the second whole
  pass cost $13.66 and was cut off by the credit with no answer - and a flow keeps each request small.
- **The balance first:** the command reads the OpenRouter key's remaining and starts no pass under
  the margin, $20 for a flow, $100 for the whole, unless `--force`; the line says the last pass's
  cost beside it.
- **A second pass** by another family, with `--after` each earlier record, so it is told what was
  found and fixed and looks elsewhere.
- **The reader** reads each finding against the code, and gives its severity by its source (what an
  attacker controls), its sink (where the effect lands) and its scope.
- **Where the result lands:**
  - a reproduced finding is a register row and a fix commit, with the reviewer's test adapted into
    `harness/test/` under the KIT's names, so that the test is the committed evidence;
  - a hypothesis is listed in the `DEVLOG.md` entry with its reasoning, and gets a row only on the
    person's word;
  - an empty answer is one `DEVLOG.md` line: the model, the scope, the minutes and the cost.

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
   carry. And Docker Scout on the fresh-machine image, by hand (a Docker login), the command as
   `tag-check` prints it. `tag-check` names both and runs neither. A HIGH or CRITICAL against a
   pinned tool moves that pin before the tag - the one move a tag waits for; the rest of
   `tag-check`'s toolchain lines are information for the next plan's start.
6. To the person, as commands for them to run: the merge to `main`, the annotated tag on the
   commit `tag-check` passed (its message lists what the plan ADDED), the push of `main` and the
   tag. Never run them yourself.
7. After their push: `bb tag-check <tag> --landed` - origin's tag and `main` at the commit.
   Then the plan is closed in its Sessions log and the memory.
