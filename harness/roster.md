# The roster — who acts in a build, from which role, on which model

Every act in a build, from the plan check to the merge, and what is behind it: a role in the
profile, a person, a script, or nothing. `method.md` §05 says what each role is for,
`portability.md` says how a seat reaches them, and [`workflow.md`](../workflow.md) says in what
order the acts happen; this document is the map between an act and its actor, which none of those
holds. The Model column is the shipped `claude` example
(`resources/profiles/claude.edn`), written into the table by `bb roster-sync` and held to it by a
test - each model, its effort and its route; a project's own profile is `<name>-build/profile.edn`, and `bb profile`
prints the live model behind each role there.

**The Architect's session** - the seat's own session, where the plan, each stage plan, each
Blueprint and each spec are written and the loop's `architect` stops are answered - is not in the
profile: it is whatever model the session runs. KIT recommends **Opus 5.5, effort high**
(`claude-opus-5-5`; in Claude Code, `/model` and `/effort`): it is what KIT was built and tried with,
and these acts are judgement, where a weaker model's mistakes become the contract every dispatched
role follows. `kit-start` says so at a project's first session.

| Act | When | Kind | Profile role | Model | Notes |
|---|---|---|---|---|---|
| Health check's runner | `bb health` | scripted | none | — | a scripted runner in every seat; no model is called |
| The plan | stage 0's plan step, once | the seat's session | the seat (`:seat`) | the session's own, not in the profile - recommended: Opus 5.5, effort high (above) | the seven documents in order, `source.md` first, and the overlay's placeholders; the plan review's findings resolved in the overview's table |
| Plan review | per stage, in its plan step | model read, cold, no tools | `:plan-reviewer` — its own role since 2026-10-06 | `openai/gpt-6.1-sol`, high, OpenRouter pinned to `openai` | the spec reviewer's selection by default. `bb plan-review`; in stage 0 the seven documents, in every stage after the stage plan first (`bb plan-review <stage-plan.md>`) with the seven as its context, and `method.md` §03's checklist - a requirement for a stage not pulled, a risk with no owning stage, a lesson unanswered, and the faults that recur - one call; findings to the plan's `reviews/` (`reviews/<stage>/` for a stage plan), resolved in the overview's table. Not a gate |
| Plan check | before the first dispatch | gate, mechanical | none | — | `bb plan-check`; `start` runs it once per workspace, cached by a hash of what it reads |
| The stage plan | per stage, just in time | the seat's session | the seat (`:seat`) | the session's own, not in the profile - recommended: Opus 5.5, effort high (above) | the goal, the risks retired, what it proves and deliberately does not, the seams, the exit criteria, the task list |
| The Blueprint | per stage, after the stage plan | the seat's session | the seat (`:seat`) | the session's own, not in the profile - recommended: Opus 5.5, effort high (above) | shapes → interfaces → namespaces → packets; the Blueprint review's findings resolved in the stage plan, then the sign-off |
| Blueprint review | per stage, before sign-off | model read, then the human gate | `:blueprint-reviewer` | `claude-opus-5-5`, high, direct to Anthropic | `bb blueprint-review <blueprint.md>`; the stage plan and the Blueprint whole, against `method.md` §07, the blueprint (over-engineered?) and §06's rules for shapes and targets; findings to the plan's `reviews/<stage>/`, resolved in the stage plan before the sign-off. Not a gate. The seat's family, by decision (`NOTES.md` row 71); the Blueprint's packets are still read one at a time by the spec review below |
| The spec | per task | the seat's session | the seat (`:seat`) | the session's own, not in the profile - recommended: Opus 5.5, effort high (above) | `bb spec-from-blueprint`, `bb sigs`; the spec review's findings read at the `:spec-reviewed` stop, the contract amended or left |
| Spec review | per task, before dispatch | model read, cold, no tools | `:spec-reviewer` | `openai/gpt-6.1-sol`, high, OpenRouter pinned to `openai` | `start` runs it for a spec with no current review and stops `:spec-reviewed`; two reviews that found something, then a person (`:spec-review/max`) |
| Coding | per run | dispatched role | `:coder` | `gemini-4-argon`, medium, direct to Google | its own worktree and nREPL |
| Testing | per run | dispatched role | `:tester` | `claude-sonnet-5-5`, medium, direct to Anthropic, 40 rounds | tests from the contract; never sees the implementation; the cache asked for on this role alone |
| Triage | on a red gate, a note or a rejected review | model read | `:orchestrator` | `claude-opus-5-5`, medium, direct to Anthropic | one call, no tools; routes to coder, tester, architect, tooling or human; also a stage-end security finding (Security routing below) |
| The Architect's stops | when the loop routes `architect` | the seat's session | the seat (`:seat`) | the session's own, not in the profile - recommended: Opus 5.5, effort high (above) | the spec review's findings, a triage that names the contract; the decision written before the retry |
| Code review | per run, after the gates are green | model read | `:reviewer` | `openai/gpt-6.1-sol`, high, OpenRouter pinned to `openai` | a diff, the contract slice, the green gate report; a rejection goes to triage, under the retry cap |
| The person | approvals, stops, merges | human | none | — | the scope, the plan, each Blueprint's sign-off, every merge, the money |
| Stage-end checks | at each stage's end | human, in a browser | none | — | screenshots and the interaction check, outside the loop (`method.md` §04; the build docs' `03` §17) |
| Security review | at each stage's end | models with tools, in a sandbox | `:security-panel` (or one `:security-reviewer`) | in rotation: `openai/gpt-6-astra`, high, OpenRouter pinned to `openai`; `gemini-4-argon`, high, direct to Google - left out while it is the Coder's family; `openai/gpt-6.1-sol`, xhigh, OpenRouter pinned to `openai` | `bb security-panel <clone of the merged tip> --stance tracer --base stage-<N-1>`: the next member in rotation by default, another, two or all of them on the person's word, all of them at pre-release; each reads the whole application with five tools (a change review a sixth, `read_diff`) in a container with no network; a finding is `reproduced` only when a test it wrote fails, else a `hypothesis`; the combined table sets the members' answers side by side. A member of the Coder's family reads nothing. Real model calls, never a gate |
| Security routing | after the security review | model read | `:orchestrator` | `claude-opus-5-5`, medium, direct to Anthropic | `bb security-route <review.edn> <clone>`: one triage call per reproduced finding - `coder` (a fix packet drafted for the Architect), `architect`, `human`; hypotheses listed for the threat model; nothing dispatched |

Three things the table makes visible. The independence rule is about roles, not models: `bb
profile` holds every verifier - `:tester`, `:reviewer`, `:spec-reviewer`, `:plan-reviewer`,
`:security-reviewer` (each member of a security reviewer panel; one of the Coder's family is left out) - to a family other than the `:coder`'s, whatever the models are. In the shipped example every review of words and of code but the Blueprint's — the
plan, each spec, each diff — is one model read three ways; whether that is the right economy is a
bake-off's question (`NOTES.md` row 46), not a rule. And the Blueprint's reader is the seat's own
family, which the rule's reasoning would forbid: an accepted exception from a measurement, written
in the profile and watched in the register (row 71), like the orchestrator's.

The seat's five rows are one voice on purpose: the plan, the stage plans, the Blueprints and
the specs are written by the same role, in the session the person works in, on whatever model that
session was started with. The three reviews of words — the plan's, the Blueprint's, each spec's —
are where the second voices come from, and of the three the plan reviewer's and the spec
reviewer's families are held to differ by check; the plan reviewer is its own role so that a
project can set it apart from the spec reviewer, and the shipped example sets the two the same.

How a role's model is chosen: `bb bake-off` - candidates read the same artifact once each, a judge
that is never a candidate maps where they agree and disagree, blind, and the person marks what is
real. The model column above is what the last such comparison, or a dated decision, left in the
shipped example.
