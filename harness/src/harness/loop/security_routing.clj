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
   [harness.contained-path :as contained-path]
   [harness.loop.triage :as triage]
   [harness.models.profile :as profile]
   [harness.models.security-review-tools :as security-review-tools]
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
  `review-file` may be a security reviewer panel's combined table (`security-panel.edn`): then
  one call per row, its test read from the member's record that carried the row, and each routed
  entry carries the members that reproduced it (`:by`).
  `triage-fn` is `(fn [trigger])` -> a verdict, `triage-role` the profile's role it calls (its
  `:family` and `:endpoint` recorded on each verdict, with the provider that answered, so a cost
  table can place the call), `architecture` the text of the architecture's security sections. Writes
  `security-routing-<stamp>.edn` and, when a finding was routed `coder`, `security-fixes-<stamp>.md`
  into `out`, `<stamp>` the run's local time (`at`, now by default). Returns `{:routed [{:finding
  :verdict}] :hypotheses [finding] :files [written]}`.

  A RUN IS A RECORD, AND A RECORD IS NEVER OVERWRITTEN. Each run wrote one `security-routing.edn`
  over the last, and the stage report priced the file on disk: the first KIT project's stage 6 routed
  four times, $1.38, and its report counted $0.57 against the cap. Each run now has its own file,
  named by its time as the panel members' records beside it are, and carries its `:at`; the report
  sums them all (`stage-report`), a project's earlier bare `security-routing.edn` its first run."
  [{:keys [review-file clone architecture triage-fn triage-role out at]}]
  (let [at (or at (java.time.ZonedDateTime/now))
        stamp (.format at (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
        review-file (str (fs/normalize (fs/absolutize review-file)))
        review (edn/read-string (slurp review-file))
        dir (fs/parent review-file)
        ;; a panel row's :record names a file of this folder by construction; the table is a build's file,
        ;; so only its file name is taken - `../x` had moved the folder whose tests were trusted
        tests-of (fn [record-name] (fs/path dir (str (fs/strip-ext (fs/file-name (str record-name))) "-tests")))
        ;; ONE REVIEW'S RECORD, OR A PANEL'S TABLE: either way, what to route and where its test is
        panel? (contains? review :rows)
        items (if panel?
                (for [{:keys [finding record by]} (:rows review)]
                  {:finding finding :tests-dir (tests-of record) :by by})
                (for [f (:findings review) :when (= :reproduced (:kind f))]
                  {:finding f :tests-dir (tests-of (fs/file-name review-file))}))
        ;; A FINDING'S PATHS ARE THE SECURITY REVIEWER'S WORDS: read only inside the clone and the
        ;; test folder - a `where` of `a/../../../.env` had sent a workspace's key to triage's
        ;; provider and into `security-routing.edn`, which the build commits. Outside, the text is nil
        routed (vec (for [{f :finding :keys [tests-dir by]} items
                          ;; a path outside is left out, not listed with no text: the record lists what was read
                          :let [files (vec (for [rel (named-files (:where f))
                                                 :let [p (contained-path/inside clone rel)]
                                                 :when p]
                                             [rel (read-text p)]))
                                v (triage-fn (trigger-for f (read-text (security-review-tools/record-test-file dir tests-dir (:test f))) files architecture))]]
                      (cond-> {:finding f
                               :tests-dir (str tests-dir)
                               :verdict (assoc (select-keys v [:route :reason :guidance :impl :target :triage/fallback :refused? :prompt :answer])
                                               :cost (get-in v [:result :cost])
                                               :generation-ids (get-in v [:result :runner/meta :generation-ids])
                                               ;; the model that routed it, its maker and channel, so the money
                                               ;; by provider and by role place it
                                               :model (get-in v [:result :runner/meta :model])
                                               :provider (get-in v [:result :runner/meta :provider])
                                               :family (:family triage-role)
                                               :endpoint (:endpoint triage-role))}
                        by (assoc :by by))))
        hypotheses (if panel?
                     (mapv :finding (:hypotheses review))
                     (vec (remove #(= :reproduced (:kind %)) (:findings review))))
        coders (filter #(= :coder (get-in % [:verdict :route])) routed)
        ;; a second run in the same second is a second file, never the first one again; the fixes
        ;; file takes the routing file's name, so a run's two files always pair
        run-id (first (remove #(fs/exists? (fs/path out (str "security-routing-" % ".edn")))
                              (cons stamp (for [i (range 2 Long/MAX_VALUE)] (str stamp "-" i)))))
        routing-file (str (fs/path out (str "security-routing-" run-id ".edn")))
        fixes-file (str (fs/path out (str "security-fixes-" run-id ".md")))]
    (fs/create-dirs out)
    (spit routing-file (with-out-str (pp/pprint {:at (str (.toOffsetDateTime (.truncatedTo at java.time.temporal.ChronoUnit/SECONDS)))
                                                 :review (str review-file)
                                                 :routed routed
                                                 :hypotheses hypotheses})))
    (when (seq coders)
      (spit fixes-file
            (str "# Fix packets drafted from the security review\n\n"
                 "Drafted by triage from `" (fs/file-name review-file) "`. Each is a packet for this stage's "
                 "Blueprint once the Architect has filled what is marked and signed it.\n\n"
                 (str/join "\n" (map-indexed (fn [i {:keys [finding verdict tests-dir]}]
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
       "  <review.edn>  a record `bb security-review` wrote, its tests beside it, or a panel's security-panel.edn\n"
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
  (println (str "\n  " (count routed) " reproduced finding(s) routed, " (count hypotheses) " hypothesis(es) for the threat model"
                (let [cs (map (comp :cost :verdict) routed)]
                  (when (seq cs) (if (every? some? cs) (format ", $%.3f" (double (reduce + cs))) ", cost not yet reported for every call")))))
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
      (let [prof (profile/read-profile profile-path)
            r (route! {:review-file (str (fs/absolutize review-file))
                       :clone (str (fs/absolutize clone))
                       :architecture (some-> (read-text arch-path) (sections security-sections))
                       :triage-fn (triage/model-triage prof)
                       :triage-role (get-in prof [:roles :orchestrator])
                       :out (or (:out opts) (str (fs/parent (fs/absolutize review-file))))})]
        (print-result r)))))
