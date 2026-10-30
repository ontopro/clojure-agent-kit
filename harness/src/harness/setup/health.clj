(ns harness.setup.health
  "`bb health` - the KIT's health check: does this KIT, on this machine, with the
  template it pins, actually work? Run, not assumed.

  ONE PIECE OF CODE, TWO SUBJECTS. Every check here takes a SUBJECT - a project
  directory, its gate commands, its nREPL command, its root namespace - and the
  same checks run against two of them:

    :selfcheck `harness/health/selfcheck`, checked in: the harness's MECHANICS, against a
              project that is always there, in seconds
    :app      an application `bb init` just generated from the pinned template
              into a scratch workspace: THE CERTIFIED PAIR - this KIT commit
              with that template commit - by the path an adopter takes

  THE CHECKS, in order, each one recorded as data (`{:check _ :subject _ :ok? _
  :ms _ :detail _}`) so the report is derived and never typed:

    :gates    every gate of the subject green, untouched
    :red      every gate shown to FAIL, one breaker at a time - a suite that has
              only ever been green proves nothing. The breakers are DATA here,
              not the selfcheck project's: they take a root namespace and nothing else,
              so the same four run against any Clojure project, the generated
              application included. Each is written, the gates run, the failure
              checked to be at THAT gate's key (triage dispatches on the key),
              and the file removed - whatever happened
    :loop     one trivial task through the whole loop with a SCRIPTED runner in
              place of every model - real worktrees, real nREPLs, real gates,
              the real REPL probe; no model calls (the balance line asks the
              OpenRouter key's status when a key is in the shell, as any run
              does, and never fails a run). Stops `:awaiting-merge`, is
              RECORDED - `record`, as method.md §03's implement step says: `run.edn`, and
              in the generated application's workspace the copy in the plan's
              `runs/`, naming the KIT, application and plan commits, each held
              to that repository's HEAD; the loop having read its rules and
              its profile from the plan, where `bb init` put them - and torn
              down, never merged
    :serve    (:app only) `bb serve`, `GET /` answers 200, the process tree
              stopped, the port free again
    :browser  (:app only) the KIT's browser pack opens the page in a headless
              Firefox; skipped, and said, where it cannot run
    :security (:app only) the KIT's security pack tries from outside what the
              architecture document's §4 says the template gives
    :image    (:app only) the application's Dockerfile - on the KIT's tested set, which
              `bb init` wrote - built and run, `GET /` the KIT's first page, the image's
              JVM read; skipped, and said, where Docker is not running

  GUIDE, DO NOT INSTALL: a failing check names what failed and what to read;
  nothing here changes the machine. Offline after the template's first fetch.
  Never part of `bb gates` - it starts JVMs and takes minutes; it is what an
  adopter runs after `bb doctor`, and what the KIT runs before it publishes a
  known-good set."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [harness.gates.boundary :as boundary]
   [harness.gates.kit-pins :as kit-pins]
   [harness.gates.run :as gates]
   [harness.loop.driver :as driver]
   [harness.loop.orchestrate :as orch]
   [harness.models.runner :as runner]
   [harness.rules :as rules]
   [harness.setup.app :as app]
   [harness.setup.doctor :as doctor]
   [harness.setup.init :as init]
   [harness.setup.project-set :as project-set]
   [harness.setup.skills :as skills]
   [harness.setup.template :as template]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; subjects
;; ---------------------------------------------------------------------------

(defn kit-dir
  "The KIT's clone: this namespace runs from `harness/`."
  []
  (str (fs/parent (fs/normalize (fs/absolutize ".")))))

(defn record-refusal
  "Why `--record` is refused for the clone at `kit`, given `ws` - the workspace found at or
  above the clone (`workspace/find-workspace`), nil when there is none - or nil when it may
  record. RECORDS ARE THE KIT'S OWN: `health/records/` and the known-good set are the KIT's
  evidence of a machine it certified, tracked files, and written into a workspace's clone
  they are local changes that block a clean upgrade. The first project's clone had none only
  because the documented path never passed the flag."
  [kit ws]
  (when ws
    (str "--record refused: records are the KIT's own, and " kit " is a workspace's clone ("
         (fs/path (:workspace/dir ws) workspace/file-name) " is above it); a workspace's clone does not write them."
         " The run itself is fine without the flag.")))

(defn- git! [dir & args]
  (let [{:keys [exit err]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed in " dir ": " (str/trim err)) {})))))

(defn selfcheck-subject
  "`<kit>/harness/health/selfcheck`, COPIED into a scratch workspace and made a repository of its
  own: the checked-in project is never written to, and the loop's branches land
  in a repository that is gone afterwards, not in the KIT's clone. Its gates are
  its own `bb` tasks, but the boundary gate and the format gate by the KIT's path, since `..` no
  longer leads to the harness or the toolchain pack."
  [kit]
  (let [ws (str (fs/path (fs/create-temp-dir {:prefix "kit-health-selfcheck"}) "selfcheck"))
        dir (str (fs/path ws "selfcheck"))]
    (fs/copy-tree (fs/path kit "harness" "health" "selfcheck") dir)
    ;; The KIT's root .gitignore is what keeps .nrepl-port and .cpcache/ out of the
    ;; project's status; on its own, the copy would report them as files a role wrote.
    (fs/copy (fs/path kit ".gitignore") (fs/path dir ".gitignore"))
    (doseq [stale [".nrepl-port" ".cpcache"]] (fs/delete-tree (fs/path dir stale)))
    (git! dir "init" "-q" "-b" "main")
    (git! dir "add" "-A")
    (git! dir "-c" "user.name=kit-health" "-c" "user.email=health@kit" "commit" "-q" "-m" "the selfcheck project, as shipped")
    (spit (str (fs/path ws "workspace.edn")) (pr-str {:workspace/app "selfcheck" :workspace/work "work"}))
    (fs/create-dirs (fs/path ws "work"))
    {:subject :selfcheck
     :workspace ws
     :dir dir
     :root "selfcheck"
     :gates [[:fmt (str "bb --config " kit "/tools/toolchain/bb.edn fmt-check --dir . src test dev")] [:lint "bb lint"] [:test "bb test"]
             [:deps (str "bb --config " kit "/harness/bb.edn boundary")]]
     :nrepl/cmd ["bb" "nrepl"]
     ;; no plan in this workspace, so the driver resolves it against harness/, where this runs
     :profile "resources/profiles/claude.edn"}))

(defn app-subject
  "A generated application: its gates and nREPL are what the pin says `bb init`
  writes into a plan's `loop.edn`, with `{{kit}}` made this KIT."
  [kit workspace app-dir app-name pin]
  (let [defaults (walk/postwalk #(if (string? %) (str/replace % "{{kit}}" kit) %) (:loop/defaults pin))]
    {:subject :app
     :workspace workspace
     :dir app-dir
     :kit kit
     :root app-name
     :gates (:gates defaults)
     :nrepl/cmd (:nrepl/cmd defaults)
     ;; the plan's, as `bb init` wrote it: the driver resolves it against the workspace's plan
     :profile "profile.edn"}))

;; ---------------------------------------------------------------------------
;; checks
;; ---------------------------------------------------------------------------

(defn- timed [f]
  (let [start (System/nanoTime)
        v (f)]
    [v (quot (- (System/nanoTime) start) 1000000)]))

(defn- result [check {:keys [subject]} ok? ms detail]
  {:check check :subject subject :ok? (boolean ok?) :ms ms :detail detail})

(def expected-gaps
  "The line above the security pack's warns and skips: on a fresh application
  they are the pinned template's known gaps, the same on every machine, and
  none of them fails the run."
  "expected on a fresh application - the template's known gaps (02-architecture.md §4 and §8); they fail nothing:")

(defn- gate-lines
  "The failed gate's output, last lines: enough to read, never the whole log."
  [{:keys [gates/failed gates/report]}]
  (let [out (:out (first (filter #(= failed (:gate %)) report)))]
    (str/join "\n" (take-last 12 (str/split-lines (str out))))))

(defn check-gates
  "Every gate green, untouched."
  [{:keys [dir gates] :as subject}]
  (let [[r ms] (timed #(gates/run-gates! dir gates))]
    (result :gates subject (:gates/passed? r) ms
            (if (:gates/passed? r)
              (str (count gates) " gates green: " (str/join ", " (map (comp name first) gates)))
              (str "failed at :" (name (:gates/failed r)) "\n" (gate-lines r))))))

(defn breakers
  "One file per gate that fails exactly that gate and no earlier one - the
  order is fmt, lint, test, deps, so each breaker passes the gates before its
  own. All they need is the project's root namespace. ONE FORM PER LINE, and
  nothing multi-line but the fmt breaker: a project's `.cljfmt.edn` may lay out
  an `ns` form its own way, and the generated application's did.

    :fmt   a form cljfmt would re-indent
    :lint  an unused binding - clj-kondo warns, and the gate fails on warnings
    :test  one assertion that is false
    :deps  a namespace `layers.edn` does not declare"
  [root]
  {:fmt {:path (str "src/" (str/replace root "-" "_") "/red_fmt.clj")
         :content (str "(ns " root ".red-fmt)\n\n(defn f [x]\n(inc x))\n")}
   :lint {:path (str "src/" (str/replace root "-" "_") "/red_lint.clj")
          :content (str "(ns " root ".red-lint)\n\n(defn f [x] (let [unused 1] x))\n")}
   :test {:path (str "test/" (str/replace root "-" "_") "/red_test.clj")
          :content (str "(ns " root ".red-test (:require [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest red (is (= 1 2)))\n")}
   :deps {:path (str "src/" (str/replace root "-" "_") "/red_deps.clj")
          :content (str "(ns " root ".red-deps)\n\n(defn f [x] x)\n")}})

(defn check-red
  "Every gate the subject has, made to fail by its breaker, and seen to fail
  THERE. The breaker is removed whatever happens."
  [{:keys [dir gates root] :as subject}]
  (let [bs (breakers root)
        [outcomes ms]
        (timed
         (fn []
           (vec (for [[gate _] gates
                      :let [{:keys [path content]} (get bs gate)
                            f (fs/path dir path)]]
                  (if-not path
                    {:gate gate :ok? false :why "no breaker for this gate"}
                    (try
                      (fs/create-dirs (fs/parent f))
                      (spit (str f) content)
                      (let [r (gates/run-gates! dir gates)]
                        (cond
                          (:gates/passed? r) {:gate gate :ok? false :why "the gates stayed green"}
                          (not= gate (:gates/failed r)) {:gate gate :ok? false
                                                         :why (str "failed at :" (name (:gates/failed r))
                                                                   " instead")}
                          :else {:gate gate :ok? true}))
                      (finally (fs/delete-if-exists f))))))))]
    (result :red subject (every? :ok? outcomes) ms
            (if (every? :ok? outcomes)
              (str "each of " (count outcomes) " gates fails on its breaker, at its own key")
              (str/join "; " (for [{:keys [gate ok? why]} outcomes]
                               (str ":" (name gate) " " (if ok? "ok" why))))))))

;; ---------------------------------------------------------------------------
;; the loop
;; ---------------------------------------------------------------------------

(defn trivial-task
  "The one task the loop is run on: method §02's `clamp` - no design content
  at all. The namespace is new, so `layers.edn` must declare it, and no
  dispatched role may write that file: it arrives as `:architecture`, the
  Architect's output, which is the case the mechanism exists for."
  [root]
  (let [file (str/replace root "-" "_")]
    {:spec {:task/id "health"
            :task/title "clamp: x held between lo and hi"
            :blueprint/slice {:shapes [] :interfaces ['(clamp [x lo hi])]}
            :files/impl [(str "src/" file "/health.clj")]
            :files/test [(str "test/" file "/health_test.clj")]
            :files/context []
            :layer/name :health}
     :writes {:coder {(str "src/" file "/health.clj")
                      (str "(ns " root ".health)\n\n(defn clamp\n  \"x, held between lo and hi.\"\n  [x lo hi]\n  (max lo (min hi x)))\n")}
              :tester {(str "test/" file "/health_test.clj")
                       (str "(ns " root ".health-test (:require [clojure.test :refer [deftest is]] [" root ".health :as h]))\n\n"
                            "(deftest clamp-holds-x-between-lo-and-hi\n"
                            "  (is (= 5 (h/clamp 5 0 10)))\n"
                            "  (is (= 0 (h/clamp -1 0 10)))\n"
                            "  (is (= 10 (h/clamp 11 0 10))))\n")}}
     :layer (symbol (str root ".health"))}))

(defn- scripted-runner
  "Every model's seat: writes what the task says, approves what it wrote."
  [writes]
  (reify runner/AgentRunner
    (run-agent [_ role pkt]
      (let [w (get writes role)]
        (doseq [[path content] w]
          (let [f (fs/path (:repl/worktree pkt) path)]
            (fs/create-dirs (fs/parent f))
            (spit (str f) content)))
        (cond-> {:status :done :files (vec (keys w)) :stdout "scripted by bb health" :cost nil
                 :runner/meta {:model "scripted"}}
          (= :reviewer role)
          (assoc :stdout "No findings: the slice is the function and the test holds it."
                 :runner/meta {:model "scripted" :verdict {:verdict :approve :reasons ["health check"]}}))))))

(defn loop-problems
  "What is wrong with the record the health loop took, as sentences; empty when
  it says what a record promises. `record` is `run.edn` as written (nil when
  it was not); `heads` the three repositories' HEAD commits as read NOW -
  `{:kit _ :app _ :plan _}`, nil where the workspace has no such repository;
  `kept` the copy `record` makes in the plan's `runs/`, as `{:path _ :exists?
  _ :same? _}`, or nil when the workspace names no records folder; `plan` what
  the loop read from the plan, `{:rules path :profile path}` with nil for a
  file that was NOT under the plan, or nil when the workspace has no plan (the
  selfcheck subject).

  THE THREE HASHES ARE THE CLAIM. A record that names commits nobody checked
  against the repositories is the memory it replaced, written down. The health
  check reads HEAD itself and holds the record to it, and the copy to the
  original - so the one thing a project's record is for, which code and which
  plan was this against, is proved on the path an adopter takes. And the loop
  is held to have read the rules overlay and the profile from the plan, where
  `bb init` put them: a loop that read the clone's would prove the wrong thing."
  [record heads kept plan]
  (let [commit (fn [k which]
                 (let [want (get heads which) got (get record k)]
                   (cond (and (nil? want) (nil? got)) nil
                         (nil? want) (str (subs (str k) 1) " is " got " but the workspace has no " (name which) " repository")
                         (not= want got) (str (subs (str k) 1) " is " (or got "nil") ", HEAD of the " (name which) " is " want))))]
    (->> (if-not (map? record)
           ["run.edn was not written"]
           [(when-not (= :awaiting-merge (:run/status record))
              (str "the record's status is " (:run/status record) ", not :awaiting-merge"))
            (commit :run/kit-commit :kit)
            (commit :run/app-commit :app)
            (commit :run/plan-commit :plan)
            (when kept
              (cond (not (:exists? kept)) (str "no copy at " (:path kept) ", the plan's records folder")
                    (not (:same? kept)) (str "the copy at " (:path kept) " differs from run.edn")))
            (when plan
              (when-not (:rules plan) "the loop's rules overlay is not the plan's"))
            (when plan
              (when-not (:profile plan) "the loop's profile is not the plan's"))])
         (remove nil?)
         vec)))

(defn- record-loop!
  "`record` on the run at `rd`, while its worktrees exist; then what
  `loop-problems` needs, read from the run's config and the repositories, and
  the paths for the detail line, relative to the workspace."
  [rd]
  (let [ctx (driver/context rd)
        cfg (:config ctx)
        ws (workspace/find-workspace rd)
        _ (binding [*out* (java.io.StringWriter.)] (driver/record! ctx))
        rec-file (fs/path rd "run.edn")
        record (when (fs/exists? rec-file) (edn/read-string (slurp (str rec-file))))
        heads {:kit (driver/head-commit (:kit/root cfg))
               :app (driver/head-commit (:repo/root cfg))
               :plan (driver/head-commit (:plan/root cfg))}
        kept (when-let [dir (:records/dir cfg)]
               (let [p (fs/path dir (str (:run/id cfg) ".edn"))]
                 {:path (str p) :exists? (fs/exists? p)
                  :same? (and (fs/exists? p) (fs/exists? rec-file) (= (slurp (str p)) (slurp (str rec-file))))}))
        plan-dir (:workspace/build ws)
        in-ws #(str (fs/relativize (:workspace/dir ws) %))
        under-plan (fn [p] (when (and plan-dir p (fs/starts-with? (fs/normalize p) (fs/normalize plan-dir))) (in-ws p)))
        plan (when plan-dir {:rules (under-plan (:workspace/rules-overlay ws))
                             :profile (under-plan (:profile cfg))})]
    {:problems (loop-problems record heads kept plan)
     :plan plan
     :kept (some-> kept :path in-ws)}))

(defn check-loop
  "One trivial task through the whole loop: provisioning (three worktrees, two
  real nREPLs by the subject's command, the real REPL probe), the two
  dispatches, assembly, gate 0, the subject's real gates, the review - to the
  `:reviewed` stop, `:awaiting-merge`. Then RECORDED, as a project's run is
  before its teardown, and the record held to the repositories (`loop-problems`).
  Then torn down: the loop never merges, and neither does this."
  [{:keys [workspace dir root gates nrepl/cmd profile] :as subject}]
  (let [{:keys [spec writes layer]} (trivial-task root)
        rd (str (fs/path workspace "work" "runs" "health"))
        layers (edn/read-string (slurp (str (fs/path dir boundary/file-name))))]
    (fs/create-dirs (fs/path rd "arch"))
    (spit (str (fs/path rd "arch" boundary/file-name)) (pr-str (assoc layers layer #{})))
    (spit (str (fs/path rd "spec.edn")) (pr-str spec))
    (spit (str (fs/path rd "loop.edn"))
          (pr-str {:run/id "health" :profile profile
                   ;; no spec review: the trivial task's contract is the KIT's; no plan check: the
                   ;; generated plan is the template as shipped, unfilled on purpose - the health
                   ;; check proves the machine, not a plan
                   :gates gates :nrepl/cmd cmd :spec-review/run? false :plan-check/run? false :review/repl? false
                   :architecture {:from "arch" :files [boundary/file-name]}}))
    (let [[res ms] (timed (fn []
                            (try
                              (let [out (java.io.StringWriter.)
                                    r (binding [*out* out]
                                        (with-redefs-fn {#'runner/api-runner (fn [& _] (scripted-runner writes))}
                                          #(orch/run-task! rd {:triage-fn (constantly {:route :human :reason "bb health does not retry"})})))
                                    r (assoc r :console (str out))]
                                (if (= :awaiting-merge (:status r))
                                  (merge r (record-loop! rd))
                                  r))
                              (catch Exception e {:threw (ex-message e) :data (ex-data e)})
                              (finally
                                (try (binding [*out* (java.io.StringWriter.)] (driver/teardown! (driver/context rd)))
                                     (catch Exception _ nil))))))
          stopped (= :awaiting-merge (:status res))
          problems (:problems res)]
      (result :loop subject (and stopped (empty? problems)) ms
              (cond
                (:threw res) (str "threw: " (:threw res) " " (pr-str (dissoc (:data res) :run-dir)))
                (and stopped (seq problems))
                (str "stopped :" (name (:stop/kind res)) " and recorded, but: " (str/join "; " problems) "; torn down")
                stopped
                (str "provision, dispatch x2, assemble, gate 0, gates, review: stopped :" (name (:stop/kind res))
                     " (" (:attempts res) " attempt); "
                     (if-let [{:keys [rules profile]} (:plan res)]
                       (str "rules " rules " and profile " profile ", the plan's; recorded to " (:kept res)
                            ", naming the KIT, application and plan commits, each at HEAD")
                       "recorded (no plan in this workspace: the KIT's and the project's commits, no copy)")
                     "; torn down")
                :else (str "stopped :" (some-> (:stop/kind res) name) " - " (:reason res) "\n"
                           (str/join "\n" (take-last 15 (str/split-lines (str (:console res)))))))))))

;; ---------------------------------------------------------------------------
;; serve
;; ---------------------------------------------------------------------------

(def serve-timeout-ms 120000)

(defn free-port
  "A port nothing holds, picked by binding 0 and reading what the kernel gave. THE CHECK'S OWN
  PORT, NEVER THE PERSON'S: the serve step bound 8000, the template's default, and a `bb serve`
  of this project's or another's left running - the first project met one from an archived
  site - failed the check for a reason that was not the KIT's. The template (kit-v1.3) takes
  `PORT`, so the application is told the port rather than found on one."
  []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn port-holder
  "What holds `port`, for a message: `pid <n> (<command>) in <folder>` per listener, from
  `lsof`; \"unknown (lsof absent)\" where it is not installed. A busy port said as a number is a
  fact; said with its holder it is a thing to stop."
  [port]
  (try (let [out (:out (p/shell {:out :string :err :discard :continue true}
                                "lsof" "-nP" (str "-iTCP:" port) "-sTCP:LISTEN" "-Fpc"))
             ;; -F: one field per line, `p<pid>` then `c<command>`
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

(defn- answers?
  "Does anything accept a connection on `port` at `addr`?"
  [^java.net.InetAddress addr port]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. addr (int port)) 500)
         true)
       (catch java.io.IOException _ false)))

(defn port-free?
  "Can the application take `port`, and will `localhost` reach it there? The
  bind alone is not the answer: the application binds the wildcard address,
  and macOS lets that bind succeed on a port another program holds on
  127.0.0.1 - where `localhost` then reaches the other program. So nothing may
  answer on any address `localhost` resolves to either."
  [port]
  (and (try (with-open [s (java.net.ServerSocket. port)] (.close s) true)
            (catch java.io.IOException _ false))
       (not-any? #(answers? % port) (java.net.InetAddress/getAllByName "localhost"))))

(defn- get-status [url]
  (try (:status (http/get url {:throw false :timeout 2000}))
       (catch Exception _ nil)))

(def kit-page-marks
  "What `GET /` of a generated application carries: the KIT's first page, which `bb init` writes
  over the template's (`app/home-page`) - its name and the next step. A pin whose template moved
  its home page fails here, before any adopter generates from it."
  ["Clojure Agent Kit" "/kit-start"])

(defn check-serve
  "`bb serve` in the application, `GET /` is 200 and is the KIT's first page, the process tree
  stopped, the port free again. A busy port is reported, not tried around: the template's
  port is configuration, not a flag."
  [{:keys [dir] :as subject}]
  (let [serve-port (free-port)]
    (if-not (port-free? serve-port)
    ;; picked free a moment ago and taken since: said with its holder, never tried around
      (result :serve subject false 0
              (str "port " serve-port ", picked free, is in use by " (port-holder serve-port) "; run again"))
      (let [url (str "http://localhost:" serve-port "/")
            [[status body missing-asset] ms]
            (timed
             (fn []
               (let [pr (p/process {:dir dir :out :string :err :string} "bb" "serve" "--port" (str serve-port))
                     deadline (+ (System/currentTimeMillis) serve-timeout-ms)]
                 (try
                   (loop []
                     (let [s (get-status url)]
                       (cond
                       ;; AND A MISSING ASSET ASKED FOR: the template's cached handler once answered
                       ;; 200 with an empty body, cached for a year, where a 404 belonged
                         (= 200 s) [200 (try (:body (http/get url {:throw false :timeout 2000}))
                                             (catch Exception _ nil))
                                    (get-status (str url "assets/no-such-file-" (System/currentTimeMillis) ".css"))]
                         (> (System/currentTimeMillis) deadline) [:timeout]
                         (not (.isAlive (:proc pr))) [:exited (:exit @pr)]
                         :else (do (Thread/sleep 1000) (recur)))))
                   (finally
                     (p/destroy-tree pr)
                     (deref pr 10000 nil))))))
            freed? (loop [n 0]
                     (cond (port-free? serve-port) true
                           (> n 20) false
                           :else (do (Thread/sleep 500) (recur (inc n)))))
            missing (remove #(str/includes? (str body) %) kit-page-marks)]
        (result :serve subject (and (= 200 status) (empty? missing) (= 404 missing-asset) freed?) ms
                (cond
                  (not= 200 status) (str "no 200 from " url " within " (quot serve-timeout-ms 1000) "s ("
                                         (pr-str status) ")")
                  (seq missing) (str "GET " url " -> 200, but not the KIT's first page: no "
                                     (str/join ", " (map pr-str missing)) " - bb init writes it over the template's home-page")
                  (not= 404 missing-asset) (str "GET " url " -> 200, but a missing asset under /assets/ answered "
                                                (pr-str missing-asset) ", not 404 - the template's asset handler (kit-v1.3 answers 404 in every profile)")
                  (not freed?) (str "served 200, but port " serve-port " was still held after the stop")
                  :else (str "GET " url " -> 200, the KIT's first page, a missing asset 404, then stopped; port free")))))))

;; ---------------------------------------------------------------------------
;; browser
;; ---------------------------------------------------------------------------

(def browser-timeout-ms 240000)

(defn browser-available?
  "Whether the template's browser check can run here: geckodriver on the PATH
  and a Firefox where the template's driver looks. A probe that PERFORMS comes
  next; this only decides whether to try. `which` and `exists?` are
  parameters so a test can say either way."
  ([] (browser-available? #(fs/which %) #(fs/exists? %)))
  ([which exists?]
   (boolean (and (which "geckodriver")
                 (or (which "firefox")
                     (some exists? ["/Applications/Firefox.app/Contents/MacOS/firefox"
                                    "/usr/bin/firefox" "/usr/local/bin/firefox" "/snap/bin/firefox"]))))))

;; FIREFOX UPDATES ITSELF FROM UNDER A CHECK. geckodriver already writes
;; `app.update.disabledForTesting` into every profile it starts, so the packs' Firefox never
;; checks for an update. But an update is staged per INSTALL, shared by every profile, and the
;; next Firefox to start applies it - the person's everyday browser staged one, a browser check's
;; launch applied it, Firefox.app was replaced mid-run and had to be reinstalled (the first KIT
;; project, 2026-10-22). Only the machine can stop it, so the check says when nothing does.

(def firefox-update-fix
  "How a person turns Firefox's updates off for the machine - the warning's second half."
  (str "Turn it off for the machine: on macOS `sudo defaults write /Library/Preferences/org.mozilla.firefox "
       "EnterprisePoliciesEnabled -bool TRUE` and `sudo defaults write /Library/Preferences/org.mozilla.firefox "
       "DisableAppUpdate -bool TRUE`; anywhere, a `policies.json` of `{\"policies\": {\"DisableAppUpdate\": true}}` "
       "in Firefox's `distribution/` folder (on Linux, `/etc/firefox/policies/`)."))

(defn policy-disables-updates?
  "Whether a Firefox `policies.json`'s text turns app updates off."
  [text]
  (boolean (re-find #"\"DisableAppUpdate\"\s*:\s*true" (str text))))

(def firefox-defaults-domains
  "Where macOS keeps Firefox's policies: the machine's (`sudo defaults write`) and the user's.
  `defaults` does not merge them, so each is read."
  ["/Library/Preferences/org.mozilla.firefox" "org.mozilla.firefox"])

(defn defaults-disable-updates?
  "Whether a macOS `defaults` domain turns Firefox's updates off: `read` is `(fn [domain key]
  -> the value's text, or nil)`. Firefox reads the domain's policies only when
  `EnterprisePoliciesEnabled` is on, so `DisableAppUpdate` alone is a policy it ignores."
  [read]
  (boolean (some (fn [domain] (every? #(= "1" (some-> (read domain %) str/trim))
                                      ["EnterprisePoliciesEnabled" "DisableAppUpdate"]))
                 firefox-defaults-domains)))

(defn staged-update?
  "Whether an `update.status` file's text says an update waits to be applied at the next start."
  [text]
  (boolean (re-matches #"(pending|applied)(-service|-elevate)?" (str/trim (str text)))))

(defn firefox-update-warning
  "The browser check's warning, or nil: `{:policy? :staged}` - whether the machine turns
  Firefox's updates off, and the `update.status` file of an update staged, if one is. A staged
  update is said first: it is applied at the next start whatever the policy says now.
  Information, never a failure."
  [{:keys [policy? staged]}]
  (cond staged (str "warn: a Firefox update is staged (" staged ") - the next Firefox to start applies it, a "
                    "browser check's included, and it can replace Firefox mid-run. " firefox-update-fix)
        (not policy?) (str "warn: no DisableAppUpdate policy on this machine - an update your everyday Firefox "
                           "stages is applied by the next Firefox to start, a browser check's included. " firefox-update-fix)))

(defn firefox-update-state
  "What this machine says about Firefox's updates, for `firefox-update-warning`: the policy -
  macOS's `defaults` domain, or a `policies.json` where Firefox is installed - and an update
  staged under the caches Firefox keeps them in."
  []
  (let [mac? (str/starts-with? (System/getProperty "os.name") "Mac")
        defaults? (and mac?
                       (defaults-disable-updates?
                        (fn [domain k]
                          (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                                            "defaults" "read" domain k)]
                            (when (zero? exit) out)))))
        policies ["/Applications/Firefox.app/Contents/Resources/distribution/policies.json"
                  "/etc/firefox/policies/policies.json" "/usr/lib/firefox/distribution/policies.json"]
        statuses (concat (when mac?
                           (let [dir (fs/path (System/getProperty "user.home") "Library" "Caches" "Mozilla" "updates")]
                             (when (fs/directory? dir) (fs/glob dir "**/updates/0/update.status"))))
                         (filter fs/exists? [(fs/path "/usr/lib/firefox/updates/0/update.status")]))]
    {:policy? (boolean (or defaults? (some #(and (fs/exists? %) (policy-disables-updates? (slurp %))) policies)))
     :staged (some #(when (staged-update? (slurp (str %))) (str %)) statuses)}))

(defn check-browser
  "The KIT's browser pack, `bb --config <kit>/tools/browser/bb.edn check --only
  screenshots --width 1440 --width 390 /`, run in the application: it serves, opens the
  page in a headless Firefox through geckodriver, screenshots it as tall as it is at each
  width - 390 in a frame that wide, since headless Firefox keeps no window under 500 -
  measures it, and stops the server. Ok when the task exits 0 and both screenshots exist:
  the pack fails a width it did not measure, so a 390 that came out 500 is a red check. The pack is the KIT's, not the template's, so this
  certifies the KIT with the browser on this machine, whatever the application.

  A PROBE THAT PERFORMS. `geckodriver --version` passes on a machine where no
  browser can start: on macOS the permission to use Firefox belongs to the
  terminal application, and a shell under a daemon gets a silent refusal that
  only a real start shows - Firefox up, no content process, geckodriver giving
  up after 60 s. The first real project lost an hour to it after a green
  doctor. Starting one here, once, is what the version check cannot be.

  SKIPPED, AND SAID SO, where geckodriver or Firefox is absent: the KIT guides
  and does not install, and a health run must stay possible on a machine with
  no browser. The record carries the skip; the README's row prints it; `ok?`
  stays true so the run is healthy, and nobody reads a browser check that did
  not run as one that passed. `run` is `(fn [dir env argv] -> {:exit :out})`,
  for a test; `update-state` is `firefox-update-state`'s, for one too - its warning
  (`firefox-update-warning`) is a line of the detail, never a failure."
  ([subject] (check-browser subject {}))
  ([{:keys [dir kit] :as subject} {:keys [available? run update-state]
                                   :or {available? browser-available?
                                        update-state firefox-update-state
                                        run (fn [dir env argv]
                                              (apply p/shell {:dir dir :out :string :err :string :continue true
                                                              :extra-env env :timeout browser-timeout-ms}
                                                     argv))}}]
   (if-not (available?)
     (assoc (result :browser subject true 0
                    "skipped: no geckodriver on the PATH, or no Firefox - the KIT's browser pack needs both (`bb doctor` has the row)")
            :skipped? true)
     (let [out-dir (str (fs/create-temp-dir {:prefix "kit-health-browser"}))
           shots (mapv #(fs/path out-dir % "home.png") ["1440" "390"])
           [{:keys [exit out err]} ms]
           (timed #(try (run dir {} ["bb" "--config" (str (fs/path kit "tools" "browser" "bb.edn"))
                                     "check" "--only" "screenshots" "--width" "1440" "--width" "390" "--out" out-dir "/"])
                        (catch Exception e {:exit -1 :out "" :err (ex-message e)})))
           ok? (and (= 0 exit) (every? #(and (fs/exists? %) (pos? (fs/size %))) shots))
           tail (str/join "\n" (take-last 6 (str/split-lines (str out err))))
           warning (firefox-update-warning (update-state))]
       (fs/delete-tree out-dir)
       (result :browser subject ok? ms
               (if ok?
                 (str "served, Firefox opened / at 1440 and 390, a screenshot as tall as the page and its measure at each; stopped"
                      (let [lines (filter #(str/includes? % "scrollWidth") (str/split-lines (str out)))]
                        (when (seq lines) (str "\n" (str/join "\n" (map str/trim lines)))))
                      (when warning (str "\n" warning)))
                 (str "the browser pack's check exited " exit (when-not (every? fs/exists? shots) ", a screenshot not written")
                      (when (str/includes? (str out err) "did not start")
                        (str "; Firefox did not start - the application this runs in is not allowed to use it. "
                             "On macOS: System Settings > Privacy & Security > Files & Folders (or Full Disk Access) > "
                             "the application the session runs in (Terminal, iTerm, the IDE, the Claude app) > allow it, "
                             "or run the check once from that application and answer the prompt; then run `bb health` again"))
                      (when warning (str "\n" warning))
                      "\n" tail)))))))

;; ---------------------------------------------------------------------------
;; security
;; ---------------------------------------------------------------------------

(def security-timeout-ms 240000)

(def security-commands
  "The security pack's commands as health runs them: the command, the record it
  writes, its flags, and how its line in the detail ends."
  [["check" "security.edn" [] "from outside"]
   ["deps" "security-deps.edn" [] "the dependencies"]
   ["secrets" "security-secrets.edn" ["--all"] "secrets in the history"]])

(defn check-security
  "The KIT's security pack run in the application, its three commands: `check`
  serves, sends its requests from outside - headers, cookies, a POST without its
  token, error pages, static folders - and stops the server; `deps` asks OSV about
  every library the application ships; `secrets --all` reads its whole history
  for a committed secret. Ok when each exits 0 and wrote its record: no failing
  row. So every claim of the build docs' `02-architecture.md` §4 that a
  request can try is tried on the day of the run, against the application the
  pin generates, and the pin's dependencies are asked about the same day: an
  advisory published since the pin was made fails here first.

  The warns and skips travel into the detail, one line each: a warn is the
  template's known gap (no CSP, Jetty's version), a skip what a generated
  application gives nothing to try (no form route, no login route, no https) or
  what could not be asked (OSV unreachable), and neither fails the run. `run` is
  `(fn [dir env argv] -> {:exit :out})`, for a test."
  ([subject] (check-security subject {}))
  ([{:keys [dir kit] :as subject} {:keys [run]
                                   :or {run (fn [dir env argv]
                                              (apply p/shell {:dir dir :out :string :err :string :continue true
                                                              :extra-env env :timeout security-timeout-ms}
                                                     argv))}}]
   (let [out-dir (str (fs/create-temp-dir {:prefix "kit-health-security"}))
         pack (str (fs/path kit "tools" "security" "bb.edn"))
         one (fn [[cmd file flags label]]
               (let [{:keys [exit out err]} (try (run dir {} (into ["bb" "--config" pack cmd] (concat flags ["--out" out-dir])))
                                                 (catch Exception e {:exit -1 :out "" :err (ex-message e)}))
                     record-file (fs/path out-dir file)]
                 {:cmd cmd :label label :exit exit
                  :tail (str/join "\n" (take-last 6 (str/split-lines (str out err))))
                  :rec (when (fs/exists? record-file)
                         (try (edn/read-string (slurp (str record-file))) (catch Exception _ nil)))}))
         [runs ms] (timed #(mapv one security-commands))
         ok? (every? (fn [{:keys [exit rec]}] (and (= 0 exit) (:ok? rec))) runs)]
     (fs/delete-tree out-dir)
     (result :security subject ok? ms
             (str/join "\n"
                       (concat
                        (for [{:keys [cmd label exit rec tail]} runs]
                          (if rec
                            (let [{:keys [ok warn fail skipped]} (:counts rec)]
                              (str ok " ok, " warn " warn, " fail " fail, " skipped " skipped, " label))
                            (str "the security pack's " cmd " exited " exit ", no record written\n" tail)))
                        (let [rows (for [{:keys [rec]} runs
                                         {:keys [status check subject says]} (:rows rec)
                                         :when (#{:fail :warn :skipped} status)]
                                     (str (name status) " " (name check)
                                          (when (#{:deps :secrets} check) (str " " subject))
                                          ": " says))]
                          (when (seq rows) (cons expected-gaps rows)))))))))

;; ---------------------------------------------------------------------------
;; XTDB: the KIT's tested version, on the tested JDK (plan 7.0, item 7.3)
;; ---------------------------------------------------------------------------

(def xtdb-timeout-ms 600000)

(defn xtdb-dir [kit] (str (fs/path kit "harness" "health" "xtdb")))

(defn xtdb-version
  "The KIT's tested XTDB version: the probe's `deps.edn`, the one place it is written."
  [kit]
  (get-in (edn/read-string (slurp (str (fs/path (xtdb-dir kit) "deps.edn")))) [:deps 'com.xtdb/xtdb-core :mvn/version]))

(defn xtdb-image [version] (str "ghcr.io/xtdb/xtdb:" version))

(defn xtdb-subject [kit] {:subject :xtdb :kit kit :dir (xtdb-dir kit)})

(defn- probe-line [out] (last (filter #(re-find #"^(ok |FAILED)" %) (str/split-lines (str out)))))

(defn- default-shell [dir argv]
  (try (apply p/shell {:dir dir :out :string :err :string :continue true :timeout xtdb-timeout-ms} argv)
       (catch Exception e {:exit -1 :out "" :err (ex-message e)})))

(defn check-xtdb-embedded
  "An in-process XTDB node of the KIT's tested version on this JVM - the one `bb doctor` checked -
  with `-Dio.netty.noUnsafe=false`, which JDK 25 needs: two puts of one document, read now and over
  valid time (`health/xtdb/`'s probe). Its own JVM project, so the gates stay JVM-free; the first
  run downloads XTDB and Arrow. `run` is `(fn [dir argv] -> {:exit :out :err})`, for a test."
  ([subject] (check-xtdb-embedded subject {}))
  ([{:keys [dir kit] :as subject} {:keys [run] :or {run default-shell}}]
   (let [[{:keys [exit out err]} ms] (timed #(run dir ["clojure" "-M:probe"]))
         line (probe-line out)]
     (result :embedded subject (and (= 0 exit) (some-> line (str/starts-with? "ok "))) ms
             (str "XTDB " (xtdb-version kit) ": "
                  (or line (str "the probe exited " exit "\n" (str/join "\n" (take-last 6 (str/split-lines (str out err)))))))))))

(defn- docker-running? [] (= 0 (:exit (default-shell "." ["docker" "info" "--format" "{{.ServerVersion}}"]))))

(defn- container-java
  "The JVM version inside a running container, from its `java -version`; nil when it does not say."
  [run dir name]
  (let [{:keys [out err]} (run dir ["docker" "exec" name "java" "-version"])]
    (second (re-find #"version \"([^\"]+)\"" (str out err)))))

(defn check-xtdb-pgwire
  "The XTDB server image of the tested version (`xtdb-image`), started with no port but its Postgres
  wire on a free one of localhost, and reached through it by the same probe: two inserts, read over
  valid time; the container removed after, whatever happened. The image brings its own JVM, so the
  line names both - the server's, read from the running container (`:server-java`, which the record
  carries), and the probe's on this machine. Where Docker is not running, skipped and said. `run`
  and `docker?` for a test; `sleep` between the tries while the server starts."
  ([subject] (check-xtdb-pgwire subject {}))
  ([{:keys [dir kit] :as subject} {:keys [run docker? sleep tries]
                                   :or {run default-shell
                                        docker? docker-running?
                                        sleep #(Thread/sleep 2000) tries 30}}]
   (if-not (docker?)
     (assoc (result :pgwire subject true 0 "skipped: Docker is not running - the image is tried where it is")
            :skipped? true)
     (let [image (xtdb-image (xtdb-version kit))
           name (str "kit-health-xtdb-" (subs (str (random-uuid)) 0 8))
           server-java (volatile! nil)
           [line ms]
           (timed (fn []
                    (try
                      (let [{:keys [exit err]} (run dir ["docker" "run" "-d" "--rm" "--name" name "-p" "127.0.0.1::5432" image])]
                        (if-not (= 0 exit)
                          (str "FAILED: docker run " image " exited " exit ": " (str/trim (str err)))
                          (let [port (some->> (:out (run dir ["docker" "port" name "5432"])) str/split-lines first (re-find #":(\d+)$") second)]
                            (loop [i 1]
                              (let [l (probe-line (:out (run dir ["clojure" "-M:probe" "--pgwire" (str port)])))]
                                (cond (some-> l (str/starts-with? "ok "))
                                      (do (vreset! server-java (container-java run dir name)) (str l " (try " i ")"))
                                      (>= i tries) (or l "FAILED: the probe never answered")
                                      :else (do (sleep) (recur (inc i)))))))))
                      (finally (run dir ["docker" "rm" "-f" name])))))]
       (cond-> (result :pgwire subject (some-> line (str/starts-with? "ok ")) ms
                       (str image (some->> @server-java (str " on java ")) ": " line))
         @server-java (assoc :server-java @server-java))))))

(def image-platform
  "The platform the application's image is built and run for: the template's Dockerfile is
  amd64's alone at `kit-v1.3` - it downloads tailwindcss's x64 binary, and babashka's aarch64
  `--static` build is linked against glibc, which the Alpine image lacks (`bb: not found`) - so
  an arm64 machine builds it under emulation, as a deployment to amd64 would run it."
  "linux/amd64")

(def image-tries 60)

(defn- http-page [url]
  (try (let [r (http/get url {:throw false :timeout 2000})] [(:status r) (:body r)])
       (catch Exception _ nil)))

(defn check-image
  "The application's image built from its Dockerfile - the KIT's tested set over the template's,
  which `bb init` wrote - for `image-platform`, run with a session secret made for the run and a
  tmpfs at `/app/db` (the runtime stage makes no folder for SQLite's file: a deployment mounts a
  volume there), `GET /` on a free localhost port answering the KIT's first page; the JVM inside
  read (`:image-java`, which the record carries). The container and the image removed after,
  whatever happened. Skipped, and said, where Docker is not running. `run`, `docker?`, `page`
  (`(fn [url]) -> [status body]`) and `sleep` for a test."
  ([subject] (check-image subject {}))
  ([{:keys [dir] :as subject} {:keys [run docker? page sleep tries]
                               :or {run default-shell docker? docker-running? page http-page
                                    sleep #(Thread/sleep 2000) tries image-tries}}]
   (if-not (docker?)
     (assoc (result :image subject true 0 "skipped: Docker is not running - the image is built where it is")
            :skipped? true)
     (let [id (subs (str (random-uuid)) 0 8)
           tag (str "kit-health-app:" id)
           container (str "kit-health-app-" id)
           tail (fn [{:keys [out err]}] (str/join "\n" (take-last 6 (str/split-lines (str out err)))))
           [{:keys [ok? line java]} ms]
           (timed
            (fn []
              (try
                (let [b (run dir ["docker" "build" "--platform" image-platform "-t" tag "."])]
                  (if-not (= 0 (:exit b))
                    {:line (str "FAILED: docker build exited " (:exit b) "\n" (tail b))}
                    (let [s (run dir ["docker" "run" "-d" "--platform" image-platform "--name" container
                                      "-e" (str "SESSION_SECRET_KEY=" (random-uuid)) "--tmpfs" "/app/db"
                                      "-p" "127.0.0.1::80" tag])]
                      (if-not (= 0 (:exit s))
                        {:line (str "FAILED: docker run exited " (:exit s) ": " (str/trim (str (:err s))))}
                        (let [port (some->> (:out (run dir ["docker" "port" container "80"])) str/split-lines first
                                            (re-find #":(\d+)$") second)
                              url (str "http://127.0.0.1:" port "/")]
                          (loop [i 1]
                            (let [[status body] (page url)
                                  missing (remove #(str/includes? (str body) %) kit-page-marks)]
                              (cond
                                (and (= 200 status) (empty? missing))
                                {:ok? true :java (container-java run dir container)
                                 :line (str "GET / -> 200, the KIT's first page (try " i ")")}
                                (= 200 status)
                                {:line (str "GET / -> 200, but not the KIT's first page: no " (str/join ", " (map pr-str missing)))}
                                (>= i tries)
                                {:line (str "FAILED: no 200 from the container within " tries " tries\n"
                                            (tail (run dir ["docker" "logs" container])))}
                                :else (do (sleep) (recur (inc i)))))))))))
                (finally
                  (run dir ["docker" "rm" "-f" container])
                  (run dir ["docker" "rmi" "-f" tag])))))]
       (cond-> (result :image subject ok? ms (str image-platform (some->> java (str ", java ")) ": " line))
         java (assoc :image-java java))))))

;; ---------------------------------------------------------------------------
;; the generated application
;; ---------------------------------------------------------------------------

(def app-name "hc")

(defn generate-app!
  "`bb init` into a scratch workspace, by the same code an adopter's command
  runs - not by shelling the command, so the failure surfaces as data here.
  Returns `{:workspace dir :app dir :pin pin :ms ms}`."
  [kit]
  (let [pin (template/pin (template/load-pins))
        local-root (some-> (System/getenv "KIT_TEMPLATE_LOCAL") not-empty fs/absolutize fs/normalize str)
        ws (str (fs/path (fs/create-temp-dir {:prefix "kit-health"}) app-name))
        req {:name app-name :kit-dir kit :dir ws
             :build-docs (init/build-docs-files kit)
             :skills (skills/sources kit)
             :rule-mirrors [(str app-name "-app/AGENTS.md")]
             :loop/defaults (:loop/defaults pin)}
        lay (init/layout req)
        [_ ms] (timed #(init/create! lay {:app-fn (app/app-fn pin {:app-name app-name :local-root local-root})}))]
    {:workspace ws :app (str (fs/path ws (str app-name "-app"))) :pin pin :local-root local-root :ms ms}))

;; ---------------------------------------------------------------------------
;; the record, and the README block rendered from it
;; ---------------------------------------------------------------------------

(def records-dir "health/records")
(def known-good-path (str "resources/" doctor/known-good-resource))
(def begin-marker "<!-- health:begin -->")
(def end-marker "<!-- health:end -->")

(defn- linux-release
  "The distribution's name from `/etc/os-release` (`Ubuntu 24.04.5 LTS`), where the file is;
  nil elsewhere. On Linux the JVM's `os.version` is the KERNEL's version - in a container
  the virtual machine's, which says nothing of the OS the tools were installed on."
  []
  (let [f (fs/file "/etc/os-release")]
    (when (fs/exists? f)
      (some->> (str/split-lines (slurp f))
               (some #(second (re-matches #"PRETTY_NAME=\"?([^\"]*)\"?" %)))))))

(defn platform
  "This machine, as a record's key and label: `{:key \"macos-arm64\" :label \"macOS 26.5 arm64\"}`,
  `{:key \"linux-arm64\" :label \"Linux Ubuntu 24.04.5 LTS arm64\"}`. Nothing of the host but
  its OS and architecture."
  []
  (let [os (System/getProperty "os.name")
        arch (System/getProperty "os.arch")
        os-key (cond (str/starts-with? os "Mac") "macos"
                     (str/starts-with? os "Linux") "linux"
                     (str/starts-with? os "Windows") "windows"
                     :else (str/lower-case (str/replace os #"\\s+" "-")))
        version (or (when (= "linux" os-key) (linux-release))
                    (System/getProperty "os.version"))
        arch-key (case arch ("aarch64" "arm64") "arm64" ("amd64" "x86_64") "x86_64" arch)]
    {:key (str os-key "-" arch-key)
     :label (str (case os-key "macos" "macOS" "linux" "Linux" "windows" "Windows" os) " " version " " arch-key)}))

(defn- kit-commit [kit]
  (let [out (fn [& args] (str/trim (:out (apply p/shell {:dir kit :out :string :err :string :continue true} "git" args))))]
    {:sha (out "rev-parse" "HEAD") :short (out "rev-parse" "--short" "HEAD")
     :dirty? (not (str/blank? (out "status" "--porcelain")))}))

(defn record
  "What a health run certifies, as data that can be committed: WHEN, on WHAT
  (OS and architecture, nothing else of the host), WHICH KIT commit with WHICH
  template commit, the tool versions the doctor saw, and every check with its
  outcome and time - never its detail, which carries scratch paths."
  [kit checks pin doctor-results & [local-root]]
  (let [{:keys [key label]} (platform)]
    (cond-> {:health/as-of (str (java.time.LocalDate/now))
             :platform {:key key :label label}
             :kit (kit-commit kit)
             :template (select-keys pin [:template :git/tag :git/sha])
             :tools (into (sorted-map)
                          (keep (fn [{:keys [tool req version]}] (when (= :required req) [tool version])))
                          doctor-results)
             ;; a skipped check says so in the record, so a row never reads it as passed
             :checks (mapv #(select-keys % [:subject :check :ok? :ms :skipped?]) checks)
             ;; the store the KIT offers, as tried: its version and its server image (plan 7.0, item 7.3)
             ;; the image's own JVM, as the pgwire check read it (none when the check did not run)
             :xtdb (cond-> {:version (xtdb-version kit) :image (xtdb-image (xtdb-version kit))}
                     (some :server-java checks) (assoc :server-java (some :server-java checks)))
             :ok? (every? :ok? checks)}
      ;; THE PROJECT'S TOOLCHAIN, AS PROVED: the set `bb init` wrote, when the image built from it
      ;; served (plan 7.0, item 7.4.4) - and the JVM inside it
      (some :image-java checks) (assoc :project (assoc (project-set/load-set) :image-java (some :image-java checks)))
      ;; A RUN FROM A LOCAL CLONE SAYS SO IN THE RECORD, not only on the terminal: the pin above is
      ;; what the clone stood for, not what was generated, and `bb tag-check` refuses such a record -
      ;; a tag's records come from the pushed pin, never with KIT_TEMPLATE_LOCAL set
      local-root (assoc :template/local-root local-root))))

(defn run-path
  "Where this run of `rec` is kept: `health/records/<platform>/<as-of>-<kit short sha>.edn`, and
  `-2`, `-3` for a second run of the same commit on the same day - every run kept (the person,
  2026-10-27: the health records kept per run; plan 7.0, item 11)."
  [{:keys [platform kit] :health/keys [as-of]}]
  (let [dir (fs/path records-dir (:key platform))
        base (str as-of "-" (:short kit))]
    (str (first (remove fs/exists? (cons (fs/path dir (str base ".edn"))
                                         (map #(fs/path dir (str base "-" % ".edn")) (range 2 100))))))))

(defn write-record!
  "`health/records/<platform>.edn` - one per platform, the latest run, what the README block, the
  tag check and the status read - and the run itself kept at `run-path`; and
  `resources/known-good.edn` from the same tools, so what the doctor shows as
  known-good is what a health run saw and not what someone typed."
  [{:keys [platform tools] :as rec}]
  (let [path (fs/path records-dir (str (:key platform) ".edn"))
        run (run-path rec)
        text (str ";; Written by `bb health --record`; rendered into the README by `bb health-sync`. Do not edit.\n"
                  (with-out-str (pp/pprint rec)))]
    (fs/create-dirs (fs/parent run))
    (spit run text)
    (spit (str path) text)
    (spit known-good-path
          (str ";; WRITTEN BY `bb health --record` from the health run named below - the versions that were\n"
               ";; actually run together through the KIT's gates, `bb init`, the loop and the generated\n"
               ";; application's gates. `bb doctor` shows it beside what is installed; newer is 'newer than\n"
               ";; tested', information and never a fault. Versions are floors; the JDK's is 21, 25 preferred.\n"
               (with-out-str (pp/pprint (cond-> {:as-of (:health/as-of rec) :platform (:label platform) :tools tools}
                                          (:xtdb rec) (assoc :xtdb (:xtdb rec))
                                          (:project rec) (assoc :project (:project rec)))))))
    (str path)))

(defn load-records
  "Every committed record, by platform key."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "*.edn")]
          [(str/replace (fs/file-name f) #"\\.edn$" "") (edn/read-string (slurp (str f)))])))

(defn render
  "The README block: one row per platform with a record, and the sentence that
  says nothing else has one. Derived, never typed."
  [records]
  (if (empty? records)
    "No health record yet: `bb health --record` writes one, and `bb health-sync` renders it here."
    (str "| Platform | Run on | KIT commit | Template | Checks | Time |\n|---|---|---|---|---|---|\n"
         (str/join "\n"
                   (for [[_ {:keys [health/as-of platform kit template checks] local-root :template/local-root}] records
                         :let [skipped (count (filter :skipped? checks))
                               ok (- (count (filter :ok? checks)) skipped)]]
                     (str "| " (:label platform) " | " as-of " | `" (:short kit) "`" (when (:dirty? kit) " (uncommitted changes)")
                          " | `" (:git/tag template) "` (`" (subs (:git/sha template) 0 7) "`)"
                          (when local-root " from a LOCAL CLONE, not the pin")
                          " | " ok " of " (count checks) " ok" (when (pos? skipped) (str ", " skipped " skipped")) ": "
                          (str/join ", " (map #(str (name (:subject %)) " " (name (:check %))
                                                    (cond (:skipped? %) " SKIPPED" (not (:ok? %)) " FAILED"))
                                              checks))
                          " | " (format "%.0fs" (/ (reduce + (map :ms checks)) 1000.0)) " |")))
         "\n\nOne record per platform actually run, the latest run on it; a platform not in the table has"
         " none. `bb health --record` on such a machine writes one - commit it, and `bb health-sync`.")))

(defn sync-main
  "bb health-sync [--check] [readme]   (default: ../README.md)

  Renders every record into the README between the markers; `--check` only
  compares, and fails on drift - the gate `bb gates` runs."
  [& args]
  (let [check? (boolean (some #{"--check"} args))
        readme (or (first (remove #(str/starts-with? % "--") args)) "../README.md")
        doc (slurp readme)
        block (render (load-records records-dir))
        updated (try (rules/splice doc block begin-marker end-marker)
                     (catch clojure.lang.ExceptionInfo e
                       (println (str "health-sync: " (ex-message e) " in " readme))
                       (System/exit 1)))]
    (cond
      (= doc updated) (println (str "health block in sync: " readme))
      check? (do (println (str "health block drift: " readme " does not match " records-dir "/ - run `bb health-sync`"))
                 (System/exit 1))
      :else (do (spit readme updated) (println (str "health block synced: " readme))))))

;; ---------------------------------------------------------------------------
;; the run
;; ---------------------------------------------------------------------------

(defn- say [& xs] (println (apply str xs)) (flush))

(def width
  "No line of the report is wider than this: a terminal's soft wrap drops a
  space when the text is copied, and a 300-column line is not read."
  100)

(defn wrap
  "`text` as lines of at most `n` columns, broken at spaces; a word longer
  than `n` stands on its own line. A line that fits is left as it is, its own
  spacing kept."
  [n text]
  (if (<= (count text) n)
    [text]
    (reduce (fn [lines word]
              (let [last-line (peek lines)]
                (if (and last-line (<= (+ (count last-line) 1 (count word)) n))
                  (conj (pop lines) (str last-line " " word))
                  (conj lines word))))
            []
            (remove str/blank? (str/split text #" ")))))

(defn- kit-version
  "KIT's tag and commit, `{:tag \"0.6.3\" :short \"304eecd\"}`; nil for what git cannot say."
  [kit]
  (let [out (fn [& args] (let [{:keys [exit out]} (apply p/shell {:dir kit :out :string :err :string :continue true} "git" args)]
                           (when (zero? exit) (not-empty (str/trim out)))))]
    {:tag (out "describe" "--tags" "--abbrev=0") :short (out "rev-parse" "--short" "HEAD")}))

(defn summary
  "The lines a healthy run ends with - what is READY, on what, and what was
  expected - from plain data, so every line is tested: `:kit` (`kit-version`),
  `:pin` (the template pin, nil when no application was generated), `:platform`
  (its label), `:results` (every check), `:doctor` (the doctor's results)."
  [{:keys [kit pin platform results doctor]}]
  (let [ms (reduce + (map :ms results))
        not-installed (for [{:keys [tool req status]} doctor
                            :when (and (not= :required req) (= :missing status))]
                        (name tool))
        security (some #(when (= :security (:check %)) (:detail %)) results)
        lines-of (fn [prefix] (count (filter #(str/starts-with? % prefix) (some-> security str/split-lines))))
        warns (lines-of "warn ")
        skips (lines-of "skipped ")
        plural (fn [n word] (str n " " word (when (not= 1 n) "s")))
        ;; a line too wide goes on under where its text began
        lines (fn [lead text]
                (let [pad (apply str (repeat (count lead) " "))]
                  (map str (cons lead (repeat pad)) (wrap (- width (count lead)) text))))]
    (vec
     (concat
      (lines "  " (str "READY - KIT " (or (:tag kit) "(untagged)") (some->> (:short kit) (str " at "))
                       (when pin (str " with template " (:git/tag pin) " (" (subs (:git/sha pin) 0 7) ")"))
                       ", on " platform))
      (lines "    toolchain  " (str "every required tool ok"
                                    (when (seq not-installed)
                                      (str "; optional, not installed: " (str/join ", " not-installed) " (not needed)"))))
      (lines "    health     " (str (count results) " of " (count results) " checks ok in " (format "%.0fs" (/ ms 1000.0))))
      (when (pos? (+ warns skips))
        (lines "    expected   " (str (plural warns "security warning") " and " (plural skips "skip")
                                      " - the template's known gaps, on every machine")))))))

(def ^:private row-format "  %-9s %-8s %-4s %7s  ")

(def ^:private indent (apply str (repeat (count (format row-format "" "" "" "")) " ")))

(defn- show [{:keys [check subject ok? ms detail]}]
  (let [n (- width (count indent))
        ;; a line that wraps goes on two spaces in, so each line of the detail starts where it is seen
        hang (fn [raw]
               ;; a pack's own padded columns, squeezed to three spaces before they are measured
               (let [line (str/replace raw #" {4,}" "   ")]
                 (if (<= (count line) n)
                   [line]
                   (let [l1 (first (wrap n line))
                         words (remove str/blank? (str/split line #" "))
                         more-text (str/join " " (drop (count (str/split l1 #" ")) words))]
                     (cons l1 (when-not (str/blank? more-text)
                                (map #(str "  " %) (wrap (- n 2) more-text))))))))
        [first-line & more] (mapcat hang (str/split-lines detail))]
    (say (format row-format (name subject) (name check) (if ok? "ok" "FAIL") (format "%.1fs" (/ ms 1000.0)))
         first-line)
    (doseq [line more] (say indent line))))

(defn run-subject!
  "The checks for one subject, in order, each printed as it completes. `checks`
  names which; every subject gets :gates and :red."
  [subject checks]
  (vec (for [c checks
             :let [r (case c
                       :gates (check-gates subject)
                       :red (check-red subject)
                       :loop (check-loop subject)
                       :serve (check-serve subject)
                       :image (check-image subject)
                       :browser (check-browser subject)
                       :security (check-security subject)
                       :embedded (check-xtdb-embedded subject)
                       :pgwire (check-xtdb-pgwire subject))]]
         (do (show r) r))))

(defn -main
  "bb health [--selfcheck-only] [--keep] [--record]

  The selfcheck project first, then the generated application. `--selfcheck-only` skips
  generation (no JVM beyond the project's own gates). `--keep` leaves the
  scratch workspace for inspection and prints where it is. `--record`, on a
  healthy full run, writes this platform's record and the known-good set -
  commit them, then `bb health-sync` renders the README block. It is refused before the run when
  this machine runs another version than the KIT pins of a tool the record holds (`bb kit-pins`,
  `kit-pins/record-drift`): a record certifies the pins, not a drift nobody chose."
  [& args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        kit (kit-dir)
        doctor-results (doctor/report)]
    (say "\nbb health - " kit)
    ;; BEFORE THE MINUTES ARE SPENT: a clone that may not record is told so now, not after the run
    (when (flags "--record")
      (when-let [why (record-refusal kit (workspace/find-workspace kit))]
        (say "  " why)
        (System/exit 1))
      ;; A RECORD CERTIFIES THE KIT'S PINS, NOT WHAT THIS MACHINE DRIFTED TO (plan 7.0, item 8.1d): a
      ;; tool a brew upgrade moved is refused here, in seconds, offline - the doctor's probe above
      (let [pins (kit-pins/read-pins kit)
            doctor (into {} (map (juxt :tool #(select-keys % [:version :status]))) doctor-results)
            rows (kit-pins/rows pins (kit-pins/installed pins doctor (System/getProperty "user.home")) {} nil)]
        (when-let [why (kit-pins/drift-refusal
                        (kit-pins/record-drift rows (str/starts-with? (System/getProperty "os.name") "Linux")))]
          (say "  " why)
          (System/exit 1))))
    (say "\n  doctor: gates tier " (if (doctor/ok? doctor-results :gates) "ok" "NOT OK")
         ", loop tier " (if (doctor/ok? doctor-results :loop) "ok" "NOT OK") " (`bb doctor` has the table)")
    (when-not (doctor/ok? doctor-results :loop)
      (say "  a loop cannot run here; run `bb doctor`, follow it, run this again")
      (System/exit 1))
    (say "\n" (format row-format "subject" "check" "ok" "time") "detail")
    (let [selfcheck (let [s (selfcheck-subject kit)
                          rs (run-subject! s [:gates :red :loop])]
                      (if (flags "--keep") (say "  kept: " (:workspace s)) (fs/delete-tree (fs/parent (:workspace s))))
                      rs)
          app (when-not (flags "--selfcheck-only")
                (say "\n  generating the application from " (:template (template/pin (template/load-pins)))
                     " (a JVM starts) ...")
                (let [{:keys [workspace app pin local-root ms]} (generate-app! kit)]
                  (say (format "  generated in %.1fs: %s%s" (/ ms 1000.0) app
                               (if local-root (str " - from LOCAL CLONE " local-root ", not the pin") "")))
                  (let [rs (run-subject! (app-subject kit workspace app app-name pin) [:gates :red :loop :serve :browser :security :image])]
                    (if (flags "--keep")
                      (say "\n  kept: " workspace)
                      (fs/delete-tree (fs/parent workspace)))
                    rs)))
          ;; the store the KIT offers, on the tested JDK (plan 7.0, item 7.3)
          xtdb (when-not (flags "--selfcheck-only") (run-subject! (xtdb-subject kit) [:embedded :pgwire]))
          all (concat selfcheck app xtdb)
          bad (remove :ok? all)]
      (say "")
      (if (seq bad)
        (do (say "  NOT HEALTHY: " (str/join ", " (map #(str (name (:subject %)) "/" (name (:check %))) bad))
                 (when (flags "--record") " - no record written"))
            (System/exit 1))
        (do (say "  healthy: " (count all) " checks, "
                 (format "%.0fs" (/ (reduce + (map :ms all)) 1000.0)))
            (say "")
            (doseq [l (summary {:kit (kit-version kit) :pin (when app (template/pin (template/load-pins)))
                                :platform (:label (platform)) :results all :doctor doctor-results})]
              (say l))
            (cond
              (and (flags "--record") (flags "--selfcheck-only"))
              (say "  --record needs the full run (both subjects); nothing written")
              (flags "--record")
              (let [rec (record kit all (template/pin (template/load-pins)) doctor-results
                                (some-> (System/getenv "KIT_TEMPLATE_LOCAL") not-empty fs/absolutize fs/normalize str))
                    path (write-record! rec)]
                (say "  recorded: " path " and " known-good-path
                     (when (get-in rec [:kit :dirty?]) " - NOTE: the KIT's tree has uncommitted changes, and the record says so")
                     "\n  commit them, then `bb health-sync` renders the README block"))))))))
