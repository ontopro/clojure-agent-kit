(ns svg-trace-command
  "`bb --config <kit>/tools/svg/bb.edn trace <icons.edn> [--out <dir>] [--only <name>,<name>]`: each
  icon the file names traced from its picture (`svg-trace`), written as `<name>.svg`, with
  `overlay-<name>.svg` - the trace over the picture - and `index.html`, a contact sheet of them all,
  for a person to judge before an icon is used. The JVM's edge of the trace: it reads the PNGs.

  The file: `{:stroke 1.5 :icons {\"name\" {:png \"crops/name.png\" :region [x0 y0 x1 y1] ...}}}`,
  each entry's keys as `svg-trace/trace-elements` takes them, `:ink` `:saturation` (colour on white,
  the default) or `:darkness` (black line art), `:bbox` in the picture's pixels when the icon's box
  is not its strokes' own. The PNG paths are the file's folder's, and a picture outside it is
  refused. The output is `--out`, else `traced/` beside the file. An icon's name is letters, digits,
  `_` and `-`, and nothing is written through a link (`svg-trace-files`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [svg-trace :as t]
            [svg-trace-files :as files])
  (:import [java.util Base64]
           [javax.imageio ImageIO])
  (:gen-class))

(defn- grid-of [^java.io.File png ink]
  (let [img (ImageIO/read png)
        w (.getWidth img) h (.getHeight img)]
    (t/ink-grid w h (fn [x y] (let [c (.getRGB img (int x) (int y))]
                                [(bit-and (bit-shift-right c 16) 255) (bit-and (bit-shift-right c 8) 255) (bit-and c 255)]))
                (or ink :saturation))))

(defn- base64 [^java.io.File f]
  (.encodeToString (Base64/getEncoder) (java.nio.file.Files/readAllBytes (.toPath f))))

(defn- contact-sheet [rows stroke]
  (str "<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>Traced icons</title>\n"
       "<style>body{font:14px system-ui,sans-serif;margin:16px;color:#222}"
       "table{border-collapse:collapse}td,th{border-bottom:1px solid #ddd;padding:8px;vertical-align:middle;text-align:left}"
       ".icon img{width:48px;height:48px}.small img{width:24px;height:24px}</style></head><body>\n"
       "<h1>Traced icons</h1>\n<p>Each icon at 48 and 24 pixels, stroke " (files/escape-html stroke) ", and its trace in green over the picture it"
       " was traced from. Judge them here before any is used.</p>\n<table>\n"
       "<tr><th>Icon</th><th>48 px</th><th>24 px</th><th>Over its picture</th><th>Chains · elements</th></tr>\n"
       (str/join "\n" (for [{:keys [nm chains elements]} rows
                            :let [nm (files/escape-html nm)]]
                        (str "<tr><td>" nm "</td><td class=\"icon\"><img src=\"" nm ".svg\" alt=\"\"></td>"
                             "<td class=\"small\"><img src=\"" nm ".svg\" alt=\"\"></td>"
                             "<td><img src=\"overlay-" nm ".svg\" alt=\"\"></td><td>" chains " · " elements "</td></tr>")))
       "\n</table>\n</body></html>\n"))

(defn trace!
  "Trace every icon of the file at `config-path` (or those in `only`) into `out`. Returns the rows."
  [config-path out only]
  (let [config (edn/read-string (slurp config-path))
        base (.getParentFile (.getAbsoluteFile (io/file config-path)))
        stroke (or (:stroke config) 1.5)
        out (files/out-folder base out)
        chosen (filter (fn [[nm]] (or (empty? only) (contains? only nm))) (sort-by (comp str key) (:icons config)))]
    ;; every name refused before anything is written: a refusal after the first write had clobbered it
    (when-let [bad (seq (files/bad-names (map key chosen)))]
      (throw (ex-info (str "an icon's name is letters, digits, _ and -; refused: " (str/join ", " (map pr-str bad))) {:icons bad})))
    (.mkdirs out)
    (let [rows (vec (for [[nm entry] chosen
                          :let [png (files/picture-file base (:png entry))
                                _ (when-not png (throw (ex-info (str nm ": no picture at " (pr-str (:png entry)) " inside " base) {:icon nm})))
                                grid (grid-of png (:ink entry))
                                {:keys [svg elements chains k bbox]} (t/icon grid entry stroke)]]
                      (do (when-not svg (throw (ex-info (str nm ": nothing traced - a lower :threshold, or the :region") {:icon nm})))
                          (files/write! out (str nm ".svg") svg)
                          (files/write! out (str "overlay-" nm ".svg")
                                        (t/overlay-svg elements (base64 png) (* k (:h grid)) stroke bbox))
                          (println (str "  " nm ": " chains " chains, " (count elements) " elements"))
                          {:nm nm :chains chains :elements (count elements)})))]
      (files/write! out "index.html" (contact-sheet rows stroke))
      (println (str "  " (count rows) " icon(s) traced; the contact sheet: " (io/file out "index.html")))
      rows)))

(defn -main [& args]
  (let [[config & more] args
        flag (fn [f] (second (drop-while #(not= f %) more)))
        only (some-> (flag "--only") (str/split #",") set)]
    (if-not config
      (do (println "usage: bb --config <kit>/tools/svg/bb.edn trace <icons.edn> [--out <dir>] [--only <name>,<name>]")
          (System/exit 2))
      (try (trace! config (flag "--out") only)
           (catch clojure.lang.ExceptionInfo e
             (println (str "trace: " (ex-message e)))
             (System/exit 1))))))
