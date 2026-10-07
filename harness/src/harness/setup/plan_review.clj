(ns harness.setup.plan-review
  "The plan-review pass of `method.md` §02, run: `bb plan-review [<plan-dir>]`.

  The seven Phase A documents - `source.md`, `00`…`04` and `05-lessons.md` - read cold, as an
  artifact, by the profile's `:spec-reviewer` role: a model that did not write
  them (`bb profile` holds it to a family other than the seat's), one
  completion, no tools, with §02's own checklist of the findings that recur.
  What it gives the Architect is a list, printed with what the call cost and
  written to `<plan>/reviews/plan-review.edn`, beside every other review's raw
  material; the findings and their resolutions go in the overview's table by
  hand, which is where the plan keeps them.

  NOT A GATE. `bb plan-check` is the gate - cheap, and about what the plan
  must no longer carry. This pass is about what the plan says, and a review
  that found nothing proves nothing; it is a desk step, typed once before
  Foundation and again when the plan is revised at a stage boundary. Two
  projects wrote this as a throwaway script each, with the checklist pasted
  in by hand; here the checklist is read from `method.md` at the call, so the
  method and the review cannot drift apart.

  The pure parts are `review-input`, `checklist` and `parse-findings`;
  `review!` is the command."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.models.agent :as agent]
   [harness.models.profile :as profile]
   [harness.setup.plan :as plan]
   [harness.setup.workspace :as workspace]))

(def documents
  "The Phase A documents, in the order they are written and read; the lessons
  last, empty before stage 0 ends and read for the stage it does not answer after."
  ["source.md" "00-overview.md" "01-requirements.md" "02-architecture.md"
   "03-method-and-tooling.md" "04-decision-log.md" "05-lessons.md"])

(def kinds
  "The findings §02 names as the ones that recur, as the `kind` the answer
  uses; the prompt lists them, the checklist explains them."
  ["contradiction" "status-inconsistency" "copy-paste" "missing-nfr" "no-execution-model"
   "deferred-without-owner" "compliance-scope-implicit" "accuracy-semantics"
   "broken-cross-reference" "decided-too-early"])

(def system-prompt
  (str "You review a software project's plan BEFORE its first stage is built - as an artifact, "
       "adversarially, looking for gaps. You did not write it. From it, an Architect will cut task "
       "contracts that three roles work from without talking to each other, and a gap in the plan "
       "surfaces there as a rewrite, stages later.\n\n"
       "You are given the plan's documents in the order they were written - the source the plan "
       "derives from, the overview, the requirements, the architecture, the method as instantiated, "
       "the decision log - and the method's own checklist of the findings that recur in such reviews. "
       "Read every document, then find every place the checklist names, and anything else that would "
       "make two careful readers of this plan build different things.\n\n"
       "Two of the documents come half-written by the method the project adopted: their GIVEN parts "
       "state what adopting fixed and are not the project's to change - do not review those parts, "
       "and do not report that a given part decides something. A decision marked PROVISIONAL with a "
       "gating spike, or OPEN with an owning stage, is the method working as intended and is not a "
       "finding; a decision marked RESOLVED with no evidence behind it is.\n\n"
       "Report only what a reader could act on: name the document and the section, quote the words "
       "that disagree or the sentence that is missing its other half. Do not report style, length, or "
       "what a later stage is explicitly named to decide. An empty list is a valid answer when the "
       "plan is tight.\n\n"
       "You may reason first. End with exactly one fenced json block:\n\n"
       "```json\n"
       "{\"findings\": [{\"kind\": \"contradiction\", \"where\": \"01-requirements.md §4; 02-architecture.md §12\",\n"
       "               \"finding\": \"one sentence\", \"evidence\": \"the words, quoted\"}]}\n"
       "```\n\n"
       "\"kind\" is one of: " (str/join ", " kinds) ", or \"other\"."))

(defn checklist
  "§02's plan-review pass from `method-text` (`method.md`): from its heading to
  the rule that ends the section - the findings that recur, by name, and the
  exit criteria. Throws when the heading is not there: a review without the
  checklist is a different review."
  [method-text]
  (let [from (str/index-of method-text "### The plan-review pass")
        to (some-> from (as-> i (str/index-of method-text "\n---" i)))]
    (when-not from
      (throw (ex-info "method.md has no '### The plan-review pass' heading - the checklist the review sends"
                      {:plan-review/error :no-checklist})))
    (str/trim (subs method-text from (or to (count method-text))))))

(defn review-input
  "The text the review reads: each document in order, named, with one that is
  not there said as such; then the checklist."
  [docs checklist]
  (str (str/join "\n\n" (for [[nm text] docs]
                          (str "=== " nm (when-not text " (not written)") " ===\n" (or text ""))))
       "\n\n=== THE CHECKLIST (method.md §02, the plan-review pass) ===\n" checklist))

(defn parse-findings
  "The findings vector from the answer's last json block; nil when there is no
  block or it has no `findings` key. An empty vector is an answer."
  [text]
  (when-let [block (agent/last-json-block text)]
    (when (contains? block :findings)
      (mapv #(select-keys % [:kind :where :finding :evidence]) (:findings block)))))

(defn read-documents
  "`documents` under `<plan>/docs/` as `[[name text-or-nil] …]`."
  [plan-dir]
  (vec (for [nm documents
             :let [f (fs/path plan-dir "docs" nm)]]
         [nm (when (fs/exists? f) (slurp (str f)))])))

(defn- existing [out-file]
  (when (fs/exists? out-file) (edn/read-string (slurp (str out-file)))))

(defn- print-findings [findings cost]
  (println (format "plan review: %d finding%s%s" (count findings) (if (= 1 (count findings)) "" "s")
                   (if cost (format ", $%.4f" (double cost)) "")))
  (doseq [[i {:keys [kind where finding evidence]}] (map-indexed vector findings)]
    (println (format "  %2d. [%s] %s — %s" (inc i) kind where finding))
    (when (seq (str evidence)) (println (str "        " evidence)))))

(defn read!
  "The read itself, with `role` (a role block, not a file) in the reviewer's
  seat: the documents under `plan-dir` with the checklist from `method-path`,
  one completion, no tools. Returns the findings (nil when the answer had no
  block), the documents that were there, the model as served, the cost, the
  time, and the text sent - what a bake-off compares candidates on. Throws by
  name when no document is there or the call fails."
  [role plan-dir method-path]
  (let [docs (read-documents plan-dir)
        _ (when (every? (comp nil? second) docs)
            (throw (ex-info (str "none of the plan's documents is under " (fs/path plan-dir "docs"))
                            {:plan-review/error :no-documents :plan (str plan-dir)})))
        input (review-input docs (checklist (slurp (str method-path))))
        t0 (System/currentTimeMillis)
        r (agent/converse! role system-prompt input {:dir (str plan-dir)} {:max-iterations 1 :tools #{}})]
    (when (= :failed (:status r))
      (throw (ex-info (if (= :credit (get-in r [:error :harness/error]))
                        (str "the plan review's model call was refused for credit (402) by " (:endpoint role)
                             " - add credit to the account whose key is in " (:key-env role))
                        "the plan review's model call failed")
                      {:plan-review/error :call-failed
                       :error (select-keys (:error r) [:harness/error :status])})))
    (merge {:findings (parse-findings (:text r))
            :documents (vec (for [[nm text] docs :when text] nm))
            :ms (- (System/currentTimeMillis) t0)
            :input input
            :text (:text r)}
           (agent/call-record r role))))

(defn review!
  "Read the documents under `plan-dir`, send them with the checklist to the
  `:spec-reviewer` role of `profile-path`, print the list, write
  `<plan>/reviews/plan-review.edn` - the findings, their count, the model,
  the cost, the documents read, and every earlier review of this plan under
  `:reviews`. `method-path` is where the checklist is read from. Returns the
  written map; an answer with no findings block writes nothing and returns
  `{:no-block? true}` - a count of zero from an answer that gave none would
  read as a clean review."
  [plan-dir profile-path method-path]
  (let [role (get-in (profile/read-profile profile-path) [:roles :spec-reviewer])
        _ (when-not role
            (throw (ex-info (str "the profile " profile-path " has no :spec-reviewer role")
                            {:plan-review/error :no-spec-reviewer :profile (str profile-path)})))
        {:keys [findings documents model cost] :as read} (read! role plan-dir method-path)
        out-file (fs/path plan-dir "reviews" "plan-review.edn")
        out (merge {:findings findings
                    :count (count findings)
                    :documents documents
                    :model model
                    :cost cost
                    :at (str (java.time.Instant/now))}
                   ;; what `bb reprice` fetches a late cost by; the variable's NAME, never a key
                   (select-keys read [:generation-ids :endpoint :key-env]))]
    (if (nil? findings)
      (do (println "plan review: the answer had no findings block; nothing written — run it again")
          {:no-block? true})
      (do (fs/create-dirs (fs/parent out-file))
          (spit (str out-file)
                (with-out-str
                  (pp/pprint (assoc out :reviews (conj (vec (:reviews (existing out-file)))
                                                       (dissoc out :findings))))))
          (print-findings findings (:cost out))
          (println (str "written to " out-file
                        " — resolve each in 00-overview.md §5's table; this is a reading, not a gate"
                        " (`bb plan-check` is the gate)"))
          out))))

(defn -main
  "bb plan-review [<plan-dir>] [--profile <profile.edn>] [--workspace <dir>]

  The plan is the workspace's when none is given; the profile is the plan's
  `profile.edn` (where `bb init` put it) unless `--profile` names another."
  [& args]
  (let [{:keys [flags positional]} (loop [[a & more] (:args (workspace/split-args args)) flags {} positional []]
                                     (cond (nil? a) {:flags flags :positional positional}
                                           (str/starts-with? a "--") (recur (rest more) (assoc flags a (first more)) positional)
                                           :else (recur more flags (conj positional a))))
        plan-arg (first positional)
        ws (if plan-arg (workspace/find-workspace plan-arg) (workspace/current-or-exit args))
        plan-dir (or plan-arg (:workspace/build ws))]
    (when-not plan-dir
      (println "plan-review: no plan given and no workspace.edn at or above here - bb plan-review <plan-dir>")
      (System/exit 2))
    (try
      (let [r (review! (str (fs/normalize (fs/absolutize plan-dir)))
                       (or (get flags "--profile") (str (fs/path plan-dir "profile.edn")))
                       (fs/path (plan/kit-dir ws) "method.md"))]
        (when (:no-block? r) (System/exit 1)))
      (catch clojure.lang.ExceptionInfo e
        (println "plan-review:" (ex-message e))
        (System/exit 1)))))
