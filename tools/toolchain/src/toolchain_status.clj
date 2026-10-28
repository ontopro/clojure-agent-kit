(ns toolchain-status
  "What of the download matrix this machine has: a row per tool it takes, the matrix's version,
  where the pack puts it, and whether it is there. `bb doctor` will read these rows from the pack
  as `status --edn` (plan 7.0, item 8.2e) - the format is the contract, held by a test.

  The cache is `KIT_TOOLCHAIN_DIR`, else `~/.kit/toolchain/`. A tool is present when its executable
  is at its cache path; nothing here runs it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [toolchain-download-matrix :as dm]))

(defn cache-dir
  "The toolchain cache: `env`'s `KIT_TOOLCHAIN_DIR` when set, else `~/.kit/toolchain` under `home`."
  [env home]
  (let [d (get env "KIT_TOOLCHAIN_DIR")]
    (if (str/blank? d) (str home "/.kit/toolchain") d)))

(defn rows
  "A row per tool `platform` takes - `{:tool :version :path :present?}` - `present?` the test of a path."
  [matrix cache platform present?]
  (for [t (dm/machine-tools matrix platform nil)
        :let [p (dm/bin-path matrix cache t platform)]]
    {:tool t :version (:version (get matrix t)) :path p :present? (boolean (present? p))}))

(defn lines
  "The rows as a person reads them."
  [cache platform rows]
  (into [(str "toolchain cache " cache " (" (name platform) ")")]
        (for [{:keys [tool version path present?]} rows]
          (format "  %-10s %-16s %s" (name tool) version (if present? path "not installed")))))

(defn main
  "The `status` task: the rows for this machine, `--edn` as the map the doctor will read."
  [pack-dir args]
  (let [matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
        platform (dm/platform-key (System/getProperty "os.name") (System/getProperty "os.arch"))
        cache (cache-dir (System/getenv) (System/getProperty "user.home"))
        rs (rows matrix cache platform fs/executable?)]
    (if (some #{"--edn"} args)
      (prn {:cache cache :platform platform :rows (vec rs)})
      (run! println (lines cache platform rs)))))
