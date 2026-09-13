(ns harness.tools-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
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
