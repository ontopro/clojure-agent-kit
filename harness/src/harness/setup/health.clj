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
              RECORDED - `record`, as method.md §03 step 5 says: `run.edn`, and
              in the generated application's workspace the copy in the plan's
              `runs/`, naming the KIT, application and plan commits, each held
              to that repository's HEAD; the loop having read its rules and
              its profile from the plan, where `bb init` put them - and torn
              down, never merged
    :serve    (:app only) `bb serve`, `GET /` answers 200, the process tree
              stopped, the port free again

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
   [harness.gates.run :as gates]
   [harness.loop.driver :as driver]
   [harness.loop.orchestrate :as orch]
   [harness.models.runner :as runner]
   [harness.rules :as rules]
   [harness.setup.app :as app]
   [harness.setup.doctor :as doctor]
   [harness.setup.init :as init]
   [harness.setup.template :as template]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; subjects
;; ---------------------------------------------------------------------------

(defn kit-dir
  "The KIT's clone: this namespace runs from `harness/`."
  []
  (str (fs/parent (fs/normalize (fs/absolutize ".")))))

(defn- git! [dir & args]
  (let [{:keys [exit err]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed in " dir ": " (str/trim err)) {})))))

(defn selfcheck-subject
  "`<kit>/harness/health/selfcheck`, COPIED into a scratch workspace and made a repository of its
  own: the checked-in project is never written to, and the loop's branches land
  in a repository that is gone afterwards, not in the KIT's clone. Its gates are
  its own `bb` tasks, but the boundary gate by the KIT's path, since `..` no
  longer leads to the harness."
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
     :gates [[:fmt "bb fmt-check"] [:lint "bb lint"] [:test "bb test"]
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
  "The one task the loop is run on: method §03's `clamp` - no design content
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
                   :gates gates :nrepl/cmd cmd :spec-review/run? false :plan-check/run? false
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

(def serve-port 8000)
(def serve-timeout-ms 120000)

(defn- port-free? [port]
  (try (with-open [s (java.net.ServerSocket. port)] (.close s) true)
       (catch java.io.IOException _ false)))

(defn- get-status [url]
  (try (:status (http/get url {:throw false :timeout 2000}))
       (catch Exception _ nil)))

(defn check-serve
  "`bb serve` in the application, `GET /` is 200, the process tree stopped, the
  port free again. A busy port is reported, not tried around: the template's
  port is configuration, not a flag."
  [{:keys [dir] :as subject}]
  (if-not (port-free? serve-port)
    (result :serve subject false 0
            (str "port " serve-port " is in use; stop what holds it and run again"))
    (let [url (str "http://localhost:" serve-port "/")
          [[status] ms]
          (timed
           (fn []
             (let [pr (p/process {:dir dir :out :string :err :string} "bb" "serve")
                   deadline (+ (System/currentTimeMillis) serve-timeout-ms)]
               (try
                 (loop []
                   (let [s (get-status url)]
                     (cond
                       (= 200 s) [200]
                       (> (System/currentTimeMillis) deadline) [:timeout]
                       (not (.isAlive (:proc pr))) [:exited (:exit @pr)]
                       :else (do (Thread/sleep 1000) (recur)))))
                 (finally
                   (p/destroy-tree pr)
                   (deref pr 10000 nil))))))
          freed? (loop [n 0]
                   (cond (port-free? serve-port) true
                         (> n 20) false
                         :else (do (Thread/sleep 500) (recur (inc n)))))]
      (result :serve subject (and (= 200 status) freed?) ms
              (cond
                (not= 200 status) (str "no 200 from " url " within " (quot serve-timeout-ms 1000) "s ("
                                       (pr-str status) ")")
                (not freed?) (str "served 200, but port " serve-port " was still held after the stop")
                :else (str "GET " url " -> 200, then stopped; port free"))))))

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

(defn check-browser
  "The KIT's browser pack, `bb --config <kit>/tools/browser/bb.edn check --only
  screenshots /`, run in the application: it serves, opens the page in a
  headless Firefox through geckodriver, screenshots it as tall as it is,
  measures it, and stops the server. Ok when the task exits 0 and the
  screenshot exists. The pack is the KIT's, not the template's, so this
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
  for a test."
  ([subject] (check-browser subject {}))
  ([{:keys [dir kit] :as subject} {:keys [available? run]
                                   :or {available? browser-available?
                                        run (fn [dir env argv]
                                              (apply p/shell {:dir dir :out :string :err :string :continue true
                                                              :extra-env env :timeout browser-timeout-ms}
                                                     argv))}}]
   (if-not (available?)
     (assoc (result :browser subject true 0
                    "skipped: no geckodriver on the PATH, or no Firefox - the KIT's browser pack needs both (`bb doctor` has the row)")
            :skipped? true)
     (let [out-dir (str (fs/create-temp-dir {:prefix "kit-health-browser"}))
           shot (fs/path out-dir "1440" "home.png")
           [{:keys [exit out err]} ms]
           (timed #(try (run dir {} ["bb" "--config" (str (fs/path kit "tools" "browser" "bb.edn"))
                                     "check" "--only" "screenshots" "--out" out-dir "/"])
                        (catch Exception e {:exit -1 :out "" :err (ex-message e)})))
           ok? (and (= 0 exit) (fs/exists? shot) (pos? (fs/size shot)))
           tail (str/join "\n" (take-last 6 (str/split-lines (str out err))))]
       (fs/delete-tree out-dir)
       (result :browser subject ok? ms
               (if ok?
                 (str "served, Firefox opened /, a screenshot as tall as the page and its measure; stopped"
                      (let [line (last (filter #(str/includes? % "scrollWidth") (str/split-lines (str out))))]
                        (when line (str "\n" (str/trim line)))))
                 (str "the browser pack's check exited " exit (when-not (fs/exists? shot) ", no screenshot written")
                      (when (str/includes? (str out err) "did not start")
                        "; Firefox did not start - on macOS, run once from a terminal and grant it the permission")
                      "\n" tail)))))))

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
             :plan-template (init/plan-template-files kit)
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

(defn platform
  "This machine, as a record's key and label: `{:key \"macos-arm64\" :label \"macOS 26.5 arm64\"}`.
  Nothing of the host but its OS and architecture."
  []
  (let [os (System/getProperty "os.name")
        version (System/getProperty "os.version")
        arch (System/getProperty "os.arch")
        os-key (cond (str/starts-with? os "Mac") "macos"
                     (str/starts-with? os "Linux") "linux"
                     (str/starts-with? os "Windows") "windows"
                     :else (str/lower-case (str/replace os #"\\s+" "-")))
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
  [kit checks pin doctor-results]
  (let [{:keys [key label]} (platform)]
    {:health/as-of (str (java.time.LocalDate/now))
     :platform {:key key :label label}
     :kit (kit-commit kit)
     :template (select-keys pin [:template :git/tag :git/sha])
     :tools (into (sorted-map)
                  (keep (fn [{:keys [tool req version]}] (when (= :required req) [tool version])))
                  doctor-results)
     ;; a skipped check says so in the record, so a row never reads it as passed
     :checks (mapv #(select-keys % [:subject :check :ok? :ms :skipped?]) checks)
     :ok? (every? :ok? checks)}))

(defn write-record!
  "`health/records/<platform>.edn` - one per platform, the latest run - and
  `resources/known-good.edn` from the same tools, so what the doctor shows as
  known-good is what a health run saw and not what someone typed."
  [{:keys [platform tools] :as rec}]
  (let [path (fs/path records-dir (str (:key platform) ".edn"))]
    (fs/create-dirs records-dir)
    (spit (str path) (str ";; Written by `bb health --record`; rendered into the README by `bb health-sync`. Do not edit.\n"
                          (with-out-str (pp/pprint rec))))
    (spit known-good-path
          (str ";; WRITTEN BY `bb health --record` from the health run named below - the versions that were\n"
               ";; actually run together through the KIT's gates, `bb init`, the loop and the generated\n"
               ";; application's gates. `bb doctor` shows it beside what is installed; newer is 'newer than\n"
               ";; tested', information and never a fault. Versions are floors except the JDK's major.\n"
               (with-out-str (pp/pprint {:as-of (:health/as-of rec) :platform (:label platform) :tools tools}))))
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
                   (for [[_ {:keys [health/as-of platform kit template checks]}] records
                         :let [skipped (count (filter :skipped? checks))
                               ok (- (count (filter :ok? checks)) skipped)]]
                     (str "| " (:label platform) " | " as-of " | `" (:short kit) "`" (when (:dirty? kit) " (uncommitted changes)")
                          " | `" (:git/tag template) "` (`" (subs (:git/sha template) 0 7) "`)"
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

(defn- show [{:keys [check subject ok? ms detail]}]
  (say (format "  %-8s %-8s %-4s %6.1fs  %s" (name subject) (name check) (if ok? "ok" "FAIL")
               (/ ms 1000.0) (first (str/split-lines detail))))
  (doseq [line (rest (str/split-lines detail))]
    (say "                                  " line)))

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
                       :browser (check-browser subject))]]
         (do (show r) r))))

(defn -main
  "bb health [--selfcheck-only] [--keep] [--record]

  The selfcheck project first, then the generated application. `--selfcheck-only` skips
  generation (no JVM beyond the project's own gates). `--keep` leaves the
  scratch workspace for inspection and prints where it is. `--record`, on a
  healthy full run, writes this platform's record and the known-good set -
  commit them, then `bb health-sync` renders the README block."
  [& args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        kit (kit-dir)
        doctor-results (doctor/report)]
    (say "\nbb health - " kit)
    (say "\n  doctor: gates tier " (if (doctor/ok? doctor-results :gates) "ok" "NOT OK")
         ", loop tier " (if (doctor/ok? doctor-results :loop) "ok" "NOT OK") " (`bb doctor` has the table)")
    (when-not (doctor/ok? doctor-results :loop)
      (say "  a loop cannot run here; run `bb doctor`, follow it, run this again")
      (System/exit 1))
    (say "\n  subject  check    ok      time  detail")
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
                  (let [rs (run-subject! (app-subject kit workspace app app-name pin) [:gates :red :loop :serve :browser])]
                    (if (flags "--keep")
                      (say "\n  kept: " workspace)
                      (fs/delete-tree (fs/parent workspace)))
                    rs)))
          all (concat selfcheck app)
          bad (remove :ok? all)]
      (say "")
      (if (seq bad)
        (do (say "  NOT HEALTHY: " (str/join ", " (map #(str (name (:subject %)) "/" (name (:check %))) bad))
                 (when (flags "--record") " - no record written"))
            (System/exit 1))
        (do (say "  healthy: " (count all) " checks, "
                 (format "%.0fs" (/ (reduce + (map :ms all)) 1000.0)))
            (cond
              (and (flags "--record") (flags "--selfcheck-only"))
              (say "  --record needs the full run (both subjects); nothing written")
              (flags "--record")
              (let [rec (record kit all (template/pin (template/load-pins)) doctor-results)
                    path (write-record! rec)]
                (say "  recorded: " path " and " known-good-path
                     (when (get-in rec [:kit :dirty?]) " - NOTE: the KIT's tree has uncommitted changes, and the record says so")
                     "\n  commit them, then `bb health-sync` renders the README block"))))))))
