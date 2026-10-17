(ns harness.setup.stage-move
  "A workspace made before the folder per stage (`harness.setup.stages`) brought into it: the
  step `bb upgrade` phase 2 runs before the build documents are patched.

  WHAT MOVES. Every flat entry of the build's `docs/stages/` whose name begins `stage-N-` and is
  not a template - the stage plan, the blueprint, the gates record, the security pack's records,
  the browser pack's interactions, a security review's folder, whatever else a project put there
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
  uncommitted edits among those it would touch stops it by name before anything is written."
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
          ;; another stage's file, named bare when the two were neighbours
          (str/replace #"(?<![\w./-])(stage-(\d+)-[\w.-]*?(?:\.md|\.edn|security-review))(?![\w-])"
                       (fn [[m nm k]] (if (or (= own (parse-long k)) (template? nm)) m (str "../stage-" k "/" nm)))))))))

(defn rewrite-doc
  "The text of one of the build's other documents, under `docs/` and outside the stage folders."
  [text]
  (outside-fences
   text
   (fn [l]
     (-> l
         (str/replace #"(?<![\w-])stages/(stage-(\d+)-[\w.-]+)"
                      (fn [[m nm k]] (if (template? nm) m (str "stages/stage-" k "/" nm))))
         (str/replace #"(?<![\w./-])(?:\.\./)?reviews/stage-(\d+)/"
                      (fn [[_ k]] (str "stages/stage-" k "/reviews/")))))))

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
