;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.loop.provision
  "Workspaces: a git worktree and its own nREPL, one per (task, role).

  THREE PER TASK — coder, tester, and a gate workspace neither agent touched.
  Two worktrees would be cheaper and would lose the thing this buys: assembly
  into the third is a FILTER, so a file an agent wrote outside its
  `:files/target` cannot reach the gates at all. §12's *auto-approving an
  agent's edits is only safe because it runs in an isolated workspace* stops
  being an assertion.

  It is also what makes the Tester's independence real rather than asserted.
  §07 step 3 dispatches Coder and Tester concurrently and says they *do not
  share each other's output*: the Tester's worktree holds no implementation, so
  a test derived from one is impossible rather than merely forbidden. The
  `:no-gates` rule is belt-and-braces over that, and until this namespace
  existed it was the only thing there.

  LAYOUT IS INJECTABLE. `harness/README.md` cut provisioning as
  *git-worktree-and-nREPL specific, and short enough to write against your own
  layout* — so every path, command and timeout here is an option with a
  default, and this file is a worked example rather than a framework.

  A NOTE ON THE PROJECT ROOT. A git worktree is a checkout of the whole
  repository; the project may sit in a subdirectory of it. `:worktree/path` is
  therefore the PROJECT root — what a packet's `:repl/worktree` means and where
  gates run — while `:worktree/git-root` is what git was told to make and what
  teardown removes. Conflating them works right up until the first monorepo."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [harness.gates.repair :as repair]))

(def defaults
  "Everything a caller may override. `:nrepl/cmd` is the seam tests point at a
  stub script; `:project/subdir` is nil when the project is the repo root.

  `-Srepro` BECAUSE A PERSONAL ALIAS OF THE SAME NAME LEAKS IN: the Clojure CLI
  merges `~/.clojure/deps.edn` into the project's, so one machine's own `:test
  {:main-opts [\"-m\" \"kaocha.runner\"]}` made a project's `-M:dev:test` try to
  run kaocha. A worktree's REPL is the project's and nobody else's; the project
  must define the alias itself."
  {:nrepl/cmd ["clojure" "-Srepro" "-M:nrepl"]
   :project/subdir nil
   :ready/poll-ms 200
   :ready/timeout-ms 60000})

;; ---------------------------------------------------------------------------
;; git
;; ---------------------------------------------------------------------------

(defn- git!
  "Run git in `dir`, throwing on failure. Provisioning failures are NOT gate
  failures — a worktree that could not be created means the loop never started,
  and dressing that up as a red gate would send triage after the wrong thing."
  [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir dir :out :string :err :string
                                               :continue true}
                                      "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "git failed during provisioning"
                      {:args (vec args) :dir dir :exit exit :out (str out err)})))
    (str/trim out)))

(defn branch-name
  "The branch a workspace gets. One per (task, role), so an escalated worktree
  can be committed to and inspected without touching anyone else's."
  [task-id role]
  (str task-id "-" (name role)))

;; ---------------------------------------------------------------------------
;; readiness
;; ---------------------------------------------------------------------------

(defn read-port
  "The port from `dir`'s .nrepl-port, or nil.

  Read rather than chosen. nREPL writes the port it actually bound, which is
  why `:nrepl` binds port 0 — picking a free port yourself and then handing it
  over is a race, and it is the same file `clj-nrepl-eval --discover-ports`
  reads."
  [dir]
  (let [f (fs/path dir ".nrepl-port")]
    (when (fs/exists? f)
      (some-> (slurp (str f)) str/trim not-empty parse-long))))

(defn wait-for-port
  "Poll until `dir` has a readable .nrepl-port, or throw.

  Polling, never sleeping. A fixed sleep is the classic flake: too short and it
  fails on a cold JVM, too long and every task pays for the worst case.

  AND IT WATCHES THE PROCESS, not only the clock. An nREPL that dies on a bad
  alias, a missing dependency or a broken `user.clj` never writes a port file,
  so waiting out the timeout turns a one-line error into sixty seconds of
  nothing followed by the wrong diagnosis. Given the process handle, this
  throws the moment it is gone and names `:log`, which is where the reason is.
  The 2-arity is for a caller that has no handle."
  ([dir opts] (wait-for-port dir nil opts))
  ([dir proc {:keys [ready/poll-ms ready/timeout-ms nrepl/log]}]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)
         died? (fn [] (and proc (not (.isAlive ^java.lang.Process (:proc proc)))))]
     (loop []
       (or (read-port dir)
           (cond
             (died?)
             (throw (ex-info "the nREPL exited before it wrote a port file"
                             (cond-> {:dir (str dir) :exit (:exit (deref proc 1000 {}))}
                               log (assoc :log (str log)))))

             (< (System/currentTimeMillis) deadline)
             (do (Thread/sleep poll-ms) (recur))

             :else
             (throw (ex-info "nREPL did not report a port before the timeout"
                             (cond-> {:dir (str dir) :timeout-ms timeout-ms}
                               log (assoc :log (str log)))))))))))

;; ---------------------------------------------------------------------------
;; provisioning
;; ---------------------------------------------------------------------------

(defn provision!
  "One workspace. Returns a harness.contract.shapes/Session.

  Required: `:repo/root`, `:worktrees/dir`, `:task/id`, `:task/role`.
  Optional: `:base` (default HEAD), plus anything in `defaults`.

  `:nrepl?` false gives a worktree with no REPL — what the gate workspace
  wants, and why `:nrepl/port` is optional on the schema."
  [{:keys [repo/root worktrees/dir task/id task/role base nrepl?] :as opts}]
  (let [{:keys [nrepl/cmd project/subdir] :as opts} (merge defaults opts)
        nrepl? (if (nil? nrepl?) true nrepl?)
        git-root (str (fs/path dir (branch-name id role)))
        _ (git! root "worktree" "add" "-b" (branch-name id role) git-root (or base "HEAD"))
        project (if subdir (str (fs/path git-root subdir)) git-root)
        ;; THE nREPL'S OUTPUT IS KEPT, not discarded. It was `:out :discard`,
        ;; which is fine right up to the first nREPL that refuses to start: the
        ;; one line saying which alias or dependency was wrong went to nowhere,
        ;; and all the harness could report was that no port file appeared.
        ;;
        ;; NEXT TO THE WORKTREE, NOT INSIDE IT, and that is the whole point. A
        ;; launch that fails removes the worktree before rethrowing — so a log
        ;; written inside it is deleted at exactly the moment it is the only
        ;; thing that can say what went wrong. Outside, it survives the
        ;; rollback; `teardown!` removes it on the way out of a run that worked.
        ;; It is also then invisible to the scope check, which reads the
        ;; worktree, so it needs no entry in `harness-artifacts`.
        log (str (fs/path dir (str (branch-name id role) ".nrepl.log")))
        fail! (fn [e]
                ;; If the REPL cannot start, remove the worktree before
                ;; rethrowing. A half-provisioned workspace is worse than none:
                ;; the branch name is derived from the task, so the debris
                ;; collides with the next attempt at the same task and the
                ;; second failure names the wrong cause. And the branch —
                ;; teardown! deliberately keeps it, because escalated work must
                ;; stay reachable, but a provision that failed produced nothing
                ;; to preserve.
                (git! root "worktree" "remove" "--force" git-root)
                (git! root "branch" "-D" (branch-name id role))
                (throw e))
        proc (when nrepl?
               (try (apply p/process {:dir project :out (fs/file log) :err (fs/file log)} cmd)
                    (catch Exception e (fail! e))))
        port (when nrepl?
               (try (wait-for-port project proc (assoc opts :nrepl/log log))
                    (catch Exception e
                      (when-let [p (:proc proc)] (.destroy ^java.lang.Process p))
                      (fail! e))))]
    (cond-> {:worktree/path project
             :worktree/git-root git-root
             :task/id id
             :task/role role}
      nrepl? (assoc :nrepl/port port
                    ;; The pid, not the process object: a session is data, and
                    ;; §12 says the run log is the only artifact that survives
                    ;; a run. A handle that cannot be written down is no use to
                    ;; whoever reads that log.
                    :nrepl/pid (.pid ^java.lang.Process (:proc proc))
                    :nrepl/log log))))

(defn teardown!
  "Stop the nREPL and remove the worktree.

  Separate from `provision!` and never called by it, because §07 step 5 leaves
  an escalated workspace in place — whether to tear down is the loop's policy
  and not this namespace's. The branch survives `git worktree remove`, so the
  work is still reachable after the directory is gone."
  [{:keys [worktree/git-root nrepl/pid nrepl/log] :as session} repo-root]
  (when pid
    (try (p/shell {:continue true :out :discard :err :discard} "kill" (str pid))
         (catch Exception _ nil)))
  (git! repo-root "worktree" "remove" "--force" git-root)
  ;; The nREPL log sits BESIDE the worktree so that a failed launch keeps it. A
  ;; teardown is the run saying it is finished with this workspace, so the log
  ;; goes with it rather than accumulating one file per role per run.
  (when log (fs/delete-if-exists log))
  (assoc session :torn-down? true))

;; ---------------------------------------------------------------------------
;; assembly — a filter, not a merge
;; ---------------------------------------------------------------------------

(def harness-artifacts
  "Files the HARNESS writes into a workspace it provisioned.

  Excluded from the scope check: accusing an agent of writing the port file
  this namespace created is a false positive, and it would fail every task.
  It only stays hidden while every project happens to gitignore `.nrepl-port`
  — this repository does, which is why provisioning by hand did not show it and
  the first test run did."
  #{".nrepl-port"})

(defn scope-violations
  "Files changed in 'session`'s worktree that its packet never claimed.

  The `:scope` check, and it lives here rather than in `gates/default-gate-seq`
  because it is a precondition on building the gate workspace — nothing should
  be assembled from a worktree whose contents are not accounted for.

  Uses `repair/changed-files`, unfiltered: an agent that wrote a stray .edn or
  .md is as out of scope as one that wrote a stray .clj.

  `:harness/wrote` on the session is excluded too. The generated stub lives in
  the Tester's worktree and the Tester did not write it — the second run of the
  loop caught that, after the first had already taught the same lesson about
  `.nrepl-port`. Anything the harness places has to be recorded where the check
  can see it."
  [session targets]
  (let [claimed (into (into harness-artifacts (:harness/wrote session)) targets)]
    (vec (remove claimed (repair/changed-files (:worktree/path session))))))

(defn assemble!
  "Copy each role's declared targets into the gate workspace.

  `role-work` is a sequence of [session targets]. Refuses loudly on any scope
  violation rather than silently dropping the file: an agent writing outside
  its packet is a signal — a confused model, or a wrong packet — and dropping
  it leaves the gates running on an incomplete change that either fails
  confusingly or passes wrongly.

  The join is conflict-free by construction: `packet/coder-packet` targets
  `:files/impl` and `packet/tester-packet` targets `:files/test`, which are
  disjoint sets.

  `architecture` is `{:from dir :files [...]}` — files that belong to no role
  and so pass no scope check. Running the loop found the need: a new namespace
  requires a `layers.edn` entry, gate 4 fails without one, and NO dispatched
  role can supply it. It is in neither packet's `:files/target`, and a Coder
  that wrote it anyway would have the assembly refuse it. That is correct — the
  layer table is architecture (§02), written before the code — but it left the
  gate workspace with no way to receive the Architect's output at all."
  ([gate-session role-work] (assemble! gate-session role-work nil))
  ([gate-session role-work {:keys [from files] :as architecture}]
   (let [violations (into {}
                          (keep (fn [[session targets]]
                                  (when-let [v (seq (scope-violations session targets))]
                                    [(:task/role session) (vec v)])))
                          role-work)]
     (when (seq violations)
       (throw (ex-info "files written outside the packet's :files/target"
                       {:violations violations})))
     (when (and architecture (or (nil? from) (empty? files)))
       (throw (ex-info "architecture needs both :from and :files"
                       {:architecture architecture})))
     ;; AND A ROLE THAT PRODUCED NOTHING IS REFUSED TOO. A Tester once
     ;; exhausted its iteration budget having written no test file; assembly
     ;; copied the Coder's implementation, skipped the absent test silently,
     ;; and ALL FOUR GATES WENT GREEN — over the sandbox's existing tests, with
     ;; zero coverage of the new namespace. §07's exit criterion
     ;; is *independently-authored tests green*; there were none, and nothing
     ;; looked.
     ;;
     ;; This namespace already refuses an EXTRA file loudly. The inverse was
     ;; never covered, and it is the more dangerous direction: a scope
     ;; violation fails noisily, a missing deliverable passes.
     (let [absent (into {}
                        (keep (fn [[session targets]]
                                (when-let [m (seq (remove #(fs/exists? (fs/path (:worktree/path session) %))
                                                          targets))]
                                  [(:task/role session) (vec m)])))
                        role-work)]
       (when (seq absent)
         (throw (ex-info "a role did not produce its :files/target"
                         {:missing absent}))))
     (let [gate-dir (:worktree/path gate-session)
           copy! (fn [src-dir path]
                   (let [src (fs/path src-dir path)
                         dst (fs/path gate-dir path)]
                     (when (fs/exists? src)
                       (fs/create-dirs (fs/parent dst))
                       (fs/copy src dst {:replace-existing true})
                       path)))]
       {:assembled (vec (for [[session targets] role-work
                              t targets
                              :let [c (copy! (:worktree/path session) t)]
                              :when c]
                          c))
        ;; Reported separately from :assembled. A reader of the run log should
        ;; be able to tell what an agent produced from what the Architect did.
        :architecture (vec (keep #(copy! from %) files))
        :into gate-dir}))))

(defn review-diff
  "What the Reviewer is shown: the gate workspace against HEAD, NEW FILES
  INCLUDED.

  The early dispatched runs built this by hand, as `git diff HEAD` — which does
  not show an untracked file, and everything a task creates is untracked. So the
  first two Reviewers to be dispatched at all were handed a `layers.edn` change
  and neither the implementation nor the tests. The loop assembles NEW namespaces
  by design, so the omission was the common case, not an edge. Intent-to-add
  records a path in the index without staging its content, which is exactly
  enough for `git diff` to see it; the gate workspace is disposable, so
  touching its index costs nothing.

  Returns \"(no diff)\" rather than an empty string, because a Reviewer handed
  nothing should be told so rather than left to wonder whether it was cut."
  [gate-session]
  (let [dir (:worktree/path gate-session)]
    (git! dir "add" "--intent-to-add" "--all" ".")
    (let [d (git! dir "diff" "HEAD")]
      (if (str/blank? d) "(no diff)" d))))
