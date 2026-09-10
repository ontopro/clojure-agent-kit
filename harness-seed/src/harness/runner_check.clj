;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.runner-check
  "A conformance check for AgentRunner implementations.

  The project this came from grew four runners — a manual one, a CLI, an
  HTTP endpoint, a second CLI — and each faced only its own unit tests.
  Nothing asserted a shared contract, so five keys drifted onto the result
  map over two months without anyone noticing, and the loop quietly grew
  `if` branches to read them.

  So: run every runner you write through `check-runner`. It returns data
  rather than asserting, so it works from a test and from the REPL."
  (:require
   [babashka.fs :as fs]
   [harness.runner :as runner]
   [harness.shapes :as shapes]))

(defn- check [id pass? detail]
  {:check id :pass? (boolean pass?) :detail detail})

(defn- safely
  "Call a runner, capturing a throw as a value — deciding whether throwing
  is acceptable is this namespace's job, not the JVM's."
  [runner role packet]
  (try {:result (runner/run-agent runner role packet)}
       (catch Exception e {:threw e})))

(defn check-runner
  "Run `runner` through the AgentRunner contract. Returns a vector of
  {:check :pass? :detail}.

  Options:
    :packet         a coder packet to dispatch  (required)
    :expect-writes? true when the runner really writes the files it
                    reports, enabling the :files-exist check. ManualRunner
                    legitimately fails that one — its :done is a human's
                    word, not a filesystem fact — so it is opt-in."
  [runner {:keys [packet expect-writes?]}]
  (let [{:keys [result threw]} (safely runner :coder packet)
        rev-packet (-> packet
                       (dissoc :files/target :repl/port)
                       (assoc :task/role :reviewer
                              :review/diff "diff --git a/x b/x"
                              :review/gate-report {}))
        rev (:result (safely runner :reviewer rev-packet))]
    (cond-> [(check :no-throw (nil? threw)
                    (if threw
                      (str "threw " (.getName (class threw)) ": " (ex-message threw)
                           " — a runner must return :failed, never throw; an"
                           " uncaught exception loses an already-paid-for run")
                      "returns rather than throws"))

             (check :result-shape (shapes/valid-result? result)
                    (or (some-> (shapes/explain-result result) pr-str)
                        "validates against AgentResult"))

             (check :status-enum (contains? #{:done :failed} (:status result))
                    (str ":status was " (pr-str (:status result))))

             (check :files-vector (and (vector? (:files result))
                                       (every? string? (:files result)))
                    (str ":files was " (pr-str (:files result))
                         " — the loop feeds this straight to gate 0"))]

      (= :failed (:status result))
      (conj (check :failed-produces-nothing (empty? (:files result))
                   "a :failed result reports no files"))

      (some? rev)
      (conj (check :reviewer-writes-nothing (empty? (:files rev))
                   "the reviewer role is read-only"))

      (and expect-writes? (= :done (:status result)))
      (conj (let [wt (:repl/worktree packet)
                  missing (remove #(fs/exists? (fs/path wt %)) (:files result))]
              (check :files-exist (empty? missing)
                     (if (seq missing)
                       (str "reported but not on disk under " wt ": " (vec missing)
                            " — gate 0 will report these :missing and the gates"
                            " then fail for an unrelated-looking reason")
                       "every reported file exists in the worktree")))))))

(defn failures
  "Just the checks that did not pass."
  [results]
  (vec (remove :pass? results)))

(defn conforms?
  [results]
  (every? :pass? results))
