#!/usr/bin/env bb
;; Screenshots from a real viewport, one per page and width, each as tall as its page, with a
;; measurement file: scrollWidth against innerWidth says whether anything overflows. The
;; application must be running (`bb serve`, or `bb browser-check`, which serves for you).
;;
;;   bb tools/browser/screenshots.bb [--width N]... [path ...]
;;
;; Defaults: width 1440, the path `/`. Writes <out>/<width>/<slug>.png and
;; <out>/measurements-<width>.edn under .local/browser/ (BROWSER_OUT overrides).

(require '[babashka.fs :as fs])
(load-file (str (fs/parent (fs/absolutize *file*)) "/driver.clj"))

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
    (with-firefox [width 900]
      (fn [d]
        (vec (for [path paths]
               (do (e/set-window-size d {:width width :height 900})
                   (open-page d path)
                   (e/wait 0.3)
                   (let [m (fit-height! d width)
                         file (str dir "/" (slug-of path) ".png")
                         overflows? (> (:scrollWidth m) (:innerWidth m))]
                     (e/screenshot d file)
                     (println (format "%-40s %5dx%-5d scrollWidth %d%s"
                                      path width (:innerHeight m) (:scrollWidth m)
                                      (if overflows? "  OVERFLOWS" "")))
                     {:path path :title (e/get-title d) :file file
                      :innerWidth (:innerWidth m) :scrollWidth (:scrollWidth m)
                      :scrollHeight (:scrollHeight m) :overflows? overflows?}))))))))

(let [{:keys [widths paths]} (parse-args *command-line-args*)]
  (ensure-out!)
  (doseq [w widths]
    (let [rows (shoot-all w paths)]
      (spit (out (str "measurements-" w ".edn"))
            (pr-str {:at (now) :width w :pages rows
                     :overflowing (mapv :path (filter :overflows? rows))}))
      (println (count rows) "page(s) at" w "wide;" (count (filter :overflows? rows)) "overflow"))))
