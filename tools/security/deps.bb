#!/usr/bin/env bb
;; The dependencies' published advisories. Run from the application's folder:
;;
;;   bb --config <kit>/tools/security/bb.edn deps [--routes <file>] [--out <dir>] [--record <file>]
;;
;; The resolved runtime classpath (`clojure -X:deps list` - what ships, not a test or build
;; alias's), every library asked of OSV (osv.dev, the GitHub advisory database among its
;; sources) in one request, no key and no download. `security_scan.clj` has the judgements;
;; the routes file's `:accepted` holds an advisory the project has read and accepted.

(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[babashka.process :as p]
         '[cheshire.core :as json]
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
          (= a "--routes") (recur (rest more) (assoc m :routes (first more)))
          (= a "--out") (recur (rest more) (assoc m :out (str (fs/absolutize (first more)))))
          (= a "--record") (recur (rest more) (assoc m :record (str (fs/absolutize (first more)))))
          :else (do (println "unknown argument:" a) (System/exit 2)))))

(def opts (parse-args *command-line-args*))
(def routes (when-let [f (:routes opts)]
              (when-not (fs/exists? f) (println "no routes file at" f) (System/exit 2))
              (edn/read-string (slurp f))))
(def today (str (java.time.LocalDate/now)))

(defn out-dir []
  (or (:out opts) (System/getenv "SECURITY_OUT")
      (let [here (fs/canonicalize ".") parent (fs/parent here)]
        (if (and parent (fs/exists? (fs/path parent "workspace.edn")))
          (str (fs/path parent "work" "security" (fs/file-name here)))
          (str (fs/absolutize ".local/security"))))))

(defn clojure-out
  "The Clojure CLI's output for `args` in this folder, or [nil why]."
  [& args]
  (let [r (try (apply p/shell {:out :string :err :string :continue true} "clojure" args)
               (catch Exception e {:exit -1 :err (ex-message e)}))]
    (if (zero? (:exit r)) [(:out r) nil] [nil (str "clojure " (str/join " " args) " failed: " (str/trim (str (:err r))))])))

(defn query-osv
  "`{name [osv-record ...]}` for the libraries with an advisory: the batch query for the ids,
  then each advisory's record, once."
  [libs]
  (let [resp (http/post "https://api.osv.dev/v1/querybatch"
                        {:headers {"Content-Type" "application/json"} :timeout 60000
                         :body (json/generate-string
                                {:queries (for [l libs] {:package {:ecosystem "Maven" :name (:name l)} :version (:version l)})})})
        results (:results (json/parse-string (:body resp) true))
        ids (into {} (for [[l r] (map vector libs results) :when (seq (:vulns r))] [(:name l) (mapv :id (:vulns r))]))
        record (memoize (fn [id] (json/parse-string (:body (http/get (str "https://api.osv.dev/v1/vulns/" id) {:timeout 30000})) true)))]
    (update-vals ids #(mapv record %))))

(defn scan []
  (let [[listing why] (clojure-out "-X:deps" "list")
        [tree _] (when listing (clojure-out "-Stree"))]
    (if-not listing
      (ss/dep-skipped why)
      (let [libs (ss/parse-deps-list listing)]
        (try (ss/dep-rows libs (query-osv libs) (ss/parse-tree tree) (:accepted routes) today)
             (catch Exception e (ss/dep-skipped (str "OSV did not answer (" (ex-message e) "): nothing was checked"))))))))

(let [rows (scan)
      counts (sc/counts rows)
      record {:security/at (str (java.time.LocalDateTime/now (java.time.ZoneId/systemDefault)))
              :scan :deps :source "osv.dev" :app (str (fs/file-name (fs/canonicalize ".")))
              :routes (:routes opts) :counts counts :ok? (zero? (:fail counts)) :rows rows}
      dir (out-dir)
      edn-text (str ";; Written by the KIT's security pack (`tools/security/`). Do not edit; run it again.\n"
                    (with-out-str (pp/pprint record)))]
  (println "\n  the dependencies' published advisories\n")
  (println (sc/table rows))
  (fs/create-dirs dir)
  (spit (str (fs/path dir "security-deps.edn")) edn-text)
  (spit (str (fs/path dir "security-deps.md")) (sc/markdown {:base (str (:app record) " dependencies") :at (:security/at record)} rows))
  (when-let [f (:record opts)]
    (some-> (fs/parent f) fs/create-dirs)
    (spit f edn-text))
  (println (format "\n  %d ok, %d warn, %d fail, %d skipped - %s/security-deps.md%s"
                   (:ok counts) (:warn counts) (:fail counts) (:skipped counts) dir
                   (if-let [f (:record opts)] (str ", recorded to " f) "")))
  (when-not (:ok? record) (System/exit 1)))
