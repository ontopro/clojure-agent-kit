;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.models.agent
  "One model, one conversation, the tools it may call.

  SEPARATE FROM harness.models.runner ON PURPOSE. The runner is the `AgentRunner`
  protocol — a seam whose job is to be swappable. This is the engine behind one
  implementation of it, and it knows nothing about task packets, roles or
  worktrees: it takes a role profile, a system prompt, an opening message and a
  tool context. That makes it testable against a stub server with no packet, no
  git and no nREPL, which is most of what there is to get wrong.

  THE LOOP IS THE WHOLE THING. Call the model; if it asked for tools, run them,
  append the results in that shape's own message vocabulary, and go round
  again. It stops when the model stops asking, or at the iteration cap.

  THE CAP DOES NOT THROW. A run that hit it may still have written usable
  files, and whether that is a failure is the orchestrator's policy, not this
  namespace's — §07's merge leaves an escalated workspace standing for exactly
  this reason. `:capped?` says what happened and the caller decides."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [harness.models.adapter :as adapter]
   [harness.models.provenance :as provenance]
   [harness.models.tools :as tools]))

(def defaults
  ;; :max-iterations IS THE HARNESS'S DEFAULT, NOT A PROJECT'S POLICY. Twenty-four
  ;; was the seed's number, with no reason recorded. A project sets its own per
  ;; role as `:max-rounds` in its profile (`shapes/RoleProfile`), and the runner
  ;; passes it here; this applies where the profile says nothing.
  {:max-iterations 24 :timeout-ms 180000
   ;; A transient error (adapter/transient?) is sent again up to :attempts
   ;; times in all, waiting Retry-After when the host says, else
   ;; :interval-ms times the attempt number. One endpoint refused 3 of 4
   ;; requests inside 75s, and every one of them was a failed dispatch.
   :retry {:attempts 4 :interval-ms 2000}})

(defn refused?
  "Whether the model declined: a completion in `steps` whose host finish reason is
  `content_filter` or whose native one is `refusal` (Anthropic's word). That is the model's own
  safeguard speaking, not a failure of the call - and it can stop an answer halfway, so a reply
  cut off by it reads like one with no verdict in it unless this is asked.

  OR THE CALL'S ERROR SAYS SO. Through OpenRouter a refusal is a completion's finish reason; at
  Anthropic's own API it is the response's `stop_reason`, which the adapter turns into the call's
  error (`:stop-reason \"refusal\"`) and no step - so a security review refused there was
  recorded as no answer, and its bake-off row said so.

  OR AN HTTP 403 THAT SAYS IT WAS FLAGGED. OpenAI's safeguard answers a request it will not serve with
  a 403 - \"This content was flagged for possible cybersecurity risk\" - an error, not a finish reason;
  two flows of the KIT's own review were recorded as failed calls with no answer, as if broken."
  ([steps] (refused? steps nil))
  ([steps error]
   (boolean (or (= "refusal" (:stop-reason error))
                (and (= 403 (:status error))
                     (re-find #"(?i)\bflagged\b|content.?filter|safeguard|safety system" (str (:message error))))
                (some #(or (= "refusal" (:native-finish-reason %)) (= "content_filter" (:finish-reason %))) steps)))))

(defn last-json-block
  "The LAST fenced ```json block in `text`, parsed with keyword keys; nil when
  there is none, when it does not parse, or when it is not an object. Last,
  not first: a model that thinks out loud may quote a block before it answers
  with one.

  HERE BECAUSE TWO CALLERS READ A MODEL'S ANSWER THIS WAY and they sit on
  opposite sides of the driver: `harness.loop.triage` reads a routing verdict and
  `harness.models.runner` reads the code reviewer's. Triage requires the driver and the
  driver requires the runner, so the parser could not live in either."
  [text]
  (when text
    (when-let [block (last (re-seq #"(?s)```json\s*(.*?)```" text))]
      (let [v (try (json/parse-string (second block) true)
                   (catch Exception _ nil))]
        (when (map? v) v)))))

(defn- post-once!
  "One HTTP request. Returns `[parsed nil headers]` or `[nil error headers]` —
  never throws, so a 429 mid-conversation is something the caller can decide
  about rather than a stack trace that loses the turns already paid for."
  [role system messages tool-decls timeout-ms]
  (let [{:keys [url headers body]} (adapter/request role system messages tool-decls)
        {:keys [status body] resp-headers :headers}
        (try (http/post url {:headers headers
                             :body (json/generate-string body)
                             :throw false
                             :timeout timeout-ms})
             (catch Exception e
               {:status 0 :body (json/generate-string {:error {:message (ex-message e)}})}))
        decoded (try (json/parse-string body true)
                     (catch Exception _ {:error {:message (str "unparseable body: " body)}}))]
    (if-let [e (adapter/error status decoded)]
      [nil e resp-headers]
      [(adapter/parse (:shape role) decoded) nil resp-headers])))

(defn retry-wait-ms
  "How long to wait before attempt `n` (2 upward): the host's Retry-After in
  seconds when it sends one, else `interval-ms` times the attempts so far."
  [headers interval-ms n]
  (let [after (some-> (get headers "retry-after") str/trim parse-long)]
    (if after (* 1000 after) (* interval-ms (dec n)))))

(def redact
  "`provenance/redact`: `x` with the key `secret` replaced by `<key-env>`. Defined there, below every
  caller that sends a key - the generation record and the balance read too."
  provenance/redact)

(defn with-notice
  "The tool results' turn `msgs` (`adapter/tool-results-message`'s) with `notice` for the model to read
  beside them: a text block in the Anthropic shape's one user turn, a user message after the OpenAI
  shape's tool messages."
  [shape msgs notice]
  (if (= :anthropic shape)
    (update-in msgs [(dec (count msgs)) :content] conj {:type "text" :text notice})
    (conj msgs {:role "user" :content notice})))

(def secret-name
  "An environment variable whose name says it holds a secret."
  #"(?i)key|token|secret|password")

(defn secrets
  "The environment's secrets, as `[[name value] ...]`, longest value first: every variable `env` (a
  map, `System/getenv`'s) whose name `secret-name` matches, and the role's own `key-env`, whatever it
  is called - each value of eight characters or more. Computed once a conversation; never printed."
  [env key-env]
  (->> env
       (filter (fn [[k v]] (and (string? v) (>= (count v) 8)
                                (or (= k key-env) (re-find secret-name (str k))))))
       (map (fn [[k v]] [(str k) v]))
       (sort-by (comp - count second))
       vec))

(defn redact-secrets
  "`x` with each of `pairs` (`secrets`) redacted, its value replaced by `<NAME>`."
  [x pairs]
  (reduce (fn [x [k v]] (redact x v k)) x pairs))

(defn- post!
  "One completion, sent again on a transient error up to `attempts` times in
  all. Returns `[parsed error retries]`: the answer or the last error, and how
  many times the request was repeated — a number the record keeps, because a
  dispatch that took four tries is a fact about the endpoint."
  [role system messages tool-decls timeout-ms {:keys [attempts interval-ms]}]
  (loop [n 1]
    (let [[parsed err headers] (post-once! role system messages tool-decls timeout-ms)]
      (if (and err (adapter/transient? err) (< n attempts))
        (do (Thread/sleep (retry-wait-ms headers interval-ms (inc n)))
            (recur (inc n)))
        [parsed err (dec n)]))))

(defn- fetch-later
  "Start fetching `parsed`'s generation record and return at once.

  OFF THE LOOP, because nothing inside the loop reads a generation record and
  waiting on one bought nothing. Measured on 2026-09-14 against one run's
  Tester and code reviewer models: 6.8–10.5s of waiting per completion, against 0.8–2.2s
  for the completion itself — 84–88% of a tool round trip's wall time spent
  on bookkeeping. Each fetch starts the moment its completion returns, so it
  keeps the full retry budget `provenance/fetch!` gives it."
  [role parsed ms-completion key opts]
  {:parsed parsed
   :ms-completion ms-completion
   ;; NOT FETCHED WHEN THE REPLY CARRIED ITS COST: the record was fetched for the cost, and
   ;; since 2026-10 OpenRouter's answers 404 for every id - a wait of the whole retry
   ;; budget per completion for nothing (row 129)
   :record (if (some? (:cost parsed))
             (delay {:gen nil :ms 0})
             (future
               (let [t (System/currentTimeMillis)
                     gen (provenance/fetch! (:endpoint role) (:id parsed) key opts)]
                 {:gen gen :ms (- (System/currentTimeMillis) t)})))})

(defn- settle
  "`result` with its :steps, once every pending generation record is in.

  EVERY EXIT GOES THROUGH HERE — the answer, the cap, and an API error. A
  conversation that fails on its fifth completion has already paid for four,
  and leaving those fetches undereferenced would lose their cost and leave
  threads polling after the dispatch was over. Waits at most as long as the
  slowest pending record, not the sum of them."
  [result pending role]
  (let [t (System/currentTimeMillis)
        steps (mapv (fn [{:keys [parsed ms-completion record]}]
                      (let [{:keys [gen ms]} @record]
                        (assoc (provenance/of parsed gen role)
                               :ms/completion ms-completion
                               :ms/provenance ms)))
                    pending)]
    (assoc result
           :steps steps
           :ms-provenance-wait (- (System/currentTimeMillis) t))))

(defn call-record
  "What a reading - one completion, no tools - records about its call, from
  `converse!`'s result and the role block that made it: the model as served,
  the cost (nil when no generation record had arrived), the completion ids,
  and the endpoint and the NAME of the key's variable, so `bb reprice` can
  fetch the cost later with nothing but the file.

  THE READINGS KEPT NONE OF THIS BEYOND THE COST, and on the first real project
  every generation record lagged past the wait: the plan review, the Blueprint
  review and the spec review each wrote `:cost nil`, with no id to fetch by,
  and the person took the figure off the account balance instead. A dispatch
  step had kept its ids for that purpose since the fifth build; a reading is
  the same call with a different reader.

  THE COST IS THE CALL'S ONLY WHEN EVERY COMPLETION WAS PRICED, as a dispatch's is
  (`runner`): a sum over some of them was written as the reading's cost, and `bb reprice`,
  which fills a nil, never filled it. `:completions` lets the reprice tell a call whose every
  completion has an id from one that lost some."
  [{:keys [steps]} role]
  {:model (some :model (reverse steps))
   :cost (when (and (seq steps) (every? (comp number? :cost) steps)) (reduce + (map :cost steps)))
   :completions (count steps)
   :generation-ids (vec (keep :generation-id steps))
   :endpoint (:endpoint role)
   :key-env (:key-env role)})

(defn converse!
  "Run `role` to a conclusion, letting it call tools in `ctx`.

  Returns

    {:status   :done | :failed
     :text     the model's final answer
     :messages the whole conversation, in the wire shape
     :calls    every tool call made, in order, each with its :ms
     :turns    one map per completion, {:text _ :calls [...]}: what the model
               said and what it ran, in order — the transcript a run log keeps.
               One candidate model made 24 completions and left nothing to say why,
               because only :calls' count survived the dispatch
     :steps    one provenance map per completion — model, provider, cost,
               tokens, and :ms/completion and :ms/provenance
     :ms-provenance-wait  how long the end waited for generation records
     :iterations _  :capped? _  :error _}

  `:steps` is per COMPLETION rather than per conversation because a tool loop
  makes several calls and they can be served by different backends; summing
  them here would throw away the thing §10 says to log."
  ([role system opening ctx] (converse! role system opening ctx nil))
  ([role system opening ctx opts]
   (let [{:keys [max-iterations timeout-ms retry]} (merge defaults opts)
         retry (merge (:retry defaults) retry)
         registry (:registry opts)
         offered (set (or (:tools opts) (tools/for-role :coder)))
         decls (tools/declarations (:shape role) offered registry)
         key (some-> (:key-env role) System/getenv)
         ;; WHAT A TOOL RETURNS IS READ FOR SECRETS before the model or the transcript sees it: the
         ;; Coder's `nrepl_eval` runs on the host with the person's whole environment, and one
         ;; `(System/getenv)` would put every key into the transcript and the committed record
         env-secrets (secrets (into {} (System/getenv)) (:key-env role))]
     (loop [messages [{:role "user" :content opening}]
            calls []
            turns []
            pending []
            retries 0
            n 1]
       ;; TIMED IN THREE PARTS, because the whole dispatch was the only number
       ;; there was. One Tester spent 21.8s an iteration against the Coder's
       ;; 4.7s while sending fewer tokens, and nothing could say whether that
       ;; was the model, the tools, or waiting on bookkeeping.
       (let [t0 (System/currentTimeMillis)
             [parsed err sent-again] (post! role system messages decls timeout-ms retry)
             ;; THE KEY NEVER LEAVES IN AN ERROR: a provider had echoed it back
             err (redact err key (:key-env role))
             ;; NOR IN AN ANSWER: a provider that echoes the key in its text or a tool call's arguments
             ;; had put it into the history sent back, the notes, the transcript and the run's record
             parsed (some-> parsed (redact-secrets env-secrets))
             ms-completion (- (System/currentTimeMillis) t0)
             retries (+ retries sent-again)]
         (cond
           err
           ;; The turns before the failure are kept: they are the diagnosis.
           ;; A CREDIT REFUSAL NAMES WHO REFUSED: the stop it becomes tells a
           ;; person which account to top up, and the error alone knows only
           ;; a status. The variable's name, never the key.
           (settle {:status :failed
                    :error (cond-> err
                             (= :credit (:harness/error err))
                             (assoc :endpoint (:endpoint role) :key-env (:key-env role)))
                    :messages messages :calls calls
                    :turns turns :iterations n :capped? false :retries retries}
                   pending role)

           :else
           (let [pending (conj pending (fetch-later role parsed ms-completion key opts))]
             (if (= :tool-use (:stop parsed))
               ;; THE OFFERED SET IS WHAT RUNS, NOT ONLY WHAT IS DECLARED. A provider can return a call
               ;; for a tool it was never offered: a reading with no tools had read a project's file,
               ;; and only a missing `:targets` kept the code reviewer from writing. Such a call is
               ;; answered as refused and kept marked, a signal like a write outside the packet
               (let [results (mapv (fn [c]
                                     (let [t (System/currentTimeMillis)
                                           r (if (contains? offered (:name c))
                                               (if registry (tools/invoke registry ctx c) (tools/invoke ctx c))
                                               (assoc (tools/err (str (:name c) " was not offered to you - your tools are: "
                                                                      (if (seq offered) (str/join ", " (sort offered)) "none")))
                                                      :id (:id c) :unoffered? true))]
                                       (-> r
                                           (update :content redact-secrets env-secrets)
                                           (assoc :ms (- (System/currentTimeMillis) t)))))
                                   (:tool-calls parsed))
                     messages (-> messages
                                  (conj (adapter/assistant-message (:shape role) parsed))
                                  ;; THE LAST COMPLETION IS SAID TO BE THE LAST. A security review's flow
                                  ;; spent all twenty on reading and never answered: it was not told the
                                  ;; next was its last. `:final-notice` rides with the results before it
                                  (into (cond-> (adapter/tool-results-message (:shape role) results)
                                          (and (:final-notice opts) (= (inc n) max-iterations))
                                          (#(with-notice (:shape role) % (:final-notice opts))))))
                     ;; the provider's :extra-content (Gemini's thought signature) travels back in the
                     ;; assistant message above, not into the transcript and the run log
                     made (mapv (fn [c r] (merge (dissoc c :extra-content) (select-keys r [:content :error? :args :ms :unoffered?])))
                                (:tool-calls parsed) results)
                     calls (into calls made)
                     turns (conj turns {:text (:text parsed) :calls made})]
                 (if (< n max-iterations)
                   (recur messages calls turns pending retries (inc n))
                   ;; Capped, not failed. Files may already be on disk and the
                   ;; gates are about to look at them anyway.
                   (settle {:status :done :text (:text parsed) :messages messages
                            :calls calls :turns turns :iterations n :capped? true :retries retries}
                           pending role)))
               (settle {:status :done :text (:text parsed)
                        :messages (conj messages (adapter/assistant-message (:shape role) parsed))
                        :calls calls :turns (conj turns {:text (:text parsed) :calls []})
                        :iterations n :capped? false :retries retries}
                       pending role)))))))))
