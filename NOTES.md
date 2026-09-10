# Notes — known limitations and what's not done

Honest state of the kit. Everything here is deliberate: either deferred with a reason, or a
known weak point worth naming rather than discovering.

## Known limitations

**A rule renders as one markdown bullet.** `harness.rules/markdown` emits
`- **Title** text`, so a rule's `:text` supports inline formatting (`code`, *emphasis*) and
nothing else — no fenced code blocks, tables or sub-bullets. A rule needing those has no
home today. The fix is to extend the renderer; the tempting workaround is the trap below.
Documented at all three points where you'd hit it: the rule-source header, the renderer's
docstring, and `harness-seed/README.md`.

**Nothing detects a rule written outside the markers.** This is the weakest link in the
rule-source design. A rule hand-written into `CLAUDE.md` outside `<!-- agent-rules:begin -->`
survives every `bb rules-sync`, so the drift gate never complains — and it reaches only
clients that read that file, which by the independence rule excludes the agent verifying the
Coder. Silent, and exactly the failure the design exists to prevent. It is documented in
four places and enforced in none. A check would have to heuristically flag bullet lists
outside the block, which risks false positives on legitimate hand-written content; that
tradeoff hasn't been made yet.

**No CI.** `bb gates` runs locally only. There is no Actions workflow, so nothing verifies
the gates on push or on a pull request.

## Not built, deliberately

`harness-seed/README.md` §"Deliberately left out" is the authoritative list — the
orchestration loop, triage, workspace provisioning, headless runners, per-gate timeout — each
with the reason and the order to add them back. `method.md` §12 argues why you should not
build the loop speculatively in the first place.

## Adjacent work

The kit was extracted from a private harness, and several defects found during extraction
still exist upstream — `harness-seed/PROVENANCE.md` lists every divergence with its reason,
and doubles as the fix list for that project. Back-porting them is pending.

## If you change the licence files

`LICENSE` must stay the canonical MIT text and nothing else. Appending the third-party
notices to it made GitHub classify the repo as "Other" / NOASSERTION rather than MIT, which
cost the licence badge and licence-filtered search. That is why `NOTICE` is a separate file
(see commit `fb5e3b0`).
