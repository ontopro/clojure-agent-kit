(ns harness.tools-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.doctor :as doctor]
   [harness.tools :as tools]))

(defn- workspace []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "src" "app"))
    (spit (str (fs/path dir "src" "app" "store.clj")) "(ns app.store)\n")
    dir))

(defn- ctx [dir] {:dir dir :targets ["src/app/service.clj"] :port nil})

(defn- call [dir nm args] (tools/invoke (ctx dir) {:id "c1" :name nm :args args}))

;; ---------------------------------------------------------------------------
;; nothing throws, ever
;; ---------------------------------------------------------------------------

(deftest every-failure-comes-back-as-a-result-the-model-can-read
  ;; §10 lesson 8. A dispatch that dies because a model asked for a missing
  ;; file has turned a recoverable turn into a lost task.
  (let [dir (workspace)]
    (doseq [[nm args why]
            [["read_file" {:path "nope.clj"} "a file that is not there"]
             ["read_file" {} "a missing argument"]
             ["write_file" {:path "src/app/other.clj" :content "x"} "a path outside :files/target"]
             ["write_file" {:path "src/app/service.clj"} "no content"]
             ["nrepl_eval" {:code "(+ 1 1)"} "no REPL attached"]
             ["no_such_tool" {} "a hallucinated tool"]
             ["read_file" "{not json" "arguments that are not JSON"]]]
      (let [r (call dir nm args)]
        (is (true? (:error? r)) why)
        (is (str/starts-with? (:content r) "ERROR: ") why)
        (is (= "c1" (:id r)) "the call id survives, or the model cannot match the result")))))

(deftest an-unknown-tool-is-told-what-does-exist
  (is (str/includes? (:content (call (workspace) "grep" {}))
                     "read_file, write_file")))

;; ---------------------------------------------------------------------------
;; reading and writing
;; ---------------------------------------------------------------------------

(deftest read-file-returns-contents
  (let [r (call (workspace) "read_file" {:path "src/app/store.clj"})]
    (is (false? (:error? r)))
    (is (= "(ns app.store)\n" (:content r)))))

(deftest write-file-refuses-a-path-the-packet-did-not-claim
  ;; Enforced here as well as at assembly. An agent that learns at the END of a
  ;; task that half its work is refused has already spent the task.
  (let [dir (workspace)
        refused (call dir "write_file" {:path "src/app/store.clj" :content "x"})
        allowed (call dir "write_file" {:path "src/app/service.clj" :content "(ns app.service)"})]
    (is (true? (:error? refused)))
    (is (str/includes? (:content refused) ":files/target"))
    (is (= "(ns app.store)\n" (slurp (str (fs/path dir "src/app/store.clj"))))
        "and the refused write did not happen")
    (is (false? (:error? allowed)))
    (is (= "(ns app.service)" (slurp (str (fs/path dir "src/app/service.clj")))))))

(deftest a-path-cannot-climb-out-of-the-workspace
  ;; :files/target is compared as strings, so `../..` escapes the worktree
  ;; while never appearing in the target list.
  ;;
  ;; THE ESCAPE HAS TO BE THE ONLY THING WRONG. An earlier version of this
  ;; test used a non-existent path and a path outside :files/target, so it
  ;; passed with the containment check DELETED — the later checks caught both
  ;; for other reasons. Mutation found it. Here the target file exists and the
  ;; written path is in :files/target, so containment is all that stands.
  (let [outer (str (fs/create-temp-dir))
        dir (str (fs/path outer "ws"))
        _ (fs/create-dirs (fs/path dir "src"))
        _ (spit (str (fs/path outer "secret.txt")) "SECRET")
        escape "../secret.txt"]
    (testing "reading a file that exists, and is readable, and is outside"
      (is (= "SECRET" (slurp (str (fs/path outer "secret.txt"))))
          "the file really is there, so only containment can refuse it")
      (let [r (tools/invoke {:dir dir :targets []} {:id "c" :name "read_file"
                                                    :args {:path escape}})]
        (is (true? (:error? r)))
        (is (str/includes? (:content r) "outside the workspace"))))
    (testing "writing to an escaping path that IS in :files/target"
      (let [r (tools/invoke {:dir dir :targets [escape]}
                            {:id "c" :name "write_file"
                             :args {:path escape :content "OWNED"}})]
        (is (true? (:error? r)))
        (is (str/includes? (:content r) "outside the workspace"))
        (is (= "SECRET" (slurp (str (fs/path outer "secret.txt"))))
            "and the file outside was not overwritten")))))

(deftest an-unexpected-exception-inside-a-tool-is-still-a-result
  ;; The catch-all backstop. Every tool returns data on the paths it knows
  ;; about; this is for the ones it does not. Reading a DIRECTORY passes the
  ;; exists? check and then throws inside slurp — mutation showed nothing was
  ;; exercising this, so a future tool could kill a dispatch.
  (let [r (call (workspace) "read_file" {:path "src"})]
    (is (true? (:error? r)))
    (is (str/starts-with? (:content r) "ERROR: "))
    (is (= "c1" (:id r)))))

(deftest output-is-clipped-and-says-so
  ;; Tool output is charged as input tokens on every later turn, so an
  ;; unbounded read compounds. A model that cannot tell it got half a file will
  ;; reason confidently about the missing half.
  (let [dir (workspace)
        _ (spit (str (fs/path dir "big.txt")) (apply str (repeat 30000 "x")))
        r (call dir "read_file" {:path "big.txt"})]
    (is (str/includes? (:content r) "[truncated at 20000 characters of 30000]"))))

;; ---------------------------------------------------------------------------
;; nrepl_eval: what comes back when the evaluated code fails
;; ---------------------------------------------------------------------------

;; Every `{:exit :out :err}` below is what `clj-nrepl-eval` (bbin d341c23)
;; printed against a live Clojure 1.12.5 nREPL on 2026-09-15, trimmed.

(def reachable "The nREPL is reachable")

(deftest a-form-that-throws-is-an-error-the-model-can-read
  ;; D20: the stub the Tester was given throws by design; clj-nrepl-eval exited
  ;; 0 with the error on stderr, and the model got "".
  (let [r (tools/eval-result
           {:exit 0 :out ""
            :err (str "Execution error (AssertionError) at sandbox.constants/fold-constants (constants.clj:12).\n"
                      "not implemented — generated stub: fold-constants\n")})]
    (is (true? (:error? r)))
    (is (str/starts-with? (:content r) "ERROR: "))
    (is (str/includes? (:content r) "not implemented — generated stub: fold-constants"))
    (is (str/includes? (:content r) reachable)
        "a failed form must not read like a dead REPL")))

(deftest every-clojure-main-error-phase-is-flagged
  ;; One first line per phase of clojure.main/ex-str, 1.12.5.
  (doseq [line ["Syntax error reading source at (sandbox/constants_test.clj:99:53)."
                "Syntax error macroexpanding clojure.core/let at (REPL:1:1)."
                "Unexpected error (ClassCastException) macroexpanding foo at (REPL:1:1)."
                "Syntax error compiling at (REPL:0:0)."
                "Syntax error (ClassNotFoundException) compiling at (REPL:1:1)."
                "Unexpected error compiling at (REPL:1:1)."
                "Error reading eval result (ClassCastException) at foo (REPL:1)."
                "Error printing return value at user$eval7747$reify__7748/toString (NO_SOURCE_FILE:1)."
                "Execution error (ExceptionInfo) at user/eval7743 (REPL:1)."
                "Execution error - invalid arguments to foo at (REPL:1)."]]
    (is (true? (:error? (tools/eval-result {:exit 0 :out "" :err (str line "\ncause\n")})))
        line)))

(deftest an-error-after-other-output-is-still-flagged-and-both-streams-come-back
  ;; Two forms: the first printed a warning and returned, the second threw.
  (let [r (tools/eval-result {:exit 0 :out "=> nil\n*======== user | clj ========*\n"
                              :err "warn first\nExecution error (ExceptionInfo) at user/eval7753 (REPL:1).\nthen boom\n"})]
    (is (true? (:error? r)))
    (is (str/includes? (:content r) "warn first"))
    (is (str/includes? (:content r) "then boom"))
    (is (str/includes? (:content r) "=> nil"))
    (is (str/includes? (:content r) "Forms before it may have run"))))

(deftest stderr-without-an-error-is-shown-but-not-flagged
  ;; A reflection warning goes to stderr on a successful evaluation.
  (let [r (tools/eval-result {:exit 0 :out "=> :ok\n*======== user | clj ========*\n"
                              :err "Reflection warning, NO_SOURCE_PATH:1:38 - reference to field length can't be resolved.\n"})]
    (is (false? (:error? r)))
    (is (str/includes? (:content r) "=> :ok"))
    (is (str/includes? (:content r) "Reflection warning"))))

(deftest a-clean-result-is-exactly-stdout
  (let [out "=> 2\n*======== user | clj ========*\n"]
    (is (= {:error? false :content out} (tools/eval-result {:exit 0 :out out :err ""})))))

(deftest a-timeout-is-its-own-error
  ;; On stdout, exit 0, stderr empty: no stderr check sees it.
  (let [r (tools/eval-result {:exit 0 :err ""
                              :out "\n⚠️  Timeout hit, sending nREPL :interrupt …\n✋ Evaluation interrupted."})]
    (is (true? (:error? r)))
    (is (str/includes? (:content r) "timed out"))
    (is (str/includes? (:content r) reachable))))

(deftest an-unreachable-repl-says-so-and-not-that-the-code-failed
  (let [r (tools/eval-result {:exit 1 :out ""
                              :err "----- Error -----\nType:     java.net.ConnectException\nMessage:  Connection refused\n"})]
    (is (true? (:error? r)))
    (is (str/includes? (:content r) "could not be reached"))
    (is (str/includes? (:content r) "Connection refused"))
    (is (not (str/includes? (:content r) reachable)))))

(deftest a-long-failed-evaluation-is-clipped-and-keeps-its-error
  ;; Stderr goes first on a failure, so clipping cuts output, never the error.
  (let [r (tools/eval-result {:exit 0 :out (apply str (repeat 30000 "x"))
                              :err "Execution error (ExceptionInfo) at user/eval1 (REPL:1).\nboom\n"})]
    (is (true? (:error? r)))
    (is (str/includes? (:content r) "boom"))
    (is (str/includes? (:content r) "[truncated at 20000 characters of"))))

(deftest nrepl-eval-hands-clj-nrepl-eval-output-to-eval-result
  ;; Through invoke, so the wiring is tested and not only the function. The
  ;; non-zero exit is the branch that called a string as a function.
  (with-redefs [doctor/require-tool! (fn [_] nil)
                p/shell (fn [& _] {:exit 1 :out "" :err "Message:  Connection refused\n"})]
    (let [r (tools/invoke {:dir "." :port 7 :targets []}
                          {:id "c1" :name "nrepl_eval" :args {:code "(+ 1 1)"}})]
      (is (true? (:error? r)))
      (is (str/includes? (:content r) "Connection refused"))
      (is (not (str/includes? (:content r) "cannot be cast"))))))

;; ---------------------------------------------------------------------------
;; arguments arrive in two shapes
;; ---------------------------------------------------------------------------

(deftest arguments-may-be-a-json-string-or-a-map
  ;; OpenAI sends a string, Anthropic a decoded map. adapter deliberately does
  ;; not normalise: a malformed string is a TOOL failure the model can act on.
  (let [dir (workspace)]
    (is (= "(ns app.store)\n"
           (:content (call dir "read_file" "{\"path\":\"src/app/store.clj\"}"))))
    (is (= "(ns app.store)\n"
           (:content (call dir "read_file" {:path "src/app/store.clj"}))))))

;; ---------------------------------------------------------------------------
;; declarations
;; ---------------------------------------------------------------------------

(deftest the-same-tools-in-two-spellings
  (let [all (tools/for-role :coder)
        o (tools/declarations :openai all)
        a (tools/declarations :anthropic all)]
    (is (= ["note" "nrepl_eval" "read_file" "write_file"]
           (mapv #(get-in % [:function :name]) o)))
    (is (= ["note" "nrepl_eval" "read_file" "write_file"] (mapv :name a)))
    (testing "openai nests under :function, anthropic does not"
      (is (every? #(= "function" (:type %)) o))
      (is (every? :input_schema a))
      (is (not-any? :input_schema o)))))

(deftest a-declaration-says-what-the-tool-refuses
  ;; The description is prompt text: a model picks a tool from it, so what
  ;; write_file will not do belongs in it.
  (is (str/includes? (get-in (first (filter #(= "write_file" (get-in % [:function :name]))
                                            (tools/declarations :openai (tools/for-role :coder))))
                             [:function :description])
                     "REFUSED")))

(deftest a-reviewer-is-not-offered-a-tool-it-is-told-not-to-use
  ;; :review-read-only says the Reviewer writes nothing and has no REPL. Its
  ;; packet has no :files/target either, so write_file would refuse every
  ;; path — but refusing a request and never inviting it are different, and
  ;; a rule that forbids what the tools still offer is one waiting to be
  ;; broken.
  (is (= #{"read_file" "note"} (tools/for-role :reviewer))
      "it may still leave a note — structured findings are the point of the role")
  (is (= #{"read_file" "write_file" "nrepl_eval" "note"} (tools/for-role :coder)))
  (is (= ["note" "read_file"]
         (mapv :name (tools/declarations :anthropic (tools/for-role :reviewer))))))
