(ns harness.loop.security-routing
  "Who acts on what a security review reproduced: `bb security-route <review.edn> <clone>`, at a
  stage's end, after `bb security-review` read the merged application.

  A REPRODUCED FINDING IS A BLOCKER, AND IT IS ROUTED, NOT FIXED. Each one goes to triage's
  `:security-finding` trigger - one call to the profile's `:orchestrator`, which answers
  `coder`, `architect` or `human`. A finding that reached the security reviewer has passed the
  per-task code reviews, the gates and the stage's tests, so it is usually a gap in the contract or
  the design; the prompt says so and leans to the architect. Nothing is dispatched: `coder`
  writes a fix packet's DRAFT in the Blueprint's packet format, for the Architect to complete and
  sign; `architect` and `human` are a decision for a person, the second for the threat model.

  A HYPOTHESIS IS NEVER ROUTED. A finding the security reviewer could not show with a test goes
  to the person as it is, for the threat model (02 §15) - not to a model that would have to guess.

  THE SECURITY REVIEWER'S TEST IS NOT THE TESTER'S. It was written by a model that read the code,
  so it is kept beside the draft as the fix's acceptance check and never put in a packet's
  context: the Tester writes its test from the draft's target, which triage words in the
  contract's terms."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.loop.triage :as triage]
   [harness.models.profile :as profile]
   [harness.setup.workspace :as workspace]))

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn named-files
  "The files a finding's `where` names, in order, each once: `src/app/web.clj: logout!;
  src/app/db.clj` -> [\"src/app/web.clj\" \"src/app/db.clj\"]."
  [where]
  (vec (distinct (re-seq #"[A-Za-z0-9_][A-Za-z0-9_./-]*\.(?:clj|cljc|cljs|edn|sql|html|js|css)\b" (str where)))))

(defn sections
  "The sections of a Markdown document whose `## N.` heading has a number in `nums`, each to the
  next heading of its level or above, joined in the document's order."
  [md nums]
  (let [want (set (map str nums))]
    (->> (str/split (str md) #"(?m)^(?=#{1,2} )")
         (filter #(when-let [[_ n] (re-find #"^## (\d+)\." %)] (want n)))
         (map str/trim)
         (str/join "\n\n"))))

(def security-sections
  "The architecture document's sections triage is shown: what the template gives (§4), what the
  project chose (§8), and the threat model (§15)."
  [4 8 15])

(defn trigger-for
  "Triage's `:security-finding` trigger for `finding`. `files` is `[[path text-or-nil]]` - the
  files its `where` names, read from the merged application."
  [finding test-text files architecture]
  {:trigger :security-finding
   :payload {:finding (select-keys finding [:title :where :why :test])
             :test-text test-text
             :files files
             :architecture architecture}})

(defn- slug [s]
  (let [s (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))]
    (subs s 0 (min 40 (count s)))))

(defn test-path
  "Where the Tester's test of a fix to `impl` goes: `src/app/web.clj` -> `test/app/web_security_test.clj`."
  [impl]
  (when impl
    (-> (str impl)
        (str/replace #"^src/" "test/")
        (str/replace #"\.(clj[cs]?)$" "_security_test.$1"))))

(defn fix-draft
  "The fix packet's draft for the `n`th finding routed `coder`, in the Blueprint's packet format:
  what triage named filled in, what only the Architect can say left as a mark. `kept` is where the
  security reviewer's test is kept."
  [n finding {:keys [reason impl target]} kept]
  (let [id (format "fix-%02d" n)
        target (or target "<the promise the fix must keep, one checkable sentence>")]
    (str "### " id " — " (:title finding) "\n\n"
         "From the security review: " (:why finding) "\n"
         "Routed coder by triage: " reason "\n\n"
         "```clojure\n"
         "{:task/id          \"" id "-" (slug (:title finding)) "\"\n"
         " :task/title       " (pr-str (str "Fix: " (:title finding))) "\n"
         " :blueprint/slice  {:shapes     []\n"
         "                    :interfaces [<the functions the fix changes, with their argument vectors>]\n"
         "                    :deps-sigs  [<what they may call>]}\n"
         " :files/impl       [" (pr-str (or impl "<the one file the fix changes>")) "]\n"
         " :files/test       [" (pr-str (or (test-path impl) "<its security test>")) "]\n"
         " :files/context    [<read-only files>]\n"
         " :layer/name       :<layer>\n"
         " :property-targets [" (pr-str target) "]}\n"
         "```\n\n"
         "**Acceptance:** the security reviewer's test `" (:test finding) "` (kept at `" kept "`) passes "
         "on the merged tip. It is not given to the Tester: it was written from the code.\n")))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn- read-text [path]
  (when (and path (fs/exists? path) (fs/regular-file? path)) (slurp (str path))))

(defn route!
  "Route every reproduced finding of the security review recorded at `review-file` (its tests in
  `<id>-tests/` beside it), reading the files it names from `clone`, the merged application.
  `triage-fn` is `(fn [trigger])` -> a verdict, `architecture` the text of the architecture's
  security sections. Writes `security-routing.edn` and, when a finding was routed `coder`,
  `security-fixes.md` into `out`. Returns `{:routed [{:finding :verdict}] :hypotheses [finding]
  :files [written]}`."
  [{:keys [review-file clone architecture triage-fn out]}]
  (let [review (edn/read-string (slurp (str review-file)))
        tests-dir (fs/path (fs/parent review-file) (str (fs/strip-ext (fs/file-name review-file)) "-tests"))
        findings (:findings review)
        routed (vec (for [f findings
                          :when (= :reproduced (:kind f))
                          :let [files (vec (for [rel (named-files (:where f))]
                                             [rel (read-text (fs/path clone rel))]))
                                v (triage-fn (trigger-for f (read-text (some->> (:test f) (fs/path tests-dir))) files architecture))]]
                      {:finding f
                       :verdict (select-keys v [:route :reason :guidance :impl :target :triage/fallback :cost :prompt :answer])}))
        hypotheses (vec (remove #(= :reproduced (:kind %)) findings))
        coders (filter #(= :coder (get-in % [:verdict :route])) routed)
        routing-file (str (fs/path out "security-routing.edn"))
        fixes-file (str (fs/path out "security-fixes.md"))]
    (fs/create-dirs out)
    (spit routing-file (with-out-str (pp/pprint {:review (str review-file)
                                                 :routed routed
                                                 :hypotheses hypotheses})))
    (when (seq coders)
      (spit fixes-file
            (str "# Fix packets drafted from the security review\n\n"
                 "Drafted by triage from `" (fs/file-name review-file) "`. Each is a packet for this stage's "
                 "Blueprint once the Architect has filled what is marked and signed it.\n\n"
                 (str/join "\n" (map-indexed (fn [i {:keys [finding verdict]}]
                                               (fix-draft (inc i) finding verdict (str (fs/path tests-dir (:test finding)))))
                                             coders)))))
    {:routed routed
     :hypotheses hypotheses
     :files (cond-> [routing-file] (seq coders) (conj fixes-file))}))

;; ---------------------------------------------------------------------------
;; the command
;; ---------------------------------------------------------------------------

(def usage
  (str "bb security-route <review.edn> <clone> [--profile <profile.edn>] [--architecture <02-architecture.md>] [--out <dir>]\n"
       "  <review.edn>  a record `bb security-review` wrote, its tests beside it\n"
       "  <clone>       the merged application the review read\n"
       "One triage call per reproduced finding, to the profile's :orchestrator: it spends money.\n"
       "Defaults: the workspace's profile and docs/02-architecture.md; --out the review's folder."))

(defn parse-args [args]
  (loop [[a & more] args m {}]
    (cond (nil? a) m
          (#{"--profile" "--architecture" "--out"} a) (recur (rest more) (assoc m (keyword (subs a 2)) (first more)))
          (str/starts-with? a "--") (throw (ex-info (str "unknown argument " a "\n" usage) {}))
          :else (recur more (update m :positional (fnil conj []) a)))))

(defn- print-result [{:keys [routed hypotheses files]}]
  (println (str "\n  " (count routed) " reproduced finding(s) routed, " (count hypotheses) " hypothesis(es) for the threat model"))
  (doseq [{:keys [finding verdict]} routed]
    (println (str "  - [" (name (:route verdict)) "] " (:title finding)))
    (println (str "      " (:reason verdict) (when (:triage/fallback verdict) (str " (fallback: " (:triage/fallback verdict) ")"))))
    (when (:guidance verdict) (println (str "      " (:guidance verdict)))))
  (doseq [h hypotheses]
    (println (str "  - [hypothesis, for 02 §15] " (:title h))))
  (doseq [f files] (println (str "  wrote " f))))

(defn -main [& args]
  (let [{:keys [positional] :as opts} (parse-args (:args (workspace/split-args args)))
        [review-file clone] positional]
    (when-not (and review-file clone)
      (println usage)
      (System/exit 2))
    (let [ws (workspace/current)
          profile-path (or (:profile opts) (profile/project-profile))
          arch-path (or (:architecture opts)
                        (some-> (:workspace/build ws) (fs/path "docs" "02-architecture.md") str))]
      (when-not profile-path
        (println "security-route: no profile - pass --profile, or run it in a workspace")
        (System/exit 2))
      (let [r (route! {:review-file (str (fs/absolutize review-file))
                       :clone (str (fs/absolutize clone))
                       :architecture (some-> (read-text arch-path) (sections security-sections))
                       :triage-fn (triage/model-triage (profile/read-profile profile-path))
                       :out (or (:out opts) (str (fs/parent (fs/absolutize review-file))))})]
        (print-result r)))))
