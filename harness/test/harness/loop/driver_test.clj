(ns harness.loop.driver-test
  "The driver's pure half, plus the commands that need only a directory.
  Provisioning worktrees and calling models is `orchestrate_test`'s business
  and the runs'; what is tested here is everything a run's record and its
  retries depend on being right."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.packet]
   [harness.contract.shapes :as shapes]
   [harness.contract.sigs]
   [harness.contract.stub]
   [harness.gates.repair]
   [harness.gates.run :as gates]
   [harness.loop.driver :as loop]
   [harness.loop.log :as log]
   [harness.loop.provision]
   [harness.models.profile :as profile]
   [harness.money.report :as report]
   [harness.setup.workspace :as workspace]))

(deftest config-fills-defaults-and-refuses-to-guess
  (let [run-dir (str (fs/create-temp-dir))
        cfg (loop/resolve-config run-dir "/repo" {:run/id "d10" :profile "resources/profiles/claude.edn"
                                                  :architecture {:from "arch" :files ["layers.edn"]}})]
    (testing "the defaults"
      (is (= gates/default-gate-seq (:gates cfg)))
      (is (= ["clojure" "-Srepro" "-M:nrepl"] (:nrepl/cmd cfg))
          "-Srepro: a personal alias of the same name must not leak into a worktree's REPL")
      (is (= "/repo" (:repo/root cfg))))
    (testing "the architecture resolves against the run directory, the profile against the working directory"
      (is (= (str (fs/absolutize (fs/path run-dir "arch"))) (get-in cfg [:architecture :from])))
      (is (= (str (fs/absolutize "resources/profiles/claude.edn")) (:profile cfg))))
    (testing "in a workspace with a plan, the profile resolves against the PLAN - where bb init put it"
      (let [ws {:workspace/dir "/ws" :workspace/app "/ws/xyx-app" :workspace/build "/ws/xyx-build" :workspace/work "/ws/work"}]
        (is (= "/ws/xyx-build/profile.edn"
               (:profile (loop/resolve-config run-dir "/repo" {:run/id "r" :profile "profile.edn"} ws))))
        (is (= "/elsewhere/p.edn"
               (:profile (loop/resolve-config run-dir "/repo" {:run/id "r" :profile "/elsewhere/p.edn"} ws)))
            "an absolute path is taken as given")
        (is (= (str (fs/absolutize "resources/profiles/claude.edn"))
               (:profile (loop/resolve-config run-dir "/repo" {:run/id "r" :profile "resources/profiles/claude.edn"}
                                              (dissoc ws :workspace/build))))
            "a workspace without a plan (the health check's selfcheck) resolves as outside one")))
    (testing "a missing run id or profile is refused, not invented"
      (is (thrown-with-msg? Exception #":run/id" (loop/resolve-config run-dir "/repo" {:profile "p"})))
      (is (thrown-with-msg? Exception #":profile" (loop/resolve-config run-dir "/repo" {:run/id "x"}))))))

(deftest step-names-say-how-many-times-a-role-came-back
  (is (= :coder-r1 (loop/retry-step-name :coder 2)))
  (is (= :tester-r2 (loop/retry-step-name :tester 3)))
  (is (= :reviewer (loop/reviewer-step-name 0)))
  (is (= :reviewer-r1 (loop/reviewer-step-name 1))))

(deftest an-attempt-is-numbered-per-role-and-capped-per-task
  (testing "the number in a step name is the role's own"
    (is (= 2 (loop/next-attempt {:coder 1 :tester 1} :coder)))
    (is (= 3 (loop/next-attempt {:coder 2} :coder))))
  (testing "the cap is spent on ROUNDS, which a Coder retry and a Tester retry share"
    ;; A per-role cap of 3 dispatches one task six times before anything
    ;; escalates, which is not a cap anyone chose.
    (is (= 1 (loop/rounds {:events []})))
    (is (= 2 (loop/rounds {:events [{:event/kind :triage :role :coder}]})))
    (is (= 3 (loop/rounds {:events [{:event/kind :triage :role :coder}
                                    {:event/kind :dispatch}
                                    {:event/kind :triage :role :tester}]}))
        "one round per triage decision, whichever role it routed to"))
  (testing "the cap comes from the spec, and defaults"
    (is (= 3 (loop/retry-cap {})))
    (is (= 5 (loop/retry-cap {:gates {:retry-cap 5}})))))

(deftest a-dispatch-event-keeps-what-a-document-will-quote
  (let [pkt {:task/attempt 2 :task/feedback [{:feedback/from :triage :feedback/text "why"}]}
        result {:status :done :files ["src/a.clj"] :stdout "summary" :notes ["a note"] :cost 0.01
                :runner/meta {:model "m" :provider "OpenAI" :service-tier "flex" :iterations 7
                              :capped? false :tool-calls 8 :ms-completion 900 :ms-provenance 50
                              :ms-provenance-wait 10 :ms-tools 3 :reasoning-tokens 12 :completions 7
                              :generation-ids ["g"]}}
        e (loop/dispatch-event :coder-r1 :coder pkt result)]
    (is (= ["a note"] (:notes e)) "notes are the return channel")
    (is (= [:triage] (mapv :feedback/from (:feedback e))) "and what the retry was told")
    (is (= 900 (get-in e [:timing :ms-completion])) "the timing split, for where the time went")
    (is (= "flex" (:service-tier e)))
    (is (= 2 (:attempt e)))
    (is (not (contains? (:timing e) :generation-ids)) "timing only, not every meta key")
    (is (not (contains? e :transcript)) "no transcript, no key — older state replays unchanged")
    (is (not (some #{:cost-source :usage :pricing :retries} (keys e))) "nor the cost provenance, nor retries when there were none"))
  (testing "a request sent again is on the event"
    (is (= 2 (:retries (loop/dispatch-event :coder :coder {} {:status :done :runner/meta {:retries 2}})))))
  (testing "a list-price cost travels with its counts and rates"
    (let [e (loop/dispatch-event :coder :coder {} {:status :done :cost 0.1
                                                   :runner/meta {:cost-source :list-price :usage {:in 1 :out 2}
                                                                 :pricing {:per-mtok {:in 10} :source "s" :as-of "d"}}})]
      (is (= :list-price (:cost-source e)))
      (is (= {:in 1 :out 2} (:usage e)))
      (is (= "d" (get-in e [:pricing :as-of])))))
  (testing "the usage is kept when the cost is not there yet: it is the completion's, not the cost's"
    (let [e (loop/dispatch-event :coder :coder {} {:status :done :cost nil
                                                   :runner/meta {:model "m" :usage {:in 5 :out 7}}})]
      (is (= {:in 5 :out 7} (:usage e)))
      (is (not (contains? e :cost-source))))))

(deftest the-record-keeps-a-capped-transcript-and-the-run-directory-the-whole-one
  ;; Tool results run to 20,000 chars and write_file args hold whole files; the record keeps enough to see what happened, in order.
  (let [long-s (apply str (repeat 1200 "x"))
        turns [{:text "reading" :calls [{:name "read_file" :args {:path "a.clj"} :content long-s :ms 3}]}
               {:text "done" :calls []}]
        capped (loop/cap-transcript turns)]
    (is (= "reading" (:text (first capped))) "short strings are left alone")
    (is (= (apply str (repeat 500 "y")) (:text (first (loop/cap-transcript [{:text (apply str (repeat 500 "y")) :calls []}]))))
        "exactly the cap is not cut")
    (is (= (str (apply str (repeat 500 "x")) "…[700 more chars]")
           (:content (first (:calls (first capped))))))
    (is (= {:path "a.clj"} (:args (first (:calls (first capped))))))
    (testing "the event carries the capped copy"
      (let [e (loop/dispatch-event :coder :coder {} {:status :done :runner/meta {:transcript turns}})]
        (is (= capped (:transcript e)))))))

(deftest the-record-ends-at-the-last-dispatch-not-at-the-write-up
  ;; An early run printed a wall time below the sum of its own steps; the clock
  ;; stops at the run's last dispatch or gate, not at `record`.
  (let [state {:config {:run/id "d10"} :spec {:task/id "t-12"}
               :started-ms 1757000000000
               :attempts {:coder 2 :tester 1}
               :last-gates {:gates/passed? true}
               :steps [(report/synthetic {:step/name :coder :step/kind :dispatch :step/status :done :step/ms 5})]
               :events [{:event/kind :dispatch :event/at-ms 1000}
                        {:event/kind :gates :event/at-ms 1500}
                        {:event/kind :triage :event/at-ms 2000}
                        {:event/kind :dispatch :event/at-ms 3000}
                        {:event/kind :mutation :event/at-ms 90000}]}
        r (loop/run-record state)]
    (is (= 3000 (:run/wall-ms r)) "the mutation check afterwards is not part of the run")
    (is (= 2 (:run/attempts r)))
    (is (= :awaiting-merge (:run/status r)) "green and reviewed, waiting on a person to merge")
    (is (= "d10" (:run/id r)))
    (is (= "t-12" (:task/id r)))
    (is (= #inst "2025-09-04T15:33:20.000-00:00" (:run/started-at r)) "the clock the wall time is measured from")
    (is (= :escalated (:run/status (loop/run-record (assoc state :last-gates {:gates/passed? false})))))
    (testing "and the loop's own stop wins over the gates"
      (is (= :escalated (:run/status (loop/run-record
                                      (update state :events conj
                                              {:event/kind :stopped :run/status :escalated
                                               :stop/kind :empty-diff}))))
          "green gates over no change at all is not a run waiting to be merged"))
    (testing "the record validates against shapes/TaskRun, which nothing used to call"
      (is (nil? (shapes/explain-run r))))
    (testing "the files the run produced go in the record, and no key when there are none"
      (is (= {"src/a.clj" "(ns a)"}
             (:run/files (loop/run-record (assoc state :final-files {"src/a.clj" "(ns a)"})))))
      (is (not (contains? (loop/run-record state) :run/files)))
      (is (not (contains? (loop/run-record (assoc state :final-files {})) :run/files))))))

(deftest a-red-gate-keeps-its-output-in-the-record
  ;; A claim audit found a record that said the test gate had failed, with the
  ;; token it failed on only in a gitignored file.
  (let [red {:gates/passed? false :gates/failed :test
             :gates/report [{:gate :fmt :status :pass :ms 1 :out ""}
                            {:gate :test :status :fail :ms 2 :out "ERROR in (round-trips) {:token \"(z\"}"}]}
        green {:gates/passed? true :gates/failed nil
               :gates/report [{:gate :test :status :pass :ms 2 :out "Ran 3 tests"}]}]
    (is (= [{:feedback/from :gate :feedback/text "test failed:\nERROR in (round-trips) {:token \"(z\"}"}]
           (:feedback (loop/gates-event red)))
        "the failing gate's output, not the passing ones'")
    (is (= {:event/kind :gates :passed? true :failed nil} (loop/gates-event green))
        "a green run's event is as it always was, so older records replay unchanged")
    (testing "check! stops at the calls check, before any shell gate, when the implementation strays"
      ;; A Coder once called a var its slice never granted.
      (let [run-dir (fs/create-temp-dir)
            ran-gates (atom 0)
            ctx {:run-dir (str run-dir)
                 :state (atom {:started-ms (System/currentTimeMillis) :steps [] :events []
                               :config {:gates []}
                               :sessions {:gate {:worktree/path (str (fs/create-temp-dir))}}})}
            stray [{:call 'a.store/variadic :arity 2 :file "src/a/impl.clj" :line 4
                    :violation :undeclared-call :detail "a.store/variadic is called and no :deps-sigs entry names it"}]]
        (spit (str (fs/path run-dir "spec.edn")) (pr-str {:task/id "t" :files/impl [] :files/test []}))
        (with-out-str
          (with-redefs-fn {#'harness.loop.provision/assemble! (fn [& _] {})
                           #'harness.gates.repair/repair! (fn [& _] {:exit 0})
                           #'harness.contract.sigs/undeclared-calls (fn [& _] stray)
                           #'harness.gates.run/run-gates! (fn [& _] (swap! ran-gates inc) green)}
            #(loop/check! ctx)))
        (let [st @(:state ctx)]
          (is (zero? @ran-gates) "cheap before expensive: no shell gate ran")
          (is (= :calls (get-in st [:last-gates :gates/failed])))
          (is (= :fail (:step/status (first (filter #(= :calls (:step/name %)) (:steps st))))))
          (is (= stray (:violations (first (filter #(= :calls (:event/kind %)) (:events st))))))
          (is (re-find #"a\.store/variadic" (-> (filter #(= :gates (:event/kind %)) (:events st)) first :feedback first :feedback/text))
              "and the Coder's feedback names the call"))))
    (testing "check! records that event"
      (let [run-dir (fs/create-temp-dir)
            ctx {:run-dir (str run-dir)
                 :state (atom {:started-ms (System/currentTimeMillis) :steps [] :events []
                               :config {:gates []}
                               :sessions {:gate {:worktree/path (str (fs/create-temp-dir))}}})}]
        (spit (str (fs/path run-dir "spec.edn")) (pr-str {:task/id "t" :files/impl [] :files/test []}))
        (with-out-str
          (with-redefs-fn {#'harness.loop.provision/assemble! (fn [& _] {})
                           #'harness.gates.repair/repair! (fn [& _] {:exit 0})
                           #'harness.gates.run/run-gates! (fn [& _] red)}
            #(loop/check! ctx)))
        (is (= "test failed:\nERROR in (round-trips) {:token \"(z\"}"
               (-> (filter #(= :gates (:event/kind %)) (:events @(:state ctx))) first :feedback first :feedback/text)))))))

(deftest the-record-reads-final-files-so-a-replay-after-teardown-matches
  (let [dir (fs/create-temp-dir)
        spec {:files/impl ["src/sandbox/render.clj"] :files/test ["test/sandbox/render_test.clj" "test/sandbox/gone_test.clj"]}]
    (spit (str (fs/path dir "render.clj")) "(ns sandbox.render)")
    (spit (str (fs/path dir "render_test.clj")) "(ns sandbox.render-test)")
    (is (= {"src/sandbox/render.clj" "(ns sandbox.render)"
            "test/sandbox/render_test.clj" "(ns sandbox.render-test)"}
           (loop/final-files spec dir))
        "keyed by the spec's path; a file never written is left out")
    (testing "record! with no worktrees left reads final/ into run.edn"
      (let [run-dir (fs/create-temp-dir)
            ctx {:run-dir (str run-dir)
                 :state (atom {:config {:run/id "r"} :attempts {:coder 1} :steps [] :events []
                               :started-ms (System/currentTimeMillis)})}]
        (spit (str (fs/path run-dir "spec.edn")) (pr-str (assoc spec :task/id "t")))
        (fs/create-dirs (fs/path run-dir "final"))
        (fs/copy (fs/path dir "render.clj") (fs/path run-dir "final" "render.clj"))
        (with-out-str (loop/record! ctx))
        (is (= {"src/sandbox/render.clj" "(ns sandbox.render)"}
               (:run/files (clojure.edn/read-string (slurp (str (fs/path run-dir "run.edn")))))))))
    (testing "two paths with one file name are refused, not recorded as one"
      (is (thrown-with-msg? Exception #"core.clj"
                            (loop/final-files {:files/impl ["src/a/core.clj" "src/b/core.clj"]} dir))))))

;; ---------------------------------------------------------------------------
;; the record holds the bytes the gates passed
;; ---------------------------------------------------------------------------

(defn- put! [dir path content]
  (let [f (fs/path dir path)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) content)))

(deftest final-files-are-read-by-path-and-a-flat-final-still-reads
  (let [dir (fs/create-temp-dir)
        spec {:files/impl ["src/a/core.clj" "src/b/core.clj"]}]
    (put! dir "src/a/core.clj" "(ns a.core)")
    (put! dir "src/b/core.clj" "(ns b.core)")
    (is (= {"src/a/core.clj" "(ns a.core)" "src/b/core.clj" "(ns b.core)"}
           (loop/final-files spec dir))
        "two paths with one file name no longer collide")
    (testing "a final/ written by file name, before the fix, still reads"
      (let [flat (fs/create-temp-dir)]
        (put! flat "render.clj" "(ns sandbox.render)")
        (is (= {"src/sandbox/render.clj" "(ns sandbox.render)"}
               (loop/final-files {:files/impl ["src/sandbox/render.clj"]} flat)))))
    (testing "by path wins over a flat file of the same name"
      (let [both (fs/create-temp-dir)]
        (put! both "render.clj" "old")
        (put! both "src/sandbox/render.clj" "new")
        (is (= {"src/sandbox/render.clj" "new"}
               (loop/final-files {:files/impl ["src/sandbox/render.clj"]} both)))))
    (testing "as-written is its own map, and the record gets the key only when gate 0 changed something"
      (put! dir "as-written/src/b/core.clj" "(ns b.core )")
      (is (= {"src/b/core.clj" "(ns b.core )"} (loop/files-as-written spec dir)))
      (is (= {"src/b/core.clj" "x"}
             (:run/files-as-written (loop/run-record {:started-ms 0 :files-as-written {"src/b/core.clj" "x"}}))))
      (is (not (contains? (loop/run-record {:started-ms 0 :files-as-written {}}) :run/files-as-written))))))

(deftest the-gate-worktree-is-current-only-after-a-gate-run-that-followed-every-role
  (is (true? (loop/gated-current? [{:event/kind :dispatch :role :coder}
                                   {:event/kind :dispatch :role :tester}
                                   {:event/kind :gates}])))
  (is (true? (loop/gated-current? [{:event/kind :gates} {:event/kind :dispatch :role :reviewer}]))
      "the Reviewer writes nothing")
  (is (false? (loop/gated-current? [{:event/kind :gates} {:event/kind :triage}
                                    {:event/kind :dispatch :role :tester}]))
      "a retry after the last gate run has not been gated")
  (is (false? (loop/gated-current? [{:event/kind :gates} {:event/kind :assemble-refused}])))
  (is (false? (loop/gated-current? [{:event/kind :dispatch :role :coder}])) "never gated"))

;; ---------------------------------------------------------------------------
;; the merge, as far as it is pure — `orchestrate_test` does the git
;; ---------------------------------------------------------------------------

(def ^:private awaiting
  "The events of a run the loop stopped for the merge."
  [{:event/kind :dispatch :role :coder :status :done}
   {:event/kind :dispatch :role :tester :status :done}
   {:event/kind :gates :passed? true}
   {:event/kind :dispatch :role :reviewer :status :done :verdict {:verdict :approve :reasons []}}
   {:event/kind :stopped :stop/kind :reviewed :run/status :awaiting-merge}])

(deftest a-reviewers-dispatch-event-carries-its-verdict-nil-included
  (let [v {:verdict :reject :reasons ["breaks target 2"]}]
    (is (= v (:verdict (loop/dispatch-event :reviewer :reviewer {} {:status :done :runner/meta {:verdict v}}))))
    (let [e (loop/dispatch-event :reviewer :reviewer {} {:status :done :runner/meta {}})]
      (is (contains? e :verdict) "no verdict is a fact the loop stops on, so the key is there")
      (is (nil? (:verdict e))))
    (is (not (contains? (loop/dispatch-event :coder :coder {} {:status :done :runner/meta {}}) :verdict)))))

(deftest a-run-awaits-a-merge-only-while-nothing-has-moved-since-the-loop-said-so
  (is (true? (loop/awaiting-merge? awaiting)))
  (is (true? (loop/awaiting-merge? (conj awaiting {:event/kind :mutation})))
      "a mutation check recorded before deciding changes nothing that would merge")
  (is (true? (loop/awaiting-merge? (conj awaiting {:event/kind :merge-commit} {:event/kind :merge-failed})))
      "nor does a merge that git refused: the command can be run again")
  (is (false? (loop/awaiting-merge? (conj awaiting {:event/kind :triage} {:event/kind :dispatch :role :coder})))
      "a retry after the approval was approved as something else")
  (is (false? (loop/awaiting-merge? (conj awaiting {:event/kind :amend}))))
  (is (false? (loop/awaiting-merge? (conj (pop awaiting) {:event/kind :stopped :run/status :escalated})))
      "any other stop")
  (is (false? (loop/awaiting-merge? (pop awaiting)))
      "and a run driven by hand, with no stop at all: `run` is what reads the verdict")
  (is (false? (loop/awaiting-merge? []))))

(deftest merge-is-refused-unless-the-loop-stopped-for-it-gated-current-with-a-decision
  (let [refusal (fn [events decision] (:run-loop/error (loop/merge-refusal {:events events} decision)))]
    (is (nil? (loop/merge-refusal {:events awaiting} "the slice is met")))
    (testing "no decision"
      (is (= :no-decision (refusal awaiting nil)))
      (is (= :no-decision (refusal awaiting "  "))))
    (testing "not stopped for the merge"
      (is (= :not-awaiting-merge (refusal (pop awaiting) "d")))
      (is (= :not-awaiting-merge (refusal (conj awaiting {:event/kind :amend}) "d"))))
    (testing "not gated-current: the last stop says awaiting, and the events under it do not bear that out"
      (is (= :not-gated-current
             (refusal [{:event/kind :gates :passed? true}
                       {:event/kind :dispatch :role :tester :status :done}
                       {:event/kind :stopped :run/status :awaiting-merge}]
                      "d"))))
    (testing "already merged, before anything else"
      (is (= :already-merged (refusal (conj awaiting {:event/kind :merged}) nil))))
    (testing "every refusal says what to do, not only what is wrong"
      (is (re-find #"\{:decision" (:message (loop/merge-refusal {:events awaiting} nil))))
      (is (re-find #"bb run-loop run" (:message (loop/merge-refusal {:events []} "d")))))))

(deftest a-merged-run-says-merged-whatever-its-last-stop-said
  (let [state {:config {:run/id "a"} :spec {:task/id "t"} :started-ms 0 :steps []
               :events (conj awaiting {:event/kind :merge-commit} {:event/kind :merged})}]
    (is (= :merged (:run/status (loop/run-record state))))
    (is (= :awaiting-merge (:run/status (loop/run-record (update state :events #(vec (drop-last 2 %)))))))))

(deftest a-diffs-header-is-dropped-and-its-hunks-kept
  (is (= "@@ -80,2 +80,2 @@\n-  a\n+   a"
         (loop/diff-hunks "diff --git a/tmp/x b/tmp/y\nindex 1..2 100644\n--- a/tmp/x\n+++ b/tmp/y\n@@ -80,2 +80,2 @@\n-  a\n+   a")))
  (is (= "" (loop/diff-hunks ""))))

(defn- record-ctx
  "A ctx whose `events.log` agrees with its state — what `event!` would have
  left. `record!` refuses when they do not, which is its own test below."
  [run-dir sessions events spec]
  (spit (str (fs/path run-dir "spec.edn")) (pr-str (assoc spec :task/id "t")))
  (let [ctx {:run-dir (str run-dir)
             :state (atom {:config {:run/id "r"} :attempts {:coder 1} :steps [] :events events
                           :started-ms (System/currentTimeMillis) :sessions sessions})}]
    (doseq [e events] (log/append! (loop/log-file ctx) e))
    ctx))

(deftest record-refuses-when-the-state-and-the-append-only-log-disagree
  ;; state.edn is rewritten on every command; events.log is only appended to.
  ;; They differ only if one of them lost something, and a record written from
  ;; whichever was read first is a run history nobody can check.
  (let [spec {:files/impl [] :files/test []}
        run-dir (fs/create-temp-dir)
        ctx (record-ctx run-dir {} [{:event/kind :dispatch :role :coder}] spec)]
    (testing "agreeing, it records"
      (is (some? (with-out-str (loop/record! ctx)))))
    (testing "one event in the state that never reached the log is refused, by count"
      (swap! (:state ctx) update :events conj {:event/kind :gates})
      (is (thrown-with-msg? Exception #"3 events in the state, 2 in the log"
                            (loop/record! ctx))))
    (testing "and so is the same number of events in a different order"
      (let [d (fs/create-temp-dir)
            c (record-ctx d {} [{:event/kind :dispatch} {:event/kind :gates}] spec)]
        (swap! (:state c) assoc :events [{:event/kind :gates} {:event/kind :dispatch}])
        (is (thrown-with-msg? Exception #"disagree" (loop/record! c)))))))

(deftest record-copies-the-gated-bytes-and-keeps-what-gate-0-changed
  (let [spec {:files/impl ["src/sandbox/a.clj"] :files/test ["test/sandbox/a_test.clj"]}
        coder (fs/create-temp-dir) tester (fs/create-temp-dir) gate (fs/create-temp-dir)
        sessions {:coder {:worktree/path (str coder)} :tester {:worktree/path (str tester)}
                  :gate {:worktree/path (str gate)}}
        gated-events [{:event/kind :dispatch :role :coder} {:event/kind :dispatch :role :tester}
                      {:event/kind :gates}]
        run (fn [events]
              (let [run-dir (fs/create-temp-dir)
                    ctx (record-ctx run-dir sessions events spec)]
                (with-out-str (loop/record! ctx))
                [run-dir (clojure.edn/read-string (slurp (str (fs/path run-dir "run.edn"))))]))]
    (put! coder "src/sandbox/a.clj" "(ns sandbox.a)\n")
    (put! gate "src/sandbox/a.clj" "(ns sandbox.a)\n")
    (put! tester "test/sandbox/a_test.clj" "(ns sandbox.a-test)\n  (def x 1)\n")
    (put! gate "test/sandbox/a_test.clj" "(ns sandbox.a-test)\n(def x 1)\n")
    (testing "gated: the record holds what the gates passed, and the written bytes where they differ"
      (let [[run-dir r] (run gated-events)]
        (is (= {"src/sandbox/a.clj" "(ns sandbox.a)\n"
                "test/sandbox/a_test.clj" "(ns sandbox.a-test)\n(def x 1)\n"}
               (:run/files r)))
        (is (= {"test/sandbox/a_test.clj" "(ns sandbox.a-test)\n  (def x 1)\n"}
               (:run/files-as-written r))
            "only the file gate 0 changed")
        (is (fs/exists? (fs/path run-dir "final" "test" "sandbox" "a_test.clj")) "by path")))
    (testing "not gated since the last dispatch: the written bytes, and nothing as-written"
      (let [[_ r] (run (conj gated-events {:event/kind :dispatch :role :tester}))]
        (is (= "(ns sandbox.a-test)\n  (def x 1)\n" (get (:run/files r) "test/sandbox/a_test.clj")))
        (is (not (contains? r :run/files-as-written)))))
    (testing "a record taken again after the files agree drops a stale as-written copy"
      (let [run-dir (fs/create-temp-dir)
            ctx (record-ctx run-dir sessions gated-events spec)]
        (with-out-str (loop/record! ctx))
        (put! tester "test/sandbox/a_test.clj" "(ns sandbox.a-test)\n(def x 1)\n")
        (with-out-str (loop/record! ctx))
        (is (not (fs/exists? (fs/path run-dir "final" "as-written" "test" "sandbox" "a_test.clj"))))
        (is (not (contains? (clojure.edn/read-string (slurp (str (fs/path run-dir "run.edn"))))
                            :run/files-as-written)))))))

(deftest check-records-what-gate-0-changed-and-nothing-when-it-changed-nothing
  (let [spec {:task/id "t" :files/impl ["src/a/impl.clj"] :files/test []}
        ;; red, so check! stops at its routing proposal and builds no Reviewer packet
        red {:gates/passed? false :gates/failed :test
             :gates/report [{:gate :test :status :fail :ms 1 :out "FAIL in (x)"}]}
        run (fn [repair]
              (let [run-dir (fs/create-temp-dir)
                    gate (fs/create-temp-dir)
                    ctx {:run-dir (str run-dir)
                         :state (atom {:started-ms (System/currentTimeMillis) :steps [] :events []
                                       :config {:gates []}
                                       :sessions {:gate {:worktree/path (str gate)}}})}]
                (put! gate "src/a/impl.clj" "(ns a.impl)\n  (def x 1)\n")
                (spit (str (fs/path run-dir "spec.edn")) (pr-str spec))
                (with-out-str
                  (with-redefs-fn {#'harness.loop.provision/assemble! (fn [& _] {})
                                   #'harness.gates.repair/repair! (fn [dir _] (repair dir) {:exit 0})
                                   #'harness.contract.sigs/undeclared-calls (fn [& _] [])
                                   #'harness.gates.run/run-gates! (fn [& _] red)}
                    #(loop/check! ctx)))
                (filter #(= :gate-0 (:event/kind %)) (:events @(:state ctx)))))]
    (let [[e & more] (run (fn [dir] (put! dir "src/a/impl.clj" "(ns a.impl)\n(def x 1)\n")))]
      (is (nil? more))
      (is (= ["src/a/impl.clj"] (keys (:changed e))))
      (is (re-find #"(?m)^-  \(def x 1\)$" (get (:changed e) "src/a/impl.clj")))
      (is (re-find #"(?m)^\+\(def x 1\)$" (get (:changed e) "src/a/impl.clj")))
      (is (not (re-find #"/tmp|/var/|/private/" (get (:changed e) "src/a/impl.clj")))
          "no temporary path in the record"))
    (is (empty? (run (fn [_] nil))) "a gate 0 that changed nothing leaves no event, so older records replay unchanged")))

(deftest a-dispatch-that-changed-nothing-is-said-out-loud
  (is (true? (loop/wrote-nothing? :tester {:status :done :files []})))
  (is (false? (loop/wrote-nothing? :tester {:status :done :files ["test/a_test.clj"]})))
  (is (false? (loop/wrote-nothing? :reviewer {:status :done :files []})) "the Reviewer writes nothing by design")
  (is (false? (loop/wrote-nothing? :coder {:status :failed :files []})) "a failed dispatch already says so"))

(deftest a-dispatch-that-left-notes-pauses-the-run
  (is (true? (loop/pause? {} {:notes ["the contract breaks property_test.clj"]})))
  (is (false? (loop/pause? {} {:notes []})) "no notes, no pause")
  (is (false? (loop/pause? {} {})))
  (is (false? (loop/pause? {:pause-on-notes? false} {:notes ["n"]})) "switched off in loop.edn")
  (is (true? (:pause-on-notes? (loop/resolve-config (str (fs/create-temp-dir)) "/r" {:run/id "x" :profile "p"})))
      "on by default")
  (is (= :tester (loop/next-step :coder)))
  (is (= :check (loop/next-step :tester))))

(defn- flow
  "Drive 'start`'s dispatch sequence with nothing real behind it: `results` maps a
  role to the result its dispatch returns. Returns what happened."
  [cfg results f]
  (let [dispatched (atom []) checked (atom 0)
        ctx {:run-dir (str (fs/create-temp-dir))
             :state (atom {:started-ms (System/currentTimeMillis) :steps [] :events [] :config cfg})}]
    (with-redefs-fn {#'harness.loop.driver/dispatch! (fn [_ _ role _] (swap! dispatched conj role) (results role))
                     #'harness.loop.driver/first-packet (fn [_ role] {:task/role role})
                     #'harness.loop.driver/check! (fn [_] (swap! checked inc))}
      #(f ctx))
    {:dispatched @dispatched :checked @checked :state @(:state ctx)}))

(deftest the-run-stops-where-a-note-was-left-and-continues-on-request
  ;; A Coder's note once predicted the red gate exactly, and the loop
  ;; dispatched the Tester and ran the gates anyway.
  (let [noted {:coder {:notes ["the contract breaks property_test.clj"]} :tester {}}
        proceed @#'harness.loop.driver/proceed!]
    (testing "a Coder note stops the run before the Tester"
      (let [r (flow {} noted #(proceed % :coder (noted :coder)))]
        (is (= [] (:dispatched r)) "the Tester was not dispatched")
        (is (zero? (:checked r)) "and no gate ran")
        (is (= {:after :coder :next :tester} (get-in r [:state :paused])))
        (is (= ["the contract breaks property_test.clj"]
               (:notes (last (filter #(= :paused (:event/kind %)) (get-in r [:state :events]))))))))
    (testing "continue carries on from the pause to the Tester and then the gates"
      (let [decision (str (fs/create-temp-file))
            _ (spit decision (pr-str {:decision "an observation, not a conflict"}))
            r (flow {} noted (fn [ctx]
                               ((var-get #'harness.loop.driver/proceed!) ctx :coder (noted :coder))
                               (loop/continue! ctx decision)))]
        (is (= [:tester] (:dispatched r)))
        (is (= 1 (:checked r)))
        (is (nil? (get-in r [:state :paused])) "the pause is cleared")
        (is (= "an observation, not a conflict"
               (:decision (first (filter #(= :continued (:event/kind %)) (get-in r [:state :events])))))
            "and the reason is on the event")))
    (testing "continue with nothing decided since the pause, and no reason, is refused"
      ;; A run continued on an observation, and the record never said why.
      (let [r (flow {} noted (fn [ctx]
                               ((var-get #'harness.loop.driver/proceed!) ctx :coder (noted :coder))
                               (try (loop/continue! ctx) nil
                                    (catch clojure.lang.ExceptionInfo e
                                      (swap! (:state ctx) assoc :refused (ex-data e))))))]
        (is (= :no-decision (get-in r [:state :refused :run-loop/error])))
        (is (= [] (:dispatched r)) "nothing was dispatched")
        (is (= {:after :coder :next :tester} (get-in r [:state :paused])) "and the pause stands")))
    (testing "a decision file with no :decision is refused too"
      (let [blank (str (fs/create-temp-file))
            _ (spit blank (pr-str {:decision "  "}))]
        (is (thrown-with-msg? Exception #"no :decision"
                              (flow {} noted (fn [ctx]
                                               ((var-get #'harness.loop.driver/proceed!) ctx :coder (noted :coder))
                                               (loop/continue! ctx blank)))))))
    (testing "an amend or a retry after the pause is its own reason, and continue needs no file"
      (let [r (flow {} noted (fn [ctx]
                               ((var-get #'harness.loop.driver/proceed!) ctx :coder (noted :coder))
                               (swap! (:state ctx) update :events conj {:event/kind :amend :reason "why"})
                               (loop/continue! ctx)))]
        (is (= [:tester] (:dispatched r)))
        (is (not (contains? (first (filter #(= :continued (:event/kind %)) (get-in r [:state :events])))
                            :decision))
            "the event is as it was, so an older record replays unchanged"))
      (is (true? (loop/decided-since-pause? [{:event/kind :paused} {:event/kind :triage}])))
      (is (false? (loop/decided-since-pause? [{:event/kind :amend} {:event/kind :paused}]))
          "an amend before the pause decided an earlier pause, not this one")
      (is (false? (loop/decided-since-pause? [{:event/kind :paused} {:event/kind :dispatch}]))))
    (testing "the command line hands continue its decision file"
      ;; The path a person actually types: a mutant dropping the argument in
      ;; -main survived every test above.
      (let [got (atom nil)]
        (with-redefs-fn {#'harness.loop.driver/context (fn [_] {:state (atom {:paused {:after :coder :next :tester}})})
                         #'harness.loop.driver/continue! (fn [_ & args] (reset! got (vec args)))}
          #(loop/-main "continue" "run-dir" "decision.edn"))
        (is (= ["decision.edn"] @got))))
    (testing "a Tester note stops the run before the gates"
      (let [r (flow {} {:coder {} :tester {:notes ["a target contradicts another"]}}
                    #(proceed % :coder {}))]
        (is (= [:tester] (:dispatched r)))
        (is (zero? (:checked r)))
        (is (= {:after :tester :next :check} (get-in r [:state :paused])))))
    (testing "switched off, notes do not stop anything"
      (let [r (flow {:pause-on-notes? false} noted #(proceed % :coder (noted :coder)))]
        (is (= [:tester] (:dispatched r)))
        (is (= 1 (:checked r)))))
    (testing "continue on a run that is not paused is refused"
      (is (thrown-with-msg? Exception #"not paused"
                            (flow {} noted loop/continue!))))
    (testing "continue after a failed retry is refused, not built on"
      ;; A Coder retry once died on an API error and `continue` dispatched the
      ;; Tester against the file the retry never rewrote.
      (let [failed {:event/kind :dispatch :event/step :coder-r1 :status :failed}
            r (flow {} noted (fn [ctx]
                               (swap! (:state ctx) assoc :paused {:after :coder :next :tester})
                               (swap! (:state ctx) update :events conj failed)
                               (try (loop/continue! ctx) nil
                                    (catch clojure.lang.ExceptionInfo e
                                      (swap! (:state ctx) assoc :refused (ex-data e))))))]
        (is (= [] (:dispatched r)) "the Tester was not dispatched")
        (is (= {:run-loop/error :last-dispatch-failed :step :coder-r1} (get-in r [:state :refused])))
        (is (= {:after :coder :next :tester} (get-in r [:state :paused])) "and the pause stands"))
      (is (nil? (loop/last-dispatch-failed {:events [{:event/kind :dispatch :status :failed}
                                                     {:event/kind :dispatch :status :done}]}))
          "a later dispatch that succeeded clears it"))))

(defn- start-run
  "Run 'start!` with nothing real behind it: provisioning, the signature check, the
  stub and every dispatch are replaced. `overrides` replaces any of those further.
  Returns the context and a log of what was called."
  ([spec] (start-run spec {}))
  ([spec overrides]
   (let [run-dir (str (fs/create-temp-dir))
         calls (atom [])
         _ (spit (str (fs/path run-dir "spec.edn")) (pr-str spec))
         cfg {:run/id "t" :profile "resources/profiles/claude.edn" :repo/root "/r"
              :worktrees/dir (str (fs/create-temp-dir)) :project/subdir nil
              :nrepl/cmd ["x"] :gates [] :pause-on-notes? true
              ;; the loop reviews a spec with a model before start; not here
              :spec-review? false}
         ctx {:run-dir run-dir :config cfg :state (atom nil)}
         fakes (merge {#'harness.loop.provision/provision!
                       (fn [o] (swap! calls conj [:provision (:task/role o)])
                         {:task/id (:task/id o) :task/role (:task/role o)
                          :worktree/path (str (fs/create-temp-dir)) :worktree/git-root "/g"})
                       #'harness.contract.sigs/task-dependents
                       (fn [_ sp] (swap! calls conj [:task-dependents (:files/test sp)]) ["test/p_test.clj"])
                       #'harness.contract.sigs/violations (fn [& _] [])
                       #'harness.contract.stub/write! (fn [_ impl _] (first impl))
                       ;; packets are not what these tests are about, and a real one
                       ;; would refuse the fake sessions (no nREPL port)
                       #'harness.loop.driver/first-packet (fn [_ role] {:task/role role})
                       #'harness.loop.driver/dispatch! (fn [_ step _ _] (swap! calls conj [:dispatch step]) {})
                       #'harness.loop.driver/check! (fn [_] (swap! calls conj [:check]))}
                      overrides)
         result (try (with-redefs-fn fakes #(loop/start! ctx)) nil
                     (catch clojure.lang.ExceptionInfo e e))]
     {:ctx ctx :calls @calls :thrown result})))

(deftest start-shows-a-task-its-dependents-not-its-own-tests
  ;; Review R2, at the driver: the dependents come from task-dependents, which
  ;; is given the whole spec so it can leave out the task's own :files/test.
  (let [{:keys [ctx calls]} (start-run {:task/id "t-1" :files/impl ["src/a.clj"]
                                        :files/test ["test/a_test.clj"] :files/context []
                                        ;; a slice with nothing to deliver is refused before
                                        ;; provisioning now; these tests are about what follows
                                        :blueprint/slice {:shapes [] :interfaces '[(f [x])]}})]
    (is (some #{[:task-dependents ["test/a_test.clj"]]} calls))
    (is (= ["test/p_test.clj"] (:dependents @(:state ctx))))))

(deftest a-failed-start-can-be-torn-down-and-started-again
  ;; Review R4: sessions were saved only after all three worktrees existed, and a
  ;; run that dispatched nothing could not be restarted — state.edn refused
  ;; 'start`, and the kept branches refused `git worktree add -b`.
  (let [spec {:task/id "t-4" :files/impl ["src/a.clj"] :files/test ["test/a_test.clj"]
              :files/context [] :blueprint/slice {:shapes [] :interfaces '[(f [x])]}}
        third-fails {#'harness.loop.provision/provision!
                     (fn [o]
                       (when (= :reviewer (:task/role o))
                         (throw (ex-info "nREPL never came up" {:role :reviewer})))
                       {:task/id (:task/id o) :task/role (:task/role o)
                        :worktree/path (str (fs/create-temp-dir)) :worktree/git-root "/g"})}
        teardown-with (fn [ctx]
                        (let [torn (atom []) deleted (atom [])]
                          (with-redefs-fn {#'harness.loop.provision/teardown! (fn [s _] (swap! torn conj (:task/role s)) (assoc s :torn-down? true))
                                           #'harness.loop.driver/delete-branch! (fn [_ b] (swap! deleted conj b) true)}
                            #(loop/teardown! ctx))
                          {:torn @torn :deleted @deleted}))]
    (testing "a provision that throws leaves the sessions before it on disk"
      (let [{:keys [ctx thrown]} (start-run spec third-fails)
            on-disk (clojure.edn/read-string (slurp (str (fs/path (:run-dir ctx) "state.edn"))))]
        (is (some? thrown))
        (is (= #{:coder :tester} (set (keys (:sessions on-disk)))))
        (testing "and teardown removes them, their branches and state.edn"
          (let [{:keys [torn deleted]} (teardown-with ctx)]
            (is (= [:coder :tester] torn))
            (is (= ["t-coder" "t-tester" "t-reviewer"] deleted) "named by the run id \"t\", not the task's")
            (is (not (fs/exists? (fs/path (:run-dir ctx) "state.edn"))))
            (is (not (fs/exists? (fs/path (:run-dir ctx) "events.log")))
                "the log too: left behind, the next start appended to it and record refused the drift")
            (testing "so start is no longer refused"
              (is (nil? (:thrown (let [ctx2 (assoc ctx :state (atom nil))]
                                   {:thrown (try (with-redefs-fn {#'harness.loop.provision/provision! (fn [_] (throw (ex-info "stop here" {})))}
                                                   #(loop/start! ctx2))
                                                 nil
                                                 (catch clojure.lang.ExceptionInfo e
                                                   (when (= :already-started (:run-loop/error (ex-data e))) e)))})))))))))
    (testing "a second implementation file is refused before anything is provisioned or paid"
      ;; The fifth project's t-04 (handlers.clj and routes.clj): the schema admitted it, so
      ;; `start` paid two spec reviews and cut three worktrees before the stub threw.
      (let [{:keys [ctx calls thrown]} (start-run (assoc spec :files/impl ["src/a.clj" "src/b.clj"]))]
        (is (= :precondition (:run-loop/error (ex-data thrown))))
        (is (re-find #"files/impl \[\"src/a.clj\" \"src/b.clj\"\] — 2 files — a task stubs exactly one implementation file" (ex-message thrown))
            "the key, the entry and the rule")
        (is (= [] calls) "nothing provisioned, no signature check, no dispatch")
        (is (not (fs/exists? (fs/path (:run-dir ctx) "state.edn"))) "and nothing to tear down")))
    (testing "an unqualified :deps-sigs entry is refused the same way"
      ;; The fifth project's t-03 wrote `(base [title content])` as the method's example showed;
      ;; the signature check passed it and the calls gate, which grants by ns/name, went red
      ;; after two dispatches.
      (let [{:keys [calls thrown]} (start-run (assoc-in spec [:blueprint/slice :deps-sigs] '[(base [title content])]))]
        (is (= :precondition (:run-loop/error (ex-data thrown))))
        (is (re-find #"deps-sigs \(base \[title content\]\) — unqualified" (ex-message thrown)))
        (is (= [] calls))))
    (testing "a refused precondition resets the same way"
      (let [{:keys [ctx thrown]} (start-run spec {#'harness.contract.sigs/violations (fn [& _] [{:sig 'x :violation :unknown-var :detail "x"}])})]
        (is (= :precondition (:run-loop/error (ex-data thrown))))
        (is (re-find #"teardown" (ex-message thrown)) "and the message says how to recover")
        (is (= 3 (count (:deleted (teardown-with ctx)))))
        (is (not (fs/exists? (fs/path (:run-dir ctx) "state.edn"))))))
    (testing "a run that dispatched is not reset, and is not torn down unrecorded either (row 85)"
      (let [{:keys [ctx]} (start-run spec {#'harness.loop.driver/dispatch!
                                           (fn [ctx step _ _]
                                             ;; as the real dispatch! does, record it
                                             (swap! (:state ctx) update :events conj
                                                    {:event/kind :dispatch :event/step step})
                                             {})})]
        (is (loop/dispatched? @(:state ctx)))
        (is (thrown-with-msg? Exception #"record" (teardown-with ctx)))
        (is (fs/exists? (fs/path (:run-dir ctx) "state.edn")))))))

(deftest a-recorded-runs-branches-are-deleted-at-teardown-and-kept-on-request
  ;; The fifth build closed with 31 task branches in its application, rehearsal and merged
  ;; alike, because teardown kept every dispatched run's branches for ever. They are evidence
  ;; only until `record` has run; after that run.edn holds what they held.
  (let [spec {:task/id "t-4" :files/impl ["src/a.clj"] :files/test ["test/a_test.clj"]
              :files/context [] :blueprint/slice {:shapes [] :interfaces '[(f [x])]}}
        dispatched {#'harness.loop.driver/dispatch!
                    (fn [ctx step _ _]
                      (swap! (:state ctx) update :events conj {:event/kind :dispatch :event/step step})
                      {})}
        torn (atom [])
        teardown-with (fn [ctx opts]
                        (let [deleted (atom [])]
                          (with-redefs-fn {#'harness.loop.provision/teardown! (fn [s _] (swap! torn conj (:task/role s)) (assoc s :torn-down? true))
                                           #'harness.loop.driver/delete-branch! (fn [_ b] (swap! deleted conj b) true)}
                            #(loop/teardown! ctx opts))
                          @deleted))
        recorded! (fn [ctx] (spit (str (fs/path (:run-dir ctx) "run.edn")) "{}"))]
    (testing "dispatched and not recorded: refused before anything is removed - the roles' files are in the worktrees"
      (let [{:keys [ctx]} (start-run spec dispatched)]
        (reset! torn [])
        (is (thrown-with-msg? Exception #"not recorded" (teardown-with ctx {})))
        (is (= [] @torn) "no worktree was touched")
        (is (fs/exists? (fs/path (:run-dir ctx) "state.edn")))))
    (testing "dispatched and not recorded, --discard: removed, branches and all, and said so"
      (let [{:keys [ctx]} (start-run spec dispatched)]
        (reset! torn [])
        (is (= ["t-coder" "t-tester" "t-reviewer"] (teardown-with ctx {:discard? true})))
        (is (seq @torn))))
    (testing "recorded: the three branches go, and the run stays history"
      (let [{:keys [ctx]} (start-run spec dispatched)]
        (recorded! ctx)
        (is (= ["t-coder" "t-tester" "t-reviewer"] (teardown-with ctx {})))
        (is (fs/exists? (fs/path (:run-dir ctx) "state.edn")) "state.edn is not removed: this is not a reset")))
    (testing "recorded, --keep-branches: kept for a run somebody means to reopen"
      (let [{:keys [ctx]} (start-run spec dispatched)]
        (recorded! ctx)
        (is (= [] (teardown-with ctx {:keep-branches? true})))))))

(deftest an-amended-slice-regenerates-the-testers-stub
  ;; Review R3: the stub was written once, from the original slice, and never
  ;; again — an amendment adding an interface left the Tester prototyping against
  ;; a stub that contradicted its packet.
  (let [amend-with (fn [before after]
                     (let [run-dir (str (fs/create-temp-dir))
                           tester-dir (str (fs/create-temp-dir))
                           before-file (str (fs/path run-dir "spec.v1.edn"))
                           ctx {:run-dir run-dir
                                :state (atom {:started-ms (System/currentTimeMillis) :events []
                                              :sessions {:tester {:worktree/path tester-dir
                                                                  :harness/wrote ["src/app/calc.clj"]}}})}]
                       (spit before-file (pr-str before))
                       (spit (str (fs/path run-dir "spec.edn")) (pr-str after))
                       (loop/amend! ctx before-file "test")
                       {:ctx ctx :stub (fs/path tester-dir "src/app/calc.clj")}))
        v1 {:task/id "t" :files/impl ["src/app/calc.clj"]
            :blueprint/slice {:shapes [] :interfaces '[(f [x])]}}]
    (testing "an interface added by the amendment is in the regenerated stub"
      (let [{:keys [ctx stub]} (amend-with v1 (assoc-in v1 [:blueprint/slice :interfaces] '[(f [x]) (g [x y])]))]
        (is (fs/exists? stub))
        (is (re-find #"defn g" (slurp (str stub))))
        (is (= ["src/app/calc.clj"] (get-in @(:state ctx) [:sessions :tester :harness/wrote]))
            "recorded as the harness's write, once")
        (is (= "src/app/calc.clj" (:stub-rewritten (last (:events @(:state ctx))))))))
    (testing "an amendment that leaves the slice alone writes no stub"
      (let [{:keys [ctx stub]} (amend-with v1 (assoc v1 :property-targets ["new target"]))]
        (is (not (fs/exists? stub)))
        (is (nil? (:stub-rewritten (last (:events @(:state ctx))))))))))

(deftest packets-see-the-dependents-start-computed
  (let [spec {:task/id "t" :files/context ["src/a.clj"]}]
    (is (= ["src/a.clj" "test/p_test.clj"]
           (:files/context (loop/effective-spec spec {:dependents ["test/p_test.clj"]}))))
    (is (= spec (loop/effective-spec spec {}))
        "a run recorded before dependents existed reads its spec unchanged")))

(deftest a-red-gate-proposes-its-owner-and-decides-nothing
  ;; The three routings that were mechanical in nine recorded hand decisions,
  ;; as a proposal recorded beside the decision that follows it.
  (let [spec {:files/impl ["src/sandbox/render.clj"] :files/test ["test/sandbox/render_test.clj"]}
        deps ["test/sandbox/property_test.clj"]
        red (fn [gate out] {:gates/passed? false :gates/failed gate
                            :gates/report [{:gate :fmt :status :pass :out ""}
                                           {:gate gate :status :fail :out out}]})]
    (testing "an assertion failing in the task's own test is the Coder's by default (method §07)"
      (let [r (loop/propose-routing spec deps (red :test "FAIL in (x) (render_test.clj:12)"))]
        (is (= :coder (:owner r)))
        (is (= :coder (:retry-role r)))
        (is (= ["test/sandbox/render_test.clj"] (:files-named r)))
        (is (= [:gate] (:sources r)))
        (is (re-find #"§07" (:advice r)))
        (is (re-find #"retry tester" (:advice r)) "and says the other way out is a triage decision")))
    (testing "the Tester's file failing to compile, or failing format or lint, is the Tester's"
      (let [r (loop/propose-routing spec deps (red :test "Syntax error compiling at (sandbox/render_test.clj:3:1)."))]
        (is (= :tester (:owner r)))
        (is (= :tester (:retry-role r)))
        (is (re-find #"did not read, compile" (:advice r))))
      (is (= :tester (:owner (loop/propose-routing spec deps (red :lint "test/sandbox/render_test.clj:3:1: warning: unused binding")))))
      (is (= :tester (:owner (loop/propose-routing spec deps (red :fmt "test/sandbox/render_test.clj has incorrect formatting"))))))
    (testing "a failure only in the Coder's file is the Coder's"
      (is (= :coder (:owner (loop/propose-routing spec deps (red :lint "src/sandbox/render.clj:3:1: warning"))))))
    (testing "a dependent no role owns has no owner: the contract or the Coder"
      (let [r (loop/propose-routing spec deps (red :test "ERROR in (round-trips) (property_test.clj:9)"))]
        (is (nil? (:owner r)))
        (is (= :coder (:retry-role r)))
        (is (= ["test/sandbox/property_test.clj"] (:files-named r)))
        (is (= [:architect :gate] (:sources r)))
        (is (re-find #"amend" (:advice r)))))
    (testing "a test gate whose every failing namespace is nobody's in the run is the tooling's"
      (let [r (loop/propose-routing spec deps (red :test "FAIL in sandbox.util-test/clamp-above-hi-spec (util_test.clj:46)\nexpected true\n  actual false"))]
        (is (= :tooling (:owner r)))
        (is (nil? (:retry-role r)) "no role to retry")
        (is (= ["sandbox.util-test"] (:foreign-namespaces r)))
        (is (= [] (:files-named r)))
        (is (re-find #"cannot see a fix made after they were cut" (:advice r))))
      (let [r (loop/propose-routing spec deps (red :test "FAIL in sandbox.util-test/x (util_test.clj:1)\nFAIL in sandbox.render-test/y (render_test.clj:2)"))]
        (is (= :coder (:owner r)) "the run's own test failing too: routed as ever")
        (is (= ["sandbox.util-test"] (:foreign-namespaces r)) "and the foreign one still named"))
      (is (not (contains? (loop/propose-routing spec deps (red :lint "src/other/thing.clj:3:1: warning")) :foreign-namespaces))
          "only the test gate names namespaces"))
    (testing "a runner that prints no Testing line names the namespace on the failure itself"
      (is (= #{"app.util-test"} (loop/failing-namespaces "FAIL in app.util-test/clamp-above-hi-spec (util_test.clj:46)\nexpected: true")))
      (is (= #{"a.b-test" "c.d-test"} (loop/failing-namespaces "Testing a.b-test\nFAIL in (x) (b.clj:1)\nERROR in c.d-test/y (d.clj:2)"))))
    (testing "a whole-suite test gate names namespaces, and a failure belongs to the Testing line above it"
      (is (= "sandbox.property-test" (loop/path-ns "test/sandbox/property_test.clj")))
      (is (= "sandbox.render" (loop/path-ns "src/sandbox/render.clj")))
      (is (= #{"sandbox.property-test"}
             (loop/failing-namespaces "Testing sandbox.render-test\n\nRan ok\n\nTesting sandbox.property-test\n{:result true}\n\nERROR in (round-trips) (parse.clj:25)\nFAIL in (idempotent) (parse.clj:9)\nTesting sandbox.api-test\n")))
      (is (= #{} (loop/failing-namespaces "Testing sandbox.api-test\nRan 3 tests")))
      (let [r (loop/propose-routing spec deps (red :test "Testing sandbox.render-test\nTesting sandbox.property-test\nERROR in (round-trips) (parse.clj:25)"))]
        (is (= ["test/sandbox/property_test.clj"] (:files-named r)) "the green namespace is not named")
        (is (nil? (:owner r))))
      (is (= :coder (:owner (loop/propose-routing spec deps (red :test "Testing sandbox.render-test\nFAIL in (x) (render_test.clj:3)"))))))
    (testing "both roles' files named, or none, is no owner either"
      (is (nil? (:owner (loop/propose-routing spec deps (red :test "render.clj render_test.clj")))))
      (is (nil? (:owner (loop/propose-routing spec deps (red :test "render_test.clj and property_test.clj"))))
          "a Tester file beside a dependent is not the Tester's alone")
      (is (nil? (:owner (loop/propose-routing spec deps (red :test "nothing here")))))
      (is (= [] (:files-named (loop/propose-routing spec deps (red :test "nothing here"))))))
    (testing "a red targets gate is the Tester's, whatever it names"
      (let [r (loop/propose-routing spec deps (red :targets "property target 7 of 8 is named by no test"))]
        (is (= :tester (:owner r)))
        (is (= :tester (:retry-role r)))))
    (testing "the calls check is the Coder's or the slice's, whatever it names"
      (let [r (loop/propose-routing spec deps (red :calls "sandbox.expr/literal? is called and no :deps-sigs entry names it"))]
        (is (= :coder (:owner r)))
        (is (= [:gate] (:sources r)))
        (is (re-find #"amend" (:advice r)))))
    (testing "check! records the proposal as an event, after the gate event"
      (let [run-dir (fs/create-temp-dir)
            ctx {:run-dir (str run-dir)
                 :state (atom {:started-ms (System/currentTimeMillis) :steps [] :events []
                               :config {:gates []} :dependents deps
                               :sessions {:gate {:worktree/path (str (fs/create-temp-dir))}}})}]
        (spit (str (fs/path run-dir "spec.edn")) (pr-str (assoc spec :task/id "t")))
        (with-out-str
          (with-redefs-fn {#'harness.loop.provision/assemble! (fn [& _] {})
                           #'harness.gates.repair/repair! (fn [& _] {:exit 0})
                           #'harness.contract.sigs/undeclared-calls (fn [& _] [])
                           #'harness.gates.run/run-gates! (fn [& _] (red :test "ERROR (property_test.clj:9)"))}
            #(loop/check! ctx)))
        (let [[g r] (take-last 2 (:events @(:state ctx)))]
          (is (= :gates (:event/kind g)))
          (is (= :routing (:event/kind r)))
          (is (nil? (:owner r)))
          (is (= ["test/sandbox/property_test.clj"] (:files-named r))))))))

(deftest an-unaccounted-property-target-fails-the-run-after-every-other-gate-is-green
  ;; A Tester once covered six of eight targets, skipped validate-once and
  ;; passed fmt, lint, test and deps; only mutation found it, by hand,
  ;; afterwards.
  (let [green {:gates/passed? true :gates/report [{:gate :test :status :pass :out "Ran 3 tests"}]}
        run! (fn [test-src gates]
               (let [run-dir (fs/create-temp-dir)
                     wt (fs/create-temp-dir)
                     spec {:task/id "t" :files/impl ["src/s.clj"] :files/test ["test/s_test.clj"]
                           :property-targets ["target one" "target two"]}
                     ctx {:run-dir (str run-dir)
                          :state (atom {:started-ms (System/currentTimeMillis) :steps [] :events []
                                        :config {:gates []} :dependents []
                                        :sessions {:gate {:worktree/path (str wt)}}})}]
                 (fs/create-dirs (fs/path wt "test"))
                 (spit (str (fs/path wt "test/s_test.clj")) test-src)
                 (spit (str (fs/path run-dir "spec.edn")) (pr-str spec))
                 (with-out-str
                   (with-redefs-fn {#'harness.loop.provision/assemble! (fn [& _] {})
                                    #'harness.gates.repair/repair! (fn [& _] {:exit 0})
                                    #'harness.contract.sigs/undeclared-calls (fn [& _] [])
                                    #'harness.loop.provision/review-diff (fn [& _] "")
                                    ;; the green path builds the Reviewer's packet before it
                                    ;; dispatches, and this spec is a stub, not a real one
                                    #'harness.contract.packet/reviewer-packet (fn [& _] {})
                                    #'loop/dispatch! (fn [& _] nil)
                                    #'harness.gates.run/run-gates! (fn [& _] gates)}
                     #(loop/check! ctx)))
                 @(:state ctx)))]

    (testing "a target no test names turns a green run red, and routes to the Tester"
      (let [st (run! ";; target 1\n(deftest one [] nil)\n" green)
            gr (:last-gates st)
            routing (last (filter #(= :routing (:event/kind %)) (:events st)))]
        (is (false? (:gates/passed? gr)))
        (is (= :targets (:gates/failed gr)))
        (is (re-find #"target 2 of 2" (:out (last (:gates/report gr)))))
        (is (= :tester (:owner routing)))))

    (testing "covering both targets leaves the run green"
      (let [st (run! ";; targets 1, 2\n(deftest both [] nil)\n" green)]
        (is (true? (:gates/passed? (:last-gates st))))))

    (testing "a declared exemption counts, and is kept in the event for a person to read"
      (let [st (run! ";; target 1\n(deftest one [] nil)\n\n;; target 2 — not a test: gate 4 checks it\n" green)
            ev (last (filter #(= :targets (:event/kind %)) (:events st)))]
        (is (true? (:gates/passed? (:last-gates st))))
        (is (= [2] (mapv :target (:exempt ev))))
        (is (= "gate 4 checks it" (:reason (first (:exempt ev)))))))

    (testing "AN ALREADY-RED GATE KEEPS ITS OWN FAILURE: bookkeeping never hides a defect"
      (let [red {:gates/passed? false :gates/failed :test
                 :gates/report [{:gate :test :status :fail :out "FAIL in (x) (s_test.clj:3)"}]}
            st (run! "(deftest unmarked [] nil)\n" red)
            gr (:last-gates st)]
        (is (= :test (:gates/failed gr)) "not :targets, although no target is accounted for")
        (is (= :targets (:gate (last (:gates/report gr)))) "and the targets result is still recorded")))))

(deftest the-testers-feedback-is-checked-for-the-implementation
  ;; Every Tester retry on record had been shielded by hand.
  (let [spec {:files/impl ["src/sandbox/bind.clj"] :files/test ["test/sandbox/bind_test.clj"]}
        names '[{:ns sandbox.bind :name walk} {:ns sandbox.bind :name ops}]
        fb (fn [from text] [{:feedback/from from :feedback/text text}])]
    (testing "each rule once"
      (is (= [:impl-path] (mapv :leak (loop/leaks spec names (fb :triage "see src/sandbox/bind.clj")))))
      (is (= [:impl-path] (mapv :leak (loop/leaks spec names (fb :triage "in bind.clj the check")))) "by file name too")
      (is (= [:impl-var] (mapv :leak (loop/leaks spec names (fb :triage "walk recurses")))))
      (is (= [:impl-var] (mapv :leak (loop/leaks spec names (fb :triage "bind/walk recurses")))) "qualified")
      (is (= [:code-block] (mapv :leak (loop/leaks spec names (fb :triage "```clojure\n(+ 1 2)\n```")))))
      (is (= [:reviewer-source] (mapv :leak (loop/leaks spec names (fb :reviewer "fine words"))))))
    (testing "clean feedback, and the token boundary"
      (is (= [] (loop/leaks spec names (fb :triage "bind throws on nil; add invalid inputs"))))
      (is (= [] (loop/leaks spec names (fb :architect "the contract changed to version 2"))))
      (is (= [] (loop/leaks spec names (fb :triage "walking and ops-like and walk-tree"))) "not a whole symbol")
      (is (= [] (loop/leaks spec names (fb :triage "re-walk and tree-walk and my.ops"))) "nor with a symbol character before it")
      (is (= [] (loop/leaks spec names []))))
    (testing "the details name what leaked, quote the word, and say which item it was in"
      (let [[l] (loop/leaks spec names (fb :triage "ops is a map"))]
        (is (= :triage (:feedback/from l)))
        (is (re-find #"sandbox\.bind/ops" (:detail l)))
        (is (re-find #"the word `ops`" (:detail l)) "a person should not have to find it in the paragraph")))
    (testing "A WORD THE CONTRACT USES IS NOT A LEAK: the Tester already holds the title and the targets"
      ;; Two projects' first shielded retries were both refused for guidance that quoted a target:
      ;; the Coder had a private helper named for an English word the contract also uses.
      (let [spec (assoc spec
                        :task/title "bind - substitute while you walk the tree"
                        :property-targets ["the header carries the site title" "ops is granted"])
            names '[{:ns sandbox.bind :name walk} {:ns sandbox.bind :name header} {:ns sandbox.bind :name helper}]]
        (is (= [] (loop/leaks spec names (fb :triage "assert that the header text contains the title")))
            "a word from a property target")
        (is (= [] (loop/leaks spec names (fb :gate "FAIL in (walk-test): expected the header"))) "and one from the title")
        (is (= ["the word `helper` names sandbox.bind/helper, which the slice's :interfaces do not grant"]
               (mapv :detail (loop/leaks spec names (fb :triage "the header is built by helper"))))
            "a helper the contract never mentions is still refused, beside a word that is not")
        (is (= [:impl-path] (mapv :leak (loop/leaks spec names (fb :triage "the header in bind.clj"))))
            "and the other rules are untouched by it")))
    (testing "the word must stand in the contract as a whole symbol, as it must in the feedback"
      (let [spec (assoc spec :property-targets ["walking the tree; re-walk is allowed"])]
        (is (= [:impl-var] (mapv :leak (loop/leaks spec names (fb :triage "walk recurses")))))))
    (testing "a Coder's retry is not checked, a Tester's is refused unless allowed"
      (let [leaky (fb :triage "walk recurses")]
        (is (nil? (loop/leak-check :coder spec names leaky false)))
        (is (thrown-with-msg? Exception #"--allow-leak" (loop/leak-check :tester spec names leaky false)))
        (is (= {:leaks [{:leak :impl-var :feedback/from :triage
                         :detail "the word `walk` names sandbox.bind/walk, which the slice's :interfaces do not grant"}]
                :allowed? true}
               (loop/leak-check :tester spec names leaky true)))
        (is (= {:leaks [] :allowed? false} (loop/leak-check :tester spec names (fb :triage "clean") nil)))))))

;; ---------------------------------------------------------------------------
;; a retry starts from the bytes the gates judged
;; ---------------------------------------------------------------------------

(deftest the-gates-copy-is-the-newest-only-when-nothing-was-written-since
  (let [wrote (fn [role] {:event/kind :dispatch :role role :status :done :files ["f.clj"]})
        nothing (fn [role] {:event/kind :dispatch :role role :status :failed :files []})]
    (is (true? (loop/gated-since-written? [(wrote :tester) {:event/kind :gates}] :tester)))
    (is (true? (loop/gated-since-written? [(wrote :tester) {:event/kind :gates} (nothing :tester)] :tester))
        "a dispatch that wrote nothing changes no file, so the gate's copy is still the newest")
    (is (true? (loop/gated-since-written? [(wrote :tester) {:event/kind :gates} (wrote :coder)] :tester))
        "the other role writing says nothing about this role's files")
    (is (false? (loop/gated-since-written? [(wrote :tester) {:event/kind :gates} (wrote :tester)] :tester))
        "a hand retry before any check: the role's copy is newer, and must not be overwritten")
    (is (false? (loop/gated-since-written? [(wrote :tester)] :tester)) "never gated")
    (is (false? (loop/gated-since-written? [] :tester)))))

;; ---------------------------------------------------------------------------
;; which repository a run works on
;; ---------------------------------------------------------------------------

(defn- git-repo! [dir]
  (fs/create-dirs dir)
  (doseq [args [["init" "-q" "-b" "main"] ["config" "user.email" "harness@test"]
                ["config" "user.name" "harness-test"]]]
    (apply p/shell {:dir (str dir) :out :string :err :string} "git" args))
  (str (fs/real-path dir)))

(defn- workspace!
  "A plain folder - NOT a repository - holding an application repository, a KIT-like
  repository and a `work/` folder, with the `workspace.edn` that says which is which."
  []
  (let [ws (fs/real-path (fs/create-temp-dir))]
    (git-repo! (fs/path ws "xyx-app"))
    (git-repo! (fs/path ws "kit"))
    (fs/create-dirs (fs/path ws "work" "runs" "r1"))
    (fs/create-dirs (fs/path ws "kit" "runs" "r1"))
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/app "xyx-app" :workspace/build "xyx-build" :workspace/work "work"}))
    (str ws)))

(deftest a-workspace-is-found-by-walking-up-and-its-folders-are-absolute
  (let [ws (workspace!)
        found (workspace/find-workspace (str (fs/path ws "work" "runs" "r1")))]
    (is (= ws (:workspace/dir found)))
    (is (= (str (fs/path ws "xyx-app")) (:workspace/app found)))
    (is (= (str (fs/path ws "work")) (:workspace/work found)))
    (is (= found (workspace/find-workspace (str (fs/path ws "kit" "runs" "r1"))))
        "the same answer from inside the KIT's clone")
    (is (nil? (workspace/find-workspace (str (fs/create-temp-dir)))) "and none where there is none")))

(deftest the-project-is-named-most-explicit-first-and-must-be-a-repository
  (let [ws (workspace!)
        found (workspace/find-workspace ws)
        app (str (fs/path ws "xyx-app"))
        in-work (str (fs/path ws "work" "runs" "r1"))
        in-kit (str (fs/path ws "kit" "runs" "r1"))]
    (testing "in a workspace the application is the project, wherever the run directory is"
      (is (= {:repo/root app :repo/from :workspace} (loop/project-root in-work {} found))
          "a run directory under work/ is in no repository at all")
      (is (= {:repo/root app :repo/from :workspace} (loop/project-root in-kit {} found))
          "and one inside the KIT's clone must not make the KIT the project"))
    (testing "loop.edn's :repo/root wins, relative to the run directory"
      (is (= {:repo/root (str (fs/path ws "kit")) :repo/from :loop-edn}
             (loop/project-root in-work {:repo/root "../../../kit"} found))))
    (testing "with neither, the run directory's own repository - every run recorded so far"
      (is (= {:repo/root (str (fs/path ws "kit")) :repo/from :run-dir}
             (loop/project-root in-kit {} nil))))
    (testing "an answer that is not a repository is an error that says where it came from"
      (let [err (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))
            typo (err #(loop/project-root in-work {} (assoc found :workspace/app (str (fs/path ws "xyx-ap")))))
            bad (err #(loop/project-root in-work {:repo/root "nowhere"} found))
            none (err #(loop/project-root in-work {} nil))]
        (is (= [:no-repo :workspace] ((juxt :run-loop/error :repo/from) (ex-data typo))))
        (is (re-find #"workspace.edn" (ex-message typo)))
        (is (= :loop-edn (:repo/from (ex-data bad))))
        (is (= :run-dir (:repo/from (ex-data none))))
        (is (re-find #"no workspace.edn was found" (ex-message none)))))))

(deftest in-a-workspace-the-worktrees-go-under-work-and-not-the-temp-folder
  (let [cfg {:run/id "r1" :profile "resources/profiles/claude.edn"}
        ws {:workspace/work "/ws/work"}]
    (is (= "/ws/work/worktrees/r1" (:worktrees/dir (loop/resolve-config "/ws/work/runs/r1" "/ws/xyx-app" cfg ws))))
    (is (str/includes? (:worktrees/dir (loop/resolve-config "/x/run" "/x" cfg)) "run-loop")
        "outside a workspace, the temp folder as before")
    (is (= "/ws/work/runs/r1/wt"
           (:worktrees/dir (loop/resolve-config "/ws/work/runs/r1" "/ws/xyx-app" (assoc cfg :worktrees/dir "wt") ws)))
        "and loop.edn still has the last word")))

(deftest a-dispatch-event-carries-its-error-s-kind-and-a-credit-refusal-s-who
  (let [credit {:harness/error :credit :status 402 :message "insufficient credits"
                :endpoint "https://openrouter.ai/api/v1" :key-env "OPENROUTER_API_KEY"}
        e (loop/dispatch-event :reviewer :reviewer {} {:status :failed :runner/meta {:error credit}})]
    (is (= :credit (:error/kind e)))
    (is (= {:status 402 :endpoint "https://openrouter.ai/api/v1" :key-env "OPENROUTER_API_KEY"} (:credit e)))
    (is (string? (:error e)) "the text, for a document"))
  (let [e (loop/dispatch-event :coder :coder {} {:status :failed :runner/meta {:error {:harness/error :api-error :status 400 :message "bad"}}})]
    (is (= :api-error (:error/kind e)))
    (is (not (contains? e :credit))))
  (is (not (some #{:error :error/kind :credit} (keys (loop/dispatch-event :coder :coder {} {:status :done :runner/meta {}}))))
      "no error, no key — older state replays unchanged"))

(deftest dirty-files-lists-what-the-worktrees-will-not-see-and-leaves-the-loop-s-own-out
  (let [repo (git-repo! (fs/path (fs/create-temp-dir) "app"))
        git (fn [& args] (apply p/shell {:dir repo :out :string :err :string} "git" args))]
    (spit (str (fs/path repo "a.txt")) "a")
    (spit (str (fs/path repo ".gitignore")) "ignored.txt\n")
    (git "add" "-A") (git "commit" "-qm" "init")
    (is (= [] (loop/dirty-files repo [])) "clean after the commit")
    (spit (str (fs/path repo "a.txt")) "changed")
    (spit (str (fs/path repo "new.txt")) "n")
    (spit (str (fs/path repo "ignored.txt")) "i")
    (fs/create-dirs (fs/path repo "run" "wt"))
    (spit (str (fs/path repo "run" "state.edn")) "{}")
    (spit (str (fs/path repo "run" "wt" "x.clj")) "x")
    (is (= [" M a.txt" "?? new.txt"]
           (loop/dirty-files repo [(str (fs/path repo "run")) (str (fs/path repo "run" "wt"))]))
        "a modified file and an untracked one; not the ignored one, not the run directory")
    (is (= [" M a.txt" "?? new.txt" "?? run/state.edn" "?? run/wt/x.clj"] (loop/dirty-files repo []))
        "with nothing excluded, the run's files count too")
    (is (= [" M a.txt" "?? new.txt"] (loop/dirty-files repo [(str (fs/path repo "run")) "/elsewhere/wt"]))
        "an exclude outside the repository is ignored")
    (is (= [] (loop/dirty-files "/nowhere/at/all" [])) "not a repository: nothing, never a throw")))

(deftest context-finds-the-projects-profile-in-the-plan
  ;; `bb init` writes <build>/profile.edn and a loop.edn that says `:profile "profile.edn"`;
  ;; from a run directory under work/, `start` must read that file and not one in the clone.
  (let [ws (workspace!)
        plan (fs/path ws "xyx-build")
        run-dir (str (fs/path ws "work" "runs" "r1"))]
    (fs/create-dirs plan)
    (fs/copy "resources/profiles/claude.edn" (fs/path plan "profile.edn"))
    (spit (str (fs/path run-dir "loop.edn")) (pr-str {:run/id "r1" :profile "profile.edn"}))
    (let [cfg (:config (loop/context run-dir))]
      (is (= (str (fs/path plan "profile.edn")) (:profile cfg)))
      (is (= (str (fs/path ws "xyx-app")) (:repo/root cfg)))
      (is (shapes/valid-profile? (profile/read-profile (:profile cfg)))
          "and it is the shipped example, readable where it was copied"))))

(deftest the-records-home-and-its-tables-are-found-absolute-and-absent-when-unnamed
  (let [ws (workspace!)]
    (is (nil? (:workspace/records (workspace/find-workspace ws)))
        "a workspace.edn from before the keys: no home, so no copy")
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/app "xyx-app" :workspace/build "xyx-build" :workspace/work "work"
                   :workspace/records "xyx-build/runs" :workspace/run-tables "xyx-build/RUNS.md"}))
    (let [found (workspace/find-workspace (str (fs/path ws "work" "runs" "r1")))]
      (is (= (str (fs/path ws "xyx-build" "runs")) (:workspace/records found)))
      (is (= (str (fs/path ws "xyx-build" "RUNS.md")) (:workspace/run-tables found))))))

(deftest config-keeps-where-the-record-goes-and-which-repositories-it-names
  (let [ws {:workspace/dir "/ws" :workspace/app "/ws/xyx-app" :workspace/build "/ws/xyx-build"
            :workspace/work "/ws/work" :workspace/kit "/ws/kit" :workspace/records "/ws/xyx-build/runs"}
        cfg (loop/resolve-config "/ws/work/runs/r1" "/ws/xyx-app" {:run/id "r1" :profile "profile.edn"} ws)]
    (is (= "/ws/xyx-build/runs" (:records/dir cfg)))
    (is (= "/ws/kit" (:kit/root cfg)))
    (is (= "/ws/xyx-build" (:plan/root cfg)))
    (let [bare (loop/resolve-config "/x/run" "/x" {:run/id "r" :profile "p"})
          here (str/trim (:out (p/shell {:out :string :err :string} "git" "rev-parse" "--show-toplevel")))]
      (is (nil? (:records/dir bare)) "no workspace: the run directory is the record's only home")
      (is (nil? (:plan/root bare)))
      (is (= here (:kit/root bare)) "and the KIT is the one this runs from"))))

(deftest record-copies-itself-into-the-plan-and-names-the-three-commits
  ;; Two builds copied run.edn into the plan by hand and answered "against which commit?"
  ;; from memory. The record is what a document cites, so it carries both.
  (let [ws (workspace!)
        plan (git-repo! (fs/path ws "xyx-build"))
        commit! (fn [dir]
                  (spit (str (fs/path dir "f")) "x")
                  (p/shell {:dir dir :out :string :err :string} "git" "add" "-A")
                  (p/shell {:dir dir :out :string :err :string} "git" "commit" "-q" "-m" "c")
                  (str/trim (:out (p/shell {:dir dir :out :string :err :string} "git" "rev-parse" "HEAD"))))
        app-sha (commit! (str (fs/path ws "xyx-app")))
        kit-sha (commit! (str (fs/path ws "kit")))
        plan-sha (commit! plan)
        spec {:files/impl [] :files/test []}
        run-dir (fs/path ws "work" "runs" "r1")
        ctx (record-ctx run-dir {} [{:event/kind :dispatch :role :coder}] spec)]
    (swap! (:state ctx) assoc :config {:run/id "r1" :records/dir (str (fs/path ws "xyx-build" "runs"))
                                       :repo/root (str (fs/path ws "xyx-app"))
                                       :kit/root (str (fs/path ws "kit")) :plan/root plan})
    (let [out (with-out-str (loop/record! ctx))
          r (clojure.edn/read-string (slurp (str (fs/path run-dir "run.edn"))))
          kept (fs/path ws "xyx-build" "runs" "r1.edn")]
      (is (= {:run/kit-commit kit-sha :run/app-commit app-sha :run/plan-commit plan-sha}
             (select-keys r [:run/kit-commit :run/app-commit :run/plan-commit])))
      (is (fs/exists? kept) "the plan's copy, by run id")
      (is (= r (clojure.edn/read-string (slurp (str kept)))) "the same record, byte for byte in meaning")
      (is (str/includes? out (str "copied to " kept))))
    (testing "outside a workspace: no copy, a commit for what is a repository, nil for what is not"
      (let [rd (fs/create-temp-dir)
            ctx2 (record-ctx rd {} [{:event/kind :dispatch :role :coder}] spec)]
        (swap! (:state ctx2) assoc :config {:run/id "r2" :repo/root (str (fs/path ws "xyx-app"))
                                            :kit/root (str (fs/path rd "nowhere"))})
        (with-out-str (loop/record! ctx2))
        (let [r2 (clojure.edn/read-string (slurp (str (fs/path rd "run.edn"))))]
          (is (= app-sha (:run/app-commit r2)))
          (is (nil? (:run/kit-commit r2)) "a folder that is not there")
          (is (contains? r2 :run/plan-commit))
          (is (nil? (:run/plan-commit r2)) "no plan: nil, written, so the report says — and not 'not recorded'")
          (is (shapes/valid-run? r2)))))))
