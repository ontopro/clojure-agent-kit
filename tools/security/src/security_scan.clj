(ns security-scan
  "The security pack's two scans over the source, as pure functions: the dependencies'
  published advisories, and a secret committed by mistake. `deps.bb` and `secrets.bb` beside
  this folder run the commands and the queries and hand the text over, so a test gives canned
  text and reads the rows. A row has the shape of `security-check`'s:
  `{:check :status :subject :says :fix}`.

  NEITHER IS A GATE. An advisory is published whatever the diff did, so a dependency that was
  clean yesterday fails today: these run at a stage's end and in `bb health`, never in a run's
  gates. And NO SECRET IS EVER IN A ROW: a hit is the file, the line, the commit and the rule
  that matched - never the matched text, which stays in the scanning function's hands."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; the dependencies: what is resolved, and who brings it
;; ---------------------------------------------------------------------------

(defn parse-deps-list
  "`clojure -X:deps list` as `[{:lib \"group/artifact\" :name \"group:artifact\" :version v}]`:
  every library on the resolved runtime classpath - what ships, not a test or build alias's."
  [text]
  (vec (for [line (str/split-lines (str text))
             :let [[_ g a v] (re-find #"^([^\s/]+)/(\S+) (\S+)" line)]
             :when v]
         {:lib (str g "/" a) :name (str g ":" a) :version v})))

(defn parse-tree
  "`clojure -Stree` as `{lib #{top-level lib ...}}`: for each library included (a `.` line, or
  a top-level one), the libraries in `deps.edn` that bring it - the line to change. A top-level
  library brings itself; an `X` line (omitted, superseded) brings nothing."
  [text]
  (loop [[line & more] (str/split-lines (str text)) top nil acc {}]
    (cond
      (nil? line) acc
      (re-find #"^\S" line) (let [lib (first (str/split line #"\s+"))]
                              (recur more lib (update acc lib (fnil conj #{}) lib)))
      :else (if-let [[_ lib] (re-find #"^\s+\. (\S+)" line)]
              (recur more top (if top (update acc lib (fnil conj #{}) top) acc))
              (recur more top acc)))))

;; ---------------------------------------------------------------------------
;; versions, as Maven orders them closely enough to place one in an advisory's range
;; ---------------------------------------------------------------------------

(defn- tokens [v]
  (mapv #(if (re-matches #"\d+" %) (parse-long %) (str/lower-case %))
        (remove str/blank? (str/split (str v) #"[.\-+_]"))))

(defn compare-versions
  "Negative, zero or positive, as `compare`. Numbers compare as numbers; a qualifier (`alpha`,
  `rc1`) sorts below a number and below its absence, so `1.0-rc1` < `1.0` < `1.0.1`; trailing
  zeros are no difference."
  [a b]
  (loop [[x & xs :as as] (tokens a) [y & ys :as bs] (tokens b)]
    (cond
      (and (empty? as) (empty? bs)) 0
      (empty? as) (cond (every? #(= 0 %) bs) 0 (number? y) -1 :else 1)
      (empty? bs) (cond (every? #(= 0 %) as) 0 (number? x) 1 :else -1)
      (and (number? x) (number? y)) (let [c (compare x y)] (if (zero? c) (recur xs ys) c))
      (number? x) 1
      (number? y) -1
      :else (let [c (compare x y)] (if (zero? c) (recur xs ys) c)))))

(defn fixed-in
  "The version that fixes `version` of the package `pkg` (`group:artifact`) in an OSV advisory:
  the `fixed` event closing the range that contains it, walking each range's events in order.
  `:none` when the range containing it ends in `last_affected` or never closes; nil when no
  range contains it (the advisory lists it by version alone)."
  [vuln pkg version]
  (some (fn [{:keys [ranges]}]
          (some (fn [{:keys [events]}]
                  (loop [[e & more] events from nil]
                    (cond
                      (nil? e) (when (and from (>= (compare-versions version from) 0)) :none)
                      (:introduced e) (recur more (:introduced e))
                      (and from (:fixed e))
                      (if (and (>= (compare-versions version from) 0) (neg? (compare-versions version (:fixed e))))
                        (:fixed e)
                        (recur more nil))
                      (and from (:last_affected e))
                      (if (and (>= (compare-versions version from) 0) (<= (compare-versions version (:last_affected e)) 0))
                        :none
                        (recur more nil))
                      :else (recur more from))))
                (remove #(= "GIT" (:type %)) ranges)))
        (filter #(= pkg (get-in % [:package :name])) (:affected vuln))))

;; ---------------------------------------------------------------------------
;; the dependency rows
;; ---------------------------------------------------------------------------

(def severities ["CRITICAL" "HIGH" "MODERATE" "LOW"])

(defn severity
  "The advisory's severity as GitHub's database rates it, upper case; `UNKNOWN` when the advisory
  carries none."
  [vuln]
  (or (some-> (get-in vuln [:database_specific :severity]) str/upper-case) "UNKNOWN"))

(defn- rank [sev] (or (some (fn [[i s]] (when (= s sev) i)) (map-indexed vector severities)) 4))

(defn accepted
  "The project's acceptance of advisory `id`, from the routes file's `:accepted {id {:reason
  :until \"YYYY-MM-DD\"}}`, as `{:reason :until :expired?}` against `today`; nil when none."
  [accepted-map id today]
  (when-let [{:keys [reason until]} (get accepted-map id)]
    {:reason reason :until until :expired? (boolean (and until (neg? (compare (str until) (str today)))))}))

(defn- advisory-text [{:keys [id aliases sev fix]}]
  (str sev " " id (when-let [cve (first (filter #(str/starts-with? % "CVE-") aliases))] (str " (" cve ")"))
       (case fix nil "" :none ", no fixed version" (str ", fixed in " fix))))

(defn dep-row
  "One vulnerable library: `lib` from `parse-deps-list`, `advisories` its OSV records, `via` the
  top-level libraries that bring it, `accepted-map` and `today` as for `accepted`. A fail when an
  advisory not accepted is HIGH or CRITICAL; a warn otherwise (an accepted advisory says its
  reason; one whose acceptance expired counts as not accepted); the fix names the version line
  that clears every advisory and the line in `deps.edn` to change."
  [{:keys [lib name version]} advisories via accepted-map today]
  (let [as (->> advisories
                (map (fn [v] {:id (:id v) :aliases (:aliases v) :sev (severity v) :fix (fixed-in v name version)
                              :accepted (accepted accepted-map (:id v) today)}))
                (sort-by (comp rank :sev)))
        open (remove #(some-> % :accepted :expired? not) as)
        live (fn [a] (and (:accepted a) (not (:expired? (:accepted a)))))
        fixes (remove #{:none} (keep :fix as))
        to (when (seq fixes) (reduce #(if (pos? (compare-versions %2 %1)) %2 %1) fixes))
        via (sort (disj (set via) lib))
        status (cond (some #(#{"CRITICAL" "HIGH"} (:sev %)) open) :fail
                     :else :warn)]
    {:check :deps
     :status status
     :subject (str lib " " version)
     :says (str (str/join "; " (for [a as]
                                 (str (advisory-text a)
                                      (cond (live a) (str " - accepted until " (:until (:accepted a)) ": " (:reason (:accepted a)))
                                            (:accepted a) (str " - its acceptance ended " (:until (:accepted a)))))))
                (when (some #(= "UNKNOWN" (:sev %)) open) " - an advisory with no severity: read it")
                (if (seq via) (str "; brought by " (str/join ", " via)) "; in deps.edn"))
     :fix (str (if to (str "a version of " lib " at " to " or later") (str "no fixed version of " lib " yet: accept it with a reason, or remove what brings it"))
               (when to
                 (if (seq via)
                   (str " - raise " (str/join " or " via) " to a release that brings it, or add `" lib " {:mvn/version \"" to "\"}` to deps.edn's :deps (a top-level version wins)")
                   " - its version in deps.edn")))}))

(defn dep-rows
  "Every row of the dependency scan: one per vulnerable library, or one ok row when none is.
  `libs` from `parse-deps-list`; `found` `{name [osv-record ...]}`; `tree` from `parse-tree`."
  [libs found tree accepted-map today]
  (let [hits (filter #(seq (found (:name %))) libs)]
    (if (empty? hits)
      [{:check :deps :status :ok :subject (str (count libs) " libraries")
        :says (str "no published advisory for any, OSV on " today)}]
      (vec (sort-by (juxt #(case (:status %) :fail 0 :warn 1 2) :subject)
                    (for [l hits] (dep-row l (found (:name l)) (tree (:lib l)) accepted-map today)))))))

(defn dep-skipped
  "The row when the scan could not run: no network, no Clojure CLI - said, never an ok."
  [why]
  [{:check :deps :status :skipped :subject "dependencies" :says why}])

;; ---------------------------------------------------------------------------
;; secrets: the rules
;; ---------------------------------------------------------------------------

(def prefixed
  "Values that say what they are by their shape: [rule pattern]. A line matching one is a hit
  whatever it is assigned to."
  [[:aws-access-key #"\b(?:AKIA|ASIA)[A-Z2-7]{16}\b"]
   [:github-token #"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}\b|\bgithub_pat_[A-Za-z0-9_]{60,}"]
   [:openrouter-key #"\bsk-or-v1-[0-9a-f]{64}\b"]
   [:anthropic-key #"\bsk-ant-[a-z0-9]+-[A-Za-z0-9_-]{80,}"]
   [:openai-key #"\bsk-(?!or-|ant-)(?:proj-)?[A-Za-z0-9_-]{40,}"]
   [:stripe-key #"\b[sr]k_live_[A-Za-z0-9]{20,}"]
   [:slack-token #"\bxox[abposr]-[A-Za-z0-9-]{10,}"]
   [:google-api-key #"\bAIza[0-9A-Za-z_-]{35}\b"]
   [:private-key #"-----BEGIN [A-Z ]*PRIVATE KEY-----"]
   [:url-credentials #"[a-z][a-z0-9+.-]*://[^\s:/@\"']+:[^\s@/\"']{3,}@"]])

(def assigned
  "A value given to a name that says it is secret - `secret`, `token`, `password`, `api-key`,
  `private-key` - by `=`, `:` or a Clojure `def` or map entry: the name, then the value."
  #"(?i)([a-z0-9_.-]*(?:secret|token|passw(?:or)?d|api[_-]?key|private[_-]?key)[a-z0-9_.-]*)(?:\s*[:=]\s*[\"']?|\s+[\"'])([A-Za-z0-9+/=_.-]{16,})")

(defn entropy
  "Shannon entropy of `s`, bits per character: a word or a placeholder is low, a key is high."
  [s]
  (let [n (count s)]
    (- (reduce + (for [[_ c] (frequencies s)]
                   (let [p (/ c n)] (* p (/ (Math/log p) (Math/log 2)))))))))

(defn wordy?
  "Words and numbers joined by `-`, `_` or `.`, all lower case - a placeholder or a test
  value (`test-session-key-0000`, `replace-with-a-real-secret`), not a key."
  [v]
  (every? #(re-matches #"[a-z]+|[0-9]+" %) (str/split v #"[-_.]+")))

(defn random-looking?
  "Whether a value reads as generated rather than written. ENTROPY ALONE DOES NOT SEPARATE
  THEM: measured on 5,000 random values each, a 32-character hex key scored as low as 3.05
  bits a character and one in five below 3.5, while placeholders scored 3.1 to 3.6. Their
  SHAPE does: hex of 32 characters or more is a key; otherwise a value that is not words
  joined by separators, mixes two of lower case, upper case and digits, and scores above 3."
  [v]
  (or (boolean (re-matches #"[0-9a-fA-F]{32,}" v))
      (and (not (wordy? v))
           (<= 2 (count (filter #(re-find % v) [#"[a-z]" #"[A-Z]" #"[0-9]"])))
           (> (entropy v) 3.0))))

(defn line-rule
  "The rule a line of source breaks, or nil: the first value that names its kind, else a
  random-looking value given to a secret's name."
  [line]
  (or (some (fn [[rule re]] (when (re-find re line) rule)) prefixed)
      (when-let [[_ _ v] (re-find assigned line)]
        (when (random-looking? v) :assigned-high-entropy))))

(defn env-file?
  "A committed environment file: `.env` or `.env.<anything>` in any folder, except the example,
  sample and template that are meant to be committed."
  [path]
  (boolean (and (re-find #"(?:^|/)\.env(?:\.[^/]*)?$" path)
                (not (re-find #"\.(?:example|sample|template)$" path)))))

;; ---------------------------------------------------------------------------
;; secrets: reading history, and the rows
;; ---------------------------------------------------------------------------

(defn added-lines
  "The lines a `git log -p --unified=0 --format='commit %h'` output adds, as `[{:commit :file
  :line :text}]`, `:line` the line's number in that commit's version of the file. Every
  commit's own, so a secret added and removed inside the range is still found."
  [text]
  (loop [[l & more] (str/split-lines (str text)) commit nil file nil n 0 acc []]
    (cond
      (nil? l) acc
      (str/starts-with? l "commit ") (recur more (str/trim (subs l 7)) nil 0 acc)
      (str/starts-with? l "+++ ") (recur more commit (when-not (= l "+++ /dev/null") (str/replace l #"^\+\+\+ b/" "")) 0 acc)
      (str/starts-with? l "@@") (recur more commit file (dec (or (some-> (re-find #"\+(\d+)" l) second parse-long) 1)) acc)
      (and file (str/starts-with? l "+")) (let [n (inc n)]
                                            (recur more commit file n (conj acc {:commit commit :file file :line n :text (subs l 1)})))
      :else (recur more commit file n acc))))

(defn added-files
  "The files a `git log --diff-filter=A --name-only --format='commit %h'` output adds, as
  `[{:commit :file}]`."
  [text]
  (loop [[l & more] (str/split-lines (str text)) commit nil acc []]
    (cond (nil? l) acc
          (str/starts-with? l "commit ") (recur more (str/trim (subs l 7)) acc)
          (str/blank? l) (recur more commit acc)
          :else (recur more commit (conj acc {:commit commit :file (str/trim l)})))))

(def secret-fix
  "What fixes a hit, on one line."
  (str "rotate it where it was issued - removing the line does not remove it from the history, "
       "and the history is what was pushed; then read it from the environment, which the "
       "template's `.gitignore` keeps out of the repository (`.env`, `.env.local`)"))

(defn- allowed [allow-list {:keys [file rule]}]
  (some #(when (and (= file (:file %)) (or (nil? (:rule %)) (= rule (:rule %)))) %) allow-list))

(defn secret-rows
  "Every row of the secrets scan over `lines` (from `added-lines`) and `files` (from
  `added-files`), against the routes file's `:secrets-allowed [{:file :rule :reason}]` (`:rule`
  optional). A hit is a fail, an allowed one a warn naming its reason; none is one ok row.
  The rows carry the file, the line, the commit and the rule - never the text."
  [lines files allow-list range-label]
  (let [hits (concat
              (for [{:keys [file commit]} files :when (env-file? file)]
                {:file file :commit commit :rule :committed-env-file})
              (for [{:keys [text] :as l} lines
                    :let [rule (line-rule text)] :when rule]
                (assoc (select-keys l [:file :line :commit]) :rule rule)))]
    (if (empty? hits)
      [{:check :secrets :status :ok :subject range-label
        :says (str (count lines) " added lines, " (count files) " added files; nothing reads as a secret")}]
      (vec (for [{:keys [file line commit rule] :as h} hits
                 :let [a (allowed allow-list h)]]
             (cond-> {:check :secrets
                      :status (if a :warn :fail)
                      :subject (str file (when line (str ":" line)) (when commit (str " @" commit)))
                      :says (str (name rule) " - the value is not printed"
                                 (when a (str "; allowed: " (:reason a))))}
               (not a) (assoc :fix secret-fix)))))))
