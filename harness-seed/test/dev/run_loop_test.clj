(ns dev.run-loop-test
  "The driver's pure half. The commands themselves provision worktrees and call
  models, so they are exercised by runs, recorded in `RUNS.md`; what is tested
  here is everything a run's record and its retries depend on being right."
  (:require
   [babashka.fs :as fs]
   [clojure.edn]
   [clojure.test :refer [deftest is testing]]
   [harness.gates :as gates]
   [harness.provision]
   [harness.repair]
   [harness.report :as report]
   [harness.sigs]
   [harness.stub]
   [run-loop :as loop]))

(deftest config-fills-defaults-and-refuses-to-guess
  (let [run-dir (str (fs/create-temp-dir))
        cfg (loop/resolve-config run-dir "/repo" {:run/id "d10" :profile "resources/profiles/claude.edn"
                                                  :architecture {:from "arch" :files ["layers.edn"]}})]
    (testing "the defaults"
      (is (= gates/default-gate-seq (:gates cfg)))
      (is (= ["clojure" "-M:nrepl"] (:nrepl/cmd cfg)))
      (is (= "/repo" (:repo/root cfg))))
    (testing "the architecture resolves against the run directory, the profile against the working directory"
      (is (= (str (fs/absolutize (fs/path run-dir "arch"))) (get-in cfg [:architecture :from])))
      (is (= (str (fs/absolutize "resources/profiles/claude.edn")) (:profile cfg))))
    (testing "a missing run id or profile is refused, not invented"
      (is (thrown-with-msg? Exception #":run/id" (loop/resolve-config run-dir "/repo" {:profile "p"})))
      (is (thrown-with-msg? Exception #":profile" (loop/resolve-config run-dir "/repo" {:run/id "x"}))))))

(deftest step-names-say-how-many-times-a-role-came-back
  (is (= :coder-r1 (loop/retry-step-name :coder 2)))
  (is (= :tester-r2 (loop/retry-step-name :tester 3)))
  (is (= :reviewer (loop/reviewer-step-name 0)))
  (is (= :reviewer-r1 (loop/reviewer-step-name 1))))

(deftest a-retry-past-the-cap-escalates-instead
  (is (= 2 (loop/next-attempt {:coder 1 :tester 1} :coder 3)))
  (is (= 3 (loop/next-attempt {:coder 2} :coder 3)))
  (is (thrown-with-msg? Exception #"escalate" (loop/next-attempt {:coder 3} :coder 3))))

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
      (is (= "d" (get-in e [:pricing :as-of]))))))

(deftest the-record-keeps-a-capped-transcript-and-the-run-directory-the-whole-one
  ;; NOTES.md row 8. Tool results run to 20,000 chars and write_file args hold
  ;; whole files; the record keeps enough to see what happened, in order.
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
  ;; D6 printed a wall time below the sum of its own steps; D7 learned to stop
  ;; the clock at the run's last dispatch or gate, not at `record`.
  (let [state {:config {:run/id "d10"} :spec {:task/id "t-12"}
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
    (is (= :done (:run/status r)))
    (is (= "d10" (:run/id r)))
    (is (= "t-12" (:task/id r)))
    (is (= :failed (:run/status (loop/run-record (assoc state :last-gates {:gates/passed? false})))))
    (testing "the files the run produced go in the record, and no key when there are none"
      (is (= {"src/a.clj" "(ns a)"}
             (:run/files (loop/run-record (assoc state :final-files {"src/a.clj" "(ns a)"})))))
      (is (not (contains? (loop/run-record state) :run/files)))
      (is (not (contains? (loop/run-record (assoc state :final-files {})) :run/files))))))

(deftest a-red-gate-keeps-its-output-in-the-record
  ;; The claim audit: D13's record said the test gate failed, and the token it
  ;; failed on was only in a gitignored file.
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
      ;; NOTES.md row 11: D8's Coder called a var its slice never granted.
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
          (with-redefs-fn {#'harness.provision/assemble! (fn [& _] {})
                           #'harness.repair/repair! (fn [& _] {:exit 0})
                           #'harness.sigs/undeclared-calls (fn [& _] stray)
                           #'harness.gates/run-gates! (fn [& _] (swap! ran-gates inc) green)}
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
          (with-redefs-fn {#'harness.provision/assemble! (fn [& _] {})
                           #'harness.repair/repair! (fn [& _] {:exit 0})
                           #'harness.gates/run-gates! (fn [& _] red)}
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
                 :state (atom {:config {:run/id "r"} :attempts {:coder 1} :steps [] :events []})}]
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
;; NOTES.md row 14: the record holds the bytes the gates passed
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
             (:run/files-as-written (loop/run-record {:files-as-written {"src/b/core.clj" "x"}}))))
      (is (not (contains? (loop/run-record {:files-as-written {}}) :run/files-as-written))))))

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

(deftest a-diffs-header-is-dropped-and-its-hunks-kept
  (is (= "@@ -80,2 +80,2 @@\n-  a\n+   a"
         (loop/diff-hunks "diff --git a/tmp/x b/tmp/y\nindex 1..2 100644\n--- a/tmp/x\n+++ b/tmp/y\n@@ -80,2 +80,2 @@\n-  a\n+   a")))
  (is (= "" (loop/diff-hunks ""))))

(defn- record-ctx [run-dir sessions events spec]
  (spit (str (fs/path run-dir "spec.edn")) (pr-str (assoc spec :task/id "t")))
  {:run-dir (str run-dir)
   :state (atom {:config {:run/id "r"} :attempts {:coder 1} :steps [] :events events
                 :sessions sessions})})

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
                  (with-redefs-fn {#'harness.provision/assemble! (fn [& _] {})
                                   #'harness.repair/repair! (fn [dir _] (repair dir) {:exit 0})
                                   #'harness.sigs/undeclared-calls (fn [& _] [])
                                   #'harness.gates/run-gates! (fn [& _] red)}
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
  "Drive `start`'s dispatch sequence with nothing real behind it: `results` maps a
  role to the result its dispatch returns. Returns what happened."
  [cfg results f]
  (let [dispatched (atom []) checked (atom 0)
        ctx {:run-dir (str (fs/create-temp-dir))
             :state (atom {:started-ms (System/currentTimeMillis) :steps [] :events [] :config cfg})}]
    (with-redefs-fn {#'run-loop/dispatch! (fn [_ _ role _] (swap! dispatched conj role) (results role))
                     #'run-loop/first-packet (fn [_ role] {:task/role role})
                     #'run-loop/check! (fn [_] (swap! checked inc))}
      #(f ctx))
    {:dispatched @dispatched :checked @checked :state @(:state ctx)}))

(deftest the-run-stops-where-a-note-was-left-and-continues-on-request
  ;; D14: the Coder's note predicted the red gate exactly, and the loop dispatched
  ;; the Tester and ran the gates anyway.
  (let [noted {:coder {:notes ["the contract breaks property_test.clj"]} :tester {}}
        proceed @#'run-loop/proceed!]
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
                               ((var-get #'run-loop/proceed!) ctx :coder (noted :coder))
                               (loop/continue! ctx decision)))]
        (is (= [:tester] (:dispatched r)))
        (is (= 1 (:checked r)))
        (is (nil? (get-in r [:state :paused])) "the pause is cleared")
        (is (= "an observation, not a conflict"
               (:decision (first (filter #(= :continued (:event/kind %)) (get-in r [:state :events])))))
            "and the reason is on the event (NOTES.md row 15)")))
    (testing "continue with nothing decided since the pause, and no reason, is refused"
      ;; D19: continued on an observation, and the record never said why.
      (let [r (flow {} noted (fn [ctx]
                               ((var-get #'run-loop/proceed!) ctx :coder (noted :coder))
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
                                               ((var-get #'run-loop/proceed!) ctx :coder (noted :coder))
                                               (loop/continue! ctx blank)))))))
    (testing "an amend or a retry after the pause is its own reason, and continue needs no file"
      (let [r (flow {} noted (fn [ctx]
                               ((var-get #'run-loop/proceed!) ctx :coder (noted :coder))
                               (swap! (:state ctx) update :events conj {:event/kind :amend :reason "why"})
                               (loop/continue! ctx)))]
        (is (= [:tester] (:dispatched r)))
        (is (not (contains? (first (filter #(= :continued (:event/kind %)) (get-in r [:state :events])))
                            :decision))
            "the event is as it was, so D15's and D17's records replay unchanged"))
      (is (true? (loop/decided-since-pause? [{:event/kind :paused} {:event/kind :triage}])))
      (is (false? (loop/decided-since-pause? [{:event/kind :amend} {:event/kind :paused}]))
          "an amend before the pause decided an earlier pause, not this one")
      (is (false? (loop/decided-since-pause? [{:event/kind :paused} {:event/kind :dispatch}]))))
    (testing "the command line hands continue its decision file"
      ;; The path a person actually types: a mutant dropping the argument in
      ;; -main survived every test above.
      (let [got (atom nil)]
        (with-redefs-fn {#'run-loop/context (fn [_] {:state (atom {:paused {:after :coder :next :tester}})})
                         #'run-loop/continue! (fn [_ & args] (reset! got (vec args)))}
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
      ;; D17: the Coder's retry died on an API error and `continue` dispatched
      ;; the Tester against the file the retry never rewrote.
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
  "Run `start!` with nothing real behind it: provisioning, the signature check, the
  stub and every dispatch are replaced. `overrides` replaces any of those further.
  Returns the context and a log of what was called."
  ([spec] (start-run spec {}))
  ([spec overrides]
   (let [run-dir (str (fs/create-temp-dir))
         calls (atom [])
         _ (spit (str (fs/path run-dir "spec.edn")) (pr-str spec))
         cfg {:run/id "t" :profile "resources/profiles/claude.edn" :repo/root "/r"
              :worktrees/dir (str (fs/create-temp-dir)) :project/subdir nil
              :nrepl/cmd ["x"] :gates [] :pause-on-notes? true}
         ctx {:run-dir run-dir :config cfg :state (atom nil)}
         fakes (merge {#'harness.provision/provision!
                       (fn [o] (swap! calls conj [:provision (:task/role o)])
                         {:task/id (:task/id o) :task/role (:task/role o)
                          :worktree/path (str (fs/create-temp-dir)) :worktree/git-root "/g"})
                       #'harness.sigs/task-dependents
                       (fn [_ sp] (swap! calls conj [:task-dependents (:files/test sp)]) ["test/p_test.clj"])
                       #'harness.sigs/violations (fn [& _] [])
                       #'harness.stub/write! (fn [_ impl _] (first impl))
                       ;; packets are not what these tests are about, and a real one
                       ;; would refuse the fake sessions (no nREPL port)
                       #'run-loop/first-packet (fn [_ role] {:task/role role})
                       #'run-loop/dispatch! (fn [_ step _ _] (swap! calls conj [:dispatch step]) {})
                       #'run-loop/check! (fn [_] (swap! calls conj [:check]))}
                      overrides)
         result (try (with-redefs-fn fakes #(loop/start! ctx)) nil
                     (catch clojure.lang.ExceptionInfo e e))]
     {:ctx ctx :calls @calls :thrown result})))

(deftest start-shows-a-task-its-dependents-not-its-own-tests
  ;; Review R2, at the driver: the dependents come from task-dependents, which
  ;; is given the whole spec so it can leave out the task's own :files/test.
  (let [{:keys [ctx calls]} (start-run {:task/id "t-1" :files/impl ["src/a.clj"]
                                        :files/test ["test/a_test.clj"] :files/context []
                                        :blueprint/slice {:shapes [] :interfaces []}})]
    (is (some #{[:task-dependents ["test/a_test.clj"]]} calls))
    (is (= ["test/p_test.clj"] (:dependents @(:state ctx))))))

(deftest a-failed-start-can-be-torn-down-and-started-again
  ;; Review R4: sessions were saved only after all three worktrees existed, and a
  ;; run that dispatched nothing could not be restarted — state.edn refused
  ;; `start`, and the kept branches refused `git worktree add -b`.
  (let [spec {:task/id "t-4" :files/impl ["src/a.clj"] :files/test ["test/a_test.clj"]
              :files/context [] :blueprint/slice {:shapes [] :interfaces []}}
        third-fails {#'harness.provision/provision!
                     (fn [o]
                       (when (= :reviewer (:task/role o))
                         (throw (ex-info "nREPL never came up" {:role :reviewer})))
                       {:task/id (:task/id o) :task/role (:task/role o)
                        :worktree/path (str (fs/create-temp-dir)) :worktree/git-root "/g"})}
        teardown-with (fn [ctx]
                        (let [torn (atom []) deleted (atom [])]
                          (with-redefs-fn {#'harness.provision/teardown! (fn [s _] (swap! torn conj (:task/role s)) (assoc s :torn-down? true))
                                           #'run-loop/delete-branch! (fn [_ b] (swap! deleted conj b) true)}
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
            (is (= ["t-4-coder" "t-4-tester" "t-4-reviewer"] deleted))
            (is (not (fs/exists? (fs/path (:run-dir ctx) "state.edn"))))
            (testing "so start is no longer refused"
              (is (nil? (:thrown (let [ctx2 (assoc ctx :state (atom nil))]
                                   {:thrown (try (with-redefs-fn {#'harness.provision/provision! (fn [_] (throw (ex-info "stop here" {})))}
                                                   #(loop/start! ctx2))
                                                 nil
                                                 (catch clojure.lang.ExceptionInfo e
                                                   (when (= :already-started (:run-loop/error (ex-data e))) e)))})))))))))
    (testing "a refused precondition resets the same way"
      (let [{:keys [ctx thrown]} (start-run spec {#'harness.sigs/violations (fn [& _] [{:sig 'x :violation :unknown-var :detail "x"}])})]
        (is (= :precondition (:run-loop/error (ex-data thrown))))
        (is (re-find #"teardown" (ex-message thrown)) "and the message says how to recover")
        (is (= 3 (count (:deleted (teardown-with ctx)))))
        (is (not (fs/exists? (fs/path (:run-dir ctx) "state.edn"))))))
    (testing "a run that dispatched keeps its branches and state"
      (let [{:keys [ctx]} (start-run spec {#'run-loop/dispatch!
                                           (fn [ctx step _ _]
                                             ;; as the real dispatch! does, record it
                                             (swap! (:state ctx) update :events conj
                                                    {:event/kind :dispatch :event/step step})
                                             {})})
            {:keys [deleted]} (teardown-with ctx)]
        (is (loop/dispatched? @(:state ctx)))
        (is (= [] deleted))
        (is (fs/exists? (fs/path (:run-dir ctx) "state.edn")))))))

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
  ;; NOTES.md row 1: the three routings that were mechanical in D7–D17's nine
  ;; hand decisions, as a proposal recorded beside the decision.
  (let [spec {:files/impl ["src/sandbox/render.clj"] :files/test ["test/sandbox/render_test.clj"]}
        deps ["test/sandbox/property_test.clj"]
        red (fn [gate out] {:gates/passed? false :gates/failed gate
                            :gates/report [{:gate :fmt :status :pass :out ""}
                                           {:gate gate :status :fail :out out}]})]
    (testing "an assertion failing in the task's own test is the Coder's by default (method §07, NOTES.md row 17)"
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
    (testing "a dependent no role owns has no owner: the contract or the Coder (D12)"
      (let [r (loop/propose-routing spec deps (red :test "ERROR in (round-trips) (property_test.clj:9)"))]
        (is (nil? (:owner r)))
        (is (= :coder (:retry-role r)))
        (is (= ["test/sandbox/property_test.clj"] (:files-named r)))
        (is (= [:architect :gate] (:sources r)))
        (is (re-find #"amend" (:advice r)))))
    (testing "a whole-suite test gate names namespaces, and a failure belongs to the Testing line above it (D12)"
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
          (with-redefs-fn {#'harness.provision/assemble! (fn [& _] {})
                           #'harness.repair/repair! (fn [& _] {:exit 0})
                           #'harness.sigs/undeclared-calls (fn [& _] [])
                           #'harness.gates/run-gates! (fn [& _] (red :test "ERROR (property_test.clj:9)"))}
            #(loop/check! ctx)))
        (let [[g r] (take-last 2 (:events @(:state ctx)))]
          (is (= :gates (:event/kind g)))
          (is (= :routing (:event/kind r)))
          (is (nil? (:owner r)))
          (is (= ["test/sandbox/property_test.clj"] (:files-named r))))))))

(deftest the-testers-feedback-is-checked-for-the-implementation
  ;; NOTES.md row 5: every Tester retry in D7, D8 and D12 was shielded by hand.
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
    (testing "the details name what leaked, and the item it was in"
      (let [[l] (loop/leaks spec names (fb :triage "ops is a map"))]
        (is (= :triage (:feedback/from l)))
        (is (re-find #"sandbox\.bind/ops" (:detail l)))))
    (testing "a Coder's retry is not checked, a Tester's is refused unless allowed"
      (let [leaky (fb :triage "walk recurses")]
        (is (nil? (loop/leak-check :coder spec names leaky false)))
        (is (thrown-with-msg? Exception #"--allow-leak" (loop/leak-check :tester spec names leaky false)))
        (is (= {:leaks [{:leak :impl-var :feedback/from :triage
                         :detail "names sandbox.bind/walk, which the slice's :interfaces do not grant"}]
                :allowed? true}
               (loop/leak-check :tester spec names leaky true)))
        (is (= {:leaks [] :allowed? false} (loop/leak-check :tester spec names (fb :triage "clean") nil)))))))
