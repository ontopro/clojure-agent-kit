(ns harness.provision-test
  "Provisioning against real git, with a STUB nREPL.

  The git half is real — `repair_test.clj` already shells real git, and a
  worktree fixture is cheap. The nREPL half is stubbed: the seed is bb-only and
  has no JVM, and a suite that starts one JVM per test would be too slow to run
  on every change. `:nrepl/cmd` exists as an option for exactly this, which is
  the technique README prescribes for runners.

  The real thing was exercised by hand against `sandbox/`: three worktrees, two
  live nREPLs reachable by `clj-nrepl-eval`, assembly refusing a stray file and
  then succeeding, and teardown freeing the port after the REPL had been used."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.provision :as prov]
   [harness.shapes :as shapes]))

(defn- git! [dir & args]
  (apply p/shell {:dir dir :out :string :err :string} "git" args))

(defn- repo
  "A throwaway repo with one commit, so `worktree add HEAD` has a HEAD."
  []
  (let [dir (str (fs/create-temp-dir))]
    (git! dir "init" "-q")
    (git! dir "config" "user.email" "t@example.com")
    (git! dir "config" "user.name" "T")
    (fs/create-dirs (fs/path dir "src"))
    (spit (str (fs/path dir "src" "seed.clj")) "(ns seed)\n")
    (git! dir "add" "-A")
    (git! dir "commit" "-qm" "init")
    dir))

(def ^:private stub-nrepl
  "Writes a port file and stays up — everything provisioning needs from an
  nREPL, without a JVM."
  ["sh" "-c" "echo 45999 > .nrepl-port; sleep 30"])

(defn- provision-opts [root wt role]
  {:repo/root root :worktrees/dir wt :task/id "t-01" :task/role role
   :nrepl/cmd stub-nrepl :ready/poll-ms 20 :ready/timeout-ms 8000})

(deftest provisions-a-worktree-with-its-own-repl
  (let [root (repo) wt (str (fs/create-temp-dir))
        s (prov/provision! (provision-opts root wt :coder))]
    (is (shapes/valid-session? s))
    (is (fs/exists? (fs/path (:worktree/git-root s) "src" "seed.clj"))
        "the worktree is a checkout, not an empty directory")
    (is (= 45999 (:nrepl/port s)) "the port is READ, not chosen")
    (is (pos-int? (:nrepl/pid s)))
    (testing "the branch is named for the task and role, so escalated work is reachable"
      (is (str/includes? (:out (git! root "branch" "--list")) "t-01-coder")))
    (prov/teardown! s root)))

(deftest a-gate-workspace-has-no-repl
  (let [root (repo) wt (str (fs/create-temp-dir))
        s (prov/provision! (assoc (provision-opts root wt :reviewer) :nrepl? false))]
    (is (shapes/valid-session? s) "Session tolerates a missing port on purpose")
    (is (nil? (:nrepl/port s)))
    (is (nil? (:nrepl/pid s)))
    (prov/teardown! s root)))

(deftest project-root-and-git-root-differ-when-the-project-is-nested
  ;; A worktree is a checkout of the whole repository; the project may sit in a
  ;; subdirectory. :worktree/path is what a packet's :repl/worktree means.
  (let [root (repo) wt (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path root "app"))
    (spit (str (fs/path root "app" "x.clj")) "(ns x)\n")
    (git! root "add" "-A") (git! root "commit" "-qm" "app")
    (let [s (prov/provision! (assoc (provision-opts root wt :coder)
                                    :project/subdir "app"))]
      (is (not= (:worktree/path s) (:worktree/git-root s)))
      (is (str/ends-with? (:worktree/path s) "/app"))
      (is (fs/exists? (fs/path (:worktree/path s) "x.clj")))
      (prov/teardown! s root))))

(deftest a-failed-launch-leaves-nothing-behind
  ;; Found by running it: the worktree was created before the launch failed, and
  ;; the branch outlived it, so the next attempt at the same task died with
  ;; "already exists" and named the wrong cause.
  (let [root (repo) wt (str (fs/create-temp-dir))
        opts (assoc (provision-opts root wt :coder) :nrepl/cmd ["definitely-not-a-program"])]
    (is (thrown? Exception (prov/provision! opts)))
    (testing "no worktree"
      (is (not (fs/exists? (fs/path wt "t-01-coder")))))
    (testing "and no branch, so the task can be retried"
      (is (not (str/includes? (:out (git! root "branch" "--list")) "t-01-coder"))))
    (testing "which means a retry succeeds"
      (let [s (prov/provision! (provision-opts root wt :coder))]
        (is (shapes/valid-session? s))
        (prov/teardown! s root)))))

(deftest teardown-removes-the-worktree-and-keeps-the-branch
  (let [root (repo) wt (str (fs/create-temp-dir))
        s (prov/provision! (provision-opts root wt :coder))]
    (prov/teardown! s root)
    (is (not (fs/exists? (:worktree/git-root s))))
    (testing "the branch survives — §07 leaves escalated work reachable"
      (is (str/includes? (:out (git! root "branch" "--list")) "t-01-coder")))))

(deftest scope-violations-see-every-file-not-only-clojure-ones
  (let [root (repo) wt (str (fs/create-temp-dir))
        s (prov/provision! (provision-opts root wt :coder))
        w (:worktree/path s)]
    (spit (str (fs/path w "src" "seed.clj")) "(ns seed)\n(def a 1)\n")
    (spit (str (fs/path w "notes.md")) "stray\n")
    (is (= ["notes.md"] (prov/scope-violations s ["src/seed.clj"]))
        "a stray .md is as out of scope as a stray .clj")
    (testing "nothing outside the target is clean"
      (fs/delete (fs/path w "notes.md"))
      (is (empty? (prov/scope-violations s ["src/seed.clj"]))))
    (prov/teardown! s root)))

(deftest assembly-refuses-rather-than-dropping-silently
  (let [root (repo) wt (str (fs/create-temp-dir))
        coder (prov/provision! (provision-opts root wt :coder))
        gate (prov/provision! (assoc (provision-opts root wt :reviewer) :nrepl? false))
        w (:worktree/path coder)]
    (spit (str (fs/path w "src" "seed.clj")) "(ns seed)\n(def a 1)\n")
    (spit (str (fs/path w "stray.clj")) "(ns stray)\n")
    (testing "a file outside the packet stops the assembly and is named"
      (let [e (try (prov/assemble! gate [[coder ["src/seed.clj"]]]) nil
                   (catch Exception e e))]
        (is (some? e) "silently dropping it would leave the gates on an incomplete change")
        (is (= {:coder ["stray.clj"]} (:violations (ex-data e))))))
    (testing "and once it is gone, only the declared target crosses"
      (fs/delete (fs/path w "stray.clj"))
      (let [r (prov/assemble! gate [[coder ["src/seed.clj"]]])]
        (is (= ["src/seed.clj"] (:assembled r)))
        (is (str/includes? (slurp (str (fs/path (:worktree/path gate) "src" "seed.clj")))
                           "(def a 1)")
            "the gate workspace holds what the coder wrote")))
    (prov/teardown! coder root)
    (prov/teardown! gate root)))

(deftest wait-for-port-times-out-rather-than-hanging
  ;; Polling, not sleeping — and a bounded failure rather than a wedged loop.
  (let [dir (str (fs/create-temp-dir))]
    (is (thrown-with-msg? Exception #"did not report a port"
                          (prov/wait-for-port dir {:ready/poll-ms 10
                                                   :ready/timeout-ms 60})))
    (testing "and reads the port once it appears"
      (spit (str (fs/path dir ".nrepl-port")) "45999\n")
      (is (= 45999 (prov/wait-for-port dir {:ready/poll-ms 10 :ready/timeout-ms 500}))))))

(deftest scope-check-sees-modified-files-in-a-nested-project
  ;; Found by running the loop by hand, not by unit tests — every fixture until
  ;; now had the project AT the worktree root, where the two git commands agree.
  ;;
  ;; `git diff --name-only` reports from the REPOSITORY root; `ls-files
  ;; --others` reports from the current directory. With the project one level
  ;; down they disagree, the existence filter drops the diff results, and the
  ;; scope check sees only NEW files. An agent editing a file it was never
  ;; given was invisible.
  (let [root (repo) wt (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path root "app" "src"))
    (spit (str (fs/path root "app" "src" "x.clj")) "(ns x)\n")
    (spit (str (fs/path root "app" "layers.edn")) "{x #{}}\n")
    (git! root "add" "-A") (git! root "commit" "-qm" "app")
    (let [s (prov/provision! (assoc (provision-opts root wt :coder)
                                    :project/subdir "app"))
          w (:worktree/path s)]
      (spit (str (fs/path w "src" "x.clj")) "(ns x)\n(def a 1)\n")
      (spit (str (fs/path w "layers.edn")) "{x #{} y #{}}\n")
      (testing "a MODIFIED tracked file outside the target is caught"
        (is (= ["layers.edn"] (prov/scope-violations s ["src/x.clj"]))))
      (testing "and the claimed one is not"
        (is (not (some #{"src/x.clj"} (prov/scope-violations s ["src/x.clj"])))))
      (prov/teardown! s root))))

(deftest assembly-takes-architecture-that-belongs-to-no-role
  ;; Found by running the loop: a new namespace needs a layers.edn entry, gate 4
  ;; fails without one, and no dispatched role can supply it — it is in neither
  ;; packet's :files/target and assembly refuses it as a scope violation. The
  ;; layer table is architecture, written before the code, so it needs its own
  ;; way in.
  (let [root (repo) wt (str (fs/create-temp-dir))
        coder (prov/provision! (provision-opts root wt :coder))
        gate (prov/provision! (assoc (provision-opts root wt :reviewer) :nrepl? false))
        arch (str (fs/create-temp-dir))]
    (spit (str (fs/path (:worktree/path coder) "src" "seed.clj")) "(ns seed)\n(def a 1)\n")
    (spit (str (fs/path arch "layers.edn")) "{seed #{}}\n")
    (let [r (prov/assemble! gate [[coder ["src/seed.clj"]]]
                            {:from arch :files ["layers.edn"]})]
      (testing "the Architect's file reaches the gate workspace"
        (is (= ["layers.edn"] (:architecture r)))
        (is (fs/exists? (fs/path (:worktree/path gate) "layers.edn"))))
      (testing "and is reported apart from agent output, so a run log can tell them apart"
        (is (= ["src/seed.clj"] (:assembled r)))))
    (testing "it does not disable the scope check"
      (spit (str (fs/path (:worktree/path coder) "stray.clj")) "(ns stray)\n")
      (is (thrown? Exception
                   (prov/assemble! gate [[coder ["src/seed.clj"]]]
                                   {:from arch :files ["layers.edn"]}))))
    (testing "a half-specified architecture map is refused rather than ignored"
      (fs/delete (fs/path (:worktree/path coder) "stray.clj"))
      (is (thrown-with-msg? Exception #"needs both"
                            (prov/assemble! gate [[coder ["src/seed.clj"]]] {:from arch}))))
    (prov/teardown! coder root)
    (prov/teardown! gate root)))

(deftest harness-written-files-are-not-the-agents-fault
  ;; Found by the SECOND end-to-end run, after the first had already taught the
  ;; same lesson about .nrepl-port. The generated stub lives in the Tester's
  ;; worktree and the Tester did not write it, so the scope check reported it
  ;; and assembly refused — a task using a stub could never have assembled.
  ;;
  ;; harness-artifacts is a fixed set and cannot cover a path that depends on
  ;; the task, so the session records what the harness put there.
  (let [root (repo) wt (str (fs/create-temp-dir))
        s (prov/provision! (provision-opts root wt :tester))
        w (:worktree/path s)]
    (spit (str (fs/path w "src" "stub.clj")) "(ns stub)\n")
    (spit (str (fs/path w "src" "seed_test.clj")) "(ns seed-test)\n")
    (testing "without the record, the harness's own file is blamed on the agent"
      (is (some #{"src/stub.clj"} (prov/scope-violations s ["src/seed_test.clj"]))))
    (testing "with it, only what the agent actually wrote outside its target counts"
      (let [s' (assoc s :harness/wrote ["src/stub.clj"])]
        (is (empty? (prov/scope-violations s' ["src/seed_test.clj"])))
        (is (shapes/valid-session? s') ":harness/wrote is part of the Session")
        (testing "and a genuine stray is still caught"
          (spit (str (fs/path w "stray.md")) "x\n")
          (is (= ["stray.md"] (prov/scope-violations s' ["src/seed_test.clj"]))))))
    (prov/teardown! s root)))

(deftest a-role-that-produced-nothing-is-refused
  ;; Run D6: the Tester exhausted its iteration budget having written no test
  ;; file. Assembly copied the Coder's implementation, skipped the absent test
  ;; SILENTLY, and all four gates went green — over the sandbox's existing
  ;; tests, with zero coverage of the new namespace. A scope violation fails
  ;; noisily; a missing deliverable passed.
  (let [gate (str (fs/create-temp-dir))
        coder (str (fs/create-temp-dir))
        tester (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path coder "src"))
    (spit (str (fs/path coder "src/impl.clj")) "(ns impl)\n")
    (let [e (try (prov/assemble! {:worktree/path gate}
                                 [[{:worktree/path coder :task/role :coder} ["src/impl.clj"]]
                                  [{:worktree/path tester :task/role :tester} ["test/impl_test.clj"]]])
                 nil
                 (catch Exception ex (ex-data ex)))]
      (is (= {:tester ["test/impl_test.clj"]} (:missing e))
          "named by role, so triage knows who to dispatch again")
      (is (not (fs/exists? (fs/path gate "src/impl.clj")))
          "and nothing was assembled — a half-built change must not reach the gates"))))

(deftest the-review-diff-shows-the-files-a-task-created
  ;; Found preparing D7: every driver built the Reviewer's diff with
  ;; `git diff HEAD`, which omits untracked files — so the D5 and D6 Reviewers
  ;; got the layers.edn change and not one line of the code or tests they reviewed.
  (let [root (repo) wt (str (fs/create-temp-dir))
        coder (prov/provision! (provision-opts root wt :coder))
        gate (prov/provision! (assoc (provision-opts root wt :reviewer) :nrepl? false))
        arch (str (fs/create-temp-dir))]
    (testing "nothing assembled says so, rather than handing over an empty string"
      (is (= "(no diff)" (prov/review-diff gate))))
    (spit (str (fs/path (:worktree/path coder) "src" "fresh.clj")) "(ns fresh)\n(def created 1)\n")
    (fs/create-dirs (fs/path arch "src"))
    (spit (str (fs/path arch "src" "seed.clj")) "(ns seed)\n(def modified 2)\n")
    (prov/assemble! gate [[coder ["src/fresh.clj"]]] {:from arch :files ["src/seed.clj"]})
    (let [d (prov/review-diff gate)]
      (testing "a file the task created is in it"
        (is (str/includes? d "src/fresh.clj"))
        (is (str/includes? d "+(def created 1)")))
      (testing "and so is a tracked file it changed"
        (is (str/includes? d "+(def modified 2)"))))
    (prov/teardown! coder root)
    (prov/teardown! gate root)))
