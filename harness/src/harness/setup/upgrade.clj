(ns harness.setup.upgrade
  "What a pulled KIT expects that this workspace lacks - a report, never a
  migration.

  The KIT's clone is upgraded with `git pull`, and a workspace's copies of what
  `bb init` wrote - `workspace.edn`, the rules overlay, the profile, the plan's
  documents, the application's rule mirror - are the project's own decisions and
  are not rewritten by anyone. What a pull changes that a workspace does not
  have was, until this, said one DEVLOG entry at a time, to be found by whoever
  read the DEVLOG. `bb doctor` run in a workspace prints this report after its
  table, and it changes no verdict: it is information for the person, with the
  command that shows each change.

  `expectations` is pure over `facts`, so every line it can print is tested
  without a git repository; `facts` is the edge, reading the workspace and
  asking git in the KIT's clone what changed since the commit `bb init`
  recorded (`:workspace/kit-commit`). A workspace with no recorded commit gets
  the lines that need none and a line saying why the rest are missing."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [harness.models.profile :as profile]
   [harness.rules :as rules]
   [harness.setup.doctor :as doctor]
   [harness.setup.workspace :as workspace]))

(def expected-keys
  "The keys of `workspace.edn` the harness reads, in the order `bb init` writes
  them. A key missing reads as if the thing were not there; this report says so."
  [:workspace/kit :workspace/app :workspace/build :workspace/work
   :workspace/rule-mirrors :workspace/rules-overlay :workspace/records
   :workspace/run-tables :workspace/kit-commit])

(def renamed-keys
  "Keys the harness read once and reads no longer, each to the key that took its
  place: a workspace made before the rename carries the old one, and nothing
  reads it."
  {:workspace/plan :workspace/build})

;; ---------------------------------------------------------------------------
;; pure: the lines
;; ---------------------------------------------------------------------------

(defn expectations
  "The report's lines from `facts`:

    :dir              the workspace folder
    :kit              the KIT's clone
    :keys-present     the keys `workspace.edn` holds, as written
    :made-at          the KIT commit `bb init` recorded, or nil
    :head             the KIT's commit now
    :behind           how many commits `head` is past `made-at`, or nil when
                      `made-at` is unknown here (another KIT, or never recorded)
    :template-changed plan-template files changed since `made-at`
    :guidance-changed ids of placeholder rules whose shipped text changed since
                      `made-at`, among those this workspace's overlay fills
    :mirrors-drifted  rule mirrors that do not match the source now
    :profile          the project's profile path, or nil
    :profile-missing  roles the KIT's profile shape names and the profile lacks

  Each line names what is expected, what is here, and the command that shows
  the difference. An empty vector means nothing is missing."
  [{:keys [kit keys-present made-at head behind template-changed guidance-changed
           mirrors-drifted profile profile-missing]}]
  (let [present (set keys-present)]
    (cond-> []
      (and made-at (nil? behind))
      (conj (str "made at KIT commit " made-at ", which this clone (" kit ", at " head
                 ") does not have: another KIT, or a commit not yet pulled - nothing below can say what changed since"))

      (and made-at behind (pos? behind))
      (conj (str "made at KIT commit " made-at "; the KIT is " behind " commit"
                 (when (not= 1 behind) "s") " later, at " head))

      (and made-at behind (zero? behind))
      (conj (str "made at KIT commit " made-at ", which is where the KIT is"))

      (nil? made-at)
      (conj (str "workspace.edn records no :workspace/kit-commit (bb init writes it since 2026-10-06): "
                 "add the line `:workspace/kit-commit \"<the KIT commit this workspace was made at>\"` "
                 "and the next report can say what changed since"))

      true
      (into (for [[old new] renamed-keys :when (present old)]
              (str "workspace.edn has " old ", which the harness no longer reads: the key is " new
                   " now - rename it, keeping the value")))

      true
      (into (for [k expected-keys
                  :when (and (not (present k)) (not= k :workspace/kit-commit)
                             (not (some #(= k (renamed-keys %)) (keys renamed-keys))))]
              (str "workspace.edn has no " k ": the harness reads as if that thing were not there")))

      (seq template-changed)
      (conj (str "the plan template changed since: " (str/join ", " template-changed)
                 " - the build's copies are its own; where a document is still being filled, read the diff: "
                 "`git -C " kit " diff " made-at " HEAD -- plan-template/`"))

      (seq guidance-changed)
      (conj (str "the shipped guidance of placeholder rule" (when (not= 1 (count guidance-changed)) "s") " "
                 (str/join ", " guidance-changed) " changed since this workspace filled "
                 (if (= 1 (count guidance-changed)) "it" "them")
                 "; the overlay keeps the text as filled, so the new guidance is not seen: "
                 "`git -C " kit " diff " made-at " HEAD -- harness/resources/agent-rules.edn`"))

      (seq mirrors-drifted)
      (into (for [m mirrors-drifted]
              (str m " does not match the rule source: run `bb rules-sync` in the KIT's harness/")))

      (and profile (seq profile-missing))
      (into (for [r profile-missing]
              (str profile " has no " r ": the KIT's profile shape names it, and `bb profile` refuses without it"))))))

(defn render
  "The report as `bb doctor` prints it."
  [{:keys [dir] :as facts}]
  (let [lines (expectations facts)]
    (str "Workspace " dir "\n"
         (if (seq lines)
           (str/join "\n" (map #(str "  - " %) lines))
           "  nothing this KIT expects is missing here"))))

;; ---------------------------------------------------------------------------
;; the edge: facts
;; ---------------------------------------------------------------------------

(defn- git
  "git's stdout in `dir`, trimmed, or nil when the command fails."
  [dir & args]
  (let [{:keys [exit out]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)]
    (when (zero? exit) (str/trim out))))

(defn- raw-workspace
  "`workspace.edn` as written, keys and all - `find-workspace` makes paths
  absolute and adds its own keys, and this report is about what the FILE says."
  [ws]
  (edn/read-string (slurp (str (fs/path (:workspace/dir ws) workspace/file-name)))))

(defn- placeholder-texts
  "The shipped placeholder rules' `:id -> :text` in `source-text` (the rule
  source as EDN text), for the ids in `ids`."
  [source-text ids]
  (into {} (for [{:keys [id text]} (edn/read-string source-text) :when (ids id)] [id text])))

(defn- guidance-changed
  "Among the placeholder rules this workspace's overlay fills, the ids whose
  shipped text differs between the KIT at `made-at` and the KIT now."
  [ws kit-dir made-at]
  (let [overlay-path (:workspace/rules-overlay ws)
        filled (when (and overlay-path (fs/exists? overlay-path))
                 (into #{} (map :id) (edn/read-string (slurp overlay-path))))
        then (when (seq filled) (git kit-dir "show" (str made-at ":harness/resources/" rules/resource-name)))]
    (if (and then (seq filled))
      (let [before (placeholder-texts then filled)
            now (into {} (for [{:keys [id text]} (rules/shipped) :when (filled id)] [id text]))]
        (vec (sort (for [[id text] now :when (and (contains? before id) (not= text (before id)))] id))))
      [])))

(defn- mirrors-drifted
  "The workspace's rule mirrors that exist and do not match the source now."
  [ws]
  (vec (for [m (:workspace/rule-mirrors ws)
             :when (fs/exists? m)
             :let [{:keys [changed?]} (try (rules/sync! m :check? true)
                                           (catch clojure.lang.ExceptionInfo _ {:changed? true}))]
             :when changed?]
         m)))

(defn- profile-missing
  "The roles the KIT's profile names that the project's profile lacks."
  [profile-path]
  (when profile-path
    (let [have (set (keys (:roles (edn/read-string (slurp profile-path)))))]
      (vec (remove have profile/roles)))))

(defn facts
  "Everything `expectations` needs, read from the workspace `ws` and asked of
  git in `kit-dir`. The commit comparisons need `made-at` to be in this clone's
  history; when it is not, `:behind` is nil and the two change lists are empty."
  [ws kit-dir]
  (let [raw (raw-workspace ws)
        made-at (:workspace/kit-commit raw)
        head (git kit-dir "rev-parse" "HEAD")
        known? (and made-at (some? (git kit-dir "merge-base" "--is-ancestor" made-at "HEAD")))
        behind (when known? (some-> (git kit-dir "rev-list" "--count" (str made-at "..HEAD")) parse-long))
        profile-path (profile/plan-profile ws)]
    {:dir (:workspace/dir ws)
     :kit (str kit-dir)
     :keys-present (vec (keys raw))
     :made-at made-at
     :head head
     :behind behind
     :template-changed (if known?
                         (vec (remove str/blank? (str/split-lines (or (git kit-dir "diff" "--name-only" made-at "HEAD" "--" "plan-template/") ""))))
                         [])
     :guidance-changed (if known? (guidance-changed ws kit-dir made-at) [])
     :mirrors-drifted (mirrors-drifted ws)
     :profile profile-path
     :profile-missing (profile-missing profile-path)}))

(defn report
  "The report for the workspace a command runs in, or nil outside one. `kit-dir`
  is the KIT's clone - the folder above `harness/`."
  [ws kit-dir]
  (when ws (render (facts ws kit-dir))))

(defn doctor-main
  "`bb doctor`: the doctor's table and verdicts (`doctor/main`), then - in a
  workspace, and not with `--edn` - this report, and exit with the doctor's
  code. The composition lives here because the doctor cannot require this
  namespace: it requires the profile, which requires the doctor."
  [& args]
  (let [code (apply doctor/main args)]
    (when-not (some #{"--edn"} args)
      (when-let [ws (workspace/current-or-exit args)]
        (println)
        (println (report ws (str (fs/parent (fs/normalize (fs/absolutize "."))))))))
    (System/exit code)))
