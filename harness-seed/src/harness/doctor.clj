;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.doctor
  "Toolchain doctor: what this stack needs, whether it is here, and what it does.

  A Clojure agentic loop leans on a handful of small binaries that are easy
  to forget and invisible when missing — a loop with no `clj-paren-repair`
  does not fail, it just quietly spends the Coder's retry budget on parens.
  So the toolchain is checked and *reported*, not assumed.

  `bb doctor` renders the table; `bb doctor --edn` emits a pins-shaped map
  worth pasting into the decision log. It is the first gate in `bb gates`
  because it is the cheapest check there is, and it makes Foundation
  readiness criterion 1 something you run rather than something you assert.

  SAFETY RULE: never probe a tool by handing it an unrecognized flag.
  `clj-paren-repair --version` does not error — it treats `--version` as a
  FILENAME and runs a repair pass, exiting 0. A health check that mutates
  is worse than no health check. Probe with --version only where it is
  documented, else --help, `bbin ls`, or plain presence on PATH."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The toolchain
;; ---------------------------------------------------------------------------

(def toolchain
  "Every tool the loop touches, why, and how to ask it its version.

  :req      :required   — the loop cannot run without it; a miss fails `bb doctor`
            :recommended — the loop degrades without it; reported, never fatal
            :optional    — convenience
  :via      :flag  — run :cmd and parse stdout+stderr
            :bbin  — look it up in `bbin ls` (these tools have NO --version)
            :which — presence on PATH only"
  [{:tool :bb :via :flag :cmd ["bb" "--version"] :req :required :min "1.12.212"
    :does "Task runner; the harness itself runs on it"
    :needed-for "The bb.edn task surface; bbin requires >= 1.12.212"}

   {:tool :java :via :flag :cmd ["java" "-version"] :req :required
    :does "JVM the project and its nREPL run on"
    :needed-for "Pin it in .mise.toml and this verifies the major. Some stores
                 are JDK-version-sensitive: XTDB v2 needs 21 exactly — it fails at
                 class-load on newer JDKs (verified on 2.0.0 and 2.1.0)"}

   {:tool :clojure :via :flag :cmd ["clojure" "--version"] :req :required
    :does "Clojure CLI: deps, aliases, the nREPL server"
    :needed-for "clj -M:nrepl per worktree"}

   {:tool :git :via :flag :cmd ["git" "--version"] :req :required
    :does "Version control, and worktrees"
    :needed-for "One isolated workspace per task"}

   {:tool :cljfmt :via :flag :cmd ["cljfmt" "--version"] :req :required
    :does "Formatter"
    :needed-for "Gate 1, and gate 0's format-on-write"}

   {:tool :clj-kondo :via :flag :cmd ["clj-kondo" "--version"] :req :required
    :does "Linter — run it fail-on-warning, not just fail-on-error"
    :needed-for "Gate 2"}

   {:tool :clj-nrepl-eval :via :bbin :req :required
    :does "CLI nREPL bridge — uniform REPL access across model families"
    :needed-for "Coder/Tester eval; --discover-ports finds a worktree's port"}

   {:tool :clj-paren-repair :via :bbin :req :required
    :does "On-demand delimiter repair"
    :needed-for "Gate 0 for agents whose client has no write hook"}

   {:tool :clj-paren-repair-claude-hook :via :bbin :req :recommended
    :does "Zero-token delimiter repair at write time, before it reaches disk"
    :needed-for "Gate 0 for hook-capable clients. Keep BOTH — hooks cover
                 Write/Edit, but an agent can still edit via the shell"}

   {:tool :bbin :via :flag :cmd ["bbin" "--version"] :req :recommended
    :does "Installs single-file Babashka tools"
    :needed-for "Installing the three tools above"}

   {:tool :clj-depend :via :which :req :recommended
    :does "Namespace layer-boundary checker"
    :needed-for "Gate 4. Usually a deps alias (clj -M:depend), not a binary —
                 reported here so the gate is not forgotten"}

   {:tool :neil :via :flag :cmd ["neil" "--version"] :req :optional
    :does "Project scaffolding from a deps-new template"
    :needed-for "Foundation step 1, once per project"}

   {:tool :mise :via :flag :cmd ["mise" "--version"] :req :optional
    :does "Pins language/tool versions per project"
    :needed-for "The scaffold ships a mise.toml"}])

(def flag-allowlist
  "Probe flags that are safe because the tool documents them. A tool that
  takes FILE arguments will happily treat an unknown flag as a filename."
  #{"--version" "-version" "--help" "-h"})

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

(defn- parse-version
  "First dotted-numeric run in `s`, e.g. \"babashka v1.13.220\" -> \"1.13.220\".
  Falls back to a bare year-style token (clj-kondo prints v2026.08.04)."
  [s]
  (when s
    (some-> (re-find #"\d+(?:\.\d+)+" s) str/trim)))

(defn bbin-index
  "Parse `bbin ls` into {tool-name sha}. Its output is three columns —
  name, commit sha, git url — with no machine-readable mode."
  []
  (->> (or (sh ["bbin" "ls"]) "")
       str/split-lines
       (keep (fn [line]
               (let [[nm sha] (str/split (str/trim line) #"\s+")]
                 (when (and nm sha (not (str/blank? nm)))
                   [nm sha]))))
       (into {})))

(defn- major
  "Leading numeric segment of a version string: \"21.0.12\" -> 21,
  \"temurin-21.0.2+13.0.LTS\" -> 21."
  [s]
  (some-> s (->> (re-find #"\d+")) parse-long))

(def ^:private mise-aliases
  "mise's tool names where they differ from ours."
  {"babashka" :bb})

(defn- mise-file
  "Nearest .mise.toml or mise.toml, walking up from `dir` to the filesystem
  root. Returns nil when there is none — the normal case for this seed,
  which means no version constraints apply."
  [dir]
  (loop [d (fs/absolutize (or dir "."))]
    (or (first (filter fs/exists? [(fs/path d ".mise.toml") (fs/path d "mise.toml")]))
        (when-let [parent (fs/parent d)]
          (recur parent)))))

(defn mise-pins
  "Read the [tools] table of the nearest mise config into
  {tool-key {:major _ :pin _ :source _}}.

  This reads a known table shape with a regex; it is NOT a TOML parser, and
  Babashka has none. It handles `name = \"value\"` lines under [tools] and
  stops at the next [section] header, so the [alias] table that usually
  follows cannot bleed in.

  Only the MAJOR version is kept. mise enforces the exact pin; the doctor
  verifies the part that actually breaks — a JDK 25 where 21 was pinned.
  Flagging 21.0.12 against a 21.0.2 pin would be noise, not a finding."
  ([] (mise-pins "."))
  ([dir]
   (if-let [f (mise-file dir)]
     (let [body (slurp (str f))
           tools (second (re-find #"(?ms)^\[tools\]\s*$(.*?)(?=^\[|\z)" body))]
       (into {}
             (keep (fn [[_ nm v]]
                     (let [k (get mise-aliases nm (keyword nm))]
                       (when-let [m (major v)]
                         [k {:major m :pin v :source (str (fs/file-name f))}]))))
             (re-seq #"(?m)^\s*([A-Za-z0-9_.-]+)\s*=\s*\"([^\"]+)\"" (or tools ""))))
     {})))

(defn java-home
  "JAVA_HOME, as a seam tests can redefine."
  []
  (System/getenv "JAVA_HOME"))

(defn java-home-mismatch
  "JAVA_HOME's major versus the major of the java on PATH, when they differ.

  A pin that only checks PATH is defeated by any launcher that honours
  JAVA_HOME, and the two routinely disagree once more than one version
  manager is installed. Returns a warning string, or nil."
  [path-version]
  (when-let [jh (java-home)]
    (let [release (fs/path jh "release")
          jh-major (or (when (fs/exists? release)
                         (major (second (re-find #"JAVA_VERSION=\"([^\"]+)\""
                                                 (slurp (str release))))))
                       (major (fs/file-name jh)))
          path-major (major path-version)]
      (when (and jh-major path-major (not= jh-major path-major))
        (str "JAVA_HOME is on " jh-major " but PATH java is " path-major " (" jh ")")))))

(defn- version-cmp
  "Compare dotted-numeric version strings segment by segment."
  [a b]
  (let [seg #(map parse-long (str/split % #"\."))
        pad (fn [xs n] (concat xs (repeat (- n (count xs)) 0)))
        [xa xb] [(seg a) (seg b)]
        n (max (count xa) (count xb))]
    (compare (vec (pad xa n)) (vec (pad xb n)))))

(defn probe
  "Check one tool. Returns
  {:tool :req :does :needed-for :status :version :path :pin :note},
  where :status is
  :ok | :missing | :not-on-path | :too-old | :wrong-version | :unknown-version.

  `bbin-idx` and `pins` are passed in so a full report shells `bbin ls` and
  reads the mise config once each, and so tests can inject both."
  [{:keys [tool via cmd min] :as spec} bbin-idx pins]
  (let [nm (name tool)
        base (select-keys spec [:tool :req :does :needed-for])
        path (some-> (fs/which nm) str)
        version (case via
                  :bbin (some-> (get bbin-idx nm) (subs 0 7))
                  :which nil
                  :flag (parse-version (sh cmd)))
        ;; A bbin-installed tool can be in the index yet unreachable: bbin
        ;; itself is on PATH while its shim directory (~/.local/bin) is not.
        ;; `bbin ls` still lists it, so the index alone would report ok for a
        ;; tool the loop cannot actually run. Require both.
        installed? (case via
                     :bbin (some? (get bbin-idx nm))
                     (some? path))
        pin (get pins tool)
        pinned-major (:major pin)
        running-major (major version)]
    (cond-> (assoc base
                   :path path
                   :version version
                   :pin pin
                   :status (cond
                             (not installed?) :missing
                             (nil? path) :not-on-path
                             (and pinned-major running-major
                                  (not= pinned-major running-major)) :wrong-version
                             (and min version (neg? (version-cmp version min))) :too-old
                             (and (= via :flag) (nil? version)) :unknown-version
                             :else :ok))
      (= :java tool)
      (assoc :note (java-home-mismatch version)))))

(defn report
  "Probe the whole toolchain. Shells `bbin ls` and reads the mise config once."
  ([] (report toolchain))
  ([specs] (report specs (mise-pins)))
  ([specs pins]
   (let [idx (bbin-index)]
     (mapv #(probe % idx pins) specs))))

(defn ok?
  "True when no :required tool is unusable. :recommended and :optional are
  reported but never fatal — a template that refuses to run without every
  nicety teaches people to skip the check."
  [results]
  (not-any? (fn [{:keys [req status]}]
              (and (= req :required)
                   (contains? #{:missing :not-on-path :wrong-version :too-old} status)))
            results))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- status-label [{:keys [status req]}]
  (case status
    :ok "ok"
    :missing (if (= req :required) "MISSING" "missing")
    :not-on-path "NOT ON PATH"
    :wrong-version "WRONG VERSION"
    :too-old "TOO OLD"
    :unknown-version "ok?"))

(defn- detail
  "What follows the status: what the tool is for, plus anything the reader
  has to act on — a violated pin, or a JAVA_HOME that disagrees with PATH."
  [{:keys [does status version pin note]}]
  (str does
       (when (and pin (= :ok status))
         (str "  [pinned " (:major pin) " via " (:source pin) "]"))
       (when (= :wrong-version status)
         (str "\n      " (:source pin) " pins " (:pin pin)
              " (major " (:major pin) ") but " (or version "?") " is running"))
       (when note (str "\n      " note))))

(defn render-table
  "The human report: one line per tool, plus a summary."
  [results]
  (let [w (apply max 4 (map #(count (name (:tool %))) results))
        row (fn [{:keys [tool version] :as r}]
              (format (str "  %-" w "s  %-12s  %-12s %s")
                      (name tool) (or version "—") (status-label r) (detail r)))
        tally (fn [req] (->> results (filter #(= req (:req %)))
                             (group-by #(if (= :ok (:status %)) :ok :not-ok))))
        {rok :ok rbad :not-ok} (tally :required)]
    (str "\nClojure toolchain\n\n"
         (str/join "\n" (map row results))
         "\n\n  " (count rok) " required ok"
         (when (seq rbad)
           (str " · " (count rbad) " required NOT USABLE: "
                (str/join ", " (map #(str (name (:tool %))
                                          " (" (name (:status %)) ")") rbad))))
         (let [soft (remove #(or (= :required (:req %)) (= :ok (:status %))) results)]
           (when (seq soft)
             (str " · " (count soft) " not required, missing: "
                  (str/join ", " (map #(name (:tool %)) soft)))))
         "\n")))

(defn render-edn
  "A pins-shaped map for the decision log: {tool {:version _ :status _}}."
  [results]
  (into (sorted-map)
        (map (juxt :tool #(-> (select-keys % [:version :status :req])
                              (cond-> (:pin %) (assoc :pinned (get-in % [:pin :pin]))))))
        results))

;; ---------------------------------------------------------------------------
;; Declaring a dependency
;; ---------------------------------------------------------------------------

(defn require-tool!
  "Assert `tool` is usable, or throw with something actionable. Call this
  wherever code shells out to a toolchain binary, so a bare machine gets a
  named cause instead of an IOException from three frames down."
  [tool]
  (let [spec (first (filter #(= tool (:tool %)) toolchain))
        {:keys [status]} (probe spec (bbin-index) (mise-pins))]
    (when (contains? #{:missing :not-on-path :wrong-version} status)
      (throw (ex-info (str (name tool)
                           (case status
                             :not-on-path " is installed but its shim is not on PATH"
                             :wrong-version " is on the wrong major version"
                             " is not installed")
                           " — run `bb doctor`")
                      {:tool tool
                       :status status
                       :does (:does spec)
                       :needed-for (:needed-for spec)})))
    true))

(defn -main [& args]
  (let [results (report)]
    (if (some #{"--edn"} args)
      (prn (render-edn results))
      (println (render-table results)))
    (when-not (ok? results)
      (System/exit 1))))
