(ns harness.loop.review-repl
  "The code reviewer's REPL: a container per review round, the security review's sandbox as it
  stands - the toolchain image, no network, the dependency cache read-only, limits on memory,
  processes and processors - holding a COPY of the gate worktree's tree, the uncommitted files
  included, with the project's nREPL started in it. The reviewer's `nrepl_eval` is a `docker
  exec` into it (`harness.models.code-review-tools`).

  NOTHING THE LOOP MERGES IS MOUNTED: not the gate worktree, not the repository, not a home
  folder. The copy is scratch under the run directory, mounted writable because the JVM writes
  beside it (`.nrepl-port`, `.cpcache`), and removed with the container after the verdict.

  IT DEGRADES, NEVER BLOCKS (method §10): no `docker`, a daemon down, an image missing, an nREPL
  that never writes its port - each is `{:none \"why\"}`, the container and the copy removed, and
  the review runs as it did before, with `read_file` and `note`. `:review/repl? false` in
  `loop.edn` skips it.

  Two phases, as the security sandbox: the first container has the network and warms the cache
  for the nREPL's command; the second has none. The container is named under `kit-review-`, so
  one a killed run left is cleared by the next review's `clear-stale!`.

  PURE UP TO THE EDGE: the `*-argv` functions build the commands; `start!` and `stop!` take the
  function that runs one (`run`), so the order of the steps is tested without Docker."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [harness.models.code-review-tools :as crt]
   [harness.models.security-review-sandbox :as sbx]))

(defn warm-script
  "What the nREPL's command `cmd` (`[\"clojure\" \"-Srepro\" \"-M:nrepl\"]`) needs, and what
  `clj-nrepl-eval` needs: a bbin script whose dependencies are resolved when it starts, into
  `/root/.m2` - the volume here, which hides the image's own. Fetched once with the network, or
  the first evaluation in the second container fails at `babashka.deps/add-deps` (the live
  check, 2026-10-27)."
  [cmd]
  (str "set -e\n"
       (str/join " " (concat [(first cmd) "-P"] (rest cmd))) "\n"
       "clj-nrepl-eval --help > /dev/null 2>&1 || true\n"))

(defn warm-argv
  "The first container: network on, the cache writable, the copy mounted."
  [{:keys [image clone cmd]}]
  ["docker" "run" "--rm"
   "-v" (str clone ":" sbx/app-dir)
   "-v" (str sbx/m2-volume ":/root/.m2")
   "-w" sbx/app-dir
   image "bash" "-c" (warm-script cmd)])

(defn run-argv
  "The second container, left running: no network, the cache read-only, the security sandbox's
  limits, the copy mounted - and nothing else."
  [{:keys [image clone name]}]
  ["docker" "run" "-d" "--name" name
   "--network" "none"
   "--memory" "4g" "--cpus" "2" "--pids-limit" "512"
   "--cap-drop" "ALL" "--security-opt" "no-new-privileges"
   "-v" (str clone ":" sbx/app-dir)
   "-v" (str sbx/m2-volume ":/root/.m2:ro")
   "-w" sbx/app-dir
   image "sleep" "infinity"])

(defn nrepl-argv
  "The nREPL started detached in the container, its output kept there."
  [name cmd]
  ["docker" "exec" "-d" "-w" sbx/app-dir name "bash" "-c" (str (str/join " " cmd) " > /tmp/nrepl.log 2>&1")])

(defn port-argv
  "Read the port the nREPL bound."
  [name]
  ["docker" "exec" "-w" sbx/app-dir name "cat" ".nrepl-port"])

(defn copy-tree!
  "The files of `worktree` git tracks, and those it does not but would not ignore - the
  uncommitted ones the gates ran on - copied into `clone`. A `.nrepl-port` is not copied: the
  sandbox's nREPL writes its own."
  [worktree clone]
  (let [{:keys [exit out err]} (p/shell {:dir (str worktree) :out :string :err :string :continue true}
                                        "git" "ls-files" "-co" "--exclude-standard" "-z")]
    (when-not (zero? exit)
      (throw (ex-info (str "git ls-files in the gate worktree failed: " (str/trim (str err))) {})))
    (fs/create-dirs clone)
    (doseq [rel (remove #(or (str/blank? %) (= ".nrepl-port" %)) (str/split (str out) #"\u0000"))
            :let [from (fs/path worktree rel)]
            :when (fs/regular-file? from {:nofollow-links true})]
      (fs/create-dirs (fs/parent (fs/path clone rel)))
      (fs/copy from (fs/path clone rel) {:replace-existing true}))
    clone))

(defn- default-run [argv opts]
  (try (apply p/shell (merge {:out :string :err :string :continue true} opts) argv)
       (catch Exception e {:exit 127 :out "" :err (ex-message e)})))

(defn- tail [{:keys [out err]}]
  (str/join " | " (take-last 3 (remove str/blank? (str/split-lines (str out err))))))

(defn stop!
  "Remove the container and the copy; nil-safe, never throws."
  ([sb] (stop! sb default-run))
  ([{:keys [name clone]} run]
   (when name (try (sbx/stop! name run) (catch Exception _ nil)))
   (when (and clone (fs/exists? clone)) (try (fs/delete-tree clone) (catch Exception _ nil)))
   nil))

(defn start!
  "Copy the gate worktree's tree to `<run-dir>/review-clone`, warm the cache, start the container
  and its nREPL, read the port and check it answers `(+ 1 1)`. Returns `{:name :port :clone
  :sandbox-ms}`, or `{:none \"why\"}` with everything removed - never throws.

  `opts`: `:kit :worktree :run-dir :cmd`, and optionally `:image :run :sleep :timeout-ms`."
  [{:keys [kit worktree run-dir cmd image run sleep timeout-ms]
    :or {run default-run sleep #(Thread/sleep (long %)) timeout-ms 120000}}]
  (let [t0 (System/currentTimeMillis)
        clone (str (fs/path run-dir "review-clone"))
        name (sbx/container-name)
        sb {:name name :clone clone}
        step! (fn [what argv]
                (let [r (run argv {})]
                  (when-not (zero? (:exit r))
                    (throw (ex-info (str what " failed: " (tail r)) {:step what})))
                  r))]
    (try
      (when (fs/exists? clone) (fs/delete-tree clone))
      (copy-tree! worktree clone)
      (let [spec {:image (or image (sbx/image-tag kit)) :clone clone :name name :cmd cmd}]
        (try (sbx/clear-stale! run) (catch Exception _ nil))
        (step! "warming the dependency cache" (warm-argv spec))
        (step! "starting the container" (run-argv spec))
        (step! "starting the nREPL" (nrepl-argv name cmd))
        (let [port (loop [waited 0]
                     (let [r (run (port-argv name) {})
                           port (when (zero? (:exit r)) (some-> (:out r) str/trim not-empty parse-long))]
                       (cond
                         port port
                         (>= waited timeout-ms) (throw (ex-info (str "the nREPL wrote no port in " (quot timeout-ms 1000) "s") {}))
                         :else (do (sleep 1000) (recur (+ waited 1000))))))
              probe (run (crt/eval-argv name port "(+ 1 1)") {})]
          (when-not (and (zero? (:exit probe)) (str/includes? (str (:out probe)) "2"))
            (throw (ex-info (str "the nREPL on port " port " did not answer (+ 1 1): " (tail probe)) {})))
          (assoc sb :port port :sandbox-ms (- (System/currentTimeMillis) t0))))
      (catch Exception e
        (stop! sb run)
        {:none (str "the review REPL: " (ex-message e))}))))
