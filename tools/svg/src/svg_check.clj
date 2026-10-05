(ns svg-check
  "A check of the SVG files a project produces, in the JVM and with no browser: each is
  rasterised with Apache Batik, and the check says whether it rendered, whether anything is
  drawn, and - when a colour is given - how much of what is drawn is that colour. For the icons
  a project recolours by script, that is the whole question; for a diagram, the first two are.

  Usage, through the pack's bb.edn:

    bb --config <kit>/tools/svg/bb.edn check [--out <dir>] [--size N] [--color #rrggbb] <file-or-dir>...

  Writes <out>/<name>.png for each file, and <out>/svg-check.edn and svg-check.md. Exit 1 when a
  file does not render or draws nothing; a colour share is reported, never judged - what share
  is right is the project's to say."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File]
           [javax.imageio ImageIO]
           [org.apache.batik.transcoder TranscoderInput TranscoderOutput]
           [org.apache.batik.transcoder.image PNGTranscoder]))

(defn- rasterise
  "The PNG bytes of `svg-file` rendered `size` pixels wide."
  [^File svg-file size]
  (let [t (PNGTranscoder.)
        out (ByteArrayOutputStream.)]
    (.addTranscodingHint t PNGTranscoder/KEY_WIDTH (float size))
    (with-open [in (io/input-stream svg-file)]
      (.transcode t (TranscoderInput. in) (TranscoderOutput. out)))
    (.toByteArray out)))

(defn- hex->rgb [s]
  (let [h (str/replace s #"^#" "")]
    (mapv #(Integer/parseInt (subs h % (+ % 2)) 16) [0 2 4])))

(defn- near?
  "Within `tolerance` of `rgb` on every channel."
  [[r g b] [tr tg tb] tolerance]
  (and (<= (Math/abs (- r tr)) tolerance) (<= (Math/abs (- g tg)) tolerance) (<= (Math/abs (- b tb)) tolerance)))

(defn- measure
  "Over the PNG's pixels: how many are drawn (alpha above a quarter), and of those how many
  are near `color` when one is given."
  [png-bytes color]
  (let [img (ImageIO/read (ByteArrayInputStream. png-bytes))
        w (.getWidth img) h (.getHeight img)
        target (some-> color hex->rgb)
        [drawn near]
        (reduce (fn [[d n] [x y]]
                  (let [p (.getRGB img x y)
                        a (bit-and (bit-shift-right p 24) 0xff)]
                    (if (> a 64)
                      [(inc d) (if (and target (near? [(bit-and (bit-shift-right p 16) 0xff)
                                                       (bit-and (bit-shift-right p 8) 0xff)
                                                       (bit-and p 0xff)]
                                                      target 40))
                                 (inc n) n)]
                      [d n])))
                [0 0]
                (for [y (range h) x (range w)] [x y]))]
    {:width w :height h :pixels (* w h) :drawn drawn
     :drawn-share (double (/ drawn (max 1 (* w h))))
     :color-share (when target (double (/ near (max 1 drawn))))}))

(defn check-file
  "One file's row: `:renders?`, the measure, where the PNG went, or `:error`."
  [^File f {:keys [out size color]}]
  (try
    (let [png (rasterise f size)
          m (measure png color)
          target (io/file out (str (str/replace (.getName f) #"\.svg$" "") ".png"))]
      (io/make-parents target)
      (io/copy png target)
      (merge {:file (.getPath f) :renders? true :empty? (< (:drawn-share m) 0.01) :png (.getPath target)} m))
    (catch Exception e
      ;; one line: Batik's message carries the parser's own on lines of its own
      {:file (.getPath f) :renders? false
       :error (str/replace (str (.getSimpleName (class e)) ": " (ex-message e)) #"\s*\n\s*" " - ")})))

(defn- svg-files [paths]
  (vec (for [p paths
             f (let [file (io/file p)]
                 (if (.isDirectory file)
                   (sort-by #(.getPath ^File %) (filter #(str/ends-with? (.getName ^File %) ".svg") (file-seq file)))
                   [file]))]
         f)))

(defn- parse-args [args]
  (loop [[a & more] args m {:out nil :size 256 :color nil :paths []}]
    (cond (nil? a) m
          (= a "--out") (recur (rest more) (assoc m :out (first more)))
          (= a "--size") (recur (rest more) (assoc m :size (parse-long (first more))))
          (= a "--color") (recur (rest more) (assoc m :color (first more)))
          :else (recur more (update m :paths conj a)))))

(defn- default-out []
  (let [here (.getCanonicalFile (io/file "."))
        parent (.getParentFile here)]
    (if (and parent (.exists (io/file parent "workspace.edn")))
      (.getPath (io/file parent "work" "svg" (.getName here)))
      ".local/svg")))

(defn- pct [x] (if x (format "%.0f%%" (* 100.0 x)) "-"))

(defn- render-md [rows {:keys [size color]}]
  (str "# SVG check, by the KIT's `tools/svg`\n\nRendered " size " px wide with Apache Batik 1.19"
       (when color (str "; the colour asked for is `" color "`")) ".\n\n"
       "| File | Renders | Drawn | " (if color "Of it the colour" "Colour") " | Note |\n|---|---|---|---|---|\n"
       (str/join "\n" (for [r rows]
                        (str "| `" (:file r) "` | " (if (:renders? r) "yes" "**NO**") " | "
                             (if (:renders? r) (pct (:drawn-share r)) "-") " | "
                             (if (:renders? r) (pct (:color-share r)) "-") " | "
                             (cond (:error r) (:error r) (:empty? r) "**draws nothing**" :else "") " |")))
       "\n\nA share is reported, not judged: what share is right is the project's to say.\n"))

(defn -main [& args]
  (let [{:keys [out color paths] :as opts} (parse-args args)
        out (or out (System/getenv "SVG_OUT") (default-out))
        opts (assoc opts :out out)
        files (svg-files paths)]
    (when (empty? files)
      (println "svg-check: no .svg file named or found under the folders given") (System/exit 2))
    (.mkdirs (io/file out))
    (let [rows (mapv #(check-file % opts) files)
          bad (filter #(or (not (:renders? %)) (:empty? %)) rows)]
      (doseq [r rows]
        (println (format "%-50s %s drawn %s%s%s" (:file r) (if (:renders? r) "renders" "NO RENDER")
                         (if (:renders? r) (pct (:drawn-share r)) "-")
                         (if color (str " colour " (pct (:color-share r))) "")
                         (cond (:error r) (str "  " (:error r)) (:empty? r) "  DRAWS NOTHING" :else ""))))
      (spit (io/file out "svg-check.edn") (pr-str {:at (str (java.time.ZonedDateTime/now)) :size (:size opts) :color color :files rows}))
      (spit (io/file out "svg-check.md") (render-md rows opts))
      (println (count rows) "file(s);" (count bad) "that do not render or draw nothing; report in" out)
      (System/exit (if (seq bad) 1 0)))))
