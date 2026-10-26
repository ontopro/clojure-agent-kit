(ns browser-measure
  "The browser pack's judgements on a measured page, apart from the browser: pure, so the
  harness's tests reach them (`harness/test/harness/tools/browser_measure_test.clj`). The
  scripts beside this folder load it with `load-file`.")

(def min-window
  "The narrowest window headless Firefox keeps, in CSS pixels. Asked for less - by WebDriver, by
  `--width`, by `--window-size` - it stays this wide, and a device-pixel ratio does not move it
  (Firefox 158, macOS arm64, 2026-10-26). Every \"390\" the pack measured before was 500."
  500)

(def frame-window
  "The window a narrow width's frame sits in: wide enough that the frame is never squeezed."
  1000)

(defn framed?
  "Whether `width` is measured in a frame of that width inside a wider window - every width
  under the window's minimum; at or above it, the window itself is the width."
  [width]
  (< width min-window))

(defn width-problem
  "Why a page's measure does not stand for `width`, or nil. A viewport of another width is
  another page than the one named: the run fails on it, it never passes as that width."
  [width {:keys [innerWidth]}]
  (when (not= width innerWidth)
    (str "measured " innerWidth " px wide, not the " width " asked")))
