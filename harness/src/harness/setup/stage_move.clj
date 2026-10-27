(ns harness.setup.stage-move
  "A workspace made before the folder per stage (`harness.setup.stages`) brought into it: the
  step `bb upgrade` phase 2 runs before the build documents are patched.

  WHAT MOVES. Every flat entry of the build's `docs/stages/` whose name begins `stage-N-` and is
  not a template - the stage plan, the blueprint, the gates record, the security pack's records,
  a project's own records (an interactions check's, registered in `stage-names.edn`), a security review's folder, whatever else a project put there
  under its stage's name - into `docs/stages/stage-N/`; and every `reviews/stage-N/` into
  `docs/stages/stage-N/reviews/`. The project plan review, `reviews/plan-review.edn`, is no
  stage's and stays. Each entry by `git mv` when git tracks it, so its history follows; an
  untracked one moved on disk.

  WHAT IS REWRITTEN, in the same commit, so the history reads as a rename with a small edit.
  Paths in the stage documents that moved (their own `.md` files, not a security review's
  folder, whose files quote the application) and in the build's other documents (`docs/**/*.md`
  outside the stage folders and the templates), never inside a fenced block - a blueprint's
  packets are fences, and a path in one is the application's, for the Coder:
    - a moved document's `../` runs gain one `../`, at the run's start (`../../../x` stays three
      folders up to the same place: four);
    - a reading cited at its old place, `reviews/stage-K/` (with the `../` that reached it, or
      written from the build's root): the moved document's own `reviews/`, another stage's
      folder's, or from `docs/`, `stages/stage-K/reviews/`;
    - another stage's file named bare in a moved document, `stage-K-x.md`: `../stage-K/stage-K-x.md`;
    - `stages/stage-K-x` in any other document: `stages/stage-K/stage-K-x`.

  DONE WHEN no flat `stage-N-` entry and no `reviews/stage-N/` is left: a workspace made by a
  KIT with the folders, or a re-run, has nothing to move and says so. A document with
  uncommitted edits among those it would touch stops it by name before anything is written.

  THEN A STAGE PLAN BY ITS ONE NAME (`rename!`): a stage plan kept under another name than
  `stage-N-plan.md` - stage 0's was `stage-0-<name>.md`, and the lookup before took any - renamed
  to it, and the old name rewritten in the build's documents, the blueprint's `**Input:**` header
  among them."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [harness.setup.stages :as stages]))

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn flat-entry-id
  "The stage a flat entry of `docs/stages/` belongs to, by its name - `stage-1-plan.md` and
  `stage-1-security-review` are 1 - or nil for a template, a stage folder, or any other name."
  [nm]
  (when-not (str/includes? nm "-template.")
    (some-> (re-find #"^stage-(\d+)-." nm) second parse-long)))

(defn- outside-fences
  "`text` with `f` applied to every line outside a fenced block; the fence lines kept as they are."
  [text f]
  (let [lines (str/split-lines text)
        out (loop [[l & more] lines fence? false acc []]
              (if (nil? l)
                acc
                (let [toggle? (str/starts-with? (str/triml l) "```")]
                  (recur more (if toggle? (not fence?) fence?)
                         (conj acc (if (or fence? toggle?) l (f l)))))))]
    (str (str/join "\n" out) (when (str/ends-with? text "\n") "\n"))))

(defn- template? [nm] (str/includes? nm "-template."))

(defn- stages-paths
  "`l` with each `stages/stage-K-x` - a path written from `docs/` or above - put through the
  stage's folder, `stages/stage-K/stage-K-x`; a template's name as it was."
  [l]
  (str/replace l #"(?<![\w-])stages/(stage-(\d+)-[\w.-]+)"
               (fn [[m nm k]] (if (template? nm) m (str "stages/stage-" k "/" nm)))))

(defn rewrite-moved
  "The text of a stage document that moved from `docs/stages/` into its folder, stage `own`.
  `resolves?` says whether a relative path (its anchor dropped) named something from the
  document's old place: one that did not is a quotation - a link copied from the application's
  content, say - and is left as written. Every path resolves when it is not given."
  ([text own] (rewrite-moved text own (constantly true)))
  ([text own resolves?]
   (outside-fences
    text
    (fn [l]
      (-> l
          ;; one folder deeper: one more `../` where each run of them begins, for a path that led somewhere
          (str/replace #"(?<![\w./])((?:\.\./)+)([\w.#/-]*)"
                       (fn [[m ups rest]]
                         (if (resolves? (str ups (str/replace (first (str/split rest #"#")) #"[.,;:]+$" "")))
                           (str "../" m)
                           m)))
          ;; a reading at its old place - reached by `../` (now three) or written from the build's root
          (str/replace #"(?<![\w./-])(?:\.\./\.\./\.\./)?reviews/stage-(\d+)/"
                       (fn [[_ k]] (if (= own (parse-long k)) "reviews/" (str "../stage-" k "/reviews/"))))
          ;; a path written from `docs/` or above, inside a stage document
          stages-paths
          ;; another stage's file, named bare when the two were neighbours
          (str/replace #"(?<![\w./-])(stage-(\d+)-[\w.-]*?(?:\.md|\.edn|security-review))(?![\w-])"
                       (fn [[m nm k]] (if (or (= own (parse-long k)) (template? nm)) m (str "../stage-" k "/" nm)))))))))

(defn rewrite-doc
  "The text of one of the build's other documents, under `docs/` and outside the stage folders,
  at any depth: a reading reached by `../` runs ONE `../` shorter to its stage folder under
  `docs/`, since the run climbed to the build's root - `../reviews/stage-1/` from `docs/` is
  `stages/stage-1/reviews/`, `../../reviews/stage-1/` from `docs/api-notes/` is
  `../stages/stage-1/reviews/`; one written from the build's root, with none, is put as from `docs/`."
  [text]
  (outside-fences
   text
   (fn [l]
     (-> l
         stages-paths
         (str/replace #"(?<![\w./-])((?:\.\./)*)reviews/stage-(\d+)/"
                      (fn [[_ ups k]]
                        (str (subs ups (min 3 (count ups))) "stages/stage-" k "/reviews/")))))))

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

(defn plan
  "What the move would do in the build at `build`: `{:moves [[from to] …] :ids #{…}}`, paths
  relative to the build; empty when nothing is flat."
  [build]
  (let [stages-dir (stages/stages-dir build)
        flat (for [e (when (fs/directory? stages-dir) (sort (map str (fs/list-dir stages-dir))))
                   :let [nm (str (fs/file-name e)) id (flat-entry-id nm)]
                   :when id]
               [(str "docs/stages/" nm) (str "docs/stages/stage-" id "/" nm) id])
        reviews (for [d (when (fs/directory? (fs/path build "reviews")) (sort (map str (fs/list-dir (fs/path build "reviews")))))
                      :let [id (stages/folder-id (fs/file-name d))]
                      :when (and id (fs/directory? d))]
                  [(str "reviews/stage-" id) (str "docs/stages/stage-" id "/reviews") id])]
    {:moves (mapv (fn [[a b]] [a b]) (concat flat reviews))
     :ids (into (sorted-set) (map #(nth % 2)) (concat flat reviews))}))

(defn- tracked? [build rel]
  (not (str/blank? (:out (git build "ls-files" "--" rel)))))

(defn- other-docs
  "The build's documents outside the stage folders and the templates, relative to the build."
  [build]
  (let [docs (fs/path build "docs")]
    (->> (fs/glob docs "**.md")
         (map #(str (fs/relativize build %)))
         (remove #(re-find #"^docs/stages/" %))
         sort)))

(defn move!
  "The move in the build at `build`, committed there with `message`. Returns `{:moved n
  :rewritten [rel …] :detail s}`; throws ex-info, writing nothing, when a path it would touch has
  uncommitted edits."
  [build message]
  (let [{:keys [moves ids]} (plan build)]
    (if (empty? moves)
      {:moved 0 :rewritten [] :detail "nothing flat in docs/stages/ and no reviews/stage-N/: nothing to move"}
      (let [docs (other-docs build)
            dirty (->> (concat (map first moves) docs)
                       (filter #(not (str/blank? (:out (git build "status" "--porcelain" "--untracked-files=no" "--" %))))))]
        (when (seq dirty)
          (throw (ex-info (str (str/join ", " dirty) " in " build " " (if (next dirty) "have" "has")
                               " uncommitted edits: commit or set them aside, then `bb upgrade --after-pull` again - nothing was moved")
                          {:upgrade/error :uncommitted :paths (vec dirty)})))
        (doseq [id ids] (fs/create-dirs (stages/stage-dir build id)))
        (doseq [[from to] moves]
          (when (fs/exists? (fs/path build to))
            (throw (ex-info (str to " exists already in " build ": move " from " by hand, then `bb upgrade --after-pull` again")
                            {:upgrade/error :exists :path to}))))
        ;; TRACKED BY GIT, MOVED BY GIT, so the history follows; an untracked entry is moved on disk
        ;; and stays untracked - the move is not where a person's choice to leave it out is undone
        ;; THE NEW TEXTS READ BEFORE ANYTHING MOVES, so a path is asked whether it led somewhere
        ;; from the old place, in the old layout
        (let [tracked-moves (vec (filter (fn [[from]] (tracked? build from)) moves))
              old-dir (stages/stages-dir build)
              resolves? #(fs/exists? (fs/normalize (fs/path old-dir %)))
              texts (vec (for [[rel from f] (concat (for [[from to] moves
                                                          :when (str/ends-with? to ".md")
                                                          :let [id (stages/folder-id (fs/file-name (fs/parent (fs/path build to))))]]
                                                      [to from #(rewrite-moved % id resolves?)])
                                                    (for [d docs] [d d rewrite-doc]))
                               :let [before (slurp (str (fs/path build from)))
                                     after (f before)]
                               :when (not= before after)]
                           [rel after]))]
          (doseq [[from to] moves]
            (if (some #{[from to]} tracked-moves)
              (git! build "mv" from to)
              (fs/move (fs/path build from) (fs/path build to))))
          (let [rewritten (vec (for [[rel after] texts]
                                 (do (spit (str (fs/path build rel)) after) rel)))
                ;; ONLY THESE PATHS, and only what git knows: a commit without a pathspec would sweep up
                ;; whatever the person had staged; the old paths carry the renames' other side
                touched (distinct (concat (mapcat identity tracked-moves) (filter #(tracked? build %) rewritten)))]
            (when (seq touched)
              (apply git! build "commit" "-q" "-m" message "--" touched))
            {:moved (count moves) :rewritten rewritten
             :detail (str (count moves) " entr" (if (= 1 (count moves)) "y" "ies") " moved into "
                          (str/join ", " (map #(str "docs/stages/stage-" % "/") ids))
                          "; paths rewritten in " (count rewritten) " document" (when (not= 1 (count rewritten)) "s")
                          "; committed in the build")}))))))

;; ---------------------------------------------------------------------------
;; a stage plan by its one name, `stage-N-plan.md`
;; ---------------------------------------------------------------------------

(defn- kind-line? [text] (boolean (re-find #"(?m)^\*\*Kind:\*\*" text)))

(defn plan-renames
  "The stage plans the build at `build` keeps under another name: `{:renames [[from to] …] :unsure
  [[id [name …]] …]}`, paths relative to the build. In a stage folder with no `stage-N-plan.md`,
  the one top-level Markdown file with a `**Kind:**` line - what a plan written from a template
  carries; never the blueprint, a template or a brief - is the plan to rename. A folder with
  Markdown and not exactly one such file is `:unsure`, its files named, never guessed: a stage
  pulled with only a brief has no plan to find."
  [build]
  (let [found (for [[id dir] (stages/folders (stages/stages-dir build))
                    :when (not (stages/plan-file dir))
                    :let [mds (->> (fs/list-dir dir)
                                   (filter fs/regular-file?)
                                   (map (comp str fs/file-name))
                                   (filter #(and (str/ends-with? % ".md") (not (str/ends-with? % "-blueprint.md")) (not (template? %))))
                                   sort vec)
                          kinded (filterv #(kind-line? (slurp (str (fs/path dir %)))) mds)
                          in-dir #(str "docs/stages/" (stages/folder-name id) "/" %)]
                    :when (seq mds)]
                (if (= 1 (count kinded))
                  {:rename [(in-dir (first kinded)) (in-dir (str (stages/folder-name id) "-plan.md"))]}
                  {:unsure [id mds]}))]
    {:renames (vec (keep :rename found)) :unsure (vec (keep :unsure found))}))

(defn rewrite-name
  "`text` with the file name `from` written as `to` wherever it stands as a name - bare or at a
  path's end, never inside a longer name - outside fenced blocks, where a blueprint's packets are."
  [text from to]
  (outside-fences text #(str/replace % (re-pattern (str "(?<![\\w.-])" (java.util.regex.Pattern/quote from) "(?![\\w-]|\\.\\w)")) to)))

(defn- build-docs-md
  "The build's Markdown under `docs/`, relative to the build, sorted - a template and a security
  review's folder, whose files quote the application, aside."
  [build]
  (->> (fs/glob (fs/path build "docs") "**.md")
       (map #(str (fs/relativize build %)))
       (remove #(or (template? %) (re-find #"-security-review/" %)))
       sort))

(defn- unsure-text [unsure]
  (str/join "; " (for [[id mds] unsure]
                   (str "docs/stages/" (stages/folder-name id) "/ has no " (stages/folder-name id) "-plan.md and "
                        (str/join ", " mds) " - rename its plan by hand if one of them is it"))))

(defn rename!
  "The renames of `plan-renames` in the build at `build`, committed there with `message`: each by
  `git mv` when git tracks it, the old name rewritten in the build's Markdown. Returns `{:renamed n
  :rewritten [rel …] :unsure […] :detail s}`; throws ex-info, writing nothing, when a path it would
  touch has uncommitted edits. A folder that has `stage-N-plan.md` is never asked."
  [build message]
  (let [{:keys [renames unsure]} (plan-renames build)
        said (when (seq unsure) (str "; " (unsure-text unsure)))]
    (if (empty? renames)
      {:renamed 0 :rewritten [] :unsure unsure :detail (str "every stage plan is stage-N-plan.md" said)}
      (let [names (mapv (fn [[a b]] [(str (fs/file-name a)) (str (fs/file-name b))]) renames)
            ;; THE NEW TEXTS READ BEFORE ANYTHING MOVES, under the names they have now: `[old new after]`
            texts (vec (for [d (build-docs-md build)
                             :let [before (slurp (str (fs/path build d)))
                                   after (reduce (fn [t [a b]] (rewrite-name t a b)) before names)]
                             :when (not= before after)]
                         [d (or (some (fn [[a b]] (when (= a d) b)) renames) d) after]))
            ;; ONLY WHAT IT WOULD TOUCH: a plan it renames, a document whose text it changes
            dirty (->> (distinct (concat (map first renames) (map first texts)))
                       (filter #(not (str/blank? (:out (git build "status" "--porcelain" "--untracked-files=no" "--" %))))))]
        (when (seq dirty)
          (throw (ex-info (str (str/join ", " dirty) " in " build " " (if (next dirty) "have" "has")
                               " uncommitted edits: commit or set them aside, then `bb upgrade --after-pull` again - nothing was renamed")
                          {:upgrade/error :uncommitted :paths (vec dirty)})))
        (let [tracked-renames (vec (filter (fn [[from]] (tracked? build from)) renames))]
          (doseq [[from to] renames]
            (if (some #{[from to]} tracked-renames)
              (git! build "mv" from to)
              (fs/move (fs/path build from) (fs/path build to))))
          (let [rewritten (vec (for [[_ rel after] texts] (do (spit (str (fs/path build rel)) after) rel)))
                touched (distinct (concat (mapcat identity tracked-renames) (filter #(tracked? build %) rewritten)))]
            (when (seq touched)
              (apply git! build "commit" "-q" "-m" message "--" touched))
            {:renamed (count renames) :rewritten rewritten :unsure unsure
             :detail (str (str/join ", " (map (fn [[a b]] (str a " renamed " (fs/file-name b))) renames))
                          "; the old name rewritten in " (count rewritten) " document" (when (not= 1 (count rewritten)) "s")
                          "; committed in the build" said)}))))))
