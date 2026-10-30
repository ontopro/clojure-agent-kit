(ns toolchain-env
  "`toolchain-env [<dir>]`: the shell lines for `eval \"$(bb toolchain-env)\"` - the JDK the project
  pins, and the line that has the shell load the workspace's keys (plan 7.0, items 8.3c and 8.6a).

  TOOLING, NEVER THE HARNESS (the person, 2026-10-28: \"setting the Java version to use seems like a
  tooling concern or belongs in a bb script - not in the harness\"; \"this is environment setup. so
  it does not belong in the harness code\"). The harness reads the environment; this makes it, before
  the session starts. Nothing here SETS a variable: it prints lines, and the shell that evals them sets
  them.

  NO VALUE IS EVER PRINTED (the writer's finding, the person's \"2. Yes\", 2026-10-28): the keys line is
  `set -a; . '<.env>'; set +a`, so the shell reads the file itself and no key passes through this
  command's output - a bare run, or an agent's, puts nothing secret on a screen or in a transcript.
  And the file is checked first (`check-env`): a `.env` is sourced, so `$(...)` in it would run - a
  line the shell would expand or run is refused by its line number, its value never echoed.

  The JDK: the major the project pins - the `java` line of `.mise.local.toml` over `.mise.toml`, read
  as `harness.setup.doctor/mise-pins` reads it (a test holds the two readers to one answer) - else 25,
  the KIT's preferred; then the first JDK of that major in the toolchain cache, macOS's `java_home`,
  jenv's, mise's, Linux's `/usr/lib/jvm`, each a home whose own `release` file says that major. Said on
  stderr; stdout carries only the lines to eval, and nothing at all when a step refuses."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [toolchain-download-matrix :as dm]
            [toolchain-status :as status]))

(def preferred-major 25)

;; ---------------------------------------------------------------- the pin

(def ^:private mise-names
  "mise's config files in one folder, the project's before the local one mise reads over it."
  [[".mise.toml" "mise.toml"] [".mise.local.toml" "mise.local.toml"]])

(defn toml-java
  "The `java` line's value under `[tools]` in a mise file's text, or nil - pure."
  [text]
  (some->> (re-find #"(?ms)^\[tools\][ \t]*\n(.*?)(?=^\[|\z)" (or text ""))
           second
           (re-find #"(?m)^[ \t]*java[ \t]*=[ \t]*\"([^\"]*)\"")
           second))

(defn major-of
  "The major a pin or a version names: `temurin-25` → 25, `21.0.13` → 21, `25` → 25; nil for none."
  [v]
  (some->> v (re-find #"\d+") parse-long))

(defn java-pin
  "The JDK major pinned at `dir`: the nearest folder up holding a mise file, its `.mise.local.toml`'s
  `java` line over its `.mise.toml`'s, as mise reads them. Nil when no file up names one."
  [dir]
  (loop [d (fs/absolutize dir)]
    (when d
      (let [found (keep (fn [names] (first (filter fs/exists? (map #(fs/path d %) names)))) mise-names)]
        (if (seq found)
          (some->> (reverse found) (keep #(toml-java (slurp (str %)))) first major-of)
          (recur (fs/parent d)))))))

(defn find-workspace
  "The nearest folder up from `dir` holding `workspace.edn`, as `harness.setup.workspace` finds it,
  with the workspace's map; nil outside a workspace."
  [dir]
  (loop [d (fs/absolutize dir)]
    (when d
      (let [f (fs/path d "workspace.edn")]
        (if (fs/exists? f)
          {:dir (str d) :workspace (edn/read-string (slurp (str f)))}
          (recur (fs/parent d)))))))

(defn pin-dir
  "Where the project's pin is read from: in a workspace, its application's folder (the pin is the
  application's `.mise.toml`); outside one, `dir` itself."
  [dir]
  (if-let [{ws :dir {app :workspace/app} :workspace} (find-workspace dir)]
    (str (fs/path ws (or app ".")))
    (str (fs/absolutize dir))))

;; ---------------------------------------------------------------- the JDK

(defn release-major
  "The major a JDK home's own `release` file says (`JAVA_VERSION=\"25.0.5\"`), or nil."
  [home]
  (let [f (fs/path home "release")]
    (when (fs/exists? f)
      (some->> (re-find #"(?m)^JAVA_VERSION=\"([^\"]+)\"" (slurp (str f))) second major-of))))

(defn jdk-home?
  "Whether `home` is a JDK of `major`: its `bin/java` runs and its `release` says that major."
  [home major]
  (boolean (and home (fs/executable? (fs/path home "bin" "java")) (= major (release-major home)))))

(defn- sh-out [& args]
  (try (let [{:keys [exit out]} (apply p/shell {:out :string :err :string :continue true} args)]
         (when (zero? exit) (not-empty (str/trim out))))
       (catch Exception _ nil)))

(defn lookups
  "Where a JDK of a major is looked for, in order: `[label (fn [major] home-or-nil)]`. `env` is
  `{:cache :matrix :platform :home}`."
  [{:keys [cache matrix platform home]}]
  [["the toolchain cache"
    (fn [major]
      (when (= major (major-of (get-in matrix [:java :version])))
        (some-> (dm/bin-path matrix cache :java platform) fs/parent fs/parent str)))]
   ["macOS's java_home"
    (fn [major] (when (fs/exists? "/usr/libexec/java_home") (sh-out "/usr/libexec/java_home" "-v" (str major))))]
   ["jenv"
    (fn [major] (let [d (fs/path home ".jenv" "versions" (str major))] (when (fs/exists? d) (str (fs/real-path d)))))]
   ["mise"
    (fn [major] (some->> (fs/glob (fs/path home ".local" "share" "mise" "installs" "java") (str "temurin-" major "*"))
                         (map str) sort last))]
   ["Linux's /usr/lib/jvm"
    (fn [major] (some->> (when (fs/exists? "/usr/lib/jvm") (fs/glob "/usr/lib/jvm" (str "*-" major "*")))
                         (map str) sort last))]])

(defn find-jdk
  "The first JDK of `major` the `lookups` find: `{:home :from}`, or nil. `home?` the test of a home."
  [major lookups home?]
  (some (fn [[label f]] (let [h (f major)] (when (home? h major) {:home h :from label}))) lookups))

;; ---------------------------------------------------------------- the keys

(def ^:private name-re #"[A-Za-z_][A-Za-z0-9_]*")

(defn- value-ok?
  "Whether a `.env` value is one the shell takes as it is: bare with nothing it would expand or run,
  single-quoted with no `'` inside, or double-quoted with no `$`, backquote, `\\` or `\"` inside."
  [v]
  (or (re-matches #"[^$`\\\"';&|<>()\s]*" v)
      (re-matches #"'[^']*'" v)
      (re-matches #"\"[^$`\\\"]*\"" v)))

(defn check-env
  "A `.env`'s text checked before the shell sources it - pure: `{:ok? :names :refused [{:line :why}]}`.
  Taken: blank lines, `#` comments, `NAME=value` with a value `value-ok?`. Refused by line number, the
  value never in the reason: an `export` keyword, a line with no `=`, a name that is not one, a value
  the shell would expand or run - a `.env` is sourced, so `$(...)` in it would execute."
  [text]
  (let [rows (for [[i line] (map-indexed vector (str/split-lines (or text "")))
                   :let [l (str/trim line)]
                   :when (not (or (str/blank? l) (str/starts-with? l "#")))]
               (let [[_ nm v] (re-matches #"([^=]*)=(.*)" l)]
                 (cond (str/starts-with? l "export ") {:line (inc i) :why "an `export` keyword - write NAME=value alone"}
                       (nil? nm) {:line (inc i) :why "no `=` - a line is NAME=value"}
                       (not (re-matches name-re nm)) {:line (inc i) :why "not a variable's name before the `=`"}
                       (not (value-ok? v)) {:line (inc i) :why "a value the shell would expand or run - quote it in '...'"}
                       :else {:name nm})))
        refused (vec (filter :line rows))]
    {:ok? (empty? refused) :names (vec (keep :name rows)) :refused refused}))

(defn env-file
  "The `.env` the keys line loads: the workspace's (the nearest folder up from `dir` holding
  `workspace.edn`), else the KIT clone's own at `kit` (its `--kit` review and bake-offs); nil when
  neither has one. With the paths looked at."
  [dir kit]
  (let [paths (distinct (keep identity [(some-> (find-workspace dir) :dir (fs/path ".env") str)
                                        (str (fs/path kit ".env"))]))]
    {:file (first (filter fs/exists? paths)) :looked paths}))

;; ---------------------------------------------------------------- the lines

(defn sq "`s` single-quoted for a shell." [s] (str "'" (str/replace s "'" "'\\''") "'"))

(defn env-lines
  "The lines to eval - pure: the JDK's home and its `bin` first on the PATH, then the keys line when
  there is a `.env`."
  [home env]
  (cond-> [(str "export JAVA_HOME=" (sq home))
           (str "export PATH=" (sq (str home "/bin")) ":\"$PATH\"")]
    env (conj (str "set -a; . " (sq env) "; set +a"))))

(defn main
  "The `toolchain-env [<dir>]` task. `<dir>` defaults to `KIT_WORKSPACE`, else the folder it is run
  in. Exit 1, nothing on stdout, when no JDK of the pinned major is found or the `.env` is refused."
  [pack-dir args]
  (let [say #(binding [*out* *err*] (println (str "toolchain-env: " %)))
        dir (or (first args) (not-empty (System/getenv "KIT_WORKSPACE")) ".")
        kit (str (fs/parent (fs/parent (fs/absolutize pack-dir))))
        pinned (java-pin (pin-dir dir))
        major (or pinned preferred-major)
        matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
        env {:cache (status/cache-dir (System/getenv) (System/getProperty "user.home"))
             :matrix matrix
             :platform (dm/platform-key (System/getProperty "os.name") (System/getProperty "os.arch"))
             :home (System/getProperty "user.home")}
        jdk (find-jdk major (lookups env) jdk-home?)
        {:keys [file looked]} (env-file dir kit)
        checked (some-> file slurp check-env)]
    (cond
      (nil? jdk)
      (do (say (str "no JDK " major " found (" (if pinned "the project's pin" "the KIT's preferred, no pin") ") - looked in "
                    (str/join ", " (map first (lookups env))) "; `bb --config " pack-dir "/bb.edn toolchain-install --only java`"))
          (System/exit 1))
      (and checked (not (:ok? checked)))
      (do (say (str file " is not sourced - each line the shell would not take as it is:"))
          (doseq [{:keys [line why]} (:refused checked)] (say (str "  line " line ": " why)))
          (System/exit 1))
      :else
      (do (say (str "the JDK " major " (" (if pinned "the project's pin" "the KIT's preferred") ") from " (:from jdk) ": " (:home jdk)))
          (say (if file
                 (str "the keys: " file " - " (count (:names checked)) " names, loaded by the shell, no value printed")
                 (str "no .env (looked at " (str/join ", " looked) ") - the keys stay what the shell has")))
          (run! println (env-lines (:home jdk) file))))))
