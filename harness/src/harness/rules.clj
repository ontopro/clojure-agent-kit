;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Extracted from src/thub/harness/rules.clj @ 5df04ad (2026-09-05).
(ns harness.rules
  "The agent-rule source and its two renderings.

  `resources/agent-rules.edn` is the single source of truth for every rule an
  agent is told to follow - merged, in a workspace, with the project's OVERLAY
  (`project-rules`: the placeholders filled and project rules added in the plan
  repository, the KIT's own rules untouchable). It has two renderings:

  - `rule-block` — the bullet list interpolated into a headless agent's system
    prompt, filtered by audience, with per-dispatch values substituted in;
  - `markdown` — the mirrored sections of the agent-rules file, written by
    `bb rules-sync` and drift-checked by `bb rules-check` inside `bb gates`.

  The mirror defaults to AGENTS.md, which the Antigravity IDE, OpenCode and Pi
  read natively.
  Claude Code reads only CLAUDE.md, so the seed ships a CLAUDE.md that imports
  AGENTS.md rather than a second generated copy: one marker block, one drift
  target, and exactly one place a rule can be hand-written outside it.

  Rules live in one file rather than inline in prompt strings because the
  loop's measured lesson is that prompt rules beat retry feedback — three
  rounds of gate feedback failed to break a habit that one up-front rule broke
  immediately. That makes them load-bearing assets, and the same rule written
  in two places with nothing keeping them honest is how they rot.

  The prompt rendering is the one that must never be skipped. AGENTS.md is a
  convention among interactive clients; the method requires the agent verifying
  the Coder to be a different model family, and that agent reads no file at
  all — it gets an HTTP request with a system prompt."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [harness.setup.workspace :as workspace]))

(def resource-name "agent-rules.edn")

(def doc-substitutions
  "Placeholder values for the human mirror, which has no dispatch behind it."
  {:repl-port "<port>"
   :layer "<your layer>"
   :eval-how "clj-nrepl-eval -p <port> \"<code>\" (--discover-ports finds the port if you lose it)"})

(defn shipped
  "The KIT's rule source alone - the classpath resource, in declaration order.
  What a project reads is `load-rules`, which merges its overlay over this."
  []
  (edn/read-string (slurp (or (io/resource resource-name)
                              (throw (ex-info "agent-rules.edn not on the classpath"
                                              {:resource resource-name}))))))

(defn unfilled
  "The `<angle-bracket>` placeholders still standing in `rules`, as
  `[{:id kw :placeholder str}]`.

  A PLACEHOLDER NOBODY FILLED REACHES EVERY AGENT AS LITERAL TEXT, with every
  gate green: the first project to copy the seed sent *<Name your layers…>* to
  its Coder until a person noticed. `rules-check` cannot fail on it - the seed
  ships with its placeholders standing, and its own gates must pass - so the
  check lives where it costs something to ignore: `start` prints the list
  before it dispatches anyone. A placeholder is a `<`, a capital letter, and at
  least a few words; `<port>` and `<code>` in a rule's own prose are not one."
  [rules]
  (vec (for [{:keys [id text]} rules
             p (re-seq #"<[A-Z][^<>]{12,}>" (str text))]
         {:id id :placeholder p})))

;; ---------------------------------------------------------------------------
;; the overlay: a project's rules, over the KIT's
;; ---------------------------------------------------------------------------
;;
;; The clone of the KIT a project builds with is upgraded with `git pull`, so no project
;; edits `resources/agent-rules.edn`. What a project decides about its rules - the three
;; placeholders filled, rules of its own added - lives in the plan repository, in the file
;; `workspace.edn` names as `:workspace/rules-overlay` (`bb init` writes it with the
;; placeholders standing), and is merged over the source by :id here. Every rendering reads
;; the merged set. A KIT rule that is not a placeholder is not the overlay's to change: that
;; refusal is what holds the given part of the KIT against an edit made by accident.

(defn placeholder-ids
  "The ids of the rules that ship with a `<placeholder>` standing - the ones a
  project fills. Derived from the text, not listed, so a rule that gains a
  placeholder is fillable by that fact."
  [rules]
  (into #{} (map :id) (unfilled rules)))

(defn- rule-problems
  "Why `rule` cannot be dispatched as one, as sentences; empty when it can.
  `audiences` are the ones the source serves - a rule for nobody the source
  knows reaches nobody."
  [{:keys [group audience title text]} audiences]
  (cond-> []
    (not (contains? #{:non-negotiable :conventions} group))
    (conj ":group must be :non-negotiable or :conventions")

    (not (and (set? audience) (seq audience) (every? audiences audience)))
    (conj (str ":audience must be a non-empty subset of " (pr-str audiences)))

    (not (string? title)) (conj ":title must be a string")
    (not (string? text)) (conj ":text must be a string")))

(defn overlay
  "`shipped` with a project's `entries` merged over it by `:id`. An entry whose
  id is one of the source's PLACEHOLDER rules (`placeholder-ids`) replaces that
  rule in place - its keys merge over the rule's, so `{:id _ :text _}` fills it.
  An entry with an id the source lacks is a project rule, appended in the
  overlay's order, and must be whole (`:group`, `:audience`, `:title`, `:text`).
  An entry whose id is any OTHER rule of the source is refused by name: those
  rules are the KIT's. Throws ex-info naming every problem at once."
  [shipped entries]
  (let [fillable (placeholder-ids shipped)
        shipped-ids (into #{} (map :id) shipped)
        audiences (into #{} (mapcat :audience) shipped)
        by-id (group-by :id entries)
        entry-of #(first (get by-id %))
        merged (into (mapv #(if-let [e (entry-of (:id %))] (merge % e) %) shipped)
                     (remove #(contains? shipped-ids (:id %)) entries))
        problems
        (concat
         (for [[id es] by-id :when (> (count es) 1)]
           (str (pr-str id) " appears " (count es) " times"))
         (for [{:keys [id]} entries :when (not (keyword? id))]
           (str (pr-str id) " is not a keyword :id"))
         (for [{:keys [id]} entries :when (and (shipped-ids id) (not (fillable id)))]
           (str id " is the KIT's rule, not a placeholder - not the overlay's to change"))
         (for [{:keys [id] :as r} merged :when (entry-of id)
               p (rule-problems r audiences)]
           (str id ": " p)))]
    (when (seq problems)
      (throw (ex-info (str "the rules overlay is refused: " (str/join "; " problems))
                      {:rules/error :overlay :problems (vec problems)})))
    merged))

(defn read-overlay
  "The overlay file at `path`, as entries. A workspace that names one it does
  not have is a fault, not an empty overlay: `bb init` writes it, and a
  project whose rules silently fell back to the source would dispatch the
  placeholders as literal text."
  [path]
  (when-not (fs/exists? path)
    (throw (ex-info (str "the rules overlay " path " that workspace.edn names is not there")
                    {:rules/error :no-overlay :path (str path)})))
  (let [entries (edn/read-string (slurp (str path)))]
    (when-not (and (vector? entries) (every? map? entries))
      (throw (ex-info (str "the rules overlay " path " must be a vector of rule maps")
                      {:rules/error :overlay :path (str path)})))
    entries))

(defn project-rules
  "The rules a project's agents read: the source with the workspace's overlay
  merged over it - or the source alone when `ws` is nil (no workspace) or
  names no overlay (one made before overlays existed)."
  [ws]
  (let [source (shipped)]
    (if-let [path (:workspace/rules-overlay ws)]
      (overlay source (read-overlay path))
      source)))

(defn load-rules
  "The rules, in declaration order. With no argument: the source merged with
  the overlay of the workspace this command runs in (`workspace/current`: the
  run's, the one pointed at, or the one `harness/` is in by walking up), or
  the source alone outside one - so every rendering made from here reads the
  project's rules. Pass any slurpable thing to load a rule file alone (tests)."
  ([] (project-rules (workspace/current)))
  ([source] (edn/read-string (slurp source))))

(defn rules-for-mirror
  "The rules a mirror renders from: the project's when `path` is one of the
  mirrors its workspace lists (`:workspace/rule-mirrors`), the source for every
  other. The KIT's own `AGENTS.md` and the selfcheck's sit INSIDE an adopter's
  workspace by default, and rendered from the project's rules they would change
  under `bb rules-sync` - a project's edit in the clone, by the back door."
  [path]
  (let [abs (str (fs/normalize (fs/absolutize path)))
        ws (workspace/find-workspace (fs/parent abs))]
    (if (and ws (some #{abs} (:workspace/rule-mirrors ws)))
      (project-rules ws)
      (shipped))))

(defn fill-where
  "Where a standing placeholder is filled, for a message: the workspace's
  overlay when this command runs in one, else the source (a harness run bare)."
  []
  (or (:workspace/rules-overlay (workspace/current))
      (str "resources/" resource-name)))

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

(defn prompt-substitutions
  "Per-dispatch substitutions for `rule-block`, with `:eval-how` derived from
  `:repl-port` when the caller did not supply one.

  The derived hint names the bridge `bb doctor` declares required. Pass your
  own `:eval-how` to use a different one — like ManualRunner's eval-hint, it
  is a string, not a dependency."
  [{:keys [repl-port eval-how] :as subs}]
  (cond-> subs
    (and repl-port (not eval-how))
    (assoc :eval-how (str "clj-nrepl-eval -p " repl-port " \"<code>\""))))

(def ^:private markdown-sections
  "Group key -> agent-rules heading, in the order they are written."
  [[:non-negotiable "## Non-negotiables"]
   [:conventions "## Conventions (the lint gate fails on WARNINGS, not just errors)"]])

(defn markdown
  "The :human rules as the mirror's sections, grouped and titled.

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
  ([doc block] (splice doc block begin-marker end-marker))
  ([doc block begin-marker end-marker]
   (let [begin (str/index-of doc begin-marker)
         end (str/index-of doc end-marker)]
     (when (or (nil? begin) (nil? end) (> begin end))
       (throw (ex-info (str "markers missing or out of order: " begin-marker " / " end-marker)
                       {:begin begin :end end})))
     (str (subs doc 0 (+ begin (count begin-marker)))
          "\n\n" block "\n\n"
          (subs doc end)))))

(defn sync!
  "Write the rendered rules into the marker block of `doc-path` - from `:rules`
  when given, else from what `rules-for-mirror` says this mirror renders.
  Returns {:path _ :changed? _}; with `:check? true` it only reports."
  [doc-path & {:keys [check? rules]}]
  (let [doc (slurp doc-path)
        updated (splice doc (markdown (or rules (rules-for-mirror doc-path))))
        changed? (not= doc updated)]
    (when (and changed? (not check?))
      (spit doc-path updated))
    {:path doc-path :changed? changed?}))

(def ^:private reserved-flags
  "Flags `prompt-main` consumes itself. Every OTHER --flag becomes a
  {{placeholder}} substitution, so adding a placeholder to the rule source
  needs no change here."
  #{:audience :out})

(defn- parse-flags
  "`--key value` pairs to {:key \"value\"}. Positional args are ignored — this
  entry point has none, and silently dropping one is better than guessing
  which flag it belonged to."
  [args]
  (into {}
        (comp (partition-all 2)
              (keep (fn [[k v]]
                      (when (and k (str/starts-with? k "--"))
                        [(keyword (subs k 2)) v]))))
        args))

(defn prompt-main
  "bb rules-prompt --audience <name> [--out FILE] [--<key> <value>]...

  Writes the PROMPT rendering, which is the only channel that reaches a model
  family reading no rules file at all. Where it goes is the caller's problem
  and deliberately so: Pi replaces its system prompt from a file, a headless
  runner interpolates the block into an HTTP request, and a client with an
  append flag takes it on stdin."
  [& args]
  (let [flags (parse-flags args)
        audiences (into (sorted-set) (mapcat :audience) (load-rules))
        audience (some-> (:audience flags) keyword)]
    (when-not (contains? audiences audience)
      (println (str "rules-prompt: --audience must be one of "
                    (str/join ", " (map name audiences))
                    (when audience (str " — got " (name audience)))))
      (System/exit 1))
    (let [block (rule-block audience
                            (prompt-substitutions
                             (apply dissoc flags reserved-flags)))]
      (if-let [out (:out flags)]
        (do (spit out block)
            (println (str "rules-prompt: " (count (str/split-lines block))
                          " rules for " (name audience) " -> " out)))
        (println block)))))

(defn mirror-paths
  "Every generated mirror `bb rules-sync` writes and `bb rules-check` guards, as
  DATA from three places, nearest first:

  - `resources/rule-mirrors.edn` - what the harness itself ships: its own AGENTS.md
    and the selfcheck project's;
  - `../rule-mirrors.edn` - mirrors of a repository AROUND the harness, read when
    it is there. This repository kept its sandbox's mirror there while the sandbox
    lived outside the harness; nothing lists one today, and the seam stays for a
    repository that wraps the harness;
  - the workspace's `:workspace/rule-mirrors` - the application's AGENTS.md.
    `bb init` records it THERE because it writes nothing into the KIT's clone:
    a clone that `git pull` upgrades must not carry a project's file list.

  The first two are relative to `seed-dir`; the workspace's come back absolute.
  With no workspace given, it is the one `seed-dir` is in by walking up; the
  tasks pass `workspace/current`'s, so a KIT kept outside its workspace, pointed
  at it, lists the application's mirror too."
  ([] (mirror-paths "." (workspace/current)))
  ([seed-dir] (mirror-paths seed-dir (workspace/find-workspace seed-dir)))
  ([seed-dir ws]
   (let [read-list (fn [f] (when (.exists (io/file seed-dir f))
                             (edn/read-string (slurp (io/file seed-dir f)))))]
     (vec (concat (read-list "resources/rule-mirrors.edn")
                  (read-list "../rule-mirrors.edn")
                  (:workspace/rule-mirrors ws))))))

(defn -main
  "bb rules-sync [--check] [--workspace <dir>] [path...]   (default: `mirror-paths`)

  Takes any number of mirrors. A project that generates more than one — this
  repository generates its own and the sandbox's — needs every one of them
  drift-checked, or the unchecked one is correct only by luck. That is the
  failure the rule source exists to prevent, one level out. With none given,
  every mirror the harness, the repository around it and the workspace list -
  the workspace this runs in, or the one `--workspace` / `KIT_WORKSPACE` names."
  [& args]
  (let [ws (workspace/current-or-exit args)
        {:keys [args]} (workspace/split-args args)
        check? (boolean (some #{"--check"} args))
        paths (or (seq (remove #(str/starts-with? % "--") args)) (mirror-paths "." ws))]
    (doseq [path paths]
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
                            resource-name " (with the workspace's overlay, for a project's mirror)"
                            " — run `bb rules-sync`"))
              (System/exit 1))

          check? (println (str "agent-rules in sync: " path))
          changed? (println (str "agent-rules synced: " path))
          :else (println (str "agent-rules already current: " path)))))))
