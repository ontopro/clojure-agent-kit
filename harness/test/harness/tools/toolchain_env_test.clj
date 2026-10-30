(ns harness.tools.toolchain-env-test
  "The toolchain pack's `toolchain-env` (`tools/toolchain/src/toolchain_env.clj`, plan 7.0, items
  8.3c, 8.6a and 8.6d): the `.env` checked before the shell sources it - flagged, never fenced,
  and held by no repository - the pin read as the doctor reads it,
  the JDK looked for in order, the lines to eval - never a value. Tried by hand in a scratch workspace:
  a local pin of 21 over the project's 25, the keys loaded by the shell, a `$(...)` line flagged with
  nothing on stdout."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.doctor :as doctor]
   [toolchain-env :as env]))

(deftest the-env-file-is-checked-before-the-shell-sources-it
  (let [ok (env/check-env (str "# a comment\n\nA_KEY=bare-value\nB='single quoted, spaces'\n"
                               "C=\"double quoted\"\nEMPTY=\nD=a=b\nexport DATABASE_URL=postgres://u:p@h:5432/db\n"
                               "PORT=3000\nDEBUG=true\n"))]
    (is (:ok? ok))
    (is (= ["A_KEY" "B" "C" "EMPTY" "D" "DATABASE_URL" "PORT" "DEBUG"] (:names ok))
        "any name taken; an empty value - the template's state; a second `=` is the value's; `export` taken"))
  (testing "flagged by line number, the line's text never in the reason"
    (let [{:keys [ok? flags names]} (env/check-env (str "rm -rf ~\nB\n1X=2\nD=$(touch /tmp/x)\nE=`id`\n"
                                                        "F=a b\nG='it's'\nH=\"$HOME\"\nI=x;rm\n"))]
      (is (not ok?))
      (is (= [1 2 3 4 5 6 7 8 9] (map :line flags)))
      (is (= "not NAME=value - the shell would run the line as a command" (:why (first flags))))
      (is (= #{:line :why} (set (mapcat keys flags))) "a flag carries no name and no text")
      (is (not-any? #(re-find #"rm|touch|id|HOME|~" (:why %)) flags) "no text echoed - a bare word IS its text")
      (is (= ["D" "E" "F" "G" "H" "I"] names) "the names of a flagged file, never a value")))
  (testing "a name a program the KIT runs reads its hooks from, in any case, is flagged"
    (let [names ["PATH" "path" "JAVA_HOME" "IFS" "BASH_ENV" "ZDOTDIR" "PROMPT_COMMAND" "LD_PRELOAD"
                 "DYLD_INSERT_LIBRARIES" "JAVA_TOOL_OPTIONS" "java_tool_options" "BABASHKA_PRELOADS"
                 "GIT_CONFIG_COUNT" "JAVA_CMD" "JAVA_OPTS" "CLJ_CONFIG" "DOCKER_HOST" "KIT_TEMPLATE_LOCAL"]
          {:keys [ok? flags]} (env/check-env (str/join (map #(str % "=/w\n") names)))]
      (is (not ok?))
      (is (= (count names) (count flags)))
      (is (every? #(= "a name the shell or a program the KIT runs reads (bb, git, clojure, the JVM)" (:why %)) flags))))
  (testing "a carriage return is flagged on any line - the shell keeps it in the value"
    (let [{:keys [ok? flags]} (env/check-env "A=1\r\n# c\r\n\r\nB=2\n")]
      (is (not ok?))
      (is (= [1 2 3] (map :line flags))))))

(deftest no-repository-holds-the-env-file
  (is (= :tracked (env/repo-verdict 0 nil)))
  (is (= :tracked (env/repo-verdict 0 1)) "tracked is said as tracked - check-ignore says 1 for it too")
  (is (= :not-ignored (env/repo-verdict 1 1)))
  (is (nil? (env/repo-verdict 1 0)) "ignored: held by none")
  (is (nil? (env/repo-verdict 128 nil)) "no repository: passes, not a fault")
  (testing "against git, in a scratch repository"
    (let [r (fs/create-temp-dir)
          git #(apply p/shell {:dir (str r) :out :string :err :string} "git" %&)
          env (str (fs/path r ".env"))]
      (git "init" "-q")
      (spit env "K=\n")
      (is (= :not-ignored (env/repo-check env)))
      (spit (str (fs/path r ".gitignore")) ".env\n")
      (is (nil? (env/repo-check env)))
      (git "add" "-f" ".env")
      (is (= :tracked (env/repo-check env)))
      (is (nil? (env/repo-check (str (fs/path (fs/create-temp-dir) ".env")))) "no repository"))))

(deftest the-pin-is-read-as-the-doctor-reads-it
  ;; the pack cannot require the doctor, so a test holds the two readers to one answer
  (let [check (fn [files nested?]
                (let [d (fs/create-temp-dir)]
                  (doseq [[f text] files] (spit (str (fs/path d f)) text))
                  (let [at (if nested? (doto (fs/path d "a" "b") fs/create-dirs) d)]
                    [(env/java-pin at) (get-in (doctor/mise-pins (str at)) [:java :major])])))]
    (doseq [[label files nested?] [["the project's file" {".mise.toml" "[tools]\njava = \"temurin-25\"\n"} false]
                                   ["the local one over it" {".mise.toml" "[tools]\njava = \"temurin-25\"\n"
                                                             ".mise.local.toml" "[tools]\njava = \"temurin-21\"\n"} false]
                                   ["found walking up" {".mise.toml" "[tools]\njava = \"21\"\nclojure = \"1.12\"\n"} true]]]
      (let [[ours doctors] (check files nested?)]
        (is (= doctors ours) label)
        (is (some? ours) label)))
    (is (nil? (env/java-pin (fs/create-temp-dir))) "no file up: no pin")))

(deftest in-a-workspace-the-pin-is-the-applications
  (let [ws (fs/create-temp-dir)]
    (spit (str (fs/path ws "workspace.edn")) "{:workspace/app \"xyx-app\"}")
    (fs/create-dirs (fs/path ws "xyx-app"))
    (spit (str (fs/path ws "xyx-app" ".mise.toml")) "[tools]\njava = \"temurin-21\"\n")
    (is (= 21 (env/java-pin (env/pin-dir (str ws)))))))

(deftest the-env-file-is-the-one-it-is-given-when-it-is-there
  ;; plan 7.0, item 8.2b: the path is `toolchain-status/place`'s - the folder above the clone - one rule
  ;; where the workspace's and the clone's were two
  (let [f (str (fs/path (fs/create-temp-dir) ".env"))]
    (is (= {:file nil :looked [f]} (env/env-file f)) "absent: none, the path said")
    (spit f "K=\n")
    (is (= {:file f :looked [f]} (env/env-file f)))))

(deftest the-jdk-is-the-first-lookup-that-finds-the-major
  (let [lookups [["the toolchain cache" (constantly nil)]
                 ["macOS's java_home" (fn [m] (str "/jdk/" m))]
                 ["jenv" (fn [m] (str "/jenv/" m))]]
        home? (fn [h m] (and h (str/ends-with? h (str m))))]
    (is (= {:home "/jdk/25" :from "macOS's java_home"} (env/find-jdk 25 lookups home?)))
    (is (nil? (env/find-jdk 25 lookups (constantly false))) "none of that major: nil, the install named")
    (testing "a home is a JDK of the major only when its own release file says so"
      (let [h (fs/create-temp-dir)]
        (fs/create-dirs (fs/path h "bin"))
        (spit (str (fs/path h "bin" "java")) "#!/bin/sh\n")
        (fs/set-posix-file-permissions (fs/path h "bin" "java") "rwxr-xr-x")
        (spit (str (fs/path h "release")) "JAVA_VERSION=\"25.0.5\"\n")
        (is (env/jdk-home? (str h) 25))
        (is (not (env/jdk-home? (str h) 21)))))))

(deftest the-lines-carry-paths-never-a-value
  (is (= ["export JAVA_HOME='/j d'" "export PATH='/j d/bin':\"$PATH\"" "set -a; . '/w/.env'; set +a"]
         (env/env-lines "/j d" "/w/.env")))
  (is (= 2 (count (env/env-lines "/j" nil))) "no .env: no keys line")
  (is (= ["set -a; . '/w/.env'; set +a"] (env/env-lines nil "/w/.env")) "no JDK: the keys line still")
  (is (= "'it'\\''s'" (env/sq "it's")) "a quote in a path stays a path"))

(deftest the-env-record-says-what-each-load-saw-names-never-a-value
  (let [entries [{:at "t1" :sha256 "a" :names ["A" "B"]}]
        e (env/seen-entry entries "t2" "b" {:names ["B" "C"] :flags []} true)]
    (is (= {:at "t2" :sha256 "b" :names ["B" "C"] :added ["C"] :removed ["A"] :flags [] :loaded? true} e))
    (is (= "the .env changed since t1: +C, -A" (env/change-line entries e "/w/.env")))
    (is (= "the .env changed since t1: the same names, values or other lines changed"
           (env/change-line entries (env/seen-entry entries "t2" "c" {:names ["A" "B"]} true) "/w/.env")))
    (is (= "first sight of /w/.env: 2 names, recorded in /w/.env-record.edn" (env/change-line [] e "/w/.env"))
        "the first sight says so - a deleted record shows")
    (is (= ["A" "B"] (:added (env/seen-entry [] "t" "x" {:names ["A" "B"]} true))) "the first sight: every name added"))
  (testing "an acceptance names one sha256, the latest kept"
    (let [es [{:sha256 "a" :accepted-by "P" :at "1"} {:sha256 "b" :names []} {:sha256 "a" :accepted-by "Q" :at "2"}]]
      (is (= "Q" (:accepted-by (env/acceptance es "a"))))
      (is (nil? (env/acceptance es "b")) "a sight is not an acceptance")))
  (testing "a damaged line is skipped and counted"
    (let [f (str (fs/path (fs/create-temp-dir) ".env-record.edn"))]
      (spit f "{:sha256 \"a\"}\nnot edn {\n[1 2]\n{:sha256 \"b\"}\n")
      (is (= {:entries [{:sha256 "a"} {:sha256 "b"}] :unread 2} (env/read-record f)))))
  (testing "against files: a load adds an entry only on a change; a flagged file loads only once accepted"
    (let [d (fs/create-temp-dir)
          env (str (fs/path d ".env"))
          rec (env/record-file env)
          secrets ["sk-or-v1-0123secret" "postgres://u:hunter2@h/db" "rm -rf ~"]
          load! #(let [w (java.io.StringWriter.)
                       r (binding [*err* w] (env/load-env! "t" env))]
                   (assoc r :err (str w)))]
      (spit env (str "OPENROUTER_API_KEY=" (secrets 0) "\nDATABASE_URL='" (secrets 1) "'\n"))
      (is (:load? (load!)))
      (is (= 1 (count (:entries (env/read-record rec)))))
      (load!)
      (is (= 1 (count (:entries (env/read-record rec)))) "unchanged: no entry")
      (spit env (str (slurp env) (secrets 2) "\n"))
      (let [r (load!)]
        (is (not (:load? r)) "flagged, not accepted: not loaded")
        (is (str/includes? (:err r) "1 line(s) flagged") (:err r)))
      (is (false? (:loaded? (last (:entries (env/read-record rec))))))
      (env/append-entry! rec {:at "now" :sha256 (env/sha256 (slurp env)) :accepted-by "P"})
      (let [r (load!)]
        (is (:load? r) "accepted: loaded")
        (is (= "P" (:accepted-by (:accepted r)))))
      (spit env (str (slurp env) "X=1\n"))
      (is (not (:load? (load!))) "changed after the acceptance: not loaded until accepted again")
      (testing "an acceptance before any load names the file's names, so the next change is measured from it"
        (let [d2 (fs/create-temp-dir)
              env2 (str (fs/path d2 ".env"))
              rec2 (env/record-file env2)
              load2! #(binding [*err* (java.io.StringWriter.)] (env/load-env! "t" env2))]
          (spit env2 "A=1\nrm -rf ~\n")
          (env/append-entry! rec2 {:at "t0" :sha256 (env/sha256 (slurp env2)) :names ["A"] :accepted-by "P"})
          (is (:load? (load2!)) "accepted: loaded")
          (is (= 1 (count (:entries (env/read-record rec2)))) "the acceptance is what the file had: no entry")
          (spit env2 (str (slurp env2) "X=1\n"))
          (load2!)
          (is (= ["X"] (:added (last (:entries (env/read-record rec2))))))))
      (let [record (slurp rec)]
        (doseq [s secrets] (is (not (str/includes? record s)) "no value or flagged text in the record"))))))

(defn- scratch-project
  "A project folder holding a copy of the pack as a clone's `KIT/tools/toolchain` - the pack's tasks then
  read the project folder above that clone (plan 7.0, item 8.2b) - with `env` as its `.env`."
  [env]
  (let [d (fs/create-temp-dir)
        pack (fs/path d "KIT" "tools" "toolchain")]
    (fs/create-dirs (fs/parent pack))
    (fs/copy-tree "../tools/toolchain" pack)
    (spit (str (fs/path d ".env")) env)
    {:dir d :config (str (fs/path pack "bb.edn"))}))

(deftest env-accept-needs-a-terminal
  (let [{:keys [dir config]} (scratch-project "rm -rf ~\n")
        {:keys [exit err]} (p/shell {:out :string :err :string :continue true :in ""}
                                    "bb" "--config" config "env-accept")]
    (is (= 1 exit))
    (is (str/includes? err (str "the keys in " (fs/path (fs/real-path dir) ".env")))
        "the .env it is about, named: the one above the clone")
    (is (str/includes? err "needs a terminal"))
    (is (not (fs/exists? (fs/path dir ".env-record.edn"))) "nothing written")))

(deftest the-env-commands-take-no-folder
  ;; plan 7.0, item 8.2b: the .env is the project's, the folder above the clone - a folder given, the
  ;; way the workspace's and the clone's were once chosen between, is refused by name
  (let [{:keys [dir config]} (scratch-project "K=\n")]
    (doseq [task ["toolchain-env" "env-accept"]]
      (let [{:keys [exit out err]} (p/shell {:out :string :err :string :continue true :in ""}
                                            "bb" "--config" config task (str dir))]
        (is (= 1 exit) task)
        (is (= "" out) task)
        (is (str/includes? err "takes no folder") task)))))

(deftest a-keys-file-others-can-read-is-said
  (let [f (str (fs/path (fs/create-temp-dir) ".env"))]
    (spit f "K=\n")
    (fs/set-posix-file-permissions f "rw-r--r--")
    (is (env/others-read? f))
    (fs/set-posix-file-permissions f "rw-------")
    (is (not (env/others-read? f)))
    (is (not (env/others-read? (str f ".absent"))) "no file: nothing to say")))
