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
  record and no cost in the response — only `usage`. Without a price that is
  reported as cost `nil`, which `harness.report` renders as an unbracketed
  em-dash meaning *nobody knew*, and the footer says how many steps the total
  actually covers. A price table in the harness would rot and would produce a
  number that looks measured and is not — §10 lesson 11. So the table is not
  here: a profile may carry `:pricing` for a role, with its source URL and
  date, and `list-price` turns usage into dollars from it. That cost is marked
  `:list-price` and rendered with a `~`, never as the endpoint's word.

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

(defn list-price
  "Dollars for `usage` at `per-mtok` rates, or nil when the uncached input or
  the output count is missing. Cache reads and writes are priced at their own
  rates; a write is at the 5-minute rate unless the usage splits it by
  duration. Checked against the pricing page's own worked examples in the tests."
  [usage per-mtok]
  (let [{:keys [in out cache-read cache-write cache-write-5m cache-write-1h]} usage
        {i :in o :out cr :cache-read w5 :cache-write-5m w1 :cache-write-1h} per-mtok]
    (when (and in out i o cr w5 w1)
      (/ (+ (* in i)
            (* out o)
            (* (or cache-read 0) cr)
            (if (or cache-write-5m cache-write-1h)
              (+ (* (or cache-write-5m 0) w5) (* (or cache-write-1h 0) w1))
              (* (or cache-write 0) w5)))
         1e6))))

(def ^:private endpoint-providers
  "Who serves a direct endpoint, from its host. A fact about where the request
  went, not a guess about who answered; an unknown host stays nil."
  {"api.anthropic.com" "Anthropic API"})

(defn provider-of-endpoint [endpoint]
  (some->> endpoint (re-find #"^https?://([^/:]+)") second endpoint-providers))

(defn of
  "What a dispatch should record, from the parsed completion and an optional
  generation record.

  `:cost` is nil unless the generation record supplied one. `:tokens` prefers
  the generation record's NATIVE counts — the completion's are normalised and
  can differ from what the host actually billed — and falls back to the
  completion's own, which is what a direct endpoint leaves you with.

  With a `role` (the profile's role config), a missing cost is computed from
  the role's `:pricing` when it has one and marked `:cost-source :list-price`,
  a reported one is marked `:reported`, a missing provider is the endpoint's
  host, and `:usage` is kept so the record can re-derive the number."
  ([parsed generation role]
   (let [base (of parsed generation)
         computed (when (and (nil? (:cost base)) (:pricing role))
                    (list-price (:usage parsed) (get-in role [:pricing :per-mtok])))]
     (cond-> (assoc base :usage (:usage parsed))
       (:cost base) (assoc :cost-source :reported)
       computed (assoc :cost computed :cost-source :list-price)
       (nil? (:provider base)) (assoc :provider (provider-of-endpoint (:endpoint role))))))
  ([parsed generation]
   (let [{:keys [in out cache-write cache-read]} (:usage parsed)
         ;; :in is the UNCACHED input; the cached input was sent and billed
         ;; too. D18, the first cached run, reported 1,445 tokens for a
         ;; dispatch that moved 19,423 until this counted the cache.
         in (when (or in cache-write cache-read)
              (+ (or in 0) (or cache-write 0) (or cache-read 0)))]
    ;; The generation record's model is the RESOLVED one — asking for
    ;; `deepseek/deepseek-v4-flash` got back `deepseek-v4-flash-20260423`.
    ;; What you requested is in the profile; what answered belongs here.
     {:model (or (:model generation) (get-in parsed [:raw :model]))
      :provider (:provider_name generation)
      :cost (:total_cost generation)
      :tokens (or (when-let [n (:native_tokens_prompt generation)]
                    (+ n (or (:native_tokens_completion generation) 0)))
                  (when (or in out) (+ (or in 0) (or out 0))))
     ;; THREE MORE FIELDS THE RECORD ALREADY HAD. Reasoning tokens and the
     ;; host's own generation time say whether a slow turn was the model
     ;; thinking; the service tier is the only field that tells OpenAI's flex
     ;; from its standard endpoint — :provider_name is "OpenAI" for both.
      :reasoning-tokens (:native_tokens_reasoning generation)
      :generation-ms (:generation_time generation)
      :service-tier (:service_tier generation)
      :generation-id (:id parsed)})))
