(ns harness.models.code-review-tools
  "The code reviewer's tools when the loop could give it a REPL: `read_file` and `note`, as
  `harness.models.tools` has them, and `nrepl_eval` - run in the review sandbox
  (`harness.loop.review-repl`), a container with a copy of the tree nobody merges and no
  network, never on the host.

  WHY A REVIEWER EVALUATES: one project's code reviewer rejected a packet three times on one
  wrong belief about `clojure.string/trim`, which one evaluated form ends. A finding that rests
  on what a library does is checked before it is a finding (the person, 2026-10-27).

  THE SAME RESULT AS EVERY OTHER `nrepl_eval`: the output goes through `tools/eval-result`, so
  a timeout, a failed form and an unreachable nREPL read as they do for the Coder. `exec` is
  the one call that leaves the process; tests replace it."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]
   [harness.models.tools :as tools]))

(def app-dir
  "Where the copy sits in the sandbox: the security review's sandbox's folder."
  "/work/app")

(defn eval-argv
  "The command that evaluates `code` in the sandbox `name`'s nREPL on `port`."
  [name port code]
  ["docker" "exec" "-w" app-dir name "clj-nrepl-eval" "-p" (str port) code])

(defn exec
  "Run `argv` and return `{:exit :out :err}`; never throws."
  [argv]
  (try (select-keys (apply p/shell {:out :string :err :string :continue true} argv) [:exit :out :err])
       (catch Exception e {:exit 127 :out "" :err (ex-message e)})))

(defn- nrepl-eval
  [{:keys [sandbox port]} {:keys [code]}]
  (cond
    (str/blank? code) (tools/err "code is required")
    (or (nil? sandbox) (nil? port)) (tools/err "no REPL is attached to this review")
    :else (tools/eval-result (exec (eval-argv sandbox port code)))))

(def registry
  "The reviewer's tools with a REPL: name -> {:description :schema :fn}, `harness.models.tools`'
  shape, so `declarations` and `invoke` take it as they take the security review's."
  (merge (select-keys tools/specs ["read_file" "note"])
         {"nrepl_eval"
          {:description (str "Evaluate Clojure in a REPL over a copy of the code under review - the "
                             "application's dependencies, no network, nothing merged from it. Check a "
                             "claim about what a library or the code does before you make it a finding.")
           :schema {:type "object"
                    :properties {:code {:type "string" :description "One or more forms to evaluate."}}
                    :required ["code"]}
           :fn #'nrepl-eval}}))

(def offered
  "The tool names a code reviewer with a REPL is offered."
  #{"read_file" "note" "nrepl_eval"})
