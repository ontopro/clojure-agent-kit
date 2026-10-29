;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.models.profile
  "Which model answers for which role, and the one client the human works in.

  WHY THIS EXISTS. Three separate things were being conflated every time this
  came up in conversation: the interactive SEAT (exactly one per project), the
  dispatch HARNESS (this code), and the model FAMILY per dispatched role. The
  seat is where a human sits; the roles are reached over HTTP and are nobody's
  editor. Writing them down in one shape is what stops the question recurring.

  AND IT MAKES §05's INDEPENDENCE RULE CHECKABLE. *Verifier ≠ Coder family* is
  the one decision the method rests on — the Tester and the code reviewer exist to
  disagree with the Coder, and two roles from the same family share its blind
  spots. It has lived in prose since it was decided: method §05, and decision
  log P0-3. `violations` is the first thing that can fail on it.

  This is the same gap `:shapes` and `:deps-sigs` had, in the rule that matters
  most. A rule no code can check is a rule that quietly stops being true.

  WHAT IS CHECKED WHERE, and why it is split three ways:

    STRUCTURE (default) — shape, and the independence rule. Pure data, offline,
    deterministic, the same answer on every machine. Safe in `bb gates`.

    ENVIRONMENT (`:env?`) — is the seat installed, is each named key variable
    set. True or false depending on whose laptop this is, so a gate that ran it
    would fail for a contributor who uses a different seat.

    REACHABILITY (`:probe?`) — does each endpoint answer. Network I/O. `bb
    gates` must pass on a plane.

  Nothing here reads a key. `:key-env` names a variable and this checks it is
  set; the value never enters a log, a report, or an ex-info."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.string :as str]
   [harness.contract.shapes :as shapes]
   [harness.models.catalogue :as catalogue]
   [harness.setup.doctor :as doctor]
   [harness.setup.workspace :as workspace]))

(def profile-file-name
  "What `bb init` names a project's profile, in the build folder."
  "profile.edn")

(defn plan-profile
  "`<build>/profile.edn` of the workspace `ws`, or nil: no workspace, one whose
  `workspace.edn` names no build, or a build with no profile yet."
  [ws]
  (when-let [plan (:workspace/build ws)]
    (let [path (str (fs/path plan profile-file-name))]
      (when (fs/exists? path) path))))

(defn project-profile
  "A project's OWN profile: `<build>/profile.edn` of the workspace this command
  runs in (`workspace/current`: pointed at by `--workspace` or `KIT_WORKSPACE`,
  else found by walking up from `harness/` in a workspace's clone) - or, given
  `dir`, of the workspace `dir` is in. Nil outside a workspace, in one whose
  `workspace.edn` names no build, or whose build has no profile yet. `bb init`
  writes it there as a copy of the seat's shipped example, and the clone
  carries nothing of it.

  Absent from the KIT's own development folder on purpose: the KIT is worked
  ON rather than dispatched through (root CLAUDE.md), so it has no seat and no
  roles to configure."
  ([] (plan-profile (workspace/current)))
  ([dir] (plan-profile (workspace/find-workspace dir))))

(def examples-dir
  "The seed's worked examples, one per seat.

  TWO OF THEM, AND THAT IS THE POINT. A single committed profile reads as
  *your* configuration — which is exactly how the first version of this file
  was misread, and it had the Coder on Anthropic under a Google seat, so the
  misreading landed on a mistake. Two profiles cannot both be yours.

  Since 2026-10-16 they differ only in the seat and the orchestrator: the
  measured roles under either client. Each exercises both `:shape` adapters."
  "resources/profiles")

(def verifiers
  "The roles whose job is to disagree with the author of what they read. The
  Tester and the code reviewer derive from the same Blueprint slice as the Coder and
  may not share the family that wrote the code. The SPEC REVIEWER reads the
  Architect's contract before anyone works from it, and the Architect is the
  seat, whose family is the Coder's by convention (portability.md) — so \"not
  the Coder's family\" is the expressible form of \"not the Architect's\", and a
  spec reviewer that shared it would read the sentence the way its author did.

  NOT THE ORCHESTRATOR. Its one dispatch is the triage call — who owns a red
  gate or a note — and it neither writes code nor judges a diff, so the
  independence rule has nothing to say about it. `claude.edn` runs it on the
  Coder's family, by decision; `NOTES.md`'s register watches that.

  NOT THE BLUEPRINT REVIEWER EITHER, by a different route: the rule DOES reach
  it — it reads the seat's Blueprint as the spec reviewer reads the seat's
  spec — and the decision (2026-09-25) is to let it share the seat's family
  anyway, because measured on Blueprints that family found what two others
  missed. An exception written down, not a rule that lapsed: `claude.edn`'s
  comment carries the reasoning, `NOTES.md`'s register watches it and says
  what would reverse it.

  AND THE PLAN REVIEWER, by the spec reviewer's route: it reads the plan and
  every stage plan cold, and the seat wrote them, so a reader of the seat's
  family would read each sentence the way its author did. Its own role from
  2026-10-06 (it borrowed the spec reviewer's block before), set to the spec
  reviewer's selection in both shipped examples, and held to the same rule.

  AND THE SECURITY REVIEWER (2026-10-08): it reads the merged application to
  find what the Coder got wrong, so a model of the Coder's family would share
  the blind spots it is there to look past."
  [:tester :reviewer :spec-reviewer :plan-reviewer :security-reviewer])

(def roles
  "Every role a profile configures, in the order a build meets them: the plan
  reviewer in every stage's plan step, stage 0's first; the Blueprint reviewer
  once per stage before sign-off; the spec reviewer before every `start`; then
  the two authors; then the two judges; then the security reviewer, once at
  every stage's end."
  [:plan-reviewer :blueprint-reviewer :spec-reviewer :coder :tester :reviewer :orchestrator :security-reviewer])

(def name-re
  "A panel member's name, and the review record named after it: letters, digits, `.`, `_`, `-` and
  `:` - a model slug's last segment, as a candidate's id is, may carry a variant (`:free`, `:beta`)."
  #"[A-Za-z0-9._:-]+")

(defn member-name
  "What a security reviewer panel knows a member by: its `:name`, else its model slug's last
  segment (`openai/gpt-6-astra` is `gpt-6-astra`), as the review records' file names carry it."
  [member]
  (or (:name member) (last (str/split (:model member) #"/"))))

(defn security-panel
  "The profile's security reviewers in rotation order, each with its `:name`: `:security-panel`,
  or the one `:roles :security-reviewer` as a panel of one - a profile written before the panel
  reads unchanged."
  [profile]
  (mapv #(assoc % :name (member-name %))
        (or (:security-panel profile)
            (some-> (get-in profile [:roles :security-reviewer]) vector))))

(defn panel-split
  "`{:eligible [members] :left-out [{:name :family :reason}]}`. A member of the Coder's family
  reads nothing while the Coder is of it - it would share the blind spots it is there to look
  past (`verifiers`). Left out, and said; not a violation, since the panel outlives the Coder."
  [profile]
  (let [coder (get-in profile [:roles :coder :family])
        {out true in false} (group-by #(boolean (and coder (= coder (:family %)))) (security-panel profile))]
    {:eligible (vec in)
     :left-out (mapv (fn [m] {:name (:name m) :family (:family m)
                              :reason (str "the Coder's family (" (name coder) ")")})
                     out)}))

(defn as-roles
  "`profile` with `:roles :security-reviewer` filled from its panel - the first member eligible,
  else the first - so a reader of the eight roles reads a panel's profile as it read one before."
  [profile]
  (if (or (get-in profile [:roles :security-reviewer]) (empty? (:security-panel profile)))
    profile
    (let [m (or (first (:eligible (panel-split profile))) (first (security-panel profile)))]
      (assoc-in profile [:roles :security-reviewer] (dissoc m :name)))))

(defn role-blocks
  "Every role block the profile configures, `[label block]`: the eight roles and each panel member.
  What reads every block - the key and endpoint checks, the balance - reads this, so a panel
  member is never missed."
  [profile]
  (concat (dissoc (:roles profile) :security-reviewer)
          (for [m (security-panel profile)] [(str "security reviewer " (:name m)) (dissoc m :name)])))

(defn read-profile
  "The profile at `path`, or a throw naming the file.

  A missing profile is not a violation — it is a project that has not written
  one yet, and `violations` has nothing to say about a file that is not there.
  Reading is separate so the caller decides which that is."
  [path]
  (when-not (and path (fs/exists? path))
    (throw (ex-info "no profile" {:profile/error :no-file :path (str path)})))
  (try
    (edn/read-string (slurp (str path)))
    (catch Exception e
      (throw (ex-info "profile is not readable EDN"
                      {:profile/error :unreadable :path (str path)} e)))))

;; ---------------------------------------------------------------------------
;; structure — pure, offline, the same on every machine
;; ---------------------------------------------------------------------------

(defn- structural
  [{:keys [roles] :as profile}]
  (if-let [errs (shapes/explain-profile profile)]
    ;; One violation, not many: a profile that does not conform has no roles to
    ;; ask questions about, and every later check would report noise derived
    ;; from the same fault.
    [{:profile/error :invalid
      :detail "does not match the Profile schema"
      :errors errs}]
    (let [coder (get-in roles [:coder :family])
          panel (:security-panel profile)
          names (map member-name panel)]
      (cond-> (vec (for [r verifiers
                         :when (= coder (get-in roles [r :family]))]
                     {:profile/error :verifier-shares-coder-family
                      :role r
                      :detail (str (name r) " is " coder ", the same family as the coder"
                                   " — method §05 and decision log P0-3: a verifier that"
                                   " shares the Coder's family shares its blind spots")}))
        ;; ONE LIST, NOT TWO: a panel and a lone security reviewer would say two things
        (and panel (:security-reviewer roles))
        (conj {:profile/error :panel-and-security-reviewer
               :detail (str "the profile has a :security-panel and a :roles :security-reviewer - two lists;"
                            " keep the panel, with the reviewer as one of its members")})

        (and (nil? panel) (nil? (:security-reviewer roles)))
        (conj {:profile/error :no-security-reviewer
               :detail "neither :roles :security-reviewer nor :security-panel - every stage's end needs one"})

        (seq (remove #(re-matches name-re (str %)) names))
        (conj {:profile/error :panel-member-name
               :detail (str "a security panel member's name names its review records' files: "
                            (str/join ", " (map pr-str (remove #(re-matches name-re (str %)) names)))
                            " - give it a :name of letters, digits, `.`, `_`, `-` and `:`")})

        (not= (count names) (count (distinct names)))
        (conj {:profile/error :panel-duplicate-name
               :detail (str "two members of the security panel have the name "
                            (str/join ", " (for [[n c] (frequencies names) :when (> c 1)] n))
                            " - give one a :name; the folders and the rotation tell members apart by it")})

        (and (seq panel) (empty? (:eligible (panel-split profile))))
        (conj {:profile/error :panel-all-coder-family
               :detail (str "every member of the security panel is " coder ", the Coder's family -"
                            " none may read the Coder's work; add a member of another family")})))))

;; ---------------------------------------------------------------------------
;; environment — true or false depending on whose machine this is
;; ---------------------------------------------------------------------------

(defn probe-tool
  "Probe one toolchain spec. Pulled out and injectable for the same reason
  `doctor/probe` takes its index and pins as arguments: the alternative is a
  test that asserts something about the machine it happens to run on, and the
  seat-unavailable path would otherwise be exercised only by not having your
  own seat installed."
  [spec]
  (doctor/probe spec (doctor/bbin-index) (doctor/mise-pins)))

(defn- seat-violations
  [seat probe]
  (if-let [spec (first (filter #(= seat (:tool %)) doctor/toolchain))]
    (let [{:keys [status]} (probe spec)]
      (when-not (= :ok status)
        [{:profile/error :seat-unavailable
          :detail (str (name seat) " is the seat and `bb doctor` reports it "
                       (name status))}]))
    [{:profile/error :seat-unknown
      :detail (str "no tool named " (name seat) " in harness.setup.doctor/toolchain"
                   " — the seat has to be something the doctor can probe")}]))

(defn- key-violations
  [roles]
  (vec (for [[r {:keys [key-env]}] roles
             :when (and key-env (str/blank? (System/getenv key-env)))]
         {:profile/error :key-env-unset
          :role r
          ;; The variable's NAME, never a value, and never a hint about one.
          :detail (str key-env " is named by " (name r) " and is not set")})))

;; ---------------------------------------------------------------------------
;; reachability — network
;; ---------------------------------------------------------------------------

(defn reachable?
  "Whether `endpoint` answers at all.

  ANY HTTP RESPONSE COUNTS, 401 included. The question is whether the host is
  there and serving, not whether this profile is authorised — authorisation is
  what `:key-env` is about, and conflating the two would report a missing key
  as a dead endpoint. `:throw false` because a 4xx is an answer."
  [endpoint]
  (try
    (some? (:status (http/get endpoint {:throw false :timeout 5000})))
    (catch Exception _ false)))

(defn- endpoint-violations
  [roles]
  (vec (for [[endpoint rs] (group-by #(get-in (val %) [:endpoint]) roles)
             :when (not (reachable? endpoint))]
         {:profile/error :endpoint-unreachable
          :role (mapv key rs)
          :detail (str endpoint " did not answer")})))

;; ---------------------------------------------------------------------------

(defn violations
  "Everything wrong with `profile`, as data.

  Structural checks always run. `:env?` adds the seat and key-variable checks;
  `:probe?` adds one request per distinct endpoint. Both are off by default so
  that the default is the answer that does not depend on the machine.

  A structural failure short-circuits: a profile that is not a Profile has no
  roles to ask about, and the rest would report noise derived from one fault.

  `:probe-tool` replaces how the seat is looked up, for tests."
  ([profile] (violations profile nil))
  ([profile {:keys [env? probe?] probe-tool* :probe-tool}]
   (let [structure (structural profile)]
     (if (seq structure)
       structure
       (cond-> []
         env? (into (seat-violations (:seat profile) (or probe-tool* probe-tool)))
         env? (into (key-violations (role-blocks profile)))
         probe? (into (endpoint-violations (role-blocks profile))))))))

(defn verify!
  "`violations`, but throwing — the same shape as `sigs/verify!` and
  `provision/assemble!`. A profile is read once at the top of a run, and a
  wrong one dispatches every role to the wrong place."
  ([profile] (verify! profile nil))
  ([profile opts]
   (let [v (violations profile opts)]
     (when (seq v)
       (throw (ex-info "invalid profile" {:violations v})))
     v)))

(defn summary
  "The profile as lines a human can check at a glance: the seat, then each
  role's family, model and endpoint. Independence is the thing being eyeballed,
  so family comes before model."
  [{:keys [seat] :as profile}]
  (let [role-map (:roles (as-roles profile))
        line (fn [label {:keys [family model endpoint]}]
               (format "%-19s %-11s %-34s %s" label (name family) model endpoint))
        out (set (map :name (:left-out (panel-split profile))))]
    (into [(str "seat  " (name seat))]
          (mapcat (fn [r]
                    (if (and (= r :security-reviewer) (:security-panel profile))
                      ;; a line per member, in rotation order, the left-out said in place of its endpoint
                      (for [m (security-panel profile)]
                        (if (out (:name m))
                          (format "%-19s %-11s %-34s %s" "security panel" (name (:family m)) (:model m)
                                  "left out: the Coder's family")
                          (line "security panel" m)))
                      [(line (name r) (get role-map r))]))
                  roles))))

(defn newer-models
  "Per role of `profile`, what `listing` (the catalogue's `:data`) carries in
  the same line as the role's model with a later version - `[{:role _ :model
  _ :newer [rows]} …]`, newest first, roles with none left out. The age of a
  selection, said by a command: every brief checked it by hand against the
  same listing."
  [profile listing]
  (vec (for [r roles
             :let [m (get-in (as-roles profile) [:roles r :model])
                   newer (catalogue/newer-in-line listing m)]
             :when (seq newer)]
         {:role r :model m :newer newer})))

(defn newer-lines
  "`newer-models` as the lines `bb profile` prints: information, worded as
  the doctor words *newer than tested*, and never a violation - the listing
  says nothing about whether the newer model does the role's job better.
  A selection is measured before it changes."
  [profile listing]
  (vec (for [{:keys [role model newer]} (newer-models profile listing)]
         (str (name role) "  " model ": newer in its line - "
              (str/join ", " (for [{:keys [id date]} newer] (str id " (listed " date ")")))
              " - information, not a fault; measure before changing (bb bake-off)"))))

(defn examples
  "Every worked example the seed ships, as `{seat-name profile}`.

  Keyed by the FILE's name rather than the profile's `:seat`, so a file
  whose name and seat disagree is visible rather than silently deduplicated
  — a test asserts they match."
  ([] (examples examples-dir))
  ([dir]
   (into (sorted-map)
         (for [f (sort (fs/glob dir "*.edn"))]
           [(str/replace (fs/file-name f) #"\.edn$" "")
            (read-profile f)]))))

;; ---------------------------------------------------------------------------
;; the roster's Model column, written from the shipped `claude` example
;; ---------------------------------------------------------------------------
;; `roster.md` maps every act of a build to its actor, and its Model column said the `claude`
;; example's models by hand - and fell behind it for twelve days (register row 235). The column
;; is written from the profile now, and a test holds the file to it.

(defn- route
  "How a role is reached, as the roster says it."
  [{:keys [endpoint params]}]
  (cond (str/includes? endpoint "api.anthropic.com") "direct to Anthropic"
        (str/includes? endpoint "generativelanguage.googleapis.com") "direct to Google"
        (str/includes? endpoint "openrouter.ai")
        (if-let [only (seq (get-in params [:provider :only]))]
          (str "OpenRouter pinned to " (str/join ", " (map #(str "`" % "`") only)))
          "OpenRouter")
        :else endpoint))

(defn- model-text [{:keys [model params max-rounds] :as role}]
  (str/join ", " (remove nil? [(str "`" model "`")
                               (or (:reasoning_effort params) (get-in params [:output_config :effort]))
                               (route role)
                               (when max-rounds (str max-rounds " rounds"))])))

(defn roster-model
  "The roster's Model cell for `role` of `profile`: the model, its effort, its route; for
  `:security-panel` (or `:security-reviewer`) every member in rotation order, one of the Coder's
  family said to be left out."
  [profile role]
  (if (#{:security-panel :security-reviewer} role)
    (let [out (set (map :name (:left-out (panel-split profile))))]
      (str "in rotation: "
           (str/join "; " (for [m (security-panel profile)]
                            (str (model-text m) (when (out (:name m)) " - left out while it is the Coder's family"))))))
    (model-text (get-in profile [:roles role]))))

(defn render-roster
  "`text` (the roster) with the Model cell of every table row whose Profile role cell names a role
  of `profile` - its first backquoted keyword - written from the profile. Every other byte kept.
  A row is a line that starts `| ` and ends ` |`; any other line is left as it is. A row that names
  a role but has not six cells - a ` | ` typed inside a cell - is refused by its line number: the
  check claims it and cannot read it, and a row skipped without a word is how the column fell
  behind."
  [text profile]
  (->> (str/split text #"\n" -1)
       (map-indexed
        (fn [i line]
          (if-not (and (>= (count line) 4) (str/starts-with? line "| ") (str/ends-with? line " |"))
            line
            (let [cells (str/split (subs line 2 (- (count line) 2)) #" \| " -1)
                  role (some->> (get cells 3) (re-find #"`:([a-z-]+)`") second keyword)
                  ours? (and role (or (get-in profile [:roles role]) (#{:security-panel} role)))]
              (cond (not ours?) line
                    (not= 6 (count cells))
                    (throw (ex-info (str "roster.md line " (inc i) ": " (count cells)
                                         " cells, 6 expected - a ' | ' inside a cell?")
                                    {:line (inc i) :cells (count cells)}))
                    :else
                    (str "| " (str/join " | " (assoc (vec cells) 4 (roster-model profile role))) " |"))))))
       (str/join "\n")))

;; portability.md's two tables and the README's profile sentence say the same models by hand, each
;; cell beside its dated history, which the profile does not hold: they are checked, not written.

(defn- effort [{:keys [params]}]
  (or (:reasoning_effort params) (get-in params [:output_config :effort])))

(defn- stated-gaps
  "What `cell` fails to say of `members` (one role's selection, or the panel in rotation order):
  each model backquoted, its name without the router's prefix, in order; from it to the next
  member's name, `effort <e>` when `effort?` and `left out` for a member left out; in the cell,
  `direct` for a direct route and, when `openrouter?`, `OpenRouter` for the router's."
  [cell members left-out {:keys [effort? openrouter?]}]
  (let [names (map #(str "`" (str/replace (:model %) #"^[a-z-]+/" "") "`") members)
        at (reductions (fn [from nm] (some-> from (#(str/index-of cell nm %)))) 0 names)
        at (rest at)]
    (concat
     (for [[nm i] (map vector names at) :when (nil? i)] (str "names no " nm))
     (when (every? some? at)
       (for [[m i end] (map vector members at (concat (rest at) [(count cell)]))
             :let [seg (subs cell i end)
                   nm (str/replace (:model m) #"^[a-z-]+/" "")]
             gap [(when (and effort? (not (re-find (re-pattern (str "\\beffort " (effort m) "\\b")) seg)))
                    (str nm ": no `effort " (effort m) "`"))
                  (when (and (left-out (:name m)) (not (str/includes? seg "left out")))
                    (str nm ": not said to be left out"))]
             :when gap]
         gap))
     (when (and (some #(not (str/includes? (:endpoint %) "openrouter.ai")) members)
                (not (str/includes? cell "direct")))
       ["no `direct`"])
     (when (and openrouter? (some #(str/includes? (:endpoint %) "openrouter.ai") members)
                (not (str/includes? cell "OpenRouter")))
       ["no `OpenRouter`"]))))

(defn- role-members [profile role]
  (if (= :security-panel role)
    (security-panel profile)
    [(get-in profile [:roles role])]))

(def stated-tables
  "The tables that say the `claude` example's models by hand: in `file` (from `harness/`), the
  rows after the line starting `header`, the selection in cell `column`, the role named by cell 0."
  [{:file "../portability.md" :header "| | seat `claude` | seat `agy-ide` |" :column 1
    :effort? true :openrouter? true
    :labels {"coder" :coder "tester" :tester "reviewer" :reviewer "spec-reviewer" :spec-reviewer
             "plan-reviewer" :plan-reviewer "blueprint-reviewer" :blueprint-reviewer
             "security-reviewer" :security-panel "orchestrator" :orchestrator}}
   {:file "../portability.md" :header "| Role | Constraint | Family | Model (as the shipped `claude.edn`"
    :column 3 :effort? true :openrouter? false
    :labels {"**Coder**" :coder "**Tester**" :tester "**Code reviewer**" :reviewer
             "**Spec reviewer**" :spec-reviewer "**Plan reviewer**" :plan-reviewer
             "**Blueprint reviewer**" :blueprint-reviewer "**Security reviewer**" :security-panel
             "**Orchestrator**" :orchestrator}}])

(defn table-gaps
  "Each line where `text`'s table (one of `stated-tables`) fails to say `profile`'s selection,
  and each role of the table it has no row for. A cell saying it is `the spec reviewer's
  selection` holds when the two roles are the same block. Empty when the table says them all."
  [text profile {:keys [file header column labels] :as table}]
  (let [lines (str/split-lines text)
        start (first (keep-indexed (fn [i l] (when (str/starts-with? l header) i)) lines))]
    (if-not start
      [(str file ": no table under `" header "`")]
      (let [rows (->> (map vector (range) lines) (drop (+ 2 start)) (take-while #(str/starts-with? (second %) "| ")))
            out (set (map :name (:left-out (panel-split profile))))
            seen (keep (fn [[i l]]
                         (let [cells (mapv str/trim (str/split (subs l 1) #"\|"))]
                           (when-let [role (labels (first cells))] [i role (get cells column "")])))
                       rows)]
        (concat
         (for [[i role cell] seen
               gap (if (str/starts-with? cell "the spec reviewer's selection")
                     (when (not= (get-in profile [:roles role]) (get-in profile [:roles :spec-reviewer]))
                       ["says the spec reviewer's selection, and it is not"])
                     (stated-gaps cell (role-members profile role) out table))]
           (str file " line " (inc i) ", " (name role) ": " gap))
         (for [role (sort (set/difference (set (vals labels)) (set (map second seen))))]
           (str file ": no row for " (name role))))))))

(defn readme-gaps
  "Where the README's profile sentence, `(its Coder is ...)`, fails to say `profile`'s Coder."
  [text profile]
  (let [flat (str/replace text #"\s+" " ")
        sentence (second (re-find #"\(its Coder is ([^)]*)\)" flat))]
    (if-not sentence
      ["README.md: no `(its Coder is ...)`"]
      (map #(str "README.md, its Coder: " %)
           (stated-gaps sentence [(get-in profile [:roles :coder])] #{} {:openrouter? true})))))

(defn roster-sync-main
  "`bb roster-sync`: `roster.md`'s Model column written from the shipped `claude` example."
  [& _]
  (let [f "roster.md"
        before (slurp f)
        after (render-roster before (get (examples) "claude"))]
    (if (= before after)
      (println "roster.md: the Model column is the claude example's already")
      (do (spit f after) (println "roster.md: the Model column written from resources/profiles/claude.edn")))))

(defn -main
  "bb profile — check a profile.

  With no arguments: this project's own profile - `<build>/profile.edn` of the
  workspace this clone is in, or the one `--workspace <dir>` / `KIT_WORKSPACE`
  points at (`project-profile`) - if there is one, and otherwise every worked
  example the seed ships, structurally. The header says which, because
  printing a template as though it were your configuration is how the first
  version of this file was misread.

  `--seat <name>` picks one shipped example and adds the environment checks;
  `--file <path>` takes any profile; `--probe` adds one request per endpoint.
  Exits non-zero on any violation, so it composes into a shell the way the
  gates do.

  After the violations, one line per role whose model has a newer model in
  its line in the catalogue (`newer-lines`, over OpenRouter's public listing,
  no key sent): information, never a violation. `--offline` skips the
  listing; the examples-only fallback never fetches it, so `bb gates` can
  run on a plane."
  [& args]
  (let [ws (workspace/current-or-exit args)
        {:keys [args]} (workspace/split-args args)
        flags (set args)
        arg (fn [f] (second (drop-while #(not= f %) args)))
        project-profile (fn [] (plan-profile ws))
        opts {:env? true :probe? (contains? flags "--probe")}
        listing (delay (try (catalogue/fetch-listing)
                            (catch Exception e
                              (println (str "  the model listing did not answer (" (ex-message e)
                                            "); newer models not checked"))
                              nil)))
        report (fn [label profile opts]
                 (println (str "  " label))
                 (doseq [l (summary profile)] (println (str "  " l)))
                 (println)
                 (when (and (nil? (:security-panel profile)) (get-in profile [:roles :security-reviewer]))
                   (println "  the security reviewer panel is its one :security-reviewer; a :security-panel adds members")
                   (println "  (the shipped examples show one)")
                   (println))
                 (let [v (violations profile opts)]
                   (doseq [{:keys [profile/error detail]} v]
                     (println (str "  " (name error) " — " detail)))
                   (when-not (contains? flags "--offline")
                     (when-let [l @listing]
                       (doseq [line (newer-lines profile l)] (println (str "  " line)))))
                   v))]
    (cond
      (arg "--file")
      (let [v (report (arg "--file") (read-profile (arg "--file")) opts)]
        (when (seq v) (System/exit 1)))

      (arg "--seat")
      (if-let [profile (get (examples) (arg "--seat"))]
        (let [v (report (str examples-dir "/" (arg "--seat")
                             ".edn — a worked example, not this project's config")
                        profile opts)]
          (when (seq v) (System/exit 1)))
        (do (println (str "  no example for seat " (arg "--seat")
                          " — have " (str/join ", " (keys (examples)))))
            (System/exit 1)))

      (project-profile)
      (let [path (project-profile)
            v (report (str path " — this project's profile, in the workspace's plan")
                      (read-profile path) opts)]
        (when (seq v) (System/exit 1)))

      :else
      ;; No profile of its own. Structural checks only: whether a SEAT is
      ;; installed or a KEY is set says something about this machine, and
      ;; nothing about an example that was never meant to run here.
      (let [all (examples)
            bad (into {} (keep (fn [[k p]] (when-let [v (seq (violations p))] [k (vec v)])) all))]
        (println (str "  No project profile: this clone is in no workspace whose plan has a "
                      profile-file-name " (`bb init` writes one)."))
        (println "  The KIT's own repository runs no dispatch loop (see CLAUDE.md); what follows is the")
        (println (str "  shipped worked examples in " examples-dir "/, checked structurally.\n"))
        (doseq [[nm p] all]
          (println (format "  %-9s %-6s %s"
                           nm (if (bad nm) "FAILS" "ok")
                           (str/join "  " (for [r roles]
                                            (str (name r) " " (name (get-in (as-roles p) [:roles r :family]))))))))
        (when (seq bad)
          (println)
          (doseq [[nm vs] bad, {:keys [profile/error detail]} vs]
            (println (str "  " nm ": " (name error) " — " detail)))
          (System/exit 1))))))
