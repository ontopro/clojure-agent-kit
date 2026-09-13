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
   [harness.adapter :as adapter]
   [harness.provenance :as provenance]
   [harness.tools :as tools]))

(def defaults
  {:max-iterations 24 :timeout-ms 180000})

(defn- post!
  "One completion. Returns `[parsed nil]` or `[nil error]` — never throws, so a
  429 mid-conversation is something the caller can decide about rather than a
  stack trace that loses the turns already paid for."
  [role system messages tool-decls timeout-ms]
  (let [{:keys [url headers body]} (adapter/request role system messages tool-decls)
        {:keys [status body]}
        (try (http/post url {:headers headers
                             :body (json/generate-string body)
                             :throw false
                             :timeout timeout-ms})
             (catch Exception e
               {:status 0 :body (json/generate-string {:error {:message (ex-message e)}})}))
        decoded (try (json/parse-string body true)
                     (catch Exception _ {:error {:message (str "unparseable body: " body)}}))]
    (if-let [e (adapter/error status decoded)]
      [nil e]
      [(adapter/parse (:shape role) decoded) nil])))

(defn converse!
  "Run `role` to a conclusion, letting it call tools in `ctx`.

  Returns

    {:status   :done | :failed
     :text     the model's final answer
     :messages the whole conversation, for a run log
     :calls    every tool call made, in order
     :steps    one provenance map per completion — model, provider, cost, tokens
     :iterations _  :capped? _  :error _}

  `:steps` is per COMPLETION rather than per conversation because a tool loop
  makes several calls and they can be served by different backends; summing
  them here would throw away the thing §10 says to log."
  ([role system opening ctx] (converse! role system opening ctx nil))
  ([role system opening ctx opts]
   (let [{:keys [max-iterations timeout-ms]} (merge defaults opts)
         decls (tools/declarations (:shape role)
                                   (or (:tools opts) (tools/for-role :coder)))
         key (some-> (:key-env role) System/getenv)]
     (loop [messages [{:role "user" :content opening}]
            calls []
            steps []
            n 1]
       (let [[parsed err] (post! role system messages decls timeout-ms)]
         (cond
           err
           {:status :failed :error err :messages messages :calls calls
            :steps steps :iterations n :capped? false}

           :else
           (let [gen (provenance/fetch! (:endpoint role) (:id parsed) key opts)
                 steps (conj steps (provenance/of parsed gen))]
             (if (= :tool-use (:stop parsed))
               (let [results (mapv #(tools/invoke ctx %) (:tool-calls parsed))
                     messages (-> messages
                                  (conj (adapter/assistant-message (:shape role) parsed))
                                  (into (adapter/tool-results-message (:shape role) results)))
                     calls (into calls (map (fn [c r] (merge c (select-keys r [:content :error? :args])))
                                            (:tool-calls parsed) results))]
                 (if (< n max-iterations)
                   (recur messages calls steps (inc n))
                   ;; Capped, not failed. Files may already be on disk and the
                   ;; gates are about to look at them anyway.
                   {:status :done :text (:text parsed) :messages messages
                    :calls calls :steps steps :iterations n :capped? true}))
               {:status :done :text (:text parsed)
                :messages (conj messages (adapter/assistant-message (:shape role) parsed))
                :calls calls :steps steps :iterations n :capped? false}))))))))
