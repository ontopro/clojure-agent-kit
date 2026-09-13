(ns harness.api-runner-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [harness.packet]
   [harness.runner :as runner]
   [harness.runner-check :as check]
   [harness.shapes :as shapes]
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
  ;; Run D4: the Tester's :files came back as [the generated stub, its own
  ;; test file]. git cannot tell a stub the harness wrote from something the
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

(deftest everything-provider-specific-lives-under-runner-meta
  ;; AgentResult is closed. Upstream, five keys accreted onto the top level
  ;; over two months and the orchestrator branched on all of them.
  (let [r (stub [(text "hi")]
                #(runner/run-agent (runner/api-runner (profile %)) :coder
                                   (packet (git-repo))))]
    (is (= #{:status :files :stdout :cost :runner/meta} (set (keys r))))
    (is (every? (set (keys (:runner/meta r)))
                [:model :iterations :capped? :completions :tool-calls :generation-ids]))))

;; ---------------------------------------------------------------------------
;; failure
;; ---------------------------------------------------------------------------

(deftest a-note-travels-and-a-final-message-does-not
  ;; The channel was never missing, it was PROSE. Run 4's Coder said the
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
  ;; D4 put the gate output in the opening message by hand because nothing in
  ;; TaskPacket could carry it. Buried under the packet it would compete with
  ;; the packet for attention; the whole point is that this attempt differs.
  (let [p (harness.packet/for-retry
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
       :coder (packet dir))
      (let [[sys user] (:messages @seen)]
        (is (= "system" (:role sys)))
        (is (str/includes? (:content sys) "Prototype forms in the live nREPL")
            "the rule source is the system prompt, rendered for this audience")
        (is (str/includes? (:content sys) "boundaries of layer service")
            "with this packet's layer substituted in too")
        (is (str/includes? (:content sys) "clj-nrepl-eval -p 7777")
            "with this dispatch's own port substituted in")
        (is (str/includes? (:content user) "src/app/service.clj")
            "and the packet arrives verbatim, not paraphrased")
        (is (str/includes? (:content user) "THE FILE MAY NOT EXIST YET")
            "and the role is told what finishing looks like, which neither the
             packet nor the rules ever say"))
      (finally (srv/server-stop! stop)))))
