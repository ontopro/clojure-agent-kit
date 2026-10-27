(ns harness.loop.review-repl-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.loop.review-repl :as rr]))

(def cmd ["clojure" "-Srepro" "-M:nrepl"])

(deftest the-commands-mount-the-copy-and-nothing-else
  (let [w (rr/warm-argv {:image "img" :clone "/c" :cmd cmd})]
    (is (= ["img" "bash" "-c"] (take 3 (take-last 4 w))))
    (is (str/includes? (last w) "clojure -P -Srepro -M:nrepl\n") "the nREPL's own command, -P")
    (is (str/includes? (last w) "clj-nrepl-eval --help") "and the eval tool's own dependencies, into the volume")
    (is (not (some #{"--network"} w)) "the warm phase has the network"))
  (let [r (rr/run-argv {:image "img" :clone "/c" :name "kit-review-1"})
        mounts (keep (fn [[a b]] (when (= "-v" a) b)) (partition 2 1 r))]
    (is (= ["/c:/work/app" "kit-review-m2:/root/.m2:ro"] mounts) "the copy and the cache read-only; no .git, no home")
    (is (= "none" (second (drop-while #(not= "--network" %) r))))
    (is (some #{"--cap-drop"} r)))
  (is (= ["docker" "exec" "-d" "-w" "/work/app" "kit-review-1" "bash" "-c" "clojure -Srepro -M:nrepl > /tmp/nrepl.log 2>&1"]
         (rr/nrepl-argv "kit-review-1" cmd))))

(defn- git! [dir & args] (apply p/shell {:dir (str dir) :out :string :err :string} "git" args))

(deftest the-copy-holds-what-the-gates-ran-on
  (let [wt (fs/create-temp-dir)
        clone (fs/path (fs/create-temp-dir) "review-clone")]
    (git! wt "init" "-q")
    (spit (str (fs/path wt ".gitignore")) "target/\n.nrepl-port\n")
    (fs/create-dirs (fs/path wt "src" "app"))
    (spit (str (fs/path wt "src" "app" "core.clj")) "(ns app.core)")
    (git! wt "add" "-A")
    (git! wt "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "c")
    (spit (str (fs/path wt "src" "app" "new.clj")) "(ns app.new)")
    (spit (str (fs/path wt "src" "app" "core.clj")) "(ns app.core) ;; changed")
    (fs/create-dirs (fs/path wt "target"))
    (spit (str (fs/path wt "target" "x.jar")) "x")
    (spit (str (fs/path wt ".nrepl-port")) "1234")
    (rr/copy-tree! wt clone)
    (is (= "(ns app.core) ;; changed" (slurp (str (fs/path clone "src" "app" "core.clj")))) "the uncommitted edit")
    (is (fs/exists? (fs/path clone "src" "app" "new.clj")) "the untracked file")
    (is (not (fs/exists? (fs/path clone "target"))) "what git ignores")
    (is (not (fs/exists? (fs/path clone ".nrepl-port"))) "the sandbox's nREPL writes its own")
    (fs/delete-tree wt)
    (fs/delete-tree (fs/parent clone))))

(defn- fake-run
  "A `run` answering by the command: `answers` maps a predicate on the argv to a result."
  [calls answers]
  (fn [argv _]
    (swap! calls conj argv)
    (or (some (fn [[pred r]] (when (pred argv) r)) answers) {:exit 0 :out "" :err ""})))

(defn- has [s] (fn [argv] (some #(= s %) argv)))

(deftest start-runs-the-steps-in-order-and-hands-over-a-checked-port
  (let [wt (fs/create-temp-dir)
        rd (fs/create-temp-dir)
        calls (atom [])]
    (git! wt "init" "-q")
    (spit (str (fs/path wt "deps.edn")) "{}")
    (let [sb (rr/start! {:worktree wt :run-dir rd :cmd cmd :image "img" :sleep (fn [_])
                         :run (fake-run calls [[(has "cat") {:exit 0 :out "51234\n"}]
                                               [(has "clj-nrepl-eval") {:exit 0 :out "2\n"}]])})]
      (is (= 51234 (:port sb)))
      (is (str/starts-with? (:name sb) "kit-review-") "a killed run's container is cleared by the next review")
      (is (fs/exists? (fs/path (:clone sb) "deps.edn")))
      (is (= ["ps" "ps" "run" "run" "exec" "exec" "exec"] (mapv second @calls))
          "clear the exited, then the running an hour old, warm, start, the nREPL, its port, the probe")
      (is (str/starts-with? (:name sb) "kit-review-repl-") "its own prefix: a security review's is never taken for one")
      (is (= "img" (:image sb)) "the record says which image evaluated")
      (is (= ["docker" "exec" "-w" "/work/app" (:name sb) "clj-nrepl-eval" "-p" "51234" "(+ 1 1)"] (last @calls))))
    (fs/delete-tree wt)
    (fs/delete-tree rd)))

(deftest any-failure-is-none-with-everything-removed-and-never-a-throw
  (let [wt (fs/create-temp-dir)
        rd (fs/create-temp-dir)]
    (git! wt "init" "-q")
    (spit (str (fs/path wt "deps.edn")) "{}")
    (testing "the warm phase fails"
      (let [calls (atom [])
            sb (rr/start! {:worktree wt :run-dir rd :cmd cmd :image "img" :sleep (fn [_])
                           :run (fake-run calls [[(has "--rm") {:exit 1 :err "no such image"}]])})]
        (is (= "the review REPL: warming the dependency cache failed: no such image" (:none sb)))
        (is (some #(= ["docker" "rm" "-f"] (take 3 %)) @calls) "the container removed")
        (is (not (fs/exists? (fs/path rd "review-clone"))) "the copy removed")))
    (testing "no docker at all"
      (let [sb (rr/start! {:worktree wt :run-dir rd :cmd cmd :image "img" :sleep (fn [_])
                           :run (fn [_ _] {:exit 127 :out "" :err "docker: not found"})})]
        (is (str/includes? (:none sb) "docker: not found"))))
    (testing "the nREPL never writes its port"
      (let [sb (rr/start! {:worktree wt :run-dir rd :cmd cmd :image "img" :sleep (fn [_]) :timeout-ms 3000
                           :run (fake-run (atom []) [[(has "cat") {:exit 1 :err "No such file"}]])})]
        (is (= "the review REPL: the nREPL wrote no port in 3s" (:none sb)))))
    (testing "the port answers nothing"
      (let [sb (rr/start! {:worktree wt :run-dir rd :cmd cmd :image "img" :sleep (fn [_])
                           :run (fake-run (atom []) [[(has "cat") {:exit 0 :out "51234"}]
                                                     [(has "clj-nrepl-eval") {:exit 1 :err "Connection refused"}]])})]
        (is (str/starts-with? (:none sb) "the review REPL: the nREPL on port 51234 did not answer (+ 1 1)"))))
    (fs/delete-tree wt)
    (fs/delete-tree rd)))

(deftest a-review-repl-running-over-an-hour-is-a-killed-runs
  ;; Fable 5.1's read of 014e30a: `clear-stale!` takes only the exited
  (let [now (java.time.Instant/parse "2026-10-27T20:00:00Z")
        out (str "kit-review-repl-a\t2026-10-27 18:30:00 +0000 UTC\n"
                 "kit-review-repl-b\t2026-10-27 19:30:00 +0000 UTC\n"
                 "kit-review-repl-c\t2026-10-27 12:10:00 -0700 PDT\n"
                 "garbage\n")]
    (is (= ["kit-review-repl-a"] (rr/stale-running out now)) "18:30 is over an hour; 19:30 and 12:10 PDT (19:10Z) are not"))
  (is (some #{"name=^kit-review-repl-"} rr/stale-running-argv) "the review REPL's alone"))

(deftest stop-removes-the-container-and-the-copy
  (let [clone (fs/create-temp-dir)
        calls (atom [])]
    (rr/stop! {:name "kit-review-x" :clone (str clone)} (fake-run calls []))
    (is (= [["docker" "rm" "-f" "kit-review-x"]] @calls))
    (is (not (fs/exists? clone)))))
