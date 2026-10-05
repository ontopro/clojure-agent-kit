# tools/browser/ - the stage-end checks in a real browser

What the tests verify as an HTTP answer, a browser proves or disproves: a page that renders
and does not overflow, a control a keyboard reaches, a rule an accessibility scan can apply
(`method.md` §04). This pack runs those checks with Etaoin under Babashka, driving a headless
Firefox through geckodriver. No Node and no npm, and nothing of any framework: the command
that serves and the path that says the server is up are flags.

Run it from the application's folder, through the pack's own `bb.edn`:

```
bb --config <kit>/tools/browser/bb.edn check [--serve "<cmd>"] [--health <path>] [--base <url>]
     [--out <dir>] [--running] [--only screenshots|accessibility] [--width N]... [path ...]
```

`<kit>` is the KIT's clone - the path a workspace's `loop.edn` already carries for the boundary
gate. Defaults are the Stack Lite template's: `bb serve`, `/health`, `http://localhost:8000`.
A Biff application is `--serve "clj -M:dev" --base http://localhost:8080`; a bare deps project,
whatever starts it.

| Task | What | Writes |
|---|---|---|
| `check [path ...]` | serves the application, runs the two below on the paths, stops it | everything below |
| `screenshots [--width N]... [path ...]` | one screenshot per page and width, as tall as the page; `scrollWidth` against `innerWidth` | `<width>/<slug>.png`, `measurements-<width>.edn` |
| `accessibility [path ...]` | an axe-core scan of each page | `accessibility.edn`, `accessibility.md` |

Where it writes: `--out`, else `BROWSER_OUT`, else the workspace's `work/browser/<app>/` when
the application is in a KIT workspace (scratch, in no repository), else `.local/browser/`.
The outputs a committed document cites are copied beside it, not left there: a published
figure nobody can find is a claim.

`driver.clj` is the shared driver, and a project's own interaction checks stand on it too:
`(load-file "<kit>/tools/browser/driver.clj")` gives `with-firefox`, `open-page` (waits for
the document and, where the page loads them, HTMX and Alpine), `measure`, `fit-height!` and
the places the scripts write. `server.clj` is the serve-check-stop skeleton a stage-end script
of any kind stands on: `with-server` starts, waits, runs, stops - the process tree first, then
whatever still listens on the port.

**Needs:** geckodriver and Firefox on the machine. `bb doctor` has the geckodriver row and the
install route; Firefox comes from mozilla.org. axe-core (MPL-2.0) is fetched once into the
output folder and never committed. `bb health` runs this pack's `check --only screenshots /`
against the generated application, once, so a machine that can start a browser is certified
and one that cannot is told.

## Three things to know

- **The permission is the terminal application's.** macOS grants the right to use Firefox to
  the application that asks. A shell under a daemon gets no question and a silent refusal:
  Firefox starts, never launches its content process, and geckodriver gives up after 60 s.
  `geckodriver --version` passes all the same. Run the checks from a terminal once and answer
  the prompt.
- **A screenshot is of the viewport.** The window is made as tall as the page before the shot.
- **Key actions leak between checks.** A key held by one check has been seen to change the
  focus behaviour of the next even after `release-actions`; `open-page` releases and goes
  through a blank page, and a script with interaction checks runs each in a browser of its own.

## What is the project's

The interaction checks - the thing its tests prove only as an HTTP contract, exercised in the
browser: a search typed into, a menu opened, a form submitted - and any keyboard walk. The scan
finds what a rule can find, not what a person finds by using the page. Those are scripts in
the project, built on this driver, named in its plan's `03-method-and-tooling.md` §17.
