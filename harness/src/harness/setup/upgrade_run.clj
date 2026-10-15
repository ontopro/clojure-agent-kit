(ns harness.setup.upgrade-run
  "`bb upgrade --after-pull`: phase 2 of a KIT upgrade - the workspace brought up to the KIT
  just checked out, by that KIT's own code.

  TWO PHASES, SPLIT BY WHOSE CODE RUNS. Phase 1, the check and the move, is the old version's:
  it touches nothing outside `KIT/` and ends by starting a new process on the new version.
  Phase 2 is this: the new version alone knows what its release changed. In the plan's order,
  each step recorded in `work/upgrades/<id>/done.edn` as it finishes, so a run after a failure
  picks up at the first step not done, and a copy of every root file is kept under `before/`
  there - the workspace root is no repository, so that folder is the way back:

    workspace-keys   `workspace.edn`'s renamed keys migrated FIRST - every step below reads the
                     file through this version's code, and a renamed key reads as nil until then
    front-door       the workspace's `bb.edn`, so the tasks after it exist
    skills           the skills rendered, the stale renderings removed
    orientation      the workspace `CLAUDE.md` rewritten while its marker's hash still matches
                     the text below it; edited or unmarked, left alone and the new text written
                     beside it as `CLAUDE.md.new` (marked, so taking it whole re-adopts the
                     file); the off-loop role file copied again when it is still the old
                     commit's copy, else the same `.new`
    build-docs       the diff from the recorded KIT commit to HEAD over `build-docs/`, applied to
                     the build's `docs/` HUNK BY HUNK: a hunk that applies is applied; one that
                     does not is dropped as \"instruction already removed\" only when every line
                     it removes is a template instruction (a blockquote line) now gone from the
                     document and every line it adds is one too; any other hunk is a conflict,
                     saved under `conflicts/`, named by file, never a `.rej` left in the build.
                     A document with uncommitted edits stops the step by name. The documents
                     touched are committed in the build with the release named
    loop-edn         an old `loop.edn`'s literal KIT path rewritten to `{{kit}}`
    app              the application's upgrade branch: `bb template-upgrade` with the rule
                     mirror re-rendered on that branch before its gates - one branch, one merge,
                     one decision; a release that leaves the pin alone still gets the branch for
                     the mirror, and a branch with nothing on it is no run
    workspace-edn    `:workspace/kit-commit` and `:workspace/kit-version` moved to this KIT -
                     the one source the doctor and the next upgrade read - and the upgrade's
                     record under `:workspace/upgraded-to`: what it left for the Architect
    doctor           the doctor's report, whose notice says the release is taken and what awaits

  CUMULATIVE: the diffs run from the workspace's recorded commit to HEAD, so a workspace comes
  from any older version, releases skipped included. The first failure stops it; `--dry-run`
  says what it would do."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.rules :as rules]
   [harness.setup.front-door :as front-door]
   [harness.setup.init :as init]
   [harness.setup.skills :as skills]
   [harness.setup.template :as template]
   [harness.setup.template-upgrade :as tu]
   [harness.setup.upgrade :as upgrade]
   [harness.setup.workspace :as workspace]))

(def steps
  "The steps, in order; each is a keyword `done.edn` records."
  [:workspace-keys :front-door :skills :orientation :build-docs :loop-edn :app :workspace-edn :doctor])

(def role-file ".claude/agents/interactive-programmer.md")
(def role-source "harness/agents/interactive-programmer.md")

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn upgrade-id
  "The upgrade's folder under `work/upgrades/`, deterministic: a run after a failure finds it."
  [from to version]
  (str "to-" (or version (subs to 0 7)) "-from-" (subs from 0 7)))

(defn migrate-keys
  "`raw` (`workspace.edn` as written) with each key the harness no longer reads
  (`upgrade/renamed-keys`) moved to the key that took its place, when that key is absent."
  [raw]
  (reduce (fn [m [old new]]
            (if (and (contains? m old) (not (contains? m new)))
              (-> m (assoc new (get m old)) (dissoc old))
              (dissoc m old)))
          raw
          upgrade/renamed-keys))

(defn rewrite-kit-path
  "`loop-text` with a literal KIT path in a gate command made `{{kit}}` - `bb --config
  <anything>/harness/bb.edn` - as `[text changed?]`."
  [loop-text]
  (let [out (str/replace loop-text #"--config \S+/harness/bb\.edn" "--config {{kit}}/harness/bb.edn")]
    [out (not= out loop-text)]))

(defn parse-diff
  "A unified diff (`git diff`) as `[{:path rel :new? :deleted? :hunks [{:header :lines}]}]`,
  `rel` the path under the old root (`a/`) or the new one for an added file, the roots
  stripped; a hunk's `:lines` keep their first character (` `, `-`, `+`)."
  [text]
  (let [lines (str/split-lines (str text))
        file-start #(re-matches #"diff --git a/(.*) b/(.*)" %)]
    (loop [ls lines files []]
      (if (empty? ls)
        files
        (let [[l & more] ls]
          (if-let [[_ a-path b-path] (file-start l)]
            (let [[header-lines rest*] (split-with #(not (str/starts-with? % "@@")) more)
                  new? (some #(= "--- /dev/null" %) header-lines)
                  deleted? (some #(= "+++ /dev/null" %) header-lines)
                  [hunk-lines after] (split-with #(not (file-start %)) rest*)
                  hunks (loop [hl hunk-lines acc []]
                          (if (empty? hl)
                            acc
                            (let [[h & body] hl
                                  [content tail] (split-with #(not (str/starts-with? % "@@")) body)]
                              (recur tail (conj acc {:header h :lines (vec (remove #(str/starts-with? % "\\ No newline") content))})))))]
              (recur after (conj files {:path (if new? b-path a-path) :new? (boolean new?) :deleted? (boolean deleted?) :hunks hunks})))
            (recur more files)))))))

(defn instruction-line?
  "A template instruction as `plan-check` reads one: a blockquote line."
  [line]
  (str/starts-with? (str/triml line) ">"))

(defn hunk-class
  "Why a hunk that does not apply to `doc-text` may be dropped, or not: `:instruction-removed`
  when every line it removes is an instruction now gone from the document and every line it
  adds is an instruction too - the project deleted the template's instruction, as the
  template told it to, and the template reworded it; else `:conflict`. ADDED LINES COUNT: a
  hunk whose removed instruction is gone but which adds a heading, a row or a section is
  content the project would lose without a word."
  [{:keys [lines]} doc-text]
  (let [removed (map #(subs % 1) (filter #(str/starts-with? % "-") lines))
        added (map #(subs % 1) (filter #(str/starts-with? % "+") lines))
        doc-lines (set (map str/trim (str/split-lines (str doc-text))))]
    (if (and (seq removed)
             (every? instruction-line? removed)
             (not-any? #(contains? doc-lines (str/trim %)) removed)
             (every? instruction-line? added))
      :instruction-removed
      :conflict)))

(defn hunk-patch
  "One hunk of `rel` (under the build's `docs/`) as a patch `git apply` reads, counts to be
  recounted."
  [rel {:keys [header lines]}]
  (str "--- a/docs/" rel "\n+++ b/docs/" rel "\n" header "\n" (str/join "\n" lines) "\n"))

(defn report-text
  "`report.md` from the upgrade's facts and its steps' records."
  [{:keys [id from to version at]} records]
  (str "# KIT upgrade " id "\n\n"
       "From commit `" (subs from 0 7) "` to `" (subs to 0 7) "`" (when version (str " (" version ")")) ", " at ".\n\n"
       (str/join "\n" (for [s steps :let [{:keys [status detail]} (get records s)]]
                        (str "- **" (name s) "**: " (if status (str (name status) (when detail (str " - " detail))) "not run"))))
       "\n"))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn- git [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)]
    {:exit exit :out (str/trim (str out err))}))

(defn- git! [dir & args]
  (let [{:keys [exit out]} (apply git dir args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed in " dir ": " out) {:upgrade/error :git :out out})))
    out))

(defn- say [& xs] (println (apply str "  " xs)))

(defn kit-dir
  "The KIT this process runs from: the folder above the `bb.edn` babashka was given
  (`bb --config KIT/harness/bb.edn upgrade --after-pull` from the workspace, or the front door),
  else the parent of the working directory (`harness/`)."
  []
  (or (some-> (System/getProperty "babashka.config") fs/absolutize fs/normalize fs/parent fs/parent str)
      (str (fs/parent (fs/normalize (fs/absolutize "."))))))

(defn- read-done [done-file]
  (if (fs/exists? done-file) (edn/read-string {:default tagged-literal} (slurp (str done-file))) {}))

(defn- backup! [before-dir ws-dir rel]
  (let [src (fs/path ws-dir rel)]
    (when (fs/exists? src)
      (let [dst (fs/path before-dir rel)]
        (fs/create-dirs (fs/parent dst))
        (when-not (fs/exists? dst) (fs/copy src dst))))))

(defn- raw-workspace [ws]
  (edn/read-string (slurp (str (fs/path (:workspace/dir ws) workspace/file-name)))))

(defn- write-workspace! [ws raw]
  (spit (str (fs/path (:workspace/dir ws) workspace/file-name)) (init/workspace-edn raw)))

(defn- app-name [ws]
  (let [f (fs/path (:workspace/app ws) "layers.edn")]
    (or (when (fs/exists? f) (tu/app-name-of (slurp (str f))))
        (str/replace (fs/file-name (:workspace/app ws)) #"-app$" ""))))

;; the steps -----------------------------------------------------------------

(defn- step-workspace-keys! [{:keys [ws before]}]
  (let [raw (raw-workspace ws)
        migrated (migrate-keys raw)]
    (if (= raw migrated)
      {:status :done :detail "no renamed key"}
      (do (backup! before (:workspace/dir ws) workspace/file-name)
          (write-workspace! ws migrated)
          {:status :done :detail (str "renamed: " (str/join ", " (for [[o n] upgrade/renamed-keys :when (contains? raw o)] (str o " -> " n))))}))))

(defn- step-front-door! [{:keys [ws kit before]}]
  (backup! before (:workspace/dir ws) "bb.edn")
  (let [{:keys [existed? generated? changed? missing]} (front-door/sync! kit (:workspace/dir ws))]
    (cond
      (and existed? (not generated?)) {:status :done :detail "bb.edn is not one bb init wrote: left as it is"}
      changed? {:status :done :detail (str "written" (when (seq missing) (str "; it now offers " (str/join ", " missing))))}
      :else {:status :done :detail "already current"})))

(defn- step-skills! [{:keys [ws kit head]}]
  (let [rs (skills/sync! kit (:workspace/dir ws) :commit head)
        written (map :name (filter :changed? rs))
        pruned (map :name (skills/prune! kit (:workspace/dir ws)))]
    {:status :done
     :detail (str (if (seq written) (str "written: " (str/join ", " written)) "all current")
                  (when (seq pruned) (str "; removed, no longer shipped: " (str/join ", " pruned))))}))

(defn- step-orientation! [{:keys [ws kit before from head version]}]
  (let [ws-dir (:workspace/dir ws)
        raw (raw-workspace ws)
        claude (fs/path ws-dir "CLAUDE.md")
        want (init/with-marker (init/workspace-claude-md (app-name ws) raw) version head)
        have (when (fs/exists? claude) (slurp (str claude)))
        state (if have (init/marker-state have) :missing)
        claude-r (cond
                   (= have want) "CLAUDE.md current"
                   (#{:current :missing} state) (do (backup! before ws-dir "CLAUDE.md") (spit (str claude) want) "CLAUDE.md rewritten")
                   :else (do (spit (str (fs/path ws-dir "CLAUDE.md.new")) want)
                             (str "CLAUDE.md is " (name state) " - the project's; the new text is beside it as CLAUDE.md.new")))
        role (fs/path ws-dir role-file)
        new-role (slurp (str (fs/path kit role-source)))
        old-role (:out (git kit "show" (str from ":" role-source)))
        have-role (when (fs/exists? role) (slurp (str role)))
        role-r (cond
                 (= have-role new-role) "the role file current"
                 (or (nil? have-role) (= (str/trimr have-role) (str/trimr old-role)))
                 (do (backup! before ws-dir role-file) (fs/create-dirs (fs/parent role)) (spit (str role) new-role) "the role file copied again")
                 :else (do (spit (str role ".new") new-role)
                           (str "the role file is edited - the project's; the new one is beside it as " (fs/file-name role) ".new")))
        new-files (cond-> []
                    (str/includes? claude-r ".new") (conj "CLAUDE.md.new")
                    (str/includes? role-r ".new") (conj (str role-file ".new")))]
    {:status :done :detail (str claude-r "; " role-r) :new new-files}))

(defn- step-build-docs! [{:keys [ws kit dir from head version]}]
  (let [build (:workspace/build ws)
        diff (:out (git kit "diff" from head "--" "build-docs/"))
        files (parse-diff diff)
        conflicts-dir (fs/path dir "conflicts")
        results (for [{:keys [path new? deleted? hunks]} files
                      :let [rel (subs path (count "build-docs/"))
                            target (fs/path build "docs" rel)]]
                  (do
                    (when-not (str/blank? (:out (git build "status" "--porcelain" "--" (str "docs/" rel))))
                      (throw (ex-info (str "docs/" rel " has uncommitted edits in " build ": commit or set them aside, then `bb upgrade --after-pull` again")
                                      {:upgrade/error :uncommitted :path (str target)})))
                    (cond
                      deleted? {:rel rel :outcome :deleted-in-kit}
                      new? (let [content (:out (git kit "show" (str head ":" path)))]
                             (cond (not (fs/exists? target)) (do (fs/create-dirs (fs/parent target)) (spit (str target) (str content "\n")) {:rel rel :outcome :added :touched true})
                                   (= (str/trim (slurp (str target))) (str/trim content)) {:rel rel :outcome :same}
                                   :else (let [c (fs/path conflicts-dir "docs" (str rel ".new"))]
                                           (fs/create-dirs (fs/parent c)) (spit (str c) content) {:rel rel :outcome :conflict :conflicts [(str (fs/relativize (:workspace/dir ws) c))]})))
                      (not (fs/exists? target)) {:rel rel :outcome :not-in-build}
                      :else
                      (let [r (reduce (fn [acc [n hunk]]
                                        (let [patch (fs/path dir "patches" (str rel "." n ".patch"))
                                              _ (fs/create-dirs (fs/parent patch))
                                              _ (spit (str patch) (hunk-patch rel hunk))
                                              {:keys [exit]} (git build "apply" "--check" "--recount" (str patch))]
                                          (if (zero? exit)
                                            (do (git! build "apply" "--recount" (str patch)) (update acc :applied inc))
                                            (case (hunk-class hunk (slurp (str target)))
                                              :instruction-removed (update acc :dropped inc)
                                              :conflict (let [c (fs/path conflicts-dir "docs" (str rel "." n ".patch"))]
                                                          (fs/create-dirs (fs/parent c))
                                                          (fs/copy patch c {:replace-existing true})
                                                          (update acc :conflicts conj (str (fs/relativize (:workspace/dir ws) c))))))))
                                      {:applied 0 :dropped 0 :conflicts []}
                                      (map-indexed (fn [i h] [(inc i) h]) hunks))]
                        (assoc r :rel rel :outcome :patched :touched (pos? (:applied r)))))))
        results (vec results)
        touched (map #(str "docs/" (:rel %)) (filter :touched results))
        conflicts (vec (mapcat :conflicts results))]
    (when (seq touched)
      (apply git! build "add" "--" touched)
      (git! build "commit" "-q" "-m" (str "KIT " (or version (subs head 0 7)) ": the build documents the release changed, from "
                                          (subs from 0 7) " to " (subs head 0 7))))
    {:status :done
     :detail (str (count files) " file" (when (not= 1 (count files)) "s") " changed in build-docs/: "
                  (str/join "; " (for [{:keys [rel outcome applied dropped] cs :conflicts} results]
                                   (str rel " "
                                        (case outcome
                                          :patched (str applied " applied, " dropped " dropped as instructions removed, " (count cs) " conflict" (when (not= 1 (count cs)) "s"))
                                          :added "added" :same "already there" :conflict "exists and differs: the KIT's beside it"
                                          :deleted-in-kit "deleted in the KIT: left" :not-in-build "not in the build: left"))))
                  (when (seq touched) (str "; committed in the build")))
     :conflicts conflicts}))

(defn- step-loop-edn! [{:keys [ws head version]}]
  (let [build (:workspace/build ws)
        f (fs/path build "loop.edn")]
    (if-not (fs/exists? f)
      {:status :done :detail "no loop.edn"}
      (let [[out changed?] (rewrite-kit-path (slurp (str f)))]
        (if-not changed?
          {:status :done :detail "names no literal KIT path"}
          (do (spit (str f) out)
              (git! build "add" "--" "loop.edn")
              (git! build "commit" "-q" "-m" (str "KIT " (or version (subs head 0 7)) ": loop.edn names the KIT by {{kit}}, so the workspace can be copied or moved"))
              {:status :done :detail "the literal KIT path rewritten to {{kit}}, committed in the build"}))))))

(defn- mirror-hook
  "`:before-gates` for the application's branch: the rule mirrors re-rendered in the worktree
  from the project's rules, committed there when changed. Idempotent."
  [ws version]
  (fn [wt]
    (let [app (:workspace/app ws)
          rels (for [m (:workspace/rule-mirrors ws) :when (str/starts-with? m (str app "/"))]
                 (str (fs/relativize app m)))
          changed (doall (for [rel rels
                               :let [path (fs/path wt rel)]
                               :when (fs/exists? path)
                               :let [{:keys [changed?]} (rules/sync! (str path) :rules (rules/project-rules ws))]
                               :when changed?]
                           rel))]
      (when (seq changed)
        (apply git! wt "add" "--" changed)
        (git! wt "commit" "-q" "-m" (str "KIT " version ": the rule mirror re-rendered from the release's rule source"))
        (say "the rule mirror re-rendered on the branch: " (str/join ", " changed))))))

(defn- step-app! [{:keys [ws version generate gates]}]
  (let [r (tu/upgrade! ws (cond-> {:pin (template/pin (template/load-pins))
                                   :local-root (some-> (System/getenv "KIT_TEMPLATE_LOCAL") not-empty fs/absolutize fs/normalize str)
                                   :gates (or gates (tu/project-gates ws))
                                   :id (str "upgrade-" version)
                                   :branch-anyway? true
                                   :before-gates (mirror-hook ws version)}
                            generate (assoc :generate generate)))
        run-dir (str (fs/path (:workspace/work ws) "runs" (str "upgrade-" version)))]
    {:status :done
     :detail (case (:outcome r)
               :awaiting-merge (str "the branch upgrade-" version " is gated green and awaits its merge: " (tu/merge-command run-dir))
               :conflict (str "the template's merge stopped on " (str/join ", " (:conflicts r)) " - the Architect's, in the worktree; `bb template-upgrade` after")
               :gates-red (str "the gate " (name (:failed r)) " is red on the branch - the Architect's; `bb template-upgrade` after")
               :nothing "the application needs nothing: the template as it was and the mirror current"
               (str (:outcome r)))
     :app (when (not= :nothing (:outcome r))
            {:branch (str "upgrade-" version) :run-dir run-dir :state (:outcome r)})}))

(defn- step-workspace-edn! [{:keys [ws before from head version at records]}]
  (backup! before (:workspace/dir ws) workspace/file-name)
  (let [raw (raw-workspace ws)
        record {:from from :to head :version version :at at
                :app (get-in records [:app :app])
                :conflicts (vec (get-in records [:build-docs :conflicts]))
                :new (vec (get-in records [:orientation :new]))}]
    (write-workspace! ws (assoc raw :workspace/kit-commit head :workspace/kit-version version :workspace/upgraded-to record))
    {:status :done :detail (str ":workspace/kit-commit moved to " (subs head 0 7) (when version (str " (" version ")")) "; the upgrade recorded")}))

(defn- step-doctor! [{:keys [ws kit]}]
  (let [ws* (workspace/find-workspace (:workspace/dir ws))]
    (println)
    (println (upgrade/report ws* kit))
    {:status :done :detail "printed"}))

(def step-fns
  {:workspace-keys step-workspace-keys! :front-door step-front-door! :skills step-skills!
   :orientation step-orientation! :build-docs step-build-docs! :loop-edn step-loop-edn!
   :app step-app! :workspace-edn step-workspace-edn! :doctor step-doctor!})

(defn after-pull!
  "Phase 2 over the workspace `ws` from the KIT at `kit`. Opts: `:generate` and `:gates` for
  the application's step (tests); `:now`. Returns `{:ok? bool :records {step record} :dir}`."
  [ws kit {:keys [now] :as opts}]
  (let [raw (raw-workspace ws)
        from (or (:workspace/kit-commit raw)
                 (throw (ex-info "workspace.edn records no :workspace/kit-commit: nothing says which KIT this workspace is at, so nothing can say what changed since" {:upgrade/error :no-commit})))
        head (git! kit "rev-parse" "HEAD")
        version (let [{:keys [exit out]} (git kit "describe" "--tags" "--abbrev=0")] (when (zero? exit) out))
        _ (when (= from head)
            (throw (ex-info (str "the workspace is at this KIT's commit already (" (subs head 0 7) "): nothing to bring up") {:upgrade/error :current :nothing true})))
        _ (when-not (zero? (:exit (git kit "merge-base" "--is-ancestor" from head)))
            (throw (ex-info (str "the workspace's recorded KIT commit " (subs from 0 7) " is not in this clone's history: another KIT, or a commit not fetched") {:upgrade/error :unknown-commit})))
        id (upgrade-id from head version)
        dir (fs/path (:workspace/work ws) "upgrades" id)
        before (fs/path dir "before")
        done-file (fs/path dir "done.edn")
        at (str (or now (java.time.LocalDate/now)))
        _ (fs/create-dirs before)
        facts {:id id :from from :to head :head head :version version :at at}]
    (println (str "bb upgrade --after-pull: " (:workspace/dir ws) " from " (subs from 0 7) " to " (subs head 0 7)
                  (when version (str " (" version ")")) " - " dir))
    (loop [[s & more] steps records (read-done done-file)]
      (if-not s
        (do (spit (str (fs/path dir "report.md")) (report-text facts records))
            (say "done - the report: " (fs/path dir "report.md"))
            {:ok? true :records records :dir (str dir)})
        (if (= :done (get-in records [s :status]))
          (do (say (name s) ": done before - " (get-in records [s :detail]))
              (recur more records))
          (let [r (try ((step-fns s) (merge opts facts {:ws ws :kit kit :dir dir :before before :records records}))
                       ;; ANY failure is a stopped step, recorded and said - never a stack trace over a half-done upgrade
                       (catch Exception e
                         {:status :stopped :detail (str (when-not (instance? clojure.lang.ExceptionInfo e) (str (.getSimpleName (class e)) ": ")) (ex-message e))}))
                records (assoc records s r)]
            (spit (str done-file) (with-out-str (pp/pprint records)))
            (say (name s) ": " (name (:status r)) (when (:detail r) (str " - " (:detail r))))
            (if (= :stopped (:status r))
              (do (spit (str (fs/path dir "report.md")) (report-text facts records))
                  (say "STOPPED at " (name s) " - fix it, then `bb upgrade --after-pull` again: it picks up here")
                  {:ok? false :records records :dir (str dir) :stopped s})
              (recur more records))))))))

(defn dry-run!
  "What `after-pull!` would do, from the same facts; nothing written."
  [ws kit]
  (let [raw (raw-workspace ws)
        from (:workspace/kit-commit raw)
        head (git! kit "rev-parse" "HEAD")
        version (let [{:keys [exit out]} (git kit "describe" "--tags" "--abbrev=0")] (when (zero? exit) out))]
    (if-not from
      (say "workspace.edn records no :workspace/kit-commit: the upgrade would refuse")
      (let [files (parse-diff (:out (git kit "diff" from head "--" "build-docs/")))
            then-pins (some-> (:out (git kit "show" (str from ":harness/resources/" template/resource-name))) java.io.StringReader. template/load-pins)
            moved (when then-pins (upgrade/pin-moved then-pins (template/load-pins)))]
        (say "from " (subs from 0 7) " to " (subs head 0 7) (when version (str " (" version ")")) "; the record would be work/upgrades/" (upgrade-id from head version))
        (say "workspace-keys: " (if (some #(contains? raw %) (keys upgrade/renamed-keys)) "renamed keys to migrate" "nothing to migrate"))
        (say "front-door: " (if (:changed? (front-door/sync! kit (:workspace/dir ws) :check? true)) "would be written" "current"))
        (say "skills: " (let [rs (skills/sync! kit (:workspace/dir ws) :check? true)] (str (count (filter :changed? rs)) " to write, " (count (skills/stale kit (:workspace/dir ws))) " stale to remove")))
        (say "orientation: CLAUDE.md is " (let [f (fs/path (:workspace/dir ws) "CLAUDE.md")] (if (fs/exists? f) (name (init/marker-state (slurp (str f)))) "missing")))
        (say "build-docs: " (count files) " file" (when (not= 1 (count files)) "s") " changed: " (str/join ", " (map #(subs (:path %) (count "build-docs/")) files)))
        (say "loop-edn: " (if (second (rewrite-kit-path (slurp (str (fs/path (:workspace/build ws) "loop.edn"))))) "a literal KIT path to rewrite" "nothing to rewrite"))
        (say "app: " (if moved (str "the template pin moved " (:git/tag (:from moved)) " -> " (:git/tag (:to moved)) ": template-upgrade, the mirror on its branch") "the pin as it was: a branch for the mirror if it changed"))
        (println "bb upgrade --dry-run: nothing was written.")))))

(defn -main
  "bb upgrade --after-pull [--dry-run] [--workspace <dir>]

  Phase 2 of a KIT upgrade: the workspace brought up to the KIT this runs from. Phase 1 - the
  check and the move of `KIT/` - comes with the next release; until then the first upgrade is
  by hand: fetch, `git -C KIT checkout <tag>`, then `bb --config KIT/harness/bb.edn upgrade
  --after-pull` from the workspace folder."
  [& args]
  (let [ws (workspace/current-or-exit args)
        flags (set (filter #(str/starts-with? % "--") args))
        kit (kit-dir)]
    (when-not ws
      (println "upgrade: no workspace here - run it from the workspace folder, or with --workspace <dir>")
      (System/exit 1))
    (cond
      (not (flags "--after-pull"))
      (do (println (str "upgrade: this KIT runs phase 2 only, `bb upgrade --after-pull` - the check and the move of KIT/ are"
                        " by hand until the next release: `git -C " (fs/relativize (:workspace/dir ws) (:workspace/kit ws))
                        " fetch --tags && git -C " (fs/relativize (:workspace/dir ws) (:workspace/kit ws)) " checkout <tag>`, then this with --after-pull"))
          (System/exit 2))

      (flags "--dry-run") (dry-run! ws kit)

      :else
      (try
        (let [{:keys [ok?]} (after-pull! ws kit {})]
          (System/exit (if ok? 0 1)))
        (catch clojure.lang.ExceptionInfo e
          (println (str "upgrade: " (ex-message e)))
          (System/exit (if (:nothing (ex-data e)) 0 1)))))))
