(ns harness.setup.download-matrix
  "THE DOWNLOAD MATRIX, AS THE HARNESS READS IT (plan 7.0, item 8.3a). The toolchain pack owns the file,
  `tools/toolchain/download-matrix.edn` in the KIT's clone, and the harness never loads the pack: it reads the
  file as EDN here, once, for the doctor and `bb kit-pins`. Two of the pack's rules are copied, a test holding
  each to the pack's own: a bbin row's `:version` is its commit's first seven characters
  (`commit-version`), and a machine's platform is one the matrix publishes for, or nil (`platform`).

  `reference` is the doctor's: each of its rows the matrix names, at the matrix's version - a tool by its
  own name, and a bbin row's commands (clojure-mcp-light's three) by theirs."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn clone-dir
  "The KIT's clone this harness is in: the folder above `harness/`, found from the harness's own
  resources, so it holds wherever the command was started."
  []
  (some-> (io/resource "toolchain.edn") io/file fs/parent fs/parent fs/parent str))

(defn path [clone] (str (fs/path clone "tools" "toolchain" "download-matrix.edn")))

(defn commit-version
  "`row` with its `:version` the first seven characters of its `:git/sha` when it has a sha and no
  version - pure; the pack's `toolchain-download-matrix/commit-version`."
  [{:keys [version] sha :git/sha :as row}]
  (if (and sha (nil? version)) (assoc row :version (subs sha 0 7)) row))

(defn read-matrix
  "The matrix in the clone `clone`, each row's version as `commit-version` gives it; nil when the clone
  has no matrix."
  [clone]
  (let [f (path clone)]
    (when (fs/exists? f)
      (update-vals (edn/read-string (slurp f)) commit-version))))

(defn platform
  "This machine's platform as the matrix keys it - one bb's row publishes an archive for - or nil: the
  pack's `platform-key`, from the file rather than the pack's list."
  [matrix os-name os-arch]
  (let [os (cond (str/starts-with? os-name "Mac") "macos"
                 (str/starts-with? os-name "Linux") "linux")
        arch (case os-arch
               ("aarch64" "arm64") "aarch64"
               ("amd64" "x86_64") "amd64"
               nil)
        k (when (and os arch) (keyword (str os "-" arch)))]
    (when (contains? (get-in matrix [:bb :platforms]) k) k)))

(defn reference
  "`{row version}` - each doctor row the matrix names, at the matrix's version: a tool by its own name, a
  bbin row's commands by theirs; an image-only tool none (a machine never takes it)."
  [matrix]
  (into {}
        (mapcat (fn [[tool {:keys [version image-only commands]}]]
                  (when-not image-only
                    (if commands
                      (for [c commands] [(keyword (:name c)) version])
                      [[tool version]]))))
        matrix))

(defn matrix-tool
  "The matrix's tool a doctor row stands for: its own name, or the bbin row whose command it is."
  [matrix row]
  (or (some (fn [[tool {:keys [commands]}]] (when (some #(= (name row) (:name %)) commands) tool)) matrix)
      (when (contains? matrix row) row)))
