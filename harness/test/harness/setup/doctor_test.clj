(ns harness.setup.doctor-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.doctor :as doctor]))

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

(deftest bbin-index-parsing
  (testing "three-column `bbin ls` output becomes {name sha}"
    (with-redefs [doctor/sh (constantly
                             (str "\nclj-nrepl-eval    abc1234  https://example/x.git\n"
                                  "clj-paren-repair  def5678  https://example/x.git\n"))]
      (is (= {"clj-nrepl-eval" "abc1234" "clj-paren-repair" "def5678"}
             (#'doctor/bbin-index)))))
  (testing "bbin absent entirely yields an empty index, not a crash"
    (with-redefs [doctor/sh (constantly nil)]
      (is (= {} (#'doctor/bbin-index))))))

(deftest render
  (let [results (mapv #(doctor/probe % {} {}) specs)]
    (testing "every tool appears in the table, with what it does"
      (let [out (doctor/render-table results)]
        (doseq [{:keys [tool does]} specs]
          (is (str/includes? out (name tool)))
          (is (str/includes? out does)))))
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
  ;; bbin lives on PATH while its shim directory (~/.local/bin) may not.
  ;; `bbin ls` still lists the tool, so the index alone would report ok for
  ;; something the loop cannot actually run — the exact failure this whole
  ;; namespace exists to prevent.
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
;; Version pinning
;; ---------------------------------------------------------------------------

(def ^:private mise-body
  ;; the shape mise actually writes, [alias] table and all
  (str "[tools]\n"
       "java = \"temurin-21.0.2+13.0.LTS\"\n"
       "clojure = \"1.12.1.1550\"\n"
       "babashka = \"1.12.206\"\n"
       "cljfmt = \"0.13.1\"\n"
       "\n"
       "[alias]\n"
       "cljfmt = \"asdf:https://github.com/b-social/asdf-cljfmt\"\n"))

(defn- with-mise [body f]
  (let [dir (str (fs/create-temp-dir))]
    (spit (str (fs/path dir ".mise.toml")) body)
    (f dir)))

(deftest reads-the-tools-table
  (with-mise mise-body
    (fn [dir]
      (let [pins (doctor/mise-pins dir)]
        (testing "each pinned tool yields its major, the pin, and where it came from"
          (is (= 21 (get-in pins [:java :major])))
          (is (= "temurin-21.0.2+13.0.LTS" (get-in pins [:java :pin])))
          (is (= ".mise.toml" (get-in pins [:java :source]))))
        (testing "mise's tool names are mapped to ours"
          (is (= 1 (get-in pins [:bb :major])) "babashka -> :bb")
          (is (not (contains? pins :babashka))))
        (testing "the [alias] table does not bleed into [tools]"
          ;; cljfmt appears in both; only the [tools] value is a version
          (is (= "0.13.1" (get-in pins [:cljfmt :pin])))))))
  (testing "a config with no [tools] table yields no pins"
    (with-mise "[alias]\nfoo = \"bar\"\n"
      (fn [dir] (is (= {} (doctor/mise-pins dir))))))
  (testing "no config at all is not an error — it means no constraints"
    (is (= {} (doctor/mise-pins (str (fs/create-temp-dir)))))))

(deftest the-jdk-is-the-one-exact-major-and-a-pin-that-disagrees-is-the-pins-fault
  (let [spec {:tool :java :via :flag :cmd ["java" "-version"] :req :required :major 21
              :does "JVM" :needed-for "XTDB v2 needs 21"}
        pin (fn [m v] {:java {:major m :pin v :version (str m) :source ".mise.toml"}})]
    (with-redefs [fs/which (constantly "/usr/bin/java")]
      (testing "running 21 is ok, with or without a pin, and a patch level is not a difference"
        (with-redefs [doctor/sh (constantly "openjdk version \"21.0.12.1\"")]
          (is (= :ok (:status (doctor/probe spec {} {}))))
          (let [r (doctor/probe spec {} (pin 21 "temurin-21"))]
            (is (= :ok (:status r)))
            (is (nil? (:note r)) "21.0.12.1 is not 'newer than' a pin that names the major 21"))))
      (testing "another major is :wrong-version, pin or no pin - the KIT's own constraint"
        (with-redefs [doctor/sh (constantly "openjdk version \"25.0.4.1\"")]
          (is (= :wrong-version (:status (doctor/probe spec {} {}))))
          (is (= :wrong-version (:status (doctor/probe spec {} (pin 21 "temurin-21")))))))
      (testing "a pin on another major with the right JDK installed: ok, and the pin is what to change"
        (with-redefs [doctor/sh (constantly "openjdk version \"21.0.12.1\"")]
          (let [r (doctor/probe spec {} (pin 25 "temurin-25"))]
            (is (= :ok (:status r)))
            (is (str/includes? (:note r) "change the pin, not the JDK"))))))))

(deftest every-other-pin-is-a-floor-and-calendar-versions-compare-as-dates
  ;; Row 20's third cause: a 2025.06.05 pin against an installed 2026.08.04 was read as a
  ;; MAJOR mismatch and the doctor said NOT USABLE on a machine that was fine.
  (let [kondo {:tool :clj-kondo :via :flag :cmd ["clj-kondo" "--version"] :req :required
               :does "Linter" :needed-for "gate 2"}
        pin (fn [v] {:clj-kondo {:major (parse-long (re-find #"\d+" v)) :pin v :version v :source ".mise.toml"}})]
    (with-redefs [fs/which (constantly "/usr/bin/clj-kondo")
                  doctor/sh (constantly "clj-kondo v2026.08.04")]
      (testing "newer than the pin is ok, and said"
        (let [r (doctor/probe kondo {} (pin "2025.06.05"))]
          (is (= :ok (:status r)))
          (is (str/includes? (:note r) "a pin is a floor"))))
      (testing "equal to the pin is ok and silent"
        (is (nil? (:note (doctor/probe kondo {} (pin "2026.08.04"))))))
      (testing "below the pin is :too-old"
        (is (= :too-old (:status (doctor/probe kondo {} (pin "2027.01.01"))))))
      (testing "newer than the known-good set is information, never a fault"
        (let [r (doctor/probe kondo {} {} {:clj-kondo "2026.01.01"})]
          (is (= :ok (:status r)))
          (is (str/includes? (:note r) "newer than tested (2026.01.01)")))
        (is (nil? (:note (doctor/probe kondo {} {} {:clj-kondo "2026.08.04"}))))))))

(deftest a-bbin-tool-is-probed-by-running-it
  ;; In `bbin ls` and on PATH was reported ok for a tool that could not run; the probe now asks.
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
      (testing "a different commit from the tested one is a note, not a fault"
        (with-redefs [doctor/run (constantly {:exit 0})]
          (let [r (doctor/probe spec idx {} {:clj-paren-repair "0000000"})]
            (is (= :ok (:status r)))
            (is (str/includes? (:note r) "not the tested commit"))))))))

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

(deftest the-templates-own-mise-file-is-read-comments-and-prefix-pin-included
  ;; the shape the pinned template ships, verbatim in kind: comment lines, a prefix pin, [alias]
  (with-mise (str "# A KNOWN-GOOD SET, not a set of requirements: these versions were run together.\n"
                  "# mise is optional. With it: `mise trust && mise install` - which DOWNLOADS a JDK.\n"
                  "[tools]\n"
                  "java = \"temurin-21\"\n"
                  "clojure = \"1.12.6.1673\"\n"
                  "babashka = \"1.13.223\"\n"
                  "clj-kondo = \"2026.08.04\"\n"
                  "tailwindcss = \"4.3.3\"\n\n"
                  "[alias]\n"
                  "tailwindcss = \"asdf:https://github.com/virtualstaticvoid/asdf-tailwindcss\"\n")
    (fn [dir]
      (let [pins (doctor/mise-pins dir)]
        (is (= {:major 21 :pin "temurin-21" :version "21" :source ".mise.toml"} (:java pins))
            "a prefix pin names a major and nothing finer")
        (is (= "2026.08.04" (get-in pins [:clj-kondo :version])))
        (is (= 1 (get-in pins [:bb :major])))
        (is (contains? pins :tailwindcss) "a tool the doctor does not know is read and ignored")))))

(deftest java-home-divergence
  ;; A pin that only checks PATH is defeated by any launcher honouring
  ;; JAVA_HOME, and the two routinely disagree once several version
  ;; managers are installed.
  (let [home (str (fs/create-temp-dir))]
    (spit (str (fs/path home "release")) "JAVA_VERSION=\"25.0.4.1\"\n")
    (testing "a JAVA_HOME on another major is reported"
      (with-redefs [doctor/java-home (constantly home)]
        (is (str/includes? (doctor/java-home-mismatch "21.0.12") "25"))
        (is (str/includes? (doctor/java-home-mismatch "21.0.12") "21"))))
    (testing "agreement is silent"
      (with-redefs [doctor/java-home (constantly home)]
        (is (nil? (doctor/java-home-mismatch "25.0.4.1")))))
    (testing "no JAVA_HOME set is silent"
      (with-redefs [doctor/java-home (constantly nil)]
        (is (nil? (doctor/java-home-mismatch "21.0.12")))))))

(deftest pins-are-read-per-directory
  ;; `report` could not be reached with a directory before --dir existed, so a
  ;; harness had no way to verify a worktree it had just provisioned.
  (let [dir (str (fs/create-temp-dir))
        nested (str (fs/create-dirs (fs/path dir "src" "deep")))]
    (spit (str (fs/path dir ".mise.toml"))
          "[tools]\njava = \"temurin-21\"\nbabashka = \"1.13.220\"\n")
    (testing "pins come from the named directory"
      (let [pins (doctor/mise-pins dir)]
        (is (= 21 (get-in pins [:java :major])))
        (is (= 1 (get-in pins [:bb :major])) "mise's `babashka` maps to :bb")))
    (testing "and are found by walking up from a subdirectory"
      (is (= 21 (get-in (doctor/mise-pins nested) [:java :major]))))
    (testing "a directory with no config anywhere above it yields no pins"
      ;; fs/create-temp-dir sits outside any repo, so nothing is inherited
      (is (= {} (doctor/mise-pins (str (fs/create-temp-dir))))))))
