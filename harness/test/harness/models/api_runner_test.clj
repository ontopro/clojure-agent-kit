(ns harness.models.api-runner-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.packet]
   [harness.contract.shapes :as shapes]
   [harness.models.runner :as runner]
   [harness.models.runner-check :as check]
   [org.httpkit.server :as srv]))

(defn- git-repo
  "A real git repository with one commit. `repair/changed-files` shells git, so
  a fixture that only pretends to be a repo would let `:files` come back empty
  and every assertion about it pass for the wrong reason."
  []
  (let [dir (str (fs/create-temp-dir))
        run (fn [& args] (apply p/shell {:dir dir :out :string :err :string} "git" args))]
    (run "init" "-q")
    (run "config" "user.email" "t@t")
    (run "config" "user.name" "t")
    (fs/create-dirs (fs/path dir "src" "app"))
    (spit (str (fs/path dir "src" "app" "store.clj")) "(ns app.store)\n")
    (run "add" "-A")
    (run "commit" "-qm" "base")
    dir))

(defn- stub
  "A model server returning `responses` in order, last repeating."
  [responses f]
  (let [n (atom -1)
        stop (srv/run-server
              (fn [_] (let [i (min (swap! n inc) (dec (count responses)))]
                        {:status 200 :headers {"Content-Type" "application/json"}
                         :body (json/generate-string (nth responses i))}))
              {:port 0 :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (srv/server-port stop)))
         (finally (srv/server-stop! stop)))))

(defn- text [s] {:id "g1" :choices [{:message {:content s}}]
                 :usage {:prompt_tokens 10 :completion_tokens 4}})

(defn- write-call [path content]
  {:id "g2"
   :choices [{:message {:content nil
                        :tool_calls [{:id "c1" :type "function"
                                      :function {:name "write_file"
                                                 :arguments (json/generate-string
                                                             {:path path :content content})}}]}}]
   :usage {:prompt_tokens 10 :completion_tokens 4}})

(defn- profile [endpoint]
  {:seat :claude
   :roles (into {} (for [r [:coder :tester :reviewer]]
                     [r {:family :stub :model "stub-1" :shape :openai :endpoint endpoint}]))})

(defn- packet [dir]
  {:task/id "t-01" :task/title "A task" :task/role :coder
   :blueprint/slice {:shapes [] :interfaces ['(f [x])]}
   :files/target ["src/app/service.clj"]
   :files/context ["src/app/store.clj"]
   :repl/worktree dir :repl/port 7777
   :layer/name :service :gates {:retry-cap 3}})

;; ---------------------------------------------------------------------------
;; the contract
;; ---------------------------------------------------------------------------

(deftest it-conforms-to-the-agent-runner-contract
  ;; The check that exists because four runners in the source project each
  ;; faced only their own unit tests and five keys drifted onto the result map.
  (let [dir (git-repo)
        results (stub [(write-call "src/app/service.clj" "(ns app.service)\n(defn f [x] x)\n")
                       (text "wrote it")]
                      #(check/check-runner (runner/api-runner (profile %))
                                           {:packet (packet dir) :expect-writes? true}))]
    (doseq [{:keys [check pass? detail]} results]
      (is pass? (str (name check) ": " detail)))
    (is (check/conforms? results))
    (is (some #(= :files-exist (:check %)) results)
        "and :files-exist really ran — it is the one that needs a real repo")))

;; ---------------------------------------------------------------------------
;; :files comes from git
;; ---------------------------------------------------------------------------

(deftest files-are-what-changed-on-disk-not-what-the-model-claimed
  ;; A model that says it wrote a file is making a claim; git is looking. The
  ;; loop feeds :files straight to gate 0, so a claim would send gate 0 after a
  ;; file that is not there and the gates would fail for an unrelated reason.
  (let [dir (git-repo)
        r (stub [(text "I wrote src/app/service.clj and src/app/imaginary.clj")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder (packet dir)))]
    (is (= :done (:status r)))
    (is (= [] (:files r)) "it wrote nothing, whatever it said")))

(deftest what-the-harness-placed-is-not-reported-as-the-agents-work
  ;; An early run: the Tester's :files came back as [the generated stub, its
  ;; own test file]. git cannot tell a stub the harness wrote from something the
  ;; agent wrote, and `provision/scope-violations` already knew that — it
  ;; just had the Session, and the runner has only the packet.
  (let [dir (git-repo)
        _ (spit (str (fs/path dir "src" "app" "stub.clj")) "(ns app.stub)\n")
        pkt (assoc (packet dir) :harness/wrote ["src/app/stub.clj"])
        r (stub [(write-call "src/app/service.clj" "(ns app.service)\n")
                 (text "done")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder pkt))]
    (is (= ["src/app/service.clj"] (:files r))
        "the stub changed on disk too, and is not the agent's output")))

(deftest a-real-write-shows-up-in-files
  (let [dir (git-repo)
        r (stub [(write-call "src/app/service.clj" "(ns app.service)\n")
                 (text "done")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder (packet dir)))]
    (is (= ["src/app/service.clj"] (:files r)))
    (is (fs/exists? (fs/path dir "src/app/service.clj")))))

(deftest a-retry-that-writes-nothing-reports-nothing
  ;; A Tester retry started in a worktree holding the first attempt's file,
  ;; wrote nothing, and reported that file, because git status lists it either
  ;; way.
  (let [dir (git-repo)
        _ (spit (str (fs/path dir "src/app/service.clj")) "(ns app.service)\n")
        quiet (stub [(text "the REPL stopped answering, so I wrote nothing")]
                    #(runner/run-agent (runner/api-runner (profile %)) :coder (packet dir)))
        rewrote (stub [(write-call "src/app/service.clj" "(ns app.service)\n(defn f [x] x)\n")
                       (text "done")]
                      #(runner/run-agent (runner/api-runner (profile %)) :coder (packet dir)))]
    (is (= [] (:files quiet)) "the file was there before, and this dispatch did not touch it")
    (is (= ["src/app/service.clj"] (:files rewrote)) "a retry that changes it still reports it")))

(deftest written-since-is-what-changed-while-the-dispatch-ran
  (is (= ["b.clj" "c.clj"]
         (runner/written-since {"a.clj" "same" "b.clj" "old"}
                               {"a.clj" "same" "b.clj" "new" "c.clj" "created"})))
  (is (= [] (runner/written-since {"a.clj" "x"} {"a.clj" "x"})))
  (is (= ["a.clj"] (runner/written-since nil {"a.clj" "x"})) "no snapshot is an empty one")
  (is (= {} (runner/worktree-snapshot nil))))

(deftest a-refused-write-does-not-appear-in-files
  ;; write_file refuses a path outside :files/target, and because :files comes
  ;; from git the result says so even though the model believes it succeeded.
  (let [dir (git-repo)
        r (stub [(write-call "src/app/store.clj" "CLOBBERED")
                 (text "done")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder (packet dir)))]
    (is (= [] (:files r)))
    (is (= "(ns app.store)\n" (slurp (str (fs/path dir "src/app/store.clj")))))))

;; ---------------------------------------------------------------------------
;; cost, and what is not known
;; ---------------------------------------------------------------------------

(deftest a-cost-nothing-reported-is-nil-not-zero
  ;; The stub has no generation endpoint, so no completion has a cost. Zero
  ;; would read as free; the report renders nil as an unbracketed em-dash.
  (let [r (stub [(text "hi")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder
                                   (packet (git-repo))))]
    (is (nil? (:cost r)))
    (is (= 0 (get-in r [:runner/meta :cost-known])))
    (is (= 1 (get-in r [:runner/meta :completions])))
    (is (= 14 (get-in r [:runner/meta :tokens])) "tokens are still measured")))

(deftest a-priced-role-computes-a-cost-and-says-so
  ;; Direct to Anthropic there is no generation record. With list prices in the
  ;; profile the cost is usage x price, marked :list-price, with the counts and
  ;; the rates beside it so the record can re-derive it.
  (let [pricing {:per-mtok {:in 10 :out 50 :cache-write-5m 12.5 :cache-write-1h 20 :cache-read 0.25}
                 :source "https://platform.claude.com/docs/en/about-claude/pricing" :as-of "2026-09-14"}
        priced (fn [endpoint] (assoc-in (profile endpoint) [:roles :coder :pricing] pricing))
        r (stub [(text "hi")]
                #(runner/run-agent (runner/api-runner (priced %)) :coder (packet (git-repo))))]
    ;; the stub reports 10 prompt and 4 completion tokens
    (is (< (Math/abs (- 0.0003 (:cost r))) 1e-12))
    (is (= :list-price (get-in r [:runner/meta :cost-source])))
    (is (= {:in 10 :out 4} (get-in r [:runner/meta :usage])))
    (is (= "2026-09-14" (get-in r [:runner/meta :pricing :as-of])))
    (is (shapes/valid-result? r)))
  (testing "without :pricing, no cost and no source, as before"
    (let [r (stub [(text "hi")]
                  #(runner/run-agent (runner/api-runner (profile %)) :coder (packet (git-repo))))]
      (is (nil? (:cost r)))
      (is (nil? (get-in r [:runner/meta :cost-source])))
      (is (not (contains? (:runner/meta r) :pricing))))))

(deftest a-request-sent-again-is-counted-in-runner-meta
  ;; A rate limit is retried, and how many times is a fact about the endpoint
  ;; the record keeps.
  (let [r (stub [{:error {:message "temporarily rate-limited upstream" :code 429}} (text "hi")]
                #(runner/run-agent (runner/api-runner (profile %) {:retry {:attempts 3 :interval-ms 1}})
                                   :coder (packet (git-repo))))]
    (is (= :done (:status r)))
    (is (= 1 (get-in r [:runner/meta :retries])))))

(deftest everything-provider-specific-lives-under-runner-meta
  ;; AgentResult is closed. Upstream, five keys accreted onto the top level
  ;; over two months and the orchestrator branched on all of them.
  (let [r (stub [(text "hi")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder
                                   (packet (git-repo))))]
    (is (= #{:status :files :stdout :cost :runner/meta} (set (keys r))))
    (is (every? (set (keys (:runner/meta r)))
                [:model :iterations :capped? :completions :tool-calls :generation-ids
                 :ms-completion :ms-provenance :ms-tools :service-tier :transcript :retries]))
    (is (= [{:text "hi" :calls []}] (get-in r [:runner/meta :transcript]))
        "the transcript rides in meta; the result stays closed")))

;; ---------------------------------------------------------------------------
;; failure
;; ---------------------------------------------------------------------------

(deftest the-coder-and-reviewer-are-told-what-a-dependent-is-for
  ;; A dependent in :files/context is only a path unless someone says why it is
  ;; there. A Coder once broke one it was never shown; now it is shown, and told.
  (is (str/includes? (:coder runner/deliverables) "the files that require it, and they must keep working"))
  (is (str/includes? (:reviewer runner/deliverables) "the files in :files/context that require it still work")))

(deftest a-note-travels-and-a-final-message-does-not
  ;; The channel was never missing, it was PROSE. One early Coder said the
  ;; contract was silent about dividing 7 by 2 — in :stdout, which nothing
  ;; reads. A note is captured because `converse!` already keeps every call.
  (let [note-call {:id "g"
                   :choices [{:message {:content nil
                                        :tool_calls [{:id "c1" :type "function"
                                                      :function {:name "note"
                                                                 :arguments (json/generate-string
                                                                             {:text "the contract is silent about division by zero"})}}]}}]
                   :usage {:prompt_tokens 1 :completion_tokens 1}}
        r (stub [note-call (text "done")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder
                                   (packet (git-repo))))]
    (is (= ["the contract is silent about division by zero"] (:notes r)))
    (is (shapes/valid-result? r) "and AgentResult is still closed around it")))

(deftest no-notes-means-no-key-rather-than-an-empty-one
  ;; :notes is optional. An empty vector on every result would make `seq` the
  ;; test everywhere instead of presence, for no gain.
  (let [r (stub [(text "hi")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder
                                   (packet (git-repo))))]
    (is (not (contains? r :notes)))))

(deftest a-retry-prompt-leads-with-why
  ;; An early run put the gate output in the opening message by hand because
  ;; nothing in TaskPacket could carry it. Buried under the packet it would compete with
  ;; the packet for attention; the whole point is that this attempt differs.
  (let [p (harness.contract.packet/for-retry
           (assoc (packet ".") :task/role :coder) 2
           [{:feedback/from :gate :feedback/text "lint failed:\nunused binding x"}
            {:feedback/from :tester :feedback/text "the contract omits an empty input"}])
        out (runner/packet-prompt p)]
    (is (str/includes? out "THIS IS ATTEMPT 2"))
    (is (< (str/index-of out "THIS IS ATTEMPT") (str/index-of out "Your workspace"))
        "before the packet, not after it")
    (is (str/includes? out "— from the gate:"))
    (is (str/includes? out "— from the tester:"))
    (is (str/includes? out "unused binding x"))))

(deftest a-first-attempt-says-nothing-about-attempts
  (let [out (runner/packet-prompt (assoc (packet ".") :task/role :coder))]
    (is (not (str/includes? out "ATTEMPT")))))

(deftest a-writing-role-is-told-to-edit-a-written-file-not-resend-it
  ;; The tool's description alone did not stop whole-file rewrites over one
  ;; lint warning; the sentence sits beside the deliverable that names write_file.
  (doseq [role [:coder :tester]]
    (is (str/includes? (runner/packet-prompt (assoc (packet ".") :task/role role))
                       "change it with edit_file")
        (name role)))
  (is (not (str/includes? (runner/packet-prompt (assoc (packet ".") :task/role :reviewer))
                          "edit_file"))
      "the Reviewer has no such tool and is not told about one"))

(deftest a-dead-endpoint-is-a-failed-result-not-a-throw
  (let [r (runner/run-agent (runner/api-runner (profile "http://127.0.0.1:1")
                                               {:timeout-ms 300})
                            :coder (packet (git-repo)))]
    (is (= :failed (:status r)))
    (is (= [] (:files r)) "a failed result reports no files")))

(deftest an-exception-anywhere-inside-becomes-a-failed-result
  ;; The catch-all. `adapter/request` throws when a role names a key variable
  ;; that is not exported — exactly what `bb profile` warns about — and that
  ;; throw is raised OUTSIDE the http try in `converse!`, so only the runner's
  ;; own catch stands between it and a lost dispatch. Mutation found nothing
  ;; exercising it.
  (let [p (assoc-in (profile "http://127.0.0.1:1")
                    [:roles :coder :key-env] "HARNESS_DEFINITELY_UNSET")
        r (runner/run-agent (runner/api-runner p) :coder (packet (git-repo)))]
    (is (= :failed (:status r)))
    (is (= [] (:files r)))
    (is (= :dispatch-threw (get-in r [:runner/meta :error :harness/error])))
    (is (= "HARNESS_DEFINITELY_UNSET"
           (get-in r [:runner/meta :error :data :key-env]))
        "and the variable's NAME survives into the run log, never its value")))

(deftest a-role-the-profile-does-not-have-fails-cleanly
  (let [r (runner/run-agent (runner/api-runner {:seat :claude :roles {}})
                            :coder (packet (git-repo)))]
    (is (= :failed (:status r)))
    (is (str/includes? (:stdout r) "no coder role"))))

;; ---------------------------------------------------------------------------
;; the reviewer
;; ---------------------------------------------------------------------------

(deftest the-reviewer-gets-no-tool-it-was-told-not-to-use
  (let [dir (git-repo)
        rev (-> (packet dir) (dissoc :files/target :repl/port) (assoc :task/role :reviewer))
        seen (atom nil)
        stop (srv/run-server
              (fn [req]
                (reset! seen (json/parse-string (slurp (:body req)) true))
                {:status 200 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string (text "looks fine"))})
              {:port 0 :legacy-return-value? false})]
    (try
      (let [r (runner/run-agent
               (runner/api-runner (profile (str "http://127.0.0.1:" (srv/server-port stop))))
               :reviewer rev)]
        (is (= [] (:files r)))
        (is (= ["note" "read_file"] (mapv #(get-in % [:function :name]) (:tools @seen)))
            "no write_file, no nrepl_eval — it has neither a target nor a REPL"))
      (finally (srv/server-stop! stop)))))

(defn- review
  "One Reviewer dispatch over a stub model that answers `reply`."
  [reply]
  (let [rev (-> (packet (git-repo)) (dissoc :files/target :repl/port) (assoc :task/role :reviewer))]
    (stub [(text reply)]
          #(runner/run-agent (runner/api-runner (profile %)) :reviewer rev))))

(deftest the-reviewers-verdict-block-is-parsed-into-runner-meta
  (testing "the Reviewer is asked for one, after its findings"
    (is (str/includes? (:reviewer runner/deliverables) "\"verdict\": \"approve\" | \"reject\""))
    (is (str/includes? (:reviewer runner/deliverables) "AFTER your findings")))
  (testing "approve, with no reasons"
    (let [r (review "No findings.\n```json\n{\"verdict\": \"approve\", \"reasons\": []}\n```")]
      (is (= :done (:status r)))
      (is (= {:verdict :approve :reasons []} (get-in r [:runner/meta :verdict])))
      (is (str/starts-with? (:stdout r) "No findings.") "the findings stay whole in :stdout")
      (is (shapes/valid-result? r) "and AgentResult stays closed: the verdict rides in meta")))
  (testing "reject, the LAST block when the findings quote one"
    (let [r (review (str "FINDING: the diff returns\n```json\n{\"verdict\": \"approve\"}\n```\nwhich is wrong.\n"
                         "```json\n{\"verdict\": \"Reject\", \"reasons\": [\"breaks target 2\", \"\"]}\n```"))]
      (is (= {:verdict :reject :reasons ["breaks target 2"]} (get-in r [:runner/meta :verdict]))
          "case-folded, and a blank reason is not a reason"))))

(deftest a-review-with-no-verdict-block-is-still-a-done-dispatch
  ;; Upstream fails the dispatch. The findings were paid for and are usually
  ;; fine; what is missing is only how the LOOP reads them, so the loop stops.
  (doseq [[why reply] {"no block at all" "Looks fine to me."
                       "a block that does not parse" "ok\n```json\n{verdict: approve\n```"
                       "a verdict that is neither word" "ok\n```json\n{\"verdict\": \"maybe\"}\n```"
                       "a block with no verdict key" "ok\n```json\n{\"reasons\": [\"x\"]}\n```"}]
    (testing why
      (let [r (review reply)]
        (is (= :done (:status r)))
        (is (contains? (:runner/meta r) :verdict) "the key is there, so nil means none was given")
        (is (nil? (get-in r [:runner/meta :verdict])))))))

(deftest only-the-reviewer-has-a-verdict
  (let [r (stub [(text "done\n```json\n{\"verdict\": \"approve\"}\n```")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder (packet (git-repo))))]
    (is (not (contains? (:runner/meta r) :verdict)))))

;; ---------------------------------------------------------------------------
;; the prompt
;; ---------------------------------------------------------------------------

(deftest the-rules-and-the-packet-both-reach-the-model
  (let [dir (git-repo)
        seen (atom nil)
        stop (srv/run-server
              (fn [req]
                (reset! seen (json/parse-string (slurp (:body req)) true))
                {:status 200 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string (text "ok"))})
              {:port 0 :legacy-return-value? false})]
    (try
      (runner/run-agent
       (runner/api-runner (profile (str "http://127.0.0.1:" (srv/server-port stop))))
       :coder (harness.contract.packet/coder-packet
               {:task/id "t-01" :task/title "A task"
                :blueprint/slice {:shapes [] :interfaces ['(f [x])]}
                :files/impl ["src/app/service.clj"] :files/context ["src/app/store.clj"]
                :layer/name :service :property-targets ["f is total over every x"]}
               {:worktree/path dir :nrepl/port 7777}))
      (let [[sys user] (:messages @seen)]
        (is (= "system" (:role sys)))
        (is (str/includes? (:content sys) "Prototype forms in the live nREPL")
            "the rule source is the system prompt, rendered for this audience")
        (is (str/includes? (:content sys) "boundaries of layer service")
            "with this packet's layer substituted in too")
        (is (str/includes? (:content sys) "clj-nrepl-eval -p 7777")
            "with this dispatch's own port substituted in")
        (is (str/includes? (:content user) "f is total over every x")
            "a property target reaches the CODER's model, not only its packet")
        (is (str/includes? (:content user) "satisfying every :property-targets entry")
            "and the Coder is told what to do with it")
        (is (str/includes? (:content user) "src/app/service.clj")
            "and the packet arrives verbatim, not paraphrased")
        (is (str/includes? (:content user) "THE FILE MAY NOT EXIST YET")
            "and the role is told what finishing looks like, which neither the
             packet nor the rules ever say"))
      (finally (srv/server-stop! stop)))))

;; ---------------------------------------------------------------------------
;; capped, and nothing written
;; ---------------------------------------------------------------------------

(defn- read-call
  "A completion that calls `read_file` and never stops — the shape a model that
  spends its whole budget in the REPL leaves behind."
  [path]
  {:id "g3"
   :choices [{:message {:content nil
                        :tool_calls [{:id "c1" :type "function"
                                      :function {:name "read_file"
                                                 :arguments (json/generate-string {:path path})}}]}}]
   :usage {:prompt_tokens 10 :completion_tokens 4}})

(deftest the-rounds-limit-is-the-profiles-when-a-role-sets-one
  ;; The loop builds the runner with no options, so nothing in a project reached the
  ;; cap: a Tester ended on the harness's 24 three times on one contract. The number
  ;; is the role block's `:max-rounds`; absent, the default applies as before.
  (let [dir (git-repo)
        run (fn [max-rounds]
              (stub (vec (repeat 10 (read-call "src/app/store.clj")))
                    #(runner/run-agent (runner/api-runner (cond-> (profile %)
                                                            max-rounds (assoc-in [:roles :coder :max-rounds] max-rounds)))
                                       :coder (packet dir))))]
    (let [r (run 2)]
      (is (= 2 (get-in r [:runner/meta :iterations])) "stopped at the role's limit")
      (is (true? (get-in r [:runner/meta :capped?]))))
    (let [r (run 4)]
      (is (= 4 (get-in r [:runner/meta :iterations]))))))

(deftest a-capped-coder-or-tester-that-wrote-nothing-is-a-failed-dispatch
  ;; A capped dispatch that DID write files still passes as :done — the gates
  ;; decide whether the output was any good, and discarding salvageable work is
  ;; the wrong default. But a role whose deliverable is a file, capped with no
  ;; file, is not a dispatch the loop should carry on from: assembly refuses the
  ;; missing target one step later with nothing to say why.
  (let [dir (git-repo)
        run (fn [role opts]
              (stub [(read-call "src/app/store.clj")]
                    #(runner/run-agent (runner/api-runner (profile %) opts)
                                       role (assoc (packet dir) :task/role role))))]
    (testing "the Coder"
      (let [r (run :coder {:max-iterations 2})]
        (is (true? (get-in r [:runner/meta :capped?])))
        (is (= [] (:files r)))
        (is (= :failed (:status r)))
        (is (str/includes? (:stdout r) "written no file"))
        (is (shapes/valid-result? r))))
    (testing "the Tester, the same"
      (is (= :failed (:status (run :tester {:max-iterations 2})))))
    (testing "the Reviewer is exempt — it writes nothing by design"
      (is (= :done (:status (run :reviewer {:max-iterations 2})))))
    (testing "and a capped dispatch that DID write a file is still :done"
      (let [d (git-repo)
            r (stub [(write-call "src/app/service.clj" "(ns app.service)\n")
                     (read-call "src/app/store.clj")]
                    #(runner/run-agent (runner/api-runner (profile %) {:max-iterations 3})
                                       :coder (packet d)))]
        (is (true? (get-in r [:runner/meta :capped?])))
        (is (= ["src/app/service.clj"] (:files r)))
        (is (= :done (:status r)) "the gates decide whether it was any good")))))

;; ---------------------------------------------------------------------------
;; the tester is told when to stop
;; ---------------------------------------------------------------------------

(deftest the-tester-is-told-when-to-stop-and-what-not-to-check
  ;; Two Testers in a row spent their budget after they had tests worth keeping:
  ;; one re-ran its file against the throwing stub to the cap, the next never
  ;; wrote at all, bracket-checking its file as an escaped string in the REPL.
  (let [d (:tester runner/deliverables)]
    (is (str/includes? d "Call write_file EARLY"))
    (testing "and what to do with a target it has nothing to test with: not search, write and note"
      (is (str/includes? d "IF A TARGET CANNOT BE TESTED WITH WHAT YOU WERE GIVEN"))
      (is (str/includes? d "do NOT go searching for it"))
      (is (str/includes? d "write what you have, then leave a note saying what stopped you")))
    (is (str/includes? d "Do NOT check the file's brackets"))
    (is (str/includes? d "stubs that throw on purpose"))))

;; ---------------------------------------------------------------------------
;; every reason names what it breaks
;; ---------------------------------------------------------------------------

(deftest the-reviewer-is-asked-what-each-finding-breaks
  ;; `reject` was defined in prose from the start and did not hold: one Reviewer
  ;; rejected four times in eight tasks on findings that broke no target.
  (let [d (:reviewer runner/deliverables)]
    (is (str/includes? d "\"breaks\": \"target 3\" | \"slice\" | \"dependent\" | \"none\""))
    (is (str/includes? d "ONLY a finding that breaks a target, the slice or a dependent can justify it"))
    (is (str/includes? d "If every finding you have breaks \"none\", your verdict is `approve`"))))

(deftest the-reviewer-is-told-that-an-input-which-cannot-occur-breaks-nothing
  ;; One Reviewer rejected on a value that was valid by its type and impossible to write in the
  ;; project's only input format; a round was paid. NARROW: twice an Architect had waved away an
  ;; input that COULD arrive, so only a source the rules name counts.
  (let [d (:reviewer runner/deliverables)]
    (is (str/includes? d "The type decides what a value IS"))
    (is (str/includes? d "ONLY when those rules name the source"))
    (is (str/includes? d "never on your own sense that an input is unlikely"))
    (is (str/includes? d "Where the rules name no source, every value the type admits can arrive"))))

(deftest a-reason-is-a-finding-and-what-it-breaks
  (testing "the map shape: the finding with what it breaks after it, and :breaks beside the strings"
    (is (= {:verdict :reject
            :reasons ["load-site ignores trailing forms [breaks: target 13]"
                      "a broad catch hides an I/O failure [breaks: none]"]
            :breaks ["target 13" "none"]}
           (runner/review-verdict
            (str "findings...\n```json\n"
                 "{\"verdict\": \"reject\", \"reasons\": ["
                 "{\"finding\": \"load-site ignores trailing forms\", \"breaks\": \"Target 13\"},"
                 "{\"finding\": \"a broad catch hides an I/O failure\", \"breaks\": \"none\"}]}\n```")))))
  (testing "an approval carrying a finding that breaks nothing — the case the field exists for"
    (is (= {:verdict :approve :reasons ["a broad catch hides an I/O failure [breaks: none]"] :breaks ["none"]}
           (runner/review-verdict
            "```json\n{\"verdict\": \"approve\", \"reasons\": [{\"finding\": \"a broad catch hides an I/O failure\", \"breaks\": \"none\"}]}\n```"))))
  (testing "the older shape, bare strings, still reads: a reason with no :breaks, and no :breaks key"
    (is (= {:verdict :reject :reasons ["breaks target 2"]}
           (runner/review-verdict "```json\n{\"verdict\": \"reject\", \"reasons\": [\"breaks target 2\"]}\n```"))))
  (testing "a reason map with no finding is not a reason"
    (is (= {:verdict :approve :reasons []}
           (runner/review-verdict "```json\n{\"verdict\": \"approve\", \"reasons\": [{\"breaks\": \"none\"}]}\n```")))))
