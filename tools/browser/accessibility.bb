#!/usr/bin/env bb
;; An automated accessibility scan of pages with axe-core, in a real browser. It finds what a
;; rule can find - contrast, names, landmarks, roles - and nothing a person finds by using the
;; page with a keyboard; a keyboard walk is the project's to write. The application must be
;; running (`bb serve`, or `bb browser-check`, which serves for you).
;;
;;   bb tools/browser/accessibility.bb [path ...]
;;
;; Default: the path `/`. axe-core (MPL-2.0) is fetched once into <out>/axe.min.js and is not
;; committed. Writes <out>/accessibility.edn and <out>/accessibility.md under .local/browser/.

(require '[babashka.fs :as fs])
(load-file (str (fs/parent (fs/absolutize *file*)) "/driver.clj"))
(require '[babashka.http-client :as http])

(def axe-version "4.10.2")
(def axe-url (str "https://cdnjs.cloudflare.com/ajax/libs/axe-core/" axe-version "/axe.min.js"))
(def axe-file (out "axe.min.js"))

(defn ensure-axe! []
  (when-not (fs/exists? axe-file)
    (println "fetching axe-core" axe-version "(MPL-2.0) into" axe-file "- kept out of the repository")
    (let [{:keys [status body]} (http/get axe-url {:as :stream :throw false})]
      (when-not (= 200 status)
        (println "could not fetch axe-core:" status) (System/exit 2))
      (ensure-out!)
      (fs/copy body axe-file))))

(defn scan
  "The violations axe-core finds on `path`, each with its impact, its rule and where it was seen."
  [d path]
  (open-page d path)
  (e/js-execute d (slurp axe-file))
  (let [r (e/js-async d (str "var done = arguments[arguments.length - 1];"
                             "axe.run(document, {resultTypes: ['violations', 'incomplete']})"
                             ".then(function (r) { done({violations: r.violations.map(function (v) {"
                             "  return {id: v.id, impact: v.impact, help: v.help, nodes: v.nodes.length,"
                             "          targets: v.nodes.slice(0, 3).map(function (n) { return n.target.join(' '); })}; }),"
                             "  incomplete: r.incomplete.length}); })"
                             ".catch(function (e) { done({error: String(e)}); });"))]
    (assoc r :path path)))

(defn render-md [scans]
  (str "# Accessibility scan, by `bb tools/browser/accessibility.bb`\n\n"
       "Run " (now) ", headless Firefox 1440 wide, axe-core " axe-version ". A scan finds what a rule can"
       " find; what a person finds by using the page with a keyboard is not here.\n\n"
       "| Page | Violations | Serious or critical | Incomplete (needs a person) |\n|---|---|---|---|\n"
       (str/join "\n" (for [s scans]
                        (str "| `" (:path s) "` | " (count (:violations s)) " | "
                             (count (filter #(#{"serious" "critical"} (:impact %)) (:violations s)))
                             " | " (:incomplete s) " |")))
       "\n\n## Every distinct violation, with where it was seen\n\n"
       (let [by-id (group-by :id (mapcat (fn [s] (map #(assoc % :path (:path s)) (:violations s))) scans))]
         (if (empty? by-id)
           "None.\n"
           (str/join "\n" (for [[id vs] (sort-by key by-id)]
                            (str "- **" id "** (" (:impact (first vs)) "): " (:help (first vs))
                                 " - on " (count vs) " page(s), e.g. `" (:path (first vs)) "`, "
                                 (first (:targets (first vs))))))))
       "\n"))

(let [paths (if (seq *command-line-args*) (vec *command-line-args*) ["/"])]
  (ensure-out!)
  (ensure-axe!)
  (let [scans (with-firefox [1440 900] (fn [d] (vec (for [p paths] (scan d p)))))]
    (spit (out "accessibility.edn") (pr-str {:at (now) :axe axe-version :scans scans}))
    (spit (out "accessibility.md") (render-md scans))
    (doseq [s scans]
      (println (format "%-40s violations %d incomplete %d%s" (:path s) (count (:violations s)) (:incomplete s)
                       (if (:error s) (str "  ERROR " (:error s)) ""))))
    (println "distinct violations:" (count (distinct (map :id (mapcat :violations scans)))))))
