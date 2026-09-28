(ns harness.setup.blueprint-review
  "A stage's Blueprint, read cold before sign-off: `bb blueprint-review <blueprint.md>`.

  `method.md` §07 step 2 says the Orchestrator reviews the Blueprint before
  the human signs it off - if it is over-engineered, it goes back to the
  Architect - and §06 says what its shapes and targets must be. Nothing ran
  either question: the harness's orchestrator role is triage, and a
  Blueprint's packets met a model one at a time, in the spec review. This is
  the read: the stage document (the Blueprint's input), the Blueprint, and the
  method's two sections, to the profile's `:blueprint-reviewer` role - one
  completion, no tools - the findings printed with the cost and written to
  `<plan>/reviews/<stage>/blueprint-review.edn`, beside the stage's other raw
  material. The Architect resolves them in the stage document, then signs off.

  NOT A GATE, and not the spec review: that still reads every packet cut from
  the Blueprint before it dispatches. This reads the whole, once, for what a
  packet cannot show - a layer with one use, a shape nothing needs, a target
  that promises two things. The findings' parsing is the plan review's; the
  checklist is read from `method.md` at the call, so the method and the review
  cannot drift apart.

  The pure parts are `checklist`, `stage-document-name`, `stage-key` and
  `review-input`; `review!` is the command."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [harness.models.agent :as agent]
   [harness.models.profile :as profile]
   [harness.setup.plan :as plan]
   [harness.setup.plan-review :as plan-review]
   [harness.setup.workspace :as workspace]))

(def kinds
  "The findings a Blueprint read is for, as the `kind` the answer uses: the
  method's over-engineering question, then §06's rules for targets and shapes,
  then the two ways a Blueprint disagrees with its own stage document."
  ["over-engineered" "target-two-promises" "target-not-checkable" "target-no-marker"
   "shape-undefined" "shape-not-granted" "seam-unstated" "packet-task-mismatch"
   "interface-malformed"])

(def system-prompt
  (str "You review a stage's BLUEPRINT before the human signs it off - as an artifact, adversarially. "
       "You did not write it. It is read twice, by two agents who never compare notes: a Coder implements "
       "against it and a Tester derives tests from it, each from one task packet at a time, and a "
       "spec review reads each packet before it dispatches. You read the whole, once, for what a packet "
       "cannot show.\n\n"
       "You are given the stage document the Blueprint derives from, the Blueprint, and two sections of "
       "the method the project adopted: the step that asks whether the Blueprint is over-engineered, and "
       "the rules its data shapes and property targets must follow. Answer both questions, and check the "
       "Blueprint against its stage document.\n\n"
       "OVER-ENGINEERED means: a layer, a namespace, a shape or an abstraction that this stage's task list "
       "and exit criteria do not need; an interface with one caller and one implementation dressed as a "
       "seam; a packet that builds for a later stage. Name each and say what the stage document asks for "
       "instead. What the stage document names as deliberately deferred is not over-engineering; what it "
       "names as this stage's is not gold-plating.\n\n"
       "The rules for targets and shapes are in the text: one promise per target; a target stated as "
       "something checkable, not a description; a marker attribute for anything a test must find; what a "
       "seam guarantees written where every role reads it; a shape a packet names under :shapes must be "
       "defined in §1 or inline, and a var a target requires the code to refer to must be granted under "
       ":deps-sigs, and every :deps-sigs entry is qualified, ns/f, because the calls gate grants by qualified "
       "name. An interface is (name [args]) for a function and (name) for a var, never a bare name.\n\n"
       "The stack, the layering as the boundary file declares it, the six roles, the packet, the gates and "
       "the retry cap are GIVEN by the method and are not the Blueprint's to have decided - do not report "
       "them. Report only what a reader could act on: name the section, the packet id or the target, quote "
       "the words. Do not report style or length. An empty list is a valid answer when the Blueprint is "
       "tight.\n\n"
       "You may reason first. End with exactly one fenced json block:\n\n"
       "```json\n"
       "{\"findings\": [{\"kind\": \"target-two-promises\", \"where\": \"t-03 property target 2\",\n"
       "               \"finding\": \"one sentence\", \"evidence\": \"the words, quoted\"}]}\n"
       "```\n\n"
       "\"kind\" is one of: " (str/join ", " kinds) ", or \"other\"."))

(defn- section
  "`text` from `from-heading` to the line before `to-heading`; throws, naming
  `what`, when the heading is not there - a review without its checklist is a
  different review."
  [text from-heading to-heading what]
  (let [from (str/index-of text from-heading)
        to (some-> from (as-> i (str/index-of text to-heading i)))]
    (when-not from
      (throw (ex-info (str "method.md has no '" from-heading "' heading - " what)
                      {:blueprint-review/error :no-checklist :heading from-heading})))
    (str/trim (subs text from (or to (count text))))))

(defn checklist
  "The two sections of `method-text` (`method.md`) the review sends: §07's
  strategic-planning step, which asks whether the Blueprint is over-engineered,
  and §06 from its data-shapes-first rule through the rules for writing
  property targets, up to the per-role deltas. Throws when either heading is
  missing."
  [method-text]
  (str (section method-text "### Step 2 · Strategic planning" "\n### Step 2½"
                "the step that asks whether a Blueprint is over-engineered")
       "\n\n"
       (section method-text "### Data shapes first" "\n### Per-role deltas"
                "the rules for shapes and targets the review checks against")))

(defn stage-document-name
  "The stage document a Blueprint names as its input in its header
  (`**Input:** \\`stage-N-<name>.md\\``), or nil when the header does not."
  [blueprint-text]
  (second (re-find #"\*\*Input:\*\*\s*`([^`]+\.md)`" blueprint-text)))

(defn stage-key
  "The folder under `reviews/` a Blueprint's read is written to: its file name
  without `-blueprint.md` (`stage-1-blueprint.md` → `stage-1`), or without
  `.md` when it is not named that way."
  [blueprint-path]
  (let [nm (fs/file-name blueprint-path)]
    (if (str/ends-with? nm "-blueprint.md")
      (subs nm 0 (- (count nm) (count "-blueprint.md")))
      (str/replace nm #"\.md$" ""))))

(defn review-input
  "The text the review reads: the stage document (named, or said to be not
  found), the Blueprint, then the checklist."
  [stage-name stage-text blueprint-name blueprint-text checklist]
  (str "=== " (or stage-name "the stage document") (when-not stage-text " (not found)") " ===\n"
       (or stage-text "")
       "\n\n=== " blueprint-name " ===\n" blueprint-text
       "\n\n=== THE CHECKLIST (method.md §07 step 2, and §06's rules for shapes and targets) ===\n"
       checklist))

(defn plan-dir-of
  "The plan a Blueprint belongs to: it lives at `<plan>/docs/stages/<file>`.
  Throws by name when it does not."
  [blueprint-path]
  (let [bp (fs/absolutize blueprint-path)
        stages (fs/parent bp)
        docs (some-> stages fs/parent)]
    (when-not (and (fs/exists? bp)
                   stages (= "stages" (fs/file-name stages))
                   docs (= "docs" (fs/file-name docs)))
      (throw (ex-info (str "a Blueprint lives at <plan>/docs/stages/<stage>-blueprint.md; " bp
                           (if (fs/exists? bp) " is not there" " does not exist"))
                      {:blueprint-review/error :not-a-blueprint-path :path (str bp)})))
    (str (fs/normalize (fs/parent docs)))))

(defn- existing [out-file]
  (when (fs/exists? out-file) (edn/read-string (slurp (str out-file)))))

(defn- print-findings [findings cost]
  (println (format "blueprint review: %d finding%s%s" (count findings) (if (= 1 (count findings)) "" "s")
                   (if cost (format ", $%.4f" (double cost)) "")))
  (doseq [[i {:keys [kind where finding evidence]}] (map-indexed vector findings)]
    (println (format "  %2d. [%s] %s — %s" (inc i) kind where finding))
    (when (seq (str evidence)) (println (str "        " evidence)))))

(defn read!
  "The read itself, with `role` (a role block, not a file) in the reviewer's
  seat: the Blueprint at `blueprint-path`, the stage document its header
  names, the checklist from `method-path`; one completion, no tools. Returns
  the findings (nil when the answer had no block), the documents read, the
  model as served, the cost, the time, and the text sent - what a bake-off
  compares candidates on."
  [role blueprint-path method-path]
  (let [plan-dir (plan-dir-of blueprint-path)
        bp-name (fs/file-name blueprint-path)
        bp-text (slurp (str blueprint-path))
        stage-name (stage-document-name bp-text)
        stage-file (when stage-name (fs/path (fs/parent blueprint-path) stage-name))
        stage-text (when (and stage-file (fs/exists? stage-file)) (slurp (str stage-file)))
        input (review-input stage-name stage-text bp-name bp-text (checklist (slurp (str method-path))))
        t0 (System/currentTimeMillis)
        r (agent/converse! role system-prompt input {:dir plan-dir} {:max-iterations 1 :tools #{}})]
    (when (= :failed (:status r))
      (throw (ex-info (if (= :credit (get-in r [:error :harness/error]))
                        (str "the Blueprint review's model call was refused for credit (402) by " (:endpoint role)
                             " - add credit to the account whose key is in " (:key-env role))
                        "the Blueprint review's model call failed")
                      {:blueprint-review/error :call-failed
                       :error (select-keys (:error r) [:harness/error :status])})))
    {:findings (plan-review/parse-findings (:text r))
     :documents (vec (remove nil? [(when stage-text stage-name) bp-name]))
     :model (some :model (reverse (:steps r)))
     :cost (let [cs (keep :cost (:steps r))] (when (seq cs) (reduce + cs)))
     :ms (- (System/currentTimeMillis) t0)
     :input input
     :text (:text r)}))

(defn review!
  "Read the Blueprint at `blueprint-path` and the stage document it names, send
  them with the checklist to the `:blueprint-reviewer` role of `profile-path`,
  print the list, write `<plan>/reviews/<stage>/blueprint-review.edn` - the
  findings, their count, the model, the cost, the documents read, and every
  earlier review of this Blueprint under `:reviews`. `method-path` is where
  the checklist is read from. Returns the written map; an answer with no
  findings block writes nothing and returns `{:no-block? true}`."
  [blueprint-path profile-path method-path]
  (let [plan-dir (plan-dir-of blueprint-path)
        role (get-in (profile/read-profile profile-path) [:roles :blueprint-reviewer])
        _ (when-not role
            (throw (ex-info (str "the profile " profile-path " has no :blueprint-reviewer role")
                            {:blueprint-review/error :no-blueprint-reviewer :profile (str profile-path)})))
        {:keys [findings documents model cost]} (read! role blueprint-path method-path)
        out-file (fs/path plan-dir "reviews" (stage-key blueprint-path) "blueprint-review.edn")
        out {:findings findings
             :count (count findings)
             :documents documents
             :model model
             :cost cost
             :at (str (java.time.Instant/now))}]
    (if (nil? findings)
      (do (println "blueprint review: the answer had no findings block; nothing written — run it again")
          {:no-block? true})
      (do (fs/create-dirs (fs/parent out-file))
          (spit (str out-file)
                (with-out-str
                  (pp/pprint (assoc out :reviews (conj (vec (:reviews (existing out-file)))
                                                       (dissoc out :findings))))))
          (print-findings findings (:cost out))
          (println (str "written to " out-file
                        " — resolve each in the stage document, then sign the Blueprint off;"
                        " this is a reading, not a gate (the spec review still reads every packet)"))
          out))))

(defn -main
  "bb blueprint-review <blueprint.md> [--profile <profile.edn>] [--workspace <dir>]

  The Blueprint's plan is the folder above its `docs/stages/`; the profile is
  that plan's `profile.edn` unless `--profile` names another; the checklist is
  the KIT's `method.md`."
  [& args]
  (let [{:keys [flags positional]} (loop [[a & more] (:args (workspace/split-args args)) flags {} positional []]
                                     (cond (nil? a) {:flags flags :positional positional}
                                           (str/starts-with? a "--") (recur (rest more) (assoc flags a (first more)) positional)
                                           :else (recur more flags (conj positional a))))
        bp (first positional)]
    (when-not bp
      (println "blueprint-review: which Blueprint? bb blueprint-review <plan>/docs/stages/<stage>-blueprint.md")
      (System/exit 2))
    (try
      (let [plan-dir (plan-dir-of bp)
            ws (or (workspace/find-workspace plan-dir) (workspace/current-or-exit args))
            r (review! (str (fs/normalize (fs/absolutize bp)))
                       (or (get flags "--profile") (str (fs/path plan-dir "profile.edn")))
                       (fs/path (plan/kit-dir ws) "method.md"))]
        (when (:no-block? r) (System/exit 1)))
      (catch clojure.lang.ExceptionInfo e
        (println "blueprint-review:" (ex-message e))
        (System/exit 1)))))
