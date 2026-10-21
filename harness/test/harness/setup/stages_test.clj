(ns harness.setup.stages-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.stages :as stages]))

(deftest a-stage-is-its-folder
  (is (= 2 (stages/folder-id "stage-2")))
  (is (= 12 (stages/folder-id "stage-12")))
  (testing "a template, a file, a folder of another name are no stage"
    (is (nil? (stages/folder-id "stage-N-template.md")))
    (is (nil? (stages/folder-id "stage-1-plan.md")))
    (is (nil? (stages/folder-id "stage-x"))))
  (is (= "docs/stages/stage-2/stage-2-gates.edn" (stages/rel 2 "-gates.edn"))))

(deftest the-folders-the-plan-and-where-a-document-belongs
  (let [build (str (fs/real-path (fs/create-temp-dir {:prefix "stages-"})))
        dir (stages/stages-dir build)]
    (fs/create-dirs (fs/path dir "stage-0" "reviews"))
    (fs/create-dirs (fs/path dir "stage-10"))
    (fs/create-dirs (fs/path dir "stage-2"))
    (fs/create-dirs (fs/path dir "notes"))
    (spit (str (fs/path dir "stage-N-template.md")) "# template")
    (spit (str (fs/path dir "stage-0" "stage-0-spike.md")) "# Stage 0")
    (spit (str (fs/path dir "stage-0" "stage-0-blueprint.md")) "# Blueprint")
    (spit (str (fs/path dir "stage-0" "stage-0-gates.edn")) "{}")
    (is (= [0 2 10] (keys (stages/folders dir))) "by number, not by name; templates and other folders are none")
    (is (= "stage-0-spike.md" (stages/plan-file (fs/path dir "stage-0")))
        "the plan is the folder's .md that is not the blueprint")
    (is (nil? (stages/plan-file (fs/path dir "stage-2"))) "a stage pulled, its plan not yet written")
    (is (= {:build (fs/path build) :id 0 :dir (fs/path dir "stage-0")}
           (stages/of-document (str (fs/path dir "stage-0" "stage-0-blueprint.md")))))
    (is (nil? (stages/of-document (str (fs/path dir "stage-N-template.md")))) "not in a stage folder")
    (is (nil? (stages/of-document (str (fs/path build "stage-0" "stage-0-blueprint.md")))) "a stage folder outside docs/stages/")
    (is (= (str (fs/path dir "stage-0" "reviews")) (str (stages/reviews-dir build 0))))
    (is (= (str (fs/path build "reviews" "plan-review.edn")) (str (stages/project-plan-review build)))
        "the whole plan's reading is not a stage's")))

(deftest a-run-belongs-to-its-stage-not-to-every-stage-with-its-task-id
  ;; the first KIT project's stage 2 reused stage 0's and 1's task ids: read by id alone, its
  ;; packets showed merged from the older runs and the stage report counted them in both
  (let [infos [{:id 0 :tasks #{"t-02-pages" "t-01-content"} :approved "2026-10-13"}
               {:id 1 :tasks #{"t-05-page-view"} :approved "2026-10-16"}
               {:id 2 :tasks #{"t-02-pages" "t-05-page-view"} :approved "2026-10-17"}
               {:id 3 :tasks #{"t-09"} :approved nil}]]
    (is (= 0 (stages/record-stage {:task/id "t-02-pages" :task/stage 0 :run/started-at "2026-10-20T10:00"} infos))
        "a record that says its stage is that stage's, whatever its date")
    (is (= 0 (stages/record-stage {:task/id "t-02-pages" :run/started-at "2026-10-13T16:00"} infos)) "by date: stage 0's day")
    (is (= 2 (stages/record-stage {:task/id "t-02-pages" :run/started-at "2026-10-17T16:00"} infos)) "by date: after stage 2's approval")
    (is (= 1 (stages/record-stage {:task/id "t-05-page-view" :run/started-at "2026-10-16T20:00"} infos)))
    (is (= 2 (stages/record-stage {:task/id "t-05-page-view" :run/started-at "2026-10-17"} infos))
        "the same day as the later stage's approval: the later - the gates carry dates, not times")
    (is (= 0 (stages/record-stage {:task/id "t-01-content"} infos)) "one stage names it: that one, date or none")
    (is (= 3 (stages/record-stage {:task/id "t-09" :run/started-at "2026-10-01"} infos)) "one stage names it, approved or not")
    (is (nil? (stages/record-stage {:task/id "t-02-pages" :run/started-at "2026-10-01"} infos))
        "two stages name it and neither was approved by then: counted in neither, not both")
    (is (nil? (stages/record-stage {:task/id "t-99" :run/started-at "2026-10-17"} infos)) "no blueprint names it")))

(deftest a-runs-day-is-this-machines
  (is (= "2026-10-16" (stages/day "2026-10-16T23:30")))
  (let [i #inst "2026-10-17T02:30:00.000-00:00"
        local (str (.toLocalDate (.atZone (.toInstant i) (java.time.ZoneId/systemDefault))))]
    (is (= local (stages/day i)) "an evening run west of UTC is the day the person wrote in the gates record")))

(deftest a-gate-is-passed-at-a-time-and-a-record-from-before-on-a-day
  (let [chicago "America/Chicago"]
    (testing "an :at with its offset, a Z, an #inst"
      (is (= (java.time.Instant/parse "2026-10-21T14:30:00Z") (stages/gate-instant {:at "2026-10-21T09:30-05:00"} nil)))
      (is (= (java.time.Instant/parse "2026-10-21T14:30:00Z") (stages/gate-instant {:at "2026-10-21T14:30:00Z"} nil)))
      (is (= (java.time.Instant/parse "2026-10-21T14:30:00Z") (stages/gate-instant {:at #inst "2026-10-21T14:30:00Z"} nil))))
    (testing "a :date is that day's midnight in the project's zone, else this machine's"
      (is (= (java.time.Instant/parse "2026-10-21T05:00:00Z") (stages/gate-instant {:date "2026-10-21"} chicago)))
      (is (= (.toInstant (.atStartOfDay (java.time.LocalDate/parse "2026-10-21") (java.time.ZoneId/systemDefault)))
             (stages/gate-instant {:date "2026-10-21"} nil))))
    (testing "an :at that cannot be read is nil and said, never thrown: a bare date, no offset, a typo"
      (doseq [a ["2026-10-21" "2026-10-21T09:30" "2026-1O-21T09:30Z" 20261021]]
        (is (nil? (stages/gate-instant {:at a} nil)) (pr-str a))
        (is (stages/unreadable-at? {:at a}) (pr-str a)))
      (is (not (stages/unreadable-at? {:date "2026-10-21"})) "a :date is the old form, not an unreadable :at")
      (is (not (stages/unreadable-at? nil))))
    (testing "the day shown: an :at's in the offset it was written with; a :date as written"
      (is (= "2026-10-21" (stages/gate-day {:at "2026-10-21T23:30-05:00"} nil)) "04:30 the next day in UTC")
      (is (= "2026-10-21" (stages/gate-day {:date "2026-10-21"} nil)))
      (is (nil? (stages/gate-day nil nil))))
    (testing "now, to the minute, in the zone - and read back"
      (let [a (stages/now-at chicago)]
        (is (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d-0[56]:00" a) a)
        (is (stages/parse-at a))))))

(deftest a-run-on-an-approval-day-is-placed-by-the-instant-when-both-carry-one
  (let [at #(java.time.Instant/parse %)
        infos [{:id 4 :tasks #{"t-01"} :approved "2026-10-19"}
               {:id 5 :tasks #{"t-01"} :approved "2026-10-21" :approved-at (at "2026-10-21T15:00:00Z")}]]
    (is (= 4 (stages/record-stage {:task/id "t-01" :run/started-at #inst "2026-10-21T14:00:00Z"} infos))
        "before stage 5's approval on its day: stage 4's - the day alone had said 5")
    (is (= 5 (stages/record-stage {:task/id "t-01" :run/started-at #inst "2026-10-21T16:00:00Z"} infos)))
    (testing "a stage with only a :date keeps the day: a mixed build places as it did"
      (is (= 4 (stages/record-stage {:task/id "t-01" :run/started-at "2026-10-19T10:00"} infos)))
      (is (= 5 (stages/record-stage {:task/id "t-01" :run/started-at "2026-10-21"} (update infos 1 dissoc :approved-at)))))))

(deftest a-stages-cap-is-its-latest-approval-and-one-pass-is-open-at-a-time
  (let [first-pass {:stage/approved {:at "2026-10-15T10:00Z" :cap "$20" :by "Ada"}}]
    (is (= "$20" (stages/effective-cap first-pass)) "no amendment: the approval's")
    (is (= "$30" (stages/effective-cap (assoc first-pass :stage/amendments [{:q "Q-1" :approved {:cap "$30"}}]))))
    (is (= "$20" (stages/effective-cap (assoc first-pass :stage/amendments [{:q "Q-1" :opened "x"}]))) "opened, not approved")
    (is (= "$45" (stages/effective-cap (assoc first-pass :stage/amendments [{:q "Q-1" :approved {:cap "$30"}} {:q "Q-2" :approved {:cap "$45"}}])))
        "the latest, never a sum")
    (is (= "$30" (stages/effective-cap (assoc first-pass :stage/amendments [{:q "Q-1" :approved {:cap "$30"}} {:q "Q-2" :approved {:by "Ada"}}])))
        "a re-approval without a :cap leaves the one before")
    (is (nil? (stages/open-amendment first-pass)))
    (is (= "Q-2" (:q (stages/open-amendment {:stage/amendments [{:q "Q-1" :signed {:by "Ada"}} {:q "Q-2"}]}))))
    (is (nil? (stages/open-amendment {:stage/amendments [{:q "Q-1" :signed {:by "Ada"}}]})) "signed: closed")))

(deftest a-readings-times-and-a-reviews-file-stamp-read-as-instants
  (let [d (fs/create-temp-dir) f (fs/path d "plan-review.edn")]
    (spit (str f) (pr-str {:at "2026-10-21T15:00:00Z" :reviews [{:at "2026-10-15T09:00:00Z"} {:at "2026-10-21T15:00:00Z"}]}))
    (is (= (mapv #(java.time.Instant/parse %) ["2026-10-21T15:00:00Z" "2026-10-15T09:00:00Z"]) (stages/reading-times f)))
    (is (= [] (stages/reading-times (fs/path d "none.edn")))))
  (is (= (.toInstant (.atZone (java.time.LocalDateTime/parse "2026-10-19T17:33:48") (java.time.ZoneId/systemDefault)))
         (stages/stamp-instant "gpt-6-astra-tracer-20261019-173348.edn")))
  (is (nil? (stages/stamp-instant "security-panel.edn")))
  (is (= "2026-10-21 09:30" (stages/gate-time {:at "2026-10-21T09:30-05:00"} nil)))
  (is (= "2026-10-21" (stages/gate-time {:date "2026-10-21"} nil))))
