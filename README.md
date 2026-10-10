# Clojure Agent Kit (aka "KIT")

**An opinionated, reusable way to build Clojure software with a small team of independent
AI agents** — a contract-first blueprint, an isolated dispatch loop, a living decision log,
and quality gates ordered cheap-to-expensive.

Distilled from a live multi-agent build that ran this loop across dozens of real dispatches,
including several it got wrong. What's here is the discipline that survived contact with
those runs, not a design done on paper.

**KIT** is the short form of *the Clojure Agent Kit*, and is how these documents refer to it.
It is a name, not an acronym. The application template it brings with it is
[`ontopro/clojure-stack-lite`](https://github.com/ontopro/clojure-stack-lite), always named with
the tag KIT pins, as in `ontopro/clojure-stack-lite` at tag `kit-v1.2`: those tags version the
template, not KIT.

> **Clone it and build beside it.** KIT is what a project adopts: its clone sits in the
> project's workspace next to the application it generates from a pinned template, upgraded
> with `git pull`, and nothing of the project is written into it; `bb doctor` run in the
> workspace then says what the pulled KIT expects that the workspace, made at an earlier commit,
> lacks. KIT carries a version tag at each plan's boundary - `0.6.2` is the current one, and
> the DEVLOG's heading for it says in a line what a workspace made before it needs; `bb init`
> records the version beside the commit. There is no library to require; there is one commit
> of one template that a dated health check certifies with it.

## For adopters

What is in the clone - the method, the plan template, the harness, the tools, the skills - is
listed under [Five parts](#five-parts), in the section for KIT developers below.

### Before anything: three installs

KIT guides and does not install. Put these on the machine yourself - each is one page and one
or two commands - then let `bb doctor` say what is still missing and what fixes it.

| Install | Version | Route |
|---|---|---|
| **JDK 21** | major 21, exactly - the one version KIT holds to (XTDB, an optional store, documents a minimum of 21 and its early v2 releases failed at class-load on newer JDKs) | [Temurin 21](https://adoptium.net/temurin/releases/?version=21); on macOS `brew install --cask temurin@21` |
| **Clojure CLI** | any current release | [clojure.org/guides/install_clojure](https://clojure.org/guides/install_clojure); on macOS `brew install clojure/tools/clojure` |
| **Babashka** | 1.12.212 or newer (bbin needs it) | [babashka.org](https://babashka.org) → install; on macOS `brew install borkdude/brew/babashka` |

Every other version is a floor, not a pin. `bb doctor` shows what is installed beside KIT's
dated known-good set (`harness/resources/known-good.edn`, the versions that were actually run
together) and calls a newer version *newer than tested* - information, not a fault.

### How to use KIT
There are basically 2 steps to use KIT:
- A. **setup** - to set up the KIT adopter's workspace
- B. **build** - to build the adopter's application

#### A. Set up workspace
1. Set up your development environment as described in the [Before anything: three installs](#before-anything-three-installs) section above.
2. Create project folder `<xyx>`. This will be the main working directory where all the work will be done.
    - recommend using a short name for the `<xyx>`.
    - the name must be lowercase letters, digits and single hyphens, starting with a letter -
      for example `my-site` - because `bb init` takes it as the project's name, and it becomes
      folder names and the application's namespace.
    - It will have a number of directories and files but only one of which you will manually create in step 3.

3. Open a terminal and `cd` into the newly created `<xyx>`. Clone this repo into it with:

    ```shell
    git clone https://github.com/ontopro/clojure-agent-kit.git KIT
    ```

    - **KIT** is a clone of the **Clojure Agent Kit (aka KIT)** and it will be guiding you through the product build, test and deployment process.
4. Next, do the following steps:

    - `cd` into the KIT directory.
    ```shell
    cd KIT
    ```

    - Run `bb self-check` for KIT to do a full diagnostics check on itself. It does 2 things:
      - `bb doctor` (for environment check)
      - `bb health` (this KIT, this machine, the pinned template - minutes, starts JVMs)
    ```shell
    bb self-check
    ```

    - Initialize the workspace for the build process.
      By default `init` will use the `<xyx>` to create the
      `<xyx>-app` and `<xyx>-build` plus a few other
      directories and files in the parent `<xyx>` folder.
    ```shell
    bb init
    ```

    - Move back to the `<xyx>` where you will be doing all the work from.
    ```shell
    cd ..
    ```

At this point you are all set to build your application.

#### B. Build the application

- Make sure you are in the `<xyx>` directory.
- Start a Claude Code session and type `/kit-start`.
- Claude walks you through:
  - the workspace directory structure
  - the approach: the opening of KIT's [`method.md`](method.md), then the steps in order from [`workflow.md`](workflow.md)
  - the roles and the models they run on - [`harness/roster.md`](harness/roster.md) has the when and why - and the
    model and effort it recommends for this session, the Architect's
  - the first step: scoping, in the Plan phase

### Where it was last shown to work

Rendered by `bb health-sync` from `harness/health/records/`, one record per platform, each
written by `bb health --record` after a run in which every check passed: the selfcheck project and an
application generated from the pinned template, gates green, every gate failed on purpose, one
task through the loop, the application served, a headless Firefox opening it through KIT's browser
pack (`tools/browser/`) where geckodriver and Firefox are installed (skipped, and the row says so,
where they are not), and KIT's security pack (`tools/security/`) trying from outside what the
plan template's architecture document says the template gives, and asking about every library it
ships and every line of its history. `bb gates` fails if this block and the records
disagree. The date is the claim; nothing here says it still holds today.

<!-- health:begin -->

| Platform | Run on | KIT commit | Template | Checks | Time |
|---|---|---|---|---|---|
| Linux Ubuntu 24.04.5 LTS arm64 | 2026-10-09 | `6b4759c` | `kit-v1.2` (`9ca4b7b`) | 9 of 9 ok: selfcheck gates, selfcheck red, selfcheck loop, app gates, app red, app loop, app serve, app browser, app security | 85s |
| macOS 27.0.1 arm64 | 2026-10-09 | `6b4759c` | `kit-v1.2` (`9ca4b7b`) | 9 of 9 ok: selfcheck gates, selfcheck red, selfcheck loop, app gates, app red, app loop, app serve, app browser, app security | 83s |

One record per platform actually run, the latest run on it; a platform not in the table has none. `bb health --record` on such a machine writes one - commit it, and `bb health-sync`.

<!-- health:end -->

### Is it worth it?

It pays for itself when a project has enough scope to amortise setting the process up once,
and a correctness bar worth a dedicated, independent verification step. For a weekend build
or a single-session prototype, skip to a plain coder loop plus gates plus one human review —
the full role model and the decision log are overhead you don't need yet.

It scaffolds on stock
[Clojure Stack Lite](https://github.com/abogoyavlensky/clojure-stack-lite) (HTMX, AlpineJS,
TailwindCSS, SQLite/PostgreSQL), with [XTDB v2](https://xtdb.com) as a SQL-compatible
alternative datastore.

### Known limitations

[`NOTES.md`](NOTES.md) is the honest state of KIT — what is deliberately deferred, and
where the weak points are.

## For KIT developers

### Five parts

| | What it is |
|---|---|
| **[`method.md`](method.md)** | The method. Three phases — Plan, Foundation, Stages — run as one lean-agile discipline: lean decides *what* to build, agile decides *how*, and the flow is Kanban (pulled, WIP-limited, no timeboxes). Roles, the task-packet contract, the rule source, the decision log, the gate order, and a field guide of twelve lessons each bought with a real run. |
| **[`plan-template/`](plan-template/)** | The plan template, half-written on purpose — the source a plan derives from, an overview with a ranked risk register, requirements with MVP/post-MVP scoping, architecture and method-and-tooling each in three parts (GIVEN by adopting KIT, CHOSEN once in Foundation, THEIRS the domain), the decision log, and just-in-time stage docs. `bb init` copies it into the project's plan repository, beside the rules overlay, the profile and the run records that repository also holds, and `bb plan-check` reads the filled plan before Foundation. |
| **[`harness/`](harness/)** | The code that runs, and its health check's first subject (`health/selfcheck/`, a deliberately trivial project with gates that execute and fixtures that make each fail): the doctor, `bb init` and the health check; the two readings of a filled plan (`bb plan-check`, the gate; `bb plan-review`, the model's pass); the packet assembler, the gate runner, gate 0 and the boundary gate, three-worktree provisioning, an API-backed runner, a per-run cost report that names the three commits it ran against, the rule source with a drift gate, a bake-off that compares candidates for a role with a judge reading blind, and the loop that drives them — stopping for a person at every branch it cannot decide. [Its own README](harness/README.md) is the inventory. |
| **[`tools/`](tools/)** | Tool packs a project runs from KIT's clone, each with its own `bb.edn` and dependencies, against the project's own server whatever its framework — the way a project already runs the boundary gate. The first is `tools/browser/`: the stage-end checks in a real browser (screenshots as tall as the page with the overflow measure, an axe-core scan, the serve-check-stop skeleton), Etaoin under Babashka driving a headless Firefox through geckodriver. Beside it, `tools/svg/` checks the SVG files a project draws, and `tools/security/` tries from outside what a request can - the headers, the cookies, a POST without its token, the routes that need a login, the error pages, the static folders, TLS - at every stage's end and in `bb health`. [Its README](tools/README.md) is the list. |
| **[`skills/`](skills/)** | One skill per step of the workflow that is a conversation with a person and has a defined output - `scoping`, `plan`, `stage-plan`, `stage-end`, and beside them `kit-start`, the orientation a project's first session begins with - in one anatomy (when it runs, reads, refuses when, asks, writes, done when), each a thin layer over the method section it cites. Claude Code's mechanism, loaded from `.claude/skills/`: `bb init` renders them into a workspace's, where `scoping` is the first conversation, and `bb skills-sync --check` holds both to the source and every cited heading to the method. The other seats follow the same sections by hand. [Its README](skills/README.md) says the rules. |

**Each of the repository's own documents has one job**, and none of them repeats another:

| | |
|---|---|
| [`NOTES.md`](NOTES.md) | what is missing, weak or open **now** |
| [`DEVLOG.md`](DEVLOG.md) | what changed, when, and why — newest first |
| [`portability.md`](portability.md) | running KIT from a seat other than Claude Code, and the dispatch design that follows |
| [`harness/README.md`](harness/README.md) | the harness: what is in it, how a project adopts it, what is deliberately left out |
| [`workflow.md`](workflow.md) | the method as it runs on KIT, in order — setup, scoping, the plan for stage 0, stage 0 with Foundation inside it, the stages with their four steps and their reviews, the loop, pre-release and release — as one diagram and a table of steps |
| [`harness/roster.md`](harness/roster.md) | who acts in a build — every review, gate and dispatch — from which role, on which model |
| `CLAUDE.md` | working rules for an agent changing this repository |

That separation is not tidiness. Two of these carried the same forward-looking list for a
while and both went stale; the rule now is that a fact lives in one of them and the others
link to it.

### The commands

```bash
bb self-check # from the root of the clone: bb doctor, then - when it says yes - bb health
bb doctor     # from the root of the clone: every tool, its version, and for anything unusable
              # the command or page that fixes it. Run it, follow it, run it again - until both
              # verdicts say yes: "The KIT's gates can run here" and "A loop can run here"
bb health     # then, once: is this KIT healthy here, with the template it pins? Minutes, starts JVMs:
              # the selfcheck project and a freshly generated application, gates green, gates failed on purpose,
              # one task through the loop, the application served, a Firefox opening it (skipped, and said, with no geckodriver)
bb init       # then: the workspace, named after the folder the clone is in - see harness/README.md
bb gates      # KIT's own gates: doctor -> format -> lint -> rules -> reports -> test

cd harness
bb example    # the whole loop shape in one run — no model calls, no network
```

The root `bb.edn` is a front door: it offers the tasks that take no path argument and hands each
to `harness/`, where the harness and the rest of its tasks live.

`bb example` is the 60-second tour: it assembles a task packet, shows the Tester's context
being stripped of the implementation, dispatches to a scripted runner, repairs the output,
runs the gates until one fails, and checks the runner against the contract.

### Where things live

- **The clone.** A workspace's clone of KIT is `KIT/` by the README's steps, but the name is not
  fixed: `workspace.edn` records the folder, so it may be renamed, or kept outside the workspace
  and pointed at it with `KIT_WORKSPACE` or `--workspace <dir>`.
- **The template.** `ontopro/clojure-stack-lite` is KIT's fork of the upstream template: its `kit`
  branch is KIT's line of it, tagged `kit-v1`, `kit-v1.1`, `kit-v1.2`, …, and its `master` is an
  untouched mirror of the upstream.

## Provenance

[`harness/PROVENANCE.md`](harness/PROVENANCE.md) records what was extracted from
the private harness it came from and every place this copy deliberately diverges — each with
its reason. It is a **fork, not a mirror**; "sync with upstream" is not a supported
operation, and the divergences are fixes rather than drift.

## Licence

[MIT](LICENSE). Six rules in the harness's rule source are adapted from
[github/awesome-copilot](https://github.com/github/awesome-copilot) (MIT), and the application
template KIT pins is a fork of [abogoyavlensky/clojure-stack-lite](https://github.com/abogoyavlensky/clojure-stack-lite)
(MIT); see [`NOTICE`](NOTICE) for the attributions and the upstream licence texts. An application
generated from it carries no licence file: choosing one is its owner's first decision.
