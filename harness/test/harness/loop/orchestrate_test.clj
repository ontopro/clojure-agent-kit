(ns harness.loop.orchestrate-test
  "The loop, end to end against a scratch git repo — real worktrees, a stub
  nREPL, real assembly and real gates, with only the dispatch faked.

  WHY NOT FAKE MORE. Everything between the dispatches is where this loop's
  decisions live: assembly refusing a file, the gate run, the diff the Reviewer
  would read, the events each of those leaves. A test that stubbed them would
  be testing `next-action` against states it had invented rather than against
  the states the driver actually produces, which is the one thing a test here
  is for. The dispatch is faked because it is the only step that costs money.

  `:repl-probe` is a constant: the fixture worktrees run a shell script that
  writes a port file, not an nREPL, so the real probe would fail every test
  for the wrong reason. That seam is exercised on its own below. `:triage-fn`
  is a constant too — the default would ask the profile's model — and each
  test says what triage answers, the way thub's tests do."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.shapes :as shapes]
   [harness.loop.driver :as driver]
   [harness.loop.log :as log]
   [harness.loop.orchestrate :as orch]
   [harness.models.runner :as runner]
   [harness.rules :as rules]))

;; ---------------------------------------------------------------------------
;; the fixture
;; ---------------------------------------------------------------------------

(defn- git! [dir & args]
  (apply p/shell {:dir dir :out :string :err :string :continue true} "git" args))

(def ^:private impl-path "src/scratch/x.clj")
(def ^:private test-path "test/scratch/x_test.clj")
(def ^:private impl-src "(ns scratch.x)\n\n(defn f [x] x)\n")
(def ^:private test-src "(ns scratch.x-test)\n")

(def ^:private stub-nrepl
  "Writes a port file and stays up — everything provisioning needs, without a
  JVM. `provision_test.clj` uses the same one."
  ["sh" "-c" "echo 45999 > .nrepl-port; sleep 30"])

(def ^:private spec
  {:task/id "t-01"
   :task/title "a trivial slice"
   :blueprint/slice {:shapes [] :interfaces ['(f [x])]}
   :files/impl [impl-path]
   :files/test [test-path]
   :files/context []
   :layer/name :service})

(defn- scratch-repo
  "A repo with one commit, so `worktree add HEAD` has a HEAD. `committed`
  optionally seeds the files the task will write, for the empty-diff case."
  [& [committed]]
  (let [dir (str (fs/create-temp-dir))]
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "harness@test")
    (git! dir "config" "user.name" "harness-test")
    (spit (str (fs/path dir "README.md")) "scratch\n")
    (doseq [[path content] committed]
      (let [f (fs/path dir path)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) content)))
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "init")
    dir))

(defn- run-dir!
  "A run directory inside `repo`, with the spec and the loop config in it."
  [repo & [{:keys [gates config]}]]
  (let [d (fs/path repo "run")]
    (fs/create-dirs d)
    (spit (str (fs/path d "spec.edn")) (pr-str spec))
    (spit (str (fs/path d "loop.edn"))
          (pr-str (merge {:run/id "a-test"
                          :profile "resources/profiles/claude.edn"
                          :gates (or gates [[:ok "true"]])
                          :nrepl/cmd stub-nrepl
                          ;; the loop reviews a spec before start; these tests are about what follows
                          :spec-review/run? false
                          :worktrees/dir "wt"}
                         config)))
    (str d)))

(def ^:private approves {:verdict :approve :reasons []})

(defn- rejects [& reasons] {:verdict :reject :reasons (vec reasons)})

(defn- fake-dispatch
  "Stands in for `driver/dispatch!`: writes what `writes` says this role
  produces into its own worktree — a map of path to content, or a function
  of the packet returning one, so a retry can write differently — records
  the same step and event a real dispatch would, and returns `results`'
  answer for the role, which may also be a function of the packet: a
  Reviewer that rejects the first diff and approves the second is one.

  THE REVIEWER APPROVES UNLESS A TEST SAYS OTHERWISE, the way the gates are
  green unless a test says otherwise: most tests here are about something
  else, and the verdict is what lets them reach the stop they are about."
  [writes results]
  (fn [ctx step-name role pkt]
    (let [answer (get results role)
          answer (if (fn? answer) (answer pkt) answer)
          w (get writes role)
          w (if (fn? w) (w pkt) w)
          ;; :files IS WHAT IT WROTE, as a real dispatch reports from git. It was
          ;; always [], which nothing read until `gated-since-written?` did — and
          ;; then a retry that had written looked like one that had not.
          r (merge {:status :done :files (vec (keys w)) :stdout "ok" :cost 0.01
                    :runner/meta (cond-> {:model "fake-1"} (= :reviewer role) (assoc :verdict approves))}
                   answer)]
      (doseq [[path content] w]
        (let [f (fs/path (:repl/worktree pkt) path)]
          (fs/create-dirs (fs/parent f))
          (spit (str f) content)))
      (@#'driver/step! ctx {:step/name step-name :step/kind :dispatch
                            :step/status (if (= :done (:status r)) :done :fail)
                            :step/ms 10 :step/model "fake-1" :step/cost (:cost r)})
      (driver/event! ctx (driver/dispatch-event step-name role pkt r))
      r)))

(def ^:private green-writes
  {:coder {impl-path impl-src} :tester {test-path test-src}})

(defn- quietly
  "Run `f`, keeping its console noise out of the test output, and return what
  it returned — `with-out-str` returns the noise instead."
  [f]
  (let [res (atom nil)]
    (with-out-str (reset! res (f)))
    @res))

(def ^:private a-person
  "The triage answer that hands everything to a person — the default here,
  so a test that is not about triage sees the stops milestone 1 had."
  (constantly {:route :human :reason "a person decides"}))

(defn- drive!
  "`run-task!` with the dispatch faked, the REPL probe answered, and triage
  answered by `triage` (a fn of the trigger)."
  [run-dir {:keys [writes results probe triage]
            :or {writes green-writes results {} probe (constantly true) triage a-person}}]
  (with-redefs-fn {#'driver/dispatch! (fake-dispatch writes results)}
    #(quietly (fn [] (orch/run-task! run-dir {:repl-probe probe :triage-fn triage})))))

(defn- events [run-dir]
  (:events (edn/read-string (slurp (str (fs/path run-dir "state.edn"))))))

(defn- kinds [run-dir] (mapv :event/kind (events run-dir)))

(defn- teardown! [run-dir]
  ;; The tests' runs stop for a person and are never recorded; `--discard` is the
  ;; way to drop such a run, and a plain teardown refuses it (row 85).
  (with-out-str (driver/teardown! (driver/context run-dir) {:discard? true})))

;; ---------------------------------------------------------------------------
;; the stops
;; ---------------------------------------------------------------------------

(deftest the-loop-runs-to-the-reviewer-and-stops-for-a-person
  (let [repo (scratch-repo)
        rd (run-dir! repo)]
    (let [res (drive! rd {})]
      (is (= :awaiting-merge (:status res)) "green, reviewed, and the merge is not the loop's")
      (is (= :reviewed (:stop/kind res)))
      (is (= :human (:route res)))
      (is (= 1 (:attempts res)) "one round")
      (testing "it got there through every step, in order"
        (is (= [:balance :dependents :dispatch :dispatch :calls :targets :gates :dispatch :stopped]
               (kinds rd))))
      (testing "the stop is in the append-only log too, not only in state.edn"
        (let [logged (log/read-log (str (fs/path rd "events.log")))]
          (is (= (kinds rd) (mapv :event/kind logged)))
          (is (every? inst? (map :at logged)) "each stamped when it happened")))
      (testing "and `record` writes a record that validates against TaskRun"
        (with-out-str (driver/record! (driver/context rd)))
        (let [r (edn/read-string (slurp (str (fs/path rd "run.edn"))))]
          (is (nil? (shapes/explain-run r)))
          (is (= :awaiting-merge (:run/status r)) "the loop's outcome, not the gates'")
          (is (= "t-01" (:task/id r)))
          (is (inst? (:run/started-at r)))
          (is (= impl-src (get (:run/files r) impl-path)) "the gated bytes"))))
    (teardown! rd)))

(deftest a-run-whose-directory-is-in-no-repository-works-on-the-workspace-s-application
  ;; A workspace: a plain folder, the application's repository inside it, and the run directory
  ;; under work/ - in NO repository. The project used to be wherever the run directory was, so
  ;; this would have stopped at `the run directory is not inside a git repository`.
  (let [app (scratch-repo)
        ws (fs/parent app)
        work (fs/path ws (str "work-" (fs/file-name app)))
        rd (fs/path work "runs" "r1")]
    (fs/create-dirs rd)
    (spit (str (fs/path work "workspace.edn"))
          (pr-str {:workspace/app (str "../" (fs/file-name app)) :workspace/work "."}))
    (spit (str (fs/path rd "spec.edn")) (pr-str spec))
    (spit (str (fs/path rd "loop.edn"))
          (pr-str {:run/id "ws-test" :profile "resources/profiles/claude.edn"
                   :gates [[:ok "true"]] :nrepl/cmd stub-nrepl :spec-review/run? false}))
    (let [res (drive! (str rd) {})
          cfg (:config (edn/read-string (slurp (str (fs/path rd "state.edn")))))]
      (is (= :reviewed (:stop/kind res)) "the whole loop, to the merge stop")
      (is (= (str (fs/real-path app)) (str (fs/real-path (:repo/root cfg))))
          "the project is the workspace's application, not the run directory's surroundings")
      (is (str/starts-with? (str (fs/real-path (:worktrees/dir cfg))) (str (fs/real-path work)))
          "and the worktrees are under the workspace's work folder")
      (teardown! (str rd)))))

(deftest a-red-gate-routed-to-a-person-stops-with-the-proposal-beside-the-verdict
  (let [repo (scratch-repo)
        rd (run-dir! repo {:gates [[:red "false"]]})
        asked (atom [])]
    (let [res (drive! rd {:triage (fn [trg] (swap! asked conj trg) {:route :human :reason "cannot tell"})})]
      (is (= :escalated (:status res)))
      (is (= :red-gate (:stop/kind res)))
      (is (= "triage: cannot tell" (:reason res)))
      (is (= :human (:route res)) "the model's route, with nothing dispatched on it"))
    (testing "triage was shown the failing gate and both worktrees"
      (let [trg (first @asked)]
        (is (= 1 (count @asked)))
        (is (= :red-gate (:trigger trg)))
        (is (= :red (get-in trg [:payload :gate-result :gates/failed])))
        (is (= "t-01" (get-in trg [:spec :task/id])))
        (is (fs/exists? (fs/path (get-in trg [:worktrees :coder]) impl-path)) "the Coder's file as written")))
    (testing "the proposal is recorded, the verdict is on the stop, and no Reviewer was dispatched"
      (is (some #(= :routing (:event/kind %)) (events rd)))
      (let [stopped (last (events rd))]
        (is (= :stopped (:event/kind stopped)))
        (is (= {:by :model :route :human :reason "cannot tell" :guidance nil} (:triage stopped))))
      (is (= 2 (count (filter #(= :dispatch (:event/kind %)) (events rd))))))
    (testing "running again over the untouched run is the same stop, and does not ask again"
      (let [res (drive! rd {:triage (fn [trg] (swap! asked conj trg) {:route :coder :reason "changed my mind"})})]
        (is (= :red-gate (:stop/kind res)))
        (is (= :human (:route res)))
        (is (= 1 (count @asked)) "no second opinion was bought")))
    (teardown! rd)))

(deftest a-red-gate-triage-routed-tooling-stops-and-names-the-namespace-nobody-owns
  ;; Row 61: a merged property test failed a later task's gate; no role in the run owned
  ;; it, and the loop had no route that said so.
  (let [repo (scratch-repo)
        rd (run-dir! repo {:gates [[:test "printf 'FAIL in scratch.util-test/clamp-above-hi-spec (util_test.clj:46)\\nexpected true\\n' && false"]]})
        asked (atom [])]
    (let [res (drive! rd {:triage (fn [trg] (swap! asked conj trg) {:route :tooling :reason "a merged property test, nobody's here"})})]
      (is (= :escalated (:status res)))
      (is (= :tooling (:stop/kind res)))
      (is (= :person (:owner res)))
      (is (= "triage: a merged property test, nobody's here — failing outside this run: scratch.util-test" (:reason res))))
    (testing "the proposal said so first, and nothing was dispatched on it"
      (let [routing (first (filter #(= :routing (:event/kind %)) (events rd)))]
        (is (= :tooling (:owner routing)))
        (is (= ["scratch.util-test"] (:foreign-namespaces routing))))
      (is (= 2 (count (filter #(= :dispatch (:event/kind %)) (events rd))))))
    (testing "running again is the same stop"
      (is (= :tooling (:stop/kind (drive! rd {:triage (fn [trg] (swap! asked conj trg) {:route :coder :reason "no"})}))))
      (is (= 1 (count @asked))))
    (teardown! rd)))

(deftest a-note-triage-routed-tooling-stops-keeps-the-pause-and-a-continue-carries-on
  ;; Row 12: a Coder used `note` to report a harness defect and the only routes were
  ;; contract-shaped. Now the loop stops for the machine's owner, and the decision that the
  ;; machine is fixed is a `continue`.
  (let [repo (scratch-repo)
        rd (run-dir! repo)]
    (let [res (drive! rd {:results {:coder {:notes ["the REPL tool drops the first line of every eval"]}}
                          :triage (constantly {:route :tooling :reason "the harness's REPL tool, not the contract"})})]
      (is (= :tooling (:stop/kind res)))
      (is (= :person (:owner res)))
      (is (= "triage: the harness's REPL tool, not the contract" (:reason res)))
      (is (= 1 (count (filter #(= :dispatch (:event/kind %)) (events rd)))) "the Tester waits"))
    (testing "once the machine is fixed, a continue with the decision carries on to the Tester"
      (let [decision (str (fs/path rd "decision.edn"))]
        (spit decision (pr-str {:decision "the tool is fixed and the contract stands"}))
        (with-redefs-fn {#'driver/dispatch! (fake-dispatch green-writes {})}
          #(with-out-str (driver/continue! (driver/context rd) decision)))
        (is (= :reviewed (:stop/kind (drive! rd {}))))))
    (teardown! rd)))

(defn- red-until-fixed
  "A gate that is red until the impl file says `fixed` — and a Coder that
  writes `fixed` only when it is told why it is back."
  []
  {:gates [[:fixed (str "grep -q fixed " impl-path)]]
   :writes {:coder (fn [pkt] {impl-path (if (:task/feedback pkt) (str impl-src ";; fixed\n") impl-src)})
            :tester {test-path test-src}}})

(deftest a-red-gate-routed-to-a-role-is-retried-with-the-guidance-and-goes-green
  (let [repo (scratch-repo)
        {:keys [gates writes]} (red-until-fixed)
        rd (run-dir! repo {:gates gates})
        res (drive! rd {:writes writes
                        :triage (constantly {:route :coder :reason "impl diverges from the slice"
                                             :guidance "WRITE THE WORD fixed"})})]
    (is (= :awaiting-merge (:status res)) "red, routed, retried, green, reviewed")
    (is (= :reviewed (:stop/kind res)))
    (is (= 2 (:attempts res)) "one round of triage")
    (testing "the retry is one :triage event, by the model, with its verdict — the same event a typed retry leaves"
      (let [t (first (filter #(= :triage (:event/kind %)) (events rd)))]
        (is (= :model (:by t)))
        (is (= :coder (:role t) (:route t)))
        (is (= "impl diverges from the slice" (:decision t)) "the model's reason is the decision")
        (is (= "WRITE THE WORD fixed" (:guidance t)))
        (is (= 2 (:attempt t)))))
    (testing "the retried Coder was told the guidance first, then the gate's output"
      (let [retry (first (filter #(= :coder-r1 (:event/step %)) (events rd)))]
        (is (= [:triage :gate] (mapv :feedback/from (:feedback retry))))
        (is (= "WRITE THE WORD fixed" (:feedback/text (first (:feedback retry)))))
        (is (str/starts-with? (:feedback/text (second (:feedback retry))) "fixed failed:"))))
    (is (= [:balance :dependents :dispatch :dispatch :calls :targets :gates :routing :triage :dispatch
            :calls :targets :gates :dispatch :stopped]
           (kinds rd)))
    (testing "and the record validates, with the triage round in it"
      (with-out-str (driver/record! (driver/context rd)))
      (let [r (edn/read-string (slurp (str (fs/path rd "run.edn"))))]
        (is (nil? (shapes/explain-run r)))
        (is (= 2 (:run/attempts r)))))
    (teardown! rd)))

(deftest a-red-gate-routed-to-the-architect-stops-and-a-hand-retry-carries-on
  (let [repo (scratch-repo)
        {:keys [gates writes]} (red-until-fixed)
        rd (run-dir! repo {:gates gates})
        res (drive! rd {:writes writes
                        :triage (constantly {:route :architect :reason "the slice cannot say fixed"})})]
    (is (= :escalated (:status res)))
    (is (= :red-gate (:stop/kind res)))
    (is (= :architect (:route res)))
    (is (= "triage: the slice cannot say fixed" (:reason res)))
    (is (= 1 (:attempts res)) "an architect route spends no round")
    (is (= 2 (count (filter #(= :dispatch (:event/kind %)) (events rd)))) "nothing was dispatched on it")
    (testing "a person retries by hand; `run` then checks and reviews without asking triage again"
      (let [triage (str (fs/path rd "triage.edn"))
            asked (atom 0)]
        (spit triage (pr-str {:decision "amended; the word is fixed" :feedback [{:feedback/from :architect :feedback/text "say fixed"}]}))
        (with-redefs-fn {#'driver/dispatch! (fake-dispatch writes {})}
          #(with-out-str (driver/retry! (driver/context rd) "coder" triage)))
        (let [res (drive! rd {:writes writes :triage (fn [_] (swap! asked inc) {:route :human :reason "?"})})]
          (is (= :reviewed (:stop/kind res)))
          (is (= 2 (:attempts res)))
          (is (zero? @asked)))))
    (teardown! rd)))

(deftest a-tester-route-whose-feedback-names-the-implementation-stops-for-a-person
  ;; The same check a hand `retry tester` is refused on, as a stop rather
  ;; than a throw: the loop has no judgement to record with --allow-leak.
  (let [repo (scratch-repo)
        rd (run-dir! repo {:gates [[:red "false"]]})
        res (drive! rd {:triage (constantly {:route :tester :reason "the test misreads the contract"
                                             :guidance (str "look at " impl-path " — f is fine")})})]
    (is (= :escalated (:status res)))
    (is (= :tester-leak (:stop/kind res)))
    (is (= :human (:route res)))
    (is (str/includes? (:reason res) "names the implementation"))
    (is (str/includes? (:reason res) impl-path))
    (is (= 2 (count (filter #(= :dispatch (:event/kind %)) (events rd)))) "the Tester was not dispatched")
    (testing "the stop records what triage said and what the loop did"
      (let [t (:triage (last (events rd)))]
        (is (= :human (:route t)))
        (is (= :tester (:routed t)))))
    (teardown! rd)))

(deftest a-tester-route-with-clean-guidance-is-dispatched
  (let [repo (scratch-repo)
        rd (run-dir! repo {:gates [[:fixed (str "grep -q fixed " test-path)]]})
        writes {:coder {impl-path impl-src}
                :tester (fn [pkt] {test-path (if (:task/feedback pkt) (str test-src ";; fixed\n") test-src)})}
        res (drive! rd {:writes writes
                        :triage (constantly {:route :tester :reason "the test misreads the contract"
                                             :guidance "the contract grants f; test f, and write fixed"})})]
    (is (= :reviewed (:stop/kind res)))
    (let [t (first (filter #(= :triage (:event/kind %)) (events rd)))]
      (is (= :tester (:role t)))
      (is (= {:leaks [] :allowed? false} (:leak-check t)) "checked, clean, and recorded as such"))
    (teardown! rd)))

(deftest the-cap-is-spent-on-rounds-and-the-third-red-one-escalates
  ;; The cap is per task: two hand retries and the loop refuses a third,
  ;; whichever roles those retries went to.
  (let [repo (scratch-repo)
        rd (run-dir! repo {:gates [[:red "false"]]})
        triage (str (fs/path rd "triage.edn"))
        retry! (fn [role]
                 (spit triage (pr-str {:decision "still red"
                                       :feedback [{:feedback/from :gate :feedback/text "it failed"}]}))
                 (with-redefs-fn {#'driver/dispatch! (fake-dispatch green-writes {})}
                   #(with-out-str (driver/retry! (driver/context rd) role triage))))]
    (is (= :red-gate (:stop/kind (drive! rd {}))) "round 1")
    (retry! "coder")
    (is (= :red-gate (:stop/kind (drive! rd {}))) "round 2, still under the cap")
    (retry! "tester")
    (let [asked (atom 0)
          res (drive! rd {:triage (fn [_] (swap! asked inc) {:route :coder :reason "again"})})]
      (is (= :capped (:stop/kind res)) "round 3 is the cap, and the roles differed")
      (is (= :escalated (:status res)))
      (is (= 3 (:attempts res)))
      (is (str/includes? (:reason res) "retry cap of 3"))
      (is (zero? @asked) "at the cap nothing is asked: there is nothing left to route"))
    (testing "and a further retry by hand is refused with the same number"
      (is (thrown-with-msg? Exception #"retry cap 3 reached"
                            (driver/retry! (driver/context rd) "coder" triage))))
    (testing "the worktrees are left in place for whoever picks it up"
      (is (fs/exists? (fs/path rd "wt" "a-test-coder"))))
    (teardown! rd)))

(deftest a-dead-repl-escalates-without-dispatching-anything
  ;; The `:no-repl-no-edits` rule tells an agent to stop rather than edit blind.
  ;; The probe is what makes that more than an instruction.
  ;; The real `dispatch!` runs here, with only the model behind it replaced:
  ;; the probe is INSIDE it, so a test that faked the dispatch would be testing
  ;; that a function it wrote does not call a model.
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        dispatched (atom [])
        counting (reify runner/AgentRunner
                   (run-agent [_ role _] (swap! dispatched conj role)
                     {:status :done :files [] :stdout "" :cost nil}))]
    (let [res (with-redefs-fn {#'runner/api-runner (fn [& _] counting)}
                #(quietly (fn [] (orch/run-task! rd {:repl-probe (constantly false)}))))]
      (is (= :escalated (:status res)))
      (is (= :repl-dead (:stop/kind res)))
      (is (= :human (:route res))))
    (is (empty? @dispatched) "nothing was dispatched into a dead worktree")
    (testing "the event names the port and carries the failure as gate feedback"
      (let [e (first (filter #(= :repl-dead (:event/kind %)) (events rd)))]
        (is (= 45999 (:port e)))
        (is (str/includes? (:feedback/text (first (:feedback e))) "did not answer a probe"))))
    (teardown! rd)))

(deftest a-failed-dispatch-escalates-before-the-next-role
  (let [repo (scratch-repo)
        rd (run-dir! repo)]
    (let [res (drive! rd {:writes {:coder {impl-path impl-src}}
                          :results {:coder {:status :failed :stdout "HTTP 400"}}})]
      (is (= :escalated (:status res)))
      (is (= :dispatch-failed (:stop/kind res)))
      (is (str/includes? (:reason res) "coder")))
    (testing "the Tester was never dispatched against work that was not done"
      (is (= 1 (count (filter #(= :dispatch (:event/kind %)) (events rd))))))
    (teardown! rd)))

(deftest a-credit-refusal-stops-by-name-and-says-whose-account-to-top-up
  ;; A 402 is a failed dispatch whose answer is money, not a retry: the first
  ;; project built on the KIT met it as :dispatch-failed, retried, and paid a
  ;; second refusal for the same reason with the same text.
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        credit {:harness/error :credit :status 402 :message "insufficient credits"
                :endpoint "https://openrouter.ai/api/v1" :key-env "OPENROUTER_API_KEY"}]
    (let [res (drive! rd {:writes {:coder {impl-path impl-src}}
                          :results {:coder {:status :failed :stdout "HTTP 402" :runner/meta {:error credit}}}})]
      (is (= :escalated (:status res)))
      (is (= :credit (:stop/kind res)))
      (is (= :person (:owner res)))
      (is (str/includes? (:reason res) "openrouter.ai answered 402 to the coder dispatch"))
      (is (str/includes? (:reason res) "OPENROUTER_API_KEY"))
      (is (not (str/includes? (:reason res) "insufficient")) "the provider's text is in the event, not the stop"))
    (testing "the Tester was never dispatched, and a second `run` is the same stop"
      (is (= 1 (count (filter #(= :dispatch (:event/kind %)) (events rd)))))
      (is (= :credit (:stop/kind (drive! rd {:results {:coder {:status :failed}}})))))
    (teardown! rd)))

(deftest a-note-triage-hands-to-a-person-pauses-the-run-and-the-stop-is-the-pause
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        asked (atom [])
        noted {:coder {:notes ["the contract contradicts its own dependent"]}}]
    (let [res (drive! rd {:results noted
                          :triage (fn [trg] (swap! asked conj trg) {:route :human :reason "a conflict; a person reads it"})})]
      (is (= :paused (:stop/kind res)))
      (is (= :escalated (:status res)))
      (is (= :human (:route res)))
      (is (= "triage: a conflict; a person reads it" (:reason res))))
    (testing "triage was shown the note, who left it, and what comes next"
      (is (= {:trigger :note :payload {:after :coder :next :tester :notes ["the contract contradicts its own dependent"]}}
             (select-keys (assoc (first @asked) :payload (:payload (first @asked))) [:trigger :payload]))))
    (is (= 1 (count (filter #(= :dispatch (:event/kind %)) (events rd))))
        "the Tester waits on the decision")
    (testing "and after a decision is recorded, running again carries on"
      (let [decision (str (fs/path rd "decision.edn"))]
        (spit decision (pr-str {:decision "an observation, not a conflict"}))
        (with-redefs-fn {#'driver/dispatch! (fake-dispatch green-writes {})}
          #(with-out-str (driver/continue! (driver/context rd) decision)))
        (is (= :reviewed (:stop/kind (drive! rd {}))))))
    (teardown! rd)))

(deftest a-note-triage-continues-goes-on-to-the-tester-with-the-reason-recorded
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        res (drive! rd {:results {:coder {:notes ["the targets say nothing about NaN"]}}
                        :triage (constantly {:route :continue :reason "an observation; the targets stand"})})]
    (is (= :reviewed (:stop/kind res)) "continued to the Tester, gated, reviewed")
    (is (= 1 (:attempts res)) "a continue is not a round")
    (let [c (first (filter #(= :continued (:event/kind %)) (events rd)))]
      (is (= "an observation; the targets stand" (:decision c)) "a continue that changes nothing still says why")
      (is (= :model (:by c)))
      (is (= :continue (:route c)))
      (is (= {:after :coder :next :tester} (select-keys c [:after :next]))))
    (is (= [:balance :dependents :dispatch :paused :continued :dispatch :calls :targets :gates :dispatch :stopped]
           (kinds rd)))
    (teardown! rd)))

(deftest a-note-triage-routes-to-the-architect-and-the-run-stops
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        res (drive! rd {:results {:coder {:notes ["target 2 contradicts target 3"]}}
                        :triage (constantly {:route :architect :reason "two targets contradict"})})]
    (is (= :paused (:stop/kind res)))
    (is (= :architect (:route res)))
    (is (= "triage: two targets contradict" (:reason res)))
    (is (= 1 (count (filter #(= :dispatch (:event/kind %)) (events rd)))))
    (teardown! rd)))

(deftest a-note-triage-can-send-the-noting-role-back-with-guidance
  ;; A Coder that noted an assumption the slice already settles is
  ;; re-dispatched with the answer, and the run then carries on to the
  ;; Tester as a continue would have.
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        writes {:coder (fn [pkt] {impl-path (if (:task/feedback pkt) (str impl-src ";; told\n") impl-src)})
                :tester {test-path test-src}}
        res (drive! rd {:writes writes
                        :results {:coder {:notes ["I assumed f is identity"]}}
                        :triage (constantly {:route :coder :reason "the slice says otherwise" :guidance "f is not identity"})})]
    (is (= :reviewed (:stop/kind res)))
    (is (= 2 (:attempts res)) "a re-dispatch is a round")
    (is (= [:balance :dependents :dispatch :paused :triage :dispatch :continued :dispatch :calls :targets :gates :dispatch :stopped]
           (kinds rd)))
    (let [retry (first (filter #(= :coder-r1 (:event/step %)) (events rd)))]
      (is (= [{:feedback/from :triage :feedback/text "f is not identity"}] (:feedback retry))
          "on a note there is no gate output; the guidance is the whole feedback"))
    (is (nil? (:decision (first (filter #(= :continued (:event/kind %)) (events rd)))))
        "the continue needed no decision of its own: the retry since the pause carries one")
    (teardown! rd)))

(deftest a-measured-triage-call-is-a-report-step-and-its-prompt-is-kept
  ;; The model triage returns what it cost; a constant fn does not, and
  ;; leaves no step — a report row that measured nothing is the thing
  ;; :step/source exists to prevent.
  (let [repo (scratch-repo)
        rd (run-dir! repo {:gates [[:red "false"]]})
        measured {:route :human :reason "r" :prompt "THE PROMPT" :answer "THE ANSWER" :ms 7 :status :done
                  :result {:status :done :files [] :stdout "THE ANSWER" :cost 0.02
                           :runner/meta {:model "triage-1" :provider "P" :tokens 9}}}
        res (drive! rd {:triage (constantly measured)})]
    (is (= :red-gate (:stop/kind res)))
    (let [steps (:steps (edn/read-string (slurp (str (fs/path rd "state.edn")))))
          t (first (filter #(= :triage (:step/name %)) steps))]
      (is (some? t))
      (is (= :dispatch (:step/kind t)))
      (is (= :measured (:step/source t)))
      (is (= 0.02 (:step/cost t)))
      (is (= "triage-1" (:step/model t)))
      (is (= 7 (:step/ms t))))
    (is (= "THE PROMPT" (slurp (str (fs/path rd "triage.prompt.txt")))))
    (is (= "THE ANSWER" (slurp (str (fs/path rd "triage.answer.txt")))))
    (is (= 0.02 (:cost (:triage (last (events rd))))) "and the stop's verdict carries the cost")
    (testing "the record validates with the triage step and counts its cost"
      (with-out-str (driver/record! (driver/context rd)))
      (let [r (edn/read-string (slurp (str (fs/path rd "run.edn"))))]
        (is (nil? (shapes/explain-run r)))
        (is (= 0.04 (:run/cost r)) "two fake dispatches at 0.01 and the triage at 0.02")))
    (teardown! rd)))

(deftest green-gates-over-an-empty-diff-escalate-and-review-nothing
  ;; Every gate passes on an unchanged tree, so a green run whose diff is empty
  ;; says only that the repository was already green.
  (let [repo (scratch-repo {impl-path impl-src test-path test-src})
        rd (run-dir! repo)
        reviewed (atom 0)]
    (let [res (with-redefs-fn {#'driver/dispatch!
                               (let [f (fake-dispatch green-writes {})]
                                 (fn [ctx step role pkt]
                                   (when (= :reviewer role) (swap! reviewed inc))
                                   (f ctx step role pkt)))}
                #(quietly (fn [] (orch/run-task! rd {:repl-probe (constantly true)}))))]
      (is (= :empty-diff (:stop/kind res)))
      (is (= :escalated (:status res)))
      (is (str/includes? (:reason res) "empty diff")))
    (is (zero? @reviewed) "the Reviewer is not dispatched to read nothing")
    (is (some #(= :empty-diff (:event/kind %)) (events rd)))
    (teardown! rd)))

;; ---------------------------------------------------------------------------
;; the verdict
;; ---------------------------------------------------------------------------

(defn- rejecting-until-fixed
  "A Reviewer that rejects any diff without the word `fixed` in it, with
  findings that quote the implementation — as a real one's do."
  [pkt]
  (if (str/includes? (:review/diff pkt) "fixed")
    {}
    {:stdout "FINDING: `(defn f [x] x)` in src/scratch/x.clj ignores its bounds."
     :notes ["the targets do not say what f does at the bounds"]
     :runner/meta {:model "fake-1" :verdict (rejects "f ignores its bounds")}}))

(deftest a-rejection-routed-to-the-coder-is-retried-with-the-findings-and-reviewed-again
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        asked (atom [])
        writes {:coder (fn [pkt] {impl-path (if (:task/feedback pkt) (str impl-src ";; fixed\n") impl-src)})
                :tester {test-path test-src}}
        res (drive! rd {:writes writes
                        :results {:reviewer rejecting-until-fixed}
                        :triage (fn [trg] (swap! asked conj trg)
                                  {:route :coder :reason "the finding is a defect the targets rule out"
                                   :guidance "honour the bounds"})})]
    (is (= :awaiting-merge (:status res)) "green, rejected, routed, retried, green, approved")
    (is (= :reviewed (:stop/kind res)))
    (is (= 2 (:attempts res)) "a rejection's retry is a round like any other")
    (testing "triage was shown the verdict, the findings and the Reviewer's note"
      (is (= 1 (count @asked)))
      (is (= {:trigger :rejection
              :payload {:verdict (rejects "f ignores its bounds")
                        :findings "FINDING: `(defn f [x] x)` in src/scratch/x.clj ignores its bounds."
                        :notes ["the targets do not say what f does at the bounds"]}}
             (select-keys (first @asked) [:trigger :payload]))))
    (testing "the Coder was told the guidance first, then the Reviewer's own findings"
      (let [retry (first (filter #(= :coder-r1 (:event/step %)) (events rd)))]
        (is (= [:triage :reviewer] (mapv :feedback/from (:feedback retry))))
        (is (= "honour the bounds" (:feedback/text (first (:feedback retry)))))
        (is (str/starts-with? (:feedback/text (second (:feedback retry))) "FINDING:"))))
    (testing "both reviews are in the record, each with its verdict, and the second is a new step"
      (is (= [[:reviewer :reject] [:reviewer-r1 :approve]]
             (for [e (events rd) :when (= :reviewer (:role e))]
               [(:event/step e) (:verdict (:verdict e))]))))
    (is (= [:balance :dependents :dispatch :dispatch :calls :targets :gates :dispatch :triage :dispatch
            :calls :targets :gates :dispatch :stopped]
           (kinds rd)))
    (teardown! rd)))

(deftest a-rejection-routed-to-the-tester-sends-the-guidance-and-none-of-the-review
  ;; The Reviewer read the implementation, so every word of its findings is
  ;; derived from it. The Tester gets triage's guidance — checked — and no more.
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        writes {:coder {impl-path impl-src}
                :tester (fn [pkt] {test-path (if (:task/feedback pkt) (str test-src ";; fixed\n") test-src)})}
        res (drive! rd {:writes writes
                        :results {:reviewer rejecting-until-fixed}
                        :triage (constantly {:route :tester :reason "no test pins the bounds"
                                             :guidance "the contract grants f; add a case at each bound"})})]
    (is (= :reviewed (:stop/kind res)))
    (let [retry (first (filter #(= :tester-r1 (:event/step %)) (events rd)))
          t (first (filter #(= :triage (:event/kind %)) (events rd)))]
      (is (= [{:feedback/from :triage :feedback/text "the contract grants f; add a case at each bound"}]
             (:feedback retry))
          "the guidance alone: no :reviewer feedback, and so no quoted implementation")
      (is (= {:leaks [] :allowed? false} (:leak-check t))))
    (teardown! rd)))

(deftest a-rejection-routed-to-the-tester-with-leaky-guidance-stops-for-a-person
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        res (drive! rd {:results {:reviewer rejecting-until-fixed}
                        :triage (constantly {:route :tester :reason "no test pins the bounds"
                                             :guidance (str "read " impl-path " and test what it does")})})]
    (is (= :tester-leak (:stop/kind res)))
    (is (= :human (:route res)))
    (is (= :escalated (:status res)) "a rejected run is never awaiting a merge")
    (is (= 3 (count (filter #(= :dispatch (:event/kind %)) (events rd)))) "Coder, Tester, Reviewer — and no retry")
    (teardown! rd)))

(deftest a-rejection-routed-to-the-architect-stops-and-cannot-be-merged
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        res (drive! rd {:results {:reviewer rejecting-until-fixed}
                        :triage (constantly {:route :architect :reason "the targets do not say what happens at the bounds"})})]
    (is (= :rejected (:stop/kind res)))
    (is (= :architect (:route res)))
    (is (= :escalated (:status res)))
    (is (= "triage: the targets do not say what happens at the bounds" (:reason res)))
    (testing "and `merge` refuses it, decision or no decision: only an approval awaits a merge"
      (let [decision (str (fs/path rd "merge.edn"))]
        (spit decision (pr-str {:decision "I disagree with the Reviewer"}))
        (is (thrown-with-msg? Exception #"not awaiting a merge"
                              (driver/merge! (driver/context rd) decision)))
        (is (not (fs/exists? (fs/path repo impl-path))))))
    (teardown! rd)))

(deftest a-review-with-no-verdict-escalates-and-a-fresh-review-carries-on
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        asked (atom 0)
        res (drive! rd {:results {:reviewer {:stdout "Looks fine to me." :runner/meta {:model "fake-1" :verdict nil}}}
                        :triage (fn [_] (swap! asked inc) {:route :human :reason "?"})})]
    (is (= :no-verdict (:stop/kind res)))
    (is (= :escalated (:status res)))
    (is (= :human (:route res)))
    (is (zero? @asked) "no verdict is not a rejection: there is nothing to route")
    (testing "the dispatch itself is :done — the findings were paid for and are kept"
      (let [e (last (filter #(= :reviewer (:role %)) (events rd)))]
        (is (= :done (:status e)))
        (is (contains? e :verdict))
        (is (nil? (:verdict e)))))
    (testing "`check` again buys a fresh review, and `run` reads its verdict"
      (with-redefs-fn {#'driver/dispatch! (fake-dispatch green-writes {})}
        #(with-out-str (driver/check! (driver/context rd))))
      (is (= :reviewed (:stop/kind (drive! rd {})))))
    (teardown! rd)))

(deftest the-review-a-person-is-told-to-read-is-on-the-console
  ;; The stop says "read the Reviewer's findings above". The real `dispatch!`
  ;; runs here, with only the model behind it replaced, because the printing is
  ;; inside it.
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        writing (reify runner/AgentRunner
                  (run-agent [_ role pkt]
                    (doseq [[path content] (get green-writes role)]
                      (let [f (fs/path (:repl/worktree pkt) path)]
                        (fs/create-dirs (fs/parent f))
                        (spit (str f) content)))
                    (cond-> {:status :done :files (vec (keys (get green-writes role))) :stdout "wrote it" :cost nil}
                      (= :reviewer role)
                      (assoc :stdout "FINDING 1: f is the identity.\nNothing else."
                             :runner/meta {:verdict {:verdict :approve :reasons ["meets all three targets"]}}))))
        out (with-redefs-fn {#'runner/api-runner (fn [& _] writing)}
              #(with-out-str (orch/run-task! rd {:repl-probe (constantly true) :triage-fn a-person})))]
    (is (str/includes? out "VERDICT: approve"))
    (is (str/includes? out "- meets all three targets"))
    (is (str/includes? out "FINDING 1: f is the identity."))
    (is (str/includes? out "Nothing else.") "every line of it, not the first")
    (is (< (str/index-of out "FINDING 1") (str/index-of out "STOP · reviewed")) "above the stop that points at it")
    (is (str/includes? out "bb run-loop merge") "and the stop says how to merge")
    (is (str/includes? out "reviews this run has bought: 1 (cost not yet known)")
        "and how many reviews the run has paid for - a check after a change buys another, outside the cap (row 16)")
    (teardown! rd)))

(deftest the-reviews-a-run-has-bought-are-counted-from-its-events-and-priced-from-its-steps
  ;; One run took five reviews for most of its money, one `check` at a time, and the cap -
  ;; which counts rounds - never moved. The count is the dispatch events; the cost is the
  ;; report steps of the same names, however many of them have a figure yet.
  (let [events [{:event/kind :dispatch :event/step :coder :role :coder}
                {:event/kind :dispatch :event/step :reviewer :role :reviewer}
                {:event/kind :dispatch :event/step :reviewer-r1 :role :reviewer}
                {:event/kind :dispatch :event/step :reviewer-r2 :role :reviewer}]
        steps [{:step/name :coder :step/cost 0.5}
               {:step/name :reviewer :step/cost 0.3}
               {:step/name :reviewer-r1 :step/cost 0.25}
               {:step/name :reviewer-r2 :step/cost nil}]]
    (is (= {:count 3 :cost 0.55 :cost-known 2} (orch/reviews-bought events steps)))
    (is (= {:count 0 :cost 0 :cost-known 0} (orch/reviews-bought [(first events)] (take 1 steps)))
        "a run that has not reached a review says nothing")))

;; ---------------------------------------------------------------------------
;; the merge
;; ---------------------------------------------------------------------------

(defn- merge!
  "`driver/merge!` with `decision` written to a file first, as a person would."
  [rd decision]
  (let [f (str (fs/path rd "merge.edn"))]
    (spit f (pr-str {:decision decision}))
    (quietly #(driver/merge! (driver/context rd) f))))

(defn- branches [repo]
  (->> (str/split-lines (:out (git! repo "branch" "--format=%(refname:short)")))
       (remove str/blank?)
       set))

(deftest the-happy-path-merges-onto-the-base-checkout-records-and-tears-down
  (let [repo (scratch-repo)
        rd (run-dir! repo)]
    (is (= :awaiting-merge (:status (drive! rd {}))))
    (is (not (fs/exists? (fs/path repo impl-path))) "the loop merged nothing by itself")
    (merge! rd "the slice is met and the review found nothing")
    (testing "the merge landed on the scratch repo's main, as one commit under one merge commit"
      (is (= impl-src (slurp (str (fs/path repo impl-path)))))
      (is (= test-src (slurp (str (fs/path repo test-path)))))
      (let [log (str/split-lines (:out (git! repo "log" "--format=%s" "-3")))]
        (is (= ["Merge branch 'a-test-reviewer'" "t-01 — run a-test" "init"] log)))
      (is (= 2 (count (str/split (str/trim (:out (git! repo "log" "--format=%P" "-1"))) #" ")))
          "--no-ff: a real merge commit, two parents, even though main had not moved"))
    (testing "the worktrees are gone, and so are the branches: the run is recorded and the work is on main"
      ;; They used to stay for ever, and a build closed with 31 of them (row 78).
      (doseq [role ["coder" "tester" "reviewer"]]
        (is (not (fs/exists? (fs/path rd "wt" (str "t-01-" role))))))
      (is (= #{"main"} (branches repo))))
    (testing "the record says :merged, validates, and holds the gated bytes"
      (let [r (edn/read-string (slurp (str (fs/path rd "run.edn"))))]
        (is (nil? (shapes/explain-run r)))
        (is (= :merged (:run/status r)))
        (is (= impl-src (get (:run/files r) impl-path)))))
    (testing "the decision is recorded BEFORE the merge, on the commit's own event"
      (is (= [:stopped :merge-commit :merged :balance] (vec (take-last 4 (kinds rd)))) "record adds the balance last")
      (let [[c m] (take 2 (take-last 3 (events rd)))]
        (is (= "the slice is met and the review found nothing" (:decision c) (:decision m)))
        (is (= "a-test-reviewer" (:branch c) (:branch m)))
        (is (= "main" (:into m)))
        (is (= [impl-path test-path] (:files c)) "what the commit held — the assembled files and nothing else")
        (is (= (:commit c) (:commit m)))
        (is (= (str/trim (:out (git! repo "rev-parse" "HEAD"))) (:merge-commit m)))))
    (testing "state.edn and events.log still agree — `record` ran, and it refuses when they do not"
      (is (= (kinds rd) (mapv :event/kind (log/read-log (str (fs/path rd "events.log")))))))
    (testing "a second merge is refused, and `run` afterwards is a stop, not a review of nothing"
      (is (thrown-with-msg? Exception #"already been merged" (merge! rd "again")))
      (let [res (drive! rd {})]
        (is (= :merged (:status res)))
        (is (= :merged (:stop/kind res)))))))

(deftest a-merge-nobody-decides-awaits-with-everything-in-place
  ;; Denying a merge is not a command. The run stays where the loop left it.
  (let [repo (scratch-repo)
        rd (run-dir! repo)]
    (is (= :awaiting-merge (:status (drive! rd {}))))
    (testing "no decision file content, no merge"
      (is (thrown-with-msg? Exception #"a merge is a person's decision" (merge! rd "")))
      (is (thrown-with-msg? Exception #"a merge is a person's decision" (merge! rd nil))))
    (testing "nothing merged; branches and worktrees preserved for whoever decides"
      (is (not (fs/exists? (fs/path repo impl-path))))
      (is (= ["init"] (str/split-lines (:out (git! repo "log" "--format=%s")))))
      (is (contains? (branches repo) "a-test-reviewer"))
      (is (fs/exists? (fs/path rd "wt" "a-test-reviewer" impl-path)))
      (is (= :stopped (last (kinds rd))) "a refusal leaves no event"))
    (testing "and the run is still awaiting: the same stop, tomorrow"
      (is (= :awaiting-merge (:status (drive! rd {})))))
    (teardown! rd)))

(deftest merge-refuses-a-gate-worktree-that-changed-after-the-review
  ;; `gated-current?` reads events, and an edit by hand leaves none.
  (let [repo (scratch-repo)
        rd (run-dir! repo)]
    (drive! rd {})
    (spit (str (fs/path rd "wt" "a-test-reviewer" impl-path)) (str impl-src "(defn sneaked-in [] :unreviewed)\n"))
    (is (thrown-with-msg? Exception #"not what the Reviewer read" (merge! rd "looks good")))
    (is (not (fs/exists? (fs/path repo impl-path))))
    (is (= ["init"] (str/split-lines (:out (git! (str (fs/path rd "wt" "a-test-reviewer")) "log" "--format=%s"))))
        "and nothing was committed on the gate branch either")
    (teardown! rd)))

(deftest a-run-that-moved-on-after-the-stop-is-not-awaiting-a-merge
  ;; A hand retry after the approval: the gate worktree no longer holds the
  ;; Coder's latest file, and the approval was of something else.
  (let [repo (scratch-repo)
        rd (run-dir! repo)
        triage (str (fs/path rd "triage.edn"))]
    (drive! rd {})
    (spit triage (pr-str {:decision "one more thing" :feedback [{:feedback/from :architect :feedback/text "also g"}]}))
    (with-redefs-fn {#'driver/dispatch! (fake-dispatch {:coder {impl-path (str impl-src "(defn g [] 1)\n")}} {})}
      #(with-out-str (driver/retry! (driver/context rd) "coder" triage)))
    (is (thrown-with-msg? Exception #"not awaiting a merge" (merge! rd "merge it")))
    (testing "`run` gates and reviews the new file, and then it is"
      (is (= :awaiting-merge (:status (drive! rd {:writes {}}))))
      (merge! rd "now reviewed with g in it")
      (is (str/includes? (slurp (str (fs/path repo impl-path))) "(defn g [] 1)")))))

(deftest a-merge-git-refuses-is-aborted-tears-nothing-down-and-can-be-run-again
  (let [repo (scratch-repo)
        rd (run-dir! repo)]
    (drive! rd {})
    ;; main grows a conflicting file of the same name while the run waits
    (fs/create-dirs (fs/parent (fs/path repo impl-path)))
    (spit (str (fs/path repo impl-path)) "(ns scratch.x)\n\n(defn f [x] :someone-else)\n")
    (git! repo "add" impl-path)
    (git! repo "commit" "-q" "-m" "a conflicting x")
    (is (thrown-with-msg? Exception #"could not merge" (merge! rd "merge it")))
    (testing "the base checkout is as it was found, not mid-merge"
      (is (str/blank? (:out (git! repo "status" "--porcelain" "--untracked-files=no"))))
      (is (str/includes? (slurp (str (fs/path repo impl-path))) ":someone-else")))
    (testing "the failure is an event with git's output, and everything is still there"
      (is (= [:stopped :merge-commit :merge-failed] (vec (take-last 3 (kinds rd)))))
      (is (str/includes? (:out (last (events rd))) "CONFLICT"))
      (is (fs/exists? (fs/path rd "wt" "a-test-reviewer" impl-path))))
    (testing "once main is out of the way the same command merges, and commits nothing twice"
      (git! repo "rm" "-q" impl-path)
      (git! repo "commit" "-q" "-m" "out of the way")
      (merge! rd "merge it, again")
      (is (= impl-src (slurp (str (fs/path repo impl-path)))))
      (is (= 1 (count (filter #(= :merge-commit (:event/kind %)) (events rd)))))
      (is (= :merged (:run/status (edn/read-string (slurp (str (fs/path rd "run.edn"))))))))))

;; ---------------------------------------------------------------------------
;; next-action, over states rather than over runs
;; ---------------------------------------------------------------------------

(defn- action [state] (orch/next-action state))

(deftest next-action-decides-from-the-state-alone
  (let [dispatched [{:event/kind :dispatch :role :coder :status :done}
                    {:event/kind :dispatch :role :tester :status :done}]
        base {:sessions {:coder {}} :spec spec :events dispatched}]
    (testing "no sessions yet"
      (is (= :start (:action (action {})))))
    (testing "provisioned and nothing dispatched — `start` threw after the worktrees existed"
      (let [a (action {:sessions {:coder {}} :spec spec :events []})]
        (is (= :stop (:action a)))
        (is (= :not-dispatched (:stop/kind a)))))
    (testing "a dead REPL, before anything else"
      (is (= :repl-dead (:stop/kind (action (update base :events conj {:event/kind :repl-dead}))))))
    (testing "a failed dispatch"
      (is (= :dispatch-failed
             (:stop/kind (action (assoc base :events [{:event/kind :dispatch :event/step :coder
                                                       :status :failed}]))))))
    (testing "a failed dispatch the provider refused for credit, before the plain failure"
      (let [a (action (assoc base :events [{:event/kind :dispatch :event/step :reviewer :status :failed
                                            :error/kind :credit
                                            :credit {:status 402 :endpoint "https://api.anthropic.com/v1"
                                                     :key-env "ANTHROPIC_API_KEY"}}]))]
        (is (= :credit (:stop/kind a)))
        (is (= :human (:route a)))
        (is (str/includes? (:reason a) "api.anthropic.com answered 402 to the reviewer dispatch"))
        (is (str/includes? (:reason a) "ANTHROPIC_API_KEY")))
      (is (= :dispatch-failed
             (:stop/kind (action (assoc base :events [{:event/kind :dispatch :event/step :coder :status :failed
                                                       :error/kind :api-error}]))))
          "any other error kind is the plain failure"))
    (testing "paused: triage on the note with nothing decided, a continue once something is"
      (let [paused (assoc base :paused {:after :coder :next :tester}
                          :events (conj dispatched {:event/kind :paused}))]
        (is (= {:action :triage :trigger :note} (action paused)))
        (is (= :continue (:action (action (update paused :events conj {:event/kind :amend})))))))
    (testing "nothing since the last stop is the same stop again"
      (let [a (action (update base :events conj {:event/kind :stopped :stop/kind :red-gate :route :architect
                                                 :reason "triage: r" :run/status :escalated}))]
        (is (= {:action :stop :stop/kind :red-gate :route :architect :reason "triage: r" :run/status :escalated} a))))
    (testing "assembly refused, and an empty diff"
      (is (= :assemble-refused
             (:stop/kind (action (update base :events conj {:event/kind :assemble-refused})))))
      (is (= :empty-diff
             (:stop/kind (action (update base :events conj {:event/kind :empty-diff}))))))
    (testing "a dispatch since the last gate run is checked, not stopped"
      (is (= :check (:action (action base))))
      (is (= :check (:action (action (assoc base :last-gates {:gates/passed? true}
                                            :events (conj dispatched {:event/kind :gates}
                                                          {:event/kind :dispatch :role :tester})))))))
    (let [green (assoc base :last-gates {:gates/passed? true})
          reviewed (fn [verdict & more]
                     (assoc green :events (into (conj dispatched {:event/kind :gates}
                                                      {:event/kind :dispatch :role :reviewer :status :done
                                                       :verdict verdict})
                                                more)))]
      (testing "green and approved waits on the merge — the only way to :awaiting-merge"
        (let [a (action (reviewed {:verdict :approve :reasons []}))]
          (is (= :reviewed (:stop/kind a)))
          (is (= :awaiting-merge (:run/status a)))))
      (testing "a rejection is triaged under the cap, and escalates at it without asking"
        (is (= {:action :triage :trigger :rejection} (action (reviewed {:verdict :reject :reasons ["r"]}))))
        (let [a (action (update (reviewed {:verdict :reject :reasons ["r"]}) :events
                                #(into [{:event/kind :triage} {:event/kind :triage}] %)))]
          (is (= :capped (:stop/kind a)))
          (is (str/includes? (:reason a) "the Reviewer rejected"))))
      (testing "a review with no verdict is a person's, and is not awaiting a merge"
        (let [a (action (reviewed nil))]
          (is (= :no-verdict (:stop/kind a)))
          (is (= :escalated (:run/status a)))
          (is (= :human (:route a)))))
      (testing "a role sent back after a rejection makes that review history: check, do not ask again"
        (is (= :check (:action (action (reviewed {:verdict :reject :reasons ["r"]}
                                                 {:event/kind :triage}
                                                 {:event/kind :dispatch :role :coder :status :done}))))))
      (testing "AN AMENDMENT MAKES A REVIEW HISTORY: the verdict was about a contract that has changed"
        ;; The events as a real run left them: the rejection, triage's route to the Architect, the
        ;; stop, and then `amend`. The loop once sent that old rejection to triage a second time.
        (is (= {:action :check}
               (action (reviewed {:verdict :reject :reasons ["r"]}
                                 {:event/kind :triage}
                                 {:event/kind :stopped :stop/kind :rejected :route :architect
                                  :reason "triage: the contract" :run/status :escalated}
                                 {:event/kind :amend})))
            "gates and a fresh review, not the old rejection routed again")
        (is (= {:action :check}
               (action (reviewed {:verdict :approve :reasons []}
                                 {:event/kind :stopped :stop/kind :reviewed :route :human
                                  :reason "approved" :run/status :awaiting-merge}
                                 {:event/kind :amend})))
            "an approval of the old contract is not an approval of the new one")
        (is (= {:action :triage :trigger :rejection}
               (action (reviewed {:verdict :reject :reasons ["r"]})))
            "and with no amendment the rejection is still the last word"))
      (testing "a failed Reviewer dispatch is a failed dispatch, not a missing verdict"
        (is (= :dispatch-failed
               (:stop/kind (action (assoc green :events (conj dispatched {:event/kind :gates}
                                                              {:event/kind :dispatch :role :reviewer
                                                               :event/step :reviewer :status :failed
                                                               :verdict nil})))))))
      (testing "merged is finished, whatever else the events say"
        (let [a (action (reviewed {:verdict :approve :reasons []} {:event/kind :merged}))]
          (is (= :merged (:stop/kind a)))
          (is (= :merged (:run/status a))))))
    (testing "red under the cap is triaged; at the cap it escalates"
      (let [red (assoc base :last-gates {:gates/passed? false :gates/failed :test}
                       :events (conj dispatched {:event/kind :gates}
                                     {:event/kind :routing :retry-role :tester}))]
        (is (= {:action :triage :trigger :red-gate} (action red)))
        (testing "AN AMENDMENT MAKES A GATE RESULT HISTORY: the gates judged a spec that has changed"
          ;; The events as a real run left them: a red `calls` gate over a slice that granted
          ;; nothing, triage's route to the Architect, the stop, and then the `amend` that adds
          ;; the grant. The loop once paid triage to read the old gate output a second time.
          (is (= {:action :check}
                 (action (update red :events into
                                 [{:event/kind :triage}
                                  {:event/kind :stopped :stop/kind :red-gate :route :architect
                                   :reason "triage: the slice grants nothing" :run/status :escalated}
                                  {:event/kind :amend}])))
              "the gates again, free, before triage is asked about output that is out of date")
          (is (= {:action :triage :trigger :red-gate}
                 (action (update red :events into [{:event/kind :amend} {:event/kind :gates}])))
              "and a gate run AFTER the amendment is current: still red is triaged"))
        (is (= :capped (:stop/kind (action (update red :events into
                                                   [{:event/kind :triage} {:event/kind :triage}])))))
        (is (= :triage (:action (action (-> red
                                            (assoc-in [:spec :gates :retry-cap] 5)
                                            (update :events into [{:event/kind :triage}
                                                                  {:event/kind :triage}])))))
            "the cap comes from the spec")))))

;; ---------------------------------------------------------------------------
;; a retried role starts from the bytes the gates judged
;; ---------------------------------------------------------------------------

(deftest a-retried-role-is-given-the-file-gate-0-repaired-not-the-one-it-wrote
  ;; A Tester's first file had bracket errors; gate 0 repaired them in the GATE
  ;; worktree and the gates ran. Sent back over one assertion, the Tester opened
  ;; its own copy, could not load it, and spent every turn hunting the bracket.
  (let [repo (scratch-repo)
        rd (run-dir! repo {:gates [[:fixed (str "grep -q fixed " test-path)]]})
        broken "(ns scratch.x-test)\n\n(def cases [1 2 3)\n"
        found (atom nil)
        writes {:coder {impl-path impl-src}
                :tester (fn [pkt]
                          (if (:task/feedback pkt)
                            (do (reset! found (slurp (str (fs/path (:repl/worktree pkt) test-path))))
                                {test-path (str @found ";; fixed\n")})
                            {test-path broken}))}
        res (drive! rd {:writes writes
                        :triage (constantly {:route :tester :reason "the test over-constrains the contract"
                                             :guidance "compare order-insensitively, and write fixed"})})]
    (is (= :reviewed (:stop/kind res)))
    (testing "what the retried Tester found in its OWN worktree reads, and is the gate's copy"
      (is (not= broken @found) "not the bytes it wrote")
      (is (some? (read-string (str "[" @found "]"))) "it reads")
      (is (str/includes? @found "(def cases [1 2 3])") "with gate 0's repair in it"))
    (testing "the sync is an event, between the decision and the dispatch"
      (is (= [:triage :synced-from-gate :dispatch]
             (->> (kinds rd) (drop-while #(not= :triage %)) (take 3) vec)))
      (is (= {:role :tester :files [test-path]}
             (select-keys (first (filter #(= :synced-from-gate (:event/kind %)) (events rd))) [:role :files]))))
    (testing "and the Tester was never handed the implementation this way: what sits at the impl path in its worktree is still the stub"
      (is (not= impl-src (slurp (str (fs/path rd "wt" "a-test-tester" impl-path))))))
    (teardown! rd)))

(deftest a-retry-typed-before-any-check-does-not-overwrite-newer-work
  ;; The role's copy is newer than the gate's: a hand retry after a hand retry,
  ;; with no gate run between them.
  (let [repo (scratch-repo)
        rd (run-dir! repo {:gates [[:red "false"]]})
        triage (str (fs/path rd "triage.edn"))
        seen (atom [])
        tester (fn [pkt]
                 (let [f (fs/path (:repl/worktree pkt) test-path)]
                   (swap! seen conj (when (fs/exists? f) (slurp (str f))))
                   {test-path (str test-src ";; attempt " (:task/attempt pkt 1) "\n")}))
        retry! (fn []
                 (spit triage (pr-str {:decision "again" :feedback [{:feedback/from :gate :feedback/text "red"}]}))
                 (with-redefs-fn {#'driver/dispatch! (fake-dispatch {:tester tester} {})}
                   #(with-out-str (driver/retry! (driver/context rd) "tester" triage))))]
    (drive! rd {:writes {:coder {impl-path impl-src} :tester tester}})
    (retry!)
    (retry!)
    (is (= [nil (str test-src ";; attempt 1\n") (str test-src ";; attempt 2\n")] @seen)
        "the third attempt saw the second's bytes: nothing had been gated since, so nothing was synced")
    (is (= 0 (count (filter #(= :synced-from-gate (:event/kind %)) (events rd)))))
    (teardown! rd)))

;; ---------------------------------------------------------------------------
;; who a stop is for
;; ---------------------------------------------------------------------------

(deftest a-writer-cut-off-at-its-cap-is-named-at-the-stop
  (let [d (fn [step role capped? status]
            {:event/kind :dispatch :event/step step :role role :capped? capped? :status status
             :files [(str (name role) ".clj")] :iterations 24})]
    (is (= [{:step :tester :files ["tester.clj"] :iterations 24}]
           (orch/cut-off-writers [(d :coder :coder false :done) (d :tester :tester true :done)])))
    (is (= [] (orch/cut-off-writers [(d :tester :tester true :done) (d :tester-r1 :tester false :done)]))
        "a retry that finished replaced the file")
    (is (= [] (orch/cut-off-writers [(d :tester :tester true :failed)]))
        "capped and wrote nothing is a failed dispatch, which stops the loop by itself")
    (is (= [] (orch/cut-off-writers [(d :reviewer :reviewer true :done)])) "the Reviewer writes no file")
    (is (= [] (orch/cut-off-writers [])))))

(deftest a-stop-is-the-architect-s-when-its-answer-is-an-amendment-and-the-person-s-otherwise
  ;; A session driving the loop handles the Architect's stops itself and asks a
  ;; person only at the person's. The line is what makes a run automatable.
  (doseq [[kind route expected] [[:spec-reviewed nil :architect]
                                 [:paused :architect :architect]
                                 [:red-gate :architect :architect]
                                 [:rejected :architect :architect]
                                 [:paused :human :person]
                                 [:red-gate :human :person]
                                 [:capped :human :person]
                                 [:no-verdict :human :person]
                                 [:reviewed :human :person]
                                 [:not-dispatched :human :person]
                                 [:repl-dead :human :person]
                                 [:dispatch-failed :human :person]
                                 [:tester-leak :tester :person]
                                 [:merged :human :person]]]
    (is (= expected (orch/owner {:stop/kind kind :route route}))
        (str (name kind) " routed " (some-> route name)))))

(deftest start-warns-when-the-checkout-holds-what-the-worktrees-will-not-see
  ;; The worktrees are cut from the last commit: an edit sitting in the checkout
  ;; is invisible to every role and gate of the run. Warned, not refused;
  ;; `:repo/allow-dirty?` silences.
  (let [start-out (fn [config]
                    (let [repo (scratch-repo)
                          rd (run-dir! repo {:config config})]
                      (spit (str (fs/path repo "README.md")) "edited by hand\n")
                      (spit (str (fs/path repo "notes.txt")) "untracked\n")
                      (let [out (with-redefs-fn {#'driver/dispatch! (fake-dispatch green-writes {})}
                                  #(with-out-str (orch/run-task! rd {:repl-probe (constantly true) :triage-fn a-person})))
                            e (first (filter #(= :dirty-tree (:event/kind %)) (events rd)))]
                        (teardown! rd)
                        [out e])))]
    (let [[out e] (start-out nil)]
      (is (str/includes? out "WARNING: the worktrees are cut from the last commit"))
      (is (str/includes? out "2 files there are not in it"))
      (is (str/includes? out " M README.md"))
      (is (str/includes? out "?? notes.txt"))
      (is (not (str/includes? out "?? run/")) "the run directory and its worktrees are the loop's, not counted")
      (is (= {:count 2 :files [" M README.md" "?? notes.txt"] :allowed? false} (dissoc e :event/kind :event/at-ms :at))
          "and the record keeps it"))
    (let [[out e] (start-out {:repo/allow-dirty? true})]
      (is (not (str/includes? out "WARNING")) "said you mean it: silent")
      (is (true? (:allowed? e)) "but still on the record"))))

(deftest start-says-which-rule-source-placeholders-are-still-standing
  ;; They reach every role as literal text with every gate green. Said before anyone is
  ;; dispatched, and not refused: the seed itself ships with them standing.
  ;; ON A FIXTURE, never the live rule source: the README tells an adopter to fill the
  ;; placeholders, and a test that needs them standing goes red the moment they do.
  (let [shipped (rules/load-rules)
        filled (vec (remove #((set (map :id (rules/unfilled shipped))) (:id %)) shipped))
        standing (into filled (for [id [:fixture-one :fixture-two :fixture-three]]
                                {:id id :group :conventions :audience #{:human} :title "A fixture."
                                 :text "<Name the thing this project must say here.>"}))
        start-with (fn [rs]
                     (let [repo (scratch-repo)
                           rd (run-dir! repo)
                           out (with-redefs-fn {#'driver/dispatch! (fake-dispatch green-writes {})
                                                #'rules/load-rules (fn [& _] rs)}
                                 #(with-out-str (orch/run-task! rd {:repl-probe (constantly true) :triage-fn a-person})))]
                       (teardown! rd)
                       out))]
    (testing "standing placeholders are named before anything is provisioned"
      (let [out (start-with standing)]
        (is (str/includes? out "the rule source still has 3 placeholders"))
        (is (str/includes? out ":fixture-two"))
        (is (< (str/index-of out "the rule source still has") (str/index-of out "provisioned"))
            "before anything is provisioned, so before anyone is dispatched")))
    (testing "a rule source an adopter has filled draws no line at all"
      (is (not (str/includes? (start-with filled) "the rule source still has"))))))
