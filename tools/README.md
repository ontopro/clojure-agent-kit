# tools/ - tool packs a project runs from the KIT's clone

The fourth part of the KIT, beside the method, the plan template and the harness: checks and
helpers the method asks for that are nobody's framework's. Each pack is a folder with its own
`bb.edn` - its own dependencies, so the harness's stay what they are - run from a project's
folder against that project:

```
bb --config <kit>/tools/<pack>/bb.edn <task> [flags] [args]
```

the way a project already runs the boundary gate (`bb --config <kit>/harness/bb.edn
boundary`). `git pull` in the clone upgrades every project's packs at once; nothing is copied
into a project, and the KIT installs nothing: what a pack needs on the machine, `bb doctor`
names.

WHY HERE AND NOT IN THE TEMPLATE. The loop never looks inside a framework: a project generated
from the pinned template, a project that brought its own application, and a project on another
framework all run the same harness. The checks the method asks for at a stage's end are the
same for all three, so they live where all three can reach them. The template once carried
the browser checks for a day; it reached one kind of project of the three, and came out again.

| Pack | What | Needs on the machine |
|---|---|---|
| [`browser/`](browser/README.md) | the stage-end checks in a real browser: screenshots as tall as the page with the overflow measure, an axe-core accessibility scan, and the serve-check-stop skeleton a stage-end script stands on. Etaoin under Babashka, a headless Firefox through geckodriver | geckodriver, Firefox |
| [`svg/`](svg/README.md) | a check of the SVG files a project produces - the icons it draws or recolours by script: each rasterised with Apache Batik in the JVM, no browser; renders, draws something, and how much of it is the colour asked for | a JDK (the loop's); Batik is fetched on the first run, about ten megabytes |
| [`security/`](security/README.md) | the security checks a request can try, sent to the running application from outside: response headers, cookie flags, a POST without its token, the routes that need a login (a list the project types), error pages that name nothing of the error, static folders that neither list nor let a path out, TLS on an https base; a table, a record a stage cites, exit 1 on a failing row. Serves through the browser pack's `server.clj`. Beside it two scans over the source, `deps` (the resolved libraries' published advisories, from OSV) and `secrets` (a secret in the commits' added lines, its value never printed) | nothing beyond `bb`; the browser pack beside it; `deps` needs the Clojure CLI and the network |

A pack is optional and says what it is for; the plan template's `03-method-and-tooling.md`
§14 and §17 are where a project names the ones it uses.
