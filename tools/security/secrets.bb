#!/usr/bin/env bb
;; A secret committed by mistake. Run from the repository to scan:
;;
;;   bb --config <kit>/tools/security/bb.edn secrets (--from <rev> | --all) [--routes <file>]
;;        [--out <dir>] [--record <file>]
;;
;; `--from <rev>` reads every commit after <rev> to HEAD - each commit's own added lines, so a
;; secret added and removed inside the range is still found; `--all` reads HEAD's whole
;; history. `security_scan.clj` has the rules; the routes file's `:secrets-allowed` holds a hit
;; the project has read and allowed. THE MATCHED TEXT IS NEVER PRINTED OR WRITTEN: a hit is the
;; file, the line, the commit and the rule.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp]
         '[clojure.string :as str])

(def pack-dir (str (fs/parent (fs/absolutize *file*))))
(load-file (str pack-dir "/src/security_check.clj"))
(load-file (str pack-dir "/src/security_scan.clj"))
(alias 'sc 'security-check)
(alias 'ss 'security-scan)

(defn parse-args [args]
  (loop [[a & more] args m {}]
    (cond (nil? a) m
          (= a "--from") (recur (rest more) (assoc m :from (first more)))
          (= a "--all") (recur more (assoc m :all? true))
          (= a "--routes") (recur (rest more) (assoc m :routes (first more)))
          (= a "--out") (recur (rest more) (assoc m :out (str (fs/absolutize (first more)))))
          (= a "--record") (recur (rest more) (assoc m :record (str (fs/absolutize (first more)))))
          :else (do (println "unknown argument:" a) (System/exit 2)))))

(def opts (parse-args *command-line-args*))
(when-not (or (:from opts) (:all? opts))
  (println "say what to read: --from <rev> (the commits after it) or --all (HEAD's whole history)")
  (System/exit 2))
(def routes (when-let [f (:routes opts)]
              (when-not (fs/exists? f) (println "no routes file at" f) (System/exit 2))
              (edn/read-string (slurp f))))
(def rev-range (if (:all? opts) "HEAD" (str (:from opts) "..HEAD")))

(defn out-dir []
  (or (:out opts) (System/getenv "SECURITY_OUT")
      (let [here (fs/canonicalize ".") parent (fs/parent here)]
        (if (and parent (fs/exists? (fs/path parent "workspace.edn")))
          (str (fs/path parent "work" "security" (fs/file-name here)))
          (str (fs/absolutize ".local/security"))))))

(defn git [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} "git" args)]
    (when-not (zero? (:exit r))
      (println "git" (str/join " " args) "failed:" (str/trim (:err r)))
      (System/exit 2))
    (:out r)))

(let [lines (ss/added-lines (git "log" "-p" "--unified=0" "--no-color" "--no-ext-diff" "--format=commit %h" rev-range))
      files (ss/added-files (git "log" "--diff-filter=A" "--name-only" "--no-color" "--format=commit %h" rev-range))
      rows (ss/secret-rows lines files (:secrets-allowed routes) rev-range)
      counts (sc/counts rows)
      record {:security/at (str (java.time.LocalDateTime/now (java.time.ZoneId/systemDefault)))
              :scan :secrets :range rev-range :head (str/trim (git "rev-parse" "--short" "HEAD"))
              :routes (:routes opts) :counts counts :ok? (zero? (:fail counts)) :rows rows}
      dir (out-dir)
      edn-text (str ";; Written by the KIT's security pack (`tools/security/`). Do not edit; run it again.\n"
                    (with-out-str (pp/pprint record)))]
  (println (str "\n  secrets in " rev-range "\n"))
  (println (sc/table rows))
  (fs/create-dirs dir)
  (spit (str (fs/path dir "security-secrets.edn")) edn-text)
  (spit (str (fs/path dir "security-secrets.md")) (sc/markdown {:base (str "secrets in " rev-range) :at (:security/at record)} rows))
  (when-let [f (:record opts)]
    (some-> (fs/parent f) fs/create-dirs)
    (spit f edn-text))
  (println (format "\n  %d ok, %d warn, %d fail, %d skipped - %s/security-secrets.md%s"
                   (:ok counts) (:warn counts) (:fail counts) (:skipped counts) dir
                   (if-let [f (:record opts)] (str ", recorded to " f) "")))
  (when-not (:ok? record) (System/exit 1)))
