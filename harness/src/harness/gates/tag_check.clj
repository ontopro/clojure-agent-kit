(ns harness.gates.tag-check
  "`bb tag-check <tag>`: whether the KIT's pre-tag checklist (the head of
  `DEVLOG.md`) holds for a tag on HEAD. It checks and never tags: the tag, the
  merge and the push stay the person's.

  The checks, each a line with its verdict: the tree clean on a `plan-v*`
  branch or `main`; the health records fresh - no path `bb health` exercises
  changed since the commit each names - for both platforms; the tag's DEVLOG
  heading and its one line; the root README naming it; the tag absent here and
  on origin; the pinned template's tag on the fork, at the pinned commit; a
  secrets scan of the commits since the last tag; the gates record green for
  HEAD's tree; a docker-gates record green for HEAD. bear-claw's secrets scan
  is named, never run: the script is the person's, outside the clone.

  `--run` first does what is stale or missing, in the checklist's order,
  stopping at the first red: the health records (`bb health --record`,
  `bb docker-health`, `bb health-sync`) - and then it stops, because the
  containers clone HEAD and the records must be committed first; run again
  after the commit and it goes on to `bb gates` and `bb docker-gates`.
  `--landed`, after the person's push: origin's tag and `main` both at the
  tag's commit. The KIT's own development only."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [harness.gates.record :as record]))

;; ---------------------------------------------------------------- git and files

(defn- run
  "`cmd args...` in `dir`: {:exit :out}, never throwing."
  [dir & args]
  (let [{:keys [exit out]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true} args)]
    {:exit exit :out (str/trim out)}))

(defn- git [root & args]
  (let [{:keys [exit out]} (apply run root "git" args)]
    (when (zero? exit) out)))

(def platforms
  "The platforms a tag needs a health record for: the macOS host and the container."
  #{"macos-arm64" "linux-arm64"})

(def health-paths
  "What `bb health` exercises: a change here since a record's commit makes it
  stale. The loop, the gates, the runner and `init` all run in it, not only
  `setup/`; the records themselves are excluded."
  ["harness/src/" "harness/resources/" "harness/health/" "harness/bb.edn" "tools/" "Dockerfile" "bb.edn"])

(def records-dir "harness/health/records")

(def docker-gates-path
  "The record `bb docker-gates` writes, red or green."
  ".local/gates/docker-gates.edn")

;; ---------------------------------------------------------------- pure checks

(defn- check [id ok? detail] {:check id :ok? (boolean ok?) :detail detail})

(defn devlog-check
  "The tag's heading in `DEVLOG.md` - `## <tag> — ...` - with a line of prose
  under it before anything else."
  [devlog tag]
  (let [lines (str/split-lines (or devlog ""))
        head (str "## " tag " — ")
        after (->> lines (drop-while #(not (str/starts-with? % head))) rest (remove str/blank?) first)]
    (check :devlog (and (some #(str/starts-with? % head) lines) after (not (str/starts-with? after "#")))
           (if (some #(str/starts-with? % head) lines)
             (if (and after (not (str/starts-with? after "#")))
               (str "`" head "...` and its line")
               (str "`" head "...` has no line under it - what a workspace made before it needs"))
             (str "no `" head "...` heading")))))

(defn readme-check [readme tag]
  (check :readme (str/includes? (or readme "") (str "`" tag "`"))
         (if (str/includes? (or readme "") (str "`" tag "`"))
           (str "the root README names `" tag "`")
           (str "the root README does not name `" tag "` as the current tag"))))

(defn tree-check [branch porcelain]
  (let [branch-ok? (or (= branch "main") (some-> branch (str/starts-with? "plan-v")))]
    (check :tree (and branch-ok? (str/blank? porcelain))
           (cond (not branch-ok?) (str "on `" branch "`, not a `plan-v*` branch or `main`")
                 (not (str/blank? porcelain)) (str "the tree is not clean: "
                                                   (str/join ", " (take 5 (map #(last (str/split (str/trim %) #"\s+")) (str/split-lines porcelain)))))
                 :else (str "clean on `" branch "`")))))

(defn health-check
  "`records`: [{:platform :sha :dirty? :changed [paths]}], `changed` the health
  paths that differ between the record's commit and HEAD."
  [records]
  (let [by (into {} (map (juxt :platform identity) records))
        missing (remove by (sort platforms))
        stale (filter #(or (:dirty? %) (seq (:changed %))) (keep by (sort platforms)))]
    (check :health (and (empty? missing) (empty? stale))
           (cond (seq missing) (str "no record for " (str/join ", " missing))
                 (seq stale) (str/join "; " (for [{:keys [platform sha dirty? changed]} stale]
                                              (str platform " (on " (subs sha 0 7) ") is stale: "
                                                   (if dirty? "recorded on a dirty tree"
                                                       (str (str/join ", " (take 3 changed))
                                                            (when (> (count changed) 3) (str " and " (- (count changed) 3) " more"))
                                                            " changed since")))))
                 :else (str "both platforms recorded on code HEAD still has ("
                            (str/join ", " (map #(str (:platform %) " " (subs (:sha %) 0 7)) (keep by (sort platforms)))) ")")))))

(defn stale-platforms
  "The platforms whose health record is missing or stale."
  [records]
  (let [by (into {} (map (juxt :platform identity) records))]
    (set (filter #(let [r (by %)] (or (nil? r) (:dirty? r) (seq (:changed r)))) platforms))))

(defn gates-check [rec head-tree]
  (check :gates (and rec (zero? (:exit rec)) (= (:tree rec) (:tree-after rec) head-tree))
         (cond (nil? rec) "no gates record: `bb gates` in harness/"
               (not (zero? (:exit rec))) (str "the last gates run was red, at " (:step rec))
               (not= (:tree rec) head-tree) "the gates record is for another tree than HEAD's: `bb gates` on HEAD"
               (not= (:tree rec) (:tree-after rec)) "the tree changed while the gates ran"
               :else (str "green on HEAD's tree (" (:ended rec) ")"))))

(defn docker-gates-check [rec head]
  (check :docker-gates (and rec (zero? (:exit rec)) (= (:commit rec) head))
         (cond (nil? rec) "no docker-gates record: `bb docker-gates` at the root"
               (not= (:commit rec) head) (str "the docker-gates record is for " (some-> (:commit rec) (subs 0 7)) ", not HEAD")
               (not (zero? (:exit rec))) "the last docker-gates run on HEAD was red"
               :else (str "green on HEAD (" (:ended rec) ")"))))

(defn tag-absent-check [tag local remote]
  (check :tag-absent (and (nil? local) (str/blank? remote))
         (cond local (str "`" tag "` already exists here")
               (not (str/blank? remote)) (str "`" tag "` already exists on origin")
               :else (str "`" tag "` exists neither here nor on origin"))))

(defn peeled
  "The commit a tag names in `git ls-remote` output: the peeled `^{}` line when
  the tag is annotated, else the tag's own line."
  [ls-remote tag]
  (let [lines (map #(str/split % #"\s+") (str/split-lines (or ls-remote "")))
        ref-sha (fn [wanted] (some (fn [[sha r]] (when (= r wanted) sha)) lines))]
    (or (ref-sha (str "refs/tags/" tag "^{}")) (ref-sha (str "refs/tags/" tag)))))

(defn pin-check [{:keys [git/tag git/sha]} remote-sha]
  (check :pin (= sha remote-sha)
         (cond (nil? remote-sha) (str "the pin's tag `" tag "` is not on the fork's remote")
               (not= sha remote-sha) (str "the fork's `" tag "` is " (subs remote-sha 0 7) ", the pin is " (subs sha 0 7))
               :else (str "`" tag "` on the fork at the pinned " (subs sha 0 7)))))

(defn landed-check [tag local-commit ls-remote]
  (let [remote-tag (peeled ls-remote tag)
        main (some (fn [[sha r]] (when (= r "refs/heads/main") sha))
                   (map #(str/split % #"\s+") (str/split-lines (or ls-remote ""))))]
    (check :landed (and local-commit (= local-commit remote-tag main))
           (cond (nil? local-commit) (str "no local `" tag "`")
                 (not= local-commit remote-tag) (str "origin's `" tag "` is " (or (some-> remote-tag (subs 0 7)) "absent")
                                                     ", the local one " (subs local-commit 0 7))
                 (not= local-commit main) (str "origin's `main` is " (or (some-> main (subs 0 7)) "absent")
                                               ", not the tag's " (subs local-commit 0 7))
                 :else (str "origin's `" tag "` and `main` both at " (subs local-commit 0 7))))))

;; ---------------------------------------------------------------- facts

(defn read-records [root]
  (for [f (sort (map str (fs/glob (fs/path root records-dir) "*.edn")))
        :let [r (edn/read-string (slurp f))
              sha (get-in r [:kit :sha])]]
    {:platform (get-in r [:platform :key])
     :sha sha
     :dirty? (get-in r [:kit :dirty?])
     :changed (some-> (apply git root "diff" "--name-only" sha "HEAD" "--"
                             (concat health-paths [(str ":(exclude)" records-dir)]))
                      str/split-lines
                      (->> (remove str/blank?)))}))

(defn- read-edn [f] (when (fs/exists? f) (edn/read-string (slurp (str f)))))

(defn- pin [root]
  (let [{:keys [default templates]} (read-edn (fs/path root "harness/resources/template-pins.edn"))]
    (get templates default)))

(defn last-tag
  "The newest version tag HEAD contains other than `tag`, or nil."
  [root tag]
  (->> (some-> (git root "tag" "--merged" "HEAD" "--list" "[0-9]*.[0-9]*.[0-9]*") str/split-lines)
       (remove #{tag})
       (sort-by (fn [t] (mapv parse-long (str/split t #"\."))))
       last))

(defn checks
  "Every check, in the checklist's order. Reads the network (origin, the fork)
  and runs the secrets scan."
  [root tag]
  (let [head (git root "rev-parse" "HEAD")
        since (last-tag root tag)
        secrets (when since
                  (apply run root "bb" "--config" "tools/security/bb.edn" "secrets" "--from" since
                         ["--out" ".local/gates/tag-check"]))
        the-pin (pin root)]
    [(tree-check (git root "branch" "--show-current") (git root "status" "--porcelain"))
     (health-check (read-records root))
     (devlog-check (slurp (str (fs/path root "DEVLOG.md"))) tag)
     (readme-check (slurp (str (fs/path root "README.md"))) tag)
     (tag-absent-check tag (git root "rev-parse" "-q" "--verify" (str "refs/tags/" tag))
                       (git root "ls-remote" "--tags" "origin" (str "refs/tags/" tag)))
     (pin-check the-pin (peeled (git root "ls-remote" (:git/url the-pin) (str "refs/tags/" (:git/tag the-pin))
                                     (str "refs/tags/" (:git/tag the-pin) "^{}"))
                                (:git/tag the-pin)))
     (check :secrets (and since (zero? (:exit secrets)))
            (cond (nil? since) "no earlier version tag to scan from"
                  (zero? (:exit secrets)) (str "no secret in the commits since " since)
                  :else (str "the secrets scan since " since " failed: .local/gates/tag-check/security-secrets.md")))
     (gates-check (record/read-record root) (git root "rev-parse" "HEAD^{tree}"))
     (docker-gates-check (read-edn (fs/path root docker-gates-path)) head)]))

;; ---------------------------------------------------------------- the commands

(defn- print-checks [cs]
  (doseq [{:keys [check ok? detail]} cs]
    (println (format "  %-4s %-13s %s" (if ok? "ok" "FAIL") (name check) detail))))

(defn- step!
  "One `--run` step, its exit 0 or the command stops here."
  [label dir & args]
  (println (str "\n== " label ": " (str/join " " args)))
  (let [{:keys [exit]} @(apply p/process {:dir (str dir) :inherit true} args)]
    (when-not (zero? exit)
      (println (str "\ntag-check --run: " label " was red (exit " exit "); stopped"))
      (System/exit exit))))

(defn run-stale!
  "`--run`: the stale health records first - then stop for the commit - else
  the gates and docker-gates on HEAD, whichever are not green for it."
  [root]
  (let [stale (stale-platforms (read-records root))
        harness (fs/path root "harness")]
    (if (seq stale)
      (do (when (some #(not (str/starts-with? % "linux")) stale)
            (step! "health" harness "bb" "health" "--record"))
          (when (some #(str/starts-with? % "linux") stale)
            (step! "docker-health" root "bb" "docker-health"))
          (step! "health-sync" harness "bb" "health-sync")
          (println "\ntag-check --run: the health records are new. Commit them and the README block (and the tag's"
                   "\ndocuments), then run `bb tag-check <tag> --run` again: the containers see only what is committed.")
          (System/exit 0))
      (let [head (git root "rev-parse" "HEAD")]
        (when-not (:ok? (gates-check (record/read-record root) (git root "rev-parse" "HEAD^{tree}")))
          (step! "gates" harness "bb" "gates"))
        (when-not (:ok? (docker-gates-check (read-edn (fs/path root docker-gates-path)) head))
          (step! "docker-gates" root "bb" "docker-gates"))))))

(defn docker-recorded!
  "Run `f` (the docker-gates container) and write its record - the commit the
  container cloned, the exit, start and end - red or green; then exit as it did."
  [f]
  (let [root (or (git "." "rev-parse" "--show-toplevel") ".")
        head (git root "rev-parse" "HEAD")
        dirty? (not (str/blank? (git root "status" "--porcelain")))
        started (str (java.time.Instant/now))
        exit (try (f) 0
                  (catch Exception e (or (:exit (ex-data e)) 1)))
        out (fs/path root docker-gates-path)]
    (fs/create-dirs (fs/parent out))
    (spit (str out) (with-out-str (pprint/pprint {:commit head :exit exit :dirty? dirty?
                                                  :started started :ended (str (java.time.Instant/now))})))
    (println (str "docker-gates record: " docker-gates-path (if (zero? exit) " (green)" " (RED)")
                  (when dirty? " - uncommitted changes were NOT in the container")))
    (when-not (zero? exit) (System/exit exit))))

(defn -main
  "bb tag-check <tag> [--run | --landed]"
  [& args]
  (let [tag (first (remove #(str/starts-with? % "--") args))
        flags (set (filter #(str/starts-with? % "--") args))
        root (or (git "." "rev-parse" "--show-toplevel")
                 (do (println "tag-check: not in a git repository") (System/exit 2)))]
    (when-not (and tag (re-matches #"\d+\.\d+\.\d+" tag))
      (println "bb tag-check <tag> [--run | --landed] - the tag a version, e.g. 0.6.2; it is checked, never created")
      (System/exit 2))
    (if (flags "--landed")
      (let [c (landed-check tag (git root "rev-parse" "-q" "--verify" (str "refs/tags/" tag "^{commit}"))
                            (git root "ls-remote" "origin" (str "refs/tags/" tag) (str "refs/tags/" tag "^{}") "refs/heads/main"))]
        (print-checks [c])
        (System/exit (if (:ok? c) 0 1)))
      (do (when (flags "--run") (run-stale! root))
          (println (str "\ntag-check " tag " on " (subs (git root "rev-parse" "HEAD") 0 7)))
          (let [cs (checks root tag)]
            (print-checks cs)
            (println "  by hand: bear-claw's secrets scan (`bear-claw.bb -s secrets`), the second rule set - not run here")
            (if (every? :ok? cs)
              (println (str "\nevery check holds: `" tag "` may be cut on HEAD, by the person"))
              (do (println (str "\n" (count (remove :ok? cs)) " check(s) fail: no tag")) (System/exit 1))))))))
