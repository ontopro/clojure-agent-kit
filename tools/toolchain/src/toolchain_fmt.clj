(ns toolchain-fmt
  "`fmt` and `fmt-check [--dir <d>] <paths…>`: cljfmt as the pack's dependency (plan 7.0, item 8.2a) -
  the version is the pack's `bb.edn`'s, the config is the project's as cljfmt finds it, and the
  output a caller reads is cljfmt's own: `<path> has incorrect formatting` and the diff, the count,
  exit 1 for a file formatted incorrectly and 2 for one that could not be read.

  THE CONFIG, FILE BY FILE. cljfmt's command line reads one config, the one it finds from the folder
  it runs in. Gate 0 ran it from each file's folder, gate 1 from the project's - and a file in a
  folder with no config of its own (the KIT's `tools/*/src`) got cljfmt's defaults from the first and
  the harness's from the second: two gates, two rules. Here a file is held to the first config found
  from its own folder up, else the one found from `--dir` up (the folder a gate runs in), else
  cljfmt's defaults - one answer for gate 0 and gate 1 alike. `--dir` is also what a path is relative
  to, and the diff's names.

  cljfmt's console report calls `System/exit` after a check; `collect` keeps each file's result, and
  `main` says it."
  (:require [babashka.fs :as fs]
            [cljfmt.config :as config]
            [cljfmt.tool :as tool]))

(defn config-file
  "The config that governs `file`: the first cljfmt finds from the file's folder up, else the first
  from `dir` up; nil for cljfmt's defaults."
  [dir file]
  (or (config/find-config-file (str (fs/parent (fs/absolutize file))))
      (config/find-config-file (str (fs/absolutize dir)))))

(defn files
  "The files `paths` name, from `dir`: a file as given, a folder's files by the `:file-pattern` of the
  config `dir` finds, hidden ones included as cljfmt includes them; a path not there skipped. Absolute,
  sorted, each once."
  [dir paths]
  (let [pattern (:file-pattern (config/load-config (str dir)))]
    (->> paths
         (mapcat (fn [p]
                   (let [f (fs/absolutize (fs/path dir p))]
                     (cond (fs/directory? f) (->> (file-seq (fs/file f))
                                                  (filter #(.isFile ^java.io.File %))
                                                  (map str)
                                                  (filter #(re-find pattern %)))
                           (fs/exists? f) [(str f)]))))
         (map #(str (fs/normalize %)))
         distinct
         sort)))

(defn- collect
  "A cljfmt report that keeps each file's result and exits nothing."
  [event ctx data]
  (case event
    (:check/initial :fix/initial) (assoc ctx :results [])
    (:check/file :fix/file) (update ctx :results conj data)
    (:check/summary :fix/summary) ctx))

(defn- run [tool-fn dir paths]
  (let [dir (str (fs/absolutize dir))]
    (->> (files dir paths)
         (group-by #(config-file dir %))
         (mapcat (fn [[cfg group]]
                   (:results (tool-fn (merge (config/load-config (if cfg (str cfg) dir))
                                             {:paths group :project-root dir :report collect})))))
         (sort-by (comp str :file)))))

(defn check
  "Each file `paths` name from `dir`, checked: `{:ok? :files [{:file :status :diff :error}]}`, `:status`
  `:okay`, `:incorrect` (with the diff) or `:error` (with the exception's message)."
  [dir paths]
  (let [rs (for [{:keys [file counts diff exception]} (run tool/check-no-config dir paths)]
             (cond-> {:file (str file)
                      :status (cond (pos? (:error counts 0)) :error
                                    (pos? (:incorrect counts 0)) :incorrect
                                    :else :okay)}
               diff (assoc :diff diff)
               exception (assoc :error (ex-message exception))))]
    {:ok? (every? #(= :okay (:status %)) rs) :files (vec rs)}))

(defn fix!
  "Each file `paths` name from `dir`, reformatted in place where cljfmt would change it:
  `{:files [{:file :reformatted? :error}]}`."
  [dir paths]
  {:files (vec (for [{:keys [file reformatted exception]} (run tool/fix-no-config dir paths)]
                 (cond-> {:file (str file) :reformatted? (boolean reformatted)}
                   exception (assoc :error (ex-message exception)))))})

(defn- parse-args
  "`[--dir <d>] <paths…>` → `{:dir :paths}`."
  [args]
  (loop [[a & more :as args] args, opts {:dir "." :paths []}]
    (cond (empty? args) opts
          (= "--dir" a) (recur (rest more) (assoc opts :dir (first more)))
          :else (recur more (update opts :paths conj a)))))

(defn- warn [& xs] (binding [*out* *err*] (apply println xs)))

(defn main
  "The pack's `fmt` and `fmt-check` tasks: `mode` `:fix` or `:check`. Output and exit as cljfmt's own."
  [mode args]
  (let [{:keys [dir paths]} (parse-args args)
        rel #(str (fs/relativize (fs/absolutize dir) %))]
    (when (empty? paths)
      (warn (str "fmt" (when (= :check mode) "-check") ": name the paths - [--dir <d>] <paths…>"))
      (System/exit 2))
    (case mode
      :check
      (let [{:keys [files]} (check dir paths)
            bad (filter #(= :incorrect (:status %)) files)
            errs (filter #(= :error (:status %)) files)]
        (doseq [{:keys [file error]} errs] (warn "Failed to format file:" (rel file)) (warn error))
        (doseq [{:keys [file diff]} bad] (warn (rel file) "has incorrect formatting") (println diff))
        (when (seq errs) (warn (count errs) "file(s) could not be parsed for formatting"))
        (when (seq bad) (warn (count bad) "file(s) formatted incorrectly"))
        (when-not (or (seq bad) (seq errs)) (warn "All source files formatted correctly"))
        (System/exit (cond (seq errs) 2 (seq bad) 1 :else 0)))
      :fix
      (let [{:keys [files]} (fix! dir paths)]
        (doseq [{:keys [file reformatted? error]} files]
          (when error (warn "Failed to format file:" (rel file)) (warn error))
          (when reformatted? (warn "Reformatted" (rel file))))
        (System/exit (if (some :error files) 2 0))))))
