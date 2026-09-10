# Clojure Agent Kit

**An opinionated, reusable way to build Clojure software with a small team of independent
AI agents** — a contract-first Blueprint, an isolated dispatch loop, a living decision log,
and quality gates ordered cheap-to-expensive.

Distilled from a live multi-agent build that ran this loop across dozens of real dispatches,
including several it got wrong. What's here is the discipline that survived contact with
those runs, not a design done on paper.

> **Copy it and edit it. Don't depend on it.** This is a starting point, not a framework —
> there is no library to require and no version to track. Delete what you don't need; the
> parts you keep are meant to be changed.

## Three parts

| | What it is |
|---|---|
| **[`method.md`](method.md)** | The method. Three phases — Plan, Foundation, Stages — run as one lean-agile discipline: lean decides *what* to build, agile decides *how*, and the flow is Kanban (pulled, WIP-limited, no timeboxes). Roles, the task-packet contract, the rule source, the decision log, the gate order, and a field guide of ten lessons each bought with a real run. |
| **[`skeletons/`](skeletons/)** | Fillable plan documents — overview with a ranked risk register, requirements with MVP/post-MVP scoping, architecture, method-and-tooling, decision log, and just-in-time stage docs. |
| **[`harness-seed/`](harness-seed/)** | ~1900 lines that actually run: a toolchain doctor, Malli shapes, the packet assembler, the gate runner, gate 0, the `AgentRunner` seam with a conformance check, and the rule source with a drift gate. |

## Try the seed

Needs [Babashka](https://babashka.org), `cljfmt` and `clj-kondo`; `bb doctor` will tell you
what's missing and why each tool matters.

```bash
cd harness-seed
bb doctor     # what your Clojure toolchain has, and what it's for
bb gates      # doctor -> format -> lint -> rules -> test
bb example    # the whole loop shape in one run — no model calls, no network
```

`bb example` is the 60-second tour: it assembles a task packet, shows the Tester's context
being stripped of the implementation, dispatches to a scripted runner, repairs the output,
runs the gates until one fails, and checks the runner against the contract.

## Is it worth it?

It pays for itself when a project has enough scope to amortise setting the process up once,
and a correctness bar worth a dedicated, independent verification step. For a weekend build
or a single-session prototype, skip to a plain coder loop plus gates plus one human review —
the full role model and the decision log are overhead you don't need yet.

It scaffolds on stock
[Clojure Stack Lite](https://github.com/abogoyavlensky/clojure-stack-lite) (HTMX, AlpineJS,
TailwindCSS, SQLite/PostgreSQL), with [XTDB v2](https://xtdb.com) as a SQL-compatible
alternative datastore.

## Known limitations

[`NOTES.md`](NOTES.md) is the honest state of the kit — what is deliberately deferred, and
where the weak points are.

## Provenance

[`harness-seed/PROVENANCE.md`](harness-seed/PROVENANCE.md) records what was extracted from
the private harness it came from and every place this copy deliberately diverges — each with
its reason. It is a **fork, not a mirror**; "sync with upstream" is not a supported
operation, and the divergences are fixes rather than drift.

## Licence

[MIT](LICENSE). Six rules in the seed's rule source are adapted from
[github/awesome-copilot](https://github.com/github/awesome-copilot) (MIT); see
[`NOTICE`](NOTICE) for the attribution and the upstream licence text.
