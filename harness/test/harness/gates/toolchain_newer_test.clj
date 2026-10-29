(ns harness.gates.toolchain-newer-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.toolchain-newer :as tn]))

(deftest versions-compare-as-numbers
  (is (pos? (tn/compare-versions "1.13.226" "1.13.225")))
  (is (pos? (tn/compare-versions "0.16.10" "0.16.9")) "not as strings")
  (is (zero? (tn/compare-versions "kit-v1.3" "kit-v1.3.0")) "a missing part is 0")
  (is (neg? (tn/compare-versions "2026.07.24" "2026.08.04")))
  (is (zero? (tn/compare-versions "25.0.5" "25.0.5+8")) "Temurin's build is not the version"))

(deftest the-newest-release-skips-what-is-not-one
  (let [git-pattern (:tag (first (filter #(= :git (:tool %)) tn/sources)))
        java-pattern (:tag (first (filter #(= :java (:tool %)) tn/sources)))]
    (is (= "2.56.0" (tn/newest git-pattern ["v2.55.1" "v2.56.0" "v2.56.0-rc2" "v2.57.0-rc0"]))
        "a release candidate is never newest")
    (is (= "25.0.5" (tn/newest java-pattern ["jdk-25.0.4+7" "jdk-25.0.4.1+1" "jdk-25.0.5+8" "jdk-2023-06-08-16-57-beta"]))
        "the build number is not the version; a beta is not a release")
    (is (nil? (tn/newest git-pattern ["latest"])))))

(def files
  {:matrix {:bb {:version "1.13.225"} :clj-kondo {:version "2026.08.04"} :java {:version "25.0.5+8"}
            :geckodriver {:version "0.37.1"}}
   :dockerfile "ARG BB_VERSION=1.13.0\nARG MCP_LIGHT_TAG=v0.2.2\n  ARG INDENTED=1\nARG CLJFMT_VERSION=0.16.5\n"
   :template-pins {:default :t :templates {:t {:template 'io.github.o/t :git/tag "kit-v1.3" :git/sha "f12268d"
                                               :deps-new {:git/tag "v0.8.0" :git/sha "2f96530"}}}}})

(def project-set {:tools {:babashka "1.13.225" :clj-kondo "2026.07.24" :tailwindcss "4.3.3"}})

(deftest the-pins-are-read-first-place-first
  (is (= {:bb {:version "1.13.225" :from :download-matrix}
          :clj-kondo {:version "2026.08.04" :from :download-matrix}
          :java {:version "25.0.5+8" :from :download-matrix}
          :geckodriver {:version "0.37.1" :from :download-matrix}
          :cljfmt {:version "0.16.5" :from :dockerfile}
          :clojure-mcp-light {:version "0.2.2" :from :dockerfile}
          :tailwindcss {:version "4.3.3" :from :project-set}
          :template {:version "kit-v1.3" :from :template :gitlib {:lib 'io.github.o/t :sha "f12268d"}}
          :deps-new {:version "0.8.0" :from :template :gitlib {:lib 'io.github.seancorfield/deps-new :sha "2f96530"}}}
         (tn/pins files project-set))
      "the matrix before the Dockerfile; a pin no file carries is left out")
  (is (= {:version "0.16.6" :from :pack-deps}
         (:cljfmt (tn/pins (assoc files :pack-deps {'dev.weavejester/cljfmt {:mvn/version "0.16.6"}}) project-set)))
      "once the pack's bb.edn has the library, it is the pin")
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
            :deps-new [:unknown] :clojure [:unpinned] :git [:unpinned] :bbin [:unpinned] :xtdb [:unpinned]}
           by)
        (str "the JDK's build is not a difference; a commit installed is not compared with a tag; a source "
             "no file carries is a row, unpinned (From Fable 5.1's read of 51ce107)"))
    (testing "the lines `bb kit-status` and `bb tag-check` print: what moved and what did not answer"
      (is (= ["bb 1.13.225 -> 1.13.229 released"
              "geckodriver 0.37.1 - newest unknown (its repository did not answer)"
              "cljfmt 0.16.5 - newest unknown (its repository did not answer)"
              "clojure-mcp-light 0.2.2 - newest unknown (its repository did not answer)"
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
        (is (some #(re-matches #"git +- +- +- +no file carries its pin" %) t) "a lost pin shows")))
    ;; From Fable 5.1's read of 2267ee7: what this machine runs is so whether or not a file pins it
    (is (some #(re-matches #"git +2\.56\.0 +- +- +no file carries its pin" %)
              (tn/table (tn/rows (tn/pins files project-set) {:git "2.56.0"} {} project-set))))))

(deftest a-jdk-of-another-major-is-allowed-and-said-apart
  ;; From Fable 5.1's read of 51ce107: 21 and newer, 25 preferred (the person, 2026-10-26) - a machine
  ;; on 21 is not a difference; another patch of the pin's major is
  (let [ps {:java {:version "25.0.5+8" :from :download-matrix}}
        java-row (fn [inst notes] (first (filter #(= :java (:tool %))
                                                 (tn/rows ps {:java inst} {:java "25.0.5"} {} notes))))]
    (is (= [:other-major] (:marks (java-row "21.0.12.1" {}))))
    (is (= "installed major 21, allowed" (:note (java-row "21.0.12.1" {}))))
    (is (= "major 25 is preferred, the one the KIT tests on; 21 works - 21 and newer"
           (:note (java-row "21.0.12.1" {:java "major 25 is preferred, the one the KIT tests on; 21 works - 21 and newer"})))
        "the doctor's own words, never the report's restatement")
    (is (= [:installed-differs] (:marks (java-row "25.0.4" {}))))
    (is (= [] (:marks (java-row "25.0.5" {}))))
    (is (some #(str/ends-with? % "major 25 is preferred")
              (tn/table [(assoc (java-row "21.0.12.1" {:java "major 25 is preferred"}) :installed "21.0.12.1")])))))

(deftest the-doctors-map-is-read-whatever-its-exit
  ;; From Fable 5.1's read of 51ce107: the doctor prints its map and exits 1 when a required tool is
  ;; unusable - the machine the report is most for
  (is (= {:bb {:version "1.13.229" :status :ok} :git {:version nil :status :missing :req :required}}
         (tn/read-doctor "{:bb {:version \"1.13.229\" :status :ok} :git {:version nil :status :missing :req :required}}")))
  (is (= {:bb "1.13.229" :git "missing"}
         (tn/installed {:bb {:version "1.13.225" :from :download-matrix} :git {:version "2.56.0" :from :download-matrix}}
                       (tn/read-doctor "{:bb {:version \"1.13.229\" :status :ok} :git {:version nil :status :missing}}")
                       "/nowhere")))
  (is (= {} (tn/read-doctor "")) "no output")
  (is (= {} (tn/read-doctor "toolchain: not ready")) "not a map"))

(deftest installed-is-the-doctors-probe-or-the-librarys-fetch
  (let [home (str (fs/create-temp-dir))
        pins {:bb {:version "1.13.225" :from :download-matrix}
              :cljfmt {:version "0.16.6" :from :pack-deps}
              :clojure-mcp-light {:version "0.2.2" :from :dockerfile}
              :git {:version "2.56.0" :from :download-matrix}}
        doctor {:bb {:version "1.13.229" :status :ok} :cljfmt {:version "0.16.6" :status :ok}
                :clj-nrepl-eval {:version "d341c23" :status :ok} :git {:version nil :status :missing}}]
    (is (= {:bb "1.13.229" :cljfmt "0.16.6, not fetched" :clojure-mcp-light "d341c23" :git "missing"}
           (tn/installed pins doctor home))
        "a library the pack loads is its pin and whether bb fetched it, not the binary's probe")
    (fs/create-dirs (fs/path home ".m2" "repository" "dev" "weavejester" "cljfmt" "0.16.6"))
    (is (= "0.16.6, fetched" (:cljfmt (tn/installed pins doctor home))))
    (testing "a git dep the Clojure CLI fetches, by its sha; XTDB a Maven dep (the person: why is 0.8.0 not shown)"
      (let [ps {:deps-new {:version "0.8.0" :from :template :gitlib {:lib 'io.github.seancorfield/deps-new :sha "2f96530"}}
                :xtdb {:version "2.2.0" :from :xtdb-deps}}]
        (is (= {:deps-new "0.8.0, not fetched" :xtdb "2.2.0, not fetched"} (tn/installed ps {} home)))
        (fs/create-dirs (fs/path home ".gitlibs" "libs" "io.github.seancorfield" "deps-new" "2f96530c963dbe920f2e5945612b96410f6c6e12"))
        (fs/create-dirs (fs/path home ".m2" "repository" "com" "xtdb" "xtdb-api" "2.2.0"))
        (is (= {:deps-new "0.8.0, fetched" :xtdb "2.2.0, fetched"} (tn/installed ps {} home)))))))
