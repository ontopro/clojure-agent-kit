(ns harness.setup.template-upgrade
  "`bb template-upgrade`: the application brought to the template the KIT now pins, by a
  `template` branch in the application and git's own three-way merge.

  THE OLD SIDE IS A BRANCH, NOT A COMMIT ON MAIN. `bb init`'s first commit is the generated
  tree untouched, and a diff against it is everything the project did - but only until the
  first template upgrade: after it, the tree the previous release generated is held by no
  commit on `main` by itself. So `template` is rooted at that first commit and carries one
  commit per release taken - the whole tree the new pin generates with the project's name,
  committed as is, the tag in the message - and merging `template` into a branch off `main`
  is git's three-way merge with the right base every time: the project's changes on one side,
  the template's on the other, and a later release needs nothing more. Which pin is \"old\" is
  what `template`'s tip holds (`generated-from`); no key in `workspace.edn` says it.

  A MERGE THAT STOPS ON A CONFLICT CANNOT BE COMMITTED, so its states are written down. The
  run has a directory under `work/runs/<id>/` with the loop's own `state.edn` and `events.log`,
  and a worktree under `work/worktrees/<id>/`: a conflict names the files there and stops for
  the Architect, who resolves and commits in the worktree; run again, the project's gates run
  on the branch, and the run stops `:awaiting-merge` - what `bb run-loop merge` accepts, so the
  merge into `main` is the loop's own, behind a person's `{:decision ...}`, with no second
  merge command. The first project built on the KIT will meet exactly this: its own change to
  the server's asset handling against the template's.

  Pure where it can be - what a commit's subject says it came from, the run's id, the states
  a worktree can be in - and the edge runs git in the application and generation beside it."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.gates.boundary :as boundary]
   [harness.gates.run :as gates]
   [harness.loop.driver :as driver]
   [harness.rules :as rules]
   [harness.setup.app :as app]
   [harness.setup.template :as template]
   [harness.setup.workspace :as workspace]))

(def branch "template")

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn generated-from
  "What a commit `subject` of an untouched generated tree says it came from -
  `{:template s :tag t :sha7 x}` for the pin, `{:template s :local-root path}` for a local
  clone - or nil for any other subject."
  [subject]
  (when (and subject (str/starts-with? subject app/untouched-subject))
    (let [rest (subs subject (count app/untouched-subject))]
      (if-let [[_ tmpl root] (re-matches #"(\S+) from the local clone (.+) - NOT the pinned commit" rest)]
        {:template tmpl :local-root root}
        (when-let [[_ tmpl tag sha7] (re-matches #"(\S+) (\S+) \(([0-9a-f]{7})\)" rest)]
          {:template tmpl :tag tag :sha7 sha7})))))

(defn same-source?
  "Is `from` (`generated-from` of `template`'s tip) the pin `p` as it stands? A local clone is
  never the same by name - its tree decides, once generated."
  [from p local-root]
  (boolean (and (nil? local-root) from
                (= (:tag from) (:git/tag p))
                (= (:sha7 from) (subs (:git/sha p) 0 7)))))

(defn run-id
  "The run's id, from what is taken: `template-upgrade-<tag>`, or `-local` for a candidate."
  [p local-root]
  (str "template-upgrade-" (if local-root "local" (:git/tag p))))

(defn merged?
  "Does `state` record a merge - the run is over and its id is taken?"
  [state]
  (boolean (some #(= :merged (:event/kind %)) (:events state))))

(defn fresh-id
  "`base` when no run of that id is recorded as merged under `runs-dir`, else `base-2`,
  `base-3`...: a second upgrade to the same candidate, after the first merged, is a new run."
  [runs-dir base]
  (let [taken? (fn [id]
                 (let [f (fs/path runs-dir id "state.edn")]
                   (and (fs/exists? f) (merged? (edn/read-string {:default tagged-literal} (slurp (str f)))))))]
    (loop [n 1]
      (let [id (if (= 1 n) base (str base "-" n))]
        (if (taken? id) (recur (inc n)) id)))))

(defn open-run-id
  "The id of an open template upgrade under `runs-dir` - a run of `:run/kind :template-upgrade`
  not yet merged, the latest started when there are several - or nil. `bb template-upgrade`
  resumes it whatever its id: an upgrade's run (`upgrade-<version>`, `bb upgrade`'s) stopped on a
  conflict is the run the Architect resolved, and a second run on the same release would orphan
  that resolution."
  [runs-dir]
  (when (fs/directory? runs-dir)
    (->> (fs/list-dir runs-dir)
         (keep (fn [d]
                 (let [f (fs/path d "state.edn")]
                   (when (fs/exists? f)
                     (let [s (edn/read-string {:default tagged-literal} (slurp (str f)))]
                       (when (and (= :template-upgrade (:run/kind s)) (not (merged? s)))
                         [(:started-ms s 0) (str (fs/file-name d))]))))))
         (sort-by first)
         last
         second)))

(defn last-stop
  "The last `:stopped` event's status, or nil."
  [state]
  (some-> (last (filter #(= :stopped (:event/kind %)) (:events state))) :run/status))

(defn worktree-state
  "What the upgrade's worktree is in, from git's answers about it: `:merging` while a merge
  stands unresolved (`merge-head?` or `conflicts`), `:uncommitted` with changes not committed,
  else `:committed`."
  [{:keys [merge-head? conflicts porcelain]}]
  (cond (seq conflicts) :merging
        ;; every conflict staged and the merge not yet committed: resolved, one commit short
        merge-head? :resolved
        (not (str/blank? porcelain)) :uncommitted
        :else :committed))

(defn resolve-kit
  "`gate-seq` with `{{kit}}` made `kit-dir`, as the driver does for a run (`resolve-config`)."
  [gate-seq kit-dir]
  (mapv (fn [[k cmd]] [k (str/replace (str cmd) "{{kit}}" (str kit-dir))]) gate-seq))

(defn merge-command
  "The command that makes the upgrade permanent, for the person."
  [run-dir]
  (str "bb run-loop merge " run-dir " <decision.edn>   ; {:decision \"why this merges\"}, and a decision-log entry"))

(defn app-name-of
  "The application's name - its root namespace - read off `layers.edn`'s text, which `bb init`
  wrote as the pin's tails under that name: generation needs the name, and the folder may be
  called anything (`bb init --app`). Nil when the file has no namespaced key."
  [layers-text]
  (let [m (try (edn/read-string layers-text) (catch Exception _ nil))]
    (some->> (when (map? m) (keys m))
             (map str)
             (keep #(second (re-matches #"([^.]+)\..+" %)))
             first)))

(defn- app-name
  "`app-name-of` the application at `app`, else the folder's name without `-app`."
  [app]
  (let [f (fs/path app boundary/file-name)]
    (or (when (fs/exists? f) (app-name-of (slurp (str f))))
        (str/replace (fs/file-name app) #"-app$" ""))))

;; ---------------------------------------------------------------------------
;; the edge: git
;; ---------------------------------------------------------------------------

(defn- git
  "git in `dir`, never throwing: `{:exit _ :out \"stdout and stderr, trimmed\"}`."
  [dir env & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true :extra-env (or env {})}
                                      "git" args)]
    {:exit exit :out (str/trim (str out err))}))

(defn- git!
  "git in `dir`, its stdout trimmed; a failure is refused with git's own words."
  [dir env & args]
  (let [{:keys [exit out]} (apply git dir env args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed in " dir ": " out)
                      {:template-upgrade/error :git :args (vec args) :dir (str dir) :out out})))
    out))

(defn- refuse [message data]
  (throw (ex-info message (merge {:template-upgrade/error :refused} data))))

(defn- say [s] (println (str "  " s)))

;; ---------------------------------------------------------------------------
;; the template branch
;; ---------------------------------------------------------------------------

(defn- branch-exists? [app env b]
  (zero? (:exit (git app env "rev-parse" "-q" "--verify" (str "refs/heads/" b)))))

(defn ensure-template-branch!
  "The application's `template` branch: left as it is when it exists; made at the first commit
  when that commit is the untouched generated tree `bb init` commits (`generated-from` reads
  its subject); refused with the words otherwise. Returns the tip's `generated-from`."
  [app env]
  (when-not (branch-exists? app env branch)
    (let [roots (remove str/blank? (str/split-lines (git! app env "rev-list" "--max-parents=0" "HEAD")))
          root (first roots)
          subject (when root (git! app env "log" "-1" "--format=%s" root))]
      (when (not= 1 (count roots))
        (refuse (str "the application's history has " (count roots) " root commits; the `template` branch is rooted at the one"
                     " that is the generated tree untouched, and that is for a person to name: `git -C " app
                     " branch template <commit>`")
                {:roots (vec roots)}))
      (when-not (generated-from subject)
        (refuse (str "the application's first commit, " (subs root 0 7) " \"" subject "\", is not the untouched generated tree"
                     " `bb init` commits (\"" app/untouched-subject "...\"), so the `template` branch cannot be made from it:"
                     " `bb template-upgrade` needs that branch, rooted at the tree the template generated")
                {:root root :subject subject}))
      (git! app env "branch" branch root)
      (say (str "template branch made at the first commit " (subs root 0 7) " - the generated tree, untouched"))))
  (generated-from (git! app env "log" "-1" "--format=%s" branch)))

(defn- tree-into!
  "Make `dir`'s working tree the generated tree at `generated`: everything but `.git` removed,
  the generated tree copied in."
  [dir generated]
  (doseq [f (fs/list-dir dir)
          :when (not= ".git" (fs/file-name f))]
    (if (fs/directory? f) (fs/delete-tree f) (fs/delete f)))
  (doseq [f (fs/list-dir generated)]
    (let [to (fs/path dir (fs/file-name f))]
      ;; with their attributes: a mode the template sets is part of the tree it generates
      (if (fs/directory? f)
        (fs/copy-tree f to {:copy-attributes true})
        (fs/copy f to {:copy-attributes true})))))

(defn- ancestor?
  "Is `a` an ancestor of `b` in `dir` - `git merge-base --is-ancestor`?"
  [dir env a b]
  (zero? (:exit (git dir env "merge-base" "--is-ancestor" a b))))

(defn commit-template!
  "The tree `generated` committed onto `template` as the whole tree, with `label`'s subject,
  through a worktree at `wt` that is removed again. Returns the commit, or nil when the tree
  is the tip's already - nothing to upgrade to."
  [app env wt generated label]
  (fs/create-dirs (fs/parent wt))
  (git! app env "worktree" "add" "-q" (str wt) branch)
  (try
    (tree-into! wt generated)
    (git! wt env "add" "-A")
    (if (zero? (:exit (git wt env "diff" "--cached" "--quiet")))
      nil
      (do (git! wt env "commit" "-q" "-m" (str app/untouched-subject label))
          (git! wt env "rev-parse" "HEAD")))
    (finally
      (git app env "worktree" "remove" "--force" (str wt)))))

;; ---------------------------------------------------------------------------
;; the run: its state is the loop's, so `bb run-loop merge` reads it
;; ---------------------------------------------------------------------------

(defn initial-state
  "The run's `state.edn` as the loop's commands read it: `:config` as `driver/resolve-config`
  would fill it, the upgrade's own facts under `:template` and `:base`, and the gate session -
  the worktree the merge is in, on the upgrade's branch - under `:sessions`, where `merge`,
  `record` and `teardown` look for it."
  [{:keys [id ws now from to label template-commit base-branch base-commit worktree hook]}]
  {:run/kind :template-upgrade
   :started-ms now
   ;; what runs on the branch before its gates, by name, so a resume by any entry point does it
   :hook hook
   :config {:run/id id
            :repo/root (:workspace/app ws)
            :worktrees/dir (str (fs/path (:workspace/work ws) "worktrees" id))
            :kit/root (:workspace/kit ws)
            :plan/root (:workspace/build ws)
            :records/dir (:workspace/records ws)}
   :template {:from from :to to :label label :branch branch :commit template-commit}
   :base {:branch base-branch :commit base-commit}
   :sessions {:gate {:worktree/path (str worktree) :worktree/git-root (str worktree)
                     :worktree/branch id :task/id id :task/role :reviewer}}
   :attempts {}
   :steps []
   :events []})

(defn- worktree-facts [wt env]
  {:merge-head? (zero? (:exit (git wt env "rev-parse" "-q" "--verify" "MERGE_HEAD")))
   :conflicts (vec (remove str/blank? (str/split-lines (:out (git wt env "diff" "--name-only" "--diff-filter=U")))))
   :porcelain (:out (git wt env "status" "--porcelain"))})

(defn- stop! [ctx kind route status reason]
  (driver/event! ctx {:event/kind :stopped :stop/kind kind :route route :reason reason :run/status status}))

(defn rule-mirror-hook
  "The `:before-gates` step `bb upgrade` asks for, by name in the run's state (`:hook`): the
  application's rule mirrors re-rendered in the worktree from the project's rules - never the
  shipped ones, which `rules/sync!` would pick for a path the workspace does not list - and
  committed there when changed, with `release` in the message. Idempotent."
  [ws release]
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
        (apply git! wt nil "add" "--" changed)
        (git! wt nil "commit" "-q" "-m" (str "KIT " release ": the rule mirror re-rendered from the release's rule source"))
        (say (str "the rule mirror re-rendered on the branch: " (str/join ", " changed)))))))

(declare gates-on!)

(defn- gates!
  "The project's gates on the branch, after `:before-gates` - a step the caller adds on the
  branch first (the rule mirror re-rendered, for `bb upgrade`), run on every path to the gates
  and idempotent: it commits only a change. A branch left with nothing past its base is nothing
  to merge, and the run is removed again."
  [ctx wt {:keys [gates before-gates env ws]} run-dir]
  ;; THE HOOK THE RUN RECORDED, when the caller passed none: a resume by `bb template-upgrade`
  ;; of a run `bb upgrade` started still re-renders the mirror before the gates
  (let [state @(:state ctx)
        hook (cond (fn? before-gates) before-gates
                   (= :rule-mirror (get-in state [:hook :kind])) (rule-mirror-hook ws (get-in state [:hook :release])))]
    (when hook (hook (str wt))))
  (let [state @(:state ctx)
        head (git! wt env "rev-parse" "HEAD")]
    (if (and (nil? (get-in state [:template :commit])) (= head (get-in state [:base :commit])))
      (let [app (:repo/root (:config state))
            id (:run/id (:config state))]
        (say "the branch carries nothing past its base: nothing to merge, and no run is kept")
        (git app env "worktree" "remove" "--force" (str wt))
        (git app env "branch" "-D" id)
        (fs/delete-tree run-dir)
        {:outcome :nothing})
      (gates-on! ctx wt gates env run-dir head))))

(defn- gates-on! [ctx wt gate-seq _env run-dir head]
  (say (str "the project's gates, in " wt))
  (let [gr (gates/run-gates! (str wt) gate-seq)]
    (doseq [{:keys [gate status ms]} (:gates/report gr)]
      (driver/step! ctx {:step/name gate :step/kind :gate :step/status status :step/ms ms}))
    (swap! (:state ctx) assoc :last-gates gr)
    ;; THE COMMIT THE GATES RAN ON, so a run resumed after a further commit gates again
    (driver/event! ctx (assoc (driver/gates-event gr) :head head))
    (say (str "gates: " (str/join " " (for [{:keys [gate status]} (:gates/report gr)] (str (name gate) "=" (name status))))))
    (if (:gates/passed? gr)
      (do (stop! ctx :template-merged :human :awaiting-merge
                 (str "the template's release is merged on " (get-in @(:state ctx) [:sessions :gate :worktree/branch])
                      " and the project's gates are green; the merge into " (get-in @(:state ctx) [:base :branch]) " is a person's"))
          (say (str "AWAITING THE MERGE - " (merge-command run-dir)))
          {:outcome :awaiting-merge})
      (let [failed (:gates/failed gr)
            out (some #(when (= failed (:gate %)) (:out %)) (:gates/report gr))]
        (stop! ctx :gates-red :architect :escalated
               (str "the gate " (name failed) " is red on the upgrade branch: fix it in " wt ", commit there, then bb template-upgrade again"))
        (say (str "GATE " (name failed) " RED - fix it in " wt ", commit there, then bb template-upgrade again"))
        (when-not (str/blank? (str out))
          (println (str/join "\n" (map #(str "    " %) (take-last 20 (str/split-lines (str out)))))))
        {:outcome :gates-red :failed failed}))))

(defn- conflict! [ctx wt conflicts run-dir]
  (stop! ctx :merge-conflict :architect :escalated
         (str "the merge of template stopped on " (count conflicts) " conflict" (when (not= 1 (count conflicts)) "s")
              " - " (str/join ", " conflicts) " - in " wt ": resolve and commit there, then bb template-upgrade again"))
  (say (str "CONFLICT in " (count conflicts) " file" (when (not= 1 (count conflicts)) "s") ", the Architect's:"))
  (doseq [f conflicts] (say (str "  " f)))
  (say (str "resolve and commit in " wt ", then `bb template-upgrade` again: the gates run and the run awaits its merge"
            " (its record: " run-dir ")"))
  {:outcome :conflict :conflicts conflicts})

(defn- merge-template!
  "`git merge template` in the upgrade's worktree: clean, the gates; a conflict, the Architect's."
  [ctx wt {:keys [env] :as opts} run-dir]
  (say (str "merging template into " (get-in @(:state ctx) [:sessions :gate :worktree/branch]) " in " wt))
  ;; THE COMMIT THE RUN RECORDED, not the branch by name: a newer release committed onto
  ;; template since would otherwise merge under a state that names the older one
  (let [{:keys [commit label]} (:template @(:state ctx))
        {:keys [exit]} (git wt env "merge" "--no-edit" "-m" (str "Merge template at " (subs commit 0 7) ": " label) commit)]
    (if (zero? exit)
      (do (driver/event! ctx {:event/kind :merge :clean? true :commit (git! wt env "rev-parse" "HEAD")})
          (say "merged clean")
          (gates! ctx wt opts run-dir))
      (let [conflicts (:conflicts (worktree-facts wt env))]
        (driver/event! ctx {:event/kind :merge :clean? false :conflicts conflicts})
        (conflict! ctx wt conflicts run-dir)))))

(defn- resume! [ws ctx {:keys [env] :as opts} run-dir]
  (let [state @(:state ctx)
        wt (get-in state [:sessions :gate :worktree/path])
        id (:run/id (:config state))
        app (:workspace/app ws)]
    (when-not (fs/exists? wt)
      ;; THE BRANCH GONE TOO IS A RUN ABANDONED BY HAND, and the way out is said: a resume that
      ;; threw git's words at every call blocked the command until someone found the run directory
      (when-not (branch-exists? app env id)
        (refuse (str "the run " id " is open but its branch is gone: remove " run-dir " to abandon the run, then"
                     " `bb template-upgrade` again")
                {:run id :run-dir run-dir :branch-gone true}))
      (say (str "the worktree " wt " is gone; checked out again from " id))
      (fs/create-dirs (fs/parent wt))
      (git! app env "worktree" "add" "-q" wt id))
    (let [facts (worktree-facts wt env)]
      (case (worktree-state facts)
        :merging (do (say (str "the merge in " wt " is still unresolved, the Architect's:"))
                     (doseq [f (:conflicts facts)] (say (str "  " f)))
                     (say "resolve and commit there, then `bb template-upgrade` again")
                     {:outcome :conflict :conflicts (:conflicts facts)})
        :resolved (do (say (str "the merge in " wt " is resolved and not committed: `git commit` there, then `bb template-upgrade` again"))
                      {:outcome :uncommitted})
        :uncommitted (do (say (str "the worktree " wt " has uncommitted changes: commit the resolution there first"))
                         (println (str/join "\n" (map #(str "    " %) (str/split-lines (:porcelain facts)))))
                         {:outcome :uncommitted})
        :committed (let [head (git! wt env "rev-parse" "HEAD")
                         gated-at (:head (last (filter #(= :gates (:event/kind %)) (:events state))))]
                     (cond
                       ;; THE BRANCH WITHOUT THE TEMPLATE'S COMMIT - a worktree lost mid-conflict and checked
                       ;; out again from a branch the merge never reached - is merged again, never gated as it is
                       (and (get-in state [:template :commit])
                            (not (ancestor? wt env (get-in state [:template :commit]) head)))
                       (merge-template! ctx wt opts run-dir)

                       (and (= :awaiting-merge (last-stop state)) (= head gated-at))
                       (do (say (str "already green and awaiting the merge - " (merge-command run-dir)))
                           {:outcome :awaiting-merge})

                       :else
                       (do (driver/event! ctx {:event/kind :resolved :commit head})
                           (gates! ctx wt opts run-dir))))))))

(defn upgrade!
  "The upgrade, from the workspace `ws`. Opts: `:pin` (the KIT's); `:local-root`
  (`KIT_TEMPLATE_LOCAL`, a candidate); `:gates`, the project's gate sequence, `{{kit}}` resolved;
  `:generate`, `(fn [target-dir])` that generates the application there (`app/generate!`, or a
  test's stand-in); `:env` for git; `:now`; `:id`, the run's id in place of `run-id`'s;
  `:before-gates`, `(fn [worktree])` run on the branch before its gates on every path to them
  (`bb upgrade` re-renders the rule mirror there); `:branch-anyway?`, a branch and a run even
  when the template is as it was - for what `:before-gates` adds; nothing added, no run.
  Returns `{:outcome kw ...}`; a refusal throws `ex-info` with `:template-upgrade/error`."
  [ws {:keys [pin local-root generate env now id branch-anyway? before-gates release] :or {now (System/currentTimeMillis)} :as opts}]
  (let [app (:workspace/app ws)
        work (:workspace/work ws)
        _ (when-not (fs/directory? (fs/path app ".git"))
            (refuse (str "the workspace's application folder, " app ", is not a git repository") {:app app}))
        app-name (app-name app)
        base-branch (git! app env "rev-parse" "--abbrev-ref" "HEAD")
        _ (when (= "HEAD" base-branch)
            (refuse (str "the application at " app " has no branch checked out (detached HEAD): check out main first") {:app app}))
        from (ensure-template-branch! app env)
        runs-dir (fs/path work "runs")
        ;; AN OPEN RUN IS RESUMED WHATEVER ITS ID: `bb upgrade` names its run `upgrade-<version>`, and
        ;; `bb template-upgrade` after a conflict must find that one, not start a second
        id (or id (open-run-id runs-dir) (fresh-id runs-dir (run-id pin local-root)))
        run-dir (str (fs/path runs-dir id))
        state-file (fs/path run-dir "state.edn")
        opts (-> opts (update :gates #(or % [])) (assoc :env env :ws ws))]
    (if (fs/exists? state-file)
      ;; A RUN NOT MERGED IS RESUMED, whatever template's tip says: it holds this release already
      (let [ctx (driver/context run-dir)
            open-to (get-in @(:state ctx) [:template :to])
            want (if local-root {:local-root local-root} {:tag (:git/tag pin) :sha7 (subs (:git/sha pin) 0 7)})]
        (say (str "run " id " exists - resuming it"))
        ;; AN OPEN RUN IS FINISHED FIRST, and a pin moved since is said: the newer release waits
        (when (not= (select-keys open-to [:tag :sha7 :local-root]) want)
          (say (str "the run is open for " (or (:tag open-to) (:local-root open-to) "an earlier release")
                    "; the pin is now " (or (:git/tag pin) local-root) " - merge or abandon this run, then `bb template-upgrade` again for the newer one")))
        (resume! ws ctx opts run-dir))
      ;; NOTHING TO DO IS A QUESTION ABOUT THE BASE, not about template's tip: is the tip merged into
      ;; the branch? A tip ahead of the base with no run - a run that failed between the template
      ;; commit and its state, a run directory deleted to start over - is the release still to take,
      ;; and it is taken from the tip as it stands, without generating again.
      (let [tip (git! app env "rev-parse" branch)
            tip-merged? (ancestor? app env tip base-branch)
            label (app/generation-label pin local-root)
            to (if local-root {:template (str (:template pin)) :local-root local-root}
                   {:template (str (:template pin)) :tag (:git/tag pin) :sha7 (subs (:git/sha pin) 0 7)})
            nothing! (fn [why] (refuse (str why ": nothing to upgrade to") {:from from :to to :nothing-to-upgrade true}))
            template-commit
            (cond
              (and (same-source? from pin local-root) tip-merged?)
              (when-not branch-anyway?
                (nothing! (str "the application is generated from " (:tag from) " (" (:sha7 from) ") already, which is the pin, and it is merged")))

              (same-source? from pin local-root)
              (do (say (str "template already holds " (subs tip 0 7) ", the pin, not yet merged into " base-branch ": taken from there"))
                  tip)

              :else
              (let [_ (say (str "generating the application from " label " - a JVM starts…"))
                    generated (str (fs/path (fs/create-temp-dir {:prefix "template-upgrade-"}) (fs/file-name app)))
                    _ (if generate (generate generated) (app/generate! pin {:app-name app-name :target-dir generated :local-root local-root}))
                    template-wt (fs/path work "worktrees" id "template")
                    c (commit-template! app env template-wt generated label)]
                (fs/delete-tree (fs/parent generated))
                (cond
                  c (do (say (str "template now holds " (subs c 0 7) ": " label)) c)
                  tip-merged? (when-not branch-anyway?
                                (nothing! (str "the tree generated from " label " is template's tip already, commit for commit, and it is merged")))
                  :else (do (say (str "the tree generated from " label " is template's tip already, " (subs tip 0 7)
                                      ", not yet merged into " base-branch ": taken from there"))
                            tip))))
            base-commit (git! app env "rev-parse" "HEAD")
            wt (fs/path work "worktrees" id "app")
            _ (fs/create-dirs (fs/parent wt))
            _ (if (branch-exists? app env id)
                (git! app env "worktree" "add" "-q" (str wt) id)
                (git! app env "worktree" "add" "-q" "-b" id (str wt) base-branch))
            _ (fs/create-dirs run-dir)
            _ (spit (str state-file)
                    (with-out-str (pp/pprint (initial-state {:id id :ws ws :now now :from from :to to :label label
                                                             :hook (when (= :rule-mirror before-gates) {:kind :rule-mirror :release release})
                                                             :template-commit template-commit
                                                             :base-branch base-branch :base-commit base-commit
                                                             :worktree wt}))))
            ctx (driver/context run-dir)]
        (if template-commit
          (do (driver/event! ctx {:event/kind :template-commit :commit template-commit :label label})
              (merge-template! ctx wt opts run-dir))
          ;; THE TEMPLATE AS IT WAS: the branch is for what else the caller adds before the gates
          (do (driver/event! ctx {:event/kind :template-unchanged :label label})
              (say (str "the template is as it was (" label "): the branch is for what else the upgrade adds"))
              (gates! ctx wt opts run-dir)))))))

;; ---------------------------------------------------------------------------
;; the command
;; ---------------------------------------------------------------------------

(defn project-gates
  "The project's gate sequence: the build's `loop.edn` `:gates`, `{{kit}}` made the
  workspace's KIT; the harness's own when the file names none."
  [ws]
  (let [f (fs/path (:workspace/build ws) "loop.edn")
        cfg (when (fs/exists? f) (edn/read-string (slurp (str f))))]
    (resolve-kit (or (:gates cfg) gates/default-gate-seq) (:workspace/kit ws))))

(defn -main
  "bb template-upgrade [--workspace <dir>] [--dry-run]

  From the workspace: the application brought to the template the KIT pins (or the local
  clone `KIT_TEMPLATE_LOCAL` names) on a branch in a worktree under `work/`, through the
  application's `template` branch; a conflict is the Architect's, in the worktree; the
  project's gates; the merge a person's, `bb run-loop merge`. Run again after a resolution:
  it picks up where it stopped."
  [& args]
  (let [ws (workspace/current-or-exit args)
        dry-run? (boolean (some #{"--dry-run"} args))
        pin (template/pin (template/load-pins))
        local-root (some-> (System/getenv "KIT_TEMPLATE_LOCAL") not-empty fs/absolutize fs/normalize str)]
    (when-not ws
      (println "template-upgrade: no workspace here - run it from the workspace folder, or with --workspace <dir>")
      (System/exit 1))
    (when-not (:workspace/app ws)
      (println "template-upgrade: workspace.edn names no :workspace/app")
      (System/exit 1))
    (println (str "template-upgrade: " (fs/file-name (:workspace/app ws)) " to "
                  (app/generation-label pin local-root)))
    (if dry-run?
      (let [app (:workspace/app ws)
            has-branch? (branch-exists? app nil branch)
            tip (when has-branch? (generated-from (:out (git app nil "log" "-1" "--format=%s" branch))))]
        (say (str "template branch: " (if has-branch? (str "exists, at " (pr-str tip)) "would be made at the first commit")))
        (say (let [runs (fs/path (:workspace/work ws) "runs")
                   open (open-run-id runs)
                   id (or open (fresh-id runs (run-id pin local-root)))]
               (str "run: work/runs/" id (when open " (open: resumed)") ", worktree work/worktrees/" id "/app")))
        (say (str "gates: " (str/join " · " (map (comp name first) (project-gates ws)))))
        (println "template-upgrade --dry-run: nothing was written."))
      (try
        (let [{:keys [outcome]} (upgrade! ws {:pin pin :local-root local-root :gates (project-gates ws)})]
          (System/exit (case outcome :awaiting-merge 0 1)))
        (catch clojure.lang.ExceptionInfo e
          (println (str "template-upgrade: " (ex-message e)))
          (System/exit (if (:nothing-to-upgrade (ex-data e)) 0 1)))))))
