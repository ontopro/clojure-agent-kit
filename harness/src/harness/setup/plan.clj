(ns harness.setup.plan
  "What the harness knows about a plan's documents: the template the KIT ships
  (`plan-template/`, copied into `<name>-build/docs/` by `bb init`), the
  marks in it that a filled plan must no longer carry, and the check that
  reads a filled plan before Foundation - `bb plan-check`.

  THE TEMPLATE IS HALF-WRITTEN ON PURPOSE. Two of its documents state what
  adopting the KIT fixed and leave angle brackets only where a project decides;
  a bracket left standing reaches whoever reads the plan next as literal text -
  the failure the rule source's placeholders taught, one level up. This
  namespace is where a check over a plan reads from: `placeholders` is the
  definition of a mark, and `placeholder-counts` over the shipped template is
  the fixture such a check is written against.

  THE CHECK IS THE GATE, and it is cheap: no mark left in the governing
  documents, none of the template's instructions left standing, every
  requirement citing an observation of `source.md` or marked as the
  Architect's inference, the overlay's placeholders filled, and the given
  parts intact - the KIT's rules unedited, the template's layers not
  loosened, the gate keys in the KIT's order. The model's reading of the plan
  (`harness.setup.plan-review`) is not a gate: it gives the Architect a list,
  and this check is what `start` holds a workspace to, once, before its first
  dispatch (`inputs-hash` is how once is known).

  THE CITATIONS ARE HELD IN TWO DIRECTIONS. A requirement rests on the source
  or on the Architect, and the row says which: `S-n.m` in its Source column,
  or `(inferred)`. Neither is a failure, by name. The other direction is a
  list, not a failure: the observations of `source.md` that no governing
  document cites are the backlog nobody decided on, printed for the stage's
  end to revise the scope lists against; and the inferred requirements are
  printed for the person, who is shown them at the stage's approval."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [harness.gates.boundary :as boundary]
   [harness.gates.run :as gates]
   [harness.rules :as rules]
   [harness.setup.template :as template]
   [harness.setup.workspace :as workspace]))

(def known
  "Marks that are not a project's to fill: the workspace knows the project's
  name (`bb init` was given it) and the KIT's own path, and the template uses
  both as path tokens - `<name>-build/rules.edn`, `<kit>/harness/bb.edn`."
  #{"<name>" "<kit>"})

(defn- strip-fences
  "`text` with every fenced code block removed: a `<port>` in a command's
  usage line is documentation, not a decision."
  [text]
  (str/replace text #"(?s)```.*?```" ""))

(def mark
  "An `<angle bracket>` mark: it may wrap across lines, as prose does, but not
  across a blank line."
  #"<[^<>\n]+(?:\n[^<>\n]+)*>")

(defn placeholders
  "Every `<angle bracket>` mark in `text`, in order, that a project is meant
  to replace: outside fenced code blocks, not an autolink (`<http…>`), not an
  HTML comment (`<!-- … -->`), not one of `known`. Inline code counts - the
  seams table's `<Store>` is a decision. A wrapped mark is returned on one
  line."
  [text]
  (->> (re-seq mark (strip-fences text))
       (map #(str/replace % #"\s*\n\s*" " "))
       (remove #(or (str/starts-with? % "<!")
                    (str/starts-with? % "<http")
                    (known %)))
       vec))

(defn documents
  "The template's documents, relative to `dir`, sorted."
  [dir]
  (->> (fs/glob dir "**")
       (filter fs/regular-file?)
       (map #(str (fs/relativize dir %)))
       sort
       vec))

(defn placeholder-counts
  "Relative path -> the count of `placeholders` in it, for every document
  under `dir`. Over the shipped template, the fixture a plan check is held
  to; over a filled plan, what remains."
  [dir]
  (into (sorted-map)
        (for [rel (documents dir)]
          [rel (count (placeholders (slurp (str (fs/path dir rel)))))])))

;; ---------------------------------------------------------------------------
;; the check: what a filled plan must no longer carry, and what it must not have changed
;; ---------------------------------------------------------------------------

(defn governing?
  "Is `rel` (a path under `docs/`) a document a check reads? Two kinds are
  not: the template's own `README.md`, which explains the marks and so carries
  one, and the stage templates under `stages/` (`*-template.md`, and the gates
  record's `*-template.edn`), which are copied per stage with their marks
  standing by design. Every other document - the seven written in Phase A,
  the lessons among them, and every stage plan written since - is."
  [rel]
  (not (or (= rel "README.md")
           (and (str/starts-with? rel "stages/")
                (or (str/ends-with? rel "-template.md") (str/ends-with? rel "-template.edn"))))))

(defn governing
  "The governing documents under `docs-dir`, relative, sorted."
  [docs-dir]
  (filterv governing? (documents docs-dir)))

(defn- normalize-quote
  "A blockquote line as compared: the `>` and its spacing gone, whitespace
  collapsed; nil for a line that is not one, or is blank."
  [line]
  (when-let [[_ body] (re-matches #"\s*>\s?(.*)" line)]
    (let [t (str/trim (str/replace body #"\s+" " "))]
      (when-not (str/blank? t) t))))

(defn without-given-part
  "`lines` of a template document with its given part removed: from the
  `# Part 1 — Given` heading to the line before `# Part 2`, as the three-part
  documents (`02`, `03`) are laid out. A given part is not edited, so a
  blockquote in it is prose the project keeps, not an instruction the project
  deletes: the fifth project's `03` §10 kept its five lines and was told they
  were instructions still standing, and lost their `>` to pass the gate. A
  document with no Part 1 is returned whole."
  [lines]
  (let [part-1 (fn [l] (re-find #"^# Part 1\b" l))
        part-2 (fn [l] (re-find #"^# Part 2\b" l))
        i (first (keep-indexed (fn [i l] (when (part-1 l) i)) lines))
        j (when i (first (keep-indexed (fn [k l] (when (and (> k i) (part-2 l)) k)) lines)))]
    (if (and i j)
      (into (subvec (vec lines) 0 i) (subvec (vec lines) j))
      lines)))

(defn instruction-lines
  "Every non-blank blockquote line of every document under `template-dir`,
  normalized - the template's instructions, which a filled plan deletes once
  acted on - less those in a given part (`without-given-part`), which are the
  template's prose and stay. A set, so a line is known wherever it was copied
  to: a stage document is filled from the stage template under another name."
  [template-dir]
  (into #{}
        (comp (mapcat #(without-given-part (str/split-lines (slurp (str (fs/path template-dir %))))))
              (keep normalize-quote))
        (documents template-dir)))

(defn instructions-left
  "The 1-based line numbers of `text` that are still one of the template's
  `instructions` (a set from `instruction-lines`). A project's own blockquote
  - a sentence quoted from its brief - matches nothing and passes."
  [text instructions]
  (vec (keep-indexed (fn [i line]
                       (when (contains? instructions (normalize-quote line)) (inc i)))
                     (str/split-lines text))))

;; ---------------------------------------------------------------------------
;; the citations: a requirement rests on the source or on the Architect, and says which
;; ---------------------------------------------------------------------------

(defn- cells
  "A table line's cells, trimmed, the outer pipes dropped; an empty cell is
  an empty string, the last one included."
  [line]
  (mapv str/trim (str/split (str/replace (str/trim line) #"^\||\|$" "") #"\|" -1)))

(defn- table-line? [line] (str/starts-with? (str/trim line) "|"))

(defn requirement-citations
  "`[[id source] …]` for every row of every table in `text` whose header has
  a Source column and whose first cell is a requirement id (`FR-n`, `NFR-n`):
  the id and what its Source cell says, in the document's order. A table with
  no Source column - the index, the scope lists, the non-functional targets -
  has no citations to read."
  [text]
  (->> (str/split-lines text)
       (partition-by table-line?)
       (filter #(table-line? (first %)))
       (mapcat (fn [[header & rows]]
                 (let [hs (cells header)
                       i (first (keep-indexed (fn [i h] (when (re-matches #"(?i)\**source\**" h) i)) hs))]
                   (when i
                     (for [r rows
                           :let [cs (cells r)]
                           :when (re-matches #"N?FR-\d+" (first cs))]
                       [(first cs) (nth cs i "")])))))
       vec))

(def observation-id
  "An observation of `source.md`, as the template numbers them: `S-n.m`."
  #"S-\d+\.\d+")

(defn inferred?
  "Is `source` (a Source cell) the Architect's inference - `(inferred)`, in
  any dress?"
  [source]
  (boolean (re-find #"(?i)inferred" (str source))))

(defn cited?
  "Does `source` rest on something: an observation, or the Architect?"
  [source]
  (boolean (or (re-find observation-id (str source)) (inferred? source))))

(defn citation-problems
  "The requirements of `text` (the governing document `rel`) that cite no
  observation and are not marked inferred, one sentence each. The template's
  own `S-n.m` is such a row: it names no observation."
  [rel text]
  (vec (for [[id source] (requirement-citations text) :when (not (cited? source))]
         (str "docs/" rel ": " id " cites no observation of source.md (S-n.m in its Source column)"
              " and is not marked (inferred)"))))

(defn observations
  "The observation ids `source-text` defines, in its order: its `**S-n.m**`
  bullets."
  [source-text]
  (vec (distinct (map second (re-seq #"\*\*(S-\d+\.\d+)\*\*" source-text)))))

(defn uncited-observations
  "The observations of `source-text` that none of `other-texts` (every other
  governing document) mentions, in the source's order - the backlog nobody
  decided on, which the stage's end revises the scope lists against."
  [source-text other-texts]
  (let [cited (into #{} (mapcat #(re-seq observation-id %)) other-texts)]
    (filterv (complement cited) (observations source-text))))

(defn inferred-requirements
  "`[[id rel] …]`: the requirements across `docs` (`[[rel text] …]`) marked
  as the Architect's inference - what the person is shown at the stage's
  approval."
  [docs]
  (vec (for [[rel text] docs
             [id source] (requirement-citations text)
             :when (inferred? source)]
         [id rel])))

(defn document-problems
  "What the governing documents under `docs-dir` still carry, as sentences:
  marks (`placeholders`), the template's instructions (`instructions-left`,
  against `template-dir`), and requirements that rest on nothing
  (`citation-problems`)."
  [docs-dir template-dir]
  (let [instructions (instruction-lines template-dir)]
    (vec (mapcat
          (fn [rel]
            (let [text (slurp (str (fs/path docs-dir rel)))
                  marks (placeholders text)
                  left (instructions-left text instructions)]
              (cond-> []
                (seq marks)
                (conj (str "docs/" rel ": " (count marks) " mark" (when (> (count marks) 1) "s")
                           " left to fill - " (str/join ", " (take 3 marks))
                           (when (> (count marks) 3) (str ", … " (- (count marks) 3) " more"))))
                (seq left)
                (conj (str "docs/" rel ": " (count left) " line" (when (> (count left) 1) "s")
                           " of the template's instructions still standing (first at line "
                           (first left) ") - delete each blockquote once it is acted on"))
                :always
                (into (citation-problems rel text)))))
          (governing docs-dir)))))

(defn overlay-problems
  "The overlay's placeholders still standing, one sentence each, over the
  rules the workspace's agents will read; or the overlay's refusal (a KIT
  rule edited, a rule that is not one) as the one sentence it names."
  [ws]
  (try
    (vec (for [{:keys [id placeholder]} (rules/unfilled (rules/project-rules ws))]
           (str "rules.edn: " id " still says " (subs placeholder 0 (min 60 (count placeholder)))
                (when (> (count placeholder) 60) "…"))))
    (catch clojure.lang.ExceptionInfo e
      (if (:rules/error (ex-data e))
        [(str "rules.edn: " (ex-message e))]
        (throw e)))))

(defn- ns-root [sym] (first (str/split (str sym) #"\.")))

(defn layer-problems
  "Where the application's `layers` (its `layers.edn` as data) loosen the
  template's `pinned` (`:layers` of the pin, tails under the root namespace):
  a pinned namespace whose entry is gone, or one that may now require a
  template namespace the pin did not let it. A layer the project ADDS, and a
  pinned namespace requiring one, are the project's - the chosen part - and
  pass. The root namespace is read off the entries, not asked for."
  [layers pinned]
  (let [entries (boundary/layers layers)
        root (->> (keys entries) (map ns-root) frequencies (sort-by val >) ffirst)
        full #(symbol (str root "." %))
        template-nss (into #{} (map full) (keys pinned))]
    (if (nil? root)
      ["layers.edn: no namespace entries, so the template's layers are gone"]
      (vec (for [[tail allowed] (sort-by (comp str key) pinned)
                 :let [nm (full tail)
                       now (get entries nm)
                       may (into (sorted-set) (map full) allowed)]
                 problem (if (nil? now)
                           [(str "layers.edn: the template's " nm " is no longer declared")]
                           (for [req (sort (filter template-nss now)) :when (not (may req))]
                             (str "layers.edn: " nm " may now require " req
                                  ", which the template's layering does not allow")))]
             problem)))))

(defn gate-order-problems
  "Where `gate-seq` (a `:gates` vector of `[key command]`) departs from the
  KIT's keys in the KIT's order: a key missing, or out of order. The keys are
  a contract - triage routes on them - and a project adds a gate after them,
  not between."
  [gate-seq]
  (let [want (mapv first gates/default-gate-seq)
        have (mapv first gate-seq)
        missing (remove (set have) want)
        order (filterv (set want) have)]
    (cond-> []
      (seq missing) (conj (str "loop.edn: gate" (when (> (count missing) 1) "s") " "
                               (str/join ", " missing) " missing from :gates - the KIT's gates are "
                               (str/join ", " want) ", in that order"))
      (and (empty? missing) (not= order want))
      (conj (str "loop.edn: the KIT's gates run " (str/join ", " order) " - they run "
                 (str/join ", " want) ", cheap first; a project's gate comes after them")))))

(defn- read-edn-file [path]
  (edn/read-string (slurp (str path))))

(defn given-problems
  "The given parts of the workspace `ws`, checked: the application's
  `layers.edn` against the pin's (`pin`), and the plan's `loop.edn` gate keys.
  A file that is not there is a sentence too - `bb init` wrote both."
  [ws pin]
  (let [layers (fs/path (:workspace/app ws) boundary/file-name)
        loop-edn (fs/path (:workspace/build ws) "loop.edn")]
    (vec (concat
          (if (fs/exists? layers)
            (layer-problems (read-edn-file layers) (:layers pin))
            [(str "layers.edn: not at " layers " - gate 4 has no ruleset")])
          (if (fs/exists? loop-edn)
            (some-> (read-edn-file loop-edn) :gates gate-order-problems)
            [(str "loop.edn: not at " loop-edn)])))))

(defn problems
  "Everything `bb plan-check` reports, in order, as sentences: the documents,
  then the overlay and the given parts when the plan is in a workspace (`ws`;
  nil outside one, where only the documents can be read). `template-dir` is
  the shipped template, `pin` the template pin the application was generated
  from."
  [plan-dir ws template-dir pin]
  (let [docs (fs/path plan-dir "docs")]
    (if-not (fs/directory? docs)
      [(str "no docs/ under " plan-dir " - `bb init` writes the plan's documents there")]
      (vec (concat (document-problems docs template-dir)
                   (when ws
                     (concat (overlay-problems ws)
                             (given-problems ws pin))))))))

(defn inputs-hash
  "One hash over everything the check reads - the governing documents, the
  overlay, `layers.edn`, `loop.edn` - so `start` can know the plan it checked
  is the plan it has. A file that is not there hashes as absent, not as an
  error: the check itself says which."
  [plan-dir ws]
  (let [docs (fs/path plan-dir "docs")
        files (concat (when (fs/directory? docs) (map #(fs/path docs %) (governing docs)))
                      (when ws [(:workspace/rules-overlay ws)
                                (fs/path (:workspace/app ws) boundary/file-name)
                                (fs/path (:workspace/build ws) "loop.edn")]))]
    (hash (vec (for [f files :when f]
                 [(str f) (when (fs/exists? f) (slurp (str f)))])))))

(defn kit-dir
  "The KIT's clone: the workspace's, or the parent of `harness/`, where every
  `bb` task runs."
  [ws]
  (or (:workspace/kit ws) (str (fs/parent (fs/normalize (fs/absolutize "."))))))

(defn check
  "`problems` for `plan-dir` - the workspace's plan when nil - with the
  template and the pin found from here. Returns
  `{:plan _ :workspace _ :problems [...] :documents n}`."
  [plan-dir ws]
  (let [plan (str (fs/normalize (fs/absolutize (or plan-dir (:workspace/build ws)))))
        docs (fs/path plan "docs")
        texts (when (fs/directory? docs)
                (vec (for [rel (governing docs)] [rel (slurp (str (fs/path docs rel)))])))
        source (some (fn [[rel text]] (when (= rel "source.md") text)) texts)]
    {:plan plan
     :workspace (some-> ws :workspace/dir)
     :documents (count texts)
     :problems (problems plan ws (fs/path (kit-dir ws) "plan-template") (template/pin (template/load-pins)))
     ;; the two lists, not failures: what the person and the stage's end are shown
     :inferred (inferred-requirements texts)
     :uncited (if source
                (uncited-observations source (map second (remove #(= "source.md" (first %)) texts)))
                [])}))

(defn report
  "The check's result as the lines `bb plan-check` prints: the verdict, then
  the inferred requirements for the person and the uncited observations for
  the stage's end, each only when there are any."
  [{:keys [plan problems documents workspace inferred uncited]}]
  (str (if (seq problems)
         (str "plan-check: " (count problems) " thing" (when (> (count problems) 1) "s") " left in " plan "\n"
              (str/join "\n" (map #(str "  - " %) problems)))
         (str "plan-check: " documents " governing document" (when (not= 1 documents) "s")
              (if workspace
                ", the rules overlay, layers.edn and loop.edn - nothing left to fill, the given parts intact"
                " - nothing left to fill (no workspace.edn above the plan, so the overlay and the given parts were not read)")))
       (when (seq inferred)
         (str "\n  inferred: " (count inferred) " requirement" (when (> (count inferred) 1) "s")
              " rest" (when (= 1 (count inferred)) "s") " on the Architect's inference, shown at the stage's approval - "
              (str/join ", " (for [[id rel] inferred] (str id " (docs/" rel ")")))))
       (when (seq uncited)
         (str "\n  uncited: " (count uncited) " observation" (when (> (count uncited) 1) "s")
              " of source.md nothing cites - the backlog nobody decided on, revised at the stage's end: "
              (str/join ", " uncited)))))

(defn -main
  "bb plan-check [<plan-dir>] [--workspace <dir>]

  Reads a filled plan before Foundation and exits 1 with the list of what is
  left. With no plan given, the workspace's (`workspace/current`: the one
  pointed at, or the one `harness/` is in by walking up). A plan given by path
  finds its workspace by walking up from it."
  [& args]
  (let [plan-dir (first (remove #(str/starts-with? % "--") (:args (workspace/split-args args))))
        ws (if plan-dir
             (workspace/find-workspace plan-dir)
             (workspace/current-or-exit args))]
    (when (and (nil? plan-dir) (nil? ws))
      (println "plan-check: no plan given and no workspace.edn at or above here - bb plan-check <plan-dir>")
      (System/exit 2))
    (when (and plan-dir (not (fs/directory? plan-dir)))
      (println (str "plan-check: " plan-dir " is not a folder"))
      (System/exit 2))
    (let [r (check plan-dir ws)]
      (println (report r))
      (when (seq (:problems r)) (System/exit 1)))))
