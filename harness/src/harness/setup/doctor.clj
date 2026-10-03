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
  here* (that plus JDK 21, the Clojure CLI and the nREPL bridge). `bb init`
  refuses without the second; `bb gates` runs the doctor for the first only, so
  the KIT's own gates stay JVM-free. `--edn` emits a pins-shaped map worth
  pasting into the decision log.

  GUIDE, DO NOT INSTALL. For every tool that is not usable the report prints
  the command or page that fixes it, and nothing here runs one: run `bb doctor`,
  follow it, run it again.

  VERSIONS ARE MINIMUMS, WITH ONE EXCEPTION. A pin in the nearest `.mise.toml`
  (walking up), or a `:min` here, is a floor: older is `:too-old`, newer is fine
  and said so. The exception is the JDK, `:major 21` on its entry: XTDB
  documents a minimum of 21 and its early v2 releases failed at class-load on
  newer JDKs, so the KIT holds to 21 until that is re-checked. Beside the floors
  the report shows the KIT's dated KNOWN-GOOD SET (`resources/known-good.edn`) -
  the versions that were actually run together - and marks a newer one as
  *newer than tested*, which is information and never a fault. Three projects
  failed this check on a version rule that was wrong, not on a tool that was
  missing: a calendar version read as a major, a patch level read as a break.

  SAFETY RULE: never probe a tool by handing it an unrecognized flag.
  `clj-paren-repair --version` does not error — it treats `--version` as a
  FILENAME and runs a repair pass, exiting 0. A health check that mutates
  is worse than no health check. Probe with --version only where it is
  documented, else --help, `bbin ls`, or plain presence on PATH. The three
  bbin tools are RUN, `--help` with an empty stdin and a timeout - each was
  checked to answer it in under a second and write nothing - because being in
  `bbin ls` and on PATH proved, twice, not to mean a tool could run."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The toolchain
;; ---------------------------------------------------------------------------

(defn- mcp-light-install
  "The install of one clojure-mcp-light tool, from its README (read 2026-09-22):
  the hook is the repository's default, the other two are the same install
  with `--as` and `--main-opts`."
  [tool-name main-ns]
  (str "bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2"
       (when main-ns
         (str " --as " tool-name " --main-opts '[\"-m\" \"clojure-mcp-light." main-ns "\"]'"))))

(def toolchain
  "Every tool the loop touches, why, how to ask it its version, and what installs it.

  :req      :required   — the loop cannot run without it; a miss fails `bb doctor`
            :recommended — the loop degrades without it; reported, never fatal
            :optional    — convenience
  :tier     :gates — the KIT's own gates need it (Babashka, no JVM)
            :loop  — a loop against a project needs it as well
  :via      :flag  — run :cmd and parse stdout+stderr
            :bbin  — look it up in `bbin ls` AND run it with --help
            :which — presence on PATH only
  :min      a floor; :major an exact major, the one constraint with a reason
  :install  what fixes a miss: a command where one is documented, else the page"
  [{:tool :bb :via :flag :cmd ["bb" "--version"] :req :required :tier :gates :min "1.12.212"
    :does "Task runner; the harness itself runs on it"
    :needed-for "The bb.edn task surface; bbin requires >= 1.12.212"
    :install "https://github.com/babashka/babashka#installation (brew install borkdude/brew/babashka)"}

   {:tool :java :via :flag :cmd ["java" "-version"] :req :required :tier :loop :major 21
    :does "JVM the project and its nREPL run on"
    :needed-for "JDK 21, exactly that major: XTDB v2 documents a minimum of 21, and
                 2.0.0 and 2.1.0 failed at class-load on newer JDKs (not re-checked
                 on later releases). A .mise.toml pin is read too"
    :install "Temurin 21: https://adoptium.net/temurin/releases/?version=21 (brew install --cask temurin@21)"}

   {:tool :clojure :via :flag :cmd ["clojure" "--version"] :req :required :tier :loop
    :does "Clojure CLI: deps, aliases, the nREPL server"
    :needed-for "clojure -Srepro -M:nrepl per worktree, and `bb init`'s generation"
    :install "https://clojure.org/guides/install_clojure (brew install clojure/tools/clojure)"}

   {:tool :git :via :flag :cmd ["git" "--version"] :req :required :tier :gates
    :does "Version control, and worktrees"
    :needed-for "One isolated workspace per task"
    :install "https://git-scm.com/downloads"}

   {:tool :cljfmt :via :flag :cmd ["cljfmt" "--version"] :req :required :tier :gates
    :does "Formatter"
    :needed-for "Gate 1, and gate 0's format-on-write"
    :install "https://github.com/weavejester/cljfmt#installation"}

   {:tool :clj-kondo :via :flag :cmd ["clj-kondo" "--version"] :req :required :tier :gates
    :does "Linter — run it fail-on-warning, not just fail-on-error"
    :needed-for "Gate 2"
    :install "https://github.com/clj-kondo/clj-kondo/blob/master/doc/install.md (brew install borkdude/brew/clj-kondo)"}

   {:tool :clj-nrepl-eval :via :bbin :req :required :tier :loop
    :does "CLI nREPL bridge — uniform REPL access across model families"
    :needed-for "Coder/Tester eval; --discover-ports finds a worktree's port"
    :install (mcp-light-install "clj-nrepl-eval" "nrepl-eval")}

   {:tool :clj-paren-repair :via :bbin :req :required :tier :gates
    :does "On-demand delimiter repair"
    :needed-for "Gate 0, `bb repair`, for agents whose client has no write hook"
    :install (mcp-light-install "clj-paren-repair" "paren-repair")}

   {:tool :clj-paren-repair-claude-hook :via :bbin :req :optional
    :does "Zero-token delimiter repair at write time, for Claude Code"
    :needed-for "One client's write-time accelerant, not the mechanism. `bb
                 repair` is the floor and runs everywhere; every client can
                 still edit through the shell and bypass any hook. Other
                 clients have the same capability under other names —
                 Antigravity's .agents/hooks.json, an OpenCode plugin, a Pi
                 extension"
    :install (mcp-light-install "clj-paren-repair-claude-hook" nil)}

   ;; THE BROWSER THE STAGE-END CHECKS DRIVE. Optional and in no tier: the gates and the
   ;; loop never open a browser; the template's `bb browser-check` and `bb health`'s browser
   ;; check do, through Etaoin. Firefox has no row - it is an application, not a command on
   ;; the PATH - and `--version` here proves little anyway: on macOS the permission to use
   ;; Firefox belongs to the terminal application, and a shell under a daemon gets a silent
   ;; refusal that only a real start shows. `bb health` starts one.
   {:tool :geckodriver :via :flag :cmd ["geckodriver" "--version"] :req :optional
    :does "WebDriver for Firefox - the stage-end checks in a real browser (Etaoin, headless)"
    :needed-for "The generated application's `bb browser-check`, and the browser check of `bb health`; with Firefox installed"
    :install "https://github.com/mozilla/geckodriver/releases (brew install geckodriver); Firefox from mozilla.org"}

   {:tool :bbin :via :flag :cmd ["bbin" "--version"] :req :recommended
    :does "Installs single-file Babashka tools"
    :needed-for "Installing the three tools above"
    :install "https://github.com/babashka/bbin#installation"}

   ;; The interactive seats. All five were verified to answer --version
   ;; without dispatching anything — do that yourself before adding a sixth,
   ;; because the SAFETY RULE above applies with most force to a client that
   ;; takes a prompt as a positional argument. ALL FIVE STAY (decided 2026-09-24):
   ;; portability across seats is the design, and the KIT is proved in one seat
   ;; first and run in the others afterwards - so each entry says where it
   ;; stands: whether a profile example ships for it (`bb init --seat` refuses a
   ;; seat without one, by name) and whether a build has run from it.
   {:tool :claude :via :flag :cmd ["claude" "--version"] :req :optional
    :does "Claude Code — reads CLAUDE.md; write hooks in .claude/settings.json"
    :needed-for "The seat the KIT is proved in: every build so far ran from it,
                 and the health check's profile names it. Its profile example
                 ships (resources/profiles/claude.edn), the default for bb init"
    :install "https://claude.com/claude-code"}

   {:tool :agy-ide :via :flag :cmd ["agy-ide" "--version"] :req :optional
    :does "Antigravity IDE launcher — the IDE's agent reads AGENTS.md"
    :needed-for "An interactive seat. The CLI itself is a VS Code-style launcher
                 (--diff, --goto, --install-extension), not an agent: there is no
                 headless mode and nothing to dispatch to. Antigravity's whole
                 integration with this kit is AGENTS.md, plus optional
                 .agents/hooks.json for write-time gate 0. Its profile example
                 ships (bb init --seat agy-ide); no build has run from it yet"}

   {:tool :opencode :via :flag :cmd ["opencode" "--version"] :req :optional
    :does "OpenCode v1 — reads AGENTS.md; agents in .opencode/agents/"
    :needed-for "Coexists with v2 as a separate binary, so both are listed. No
                 profile example yet, so bb init --seat refuses it; no build has
                 run from it"}

   {:tool :opencode2 :via :flag :cmd ["opencode2" "--version"] :req :optional
    :does "OpenCode v2 — ordered {action, resource, effect} permissions"
    :needed-for "The only seat that can enforce read-only review and `never run
                 the gates` as client rules rather than prompt text. Still beta:
                 its plugin and SDK contracts are not final. No profile example
                 yet, so bb init --seat refuses it; no build has run from it"}

   {:tool :pi :via :flag :cmd ["pi" "--version"] :req :optional
    :does "Pi — reads AGENTS.md; .pi/SYSTEM.md replaces the system prompt"
    :needed-for "The programmable seat: TypeScript extensions can replace tools.
                 No profile example yet, so bb init --seat refuses it; no build
                 has run from it"}

   {:tool :mise :via :flag :cmd ["mise" "--version"] :req :optional
    :does "Pins language/tool versions per project"
    :needed-for "The template's projects ship a .mise.toml; `mise install` is one route to the JDK and tools"
    :install "https://mise.jdx.dev/getting-started.html"}])

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
  \"temurin-21.0.2+13.0.LTS\" -> 21, \"temurin-21\" -> 21."
  [s]
  (some-> s (->> (re-find #"\d+")) parse-long))

(def ^:private mise-aliases
  "mise's tool names where they differ from ours."
  {"babashka" :bb})

(defn mise-file
  "Nearest .mise.toml or mise.toml, walking up from `dir` to the filesystem
  root. Returns nil when there is none — the normal case for the KIT's own
  clone, which means no version constraints apply."
  [dir]
  (loop [d (fs/absolutize (or dir "."))]
    (or (first (filter fs/exists? [(fs/path d ".mise.toml") (fs/path d "mise.toml")]))
        (when-let [parent (fs/parent d)]
          (recur parent)))))

(defn mise-pins
  "Read the [tools] table of the nearest mise config into
  {tool-key {:major _ :pin _ :version _ :source _}}.

  This reads a known table shape with a regex; it is NOT a TOML parser, and
  Babashka has none. It handles `name = \"value\"` lines under [tools], skips
  comment lines, and stops at the next [section] header, so the [alias] table
  that usually follows cannot bleed in. `:version` is the dotted-numeric part
  of the pin (`temurin-21` -> \"21\"), `:major` its leading segment."
  ([] (mise-pins "."))
  ([dir]
   (if-let [f (mise-file dir)]
     (let [body (slurp (str f))
           tools (second (re-find #"(?ms)^\[tools\]\s*$(.*?)(?=^\[|\z)" body))]
       (into {}
             (keep (fn [[_ nm v]]
                     (let [k (get mise-aliases nm (keyword nm))]
                       (when-let [m (major v)]
                         [k {:major m :pin v :version (or (parse-version v) (str m))
                             :source (str (fs/file-name f))}]))))
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
  {:tool :req :tier :does :needed-for :install :status :version :path :pin :known-good :note},
  where :status is
  :ok | :missing | :not-on-path | :broken | :too-old | :wrong-version | :unknown-version.

  `bbin-idx`, `pins` and `good` (the known-good set's `:tools`) are passed in so
  a full report shells `bbin ls` and reads each file once, and so tests can
  inject all three.

  The rules, in order: on PATH at all; for a bbin tool, answers `--help`
  (`:broken` otherwise - the loop cannot run it whatever `bbin ls` says); an
  exact `:major` where the entry has one; then every floor - the entry's `:min`
  and the mise pin - is a MINIMUM. Newer than the pin, or than the known-good
  set, is a note."
  ([spec bbin-idx pins] (probe spec bbin-idx pins {}))
  ([{:keys [tool via cmd min] exact :major :as spec} bbin-idx pins good]
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
         pin (get pins tool)
         floor (:version pin)
         good-version (get good tool)
         numeric? (and version (re-find #"^\d" version))
         below? (fn [v] (and v numeric? (neg? (cmp-to version v))))
         above? (fn [v] (and v numeric? (pos? (cmp-to version v))))
         ;; An exact-major tool's pin is not a floor: a pin on another major disagrees
         ;; with the KIT, and the pin is what to change, not the JDK.
         pin-disagrees? (and exact pin (not= exact (:major pin)))
         floor (when-not exact floor)
         status (cond
                  (not installed?) :missing
                  (nil? path) :not-on-path
                  (and (= :bbin via) (not= 0 (:exit runs))) :broken
                  (and exact (major version) (not= exact (major version))) :wrong-version
                  (below? min) :too-old
                  (below? floor) :too-old
                  (and (= via :flag) (nil? version)) :unknown-version
                  :else :ok)
         notes (cond-> []
                 (= :java tool) (conj (java-home-mismatch version))
                 pin-disagrees?
                 (conj (str (:source pin) " pins " (:pin pin) " but the KIT requires major " exact
                            " - change the pin, not the JDK"))
                 (and (= :ok status) (above? floor))
                 (conj (str "newer than " (:source pin) "'s " (:pin pin) " - fine, a pin is a floor"))
                 (and (= :ok status) numeric? (above? good-version))
                 (conj (str "newer than tested (" good-version ")"))
                 (and (= :ok status) (= :bbin via) good-version (not= version good-version))
                 (conj (str "not the tested commit (" good-version ")")))]
     (cond-> (assoc base :path path :version version :pin pin :known-good good-version :status status)
       (seq (remove nil? notes)) (assoc :note (str/join "; " (remove nil? notes)))))))

(defn report
  "Probe the whole toolchain. Shells `bbin ls` and reads the mise config and
  the known-good set once."
  ([] (report toolchain))
  ([specs] (report specs (mise-pins)))
  ([specs pins] (report specs pins (:tools (known-good))))
  ([specs pins good]
   (let [idx (bbin-index)]
     (mapv #(probe % idx pins good) specs))))

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
  has to act on — a violated pin, a floor not met, a JAVA_HOME that disagrees
  with PATH — and, for anything unusable, what fixes it."
  [{:keys [does status version pin note install] :as r}]
  (str does
       (when (and pin (= :ok status))
         (str "  [pin " (:pin pin) " via " (:source pin) "]"))
       (case status
         :wrong-version (str "\n      must be major " (:major r) (or (some->> pin :pin (str ", and " (:source pin) " pins ")) "")
                             " but " (or version "?") " is running")
         :too-old (str "\n      " (or version "?") " is below the floor"
                       (when pin (str " " (:pin pin) " (" (:source pin) ")")))
         :broken "\n      on PATH and in `bbin ls`, but `--help` did not exit 0 within 5s"
         "")
       (when note (str "\n      " note))
       (when (and (unusable? status) install)
         (str "\n      fix: " install))))

(defn- verdict-line [results tier label]
  (let [bad (filter (fn [{:keys [req status] t :tier}]
                      (and (= req :required) (or (= tier :loop) (= tier t)) (unusable? status)))
                    results)]
    (str "  " label ": "
         (if (seq bad)
           (str "NO - " (str/join ", " (map #(str (name (:tool %)) " (" (name (:status %)) ")") bad)))
           "yes"))))

(defn render-table
  "The human report: one line per tool, the two verdicts, and where the pins
  and the known-good set came from."
  ([results] (render-table results nil nil))
  ([results good pin-file]
   (let [w (apply max 4 (map #(count (name (:tool %))) results))
         row (fn [{:keys [tool version known-good] :as r}]
               (format (str "  %-" w "s  %-12s  %-12s  %-12s %s")
                       (name tool) (or version "—") (or known-good "—") (status-label r) (detail r)))
         soft (remove #(or (= :required (:req %)) (= :ok (:status %))) results)]
     (str "\nClojure toolchain\n\n"
          (format (str "  %-" w "s  %-12s  %-12s  %-12s %s\n") "tool" "here" "known-good" "status" "")
          (str/join "\n" (map row results))
          "\n\n"
          (verdict-line results :gates "The KIT's gates can run here") "\n"
          (verdict-line results :loop "A loop can run here") "\n"
          (when (seq soft)
            (str "  not required, missing: " (str/join ", " (map #(name (:tool %)) soft)) "\n"))
          (when good
            (str "  known-good: the KIT's own set, run together on " (:as-of good) " (" (:platform good)
                 "); newer than it is information, not a fault\n"))
          (when pin-file
            (str "  pins: " pin-file " - floors, except the JDK's major\n"))))))

(defn render-edn
  "A pins-shaped map for the decision log: {tool {:version _ :status _}}."
  [results]
  (into (sorted-map)
        (map (juxt :tool #(-> (select-keys % [:version :status :req :tier])
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
    (when (unusable? status)
      (throw (ex-info (str (name tool)
                           (case status
                             :not-on-path " is installed but its shim is not on PATH"
                             :broken " is installed but does not run"
                             :wrong-version " is on the wrong major version"
                             :too-old " is below the version floor"
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

(defn -main
  "bb doctor [--edn] [--dir PATH] [--tier gates|loop]

  `--dir` reads another project's mise pins instead of the current directory's.
  The toolchain itself is machine-wide, so only the pin comparison is
  directory-sensitive — but that is the part a harness needs when it verifies a
  worktree it has just provisioned. `--tier` chooses which verdict decides the
  exit code: `gates` (what `bb gates` needs, no JVM - the KIT's own gates pass
  it) or `loop` (the default: everything a run needs)."
  [& args]
  (let [dir (or (arg-value args "--dir") ".")
        tier (keyword (or (arg-value args "--tier") "loop"))]
    ;; A missing directory must fail rather than fall through: mise-file walks
    ;; UP from where it starts, so a typo'd path would silently find some
    ;; ancestor's pins and report them as this project's.
    (when-not (fs/directory? dir)
      (println (str "doctor: --dir " dir " is not a directory"))
      (System/exit 1))
    (when-not (#{:gates :loop} tier)
      (println (str "doctor: --tier must be gates or loop, got " (name tier)))
      (System/exit 1))
    (let [good (known-good)
          results (report toolchain (mise-pins dir) (:tools good))]
      (if (some #{"--edn"} args)
        (prn (render-edn results))
        (println (render-table results good (some-> (mise-file dir) fs/canonicalize str))))
      (when-not (ok? results tier)
        (System/exit 1)))))
