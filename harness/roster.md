# The roster — who acts in a build, from which role, on which model

Every act in a build, from the plan check to the merge, and what is behind it: a role in the
profile, a person, a script, or nothing. `method.md` §05 says what each role is for,
`portability.md` says how a seat reaches them, and [`workflow.md`](../workflow.md) says in what
order the acts happen; this document is the map between an act and its actor, which none of those
holds. The Model column is the shipped `claude` example
(`resources/profiles/claude.edn`) as of 2026-10-08 — every role over OpenRouter, pinned to its
provider with no fallback; a project's own profile is `<name>-build/profile.edn`, and `bb profile`
prints the live model behind each role there.

| Act | When | Kind | Profile role | Model | Notes |
|---|---|---|---|---|---|
| Health check's runner | `bb health` | scripted | none | — | a scripted runner in every seat; no model is called |
| The plan | Phase A, once | the seat's session | the seat (`:seat`) | the session's own; not in the profile | the six documents in order, `source.md` first, and the overlay's placeholders; the plan review's findings resolved in the overview's table |
| Plan review | per stage, in its plan step | model read, cold, no tools | `:plan-reviewer` — its own role since 2026-10-06 | `openai/gpt-6.1-sol`, high — the spec reviewer's selection by default; the profile's comment says why | `bb plan-review`; in stage 0 the seven documents, in every stage after the stage plan first (`bb plan-review <stage-plan.md>`) with the seven as its context, and `method.md` §02's checklist - a requirement for a stage not pulled, a risk with no owning stage, a lesson unanswered, and the faults that recur - one call; findings to the plan's `reviews/` (`reviews/<stage>/` for a stage plan), resolved in the overview's table. Not a gate |
| Plan check | before the first dispatch | gate, mechanical | none | — | `bb plan-check`; `start` runs it once per workspace, cached by a hash of what it reads |
| The stage document | per stage, just in time | the seat's session | the seat (`:seat`) | the session's own; not in the profile | the goal, the risks retired, what it proves and deliberately does not, the seams, the exit criteria, the task list |
| The Blueprint | per stage, after the stage document | the seat's session | the seat (`:seat`) | the session's own; not in the profile | shapes → interfaces → namespaces → packets; the Blueprint review's findings resolved in the stage document, then the sign-off |
| Blueprint review | per stage, before sign-off | model read, then the human gate | `:blueprint-reviewer` | `anthropic/claude-opus-5.5`, high | `bb blueprint-review <blueprint.md>`; the stage document and the Blueprint whole, against `method.md` §07 step 2 (over-engineered?) and §06's rules for shapes and targets; findings to the plan's `reviews/<stage>/`, resolved in the stage document before the sign-off. Not a gate. The seat's family, by decision (`NOTES.md` row 71); the Blueprint's packets are still read one at a time by the spec review below |
| The spec | per task | the seat's session | the seat (`:seat`) | the session's own; not in the profile | `bb spec-from-blueprint`, `bb sigs`; the spec review's findings read at the `:spec-reviewed` stop, the contract amended or left |
| Spec review | per task, before dispatch | model read, cold, no tools | `:spec-reviewer` | `openai/gpt-6.1-sol`, high | `start` runs it for a spec with no current review and stops `:spec-reviewed`; two reviews that found something, then a person (`:spec-review/max`) |
| Coding | per run | dispatched role | `:coder` | `anthropic/claude-sonnet-5.5`, medium | its own worktree and nREPL; the cache asked for on this role alone |
| Testing | per run | dispatched role | `:tester` | `google/gemini-3.8-flash`, medium | tests from the contract; never sees the implementation |
| Triage | on a red gate, a note or a rejected review | model read | `:orchestrator` | `anthropic/claude-opus-5.5`, medium | one call, no tools; routes to coder, tester, architect, tooling or human; also a stage-end security finding (Security routing below) |
| The Architect's stops | when the loop routes `architect` | the seat's session | the seat (`:seat`) | the session's own; not in the profile | a contract that keeps drawing findings, a triage that names the contract; the decision written before the retry |
| Code review | per run, after the gates are green | model read | `:reviewer` | `openai/gpt-6.1-sol`, high | a diff, the contract slice, the green gate report; a contract that keeps drawing findings goes to a person |
| The person | approvals, stops, merges | human | none | — | the scope, the plan, each Blueprint's sign-off, every merge, the money |
| Stage-end checks | at each stage's end | human, in a browser | none | — | screenshots and the interaction check, outside the loop (`method.md` §04; the plan template's `03` §17) |
| Security review | at each stage's end | model with tools, in a sandbox | `:security-reviewer` | `openai/gpt-6-astra`, high | `bb security-review <clone of the merged tip> --stance tracer --base stage-<N-1>`: the whole application read with five tools in a container with no network; a finding is `reproduced` only when a test it wrote fails, else a `hypothesis`. Its family is held to differ from the Coder's. A real model call, never a gate |
| Security routing | after the security review | model read | `:orchestrator` | as Triage above | `bb security-route <review.edn> <clone>`: one triage call per reproduced finding - `coder` (a fix packet drafted for the Architect), `architect`, `human`; hypotheses listed for the threat model; nothing dispatched |

Three things the table makes visible. The independence rule is three roles, not three models:
`:coder`, `:tester` and `:reviewer` are held to three families by `bb profile`, whatever the
models are. In the shipped example every review of words and of code but the Blueprint's — the
plan, each spec, each diff — is one model read three ways; whether that is the right economy is a
bake-off's question (`NOTES.md` row 46), not a rule. And the Blueprint's reader is the seat's own
family, which the rule's reasoning would forbid: an accepted exception from a measurement, written
in the profile and watched in the register (row 71), like the orchestrator's.

The seat's five rows are one voice on purpose: the plan, the stage documents, the Blueprints and
the specs are written by the same role, in the session the person works in, on whatever model that
session was started with. The three reviews of words — the plan's, the Blueprint's, each spec's —
are where the second voices come from, and of the three the plan reviewer's and the spec
reviewer's families are held to differ by check; the plan reviewer is its own role so that a
project can set it apart from the spec reviewer, and the shipped example sets the two the same.

How a role's model is chosen: `bb bake-off` - candidates read the same artifact once each, a judge
that is never a candidate maps where they agree and disagree, blind, and the person marks what is
real. The model column above is what the last such comparison, or a dated decision, left in the
shipped example.
