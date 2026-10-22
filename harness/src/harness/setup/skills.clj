(ns harness.setup.skills
  "The KIT's fifth part, `skills/`, and where it is rendered to - a workspace:
  `bb skills-sync [--check]`.

  A skill is one conversational step of the workflow - scoping, the plan, a
  stage's plan, a stage's end - as Claude Code loads it: `.claude/skills/<name>/
  SKILL.md`. The source is `<kit>/skills/<name>/SKILL.md`; what Claude Code
  reads is a RENDERING of it in a workspace's `.claude/skills/`, written by `bb
  init` and updated by this command after an upgrade (`bb upgrade` runs it), each copy saying it is
  generated and from which KIT commit - held to the source the way the rule
  mirror is held to the rule source. The clone carries no rendering: a project's
  sessions open in the workspace, and scoping is the first of them.

  A rendering is the source with one generated note after its frontmatter;
  `--check` compares everything but the note's commit. A folder under the target that is not a
  shipped skill is a project's own and is never touched - unless it carries the note: then it is
  a rendering of a skill the KIT has since renamed or dropped (`stale`), which the sync removes
  (`prune!`) and `--check` fails on, so an upgraded workspace does not keep the old name beside the
  new.

  THE METHOD IS THE SOURCE of a skill's substance, and a skill cites the
  headings it reads in its *Reads* table. `citation-problems` holds every cited
  heading to the file it names, in the gates: the method can be renamed, but
  not out from under a skill.

  The pure parts are `citations`, `split-frontmatter`, `render`, `normalize`
  and `generated?`; `sync!`, `prune!` and `-main` are the commands."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [harness.contained-path :as contained-path]
   [harness.setup.workspace :as workspace]))

(def source-dir "skills")
(def target-dir ".claude/skills")
(def file-name "SKILL.md")

(defn shipped
  "The skills the KIT at `kit-dir` ships: every folder under `skills/` with a
  `SKILL.md`, sorted."
  [kit-dir]
  (let [d (fs/path kit-dir source-dir)]
    (vec (sort (for [f (when (fs/directory? d) (fs/list-dir d))
                     :when (fs/exists? (fs/path f file-name))]
                 (fs/file-name f))))))

(defn source-path [kit-dir nm] (str (fs/path kit-dir source-dir nm file-name)))

(defn sources
  "`[[name source-text] …]` for every shipped skill, sorted - what `bb init`'s
  layout renders, read once here so the layout stays data over its request."
  [kit-dir]
  (vec (for [nm (shipped kit-dir)] [nm (slurp (source-path kit-dir nm))])))

;; ---------------------------------------------------------------------------
;; citations: the headings a skill reads, held to the file
;; ---------------------------------------------------------------------------

(defn- cells [line]
  (mapv str/trim (str/split (str/replace (str/trim line) #"^\||\|$" "") #"\|" -1)))

(defn- table-line? [line] (str/starts-with? (str/trim line) "|"))

(defn citations
  "`[[file heading] …]` from every table of `text` whose header is `File |
  Heading`: the skill's *Reads* table, backticks stripped."
  [text]
  (->> (str/split-lines text)
       (partition-by table-line?)
       (filter #(table-line? (first %)))
       (mapcat (fn [[header & rows]]
                 (when (= ["File" "Heading"] (cells header))
                   (for [r rows
                         :let [[f h] (map #(str/replace % #"^`|`$" "") (cells r))]
                         :when (and (seq f) (seq h) (not (re-matches #"-+" f)))]
                     [f h]))))
       vec))

(defn heading?
  "Does `text` carry `heading` as a Markdown heading line, at any level?"
  [text heading]
  (boolean (some #(re-matches (re-pattern (str "#{1,6} " (java.util.regex.Pattern/quote heading) "\\s*")) %)
                 (str/split-lines text))))

(defn citation-problems
  "The citations of the skill `nm` (its source `text`) that do not hold in the
  KIT at `kit-dir`: a file that is not there, or a heading the file does not
  carry - one sentence each."
  [kit-dir nm text]
  (vec (for [[f h] (citations text)
             :let [path (fs/path kit-dir f)
                   problem (cond
                             (not (fs/exists? path)) (str "cites " f ", which is not in the KIT")
                             (not (heading? (slurp (str path)) h)) (str "cites the heading \"" h "\" of " f
                                                                        ", which the file no longer carries"))]
             :when problem]
         (str "skills/" nm "/" file-name " " problem))))

;; ---------------------------------------------------------------------------
;; rendering: the source with a generated note after its frontmatter
;; ---------------------------------------------------------------------------

(defn split-frontmatter
  "`[frontmatter body]`: the leading `---` block, closing fence included and a
  newline after it, and the rest. A text with no frontmatter is `[\"\" text]`."
  [text]
  (if-let [[_ fm body] (re-find #"(?s)^(---\n.*?\n---\n)(.*)$" text)]
    [fm body]
    ["" text]))

(def ^:private note-re #"(?m)^<!-- GENERATED by bb skills-sync .*? -->\n")

(defn generated-note
  "The one line a rendering adds: where it came from, that it is not edited
  here, and the KIT commit - the clone's own HEAD when `commit` is nil."
  [nm commit]
  (str "<!-- GENERATED by bb skills-sync from the KIT's skills/" nm "/" file-name
       " - edit it there, never here. KIT commit: " (or commit "HEAD, this clone's own") " -->\n"))

(defn render
  "The rendering of the skill `nm` from its `source-text`, at `commit`: the
  frontmatter, the note, the body as written."
  [nm source-text commit]
  (let [[fm body] (split-frontmatter source-text)]
    (str fm (generated-note nm commit) body)))

(defn generated? [text] (boolean (re-find note-re text)))

(defn normalize
  "`text` without its generated note, for a comparison that ignores the
  commit the note names."
  [text]
  (str/replace text note-re ""))

(defn current?
  "Is `existing` the rendering of `source-text`, whatever commit its note names?"
  [existing source-text]
  (and (generated? existing)
       (= (normalize existing) (normalize (render "x" source-text nil)))))

;; ---------------------------------------------------------------------------
;; the sync
;; ---------------------------------------------------------------------------

(defn git-head
  "The KIT's HEAD commit at `kit-dir`, or nil when git cannot say."
  [kit-dir]
  (let [{:keys [exit out]} (p/shell {:dir (str kit-dir) :out :string :err :string :continue true}
                                    "git" "rev-parse" "HEAD")]
    (when (zero? exit) (str/trim out))))

(defn sync!
  "Every shipped skill of `kit-dir` rendered under `target-root/.claude/skills/`
  - written, or with `:check? true` only compared. `:commit` is what the note
  names (nil: the clone's own HEAD, for the clone's own copy). Returns one map
  per skill: `:name`, `:path`, `:existed?`, `:changed?` (not current before
  the call). Folders under the target that are not shipped skills are left
  alone.

  NEVER THROUGH A LINK. A link planted under `.claude/skills/` - a folder the Architect's session can
  write - had made the sync overwrite a file outside the workspace, as the person. A skill whose path
  is not inside the workspace, or is a link, is not written: `:refused` says why."
  [kit-dir target-root & {:keys [check? commit]}]
  (vec (for [nm (shipped kit-dir)
             :let [source (slurp (source-path kit-dir nm))
                   rel (str target-dir "/" nm "/" file-name)
                   path (fs/path target-root rel)
                   refused (when (or (nil? (contained-path/inside target-root rel)) (fs/sym-link? path))
                             (str rel " is a link or leads outside the workspace - not written through"))
                   existed? (and (not refused) (fs/exists? path))
                   changed? (and (not refused) (not (and existed? (current? (slurp (str path)) source))))]]
         (do (when (and changed? (not check?))
               (fs/create-dirs (fs/parent path))
               (spit (str path) (render nm source commit)))
             (cond-> {:name nm :path (str path) :existed? existed? :changed? changed?}
               refused (assoc :refused refused))))))

(defn stale
  "The renderings under `target-root/.claude/skills/` of a skill the KIT at
  `kit-dir` no longer ships - renamed or dropped since they were rendered: a
  folder whose `SKILL.md` carries the generated note, under a name not in
  `shipped`. A folder without the note is a project's own and is never one.
  Sorted names."
  [kit-dir target-root]
  (let [d (fs/path target-root target-dir)
        ships (set (shipped kit-dir))]
    (vec (sort (for [f (when (fs/directory? d) (fs/list-dir d))
                     :let [nm (str (fs/file-name f))
                           p (fs/path f file-name)]
                     ;; never read through a link: a linked folder or file is not a rendering this wrote
                     :when (and (not (ships nm)) (not (fs/sym-link? f)) (fs/regular-file? p {:nofollow-links true})
                                (generated? (slurp (str p))))]
                 nm)))))

(defn prune!
  "Remove each `stale` rendering: its `SKILL.md`, then its folder when that
  leaves it empty. Returns one map per name: `:name`, `:path` (the folder),
  `:kept` (what else the folder held, left in place - never written by this
  command, so never removed by it)."
  [kit-dir target-root]
  ;; a regular file inside the workspace only: a link there had made the prune delete outside it
  (vec (for [nm (stale kit-dir target-root)
             :let [d (fs/path target-root target-dir nm)
                   f (fs/path d file-name)]
             :when (and (contained-path/inside target-root (str target-dir "/" nm "/" file-name))
                        (not (fs/sym-link? d)) (not (fs/sym-link? f))
                        (fs/regular-file? f {:nofollow-links true}))]
         (do (fs/delete f)
             (let [kept (mapv #(str (fs/file-name %)) (fs/list-dir d))]
               (when (empty? kept) (fs/delete d))
               {:name nm :path (str d) :kept kept})))))

(defn kit-dir-of
  "The KIT's clone: the workspace's, or the parent of `harness/`, where every
  `bb` task runs."
  [ws]
  (or (:workspace/kit ws) (str (fs/parent (fs/normalize (fs/absolutize "."))))))

(defn -main
  "bb skills-sync [--check] [--workspace <dir>]

  First the citations of every shipped skill, held to the files they name -
  a failure in both modes. Then the workspace's `.claude/skills/` when this
  runs in one (or `--workspace` / `KIT_WORKSPACE` names one): written, or with
  `--check` compared, drift being exit 1. Outside a workspace - the KIT's own
  gates - the citations are all there is."
  [& args]
  (let [ws (workspace/current-or-exit args)
        {:keys [args]} (workspace/split-args args)
        check? (boolean (some #{"--check"} args))
        kit-dir (kit-dir-of ws)
        problems (vec (mapcat #(citation-problems kit-dir % (slurp (source-path kit-dir %))) (shipped kit-dir)))]
    (when (seq problems)
      (doseq [l problems] (println (str "skills: " l)))
      (println "skills: a skill cites a heading its file no longer carries - rename it in the skill, or put the heading back")
      (System/exit 1))
    (let [targets (if (:workspace/dir ws) [[(:workspace/dir ws) (git-head kit-dir) "the workspace's"]] [])
          results (vec (for [[root commit label] targets
                             r (sync! kit-dir root :check? check? :commit commit)]
                         (assoc r :label label)))
          drifted (filter :changed? results)
          old (vec (for [[root _ label] targets
                         nm (stale kit-dir root)]
                     {:name nm :path (str (fs/path root target-dir nm)) :label label}))]
      (when (empty? targets)
        (println (str "skills: every citation holds; no workspace here, so nothing is rendered")))
      (doseq [{:keys [path changed? existed? label refused]} results]
        (println (str "skills " (cond refused "refused: "
                                      (and check? changed? (not existed?)) "missing: "
                                      (and check? changed?) "drift: "
                                      changed? "synced: "
                                      :else "in sync: ")
                      path " (" label ")" (when refused (str " - " refused)))))
      (if check?
        (doseq [{:keys [path label]} old]
          (println (str "skills stale: " path " (" label ") - a rendering of a skill this KIT no longer ships")))
        (doseq [[root _ label] targets
                {:keys [path kept]} (prune! kit-dir root)]
          (println (str "skills removed: " path " (" label ") - a rendering of a skill this KIT no longer ships"
                        (when (seq kept) (str "; kept beside it, not written here: " (str/join ", " kept)))))))
      (when (and check? (or (seq drifted) (seq old)))
        (println "skills: a rendering does not match skills/ - run `bb skills-sync` from the workspace folder")
        (System/exit 1))
      ;; A REFUSAL IS NEVER "IN SYNC": the rendering was not written or compared, in either mode
      (when (some :refused results)
        (println "skills: a rendering is a link or leads outside the workspace - remove it, then `bb skills-sync`")
        (System/exit 1)))))
