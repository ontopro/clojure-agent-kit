(ns harness.gates.kit-pins
  "`bb kit-pins`: the KIT's pins, each as installed here, as pinned, and newest.

  Each tool and library the KIT pins, as installed here, as pinned, and its newest release -
  information for the person, never a fault, exit 0 always.

  THE PERSON'S TWO SCENARIOS (plan 7.0, item 8.1d, 2026-10-29). In the KIT's development the pins
  are the start; this report sets beside each what this machine runs and the newest release, and the
  person gives a go or no-go per row - to the latest, or back to the pin where the machine drifted.
  A version moves only on that go, and a health run then records it as known-good. The KIT an
  adopter gets has its pins equal to known-good, the JDK apart.

  The pins: the download matrix (`tools/toolchain/download-matrix.edn`) for the tools the pack
  fetches; the pack's `bb.edn` deps for its libraries, the Dockerfile's own until a library is the
  pack's (cljfmt, clojure-mcp-light, bbin); the project set (`project-set.edn`) for what only a
  project runs (tailwindcss), and beside each tool it shares with the matrix; XTDB's, the one
  place the KIT writes it (`harness/health/xtdb/deps.edn`, plan 7.0, item 7.3); the template's and
  deps-new's tags (`template-pins.edn`). Installed: the doctor's probe of this machine where the
  doctor has a row, read from `bb doctor --edn` in a subprocess (the doctor's table is the harness's
  resource, not on the root task's classpath); a library the pack loads, its pinned version and whether bb has fetched it;
  `-` otherwise. The releases: each repository's tags by `git ls-remote`, no API and no token, the
  ones matching a release's shape - so a pre-release (`v2.56.0-rc1`) is never newest. A repository
  that does not answer is `unknown`, said as such.

  Run at a plan's start and before a tag (the kit-session skill); `bb kit-status` and `bb
  tag-check` print its newer lines. The KIT's own development only."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def sources
  "Each pin: where its releases are tagged, the shape of a release tag (its group the version),
  where the pin is read - the first place that has it - the doctor's row that probes it, and the
  project set's key for a tool a project runs too."
  [{:tool :bb :repo "babashka/babashka" :tag #"v(\d+\.\d+\.\d+)" :pin [[:download-matrix :bb]] :probe :bb :project :babashka}
   ;; the JDK the KIT installs is Temurin 25: only 25's releases are asked
   {:tool :java :repo "adoptium/temurin25-binaries" :tag #"jdk-(25\.\d+\.\d+(?:\.\d+)?)\+\d+" :pin [[:download-matrix :java]] :probe :java}
   {:tool :clj-kondo :repo "clj-kondo/clj-kondo" :tag #"v(\d+\.\d+\.\d+)" :pin [[:download-matrix :clj-kondo]] :probe :clj-kondo :project :clj-kondo}
   {:tool :clojure :repo "clojure/brew-install" :tag #"(\d+\.\d+\.\d+\.\d+)" :pin [[:download-matrix :clojure]] :probe :clojure :project :clojure}
   {:tool :git :repo "git/git" :tag #"v(\d+\.\d+\.\d+)" :pin [[:download-matrix :git]] :probe :git}
   {:tool :geckodriver :repo "mozilla/geckodriver" :tag #"v(\d+\.\d+\.\d+)" :pin [[:download-matrix :geckodriver]] :probe :geckodriver}
   {:tool :cljfmt :repo "weavejester/cljfmt" :tag #"(\d+\.\d+\.\d+)"
    :pin [[:pack-deps 'dev.weavejester/cljfmt] [:dockerfile "CLJFMT_VERSION"]] :probe :cljfmt :project :cljfmt}
   {:tool :clojure-mcp-light :repo "bhauman/clojure-mcp-light" :tag #"v(\d+\.\d+\.\d+)" :pin [[:dockerfile "MCP_LIGHT_TAG"]] :probe :clj-nrepl-eval}
   {:tool :bbin :repo "babashka/bbin" :tag #"v(\d+\.\d+\.\d+)" :pin [[:dockerfile "BBIN_VERSION"]] :probe :bbin}
   {:tool :xtdb :repo "xtdb/xtdb" :tag #"v(\d+\.\d+\.\d+)" :pin [[:xtdb-deps 'com.xtdb/xtdb-api]]}
   {:tool :tailwindcss :repo "tailwindlabs/tailwindcss" :tag #"v(\d+\.\d+\.\d+)" :pin [[:project-set :tailwindcss]]}
   {:tool :template :repo "ontopro/clojure-stack-lite" :tag #"(kit-v\d+(?:\.\d+)*)" :pin [[:template :git/tag]]}
   {:tool :deps-new :repo "seancorfield/deps-new" :tag #"v(\d+\.\d+\.\d+)" :pin [[:template :deps-new]]}])

(def ^:private where
  {:download-matrix "matrix" :pack-deps "pack bb.edn" :dockerfile "Dockerfile" :project-set "project set"
   :template "template-pins" :xtdb-deps "health/xtdb deps.edn"})

;; ---------------------------------------------------------------- pure

(defn version-key
  "`v` as a vector of its numbers, for comparing: `kit-v1.3` -> [1 3]. A build after `+` is not
  the version: Temurin's `25.0.5+8` is 25.0.5."
  [v]
  (mapv parse-long (re-seq #"\d+" (first (str/split (str v) #"\+")))))

(defn compare-versions
  "Negative, zero or positive as `a` is older than, the same as or newer than
  `b`; a missing part counts as 0, so 1.3 = 1.3.0."
  [a b]
  (let [ka (version-key a) kb (version-key b)
        n (max (count ka) (count kb))
        pad #(into % (repeat (- n (count %)) 0))]
    (compare (pad ka) (pad kb))))

(defn- version? [v] (boolean (and v (re-find #"^\d" (str v)))))

(defn newest
  "The newest release among `tags` whose whole name matches `pattern`, as its
  group captures it; nil when none does."
  [pattern tags]
  (->> tags
       (keep #(second (re-matches pattern %)))
       (sort compare-versions)
       last))

(defn dockerfile-args
  "The Dockerfile's `ARG NAME=default` lines, as {NAME default}."
  [text]
  (into {} (for [[_ k v] (re-seq #"(?m)^ARG ([A-Z_]+)=(\S+)" (or text ""))] [k v])))

(defn pins
  "Each source's pin from the files' contents, as {tool {:version :from}} - the first place of its
  `:pin` that carries it; a pin no file carries is left out. The Dockerfile's `v` prefix is
  dropped, so a pin and a release are compared as the same shape. A pin the Clojure CLI fetches as
  a git dep - the template, deps-new - carries `:gitlib {:lib :sha}` too, for `fetched`."
  [{:keys [matrix pack-deps dockerfile template-pins xtdb-deps]} & [project-set]]
  (let [args (dockerfile-args dockerfile)
        template (get-in template-pins [:templates (:default template-pins)])
        gitlib {:git/tag (some->> (:git/sha template) (hash-map :lib (:template template) :sha))
                :deps-new (some->> (get-in template [:deps-new :git/sha]) (hash-map :lib 'io.github.seancorfield/deps-new :sha))}
        read (fn [[place k]]
               (case place
                 :download-matrix (get-in matrix [k :version])
                 :pack-deps (get-in pack-deps [k :mvn/version])
                 :xtdb-deps (get-in xtdb-deps [k :mvn/version])
                 :dockerfile (some-> (get args k) (str/replace #"^v" ""))
                 :project-set (get-in project-set [:tools k])
                 :template (case k
                             :git/tag (:git/tag template)
                             :deps-new (some-> (get-in template [:deps-new :git/tag]) (str/replace #"^v" "")))))]
    (into {}
          (keep (fn [{:keys [tool pin]}]
                  (some (fn [[from k :as place]]
                          (when-let [v (read place)]
                            [tool (cond-> {:version v :from from}
                                    (and (= :template from) (gitlib k)) (assoc :gitlib (gitlib k)))]))
                        pin)))
          sources)))

(defn rows
  "One row per source: {:tool :pin :from :installed :project :newest :note :marks}. `installed` is
  {tool version-or-text} (absent: `-`), `releases` {tool newest-or-nil}, `project-set` the project
  set, `notes` {tool the doctor's note}. `:marks`, in order: `:unpinned` when no file carries the
  pin - the row kept, so a lost pin shows; `:newer` when a release is newer than the pin; `:unknown`
  when the repository did not answer; `:other-major` when this machine runs a JDK of another major
  than the pin's - allowed, 21 and newer, so not a difference, said in the doctor's own words;
  `:installed-differs` when this machine runs another version than the pin; `:project-differs`
  when the project set writes another version than the pin."
  ([pins installed releases project-set] (rows pins installed releases project-set {}))
  ([pins installed releases project-set notes]
   (for [{:keys [tool project]} sources
         :let [{pin :version from :from} (get pins tool)
               nw (get releases tool)
               inst (get installed tool)
               proj (when (and project pin (not= :project-set from)) (get-in project-set [:tools project]))
               differs? (fn [v] (and (version? v) (version? pin) (not (zero? (compare-versions v pin)))))
               other-major? (and (= :java tool) (version? inst) (version? pin)
                                 (not= (first (version-key inst)) (first (version-key pin))))]]
     (if-not pin
       ;; what this machine runs is so whether or not a file pins it (From Fable 5.1's read of 2267ee7)
       {:tool tool :installed inst :marks [:unpinned]}
       (cond-> {:tool tool :pin pin :from from :installed inst :project proj :newest nw
                :marks (cond-> []
                         (and nw (pos? (compare-versions nw pin))) (conj :newer)
                         (nil? nw) (conj :unknown)
                         other-major? (conj :other-major)
                         (and (differs? inst) (not other-major?)) (conj :installed-differs)
                         (differs? proj) (conj :project-differs))}
         other-major? (assoc :note (or (get notes tool)
                                       (str "installed major " (first (version-key inst)) ", allowed"))))))))

(defn line
  "A row as one line, for `bb kit-status` and `bb tag-check`."
  [{:keys [tool pin newest marks]}]
  (cond (some #{:newer} marks) (str (name tool) " " pin " -> " newest " released")
        (some #{:unknown} marks) (str (name tool) " " pin " - newest unknown (its repository did not answer)")
        :else (str (name tool) " " pin " - the newest")))

(defn newer-lines
  "The lines worth a reader's eye: a newer release, or a repository that did
  not answer. What `bb kit-status` and `bb tag-check` print."
  [rows]
  (map line (filter #(some #{:newer :unknown} (:marks %)) rows)))

(def ^:private mark-text
  {:unpinned "no file carries its pin" :newer "newer released" :unknown "latest unknown"
   :installed-differs "installed differs" :project-differs "project set differs"})

(defn table
  "The report: a header and a line per row - tool, installed, pinned (where), latest, marks - the
  columns padded to their widest cell."
  [rows]
  (let [cells (cons ["tool" "installed" "pinned (where)" "latest" "marks"]
                    (for [{:keys [tool pin from installed project newest note marks]} rows]
                      [(name tool) (or installed "-")
                       (if pin (str pin " (" (where from) (when project (str "; project set " project)) ")") "-")
                       (if pin (or newest "unknown") "-")
                       (str/join "; " (map #(if (= :other-major %) note (mark-text %)) marks))]))
        widths (apply map (fn [& col] (apply max (map count col))) cells)]
    (for [row cells]
      (str/trimr (str/join "  " (map (fn [c w] (format (str "%-" w "s") c)) row widths))))))

;; ---------------------------------------------------------------- facts

(defn- tags
  "The tag names of a GitHub repository, by `git ls-remote`; nil when it does
  not answer within `timeout-ms`."
  [repo timeout-ms]
  (let [proc (p/process {:out :string :err :string :extra-env {"GIT_TERMINAL_PROMPT" "0"}}
                        "git" "ls-remote" "--tags" "--refs" (str "https://github.com/" repo ".git"))
        res (deref proc timeout-ms nil)]
    (if (and res (zero? (:exit res)))
      (map #(str/replace % #"^.*refs/tags/" "") (str/split-lines (:out res)))
      (do (when-not res (p/destroy-tree proc)) nil))))

(defn- read-files [root]
  (let [read #(let [f (fs/path root %)] (when (fs/exists? f) (slurp (str f))))]
    {:matrix (some-> (read "tools/toolchain/download-matrix.edn") edn/read-string)
     :pack-deps (some-> (read "tools/toolchain/bb.edn") edn/read-string :deps)
     :dockerfile (read "Dockerfile")
     :template-pins (some-> (read "harness/resources/template-pins.edn") edn/read-string)
     :project-set (some-> (read "harness/resources/project-set.edn") edn/read-string)
     :xtdb-deps (some-> (read "harness/health/xtdb/deps.edn") edn/read-string :deps)}))

(defn read-pins
  "The pins as the clone at `root` has them."
  [root]
  (let [files (read-files root)] (pins files (:project-set files))))

(defn fetched
  "A library: its pinned version and whether it is fetched - a Maven dep (the pack's `bb.edn`, the
  health check's XTDB) into `~/.m2`; a git dep the Clojure CLI fetches (the template, deps-new) into
  `~/.gitlibs/libs/<lib>/<full sha>`, the pin's short sha a prefix of the folder's name. Nil for a pin
  that is neither."
  [{:keys [version from gitlib]} lib home]
  (cond (#{:pack-deps :xtdb-deps} from)
        (let [[group artifact] (str/split (str lib) #"/")
              dir (fs/path home ".m2" "repository" (str/replace group "." "/") artifact version)]
          (str version (if (fs/exists? dir) ", fetched" ", not fetched")))
        gitlib
        (let [dir (fs/path home ".gitlibs" "libs" (str (:lib gitlib)))
              have? (and (fs/directory? dir)
                         (some #(str/starts-with? (str (fs/file-name %)) (:sha gitlib)) (fs/list-dir dir)))]
          (str version (if have? ", fetched" ", not fetched")))))

(defn installed
  "What this machine has of each source: a library the pack loads by `fetched`, else the doctor's
  probe of its row (`doctor` is `bb doctor --edn`'s map) - the version, or the doctor's status when
  it found none."
  [pins doctor home]
  (let [by-probe (update-vals doctor #(or (:version %) (some-> (:status %) name)))]
    (into {}
          (keep (fn [{:keys [tool probe pin]}]
                  (let [lib (some (fn [[place k]] (when (#{:pack-deps :xtdb-deps} place) k)) pin)]
                    (when-let [v (or (fetched (get pins tool) lib home)
                                     (get by-probe probe))]
                      [tool v]))))
          sources)))

(defn read-doctor
  "`bb doctor --edn`'s output read; {} when it is not a map. Its exit is the doctor's verdict on the
  machine, not on its map: it prints the map and exits 1 when a required tool is unusable - the
  machine this report is most for (From Fable 5.1's read of `51ce107`)."
  [out]
  (let [m (try (edn/read-string out) (catch Exception _ nil))]
    (if (map? m) m {})))

(defn- doctor-edn
  "`bb doctor --edn` run in the clone's `harness/`, read whatever its exit."
  [root]
  (read-doctor (:out (p/shell {:out :string :err :string :continue true :dir (str (fs/path root "harness"))}
                              "bb" "doctor" "--edn"))))

(defn check
  "The rows for the clone at `root`, every repository asked at once. With `installed?` false the
  machine is not probed - `bb kit-status` and `bb tag-check` want the releases alone."
  ([root] (check root {}))
  ([root {:keys [timeout-ms installed?] :or {timeout-ms 20000 installed? false}}]
   (let [files (read-files root)
         ps (pins files (:project-set files))
         doctor (if installed? (doctor-edn root) {})
         asked (into {} (for [{:keys [tool repo tag]} sources :when (get-in ps [tool :version])]
                          [tool (future (some->> (tags repo timeout-ms) (newest tag)))]))]
     (rows ps (if installed? (installed ps doctor (System/getProperty "user.home")) {}) (update-vals asked deref)
           (:project-set files) (into {} (keep (fn [[t {:keys [note]}]] (when note [t note]))) doctor)))))

(defn -main [& _]
  (let [root (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                               "git" "rev-parse" "--show-toplevel")]
               (if (zero? exit) (str/trim out) "."))
        rs (check root {:installed? true})]
    (println "kit-pins: the KIT's pins - each as installed here, as pinned, and its newest release (git ls-remote); information, never a fault")
    (doseq [l (table rs)] (println (str "  " l)))
    (println (str "\n" (count (filter #(some #{:newer} (:marks %)) rs)) " newer released, "
                  (count (filter #(some #{:installed-differs} (:marks %)) rs)) " installed differ. "
                  "Each row is the person's go or no-go - to the latest, or back to the pin where this machine drifted; "
                  "a version moves only on that go, and a health run then records it (kit-session skill)."))))
