# clojure-agent-kit

**This file is the working rules for an agent changing this repository.** Not what the
kit is (`README.md`), not the method it teaches (`method.md`), not its current
limitations (`NOTES.md`) — those are linked where you need them.

A reusable build method for developing Clojure software with a small team of AI agents:
`method.md` (the method), `plan-template/` (the plan template), `skills/` (the conversational steps), `harness/` (runnable
code). Public, MIT, at `github.com/ontopro/clojure-agent-kit`.

## You are working ON this repo, not through its loop

That distinction is load-bearing here, because this repo contains a worked example of
agent rules and those rules are not yours.

**`harness/AGENTS.md` is a product artifact, not instructions for you.** It is
generated from `harness/resources/agent-rules.edn` and shipped as the demonstration of
the rule-source pattern. It addresses an agent dispatched with a task packet, inside a loop,
under a harness that runs the gates for it. **None of that is running when you work here.**
Three of its rules invert:

| Its rule | Reads as | For you |
|---|---|---|
| *Do not run the gates* | the harness runs them after you finish | **Run them.** Nothing else will |
| *Stay inside your packet* | write only `:files/target` | There is no packet |
| *No REPL, no edits* | a dispatched nREPL died mid-task | There is no dispatched nREPL |

`harness/CLAUDE.md` is a hand-written stub that imports `AGENTS.md` for Claude Code's
benefit. It is also a product artifact, and also not addressed to you.

`harness/agents/interactive-programmer.md` is the role you are actually playing.

## Working rules

- **Run `bb repair && bb gates` in `harness/` before committing.** `repair` is gate 0
  over the Clojure files you changed; `gates` is doctor → format → lint → the harness's own boundary gate → rules → skills → inventory → health block → the records → test. Fast,
  and the only thing checking this repo — there is no CI. **A commit holds you to it:** run
  `bb hooks-install` once per clone, and the pre-commit hook (`bb commit-check`) refuses a
  commit unless the last `bb gates` was green on exactly the tree being committed (its record,
  `.local/gates/last.edn`, names the tree). The tree gated is the whole working tree, untracked
  files included, so a commit is `git add -A` of what was gated; work not being committed is set
  aside before the gates run, not after. Never `--no-verify` to get past it; fix the cause.
- **Before a tag, the checklist at the head of `DEVLOG.md`**, in its order: the health records on
  the committed tree, the tag's documents, then the gates and `bb docker-gates` on the commit the
  tag will name.
- **A finding is not finished until a document carries it.** Anything still open goes in
  `NOTES.md`'s register; what changed and why goes in `DEVLOG.md`; a health run that
  certifies a machine goes in `harness/health/records/` by `bb health --record`. Write it up in
  the commit that closes it, not later. This rule started as *a RUN is not
  finished* — and within the hour two findings that were not runs went into a
  commit message and nowhere else, which is the exact failure it was added to
  stop. A commit message is not a document; nobody greps for a fact they do not
  know exists. The commit check holds the part it can see: code staged under `harness/src/`,
  `tools/`, `skills/` or `plan-template/` without `DEVLOG.md` or `NOTES.md` is refused, and a
  staged `NOTES.md` whose `**Updated <date> <time>` stamp was not moved past HEAD's, to no later
  than the clock, is refused.
- **A number or a capability claim names the command that produced it, and the command
  has been run.** Not "this is checkable" — the check, executed, over every case the
  sentence covers rather than the one example in front of you. Every false claim this
  repository has carried was a true example holding up an over-general sentence: a
  re-render command that worked on the run it named and threw on two others, a byte count
  read off `du` block size, a docstring saying the REPL tool was not a shell while a model
  ran `sh` through it. `bb health-sync --check` enforces this for the one claim backed by
  committed data, the README's health block. Everywhere else it is a habit, and the cheap version is to state the
  check before the claim and notice when you cannot.
- **Never hand-edit `harness/AGENTS.md`'s marker block.** It is generated. Edit
  `resources/agent-rules.edn` and run `bb rules-sync`; `bb gates` fails on drift. Same for
  any rule text — the source is the only place a rule may be written.
- **The KIT publishes no run records of its own; a project's are the project's.** The runs
  made while the KIT was developed, and the projects built to try it, are not in this
  repository, and no document here cites one by name or path: a lesson is written in the
  KIT's own words ("the first project built with the KIT"). The mechanism stays: the driver's `record` writes `run.edn`, `bb
  report` renders it, and `bb report-check` holds a document that publishes such tables to its
  records - for a project, `<name>-plan/runs/` and `<name>-plan/RUNS.md`, which `workspace.edn`
  names and `record` copies into, so the check runs with no arguments from the workspace's
  clone (`bb gates` there runs it; here, in no workspace, it has nothing to check). A run's
  own directory — `spec.edn`, `loop.edn`, `state.edn`, packets, full transcripts — is
  per-run and stays in the gitignored `.local/runs/` (or a workspace's `work/runs/`).
  The KIT's own committed evidence is `harness/health/records/`, rendered into the README
  and drift-gated. A published number nobody can re-derive is an unverifiable claim, which
  is the one thing this repo is most against. The commit check refuses an added line carrying a
  name from `.local/wording/names.txt` (gitignored; whole word, any case) - add a project's names
  there when it starts.
- **`LICENSE` must stay the canonical MIT text and nothing else.** Third-party notices live
  in `NOTICE`. Appending them to `LICENSE` made GitHub classify the repo "Other" and cost
  the licence badge (commit `fb5e3b0`).
- **The harness's own gates are JVM-free on purpose** — `harness/` has no `deps.edn` and no
  `.mise.toml`, and `bb gates` runs the doctor on the gates tier only. A JDK is needed for
  `bb init`, `bb health` and a loop, never for checking this repository; a pin the harness does
  not have would fail `bb doctor` on an unrelated machine. `bb health` is never in `bb gates`.
- **`PROVENANCE.md` divergences are fixes, not drift.** This is a fork of a private harness,
  not a mirror; "sync with upstream" is not a supported operation.
- **Pull the important point of a section into a callout.** GitHub's five alert blockquotes,
  each for one kind of thing, so a reader skimming sees what matters:
  `> [!NOTE]` useful information to know even when skimming; `> [!TIP]` advice or a shortcut;
  `> [!IMPORTANT]` a core concept or a fact a reader needs to succeed; `> [!WARNING]` content
  demanding attention to avoid an error; `> [!CAUTION]` a destructive outcome to avoid. One
  callout per point, the point in a sentence, the rest of the section as prose or a list under
  it - a section that is all callouts has none. The first is the harness README's
  "Folders say topic; layers say dependency order." Not inside `plan-template/`: the plan check
  reads every blockquote line there as a template instruction the project deletes.
- Vocabulary: this repo says **rule source**, never "corpus". And it says **the KIT** — short for
  *the Clojure Agent Kit*, a name and not an acronym — for itself. Lowercase `kit` is left to the
  three machine-facing things the root `README.md` lists: a workspace's `clojure-agent-kit/` folder, the `kit`
  branch of the template fork, and that fork's `kit-vN` tags, which version THE TEMPLATE and not
  the KIT. Older prose that says "the kit" is not swept: `DEVLOG.md` is history, `NOTES.md` is
  summarised before it reaches `main`, and the adopter-facing documents change when they are next
  rewritten.

## Before changing anything

`NOTES.md` — known limitations and what is deliberately deferred.
`harness/PROVENANCE.md` — what came from where, and why each divergence exists.
