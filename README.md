# Clojure Agent Kit (aka "KIT")

**An opinionated, reusable way to build Clojure software with a small team of independent
AI agents** — a contract-first Blueprint, an isolated dispatch loop, a living decision log,
and quality gates ordered cheap-to-expensive.

Distilled from a live multi-agent build that ran this loop across dozens of real dispatches,
including several it got wrong. What's here is the discipline that survived contact with
those runs, not a design done on paper.

**The KIT** is the short form of *the Clojure Agent Kit*, and is how these documents refer to it.
It is a name, not an acronym. The lowercase word turns up in three other places, and means
something narrower each time:

| Written | Where | What it is |
|---|---|---|
| `clojure-agent-kit/` | a folder in a project's workspace | that workspace's clone of the KIT, under the name `git clone` gives it; `workspace.edn` records it, so it may be renamed or kept elsewhere - a KIT kept outside its workspace is pointed at it with `KIT_WORKSPACE` or `--workspace <dir>` |
| `kit` | a branch of [`ontopro/clojure-stack-lite`](https://github.com/ontopro/clojure-stack-lite), the application template the KIT brings with it | the KIT's line of that template; its `master` is an untouched mirror of the upstream template |
| `kit-v1`, `kit-v2`, … | tags on that branch | versions of THE TEMPLATE as the KIT pins it — **not** versions of the KIT |

> **Clone it and build beside it.** The KIT is what a project adopts: its clone sits in the
> project's workspace next to the application it generates from a pinned template, upgraded
> with `git pull`, and nothing of the project is written into it. There is no library to
> require; there is one commit of one template that a dated health check certifies with it.

## Three parts

| | What it is |
|---|---|
| **[`method.md`](method.md)** | The method. Three phases — Plan, Foundation, Stages — run as one lean-agile discipline: lean decides *what* to build, agile decides *how*, and the flow is Kanban (pulled, WIP-limited, no timeboxes). Roles, the task-packet contract, the rule source, the decision log, the gate order, and a field guide of twelve lessons each bought with a real run. |
| **[`plan-template/`](plan-template/)** | The plan template, half-written on purpose — the source a plan derives from, an overview with a ranked risk register, requirements with MVP/post-MVP scoping, architecture and method-and-tooling each in three parts (GIVEN by adopting the KIT, CHOSEN once in Foundation, THEIRS the domain), the decision log, and just-in-time stage docs. `bb init` copies it into the project's plan repository, beside the rules overlay, the profile and the run records that repository also holds, and `bb plan-check` reads the filled plan before Foundation. |
| **[`harness/`](harness/)** | The code that runs, and its health check's first subject (`health/selfcheck/`, a deliberately trivial project with gates that execute and fixtures that make each fail): the doctor, `bb init` and the health check; the two readings of a filled plan (`bb plan-check`, the gate; `bb plan-review`, the model's pass); the packet assembler, the gate runner, gate 0 and the boundary gate, three-worktree provisioning, an API-backed runner, a per-run cost report that names the three commits it ran against, the rule source with a drift gate, a bake-off that compares candidates for a role with a judge reading blind, and the loop that drives them — stopping for a person at every branch it cannot decide. [Its own README](harness/README.md) is the inventory. |

**Each of the repository's own documents has one job**, and none of them repeats another:

| | |
|---|---|
| [`NOTES.md`](NOTES.md) | what is missing, weak or open **now** |
| [`DEVLOG.md`](DEVLOG.md) | what changed, when, and why — newest first |
| [`portability.md`](portability.md) | running the kit from a seat other than Claude Code, and the dispatch design that follows |
| [`harness/README.md`](harness/README.md) | the harness: what is in it, how a project adopts it, what is deliberately left out |
| [`workflow.md`](workflow.md) | the method as it runs on the KIT, in order — setup, plan, Foundation, stages, the loop — as one diagram and a table of steps |
| [`harness/roster.md`](harness/roster.md) | who acts in a build — every review, gate and dispatch — from which role, on which model |
| `CLAUDE.md` | working rules for an agent changing this repository |

That separation is not tidiness. Two of these carried the same forward-looking list for a
while and both went stale; the rule now is that a fact lives in one of them and the others
link to it.

## Before anything: three installs, then the doctor

The KIT guides and does not install. Put these on the machine yourself - each is one page and one
or two commands - then let `bb doctor` say what is still missing and what fixes it.

| Install | Version | Route |
|---|---|---|
| **JDK 21** | major 21, exactly - the one version the KIT holds to (XTDB, an optional store, documents a minimum of 21 and its early v2 releases failed at class-load on newer JDKs) | [Temurin 21](https://adoptium.net/temurin/releases/?version=21); on macOS `brew install --cask temurin@21` |
| **Clojure CLI** | any current release | [clojure.org/guides/install_clojure](https://clojure.org/guides/install_clojure); on macOS `brew install clojure/tools/clojure` |
| **Babashka** | 1.12.212 or newer (bbin needs it) | [babashka.org](https://babashka.org) → install; on macOS `brew install borkdude/brew/babashka` |

Every other version is a floor, not a pin. `bb doctor` shows what is installed beside the KIT's
dated known-good set (`harness/resources/known-good.edn`, the versions that were actually run
together) and calls a newer version *newer than tested* - information, not a fault.

```bash
bb doctor     # from the root of the clone: every tool, its version, and for anything unusable
              # the command or page that fixes it. Run it, follow it, run it again - until both
              # verdicts say yes: "The KIT's gates can run here" and "A loop can run here"
bb health     # then, once: is this KIT healthy here, with the template it pins? Minutes, starts JVMs:
              # the selfcheck project and a freshly generated application, gates green, gates failed on purpose,
              # one task through the loop, the application served
bb init xyx   # then: the workspace for a project - see harness/README.md
bb gates      # the KIT's own gates: doctor -> format -> lint -> rules -> reports -> test

cd harness
bb example    # the whole loop shape in one run — no model calls, no network
```

The root `bb.edn` is a front door: it offers the tasks that take no path argument and hands each
to `harness/`, where the harness and the rest of its tasks live.

`bb example` is the 60-second tour: it assembles a task packet, shows the Tester's context
being stripped of the implementation, dispatches to a scripted runner, repairs the output,
runs the gates until one fails, and checks the runner against the contract.

## Where it was last shown to work

Rendered by `bb health-sync` from `harness/health/records/`, one record per platform, each
written by `bb health --record` after a run in which every check passed: the selfcheck project and an
application generated from the pinned template, gates green, every gate failed on purpose, one
task through the loop, the application served. `bb gates` fails if this block and the records
disagree. The date is the claim; nothing here says it still holds today.

<!-- health:begin -->

| Platform | Run on | KIT commit | Template | Checks | Time |
|---|---|---|---|---|---|
| macOS 26.5.2 arm64 | 2026-09-28 | `3a49a18` | `kit-v1` (`a2c0eaa`) | 7 of 7 ok: selfcheck gates, selfcheck red, selfcheck loop, app gates, app red, app loop, app serve | 59s |

One record per platform actually run, the latest run on it; a platform not in the table has none. `bb health --record` on such a machine writes one - commit it, and `bb health-sync`.

<!-- health:end -->

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

[`harness/PROVENANCE.md`](harness/PROVENANCE.md) records what was extracted from
the private harness it came from and every place this copy deliberately diverges — each with
its reason. It is a **fork, not a mirror**; "sync with upstream" is not a supported
operation, and the divergences are fixes rather than drift.

## Licence

[MIT](LICENSE). Six rules in the harness's rule source are adapted from
[github/awesome-copilot](https://github.com/github/awesome-copilot) (MIT), and the application
template the KIT pins is a fork of [abogoyavlensky/clojure-stack-lite](https://github.com/abogoyavlensky/clojure-stack-lite)
(MIT); see [`NOTICE`](NOTICE) for the attributions and the upstream licence texts. An application
generated from it carries no licence file: choosing one is its owner's first decision.
