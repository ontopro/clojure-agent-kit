;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.setup.doctor
  "Toolchain doctor: what this stack needs, whether it is here, and what it does.

  A Clojure agentic loop leans on a handful of small binaries that are easy
  to forget and invisible when missing — a loop with no `clj-paren-repair`
  does not fail, it just quietly spends the Coder's retry budget on parens.
  So the toolchain is checked and *reported*, not assumed.

  `bb doctor` renders the table and TWO VERDICTS: *the KIT's gates can run
  here* (the Babashka tier - what `bb gates` needs, no JVM) and *a loop can run
  here* (that plus the JDK, the Clojure CLI and the nREPL bridge). `bb init`
  refuses without the second; `bb gates` runs the doctor for the first only, so
  the KIT's own gates stay JVM-free. `--edn` emits a map of each tool's version
  and status, which `bb kit-pins` and a health record read.

  THE DOWNLOAD MATRIX IS THE REFERENCE (plan 7.0, item 8.3a). Each tool the PATH gives is held to the
  version the matrix names (`harness.setup.download-matrix/reference`) - equal ok, above ok and said,
  below `:too-old`; clojure-mcp-light's commit is equal or `:wrong-version`, a commit having no order. The
  JDK too (the person, 2026-10-30): the true-up installs it like every tool; only on a machine the matrix
  publishes no build for is an own JDK taken, at its `:min`, 21, with 25 said as preferred. A build after
  `+` is no version (`java -version` prints 25.0.5 for the matrix's 25.0.5+8). With no reference -
  `bb upgrade`'s pre-check passes none - the floors alone hold, each entry's `:min`. A project's
  `.mise.toml` is written for the adopter's own shell and read here no longer: it was the floor, its
  `.mise.local.toml` over it (7.4.3), until the matrix became the one reference. Beside the reference the
  report shows the KIT's dated KNOWN-GOOD SET (`resources/known-good.edn`), the versions run together.
  Three projects once failed this check on a version rule that was wrong, not on a tool that was missing:
  a calendar version read as a major, a patch level read as a break.

  A FIX FOR EVERY ROW, AND ONE OFFER. A tool not usable gets the line that fixes it: the eval when the
  project's folder has it and this shell's PATH does not; the true-up when the folder lacks it; its own
  install route for bb and git, which the true-up does not replace, and on a machine the matrix publishes
  no build for. Run by hand at a terminal - no `--tier`, no `--edn` - the doctor offers to run the
  true-up, once, listing each tool with its size and the disk's free space; anything else is printed.

  SAFETY RULE: never probe a tool by handing it an unrecognized flag.
  `clj-paren-repair --version` does not error — it treats `--version` as a
  FILENAME and runs a repair pass, exiting 0. A health check that mutates
  is worse than no health check. Probe with --version only where it is
  documented, else --help, a bbin header, or plain presence on PATH. The three
  bbin tools are RUN, `--help` with an empty stdin and a timeout - each was
  checked to answer it in under a second and write nothing - because being
  installed and on PATH proved, twice, not to mean a tool could run. Their commit
  is the one the bbin header of the command the PATH gives names (`bbin-commits`,
  plan 7.0, item 8.2d): `bbin ls` read bbin's own folder, which is not where the
  toolchain pack puts them."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [harness.setup.download-matrix :as download-matrix]))

;; ---------------------------------------------------------------------------
;; The toolchain
;; ---------------------------------------------------------------------------

(def toolchain-resource "toolchain.edn")

(defn read-toolchain
  "The toolchain table read from `text`, the resource as EDN - the same reader `bb upgrade`
  uses on a release's copy taken from its tag (`git show <tag>:harness/resources/toolchain.edn`),
  so a release's requirements are known without running its code."
  [text]
  (edn/read-string text))

(def toolchain
  "Every tool the loop touches, why, how to ask it its version, and what installs it - the
  table in `resources/toolchain.edn`, where the keys are explained. DATA, NOT CODE, since
  2026-10-15: the doctor reads it, and so does the upgrade, from a tag, without running the
  tag."
  (read-toolchain (slurp (or (io/resource toolchain-resource)
                             (throw (ex-info "toolchain.edn not on the classpath" {:resource toolchain-resource}))))))

(def flag-allowlist
  "Probe flags that are safe because the tool documents them. A tool that
  takes FILE arguments will happily treat an unknown flag as a filename."
  #{"--version" "-version" "--help" "-h"})

(def known-good-resource "known-good.edn")

(defn known-good
  "The KIT's dated known-good set: `{:as-of _ :platform _ :tools {tool version}}`."
  ([] (known-good (io/resource known-good-resource)))
  ([source] (when source (edn/read-string (slurp source)))))

;; ---------------------------------------------------------------------------
;; Probing
;; ---------------------------------------------------------------------------

(defn sh
  "Run argv, capturing both streams. Public because it is the seam tests
  redefine and the one place to change how probing works. Returns nil when the binary is absent:
  :continue true suppresses a non-zero exit, NOT a missing program — that
  throws IOException."
  [argv]
  (try
    (let [res (p/shell {:out :string :err :string :continue true} (str/join " " argv))]
      (str (:out res) (:err res)))
    (catch java.io.IOException _ nil)))

(def probe-timeout-ms 5000)

(defn run
  "Run argv with an EMPTY stdin and a timeout: `{:exit n}`, `{:exit :timeout}`
  (the process tree destroyed), or nil when the binary is absent. The seam
  tests redefine for the bbin tools, which are probed by running them."
  [argv]
  (try
    (let [pr (apply p/process {:out :string :err :string :in ""} argv)
          res (deref pr probe-timeout-ms :timeout)]
      (if (= :timeout res)
        (do (p/destroy-tree pr) {:exit :timeout})
        {:exit (:exit res)}))
    (catch java.io.IOException _ nil)))

(defn- parse-version
  "First dotted-numeric run in `s`, e.g. \"babashka v1.13.220\" -> \"1.13.220\".
  Falls back to a bare year-style token (clj-kondo prints v2026.08.04)."
  [s]
  (when s
    (some-> (re-find #"\d+(?:\.\d+)+" s) str/trim)))

(defn bbin-commit
  "The commit a bbin wrapper's header names - its text between `; :bbin/start` and `; :bbin/end`, the
  `:git/sha` there - or nil. The toolchain pack reads the same header after it installs
  (`toolchain-download-matrix/bbin-commit`); a test holds the two to one wrapper."
  [text]
  (some->> (re-find #"(?s); :bbin/start(.*?); :bbin/end" (or text ""))
           second
           (re-find #":git/sha \"([0-9a-f]{40})\"")
           second))

(defn bbin-commits
  "{tool-name commit} for each bbin tool of `specs` the PATH gives with a bbin header - the file
  `fs/which` finds, its header read; a tool absent, or with no header, is left out."
  ([] (bbin-commits toolchain))
  ([specs]
   (into {}
         (keep (fn [{:keys [tool via]}]
                 (when (= :bbin via)
                   (let [nm (name tool)
                         sha (some-> (fs/which nm) str slurp bbin-commit)]
                     (when sha [nm sha])))))
         specs)))

(defn- major
  "Leading numeric segment of a version string: \"21.0.12\" -> 21,
  \"temurin-21.0.2+13.0.LTS\" -> 21, \"temurin-21\" -> 21."
  [s]
  (some-> s (->> (re-find #"\d+")) parse-long))

(defn reference
  "The doctor's reference on this machine: `{row version}` from the clone's download matrix
  (`download-matrix/reference`) - the JDK left out where the matrix publishes no build, so an own JDK is
  held to its `:min` there. Nil when the clone has no matrix."
  ([] (reference (download-matrix/clone-dir) (System/getProperty "os.name") (System/getProperty "os.arch")))
  ([clone os-name os-arch]
   (when-let [m (download-matrix/read-matrix clone)]
     (cond-> (download-matrix/reference m)
       (nil? (download-matrix/platform m os-name os-arch)) (dissoc :java)))))

(defn probe-env
  "What of the environment a probe reads: JAVA_HOME, against the PATH's java, and JAVA_CMD, which the
  `clojure` script runs before the PATH's (plan 7.0, item 8.3a: inputs, so no test reads this machine's)."
  []
  (select-keys (System/getenv) ["JAVA_HOME" "JAVA_CMD"]))

(defn java-home-mismatch
  "JAVA_HOME's major versus the major of the java on PATH, when they differ.

  A check that only reads the PATH is defeated by any launcher that honours
  JAVA_HOME, and the two routinely disagree once more than one version
  manager is installed. Returns a warning string, or nil. `jh` the JAVA_HOME."
  [path-version jh]
  (when jh
    (let [release (fs/path jh "release")
          jh-major (or (when (fs/exists? release)
                         (major (second (re-find #"JAVA_VERSION=\"([^\"]+)\""
                                                 (slurp (str release))))))
                       (major (fs/file-name jh)))
          path-major (major path-version)]
      (when (and jh-major path-major (not= jh-major path-major))
        (str "JAVA_HOME is on " jh-major " but PATH java is " path-major " (" jh ")")))))

(defn without-build
  "A version without its build after `+`: Temurin's `25.0.5+8` is `25.0.5`, what `java -version` prints."
  [v]
  (some-> v (str/split #"\+") first))

(defn version-cmp
  "Compare dotted-numeric version strings segment by segment. Calendar
  versions (`2026.08.04`) compare the same way, which is the right way: a
  later date is a later version, not a different major."
  [a b]
  (let [seg #(map parse-long (str/split % #"\."))
        pad (fn [xs n] (concat xs (repeat (- n (count xs)) 0)))
        [xa xb] [(seg a) (seg b)]
        n (max (count xa) (count xb))]
    (compare (vec (pad xa n)) (vec (pad xb n)))))

(defn- cmp-to
  "`version` against `floor`, over as many segments as the floor names: a pin
  of `21` is a major and `21.0.12` neither exceeds nor falls short of it."
  [version floor]
  (let [n (count (str/split floor #"\."))
        head (str/join "." (take n (str/split version #"\.")))]
    (version-cmp head floor)))

(defn- unusable? [status]
  (contains? #{:missing :not-on-path :wrong-version :too-old :broken} status))

(defn probe
  "Check one tool. Returns
  {:tool :req :tier :does :needed-for :install :status :version :path :reference :known-good :note},
  where :status is
  :ok | :missing | :not-on-path | :broken | :too-old | :wrong-version | :unknown-version.

  `bbin-idx`, `ref` (`reference`'s map, or nil for none), `good` (the known-good set's `:tools`) and
  `env` (`probe-env`'s) are passed in, so a full report reads each bbin header and each file once, and so
  tests inject all four and no test reads this machine.

  The rules, in order: on PATH at all; for a bbin tool, answers `--help`
  (`:broken` otherwise - the loop cannot run it whatever its header says); an
  exact `:major` where the entry has one; the reference - a commit equal or `:wrong-version`, a version
  below it `:too-old`, above it ok and said (a build after `+` no version); then the entry's `:min`, a
  floor. With no reference for a tool, known-good's newer is said, and the JDK's other major (on a
  machine the matrix publishes no build for) is said as not the preferred, never failed by."
  ([spec bbin-idx ref] (probe spec bbin-idx ref {} {}))
  ([spec bbin-idx ref good] (probe spec bbin-idx ref good {}))
  ([{:keys [tool via cmd min] exact :major prefer :prefer-major :as spec} bbin-idx ref good env]
   (let [nm (name tool)
         base (select-keys spec [:tool :req :tier :does :needed-for :install :major])
         path (some-> (fs/which nm) str)
         version (case via
                   :bbin (some-> (get bbin-idx nm) (subs 0 7))
                   :which nil
                   :flag (parse-version (sh cmd)))
         installed? (case via
                      :bbin (some? (get bbin-idx nm))
                      (some? path))
         ;; In the index and on PATH is not the same as runs: the shim can be
         ;; there while what it launches is not.
         runs (when (and (= :bbin via) path) (run [nm "--help"]))
         r (get ref tool)
         good-version (get good tool)
         numeric? (and version (re-find #"^\d" version))
         below? (fn [v] (and v numeric? (neg? (cmp-to version v))))
         ;; against the reference, the build after `+` dropped on both sides
         vs-ref (when (and r numeric? (not= :bbin via))
                  (version-cmp (without-build version) (without-build r)))
         commit-off? (and r version (= :bbin via) (not (str/starts-with? r version)) (not (str/starts-with? version r)))
         other-major? (and prefer (nil? r) (major version) (not= prefer (major version)))
         status (cond
                  (not installed?) :missing
                  (nil? path) :not-on-path
                  (and (= :bbin via) (not= 0 (:exit runs))) :broken
                  (and exact (major version) (not= exact (major version))) :wrong-version
                  commit-off? :wrong-version
                  (and vs-ref (neg? vs-ref)) :too-old
                  (below? min) :too-old
                  (and (= via :flag) (nil? version)) :unknown-version
                  :else :ok)
         notes (cond-> []
                 (= :java tool) (conj (java-home-mismatch version (get env "JAVA_HOME")))
                 (and (= :java tool) (get env "JAVA_CMD"))
                 (conj (str "JAVA_CMD is set (" (get env "JAVA_CMD") ") - the clojure script runs it, not the PATH's java"))
                 (and (= :ok status) vs-ref (pos? vs-ref))
                 (conj (str "newer than the KIT's download matrix (" r ")"))
                 ;; no build for this machine: an own JDK, 21 and newer, 25 preferred - said, never a fault
                 (and (= :ok status) other-major?)
                 (conj (str "major " prefer " is preferred, the one the KIT tests on; " (major version) " works - " min " and newer"))
                 (and (= :ok status) (nil? r) numeric? good-version (pos? (cmp-to version good-version)))
                 (conj (str "newer than tested (" good-version ")"))
                 (and (= :ok status) (nil? r) (= :bbin via) good-version (not= version good-version))
                 (conj (str "not the tested commit (" good-version ")")))]
     (cond-> (assoc base :path path :version version :reference r :known-good good-version :status status)
       (seq (remove nil? notes)) (assoc :note (str/join "; " (remove nil? notes)))
       ;; said on a ready machine too: the one note a short report keeps
       (and (= :ok status) other-major?) (assoc :not-preferred? true)))))

(defn report
  "Probe the whole toolchain against `ref` - the clone's `reference` unless given; nil for none, the
  floors alone, which `bb upgrade`'s pre-check passes. Reads the bbin headers, the known-good set and the
  environment once."
  ([] (report toolchain))
  ([specs] (report specs (reference)))
  ([specs ref] (report specs ref (:tools (known-good))))
  ([specs ref good]
   (let [idx (bbin-commits specs)
         env (probe-env)]
     (mapv #(probe % idx ref good env) specs))))

(defn ok?
  "True when no :required tool is unusable - in `tier` (`:gates` or `:loop`,
  the loop tier including the gates tier), or in every tier with none given.
  :recommended and :optional are reported but never fatal — a template that
  refuses to run without every nicety teaches people to skip the check."
  ([results] (ok? results nil))
  ([results tier]
   (not-any? (fn [{:keys [req status] t :tier}]
               (and (= req :required)
                    (or (nil? tier) (= tier :loop) (= tier t))
                    (unusable? status)))
             results)))

;; ---------------------------------------------------------------------------
;; The project's folder, and what fixes a row
;; ---------------------------------------------------------------------------

(defn folder-status
  "The toolchain pack's `status --edn` for the clone `clone` - the project's folder, the tools folder, the
  `.env`, which tools the folder has, and `:note`, `:refusal` or `:unpublished` - or nil when the pack
  does not answer. A subprocess: the harness never loads the pack."
  [clone]
  (try
    (let [{:keys [out]} (p/shell {:out :string :err :string :continue true}
                                 "bb" "--config" (str (fs/path clone "tools" "toolchain" "bb.edn")) "status" "--edn")
          m (edn/read-string (str/trim (or out "")))]
      (when (map? m) m))
    (catch Exception _ nil)))

(defn fix-context
  "What the fix lines and the offer are written from - pure: the clone, the true-up command, the matrix's
  tools the folder has and those it lacks, the place and its notes, from `status` (`folder-status`, or
  nil) and the clone's `matrix`."
  [clone status matrix]
  {:clone clone
   :true-up (str "bb --config " (fs/path clone "tools" "toolchain" "bb.edn") " toolchain-install")
   :true-up-argv ["bb" "--config" (str (fs/path clone "tools" "toolchain" "bb.edn")) "toolchain-install"]
   :present (set (keep #(when (:present? %) (:tool %)) (:rows status)))
   :absent (vec (keep #(when-not (:present? %) (:tool %)) (:rows status)))
   :status status
   :unpublished (:unpublished status)
   :matrix matrix})

(defn fix-for
  "The line that fixes the row `r` - pure, from `ctx` (`fix-context`'s, or nil): its own `:install` route,
  the clone's path filled for `<kit>`, where the matrix publishes no build here, for bb and git (the
  true-up cannot replace the running bb; git is no row), and for a tool no matrix row stands behind;
  `eval \"$(bb toolchain-env)\"` when the project's folder has it and this shell's PATH does not; else the
  true-up."
  [{:keys [tool install]} {:keys [clone true-up present unpublished matrix] :as ctx}]
  (let [install (cond-> install (and install clone) (str/replace "<kit>" (str clone)))
        mt (when matrix (download-matrix/matrix-tool matrix tool))]
    (cond (or (nil? ctx) unpublished (#{:bb :git} tool) (nil? mt)) install
          (contains? present mt) "eval \"$(bb toolchain-env)\" in this shell - the project's folder has it"
          :else true-up)))

(defn- human-size
  "`mb` as a person reads it: MB, GB or TB."
  [mb]
  (cond (< mb 1024) (str (Math/round (double mb)) " MB")
        (< mb (* 1024 1024)) (format "%.1f GB" (/ mb 1024.0))
        :else (format "%.1f TB" (/ mb 1024.0 1024.0))))

(defn disk-space
  "`{:free-mb :total-mb}` of the disk holding `dir` - its nearest folder that exists."
  [dir]
  (let [d (loop [d (fs/absolutize dir)] (if (or (fs/exists? d) (nil? (fs/parent d))) d (recur (fs/parent d))))
        f (fs/file d)]
    {:free-mb (quot (.getUsableSpace f) (* 1024 1024)) :total-mb (quot (.getTotalSpace f) (* 1024 1024))}))

(defn offer-lines
  "The offer's text - pure (the person, 2026-10-30: each install on a line of its own with its size, then
  the total and the disk's free space): `items` `[{:tool :version :disk-mb}]`, `space` `disk-space`'s.
  `{:lines [...] :fits? bool}`; when it does not fit, the last line says so and no question is asked."
  [items {:keys [free-mb total-mb]}]
  (let [total (reduce + 0 (keep :disk-mb items))
        fits? (< total free-mb)]
    {:fits? fits?
     :lines (concat ["The true-up installs, into the project's toolchain folder:"]
                    (for [{:keys [tool version disk-mb]} items]
                      (str "  - " (name tool) " " version (when disk-mb (str " (about " (human-size disk-mb) " on disk)"))))
                    [""
                     (str "Total installs: " (count items) " (about " (human-size total) " on disk)")
                     (str "Available disk space: " (human-size free-mb) " free of " (human-size total-mb) " disk.")]
                    (when-not fits?
                      ["Not enough space for it: free some, or set KIT_TOOLCHAIN_DIR to a folder on another disk."]))}))

(defn offer?
  "Whether the doctor asks to run the true-up - pure: run by hand (no `--tier`, no `--edn`) at a terminal,
  on a published platform, when a row's fix is the true-up."
  [console? args results ctx]
  (boolean (and console? ctx (not (:unpublished ctx))
                (not-any? #{"--tier" "--edn"} args)
                (seq (:absent ctx))
                (some #(and (unusable? (:status %)) (= (:true-up ctx) (fix-for % ctx))) results))))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- status-label [{:keys [status req]}]
  (case status
    :ok "ok"
    :missing (if (= req :required) "MISSING" "missing")
    :not-on-path "NOT ON PATH"
    :broken "DOES NOT RUN"
    :wrong-version "WRONG VERSION"
    :too-old "TOO OLD"
    :unknown-version "ok?"))

(defn- detail
  "What follows the status: what the tool is for, plus anything the reader
  has to act on — the matrix's version not met, a JAVA_HOME that disagrees
  with PATH — and, for anything unusable, what fixes it (`fix-for`)."
  [{:keys [tool does status version reference note] :as r} ctx]
  (str does
       (case status
         :wrong-version (if reference
                          (str "\n      the KIT's download matrix pins " reference " but " (or version "?") " is installed")
                          (str "\n      must be major " (:major r) " but " (or version "?") " is running"))
         :too-old (str "\n      " (or version "?") " is below "
                       (if reference (str "the KIT's download matrix, " reference) "the floor"))
         :broken "\n      on PATH with its bbin header, but `--help` did not exit 0 within 5s"
         "")
       (when note (str "\n      " note))
       ;; above the matrix, with the matrix's own in the folder: the line that runs it (Fable 5.1's answer to Q4)
       (when (and (= :ok status) reference note (str/includes? note "newer than the KIT's download matrix")
                  (:matrix ctx) (contains? (:present ctx) (download-matrix/matrix-tool (:matrix ctx) tool)))
         "\n      for the matrix's exactly: eval \"$(bb toolchain-env)\" in this shell - the project's folder has it")
       (when-let [fix (when (unusable? status) (fix-for r ctx))]
         (str "\n      fix: " fix))))

(defn- verdict-line [results tier label]
  (let [bad (filter (fn [{:keys [req status] t :tier}]
                      (and (= req :required) (or (= tier :loop) (= tier t)) (unusable? status)))
                    results)]
    (str "  " label ": "
         (if (seq bad)
           (str "NO - " (str/join ", " (map #(str (name (:tool %)) " (" (name (:status %)) ")") bad)))
           "yes"))))

(defn- header
  "Where the project's toolchain is, from `status`, and its one note - `:note`, `:refusal` or
  `:unpublished` - each once."
  [{:keys [status]}]
  (when status
    (let [{:keys [folder cache env note refusal unpublished]} status]
      (str "\nthe project folder " folder " - the tools in " cache ", the keys in " env "\n"
           (when note (str "  note: " note "\n"))
           (when refusal (str "  refused: " refusal "\n"))
           (when unpublished (str "  " unpublished "\n"))))))

(defn render-table
  "The human report, in two parts - what KIT's gates and a loop need, and what
  only a seat or a pack needs - under where the project's toolchain is, then the two verdicts, the
  known-good set, and the true-up when a row's fix is it. A tool's line is its name, versions and
  status; what it is for, and what fixes it, follow only when it is not ok (or a note speaks for it),
  so a ready machine's report is short and no line runs wide. An optional tool that is absent is `not
  installed`, not a miss. `ctx` is `fix-context`'s, or nil."
  ([results] (render-table results nil nil))
  ([results good ctx]
   (let [w (apply max 4 (map #(count (name (:tool %))) results))
         label (fn [{:keys [status req] :as r}]
                 (if (and (= :missing status) (not= :required req)) "not installed" (status-label r)))
         row (fn [{:keys [tool version known-good status] :as r}]
               (str (format (str "  %-" w "s  %-12s  %-12s  %s")
                            (name tool) (or version "—") (or known-good "—") (label r))
                    (when (or (not= :ok status) (:note r) (:not-preferred? r))
                      (str "\n      " (detail r ctx)))))
         {required true optional false} (group-by #(= :required (:req %)) results)
         true-up? (and ctx (some #(and (unusable? (:status %)) (= (:true-up ctx) (fix-for % ctx))) results))]
     (str (header ctx)
          "\nClojure toolchain - required (KIT's gates and a loop need these)\n\n"
          (format (str "  %-" w "s  %-12s  %-12s  %s\n") "tool" "here" "known-good" "status")
          (str/join "\n" (map row required))
          (when (seq optional)
            (str "\n\nOptional - a seat's client or a pack's tool; one not installed is fine\n\n"
                 (str/join "\n" (map row optional))))
          "\n\n"
          (verdict-line results :gates "The KIT's gates can run here") "\n"
          (verdict-line results :loop "A loop can run here") "\n"
          (when good
            (str "  known-good: KIT's own set, run together on " (:as-of good) " (" (:platform good) ")\n"))
          (when true-up?
            (str "  the true-up, every tool the project's folder lacks: " (:true-up ctx) "\n"))))))

(defn render-edn
  "A map of each tool's version and status: {tool {:version _ :status _ :req _ :tier _}}."
  [results]
  (into (sorted-map)
        (map (juxt :tool #(select-keys % [:version :status :req :tier])))
        results))

;; ---------------------------------------------------------------------------
;; Declaring a dependency
;; ---------------------------------------------------------------------------

(defn require-tool!
  "Assert `tool` is usable, or throw with something actionable. Call this
  wherever code shells out to a toolchain binary, so a bare machine gets a
  named cause instead of an IOException from three frames down. Held to the clone's download matrix,
  as `bb doctor` holds it."
  [tool]
  (let [spec (first (filter #(= tool (:tool %)) toolchain))
        {:keys [status]} (probe spec (bbin-commits [spec]) (reference) {} (probe-env))]
    (when (unusable? status)
      (throw (ex-info (str (name tool)
                           (case status
                             :not-on-path " is installed but its shim is not on PATH"
                             :broken " is installed but does not run"
                             :wrong-version " is not the version the KIT's download matrix pins"
                             :too-old " is older than the KIT's download matrix"
                             " is not installed")
                           " — run `bb doctor`")
                      {:tool tool
                       :status status
                       :does (:does spec)
                       :needed-for (:needed-for spec)
                       :install (:install spec)})))
    true))

(defn- arg-value
  "The token following `flag`, or nil."
  [args flag]
  (second (drop-while #(not= flag %) args)))

(defn- ask-true-up!
  "The offer, at the terminal `console`: the lines, then `Install? [y/N]`; on `y` the true-up runs. Whether
  it ran."
  [console ctx]
  (let [matrix (:matrix ctx)
        items (for [t (:absent ctx)] {:tool t :version (get-in matrix [t :version]) :disk-mb (get-in matrix [t :disk-mb])})
        {:keys [lines fits?]} (offer-lines items (disk-space (get-in ctx [:status :cache])))]
    (println)
    (run! println lines)
    (when fits?
      (let [answer (some-> (.readLine console "\nInstall? [y/N] " (object-array 0)) str/trim str/lower-case)]
        (when (#{"y" "yes"} answer)
          (apply p/shell {:continue true} (:true-up-argv ctx))
          true)))))

(defn main
  "`-main`'s body, returning the exit code instead of exiting, so that a task
  can print something after the table and exit with the doctor's verdict:
  `harness.setup.upgrade/doctor-main` does, and this namespace cannot require
  that one (it requires the profile, which requires this).

  bb doctor [--edn] [--tier gates|loop]

  `--tier` chooses which verdict decides the exit code: `gates` (what `bb gates` needs, no JVM - the KIT's
  own gates pass it) or `loop` (the default: everything a run needs). Run by hand at a terminal, with
  neither flag, it offers the true-up once (`offer?`), and on a yes runs it and reports again."
  [& args]
  (let [tier (keyword (or (arg-value args "--tier") "loop"))]
    (if-not (#{:gates :loop} tier)
      (do (println (str "doctor: --tier must be gates or loop, got " (name tier))) 1)
      (let [good (known-good)
            clone (download-matrix/clone-dir)
            results (report toolchain (reference) (:tools good))]
        (if (some #{"--edn"} args)
          (do (prn (render-edn results)) (if (ok? results tier) 0 1))
          (let [ctx (fix-context clone (folder-status clone) (download-matrix/read-matrix clone))
                console (System/console)]
            (println (render-table results good ctx))
            (if (and (offer? (some? console) args results ctx) (ask-true-up! console ctx))
              (let [again (report toolchain (reference) (:tools good))]
                (println (render-table again good (fix-context clone (folder-status clone) (:matrix ctx))))
                (if (ok? again tier) 0 1))
              (if (ok? results tier) 0 1))))))))

(defn -main
  "bb doctor [--edn] [--tier gates|loop] - `main`, then exit with its code."
  [& args]
  (System/exit (apply main args)))
