# skills/ - the KIT's fifth part

One skill per step of the workflow that is a conversation with a person and has a defined
output: `scoping` (step 0), `plan` (step 2), `stage-plan` (steps 6 and 7), `stage-end` (step
14). The loop's steps run without a person and stay commands. [`workflow.md`](../workflow.md)
has the steps; [`method.md`](../method.md) has the substance.

**The method is the source.** A skill is a thin layer that names its method section, asks its
questions and writes to the template; the questions, the sections and the rules are written
once, in `method.md` and `plan-template/`. A skill that restates its section is drift. Each
skill's *Reads* table cites the headings it reads, and `bb skills-sync --check` in the harness's
gates fails when a cited heading is no longer in the file - so the method can be renamed, but
not out from under a skill.

**One anatomy, in this order:** *when it runs* (the step); *reads* (the files, and the method
section it cites); *refuses when* (by name, before anything is spent); *asks* (the
conversation, from the method); *writes* (the documents, to the template); *done when*. A
skill reads its inputs from files, never from the conversation, so it can be started in a
fresh session; `stage-end` writes enough for `stage-plan` to start cold.

**Skills are the `claude` seat's.** They are Claude Code's mechanism, loaded from
`.claude/skills/<name>/SKILL.md`. The other four seats follow the same method section by hand;
`portability.md` says so where the seats are listed. The skills are for the Architect's session,
never for a dispatched role: the generated rule mirror is the roles' and stays separate.

**Where they are loaded from.** Two places, both renderings of this folder, held to it by
`bb skills-sync --check`:

- the KIT's clone's own `.claude/skills/`, committed, so that a session opened in the clone has
  `scoping` before any workspace exists - step 0 comes before step 1;
- a workspace's `.claude/skills/`, written by `bb init` and updated by `bb skills-sync` after a
  `git pull`; each copy says it is generated and from which KIT commit, and `bb doctor` in the
  workspace reports a copy that no longer matches.

A project may add a skill of its own under another name beside these; the sync leaves it alone.

**The split with the harness.** The skill is the conversation; a `bb` task is the record it
writes or reads: `scoping` ends in `bb init --brief`, `plan` and `stage-plan` in `bb
plan-review` and `bb plan-check`, `stage-end` in the browser pack and the records. The hand-offs
between steps are not inside the skills: `bb next` says what comes next, and a skill ends by
saying to run it.
