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
   [harness.contract.shapes :as shapes]
   [harness.rules :as rules]
   [harness.setup.workspace :as workspace]))

(def rule-source (rules/load-rules))

(def ^:private audiences
  "Every dispatched role, plus the human mirror — derived from shapes/Role
  rather than written out, so a role added to the enum without rules fails
  here instead of silently receiving an empty system prompt. The Reviewer was
  exactly that: a first-class role everywhere except the rule source, and the
  one role that reads no rules file at all."
  (conj (set (rest shapes/Role)) :human))

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
      (is (set/subset? (:audience rule) audiences) (pr-str rule))
      (is (seq (:audience rule)) (str (:id rule) " reaches nobody"))
      (is (string? (:title rule)) (pr-str rule))
      (is (string? (:text rule)) (pr-str rule))))
  (testing "ids are unique — tests and triage reference them"
    (is (= (count rule-source) (count (distinct (map :id rule-source))))))
  (testing "every audience is actually served by a rendering"
    (doseq [a audiences]
      (is (seq (rules/for-audience rule-source a)) (str "no rules for " a)))))

(deftest precedence-rule-reaches-everyone
  ;; The three-layer problem: a personal global file can contradict a project
  ;; non-negotiable, and nothing tells the model which wins unless a rule does.
  (let [p (first (filter #(= :precedence (:id %)) rule-source))]
    (is (some? p) "the rule source must state its own precedence")
    (is (= audiences (:audience p))
        "precedence must reach every audience, including roles added later")))

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

(deftest prompt-substitutions-derives-eval-how
  (testing ":eval-how is derived from :repl-port when absent"
    (is (= "clj-nrepl-eval -p 7807 \"<code>\""
           (:eval-how (rules/prompt-substitutions {:repl-port 7807})))))
  (testing "a caller-supplied :eval-how wins — the bridge is a string, not a dependency"
    (is (= "EV" (:eval-how (rules/prompt-substitutions {:repl-port 7807 :eval-how "EV"})))))
  (testing "no port, no derivation — a half-substituted rule is easier to spot than a wrong one"
    (is (nil? (:eval-how (rules/prompt-substitutions {:layer "service"}))))))

(deftest prompt-main-renders-the-requested-audience
  (let [out (with-out-str (rules/prompt-main "--audience" "tester" "--repl-port" "7807"))]
    (testing "every line is a prompt-shaped bullet"
      (is (every? #(str/starts-with? % "- ") (str/split-lines out))))
    (testing "unreserved flags substitute, so a new placeholder needs no code change"
      (is (str/includes? out "clj-nrepl-eval -p 7807"))
      (is (not (str/includes? out "{{"))))
    (testing "audience filtering reaches the CLI"
      (is (not (str/includes? out "Respect the boundaries of layer")))))
  (testing "--out writes the block rather than printing it"
    (let [f (str (fs/path (fs/create-temp-dir) "prompt.txt"))]
      (rules/prompt-main "--audience" "coder" "--layer" "service" "--out" f)
      (is (str/includes? (slurp f) "Respect the boundaries of layer service"))
      (is (not (str/ends-with? (slurp f) "\n\n"))))))

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
        doc (str (fs/path dir "AGENTS.md"))]
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

(deftest an-unfilled-placeholder-is-named-and-a-rule-s-own-angle-brackets-are-not
  ;; The first copy of the seed sent "<Name your layers…>" to its Coder with every gate green.
  (is (= [{:id :a :placeholder "<Name your layers and their allowed direction here.>"}]
         (rules/unfilled [{:id :a :text "Respect them. <Name your layers and their allowed direction here.>"}
                          {:id :b :text "clj-nrepl-eval -p <port> \"<code>\" finds it"}
                          {:id :c :text "a < b and b > a, and <Text> is short"}])))
  (testing "two in one rule are both named"
    (is (= 2 (count (rules/unfilled [{:id :a :text "<Name the first thing here please.> and <Say the second thing here.>"}])))))
  ;; NOT "the shipped file still has three": the README tells an adopter to fill them, and a test
  ;; that reads the live rule source for standing placeholders goes red the moment they do.
  (testing "a placeholder can only stand in one of the three rules that ship with one - filled or not"
    (is (every? #{:layer-boundaries :shapes-are-the-contract :data-conventions}
                (map :id (rules/unfilled rule-source)))))
  (testing "a filled rule source has none"
    (is (empty? (rules/unfilled (mapv #(assoc % :text "Filled in by the project.") rule-source)))))
  (testing "every role is told the project's data conventions"
    (let [r (first (filter #(= :data-conventions (:id %)) rule-source))]
      (is (= #{:coder :tester :reviewer :human} (:audience r))
          "the Tester too: a seam's guarantee has to reach the role that would test the input it rules out"))))

;; ---------------------------------------------------------------------------
;; the overlay
;; ---------------------------------------------------------------------------

(def ^:private source-fixture
  [{:id :precedence :group :non-negotiable :audience #{:coder :human} :title "These rules win." :text "Given."}
   {:id :layers :group :non-negotiable :audience #{:coder :human} :title "Layers." :text "Respect them. <Name your layers and their allowed direction here.>"}
   {:id :formatting :group :conventions :audience #{:coder :tester :human} :title "Formatting." :text "Given too."}])

(deftest an-overlay-fills-a-placeholder-adds-a-rule-and-cannot-touch-the-kit-s
  (testing "a placeholder rule's text is replaced in place; the rest of the rule is kept"
    (let [merged (rules/overlay source-fixture [{:id :layers :text "Respect them. web -> service -> db."}])]
      (is (= [:precedence :layers :formatting] (mapv :id merged)))
      (is (= {:id :layers :group :non-negotiable :audience #{:coder :human} :title "Layers."
              :text "Respect them. web -> service -> db."}
             (second merged)))
      (is (empty? (rules/unfilled merged)) "and nothing stands unfilled any more")))
  (testing "a whole rule of a new id is appended, in the overlay's order"
    (let [added [{:id :ours :group :conventions :audience #{:coder} :title "Ours." :text "A project rule."}
                 {:id :theirs :group :conventions :audience #{:tester} :title "Theirs." :text "Another."}]]
      (is (= [:precedence :layers :formatting :ours :theirs]
             (mapv :id (rules/overlay source-fixture added))))))
  (testing "an empty overlay is the source"
    (is (= source-fixture (rules/overlay source-fixture []))))
  (testing "a placeholder left as shipped in the overlay still stands - the file is written that way"
    (is (= [:layers] (mapv :id (rules/unfilled (rules/overlay source-fixture [{:id :layers :text (:text (second source-fixture))}]))))))
  (testing "a KIT rule that is not a placeholder is refused BY NAME - that is the check on the given part"
    (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused"
                                  (rules/overlay source-fixture [{:id :precedence :text "Ours win."}
                                                                 {:id :formatting :text "Run cljfmt."}])))]
      (is (= [":precedence is the KIT's rule, not a placeholder - not the overlay's to change"
              ":formatting is the KIT's rule, not a placeholder - not the overlay's to change"]
             (:problems (ex-data e))))))
  (testing "a new rule must be whole, and every problem is named at once"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (rules/overlay source-fixture [{:id :half :text "no group, audience or title"}
                                                        {:id :half :text "twice"}
                                                        {:id :odd :group :other :audience [:coder] :title 1 :text "x"}
                                                        {:id "string" :text "x"}])))
          problems (:problems (ex-data e))]
      (is (some #(str/starts-with? % ":half appears 2 times") problems))
      (is (some #(= ":half: :group must be :non-negotiable or :conventions" %) problems))
      (is (some #(str/starts-with? % ":odd: :audience must be a non-empty subset of") problems)
          "an audience the source does not serve reaches nobody")
      (is (some #(= ":odd: :title must be a string" %) problems))
      (is (some #(= "\"string\" is not a keyword :id" %) problems))))
  (testing "a placeholder entry cannot make the rule undispatchable either"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rules/overlay source-fixture [{:id :layers :text 42}])))))

(defn- workspace-with-overlay!
  "A workspace: the KIT's clone with its own mirror inside it (the default layout), the
  application's mirror listed in `workspace.edn`, and an overlay that fills `:layer-boundaries`."
  []
  (let [ws (str (fs/create-temp-dir))
        mirror (fn [p] (fs/create-dirs (fs/parent (fs/path ws p)))
                 (spit (str (fs/path ws p)) (str "# doc\n\n" rules/begin-marker "\n" rules/end-marker "\n"))
                 (str (fs/path ws p)))]
    (fs/create-dirs (fs/path ws "xyx-plan"))
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/kit "clojure-agent-kit" :workspace/app "xyx-app" :workspace/plan "xyx-plan"
                   :workspace/rule-mirrors ["xyx-app/AGENTS.md"]
                   :workspace/rules-overlay "xyx-plan/rules.edn"}))
    (spit (str (fs/path ws "xyx-plan" "rules.edn"))
          (pr-str [{:id :layer-boundaries :text "Respect the boundaries of layer {{layer}}. FILLED BY THE PROJECT."}
                   {:id :ours :group :conventions :audience #{:coder :human} :title "Ours." :text "A project rule."}]))
    {:ws ws
     :app-mirror (mirror "xyx-app/AGENTS.md")
     :kit-mirror (mirror "clojure-agent-kit/harness/AGENTS.md")}))

(deftest a-workspace-s-rules-are-the-source-under-its-overlay
  (let [{:keys [ws app-mirror kit-mirror]} (workspace-with-overlay!)
        found (rules/project-rules (workspace/find-workspace ws))]
    (testing "project-rules merges the overlay the workspace names"
      (is (= (str (fs/path ws "xyx-plan" "rules.edn"))
             (:workspace/rules-overlay (workspace/find-workspace ws)))
          "the overlay's path comes back absolute, like the folders")
      (is (= (conj (mapv :id (rules/shipped)) :ours) (mapv :id found)))
      (is (= [:shapes-are-the-contract :data-conventions] (mapv :id (rules/unfilled found)))
          "what the overlay filled no longer stands; what it left as shipped does"))
    (testing "no workspace, or one from before overlays, reads the source alone"
      (is (= (rules/shipped) (rules/project-rules nil)))
      (is (= (rules/shipped) (rules/project-rules {:workspace/app "x"}))))
    (testing "an overlay the workspace names and does not have is a fault, not the source"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not there"
                            (rules/project-rules {:workspace/rules-overlay (str (fs/path ws "nowhere.edn"))}))))
    (testing "the application's mirror renders from the merged set"
      (rules/sync! app-mirror)
      (is (str/includes? (slurp app-mirror) "FILLED BY THE PROJECT."))
      (is (str/includes? (slurp app-mirror) "**Ours.** A project rule."))
      (is (not (:changed? (rules/sync! app-mirror :check? true))) "and is then in sync"))
    (testing "the KIT's own mirror, inside the same workspace, renders from the source alone"
      (rules/sync! kit-mirror)
      (is (not (str/includes? (slurp kit-mirror) "FILLED BY THE PROJECT.")))
      (is (str/includes? (slurp kit-mirror) "<Name your layers and their allowed direction here"))
      (is (= (rules/shipped) (rules/rules-for-mirror kit-mirror))))
    (testing "a mirror given its rules renders those"
      (rules/sync! kit-mirror :rules sample)
      (is (str/includes? (slurp kit-mirror) "Gamma.")))))
