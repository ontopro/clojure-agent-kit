(ns harness.gates.toolchain-newer-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [harness.gates.toolchain-newer :as tn]))

(deftest versions-compare-as-numbers
  (is (pos? (tn/compare-versions "1.13.226" "1.13.225")))
  (is (pos? (tn/compare-versions "0.16.10" "0.16.9")) "not as strings")
  (is (zero? (tn/compare-versions "kit-v1.3" "kit-v1.3.0")) "a missing part is 0")
  (is (neg? (tn/compare-versions "2026.07.24" "2026.08.04"))))

(deftest the-newest-release-skips-what-is-not-one
  (let [git-pattern (:tag (first (filter #(= :git (:tool %)) tn/sources)))
        java-pattern (:tag (first (filter #(= :java (:tool %)) tn/sources)))]
    (is (= "2.56.0" (tn/newest git-pattern ["v2.55.1" "v2.56.0" "v2.56.0-rc2" "v2.57.0-rc0"]))
        "a release candidate is never newest")
    (is (= "21.0.12.1" (tn/newest java-pattern ["jdk-21.0.12+8" "jdk-21.0.12.1+1" "jdk-2023-06-08-16-57-beta"]))
        "the build number is not the version; a beta is not a release")
    (is (nil? (tn/newest git-pattern ["latest"])))))

(def files
  {:known-good {:tools {:bb "1.13.225" :clj-kondo "2026.08.04" :java "21.0.12.1" :clj-nrepl-eval "d341c23"}}
   :dockerfile "ARG BB_VERSION=1.13.225\nARG MCP_LIGHT_TAG=v0.2.2\n  ARG INDENTED=1\nARG GECKODRIVER_VERSION=0.37.1\n"
   :template-pins {:default :t :templates {:t {:git/tag "kit-v1.3" :deps-new {:git/tag "v0.8.0"}}}}})

(deftest the-pins-are-read-from-the-three-files
  (is (= {:bb "1.13.225" :clj-kondo "2026.08.04" :java "21.0.12.1" :clojure-mcp-light "0.2.2"
          :geckodriver "0.37.1" :template "kit-v1.3" :deps-new "0.8.0"}
         (tn/pins files))
      "a pin a file lacks is left out; known-good's commit for clojure-mcp-light is not a version"))

(deftest a-row-says-newer-the-newest-or-unknown
  (let [rows (tn/rows (tn/pins files) {:bb "1.13.226" :clj-kondo "2026.08.04" :java "21.0.12.1"
                                       :template "kit-v1.4" :geckodriver nil})
        by (into {} (map (juxt :tool :status)) rows)]
    (is (= {:bb :newer :clj-kondo :ok :java :ok :clojure-mcp-light :unknown :geckodriver :unknown
            :template :newer :deps-new :unknown}
           by))
    (testing "the lines a reader is shown: what moved and what did not answer, never what is current"
      (is (= ["bb 1.13.225 -> 1.13.226 released"
              "clojure-mcp-light 0.2.2 - newest unknown (its repository did not answer)"
              "geckodriver 0.37.1 - newest unknown (its repository did not answer)"
              "template kit-v1.3 -> kit-v1.4 released"
              "deps-new 0.8.0 - newest unknown (its repository did not answer)"]
             (tn/newer-lines rows))))))
