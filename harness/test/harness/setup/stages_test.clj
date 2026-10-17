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
