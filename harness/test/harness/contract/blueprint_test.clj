(ns harness.contract.blueprint-test
  "The Blueprint template's packet, read as the spec the harness reads - the
  pairing `shapes/example-packet` and method §06 have, for the template - and
  `bb spec-from-blueprint` over a Blueprint derived from that template."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.blueprint :as bp]
   [harness.contract.packet :as packet]
   [harness.contract.shapes :as shapes]
   [harness.contract.stub :as stub]))

;; The tests run in harness/, so the template is the real one beside them.
(def kit-dir (str (fs/parent (fs/real-path "."))))
(def template-path (str (fs/path kit-dir "plan-template" "stages" "stage-N-blueprint-template.md")))
(def template (slurp template-path))

(def session {:worktree/path "/work/app-wt-01" :nrepl/port 7801})

;; ---------------------------------------------------------------------------
;; the template's packet is a task spec
;; ---------------------------------------------------------------------------

(deftest the-templates-packet-is-the-spec-the-harness-reads
  ;; The template's §4 packet had drifted from the schema - :contract, :workspace, :gates
  ;; {:cmd}, no title, no targets - and every build re-derived the extraction by hand.
  ;; Read straight out of the fence, placeholders standing, it is a TaskSpec.
  (let [found (bp/packets template)
        spec (:spec (first found))]
    (is (= ["t-01-<slug>"] (mapv :task/id found)) "one packet, the template's t-01")
    (is (nil? (shapes/explain-spec spec)))
    (is (empty? (stub/check-slice (:blueprint/slice spec)))
        "and its slice is one the stub reads: a pair, a name, a function, a var")
    (testing "the driver's keys are all that separates it from the packets the roles receive"
      (is (shapes/valid-packet? (packet/coder-packet spec session)))
      (is (shapes/valid-packet? (packet/tester-packet spec session)))
      (is (shapes/valid-packet? (packet/reviewer-packet spec session "diff --git a/x" {:fmt :pass}))))))

;; ---------------------------------------------------------------------------
;; a Blueprint derived from the template
;; ---------------------------------------------------------------------------

(def ghost-packet
  "A second packet, naming a shape §1 does not define."
  "
### t-02 — ghost

```clojure
{:task/id \"t-02-ghost\"
 :task/title \"names a shape nobody defined\"
 :blueprint/slice {:shapes [Ghost] :interfaces [(f [x])]}
 :files/impl [\"src/app/ghost.clj\"]
 :files/test [\"test/app/ghost_test.clj\"]
 :files/context []
 :layer/name :service}
```
")

(def blueprint
  "The template, filled: §1 defines Concept as a def and Release as a pair; t-01 names
  both (Release first, to show §1's order wins) and defines Range inline."
  (-> template
      (str/replace "(def Concept\n  [:map\n   [:id :string]\n   ...])"
                   "(def Concept\n  [:map\n   [:id :string]\n   [:label :string]])\n\n[Release [:map [:version :string]]]")
      (str/replace "\"t-01-<slug>\"" "\"t-01-concept-ops\"")
      (str/replace "\"<what this task delivers, one line>\"" "\"Concept ops: lookup and the default options\"")
      (str/replace "[<Shape> <schema>]" "[Range [:map [:lo :int] [:hi :int]]]")
      (str/replace "<Named>]" "Release Concept]")
      (str/replace "(<fn> [<args>])" "(lookup [store id opts])")
      (str/replace "(<var>)" "(default-opts)")
      (str/replace "(<dep-ns>/<dep-fn> [<args>])" "(app.store/query [store q opts])")
      (str/replace "<ns>" "concept")
      (str/replace "<dep>" "store")
      (str/replace ":<layer>" ":service")
      (str/replace "\"<one promise, checkable, in full>\"" "\"(lookup store id opts) is nil for an id the store does not hold\"")
      (str ghost-packet)))

(deftest the-template-fixture-still-derives
  ;; Every replacement above matched something: a placeholder the template no longer
  ;; carries would leave the fixture with the template's symbol and this test silent.
  (is (= 2 (count (bp/packets blueprint))))
  (is (not-any? #(str/includes? % "<") (bp/fences (subs blueprint (str/index-of blueprint "## 4.")))) "the packets carry no placeholder")
  (is (= '[Concept Release] (mapv first (bp/definitions blueprint)))))

(deftest the-packet-found-and-its-named-shapes-inlined-from-s1
  (let [{:keys [spec inlined]} (bp/spec-for blueprint "t-01-concept-ops")]
    (is (= '[Concept Release] inlined) "reported in §1's order, not the packet's")
    (is (= '[(def Concept [:map [:id :string] [:label :string]])
             [Release [:map [:version :string]]]
             [Range [:map [:lo :int] [:hi :int]]]]
           (get-in spec [:blueprint/slice :shapes]))
        "§1's definitions verbatim - the def stays a def, the pair a pair - then the packet's own")
    (is (nil? (shapes/explain-spec spec)) "valid against PacketBase's spec part")
    (is (= ["src/app/concept.clj"] (:files/impl spec)))
    (is (= '[(lookup [store id opts]) (default-opts)] (get-in spec [:blueprint/slice :interfaces])))
    (testing "and cuts into the three packets"
      (is (shapes/valid-packet? (packet/coder-packet spec session)))
      (is (= ["src/app/store.clj"] (:files/context (packet/tester-packet spec session)))))
    (testing "written and read back, it is the same spec"
      (is (= spec (edn/read-string (bp/render spec)))))))

(deftest a-packet-with-two-implementation-files-is-refused-at-extraction
  ;; The fifth project's t-04: handlers.clj and routes.clj in one packet passed the extraction
  ;; and was refused by the stub after two spec reviews and a provisioning. Now it is refused
  ;; here, by the rule and not by the schema's count, before any of that.
  (let [two (str blueprint "\n### t-03 — two\n\n```clojure\n"
                 "{:task/id \"t-03-two\" :task/title \"two namespaces in one packet\"\n"
                 " :blueprint/slice {:shapes [] :interfaces [(f [x])]}\n"
                 " :files/impl [\"src/app/handlers.clj\" \"src/app/routes.clj\"]\n"
                 " :files/test [\"test/app/handlers_test.clj\"] :files/context [] :layer/name :web}\n```\n")
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one implementation file.*\n  split it into one packet per namespace"
                                (bp/spec-for two "t-03-two")))]
    (is (= {:blueprint/error :multi-impl :task/id "t-03-two"
            :files ["src/app/handlers.clj" "src/app/routes.clj"]}
           (ex-data e)))
    (is (= {:files/impl ["should have 1 elements"]}
           (shapes/explain-spec {:task/id "t" :task/title "t" :blueprint/slice {:shapes [] :interfaces '[(f [x])]}
                                 :files/impl ["a.clj" "b.clj"] :files/test ["t.clj"] :files/context [] :layer/name :x}))
        "and the schema agrees, for any reader that validates without the rule")))

(deftest an-unqualified-deps-sig-is-refused-at-extraction
  ;; The fifth project's t-03 wrote `(base [title content])` as the method's example packet showed
  ;; and met the red calls gate two dispatches later; the extraction is the first reader.
  (let [bare (str blueprint "\n### t-04 — bare\n\n```clojure\n"
                  "{:task/id \"t-04-bare\" :task/title \"an unqualified dependency\"\n"
                  " :blueprint/slice {:shapes [] :interfaces [(f [x])] :deps-sigs [(query [store q opts])]}\n"
                  " :files/impl [\"src/app/bare.clj\"] :files/test [\"test/app/bare_test.clj\"] :files/context [] :layer/name :web}\n```\n")
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deps-sigs \(query \[store q opts\]\) — unqualified"
                                (bp/spec-for bare "t-04-bare")))]
    (is (= :bad-slice (:blueprint/error (ex-data e))))
    (is (= '[(query [store q opts])] (:entries (ex-data e))))))

(deftest a-shape-s1-does-not-define-is-refused-by-name
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defines no shape named Ghost"
                                (bp/spec-for blueprint "t-02-ghost")))]
    (is (= {:blueprint/error :undefined-shape :task/id "t-02-ghost" :shapes '[Ghost]} (ex-data e)))))

(deftest a-task-the-blueprint-does-not-carry-is-refused-by-name
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no packet with :task/id \"t-09-none\""
                                (bp/spec-for blueprint "t-09-none")))]
    (is (= ["t-01-concept-ops" "t-02-ghost"] (:task-ids (ex-data e))) "and says which it carries"))
  (testing "a Blueprint with no §1 is refused too"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no `## 1.` section"
                          (bp/spec-for (str/replace blueprint "## 1. Data shapes" "## Shapes") "t-01-concept-ops")))))

(deftest a-packet-that-is-not-a-spec-is-refused-with-the-schemas-words
  (let [broken (str/replace blueprint ":files/test       [\"test/app/concept_test.clj\"]" "")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a task spec: \{:files/test \[\"missing required key\"\]\}"
                          (bp/spec-for broken "t-01-concept-ops")))))

(deftest the-command-pipes-the-spec-and-reports-beside-it
  ;; Without an output path the spec is stdout and nothing else - what `bb sigs` reads.
  (let [dir (str (fs/create-temp-dir))
        file (str (fs/path dir "blueprint.md"))
        _ (spit file blueprint)
        {:keys [out err exit]} (p/shell {:out :string :err :string :continue true}
                                        "bb" "spec-from-blueprint" file "t-01-concept-ops")]
    (is (= 0 exit))
    (is (= (:spec (bp/spec-for blueprint "t-01-concept-ops")) (edn/read-string out)))
    (is (str/includes? err "2 inlined from §1: Concept, Release"))
    (is (str/includes? err "impl:    src/app/concept.clj"))
    (testing "with an output path the file is written and the report is stdout"
      (let [out-file (str (fs/path dir "runs" "t-01" "spec.edn"))
            {:keys [out exit]} (p/shell {:out :string :err :string :continue true}
                                        "bb" "spec-from-blueprint" file "t-01-concept-ops" out-file)]
        (is (= 0 exit))
        (is (str/includes? out (str "-> " out-file)))
        (is (shapes/valid-spec? (edn/read-string (slurp out-file))))))
    (testing "a refusal is exit 1 with the reason"
      (let [{:keys [out exit]} (p/shell {:out :string :err :string :continue true}
                                        "bb" "spec-from-blueprint" file "t-02-ghost")]
        (is (= 1 exit))
        (is (str/includes? out "defines no shape named Ghost"))))))
