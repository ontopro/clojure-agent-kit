(ns toolchain-fmt
  "`fmt` and `fmt-check [--dir <d>] <paths…>`: cljfmt's binary, at the download matrix's version, with
  each path held to its own config (plan 7.0, item 8.2a). The binary is the toolchain cache's
  (`toolchain-install --only cljfmt`), else the one on the PATH - the doctor says when that one is not
  the matrix's version. A BINARY, NOT THE LIBRARY (the person, 2026-10-30, \"go with B\"): bb loading
  cljfmt into its interpreter cost 75 ms a call over the binary's 12-28, on every write gate 0 repairs.

  THE CONFIG, PATH BY PATH. cljfmt reads one config a run: `--config`, else the one it finds from the
  folder it runs in. Gate 0 ran it from each file's folder, gate 1 from the project's - and a file in a
  folder with no config of its own (the KIT's `tools/*/src`) got cljfmt's defaults from the first and
  the harness's from the second: two gates, two rules. Here a path is held to the first config found
  from its own folder up (a folder's from itself), else the one found from `--dir` up, else cljfmt's
  defaults; the paths sharing a config are one run of the binary, from `--dir`, with `--config` when
  there is one. cljfmt's own search, file names and order: `.cljfmt.edn`, then `cljfmt.edn` - its
  `.clj` forms only under `--read-clj-config-files`, which the KIT never passes. A folder named is held
  to one config, as cljfmt holds it; a config nested inside it is cljfmt's to ignore too.

  The output and the exits are the binary's own: `<path> has incorrect formatting` and the diff, the
  count, exit 1 for a file formatted incorrectly, 2 for one it could not read - one summary per run,
  and the highest exit of the runs."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [toolchain-download-matrix :as dm]
            [toolchain-status :as status]))

(def config-names
  "The config files cljfmt finds, in its order, without `--read-clj-config-files`."
  [".cljfmt.edn" "cljfmt.edn"])

(defn find-config
  "The first config cljfmt would find from the folder `dir` up, or nil."
  [dir]
  (some (fn [d] (some #(let [f (fs/path d %)] (when (fs/regular-file? f) (str f))) config-names))
        (take-while some? (iterate fs/parent (fs/absolutize dir)))))

(defn config-file
  "The config that governs `path` (relative to `dir`): the first found from its own folder up - a
  folder's from itself - else the first from `dir` up; nil for cljfmt's defaults."
  [dir path]
  (let [f (fs/absolutize (fs/path dir path))]
    (or (find-config (if (fs/directory? f) f (fs/parent f)))
        (find-config dir))))

(defn runs
  "The binary's runs over `paths` from `dir` - pure but for the config search: one per config, the
  paths in the order given, `[argv …]`. `mode` `:check` or `:fix`."
  [bin mode dir paths]
  (for [[cfg ps] (group-by #(config-file dir %) paths)]
    (vec (concat [bin (name mode)] (when cfg ["--config" cfg]) ps))))

(defn binary
  "The cljfmt to run: the toolchain cache's at the matrix's version, else the PATH's; nil for neither."
  [pack-dir]
  (let [matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
        platform (try (dm/platform-key (System/getProperty "os.name") (System/getProperty "os.arch"))
                      (catch Exception _ nil))
        cached (when platform
                 (dm/bin-path matrix (status/cache-dir (System/getenv) (System/getProperty "user.home")) :cljfmt platform))]
    (cond (and cached (fs/executable? cached)) cached
          :else (some-> (fs/which "cljfmt") str))))

(defn- parse-args
  "`[--dir <d>] <paths…>` → `{:dir :paths}`."
  [args]
  (loop [[a & more :as args] args, opts {:dir "." :paths []}]
    (cond (empty? args) opts
          (= "--dir" a) (recur (rest more) (assoc opts :dir (first more)))
          :else (recur more (update opts :paths conj a)))))

(defn- warn [& xs] (binding [*out* *err*] (apply println xs)))

(defn main
  "The pack's `fmt` and `fmt-check` tasks: `mode` `:fix` or `:check`. The binary's output, its exit
  the highest of its runs; exit 2 with no path, or no binary."
  [pack-dir mode args]
  (let [{:keys [dir paths]} (parse-args args)
        task (if (= :check mode) "fmt-check" "fmt")
        bin (binary pack-dir)]
    (cond
      (empty? paths) (do (warn (str task ": name the paths - [--dir <d>] <paths…>")) (System/exit 2))
      (nil? bin) (do (warn (str task ": no cljfmt - not in the toolchain cache, not on the PATH: "
                                "bb --config " pack-dir "/bb.edn toolchain-install --only cljfmt"))
                     (System/exit 2))
      :else
      (System/exit
       (reduce max 0 (for [argv (runs bin mode dir paths)]
                       (:exit (apply p/shell {:dir (str dir) :continue true} argv))))))))
