#!/usr/bin/env bb
;; Screenshots from a real viewport, one per page and width, each as tall as its page, with a
;; measurement file: scrollWidth against innerWidth says whether anything overflows. The
;; application must be running (`bb serve`, or `bb browser-check`, which serves for you).
;;
;;   bb tools/browser/screenshots.bb [--width N]... [path ...]
;;
;; Defaults: width 1440, the path `/`. Writes <out>/<width>/<slug>.png and
;; <out>/measurements-<width>.edn under .local/browser/ (BROWSER_OUT overrides).
;;
;; A WIDTH UNDER 500 IS A FRAME'S. Headless Firefox keeps no window narrower than 500 px, so every
;; "390" this script measured before was 500, and passed. A narrower width is measured in a frame
;; that wide inside a wider window (`browser-measure/framed?`); a page whose viewport is not the
;; width asked fails the run, exit 1, after its measurements are written.

(require '[babashka.fs :as fs])
(load-file (str (fs/parent (fs/absolutize *file*)) "/driver.clj"))
(load-file (str (fs/parent (fs/absolutize *file*)) "/src/browser_measure.clj"))
(alias 'bm 'browser-measure)

(defn parse-args [args]
  (loop [[a & more] args m {:widths [] :paths []}]
    (cond (nil? a) (-> m
                       (update :widths #(if (seq %) % [1440]))
                       (update :paths #(if (seq %) % ["/"])))
          (= a "--width") (recur (rest more) (update m :widths conj (parse-long (first more))))
          :else (recur more (update m :paths conj a)))))

(defn shoot-all
  "Every path at `width`; returns one row per page."
  [width paths]
  (let [dir (out (str width))]
    (fs/create-dirs dir)
    (with-firefox [(if (bm/framed? width) bm/frame-window width) 900]
      (fn [d]
        (vec (for [path paths]
               (let [framed? (bm/framed? width)
                     file (str dir "/" (slug-of path) ".png")
                     m (if framed?
                         (do (e/set-window-size d {:width bm/frame-window :height 900})
                             (open-framed! d path width)
                             (e/wait 0.3)
                             (fit-framed-height! d bm/frame-window))
                         (do (e/set-window-size d {:width width :height 900})
                             (open-page d path)
                             (e/wait 0.3)
                             (fit-height! d width)))
                     overflows? (> (:scrollWidth m) (:innerWidth m))
                     problem (bm/width-problem width m)]
                 (if framed? (e/screenshot-element d {:id "kit-frame"} file) (e/screenshot d file))
                 (println (format "%-40s %5dx%-5d scrollWidth %d%s%s"
                                  path (:innerWidth m) (:innerHeight m) (:scrollWidth m)
                                  (if overflows? "  OVERFLOWS" "") (if problem (str "  " problem) "")))
                 (cond-> {:path path :title (e/get-title d) :file file :framed? framed?
                          :innerWidth (:innerWidth m) :scrollWidth (:scrollWidth m)
                          :scrollHeight (:scrollHeight m) :overflows? overflows?}
                   problem (assoc :problem problem)))))))))

;; A REFUSAL READS AS ONE: the frame's words, not babashka's error box around them
(try
  (let [{:keys [widths paths]} (parse-args *command-line-args*)]
    (ensure-out!)
    (let [problems (atom [])]
      (doseq [w widths]
        (let [rows (shoot-all w paths)]
          (spit (out (str "measurements-" w ".edn"))
                (pr-str {:at (now) :width w :pages rows
                         :overflowing (mapv :path (filter :overflows? rows))}))
          (println (count rows) "page(s) at" w "wide;" (count (filter :overflows? rows)) "overflow")
          (doseq [row (filter :problem rows)]
            (swap! problems conj (str (:path row) " at " w ": " (:problem row))))))
      (when (seq @problems)
        (println (str "not measured at the width asked - " (str/join "; " @problems)))
        (System/exit 1))))
  (catch clojure.lang.ExceptionInfo e
    (println (str "screenshots: " (ex-message e)))
    (System/exit 1)))
