(ns harness.setup.stage-amend
  "`bb stage-amend Q-n`: open an amendment of the stage in progress - an entry `{:q :opened}` in its
  gates record's `:stage/amendments`, the time now in the project's zone. A stage amended after its
  packets ran is a pass of its own (method §04): the stage plan amended and read again, approved
  again with its cap, the blueprint amended and read again, signed off again - `bb next` asks for
  each. The entry is written by this command, never by hand, so it is opened before the readings
  that answer it: an `:opened` written after them would leave `bb next` asking for them forever.
  The two sign-offs inside it are the person's. Refused by name: no stage, a stage not yet signed
  off (amend its documents in place), a closed stage (the change goes to a later stage), an
  amendment already open (one pass at a time), a Q row `source.md` §3 does not carry."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [harness.setup.next :as next]
   [harness.setup.stages :as stages]
   [harness.setup.workspace :as workspace]
   [rewrite-clj.node :as n]
   [rewrite-clj.parser :as p]
   [rewrite-clj.zip :as z]))

(defn q-row?
  "Whether `source-text` (a build's `docs/source.md`) carries the Q row `q` in a table."
  [source-text q]
  (boolean (re-find (re-pattern (str "(?m)^\\|\\s*" (java.util.regex.Pattern/quote q) "\\s*\\|")) source-text)))

(defn refusal
  "Why stage `id`, its gates record `gates`, may not be amended for `q` now; nil when it may."
  [id gates q source-text]
  (let [open (stages/open-amendment gates)
        gates-file (stages/rel id "-gates.edn")]
    (cond
      (not (re-matches #"Q-\d+" (str q)))
      (str "an amendment names the Q row that asks for it, Q-n (source.md §3); got " (pr-str q))

      (nil? gates)
      (str "stage " id " has no gates record (" gates-file ")")

      (:stage/closed gates)
      (str "stage " id " is closed (" (stages/gate-day (:stage/closed gates) nil) ") - a closed stage is not amended; "
           "the change goes to a later stage: the scope's deferred list, with its reason and the stage that will take it (method §04)")

      (nil? (:blueprint/signed gates))
      (str "stage " id "'s blueprint is not signed off - amend its plan and blueprint in place before the sign-off")

      open
      (str "stage " id " has an amendment open (" (:q open) ", opened " (stages/gate-day {:at (:opened open)} nil)
           ", not signed) - fold " q " into it, or finish it first: one pass at a time")

      (not (q-row? (or source-text "") q))
      (str q " is not a row of docs/source.md §3 - the person's words go there first, as a Q row"))))

(defn- edn-text
  "`x` as EDN text with no commas between a map's entries - as a person types a gates record; `pr-str`
  puts them in."
  [x]
  (cond (map? x) (str "{" (str/join " " (map edn-text (apply concat x))) "}")
        (vector? x) (str "[" (str/join " " (map edn-text x)) "]")
        :else (pr-str x)))

(defn- node [x] (p/parse-string (edn-text x)))

(defn with-amendment
  "The gates record's text `text` with `entry` appended to its `:stage/amendments`, every other byte
  kept - the person's comments included. The key is added at the end of the map when the record has
  none - placed by rewrite-clj on the map's node, never by splicing the text at a brace."
  [text entry]
  (let [zloc (z/of-string text {:track-position? false})
        m (if (z/map? zloc) zloc (z/find-next zloc z/next z/map?))
        v (z/get m :stage/amendments)]
    (cond
      (nil? v)
      (-> m
          (z/append-child* (n/newlines 1))
          (z/append-child* (n/spaces 1))
          (z/append-child* (n/comment-node "; a stage amended after its packets ran, one pass at a time: `bb stage-amend Q-n` opens it"))
          (z/append-child* (n/newlines 1))
          (z/append-child* (n/spaces 1))
          (z/append-child* (n/keyword-node :stage/amendments))
          (z/append-child* (n/spaces 1))
          (z/append-child* (node [entry]))
          z/root-string)

      (or (and (= :token (z/tag v)) (nil? (z/sexpr v)))
          (and (z/vector? v) (nil? (z/down v))))
      (z/root-string (z/replace v (node [entry])))

      :else
      (-> v
          (z/append-child* (n/newlines 1))
          (z/append-child* (n/spaces 20))
          (z/append-child* (node entry))
          z/root-string))))

(defn amend!
  "Open the amendment `q` of the workspace `ws`'s stage in progress. `{:ok line}` or `{:refused why}`."
  [ws q]
  (let [build (:workspace/build ws)
        [id _] (last (stages/folders (stages/stages-dir build)))
        gates-path (when id (stages/document build id "-gates.edn"))
        text (when (and gates-path (fs/exists? gates-path)) (slurp (str gates-path)))
        gates (when text (try (edn/read-string text) (catch Exception _ nil)))
        source (fs/path build "docs" "source.md")
        why (cond (nil? id) "the build has no stage folder"
                  (and text (nil? gates)) (str (stages/rel id "-gates.edn") " does not read as EDN")
                  :else (refusal id gates q (when (fs/exists? source) (slurp (str source)))))]
    (if why
      {:refused why}
      (let [at (stages/now-at (:workspace/time-zone ws))]
        (spit (str gates-path) (with-amendment text {:q q :opened at}))
        {:ok (str "stage " id " amended for " q ", opened " at ", in " (stages/rel id "-gates.edn"))}))))

(defn -main
  "bb stage-amend Q-n [--workspace <dir>]"
  [& args]
  (let [ws (workspace/current-or-exit args)
        {:keys [args]} (workspace/split-args args)
        q (first args)]
    (if-not (and (:workspace/build ws) q)
      (do (println "usage: bb stage-amend Q-n [--workspace <dir>] - open an amendment of the stage in progress, for the Q row that asks for it")
          (System/exit 2))
      (let [{:keys [ok refused]} (amend! ws q)]
        (if refused
          (do (println (str "stage-amend: refused - " refused)) (System/exit 1))
          (let [a (next/next-action (next/facts ws))]
            (println (str "stage-amend: " ok))
            (println (str "next: step " (:step a) ", " (:owner a) " - " (:run a)))))))))
