# Stage <N> — <Name>, pre-release

**Status:** PLANNED — for the person's approval, with its cap (§7)
**Kind:** pre-release
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>
**Companion to:** `../01-requirements.md` §7 · `../02-architecture.md` §15 · `../04-decision-log.md` · `../05-lessons.md`

> A **pre-release stage** holds what release needs and nothing a user-visible stage needed
> earlier (`method.md` §04): the host and the publish command; backup and restore; metrics,
> logging, auditing; the pre-publish gate. One stage or several, each with its own gate, pulled
> like any other. Host-neutral here: the method names no host, as the loop names no framework;
> this plan names the project's. Delete this note.

---

## 1. Goal

> One paragraph. Which share of what release needs this stage takes, and why now - the first
> release this prepares, and what of the list is left to a later pre-release stage.

## 2. Requirements this stage adds

> Most non-functional requirements are written here, when pulled, like any requirement: the
> availability and recoverability bar, the observability bar, the security engineering, the
> accessibility bar. Permanent IDs; each with its source observation or `(inferred)`.

| # | Requirement | Source | Priority |
|---|---|---|---|
| NFR-n | | | |

## 3. What this stage takes

| Need | This stage | Where it is decided (D-n) | Notes |
|---|---|---|---|
| The host | <which; and that the method says nothing about it> | | |
| The publish command | <one command, kept with the release> | | |
| Backup | <what, where, how often> | | |
| Restore | <the command, and that it is rehearsed here> | | |
| Metrics | | | |
| Logging | <what release needs; a stage's own debugging logs landed in that stage> | | |
| Auditing | | | |
| The pre-publish gate | <what must be true of the application before anything is published: every page signed off, the go-live checklist's rows done, …> | | |
| The threat model | <every line of `../02-architecture.md` §15 answered, and how each is shown> | | |

## 4. The deploy path, rehearsed

> Against a throwaway host the project names - a container, a scratch machine - before the
> real one: the publish command run against it, the application answering there, the restore
> rehearsed on it. The script and its output kept beside the stage's records.

## 5. Dependency-ordered task list

1. …

## 6. Decisions exercised by this stage

| Topic | Decision (see `../04-decision-log.md`) |
|---|---|
| <The host> | |

## 7. Exit criteria (the stage's gate), and the cap

**Cap:** <$>, approved by the person on <YYYY-MM-DD>. Recorded in `stage-N-gates.edn`.

- The deploy path rehearsed against the throwaway host, and the application answered there
- A restore rehearsed: a backup taken, the data removed, the backup restored, the application
  answering with it
- The pre-publish gate written as a check a person can run, and run green
- **The threat model signed:** every line of `../02-architecture.md` §15 answered - by a line
  of its §4, a decision, or this stage's work - each with its evidence beside it, and
  `:security/signed` set in `stage-N-gates.edn` by the person; a line that cannot be answered
  this release is a decision that says so, never a line left blank
- **The security pack green on the deploy:** the KIT's security pack run against the rehearsed
  host (`--running --base <its url>`, so TLS is tried), with `../security-routes.edn` where the
  project has one; `stage-N-security.edn` beside this plan with no failing row, and its
  dependency and secrets scans' records beside it with none either; each warn read and answered
  in §15 or a decision
- **Deployed locally** still: the browser checks and the owner's walk against the local build
- **Decision-log updates recorded**; this stage's section of `../05-lessons.md` written; the scope
  lists and the stage map revised; the next stage named - another pre-release stage, or the release

## 8. Residual risks / feeds into the next stage
