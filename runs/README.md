# runs/ — the records `RUNS.md`'s tables were rendered from

Eight run records, one per run that produced a report. They are here so the
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

**Runs 1 and 2 have no surviving record** — they predate the report, and
`RUNS.md` says so rather than showing a table for them.

## What these are, exactly

`r-03`–`d6` minus the two below are **the runner's own output, byte for byte**.
They were not cleaned up or reformatted, which is the property that makes them
worth committing.

`d1-smoke.edn` and `d3.edn` are **reconstructions** — the only two files here
the runner did not write. D1 predates the run record and D3 dispatched a
single role, so both produced a raw result that `bb report` cannot read;
each was shaped into a record by a ten-line local script, from that result's
own fields and nothing typed by hand. Both render identically to what
`RUNS.md` has published all along.

## What is not here

The driver scripts, task specs and worktree/session files each run needed are
working material, not evidence, and stay in the gitignored `.local/runs/` —
they hardcode paths and are superseded by the next run. See `.local/README.md`,
and `CLAUDE.md` for why the split falls where it does.
