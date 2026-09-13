(ns harness.agent-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.agent :as agent]
   [org.httpkit.server :as srv]))

(defn- with-stub
  "A stub model server. `responses` is a vector of bodies returned in order;
  the last one repeats. Returns [result requests-seen]."
  [responses f]
  (let [seen (atom [])
        n (atom -1)
        stop (srv/run-server
              (fn [req]
                (let [body (json/parse-string (slurp (:body req)) true)]
                  (swap! seen conj (assoc (select-keys req [:uri]) :body body)))
                (let [i (min (swap! n inc) (dec (count responses)))]
                  {:status 200
                   :headers {"Content-Type" "application/json"}
                   :body (json/generate-string (nth responses i))}))
              {:port 0 :legacy-return-value? false})
        port (srv/server-port stop)]
    (try [(f (str "http://127.0.0.1:" port)) @seen]
         (finally (srv/server-stop! stop)))))

(defn- role [endpoint] {:model "m" :shape :openai :endpoint endpoint})

(defn- workspace []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "src"))
    (spit (str (fs/path dir "src" "in.clj")) "CONTENTS")
    dir))

(defn- text-reply [s]
  {:id "gen-1" :choices [{:message {:content s}}] :usage {:prompt_tokens 1 :completion_tokens 1}})

(defn- tool-reply [nm args]
  {:id "gen-2"
   :choices [{:message {:content nil
                        :tool_calls [{:id "call-1" :type "function"
                                      :function {:name nm :arguments (json/generate-string args)}}]}}]
   :usage {:prompt_tokens 1 :completion_tokens 1}})

;; ---------------------------------------------------------------------------
;; the loop
;; ---------------------------------------------------------------------------

(deftest a-plain-answer-ends-in-one-iteration
  (let [[r reqs] (with-stub [(text-reply "done")]
                   #(agent/converse! (role %) "RULES" "go" {:dir "."}))]
    (is (= :done (:status r)))
    (is (= "done" (:text r)))
    (is (= 1 (:iterations r)))
    (is (= [] (:calls r)))
    (is (= 1 (count reqs)))
    (is (= "RULES" (get-in (first reqs) [:body :messages 0 :content]))
        "the rules reached the model")))

(deftest a-tool-call-is-run-and-its-result-goes-back
  (let [dir (workspace)
        [r reqs] (with-stub [(tool-reply "read_file" {:path "src/in.clj"})
                             (text-reply "I read it")]
                   #(agent/converse! (role %) "RULES" "go" {:dir dir}))]
    (is (= :done (:status r)))
    (is (= 2 (:iterations r)))
    (is (= ["read_file"] (mapv :name (:calls r))))
    (is (= "CONTENTS" (:content (first (:calls r)))))
    (testing "and the second request carries the assistant turn plus the tool result"
      (let [msgs (get-in (second reqs) [:body :messages])]
        (is (= 4 (count msgs)) "system, user, assistant, tool")
        (is (= "assistant" (:role (nth msgs 2))))
        (is (= "tool" (:role (nth msgs 3))))
        (is (= "call-1" (:tool_call_id (nth msgs 3)))
            "matched by id, or the model cannot tell which call this answers")
        (is (= "CONTENTS" (:content (nth msgs 3))))))))

(deftest a-failing-tool-still-continues-the-conversation
  ;; The point of §10 lesson 8: the model gets to read the error and try again.
  (let [[r reqs] (with-stub [(tool-reply "read_file" {:path "nope.clj"})
                             (text-reply "ah, it is missing")]
                   #(agent/converse! (role %) "R" "go" {:dir (workspace)}))]
    (is (= :done (:status r)))
    (is (true? (:error? (first (:calls r)))))
    (is (str/starts-with? (get-in (vec (get-in (second reqs) [:body :messages])) [3 :content])
                          "ERROR: "))))

(deftest the-tools-are-declared-on-every-request
  (let [[_ reqs] (with-stub [(text-reply "hi")]
                   #(agent/converse! (role %) "R" "go" {:dir "."}))]
    (is (= 4 (count (get-in (first reqs) [:body :tools]))))))

;; ---------------------------------------------------------------------------
;; the cap
;; ---------------------------------------------------------------------------

(deftest a-model-that-never-stops-is-capped-not-failed
  ;; A capped run may still have written usable files, and the gates are about
  ;; to look at them. Whether that is a failure is the orchestrator's policy.
  (let [dir (workspace)
        [r reqs] (with-stub [(tool-reply "read_file" {:path "src/in.clj"})]
                   #(agent/converse! (role %) "R" "go" {:dir dir}
                                     {:max-iterations 3}))]
    (is (= :done (:status r)) "capped, not failed")
    (is (true? (:capped? r)))
    (is (= 3 (:iterations r)))
    (is (= 3 (count reqs)) "and it really stopped calling")
    (is (= 3 (count (:calls r))))))

;; ---------------------------------------------------------------------------
;; failure
;; ---------------------------------------------------------------------------

(deftest an-api-error-ends-the-conversation-with-what-it-said
  (let [stop (srv/run-server (fn [_] {:status 429 :body (json/generate-string
                                                         {:error {:message "slow down"}})})
                             {:port 0 :legacy-return-value? false})
        port (srv/server-port stop)]
    (try
      (let [r (agent/converse! (role (str "http://127.0.0.1:" port)) "R" "go" {:dir "."})]
        (is (= :failed (:status r)))
        (is (= 429 (get-in r [:error :status])))
        (is (= "slow down" (get-in r [:error :message]))))
      (finally (srv/server-stop! stop)))))

(deftest an-unreachable-endpoint-fails-rather-than-throwing
  (let [r (agent/converse! (role "http://127.0.0.1:1") "R" "go" {:dir "."}
                           {:timeout-ms 300})]
    (is (= :failed (:status r)))
    (is (some? (get-in r [:error :message])))))

;; ---------------------------------------------------------------------------
;; provenance per completion
;; ---------------------------------------------------------------------------

(deftest one-provenance-step-per-completion-not-per-conversation
  ;; A tool loop makes several calls and they can be served by different
  ;; backends. Summing them here would throw away the thing §10 says to log.
  (let [[r _] (with-stub [(tool-reply "read_file" {:path "src/in.clj"})
                          (text-reply "done")]
                #(agent/converse! (role %) "R" "go" {:dir (workspace)}))]
    (is (= 2 (count (:steps r))))
    (is (every? #(= 2 (:tokens %)) (:steps r)) "1 in + 1 out, from the stub")
    (is (every? #(nil? (:cost %)) (:steps r))
        "no generation endpoint on this stub, so no cost — not a zero")))
