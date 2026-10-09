(ns harness.models.review-sandbox
  "The container a security review runs in: a clone of the application under review, the
  application running on its loopback port, no network.

  TWO PHASES, BECAUSE A DEPENDENCY NEEDS THE NETWORK ONCE. The first container has the network
  and warms the dependency cache (a named volume) for every alias the review will use: `bb`'s
  own dependencies, then `clojure -P` for each of `:dev:test`, `:test` and the `-X:test` run.
  The second has `--network none`, the cache read-only, the clone mounted, and limits on memory,
  processes and processors; the application is started in it with `bb serve` and everything
  the reviewer's tools do - a request, a test run - is a `docker exec` into it. Nothing in the
  second phase can reach anything but its own loopback.

  The clone is scratch, made by the caller; the reviewer's tests are the new files below its
  `test/`, found there when the review is over. The model's own API calls are the harness's,
  made outside; only the tools' commands run in here.

  PURE UP TO THE EDGE: the `*-argv` functions build the commands and `parse-answer` reads a
  request's output; `start!` and `stop!` take the function that runs a command (`run`, default
  `babashka.process/shell`), so the order of the steps is tested without Docker."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def app-dir "/work/app")
(def review-dir "/review")
(def m2-volume "kit-review-m2")
(def app-port 8000)

(defn image-tag
  "The toolchain image `bb docker-gates` builds for the known-good set on `kit`, by its date."
  [kit]
  (str "clojure-agent-kit-toolchain:" (:as-of (edn/read-string (slurp (str (fs/path kit "harness" "resources" "known-good.edn")))))))

;; ---------------------------------------------------------------------------
;; the commands
;; ---------------------------------------------------------------------------

(def warm-script
  "Every dependency the review's commands will resolve, fetched while there is a network."
  (str "set -e\n"
       "bb tasks > /dev/null\n"
       "for a in -M:dev:test -M:test -X:test; do clojure -Srepro -P $a; done\n"))

(defn warm-argv
  "The first container: network on, the cache writable, the clone mounted."
  [{:keys [image clone]}]
  ["docker" "run" "--rm"
   "-v" (str clone ":" app-dir)
   "-v" (str m2-volume ":/root/.m2")
   "-w" app-dir
   image "bash" "-c" warm-script])

(defn run-argv
  "The second container, left running: no network, the cache read-only, limits, the clone and
  the request script mounted, nothing to do until it is told."
  [{:keys [image clone name review]}]
  ["docker" "run" "-d" "--name" name
   "--network" "none"
   "--memory" "4g" "--cpus" "2" "--pids-limit" "512"
   "--cap-drop" "ALL" "--security-opt" "no-new-privileges"
   "-v" (str clone ":" app-dir)
   "-v" (str m2-volume ":/root/.m2:ro")
   "-v" (str review ":" review-dir ":ro")
   "-w" app-dir
   image "sleep" "infinity"])

(defn exec-argv
  "A command run in the running container, in the application's folder."
  [name & cmd]
  (into ["docker" "exec" "-w" app-dir name] cmd))

(defn serve-argv
  "The application, started detached; its output kept in the container."
  [name]
  ["docker" "exec" "-d" "-w" app-dir name "bash" "-c" "bb serve > /tmp/serve.log 2>&1"])

(def pristine-copy
  "Copy the application into an empty folder and run there: the files git tracks, and the files it
  does not but would not ignore - so the reviewer's new tests come along, and `target/`, `db/` and
  `.cpcache/` (all ignored) do not. A run in the clone itself saw what earlier runs had left: a
  test that wrote into `target/` passed in the review only because a whole-suite run had made the
  folder, and failed in a fresh clone. Every run now starts from the same tree a fresh clone has."
  (str "set -e\n"
       "rm -rf /tmp/run && mkdir -p /tmp/run\n"
       "git -c safe.directory='*' ls-files -co --exclude-standard -z | tar --null -cf - -T - | tar -xf - -C /tmp/run\n"
       "cd /tmp/run\n"))

(defn tests-argv
  "The application's tests in the container, in a pristine copy of it: all of them as its own `test`
  task runs them, or one namespace through `clojure.test`, exiting non-zero when it fails. The
  namespace is a name `review-tools` has already checked, which is why it can go in a command."
  [name ns-name]
  (exec-argv name "bash" "-c"
             (str pristine-copy
                  (if ns-name
                    (str "exec clojure -Srepro -M:test -e \"(require '" ns-name ") (let [r (clojure.test/run-tests '" ns-name ")] "
                         "(System/exit (if (clojure.test/successful? r) 0 1)))\"")
                    "exec clojure -Srepro -X:test"))))

(defn request-argv
  "One request, as the script in the container sends it; the request is one JSON argument, so no
  shell reads it."
  [name {:keys [method path headers body]}]
  (exec-argv name "bb" (str review-dir "/request.bb")
             (json/generate-string {:method method :path path :headers (or headers {}) :body body})))

(defn parse-answer
  "The script's one line of JSON as `{:status :headers [[name value] ...] :body}`, or
  `{:error \"...\"}` when it said so or said nothing readable."
  [out]
  (let [line (last (remove str/blank? (str/split-lines (str out))))
        m (try (json/parse-string line true) (catch Exception _ nil))]
    (cond
      (nil? m) {:error (str "the request script's answer was not readable: " (subs (str line) 0 (min 120 (count (str line)))))}
      (:error m) {:error (:error m)}
      :else (select-keys m [:status :headers :body]))))

;; ---------------------------------------------------------------------------
;; the lifecycle
;; ---------------------------------------------------------------------------

(defn- default-run [argv opts]
  (apply p/shell (merge {:out :string :err :string :continue true} opts) argv))

(defn- tail [{:keys [out err]}]
  (str/join "\n" (take-last 8 (str/split-lines (str out err)))))

(defn container-name
  "A name no other review's container has."
  []
  (str "kit-review-" (subs (str (random-uuid)) 0 8)))

(defn stop!
  "Remove the container, running or not."
  ([name] (stop! name default-run))
  ([name run] (run ["docker" "rm" "-f" name] {})))

(defn start!
  "Warm the cache, start the isolated container, start the application in it and wait until
  `/health` answers. Returns the sandbox the review tools are handed:
  `{:name _ :request (fn [req]) :run-tests (fn [ns-or-nil]) :stop! (fn [])}`. Throws, with the
  container removed, when a step fails or the application does not come up in `timeout-ms`.

  `opts`: `:kit :clone`, and optionally `:image`, `:run`, `:sleep`, `:timeout-ms`."
  [{:keys [kit clone image run sleep timeout-ms]
    :or {run default-run sleep #(Thread/sleep (long %)) timeout-ms 120000}}]
  (let [image (or image (image-tag kit))
        name (container-name)
        spec {:image image :clone (str clone) :name name
              :review (str (fs/path kit "harness" "resources" "review"))}
        step! (fn [what argv opts]
                (let [r (run argv opts)]
                  (when-not (zero? (:exit r))
                    (stop! name run)
                    (throw (ex-info (str "the review sandbox: " what " failed: " (tail r)) {:step what :argv argv})))
                  r))
        ask (fn [req] (parse-answer (:out (run (request-argv name req) {}))))]
    (step! "warming the dependency cache" (warm-argv spec) {})
    (step! "starting the container" (run-argv spec) {})
    (step! "starting the application" (serve-argv name) {})
    (loop [waited 0]
      (let [r (ask {:method "GET" :path "/health"})]
        (cond
          (= 200 (:status r)) nil
          (>= waited timeout-ms) (do (stop! name run)
                                     (throw (ex-info (str "the review sandbox: the application did not answer /health in "
                                                          (quot timeout-ms 1000) "s: " (or (:error r) (str "status " (:status r))))
                                                     {:step "waiting for the application"})))
          :else (do (sleep 2000) (recur (+ waited 2000))))))
    {:name name
     :request ask
     :run-tests (fn [ns-name]
                  (let [r (run (tests-argv name ns-name) {})]
                    {:exit (:exit r) :out (str (:out r) (:err r))}))
     :stop! (fn [] (stop! name run))}))
