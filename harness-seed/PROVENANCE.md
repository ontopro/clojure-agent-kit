# Provenance

Extracted 2026-09-10 from `thub-harness`, a private multi-agent orchestration
harness that ran this loop across dozens of real dispatches.

| seed file | source file | source commit |
|---|---|---|
| `src/harness/shapes.clj` | `src/thub/harness/shapes.clj` | `978ff9e` (2026-07-10) |
| `src/harness/packet.clj` | `src/thub/harness/packet.clj` | `e6b40ed` (2026-07-10) |
| `src/harness/gates.clj` | `src/thub/harness/gates.clj` | `5df04ad` (2026-09-05) |
| `src/harness/repair.clj` | `src/thub/harness/gates.clj` | `5df04ad` (2026-09-05) |
| `src/harness/runner.clj` | `src/thub/harness/runner.clj` | `3dd2229` (2026-07-10) |
| `src/harness/rules.clj` | `src/thub/harness/rules.clj` | `5df04ad` (2026-09-05) |
| `resources/agent-rules.edn` | `resources/agent-rules.edn` | `5df04ad` (2026-09-05) |
| `agents/interactive-programmer.md` | `.claude/agents/clojure-interactive-programming.md` | working tree |
| `src/harness/doctor.clj` | — | written for the seed |
| `src/harness/runner_check.clj` | — | written for the seed |

## This is a fork, not a mirror

**"Sync with upstream" is not a supported operation.** The seed has deliberately
diverged, and each divergence has a reason:

- **`AgentResult` is closed, with a `:runner/meta` hatch.** Upstream it is open
  and carries five keys it never declared (`:session-id`, `:verdict`, `:capped?`,
  `:provider`, `:tokens`).
- **`GateEntry` / `GateResult` schemas exist here and not upstream**, so
  `:skipped` entries now carry `:exit nil :out ""` instead of omitting the keys.
  Upstream, three test files re-declare that shape by hand.
- **`run-gate` catches `IOException`** and returns exit `127`. Upstream a missing
  gate binary throws — `:continue true` suppresses a non-zero exit, not a missing
  program.
- **`gates/failure` is new**, because upstream synthesizes that exact map by hand
  in two places.
- **Gate 0 moved to its own namespace** (`harness.repair`), so `harness.gates`
  depends on nothing but a shell and the stack-specific part has one home.
- **`Gates` drops `:cmd`.** Dead upstream: one writer, zero readers.
- **`example-packet` was promoted** from a test fixture into `src`.
- **The rule source is genericised** — upstream's layer names, project-specific `ex-info` type
  and datastore references are replaced with `<angle-bracket>` prompts. A `:precedence`
  rule is **added**, which upstream does not have: it states that the rules outrank any
  personal or global instruction, because rule files merge silently and upstream currently
  has a live collision between a global *"run `bb test`"* and its own *"never run the
  gates"*.
- **Vocabulary: upstream says "corpus", the seed says "rule source".** Renamed because
  "corpus" collides with its NLP meaning (a body of text for training) in a document
  entirely about LLM agents. Same artifact, same shape — do not "fix" it back.
- **`ManualRunner`'s REPL hint is injectable** rather than hardcoding one bridge.
- **`rules/-main` defaults to `CLAUDE.md` in the cwd** rather than a sibling checkout, so
  the seed's drift gate needs nothing outside itself.

## Third-party provenance

Rules marked `:from :awesome-copilot` in `resources/agent-rules.edn` are adapted from
[github/awesome-copilot](https://github.com/github/awesome-copilot) (MIT) —
`instructions/clojure.instructions.md` and `agents/clojure-interactive-programming.agent.md`.
**Adapted, not copied:** that source assumes Calva and `add-libs`, both wrong here, since
this method runs a per-worktree nREPL and pins dependencies behind a gate.

## Why the ancestry is recorded at all

The risk is not that you have a stale copy — you are *meant* to edit this
immediately. The risk is that someone later finds both copies, tries to
reconcile them, and re-introduces the defects listed above.

If you do have the source checkout and want to see what else changed:

```bash
git -C ../../thub-harness show 978ff9e:src/thub/harness/shapes.clj \
  | diff - src/harness/shapes.clj
```

## Not extracted, deliberately

`coder.clj` / `tester.clj` / `reviewer.clj` (client-specific: CLI flags, JSON
scraping, a 226-line tool-call loop — stale within months) · `orchestrate.clj`
(where the client-specific leakage lives) · `triage.clj` (hardcodes gate keys and
encodes one project's routing opinion) · `worktree.clj`, `log.clj`, `llm.clj`
(project infrastructure).
