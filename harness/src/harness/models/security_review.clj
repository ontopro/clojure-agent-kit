(ns harness.models.security-review
  "A security review of an application: a model in the security reviewer's seat, a clone of the
  application, the five review tools, and the sandbox they run in. It reads the code, finds
  what it believes is wrong, and checks each belief by writing a test and running it. Its
  answer is a list of findings, each saying whether a test of its own failed on this code.

  A FINDING IS A FAILING TEST. A concern the security reviewer could state as a test and see fail is
  `reproduced`; one it could not is a `hypothesis`, for a person to read and not a reason to
  stop anything. \"Nothing found\" is a complete answer and the prompts say so, because a
  security reviewer told only to find problems finds some.

  TWO STANCES, SAME TOOLS. The `:tracer` is given the repository and follows requests through
  the routes, the middleware and the state they share; the `:diff` security reviewer is given the
  stage's change and asks of each new route, query and file operation what reaches it. The
  tools are the same for both so that a comparison of models measures the models.

  PURE UP TO THE EDGE: `system-prompt`, `opening` and `parse-findings` are functions of data;
  `review!` starts the sandbox, runs the conversation and reads what the security reviewer left."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.models.agent :as agent]
   [harness.models.catalogue :as catalogue]
   [harness.models.profile :as profile]
   [harness.models.security-review-sandbox :as sandbox]
   [harness.models.security-review-tools :as security-review-tools]))

;; ---------------------------------------------------------------------------
;; what the security reviewer is told
;; ---------------------------------------------------------------------------

(def system-prompt
  (str "You are a security reviewer for a Clojure web application. The application is its owner's "
       "own; this review is run before release, in a sandbox with no network, so that its weaknesses "
       "are found and fixed by the people who maintain it.\n\n"
       "HOW YOU WORK. Read the code with read_file and search. Form a concern about how the "
       "application could be made to do something its authors did not intend. Look at the "
       "running application with request when that helps you see what it does. Then state the "
       "concern as a TEST: a complete Clojure test namespace, written with write_test below "
       "test/, that asserts the property that should hold - for example that a request without "
       "a login is refused, or that a user cannot read another user's data - and run it with "
       "run_tests. Look at the application's existing tests first (they show how it is called "
       "in-process, with a session and its anti-forgery token) and follow them.\n\n"
       "WHAT A FINDING IS. A finding is a test of yours that FAILS on this code because the "
       "property does not hold. If your test passes, the concern did not hold: drop it. Only if "
       "you could not state a concern as a test, say so honestly by calling it a hypothesis, "
       "with the reasoning. Do not report a style problem, a missing feature or a theoretical "
       "weakness with no path from a request to the effect.\n\n"
       "BE HONEST ABOUT WHAT YOU FOUND. Finding nothing is a complete and welcome answer; an "
       "invented finding costs the people reading it more than a missed one. Report what you "
       "verified, no more.\n\n"
       "YOUR ANSWER, when you are done, is one JSON block and nothing after it:\n"
       "```json\n"
       "{\"findings\": [{\"title\": \"one line\", \"kind\": \"reproduced\" or \"hypothesis\", "
       "\"test\": \"test/path/of/the/failing_test.clj, for a reproduced finding\", "
       "\"where\": \"file and function\", \"why\": \"what is wrong and what it lets a request do, "
       "in two or three sentences\"}]}\n"
       "```\n"
       "with an empty list when you found nothing."))

(def stances
  "The two ways a review is begun: what the security reviewer is given and what it is asked to look at."
  {:tracer
   {:label "architecture tracer"
    :task (str "You are given the whole repository. Follow requests through the application: which "
               "routes there are, which middleware wraps each, what each handler checks before it "
               "acts, and what state requests share (counters, quotas, sessions, files, the "
               "database). Look for checks that apply to one HTTP method or one route but not to "
               "another that reaches the same data; checks and actions that are separate steps "
               "where requests can interleave; values that reach a query, a file path or a page "
               "without being limited; and rules about who may see or change what that a sequence "
               "of requests could get around.")}
   :diff
   {:label "change reviewer"
    :task (str "You are given the change this stage made, as a diff against its base. Review that "
               "change: for each route, handler, query and file operation it adds or alters, ask "
               "what a request can put into it, what it does with that, and what the neighbouring "
               "code assumes about it. The rest of the repository is there to read when the change "
               "calls into it.")}})

(def max-listing 200)
(def max-diff 60000)

(defn opening
  "The first message: the stance's task, the repository's files, and for the change
  stance the diff. `files` is the clone's tracked file paths; `diff` its change from the base."
  [stance {:keys [files diff scans]}]
  (let [{:keys [label task]} (get stances stance)
        shown (take max-listing files)]
    (str "This is a " label " review.\n\n" task "\n\n"
         "The application's files (" (count files) "):\n"
         (str/join "\n" shown)
         (when (> (count files) max-listing) (str "\n[" (- (count files) max-listing) " more not listed; search finds them]"))
         (when (= :diff stance)
           (str "\n\nThe change under review:\n```diff\n"
                (if (> (count diff) max-diff)
                  (str (subs diff 0 max-diff) "\n[diff cut at " max-diff " of " (count diff) " characters; read_file has the rest]")
                  diff)
                "\n```"))
         (when (seq scans) (str "\n\nResults of the dependency and secrets scans, for you to weigh:\n" scans))
         "\n\nBegin. Give your answer as the JSON block described, when you have checked what you mean to report.")))

(defn refused?
  "Whether the model declined to do the review: a completion whose host finish reason is
  `content_filter` or whose native one is `refusal` (Anthropic's word). That is the model's own
  safeguard speaking, not a failure of the call, and a review in which it happened has said
  nothing - it is reported as a refusal, never as a clean review or an empty answer."
  [steps]
  (agent/refused? steps))

(defn parse-findings
  "The findings in the security reviewer's final text, from its last ```json block: the vector (empty for
  an honest nothing), or nil when there was no block - a review with no block says nothing and
  must not read as a clean one."
  [text]
  (when-let [m (agent/last-json-block text)]
    (when (contains? m :findings)
      (vec (for [f (:findings m)]
             (-> (select-keys f [:title :kind :test :where :why])
                 (update :kind #(if (= "reproduced" (str %)) :reproduced :hypothesis))))))))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn- git [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git " (str/join " " args) " failed in " dir ": " (str/trim (:err r))) {:dir (str dir)})))
    (:out r)))

(defn review-clone!
  "A clone of `branch` of the repository at `source` for a security reviewer to be handed: that branch only,
  no tags and no remote, with the revision its change is from tagged `base`. Nothing in it says
  where it came from or what the other branches are - a security reviewer that can read `.git` would
  otherwise read the answer key's neighbourhood."
  [source branch dest]
  (let [g (fn [dir & args]
            (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)]
              (when-not (zero? (:exit r))
                (throw (ex-info (str "git " (str/join " " args) " failed: " (str/trim (:err r))) {:source (str source)})))
              (:out r)))]
    (fs/create-dirs (fs/parent dest))
    (g (fs/parent dest) "clone" "-q" "--single-branch" "--branch" branch "--no-tags" (str source) (str dest))
    (g dest "tag" "base" (str/trim (g dest "rev-list" "--max-parents=0" "HEAD")))
    (g dest "remote" "remove" "origin")
    (str dest)))

(defn clone-facts
  "What the opening needs from the clone: its tracked files, and its change from `base`."
  [clone base]
  {:files (vec (remove str/blank? (str/split-lines (git clone "ls-files"))))
   :diff (git clone "diff" "--no-color" (str base "..HEAD"))})

(defn reproductions
  "The test files the security reviewer wrote: the clone's untracked files below `test/`, path -> text."
  [clone]
  (into (sorted-map)
        (for [rel (remove str/blank? (str/split-lines (git clone "ls-files" "--others" "--exclude-standard" "test")))]
          [rel (slurp (str (fs/path clone rel)))])))

(def default-rounds
  "Completions a review may take. Thirty cut a model off that sends its requests one to a completion
  (GLM-5.3-prime used all of them, ten on single requests, and never answered) while another batched
  four reads to a completion and finished in fifteen; a limit is for a runaway, not for the slower way
  of working."
  60)
(def default-requests 150)

(def default-max-tokens
  "The most a completion may write, its reasoning included. A route's own limit is sized for
  an answer; a security reviewer thinking at high effort over a dozen files can spend 16,000 tokens
  before it writes a word, and a completion cut off there returns nothing - which a first review
  did. Fable 5.1 takes 128,000; this is what the review asks for."
  64000)

(defn- compact-turns
  "The conversation for the record: what the model said and which tools it called with what, each
  call's result cut to a line. Whole files read stay out - the record is for judging a review,
  not replaying it."
  [turns]
  (mapv (fn [{:keys [text calls]}]
          {:text text
           :calls (mapv (fn [{:keys [name args ms error? content]}]
                          {:tool name :args args :ms ms :error? error?
                           :result (let [s (str content)] (subs s 0 (min 160 (count s))))})
                        calls)})
        turns))

(defn review!
  "Review the application cloned in `clone`: start the sandbox, run `role` (a role block) in the
  stance `stance` with the five tools, stop the sandbox whatever happens. Options: `:kit`
  (this KIT's folder), `:base` (the revision the change is from, default \"base\"), `:rounds`,
  `:requests`, `:max-tokens`, `:scans` (text for the security reviewer to weigh), `:sandbox` (a started one,
  for a test).

  Returns `{:stance :status :findings :no-block? :tests :text :calls :iterations :capped? :ms
  :sandbox-ms :model :cost :generation-ids ...}` (`:ms` the conversation, `:sandbox-ms` the time
  to start the sandbox before it): `:findings` as `parse-findings` reads them, `:tests` the
  files the security reviewer wrote, `:calls` the tool calls it made as `[name ms error?]`."
  [role stance clone {:keys [kit base rounds requests max-tokens scans sandbox]
                      :or {base "base" rounds default-rounds requests default-requests max-tokens default-max-tokens}}]
  (let [role (assoc-in role [:params :max_tokens] max-tokens)
        facts (assoc (clone-facts clone base) :scans scans)
        t-sandbox (System/currentTimeMillis)
        sb (or sandbox (sandbox/start! {:kit kit :clone clone}))
        t0 (System/currentTimeMillis)]
    (try
      (let [ctx {:dir (str clone) :sandbox sb :request-budget (atom requests) :written (atom #{})}
            r (agent/converse! role system-prompt (opening stance facts) ctx
                               {:registry security-review-tools/specs :tools (set (keys security-review-tools/specs))
                                :max-iterations rounds :timeout-ms 600000})
            findings (parse-findings (:text r))]
        (merge {:stance stance
                :status (:status r)
                :findings findings
                :no-block? (nil? findings)
                :tests (reproductions clone)
                :text (:text r)
                :refused? (refused? (:steps r))
                :empty-answer? (and (= :done (:status r)) (str/blank? (:text r)))
                :turns (compact-turns (:turns r))
                :steps (:steps r)
                :calls (mapv (juxt :name :ms :error?) (:calls r))
                :iterations (:iterations r)
                :capped? (:capped? r)
                :error (:error r)
                :ms (- (System/currentTimeMillis) t0)
                :sandbox-ms (- t0 t-sandbox)}
               (agent/call-record r role)))
      (finally (when-not sandbox ((:stop! sb)))))))

(defn write-record!
  "Write the review under `out`: `<id>.edn` (everything but the tests' text) and the tests it
  wrote under `<id>-tests/`. Returns the record's path."
  [out id review]
  (fs/create-dirs out)
  (let [edn-file (str (fs/path out (str id ".edn")))
        tests-dir (fs/path out (str id "-tests"))]
    (doseq [[rel text] (:tests review)]
      (fs/create-dirs (fs/parent (fs/path tests-dir rel)))
      (spit (str (fs/path tests-dir rel)) text))
    (spit edn-file (with-out-str (pp/pprint (update review :tests #(vec (keys %))))))
    edn-file))

;; ---------------------------------------------------------------------------
;; the command
;; ---------------------------------------------------------------------------

(def usage
  (str "bb security-review <clone> --stance tracer|diff [--model \"<model> [effort]\" | --profile <profile.edn>]\n"
       "                          [--base <rev>] [--rounds N] [--max-tokens N] [--out <dir>]\n"
       "  <clone>    a git clone of the application, with the revision its change is from (default: base)\n"
       "  --model    as bb models names it and the bake-off takes it, e.g. \"openai/gpt-6-astra high\"\n"
       "  --profile  the profile whose :security-reviewer reads; without --model or --profile, the workspace's\n"
       "A real model call: it spends money (up to --rounds completions, default " default-rounds ")."))

(defn parse-args [args]
  (loop [[a & more] args m {}]
    (cond (nil? a) m
          (= a "--stance") (recur (rest more) (assoc m :stance (keyword (first more))))
          (= a "--model") (recur (rest more) (assoc m :model (first more)))
          (= a "--profile") (recur (rest more) (assoc m :profile (first more)))
          (= a "--base") (recur (rest more) (assoc m :base (first more)))
          (= a "--rounds") (recur (rest more) (assoc m :rounds (parse-long (first more))))
          (= a "--max-tokens") (recur (rest more) (assoc m :max-tokens (parse-long (first more))))
          (= a "--out") (recur (rest more) (assoc m :out (first more)))
          (str/starts-with? a "--") (throw (ex-info (str "unknown argument " a "\n" usage) {}))
          :else (recur more (update m :positional (fnil conj []) a)))))

(defn- print-review [r]
  (println (str "\n  " (name (:stance r)) " review by " (:model r) ": " (name (:status r))
                (when (:capped? r) " (stopped at its round limit)") " in " (:iterations r) " completions, "
                (quot (:ms r) 1000) "s, " (if (:cost r) (format "$%.2f" (double (:cost r))) "cost not yet reported")))
  (println (str "  tools: " (pr-str (frequencies (map first (:calls r))))))
  (cond
    (:refused? r) (println "  the model REFUSED to do this review (finish reason content_filter / refusal): its own safeguard, not a failure of the call. The review did not happen; this is not a clean review")
    (:empty-answer? r) (println "  the last completion returned no text at all - cut off at its output limit, or an empty reply; the steps in the record say which")
    (:no-block? r) (println "  the answer had no findings block - it says nothing, and is not a clean review")
    (empty? (:findings r)) (println "  no findings")
    :else (doseq [{:keys [title kind test where why]} (:findings r)]
            (println (str "  - [" (name kind) "] " title (when where (str " (" where ")")) (when test (str " - " test))))
            (when why (println (str "      " why)))))
  (println (str "  tests it wrote: " (if (seq (:tests r)) (str/join ", " (keys (:tests r))) "none"))))

(defn role-from-profile
  "The `:security-reviewer` of the profile at `path`, as a candidate: `{:profile role :id _ :model _
  :effort _}`. Throws naming the file when the profile has no such role - one written before the
  role existed; `bb doctor` in its workspace says so too."
  [path]
  (let [role (get-in (profile/read-profile path) [:roles :security-reviewer])]
    (when-not role
      (throw (ex-info (str path " has no :security-reviewer - add one (the shipped examples have it), or pass --model")
                      {:profile (str path)})))
    {:profile role
     :id (last (str/split (:model role) #"/"))
     :model (:model role)
     :effort (get-in role [:params :reasoning_effort])}))

(defn -main [& args]
  (let [{:keys [positional stance model base rounds max-tokens out] :as opts} (parse-args args)
        [clone] positional
        kit (str (fs/normalize (fs/absolutize "..")))
        profile-path (when-not model (or (:profile opts) (profile/project-profile)))]
    (when-not (and clone (#{:tracer :diff} stance) (or model profile-path))
      (println usage)
      (System/exit 2))
    (let [cand (if model
                 (catalogue/expand-candidate (catalogue/fetch-listing) (catalogue/routes) model)
                 (role-from-profile profile-path))
          id (str (:id cand) "-" (name stance) "-" (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))
          out (or out (str (fs/path kit ".local" "security-review")))]
      (println (str "  " (name stance) " review of " clone " by " (:model cand) " (" (:effort cand) "), up to " (or rounds default-rounds) " completions"))
      (let [r (review! (:profile cand) stance (str (fs/absolutize clone))
                       (cond-> {:kit kit} base (assoc :base base) rounds (assoc :rounds rounds) max-tokens (assoc :max-tokens max-tokens)))
            file (write-record! out id r)]
        (print-review r)
        (println (str "  record: " file))
        (when (or (= :failed (:status r)) (:refused? r) (:no-block? r)) (System/exit 1))))))
