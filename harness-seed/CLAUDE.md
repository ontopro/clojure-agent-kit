@AGENTS.md

The rules for this project are in `AGENTS.md`. Read it.

<!-- This file exists only because Claude Code reads CLAUDE.md and not AGENTS.md.
     The `@AGENTS.md` line above is a Claude Code import, expanded at session start;
     the sentence under it says the same thing in prose, so a client that reads this
     file literally and does not resolve imports still knows where to look. Pi is
     the case that matters: with both files present it loads BOTH and concatenates.

     Nothing generated lives here — `bb rules-sync` writes AGENTS.md and only
     AGENTS.md, and `bb rules-check` drift-checks that one file. A rule written
     into THIS file would reach Claude Code alone, which is precisely the failure
     the rule source exists to prevent.

     What does belong here: anything true of Claude Code and no other client. -->

## Claude Code specifics

**Gate 0 at write time.** `clj-paren-repair-claude-hook` repairs delimiters before a
write reaches disk, at zero tokens, preserving the native diff UI. Wire it in
`.claude/settings.json` as a `PreToolUse`/`PostToolUse` hook on `Write|Edit`.

It is an accelerant, not the mechanism: hooks cover Write/Edit and an agent can still
edit through the shell. `bb repair` is the floor, it runs on every client, and every
client has some version of this capability — Antigravity has `PreToolUse`/`PostToolUse` in
`.agents/hooks.json`, OpenCode a plugin hook, Pi a TypeScript extension.

**The off-loop role.** Copy `agents/interactive-programmer.md` to `.claude/agents/` at
your repository root.
