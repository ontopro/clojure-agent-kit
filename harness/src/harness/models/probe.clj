(ns harness.models.probe
  "The pre-run probe: before a paid run, one small call per candidate through the exact role block the
  run will use - endpoint, key, model, effort, provider pin, the run's `max_tokens` - so a run never
  starts on a candidate the host will not serve as asked. `bb security-bake-off` probes every candidate
  before its first reading, `bb security-review --kit` its one before the pass. A few cents a candidate.

  The call is a short puzzle and one tool, `answer`. What one call can answer, it checks:

  - ACCEPTED at this effort - a 400 naming the parameter, or any error: STOP;
  - A TOOL CALL back - the review is tool calls; a reply with none is asked once more, and two without
    one STOP (a tool-less first answer is a known flake, at lower efforts especially);
  - THE PINNED HOST answered - belt and braces: the pin (`:only`, no fallbacks) already makes OpenRouter
    fail rather than reroute, and the reply names the host by its display name, not the pin's slug, so a
    name that does not match is a WARN naming both, never a STOP of a run the pin already held;
  - REASONING DONE - the reply's own count (`usage.completion_tokens_details.reasoning_tokens`, as a
    run's step reads it, `provenance/of`): STOP when the host says 0 - a candidate at `none` is stopped
    here, by design - and \"not reported\", no stop, when the host gives no count;
  - THE ANSWER - information.

  WHAT ONE CALL CANNOT SAY is whether a host ran the level lower than asked: one sample cannot tell that
  from variance. A run's reasoning tokens per completion, over a reading's many completions, can - the
  bake-off's report prints them.

  PURE UP TO THE EDGE: `verdict` reads the calls' results as data; `call!` and `probe!` make them. The
  result is written beside the run's records as `probe-<stamp>.edn`, never a review record (no `:target`)."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.models.agent :as agent]
   [harness.models.provenance :as provenance]
   [harness.models.tools :as tools]))

(def task
  "The question every probe asks, and its answer."
  {:system "You are being checked before a longer job. Work the question out, then give the number through the answer tool - not as text."
   :question "A train leaves at 09:47 and arrives at 14:12 the same day. How many minutes is the journey?"
   :answer 265})

(def registry
  "The probe's one tool, in the shape `tools/declarations` reads."
  {"answer" {:description "Give your final answer to the question."
             :schema {:type "object"
                      :properties {"value" {:type "number" :description "The answer, a number."}}
                      :required ["value"]}
             :fn (fn [_ _] "ok")}})

;; ---------------------------------------------------------------------------
;; pure
;; ---------------------------------------------------------------------------

(defn- norm [s] (str/replace (str/lower-case (str s)) #"[^a-z0-9]" ""))

(defn host-matches?
  "Whether the host a reply names is the one the pin asks for: one's letters and digits begin the
  other's (`Google` and `google-vertex`, `xAI` and `xai`, `OpenAI` and `openai`)."
  [pin host]
  (let [p (norm pin) h (norm host)]
    (boolean (and (seq p) (seq h) (or (str/starts-with? p h) (str/starts-with? h p))))))

(defn verdict
  "One candidate's checks from its calls (`call!`'s maps, in order): `{:stop? _ :checks [{:check :outcome
  :line}]}`, an outcome `:ok`, `:stop`, `:warn` or `:info`."
  [role calls]
  (let [failed (first (filter :error calls))
        second-try? (and failed (not= failed (first calls)))
        answered (filter #(= :answered (:status %)) calls)
        with-tool (first (filter :tool-called? answered))
        last-call (or with-tool (last answered))
        pin (first (get-in role [:params :provider :only]))
        checks
        (if failed
          [{:check :accepted :outcome :stop
            :line (str (cond second-try? "the second try failed: "
                             (zero? (or (:http failed) 0)) "no answer (a timeout or a connection): "
                             :else (str "refused: " (:http failed) " "))
                       (:error failed))}]
          [{:check :accepted :outcome :ok :line "accepted"}
           (if with-tool
             {:check :tool-call :outcome :ok
              :line (if (= with-tool (first answered)) "a tool call" "a tool call on the second try")}
             {:check :tool-call :outcome :stop
              :line (str "no tool call in " (count answered) " tries - the review is tool calls")})
           (cond
             (nil? pin) {:check :host :outcome :info :line (str "host " (or (:host last-call) "not named") ", no pin")}
             (nil? (:host last-call)) {:check :host :outcome :info :line "host not named in the reply"}
             (host-matches? pin (:host last-call)) {:check :host :outcome :ok :line (str "host " (:host last-call))}
             :else {:check :host :outcome :warn
                    :line (str "host " (:host last-call) ", pinned " pin " - check `bb models " (:model role) " --endpoints`")})
           (let [r (:reasoning last-call)]
             (cond
               (nil? r) {:check :reasoning :outcome :info :line "reasoning not reported"}
               (zero? r) {:check :reasoning :outcome :stop
                          :line "0 reasoning tokens, as the host reports - a candidate at none is stopped here by design"}
               :else {:check :reasoning :outcome :ok :line (str "reasoning " r " tokens")}))
           {:check :answer :outcome :info
            :line (if (= (:answer task) (some-> (:answer last-call) double Math/round))
                    "answer right"
                    (str "answer " (pr-str (:answer last-call)) ", not " (:answer task)))}])]
    {:stop? (boolean (some #(= :stop (:outcome %)) checks))
     :checks checks}))

(defn line
  "A candidate's probe as one terminal line."
  [{:keys [id route]} {:keys [stop? checks]} calls]
  (let [costs (seq (keep :cost calls))
        ms (reduce + 0 (keep :ms calls))]
    (str "  probe " id (when route (str " (" route ")")) ": " (if stop? "STOP - " "ok - ")
         (str/join ", " (map #(str (when (= :warn (:outcome %)) "WARN ") (:line %))
                             (if stop? (filter #(= :stop (:outcome %)) checks) checks)))
         (if costs (format "; $%.4f" (double (reduce + 0 costs))) "; cost not reported")
         (format ", %.1fs" (/ ms 1000.0)))))

;; ---------------------------------------------------------------------------
;; the edge
;; ---------------------------------------------------------------------------

(defn- answer-of [args]
  (let [m (if (string? args) (try (json/parse-string args) (catch Exception _ nil)) args)
        v (when (map? m) (or (get m "value") (get m :value)))]
    (cond (number? v) v
          (string? v) (parse-double v)
          :else nil)))

(defn call!
  "One probe call through `role`: `{:status :answered|:error ...}` - the host, the reply's reasoning count,
  the `usage` keys it carried (which hosts report reasoning is learned from them), cost, ms, the answer."
  [role]
  (let [t0 (System/currentTimeMillis)
        [parsed err] (agent/complete! role (:system task) [{:role "user" :content (:question task)}]
                                      (tools/declarations (:shape role) #{"answer"} registry))
        ms (- (System/currentTimeMillis) t0)]
    (if err
      {:status :error :error (:message err) :http (:status err) :ms ms}
      (let [step (provenance/of parsed nil role)
            usage (get-in parsed [:raw :usage])
            call (first (filter #(= "answer" (:name %)) (:tool-calls parsed)))]
        {:status :answered
         :tool-called? (boolean call)
         :answer (some-> call :args answer-of)
         :host (:provider step)
         :reasoning (:reasoning-tokens step)
         :usage-keys (vec (sort (map name (keys usage))))
         :details-keys (vec (sort (map name (keys (:completion_tokens_details usage)))))
         :cost (:cost step)
         :ms ms}))))

(defn probe-one!
  "A candidate's calls - a second only when the first came back with no tool call - and its verdict."
  [role]
  (let [first-call (call! role)
        calls (if (and (= :answered (:status first-call)) (not (:tool-called? first-call)))
                [first-call (call! role)]
                [first-call])]
    {:calls calls :verdict (verdict role calls)}))

(defn probe!
  "Probe every candidate (`{:id :route :model :effort :profile}`), its role the run's: `max-tokens` set as
  the run sets it. Prints a line each, writes `probe-<stamp>.edn` in `out`, and returns
  `{:stop? _ :file _ :results [...]}`."
  [cands {:keys [out max-tokens]}]
  (let [results (vec (for [c cands
                           :let [role (cond-> (:profile c) max-tokens (assoc-in [:params :max_tokens] max-tokens))
                                 {:keys [calls verdict]} (probe-one! role)]]
                       (do (println (line c verdict calls))
                           (merge (select-keys c [:id :route :model :effort :member])
                                  {:stop? (:stop? verdict) :checks (:checks verdict) :calls calls}))))
        stamp (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
        file (str (fs/path out (str "probe-" stamp ".edn")))]
    (fs/create-dirs out)
    (spit file (with-out-str (pp/pprint {:probe/at (str (java.time.Instant/now)) :question (:question task)
                                         :candidates results})))
    {:stop? (boolean (some :stop? results)) :file file :results results}))
