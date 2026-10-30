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
  THE `.env` REVIEWED, NOT FENCED (the person, 2026-10-30: \"start with the KISS principle and go with
  only those that are needed\"; item 8.6d): any name is taken - a `.env` holds database URLs, ports and
  flags as well as keys. The one absolute check is that no repository holds it (`repo-check`). Then
  `check-env` FLAGS, by line number and never the line's text, what the shell would run or expand and
  the names a program the KIT runs reads its hooks from (`program-hooks`) - the file is sourced, so
  `$(...)` in it would run. A flagged file is not loaded.

  The JDK: the major the project pins - the `java` line of `.mise.local.toml` over `.mise.toml`, read
  as `harness.setup.doctor/mise-pins` reads it (a test holds the two readers to one answer) - else the
  download matrix's `java`, the KIT's preferred; then the first JDK of that major in the toolchain cache, macOS's `java_home`,
  jenv's, mise's, Linux's `/usr/lib/jvm`, each a home whose own `release` file says that major. Said on
  stderr; stdout carries only the lines to eval.

  JDK-ONLY UNTIL 8.3c: the PATH line puts the JDK's `bin` first and nothing else; the toolchain
  cache's bb, clj-kondo and clojure join it with 8.3c's cache lines."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [toolchain-download-matrix :as dm]
            [toolchain-status :as status]))

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
    ;; -F: no JDK of that major is a failure, never the default JDK printed with exit 0
    (fn [major] (when (fs/exists? "/usr/libexec/java_home") (sh-out "/usr/libexec/java_home" "-v" (str major) "-F")))]
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

(def program-hooks
  "Names flagged, in any case (zsh ties `path` to `PATH`): how the shell finds and runs a command,
  reads its own startup and splits words; what the JVM, the `clojure` launcher, bb and git load or
  run from the environment - the JDK's lines come first and a `.env` must not undo them unseen. A
  hint, never finished: a name it misses loads as any value does, and the `.env` record shows it
  arrived. `:names` exact, `:prefixes` every name they begin."
  {:names #{"PATH" "JAVA_HOME" "HOME" "IFS" "ENV" "BASH_ENV" "ZDOTDIR" "SHELL" "CDPATH" "FPATH"
            "PROMPT_COMMAND" "PS4" "JAVA_TOOL_OPTIONS" "JDK_JAVA_OPTIONS" "_JAVA_OPTIONS" "CLASSPATH"
            "JAVA_CMD" "JAVA_OPTS"}
   :prefixes ["LD_" "DYLD_" "BABASHKA_" "GIT_" "CLJ_"]})

(defn- program-hook? [nm]
  (let [n (str/upper-case nm)]
    (or (contains? (:names program-hooks) n) (some #(str/starts-with? n %) (:prefixes program-hooks)))))

(defn- value-ok?
  "Whether a `.env` value is one the shell takes as it is: bare with nothing it would expand or run,
  single-quoted with no `'` inside, or double-quoted with no `$`, backquote, `\\` or `\"` inside."
  [v]
  (or (re-matches #"[^$`\\\"';&|<>()\s]*" v)
      (re-matches #"'[^']*'" v)
      (re-matches #"\"[^$`\\\"]*\"" v)))

(defn check-env
  "A `.env`'s text checked before the shell sources it - pure: `{:ok? :names :flags [{:line :why}]}`.
  Taken: blank lines, `#` comments, `NAME=value` or `export NAME=value` with a value `value-ok?`, any
  name. A second `=` is the value's. FLAGGED, by line number - never the line's text, since a flagged
  bare word IS its text: a carriage return (the shell keeps it in the value), a line that is not
  `NAME=value` (the shell runs it as a command), a name in `program-hooks`, a value the shell would
  expand or run. `:names` every name set, flagged or not - names, never values."
  [text]
  (let [rows (for [[i line] (map-indexed vector (str/split (or text "") #"\n"))
                   :let [l (str/trim line)]
                   :when (or (str/includes? line "\r") (not (or (str/blank? l) (str/starts-with? l "#"))))]
               (let [[_ nm v] (re-matches #"(?:export\s+)?([^=]*)=(.*)" l)
                     nm (when (and nm (re-matches name-re nm)) nm)
                     flag #(hash-map :line (inc i) :why % :name nm)]
                 (cond (str/includes? line "\r") (flag "a carriage return - save the file with Unix line ends")
                       (nil? nm) (flag "not NAME=value - the shell would run the line as a command")
                       (program-hook? nm) (flag "a name the shell or a program the KIT runs reads (bb, git, clojure, the JVM)")
                       (not (value-ok? v)) (flag "a value the shell would expand or run - quote it in '...'")
                       :else {:name nm})))
        flags (vec (for [r rows :when (:line r)] (select-keys r [:line :why])))]
    {:ok? (empty? flags) :names (vec (distinct (keep :name rows))) :flags flags}))

(defn repo-verdict
  "Whether a repository holds the `.env` - pure, from the exit of `git ls-files --error-unmatch` (0
  tracked, 1 untracked, 128 no repository) and then of `git check-ignore -q` (0 ignored, 1 not
  ignored, 128 no repository; 1 for a tracked file too, so ls-files is asked first). Nil when no
  repository holds it, else `:tracked` or `:not-ignored`."
  [ls-exit ignore-exit]
  (cond (= 0 ls-exit) :tracked
        (= 128 ls-exit) nil
        (= 1 ignore-exit) :not-ignored))

(defn repo-check
  "`repo-verdict` for the `.env` at `file`, git asked in its folder. Nil when git is not installed:
  then no repository on this machine can hold it."
  [file]
  (let [git #(try (:exit (apply p/shell {:dir (str (fs/parent file)) :out :string :err :string :continue true}
                                "git" %&))
                  (catch Exception _ nil))
        nm (str (fs/file-name file))
        ls (git "ls-files" "--error-unmatch" "--" nm)]
    (when ls (repo-verdict ls (when (= 1 ls) (git "check-ignore" "-q" "--" nm))))))

(defn env-file
  "The `.env` the keys line loads: in a workspace (the nearest folder up from `dir` holding
  `workspace.edn`) the workspace's alone, never the clone's; outside one, the KIT clone's own at `kit`
  (its `--kit` review and bake-offs). Nil when that one is absent. With the path looked at."
  [dir kit]
  (let [path (str (fs/path (or (:dir (find-workspace dir)) kit) ".env"))]
    {:file (when (fs/exists? path) path) :looked [path]}))

;; ---------------------------------------------------------------- the `.env` record

(defn sha256 "The hex sha256 of `text`'s UTF-8 bytes." [text]
  (->> (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str text) "UTF-8"))
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

(defn record-file "The `.env` record beside the `.env` at `env`." [env]
  (str (fs/path (fs/parent env) ".env-record.edn")))

(defn read-record
  "The `.env` record's entries, one EDN map per line, in order; `[]` when there is none. A line that is
  not an EDN map is skipped and counted in `:unread`, never fatal: the record is evidence, and a
  damaged line costs only what it said."
  [path]
  (let [lines (if (fs/exists? path) (remove str/blank? (str/split-lines (slurp path))) [])
        read (map #(try (let [m (edn/read-string %)] (when (map? m) m)) (catch Exception _ nil)) lines)]
    {:entries (vec (keep identity read)) :unread (count (remove identity read))}))

(defn seen-entry
  "The entry a load adds when the file's sha256 is not the last entry's - pure: the time, the sha256,
  every name set and those added and removed since the last entry that named them, the flags, and
  whether it was loaded. Names, never a value."
  [entries at sha {:keys [names flags]} loaded?]
  (let [before (set (some :names (rseq (vec entries))))
        now (set names)]
    {:at at :sha256 sha :names (vec names)
     :added (vec (sort (remove before now))) :removed (vec (sort (remove now before)))
     :flags flags :loaded? (boolean loaded?)}))

(defn acceptance
  "The person's acceptance of the file whose sha256 is `sha`, the latest, or nil."
  [entries sha]
  (last (filter #(and (:accepted-by %) (= sha (:sha256 %))) entries)))

(defn change-line
  "What a load says of a change - pure: the names added and removed, or that the names are the same
  and other text changed; the flagged lines' count; nil for the first sight."
  [entries entry]
  (when-let [prev (last entries)]
    (let [moved (concat (map #(str "+" %) (:added entry)) (map #(str "-" %) (:removed entry)))]
      (str "the .env changed since " (:at prev) ": "
           (if (seq moved) (str/join ", " moved) "the same names, values or other lines changed")
           (when-let [n (not-empty (:flags entry))] (str "; " (count n) " line(s) flagged"))))))

(defn append-entry! [path entry] (spit path (str (pr-str entry) "\n") :append true))

;; ---------------------------------------------------------------- the lines

(defn sq "`s` single-quoted for a shell." [s] (str "'" (str/replace s "'" "'\\''") "'"))

(defn env-lines
  "The lines to eval - pure: the JDK's home and its `bin` first on the PATH when there is a JDK, then
  the keys line when there is a `.env`."
  [home env]
  (cond-> []
    home (conj (str "export JAVA_HOME=" (sq home))
               (str "export PATH=" (sq (str home "/bin")) ":\"$PATH\""))
    env (conj (str "set -a; . " (sq env) "; set +a"))))

(defn- say [cmd msg] (binding [*out* *err*] (println (str cmd ": " msg))))

(defn- held-line [file held]
  (str file (if (= :tracked held)
              " is tracked by the repository its folder is in - `git rm --cached` it, and ignore it there"
              " is not ignored by the repository its folder is in - one `git add -A` from committed; add it to the .gitignore")
       " - nothing loaded"))

(defn- flag-lines [{:keys [flags]}]
  (for [{:keys [line why]} flags] (str "  line " line ": " why)))

(defn- now [] (str (java.time.Instant/now)))

(defn- where-env
  "The `.env` the command is about: `<dir>` from `args`, else `KIT_WORKSPACE`, else the folder it is
  run in; the clone's own outside a workspace."
  [pack-dir args]
  (env-file (or (first args) (not-empty (System/getenv "KIT_WORKSPACE")) ".")
            (str (fs/parent (fs/parent (fs/absolutize pack-dir))))))

(defn load-env!
  "The `.env` at `file` read for a load - the record's entry added when the file is not what the last
  entry saw, said on stderr. `{:load? :accepted}`; `:load?` false for a flagged file no acceptance
  names. Never a value on stdout, stderr or in the record."
  [cmd file]
  (let [text (slurp file)
        sha (sha256 text)
        checked (check-env text)
        rec (record-file file)
        {:keys [entries unread]} (read-record rec)
        accepted (when-not (:ok? checked) (acceptance entries sha))
        load? (boolean (or (:ok? checked) accepted))]
    (when (pos? unread) (say cmd (str rec ": " unread " line(s) not an EDN map, skipped")))
    (when (not= sha (:sha256 (last entries)))
      (let [entry (seen-entry entries (now) sha checked load?)]
        (some->> (change-line entries entry) (say cmd))
        (append-entry! rec entry)))
    {:load? load? :accepted accepted :checked checked}))

(defn main
  "The `toolchain-env [<dir>]` task. `<dir>` defaults to `KIT_WORKSPACE`, else the folder it is run
  in. A `.env` a repository holds, or one with a flagged line no acceptance in the `.env` record names:
  exit 1, nothing on stdout - a fault, nothing from the run is taken until a person has read why. No
  JDK of the pinned major: exit 1, and the keys line still printed - what holds is loaded, the missing
  JDK said with the install that brings it."
  [pack-dir args]
  (let [say (partial say "toolchain-env")
        dir (or (first args) (not-empty (System/getenv "KIT_WORKSPACE")) ".")
        pinned (java-pin (pin-dir dir))
        matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
        major (or pinned (major-of (get-in matrix [:java :version])))
        env {:cache (status/cache-dir (System/getenv) (System/getProperty "user.home"))
             :matrix matrix
             :platform (dm/platform-key (System/getProperty "os.name") (System/getProperty "os.arch"))
             :home (System/getProperty "user.home")}
        jdk (find-jdk major (lookups env) jdk-home?)
        {:keys [file looked]} (where-env pack-dir args)
        held (some-> file repo-check)]
    (when held (say (held-line file held)) (System/exit 1))
    (let [{:keys [load? accepted checked]} (when file (load-env! "toolchain-env" file))]
      (when (and file (not load?))
        (say (str file " is not loaded - each line flagged, by number (the text never shown):"))
        (run! say (flag-lines checked))
        (say "open the file and read those lines; change them, or accept the file as it is with `bb env-accept` in a terminal")
        (System/exit 1))
      (say (if jdk
             (str "the JDK " major " (" (if pinned "the project's pin" "the KIT's preferred") ") from " (:from jdk) ": " (:home jdk))
             (str "no JDK " major " found (" (if pinned "the project's pin" "the KIT's preferred, no pin") ") - looked in "
                  (str/join ", " (map first (lookups env))) "; `bb --config " pack-dir "/bb.edn toolchain-install --only java`")))
      (when accepted
        (say (str "the .env's flagged lines were accepted by " (:accepted-by accepted) " at " (:at accepted))))
      (say (if file
             (str "the keys: " file " - " (count (:names checked)) " names, loaded by the shell, no value printed")
             (str "no .env (looked at " (str/join ", " looked) ") - the keys stay what the shell has")))
      (run! println (env-lines (:home jdk) (when load? file)))
      (when-not jdk (System/exit 1)))))

(defn accept-main
  "The `env-accept [<dir>]` task: the person's acceptance of the `.env` as it is now, flagged lines and
  all, added to the `.env` record - their name, the time, the file's sha256. At a terminal only
  (`System/console`): a command an agent runs has none. Said plainly: a program can fake a terminal,
  so this is not a lock - every load says who accepted the file and when, in front of the person each
  time. The file is read again after the answers and refused if it changed while they were given."
  [pack-dir args]
  (let [say (partial say "env-accept")
        console (System/console)
        {:keys [file looked]} (where-env pack-dir args)
        held (some-> file repo-check)]
    (cond
      (nil? console) (do (say "needs a terminal - run it yourself, in the shell that starts the session, not through an agent") (System/exit 1))
      (nil? file) (do (say (str "no .env (looked at " (str/join ", " looked) ")")) (System/exit 1))
      held (do (say (held-line file held)) (System/exit 1))
      :else
      (let [text (slurp file)
            sha (sha256 text)
            checked (check-env text)]
        (if (:ok? checked)
          (say (str file " has no flagged line - it loads as it is, nothing to accept"))
          (do (say (str file " - sha256 " sha " - each line flagged, by number (the text never shown):"))
              (run! say (flag-lines checked))
              (say "open the file and read those lines before you accept it")
              (let [nm (some-> (.readLine console "Your name: " (object-array 0)) str/trim not-empty)
                    word (some-> (.readLine console "Type accept to accept this .env as it is now: " (object-array 0)) str/trim)]
                (cond
                  (nil? nm) (do (say "no name - nothing accepted") (System/exit 1))
                  (not= "accept" word) (do (say "not accepted") (System/exit 1))
                  (not= sha (sha256 (slurp file))) (do (say (str file " changed while you answered - nothing accepted; run it again")) (System/exit 1))
                  :else (do (append-entry! (record-file file) {:at (now) :sha256 sha :accepted-by nm :flags (:flags checked)})
                            (say (str "accepted by " nm " - added to " (record-file file))))))))))))
