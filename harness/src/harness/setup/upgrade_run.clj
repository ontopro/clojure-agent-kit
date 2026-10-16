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
   [babashka.http-client :as http]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.set :as set]
   [clojure.string :as str]
   [harness.setup.doctor :as doctor]
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

(def release-re
  "A release tag: `N.N.N`, the shape the KIT tags a plan's boundary with. Throwaway and
  candidate tags (`0.6.5-r0`, `kit-v1.3`, `pre-move-2026-09-21`) are not releases."
  #"\d+\.\d+\.\d+")

(defn release-shaped? [tag] (boolean (re-matches release-re (str tag))))

(defn version-key
  "A release tag as a vector of its numbers, for ordering."
  [tag]
  (mapv parse-long (str/split (str tag) #"\.")))

(defn clone-version
  "The clone's own version: the highest release tag among `merged-tags`, the tags reachable from
  HEAD (`git tag --merged HEAD`). A clone on a release tag reads as it; a clone past one - a
  throwaway tag, a candidate's commit - reads as the release before; nil with none. BOTH PHASES
  READ IT SO: `git describe` would name any tag, and a throwaway named the release in a record."
  [merged-tags]
  (->> merged-tags (filter release-shaped?) (sort-by version-key) last))

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
  ;; blank lines say nothing either way: an instruction paragraph goes with its blank line
  (let [removed (remove str/blank? (map #(subs % 1) (filter #(str/starts-with? % "-") lines)))
        added (remove str/blank? (map #(subs % 1) (filter #(str/starts-with? % "+") lines)))
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
        ;; EVERY TARGET CHECKED BEFORE ANYTHING IS WRITTEN: a refusal after some documents were
        ;; patched would leave the upgrade's own edits uncommitted, and the re-run refusing them
        _ (doseq [{:keys [path]} files
                  :let [rel (subs path (count "build-docs/"))]]
            (when-not (str/blank? (:out (git build "status" "--porcelain" "--" (str "docs/" rel))))
              (throw (ex-info (str "docs/" rel " has uncommitted edits in " build ": commit or set them aside, then `bb upgrade --after-pull` again - nothing was written")
                              {:upgrade/error :uncommitted :path (str (fs/path build "docs" rel))}))))
        results (for [{:keys [path new? deleted? hunks]} files
                      :let [rel (subs path (count "build-docs/"))
                            target (fs/path build "docs" rel)]]
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
                      (assoc r :rel rel :outcome :patched :touched (pos? (:applied r))))))
        results (vec results)
        touched (map #(str "docs/" (:rel %)) (filter :touched results))
        conflicts (vec (mapcat :conflicts results))]
    (when (seq touched)
      (apply git! build "add" "--" touched)
      ;; ONLY THESE PATHS: a commit without a pathspec would sweep up whatever the person had staged
      (apply git! build "commit" "-q" "-m" (str "KIT " (or version (subs head 0 7)) ": the build documents the release changed, from "
                                                (subs from 0 7) " to " (subs head 0 7))
             "--" touched))
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
        (cond
          (not changed?) {:status :done :detail "names no literal KIT path"}

          (not (str/blank? (:out (git build "status" "--porcelain" "--" "loop.edn"))))
          (throw (ex-info (str "loop.edn has uncommitted edits in " build ": commit or set them aside, then `bb upgrade --after-pull` again")
                          {:upgrade/error :uncommitted :path (str f)}))

          :else
          (do (spit (str f) out)
              (git! build "add" "--" "loop.edn")
              (git! build "commit" "-q" "-m" (str "KIT " (or version (subs head 0 7)) ": loop.edn names the KIT by {{kit}}, so the workspace can be copied or moved")
                    "--" "loop.edn")
              {:status :done :detail "the literal KIT path rewritten to {{kit}}, committed in the build"}))))))

(defn- step-app! [{:keys [ws release generate gates]}]
  (let [id (str "upgrade-" release)
        ;; THE HOOK BY NAME, recorded in the run: `bb template-upgrade` resuming this run after a
        ;; conflict re-renders the mirror too (`tu/rule-mirror-hook`)
        r (tu/upgrade! ws (cond-> {:pin (template/pin (template/load-pins))
                                   :local-root (some-> (System/getenv "KIT_TEMPLATE_LOCAL") not-empty fs/absolutize fs/normalize str)
                                   :gates (or gates (tu/project-gates ws))
                                   :id id
                                   :release release
                                   :branch-anyway? true
                                   :before-gates :rule-mirror}
                            generate (assoc :generate generate)))
        run-dir (str (fs/path (:workspace/work ws) "runs" id))]
    {:status :done
     :detail (case (:outcome r)
               :awaiting-merge (str "the branch " id " is gated green and awaits its merge: " (tu/merge-command run-dir))
               :conflict (str "the template's merge stopped on " (str/join ", " (:conflicts r)) " - the Architect's, in the worktree; `bb template-upgrade` after")
               :gates-red (str "the gate " (name (:failed r)) " is red on the branch - the Architect's; `bb template-upgrade` after")
               :nothing "the application needs nothing: the template as it was and the mirror current"
               (str (:outcome r)))
     :app (when (not= :nothing (:outcome r))
            {:branch id :run-dir run-dir :state (:outcome r)})}))

(defn- step-workspace-edn! [{:keys [ws before from head version release at records]}]
  (backup! before (:workspace/dir ws) workspace/file-name)
  (let [raw (raw-workspace ws)
        ;; the record names what was taken - a release, or a commit past one - while the
        ;; workspace's version stays the nearest release tag
        record {:from from :to head :version release :at at
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
        version (clone-version (str/split-lines (:out (git kit "tag" "--merged" "HEAD"))))
        _ (when (= from head)
            (throw (ex-info (str "the workspace is at this KIT's commit already (" (subs head 0 7) "): nothing to bring up") {:upgrade/error :current :nothing true})))
        _ (when-not (zero? (:exit (git kit "merge-base" "--is-ancestor" from head)))
            (throw (ex-info (str "the workspace's recorded KIT commit " (subs from 0 7) " is not in this clone's history: another KIT, or a commit not fetched") {:upgrade/error :unknown-commit})))
        ;; THE RELEASE THIS UPGRADE IS NAMED BY: the clone's version when it is newer than the one the
        ;; workspace records; else - a clone past its release on a throwaway tag or a candidate's commit, the
        ;; version the same - the commit, so the branch and the record are not named after a release
        ;; the workspace already had
        release (if (and version (not= version (:workspace/kit-version raw))) version (subs head 0 7))
        id (upgrade-id from head release)
        dir (fs/path (:workspace/work ws) "upgrades" id)
        before (fs/path dir "before")
        done-file (fs/path dir "done.edn")
        at (str (or now (java.time.LocalDate/now)))
        _ (fs/create-dirs before)
        facts {:id id :from from :to head :head head :version version :release release :at at}]
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
          ;; THE WORKSPACE READ AGAIN FOR EVERY STEP: the first step migrates its renamed keys, and a
          ;; step reading a copy from before it would see the old key as nil
          (let [ws (or (workspace/find-workspace (:workspace/dir ws)) ws)
                r (try ((step-fns s) (merge opts facts {:ws ws :kit kit :dir dir :before before :records records}))
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
        version (clone-version (str/split-lines (:out (git kit "tag" "--merged" "HEAD"))))]
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

;; ---------------------------------------------------------------------------
;; phase 1: the check and the move - the OLD version's code, touching nothing outside KIT/
;; ---------------------------------------------------------------------------

(defn newer-releases
  "The release tags among `all-tags` newer than `clone-version`, oldest first."
  [all-tags clone-version]
  (let [here (some-> clone-version version-key)]
    (->> all-tags
         (filter release-shaped?)
         (filter #(or (nil? here) (pos? (compare (version-key %) here))))
         (sort-by version-key)
         vec)))

(defn choose-target
  "Which release the upgrade takes: the latest of `newer`, or `to` when named - as `{:tag t}`, or
  `{:error message}`: nothing newer (nothing changed), `to` the clone's own or older (a
  downgrade: phase 2 would run on code that does not know the newer workspace), `to` a tag the
  remote has not got."
  [newer clone-version all-tags to]
  (cond
    (nil? to) (if (seq newer)
                {:tag (last newer)}
                {:error (str "the clone is at the latest release" (when clone-version (str ", " clone-version)) ": nothing changed") :nothing true})
    (= to clone-version) {:error (str "the clone is at " to " already: nothing changed") :nothing true}
    (some #{to} newer) {:tag to}
    (not (release-shaped? to)) {:error (str to " is not a release tag (N.N.N); the releases newer than " (or clone-version "this clone") ": " (if (seq newer) (str/join ", " newer) "none"))}
    (some #{to} all-tags) {:error (str to " is older than the clone's " clone-version ": a downgrade, refused - phase 2 would run on code that does not know the newer workspace")}
    :else {:error (str to " is not a tag the remote has: the releases newer than " (or clone-version "this clone") ": " (if (seq newer) (str/join ", " newer) "none"))}))

(def record-paths
  "The KIT's own record files: a local change there is the KIT's evidence written where it should
  not be (`bb health --record` in a clone, before 0.6.5), discarded rather than kept."
  #{"harness/resources/known-good.edn"})

(defn local-changes
  "`git status --porcelain` in the clone, sorted into `{:records [paths] :other [paths]}`."
  [porcelain]
  ;; by pattern, not by column: the first line may have lost its leading space to a trim
  (let [entries (keep #(when-let [[_ status path] (re-matches #"\s*([A-Z?!]{1,2})\s+(.*)" %)] [status path])
                      (str/split-lines (str porcelain)))
        paths (map second entries)
        record? #(or (contains? record-paths %) (str/starts-with? % "harness/health/records/"))]
    {:records (vec (filter record? paths)) :other (vec (remove record? paths))
     ;; a record for a platform not yet in the repository is untracked: `checkout --` cannot discard it
     :untracked (set (for [[status path] entries :when (= "??" status)] path))}))

(defn discard-command
  "How the record files in `records` are discarded in the clone `kit-rel`: tracked ones by
  `checkout --`, untracked ones (a new platform's record) by `clean -f --`."
  [kit-rel records untracked]
  (let [[new old] ((juxt filter remove) untracked records)]
    (str/join "; " (cond-> []
                     (seq old) (conj (str "`git -C " kit-rel " checkout -- " (str/join " " old) "`"))
                     (seq new) (conj (str "`git -C " kit-rel " clean -f -- " (str/join " " new) "`"))))))

(def known-row-keys
  "What this version's probe reads of a toolchain row; a newer release's row with a key or a `:via`
  outside these is said as uncheckable here, never passed or failed by code that does not know it."
  #{:tool :via :cmd :req :tier :min :major :does :needed-for :install})

(def known-vias #{:flag :bbin :which})

(defn checkable?
  "Can this version probe `spec`, a row of a (possibly newer) toolchain?"
  [spec]
  (and (map? spec) (contains? known-vias (:via spec)) (every? known-row-keys (keys spec))))

(defn requirement-check
  "A release's `specs` (its `toolchain.edn`) against this machine, probed by `probe` (`doctor/report`
  over the checkable rows): `{:ok? :missing [{:tool :status :install}] :unchecked [tools]}` -
  `:ok?` when every required, checkable row is usable; an unchecked required row is said, and
  does not fail the check, since nothing here can decide it."
  [specs results]
  (let [[checkable unchecked] ((juxt filter remove) checkable? specs)
        status-of (into {} (map (juxt :tool :status) results))
        missing (vec (for [{:keys [tool req install]} checkable
                           :let [status (get status-of tool :unprobed)]
                           :when (and (= :required req) (not= :ok status))]
                       {:tool tool :status status :install install}))]
    {:ok? (empty? missing) :missing missing :unchecked (mapv :tool unchecked)}))

(defn rows-to-probe
  "One row per tool across the newer releases' tables, the newest table's row winning: the
  machine is probed ONCE (`doctor/report` shells `bbin ls`), and each release is checked
  against that result."
  [tables]
  (vec (vals (reduce (fn [m spec] (if (checkable? spec) (assoc m (:tool spec) spec) m)) {} (apply concat tables)))))

(defn significant-changes
  "What a release changes that a person wants said before taking it: the JDK's major, a new
  required tool, the template pin moved - from the two toolchains and the two pins files."
  [old-specs new-specs old-pins new-pins]
  (let [major #(some (fn [s] (when (= :java (:tool s)) (:major s))) %)
        required #(set (map :tool (filter (fn [s] (= :required (:req s))) %)))
        moved (when (and old-pins new-pins) (upgrade/pin-moved old-pins new-pins))]
    (cond-> []
      (and (major old-specs) (major new-specs) (not= (major old-specs) (major new-specs)))
      (conj (str "the JDK major changes: " (major old-specs) " -> " (major new-specs)))

      (seq (set/difference (required new-specs) (required old-specs)))
      (conj (str "newly required: " (str/join ", " (map name (sort (set/difference (required new-specs) (required old-specs)))))))

      moved
      (conj (str "the application template's pin moves: " (:git/tag (:from moved)) " (" (subs (:git/sha (:from moved)) 0 7) ") -> " (:git/tag (:to moved)) " (" (subs (:git/sha (:to moved)) 0 7) ")"
                 " (bb upgrade's phase 2 brings the application up on a branch)")))))

(defn hand-over-command
  "The new process phase 2 runs in, from the workspace folder: the KIT just checked out, by its
  own `bb.edn`."
  [kit-rel]
  ["bb" "--config" (str kit-rel "/harness/bb.edn") "upgrade" "--after-pull"])

(defn- remote-host
  "The host a git remote URL names, or nil for a local path."
  [url]
  (or (second (re-matches #"(?:https?|ssh|git)://([^/]+)/.*" (str url)))
      (second (re-matches #"[^@]+@([^:]+):.*" (str url)))))

(defn- reachable?* [host]
  (try (some? (http/head (str "https://" host "/") {:timeout 5000 :throw false}))
       (catch Exception _ false)))

(defn- show-at [kit tag path]
  (let [{:keys [exit out]} (git kit "show" (str tag ":" path))]
    (when (zero? exit) out)))

(defn phase-one!
  "The check and the move. Opts, for tests: `:reachable?` (host -> bool), `:probe` (specs ->
  results), `:hand-over!` (argv dir -> exit), `:to`. Returns `{:outcome kw ...}`; a refusal
  throws `ex-info` with `:upgrade/error` and the words."
  [ws kit {:keys [reachable? probe hand-over! to] :or {reachable? reachable?* probe doctor/report}}]
  (let [ws-dir (:workspace/dir ws)
        kit-rel (str (fs/relativize ws-dir kit))
        raw (raw-workspace ws)
        head (git! kit "rev-parse" "HEAD")
        merged (str/split-lines (:out (git kit "tag" "--merged" "HEAD")))
        here (clone-version merged)
        recorded-version (:workspace/kit-version raw)
        _ (when-not (:workspace/kit-commit raw)
            (throw (ex-info "workspace.edn records no :workspace/kit-commit: nothing says which KIT this workspace is at, so nothing can say what changed since - add the line `:workspace/kit-commit \"<the KIT commit this workspace was made at>\"`, then `bb upgrade` again"
                            {:upgrade/error :no-commit})))]
    ;; ALREADY MOVED: KIT/ is past what the workspace records - a run stopped after the move, or a
    ;; move by hand - so phase 2 is what is left, by this very code (it is the new version's)
    ;; DECIDED BY COMMIT, not by version: a `git pull` out of habit, or a later commit of the same
    ;; release, is a move phase 2 has not followed, and "at the latest" would hide it
    (when (and (not= head (:workspace/kit-commit raw))
               (not (zero? (:exit (git kit "merge-base" "--is-ancestor" (str (:workspace/kit-commit raw)) head)))))
      (throw (ex-info (str kit-rel " is at " (subs head 0 7) ", which does not descend from the commit the workspace records ("
                           (subs (str (:workspace/kit-commit raw)) 0 7) "): not an upgrade path - check out that commit, or a later release, then `bb upgrade` again")
                      {:upgrade/error :diverged :head head :recorded (:workspace/kit-commit raw)})))
    (if (not= head (:workspace/kit-commit raw))
      (do (println (str "bb upgrade: " kit-rel " is at " (subs head 0 7) (when here (str " (" here ")")) " already, past the commit the workspace records"
                        (when recorded-version (str " (" recorded-version ")")) ": the move is done, phase 2 picks up at its first step not done"))
          {:outcome :after-pull :result (after-pull! ws kit {})})
      (let [;; THE NETWORK FIRST, before anything moves
            origin (:out (git kit "remote" "get-url" "origin"))
            hosts (remove nil? (distinct [(remote-host origin) "repo.clojars.org" "repo1.maven.org"]))
            down (remove reachable? hosts)
            _ (when (seq down)
                (throw (ex-info (str "cannot reach " (str/join ", " down) ": the upgrade needs the network (the KIT's remote, Clojars and Maven Central"
                                     " for the application's dependencies); nothing changed")
                                {:upgrade/error :network :hosts (vec down)})))
            ;; KIT/ CLEAN: a convention nothing enforced; the KIT's own record files are named as such
            {:keys [records other untracked]} (local-changes (:out (git kit "status" "--porcelain")))
            _ (when (seq other)
                (throw (ex-info (str kit-rel " has local changes, which an upgrade would move under: " (str/join ", " other)
                                     " - the clone is the KIT's and a project writes nothing into it; set them aside, then `bb upgrade` again")
                                {:upgrade/error :dirty :files other})))
            _ (when (seq records)
                (throw (ex-info (str kit-rel " has local changes to the KIT's own record files: " (str/join ", " records)
                                     " - records are the KIT's own, written by `bb health --record` in a clone before 0.6.5; discard them: "
                                     (discard-command kit-rel records untracked) ", then `bb upgrade` again")
                                {:upgrade/error :dirty-records :files records})))
            ;; THE TAGS FETCHED, which moves nothing
            _ (println (str "bb upgrade: fetching tags from " origin))
            ;; not --quiet: a fetch that fails must fail with git's words (a tag the remote moved, a host gone)
            _ (git! kit "fetch" "--tags")
            all-tags (str/split-lines (:out (git kit "tag")))
            newer (newer-releases all-tags here)
            {:keys [tag error nothing]} (choose-target newer here all-tags to)
            _ (when error (throw (ex-info error {:upgrade/error (if nothing :nothing :target) :nothing nothing})))
            ;; EACH NEWER RELEASE, its requirements read from its tag (never by running it), checked here
            own-specs doctor/toolchain
            own-pins (template/load-pins)
            tables (into {} (for [t newer :let [text (show-at kit t "harness/resources/toolchain.edn")]]
                              [t (when text (doctor/read-toolchain text))]))
            ;; THE MACHINE PROBED ONCE, over every row the newer tables carry that this version can probe
            results (let [rows (rows-to-probe (remove nil? (vals tables)))] (if (seq rows) (probe rows) []))
            checks (for [t newer
                         :let [specs (get tables t)
                               check (if specs (requirement-check specs results) {:ok? true :missing [] :unchecked [] :no-table true})
                               pins (some-> (show-at kit t "harness/resources/template-pins.edn") java.io.StringReader. template/load-pins)
                               changes (significant-changes own-specs (or specs own-specs) own-pins pins)]]
                     (assoc check :tag t :changes changes))
            _ (println (str "  this clone: " (or here "no release tag") "; newer: " (str/join ", " newer)))
            _ (doseq [{:keys [tag ok? missing unchecked no-table changes]} checks]
                (println (str "  " tag ": " (cond no-table "no toolchain.edn at this tag - its requirements cannot be checked here"
                                                  ok? "requirements met here"
                                                  :else (str "NOT met here: " (str/join ", " (map #(str (name (:tool %)) " " (name (:status %))) missing))))
                              (when (seq unchecked) (str "; cannot check here: " (str/join ", " (map name unchecked))))
                              (when (seq changes) (str "; " (str/join "; " changes))))))
            target (first (filter #(= tag (:tag %)) checks))
            _ (when-not (:ok? target)
                (let [takeable (last (filter :ok? checks))]
                  (throw (ex-info (str tag " cannot be taken on this machine: "
                                       (str/join "; " (for [{:keys [tool status install]} (:missing target)]
                                                        (str (name tool) " is " (name status) (when install (str " - " install)))))
                                       ", then `bb upgrade` again"
                                       (if takeable (str ". The newest release this machine can take: " (:tag takeable) " (`bb upgrade --to " (:tag takeable) "`)")
                                           ". No newer release can be taken here as it is"))
                                  {:upgrade/error :requirements :tag tag :missing (:missing target)}))))
            ;; THE TARGET DESCENDS FROM THE CLONE, or it is not an upgrade path: a tag cut on another line - a
            ;; hotfix branch, a re-cut throwaway - would move KIT/ to a commit phase 2 then refuses, with the clone
            ;; stranded there; refused here, before anything moves
            _ (when-not (zero? (:exit (git kit "merge-base" "--is-ancestor" "HEAD" tag)))
                (throw (ex-info (str tag " does not descend from this clone's commit " (subs head 0 7) ": a tag on another line, not an upgrade path;"
                                     " nothing changed (`git -C " kit-rel " log --oneline " tag ".." (subs head 0 7) "` lists what this clone has that the tag lacks)")
                                {:upgrade/error :diverged-target :tag tag :head head})))
            _ (when (and (nil? to) (> (count newer) 1))
                (println (str "  taking the latest, " tag "; name another with `bb upgrade --to <tag>` if you do not want the latest")))
            ;; THE MOVE, and nothing else in this process
            _ (git! kit "checkout" "--quiet" "--detach" tag)
            _ (println (str "  " kit-rel " moved to " tag " (detached, never a branch tip)"))
            cmd (hand-over-command kit-rel)
            _ (println (str "  phase 2, in a new process on " tag "'s code: " (str/join " " cmd)))
            exit (if hand-over!
                   (hand-over! cmd ws-dir)
                   (:exit (apply p/shell {:dir ws-dir :continue true} cmd)))]
        (println (str "\nAfter it: `bb self-check` in " kit-rel " proves the machine against " tag " - the version check is not the machine proved."))
        {:outcome :moved :tag tag :exit exit :checks (vec checks)}))))

(defn -main
  "bb upgrade [--to <tag>] [--dry-run] [--workspace <dir>]   the whole upgrade, phase 1 then 2
   bb upgrade --after-pull [--dry-run]                      phase 2 alone, on the KIT just checked out

  Phase 1, the check and the move: the network reached, `KIT/` clean, the tags fetched, the
  clone's version read, every newer release's requirements read from its tag and checked here,
  the clone moved to the latest (or `--to`), then phase 2 in a new process on the new version's
  code. A re-run after the move goes straight to phase 2's first step not done. The first time
  is by hand, since the version before this had no `bb upgrade`: fetch, `git -C KIT checkout
  <tag>`, then `bb --config KIT/harness/bb.edn upgrade --after-pull` from the workspace folder."
  [& args]
  (let [ws (workspace/current-or-exit args)
        {:keys [args]} (workspace/split-args args)
        flags (set (filter #(str/starts-with? % "--") args))
        to (second (drop-while #(not= "--to" %) args))
        kit (kit-dir)]
    (when-not ws
      (println "upgrade: no workspace here - run it from the workspace folder, or with --workspace <dir>")
      (System/exit 1))
    (when (and (flags "--to") (nil? to))
      (println "upgrade: --to needs a tag after it")
      (System/exit 2))
    (try
      (cond
        (and (flags "--after-pull") (flags "--dry-run")) (dry-run! ws kit)
        (flags "--after-pull") (let [{:keys [ok?]} (after-pull! ws kit {})] (System/exit (if ok? 0 1)))
        (flags "--dry-run")
        (let [merged (str/split-lines (:out (git kit "tag" "--merged" "HEAD")))
              here (clone-version merged)
              newer (newer-releases (str/split-lines (:out (git kit "tag"))) here)]
          (println (str "bb upgrade --dry-run: this clone is at " (or here "no release tag") "; newer releases known locally (no fetch): "
                        (if (seq newer) (str/join ", " newer) "none")
                        "\n  it would: check the network, require KIT/ clean, fetch the tags, check each newer release's requirements from its tag,"
                        " move KIT/ to " (or to "the latest") " (detached), then run phase 2 in a new process. Nothing was written.")))
        :else (let [{:keys [outcome exit result]} (phase-one! ws kit {:to to})]
                (System/exit (case outcome :moved (or exit 1) :after-pull (if (:ok? result) 0 1) 1))))
      (catch clojure.lang.ExceptionInfo e
        (println (str "upgrade: " (ex-message e)))
        (System/exit (if (:nothing (ex-data e)) 0 1))))))
