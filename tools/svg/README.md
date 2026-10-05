# tools/svg/ - a check of the SVG files a project produces

The icons a project draws or recolours by script are files nobody looks at one by one until a
person does at a stage's end. This pack looks at them first, in the JVM and with no browser:
each file is rasterised with Apache Batik, and the check says whether it rendered, whether
anything is drawn, and - when a colour is given - how much of what is drawn is that colour.

```
bb --config <kit>/tools/svg/bb.edn check [--out <dir>] [--size N] [--color #rrggbb] <file-or-dir>...
```

A folder is searched for `*.svg`. Each file is rendered `--size` pixels wide (default 256) to
`<out>/<name>.png`, and `svg-check.edn` and `svg-check.md` hold the rows. The exit code is 1
when a file does not render or draws nothing; a colour share is reported and never judged,
since what share is right - an icon all one brand colour, a diagram mostly not - is the
project's to say. Where it writes: `--out`, else `SVG_OUT`, else the workspace's
`work/svg/<app>/` when the application is in a KIT workspace, else `.local/svg/`.

**Opt-in, and why.** The first run fetches Batik from Maven Central, about ten megabytes of
jars, and the check needs a JVM; neither is a dependency of every application, which is why this
is a pack and not a gate. It cannot render HTML pages and does not replace the browser pack:
a page is checked in a browser, an SVG file in here, and the two are different questions.

**What it does not do.** Compare against a reference picture - a project that draws its icons
from a source can add that as a script of its own; `check-file` in `src/svg_check.clj` gives it
the rendered bytes. And it does not say whether an icon looks right: the person's walk at the
stage's end still does that, over the PNGs this writes, one page of them instead of ninety
files.
