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

  A record from before the ids were kept has nothing to fetch by, and says so."
  (:require
   [clojure.edn :as edn]
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
  "The dispatch steps `reprice` would touch: no cost, at least one id."
  [record]
  (filter #(and (= :dispatch (:step/kind %)) (nil? (:step/cost %)) (seq (:step/generation-ids %)))
          (:run/steps record)))

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
  (let [ids (:step/generation-ids step)
        got (map (fn [id] [id (fetch id)]) ids)
        gens (map second got)
        missing (vec (keep (fn [[id g]] (when (nil? (:total_cost g)) id)) got))
        providers (distinct (keep :provider_name gens))
        tokens (when (every? :native_tokens_prompt gens)
                 (reduce + 0 (map #(+ (:native_tokens_prompt %) (or (:native_tokens_completion %) 0)) gens)))]
    (if (seq missing)
      {:step step :fetched (- (count ids) (count missing)) :missing missing}
      {:step (cond-> (assoc step
                            :step/cost (reduce + 0 (map :total_cost gens))
                            :step/cost-source :repriced)
               (and (nil? (:step/provider step)) (seq providers))
               (assoc :step/provider (str/join " / " providers))
               (and (nil? (:step/tokens step)) tokens)
               (assoc :step/tokens tokens))
       :fetched (count ids)
       :missing []})))

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
                             :line (str "  " nm ": " fetched " of " (count (:step/generation-ids s))
                                        " generation records answered; " (str/join ", " missing)
                                        " did not - left unpriced")}
                            {:step step :changed? true
                             :line (str "  " nm ": " fetched " generation record" (when (not= 1 fetched) "s")
                                        " fetched, cost " (format "$%.6f" (double (:step/cost step)))
                                        " (was —)")}))))))
        steps (mapv :step results)
        changed (count (filter :changed? results))]
    {:record (cond-> (assoc record :run/steps steps)
               (pos? changed) (assoc :run/cost (:cost (report/totals steps))))
     :changed changed
     :lines (vec (keep :line results))}))

(defn -main
  "bb reprice <run.edn> [--profile <profile.edn>]"
  [& args]
  (let [[path] (remove #(str/starts-with? % "--") args)
        prof (some->> args (drop-while #(not= "--profile" %)) second profile/read-profile)]
    (when-not path
      (println "usage: bb reprice <run.edn> [--profile <profile.edn>]")
      (System/exit 2))
    (let [record (edn/read-string {:default tagged-literal} (slurp path))]
      (when-not (:run/id record)
        (println (str "reprice: " path " has no :run/id - it is not a run record"))
        (System/exit 1))
      (if (empty? (unpriced record))
        (println (str "reprice: nothing to reprice in " path " - every dispatch step has a cost, "
                      "or the unpriced ones carry no generation id (a record from before the ids were kept)"))
        (let [{:keys [record changed lines]} (reprice record {:profile prof})]
          (doseq [l lines] (println l))
          (if (pos? changed)
            (do (spit path (pr-str record))
                (println (str "reprice: " changed " step" (when (not= 1 changed) "s") " repriced; " path
                              " rewritten, :run/cost is now " (format "$%.6f" (double (:run/cost record)))
                              ". A document publishing this run's table now fails `bb report-check` until it is re-rendered.")))
            (println "reprice: nothing changed")))))))
