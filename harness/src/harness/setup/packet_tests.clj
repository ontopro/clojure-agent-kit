(ns harness.setup.packet-tests
  "The tests that read what a packet changes, listed before the packet is cut:
  `bb packet-tests <task-id>`, run from the workspace.

  A PACKET'S TEST FILE IS EDITED, NOT REWRITTEN, and the Tester edits only the
  files `:files/test` names. A test that already reads what the packet changes -
  a routes test counting exactly four routes, a server test comparing the whole
  context, a page test reading a page header to foot - fails when the packet
  lands and no role may touch it: the run stops. One project found three such
  files in one stage by search before the run, and one more only at a spec
  review. This lists them, so `:files/test` names the ones the change breaks.

  TWO SIGNALS, AND WHAT EACH IS. A test namespace that requires the namespace of
  the packet's `:files/impl` - read from its ns form, exact. And a test file that
  names a route path the packet's targets quote (`/search`), as a string
  literal - a string match, so a test naming the path for another reason is
  listed too. A data convention the targets quote (a `data-*` attribute) is not
  read: information, never a gate, and the two signals are the ones the person
  chose (2026-10-27).

  The pure parts are `impl-namespace`, `required-namespaces`, `target-routes` and
  `readers`; `-main` is the command."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [harness.contract.blueprint :as blueprint]
   [harness.setup.stages :as stages]
   [harness.setup.workspace :as workspace]))

(defn impl-namespace
  "The namespace a source file under a `src/` folder defines, by the path:
  `src/app/foo_bar.clj` is `app.foo-bar`; nil for a path with no `src/`."
  [path]
  (when-let [[_ rel] (re-find #"(?:^|/)src/(.+)\.clj[cs]?$" path)]
    (symbol (-> rel (str/replace "/" ".") (str/replace "_" "-")))))

(defn- libs
  "The namespaces one `:require` libspec names: a symbol, a vector's first
  symbol, or a prefix list's prefix joined to each of its parts."
  [spec]
  (cond
    (symbol? spec) [spec]
    (and (vector? spec) (symbol? (first spec))) [(first spec)]
    (and (sequential? spec) (symbol? (first spec)))
    (let [prefix (first spec)]
      (for [part (rest spec)
            :let [s (if (sequential? part) (first part) part)]
            :when (symbol? s)]
        (symbol (str prefix "." s))))
    :else []))

(defn required-namespaces
  "The namespaces `text`'s ns form requires, as a set; empty when the first form is
  not an ns form or cannot be read."
  [text]
  (let [form (try (binding [*read-eval* false] (read-string text)) (catch Exception _ nil))]
    (if (and (seq? form) (= 'ns (first form)))
      (set (for [clause (drop 2 form)
                 :when (and (seq? clause) (= :require (first clause)))
                 spec (rest clause)
                 lib (libs spec)]
             lib))
      #{})))

(defn target-routes
  "The route paths `targets` (a packet's `:property-targets`) quote, in order, each once: a
  `/` not after a word character or another `/`, then a path. A file path's
  inner `/` follows a word character, so `src/app` is not one; the root `/`
  alone is not read."
  [targets]
  (vec (distinct (map second (mapcat #(re-seq #"(?<![\w/:.])(/[a-z0-9][\w\-/]*[\w])" (str %)) targets)))))

(defn- names-route?
  "Does `text` hold `route` as a string literal - the path alone, or with a query string? A
  longer path under it (`/pages/x` for `/pages`) is another route."
  [text route]
  (boolean (re-find (re-pattern (str "\"" (java.util.regex.Pattern/quote route) "[\"?]")) text)))

(defn readers
  "The test files of `tests` (`[[rel text] …]`) that read what `spec` changes, as
  `[{:file rel :requires [ns] :routes [path] :own? bool}]` in `tests`' order: each one
  requiring the namespace of a `:files/impl` or naming a route `spec`'s targets
  quote. `:own?` when the packet's `:files/test` names it already."
  [spec tests]
  (let [nss (set (keep impl-namespace (:files/impl spec)))
        routes (target-routes (:property-targets spec))
        own (set (:files/test spec))]
    (vec (for [[rel text] tests
               :let [req (vec (sort (filter nss (required-namespaces text))))
                     rs (filterv #(names-route? text %) routes)]
               :when (or (seq req) (seq rs))]
           {:file rel :requires req :routes rs :own? (contains? own rel)}))))

(defn- test-files
  "Every `.clj` file under the application's `test/`, as `[[rel text] …]`, sorted by path."
  [app]
  (let [dir (fs/path app "test")]
    (vec (sort-by first
                  (for [f (when (fs/directory? dir) (fs/glob dir "**.clj"))]
                    [(str (fs/relativize app f)) (slurp (str f))])))))

(defn packet-in
  "The latest stage of `build` whose blueprint carries `task-id`, as `{:stage id
  :blueprint path :spec map}`; nil when none does."
  [build task-id]
  (first (for [[id _] (rseq (stages/folders (stages/stages-dir build)))
               :let [bp (stages/document build id "-blueprint.md")]
               :when (fs/exists? bp)
               p (blueprint/packets (slurp (str bp)))
               :when (= task-id (:task/id p))]
           {:stage id :blueprint bp :spec (:spec p)})))

(defn report
  "The lines `bb packet-tests` prints for `found` (`readers`' answer) of `task-id`, from `where`."
  [task-id where spec found]
  (let [routes (target-routes (:property-targets spec))]
    (str "packet-tests " task-id " (" where "): "
         (if (seq found)
           (str (count found) " test file" (when (> (count found) 1) "s") " read what it changes\n"
                (str/join "\n" (for [{:keys [file requires routes own?]} found]
                                 (str "  " file " - "
                                      (str/join "; " (concat (when (seq requires) [(str "requires " (str/join ", " requires))])
                                                             (when (seq routes) [(str "names " (str/join ", " (map #(str "\"" % "\"") routes)))])))
                                      (when own? " (the packet's own :files/test)"))))
                "\nName in :files/test each one the change breaks, to be edited, not rewritten.")
           (str "no test file requires " (str/join ", " (keep impl-namespace (:files/impl spec)))
                (when (seq routes) (str " or names " (str/join ", " routes)))))
         (when (seq routes)
           (str "\nRoutes from the targets: " (str/join ", " routes)
                " - a string match: a test naming a path for another reason is listed too.")))))

(defn -main
  "bb packet-tests <task-id> [--workspace <dir>]

  Lists the application's test files that read what the packet `task-id` of the
  latest stage carrying it changes. Information, exit 0; exit 2 when there is no
  such packet."
  [& args]
  (let [ws (workspace/current-or-exit args)
        task-id (first (remove #(str/starts-with? % "--") (:args (workspace/split-args args))))]
    (when-not task-id
      (println "usage: bb packet-tests <task-id> [--workspace <dir>]")
      (System/exit 2))
    (if-let [{:keys [stage spec]} (packet-in (:workspace/build ws) task-id)]
      (println (report task-id (stages/rel stage "-blueprint.md") spec (readers spec (test-files (:workspace/app ws)))))
      (do (println (str "packet-tests: no stage's blueprint carries " task-id))
          (System/exit 2)))))
