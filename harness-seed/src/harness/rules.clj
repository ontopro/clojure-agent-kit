;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/rules.clj @ 5df04ad (2026-09-05).
(ns harness.rules
  "The agent-rule source and its two renderings.

  `resources/agent-rules.edn` is the single source of truth for every rule an
  agent is told to follow. It has two renderings:

  - `rule-block` — the bullet list interpolated into a headless agent's system
    prompt, filtered by audience, with per-dispatch values substituted in;
  - `markdown` — the mirrored sections of CLAUDE.md, written by `bb rules-sync`
    and drift-checked by `bb rules-check` inside `bb gates`.

  Rules live in one file rather than inline in prompt strings because the
  loop's measured lesson is that prompt rules beat retry feedback — three
  rounds of gate feedback failed to break a habit that one up-front rule broke
  immediately. That makes them load-bearing assets, and the same rule written
  in two places with nothing keeping them honest is how they rot.

  The prompt rendering is the one that must never be skipped: a CLAUDE.md file
  is specific to one vendor's client, and the method requires the agent
  verifying the Coder to be a different model family — which reads no
  CLAUDE.md at all."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def resource-name "agent-rules.edn")

(def doc-substitutions
  "Placeholder values for the human mirror, which has no dispatch behind it."
  {:repl-port "<port>"
   :layer "<your layer>"
   :eval-how "clj-nrepl-eval -p <port> \"<code>\" (--discover-ports finds the port if you lose it)"})

(defn load-rules
  "The rules, in declaration order. Reads the classpath resource; pass any
  slurpable thing to load a different rule file (tests)."
  ([] (load-rules (or (io/resource resource-name)
                      (throw (ex-info "agent-rules.edn not on the classpath"
                                      {:resource resource-name})))))
  ([source] (edn/read-string (slurp source))))

(defn render
  "Substitute {{placeholder}} occurrences from `subs` (keyword -> value).
  Placeholders with no entry in `subs` are left alone rather than blanked —
  a half-substituted rule is easier to spot than a silently emptied one."
  [text subs]
  (reduce (fn [s [k v]]
            (str/replace s (str "{{" (name k) "}}") (str v)))
          text
          subs))

(defn for-audience
  "The rules addressed to `audience`, in declaration order."
  [rules audience]
  (filterv #(contains? (:audience %) audience) rules))

(defn rule-block
  "The rules for `audience` as a `- `-prefixed bullet list, placeholders
  substituted from `subs`. Prompt-shaped: no titles, no headings."
  ([audience subs] (rule-block (load-rules) audience subs))
  ([rules audience subs]
   (->> (for-audience rules audience)
        (map #(str "- " (render (:text %) subs)))
        (str/join "\n"))))

(def ^:private markdown-sections
  "Group key -> CLAUDE.md heading, in the order they are written."
  [[:non-negotiable "## Non-negotiables"]
   [:conventions "## Conventions (the lint gate fails on WARNINGS, not just errors)"]])

(defn markdown
  "The :human rules as the CLAUDE.md sections, grouped and titled.

  Each rule becomes one bullet, so :text supports inline markdown only — no fenced
  blocks, tables or sub-bullets. Extend this fn if a rule needs them; the workaround
  of hand-writing such a rule outside the markers reaches only clients that read the
  file, and silently skips the agent verifying the Coder."
  ([] (markdown (load-rules)))
  ([rules]
   (let [human (for-audience rules :human)]
     (->> markdown-sections
          (keep (fn [[group heading]]
                  (when-let [bullets (seq (filter #(= group (:group %)) human))]
                    (str heading "\n\n"
                         (str/join "\n"
                                   (map #(str "- **" (:title %) "** "
                                              (render (:text %) doc-substitutions))
                                        bullets))))))
          (str/join "\n\n")))))

(def begin-marker "<!-- agent-rules:begin -->")
(def end-marker "<!-- agent-rules:end -->")

(defn splice
  "`doc` with the text between the markers replaced by `block`. Throws when a
  marker is missing or they are out of order — appending to a doc whose
  markers you could not find is how a mirror silently stops mirroring."
  [doc block]
  (let [begin (str/index-of doc begin-marker)
        end (str/index-of doc end-marker)]
    (when (or (nil? begin) (nil? end) (> begin end))
      (throw (ex-info "agent-rules markers missing or out of order"
                      {:begin begin :end end})))
    (str (subs doc 0 (+ begin (count begin-marker)))
         "\n\n" block "\n\n"
         (subs doc end))))

(defn sync!
  "Write the rendered rules into the marker block of `doc-path`.
  Returns {:path _ :changed? _}; with `:check? true` it only reports."
  [doc-path & {:keys [check?]}]
  (let [doc (slurp doc-path)
        updated (splice doc (markdown))
        changed? (not= doc updated)]
    (when (and changed? (not check?))
      (spit doc-path updated))
    {:path doc-path :changed? changed?}))

(defn -main
  "bb rules-sync [--check] [path]   (default path: CLAUDE.md)"
  [& args]
  (let [check? (boolean (some #{"--check"} args))
        path (or (first (remove #(str/starts-with? % "--") args)) "CLAUDE.md")]
    (when-not (.exists (io/file path))
      (println (str "agent-rules: " path " not found — the rule mirror is the "
                    "file agents actually read; create it with the "
                    begin-marker " / " end-marker " markers."))
      (System/exit 1))
    (let [{:keys [changed?]}
          ;; A malformed mirror is a gate failure with a fix, not a stack
          ;; trace: whoever deleted the marker needs to be told which one.
          (try (sync! path :check? check?)
               (catch clojure.lang.ExceptionInfo e
                 (println (str "agent-rules: " (ex-message e) " in " path
                               " — the block is delimited by\n  " begin-marker
                               "\n  " end-marker))
                 (System/exit 1)))]
      (cond
        (and check? changed?)
        (do (println (str "agent-rules drift: " path " does not match resources/"
                          resource-name " — run `bb rules-sync`"))
            (System/exit 1))

        check? (println (str "agent-rules in sync: " path))
        changed? (println (str "agent-rules synced: " path))
        :else (println (str "agent-rules already current: " path))))))
