;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.adapter
  "One request shape per model family, and one parsed shape out.

  WHY TWO AND NOT ONE. OpenRouter normalises frontier models to the OpenAI
  chat-completions shape, and MTPLX and oMLX serve that shape locally, so one
  adapter reaches most of what a profile is likely to name. The second exists
  because going DIRECT to Anthropic is a legitimate thing a project does — its
  own credits, no reseller margin — and that endpoint differs in three ways
  OpenRouter's normalisation hides: the system prompt is a top-level parameter
  rather than a message, an `anthropic-version` header is required, and tool
  calls come back as content blocks rather than a `tool_calls` array.

  WHY MULTIMETHODS. `:shape` is profile data, so a third shape should be a new
  method in a new file rather than an edit to a `case` here. The dispatch value
  is the shape keyword and nothing else — not the family, not the endpoint,
  because `google/gemini-3.8-flash` reached through OpenRouter speaks OpenAI and
  the same model reached directly would not.

  THIS NAMESPACE IS NOT STACK-SPECIFIC. It builds and reads JSON; a reader on
  another stack keeps it and rewrites `harness.repair`, `harness.stub` and
  `harness.sigs`."
  (:require
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; requests
;; ---------------------------------------------------------------------------

(defmulti request
  "The HTTP request map for one completion.

  `role` is a profile's role map — `:model`, `:endpoint`, `:params`, `:key-env`.
  `system` is the rendered rule block, `messages` the conversation so far, and
  `tools` the tool declarations in that shape's own vocabulary.

  Returns `{:url _ :headers _ :body _}` as DATA, not a performed request: a
  test can assert the body without a server, and the caller owns the retry,
  timeout and error policy."
  (fn [role _system _messages _tools] (:shape role)))

(defn- api-key
  "The key a role names, or a throw naming the variable.

  Reads `:key-env`; never takes a key as an argument and never puts one in an
  ex-info, because ex-infos get logged."
  [{:keys [key-env]}]
  (when key-env
    (or (not-empty (System/getenv key-env))
        (throw (ex-info "the profile names an unset key variable"
                        {:harness/error :key-env-unset :key-env key-env})))))

(defmethod request :openai
  [{:keys [model endpoint params] :as role} system messages tools]
  {:url (str (str/replace endpoint #"/+$" "") "/chat/completions")
   :headers (cond-> {"Content-Type" "application/json"}
              (:key-env role) (assoc "Authorization" (str "Bearer " (api-key role))))
   ;; The system prompt is just another message here. That is the whole
   ;; difference from :anthropic below, and it is why one shape cannot serve.
   :body (cond-> (merge {:model model
                         :messages (into [{:role "system" :content system}] messages)}
                        params)
           (seq tools) (assoc :tools tools))})

(def anthropic-version
  "Pinned, because the header is required and an unpinned API is a silent
  behaviour change on someone else's schedule."
  "2023-06-01")

(defmethod request :anthropic
  [{:keys [model endpoint params] :as role} system messages tools]
  {:url (str (str/replace endpoint #"/+$" "") "/v1/messages")
   :headers (cond-> {"Content-Type" "application/json"
                     "anthropic-version" anthropic-version}
              (:key-env role) (assoc "x-api-key" (api-key role)))
   ;; `system` is a TOP-LEVEL parameter, not a message with role "system".
   ;; Sending it as a message is accepted and ignored, which is the worst
   ;; possible failure: the rules silently do not reach the model.
   :body (cond-> (merge {:model model
                         :system system
                         :messages messages
                         :max_tokens (or (:max_tokens params) 4096)}
                        (dissoc params :max_tokens))
           (seq tools) (assoc :tools tools))})

;; ---------------------------------------------------------------------------
;; responses
;; ---------------------------------------------------------------------------

(defmulti parse
  "A decoded response body as `{:text _ :tool-calls _ :stop _ :usage _ :id _}`.

  `:tool-calls` are normalised to `{:id _ :name _ :args _}` whatever the wire
  shape was, so `harness.tools` never learns which family it is serving."
  (fn [shape _body] shape))

(defmethod parse :openai
  [_ body]
  (let [msg (get-in body [:choices 0 :message])
        usage (:usage body)]
    {:text (:content msg)
     :tool-calls (vec (for [c (:tool_calls msg)]
                        {:id (:id c)
                         :name (get-in c [:function :name])
                         :args (get-in c [:function :arguments])}))
     :stop (if (seq (:tool_calls msg)) :tool-use :end)
     :usage {:in (:prompt_tokens usage) :out (:completion_tokens usage)}
     :id (:id body)
     :raw body}))

(defmethod parse :anthropic
  [_ body]
  (let [blocks (:content body)
        usage (:usage body)]
    {:text (->> blocks (filter #(= "text" (:type %))) (map :text) (str/join))
     ;; Tool calls are CONTENT BLOCKS here, interleaved with text, rather than
     ;; a separate array. `:input` is already decoded JSON; the OpenAI shape
     ;; hands back a string, so both are passed through untouched and
     ;; harness.tools decodes what it is given.
     :tool-calls (vec (for [b blocks :when (= "tool_use" (:type b))]
                        {:id (:id b) :name (:name b) :args (:input b)}))
     :stop (if (= "tool_use" (:stop_reason body)) :tool-use :end)
     :usage {:in (:input_tokens usage) :out (:output_tokens usage)}
     :id (:id body)
     :raw body}))

(defn error
  "An API error as data, or nil when `status` is a success.

  Returned rather than thrown: a 429 or a 529 is a normal event in a loop that
  retries, and the caller decides. The message is kept because it is the only
  useful part of a failed dispatch and it goes in the run log."
  [status body]
  (when-not (<= 200 status 299)
    {:harness/error :api-error
     :status status
     ;; `(str nil)` is "", not nil, so an `or` over it silently picks the
   ;; empty string and the run log records an error with no message.
     :message (or (get-in body [:error :message])
                  (when-let [e (:error body)] (str e))
                  "no message")}))

;; ---------------------------------------------------------------------------
;; continuing a conversation
;; ---------------------------------------------------------------------------

(defmulti assistant-message
  "The assistant turn to append before tool results go back.

  Shape-specific, and that is why it lives here rather than in the loop. The
  OpenAI shape wants the `tool_calls` array echoed back on an assistant
  message; the Anthropic shape wants the original `content` blocks. Send the
  wrong one and the model is answering a conversation it did not have."
  (fn [shape _parsed] shape))

(defmethod assistant-message :openai
  [_ parsed]
  {:role "assistant"
   :content (:text parsed)
   :tool_calls (vec (for [c (:tool-calls parsed)]
                      {:id (:id c)
                       :type "function"
                       :function {:name (:name c) :arguments (:args c)}}))})

(defmethod assistant-message :anthropic
  [_ parsed]
  ;; The raw content blocks, verbatim. Reconstructing them from the parsed
  ;; shape would drop anything this namespace does not model — a thinking
  ;; block, say — and the API rejects a tool_use whose siblings went missing.
  {:role "assistant" :content (get-in parsed [:raw :content])})

(defmulti tool-results-message
  "The turn carrying tool results back to the model.

  `results` are `{:id _ :content _}`. One message per result in the OpenAI
  shape and one message holding all of them in the Anthropic shape, which is
  why this returns a VECTOR of messages either way."
  (fn [shape _results] shape))

(defmethod tool-results-message :openai
  [_ results]
  (mapv (fn [{:keys [id content]}]
          {:role "tool" :tool_call_id id :content content})
        results))

(defmethod tool-results-message :anthropic
  [_ results]
  [{:role "user"
    :content (mapv (fn [{:keys [id content]}]
                     {:type "tool_result" :tool_use_id id :content content})
                   results)}])
