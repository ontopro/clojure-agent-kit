#!/usr/bin/env bb
;; The browser checks, served and stopped for you: screenshots with measurements, and the
;; accessibility scan, on the pages named. Run from the application's folder:
;;
;;   bb --config <kit>/tools/browser/bb.edn check [--serve "<cmd>"] [--health <path>] [--base <url>]
;;        [--out <dir>] [--running] [--only screenshots|accessibility] [--width N]... [path ...]
;;
;; Defaults: `bb serve`, `/health`, `http://localhost:8000`, both checks, width 1440, the path
;; `/`, the output under the workspace's `work/browser/<app>/` or `.local/browser/` (driver.clj).
;; `--running` uses the application already serving instead of starting one.
;;
;; The flags become the settings the scripts read - SERVE_CMD, HEALTH_PATH, APP_BASE,
;; BROWSER_OUT - as environment for the scripts this runs, and as system properties for the
;; server skeleton it loads into its own process.

(require '[babashka.fs :as fs]
         '[babashka.process :as p])

(def pack-dir (str (fs/parent (fs/absolutize *file*))))

(defn parse-args [args]
  (loop [[a & more] args m {:running? false :only nil :env {} :rest []}]
    (cond (nil? a) m
          (= a "--running") (recur more (assoc m :running? true))
          (= a "--only") (recur (rest more) (assoc m :only (first more)))
          (= a "--serve") (recur (rest more) (assoc-in m [:env "SERVE_CMD"] (first more)))
          (= a "--health") (recur (rest more) (assoc-in m [:env "HEALTH_PATH"] (first more)))
          (= a "--base") (recur (rest more) (assoc-in m [:env "APP_BASE"] (first more)))
          (= a "--out") (recur (rest more) (assoc-in m [:env "BROWSER_OUT"] (str (fs/absolutize (first more)))))
          :else (recur more (update m :rest conj a)))))

(def opts (parse-args *command-line-args*))

(defn run! [script args]
  (let [{:keys [exit]} (apply p/shell {:continue true :extra-env (:env opts)} "bb" (str pack-dir "/" script) args)]
    (when-not (zero? exit) (System/exit exit))))

(defn without-widths
  "The scan takes paths only; the widths are the screenshots'."
  [args]
  (loop [[a & more] args out []]
    (cond (nil? a) out
          (= a "--width") (recur (rest more) out)
          :else (recur more (conj out a)))))

(defn checks! []
  (when (contains? #{nil "screenshots"} (:only opts))
    (run! "screenshots.bb" (:rest opts)))
  (when (contains? #{nil "accessibility"} (:only opts))
    (run! "accessibility.bb" (without-widths (:rest opts)))))

;; three top-level forms, not one: the server file is loaded before the form that names
;; `with-server` is read, and the settings are in place before the file reads them
(doseq [[k v] (:env opts)] (System/setProperty (str "browser-check." k) v))
(load-file (str pack-dir "/server.clj"))
(if (:running? opts) (checks!) (with-server checks!))
