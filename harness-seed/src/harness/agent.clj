;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.agent
  "One model, one conversation, the tools it may call.

  SEPARATE FROM harness.runner ON PURPOSE. The runner is the `AgentRunner`
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
  namespace's — §07 step 5 leaves an escalated workspace standing for exactly
  this reason. `:capped?` says what happened and the caller decides."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [harness.adapter :as adapter]
   [harness.provenance :as provenance]
   [harness.tools :as tools]))

(def defaults
  {:max-iterations 24 :timeout-ms 180000
   ;; A transient error (adapter/transient?) is sent again up to :attempts
   ;; times in all, waiting Retry-After when the host says, else
   ;; :interval-ms times the attempt number. NOTES.md row 9: flex refused
   ;; 3 of 4 requests inside 75s and every one was a failed dispatch.
   :retry {:attempts 4 :interval-ms 2000}})

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
  waiting on one bought nothing. Measured on 2026-09-14 against the D7 Tester
  and Reviewer models: 6.8–10.5s of waiting per completion, against 0.8–2.2s
  for the completion itself — 84–88% of a tool round trip's wall time spent
  on bookkeeping. Each fetch starts the moment its completion returns, so it
  keeps the full retry budget `provenance/fetch!` gives it."
  [role parsed ms-completion key opts]
  {:parsed parsed
   :ms-completion ms-completion
   :record (future
             (let [t (System/currentTimeMillis)
                   gen (provenance/fetch! (:endpoint role) (:id parsed) key opts)]
               {:gen gen :ms (- (System/currentTimeMillis) t)}))})

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

(defn converse!
  "Run `role` to a conclusion, letting it call tools in `ctx`.

  Returns

    {:status   :done | :failed
     :text     the model's final answer
     :messages the whole conversation, in the wire shape
     :calls    every tool call made, in order, each with its :ms
     :turns    one map per completion, {:text _ :calls [...]}: what the model
               said and what it ran, in order — the transcript a run log keeps.
               B1's glm-5.3 made 24 completions and left nothing to say why,
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
         decls (tools/declarations (:shape role)
                                   (or (:tools opts) (tools/for-role :coder)))
         key (some-> (:key-env role) System/getenv)]
     (loop [messages [{:role "user" :content opening}]
            calls []
            turns []
            pending []
            retries 0
            n 1]
       ;; TIMED IN THREE PARTS, because the whole dispatch was the only number
       ;; there was. D7's Tester spent 21.8s an iteration against the Coder's
       ;; 4.7s while sending fewer tokens, and nothing could say whether that
       ;; was the model, the tools, or waiting on bookkeeping.
       (let [t0 (System/currentTimeMillis)
             [parsed err sent-again] (post! role system messages decls timeout-ms retry)
             ms-completion (- (System/currentTimeMillis) t0)
             retries (+ retries sent-again)]
         (cond
           err
           ;; The turns before the failure are kept: they are the diagnosis.
           (settle {:status :failed :error err :messages messages :calls calls
                    :turns turns :iterations n :capped? false :retries retries}
                   pending role)

           :else
           (let [pending (conj pending (fetch-later role parsed ms-completion key opts))]
             (if (= :tool-use (:stop parsed))
               (let [results (mapv (fn [c]
                                     (let [t (System/currentTimeMillis)
                                           r (tools/invoke ctx c)]
                                       (assoc r :ms (- (System/currentTimeMillis) t))))
                                   (:tool-calls parsed))
                     messages (-> messages
                                  (conj (adapter/assistant-message (:shape role) parsed))
                                  (into (adapter/tool-results-message (:shape role) results)))
                     made (mapv (fn [c r] (merge c (select-keys r [:content :error? :args :ms])))
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
