;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.provenance
  "Who actually answered, what it actually cost.

  WHY THIS IS A SECOND CALL. A chat completion carries `id`, `model` and token
  counts — it does NOT name the backend that served it. One OpenRouter slug can
  be answered by several different hosts, and §10 records a community chat
  template corrupting tool-call arguments silently: the model was right and the
  host was wrong, which is undiagnosable unless the host was written down. So
  the completion gives an id and `GET /generation?id=` gives the provider, the
  exact cost and the native token counts.

  NOT EVERY ENDPOINT HAS ONE. Going direct to Anthropic there is no generation
  record and no cost in the response — only `usage`. That is reported as cost
  `nil`, which `harness.report` renders as an unbracketed em-dash meaning
  *nobody knew*, and the footer says how many steps the total actually covers.
  The alternative is a price table in the harness: it would rot, and it would
  produce a number that looks measured and is not. §10 lesson 11 is about
  exactly that mistake.

  THE RECORD CAN LAG THE COMPLETION. It is written asynchronously, so a query
  issued immediately can 404 on an id that is perfectly valid. `fetch!` retries
  briefly and then gives up — a missing cost is `nil`, never a guess, and the
  id is recorded either way so it can be backfilled from the run log."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def defaults
  "MEASURED, not guessed. The first real completion through this namespace
  returned cost nil, because the generation record took **8.6 seconds** to
  appear and the budget was 1.5. Polling a fresh id every 200ms put the
  number on the board.

  A STEADY POLL, NOT A BACKOFF. Backing off is for a server you are afraid of
  overloading; this waits on an asynchronous write that takes roughly a fixed
  time, so a flat one-second interval gets there sooner and reads more
  plainly. Twenty of them is 20s, comfortably past the 8.6 observed.

  It is a real stall, and the escape hatch is already here: `of` records
  `:generation-id` whether or not the fetch succeeded, so a run log can be
  backfilled later rather than the cost being lost."
  {:attempts 20 :interval-ms 1000 :timeout-ms 10000})

(defn generation-endpoint
  "The generation URL for `endpoint`, or nil when that host has no such thing.

  Keyed off the OpenRouter path rather than a hostname allow-list, because a
  self-hosted proxy speaking the same API is a real deployment and the check
  should be about the API, not about who runs it."
  [endpoint]
  (when (and endpoint (str/includes? endpoint "/api/v1"))
    (str (str/replace endpoint #"/+$" "") "/generation")))

(defn fetch!
  "The generation record for `id`, or nil.

  Returns nil rather than throwing on every failure path — a 404 that never
  resolves, a timeout, a dead network. The dispatch already succeeded; failing
  it now because the bookkeeping call did not answer would throw away a
  completion that was paid for."
  ([endpoint id key] (fetch! endpoint id key nil))
  ([endpoint id key opts]
   (when-let [url (and id (generation-endpoint endpoint))]
     (let [{:keys [attempts interval-ms timeout-ms]} (merge defaults opts)]
       (loop [n 1]
         (let [{:keys [status body]}
               (try
                 (http/get url {:query-params {"id" id}
                                :headers (cond-> {}
                                           key (assoc "Authorization" (str "Bearer " key)))
                                :throw false
                                :timeout timeout-ms})
                 (catch Exception _ {:status 0 :body nil}))]
           (cond
             (<= 200 status 299)
             (:data (json/parse-string body true))

             (< n attempts)
             (do (Thread/sleep interval-ms) (recur (inc n)))

             :else nil)))))))

(defn of
  "What a dispatch should record, from the parsed completion and an optional
  generation record.

  `:cost` is nil unless the generation record supplied one. `:tokens` prefers
  the generation record's NATIVE counts — the completion's are normalised and
  can differ from what the host actually billed — and falls back to the
  completion's own, which is what a direct endpoint leaves you with."
  [parsed generation]
  (let [{:keys [in out]} (:usage parsed)]
    ;; The generation record's model is the RESOLVED one — asking for
    ;; `deepseek/deepseek-v4-flash` got back `deepseek-v4-flash-20260423`.
    ;; What you requested is in the profile; what answered belongs here.
    {:model (or (:model generation) (get-in parsed [:raw :model]))
     :provider (:provider_name generation)
     :cost (:total_cost generation)
     :tokens (or (when-let [n (:native_tokens_prompt generation)]
                   (+ n (or (:native_tokens_completion generation) 0)))
                 (when (or in out) (+ (or in 0) (or out 0))))
     :generation-id (:id parsed)}))
