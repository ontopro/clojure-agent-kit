(ns harness.setup.inventory
  "The harness's namespaces, rendered into its README from the source:
  `bb inventory-sync [--check]`.

  RENDERED, NOT COUNTED. The README opened its inventory with a number of
  namespaces typed by hand, and the number was wrong within a week of being
  right - a namespace added, the sentence not. A reading of a project that
  renders every generated thing from its source and drift-gates it named
  the shape: the list between markers, from the files, held by a check in
  the gates - as the health block and the rule mirror already are.

  Two things are rendered and one is checked. The block between the markers
  is every namespace under `src/harness/`, its group (the segment after
  `harness.`, or none for `harness.rules`) and the first line of its
  docstring, with the count in one sentence. The hand-written table beneath
  it - what each namespace is for and the lesson it encodes - stays prose,
  and the check holds it to the source by NAME only: a namespace the source
  has that the hand-written part never mentions fails, since a lesson nobody
  can find by the namespace it belongs to is the omission this README was
  written against.

  The pure parts are `namespace-row`, `render` and `unnamed`; `sync!` is the
  command."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [harness.rules :as rules]))

(def begin-marker "<!-- harness-inventory:begin -->")
(def end-marker "<!-- harness-inventory:end -->")

(def groups
  "The groups in the README's order, with what each is for."
  [["setup" "the machine and the workspace"]
   ["loop" "the steps and the loop"]
   ["contract" "the packet, the shapes, the checks on a spec"]
   ["gates" "the runner, gate 0, the boundary gate"]
   ["models" "runners, adapters, profiles, provenance"]
   ["money" "balance, the reports, repricing"]])

(defn- ns-form
  "The file's `ns` form, read as EDN with its leading comments skipped; nil
  when the file does not start with one."
  [text]
  (try (let [form (edn/read-string text)]
         (when (and (seq? form) (= 'ns (first form))) form))
       (catch Exception _ nil)))

(defn namespace-row
  "`{:ns :group :doc}` from a source file's text: the namespace's name, its
  group (nil for a namespace directly under `harness`), the first line of
  its docstring (nil when it has none)."
  [text]
  (when-let [[_ nm doc] (ns-form text)]
    (let [segments (str/split (str nm) #"\.")]
      {:ns (str nm)
       :group (when (> (count segments) 2) (second segments))
       :doc (when (string? doc) (-> doc str/split-lines first str/trim not-empty))})))

(defn layer-of
  "The layer of `nm` under `layers` (the boundary gate's ruleset, `{ns
  #{allowed}}`): 0 for a namespace allowed nothing of the harness, else one
  more than the deepest layer of what it may require; nil for a namespace the
  ruleset does not declare. Folders say topic; this says dependency order."
  [layers nm]
  (when-let [allowed (get layers nm)]
    (if (empty? allowed)
      0
      (inc (reduce max 0 (keep #(layer-of layers %) allowed))))))

(defn namespaces
  "Every namespace under `src-dir` (`src/harness`), as rows with the layer
  `layers` (the ruleset) gives each, sorted by the README's group order then
  by name."
  [src-dir layers]
  (let [order (into {} (map-indexed (fn [i [g]] [g i])) groups)]
    (->> (fs/glob src-dir "**.clj")
         (map #(slurp (str %)))
         (keep namespace-row)
         (map #(assoc % :layer (layer-of layers (symbol (:ns %)))))
         (sort-by (juxt #(get order (:group %) -1) :ns))
         vec)))

(defn render
  "The block: the count in a sentence, then the table - namespace, group,
  layer, the first docstring line."
  [rows]
  (let [by-group (group-by :group rows)
        n (count rows)
        deepest (reduce max 0 (keep :layer rows))]
    (str n " namespaces, rendered from `src/harness/` by `bb inventory-sync` (`--check` in `bb gates`): "
         (str/join ", " (for [[g what] groups] (str "`harness." g "` (" (count (by-group g)) ": " what ")")))
         (when-let [top (seq (by-group nil))]
           (str ", and " (str/join ", " (map #(str "`" (:ns %) "`") top)) " on its own"))
         ". The group is the folder, what a namespace is about; the layer is its place in the dependency"
         " order `layers.edn` declares and `bb boundary` holds - 0 requires nothing of the harness, "
         deepest " is the top - computed as one more than the deepest layer of what it may require.\n\n"
         "| Namespace | Group | Layer | The first line of its docstring |\n|---|---|---|---|\n"
         (str/join "\n" (for [{:keys [ns group layer doc]} rows]
                          (str "| `" ns "` | " (or group "—") " | " (if layer layer "— (not in layers.edn)")
                               " | " (or doc "— (no docstring)") " |"))))))

(defn unnamed
  "The namespaces of `rows` that `readme-text` never names outside the
  generated block - the hand-written part has no lesson for them."
  [readme-text rows]
  (let [outside (str/replace readme-text
                             (re-pattern (str "(?s)" (java.util.regex.Pattern/quote begin-marker) ".*?"
                                              (java.util.regex.Pattern/quote end-marker)))
                             "")]
    (vec (for [{:keys [ns]} rows :when (not (str/includes? outside (str "`" ns "`")))] ns))))

(defn read-layers
  "The boundary gate's ruleset at `path` (`layers.edn`), its namespace
  entries only; `{}` when there is no file, so every layer renders as not
  declared rather than the sync failing."
  [path]
  (if (fs/exists? path)
    (into {} (filter (comp symbol? key)) (edn/read-string (slurp (str path))))
    {}))

(defn sync!
  "Render the inventory of `src-dir`, with the layers of `layers-path`, into
  `readme-path` between the markers, or with `:check? true` only compare.
  Returns `{:changed? _ :unnamed [...]}`."
  [src-dir layers-path readme-path & {:keys [check?]}]
  (let [rows (namespaces src-dir (read-layers layers-path))
        doc (slurp (str readme-path))
        updated (rules/splice doc (render rows) begin-marker end-marker)
        changed? (not= doc updated)]
    (when (and changed? (not check?)) (spit (str readme-path) updated))
    {:changed? changed? :unnamed (unnamed updated rows)}))

(defn -main
  "bb inventory-sync [--check]: the harness's own README, from its own source."
  [& args]
  (let [check? (boolean (some #{"--check"} args))
        {:keys [changed? unnamed]} (try (sync! "src/harness" "layers.edn" "README.md" :check? check?)
                                        (catch clojure.lang.ExceptionInfo e
                                          (println (str "inventory: " (ex-message e) " in README.md"))
                                          (System/exit 1)))]
    (doseq [nm unnamed]
      (println (str "inventory: README.md never names `" nm "` outside the generated block - it has no lesson there")))
    (cond
      (and check? (or changed? (seq unnamed)))
      (do (when changed? (println "inventory drift: README.md's block does not match src/harness/ - run `bb inventory-sync`"))
          (System/exit 1))
      (seq unnamed) (System/exit 1)
      check? (println "inventory in sync: README.md")
      changed? (println "inventory synced: README.md")
      :else (println "inventory already current: README.md"))))
