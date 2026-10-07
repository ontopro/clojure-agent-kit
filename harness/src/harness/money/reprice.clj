;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.money.reprice
  "`bb reprice <run.edn>`: fill the cost of every dispatch step a record left
  unpriced, from the generation records the endpoint has by now.

  WHY A COMMAND AND NOT A LONGER WAIT. A generation record lags its completion
  by seconds, sometimes longer than `provenance/fetch!`'s budget, and a
  dispatch whose record never arrived is written with `:step/cost nil`: the
  report's footer says how many steps it covers, and the sum sits under the
  key's own counter by that step. Most builds met it in a step or more,
  and nothing could put a later-fetched number in the record. The completion's id is the handle; from now on the step keeps its
  ids (`report/dispatch-step`), and this fetches them when asked.

  WHAT IT CHANGES, AND WHAT IT LEAVES. Only a dispatch step with no cost and
  at least one id; only when EVERY id answered, because a partial sum
  presented as a step's cost is the understatement the footer exists to
  prevent. A repriced step is marked `:cost-source :repriced` - the
  endpoint's word, fetched later, not a list price - and `:run/cost` is the
  steps' sum again. THE PROVIDER AND THE TOKENS COME WITH IT: a step whose
  record lagged has neither, and the generation record names who served it
  and what it billed; the first real project's report said \"by provider
  unknown\" over three repriced steps whose records had the names. The
  record is rewritten in place; a document that publishes its table then
  fails `bb report-check` until the person re-renders it, which is the drift
  gate doing its job.

  `record` RUNS IT FIRST, with one request per id and no waiting: by the time
  a run is recorded its last completion is minutes old, so most records are
  whole when written and the command is for the ones that are not.

  A READING'S RECORD TOO. The plan review, the Blueprint review and the spec
  review are one completion each and keep their completion ids, the endpoint
  and the key's variable (`agent/call-record`), so `bb reprice <review.edn>`
  fills a reading's `:cost` and each entry of its `:reviews` history the same
  way; the first real project had all three at `:cost nil` and nothing to
  fetch by. `record` does the same for the run's `:spec-review` event.

  A record from before the ids were kept has nothing to fetch by, and says so."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.models.profile :as profile]
   [harness.models.provenance :as provenance]
   [harness.money.report :as report]))

(defn role-key
  "Which profile role a step name was dispatched under: `:coder-r2` is the
  Coder's, `:triage-r1` the orchestrator's."
  [step-name]
  (let [base (keyword (first (str/split (name step-name) #"-r\d+$")))]
    (case base
      :triage :orchestrator
      :spec-review :spec-reviewer
      base)))

(defn endpoint-of
  "`{:endpoint _ :key-env _}` for a step: from the record's `:run/roles` when
  the record kept them, else from `profile`'s role; nil when neither has it."
  [record profile step-name]
  (let [rk (role-key step-name)
        from-record (get-in record [:run/roles rk])
        from-profile (get-in profile [:roles rk])]
    (some (fn [m] (when (:endpoint m) (select-keys m [:endpoint :key-env])))
          [from-record from-profile])))

(defn unpriced
  "The dispatch steps `reprice` would touch: at least one id, and no cost - or
  no provider, or no tokens. A step priced by the command before it filled
  those two has a cost and nothing else, and the first real project's tables
  said \"by provider unknown\" over them after a reprice that answered
  \"nothing to reprice\"."
  [record]
  (filter #(and (= :dispatch (:step/kind %))
                (seq (:step/generation-ids %))
                (or (nil? (:step/cost %)) (nil? (:step/provider %)) (nil? (:step/tokens %))))
          (:run/steps record)))

(defn- fetch-all
  "Every id's generation record through `fetch`: `{:gens [...] :missing [ids]
  :fetched n}`, an id with no `:total_cost` counting as missing."
  [ids fetch]
  (let [got (map (fn [id] [id (fetch id)]) ids)
        missing (vec (keep (fn [[id g]] (when (nil? (:total_cost g)) id)) got))]
    {:gens (map second got) :missing missing :fetched (- (count ids) (count missing))}))

(defn reprice-step
  "`step` with its cost filled from `fetch` (a fn of an id returning a
  generation record or nil), or unchanged. Returns `{:step _ :fetched n
  :missing [ids]}`: `:missing` names every id that did not answer, and a step
  with any missing is left exactly as it was.

  The provider and the tokens are filled too, ONLY where the step has none:
  the providers the records name, joined when a step's completions were
  served by more than one; the native prompt and completion counts summed,
  when every record carries them. A value the dispatch did record is never
  replaced by a later fetch."
  [step fetch]
  (let [{:keys [gens missing fetched]} (fetch-all (:step/generation-ids step) fetch)
        providers (distinct (keep :provider_name gens))
        tokens (when (every? :native_tokens_prompt gens)
                 (reduce + 0 (map #(+ (:native_tokens_prompt %) (or (:native_tokens_completion %) 0)) gens)))]
    (if (seq missing)
      {:step step :fetched fetched :missing missing}
      {:step (cond-> step
               ;; the cost only where there was none: a reported or list-price cost stays its own
               (nil? (:step/cost step))
               (assoc :step/cost (reduce + 0 (map :total_cost gens)) :step/cost-source :repriced)
               (and (nil? (:step/provider step)) (seq providers))
               (assoc :step/provider (str/join " / " providers))
               (and (nil? (:step/tokens step)) tokens)
               (assoc :step/tokens tokens))
       :fetched fetched
       :missing []})))

(defn- filled
  "What a reprice put on `step` that `before` lacked, as words for the line."
  [before step]
  (str/join ", " (remove nil? [(when (and (nil? (:step/cost before)) (:step/cost step))
                                 (str "cost " (format "$%.6f" (double (:step/cost step)))))
                               (when (and (nil? (:step/provider before)) (:step/provider step))
                                 (str "provider " (:step/provider step)))
                               (when (and (nil? (:step/tokens before)) (:step/tokens step))
                                 (str (:step/tokens step) " tokens"))])))

(defn- lagged
  "The line for ids that did not answer."
  [fetched ids missing]
  (str fetched " of " (count ids) " generation records answered; " (str/join ", " missing)
       " did not - left unpriced"))

(defn reprice-reading
  "A reading's record - or one entry of its `:reviews` history, or the run's
  `:spec-review` event: `:cost`, `:generation-ids`, `:endpoint`, `:key-env` -
  with its cost filled when it was nil and every id answered, marked
  `:cost-source :repriced`; else exactly as it was. Returns
  `{:reading m :changed? bool :line str-or-nil}`; the line says why nothing
  changed, and is nil when there was nothing to do."
  [{:keys [cost generation-ids endpoint key-env] :as m} {:keys [fetch-opts getenv] :or {getenv #(System/getenv %)}}]
  (cond
    (some? cost) {:reading m :changed? false}
    (empty? generation-ids) {:reading m :changed? false
                             :line "no generation id on the reading (written before the ids were kept)"}
    (nil? (provenance/generation-endpoint endpoint))
    {:reading m :changed? false :line (str endpoint " has no generation record to fetch")}
    :else
    (let [key (some-> key-env getenv)
          {:keys [gens missing fetched]} (fetch-all generation-ids #(provenance/fetch! endpoint % key fetch-opts))]
      (if (seq missing)
        {:reading m :changed? false :line (lagged fetched generation-ids missing)}
        (let [c (reduce + 0 (map :total_cost gens))]
          {:reading (assoc m :cost c :cost-source :repriced) :changed? true
           :line (str "cost " (format "$%.6f" (double c)) " (was —)")})))))

(defn reprice-review
  "A reading's whole record: its own cost and every entry of its `:reviews`
  history. Returns `{:record _ :changed n :lines [...]}` as `reprice` does."
  [m opts]
  (let [top (reprice-reading m opts)
        history (map-indexed (fn [i r] [i (reprice-reading r opts)]) (:reviews m))
        changed (count (filter :changed? (cons top (map second history))))]
    {:record (cond-> (:reading top)
               (seq history) (assoc :reviews (mapv (comp :reading second) history)))
     :changed changed
     :lines (vec (concat (when-let [l (:line top)] [(str "  the reading: " l)])
                         (for [[i {:keys [line]}] history :when line]
                           (str "  reading " (inc i) " of the history: " line))))}))

(defn reprice
  "`record` with every unpriced dispatch step repriced that could be. Returns
  `{:record _ :changed n :lines [...]}`; the lines say what happened to each
  step. `opts`: `:profile` (a read profile, for a record whose `:run/roles`
  carry no endpoint), `:fetch-opts` (`provenance/fetch!`'s, for the tests),
  `:getenv` (for the tests; `System/getenv` otherwise)."
  [record {:keys [profile fetch-opts getenv] :or {getenv #(System/getenv %)}}]
  (let [targets (set (map :step/name (unpriced record)))
        results (for [s (:run/steps record)]
                  (if-not (targets (:step/name s))
                    {:step s :line nil :changed? false}
                    (let [{:keys [endpoint key-env]} (endpoint-of record profile (:step/name s))
                          nm (name (:step/name s))]
                      (cond
                        (nil? endpoint)
                        {:step s :changed? false
                         :line (str "  " nm ": no endpoint on the record's :run/roles for " (name (role-key (:step/name s)))
                                    " - pass --profile <profile.edn>")}

                        (nil? (provenance/generation-endpoint endpoint))
                        {:step s :changed? false
                         :line (str "  " nm ": " endpoint " has no generation record to fetch")}

                        :else
                        (let [key (some-> key-env getenv)
                              {:keys [step fetched missing]}
                              (reprice-step s #(provenance/fetch! endpoint % key fetch-opts))]
                          (if (seq missing)
                            {:step s :changed? false
                             :line (str "  " nm ": " (lagged fetched (:step/generation-ids s) missing))}
                            (let [words (filled s step)]
                              (if (str/blank? words)
                                {:step s :changed? false
                                 :line (str "  " nm ": " fetched " generation record" (when (not= 1 fetched) "s")
                                            " fetched, and they name no provider and no native counts - nothing to fill")}
                                {:step step :changed? true
                                 :line (str "  " nm ": " fetched " generation record" (when (not= 1 fetched) "s")
                                            " fetched, " words " (was —)")}))))))))
        steps (mapv :step results)
        changed (count (filter :changed? results))]
    {:record (cond-> (assoc record :run/steps steps)
               (pos? changed) (assoc :run/cost (:cost (report/totals steps))))
     :changed changed
     :lines (vec (keep :line results))}))

(defn- reprice-reading-file!
  "`bb reprice` on a reading's record (`plan-review.edn`, `blueprint-review.edn`,
  `spec-review.edn`): the file rewritten as the readings write it, pretty-printed."
  [path m]
  (let [{:keys [record changed lines]} (reprice-review m {})]
    (doseq [l lines] (println l))
    (if (pos? changed)
      (do (spit path (with-out-str (pp/pprint record)))
          (println (str "reprice: " changed " reading" (when (not= 1 changed) "s") " priced; " path " rewritten")))
      (println "reprice: nothing changed"))))

(defn -main
  "bb reprice <run.edn | review.edn> [--profile <profile.edn>]"
  [& args]
  (let [[path] (remove #(str/starts-with? % "--") args)
        prof (some->> args (drop-while #(not= "--profile" %)) second profile/read-profile)]
    (when-not path
      (println "usage: bb reprice <run.edn | review.edn> [--profile <profile.edn>]")
      (System/exit 2))
    (let [record (edn/read-string {:default tagged-literal} (slurp path))
          reading? (and (nil? (:run/id record))
                        (or (contains? record :generation-ids) (contains? record :reviews)))]
      (when-not (or (:run/id record) reading?)
        (println (str "reprice: " path " has no :run/id and no reading's ids - it is neither a run record nor a review"))
        (System/exit 1))
      (when (fs/exists? (fs/path path "bake-off.edn"))
        (println (str "reprice: " path " is a bake-off folder - run `bb bake-off reprice " path "`, which prices every record and re-renders the table"))
        (System/exit 2))
      (cond
        ;; A reading's record: one call, its ids beside its cost.
        reading? (reprice-reading-file! path record)

        (empty? (unpriced record))
        (println (str "reprice: nothing to reprice in " path " - every dispatch step has its cost, provider "
                      "and tokens, or the ones without carry no generation id (a record from before the ids were kept)"))

        :else
        (let [{:keys [record changed lines]} (reprice record {:profile prof})]
          (doseq [l lines] (println l))
          (if (pos? changed)
            (do (spit path (pr-str record))
                (println (str "reprice: " changed " step" (when (not= 1 changed) "s") " repriced; " path
                              " rewritten, :run/cost is now " (format "$%.6f" (double (:run/cost record)))
                              ". A document publishing this run's table now fails `bb report-check` until it is re-rendered.")))
            (println "reprice: nothing changed")))))))
