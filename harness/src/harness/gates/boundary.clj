(ns harness.gates.boundary
  "Gate 4: the architecture-boundary check (method §09), against a project's
  `layers.edn`. `bb boundary [dir]`.

  Reads each `ns` form under `<dir>/src`, takes what it requires of ITS OWN
  tree, and compares that with the ruleset: `{ns #{allowed-ns ...}}`. Three
  kinds of violation, all data (`violations`), so the check is readable from a
  REPL and not only from the gate:

    :undeclared            a namespace under src/ absent from the ruleset - a layer
                           should be declared, not discovered
    :forbidden-dependency  a require the namespace's entry does not allow
    :orphan                a ruleset entry with no file - dead config, which the
                           fixture this was ported from let through. An entry named in
                           the ruleset's one keyword key, `:boundary/fixtures #{...}`, is
                           exempt: a red fixture is declared so that the forbidden path
                           can be exercised, and is absent until it is copied in.

  A namespace's OWN tree is whatever shares its first segment: `xyx.handlers`
  requiring `xyx.views` is checked, requiring `clojure.string` is not. Gate 4
  is about your layers, not your dependencies - and so no ruleset needs to name
  a root.

  THE GATE SHIPS WITH THE KIT AND RUNS AGAINST THE PROJECT, the way `cljfmt`
  does: `bb --config <kit>/harness/bb.edn boundary`, from the project's
  directory. A generated application's `bb.edn` carries nothing of the KIT.
  Ported from `sandbox/dev/deps_check.clj`, where the only working copy had
  lived, outside what an adopter took (register row 32). Runs on babashka, so
  it costs milliseconds and sits in the sequence ahead of the tests. A
  deliberately small stand-in for `clj-depend`."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def file-name "layers.edn")

(defn requires-of
  "The namespaces `ns-form` requires, as a set of symbols."
  [ns-form]
  (->> ns-form
       (filter list?)
       (filter #(= :require (first %)))
       (mapcat rest)
       (map #(if (vector? %) (first %) %))
       (filter symbol?)
       set))

(defn- root-of [sym]
  (first (str/split (str sym) #"\.")))

(defn layers
  "The ruleset's namespace entries - its symbol keys; `:boundary/fixtures` is not one."
  [ruleset]
  (into {} (filter (comp symbol? key)) ruleset))

(defn violations
  "Every boundary violation under `src-dir`, given `ruleset` - a vector of
  `{:ns _ :kind _ :detail _}`, sorted by file."
  [src-dir ruleset]
  (let [fixtures (set (:boundary/fixtures ruleset))
        found (for [f (sort (fs/glob src-dir "**/*.clj"))
                    :let [form (edn/read-string (slurp (str f)))]
                    :when (and (seq? form) (= 'ns (first form)))]
                [(second form) form])
        present (set (map first found))]
    (vec
     (concat
      (mapcat
       (fn [[nm form]]
         (let [allowed (get ruleset nm)
               own (filter #(= (root-of nm) (root-of %)) (requires-of form))]
           (if (nil? allowed)
             [{:ns nm :kind :undeclared
               :detail (str "not declared in " file-name " - declare the layer, do not discover it")}]
             (for [u (sort own) :when (not (contains? allowed u))]
               {:ns nm :kind :forbidden-dependency
                :detail (str "requires " u ", which its layer does not allow")}))))
       found)
      (for [nm (sort (keys (layers ruleset)))
            :when (not (or (present nm) (fixtures nm)))]
        {:ns nm :kind :orphan
         :detail (str "declared in " file-name " but no file under src/ defines it - dead config")})))))

(defn render
  "The report: one line per violation, or the count of layers intact."
  [ruleset vs]
  (if (seq vs)
    (str "boundary: violations\n"
         (str/join "\n" (for [{:keys [ns kind detail]} vs]
                          (str "  " ns " [" (name kind) "] " detail))))
    (str "boundary: " (count (layers ruleset)) " namespaces, boundaries intact")))

(defn -main
  "bb boundary [dir]   (default: the current directory)

  Reads `<dir>/layers.edn`, walks `<dir>/src`. Exit 1 on any violation - and on
  a missing ruleset: a project that opted into this gate and lost its
  `layers.edn` is not one whose boundaries are intact."
  [& [dir]]
  (let [dir (or dir ".")
        rules (fs/path dir file-name)]
    (when-not (fs/exists? rules)
      (println (str "boundary: no " file-name " in " (fs/canonicalize dir)
                    " - the gate needs the ruleset it enforces"))
      (System/exit 1))
    (let [ruleset (edn/read-string (slurp (str rules)))
          vs (violations (str (fs/path dir "src")) ruleset)]
      (println (render ruleset vs))
      (when (seq vs)
        (System/exit 1)))))
