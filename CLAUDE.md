# clojure-agent-kit

**This file is the working rules for an agent changing this repository.** Not what the
kit is (`README.md`), not the method it teaches (`method.md`), not its current
limitations (`NOTES.md`) — those are linked where you need them.

A reusable build method for developing Clojure software with a small team of AI agents:
`method.md` (the method), `skeletons/` (fillable plan documents), `harness-seed/` (runnable
code). Public, MIT, at `github.com/ontopro/clojure-agent-kit`.

## You are working ON this repo, not through its loop

That distinction is load-bearing here, because this repo contains a worked example of
agent rules and those rules are not yours.

**`harness-seed/AGENTS.md` is a product artifact, not instructions for you.** It is
generated from `harness-seed/resources/agent-rules.edn` and shipped as the demonstration of
the rule-source pattern. It addresses an agent dispatched with a task packet, inside a loop,
under a harness that runs the gates for it. **None of that is running when you work here.**
Three of its rules invert:

| Its rule | Reads as | For you |
|---|---|---|
| *Do not run the gates* | the harness runs them after you finish | **Run them.** Nothing else will |
| *Stay inside your packet* | write only `:files/target` | There is no packet |
| *No REPL, no edits* | a dispatched nREPL died mid-task | There is no dispatched nREPL |

`harness-seed/CLAUDE.md` is a hand-written stub that imports `AGENTS.md` for Claude Code's
benefit. It is also a product artifact, and also not addressed to you.

`harness-seed/agents/interactive-programmer.md` is the role you are actually playing.

## Working rules

- **Run `bb repair && bb gates` in `harness-seed/` before committing.** `repair` is gate 0
  over the Clojure files you changed; `gates` is doctor → format → lint → rules → reports → test. Fast,
  and the only thing checking this repo — there is no CI.
- **A finding is not finished until a document carries it.** A run goes in
  `RUNS.md`; anything still open goes in `NOTES.md`'s register; what changed and
  why goes in `DEVLOG.md`. Write it up in
  the commit that closes it, not later. This rule started as *a RUN is not
  finished* — and within the hour two findings that were not runs went into a
  commit message and nowhere else, which is the exact failure it was added to
  stop. A commit message is not a document; nobody greps for a fact they do not
  know exists.
- **A number or a capability claim names the command that produced it, and the command
  has been run.** Not "this is checkable" — the check, executed, over every case the
  sentence covers rather than the one example in front of you. Every false claim this
  repository has carried was a true example holding up an over-general sentence: a
  re-render command that worked on the run it named and threw on two others, a byte count
  read off `du` block size, a docstring saying the REPL tool was not a shell while a model
  ran `sh` through it. `bb report-check` enforces this for the one claim backed by
  committed data. Everywhere else it is a habit, and the cheap version is to state the
  check before the claim and notice when you cannot.
- **Never hand-edit `harness-seed/AGENTS.md`'s marker block.** It is generated. Edit
  `resources/agent-rules.edn` and run `bb rules-sync`; `bb gates` fails on drift. Same for
  any rule text — the source is the only place a rule may be written.
- **The run records are committed in `runs/`; the drivers are not.** `RUNS.md`
  publishes eight report tables and 22 cost and token figures, and `runs/` is what
  they re-render from — a published number nobody can re-derive is an unverifiable
  claim, which is the one thing this repo is most against. The drivers, task specs
  and session files stay in the gitignored `.local/runs/`: they hardcode paths and
  the next run replaces them. `.local/README.md` says what is there and that
  deleting it is safe. Keep both halves on the right side of that line.
- **`LICENSE` must stay the canonical MIT text and nothing else.** Third-party notices live
  in `NOTICE`. Appending them to `LICENSE` made GitHub classify the repo "Other" and cost
  the licence badge (commit `fb5e3b0`).
- **The seed is bb-only on purpose** — no `deps.edn`, no `.mise.toml`. It needs no JVM, so a
  JDK pin it does not have would fail `bb doctor` on an unrelated machine.
- **`PROVENANCE.md` divergences are fixes, not drift.** This is a fork of a private harness,
  not a mirror; "sync with upstream" is not a supported operation.
- Vocabulary: this repo says **rule source**, never "corpus".

## Before changing anything

`NOTES.md` — known limitations and what is deliberately deferred.
`harness-seed/PROVENANCE.md` — what came from where, and why each divergence exists.
