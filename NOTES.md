# Notes — known limitations and what's not done

**Updated 2026-09-13 14:52.**

Honest state of the kit **right now**: what is missing, what is weak, and what is open.
Everything here is deliberate — either deferred with a reason, or a weak point worth
naming rather than discovering.

It does not hold history (that is [`DEVLOG.md`](DEVLOG.md)), what the runs found
([`RUNS.md`](RUNS.md)), or the rules for working on this repo (`CLAUDE.md`). If a fact
here stops being true, it belongs in one of those and not both.

## Known limitations

**A rule renders as one markdown bullet.** `harness.rules/markdown` emits
`- **Title** text`, so a rule's `:text` supports inline formatting (`code`, *emphasis*) and
nothing else — no fenced code blocks, tables or sub-bullets. A rule needing those has no
home today. The fix is to extend the renderer; the tempting workaround is the trap below.
Documented at all three points where you'd hit it: the rule-source header, the renderer's
docstring, and `harness-seed/README.md`.

**Nothing detects a rule written outside the markers.** This is the weakest link in the
rule-source design. A rule hand-written into `AGENTS.md` outside `<!-- agent-rules:begin -->`
survives every `bb rules-sync`, so the drift gate never complains — and it reaches only
clients that read that file, which by the independence rule excludes the agent verifying the
Coder. Generating one mirror rather than several keeps this to a single file, but does not
fix it; `harness-seed/CLAUDE.md` is a hand-written stub and is a second place it could
happen. Silent, and exactly the failure the design exists to prevent. It is documented in
four places and enforced in none. A check would have to heuristically flag bullet lists
outside the block, which risks false positives on legitimate hand-written content; that
tradeoff hasn't been made yet.

**No CI.** `bb gates` runs locally only. There is no Actions workflow, so nothing verifies
the gates on push or on a pull request.

**Only one kind of documented claim is gated.** `bb report-check` re-renders every report
published in `RUNS.md` from `runs/` and fails on any difference, in both directions — a
published table with no record, and a record nothing publishes. That covers the one claim in
this repository backed by committed data. Every other assertion a document makes is checked
by someone reading it.

Running the fenced commands in the documents is the obvious second gate and a much weaker
one. It would **not** have caught the failure that prompted the first: `.local/README.md`
offered a re-rendering command as proof the figures were auditable, and that command worked —
it named `d6`, one of the six records that render. What was false was the claim around it,
which generalised past the single case it had been tested on. A command runner catches path
rot and renamed tasks; it does not catch a true example supporting an untrue sentence.

## Not built, deliberately

`harness-seed/README.md` §"Deliberately left out" is the authoritative list — the
orchestration loop, triage, per-gate timeout — each with the reason and the order to add
them back. `method.md` §12 argues why you should not build the loop
speculatively in the first place.

## Harness portability

`portability.md` is the standing analysis, and the only place these facts live: which
interactive seat, which model per role, what each candidate client actually offers, and the
dispatch design that follows.

The distinction it turns on: **a project has one interactive seat, one dispatch harness, and a
different model family per dispatched role.** §05's independence rule lives in the harness, by
family — not in the seat.

**Both halves are built.** The seat half, and of the dispatch half: provisioning, the
Blueprint slice as loadable source, the `:deps-sigs` precondition, profiles, the run report,
and the API-backed runner — two request adapters, a tool loop, and the two-call provenance
that turns a completion id into the provider that actually answered and what it cost.
`resources/profiles/` is dispatched from, not merely checked.

What remains deliberately unbuilt is the ORCHESTRATION around it: the loop itself, triage
with a capped retry, and the append-only run log. Every run so far has been driven by a
throwaway script, and triage has been done by hand.

**Readiness criterion 6 is closed nine times over.** §03 step 5 — the whole loop, end to
end — has been run against `sandbox/` on nine tasks: five through `ManualRunner` with the
roles played by hand, then four dispatched to real models for about $0.55.
**[`RUNS.md`](RUNS.md) is the record, and a run is not finished until it is in there.**
Its eight report tables re-render from [`runs/`](runs/), which is committed so the
figures are checkable; the drivers that produced them are not.
Every run found something, and most of what `harness-seed/` has grown since exists because a
run made it necessary; the design conclusions are in `portability.md`.

### Still open from the runs

Twenty-five of the runs' findings were fixed and are explained where they were found,
in [`RUNS.md`](RUNS.md). These are what is left. They are not duplicated there — a second
copy of a list is the drift these runs kept catching in the code.

| # | Still open | From | Kind |
|---|---|---|---|
| 1 | **Nothing tells a task that rewrites a namespace what else depends on it.** Run 5 rewrote `sandbox.render`; `property_test.clj` and `api_test.clj` depend on it and are in neither role's `:files/target`. They stayed green by luck, not design. | Run 5 | gap |
| 2 | **Whether D4's Tester failure was tier or family was never established.** D5 swapped `deepseek-v4-flash` for `gpt-5.2-codex` and pinned the provider in one change, and D6 then showed the real cause was a missing stop condition in the prompt. The profile change was right; the experiment that would separate the variables was never run. | D4, D6 | question |
| 3 | **A test named *at any depth* or *recursive*, satisfied by a fixture one level deep.** Caught by mutation in run 4 and again in run 5, in different namespaces by different authors. No gate can see it; it is a habit for review to watch for. | Runs 4, 5 | watch |
| 4 | **Nothing calls `packet/for-retry`.** Triage is not built, so `:task/feedback` has a producer and a consumer — `packet-prompt` renders it — and no orchestrator joining them. Every retry so far was driven by hand from a throwaway script. | b040fd6 | unexercised |
| 5 | **No model has been offered the `note` tool.** It is unit-tested with seven mutations and no dispatch has ever seen it. Whether a model uses it, and whether the extra round-trips push a Tester into its iteration cap — D6's needed 24 of 24 — are both unmeasured. | b040fd6 | unexercised |

**Two entries left this table together.** A Coder with no way to report an edge the
Blueprint is silent about, and a packet with no field for why a role is back, both wanted a
return channel, and turned out to be two halves of one circuit: a role reports something
outbound with the `note` tool, and `packet/for-retry` carries it — and any failed gate's own
output — back in as `:task/feedback`. Neither is worth anything alone: a note that reaches nobody is a
diary, and a retry with no reason is the same dispatch twice.

The framing that made it tractable: **the channel was never missing, it was prose.**
Run 4's Coder did say the contract was silent about dividing 7 by 2, and D3's did say
why it declined a `:deps-sigs` entry — both into `:stdout`, which nothing reads.

Rows 4 and 5 arrived with that same change and are what a D7 run is for. They are
marked `unexercised` rather than `gap` on purpose: the mechanism is built and
tested, and what is missing is evidence that it survives contact with a model.

## Adjacent work

The kit was extracted from a private harness, and several defects found during extraction
still exist upstream — `harness-seed/PROVENANCE.md` lists every divergence with its reason,
and doubles as the fix list for that project. Back-porting them is pending.

