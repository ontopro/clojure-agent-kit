(ns harness.setup.inventory-test
  "The inventory: a namespace row from a file, the block, the names the
  hand-written README must carry, and the sync against this harness."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.inventory :as inv]))

(deftest a-row-is-the-namespace-its-group-and-its-docstrings-first-line
  (is (= {:ns "harness.setup.next" :group "setup" :doc "What comes next in the workflow, read off the build's files: `bb next`."}
         (inv/namespace-row ";; a comment\n(ns harness.setup.next\n  \"What comes next in the workflow, read off the build's files: `bb next`.\n\n  More.\"\n  (:require [x]))\n")))
  (is (= {:ns "harness.rules" :group nil :doc "One rule source."} (inv/namespace-row "(ns harness.rules \"One rule source.\")")) "no group under harness itself")
  (is (= {:ns "harness.gates.run" :group "gates" :doc nil} (inv/namespace-row "(ns harness.gates.run (:require [x]))")) "a namespace with no docstring")
  (is (nil? (inv/namespace-row "(def x 1)")) "not a namespace file"))

(deftest the-block-counts-and-lists-by-group-and-the-hand-written-part-must-name-every-one
  (let [rows [{:ns "harness.rules" :group nil :doc "The rule source."}
              {:ns "harness.setup.doctor" :group "setup" :doc "Toolchain probe."}
              {:ns "harness.loop.driver" :group "loop" :doc nil}]
        block (inv/render rows)]
    (is (str/starts-with? block "3 namespaces, rendered from `src/harness/` by `bb inventory-sync` (`--check` in `bb gates`): `harness.setup` (1: the machine and the workspace), `harness.loop` (1: the steps and the loop), `harness.contract` (0:"))
    (is (str/includes? block ", and `harness.rules` on its own.\n\n| Namespace | Group | The first line of its docstring |\n"))
    (is (str/includes? block "| `harness.loop.driver` | loop | — (no docstring) |"))
    (is (str/includes? block "| `harness.rules` | — | The rule source. |"))
    (testing "a name inside the generated block does not count as a lesson"
      (let [readme (str "# R\n\n" inv/begin-marker "\n\n" block "\n\n" inv/end-marker "\n\n| `harness.setup.doctor` | probes | a lesson |\n")]
        (is (= ["harness.rules" "harness.loop.driver"] (inv/unnamed readme rows)))))))

(deftest this-harness-is-in-sync-and-every-namespace-has-a-lesson
  ;; The README's block is committed; `bb gates` runs the same check. A failure
  ;; here is a namespace added without `bb inventory-sync`, or one the
  ;; hand-written table never names.
  (let [rows (inv/namespaces "src/harness")]
    (is (<= 40 (count rows)) "every file under src/harness/ is a namespace")
    (is (every? :doc rows) (str "a namespace with no docstring: " (mapv :ns (remove :doc rows))))
    (let [{:keys [changed? unnamed]} (inv/sync! "src/harness" "README.md" :check? true)]
      (is (false? changed?) "run `bb inventory-sync`")
      (is (= [] unnamed)))))

(deftest the-sync-writes-between-the-markers-and-refuses-a-readme-without-them
  (let [d (str (fs/create-temp-dir {:prefix "inventory-"}))
        src (fs/path d "src" "harness" "setup")
        readme (str (fs/path d "README.md"))]
    (fs/create-dirs src)
    (spit (str (fs/path src "a.clj")) "(ns harness.setup.a \"A does a.\")")
    (spit readme (str "# R\n\n" inv/begin-marker "\n" inv/end-marker "\n\nThe lesson of `harness.setup.a`.\n"))
    (let [r (inv/sync! (str (fs/path d "src" "harness")) readme)]
      (is (true? (:changed? r)))
      (is (= [] (:unnamed r)))
      (is (str/includes? (slurp readme) "| `harness.setup.a` | setup | A does a. |")))
    (is (false? (:changed? (inv/sync! (str (fs/path d "src" "harness")) readme :check? true))) "current after")
    (spit readme "# R\n\nno markers\n")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"markers missing" (inv/sync! (str (fs/path d "src" "harness")) readme)))))
