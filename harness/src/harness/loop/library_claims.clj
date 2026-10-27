(ns harness.loop.library-claims
  "A finding that rests on a claim about what a library does is answered after
  the claim is evaluated on the JVM, and the answer carries the form and its
  result.

  WHY: one project's code reviewer rejected a packet three times on one wrong
  belief about `clojure.string/trim` - that it leaves U+2003 standing; it does
  not - and the Architect settled it on the JVM, but the amendment said
  \"(checked)\" with no form. A form and its result end the dispute at its first
  round and leave the evidence in the record.

  THE HOLD WARNS AND NEVER REFUSES (the person, 2026-10-27). `amend!` reads its
  reason and `retry-with!` each `:architect` feedback's text: one naming a
  function by its namespace and carrying no `=>` is said, and the functions are
  kept on the event as `:unevaluated` - the count that measures whether the code
  reviewer needs its own REPL. A function is named as `ns/fn` after `(`, a
  backquote or a quote, or anywhere when the namespace is qualified
  (`clojure.string/trim`), so prose like `and/or` is not one; a token followed
  by `/` or a file extension is a path. The pure parts are all of it but
  `warn!`."
  (:require
   [clojure.string :as str]))

(def rule
  "The sentence every place that answers a finding says."
  (str "A finding that rests on a claim about what a library does - what a function returns for an input,"
       " what a middleware drops - is answered after the claim is evaluated on the JVM, and the answer"
       " carries the form and its result."))

(def ^:private fn-name
  ;; `ns/fn`: a namespace (dotted or an alias), a function name; not followed by `/` or `.ext` (a path)
  #"(?<![\w./-])((?:[a-z][\w-]*\.)*[a-z][\w-]*)/([a-zA-Z*+!?<>=-][\w*+!?<>=-]*)(?![\w/]|\.\w)")

(defn named-functions
  "The `ns/fn` symbols `text` names as functions, in order, each once."
  [text]
  (let [s (str text)
        m (re-matcher fn-name s)]
    (->> (loop [acc []]
           (if (.find m)
             (let [start (.start m)
                   before (when (pos? start) (.charAt s (dec start)))
                   ns-part (.group m 1)]
               (recur (if (or (str/includes? ns-part ".") (#{\( \` \'} before))
                        (conj acc (symbol ns-part (.group m 2)))
                        acc)))
             acc))
         distinct
         vec)))

(defn evaluated?
  "Does `text` carry a result - `=>`?"
  [text]
  (str/includes? (str text) "=>"))

(defn unevaluated
  "The functions `text` names when it carries no result; `[]` otherwise."
  [text]
  (if (evaluated? text) [] (named-functions text)))

(defn warning
  "One sentence for `text` naming functions and showing no evaluation, or nil.
  `port` is the application's `.nrepl-port` - the Architect's REPL - or nil, when
  the sentence says how to start one."
  [text port]
  (when-let [fns (seq (unevaluated text))]
    (str "the reason names " (str/join ", " fns) " and shows no evaluation - " rule
         (if port
           (str " In the application: clj-nrepl-eval -p " port " '(" (first fns) " ...)', then the form and its result (=>) in the reason.")
           " No .nrepl-port in the application: start its REPL there with clojure -M:test:nrepl, evaluate the form, and put it and its result (=>) in the reason."))))

(defn warn!
  "Print `warning` for `text` when there is one; return the unevaluated functions (`[]` if none)."
  [text port]
  (when-let [w (warning text port)]
    (println (str "  note: " w)))
  (unevaluated text))
