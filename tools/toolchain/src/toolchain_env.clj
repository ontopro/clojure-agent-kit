(ns toolchain-env
  "`toolchain-env`: the shell lines for `eval \"$(bb toolchain-env)\"` - the project's toolchain, and
  the line that has the shell load the project's keys, the `.env` in the folder above the KIT's clone
  (plan 7.0, items 8.3c, 8.6a and 8.2b; `toolchain-status/place`).

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

  THE TOOLS ARE THE FOLDER'S (plan 7.0, item 8.3c): its `bin` - every tool the true-up installs, the
  JDK's executables among them - first on the PATH, and its JDK as JAVA_HOME. No JDK is looked for on
  the machine and no pin is read (`.mise.toml` is written into a project for the adopter's own shell;
  the KIT reads the download matrix): what the folder lacks is said with the true-up, and the shell's
  own is left as it is - the doctor holds it to the matrix's (plan 7.0, item 8.3a). Said on
  stderr; stdout carries only the lines to eval."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [toolchain-download-matrix :as dm]
            [toolchain-status :as status]))

;; ---------------------------------------------------------------- the JDK

(defn folder-jdk
  "The JDK home in the project's toolchain folder `cache` - the download matrix's, its `java` there
  (`executable?`) - or nil: none installed, or a platform the matrix publishes none for (`platform`
  nil). One JDK, the folder's (plan 7.0, item 8.3c): no lookup of the machine's, no pin read."
  [matrix cache platform executable?]
  (when platform
    (let [java (dm/bin-path matrix cache :java platform)]
      (when (executable? java) (str (fs/parent (fs/parent java)))))))

;; ---------------------------------------------------------------- the keys

(def ^:private name-re #"[A-Za-z_][A-Za-z0-9_]*")

(def program-hooks
  "Names flagged, in any case (zsh ties `path` to `PATH`): how the shell finds and runs a command,
  reads its own startup and splits words; what the JVM, the `clojure` launcher, bb, git, docker and
  the KIT itself (`KIT_TEMPLATE_LOCAL` is the template `bb init` runs, `KIT_TOOLCHAIN_DIR` the cache 8.3c
  puts on the PATH) load or run from the environment - the JDK's lines come first and a `.env` must not undo them unseen. A
  hint, never finished: a name it misses loads as any value does, and the `.env` record shows it
  arrived. `:names` exact, `:prefixes` every name they begin."
  {:names #{"PATH" "JAVA_HOME" "HOME" "IFS" "ENV" "BASH_ENV" "ZDOTDIR" "SHELL" "CDPATH" "FPATH"
            "PROMPT_COMMAND" "PS4" "JAVA_TOOL_OPTIONS" "JDK_JAVA_OPTIONS" "_JAVA_OPTIONS" "CLASSPATH"
            "JAVA_CMD" "JAVA_OPTS"}
   :prefixes ["LD_" "DYLD_" "BABASHKA_" "GIT_" "CLJ_" "DOCKER_" "KIT_"]})

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
                     ;; the name rides along for `:names`; `select-keys` strips it from the flag
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
  "The `.env` at `path` - the project's, in the folder above the clone (plan 7.0, item 8.2b: one rule,
  where the workspace's and the clone's were two) - or nil when it is absent. With the path looked at."
  [path]
  {:file (when (fs/exists? path) path) :looked [path]})

(defn others-read?
  "Whether the group or others can read the file at `path` - a secrets file is `600`. A wide mode is a
  leak to other users of the machine, not the agent's write: said, never a flag. False where the file
  system has no POSIX modes."
  [path]
  (boolean (try (some #{"GROUP_READ" "OTHERS_READ"} (map str (fs/posix-file-permissions path)))
                (catch Exception _ false))))

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
  every name set and those added and removed since the last entry (every entry, an acceptance too,
  names what the file had), the flags, and `:loaded?` - whether its lines were handed to the shell:
  the record says what was seen, not what the shell did with it. Names, never a value."
  [entries at sha {:keys [names flags]} loaded?]
  (let [before (set (:names (last entries)))
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
  and other text changed; the flagged lines' count. The first sight says so too, naming the record: a
  record that announces its start makes its disappearance visible - a \"first sight\" months in."
  [entries entry file]
  (if-let [prev (last entries)]
    (let [moved (concat (map #(str "+" %) (:added entry)) (map #(str "-" %) (:removed entry)))]
      (str "the .env changed since " (:at prev) ": "
           (if (seq moved) (str/join ", " moved) "the same names, values or other lines changed")
           (when-let [n (not-empty (:flags entry))] (str "; " (count n) " line(s) flagged"))))
    (str "first sight of " file ": " (count (:names entry)) " names, recorded in " (record-file file))))

(defn append-entry! [path entry] (spit path (str (pr-str entry) "\n") :append true))

;; ---------------------------------------------------------------- the lines

(defn sq "`s` single-quoted for a shell." [s] (str "'" (str/replace s "'" "'\\''") "'"))

(defn env-lines
  "The lines to eval - pure: the toolchain folder's `bin` first on the PATH when there is one, the JDK's
  home as JAVA_HOME when the folder has the JDK (its `java` is in that `bin`), then the keys line when
  there is a `.env` - last, so a `.env` read after them is flagged if it names PATH or JAVA_HOME."
  [bin home env]
  (cond-> []
    bin (conj (str "export PATH=" (sq bin) ":\"$PATH\""))
    home (conj (str "export JAVA_HOME=" (sq home)))
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

(defn- placed
  "`toolchain-status/place` for this process, said on stderr - the folder and the `.env` named every
  run - and the command refused when it takes an argument (the folder is the clone's, never given) or
  the folder is no project folder."
  [cmd pack-dir args]
  (let [{:keys [refusal note] :as at} (status/current-place pack-dir)]
    (when (seq args)
      (say cmd (str "takes no folder - the project's is the one above the KIT's clone (" (:folder at) ")"))
      (System/exit 1))
    (say cmd (status/place-line at))
    (when note (say cmd (str "note: " note)))
    (when refusal (say cmd refusal) (System/exit 1))
    at))

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
        (say cmd (change-line entries entry file))
        (append-entry! rec entry)))
    {:load? load? :accepted accepted :checked checked}))

(defn main
  "The `toolchain-env` task, for the project folder above the clone. A `.env` a repository holds, or one with a flagged line no acceptance in the `.env` record names:
  exit 1, nothing on stdout - a fault, nothing from the run is taken until a person has read why. The
  folder's `bin` first on the PATH and its JDK as JAVA_HOME (plan 7.0, item 8.3c); what the folder lacks
  is said with the true-up that brings it, and the shell's own is left as it is - exit 0: the doctor
  holds every tool to the matrix, and says the fix."
  [pack-dir args]
  (let [say (partial say "toolchain-env")
        at (placed "toolchain-env" pack-dir args)
        matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
        platform (dm/platform-key (System/getProperty "os.name") (System/getProperty "os.arch"))
        true-up (str "bb --config " pack-dir "/bb.edn toolchain-install")
        bin (let [b (str (:cache at) "/bin")] (when (fs/directory? b) b))
        home (folder-jdk matrix (:cache at) platform fs/executable?)
        {:keys [file looked]} (env-file (:env at))
        held (some-> file repo-check)]
    (when held (say (held-line file held)) (System/exit 1))
    (when (and file (others-read? file))
      (say (str file " can be read by other users of this machine - `chmod 600 " file "`")))
    (let [{:keys [load? accepted checked]} (when file (load-env! "toolchain-env" file))]
      (when (and file (not load?))
        (say (str file " is not loaded - each line flagged, by number (the text never shown):"))
        (run! say (flag-lines checked))
        (say "open the file and read those lines; change them, or accept the file as it is with `bb env-accept` in a terminal")
        (System/exit 1))
      (say (cond (nil? platform) (dm/unpublished-line (System/getProperty "os.name") (System/getProperty "os.arch"))
                 bin (str "the tools: " bin " first on the PATH")
                 :else (str "no tools in the folder (" (:cache at) "/bin) - `" true-up "` installs them; the shell's own PATH is left as it is")))
      (say (if home
             (str "the JDK " (get-in matrix [:java :version]) " from the folder: " home)
             (str "no JDK in the folder - the shell's own is left as it is; `bb doctor` holds it to the matrix's"
                  (when platform (str "; `" true-up "` brings the matrix's")))))
      (when accepted
        (say (str "the .env's flagged lines were accepted by " (:accepted-by accepted) " at " (:at accepted))))
      (say (if file
             (str "the keys: " file " - " (count (:names checked)) " names, loaded by the shell, no value printed")
             (str "no .env (looked at " (str/join ", " looked) ") - the keys stay what the shell has")))
      (run! println (env-lines bin home (when load? file))))))

(defn accept-main
  "The `env-accept` task: the person's acceptance of the `.env` as it is now, flagged lines and
  all, added to the `.env` record - their name, the time, the file's sha256. At a terminal only
  (`System/console`): a command an agent runs has none. Said plainly: a program can fake a terminal,
  so this is not a lock - every load says who accepted the file and when, in front of the person each
  time. The file is read again after the answers and refused if it changed while they were given."
  [pack-dir args]
  (let [say (partial say "env-accept")
        console (System/console)
        {:keys [file looked]} (env-file (:env (placed "env-accept" pack-dir args)))
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
                  :else (do (append-entry! (record-file file) {:at (now) :sha256 sha :names (:names checked) :accepted-by nm :flags (:flags checked)})
                            (say (str "accepted by " nm " - added to " (record-file file))))))))))))
