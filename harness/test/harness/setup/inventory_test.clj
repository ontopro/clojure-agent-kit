(ns harness.setup.inventory-test
  "The inventory: a namespace row from a file, the layer from the ruleset,
  the block, the names the hand-written README must carry, and the sync
  against this harness - which also holds the harness to its own layers."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.boundary :as boundary]
   [harness.setup.inventory :as inv]))

(deftest a-row-is-the-namespace-its-group-and-its-docstrings-first-line
  (is (= {:ns "harness.setup.next" :group "setup" :doc "What comes next in the workflow, read off the build's files: `bb next`."}
         (inv/namespace-row ";; a comment\n(ns harness.setup.next\n  \"What comes next in the workflow, read off the build's files: `bb next`.\n\n  More.\"\n  (:require [x]))\n")))
  (is (= {:ns "harness.rules" :group nil :doc "One rule source."} (inv/namespace-row "(ns harness.rules \"One rule source.\")")) "no group under harness itself")
  (is (= {:ns "harness.gates.run" :group "gates" :doc nil} (inv/namespace-row "(ns harness.gates.run (:require [x]))")) "a namespace with no docstring")
  (is (nil? (inv/namespace-row "(def x 1)")) "not a namespace file"))

(deftest the-layer-is-one-more-than-the-deepest-it-may-require
  ;; Folders say topic; the layer says dependency order, computed from the
  ;; boundary gate's ruleset and never typed.
  (let [layers '{harness.setup.doctor #{} harness.setup.workspace #{}
                 harness.rules #{harness.setup.workspace}
                 harness.gates.repair #{harness.setup.doctor}
                 harness.models.tools #{harness.gates.repair harness.setup.doctor}
                 harness.setup.health #{harness.models.tools harness.rules}}]
    (is (= 0 (inv/layer-of layers 'harness.setup.doctor)) "a leaf, whatever its folder")
    (is (= 1 (inv/layer-of layers 'harness.rules)))
    (is (= 2 (inv/layer-of layers 'harness.models.tools)))
    (is (= 3 (inv/layer-of layers 'harness.setup.health)) "the root, in the same folder as the leaf")
    (is (nil? (inv/layer-of layers 'harness.money.report)) "not declared")))

(deftest the-block-counts-and-lists-by-group-and-the-hand-written-part-must-name-every-one
  (let [rows [{:ns "harness.rules" :group nil :layer 1 :doc "The rule source."}
              {:ns "harness.setup.doctor" :group "setup" :layer 0 :doc "Toolchain probe."}
              {:ns "harness.loop.driver" :group "loop" :layer nil :doc nil}]
        block (inv/render rows)]
    (is (str/starts-with? block "3 namespaces, rendered from `src/harness/` by `bb inventory-sync` (`--check` in `bb gates`): `harness.setup` (1: the machine and the workspace), `harness.loop` (1: the steps and the loop), `harness.contract` (0:"))
    (is (str/includes? block ", and `harness.rules` on its own. The group is the folder, what a namespace is about; the layer is its place in the dependency order `layers.edn` declares and `bb boundary` holds - 0 requires nothing of the harness, 1 is the top"))
    (is (str/includes? block "| Namespace | Group | Layer | The first line of its docstring |\n"))
    (is (str/includes? block "| `harness.loop.driver` | loop | — (not in layers.edn) | — (no docstring) |"))
    (is (str/includes? block "| `harness.rules` | — | 1 | The rule source. |"))
    (testing "a name inside the generated block does not count as a lesson"
      (let [readme (str "# R\n\n" inv/begin-marker "\n\n" block "\n\n" inv/end-marker "\n\n| `harness.setup.doctor` | probes | a lesson |\n")]
        (is (= ["harness.rules" "harness.loop.driver"] (inv/unnamed readme rows)))))))

(deftest this-harness-is-in-sync-with-every-namespace-declared-named-and-within-its-layer
  ;; The README's block is committed and `layers.edn` is the harness's own
  ;; ruleset; `bb gates` runs the same two checks. A failure here is a
  ;; namespace added without `bb inventory-sync`, one the hand-written table
  ;; never names, or a require the layering does not allow.
  (let [layers (inv/read-layers "layers.edn")
        rows (inv/namespaces "src/harness" layers)]
    (is (<= 40 (count rows)) "every file under src/harness/ is a namespace")
    (is (every? :doc rows) (str "a namespace with no docstring: " (mapv :ns (remove :doc rows))))
    (is (every? :layer rows) (str "a namespace layers.edn does not declare: " (mapv :ns (remove :layer rows))))
    (is (= [] (boundary/violations "src" (edn/read-string (slurp "layers.edn")))) "the harness within its own layers")
    (is (= 0 (inv/layer-of layers 'harness.setup.doctor)) "the doctor is a leaf, whatever its folder")
    (is (= (reduce max 0 (keep :layer rows)) (inv/layer-of layers 'harness.setup.health)) "the health check is the top")
    (let [{:keys [changed? unnamed]} (inv/sync! "src/harness" "layers.edn" "README.md" :check? true)]
      (is (false? changed?) "run `bb inventory-sync`")
      (is (= [] unnamed)))))

(deftest the-sync-writes-between-the-markers-and-refuses-a-readme-without-them
  (let [d (str (fs/create-temp-dir {:prefix "inventory-"}))
        src (fs/path d "src" "harness" "setup")
        readme (str (fs/path d "README.md"))
        layers (str (fs/path d "layers.edn"))]
    (fs/create-dirs src)
    (spit (str (fs/path src "a.clj")) "(ns harness.setup.a \"A does a.\")")
    (spit layers "{harness.setup.a #{}}")
    (spit readme (str "# R\n\n" inv/begin-marker "\n" inv/end-marker "\n\nThe lesson of `harness.setup.a`.\n"))
    (let [r (inv/sync! (str (fs/path d "src" "harness")) layers readme)]
      (is (true? (:changed? r)))
      (is (= [] (:unnamed r)))
      (is (str/includes? (slurp readme) "| `harness.setup.a` | setup | 0 | A does a. |")))
    (is (false? (:changed? (inv/sync! (str (fs/path d "src" "harness")) layers readme :check? true))) "current after")
    (is (str/includes? (do (inv/sync! (str (fs/path d "src" "harness")) (str (fs/path d "none.edn")) readme) (slurp readme))
                       "| `harness.setup.a` | setup | — (not in layers.edn) |")
        "no ruleset: the layer is said to be undeclared, the sync does not fail")
    (spit readme "# R\n\nno markers\n")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"markers missing" (inv/sync! (str (fs/path d "src" "harness")) layers readme)))))
