# runs/ — the records `RUNS.md`'s tables were rendered from

One run record per run that produced a report. They are here so the
numbers in [`RUNS.md`](../RUNS.md) are **checkable rather than asserted**: every
table in that file re-renders from the file beside it, and a figure nobody can
re-derive is not evidence.

```bash
cd harness-seed && bb report < ../runs/d6.edn
```

| | Run | `RUNS.md` |
|---|---|---|
| `r-03.edn` `r-04.edn` `r-05.edn` | manual runs 3–5 | no model called, so no model, provider or cost |
| `d1-smoke.edn` | the two-adapter smoke test | a build step, not a loop run |
| `d3.edn` | the first real dispatch | one role only |
| `d4.edn` `d5.edn` `d6.edn` | the full dispatched loop | D4 failed at the test gate, and its record says so |
| `d7.edn` | the full loop, with a hand-triaged retry of both roles | the first to carry `:run/events` — see below |
| `d8.edn` | the full loop on the new Tester, retried by triage | events carry the per-dispatch timing split and both mutation checks |
| `d9.edn` | the first run with property targets on every packet; no retry | events carry ten mutants with their substitutions, and the constructor-cost timings behind `NOTES.md` row 12 |
| `d10.edn` | the first run through the committed `bb run-loop` | written by the driver's own `mutation` and `record`; events carry the two-source timing behind row 12 |
| `d11.edn` | the test of the sharpened seam rule | events carry twelve exact-match mutants and the linear `paths-to` timing |
| `d12.edn` | the first dispatched rewrite; an Architect amendment and `:architect` retries | events carry the red gate, the amendment, both triage decisions, the mutants (with one discarded and why), and the dependents re-run |
| `d13.edn` | D12's attempt 1 rerun with the dependents in every packet; failed, not retried | events carry the `:dependents` computed and the red test gate |
| `d14.edn` | D13 again with only the Coder's model changed (Claude Fable 5.1); failed, not retried | events carry the Coder's note naming the conflict, and the red test gate it predicted |
| `d15.edn` | D14 again with the note pause; paused, amended, completed | events carry the `:paused` note, the amendment, the triage decision, `:continued`, one green gate run and eleven killed mutants |
| `d16.edn` | a trivial task run for its record: the first with transcripts | each `:dispatch` event carries a capped `:transcript`, one turn per completion |
| `d17.edn` | D15 again with the Coder at low effort; paused, amended, a failed retry (API 400), a red gate the driver caused, then green | events carry the note, the pause, the failed dispatch with its error, both gate runs, the second triage, eleven killed mutants, and four transcripts |
| `d18.edn` | D16's task again with prompt caching on and a priced Coder | the Coder event carries the four-way `:usage` and the `:pricing` behind its `~$` cost; the first `:calls` step |
| `d19.edn` | stage S1, task 1 of 3: run from the `stage/s1` branch and merged onto it | events carry the Coder's note, the pause and `:continued`, and ten mutants; re-recorded after `NOTES.md` row 14's fix: `:run/files` holds the gated Tester file, `:run/files-as-written` the one it wrote |
| `d20.edn` | stage S1, task 2 of 3: red twice, target 8 amended, the Tester retried twice — its last attempt on the fixed `nrepl_eval` (`NOTES.md` row 16) | events carry two red gates each with its `:routing`, the amendment, two `:triage` events with `:leak-check`, and eleven mutants; `:run/files` equals the gated bytes |
| `d21.edn` | stage S1, task 3 of 3: the first edit to an existing namespace, green on the first attempt | the Tester's transcript carries the first `ERROR:` result from the fixed `nrepl_eval` (`NOTES.md` row 16); eight mutants, one surviving (row 20); `:run/files` equals the gated bytes |
| `b1-codex53.edn` `b1-solflex.edn` `b1-glm53.edn` | B1, a Tester bake-off: one dispatch per candidate, gated against a reference | not loop runs; `:run/events` carries the timing split, gates and mutants |

**Runs 1 and 2 have no surviving record** — they predate the report, and
`RUNS.md` says so rather than showing a table for them. **Records before D7 carry steps
only**, so the iteration caps, assertion counts and first-dispatch figures `RUNS.md`
§Run 3–§D6 quote are not in them; they were read off the runs at the time.

## What these are, exactly

Every record here but the ones named below is **the runner's own output, byte for byte**.
They were not cleaned up or reformatted, which is the property that makes them
worth committing.

**D7–D15 and B1 were backfilled on 2026-09-14**, after a claim audit found quoted code and
a failing gate's output only in the gitignored `.local/runs/`:

- **D7–D15** were re-written by the committed `bb run-loop record`, replayed from each run's
  saved state. That added `:run/files` (the impl and test files each role wrote — before
  gate 0, so not always the bytes the gates passed; see `NOTES.md` row 14, whose fix does not
  reach these: their gate worktrees are gone) and, on
  D12–D14's red gate, `:feedback` (the gate's output, from the `last-gate-feedback.edn` the
  driver saved). Each was checked, byte for byte, to equal its earlier record with those two
  additions removed.
- **B1's `b1-codex53.edn` and `b1-solflex.edn`** gained `:defspecs` on their `:test-file` event,
  counted from the test file each candidate wrote. That one key is the only thing in them
  the bake-off driver did not write.
- **D19–D21 were re-recorded on 2026-09-15** by `bb run-loop record` after `NOTES.md` row 14's
  fix, replayed from each run's saved state with `final/` laid out by path from the gate
  worktree's bytes, copied before teardown (`.local/runs/row14/rerecord.bb`). D20's and D21's
  records came out byte for byte. D19's changed in `:run/files` — the gated Tester file for the
  one it wrote — and gained `:run/files-as-written`; nothing else in it moved.

`d1-smoke.edn` and `d3.edn` are **reconstructions** — the only two files here
the runner did not write. D1 predates the run record and D3 dispatched a
single role, so both produced a raw result that `bb report` cannot read;
each was shaped into a record by a ten-line local script, from that result's
own fields and nothing typed by hand. Both render identically to what
`RUNS.md` has published all along.

**From `d7.edn` on, records are larger because they carry `:run/events`**, which
`bb report` ignores and `RUNS.md`'s D7 prose quotes from: every note a model
left, every feedback item a retry carried, each triage decision as written
before its dispatch, the contract amendment, and the mutation checks. From
`d16.edn` on, each `:dispatch` event also carries a `:transcript`: what the model
said and ran, one turn per completion, every string cut to 500 characters with the
cut announced (the whole transcript stays in the run's local directory). A cost rendered
as `~$…` was computed from usage × the profile's list prices, not reported by the endpoint;
the dispatch event carries the `:usage` counts and the `:pricing` rates it came from, and the
footer names how many steps are priced that way. From D18 on, a `:calls` step and event sit
between gate 0 and the shell gates: the implementation's calls into its context namespaces,
checked against the slice's `:deps-sigs`; a red one stops the run there. A dispatch event
carries `:retries` when any of its requests was sent again after a 429 or a 5xx. From the
commit that closed row 5 on, a red gate is followed by a `:routing` event — the files the gate
named, the owner that implies, and what a retry would carry, proposed by the driver before the
human's `:triage` event that follows — and a Tester's `:triage` event carries `:leak-check`, the
leaks found in its feedback and whether `--allow-leak` sent it anyway; `d20.edn` is the first record
to carry both. From `NOTES.md` row 14's fix, a `check` in which gate 0 changed a file leaves a
`:gate-0` event with the hunks it changed, and a record whose final files gate 0 changed carries
`:run/files-as-written` beside `:run/files`; `d19.edn` carries the second. Quoted
text in a committed document is a claim like a number is, and this is where it
can be checked.

## What is not here

The driver scripts, task specs and worktree/session files each run needed are
working material, not evidence, and stay in the gitignored `.local/runs/` —
they hardcode paths and are superseded by the next run. See `.local/README.md`,
and `CLAUDE.md` for why the split falls where it does.
