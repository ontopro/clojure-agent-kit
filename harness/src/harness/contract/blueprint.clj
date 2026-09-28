;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.contract.blueprint
  "A task's `spec.edn`, pulled out of a Blueprint written to the template.

  WHY THIS EXISTS. Method §06 says the Blueprint is written in a fixed order -
  shapes, interfaces, namespaces, then the dependency-ordered packets - and §07
  says a task starts from its packet. Between the two there was a hand step:
  the Architect opened the Blueprint, copied the packet's fence into
  `spec.edn`, and pasted in every shape the packet named from §1. Every build
  did that by hand, and each did it a little differently; meanwhile the
  template's own packet had drifted from what the harness reads, so the copy
  was also a rewrite. `bb spec-from-blueprint <blueprint.md> <task-id> [<out.edn>]`
  is that step as a command.

  WHAT IT READS. Every fenced block of the document; a fence whose first form
  is a map carrying `:task/id` is a packet, and the one whose id matches is
  the task's. §1 - the section headed `## 1.` - defines the stage's shapes,
  each as `(def Name schema)` or `[Name schema]`, the two forms
  `harness.contract.stub/shape-def` reads. A shape the packet only NAMES under
  `:shapes` is replaced by §1's definition, verbatim (the form as §1 wrote
  it), the named ones first in §1's order and the packet's own inline entries
  after them; a name §1 does not define is refused by name, since a name the
  harness cannot resolve reaches every role as a bare symbol (the fourth
  project's spec review reported those undefined, one task at a time).

  WHAT IT WRITES is a `harness.contract.shapes/TaskSpec`, validated before it
  is written: what `driver/start!` reads and `harness.contract.packet` cuts
  the three packets from. Read as EDN, as the driver reads `spec.edn`, so a
  quote or a regex in a Blueprint's fence is refused here rather than at
  `start`. Without an output path the spec goes to stdout and the report to
  stderr, so it pipes into `bb sigs`."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.contract.shapes :as shapes]
   [harness.contract.sigs :as sigs]
   [harness.contract.stub :as stub])
  (:import
   [java.io PushbackReader StringReader]))

;; ---------------------------------------------------------------------------
;; reading the document
;; ---------------------------------------------------------------------------

(defn fences
  "The content of every fenced code block in `text`, in document order,
  whatever language the fence names."
  [text]
  (mapv second (re-seq #"(?m)^```[^\n]*\n([\s\S]*?)^```" text)))

(defn section
  "The text of the section headed `## <n>.` (or `## <n> `), up to the next
  `## ` heading; nil when the document has no such heading."
  [text n]
  (when-let [m (re-find (re-pattern (str "(?m)^## " n "[. ][^\n]*\n")) text)]
    (let [from (+ (str/index-of text m) (count m))
          rest-text (subs text from)
          to (or (some-> (re-find #"(?m)^## " rest-text) (as-> h (str/index-of rest-text h)))
                 (count rest-text))]
      (subs rest-text 0 to))))

(defn read-forms
  "Every top-level form in `s`, read as EDN - the reader `spec.edn` is read
  with, so what this accepts is what `start` accepts. Throws with the reader's
  own message on anything else."
  [s]
  (let [r (PushbackReader. (StringReader. s))
        eof (Object.)]
    (loop [acc []]
      (let [form (edn/read {:eof eof :default tagged-literal} r)]
        (if (identical? form eof)
          acc
          (recur (conj acc form)))))))

(defn- try-forms
  "`read-forms`, or nil for a fence that is not EDN - §2's signatures with
  their comments usually are, a bash fence never is, and neither is a packet."
  [s]
  (try (read-forms s) (catch Exception _ nil)))

(defn packets
  "Every packet the Blueprint carries: each fence whose first form is a map
  with a `:task/id`, as `{:task/id id :spec map}`, in document order."
  [text]
  (into []
        (keep (fn [fence]
                (let [[form & more] (try-forms fence)]
                  (when (and (map? form) (:task/id form) (empty? more))
                    {:task/id (:task/id form) :spec form}))))
        (fences text)))

(defn shape-name
  "The name a §1 form defines, or nil: `(def Name schema)` and `[Name schema]`,
  the two forms the stub reads."
  [form]
  (cond
    (and (seq? form) (= 3 (count form)) (= 'def (first form)) (symbol? (second form)))
    (second form)

    (and (vector? form) (= 2 (count form)) (symbol? (first form)))
    (first form)))

(defn definitions
  "§1's shape definitions as `[[Name form] ...]`, in §1's order, each form
  verbatim. A §1 fence that is not EDN is skipped, as is any form that defines
  no shape. Nil when the document has no §1."
  [text]
  (when-let [s1 (section text 1)]
    (into []
          (comp (mapcat #(or (try-forms %) []))
                (keep (fn [form] (when-let [n (shape-name form)] [n form]))))
          (fences s1))))

;; ---------------------------------------------------------------------------
;; the spec
;; ---------------------------------------------------------------------------

(defn- refuse [msg data]
  (throw (ex-info msg (assoc data :blueprint/error (:blueprint/error data :refused)))))

(defn inline-shapes
  "`spec` with every shape its slice only names replaced by `defs`'s
  definition of it: the named ones first, in §1's order (a task's own shape
  may build on the stage's), then the slice's inline entries in their order.
  Refuses by name when a named shape has no definition."
  [spec defs]
  (let [named (filterv symbol? (get-in spec [:blueprint/slice :shapes]))
        inline (vec (remove symbol? (get-in spec [:blueprint/slice :shapes])))
        defined (into {} defs)
        missing (vec (remove defined named))]
    (when (seq missing)
      (refuse (str "the Blueprint's §1 defines no shape named "
                   (str/join ", " (map str missing))
                   " - named under :shapes by " (:task/id spec)
                   "; define it in §1 as (def Name schema) or [Name schema], or write it inline in the packet")
              {:blueprint/error :undefined-shape :task/id (:task/id spec) :shapes missing}))
    (let [wanted (set named)
          from-s1 (into [] (comp (filter (comp wanted first)) (map second)) defs)]
      (-> spec
          (assoc-in [:blueprint/slice :shapes] (into from-s1 inline))
          (assoc ::inlined (mapv first (filter (comp wanted first) defs)))))))

(defn spec-for
  "The task spec for `task-id` out of a Blueprint's `text`: the matching
  packet, its named shapes inlined from §1, validated as a
  `shapes/TaskSpec` and checked as a slice the stub can read. Returns
  `{:spec spec :inlined [names]}`; refuses by name a task the Blueprint does
  not carry, a shape §1 does not define, and a packet that is not a task spec."
  [text task-id]
  (let [found (packets text)
        ids (mapv :task/id found)
        packet (some #(when (= task-id (:task/id %)) (:spec %)) found)]
    (when-not packet
      (refuse (str "the Blueprint carries no packet with :task/id " (pr-str task-id)
                   (if (seq ids)
                     (str "; it carries " (str/join ", " (map pr-str ids)))
                     "; it carries none - a packet is a fenced map with a :task/id, §4 of the template"))
              {:blueprint/error :no-such-task :task/id task-id :task-ids ids}))
    (when (nil? (definitions text))
      (refuse "the Blueprint has no `## 1.` section - written to the template, the data shapes are §1"
              {:blueprint/error :no-shapes-section}))
    (let [{inlined ::inlined :as with-shapes} (inline-shapes packet (definitions text))
          spec (dissoc with-shapes ::inlined)]
      ;; TWO IMPLEMENTATION FILES ARE REFUSED HERE, by the rule and not by the schema's count:
      ;; the fifth project's two-file packet passed everything up to `start`'s stub step and
      ;; cost two spec reviews and a provisioning first.
      (when-let [[{:keys [entry reason]}] (stub/check-files spec)]
        (refuse (str "the packet " task-id " names " (pr-str entry) " under :files/impl: " reason
                     "\n  split it into one packet per namespace")
                {:blueprint/error :multi-impl :task/id task-id :files entry}))
      (when-let [errors (shapes/explain-spec spec)]
        (refuse (str "the packet " task-id " is not a task spec: " (binding [*print-namespace-maps* false] (pr-str errors))
                     "\n  a spec carries :task/id, :task/title, :blueprint/slice {:shapes :interfaces :deps-sigs}, "
                     ":files/impl, :files/test, :files/context, :layer/name; :property-targets and "
                     ":gates {:retry-cap n} are optional - §4 of the template shows it")
                {:blueprint/error :not-a-spec :task/id task-id :errors errors}))
      ;; ... and a `:deps-sigs` entry the calls gate could never grant (`sigs/check-deps-sigs`):
      ;; the fifth project's bare `(base [title content])` passed here and went red two dispatches later.
      (when-let [bad (seq (concat (stub/check-slice (:blueprint/slice spec))
                                  (sigs/check-deps-sigs (:deps-sigs (:blueprint/slice spec)))))]
        (refuse (str "the packet " task-id "'s slice has " (count bad) " entr" (if (= 1 (count bad)) "y" "ies")
                     " the loop cannot use: "
                     (str/join "; " (map #(str (name (:key %)) " " (pr-str (:entry %)) " — " (:reason %)) bad)))
                {:blueprint/error :bad-slice :task/id task-id :entries (mapv :entry bad)}))
      {:spec spec :inlined inlined})))

(def key-order
  "The template's order, which is the order a reader meets the packet in."
  [:task/id :task/title :blueprint/slice :files/impl :files/test :files/context
   :layer/name :property-targets :gates])

(defn- ordered
  "`m` as an array-map in `order`, any other key after, so the printed spec
  reads as the template does rather than as a hash-map happens to iterate."
  [m order]
  (apply array-map
         (mapcat (fn [k] [k (get m k)])
                 (concat (filter #(contains? m %) order)
                         (remove (set order) (keys m))))))

(defn render
  "`spec` as the text of `spec.edn`: pretty-printed in the template's key
  order, so the Architect can read what a role will receive."
  [spec]
  (let [spec (-> (ordered spec key-order)
                 (update :blueprint/slice ordered [:shapes :interfaces :deps-sigs]))]
    (with-out-str (binding [pp/*print-right-margin* 100] (pp/pprint spec)))))

(defn report
  "What was written, one line per fact: the task, where it went, the targets,
  the shapes inlined from §1, the counts."
  [{:keys [spec inlined]} blueprint out]
  (let [{:keys [blueprint/slice]} spec]
    (str/join "\n"
              [(str "spec-from-blueprint: " (:task/id spec) " from " blueprint
                    (if out (str " -> " out) " -> stdout"))
               (str "  impl:    " (str/join ", " (:files/impl spec)))
               (str "  test:    " (str/join ", " (:files/test spec)))
               (str "  context: " (if (seq (:files/context spec)) (str/join ", " (:files/context spec)) "none"))
               (str "  shapes:  " (count (:shapes slice)) " (" (count inlined) " inlined from §1"
                    (when (seq inlined) (str ": " (str/join ", " (map str inlined)))) ")")
               (str "  interfaces: " (count (:interfaces slice))
                    ", deps-sigs: " (count (:deps-sigs slice))
                    ", property targets: " (count (:property-targets spec)))])))

(defn -main
  "bb spec-from-blueprint <blueprint.md> <task-id> [<out.edn>]

  Writes the task's spec to `<out.edn>` and prints the report; without
  `<out.edn>` the spec goes to stdout and the report to stderr, so
  `bb spec-from-blueprint bp.md t-01 | bb sigs --dir <app>` reads it. Exit 1
  with the reason on any refusal."
  [& [blueprint task-id out]]
  (when-not (and blueprint task-id)
    (println "usage: bb spec-from-blueprint <blueprint.md> <task-id> [<out.edn>]")
    (System/exit 2))
  (when-not (fs/exists? blueprint)
    (println (str "spec-from-blueprint: no such file " blueprint))
    (System/exit 1))
  (let [result (try (spec-for (slurp blueprint) task-id)
                    (catch clojure.lang.ExceptionInfo e
                      (println (str "spec-from-blueprint: " (ex-message e)))
                      (System/exit 1)))
        text (render (:spec result))]
    (if out
      (do (fs/create-dirs (or (fs/parent out) "."))
          (spit out text)
          (println (report result blueprint out)))
      (do (binding [*out* *err*] (println (report result blueprint nil)))
          (print text)
          (flush)))))
