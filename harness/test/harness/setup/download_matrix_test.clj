(ns harness.setup.download-matrix-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.download-matrix :as download-matrix]
   [toolchain-download-matrix :as dm]))

(def clone (download-matrix/clone-dir))
(def matrix (download-matrix/read-matrix clone))
(def packs (dm/read-matrix "../tools/toolchain/download-matrix.edn"))

(deftest the-harness-reads-the-matrix-as-the-pack-does
  ;; plan 7.0, item 8.3a: the harness never loads the pack - its two copied rules held to the pack's
  (is (= (str (fs/canonicalize "../tools/toolchain/download-matrix.edn"))
         (str (fs/canonicalize (download-matrix/path clone))))
      "the clone found from the harness's own resources")
  (is (= (update-vals packs :version) (update-vals matrix :version)) "every tool's version, the commit's seven among them")
  (doseq [[os arch] [["Mac OS X" "aarch64"] ["Mac OS X" "x86_64"] ["Linux" "aarch64"] ["Linux" "amd64"] ["Linux" "riscv64"] ["Windows 11" "amd64"]]]
    (is (= (dm/platform-key os arch) (download-matrix/platform matrix os arch)) (str os " " arch))))

(deftest the-doctors-reference-is-each-row-the-matrix-names
  (let [ref (download-matrix/reference matrix)]
    (is (= "25.0.5+8" (:java ref)))
    (is (= "d341c23" (:clj-nrepl-eval ref) (:clj-paren-repair ref) (:clj-paren-repair-claude-hook ref))
        "clojure-mcp-light's commit on its three commands")
    (is (not (contains? ref :geckodriver)) "an image-only tool: a machine never takes it")
    (is (not (contains? ref :clojure-mcp-light)) "the bbin row by its commands, not its name"))
  (testing "a doctor row's matrix tool"
    (is (= :clojure-mcp-light (download-matrix/matrix-tool matrix :clj-paren-repair)))
    (is (= :cljfmt (download-matrix/matrix-tool matrix :cljfmt)))
    (is (nil? (download-matrix/matrix-tool matrix :docker)))))
