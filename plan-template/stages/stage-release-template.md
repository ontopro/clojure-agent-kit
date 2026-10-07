# Stage <N> — <Name>, release

**Status:** PLANNED — for the person's approval, with its cap (§6)
**Kind:** release
**Version:** 0.1 · **Last updated:** <YYYY-MM-DD>
**Companion to:** the pre-release stage plans before it · `../04-decision-log.md` · `../05-lessons.md`

> The **release stage** packages, then deploys on the server or publishes for download, and
> returns to the next increment (`method.md` §04). It repeats: a later release has a smaller
> pre-release before it, and the second release meets the first one's data. Its blueprint may
> be a single packet, the publish command and its checks, and that is still one blueprint and
> one spec. Delete this note.

---

## 1. Goal

> One paragraph. Which increments this release carries (the first shippable one is the MVP,
> named in the stage map when it came), to whom, where.

## 2. Package, then deploy or publish

| Step | Command | Kept where |
|---|---|---|
| Package | <the command that produces the artifact> | |
| Deploy on the server, or publish for download | <the command> | |
| Roll back | <the command that puts the previous release back> | |

## 3. The changelog

> An entry in the user's terms - what they can do now that they could not - with an
> **Upgrading** paragraph when there is one: what of the previous release's data and
> configuration the new one opens, and what it does not.

## 4. Dependency-ordered task list

1. …

## 5. Decisions exercised by this stage

| Topic | Decision (see `../04-decision-log.md`) |
|---|---|
| | |

## 6. Exit criteria (the stage's gate), and the cap

**Cap:** <$>, approved by the person on <YYYY-MM-DD>. Recorded in `stage-N-gates.edn`.

- **The released artifact started as packaged, on an empty home, with the build's caches out
  of reach**, by a command kept with the release - the health-check lesson turned on the
  release: what works in the build's own folder has not been shown to work anywhere else
- **From the second release on: the previous release's data and configuration opened by the
  new one**, with what is kept of the old said in the changelog's Upgrading paragraph
- **A publish re-run after a partial failure skips what is already published and finishes**
- The pre-publish gate green at the moment of publishing
- The rollback named (§2) and rehearsed once
- **The browser checks and the owner's walk against the released address**, as they ran locally
- **Decision-log updates recorded**; this stage's section of `../05-lessons.md` written; the scope
  lists and the stage map revised; the next increment pulled

## 7. Residual risks / feeds into the next increment
