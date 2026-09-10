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
{:task/id       "t-01-<slug>"
 :task/role     :coder
 :contract      {:shapes     [<Shape>]
                 :interfaces '[(<sig>)]
                 :deps-sigs  '[(<upstream sig it may CALL — never its source>)]}
 :files/target  ["src/app/<ns>.clj"]
 :files/context ["src/app/<dep>.clj"]
 :layer/name    :<layer>
 :workspace     {:repl/port <n> :dir "<wt-01>"}
 :gates         {:cmd "bb gates" :retry-cap <n>}}
```

**Acceptance:** <what "done" means for this task, beyond green gates>

> Per-role deltas from the same base packet: the **Tester's** `:files/context` **omits the
> Coder's implementation file** — enforce this in the packet assembler, not by discipline.
> The **Reviewer** gets a diff, the contract slice and the green gate report; no target file.

## 5. Spike gates run by this stage

| Spike | Gates | Pass criterion (written before it runs) | On failure |
|---|---|---|---|

## 6. Risks and deliberate simplifications

**Deliberately NOT designed in this Blueprint** (deferred per the stage doc §3):

**Riskiest tasks:**
