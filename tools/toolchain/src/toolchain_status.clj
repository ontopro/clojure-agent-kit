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
  "A row per tool `platform` takes - `{:tool :version :path :present?}` - `present?` the test of a
  path. bb is present when the bb running this (`running`'s `:bb`) is at or above the matrix's, as
  `toolchain-install` skips it then (the plan's rule), the row carrying `:running`. A row not
  present carries `:fix`, the pack's command that installs it, runnable as printed: `config` is the
  pack's `bb.edn`."
  [matrix cache platform present? {:keys [running config]}]
  (for [t (dm/machine-tools matrix platform nil)
        :let [v (:version (get matrix t))
              p (dm/bin-path matrix cache t platform)
              run (when (= :bb t) (get running :bb))
              running? (boolean (and run (not (neg? (dm/compare-versions run v)))))
              here? (or running? (boolean (present? p)))]]
    (cond-> {:tool t :version v :path p :present? here?}
      running? (assoc :running run)
      (not here?) (assoc :fix (str "bb --config " config " toolchain-install --only " (name t))))))

(defn lines
  "The rows as a person reads them, a row not present with the command that installs it."
  [cache platform rows]
  (into [(str "toolchain cache " cache " (" (name platform) ")")]
        (for [{:keys [tool version path present? running fix]} rows]
          (format "  %-10s %-16s %s" (name tool) version
                  (cond running (str "the bb running this, " running)
                        present? path
                        :else (str "not installed - " fix))))))

(defn main
  "The `status` task: the rows for this machine, `--edn` as the map the doctor will read."
  [pack-dir args]
  (let [matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
        platform (dm/platform-key (System/getProperty "os.name") (System/getProperty "os.arch"))
        cache (cache-dir (System/getenv) (System/getProperty "user.home"))
        rs (rows matrix cache platform fs/executable?
                 {:running {:bb (System/getProperty "babashka.version")} :config (str pack-dir "/bb.edn")})]
    (if (some #{"--edn"} args)
      (prn {:cache cache :platform platform :rows (vec rs)})
      (run! println (lines cache platform rs)))))
