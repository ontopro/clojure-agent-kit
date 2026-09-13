# sandbox

A deliberately trivial Clojure project, checked in so the kit can be run against something
real instead of against temp directories.

```bash
bb gates          # fmt-check -> lint -> test -> deps-check
bb nrepl          # an nREPL on an OS-assigned port, written to .nrepl-port
bb break lint     # make one gate fail on purpose
bb restore        # undo it
```

## Shape

Four namespaces, layered so the packet fields mean something:

```
sandbox.shapes      malli contract      sandbox.bindings   lookup protocol
      │                                        │
sandbox.expr  constructors              sandbox.memory     an implementation
      │                                        │
  ┌───┴────────┬──────────────┐                │
parse        compute        render             │
  └────────────┴──────────────┴────────────────┘
                    sandbox.api   the composition root
```

Two boundaries, both enforced by `bb deps-check` against `layers.edn`:

- **Siblings.** `parse`, `compute` and `render` may not see each other.
- **The protocol seam.** `compute` depends on `sandbox.bindings`, the protocol, and never on
  `sandbox.memory`, an implementation of it. This is the violation rule `:review-scope` tells a
  Reviewer to look for, and `bb break seam` proves the gate catches it.

That is why one file was not enough: `:files/context`, `:deps-sigs` and `:layer/name` in a task
packet are meaningless until one namespace has signatures another must call without reading the
implementation.

Malli validates at both seams — `parse` on the way out, `compute` on the way in — with closed
maps, so a stray key is a contract violation rather than extra data.

The arithmetic is deliberately poor: left-to-right, no precedence, so `1 + 2 * 3` is 9. It is a
fixture, not a calculator.

## Property tests

`test/sandbox/property_test.clj` covers two of method §05's four named property targets, and is
the only place the rule-source conventions about generators are exercised — bound with `def`,
`defspec` without a docstring, no top-level side effects.

| Property | §05 target |
|---|---|
| `parse` after `render` after `parse` equals `parse` | round-trip |
| `canonical` is idempotent | round-trip |
| `calculate` agrees with an independent token fold | cross-surface consistency |
| every parsed expression conforms to the shape | the contract holds |

Verified to discriminate: breaking `render` so it drops the spaces around operators fails both
round-trip properties immediately.

## Why it exists

Two jobs, and they are different.

**A self-check for changes to the kit.** `harness-seed`'s own tests build fixtures in temp
directories. That proves the functions work; it does not prove the harness works against a
project with a `deps.edn`, a JVM, a real nREPL and four gates that actually run. Change
`harness.gates` or `harness.packet` and this is what tells you.

**The thing `method.md` §03 asks for and the kit could not do.** Step 5: *"Run the whole loop
once, end to end, on a deliberately trivial task… Pick something with no design content at
all — a `clamp` function."* And readiness criteria 4 and 6 — *every gate green on the untouched
scaffold*, and *you have run the whole loop end to end on one trivial task*. Until this
directory existed there was nothing to run them against.

**It is not a demo of good Clojure.** The project is the fixture, not the point.

## Deliberate choices

**`.mise.toml` lives here and must never move to the repository root.** `harness-seed` is
bb-only on purpose — it needs no JVM, so a JDK pin it does not have would fail `bb doctor` on
an unrelated machine. `doctor/mise-file` walks up from the current directory, so a root-level
pin would leak into it. Verify with `cd ../harness-seed && bb doctor`: no `[pinned …]` should
appear.

**The gate task keys match `harness.gates/default-gate-seq`** — `:fmt :lint :test :deps`. Those
keys are a public contract: triage dispatches on them and the run log is read by key months
later, so `bb.edn` here has to speak the same vocabulary.

**`deps-check` is real, not a placeholder.** Method §09 gate 4. `dev/deps_check.clj` reads each
`ns` form, extracts what it requires from the sandbox tree, and compares that against
`layers.edn` — failing on a forbidden dependency *or* on a namespace that is not declared at
all. It runs on babashka, so it costs milliseconds. `clj-depend` would be the real tool; this
is a small stand-in, and the point of a fixture is that the gate genuinely fails.

*Known limitation:* an orphan entry — a namespace declared in `layers.edn` with no file —
passes. The check walks files and asks whether each is declared, not the reverse, so a deleted
namespace leaves dead config behind.

**`red/` proves the gates discriminate.** A suite that has only ever been green proves nothing.
Four fixtures, one per gate, each verified to fail its own gate and to short-circuit the rest:

| `bb break …` | fails at | exit |
|---|---|---|
| `fmt` | `:fmt` | 1 |
| `lint` | `:lint` | 2 — clj-kondo uses 2 for warnings, and the gate runner only asks `(zero? exit)` |
| `test` | `:test` | 1 |
| `deps` | `:deps` | 1 |

`bb restore` deletes only files named `red_*`, so it can never touch real source.

**No test-runner dependency.** `dev/test_runner.clj` globs, requires and runs — the same shape
as `harness-seed`'s `bb.edn`. It exits 1 when it finds no test namespaces at all, because a
runner that silently finds nothing and reports green is worse than one that fails.

**`:nrepl` binds port 0** and lets the OS assign, writing the result to `.nrepl-port`. That is
what provisioning will read, and what `clj-nrepl-eval --discover-ports` already looks for — it
removes the bind-then-release race that picking a port yourself creates.

## AGENTS.md

Generated from `harness-seed/resources/agent-rules.edn`, not hand-written:

```bash
cd ../harness-seed && bb rules-sync ../sandbox/AGENTS.md
```

That makes this directory the first real test of the rule source reaching a project other than
the seed itself.
