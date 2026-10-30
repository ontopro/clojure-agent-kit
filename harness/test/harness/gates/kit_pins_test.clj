(ns harness.gates.kit-pins-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.kit-pins :as tn]))

(deftest versions-compare-as-numbers
  (is (pos? (tn/compare-versions "1.13.226" "1.13.225")))
  (is (pos? (tn/compare-versions "0.16.10" "0.16.9")) "not as strings")
  (is (zero? (tn/compare-versions "kit-v1.3" "kit-v1.3.0")) "a missing part is 0")
  (is (neg? (tn/compare-versions "2026.07.24" "2026.08.04")))
  (is (zero? (tn/compare-versions "25.0.5" "25.0.5+8")) "Temurin's build is not the version"))

(deftest the-newest-release-skips-what-is-not-one
  (let [git-pattern (:tag (first (filter #(= :bb (:tool %)) tn/sources)))
        java-pattern (:tag (first (filter #(= :java (:tool %)) tn/sources)))]
    (is (= "2.56.0" (tn/newest git-pattern ["v2.55.1" "v2.56.0" "v2.56.0-rc2" "v2.57.0-rc0"]))
        "a release candidate is never newest")
    (is (= "25.0.5" (tn/newest java-pattern ["jdk-25.0.4+7" "jdk-25.0.4.1+1" "jdk-25.0.5+8" "jdk-2023-06-08-16-57-beta"]))
        "the build number is not the version; a beta is not a release")
    (is (nil? (tn/newest git-pattern ["latest"])))))

(def files
  {:matrix {:bb {:version "1.13.225"} :clj-kondo {:version "2026.08.04"} :java {:version "25.0.5+8"}
            :geckodriver {:version "0.37.1"} :cljfmt {:version "0.16.5"}
            :bbin {:version "0.2.5"} :clojure-mcp-light {:git/sha "d341c239d4b1faa58ccefaf8bc0b1e2a312e2af4"}}
   :template-pins {:default :t :templates {:t {:template 'io.github.o/t :git/tag "kit-v1.3" :git/sha "f12268d"
                                               :deps-new {:git/tag "v0.8.0" :git/sha "2f96530"}}}}})

(def project-set {:tools {:babashka "1.13.225" :clj-kondo "2026.07.24" :tailwindcss "4.3.3"}})

(deftest the-pins-are-read-first-place-first
  (is (= {:bb {:version "1.13.225" :from :download-matrix}
          :clj-kondo {:version "2026.08.04" :from :download-matrix}
          :java {:version "25.0.5+8" :from :download-matrix}
          :geckodriver {:version "0.37.1" :from :download-matrix}
          :cljfmt {:version "0.16.5" :from :download-matrix}
          :bbin {:version "0.2.5" :from :download-matrix}
          :clojure-mcp-light {:version "d341c23" :from :download-matrix}
          :tailwindcss {:version "4.3.3" :from :project-set}
          :template {:version "kit-v1.3" :from :template :gitlib {:lib 'io.github.o/t :sha "f12268d"}}
          :deps-new {:version "0.8.0" :from :template :gitlib {:lib 'io.github.seancorfield/deps-new :sha "2f96530"}}}
         (tn/pins files project-set))
      "bbin and clojure-mcp-light the matrix's (8.2d), a commit by its first seven; a pin no file carries is left out")
  (is (= {:version "2.2.0" :from :xtdb-deps}
         (:xtdb (tn/pins (assoc files :xtdb-deps {'com.xtdb/xtdb-api {:mvn/version "2.2.0"}}) project-set)))
      "XTDB from the health check's deps.edn, the one place the KIT writes it"))

(deftest a-row-carries-what-differs
  (let [rows (tn/rows (tn/pins files project-set)
                      {:bb "1.13.229" :java "25.0.5" :clj-kondo "2026.08.04" :clojure-mcp-light "d341c23"}
                      {:bb "1.13.229" :clj-kondo "2026.08.04" :java "25.0.5" :template "kit-v1.4" :geckodriver nil}
                      project-set)
        by (into {} (map (juxt :tool :marks)) rows)]
    (is (= {:bb [:newer :installed-differs] :clj-kondo [:project-differs] :java [] :geckodriver [:unknown]
            :cljfmt [:unknown] :clojure-mcp-light [:unknown] :tailwindcss [:unknown] :template [:newer]
            :deps-new [:unknown] :clojure [:unpinned] :bbin [:unknown] :xtdb [:unpinned]}
           by)
        (str "the JDK's build is not a difference; a commit installed is not compared with a tag; a source "
             "no file carries is a row, unpinned (From Fable 5.1's read of 51ce107)"))
    (testing "the lines `bb kit-status` and `bb tag-check` print: what moved and what did not answer"
      (is (= ["bb 1.13.225 -> 1.13.229 released"
              "geckodriver 0.37.1 - newest unknown (its repository did not answer)"
              "cljfmt 0.16.5 - newest unknown (its repository did not answer)"
              "clojure-mcp-light d341c23 - newest unknown (its repository did not answer)"
              "bbin 0.2.5 - newest unknown (its repository did not answer)"
              "tailwindcss 4.3.3 - newest unknown (its repository did not answer)"
              "template kit-v1.3 -> kit-v1.4 released"
              "deps-new 0.8.0 - newest unknown (its repository did not answer)"]
             (tn/newer-lines rows))))
    (testing "the report: one line a row, the columns padded"
      (let [t (tn/table rows)]
        (is (= "tool               installed   pinned (where)                               latest      marks" (first t)))
        (is (= (str "bb                 1.13.229    1.13.225 (matrix; project set 1.13.225)      1.13.229    "
                    "newer released; installed differs")
               (second t)))
        (is (some #(str/starts-with? % "tailwindcss        -           4.3.3 (project set) ") t)
            "a tool only a project runs: nothing installed here, no project column of its own")
        (is (some #(re-matches #"clojure +- +- +- +no file carries its pin" %) t) "a lost pin shows")))
    ;; From Fable 5.1's read of 2267ee7: what this machine runs is so whether or not a file pins it
    (is (some #(re-matches #"clojure +1\.12\.6\.1673 +- +- +no file carries its pin" %)
              (tn/table (tn/rows (tn/pins files project-set) {:clojure "1.12.6.1673"} {} project-set))))
    ;; plan 7.0, item 8.2d: a commit pin is equal or not, its newest the default branch's tip - moved,
    ;; never newer (From Fable 5.1's read of 3dbdcda)
    (let [rows-for (fn [installed newest]
                     (tn/rows (tn/pins files project-set) {:clojure-mcp-light installed}
                              {:clojure-mcp-light newest} project-set))
          mcp (fn [installed newest]
                (first (filter #(= :clojure-mcp-light (:tool %)) (rows-for installed newest))))]
      (is (= [] (:marks (mcp "d341c23" "d341c23"))) "the pin is the tip")
      (is (= [:moved] (:marks (mcp "d341c23" "e3cdd0d"))) "the tip moved")
      (is (= [:installed-differs] (:marks (mcp "cf48cc6" "d341c23"))) "another commit installed")
      (is (= [] (:marks (mcp "missing" "d341c23"))) "no commit installed: the doctor says it, not this")
      (is (= [:unknown] (:marks (mcp "d341c23" nil))))
      (is (some #{"clojure-mcp-light d341c23 - its default branch is at e3cdd0d (a commit pin: moved, not newer)"}
                (tn/newer-lines (rows-for "d341c23" "e3cdd0d")))))))

(deftest the-jdk-is-a-pin-like-any-other
  ;; the person, 2026-10-30: "(a) fold java in" - the doctor holds the JDK to the matrix, so another
  ;; JDK is a difference like any tool's; a build after `+` is none (`java -version` prints none)
  (let [ps {:java {:version "25.0.5+8" :from :download-matrix}}
        java-row (fn [inst] (first (filter #(= :java (:tool %)) (tn/rows ps {:java inst} {:java "25.0.5"} {}))))]
    (is (= [] (:marks (java-row "25.0.5"))) "25.0.5 is 25.0.5+8")
    (is (= [:installed-differs] (:marks (java-row "25.0.4"))))
    (is (= [:installed-differs] (:marks (java-row "21.0.12.1"))) "another major: a difference, no longer said apart")))

(deftest the-doctors-map-is-read-whatever-its-exit
  ;; From Fable 5.1's read of 51ce107: the doctor prints its map and exits 1 when a required tool is
  ;; unusable - the machine the report is most for
  (is (= {:bb {:version "1.13.229" :status :ok} :git {:version nil :status :missing :req :required}}
         (tn/read-doctor "{:bb {:version \"1.13.229\" :status :ok} :git {:version nil :status :missing :req :required}}")))
  (is (= {:bb "1.13.229" :clj-kondo "missing"}
         (tn/installed {:bb {:version "1.13.225" :from :download-matrix} :clj-kondo {:version "2026.10.26" :from :download-matrix}}
                       (tn/read-doctor "{:bb {:version \"1.13.229\" :status :ok} :clj-kondo {:version nil :status :missing}}")
                       "/nowhere")))
  (is (= {} (tn/read-doctor "")) "no output")
  (is (= {} (tn/read-doctor "toolchain: not ready")) "not a map"))

(deftest installed-is-the-doctors-probe-first-then-the-librarys-fetch
  (let [home (str (fs/create-temp-dir))
        pins {:bb {:version "1.13.225" :from :download-matrix}
              :cljfmt {:version "0.16.6" :from :download-matrix}
              :clojure-mcp-light {:version "d341c23" :from :download-matrix}
              :clj-kondo {:version "2026.10.26" :from :download-matrix}}
        doctor {:bb {:version "1.13.229" :status :ok} :cljfmt {:version "0.16.6" :status :ok}
                :clj-nrepl-eval {:version "d341c23" :status :ok} :clj-kondo {:version nil :status :missing}}]
    (is (= {:bb "1.13.229" :cljfmt "0.16.6" :clojure-mcp-light "d341c23" :clj-kondo "missing"}
           (tn/installed pins doctor home))
        "a tool the doctor probes is its probe, never its pin read back (Fable 5.1's watch on f9b5af8)")
    (is (= "0.16.5" (:cljfmt (tn/installed pins (assoc-in doctor [:cljfmt :version] "0.16.5") home)))
        "a drifted binary shows, not its pin")
    (is (= "missing" (:cljfmt (tn/installed pins {:cljfmt {:version nil :status :missing}} home)))
        "the doctor's row says missing: missing, not the pin")
    (is (not (contains? (tn/installed pins (dissoc doctor :cljfmt) home) :cljfmt))
        "a binary the doctor has no row for, and no library: nothing to say")
    (testing "a git dep the Clojure CLI fetches, by its sha; XTDB a Maven dep (the person: why is 0.8.0 not shown)"
      (let [ps {:deps-new {:version "0.8.0" :from :template :gitlib {:lib 'io.github.seancorfield/deps-new :sha "2f96530"}}
                :xtdb {:version "2.2.0" :from :xtdb-deps}}]
        (is (= {:deps-new "0.8.0, not fetched" :xtdb "2.2.0, not fetched"} (tn/installed ps {} home)))
        (fs/create-dirs (fs/path home ".gitlibs" "libs" "io.github.seancorfield" "deps-new" "2f96530c963dbe920f2e5945612b96410f6c6e12"))
        (fs/create-dirs (fs/path home ".m2" "repository" "com" "xtdb" "xtdb-api" "2.2.0"))
        (is (= {:deps-new "0.8.0, fetched" :xtdb "2.2.0, fetched"} (tn/installed ps {} home)))))))

(deftest a-record-is-refused-over-a-drifted-tool
  ;; the person, 2026-10-29: "As part of the 'bb health --record' run, it runs the report and if there
  ;; are differences, it stops" - over the tools tag-check's versions line holds; git is no pin (8.2e)
  (let [pins {:bb {:version "1.13.229" :from :download-matrix} :git {:version "2.56.0" :from :download-matrix}
              :java {:version "25.0.5+8" :from :download-matrix} :geckodriver {:version "0.37.1" :from :download-matrix}
              :clj-kondo {:version "2026.10.26" :from :download-matrix}}
        rows (tn/rows pins {:bb "1.13.230" :git "2.57.0" :java "25.0.4" :geckodriver "0.36.0" :clj-kondo "2026.10.26"} {} nil)]
    (is (= [:bb :java] (map :tool (tn/record-drift rows)))
        "git no pin, geckodriver no record's - bb, and the JDK since 8.3a")
    (is (nil? (tn/drift-refusal (tn/record-drift (tn/rows pins {:bb "1.13.229" :java "25.0.5"} {} nil))))
        "no drift, no refusal - 25.0.5 is the pin's 25.0.5+8")
    (let [r (tn/drift-refusal (tn/record-drift rows))]
      (is (str/starts-with? r "--record refused: this machine runs other versions than the KIT pins"))
      (is (str/includes? r "    bb  installed 1.13.230, pinned 1.13.229 (matrix)"))
      (is (str/ends-with? r "then run this again. Nothing was run.")))))
