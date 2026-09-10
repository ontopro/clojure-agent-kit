(ns harness.rules-test
  "The rule source is a load-bearing asset: every rule an agent follows is
  derived from it, and its prompt rendering is the only channel that reaches
  every model family. These tests guard its shape, both of its renderings, and
  the drift check that keeps the mirror honest."
  (:require
   [babashka.fs :as fs]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.rules :as rules]))

(def rule-source (rules/load-rules))

(def ^:private sample
  [{:id :alpha :group :non-negotiable :audience #{:coder :human}
    :title "Alpha." :text "Eval with {{eval-how}} on port {{repl-port}}."}
   {:id :beta :group :conventions :audience #{:tester}
    :title "Beta." :text "Tester only."}
   {:id :gamma :group :conventions :audience #{:human}
    :title "Gamma." :text "Human only."}])

(deftest rule-source-is-well-formed
  (is (seq rule-source))
  (testing "every rule carries the keys both renderings need"
    (doseq [rule rule-source]
      (is (keyword? (:id rule)) (pr-str rule))
      (is (contains? #{:non-negotiable :conventions} (:group rule)) (pr-str rule))
      (is (set/subset? (:audience rule) #{:coder :tester :human}) (pr-str rule))
      (is (seq (:audience rule)) (str (:id rule) " reaches nobody"))
      (is (string? (:title rule)) (pr-str rule))
      (is (string? (:text rule)) (pr-str rule))))
  (testing "ids are unique — tests and triage reference them"
    (is (= (count rule-source) (count (distinct (map :id rule-source))))))
  (testing "every audience is actually served by a rendering"
    (doseq [a [:coder :tester :human]]
      (is (seq (rules/for-audience rule-source a)) (str "no rules for " a)))))

(deftest precedence-rule-reaches-everyone
  ;; The three-layer problem: a personal global file can contradict a project
  ;; non-negotiable, and nothing tells the model which wins unless a rule does.
  (let [p (first (filter #(= :precedence (:id %)) rule-source))]
    (is (some? p) "the rule source must state its own precedence")
    (is (= #{:coder :tester :human} (:audience p)))))

(deftest render-substitutes
  (is (= "port 7807" (rules/render "port {{repl-port}}" {:repl-port 7807})))
  (testing "an unknown placeholder is left alone, not blanked"
    (is (= "port {{repl-port}}" (rules/render "port {{repl-port}}" {:layer :service}))))
  (testing "no placeholders is a no-op"
    (is (= "plain" (rules/render "plain" {:repl-port 1})))))

(deftest for-audience-filters-and-keeps-order
  (is (= [:alpha] (mapv :id (rules/for-audience sample :coder))))
  (is (= [:beta] (mapv :id (rules/for-audience sample :tester))))
  (is (= [:alpha :gamma] (mapv :id (rules/for-audience sample :human)))
      "declaration order is preserved"))

(deftest rule-block-is-prompt-shaped
  (let [block (rules/rule-block sample :coder {:eval-how "EV" :repl-port 7807})]
    (is (= "- Eval with EV on port 7807." block))
    (testing "no titles and no headings — this goes into a system prompt"
      (is (not (str/includes? block "Alpha.")))
      (is (not (str/includes? block "##")))))
  (testing "an audience with no rules renders empty rather than throwing"
    (is (= "" (rules/rule-block [] :coder {})))))

(deftest markdown-groups-and-filters
  (let [md (rules/markdown sample)]
    (testing "only :human rules reach the mirror"
      (is (str/includes? md "Alpha."))
      (is (str/includes? md "Gamma."))
      (is (not (str/includes? md "Tester only."))))
    (testing "rules land under their group's heading"
      (is (< (str/index-of md "## Non-negotiables")
             (str/index-of md "Alpha.")
             (str/index-of md "## Conventions")
             (str/index-of md "Gamma."))))
    (testing "doc substitutions fill placeholders the mirror has no dispatch for"
      (is (not (str/includes? md "{{"))))))

(deftest splice-requires-both-markers
  (let [doc (str "head\n" rules/begin-marker "\nold\n" rules/end-marker "\ntail")]
    (testing "the block between the markers is replaced, the rest kept"
      (let [out (rules/splice doc "NEW")]
        (is (str/includes? out "head"))
        (is (str/includes? out "NEW"))
        (is (str/includes? out "tail"))
        (is (not (str/includes? out "old"))))))
  (testing "a missing marker throws rather than appending silently"
    (is (thrown-with-msg? Exception #"markers missing"
                          (rules/splice "no markers here" "NEW")))
    (is (thrown-with-msg? Exception #"markers missing"
                          (rules/splice (str rules/begin-marker "\nonly begin") "NEW"))))
  (testing "markers in the wrong order throw"
    (is (thrown-with-msg? Exception #"out of order"
                          (rules/splice (str rules/end-marker "\n" rules/begin-marker)
                                        "NEW")))))

(deftest sync-writes-once-and-detects-drift
  (let [dir (str (fs/create-temp-dir))
        doc (str (fs/path dir "CLAUDE.md"))]
    (spit doc (str "# Doc\n\n" rules/begin-marker "\n" rules/end-marker "\n"))
    (testing "the first sync writes"
      (is (:changed? (rules/sync! doc))))
    (testing "and is idempotent"
      (is (not (:changed? (rules/sync! doc)))))
    (testing "a hand-edit inside the block is detected as drift"
      (spit doc (str/replace (slurp doc) "REPL-first." "REPL-second."))
      (is (:changed? (rules/sync! doc :check? true))))
    (testing "--check reports without writing"
      (let [before (slurp doc)]
        (rules/sync! doc :check? true)
        (is (= before (slurp doc)) "check must not repair the file it is checking")))
    (testing "and sync then repairs it"
      (is (:changed? (rules/sync! doc)))
      (is (str/includes? (slurp doc) "REPL-first.")))))
