(ns harness.setup.plan-test
  "The shipped plan template as a fixture: which documents there are, and how
  many marks each carries for a project to fill. The check over a filled plan
  (`bb plan-check`) is written against these numbers, so a change to the
  template changes them here deliberately - and against a workspace whose
  plan is filled, then broken one way at a time."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.loop.driver :as driver]
   [harness.rules :as rules]
   [harness.setup.app :as app]
   [harness.setup.init :as init]
   [harness.setup.plan :as plan]
   [harness.setup.template :as template]
   [harness.setup.workspace :as workspace]))

;; The tests run in harness/, so the template is the real one beside them.
(def kit-dir (str (fs/parent (fs/real-path "."))))
(def template-dir (str (fs/path kit-dir "plan-template")))

(deftest a-placeholder-is-an-angle-bracket-a-project-fills
  (is (= ["<Project>" "<YYYY-MM-DD>"] (plan/placeholders "# <Project>\n\n**Last updated:** <YYYY-MM-DD>")))
  (testing "not a path token the workspace already knows"
    (is (= ["<seat>"] (plan/placeholders "`<name>-build/profile.edn` for `<seat>`, via `<kit>/harness`"))))
  (testing "not documentation of a command inside a fence, nor an autolink or a comment"
    (is (= [] (plan/placeholders "```bash\nclj-nrepl-eval -p <port> \"<code>\"\n```\n<https://x> <!-- note -->")))
    (is (= ["<after>"] (plan/placeholders "```\n<in>\n```\n<after>\n```\n<in again>\n```")))
    (is (= ["<Store>"] (plan/placeholders "| Storage | `<Store>` |")) "inline code counts: a seam's name is a decision")))

(deftest the-shipped-template-carries-exactly-these-marks
  ;; The fixture. `bb init` copies every one of these into `<name>-build/docs/`.
  (is (= {"00-overview.md" 19
          "01-requirements.md" 14
          "02-architecture.md" 35
          "03-method-and-tooling.md" 42
          "04-decision-log.md" 12
          "05-lessons.md" 11
          "README.md" 1
          "source.md" 11
          "stages/stage-0-spike-template.md" 11
          "stages/stage-N-blueprint-template.md" 9
          "stages/stage-N-gates-template.edn" 2
          "stages/stage-N-template.md" 25
          "stages/stage-prerelease-template.md" 12
          "stages/stage-release-template.md" 8}
         (plan/placeholder-counts template-dir)))
  (is (= (keys (plan/placeholder-counts template-dir)) (init/plan-template-files (str (fs/parent template-dir))))
      "what bb init lists is what is counted, source.md and the README included"))

(deftest the-given-parts-ask-nothing
  ;; 02 and 03 are in three parts; the first states what adopting the KIT fixed, as references.
  ;; Past the document's own header and its opening note, it carries no mark to fill.
  (doseq [doc ["02-architecture.md" "03-method-and-tooling.md"]]
    (let [text (slurp (str (fs/path template-dir doc)))
          from (str/index-of text "\n---\n")
          to (str/index-of text "# Part 2")]
      (is (and from to (< from to)) (str doc " has a header rule and a Part 2"))
      (is (= [] (plan/placeholders (subs text from to))) (str doc "'s given part is written, not asked")))))

(deftest the-given-security-section-names-the-pin-it-was-verified-at
  ;; 02 §4 says what the pinned template does for security, each line checked against it. A pin
  ;; that moves without the section being read again is a claim about a template nobody checked:
  ;; the section names the tag it was checked at, and that must be the pin's.
  (let [text (slurp (str (fs/path template-dir "02-architecture.md")))
        section (subs text (str/index-of text "## 4. Security, as the template gives it") (str/index-of text "# Part 2"))]
    (is (str/includes? section (str "As of the pin `" (:git/tag (template/pin (template/load-pins))) "`."))
        "re-read 02 §4 against the new pin, then name its tag there")
    (is (str/includes? section "Not given:") "what the template does not do is said as plainly")))

;; ---------------------------------------------------------------------------
;; the check
;; ---------------------------------------------------------------------------

(deftest the-governing-documents-are-the-seven-and-every-stage-written
  (is (= ["00-overview.md" "01-requirements.md" "02-architecture.md" "03-method-and-tooling.md"
          "04-decision-log.md" "05-lessons.md" "source.md"]
         (plan/governing template-dir))
      "the README explains the marks and carries one; the stage templates and the gates record's are copied per stage")
  (is (not (plan/governing? "stages/stage-N-gates-template.edn")))
  (is (plan/governing? "stages/stage-1-gates.edn") "a stage's record, once copied, is read like a stage plan")
  (is (plan/governing? "stages/stage-1-foundation.md") "a stage document written from the template is read")
  (is (not (plan/governing? "stages/stage-N-template.md"))))

(deftest a-given-parts-blockquote-is-prose-not-an-instruction
  ;; The fifth project kept 03 §10's five quoted lines, as the README says a given part is
  ;; kept, and plan-check counted them as instructions still standing; the gate won and the
  ;; lines lost their `>`. A given part is not edited, so nothing in it is an instruction.
  (let [instructions (plan/instruction-lines template-dir)]
    (is (not (contains? instructions "A rules file reaches only clients that read files, and §1's independence rule puts the"))
        "03 §10's blockquote, inside Part 1")
    (is (contains? instructions "Every layer this project adds - a domain model, a service layer, an ingestion path - is one")
        "02 §4's blockquote, inside Part 2, is still an instruction")
    (is (contains? instructions "This document is `method.md` **instantiated for this project**. The method doc explains")
        "and 03's opening note, before Part 1, is still one")
    (testing "the cut is from the Part 1 heading to the line before Part 2, and a document without one is whole"
      (is (= ["a" "# Part 2 — x" "d"] (plan/without-given-part ["a" "# Part 1 — Given" "b" "> c" "# Part 2 — x" "d"])))
      (is (= ["a" "> b"] (plan/without-given-part ["a" "> b"])))
      (is (= ["# Part 1 — Given" "> b"] (plan/without-given-part ["# Part 1 — Given" "> b"])) "no Part 2: nothing is cut"))))

(deftest an-instruction-left-standing-is-the-templates-line-not-any-blockquote
  (let [instructions (plan/instruction-lines template-dir)]
    (is (contains? instructions "Three to five sentences a stakeholder could read alone."))
    (is (= [2] (plan/instructions-left "# Mission\n> Three to five sentences a stakeholder could read alone.\nOurs." instructions))
        "the template's line, wherever it was copied to, by content")
    (is (= [] (plan/instructions-left "> The brief said: build the smallest thing that works.\n>" instructions))
        "a project's own quotation is not an instruction, and a bare > is nothing")
    (is (= [1] (plan/instructions-left ">   Three  to five sentences a stakeholder could read alone.  " instructions))
        "spacing does not hide it")))

(def pinned (:layers (template/pin (template/load-pins))))
(def app-layers (edn/read-string (app/layers-edn "xyx" pinned)))

(deftest the-templates-layers-may-grow-but-not-loosen
  (is (= [] (plan/layer-problems app-layers pinned)) "as generated")
  (is (= [] (plan/layer-problems (assoc app-layers 'xyx.domain #{}
                                        'xyx.handlers #{'xyx.views 'xyx.domain})
                                 pinned))
      "a layer the project adds, and a template layer that uses it, are the chosen part")
  (is (= ["layers.edn: xyx.views may now require xyx.db, which the template's layering does not allow"]
         (plan/layer-problems (assoc app-layers 'xyx.views #{'xyx.db}) pinned)))
  (is (= ["layers.edn: the template's xyx.core is no longer declared"]
         (plan/layer-problems (dissoc app-layers 'xyx.core) pinned)))
  (is (= 1 (count (plan/layer-problems {:boundary/fixtures []} pinned))) "no entries at all is one sentence"))

(deftest the-gate-keys-run-in-the-kits-order-and-a-projects-gate-comes-after
  (let [kit [[:fmt "a"] [:lint "b"] [:test "c"] [:deps "d"]]]
    (is (= [] (plan/gate-order-problems kit)))
    (is (= [] (plan/gate-order-problems (conj kit [:e2e "bb e2e"]))))
    (is (= 1 (count (plan/gate-order-problems [[:fmt "a"] [:lint "b"] [:test "c"]]))))
    (is (str/includes? (first (plan/gate-order-problems [[:fmt "a"] [:lint "b"] [:test "c"]])) ":deps missing"))
    (is (str/includes? (first (plan/gate-order-problems [[:test "c"] [:fmt "a"] [:lint "b"] [:deps "d"]]))
                       "cheap first"))))

;; --- a workspace whose plan is as `bb init` left it, or filled ------------------

(defn- fill
  "A template document as a project would leave it: every instruction deleted,
  every mark replaced."
  [text]
  (-> (->> (str/split-lines text) (remove #(re-matches #"\s*>.*" %)) (str/join "\n"))
      (str/replace #"<[^<>\n]+>" #(if (plan/known %) % "filled"))
      ;; the template's own citation names no observation; a project's names one
      (str/replace #"S-n\.m" "S-1.1")))

(defn- workspace
  "A workspace with a plan (its docs from the template, its overlay, `loop.edn`),
  an application's `layers.edn` and a stub profile. `:filled?` fills the plan."
  [{:keys [filled?]}]
  (let [ws (str (fs/real-path (fs/create-temp-dir {:prefix "plan-check-"})))
        plan (fs/path ws "xyx-build")
        docs (fs/path plan "docs")]
    (spit (str (fs/path ws "workspace.edn"))
          (pr-str {:workspace/kit kit-dir :workspace/app "xyx-app" :workspace/build "xyx-build"
                   :workspace/work "work" :workspace/rule-mirrors []
                   :workspace/rules-overlay "xyx-build/rules.edn"}))
    (doseq [rel (plan/documents template-dir)
            :let [text (slurp (str (fs/path template-dir rel)))]]
      (fs/create-dirs (fs/parent (fs/path docs rel)))
      (spit (str (fs/path docs rel)) (if (and filled? (plan/governing? rel)) (fill text) text)))
    (spit (str (fs/path plan "rules.edn"))
          (pr-str (vec (for [{:keys [id text]} (rules/shipped)
                             :when (contains? (rules/placeholder-ids (rules/shipped)) id)]
                         {:id id :text (if filled? (str "filled " (name id)) text)}))))
    (spit (str (fs/path plan "loop.edn"))
          (pr-str {:run/id "<one per run>" :profile "profile.edn"
                   :gates [[:fmt "bb fmt-check"] [:lint "bb lint"] [:test "bb test"] [:deps "bb boundary"]]}))
    (spit (str (fs/path plan "profile.edn"))
          (pr-str {:seat :claude :roles {:spec-reviewer {:family :stub :model "m" :shape :openai
                                                         :endpoint "http://127.0.0.1:1"}}}))
    (fs/create-dirs (fs/path ws "xyx-app"))
    (spit (str (fs/path ws "xyx-app" "layers.edn")) (app/layers-edn "xyx" pinned))
    (fs/create-dirs (fs/path ws "work" "runs"))
    ws))

(defn- check [ws] (plan/check (str (fs/path ws "xyx-build")) (workspace/find-workspace ws)))

(deftest the-plan-as-init-left-it-fails-with-everything-named
  (let [{:keys [problems documents]} (check (workspace {}))
        by-doc (fn [doc] (filter #(str/starts-with? % (str "docs/" doc ":")) problems))]
    (is (= 7 documents))
    (doseq [doc (plan/governing template-dir)]
      (is (= (if (= doc "01-requirements.md") 3 2) (count (by-doc doc)))
          (str doc ": its marks and its instructions, one sentence each; the requirements' FR-1 cites the template's S-n.m")))
    (is (some #(str/includes? % "docs/00-overview.md: 19 marks") problems) "the fixture's count, by name")
    (is (some #(= % "docs/01-requirements.md: FR-1 cites no observation of source.md (S-n.m in its Source column) and is not marked (inferred)") problems))
    (is (= 3 (count (filter #(str/starts-with? % "rules.edn:") problems))) "the overlay's three placeholders")
    (is (not-any? #(str/starts-with? % "layers.edn") problems) "the given parts are intact as generated")
    (is (not-any? #(str/starts-with? % "loop.edn") problems))
    (is (= 18 (count problems)))
    (is (str/starts-with? (plan/report (check (workspace {}))) "plan-check: 18 things left"))))

(deftest a-filled-plan-passes-and-each-way-of-breaking-it-is-one-sentence
  (let [ws (workspace {:filled? true})
        plan (fs/path ws "xyx-build")
        doc (fn [nm] (str (fs/path plan "docs" nm)))]
    (is (= [] (:problems (check ws))))
    (is (str/includes? (plan/report (check ws)) "nothing left to fill, the given parts intact"))
    (testing "one mark, and one instruction, in one document"
      (spit (doc "source.md") (str (slurp (doc "source.md")) "\n<the brief>\n> Three to five sentences a stakeholder could read alone.\n"))
      (is (= ["docs/source.md: 1 mark left to fill - <the brief>"
              (str "docs/source.md: 1 line of the template's instructions still standing (first at line "
                   (count (str/split-lines (slurp (doc "source.md")))) ") - delete each blockquote once it is acted on")]
             (:problems (check ws))))
      (spit (doc "source.md") (fill (slurp (doc "source.md")))))
    (testing "a stage document written from the template is read; the template itself is not"
      (spit (doc "stages/stage-1-foundation.md") "# Stage 1\n\n> The **stage plan**: the project plan's slice for this stage. Written **just-in-time**, when\n")
      (is (= 1 (count (:problems (check ws)))))
      (is (= 8 (:documents (check ws))))
      (fs/delete (doc "stages/stage-1-foundation.md")))
    (testing "the overlay: a placeholder put back, then a KIT rule edited"
      (let [overlay (str (fs/path plan "rules.edn"))
            filled (slurp overlay)
            [pid] (seq (rules/placeholder-ids (rules/shipped)))
            kit-rule (:id (first (remove #(contains? (rules/placeholder-ids (rules/shipped)) (:id %)) (rules/shipped))))]
        (spit overlay (pr-str (mapv #(if (= pid (:id %)) (assoc % :text "<Name your layers and their allowed direction>") %)
                                    (edn/read-string filled))))
        (is (= [(str "rules.edn: " pid " still says <Name your layers and their allowed direction>")] (:problems (check ws))))
        (spit overlay (pr-str (conj (edn/read-string filled) {:id kit-rule :text "edited"})))
        (is (= 1 (count (:problems (check ws)))))
        (is (str/includes? (first (:problems (check ws))) (str kit-rule " is the KIT's rule, not a placeholder")))
        (spit overlay filled)))
    (testing "the given parts: layers loosened, gates reordered"
      (let [layers (str (fs/path ws "xyx-app" "layers.edn"))
            as-generated (slurp layers)]
        (spit layers (pr-str (assoc (edn/read-string as-generated) 'xyx.views #{'xyx.db})))
        (is (= ["layers.edn: xyx.views may now require xyx.db, which the template's layering does not allow"]
               (:problems (check ws))))
        (spit layers as-generated))
      (let [loop-edn (str (fs/path plan "loop.edn"))
            as-written (slurp loop-edn)]
        (spit loop-edn (pr-str (update (edn/read-string as-written) :gates #(vec (reverse %)))))
        (is (= 1 (count (:problems (check ws)))))
        (is (str/starts-with? (first (:problems (check ws))) "loop.edn: the KIT's gates run"))
        (spit loop-edn as-written)))
    (is (= [] (:problems (check ws))) "every break was undone")))

;; --- the citations, in two directions --------------------------------------------

(def ^:private requirements
  (str "## 6. Functional requirements\n\n"
       "| # | Requirement | Source | Priority |\n|---|---|---|---|\n"
       "| FR-1 | the first | S-1.1 | must |\n"
       "| FR-2 | the second | | must |\n"
       "| NFR-1 | the third | (inferred) | should |\n"
       "| FR-3 | the fourth | S-2.3, S-1.1 | could |\n\n"
       "### 6b. The index\n\n| # | Stage | Written in | One line |\n|---|---|---|---|\n| FR-9 | 2 | the stage plan | |\n"))

(deftest a-requirement-cites-an-observation-or-is-marked-inferred
  (is (= [["FR-1" "S-1.1"] ["FR-2" ""] ["NFR-1" "(inferred)"] ["FR-3" "S-2.3, S-1.1"]]
         (plan/requirement-citations requirements))
      "every row of a table with a Source column, by id; the index has no Source column and is not read")
  (is (= ["docs/01-requirements.md: FR-2 cites no observation of source.md (S-n.m in its Source column) and is not marked (inferred)"]
         (plan/citation-problems "01-requirements.md" requirements)))
  (is (= [] (plan/citation-problems "x.md" "no table at all")))
  (is (= [["NFR-1" "01-requirements.md"]] (plan/inferred-requirements [["01-requirements.md" requirements] ["source.md" ""]]))
      "the inferred ones, for the person at the stage's approval"))

(deftest the-observations-nobody-cites-are-a-list-not-a-failure
  (let [source "### S-1 — the brief\n\n- **S-1.1** shows\n- **S-1.2** implies\n- **S-1.3** leaves open\n\n### S-2 — a file\n\n- **S-2.3** a figure\n"]
    (is (= ["S-1.1" "S-1.2" "S-1.3" "S-2.3"] (plan/observations source)))
    (is (= ["S-1.2" "S-1.3"] (plan/uncited-observations source [requirements "a stage plan citing nothing"]))
        "in the source's order; S-2.3 is cited by FR-3")
    (is (= [] (plan/uncited-observations source [requirements "a decision resting on S-1.2 and S-1.3"]))
        "any governing document's mention counts")))

(deftest the-check-prints-the-two-lists-after-its-verdict
  (let [ws (workspace {:filled? true})
        plan (fs/path ws "xyx-build")
        doc (fn [nm] (str (fs/path plan "docs" nm)))
        r (check ws)]
    (is (= [] (:problems r)))
    (is (= [] (:inferred r)) "the filled fixture infers nothing")
    (is (= ["S-1.2" "S-1.3"] (:uncited r)) "the template's source defines three observations and its FR-1 cites the first")
    (is (str/includes? (plan/report r) "\n  uncited: 2 observations of source.md nothing cites - the backlog nobody decided on, revised at the stage's end: S-1.2, S-1.3"))
    (is (not (str/includes? (plan/report r) "inferred:")))
    (testing "a stage plan's requirements: one inferred, one resting on nothing"
      (spit (doc "stages/stage-1-skeleton.md")
            "# Stage 1\n\n## 2. Requirements this stage adds\n\n| # | Requirement | Source | Priority |\n|---|---|---|---|\n| FR-4 | walks | (inferred) | must |\n| FR-5 | runs | | must |\n")
      (let [r (check ws)]
        (is (= ["docs/stages/stage-1-skeleton.md: FR-5 cites no observation of source.md (S-n.m in its Source column) and is not marked (inferred)"]
               (:problems r)))
        (is (= [["FR-4" "stages/stage-1-skeleton.md"]] (:inferred r)))
        (is (str/includes? (plan/report r) "\n  inferred: 1 requirement rests on the Architect's inference, shown at the stage's approval - FR-4 (docs/stages/stage-1-skeleton.md)")))
      (fs/delete (doc "stages/stage-1-skeleton.md")))
    (is (= [] (:problems (check ws))))))

(deftest the-hash-covers-what-the-check-reads-and-nothing-else
  (let [ws (workspace {:filled? true})
        wsm (workspace/find-workspace ws)
        plan (str (fs/path ws "xyx-build"))
        h0 (plan/inputs-hash plan wsm)]
    (spit (str (fs/path plan "docs" "README.md")) "changed")
    (is (= h0 (plan/inputs-hash plan wsm)) "the README is not read")
    (spit (str (fs/path plan "docs" "04-decision-log.md")) "changed")
    (is (not= h0 (plan/inputs-hash plan wsm)))
    (let [h1 (plan/inputs-hash plan wsm)]
      (spit (str (fs/path ws "xyx-app" "layers.edn")) "{}")
      (is (not= h1 (plan/inputs-hash plan wsm)) "layers.edn is read"))))

;; --- start reads the plan once per workspace ----------------------------------

(defn- run-ctx [ws & [cfg]]
  (let [rd (str (fs/path ws "work" "runs" "r1"))]
    (fs/create-dirs rd)
    (spit (str (fs/path rd "spec.edn")) (pr-str {:task/id "t" :blueprint/slice {} :files/impl ["src/xyx/a.clj"]}))
    {:run-dir rd :config (merge {:plan-check/run? true} cfg) :state (atom nil)}))

(deftest start-holds-a-workspace-to-its-plan-once-and-stops-before-anything-is-paid-for
  (testing "as init left it: the stop, with the list, and nothing started"
    (let [ctx (run-ctx (workspace {}))
          e (try (with-out-str (driver/start! ctx)) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :plan-check (:run-loop/error (ex-data e))))
      (is (= 18 (count (:problems (ex-data e)))))
      (is (str/includes? (ex-message e) "bb plan-check"))
      (is (not (fs/exists? (fs/path (:run-dir ctx) "state.edn"))))
      (is (not (fs/exists? (fs/path (:run-dir ctx) ".." ".." "plan-check.edn"))) "nothing cached on a failure")))
  (testing "filled: checked once, then known by its hash until the plan changes"
    (let [ws (workspace {:filled? true})
          ctx (run-ctx ws)
          cache (fs/path ws "work" "plan-check.edn")]
      (is (= :now (:plan/checked (driver/plan-check! ctx))))
      (is (fs/exists? cache))
      (is (= :cached (:plan/checked (driver/plan-check! ctx))))
      (is (str/includes? (with-out-str (driver/plan-check! ctx)) "unchanged since"))
      (spit (str (fs/path ws "xyx-build" "docs" "source.md")) "# revised\n")
      (is (= :now (:plan/checked (driver/plan-check! ctx))) "a revised plan is read again")
      (spit (str (fs/path ws "xyx-build" "docs" "source.md")) "# revised again <with a mark>\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not ready" (driver/plan-check! ctx)))
      (spit (str (fs/path ws "xyx-build" "docs" "source.md")) "# revised\n")
      (is (= :cached (:plan/checked (driver/plan-check! ctx))) "a failure did not move the cache on")))
  (testing "off by loop.edn, and outside a workspace with a plan: nothing, and nothing said"
    (is (nil? (driver/plan-check! (run-ctx (workspace {}) {:plan-check/run? false}))))
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path dir "runs" "r1"))
      (is (nil? (driver/plan-check! {:run-dir (str (fs/path dir "runs" "r1")) :config {} :state (atom nil)}))))))
