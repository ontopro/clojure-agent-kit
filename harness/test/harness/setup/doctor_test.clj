(ns harness.setup.doctor-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.doctor :as doctor]
   [toolchain-download-matrix :as dm]))

(def wrapper
  ;; the head of a wrapper bbin 0.2.5 wrote (plan 7.0, item 8.2d)
  (str "#!/usr/bin/env bb\n\n; :bbin/start\n;\n; {:coords\n;  {:git/url \"https://github.com/bhauman/clojure-mcp-light.git\",\n"
       ";   :git/sha \"d341c239d4b1faa58ccefaf8bc0b1e2a312e2af4\"},\n;  :lib\n;  org.babashka.bbin/script-x}\n;\n; :bbin/end\n\n"
       "(def script-coords {:git/sha \"0000000000000000000000000000000000000000\"})\n"))

;; NO TEST SEES THIS MACHINE'S JAVA_HOME: a test that faked PATH java at 21 went red the day this
;; machine's JAVA_HOME moved to 25. Since 8.3a it is an input of `probe` (its `env`), `{}` unless given.

(def ^:private specs
  [{:tool :bb :via :flag :cmd ["bb" "--version"] :req :required :min "1.12.212"
    :does "Task runner" :needed-for "everything"}
   {:tool :clj-paren-repair :via :bbin :req :required
    :does "Delimiter repair" :needed-for "gate 0"}
   {:tool :neil :via :flag :cmd ["neil" "--version"] :req :optional
    :does "Scaffolding" :needed-for "step 1"}])

(deftest probe-statuses
  (let [idx {"clj-paren-repair" "d341c239d4b1faa58ccefaf8bc0b1e2a312e2af4"}]
    (testing "a bbin-installed tool reports its pinned sha, abbreviated"
      (let [r (doctor/probe (second specs) idx {})]
        (is (= :ok (:status r)))
        (is (= "d341c23" (:version r)) "seven chars, like git")))
    (testing "a bbin tool absent from the index is missing"
      (is (= :missing (:status (doctor/probe (second specs) {} {})))))
    (testing "a tool below its :min is :too-old, not :ok"
      (with-redefs [doctor/sh (constantly "babashka v1.10.0")]
        (is (= :too-old (:status (doctor/probe (first specs) idx {}))))))
    (testing "a tool at or above :min is ok"
      (with-redefs [doctor/sh (constantly "babashka v1.13.220")]
        (is (= :ok (:status (doctor/probe (first specs) idx {}))))))
    (testing "output with no parseable version is :unknown-version, not :missing"
      (with-redefs [doctor/sh (constantly "some banner with no digits")]
        (is (= :unknown-version (:status (doctor/probe (first specs) idx {}))))))))

(deftest required-vs-recommended
  (testing "a missing REQUIRED tool fails the check"
    (is (not (doctor/ok? [{:tool :cljfmt :req :required :status :missing}]))))
  (testing "a too-old required tool fails too"
    (is (not (doctor/ok? [{:tool :bb :req :required :status :too-old}]))))
  (testing "missing recommended and optional tools report but never fail"
    (is (doctor/ok? [{:tool :neil :req :optional :status :missing}
                     {:tool :bbin :req :recommended :status :missing}
                     {:tool :bb :req :required :status :ok}]))))

(deftest a-bbin-tools-commit-is-its-headers-on-the-path
  ;; plan 7.0, item 8.2d: no `bbin ls` - it read bbin's own folder, not the toolchain folder's bin/
  (testing "the header's commit, not a sha later in the file"
    (is (= "d341c239d4b1faa58ccefaf8bc0b1e2a312e2af4" (doctor/bbin-commit wrapper))))
  (testing "no header, no text: nil"
    (is (nil? (doctor/bbin-commit "#!/usr/bin/env bb\n(println 1)")))
    (is (nil? (doctor/bbin-commit nil))))
  (testing "the pack reads the same header the same way"
    (is (= (dm/bbin-commit wrapper) (doctor/bbin-commit wrapper))))
  (testing "the command the PATH gives, read; one absent or with no header, left out"
    (let [dir (fs/create-temp-dir {:prefix "bbin-commits-"})
          f (str (fs/path dir "clj-paren-repair"))]
      (spit f wrapper)
      (spit (str (fs/path dir "clj-nrepl-eval")) "#!/bin/sh\n")
      (with-redefs [fs/which (fn [n] (let [p (fs/path dir n)] (when (fs/exists? p) p)))]
        (is (= {"clj-paren-repair" "d341c239d4b1faa58ccefaf8bc0b1e2a312e2af4"}
               (doctor/bbin-commits (concat specs [{:tool :clj-nrepl-eval :via :bbin}
                                                   {:tool :clj-paren-repair-claude-hook :via :bbin}]))))))))

(deftest render
  (let [results (mapv #(doctor/probe % {} {}) specs)]
    (testing "every tool appears in the table; what it does, only when it is not ok"
      (let [out (doctor/render-table results)]
        (doseq [{:keys [tool]} specs]
          (is (str/includes? out (name tool)))))
      (let [out (doctor/render-table [{:tool :bb :req :required :status :ok :does "Task runner"}
                                      {:tool :neil :req :optional :status :missing :does "Scaffolding"}])]
        (is (not (str/includes? out "Task runner")) "an ok tool is its name, versions and status")
        (is (str/includes? out "Scaffolding"))))
    (testing "two parts: what the gates and a loop need, then the optional; an absent optional tool is not installed"
      (let [out (doctor/render-table [{:tool :bb :req :required :status :ok}
                                      {:tool :opencode2 :req :optional :status :missing :does "x"}])]
        (is (< (str/index-of out "required") (str/index-of out "bb") (str/index-of out "Optional") (str/index-of out "opencode2")))
        (is (str/includes? out "not installed"))
        (is (not (str/includes? out "missing")))))
    (testing "a missing required tool is shouted, a missing optional one is not"
      (let [out (doctor/render-table
                 [{:tool :cljfmt :req :required :status :missing :does "Formatter"}
                  {:tool :neil :req :optional :status :missing :does "Scaffolding"}])]
        (is (str/includes? out "MISSING"))
        (is (str/includes? out "cljfmt"))))
    (testing "--edn output is a pins-shaped map keyed by tool"
      (let [m (doctor/render-edn results)]
        (is (= (set (map :tool specs)) (set (keys m))))
        (is (every? #(contains? % :status) (vals m)))))))

(deftest probes-never-invoke-a-tool-destructively
  ;; clj-paren-repair --version does NOT error: it treats --version as a
  ;; FILENAME and runs a repair pass. Any probe that guesses a flag can
  ;; therefore mutate the repo it was meant to inspect.
  (testing "every :flag probe uses a flag the tool documents"
    (doseq [{:keys [tool via cmd]} doctor/toolchain
            :when (= :flag via)]
      (is (contains? doctor/flag-allowlist (last cmd))
          (str (name tool) " probes with " (pr-str (last cmd))))))
  (testing "the file-taking clojure-mcp-light tools are probed via bbin, never a flag"
    (doseq [t [:clj-nrepl-eval :clj-paren-repair :clj-paren-repair-claude-hook]]
      (is (= :bbin (:via (first (filter #(= t (:tool %)) doctor/toolchain))))
          (str (name t) " must not be probed with a flag")))))

(deftest installed-but-unreachable
  ;; A commit given for a tool the PATH does not give (an index passed in) is not ok:
  ;; the loop cannot run what is not on its PATH - the failure this whole namespace
  ;; exists to prevent.
  (let [idx {"clj-paren-repair" "d341c239d4b1faa58ccefaf8bc0b1e2a312e2af4"}
        spec (second specs)]
    (testing "in the bbin index but not on PATH is :not-on-path, not :ok"
      (with-redefs [fs/which (constantly nil)]
        (is (= :not-on-path (:status (doctor/probe spec idx {}))))))
    (testing "and it fails the required check like any other unusable tool"
      (is (not (doctor/ok? [{:tool :clj-paren-repair :req :required
                             :status :not-on-path}]))))
    (testing "the report distinguishes it from simply absent"
      (is (str/includes?
           (doctor/render-table [{:tool :clj-paren-repair :req :required
                                  :status :not-on-path :does "Delimiter repair"}])
           "NOT ON PATH")))))

(deftest require-tool-explains-itself
  (testing "a missing tool throws something a human can act on"
    (with-redefs [doctor/sh (constantly nil)
                  fs/which (constantly nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bb doctor"
                            (doctor/require-tool! :clj-paren-repair)))
      (let [data (try (doctor/require-tool! :clj-paren-repair)
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :clj-paren-repair (:tool data)))
        (is (some? (:does data)) "names what the tool is for")
        (is (some? (:needed-for data)) "names which gate needs it"))))
  (testing "a present tool just returns true"
    (is (true? (doctor/require-tool! :bb)))))

;; ---------------------------------------------------------------------------
;; The reference: the download matrix (plan 7.0, item 8.3a)
;; ---------------------------------------------------------------------------

(deftest the-jdk-is-held-to-the-matrix-and-only-off-it-to-21-and-newer
  ;; the person, 2026-10-30: the JDK is installed like every tool; an own JDK only where the matrix
  ;; publishes no build
  (let [spec (first (filter #(= :java (:tool %)) doctor/toolchain))
        probe (fn [v ref & [env]] (with-redefs [fs/which (constantly "/usr/bin/java")
                                                doctor/sh (constantly (str "openjdk version \"" v "\""))]
                                    (doctor/probe spec {} ref {} (or env {}))))
        ref {:java "25.0.5+8"}]
    (is (= {:min "21" :prefer-major 25} (select-keys spec [:min :prefer-major :major])) "the floor and the preference, for no reference")
    (testing "held to the matrix: its build after + is no version"
      (let [r (probe "25.0.5" ref)] (is (= :ok (:status r))) (is (nil? (:note r))) (is (= "25.0.5+8" (:reference r))))
      (is (= :too-old (:status (probe "25.0.4" ref))))
      (is (= :too-old (:status (probe "21.0.13" ref))) "21 against 25: the true-up installs 25")
      (let [r (probe "25.0.6" ref)]
        (is (= :ok (:status r)))
        (is (= "newer than the KIT's download matrix (25.0.5+8)" (:note r)))))
    (testing "no reference - a machine the matrix publishes no build for: 21 and newer, 25 preferred"
      (is (= :ok (:status (probe "25.0.5" nil))))
      (let [r (probe "21.0.13" nil)]
        (is (= :ok (:status r)) "21 works")
        (is (:not-preferred? r) "and is said on a ready machine")
        (is (str/includes? (:note r) "major 25 is preferred, the one the KIT tests on; 21 works - 21 and newer")))
      (is (= :too-old (:status (probe "17.0.9" nil))) "below the floor"))
    (testing "JAVA_HOME and JAVA_CMD are inputs, never this machine's"
      (is (str/includes? (:note (probe "25.0.5" ref {"JAVA_CMD" "/opt/jdk21/bin/java"}))
                         "JAVA_CMD is set (/opt/jdk21/bin/java) - the clojure script runs it, not the PATH's java")))))

(deftest the-matrix-is-the-reference-and-calendar-versions-compare-as-dates
  ;; Row 20's third cause: a 2025.06.05 pin against an installed 2026.08.04 was read as a
  ;; MAJOR mismatch and the doctor said NOT USABLE on a machine that was fine.
  (let [kondo {:tool :clj-kondo :via :flag :cmd ["clj-kondo" "--version"] :req :required
               :does "Linter" :needed-for "gate 2"}]
    (with-redefs [fs/which (constantly "/usr/bin/clj-kondo")
                  doctor/sh (constantly "clj-kondo v2026.08.04")]
      (testing "newer than the matrix is ok, and said"
        (let [r (doctor/probe kondo {} {:clj-kondo "2025.06.05"})]
          (is (= :ok (:status r)))
          (is (= "newer than the KIT's download matrix (2025.06.05)" (:note r)))))
      (testing "equal to the matrix is ok and silent"
        (is (nil? (:note (doctor/probe kondo {} {:clj-kondo "2026.08.04"})))))
      (testing "below the matrix is :too-old - a machine that passed `bb gates` before 8.3a"
        (is (= :too-old (:status (doctor/probe kondo {} {:clj-kondo "2026.10.26"})))))
      (testing "with no reference, known-good's newer is information, never a fault"
        (let [r (doctor/probe kondo {} {} {:clj-kondo "2026.01.01"})]
          (is (= :ok (:status r)))
          (is (str/includes? (:note r) "newer than tested (2026.01.01)")))
        (is (nil? (:note (doctor/probe kondo {} {} {:clj-kondo "2026.08.04"}))))
        (is (nil? (:note (doctor/probe kondo {} {:clj-kondo "2026.08.04"} {:clj-kondo "2026.01.01"})))
            "with one, the matrix speaks and known-good is the column")))))

(deftest the-reference-is-the-clones-matrix-the-jdk-off-it-where-no-build-is-published
  (let [clone (str (fs/parent (fs/real-path ".")))]
    (is (= "25.0.5+8" (:java (doctor/reference clone "Mac OS X" "aarch64"))))
    (is (= "d341c23" (:clj-nrepl-eval (doctor/reference clone "Linux" "amd64"))))
    (is (not (contains? (doctor/reference clone "Mac OS X" "x86_64") :java)) "an Intel Mac: an own JDK, at its floor")
    (is (= "0.16.6" (:cljfmt (doctor/reference clone "Mac OS X" "x86_64"))) "every other tool still the matrix's")
    (is (nil? (doctor/reference (str (fs/create-temp-dir)) "Linux" "amd64")) "no matrix, no reference")))

;; ---------------------------------------------------------------------------
;; What fixes a row, and the offer (plan 7.0, item 8.3a)
;; ---------------------------------------------------------------------------

(def ^:private clone (str (fs/parent (fs/real-path "."))))
(def ^:private matrix (dm/read-matrix "../tools/toolchain/download-matrix.edn"))
(def ^:private status
  {:folder "/p" :cache "/p/.kit/toolchain" :env "/p/.env"
   :rows [{:tool :bb :present? true} {:tool :java :present? false} {:tool :cljfmt :present? true}
          {:tool :clojure-mcp-light :present? false}]})

(deftest every-row-names-what-fixes-it
  (let [ctx (doctor/fix-context clone status matrix)
        true-up (str "bb --config " clone "/tools/toolchain/bb.edn toolchain-install")]
    (is (= true-up (:true-up ctx)))
    (testing "the folder has it, this shell's PATH does not: the eval"
      (is (= "eval \"$(bb toolchain-env)\" in this shell - the project's folder has it"
             (doctor/fix-for {:tool :cljfmt :install "x"} ctx))))
    (testing "the folder lacks it: the true-up - the JDK and clojure-mcp-light's commands among them"
      (is (= true-up (doctor/fix-for {:tool :java :install "x"} ctx)))
      (is (= true-up (doctor/fix-for {:tool :clj-paren-repair :install "x"} ctx))))
    (testing "bb and git: their own route, the clone's path filled - the true-up cannot replace the running bb"
      (is (= "https://babashka.org" (doctor/fix-for {:tool :bb :install "https://babashka.org"} ctx)))
      (is (= (str "bb --config " clone "/tools/toolchain/bb.edn toolchain-install --only bbin")
             (doctor/fix-for {:tool :bbin :install "bb --config <kit>/tools/toolchain/bb.edn toolchain-install --only bbin"}
                             (assoc ctx :present #{:bbin} :unpublished "x")))
          "a machine the matrix publishes no build for: its own route"))
    (testing "a tool no matrix row stands behind, and no context at all: its own route"
      (is (= "docker.com" (doctor/fix-for {:tool :docker :install "docker.com"} ctx)))
      (is (= "page" (doctor/fix-for {:tool :cljfmt :install "page"} nil))))
    (testing "the table says where the toolchain is, and the true-up once"
      (let [out (doctor/render-table [{:tool :java :req :required :tier :loop :status :missing :does "JVM" :install "x"}]
                                     nil ctx)]
        (is (str/includes? out "the project folder /p - the tools in /p/.kit/toolchain, the keys in /p/.env"))
        (is (str/includes? out (str "fix: " true-up)))
        (is (str/includes? out (str "the true-up, every tool the project's folder lacks: " true-up)))))
    (testing "a machine the matrix publishes no build for: its one sentence in the header"
      (is (str/includes? (doctor/render-table [] nil (doctor/fix-context clone (assoc status :unpublished "the download matrix publishes no build") matrix))
                         "  the download matrix publishes no build")))))

(deftest the-offer-lists-each-install-its-size-the-total-and-the-disk
  ;; the person, 2026-10-30: one item a line, then the total, then the disk's free space
  (let [items [{:tool :java :version "25.0.5+8" :disk-mb 294} {:tool :cljfmt :version "0.16.6" :disk-mb 36}]
        {:keys [lines fits?]} (doctor/offer-lines items {:free-mb (* 212 1024) :total-mb (* 994 1024)})]
    (is fits?)
    (is (= ["The true-up installs, into the project's toolchain folder:"
            "  - java 25.0.5+8 (about 294 MB on disk)"
            "  - cljfmt 0.16.6 (about 36 MB on disk)"
            ""
            "Total installs: 2 (about 330 MB on disk)"
            "Available disk space: 212.0 GB free of 994.0 GB disk."]
           (vec lines)))
    (let [{:keys [lines fits?]} (doctor/offer-lines items {:free-mb 100 :total-mb (* 3 1024 1024)})]
      (is (not fits?) "no question asked")
      (is (= "Available disk space: 100 MB free of 3.0 TB disk." (nth lines 5)))
      (is (str/starts-with? (last lines) "Not enough space for it"))))
  (testing "asked only by hand at a terminal, on a published platform, when a row's fix is the true-up"
    (let [ctx (doctor/fix-context clone status matrix)
          bad [{:tool :java :req :required :status :missing}]
          good [{:tool :java :req :required :status :ok}]]
      (is (doctor/offer? true [] bad ctx))
      (is (not (doctor/offer? false [] bad ctx)) "no terminal: the command is printed")
      (is (not (doctor/offer? true ["--tier" "gates"] bad ctx)) "bb gates is never asked")
      (is (not (doctor/offer? true ["--edn"] bad ctx)) "kit-pins is never asked")
      (is (not (doctor/offer? true [] good ctx)) "nothing to true up")
      (is (not (doctor/offer? true [] bad (assoc ctx :unpublished "x"))) "the true-up installs nothing there")
      (is (not (doctor/offer? true [] [{:tool :bb :req :required :status :too-old}] ctx)) "bb's fix is not the true-up"))))

(deftest a-bbin-tool-is-probed-by-running-it
  ;; Installed and on PATH was reported ok for a tool that could not run; the probe now asks.
  (let [spec (second specs)
        idx {"clj-paren-repair" "d341c239d4b1faa58ccefaf8bc0b1e2a312e2af4"}]
    (with-redefs [fs/which (constantly "/home/x/.local/bin/clj-paren-repair")]
      (testing "answers --help: ok"
        (with-redefs [doctor/run (constantly {:exit 0})]
          (is (= :ok (:status (doctor/probe spec idx {}))))))
      (testing "exits non-zero, or hangs past the timeout: DOES NOT RUN, and unusable"
        (doseq [answer [{:exit 1} {:exit :timeout}]]
          (with-redefs [doctor/run (constantly answer)]
            (let [r (doctor/probe spec idx {})]
              (is (= :broken (:status r)) (pr-str answer))
              (is (not (doctor/ok? [r])))
              (is (str/includes? (doctor/render-table [r]) "DOES NOT RUN"))))))
      (testing "the probe is --help, a documented flag, never a filename the tool would repair"
        (let [seen (atom nil)]
          (with-redefs [doctor/run (fn [argv] (reset! seen argv) {:exit 0})]
            (doctor/probe spec idx {})
            (is (= ["clj-paren-repair" "--help"] @seen))
            (is (contains? doctor/flag-allowlist (last @seen))))))
      (testing "with no reference, a different commit from the tested one is a note, not a fault"
        (with-redefs [doctor/run (constantly {:exit 0})]
          (let [r (doctor/probe spec idx {} {:clj-paren-repair "0000000"})]
            (is (= :ok (:status r)))
            (is (str/includes? (:note r) "not the tested commit")))))
      (testing "against the matrix, a commit is equal or WRONG VERSION - a commit has no above (8.3a)"
        (with-redefs [doctor/run (constantly {:exit 0})]
          (is (= :ok (:status (doctor/probe spec idx {:clj-paren-repair "d341c23"}))))
          (let [r (doctor/probe spec idx {:clj-paren-repair "cf48cc6"})]
            (is (= :wrong-version (:status r)))
            (is (str/includes? (doctor/render-table [r]) "the KIT's download matrix pins cf48cc6 but d341c23 is installed"))))))))

(deftest two-verdicts-and-what-fixes-a-miss
  (let [results [{:tool :bb :req :required :tier :gates :status :ok :does "bb"}
                 {:tool :java :req :required :tier :loop :status :missing :does "JVM"
                  :install "Temurin 21: https://adoptium.net"}
                 {:tool :neil :req :optional :status :missing :does "x"}]]
    (testing "the gates tier can be green while the loop tier is not"
      (is (true? (doctor/ok? results :gates)))
      (is (false? (doctor/ok? results :loop)))
      (is (false? (doctor/ok? results)) "no tier named: everything"))
    (testing "both verdicts are printed, the red one naming its tools and statuses"
      (let [out (doctor/render-table results)]
        (is (str/includes? out "The KIT's gates can run here: yes"))
        (is (str/includes? out "A loop can run here: NO - java (missing)"))))
    (testing "an unusable tool's line says what fixes it; a usable one's does not"
      (let [out (doctor/render-table results)]
        (is (str/includes? out "fix: Temurin 21: https://adoptium.net"))
        (is (= 1 (count (re-seq #"fix:" out))))))
    (testing "every required tool in the shipped toolchain names its fix and its tier"
      (doseq [{:keys [tool req tier install]} doctor/toolchain :when (= :required req)]
        (is (string? install) (name tool))
        (is (contains? #{:gates :loop} tier) (name tool))))))

(deftest the-known-good-set-is-dated-and-names-every-required-tool
  (let [good (doctor/known-good)]
    (is (re-matches #"\d{4}-\d{2}-\d{2}" (:as-of good)))
    (is (string? (:platform good)))
    (doseq [{:keys [tool req]} doctor/toolchain :when (= :required req)]
      (is (string? (get-in good [:tools tool])) (name tool)))))

(deftest java-home-divergence
  ;; A check that only reads the PATH is defeated by any launcher honouring
  ;; JAVA_HOME, and the two routinely disagree once several version
  ;; managers are installed. JAVA_HOME is an input (plan 7.0, item 8.3a).
  (let [home (str (fs/create-temp-dir))]
    (spit (str (fs/path home "release")) "JAVA_VERSION=\"25.0.4.1\"\n")
    (testing "a JAVA_HOME on another major is reported"
      (is (str/includes? (doctor/java-home-mismatch "21.0.12" home) "25"))
      (is (str/includes? (doctor/java-home-mismatch "21.0.12" home) "21")))
    (testing "agreement is silent"
      (is (nil? (doctor/java-home-mismatch "25.0.4.1" home))))
    (testing "no JAVA_HOME set is silent"
      (is (nil? (doctor/java-home-mismatch "21.0.12" nil))))))

(deftest the-toolchain-is-data-a-release-can-be-read-from-its-tag
  ;; `bb upgrade` reads a release's requirements as `git show <tag>:harness/resources/toolchain.edn`,
  ;; without running the release's code: the table is plain EDN, and what the doctor reads is it.
  (let [text (slurp (str (fs/path (fs/parent (fs/real-path ".")) "harness" "resources" doctor/toolchain-resource)))
        from-text (doctor/read-toolchain text)]
    (is (= doctor/toolchain from-text) "the doctor's table is the file, nothing added in code")
    (is (not (str/includes? text "#(")) "plain EDN: no reader macros, readable at any tag")
    (is (= 18 (count from-text)) "docker's row since plan 7.0, item 8.1c")
    (doseq [{:keys [tool via cmd req tier install]} from-text]
      (is (#{:flag :bbin :which} via) (str tool))
      (is (#{:required :recommended :optional} req) (str tool))
      (when (= :flag via) (is (vector? cmd) (str tool)))
      (when (= :required req)
        (is (#{:gates :loop} tier) (str tool " names its tier"))
        (is (string? install) (str tool " names its fix"))))
    (testing "the one exact major and the one floor survive as data"
      (is (= {:min "21" :prefer-major 25} (select-keys (first (filter #(= :java (:tool %)) from-text)) [:min :prefer-major :major])))
      (is (= "1.12.212" (:min (first (filter #(= :bb (:tool %)) from-text))))))
    (testing "the bbin tools' install lines are the pack's true-up, no version in them (8.2d)"
      (doseq [t [:clj-nrepl-eval :clj-paren-repair :clj-paren-repair-claude-hook]
              :let [i (:install (first (filter #(= t (:tool %)) from-text)))]]
        (is (str/includes? i "toolchain-install --only bbin,clojure-mcp-light") (str t))
        (is (not (re-find #"\d+\.\d+|--tag|--sha" i)) (str t))))))
