;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.gates.forms
  "The top-level forms of a Clojure file, as a table: kind, name, line range,
  hash. And the difference between two such tables.

  THIS IS A STACK-SPECIFIC NAMESPACE, the fourth of four - harness.gates.repair
  repairs Clojure, harness.contract.stub emits it, harness.contract.sigs reads its
  definitions, and this reads its forms. Nothing else in the harness knows what a
  `defn` is.

  WHAT IT IS FOR. A role that writes a file it did not create, or edits one it
  did, changes more than it means to: a whole-file rewrite over one lint
  warning renamed helpers and dropped a form, and every target still held.
  The after-write report says, while the author is still there, what happened
  at the level of forms: `forms 9 -> 8, lost: render-page`. The same table is
  the check a MODIFY deliverable will need - the forms a packet names may
  change, every other form is left as it was - once a packet can say that;
  the table is here before the packet is, so the report is the first use.

  NIL AND SILENT WHEN THE FILE DOES NOT PARSE, the rule `harness.contract.sigs`
  follows: a file with an unmatched bracket has no form table, not an empty
  one, and a report that cannot be made is not made. Gate 0 has already told
  the author about the bracket.

  EDAMAME READS, NOT THE CLOJURE READER: it parses without evaluating, keeps
  the line range on every form, and accepts what a Clojure file may hold -
  reader conditionals (read as `:clj`, splices flattened), tagged literals it
  has no reader for, syntax-quote, `#_` discards (dropped, as the reader
  drops them). Babashka ships it, and `clj-paren-repair` already parses with it."
  (:require
   [clojure.string :as str]
   [edamame.core :as e]))

(def ^:private read-options
  {:all true
   :read-cond :allow
   :features #{:clj}
   ;; An alias's namespace is not resolvable without loading the file, and
   ;; the table does not need it: the keyword is a value the hash covers.
   :auto-resolve (fn [alias] (if (= :current alias) 'this.ns (symbol (str alias))))
   :readers (fn [_tag] identity)
   :syntax-quote {:resolve-symbol identity}})

(defn- unsplice
  "A `#?@` at the top level parses as one vector marked as a splice; its
  elements are the top-level forms."
  [forms]
  (mapcat (fn [f] (if (:edamame/read-cond-splicing (meta f)) f [f])) forms))

(defn- row
  "One form as a table row. `:name` is the symbol after the head when there is
  one (`defn f`, `deftest t`, `s/def ::k`), and for `defmethod` the dispatch
  value beside it, so two methods of one multimethod are two rows and not a
  duplicate. A form with no head symbol - a bare value, a `do` - has a kind
  and no name."
  [form]
  (let [{:keys [row end-row]} (meta form)
        head (when (and (seq? form) (symbol? (first form))) (first form))
        nm (when (and head (symbol? (second form))) (second form))
        nm (if (and nm (= 'defmethod head) (> (count form) 2))
             (str nm " " (pr-str (nth form 2)))
             (some-> nm str))]
    {:kind (cond head (str head)
                 (seq? form) "()"
                 :else "value")
     :name nm
     :from row
     :to end-row
     :hash (hash form)}))

(defn table
  "The top-level forms of `content`, in order, each
  `{:kind :name :from :to :hash}`; `[]` for an empty file; nil when the
  content does not parse. `:from`/`:to` are line numbers, nil on a form the
  reader gives no position (a bare string or number at the top level)."
  [content]
  (try
    (mapv row (unsplice (e/parse-string-all (str content) read-options)))
    (catch Exception _ nil)))

(defn- keyed
  "The named rows, as `{[kind name] [hash ...]}` - a vector, because a name
  can lawfully occur twice (two `defmethod`s that pr-str alike; a
  redefinition, which lint will name)."
  [rows]
  (->> rows
       (filter :name)
       (group-by (juxt :kind :name))
       (into {} (map (fn [[k rs]] [k (mapv :hash rs)])))))

(defn delta
  "What changed between two tables: `{:before n :after n :lost [name ...]
  :gained [name ...] :changed [name ...]}`, names in file order of the table
  they are in. Nil when either table is nil. A name is `changed` when it is
  in both and its hash differs; unnamed forms are counted and never named."
  [before after]
  (when (and before after)
    (let [b (keyed before) a (keyed after)
          names (fn [rows pred] (->> rows (filter :name) (map (juxt :kind :name)) distinct
                                     (filter pred) (mapv second)))]
      {:before (count before)
       :after (count after)
       :lost (names before #(not (contains? a %)))
       :gained (names after #(not (contains? b %)))
       :changed (names after #(and (contains? b %) (not= (b %) (a %))))})))

(defn summary
  "`delta` as the one line the after-write report carries, or nil when
  either side does not parse: `forms 9 -> 8, lost: render-page` ;
  `forms 9 -> 9, changed: f, g` ; `forms 9 -> 9, no form changed`."
  [before-content after-content]
  (when-let [{:keys [before after lost gained changed]}
             (delta (table before-content) (table after-content))]
    (let [part (fn [label xs] (when (seq xs) (str label ": " (str/join ", " xs))))
          parts (remove nil? [(part "lost" lost) (part "gained" gained) (part "changed" changed)])]
      (str "forms " before " -> " after ", "
           (if (seq parts) (str/join "; " parts) "no form changed")))))
