(ns harness.doctor-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.doctor :as doctor]))

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

(deftest pins-are-verified-at-the-major
  (let [spec {:tool :java :via :flag :cmd ["java" "-version"] :req :required
              :does "JVM" :needed-for "XTDB v2 needs 21"}
        pin (fn [m v] {:java {:major m :pin v :source ".mise.toml"}})]
    (with-redefs [fs/which (constantly "/usr/bin/java")]
      (testing "running the pinned major is ok"
        (with-redefs [doctor/sh (constantly "openjdk version \"21.0.12.1\"")]
          (is (= :ok (:status (doctor/probe spec {} (pin 21 "temurin-21.0.2")))))))
      (testing "a different major is :wrong-version"
        (with-redefs [doctor/sh (constantly "openjdk version \"25.0.4.1\"")]
          (let [r (doctor/probe spec {} (pin 21 "temurin-21.0.2"))]
            (is (= :wrong-version (:status r)))
            (is (= 21 (get-in r [:pin :major]))))))
      (testing "a patch-level difference is NOT flagged — mise enforces the exact
                pin; the doctor checks the part that actually breaks"
        (with-redefs [doctor/sh (constantly "openjdk version \"21.0.12.1\"")]
          (is (= :ok (:status (doctor/probe spec {} (pin 21 "temurin-21.0.2+13.0.LTS")))))))
      (testing "with no pin there is no constraint"
        (with-redefs [doctor/sh (constantly "openjdk version \"25.0.4.1\"")]
          (is (= :ok (:status (doctor/probe spec {} {})))))))))

(deftest wrong-version-is-not-usable
  (testing "a required tool on the wrong major fails the check"
    (is (not (doctor/ok? [{:tool :java :req :required :status :wrong-version}]))))
  (testing "the report distinguishes it from missing, and names both versions"
    (let [out (doctor/render-table
               [{:tool :java :req :required :status :wrong-version :version "25.0.4.1"
                 :does "JVM" :pin {:major 21 :pin "temurin-21.0.2" :source ".mise.toml"}}])]
      (is (str/includes? out "WRONG VERSION"))
      (is (str/includes? out "temurin-21.0.2"))
      (is (str/includes? out "25.0.4.1"))))
  (testing "a satisfied pin is shown, so the reader knows it was checked"
    (is (str/includes?
         (doctor/render-table
          [{:tool :java :req :required :status :ok :version "21.0.12"
            :does "JVM" :pin {:major 21 :pin "temurin-21.0.2" :source ".mise.toml"}}])
         "pinned 21 via .mise.toml"))))

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
