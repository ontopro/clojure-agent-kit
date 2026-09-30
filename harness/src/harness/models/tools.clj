;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.models.tools
  "The four things a dispatched agent may do in its workspace.

  READ, WRITE, EDIT, EVAL. `write_file` and `edit_file` REFUSE a path the packet
  did not claim, so `provision/scope-violations` is the second line and not the
  only one, and an agent is told at its FIRST write rather than after spending
  the whole task.

  EDIT EXISTS BECAUSE REWRITING WAS THE ONLY WAY TO CHANGE A FILE. With
  `write_file` alone, fixing one lint warning the after-write report named
  meant sending the whole namespace again, and the transcripts of the earlier
  builds show roles doing exactly that, often. It is also the incentive behind
  a Coder asked to change one expression rewriting the namespace around it:
  when the one tool is *replace the file*, that is what a model reaches for.

  `nrepl_eval` IS A SHELL, AND SAYING OTHERWISE WOULD BE A LIE. An earlier
  version of this docstring claimed it was not a shell and not a search. The
  first real dispatch disproved that in two turns: the model evaluated a
  clojure.java.shell/sh call running `find`, and went walking the filesystem.
  A Clojure REPL is arbitrary code execution — that is what makes it worth having, and
  there is no version of it that prototypes forms but cannot spawn a process.

  So the containment is NOT the tool list. It is the git worktree the agent
  was provisioned into, which is a real boundary, plus the rules, which are
  not — they are instructions to something that may ignore them. Anyone
  handing this a model they do not trust should be reading `harness.loop.provision`,
  not this list.

  A TOOL FAILURE IS DATA, NEVER A THROW. §10 lesson 8: a tool-call error
  returns to the model as the tool's result, so it can read what went wrong and
  try something else. A dispatch that dies because a model asked for a file
  that is not there has converted a recoverable turn into a lost task, and the
  retry budget pays for it.

  ARGUMENTS ARRIVE IN TWO SHAPES because the two APIs differ — a JSON string
  from the OpenAI shape, a decoded map from the Anthropic one. `invoke` accepts
  either. `harness.models.adapter` deliberately does not normalise it: a malformed
  argument string is a TOOL failure, reportable to the model, and parsing it
  one layer up would turn it into a parse error nobody can act on."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [harness.gates.repair :as repair]
   [harness.setup.doctor :as doctor]))

(def max-output
  "Tool output is charged for as input tokens on every subsequent turn, so an
  unbounded read is a bill that compounds. Truncation is announced in the
  result rather than silent, because a model that cannot tell it got half a
  file will reason confidently about the missing half."
  20000)

(defn- clip [s]
  (let [s (str s)]
    (if (<= (count s) max-output)
      s
      (str (subs s 0 max-output)
           "\n\n[truncated at " max-output " characters of " (count s) "]"))))

(defn- ok [s] {:error? false :content (clip s)})
;; Clipped too. An error used to be short — a missing file, a refused path —
;; until `nrepl_eval` started returning a failed evaluation's whole output
;; through here.
(defn- err [s] {:error? true :content (clip (str "ERROR: " s))})

;; ---------------------------------------------------------------------------
;; the tools
;; ---------------------------------------------------------------------------

(defn- within?
  "Whether `path` stays inside `dir` once resolved.

  `..` in a path is the whole reason this exists: `:files/target` is compared
  as strings, and `src/app/../../../etc/passwd` is not in the target list while
  still escaping the worktree."
  [dir path]
  (let [root (fs/normalize (fs/absolutize dir))
        p (fs/normalize (fs/absolutize (fs/path dir path)))]
    (str/starts-with? (str p) (str root))))

(defn- read-file
  [{:keys [dir]} {:keys [path]}]
  (cond
    (str/blank? path) (err "path is required")
    (not (within? dir path)) (err (str path " is outside the workspace"))
    (not (fs/exists? (fs/path dir path))) (err (str path " does not exist"))
    :else (ok (slurp (str (fs/path dir path))))))

(defn lint-warnings
  "clj-kondo's findings for the one file at `path` in `dir`, as its own
  `file:row:col: level: message` lines — `[]` when it is clean, nil when
  clj-kondo could not be run at all (absent, or it threw)."
  [dir path]
  (try
    (let [{:keys [out]} (p/shell {:dir dir :out :string :err :string :continue true}
                                 "clj-kondo" "--lint" path)]
      (->> (str/split-lines (str out))
           (filterv #(re-find #":\d+:\d+: (warning|error): " %))))
    (catch Exception _ nil)))

(defn- after-write
  "What `write_file` does to a Clojure file it has just written, and says
  about it: repair it as gate 0 would, lint it, and report both.

  GATE 0 AND THE LINT GATE, AT THE ONE MOMENT THE AUTHOR IS STILL THERE. Both
  still run after assembly, over the gate worktree, and those runs are the ones
  that count; this is the same check delivered early, the way a write-time
  hook delivers gate 0 to an interactive client.

  REPAIR, because what a machine can fix it should fix where the author will
  next look. It ran only in the gate worktree, so a role's own copy kept its
  bracket errors: a retried Tester was handed back a file that did not read
  and spent a whole dispatch hunting a bracket that had been repaired
  somewhere it could not see.

  LINT, because clj-kondo can only REPORT. An unrequired alias, an unused
  binding, a redundant `let` have no mechanical fix — the author must decide —
  and the lint gate reports them after the author has gone, so each one cost a
  round, a triage call and a slot under the retry cap. Told here, the author
  fixes it for a turn.

  ADVISORY, AND IT SAYS SO. One file linted in a role's worktree is not the
  whole project linted in the gate's, and the Tester's copy of the
  implementation is a stub. Anything that goes wrong in here is swallowed:
  the file is written either way, and the real gates are the floor."
  [dir path content]
  (let [file (str (fs/path dir path))
        repaired? (try (repair/repair! dir [path])
                       (not= content (slurp file))
                       (catch Exception _ false))
        warnings (lint-warnings dir path)]
    (str (when repaired?
           (str "\nREPAIRED ON WRITE: delimiters and/or formatting were fixed mechanically, "
                "so the file on disk now differs from what you sent. read_file it before you "
                "edit it again."))
         (cond
           (nil? warnings) ""
           (empty? warnings) "\nlint: clean."
           :else (str "\nLINT — the lint gate FAILS on warnings as well as errors. Fix these "
                      "and write the file again:\n" (str/join "\n" warnings)))
         (when (some? warnings)
           (str "\n(advisory: this looked at this one file; the gates run later over the "
                "whole project, and they are what counts.)")))))

(defn- write-file
  [{:keys [dir targets]} {:keys [path content]}]
  (cond
    (str/blank? path) (err "path is required")
    (nil? content) (err "content is required")
    (not (within? dir path)) (err (str path " is outside the workspace"))
    ;; The packet's own list, enforced here rather than only at assembly. An
    ;; agent that learns at the END of a task that half its work is refused has
    ;; spent the whole task; told at the first write, it can ask.
    (not (contains? (set targets) path))
    (err (str path " is not in this task's :files/target — you may write only "
              (str/join ", " targets)))
    :else (do (fs/create-dirs (fs/parent (fs/path dir path)))
              (spit (str (fs/path dir path)) content)
              (ok (str "wrote " path " (" (count content) " characters)"
                       (when (re-find #"\.clj[scx]?$" path)
                         (after-write dir path content)))))))

(defn- occurrences
  "How many times `needle` occurs in `s`, non-overlapping. Nil when the needle
  is empty: an empty match is everywhere, which is neither once nor never."
  [s needle]
  (when (seq needle)
    (loop [from 0 n 0]
      (if-let [i (str/index-of s needle from)]
        (recur (+ i (count needle)) (inc n))
        n))))

(def small-file
  "Below this many characters a file whose `old_text` did not match comes back
  whole in the refusal, so the model sees what gate 0 made of it without
  another turn. Above it, the model is told to `read_file` it. Well under
  `max-output`, since the refusal is charged as input on every later turn."
  8000)

(defn- edit-file
  "Replace `old_text`, which must occur exactly once in the file, by `new_text`.

  ONCE, OR NOTHING CHANGES. Zero matches and several matches both write
  nothing and say why: a replacement that lands on the wrong of two matches is
  a defect the author cannot see, and the gates find it a round later.

  ZERO MATCHES IS USUALLY GATE 0'S DOING. The file on disk is not the file
  the model sent: `after-write` repaired delimiters, reindented it and added
  a trailing newline, and the model's `old_text` is what it remembers
  writing. So the refusal carries the file as it is now when it is small
  enough, and the model can edit against that in its next turn.

  The same refusals as `write_file`, plus one: the file must exist, because
  an edit is a change to something and the tool for the first version is
  `write_file`. The same after-write, because the file is a Clojure file
  that just changed, whatever the tool."
  [{:keys [dir targets]} {:keys [path old_text new_text]}]
  (let [file (str (fs/path dir path))]
    (cond
      (str/blank? path) (err "path is required")
      (nil? old_text) (err "old_text is required")
      (empty? old_text) (err "old_text is empty — there is nothing to match")
      (nil? new_text) (err "new_text is required")
      (not (within? dir path)) (err (str path " is outside the workspace"))
      (not (contains? (set targets) path))
      (err (str path " is not in this task's :files/target — you may edit only "
                (str/join ", " targets)))
      (not (fs/exists? file))
      (err (str path " does not exist — edit_file changes a file that is there; "
                "write_file it first"))
      :else
      (let [content (slurp file)
            n (occurrences content old_text)]
        (cond
          (zero? n)
          (err (str "old_text was not found in " path "; nothing was changed. The file "
                    "on disk may differ from what you sent: a Clojure file is repaired and "
                    "reformatted as it is written. "
                    (if (<= (count content) small-file)
                      (str "Here it is as it is now — match against THIS:\n---\n" content "---")
                      "read_file it and match against what comes back.")))

          (> n 1)
          (err (str "old_text occurs " n " times in " path "; nothing was changed. Include "
                    "enough surrounding text to make it occur once."))

          :else
          (let [i (str/index-of content old_text)
                edited (str (subs content 0 i) new_text (subs content (+ i (count old_text))))]
            (spit file edited)
            (ok (str "edited " path " (" (count old_text) " characters replaced by "
                     (count new_text) ")"
                     (when (re-find #"\.clj[scx]?$" path)
                       (after-write dir path edited))))))))))

(def clojure-error-prefixes
  "How `clojure.main/ex-str` begins every error it reports, one phase at a time:
  reading source, macroexpanding (a spec failure or an unexpected error),
  compiling (likewise), reading or printing the eval result, and execution.
  Read from `clojure/main.clj` in Clojure 1.12.5. `clj-nrepl-eval` prints that
  text on stderr, so a line of stderr beginning with one of these is a failed
  evaluation."
  ["Syntax error" "Unexpected error" "Error reading eval result"
   "Error printing return value" "Execution error"])

(def timeout-marker
  "What `clj-nrepl-eval` prints when an evaluation outlives its timeout: on
  STDOUT, with exit 0, so no stderr check sees it (bbin commit d341c23)."
  "Timeout hit, sending nREPL :interrupt")

(defn eval-result
  "What `nrepl_eval` returns for one `clj-nrepl-eval` run: `{:exit :out :err}`.

  `clj-nrepl-eval` EXITS 0 WHEN THE CODE FAILS. A form that throws, a file that
  will not read, a symbol that will not resolve — each prints its error on
  stderr and exits 0. This returned stdout alone on exit 0, so every one of
  them reached the model as an empty or partial success. In one run a Tester called
  the stub it had been given, which throws by design, saw three blank results,
  and stopped under `:no-repl-no-edits` with the nREPL alive; its first attempt
  never saw that its own test file did not read. Stderr now always comes back.

  ERRORS ARE NAMED BY WHOSE THEY ARE, because that rule tells a model to stop
  when evaluation stops working. A form that failed says the nREPL answered and
  the error is the code's; a non-zero exit says the nREPL could not be reached;
  a timeout says the form did not finish. The three must not read alike, or a
  Tester calling a stub three times reads its own errors as a dead REPL.

  STDERR IS NOT AN ERROR BY ITSELF — a reflection warning or a print to `*err*`
  goes there on success — so a result is flagged only for a line beginning
  with one of `clojure-error-prefixes`. The two streams are captured
  separately, so their relative order is gone, and a failed result puts stderr
  FIRST: clipping then cuts long output, never the error."
  ;; NOT `{:keys [exit out err]}`: a local named `err` shadows the `err` result
  ;; helper, and the version before this one called a string as a function on
  ;; every non-zero exit, so an unreachable nREPL came back as "String cannot be
  ;; cast to IFn" and never as the connection error.
  [{:keys [exit] :as shelled}]
  (let [stdout (str (:out shelled))
        stderr (str (:err shelled))
        block (fn [label s] (when-not (str/blank? s) (str "[" label "]\n" s "\n")))
        stderr-block (block "stderr — captured apart from stdout, so its order relative to it is lost" stderr)
        stdout-block (block "stdout" stdout)
        failed (fn [why] (err (str why "\n\n" stderr-block stdout-block)))]
    (cond
      (not (zero? exit))
      (failed (str "clj-nrepl-eval exited " exit ": the nREPL could not be reached, or the "
                   "command itself failed. This is not an error in your code."))

      (str/includes? stdout timeout-marker)
      (failed (str "your evaluation timed out and was interrupted. The nREPL is reachable; "
                   "the form did not finish."))

      (some (fn [line] (some #(str/starts-with? line %) clojure-error-prefixes))
            (str/split-lines stderr))
      (failed (str "a form you evaluated failed. The nREPL is reachable and answered: this "
                   "is an error in the evaluated code, not in the tool or the REPL. Forms "
                   "before it may have run."))

      (str/blank? stderr) (ok stdout)
      :else (ok (str stdout (when-not (str/blank? stdout) "\n") stderr-block)))))

(defn- nrepl-eval
  [{:keys [dir port]} {:keys [code]}]
  (cond
    (str/blank? code) (err "code is required")
    (nil? port) (err "no REPL is attached to this workspace")
    :else
    (do (doctor/require-tool! :clj-nrepl-eval)
        (eval-result (p/shell {:dir dir :out :string :err :string :continue true}
                              "clj-nrepl-eval" "-p" (str port) code)))))

(defn- note
  "Record an observation for whoever is dispatched next.

  Writes nothing and touches no file. The note is captured because
  `harness.models.agent` already keeps every tool call it made, so the tool's only
  job is to give the model somewhere STRUCTURED to put it. The channel was
  never missing — one early Coder said the contract was silent about dividing 7
  by 2, in prose, in a final message nothing reads."
  [_ctx {:keys [text]}]
  (if (str/blank? text)
    (err "text is required")
    (ok "noted — this will reach whoever is dispatched next")))

(def specs
  "Name -> {:description, :schema, :fn}. The descriptions are prompt text: a
  model chooses a tool from them, so they say what the tool is FOR and what it
  refuses, not merely what it takes."
  {"read_file"
   {:description (str "Read a file in your workspace. Use it for the "
                      ":files/context your packet lists — they are read-only "
                      "upstream dependencies.")
    :schema {:type "object"
             :properties {:path {:type "string"
                                 :description "Path relative to the workspace root."}}
             :required ["path"]}
    :fn #'read-file}

   "write_file"
   {:description (str "Write a file. REFUSED unless the path is one of your "
                      "packet's :files/target. Prototype in the REPL first; "
                      "this persists. A Clojure file is repaired (delimiters, "
                      "formatting) and linted as it is written, and the result "
                      "tells you what changed and what to fix — read it.")
    :schema {:type "object"
             :properties {:path {:type "string"
                                 :description "Path relative to the workspace root."}
                          :content {:type "string"
                                    :description "The complete new contents of the file."}}
             :required ["path" "content"]}
    :fn #'write-file}

   "edit_file"
   {:description (str "Change part of a file you have already written: old_text, "
                      "which must occur EXACTLY ONCE in the file, is replaced by "
                      "new_text. Use this to change a written file — a lint warning, "
                      "a bracket, one form — instead of sending the whole file "
                      "again with write_file; it costs a fraction of the tokens. "
                      "REFUSED unless the path is one of your packet's "
                      ":files/target, and when the file does not exist yet "
                      "(write_file creates it). Zero or several matches change "
                      "nothing, and the result says so and shows the file as it is "
                      "now, since a Clojure file is repaired and reformatted as it is "
                      "written. After an edit the file is repaired and linted as on a "
                      "write, and the result tells you what to fix — read it.")
    :schema {:type "object"
             :properties {:path {:type "string"
                                 :description "Path relative to the workspace root."}
                          :old_text {:type "string"
                                     :description (str "The exact text to replace, as it is in the "
                                                       "file now; it must occur once.")}
                          :new_text {:type "string"
                                     :description "What replaces it. Empty deletes it."}}
             :required ["path" "old_text" "new_text"]}
    :fn #'edit-file}

   "note"
   {:description (str "Report something about the CONTRACT that the next role or "
                      "the Architect needs. Use it when the Blueprint is silent "
                      "about a case you had to decide, when a :deps-sigs entry "
                      "looks wrong, or when you made an assumption someone should "
                      "check. Do NOT use it to summarise your work — that is your "
                      "final message. A note travels; a final message does not.")
    :schema {:type "object"
             :properties {:text {:type "string"
                                 :description "One observation, in a sentence or two."}}
             :required ["text"]}
    :fn #'note}

   "nrepl_eval"
   {:description (str "Evaluate Clojure in your workspace's live nREPL. "
                      "Prototype here before writing anything to disk.")
    :schema {:type "object"
             :properties {:code {:type "string"
                                 :description "One or more forms to evaluate."}}
             :required ["code"]}
    :fn #'nrepl-eval}})

;; ---------------------------------------------------------------------------
;; declaring them to a model
;; ---------------------------------------------------------------------------

(defn for-role
  "Which tools a role may be given.

  The Reviewer gets `read_file` and nothing else, because the rule
  `:review-read-only` says it writes nothing and has no REPL — and a rule
  that forbids what the tools still offer is a rule waiting to be broken.
  Its packet has no `:files/target` either, so `write_file` would refuse
  every path anyway; not declaring it is the difference between refusing a
  request and never inviting it."
  [role]
  (if (= :reviewer role) #{"read_file" "note"} (set (keys specs))))

(defmulti declarations
  "The declarations for `names` in one shape's vocabulary. Same tools, two
  spellings — OpenAI nests them under `function`, Anthropic does not."
  (fn [shape _names] shape))

(defn- selected [names]
  (sort (select-keys specs names)))

(defmethod declarations :openai
  [_ names]
  (vec (for [[nm {:keys [description schema]}] (selected names)]
         {:type "function"
          :function {:name nm :description description :parameters schema}})))

(defmethod declarations :anthropic
  [_ names]
  (vec (for [[nm {:keys [description schema]}] (selected names)]
         {:name nm :description description :input_schema schema})))

;; ---------------------------------------------------------------------------
;; running one
;; ---------------------------------------------------------------------------

(defn- decode
  "Tool arguments, whichever shape they arrived in. Returns `[ok? value]` so a
  malformed string is a tool failure the model can read rather than a throw."
  [args]
  (cond
    (map? args) [true args]
    (str/blank? (str args)) [true {}]
    :else (try [true (json/parse-string (str args) true)]
               (catch Exception _ [false (str "arguments were not valid JSON: " args)]))))

(defn invoke
  "Run one tool call. Returns `{:id _ :content _ :error? _}` and NEVER throws.

  `ctx` is `{:dir _ :targets [_] :port _}` — the workspace, what the packet
  allows writing, and the REPL. An unknown tool name is a result, not an error:
  a model that hallucinated a tool should be told so and given another turn."
  [ctx {:keys [id name args]}]
  (let [[ok? decoded] (decode args)
        result (cond
                 (not ok?) (err decoded)
                 (nil? (get specs name)) (err (str "no tool named " name
                                                   " — you have "
                                                   (str/join ", " (sort (keys specs)))))
                 :else (try ((:fn (get specs name)) ctx decoded)
                            ;; The backstop. Every tool above returns data on
                            ;; the paths it knows about; this catches the ones
                            ;; it does not, so no tool can kill a dispatch.
                            (catch Exception e
                              (err (str (ex-message e))))))]
    ;; The decoded arguments come back too. This namespace is the one that
    ;; knows a JSON string and a map mean the same thing, and a caller that
    ;; re-derives it gets the OpenAI shape wrong — `(str args)` on a string
    ;; is the string, so a note came back as its own JSON.
    (cond-> (assoc result :id id)
      ok? (assoc :args decoded))))
