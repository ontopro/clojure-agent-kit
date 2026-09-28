# Stage <N> Blueprint — <Name>

**Status:** AWAITING HUMAN GATE — no task dispatches until signed off
**Author:** Architect (<model>) · **Date:** <YYYY-MM-DD>
**Input:** `stage-N-<name>.md` · **Method:** `../03-method-and-tooling.md`

> Produced in a fixed order: **shapes → interfaces → namespaces and dependencies →
> dependency-ordered task packets.** This document is read twice, by two agents who never
> compare notes — the Coder implements against it and the Tester derives tests from it.
> That doubling is why it needs unusual precision.

---

## 0. Dispatch preconditions — HUMAN PREP (not task packets)

> Anything a task depends on that no agent can do: an account, a licensed download, a
> signed agreement, a provisioned machine. Naming these up front is cheaper than
> discovering them at attempt one.

| # | Prep | Blocks | Status |
|---|---|---|---|
| HP-0 | | t-0n | |

## 1. Data shapes — the test contract

```clojure
(def Concept
  [:map
   [:id :string]
   ...])
```

> Every field the Tester could generate against needs a stated constraint. A shape that is
> vaguer than the real contract produces tests that pass on wrong code.

## 2. Interfaces — signatures and contracts per namespace

> Beside each seam, answer: **what does everything past this point get to assume?** (validated
> once here; slugs distinct; exactly one home; …) — and put the answer in the rule source, where
> every role reads it, not only here. A guarantee that lives in the Blueprint alone reaches no
> dispatched role, and each of them will object to the input it forbids.

### `app.<ns>` (`:<layer>`)

```clojure
(lookup [store id opts])   ;; => Concept | nil. Throws <nothing>. opts: {:as-of inst}
```

## 3. Namespaces, layers, allowed dependencies

| Namespace | Layer | May depend on |
|---|---|---|

## 4. Dependency-ordered task packets

### t-01 — <title>

```clojure
{:task/id          "t-01-<slug>"
 :task/title       "<what this task delivers, one line>"
 :blueprint/slice  {:shapes     [[<Shape> <schema>]    ; a shape this task DEFINES: inline, as the roles receive it
                                 <Named>]              ; a shape §1 defines: named here, inlined by bb spec-from-blueprint
                    :interfaces [(<fn> [<args>])        ; a function to implement, with its argument vector
                                 (<var>)]               ; a VAR to define: the one-element form
                    :deps-sigs  [(<dep-ns>/<dep-fn> [<args>])]}  ; upstream signatures it may CALL, qualified - never their source
 :files/impl       ["src/app/<ns>.clj"]                 ; the Coder's target: ONE file - a second namespace is a second task
 :files/test       ["test/app/<ns>_test.clj"]           ; the Tester's target
 :files/context    ["src/app/<dep>.clj"]                ; read-only, to every role; the Tester's omits :files/impl
 :layer/name       :<layer>                             ; the boundary gate's layer for :files/impl
 :property-targets ["<one promise, checkable, in full>"] ; method §06's rules; every role receives them
 :gates            {:retry-cap 3}}                      ; optional; 3 is the harness's default
```

> **This is the task spec the harness reads - `spec.edn`, key for key.** `bb spec-from-blueprint`
> in the KIT's `harness/`, given this file, a task id and an output path, pulls the packet out of
> its fence, replaces every shape named under `:shapes` with §1's definition of it (verbatim, in
> §1's order; `(def Name schema)` and `[Name schema]` both read) and writes it validated; a shape
> §1 does not define and a task id this document does not carry are refused by name. Without an
> output path the spec goes to stdout and pipes into `bb sigs`. The driver adds the rest at
> `start`: `:task/role`, `:files/target` (`:files/impl` for the Coder, `:files/test` for the
> Tester), `:repl/worktree` and `:repl/port` from the worktrees and nREPLs it provisions - none of
> that is written here. The schema is `harness.contract.shapes/TaskSpec`, and a test in the KIT
> reads this fence against it.
>
> **An interface is a function or a var, and the form says which.** `(name [args])` is a
> function the Coder writes and the Tester's stub throws from; `(name)` is a VAR the Coder
> defines - route data, a config map, a schema - which the stub leaves unbound. A bare `name`
> is neither, and `start` refuses it by name before anything is provisioned. The same
> one-element form under `:deps-sigs` names a var the task may read.
>
> **Naming a shape under `:shapes` does not grant it.** `:shapes` tells the roles what the data
> looks like; the `calls` gate reads `:deps-sigs` and nothing else. If a target requires the
> implementation to REFER to a shape var — to validate against it, say — that var goes in
> `:deps-sigs` too, as a bare `ns/var`, like any other var from this project the code may touch. A
> slice that granted nothing once cost a red gate and a triage call over code that was right.
>
> **Every `:deps-sigs` entry is qualified**, `(ns/f [args])` or `ns/var`. The `calls` gate grants
> by qualified name, so an unqualified `(f [args])` grants nothing and the first call into that
> namespace is a red gate after two dispatches have been paid for; `bb sigs`, `bb spec-from-blueprint`
> and `start` refuse one by name.

**Acceptance:** <what "done" means for this task, beyond green gates>

**Spec review:** <finding count from `bb run-loop spec-review`, and what was fixed> — a spec that
draws ten findings is not ready; write targets one promise each, as checkable statements, with
marker attributes for anything a test must find (method §06).

> Per-role deltas from the same base packet: the **Tester's** `:files/context` **omits the
> Coder's implementation file** — enforce this in the packet assembler, not by discipline.
> The **Reviewer** gets a diff, the contract slice and the green gate report; no target file.

## 5. Spike gates run by this stage

| Spike | Gates | Pass criterion (written before it runs) | On failure |
|---|---|---|---|

## 6. Risks and deliberate simplifications

**Deliberately NOT designed in this Blueprint** (deferred per the stage doc §3):

**Riskiest tasks:**
