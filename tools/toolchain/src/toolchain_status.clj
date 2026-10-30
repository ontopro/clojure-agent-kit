(ns toolchain-status
  "What of the download matrix this machine has: a row per tool it takes, the matrix's version,
  where the pack puts it, and whether it is there. `bb doctor`'s report will read these rows
  as `status --edn` (plan 7.0, item 8.3a) - the format is the contract, held by a test.

  THE PROJECT FOLDER IS THE ONE ABOVE THE CLONE (plan 7.0, item 8.2b; the person, 2026-10-30: \"the
  folder above the clone, KIT/.kit/toolchain is fine - for KIT development too\"). The README clones the
  KIT into the project's folder, which `bb init` makes the workspace, so one rule names it before and
  after `bb init`: the tools in `<folder>/.kit/toolchain` (`KIT_TOOLCHAIN_DIR` overrides), the keys in
  `<folder>/.env`. One clone per project, always inside it (the person: \"go with A\"). A home folder or
  `/` above the clone is refused - `~/.env` is a file people already have - and a folder inside a git
  repository is said, its 400 MB untracked there (`place`).

  A tool is present when its executable is at its cache path; nothing here runs it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [toolchain-download-matrix :as dm]))

(defn clone-of
  "The KIT's clone the pack at `pack-dir` (`<clone>/tools/toolchain`) is in."
  [pack-dir]
  (str (fs/parent (fs/parent (fs/normalize (fs/absolutize pack-dir))))))

(defn project-folder "The folder above the clone `clone`." [clone]
  (str (fs/parent (fs/normalize (fs/absolutize clone)))))

(defn cache-dir
  "The toolchain folder: `env`'s `KIT_TOOLCHAIN_DIR` when set, else `.kit/toolchain` in the project
  folder `folder`."
  [env folder]
  (let [d (get env "KIT_TOOLCHAIN_DIR")]
    (if (str/blank? d) (str folder "/.kit/toolchain") d)))

(defn folder-refusal
  "Why `folder` is no project folder, or nil - pure: the home folder `home`, or `/`. The `.env` there
  would be `~/.env`, a file many people already have."
  [folder home]
  (when (or (= "/" folder) (= (str (fs/normalize home)) folder))
    (str folder " is the folder above the KIT's clone, and it is no project folder ("
         (if (= "/" folder) "the file system's root" "your home folder")
         "): clone the KIT into a project folder - `mkdir <name> && cd <name> && git clone <the KIT> KIT`")))

(defn- repo-root
  "The root of the git repository `folder` is inside, or nil."
  [folder]
  (try (let [{:keys [exit out]} (p/shell {:dir folder :out :string :err :string :continue true}
                                         "git" "rev-parse" "--show-toplevel")]
         (when (zero? exit) (str/trim out)))
       (catch Exception _ nil)))

(defn place
  "Where the project's tools and keys are, for the pack at `pack-dir`: `{:folder :cache :env
  :refusal :note}` - `:refusal` when the folder above the clone is no project folder, `:note` when it is
  inside a git repository."
  [pack-dir env home]
  (let [folder (project-folder (clone-of pack-dir))
        root (repo-root folder)]
    (cond-> {:folder folder :cache (cache-dir env folder) :env (str folder "/.env")}
      (folder-refusal folder home) (assoc :refusal (folder-refusal folder home))
      ;; the repository named: a home folder kept as a git repository (dotfiles) puts every project
      ;; under it here, and the person sees it is that one (Fable 5.1's read of e7a08d3)
      root (assoc :note (str folder " is inside the git repository at " root ": its .kit/ (about 400 MB) and "
                             ".env are untracked files there - ignore them in that repository, or move the project")))))

(defn place-line "The one line each command says of `place`." [{:keys [folder cache env]}]
  (str "the project folder " folder " - the tools in " cache ", the keys in " env))

(defn current-place
  "`place` for this process."
  [pack-dir]
  (place pack-dir (System/getenv) (System/getProperty "user.home")))

(defn commit-of
  "The commit the bbin wrapper at `path` names in its header, or nil - no file, or no header."
  [path]
  (when (fs/regular-file? path) (dm/bbin-commit (slurp (str path)))))

(defn rows
  "A row per tool `platform` takes - `{:tool :version :path :present?}` - `present?` the test of a
  path. bb is present when the bb running this (`running`'s `:bb`) is at or above the matrix's, as
  `toolchain-install` skips it then (the plan's rule), the row carrying `:running`. A row not
  present carries `:fix`, the pack's command that installs it, runnable as printed: `config` is the
  pack's `bb.edn`. A bbin row is present when each command in `<cache>/bin/` names its commit
  (`commit-of`, a path to the commit its header names; `toolchain-download-matrix/in-cache?`) - in
  `bin-dir`, where bbin wrote them: `<cache>/bin` unless the install's `--link` named another."
  [matrix cache platform present? {:keys [running config commit-of bin-dir] :or {commit-of (constantly nil)}}]
  (for [t (dm/machine-tools matrix platform nil)
        :let [v (:version (get matrix t))
              bin-dir (or bin-dir (str cache "/bin"))
              p (if (dm/bbin? matrix t) (str bin-dir "/" (get-in matrix [t :bin])) (dm/bin-path matrix cache t platform))
              run (when (= :bb t) (get running :bb))
              running? (boolean (and run (not (neg? (dm/compare-versions run v)))))
              here? (or running? (dm/in-cache? matrix t p bin-dir present? commit-of))]]
    (cond-> {:tool t :version v :path p :present? here?}
      running? (assoc :running run)
      (not here?) (assoc :fix (str "bb --config " config " toolchain-install --only " (name t))))))

(defn lines
  "The rows as a person reads them, a row not present with the command that installs it - under the
  place line, which names the folder (Fable 5.1's read of `e7a08d3`: once)."
  [platform rows]
  (into [(str "the tools for " (name platform) ":")]
        (for [{:keys [tool version path present? running fix]} rows]
          (format "  %-17s %-16s %s" (name tool) version
                  (cond running (str "the bb running this, " running)
                        present? path
                        :else (str "not installed - " fix))))))

(defn main
  "The `status` task: the rows for this machine, `--edn` as the map the doctor will read - the place
  in it. A folder that is no project folder: said, exit 1. A machine the matrix publishes no build for:
  no rows, `:unpublished` the line it is told (`dm/unpublished-line`), exit 0 - the one answer the
  doctor, `bb self-check` and `toolchain-env` read."
  [pack-dir args]
  (let [{:keys [cache refusal note] :as at} (current-place pack-dir)
        matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
        platform (dm/platform-key (System/getProperty "os.name") (System/getProperty "os.arch"))
        unpublished (when-not platform (dm/unpublished-line (System/getProperty "os.name") (System/getProperty "os.arch")))
        rs (when platform
             (rows matrix cache platform fs/executable?
                   {:running {:bb (System/getProperty "babashka.version")} :config (str pack-dir "/bb.edn")
                    :commit-of commit-of}))]
    (if (some #{"--edn"} args)
      (prn (cond-> (assoc (select-keys at [:folder :env :refusal :note]) :cache cache :platform platform :rows (vec rs))
             unpublished (assoc :unpublished unpublished)))
      (do (println (place-line at))
          (when note (println (str "  note: " note)))
          (when refusal (println (str "  refused: " refusal)))
          (if unpublished (println (str "  " unpublished)) (run! println (lines platform rs)))))
    (when refusal (System/exit 1))))
