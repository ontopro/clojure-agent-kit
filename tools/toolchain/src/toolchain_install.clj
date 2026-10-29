(ns toolchain-install
  "The pack's edge: fetching an archive, taking its sha256, unpacking it into the cache.

  `toolchain-sum` (plan 7.0, item 8.1b): each platform's archive of a tool fetched once into a
  temporary folder, its sha256 taken and set beside the publisher's, the lines to paste into the
  matrix printed; the paste is the person's, as a pin move is.

  `toolchain-install` (8.1c): the steps of `toolchain-download-matrix/plan`, walked - an archive
  fetched into `<cache>/downloads/` (or the copy there reused while its sha256 is the matrix's),
  its sha256 compared with the matrix's before anything is unpacked (a mismatch deletes the file
  and fails by name - never installed), unpacked into `<cache>/<tool>/<version>/`; the Clojure
  CLI laid out by `cli-layout`, as the official posix-install.sh does, its own `install.sh` being
  Homebrew's. `--link <dir>` puts each tool's executables in `<dir>`, for the KIT's toolchain
  image, whose PATH names no version.

  `fetch!` and `sha256-of` are the seam a test redefines; `sum-row`, `cli-layout` and the plan are
  pure."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [toolchain-download-matrix :as dm]
            [toolchain-status :as status]))

(defn fetch!
  "`url` to the file `to`, following redirects; throws on a status that is not 200."
  [url to]
  (let [r (http/get url {:as :stream :throw false})]
    (when-not (= 200 (:status r))
      (throw (ex-info (str url " answered " (:status r)) {:url url :status (:status r)})))
    (with-open [in (:body r)] (io/copy in (io/file to)))
    to))

(defn fetch-text!
  "`url`'s body as text, or nil when it does not answer 200 - a publisher that publishes no
  checksum file."
  [url]
  (let [r (http/get url {:throw false})]
    (when (= 200 (:status r)) (:body r))))

(defn sha256-of
  "The file's sha256, lower-case hex."
  [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream (io/file f))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n) (.update md buf 0 n) (recur)))))
    (format "%064x" (BigInteger. 1 (.digest md)))))

(defn sum-row
  "One archive's row: the sha256 taken, the publisher's (nil when it publishes none), the matrix's,
  and what they say together - `:matches`, `:differs` from the publisher, `:unpublished`."
  [{:keys [platform file sha256]} taken published]
  {:platform platform :file file :sha256 taken :matrix sha256 :published published
   :says (cond (nil? published) :unpublished
               (= taken published) :matches
               :else :differs)})

(defn sum-lines
  "The rows as lines to read and paste: per platform, the matrix's `{:sha256 \"…\"}` and the word."
  [tool version rows]
  (into [(str (name tool) " " version)]
        (for [{:keys [platform file sha256 matrix says]} rows]
          (str "  " (if platform (str platform " ") "") "{:sha256 \"" sha256 "\"}  ; " file " - "
               (case says
                 :matches "the publisher's checksum matches"
                 :differs "DIFFERS from the publisher's checksum - not to be pasted"
                 :unpublished "the publisher publishes no checksum: computed here")
               (cond (str/blank? matrix) ", the matrix has none yet"
                     (= matrix sha256) ", the matrix has it"
                     :else ", the matrix has ANOTHER")))))

(defn sum-main
  "The `toolchain-sum <tool> [<version>]` task. Exit 1 when an archive differs from its publisher's
  checksum."
  [pack-dir [tool version]]
  (let [matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
        k (keyword tool)
        _ (when-not (get matrix k)
            (binding [*out* *err*]
              (println "toolchain-sum <tool> [<version>] - a tool of download-matrix.edn:"
                       (str/join ", " (map name (keys matrix)))))
            (System/exit 1))
        matrix (cond-> matrix version (assoc-in [k :version] version))
        dir (fs/create-temp-dir {:prefix "toolchain-sum-"})
        ;; the folder deleted before any exit: System/exit runs no finally
        rows (try
               (vec (for [a (dm/assets matrix k)]
                      (let [f (str dir "/" (:file a))]
                        (binding [*out* *err*] (println "fetching" (:url a)))
                        (fetch! (:url a) f)
                        (let [taken (sha256-of f)]
                          (fs/delete f)
                          (sum-row a taken (some-> (:checksum-url a) fetch-text!
                                                   (dm/published-sha256 (:file a))))))))
               (finally (fs/delete-tree dir)))]
    (run! println (sum-lines k (get-in matrix [k :version]) rows))
    (when (some #(= :differs (:says %)) rows) (System/exit 1))))

;; ---------------------------------------------------------------- the install

(defn verify!
  "`file`'s sha256 against `sha256`; on a mismatch the file is deleted and the install refused by
  name. The file when it holds."
  [file sha256]
  (let [got (sha256-of file)]
    (when-not (= got sha256)
      (fs/delete-if-exists file)
      (throw (ex-info (str (fs/file-name file) ": its sha256 is " got ", the download matrix says " sha256
                           " - deleted, nothing installed")
                      {:file (str file) :sha256 got :expected sha256})))
    file))

(defn unpack!
  "The archive `archive` (`:tar.gz` or `:zip`) unpacked into the folder `to`."
  [archive kind to]
  (fs/create-dirs to)
  (case kind
    :tar.gz (p/shell {:out :string :err :string} "tar" "-xzf" (str archive) "-C" (str to))
    :zip (fs/unzip archive to {:replace-existing true})
    (throw (ex-info (str "the install unpacks .tar.gz and .zip, not " kind) {:unpack kind})))
  to)

(defn cli-layout
  "The Clojure CLI's tools tarball laid out under `prefix`, as the official posix-install.sh does
  it (plan 7.0, item 8.2: its own `install.sh` is Homebrew's) - pure: the nine files, each
  `{:from <name in the tarball's folder> :to <path> :mode <posix> :subst [placeholder value]}`.
  `clojure` finds its jars by `PREFIX`, `clj` its `clojure` by `BINDIR`."
  [version prefix]
  (let [lib (str prefix "/lib/clojure") bin (str prefix "/bin") man (str prefix "/share/man/man1")
        file (fn [from to mode & [subst]] (cond-> {:from from :to to :mode mode} subst (assoc :subst subst)))]
    [(file "deps.edn" (str lib "/deps.edn") "rw-r--r--")
     (file "example-deps.edn" (str lib "/example-deps.edn") "rw-r--r--")
     (file "tools.edn" (str lib "/tools.edn") "rw-r--r--")
     (file "exec.jar" (str lib "/libexec/exec.jar") "rw-r--r--")
     (file (str "clojure-tools-" version ".jar") (str lib "/libexec/clojure-tools-" version ".jar") "rw-r--r--")
     (file "clojure" (str bin "/clojure") "rwxr-xr-x" ["PREFIX" lib])
     (file "clj" (str bin "/clj") "rwxr-xr-x" ["BINDIR" bin])
     (file "clojure.1" (str man "/clojure.1") "rw-r--r--")
     (file "clj.1" (str man "/clj.1") "rw-r--r--")]))

(defn lay-out!
  "`cli-layout`'s files from the folder `from` into place."
  [layout from]
  (doseq [{f :from to :to mode :mode [placeholder value] :subst} layout
          :let [src (fs/path from f)]]
    (when-not (fs/exists? src)
      (throw (ex-info (str "the Clojure tools tarball has no " f " - its layout changed") {:missing f})))
    (fs/create-dirs (fs/parent to))
    (if placeholder
      (spit to (str/replace (slurp (str src)) placeholder value))
      (fs/copy src to {:replace-existing true}))
    (fs/set-posix-file-permissions to mode)))

(defn- download!
  "The step's archive in `<cache>/downloads/`: the copy there when its sha256 is the matrix's, else
  fetched and verified. Says which."
  [{:keys [asset download]}]
  (fs/create-dirs (fs/parent download))
  (if (and (fs/exists? download) (= (:sha256 asset) (sha256-of download)))
    (println "  reusing" (str download))
    (let [part (str download ".part")]
      (println "  fetching" (:url asset))
      (fetch! (:url asset) part)
      (verify! part (:sha256 asset))
      (fs/move part download {:replace-existing true}))))

(defn install-step!
  "One `:install` step: the archive downloaded and verified, unpacked into a folder beside its
  place, then moved into place - the Clojure CLI laid out by `cli-layout` - and the executable made
  one."
  [matrix {:keys [tool version dir bin download] :as step}]
  (download! step)
  (fs/create-dirs (fs/parent dir))
  (let [tmp (fs/create-temp-dir {:dir (fs/parent dir) :prefix (str (name tool) "-unpacking-")})]
    (try
      (unpack! download (:unpack (get matrix tool)) tmp)
      (fs/delete-tree dir)
      (if (= :clojure tool)
        (lay-out! (cli-layout version dir) (fs/path tmp "clojure-tools"))
        (fs/move tmp dir))
      (finally (fs/delete-tree tmp))))
  (fs/set-posix-file-permissions bin "rwxr-xr-x")
  bin)

(defn link-targets
  "What `--link <dir>` links for each tool installed or kept, as `[name target]` - pure over the
  steps and `list-dir`: the JDK's every executable in its `bin/`, the Clojure CLI's `clojure` and
  `clj`, every other tool its one executable."
  [steps list-dir]
  (mapcat (fn [{:keys [tool bin]}]
            (case tool
              :java (for [f (list-dir (str (fs/parent bin)))] [(str (fs/file-name f)) (str f)])
              :clojure [["clojure" bin] ["clj" (str (fs/parent bin) "/clj")]]
              [[(str (fs/file-name bin)) bin]]))
          (filter #(#{:installed :kept} (:step %)) steps)))

(defn parse-args
  "`[--only a,b] [--link <dir>] [--dry-run]`."
  [args]
  (loop [[a & more] args opts {}]
    (case a
      nil opts
      "--only" (recur (rest more) (assoc opts :only (mapv keyword (str/split (first more) #","))))
      "--link" (recur (rest more) (assoc opts :link (first more)))
      "--dry-run" (recur more (assoc opts :dry-run? true))
      (throw (ex-info (str "toolchain-install [--only <tool>,…] [--link <dir>] [--dry-run] - not " a) {:arg a})))))

(defn install-main
  "The `toolchain-install` task: the plan for this machine, walked; a line per tool; with `--link`,
  the executables linked; then `status`. Exit 1 on a refusal or a failure, said in one line - never
  a stack trace (the person, 2026-10-29: \"tooling that crashes … is bad\")."
  [pack-dir args]
  (try
    (let [{:keys [only link dry-run?]} (parse-args args)
          matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
          platform (dm/platform-key (System/getProperty "os.name") (System/getProperty "os.arch"))
          cache (status/cache-dir (System/getenv) (System/getProperty "user.home"))
          steps (dm/plan matrix cache platform {:only only :running {:bb (System/getProperty "babashka.version")}
                                                :present? fs/executable?})]
      (println (str "toolchain-install into " cache " (" (name platform) ")" (when dry-run? " - a dry run, nothing fetched")))
      (let [done (vec (for [{:keys [tool version step why bin] :as s} steps]
                        (case step
                          :kept (do (println (format "  %-11s %-16s kept, %s" (name tool) version bin)) s)
                          :skip (do (println (format "  %-11s %-16s skipped - %s" (name tool) version why)) s)
                          :install (if dry-run?
                                     (do (println (format "  %-11s %-16s to install from %s" (name tool) version (:url (:asset s)))) s)
                                     (do (println (format "  %-11s %-16s installing" (name tool) version))
                                         (install-step! matrix s)
                                         (println (format "  %-11s %-16s installed, %s" (name tool) version bin))
                                         (assoc s :step :installed))))))]
        (when (and link (not dry-run?))
          (fs/create-dirs link)
          (doseq [[n target] (link-targets done #(sort (fs/list-dir %)))
                  :let [l (fs/path link n)]]
            (fs/delete-if-exists l)
            (fs/create-sym-link l target))
          (println "  linked into" link))
        (run! println (status/lines cache platform (status/rows matrix cache platform fs/executable?)))))
    (catch Exception e
      (binding [*out* *err*] (println (str "toolchain-install: " (or (ex-message e) (str e)))))
      (System/exit 1))))
