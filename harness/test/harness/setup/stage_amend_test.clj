(ns harness.setup.stage-amend-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.stage-amend :as amend]
   [harness.setup.stages :as stages]))

(def ^:private signed
  {:stage/id "5" :stage/approved {:at "2026-10-15T10:00-05:00" :cap "$20" :by "Ada"}
   :blueprint/signed {:at "2026-10-16T10:00-05:00" :by "Ada"} :stage/closed nil})

(def ^:private source "| ID | Asked | Answer |\n|---|---|---|\n| Q-26 | arrows into a journey page? | yes |\n")

(deftest an-amendment-is-refused-by-name
  (is (nil? (amend/refusal 5 signed "Q-26" source)) "a signed stage, open, its Q row there")
  (testing "the three the design names, and the two before them"
    (is (str/includes? (amend/refusal 5 (assoc signed :stage/closed {:at "2026-10-20T10:00-05:00"}) "Q-26" source)
                       "stage 5 is closed (2026-10-20) - a closed stage is not amended"))
    (is (= "stage 5 has an amendment open (Q-26, opened 2026-10-21, not signed) - fold Q-27 into it, or finish it first: one pass at a time"
           (amend/refusal 5 (assoc signed :stage/amendments [{:q "Q-26" :opened "2026-10-21T09:30-05:00"}]) "Q-27"
                          (str source "| Q-27 | more? | yes |\n"))))
    (is (= "Q-27 is not a row of docs/source.md §3 - the person's words go there first, as a Q row"
           (amend/refusal 5 signed "Q-27" source)))
    (is (str/includes? (amend/refusal 5 (dissoc signed :blueprint/signed) "Q-26" source) "is not signed off - amend its plan and blueprint in place"))
    (is (str/includes? (amend/refusal 5 signed "26" source) "Q-n (source.md §3)")))
  (is (nil? (amend/refusal 5 (assoc signed :stage/amendments [{:q "Q-25" :opened "x" :signed {:by "Ada"}}]) "Q-26" source))
      "a signed amendment is done: the next may open"))

(deftest an-amendment-is-written-into-the-record-with-every-other-byte-kept
  (let [text ";; the stage's gates\n{:stage/id \"5\"\n ;; approved\n :stage/approved {:at \"2026-10-15T10:00-05:00\" :cap \"$20\" :by \"Ada\"}\n :stage/closed nil}\n"
        e1 {:q "Q-26" :opened "2026-10-21T09:30-05:00"}
        once (amend/with-amendment text e1)]
    (is (str/starts-with? once ";; the stage's gates\n{:stage/id \"5\"\n ;; approved\n :stage/approved {:at \"2026-10-15T10:00-05:00\" :cap \"$20\" :by \"Ada\"}\n :stage/closed nil"))
    (is (= [e1] (:stage/amendments (edn/read-string once))))
    (testing "a second pass appends; a record whose key is nil takes the first"
      (let [e2 {:q "Q-27" :opened "2026-10-22T09:30-05:00"}]
        (is (= [e1 e2] (:stage/amendments (edn/read-string (amend/with-amendment once e2)))))
        (is (= [e1] (:stage/amendments (edn/read-string (amend/with-amendment "{:stage/id \"5\" :stage/amendments nil}" e1)))))
        (is (= [e1] (:stage/amendments (edn/read-string (amend/with-amendment "{:stage/id \"5\" :stage/amendments []}" e1)))))))))

(deftest stage-amend-opens-the-pass-of-the-stage-in-progress
  (let [ws (fs/create-temp-dir)
        build (fs/path ws "p-build")
        gates (stages/document build 5 "-gates.edn")]
    (fs/create-dirs (stages/stage-dir build 4))
    (fs/create-dirs (stages/stage-dir build 5))
    (spit (str (fs/path build "docs" "source.md")) source)
    (spit (str gates) (pr-str signed))
    (let [{:keys [ok]} (amend/amend! {:workspace/build (str build) :workspace/time-zone "America/Chicago"} "Q-26")
          [a] (:stage/amendments (edn/read-string (slurp (str gates))))]
      (is (str/starts-with? ok "stage 5 amended for Q-26, opened "))
      (is (= "Q-26" (:q a)))
      (is (re-matches #".*-0[56]:00" (:opened a)) "in the project's zone"))
    (is (str/includes? (:refused (amend/amend! {:workspace/build (str build)} "Q-26")) "has an amendment open (Q-26"))))
