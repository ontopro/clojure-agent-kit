(ns harness.setup.roadmap-page
  "The roadmap page: `bb roadmap-page`.

  The project's roadmap, drawn - five lanes, one a phase, each stage a box in the lane of its
  phase, coloured by where it stands, the money bar under it, its open decisions and risks as
  badges - and under the drawing what waits on the person, money and time, what changed since,
  what is open, what is next. Every figure is `harness.setup.where`'s map: the page is a rendering
  of it, as the fuller account is, so the two cannot disagree.

  TWO FILES, ONE DRAWING. `work/where/roadmap.svg` is the drawing on its own, for the KIT's SVG
  pack to check (`tools/svg/`, which renders it with no browser); `work/where/roadmap.html` carries
  the same SVG text inline, so the page opens from a file with nothing else - no script, no font
  or stylesheet fetched. Generated, never committed: `work/` is the workspace's scratch folder, and
  the page's foot says when and from which commit it was drawn. Every link is relative from the
  page's folder to the workspace, so it opens wherever the workspace is.

  `render-svg` and `render-html` are pure over the map; `write!` is the edge."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [harness.setup.next :as next]
   [harness.setup.where :as where]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; pure: text in markup
;; ---------------------------------------------------------------------------

(defn esc
  "`s` as text in XML or HTML: `&`, `<`, `>` and `\"` escaped."
  [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- cut [n s] (let [s (str s)] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- usd [x] (format "$%.2f" (double x)))

(defn- dollars [x] (if (== x (Math/floor x)) (str "$" (long x)) (usd x)))

(defn phase-letter
  "The letter of the phase a stage of kind `kind` belongs to, `next/phase`'s, or nil."
  [kind]
  (some-> (next/phase kind) (subs 0 1)))

;; ---------------------------------------------------------------------------
;; pure: the drawing
;; ---------------------------------------------------------------------------

(def ^:private lane-h 88)
(def ^:private top 20)
(def ^:private box-w 106)
(def ^:private step-x 116)
(def ^:private left 160)

(def ^:private looks
  "A stage box's fill, stroke and text colour by its state; the next stage's outline dashed."
  {:closed {:fill "#cfe8cf" :stroke "#3a7d3a" :text "#222" :sub "#444"}
   :running {:fill "#fff3cd" :stroke "#b7791f" :text "#222" :sub "#444"}
   :planned {:fill "#fff3cd" :stroke "#b7791f" :text "#222" :sub "#444" :dash "5 3"}
   :next {:fill "#ffffff" :stroke "#2b5fd9" :text "#2b5fd9" :sub "#2b5fd9" :dash "5 3" :width 2}
   :ahead {:fill "#f0f0f0" :stroke "#999" :text "#555" :sub "#666"}})

(defn- lane-of
  "The lane index, 0-4, of a stage of kind `kind`: its phase's place in `where/phases`; a kind
  that is none of §01's sits in BUILD, its caption says so."
  [kind]
  (let [letter (phase-letter kind)]
    (or (first (keep-indexed (fn [i [l _]] (when (= l letter) i)) where/phases)) 2)))

(defn owner-stage
  "The stage an open decision or risk `id` is drawn on: the first stage not closed whose map row
  names it, else the last row that does; nil when none names it."
  [stages k id]
  (let [naming (filter #(some #{id} (get % k)) stages)]
    (:id (or (first (remove #(= :closed (:state %)) naming)) (last naming)))))

(defn- caption [{:keys [state money cap cap-note money-note runs kind]}]
  (let [capped (when cap (str " of " (dollars cap)))]
    (cond
      (nil? (phase-letter (str kind))) "kind not read"
      (#{:closed :running} state) (str (if money (str (usd money) capped) "no money read") " · " runs " run" (when (not= 1 runs) "s"))
      (= :planned state) (str "planned" (when cap (str " · cap " (dollars cap) (when cap-note (str " " cap-note))))
                              (when money-note (str " · " (usd money))))
      (= :next state) (str "next" (when cap (str " · cap " (dollars cap) (when cap-note (str " " cap-note)))))
      :else "not started")))

(defn- badge [x y id fill stroke ink href]
  (let [w (+ 22 (* 4 (count id)))]
    [w (str "<a xlink:href=\"" (esc href) "\"><rect x=\"" x "\" y=\"" y "\" width=\"" w "\" height=\"16\" rx=\"8\" fill=\"" fill
            "\" stroke=\"" stroke "\"/><text x=\"" (+ x (/ w 2)) "\" y=\"" (+ y 12) "\" text-anchor=\"middle\" fill=\"" ink "\">"
            (esc id) "</text></a>")]))

(defn render-svg
  "The drawing, from `where-data`'s map `d`; `up` is the path from the page's folder to the
  workspace (`\"../../\"` for `work/where/`), put before every link. Pure."
  [{:keys [workspace generated phases stages open]} up]
  (let [n (count stages)
        width (max 980 (+ left (* n step-x) 10))
        lanes-end (+ top (* 5 lane-h))
        height (+ lanes-end 20)
        build (:build workspace)
        href #(str up %)
        here-lane (first (keep-indexed (fn [i p] (when (= :here (:status p)) i)) phases))
        box (fn [{:keys [id kind]}] [(+ left (* id step-x)) (+ top (* (lane-of (str kind)) lane-h) 11)])
        by-id (into {} (map (juxt :id identity) stages))
        drawn (for [s stages] (assoc s :xy (box s)))
        decision-badges (group-by first (for [{:keys [id]} (:decisions open)
                                              :let [o (owner-stage stages :decisions id)] :when o] [o id :d]))
        risk-badges (group-by first (for [{:keys [id]} (:risks open)
                                          :let [o (owner-stage stages :risks id)] :when o] [o id :r]))
        lane-note (fn [i {:keys [status] ids :stages}]
                    (cond (= i here-lane) "<tspan fill=\"#2b5fd9\" font-weight=\"600\">▶ you are here</tspan>"
                          (= :done status) (let [c (last (keep #(:closed (by-id %)) ids))] (str "done" (when c (str " · closed " c))))
                          :else (name status)))]
    (str/join
     "\n"
     (concat
      ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       (str "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 " width " " height
            "\" width=\"" width "\" height=\"" height "\" font-family=\"Helvetica, Arial, sans-serif\" font-size=\"12\">")
       (str "  <title>" (esc build) " roadmap: five phases, " n " stage" (when (not= 1 n) "s") "</title>")
       "  <desc>Phases A to E as lanes; each stage a box in the lane of its phase; the money bar under each; open decisions and risks as badges on the stage that owns them.</desc>"
       "  <g id=\"lanes\">"]
      (for [[i _] (map-indexed vector phases)]
        (str "    <rect x=\"8\" y=\"" (+ top (* i lane-h)) "\" width=\"" (- width 16) "\" height=\"" lane-h "\" "
             (if (= i here-lane) "fill=\"#eef4ff\" stroke=\"#b9cdf5\"" "fill=\"#fafafa\" stroke=\"#e4e4e4\"") "/>"))
      ["    <g fill=\"#333\" font-weight=\"600\" font-size=\"13\">"]
      (for [[i {:keys [letter word]}] (map-indexed vector phases)]
        (str "      <text x=\"16\" y=\"" (+ top (* i lane-h) 36) "\">" letter " · " word "</text>"))
      ["    </g>" "    <g fill=\"#666\" font-size=\"11\">"]
      (for [[i p] (map-indexed vector phases)]
        (str "      <text x=\"16\" y=\"" (+ top (* i lane-h) 52) "\">" (lane-note i p) "</text>"))
      ["    </g>"
       (str "    <rect x=\"" left "\" y=\"" (+ top 11) "\" width=\"" box-w "\" height=\"46\" rx=\"6\" fill=\"#cfe8cf\" stroke=\"#3a7d3a\"/>")
       (str "    <text x=\"" (+ left 53) "\" y=\"" (+ top 30) "\" text-anchor=\"middle\" font-weight=\"600\">workspace</text>")
       (str "    <text x=\"" (+ left 53) "\" y=\"" (+ top 46) "\" text-anchor=\"middle\" fill=\"#444\" font-size=\"11\">made"
            (when (:kit-version generated) (str " · KIT " (esc (:kit-version generated)))) "</text>")
       "  </g>"]
      (when (> n 1)
        [(str "  <polyline points=\"" (str/join " " (for [{[x y] :xy} drawn] (str (+ x 53) "," (+ y 23))))
              "\" fill=\"none\" stroke=\"#9aa7bd\" stroke-opacity=\"0.6\" stroke-width=\"2\" stroke-dasharray=\"4 3\"/>")])
      ["  <g id=\"stages\">"]
      (mapcat
       (fn [{:keys [id kind state plan money cap] nm :name [x y] :xy :as s}]
         (let [{:keys [fill stroke text sub dash] w :width} (looks state)
               target (href (or plan (str build "/docs/00-overview.md")))
               ratio (when (and money cap (pos? cap)) (min 1.0 (/ money cap)))
               bar (if (and money cap (> money cap)) "#c0392b" stroke)]
           [(str "    <a xlink:href=\"" (esc target) "\">")
            (str "      <rect x=\"" x "\" y=\"" y "\" width=\"" box-w "\" height=\"46\" rx=\"6\" fill=\"" fill "\" stroke=\"" stroke "\""
                 (when w (str " stroke-width=\"" w "\"")) (when dash (str " stroke-dasharray=\"" dash "\"")) "/>")
            (str "      <text x=\"" (+ x 53) "\" y=\"" (+ y 18) "\" text-anchor=\"middle\" font-weight=\"600\" font-size=\"11.5\" fill=\"" text "\">"
                 id " · " (esc (cut 17 (or (not-empty kind) "kind?"))) "</text>")
            (str "      <text x=\"" (+ x 53) "\" y=\"" (+ y 34) "\" text-anchor=\"middle\" fill=\"" sub "\" font-size=\"11\">" (esc (cut 18 (or nm ""))) "</text>")
            "    </a>"
            (str "    <rect x=\"" x "\" y=\"" (+ y 52) "\" width=\"" box-w "\" height=\"6\" fill=\"#e6e6e6\"/>"
                 (when ratio (str "<rect x=\"" x "\" y=\"" (+ y 52) "\" width=\"" (Math/round (* box-w (double ratio))) "\" height=\"6\" fill=\"" bar "\"/>")))
            (str "    <text x=\"" x "\" y=\"" (+ y 69) "\" fill=\"" (if (= :next state) "#2b5fd9" "#555") "\" font-size=\"10.5\">" (esc (cut 24 (caption s))) "</text>")]))
       drawn)
      ["  </g>" "  <g id=\"badges\" font-size=\"10.5\" font-weight=\"600\">"]
      (mapcat
       (fn [{:keys [id] [x y] :xy}]
         (let [items (concat (get decision-badges id) (get risk-badges id))
               widths (map (fn [[_ bid]] (+ 22 (* 4 (count bid)))) items)
               total (+ (reduce + 0 widths) (* 4 (max 0 (dec (count items)))))
               x0 (- (+ x 53) (/ total 2))]
           (first
            (reduce (fn [[out bx] [_ bid kind]]
                      (let [[w svg] (if (= :d kind)
                                      (badge bx (- y 8) bid "#dbe6ff" "#2b5fd9" "#1d3f99" (href (str build "/docs/04-decision-log.md")))
                                      (badge bx (- y 8) bid "#ffe0e0" "#c0392b" "#8e2a1f" (href (str build "/docs/00-overview.md"))))]
                        [(conj out (str "    " svg)) (+ bx w 4)]))
                    [[] x0] items))))
       drawn)
      ["  </g>"
       "  <g font-size=\"11\" fill=\"#555\">"
       (str "    <rect x=\"160\" y=\"" (+ lanes-end 6) "\" width=\"12\" height=\"10\" fill=\"#cfe8cf\" stroke=\"#3a7d3a\"/><text x=\"177\" y=\"" (+ lanes-end 15) "\">closed</text>")
       (str "    <rect x=\"230\" y=\"" (+ lanes-end 6) "\" width=\"12\" height=\"10\" fill=\"#fff3cd\" stroke=\"#b7791f\"/><text x=\"247\" y=\"" (+ lanes-end 15) "\">in progress</text>")
       (str "    <rect x=\"326\" y=\"" (+ lanes-end 6) "\" width=\"12\" height=\"10\" fill=\"#fff\" stroke=\"#2b5fd9\" stroke-dasharray=\"3 2\"/><text x=\"343\" y=\"" (+ lanes-end 15) "\">next, not pulled</text>")
       (str "    <rect x=\"450\" y=\"" (+ lanes-end 6) "\" width=\"12\" height=\"10\" fill=\"#f0f0f0\" stroke=\"#999\"/><text x=\"467\" y=\"" (+ lanes-end 15) "\">ahead</text>")
       (str "    <rect x=\"516\" y=\"" (+ lanes-end 3) "\" width=\"20\" height=\"16\" rx=\"8\" fill=\"#dbe6ff\" stroke=\"#2b5fd9\"/><text x=\"541\" y=\"" (+ lanes-end 15) "\">open decision</text>")
       (str "    <rect x=\"630\" y=\"" (+ lanes-end 3) "\" width=\"20\" height=\"16\" rx=\"8\" fill=\"#ffe0e0\" stroke=\"#c0392b\"/><text x=\"655\" y=\"" (+ lanes-end 15) "\">open risk</text>")
       (str "    <text x=\"730\" y=\"" (+ lanes-end 15) "\">bar: spent of the stage's cap</text>")
       "  </g>"
       "</svg>"]))))

;; ---------------------------------------------------------------------------
;; pure: the page
;; ---------------------------------------------------------------------------

(def ^:private style
  (str/join "\n"
            ["  :root { color-scheme: light; }"
             "  body { font: 15px/1.45 -apple-system, \"Segoe UI\", Helvetica, Arial, sans-serif; color: #222; background: #fff; margin: 0; padding: 24px 16px 48px; }"
             "  main { max-width: 1000px; margin: 0 auto; }"
             "  h1 { font-size: 20px; margin: 0 0 4px; }"
             "  h2 { font-size: 16px; margin: 28px 0 8px; border-bottom: 1px solid #ddd; padding-bottom: 4px; }"
             "  .where { color: #444; margin: 0 0 16px; }"
             "  table { border-collapse: collapse; width: 100%; font-size: 14px; }"
             "  th, td { text-align: left; vertical-align: top; padding: 5px 8px; border-bottom: 1px solid #eee; }"
             "  th { font-weight: 600; color: #555; background: #fafafa; }"
             "  td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }"
             "  code { font: 13px ui-monospace, SFMono-Regular, Menlo, monospace; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }"
             "  ul { padding-left: 20px; } li { margin: 4px 0; }"
             "  .note, .foot { color: #666; font-size: 13px; }"
             "  .foot { margin-top: 32px; }"
             "  .scroll { overflow-x: auto; }"
             "  svg { max-width: 100%; height: auto; display: block; }"]))

(defn svg-body
  "The drawing as the page carries it: the SVG file's text without its XML declaration."
  [svg]
  (str/replace-first svg #"^<\?xml[^>]*\?>\n" ""))

(defn- link
  "A `where` of the map - a path, then ` §n` or ` step n` - as a link from the page's folder."
  [up where]
  (let [path (first (str/split (str where) #" " 2))]
    (str "<a href=\"" (esc (str up path)) "\">" (esc where) "</a>")))

(defn render-html
  "The roadmap page, from `where-data`'s map `d`, `up` the path from the page's folder to the
  workspace, and `svg` the drawing `render-svg` gave - carried inline, the same text. Pure."
  [{:keys [workspace generated stages headline open since money terms] nxt :next :as d} up svg]
  (let [{:keys [build kit]} workspace
        kit (or kit "KIT")
        project (str/replace (str build) #"-build$" "")
        waits (:waits-on-the-person d)
        {:keys [decisions risks]} open
        dash "—"
        num (fn [x] (str "<td class=\"num\">" x "</td>"))
        ids (fn [xs] (if (seq xs) (str/join ", " (map #(str "stage " %) xs)) "none"))]
    (str/join
     "\n"
     (concat
      ["<!doctype html>" "<html lang=\"en\">" "<head>" "<meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       (str "<title>" (esc project) " - the roadmap</title>")
       "<style>" style "</style>" "</head>" "<body>" "<main>"
       (str "<h1>" (esc project) " - the roadmap</h1>")
       (str "<p class=\"where\">" (esc (str/replace-first (str headline) #"^where: " "")) "</p>")
       "<div class=\"scroll\">"
       (svg-body svg)
       "</div>"
       ""
       (str "<h2>Waits on you (" (count waits) ")</h2>")]
      (if (seq waits)
        (concat ["<ul>"]
                (for [{:keys [what why where run]} waits]
                  (str "  <li><strong>" (esc what) "</strong> - " (esc why) "."
                       (when run (str " Run: <code>" (esc run) "</code>.")) " " (link up where) "</li>"))
                ["</ul>"])
        ["<p>Nothing: no step, gate, prep row, decision or risk is the person's now.</p>"])
      ["" "<h2>Money and time</h2>" "<table>"
       "  <thead><tr><th>Stage</th><th>Kind · name</th><th>State</th><th class=\"num\">Cap</th><th class=\"num\">Spent</th><th class=\"num\">Runs</th><th class=\"num\">Rounds</th><th class=\"num\">Run minutes</th><th>Approved → closed</th></tr></thead>"
       "  <tbody>"]
      (for [{:keys [id kind state cap cap-note money money-note runs merged rounds run-minutes approved closed days] nm :name} stages]
        (str "    <tr><td>" id "</td><td>" (esc (str/join " · " (remove str/blank? [kind nm]))) "</td><td>"
             (esc (case state :closed "closed" :running "running" :planned "planned, not approved" :next "next, not pulled" :ahead "ahead"))
             "</td>"
             (num (cond cap (esc (str (dollars cap) (when cap-note (str " " cap-note)))) cap-note (esc (str "(cap " cap-note ")")) :else dash))
             (num (cond (nil? money) dash money-note (str (usd money) " (readings only)") :else (usd money)))
             (num (if (pos? (or runs 0)) (str merged " of " runs " merged") dash))
             (num (if (pos? (or rounds 0)) rounds dash))
             (num (if (pos? (or run-minutes 0)) run-minutes dash))
             "<td>" (if approved (str approved " → " (or closed "…") (when days (str " (" days " day" (when (not= 1 days) "s") ")"))) dash) "</td></tr>"))
      [(str "    <tr><th>All</th><th></th><th></th>" "<th class=\"num\">" (dollars (:caps-approved money)) " approved"
            (when (pos? (:caps-proposed money)) (str ", " (dollars (:caps-proposed money)) " proposed")) "</th>"
            "<th class=\"num\">" (usd (:spent money)) "</th>"
            "<th class=\"num\">" (reduce + 0 (map :runs stages)) "</th>"
            "<th class=\"num\">" (reduce + 0 (map :rounds stages)) "</th>"
            "<th class=\"num\">" (reduce + 0 (map #(or (:run-minutes %) 0) stages)) "</th><th></th></tr>")
       "  </tbody>" "</table>"
       "<p class=\"note\">Spent counts a stage's runs and its readings, its security review among them. Run minutes are the loop's wall time over the stage's runs; the days are calendar days, approval to close, both counted.</p>"
       ""
       "<h2>Since you last looked</h2>"]
      (if-not (:from since)
        [(str "<p>No date to count from: " (esc (:why since)) ".</p>")]
        [(str "<p>From <strong>" (esc (:from since)) "</strong>: " (esc (:why since)) ". <code>bb last-look</code> records a look; <code>--since</code> names another date.</p>")
         "<ul>"
         (str "  <li>Closed: " (ids (:stages-closed since)) " · approved: " (ids (:approved since)) " · blueprint signed: " (ids (:signed since)) ".</li>")
         (str "  <li>Decisions settled: " (if (seq (:decisions-settled since)) (esc (str/join ", " (map :id (:decisions-settled since)))) "none dated")
              (when (pos? (:settled-undated since)) (str "; " (:settled-undated since) " settled with no date, not counted")) ".</li>")
         (str "  <li>Runs: " (:runs since) ", " (:merged since) " merged, " (usd (:run-money since)) " (runs only; a reading carries no date).</li>")
         "</ul>"])
      ["" "<h2>Open</h2>" "<ul>"
       (str "  <li>Decisions PROVISIONAL or OPEN, " (count decisions) " - " (link up (str build "/docs/04-decision-log.md")))
       "    <ul>"]
      (for [{:keys [id section status subject owner]} decisions]
        (str "      <li><strong>" (esc id) "</strong> " (esc subject) " - " status " (§" section ")" (when owner (str ", owner: " (esc owner))) "</li>"))
      ["    </ul>" "  </li>"
       (str "  <li>Risks OPEN, " (count risks) " - " (link up (str build "/docs/00-overview.md §4a")))
       "    <ul>"]
      (for [{:keys [id subject runs-in spike]} risks]
        (str "      <li><strong>" (esc id) "</strong> " (esc subject) " - runs in " (esc (or (not-empty runs-in) "(not said)"))
             (when (seq spike) (str "; " (esc spike))) "</li>"))
      ["    </ul>" "  </li>" "</ul>"
       "" "<h2>Next</h2>"
       (str "<p>Step " (:step nxt) " - " (esc (:name nxt)) "; owner: " (esc (:owner nxt)) "; run: <code>" (esc (:run nxt)) "</code></p>")]
      (when (:also nxt) [(str "<p>Also: " (esc (:also nxt)) "</p>")])
      [(str "<p class=\"foot\">Generated " (esc (:at generated)) " from <code>" (esc build) "</code>"
            (when (:plan-commit generated) (str " at <code>" (esc (:plan-commit generated)) "</code>"))
            " by <code>bb roadmap-page</code>" (when (:kit-version generated) (str " (KIT " (esc (:kit-version generated)) ")"))
            ". Not committed: run the command again for a current page. Where things are defined: "
            (str/join "; " (for [{:keys [id file heading]} (:kit terms)] (str "a " (esc (str/replace id #" n$" "")) ", " (link up (str kit "/" file)) " \"" (esc heading) "\"")))
            "; the project plan, " (link up (str build "/docs/" (get-in terms [:project-plan :file]))) " §" (get-in terms [:project-plan :section])
            "; every ID, the read more lines of <code>bb next --where</code>.</p>")
       "</main>" "</body>" "</html>"]))))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn write!
  "Write the roadmap page for workspace `ws` from `where-data`'s map `d` into `<work>/where/`:
  `roadmap.svg` and `roadmap.html`. Returns the two paths."
  [ws d]
  (let [dir (fs/path (:workspace/work ws) "where")
        up (str (fs/relativize (fs/absolutize dir) (fs/absolutize (:workspace/dir ws))) "/")
        svg (render-svg d up)
        svg-file (fs/path dir "roadmap.svg")
        html-file (fs/path dir "roadmap.html")]
    (fs/create-dirs dir)
    (spit (str svg-file) (str svg "\n"))
    (spit (str html-file) (str (render-html d up svg) "\n"))
    [(str svg-file) (str html-file)]))

(defn -main
  "bb roadmap-page [--workspace <dir>]

  The roadmap page, written to the workspace's `work/where/`: `roadmap.svg`, the drawing, and
  `roadmap.html`, the page that carries it. Prints the two paths, how to open the page and how
  to check the drawing."
  [& args]
  (let [ws (workspace/current-or-exit args)]
    (when-not ws
      (println "roadmap-page: no workspace here - run it from the workspace folder")
      (System/exit 1))
    (let [d (where/where-data ws {:at (str (java.time.Instant/now))
                                  :plan-commit (where/build-commit (:workspace/build ws))})
          rel #(str (fs/relativize (fs/absolutize (:workspace/dir ws)) (fs/absolutize %)))
          [svg html] (write! ws d)]
      (println (str "wrote " (rel svg) " and " (rel html)))
      (println (str "  open it:         open " (rel html)))
      (println (str "  check the drawing: bb --config " (or (get-in d [:workspace :kit]) "KIT") "/tools/svg/bb.edn check " (rel svg))))))
