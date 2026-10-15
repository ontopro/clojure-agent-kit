;; Serve, check, stop: the skeleton a stage-end script stands on. Loaded with `load-file`; it
;; knows nothing of any framework - the command that serves and the path that says the server
;; is up are parameters.
;;
;;   (with-server (fn [] ...))   starts the server, waits for it, runs the function, stops it.
;;
;; SERVE_CMD is the command that serves (default `bb serve`, the Stack Lite template's);
;; HEALTH_PATH the path that answers 200 once the server is up (default `/health`); APP_BASE
;; where it answers (default http://localhost:8000). `check.bb` sets them from its flags.
;;
;; THE SERVER MAY BE A GRANDCHILD. A task that starts a JVM starts it as its own child, and a
;; tree kill has been seen to miss it - a server from one check outlived the run and answered
;; the next. So the stop kills the process tree, waits for the port to stop answering, and when
;; it does not, kills whatever still listens on the port, then waits again. A port already
;; answering before the start is a refusal, not something to work around: it is someone else's
;; server, or a last run's that must be found.

(require '[babashka.process :as p]
         '[babashka.http-client :as http]
         '[clojure.string :as str])

(defn- setting
  "A setting: the system property `check.bb` sets when it loads this file, else the environment."
  [k default]
  (or (System/getProperty (str "browser-check." k)) (System/getenv k) default))

(def base (setting "APP_BASE" "http://localhost:8000"))
(def port (or (some-> (re-find #":(\d+)" base) second parse-long) 80))
(def health-path (setting "HEALTH_PATH" "/health"))
(def serve-cmd (setting "SERVE_CMD" "bb serve"))

(defn up? []
  (try (= 200 (:status (http/get (str base health-path) {:throw false :timeout 1000})))
       (catch Exception _ false)))

(defn port-holder
  "What holds the port, for a message: `pid <n> (<command>) in <folder>` per listener, from
  `lsof`; \"unknown (lsof absent)\" where it is not installed. A busy port said as a number is a
  fact; said with its holder it is a thing to stop."
  []
  (try (let [out (:out (p/shell {:out :string :err :discard :continue true}
                                "lsof" "-nP" (str "-iTCP:" port) "-sTCP:LISTEN" "-Fpc"))
             pairs (->> (str/split-lines (str out))
                        (partition-by #(str/starts-with? % "p"))
                        (partition 2)
                        (map (fn [[[p] cs]] [(subs p 1) (some #(when (str/starts-with? % "c") (subs % 1)) cs)])))
             cwd (fn [pid] (some->> (:out (p/shell {:out :string :err :discard :continue true} "lsof" "-a" "-p" pid "-d" "cwd" "-Fn"))
                                    str/split-lines (some #(when (str/starts-with? % "n") (subs % 1)))))]
         (if (seq pairs)
           (str/join ", " (for [[pid cmd] pairs] (str "pid " pid " (" cmd ")" (when-let [d (cwd pid)] (str " in " d)))))
           "nothing found listening (lsof saw no listener)"))
       (catch Exception _ "unknown (lsof absent)")))

(defn start-server!
  "Starts the server and waits for it. Returns [process seconds-to-up]. THE PORT IS TOLD, NOT
  FOUND: the serve command gets `PORT` - the KIT's template takes it - so the application comes up
  where this pack will look, on the port the pack picked (`check.bb`) or the one `--base` named."
  []
  (when (up?) (throw (ex-info (str "something already answers on " base " - " (port-holder) " - stop it, or pass --running") {:base base})))
  (let [t0 (System/nanoTime)
        proc (p/process {:cmd (p/tokenize serve-cmd) :out :string :err :string :shutdown :destroy-tree
                         :extra-env {"PORT" (str port)}})]
    (loop [i 0]
      (cond (up?) [proc (/ (- (System/nanoTime) t0) 1e9)]
            (not (.isAlive (:proc proc))) (throw (ex-info (str "`" serve-cmd "` exited before " health-path " answered")
                                                          {:exit (:exit @proc) :err (str/join "\n" (take-last 10 (str/split-lines (str (:err @proc)))))}))
            (> i 90) (do (p/destroy-tree proc) (throw (ex-info "the application did not come up in 180 s" {})))
            :else (do (Thread/sleep 2000) (recur (inc i)))))))

(defn- wait-down [] (loop [i 0] (when (and (up?) (< i 30)) (Thread/sleep 500) (recur (inc i)))))

(defn- listeners
  "The pids listening on the port, by lsof; none where lsof is absent."
  []
  (try (->> (:out (p/shell {:out :string :err :discard :continue true} "lsof" "-t" (str "-iTCP:" port) "-sTCP:LISTEN"))
            (re-seq #"\d+") (map parse-long) distinct)
       (catch Exception _ [])))

(defn stop-server!
  "Stops the server: the process tree, then whatever still listens on the port, each followed
  by a wait. Throws when the port is still answering after both, naming it."
  [proc]
  (p/destroy-tree proc)
  (wait-down)
  (when (up?)
    (doseq [pid (listeners)] (p/shell {:continue true :out :discard :err :discard} "kill" "-9" (str pid)))
    (wait-down))
  (when (up?) (throw (ex-info (str "the server did not stop; something still answers on " base) {:port port}))))

(defn with-server
  "`f`, between a start and a stop of the server; the stop runs whatever `f` did."
  [f]
  (let [[proc secs] (start-server!)]
    (println (format "serving on %s after %.1f s (`%s`)" base secs serve-cmd))
    (try (f)
         (finally (stop-server! proc) (println "stopped")))))
