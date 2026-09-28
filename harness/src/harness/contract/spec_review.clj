(ns harness.contract.spec-review
  "A review of the CONTRACT before anyone works from it: `bb run-loop spec-review <run-dir>`.

  The Architect writes `spec.edn`; `start` runs this, prints the list and
  stops; the Architect fixes the contract or not, then `start` again. One model
  call, no tools, to the profile's `:spec-reviewer` role — its own role, so
  which model reads specs is a dated selection in the profile, checked by
  `bb profile` to be a different family from the seat's (the Architect's), and
  priced and tuned on its own. Two roles that misread a sentence the same way disagree with
  nothing, and the wrong page ships green.

  It does not block `start`, no dispatched role sees it, and it decides
  nothing. What it gives the Architect is a list, and a number: a spec that
  draws ten findings is not ready. Tried retrospectively on fifteen dispatched
  specs before it came here, it named every ambiguity that had cost a round —
  including one that every role and every gate had passed — and once invented
  one, nine samples out of nine, until the type rule went into its prompt.

  The pure parts are `review-input`, `parse-findings` and `system-prompt`;
  `spec-review!` is the command."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [harness.models.agent :as agent]
            [harness.models.profile :as profile]
            [harness.rules :as rules]))

(def system-prompt
  (str "You review a task contract BEFORE it is given to anyone. Three roles will work from it without "
       "talking to each other: a Coder writes the implementation, a Tester writes property tests from the "
       "contract alone (it never sees the implementation), and a Reviewer checks the result against the "
       "contract. Wherever the contract can be read two ways, each role will pick a reading, and they may "
       "not pick the same one - or, worse, may all pick the same wrong one.\n\n"
       "You are given the task's spec (its interfaces, shapes, property targets, granted dependencies), the "
       "read-only context files the roles are given, and the project's rules. Find:\n\n"
       " (a) \"two-readings\": every place a property target, the slice, or a rule can reasonably be read two "
       "ways by careful readers. Give both readings.\n"
       " (b) \"unmentioned-input\": every input the shapes and signatures ALLOW for which no target says what "
       "happens, where two implementers could reasonably differ. Say what the input is.\n\n"
       "Report only what would make two careful readers produce different code or different tests. Do not "
       "report style, naming, performance, or things the rules or the context files already decide. An empty "
       "list is a valid answer and a good one when the contract is tight.\n\n"
       ;; THE TYPE RULE, IN THE REVIEW'S OWN VOICE. In the rules below it binds the roles; the
       ;; review read it there and still called a vector's order unspecified, every sample. Here,
       ;; and with its corollary, it stopped.
       "TYPES ARE FOLLOWED AS THE LANGUAGE DEFINES THEM. A type named in the contract means exactly what the "
       "language specification says it means - no more, no less - and that meaning is part of the contract "
       "without being written out. A finding that asks for a sentence the type already provides is not a "
       "finding. In doubt, the language specification is the referee, not your sense of what was meant. An "
       "ordered collection built from ordered inputs has the order of its construction: that order is given, "
       "not left open, and a role that imposes another has changed the contract.\n\n"
       "You may reason first. End with exactly one fenced json block:\n\n"
       "```json\n"
       "{\"findings\": [{\"target\": 2, \"kind\": \"two-readings\", \"finding\": \"one sentence\",\n"
       "               \"readings\": [\"reading A\", \"reading B\"]}]}\n"
       "```\n\n"
       "\"target\" is the target's 1-based number, or \"slice\", or \"rules\". For \"unmentioned-input\", "
       "\"readings\" holds the behaviours two implementers might choose."))

(defn review-input
  "The text the review reads: the spec printed as data (an Architect's comments
  in spec.edn are predictions and hints no role was shown, and they are not
  shown here either), each context file's contents, and the rules as the
  Reviewer reads them. `context-files` is a map of path -> contents or nil
  for a file that does not exist yet.

  THE RULES ARE LABELLED AS THE REVIEWER'S, because they are. The heading once
  said *as every role reads them* over a block rendered for one audience, and
  the review believed it: it read the Reviewer's *you write nothing* as binding
  the Coder and the Tester and reported the contradiction with their having
  files to write - in four of one project's twenty-five reviews, a false
  finding the Architect had to read past each time. The label is a one-line
  fix to a sentence that was untrue; whether it removes the finding has not
  been measured against a model."
  [spec rules-text context-files]
  (str "=== THE TASK SPEC ===\n" (with-out-str (pp/pprint spec))
       "\n"
       (str/join "\n" (for [[path text] context-files]
                        (str "=== context file: " path
                             (when-not text " (does not exist yet)") " ===\n" (or text ""))))
       "\n=== THE PROJECT'S RULES, AS THE REVIEWER IS GIVEN THEM ===\n"
       "This is the Reviewer's rendering of the rule source. The Coder and the Tester are each sent their own, "
       "which leaves out the rules addressed to the Reviewer alone - that it writes nothing, that it has a diff "
       "and a green gate report and no REPL - and adds rules about writing and the REPL that are not shown "
       "here. A rule below that speaks to \"you\" speaks to the Reviewer; it is not in conflict with the Coder "
       "and the Tester having files to write, and is not a finding.\n\n"
       rules-text))

(defn parse-findings
  "The findings vector from the answer's last json block; nil when there is no
  block or it has no `findings` key. An empty vector is an answer."
  [text]
  (when-let [block (agent/last-json-block text)]
    (when (contains? block :findings)
      (mapv #(select-keys % [:target :kind :finding :readings]) (:findings block)))))

(defn existing
  "`spec-review.edn` as data, or nil."
  [run-dir]
  (let [f (fs/path run-dir "spec-review.edn")]
    (when (fs/exists? f) (edn/read-string (slurp (str f))))))

(defn review-count
  "How many reviews this run's spec has had, across its amendments."
  [run-dir]
  (count (:reviews (existing run-dir))))

(defn reviews-with-findings
  "How many of those reviews FOUND something - the number the two-review limit counts.

  THE LIMIT IS ABOUT A CONTRACT THAT KEEPS DRAWING FINDINGS, and a review that drew
  none is not evidence of one. Counting every review, a spec whose first reading
  came back clean and whose second drew three findings reached the limit on its
  FIRST amendment: a person's stop on what was in substance the Architect's first
  answer."
  [run-dir]
  (count (filter #(pos? (or (:count %) 0)) (:reviews (existing run-dir)))))

(defn spec-identity
  "The hash a review is OF. `:files/context` is left out: which files a role is
  handed is packet plumbing, not the contract the review reads for two-way
  readings, and removing two paths from that list once made *a different spec*
  and bought a whole review. A comment edit never counted; a target, a slice or
  a shape edit still does. A review written before this change carries a hash of
  the whole spec and reads as stale once: one extra review of a run in flight."
  [spec]
  (hash (dissoc spec :files/context)))

(defn- project-dir [cfg]
  (str (fs/path (:repo/root cfg) (or (:project/subdir cfg) ""))))

(defn read!
  "The read itself, with `role` (a role block, not a file) in the reviewer's
  seat: the run's spec.edn, its context files under the project, the rules as
  the Reviewer reads them; one completion, no tools. Returns the findings (nil
  when the answer had no block), the spec's identity, the model as served, the
  cost, the time, and the text sent - what a bake-off compares candidates on."
  [role run-dir config]
  (let [spec (edn/read-string {:default tagged-literal} (slurp (str (fs/path run-dir "spec.edn"))))
        dir (project-dir config)
        context (into {} (for [f (:files/context spec)
                               :let [p (fs/path dir f)]]
                           [f (when (fs/exists? p) (slurp (str p)))]))
        ;; the rules as the Reviewer reads them: the rule source has no :spec-reviewer
        ;; audience, and the review judges the contract by the rules a Reviewer will
        input (review-input spec (rules/rule-block :reviewer {}) context)
        t0 (System/currentTimeMillis)
        r (agent/converse! role system-prompt input {:dir dir} {:max-iterations 1 :tools #{}})]
    (when (= :failed (:status r))
      (throw (ex-info (if (= :credit (get-in r [:error :harness/error]))
                        (str "the spec review's model call was refused for credit (402) by " (:endpoint role)
                             " - add credit to the account whose key is in " (:key-env role) ", then `start` again")
                        "the spec review's model call failed")
                      {:run-loop/error :spec-review-failed
                       :error (select-keys (:error r) [:harness/error :status])})))
    {:findings (parse-findings (:text r))
     :spec-hash (spec-identity spec)
     :model (some :model (reverse (:steps r)))
     :cost (let [cs (keep :cost (:steps r))] (when (seq cs) (reduce + cs)))
     :ms (- (System/currentTimeMillis) t0)
     :input input
     :text (:text r)}))

(defn spec-review!
  "Read spec.edn, ask the `:spec-reviewer` role, write `spec-review.edn` beside it,
  print the list. Refuses once the run has started: this is a desk step, and
  `start` records the review it finds."
  [{:keys [run-dir config]}]
  (when (fs/exists? (fs/path run-dir "state.edn"))
    (throw (ex-info "state.edn exists — this run has started; the spec review is a desk step, before `start`"
                    {:run-loop/error :already-started :run-dir run-dir})))
  (let [role (get-in (profile/read-profile (:profile config)) [:roles :spec-reviewer])
        _ (when-not role
            (throw (ex-info "the profile has no :spec-reviewer role" {:run-loop/error :no-spec-reviewer :profile (:profile config)})))
        {:keys [findings spec-hash model cost]} (read! role run-dir config)
        out {:findings findings
             :count (count findings)
             ;; the review is OF this spec: an edited spec.edn is reviewed again
             :spec-hash spec-hash
             :no-block? (nil? findings)
             :model model
             :cost cost
             :at (str (java.time.Instant/now))}]
    ;; NO BLOCK, NO FILE: a count of zero from an answer that gave none would be recorded by
    ;; `start` as a clean review.
    (if (nil? findings)
      (println "spec review: the answer had no findings block; nothing written — run it again")
      (do (spit (str (fs/path run-dir "spec-review.edn"))
                ;; THE HISTORY STAYS: every review of this run's spec, so `start` can count
                ;; how many a contract has drawn and stop for a person when it keeps drawing them
                (with-out-str (pp/pprint (assoc out :reviews (conj (vec (:reviews (existing run-dir))) (dissoc out :findings))))))
          (println (format "spec review: %d finding%s%s" (count findings) (if (= 1 (count findings)) "" "s")
                           (if-let [c (:cost out)] (format ", $%.4f" (double c)) "")))
          (doseq [[i {:keys [target kind finding readings]}] (map-indexed vector findings)]
            (println (format "  %2d. [target %s, %s] %s" (inc i) target kind finding))
            (doseq [rd readings] (println (str "        - " rd))))
          ;; A CLEAN READ CARRIES ON, and says so. `start` runs a review and continues on
          ;; zero findings (the stop exists so a list is read, and an empty list has no
          ;; reader); this line once printed the stop's instruction - "then `start`" - above
          ;; a loop that had already started, on seven of a project's twelve runs.
          (println (str "written to " (fs/path run-dir "spec-review.edn")
                        (if (zero? (count findings))
                          " — 0 findings, so the loop carries on"
                          " — fix the contract or not, then `start`, which records the count")))))
    out))

(defn current?
  "Is there a review of `spec` — this spec, not an earlier wording of it — beside it?
  `start` asks before it provisions anything. The hash is `spec-identity`: a comment edit, or a
  change to `:files/context` alone, does not re-review; a target edit does."
  [run-dir spec]
  (let [f (fs/path run-dir "spec-review.edn")]
    (and (fs/exists? f)
         (= (spec-identity spec) (:spec-hash (edn/read-string (slurp (str f))))))))

(defn recorded
  "The review `start` records on the run, from `spec-review.edn` when it is
  there: the count, the cost and the model, not the findings — those are the
  Architect's, and the record shows what the desk step cost and what it saw.

  EVERY REVIEW, NOT ONLY THE LAST. The top-level keys are the review that was
  current when `start` dispatched; `:reviews` is the history the file already
  keeps for the two-review limit. This recorded one review when a review was a
  command typed once before `start`. Then `start` began reviewing, stopping,
  and reviewing again after an amendment - and a run whose spec was read twice
  went into its record with the second reading and not the first, its cost
  understated by a call nobody could find again. `:spec-hash` stays out: it
  means nothing outside the process that computed it."
  [run-dir]
  (let [f (fs/path run-dir "spec-review.edn")]
    (when (fs/exists? f)
      (let [{:keys [count cost model at reviews]} (edn/read-string (slurp (str f)))]
        (cond-> {:event/kind :spec-review :findings count :cost cost :model model :at at}
          ;; only when the file has one, so a review written before the history replays unchanged
          (seq reviews) (assoc :reviews (mapv #(select-keys % [:count :cost :model :at]) reviews)))))))
