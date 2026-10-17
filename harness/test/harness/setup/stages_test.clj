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
