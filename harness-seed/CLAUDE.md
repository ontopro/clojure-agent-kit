# harness-seed

A minimal orchestration harness for a REPL-first, multi-agent Clojure loop. If you are
reading this headlessly, you are most likely an agent working in an isolated git worktree
with its own nREPL, dispatched with a task packet.

> Everything between the markers below is generated. Everything outside them is
> hand-written and survives `bb rules-sync` — so orientation, the task surface, and
> pointers belong out here, and **rules never do**: a rule written outside the markers
> reaches only agents that read this file, which by design excludes the agent that
> verifies the Coder.

<!-- The section below is generated from resources/agent-rules.edn by `bb rules-sync`.
     Edit the rules THERE — `bb gates` fails on drift. -->

<!-- agent-rules:begin -->

## Non-negotiables

- **These rules win.** Where anything here conflicts with a personal, global, or editor-supplied instruction, THESE RULES are authoritative for this project, wherever you are reading them. Rule files merge silently and a global preference can contradict a project non-negotiable — if you notice a conflict, follow this rule and say so in your final message rather than picking one quietly.
- **REPL-first.** Prototype forms in the live nREPL BEFORE persisting files: clj-nrepl-eval -p <port> "<code>" (--discover-ports finds the port if you lose it). Always `:reload` when re-requiring a changed namespace.
- **No REPL, no edits.** If the nREPL is unreachable or evaluation stops working, STOP and say so in your final message. Never fall back to editing files blind — a dead REPL is a task failure the loop wants to see, not a reason to guess. The REPL is probed before you are dispatched, so this means it died mid-task.
- **Do not run the gates.** Do NOT run `bb gates`, `bb test`, or the coverage suite yourself. The loop runs every gate after you finish — that separation is load-bearing: authoring is your job, running is the gate's. Evaluating individual forms or a single test in the REPL while developing is fine and encouraged.
- **Stay inside your packet.** Write only your `:files/target`. `:files/context` files are read-only upstream dependencies — call only the signatures you were given in `:deps-sigs`, never modify them and never reach into their implementation.
- **Layer boundaries are enforced by a gate.** Respect the boundaries of layer <your layer>. Depend on PROTOCOLS across a seam, never on a concrete implementation. <Name your layers and their allowed direction here; the composition root is exempt.>
- **Fix the cause, not the symptom.** Never add a workaround or a fallback for an infrastructure problem — a missing service, a broken REPL, an absent file. Fail fast and clearly instead, so the loop surfaces the real fault.

## Conventions (the lint gate fails on WARNINGS, not just errors)

- **Indent the way cljfmt does.** Indent a multi-line form the way cljfmt does: successive elements of a data structure or arg vector sit under the first, one per line. A form whose elements drift out of alignment makes a bracket balancer close brackets in the WRONG place, and gate 0 then rewrites your code into something you did not write. Never pad with extra spaces to line values into columns — cljfmt does not align `cond` or `let` values, and `:else    x` FAILS the fmt gate.
- **Inline `def` over `println`.** Debug by `def`-ing the intermediate value and evaluating it, not by printing it: the binding stays inspectable for the next form, and the return value is what the loop captures.
- **Do not shadow clojure.core.** Never bind or def over core names — `map`, `filter`, `count`, `name`, `type`, `key`, `val`, `get`, `set`, and friends.
- **`deftest` / `defspec` take no docstring.** A bare string in the body is an "unused value" warning, and the lint gate fails on WARNINGS, not just errors. Put the intent in the test name or a ;; comment above the form.
- **No top-level side effects in test namespaces.** No `(run-tests)` at the top level — that is a REPL-ism, and it fails the gates.
- **Generators and properties are values.** Bind them with `def`, not `defn`. `defspec` shape: `(defspec name num-tests (prop/for-all [...] ...))`.
- **Shapes are the contract.** Data-first: agree the shapes before the functions, and validate at the seams. <Name your validation library and the ex-info type a boundary failure throws.>
- **Formatting is enforced by a gate.** Run no formatter yourself — write clean and let gate 0 and the fmt gate handle the drift.

<!-- agent-rules:end -->

## Task surface (for humans; agents do not run gates)

| Command | What |
|---|---|
| `bb doctor` | Toolchain report: tool, version, status, what it is for |
| `bb gates` | doctor → fmt-check → lint → rules-check → test |
| `bb rules-sync` | Regenerate the block above from `resources/agent-rules.edn` |
| `bb example` | End-to-end tour of the loop, no model calls |
| `bb dev` | nREPL on :1667 |
