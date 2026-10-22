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

**The roadmap page's drawing too.** `bb roadmap-page` writes the project's roadmap as
`work/where/roadmap.svg` beside the page that carries it, and prints this check's command for it:
the drawing renders here as any SVG does, with no browser.

**Opt-in, and why.** The first run fetches Batik from Maven Central, about ten megabytes of
jars, and the check needs a JVM; neither is a dependency of every application, which is why this
is a pack and not a gate. It cannot render HTML pages and does not replace the browser pack:
a page is checked in a browser, an SVG file in here, and the two are different questions.

**What it does not do.** Compare against a reference picture - a project that draws its icons
from a source can add that as a script of its own; `check-file` in `src/svg_check.clj` gives it
the rendered bytes. And it does not say whether an icon looks right: the person's walk at the
stage's end still does that, over the PNGs this writes, one page of them instead of ninety
files.

## Tracing an icon from its picture

```
bb --config <kit>/tools/svg/bb.edn trace <icons.edn> [--out <dir>] [--only <name>,<name>]
```

When a site's icons come from pictures - a poster, a scan, a mock-up - drawing each by hand fails
in the same way every time: proportions drift and strokes wander. `trace` follows the picture's
own strokes instead. The region's ink is found (colour on white, or `:ink :darkness` for black
line art), thinned to its centre line, cut at its junctions, and each line fitted as the simplest
shape that holds - a straight line, a circle, an arc, else a smoothed spline - then fitted to the
24-unit grid at the file's stroke: only `path` and `circle`, `stroke="currentColor"`, round caps
and joins, and `fill="none"` on the `svg` - an element may carry `fill="currentColor"`. A short
stroke standing alone is a dot (`:dots? true`), drawn filled at the stroke's width; and a shape the
picture fills, which tracing thins to a line, is drawn by hand with `:add` and `:fill true`.

```clojure
{:stroke 1.5
 :icons {"phone" {:png "crops/phone.png" :region [26 29 84 82] :threshold 55 :epsilon 2.8 :min-length 12}
         "badge" {:png "crops/badge.png" :region [45 38 142 128] :dots? true
                 :exclude [[50 57 72 76]]                 ;; a fill, not a stroke
                 :add [{:circle [74.5 92.5 8.5] :fill true} ;; a filled disc, which tracing cannot follow
                       {:path [["M" [44 46]] ["L" [80 46]]]}]}}}
```

Every position is in the picture's own pixels; `:epsilon` and `:min-length` are in units of the
picture drawn 600 wide, so a setting means the same at any crop size. It writes `<name>.svg`,
`overlay-<name>.svg` - the trace in green over the picture - and `index.html`, a contact sheet of
every icon at 48 and 24 pixels beside its overlay, under `--out` (else `traced/` beside the file).
An icon's name is letters, digits, `_` and `-`; a picture is a file of the icons file's folder; and
nothing is written through a link, the default `traced/` included - each refused by name.

> [!IMPORTANT]
> Judge every traced icon on the contact sheet before it is used: a trace is the picture's strokes
> fitted, not a drawing anyone checked.

Each icon needs its own settings, found by eye on the overlay, and a fill the picture has is not a
stroke - leave it out with `:exclude` and draw it with `:add`. The fitting is pure and tested in
the KIT's gates (`src/svg_trace.clj`, with where the command reads and writes, `src/svg_trace_files.clj`);
the command, which reads the PNGs, runs on the JVM like the check (`src/svg_trace_command.clj`). `check` then holds the result to rendering.
