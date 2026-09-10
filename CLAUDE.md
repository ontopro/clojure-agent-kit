# clojure-agent-kit

A reusable build method for developing Clojure software with a small team of AI agents:
`method.md` (the method), `skeletons/` (fillable plan documents), `harness-seed/` (runnable
code). Public, MIT, at `github.com/ontopro/clojure-agent-kit`.

## You are working ON this repo, not through its loop

That distinction is load-bearing here, because this repo contains a worked example of
agent rules and those rules are not yours.

**`harness-seed/CLAUDE.md` is a product artifact, not instructions for you.** It is
generated from `harness-seed/resources/agent-rules.edn` and shipped as the demonstration of
the rule-source pattern. It addresses an agent dispatched with a task packet, inside a loop,
under a harness that runs the gates for it. **None of that is running when you work here.**
Three of its rules invert:

| Its rule | Reads as | For you |
|---|---|---|
| *Do not run the gates* | the harness runs them after you finish | **Run them.** Nothing else will |
| *Stay inside your packet* | write only `:files/target` | There is no packet |
| *No REPL, no edits* | a dispatched nREPL died mid-task | There is no dispatched nREPL |

`harness-seed/agents/interactive-programmer.md` is the role you are actually playing.

## Working rules

- **Run `bb gates` in `harness-seed/` before committing.** doctor → format → lint → rules →
  test. It is fast and it is the only thing checking this repo — there is no CI.
- **Never hand-edit `harness-seed/CLAUDE.md`.** It is generated. Edit
  `resources/agent-rules.edn` and run `bb rules-sync`; `bb gates` fails on drift. Same for
  any rule text — the source is the only place a rule may be written.
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
