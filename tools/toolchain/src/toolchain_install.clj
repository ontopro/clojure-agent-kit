(ns toolchain-install
  "The pack's edge: fetching an archive, taking its sha256, unpacking it into the toolchain folder.

  `toolchain-sum` (plan 7.0, item 8.1b): each platform's archive of a tool fetched once into a
  temporary folder, its sha256 taken and set beside the publisher's, the lines to paste into the
  matrix printed; the paste is the person's, as a pin move is.

  `toolchain-install` (8.1c, 8.2b): the steps of `toolchain-download-matrix/plan`, walked - an
  archive fetched into `<folder>/downloads/`, its sha256 compared with the matrix's before anything is
  unpacked (a mismatch deletes the file and fails by name - never installed), unpacked into
  `<folder>/<tool>/<version>/`, then deleted: downloads are not kept (the person, 2026-10-30) -
  `--keep-downloads` keeps them, for the KIT's toolchain image, whose build caches that folder, and a
  copy there is reused while its sha256 is the matrix's. The Clojure CLI laid out by `cli-layout`, as
  the official posix-install.sh does, its own `install.sh` being Homebrew's. Each tool's executables
  are linked into `<folder>/bin/` - the one folder callers put first on the PATH, a version moved by a
  re-link - or into `--link <dir>`, the image's `/usr/local/bin`. clojure-mcp-light has no archive: the
  folder's bbin installs each of its commands there at the matrix's commit, and each wrapper's header is
  read after (`bbin-step!`, 8.2d). The folder is the project's, the one
  above the clone (`toolchain-status/place`); a home folder or `/` there is refused.

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
        _ (when (dm/bbin? matrix k)
            (binding [*out* *err*]
              (println (str "toolchain-sum: " tool " has no archive - bbin installs it from its repository at "
                            "the commit the matrix names, git's own integrity")))
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
  "The archive `archive` (`:tar.gz` or `:zip`) unpacked into the folder `to`; a `:file` - the download
  is the executable (bbin) - copied there under `bin`'s name."
  [archive kind to & [bin]]
  (fs/create-dirs to)
  (case kind
    :file (fs/copy archive (fs/path to bin) {:replace-existing true})
    :tar.gz (let [{:keys [exit err]} (p/shell {:out :string :err :string :continue true}
                                              "tar" "-xzf" (str archive) "-C" (str to))]
              (when-not (zero? exit)
                (throw (ex-info (str "tar could not unpack " (fs/file-name archive) ": " (str/trim err))
                                {:archive (str archive) :exit exit}))))
    :zip (fs/unzip archive to {:replace-existing true})
    (throw (ex-info (str "the install unpacks .tar.gz, .zip and a :file, not " kind) {:unpack kind})))
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

(defn swap-in!
  "The folder `staged` moved into `dir`: an existing `dir` moved aside to `<dir>.old` first - a
  sibling, so a rename - and put back when the move fails, then deleted. `dir` is never missing for
  longer than a rename while `--link`'s links and `toolchain-env`'s PATH point at it (From Fable
  5.1's read of `cf554a7`)."
  [staged dir]
  (let [old (str dir ".old")]
    (fs/delete-tree old)
    (when (fs/exists? dir) (fs/move dir old))
    (try (fs/move staged dir)
         (catch Exception e
           (when (fs/exists? old) (fs/move old dir))
           (throw e)))
    (fs/delete-tree old)))

(defn install-step!
  "One `:install` step: the archive downloaded and verified, unpacked into a folder beside its
  place - the Clojure CLI laid out there by `cli-layout`, its paths the place's - the executable
  made one, and the folder swapped into place (`swap-in!`). The archive deleted after a success, unless
  `keep?`: a failure keeps the verified archive, which `download!` reuses on the retry, whose success
  deletes it - a retry never fetches the JDK's 330 MB again (Fable 5.1's read of `e7a08d3`)."
  [matrix {:keys [tool version dir bin download] :as step} keep?]
  (download! step)
  (fs/create-dirs (fs/parent dir))
  (let [tmp (fs/create-temp-dir {:dir (fs/parent dir) :prefix (str (name tool) "-unpacking-")})
        unpacked (str (fs/path tmp "unpacked"))]
    (try
      (unpack! download (:unpack (get matrix tool)) unpacked (get-in matrix [tool :bin]))
      (let [staged (if (= :clojure tool)
                     (let [s (str (fs/path tmp "laid-out"))]
                       (lay-out! (for [f (cli-layout version dir)] (update f :to #(str s (subs % (count dir)))))
                                 (fs/path unpacked "clojure-tools"))
                       s)
                     unpacked)]
        (fs/set-posix-file-permissions (str staged (subs bin (count dir))) "rwxr-xr-x")
        (swap-in! staged dir)
        (when-not keep?
          (fs/delete-if-exists download)
          (let [d (fs/parent download)]
            (when (and (fs/directory? d) (empty? (fs/list-dir d))) (fs/delete d)))))
      (finally (fs/delete-tree tmp))))
  bin)

(defn bbin-args
  "The `bbin install` arguments for one command of a bbin step - pure: the repository at the commit
  by `--git/sha` (bbin 0.2.5 drops `--tag` and refuses `--sha`: it adds `:latest-sha` unless it sees
  `:git/sha` or `:git/tag`, and its flags give `:sha` and `:tag`), and `--as` with `--main-opts` for a
  command with a `:main`; one with none takes the repository's own."
  [{:git/keys [url sha]} {:keys [main] n :name}]
  (cond-> ["install" url "--git/sha" sha]
    main (into ["--as" n "--main-opts" (pr-str ["-m" main])])))

(defn bbin-error
  "What bbin's failure says, from its output - pure: its `Message:` line, and its `Location:` line when
  there is one; else its last three lines. The stack's tail named the cause only by where it fell."
  [text]
  (let [lines (remove str/blank? (str/split-lines (or text "")))
        field (fn [k] (some #(some->> % (re-matches (re-pattern (str k ":\\s+(.*)"))) second str/trim) lines))]
    (if-let [m (field "Message")]
      (str m (some->> (field "Location") (str " (at ") (#(str % ")"))))
      (str/join " / " (take-last 3 lines)))))

(defn bbin-step!
  "One bbin step: each command installed by the folder's `bbin` (run by its path) into `bin-dir` -
  `BABASHKA_BBIN_BIN_DIR` on these calls alone, never exported - then the header of
  `<bin-dir>/<name>` read: absent, or naming another commit, fails by name. bbin writes into an old
  layout (`~/.babashka/bbin/bin`) whenever one exists, whatever the variable says."
  [{:keys [tool commands] :as step} bbin bin-dir]
  (when-not (and bbin (fs/executable? bbin))
    (throw (ex-info (str (name tool) " is installed by the folder's bbin, which is not installed - "
                         "`--only bbin," (name tool) "`")
                    {:tool tool})))
  (doseq [{n :name :as c} commands
          :let [{:keys [exit err out]} (apply p/shell {:out :string :err :string :continue true
                                                       :extra-env {"BABASHKA_BBIN_BIN_DIR" bin-dir}}
                                              bbin (bbin-args step c))
                got (status/commit-of (fs/path bin-dir n))]]
    (when-not (zero? exit)
      (throw (ex-info (str "bbin could not install " n ": " (bbin-error (str out "\n" err)))
                      {:command n :exit exit})))
    (when-not (= (:git/sha step) got)
      (throw (ex-info (str bin-dir "/" n (if got (str " names commit " (subs got 0 7)) " is not there")
                           " after bbin installed it, not " (subs (:git/sha step) 0 7)
                           (when (fs/exists? (fs/path (System/getProperty "user.home") ".babashka" "bbin" "bin"))
                             " - bbin wrote into its old layout, ~/.babashka/bbin/bin, which wins over BABASHKA_BBIN_BIN_DIR: `bbin migrate`, then again"))
                      {:command n :commit got}))))
  (:bin step))

(defn bbin-path
  "The bbin a bbin step runs: the one an earlier step of `done` installed or kept, else the folder's at
  its cache path when it is there (`executable?`) - `--only clojure-mcp-light`, the fix line `status`
  prints, names no bbin (From Fable 5.1's read of `3dbdcda`). Nil when neither."
  [done matrix cache platform executable?]
  (or (some #(when (and (= :bbin (:tool %)) (#{:installed :kept} (:step %))) (:bin %)) done)
      (let [p (dm/bin-path matrix cache :bbin platform)] (when (executable? p) p))))

(defn self-link?
  "Whether a link at `l` would point at itself - never made: the delete before it removes the file
  `target` names (From Fable 5.1's read of `db92690`)."
  [l target]
  (= (fs/normalize (fs/absolutize l)) (fs/normalize (fs/absolutize target))))

(defn link-targets
  "What `--link <dir>` links for each tool installed or kept, as `[name target]` - pure over the
  steps and `list-dir`: the JDK's every executable in its `bin/`, the Clojure CLI's `clojure` and
  `clj`, every other tool its one executable; a bbin step none - bbin wrote its commands there."
  [steps list-dir]
  (mapcat (fn [{:keys [tool bin]}]
            (case tool
              :java (for [f (list-dir (str (fs/parent bin)))] [(str (fs/file-name f)) (str f)])
              :clojure [["clojure" bin] ["clj" (str (fs/parent bin) "/clj")]]
              [[(str (fs/file-name bin)) bin]]))
          (filter #(and (#{:installed :kept} (:step %)) (not= :bbin (:kind %))) steps)))

(defn parse-args
  "`[--only a,b] [--link <dir>] [--keep-downloads] [--dry-run]`."
  [args]
  (loop [[a & more] args opts {}]
    (case a
      nil opts
      "--only" (recur (rest more) (assoc opts :only (mapv keyword (str/split (first more) #","))))
      "--link" (recur (rest more) (assoc opts :link (first more)))
      "--keep-downloads" (recur more (assoc opts :keep? true))
      "--dry-run" (recur more (assoc opts :dry-run? true))
      (throw (ex-info (str "toolchain-install [--only <tool>,…] [--link <dir>] [--keep-downloads] [--dry-run] - not " a) {:arg a})))))

(defn install-main
  "The `toolchain-install` task: the plan for this machine, walked; a line per tool; the executables
  linked into `<folder>/bin/`, or `--link <dir>`; then `status`. A folder that is no project folder is
  refused before anything is fetched. Exit 1 on a refusal or a failure, said in one line - never
  a stack trace (the person, 2026-10-29: \"tooling that crashes … is bad\")."
  [pack-dir args]
  (try
    (let [{:keys [only link keep? dry-run?]} (parse-args args)
          {:keys [cache refusal note] :as at} (status/current-place pack-dir)
          _ (when refusal (throw (ex-info refusal {})))
          link (or link (str cache "/bin"))
          matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
          platform (or (dm/platform-key (System/getProperty "os.name") (System/getProperty "os.arch"))
                       (throw (ex-info (dm/unpublished-line (System/getProperty "os.name") (System/getProperty "os.arch")) {})))
          steps (dm/plan matrix cache platform {:only only :running {:bb (System/getProperty "babashka.version")}
                                                :present? fs/executable? :commit-of status/commit-of
                                                :bin-dir link})
          bbin-of #(bbin-path % matrix cache platform fs/executable?)]
      (println (status/place-line at))
      (when note (println (str "  note: " note)))
      (println (str "toolchain-install into " cache " (" (name platform) ")" (when dry-run? " - a dry run, nothing fetched")))
      (let [done (reduce
                  (fn [done {:keys [tool version step why bin kind] :as s}]
                    (conj done
                          (case step
                            :kept (do (println (format "  %-17s %-16s kept, %s" (name tool) version bin)) s)
                            :skip (do (println (format "  %-17s %-16s skipped - %s" (name tool) version why)) s)
                            :install (if dry-run?
                                       (do (println (format "  %-17s %-16s to install %s" (name tool) version
                                                            (if (= :bbin kind)
                                                              (str "by bbin into " link ", " (:git/url s) " at " (:git/sha s))
                                                              (str "from " (:url (:asset s))))))
                                           s)
                                       (do (println (format "  %-17s %-16s installing" (name tool) version))
                                           (if (= :bbin kind)
                                             (bbin-step! s (bbin-of done) link)
                                             (install-step! matrix s keep?))
                                           (println (format "  %-17s %-16s installed, %s" (name tool) version bin))
                                           (assoc s :step :installed))))))
                  [] steps)]
        ;; the status before the links, so a link that fails loses no line of it
        (run! println (status/lines platform
                                    (status/rows matrix cache platform fs/executable?
                                                 {:running {:bb (System/getProperty "babashka.version")}
                                                  :config (str pack-dir "/bb.edn")
                                                  :commit-of status/commit-of :bin-dir link})))
        (when-not dry-run?
          (fs/create-dirs link)
          (doseq [[n target] (link-targets done #(sort (fs/list-dir %)))
                  :let [l (fs/path link n)]
                  :when (not (self-link? l target))]
            (fs/delete-if-exists l)
            (fs/create-sym-link l target))
          (println "  linked into" link))))
    (catch Exception e
      (binding [*out* *err*] (println (str "toolchain-install: " (or (ex-message e) (str e)))))
      (System/exit 1))))
