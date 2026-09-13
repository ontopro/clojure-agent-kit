(ns deps-check
  "Gate 4: the architecture-boundary check (method §09).

  Reads each namespace's `ns` form, extracts what it requires from the
  sandbox tree, and compares that against `layers.edn`. Runs on babashka, so
  it costs milliseconds and can sit in the gate sequence ahead of the tests.

  This is a deliberately small stand-in for `clj-depend`, which is not
  installed here. The point of a fixture is that the gate genuinely fails when
  a boundary is crossed — a placeholder that always exits 0 proves nothing
  about the loop that dispatches on its key."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

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

(defn- own?
  [sym]
  (str/starts-with? (str sym) "sandbox."))

(defn violations
  "Every boundary violation under `src-dir`, given `ruleset`.
  Returns a vector of {:ns _ :kind _ :detail _} — data, so this is checkable
  from the REPL and not only from the gate."
  [src-dir ruleset]
  (vec
   (mapcat
    (fn [f]
      (let [form (read-string (slurp (str f)))
            nm (second form)
            allowed (get ruleset nm)
            used (filter own? (requires-of form))]
        (if (nil? allowed)
          [{:ns nm :kind :undeclared
            :detail "not declared in layers.edn — declare the layer, do not discover it"}]
          (for [u used :when (not (contains? allowed u))]
            {:ns nm :kind :forbidden-dependency
             :detail (str "requires " u ", which its layer does not allow")}))))
    (sort (fs/glob src-dir "**/*.clj")))))

(defn -main [& _]
  (let [ruleset (edn/read-string (slurp "layers.edn"))
        vs (violations "src" ruleset)]
    (if (seq vs)
      (do (println "deps-check: boundary violations")
          (doseq [{:keys [ns kind detail]} vs]
            (println (str "  " ns " [" (name kind) "] " detail)))
          (System/exit 1))
      (println (str "deps-check: " (count ruleset) " namespaces, boundaries intact")))))
