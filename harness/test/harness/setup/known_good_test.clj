(ns harness.setup.known-good-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.toolchain-image :as toolchain-image]
   [harness.setup.doctor :as doctor]
   [harness.setup.known-good :as known-good]))

(deftest the-set-is-read-from-the-macs-health-record
  ;; plan 7.0, item 8.3f: one file for one fact - resources/known-good.edn was a copy of this record
  (let [harness (str (fs/create-temp-dir))
        records (fs/path harness "health" "records")]
    (testing "no record: no set"
      (is (nil? (known-good/read-set harness))))
    (fs/create-dirs records)
    (spit (str (fs/path records "linux-arm64.edn"))
          (pr-str {:health/as-of "2026-10-31" :platform {:key "linux-arm64" :label "Linux arm64"} :tools {:bb "1"}}))
    (testing "another platform's record is not the set"
      (is (nil? (known-good/read-set harness))))
    (spit (str (fs/path records "macos-arm64.edn"))
          (str ";; Written by `bb health --record`\n"
               (pr-str {:health/as-of "2026-10-30" :platform {:key "macos-arm64" :label "macOS 27.0 arm64"}
                        :kit {:sha "x"} :tools {:bb "1.13.229" :java "25.0.5"} :xtdb {:version "2.2.0"}
                        :checks [] :ok? true})))
    (is (= {:as-of "2026-10-30" :platform "macOS 27.0 arm64" :tools {:bb "1.13.229" :java "25.0.5"}
            :xtdb {:version "2.2.0"}}
           (known-good/read-set harness))
        "the date, the label, the tools and :xtdb - no :project when the record has none")
    (is (= (str records "/macos-arm64.edn") (known-good/record-path harness)))))

(deftest the-doctor-and-the-image-name-read-the-committed-record
  (let [good (known-good/read-set ".")]
    (is (= good (doctor/known-good)) "the doctor's column and header: the same set, found from the classpath")
    (is (contains? good :project) "the Mac's record is the only one with :project")
    (is (re-find (re-pattern (str ":" (:as-of good) "-[0-9a-f]{7}$")) (toolchain-image/image-tag ".."))
        "the toolchain image named by the record's date")))
