;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.contract.targets
  "The spec's `:property-targets`, checked against the tests the Tester wrote.

  WHY THIS EXISTS. `:property-targets` is the behavioural contract — the
  sentences a task is judged against. Every packet carries them and nothing
  ever checked that the Tester covered one.

  One staged task supplied the evidence. Its Tester wrote eight tests over targets 1 to 6,
  skipped target 7 — *validates e once, at its entry, not at every level of the
  tree* — and went green through fmt, lint, test and deps. Mutation found it
  afterwards, by hand: the mutant that moves validation into the recursive
  helper survived the whole suite. A Reviewer bake-off then showed the Reviewer
  does not cover the gap either, and for a reason no rule fixes: it verifies the
  property ITSELF and reports it satisfied, so it never asks whether a test pins
  it.

  An earlier run is the control. On the same task and the same eight targets, a different
  Tester wrote `validates-the-input-once-rather-than-each-tree-level` and killed
  that mutant — without the `:shapes-are-the-contract` rule, which no Tester has
  ever had. So the property was reachable from the target alone, and what was
  missing was not a rule but an accounting.

  WHY A CHECK AND NOT ONLY AN INSTRUCTION. `runner.clj`'s Tester instruction now
  names `:property-targets`, and that is the likelier root cause: it used to
  call `:blueprint/slice` \"the contract\" and never mention the targets at all.
  But an instruction which fixes a behaviour in one model is a fix for THAT
  MODEL, and that was a finding of its own — the `write_file` clause held for
  `gpt-5.6-sol` across fifteen runs and broke on the first different Tester. An
  instruction-only change would repeat that. This is the part that does not care
  which model is in the seat.

  IT CHECKS ACCOUNTING, NOT ADEQUACY. A test may name target 7 and still not pin
  it — the test that cannot fail — and only mutation finds
  those. A Tester that emits the comments and writes weak tests passes this. It
  raises the floor; it proves nothing, and it is not a substitute for the
  mutation step.

  THE MARKER IS THE EXISTING IDIOM, not a new one. `:test-no-docstring` already
  says to put a test's intent \"in the test name or a ;; comment above the
  form\", because a bare string in a `deftest` body is an unused value and the
  lint gate fails on warnings. So:

      ;; target 7
      (deftest validates-the-input-once-rather-than-each-tree-level ...)

      ;; targets 1, 2
      (deftest substitutes-bound-variables-at-any-depth ...)

  A target that is no test's job is DECLARED rather than silently dropped, and
  may sit anywhere in the file:

      ;; target 8 — not a test: sandbox.constants depends only on expr and
      ;; shapes, which gate 4 checks.

  The declaration is reported to the Orchestrator rather than swallowed: a
  judgement that something needs no test is exactly the kind that should be
  seen."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private target-line
  "A comment line naming one or more targets: `;; target 7`, `;; targets 1, 2`."
  #"(?i)^\s*;+\s*targets?\b([^\n]*)")

(def ^:private exempt-marker
  "What turns a target line into a declaration that no test is owed."
  #"(?i)\bnot\s+a\s+test\b\s*:?\s*(.*)")

(defn- numbers
  "Every integer in `s`, in order."
  [s]
  (mapv parse-long (re-seq #"\d+" (or s ""))))

(defn- form-opener?
  "Does this line start a test form? `deftest` and `defspec` only — the two the
  conventions name."
  [line]
  (boolean (re-find #"^\s*\((deftest|defspec)\b" line)))

(defn- scan
  "One pass over a test file's lines.

  A comment block is the run of contiguous comment lines directly above a form;
  a blank line between them breaks it, which keeps attribution unambiguous
  rather than clever. Targets named in a block that opens a `deftest` or
  `defspec` are COVERED. A target line saying `not a test` is a DECLARATION and
  stands wherever it appears."
  [path text]
  (reduce
   (fn [acc [i line]]
     (let [[_ tail] (re-find target-line line)
           [_ reason] (when tail (re-find exempt-marker line))]
       (cond
         ;; a declaration that no test is owed — stands wherever it appears, and
         ;; does not join the block, so it can never be read as coverage. The
         ;; reason is required: a bare "not a test:" declares nothing, and falls
         ;; through to be counted like any other target line.
         (not (str/blank? reason))
         (update acc :exempt into
                 (map (fn [n] {:target n :file path :line (inc i)
                               :reason (str/trim reason)}))
                 ;; ONLY the numbers before the marker. The reason is prose and
                 ;; prose has digits in it: "not a test: gate 4 checks it" read
                 ;; as target 4 until a test caught it.
                 (numbers (str/replace tail exempt-marker "")))

         ;; a target line joins the block being built
         tail (update acc :block into (numbers tail))

         ;; a form closes the block: whatever it named is covered here
         (form-opener? line)
         (-> acc
             (update :covered into
                     (map (fn [n] {:target n :file path :line (inc i)}))
                     (:block acc))
             (assoc :block []))

         ;; a plain comment line continues the block
         (re-find #"^\s*;" line) acc

         ;; anything else — a blank line, or code — breaks it
         :else (assoc acc :block []))))
   {:covered [] :exempt [] :block []}
   (map-indexed vector (str/split-lines text))))

(defn coverage
  "How the Tester's files account for every `:property-targets` entry.

  Targets are addressed by their 1-based position in the spec's vector, which is
  what the packet the Tester read carried. Returns

      {:covered   [{:target n :file path :line n} ...]
       :exempt    [{:target n :file path :line n :reason str} ...]
       :uncovered [{:target n :violation kw :detail str} ...]
       :total     n}

  `:uncovered` is the gate's view and is empty when every target is either
  covered by a test or declared exempt. A marker naming a target the spec does
  not have is `:unknown-target` — a renumbered spec or a typo, and worth saying
  rather than ignoring. A missing test file is reported, never skipped."
  [dir {:keys [files/test property-targets]}]
  (let [total (count property-targets)
        {missing false present true} (group-by #(fs/exists? (fs/path dir %)) test)
        scans (map #(scan % (slurp (str (fs/path dir %)))) present)
        covered (into [] (mapcat :covered) scans)
        exempt (into [] (mapcat :exempt) scans)
        named (into #{} (map :target) (concat covered exempt))
        in-range? (fn [n] (<= 1 n total))]
    {:covered covered
     :exempt exempt
     :total total
     :uncovered
     (cond-> []
       ;; A spec with no targets has nothing for this check to have an opinion
       ;; about, a missing file included — assembly already refuses a role that
       ;; wrote no :files/target.
       (and (pos? total) (seq missing))
       (into (map (fn [p] {:target nil :file p :violation :missing-test-file
                           :detail (str p " is not in the worktree — nothing could be checked")}))
             (sort missing))

       (pos? total)
       (into (comp (remove named) (map (fn [n]
                                         {:target n :violation :uncovered-target
                                          :detail (str "property target " n " of " total
                                                       " is named by no test and declared by no"
                                                       " `;; target " n " — not a test: …`: "
                                                       (pr-str (nth property-targets (dec n))))})))
             (range 1 (inc total)))

       :always
       (into (comp (remove (comp in-range? :target))
                   (map (fn [{:keys [target file line]}]
                          {:target target :file file :line line :violation :unknown-target
                           :detail (str "a marker names target " target " and the spec has "
                                        total)})))
             (concat covered exempt)))}))
