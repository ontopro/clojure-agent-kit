(ns harness.gates.kit-status-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.kit-status :as ks]))

(def notes-before
  (str "# Notes\n\n| # | Finding | From | Status | What |\n|---|---|---|---|---|\n"
       "| 1 | **Old and open.** text | x | **open** | y |\n"
       "| 2 | **Old and fixed.** text | x | **fixed** `abc` | y |\n"))

(def notes-now
  (str notes-before
       "| 3 | **New and open.** a \\| pipe in it | x | **open** 2026-10-09 | y |\n"
       "| 4 | **New and watched.** text | x | **changed, watched** | y |\n"
       "| 5 | **New and fixed.** text | x | **fixed** `def` | y |\n"))

(deftest the-register-is-read-by-row
  (is (= [{:row 1 :title "Old and open." :status "**open**"}
          {:row 2 :title "Old and fixed." :status "**fixed** `abc`"}]
         (ks/register-rows notes-before)))
  (testing "an escaped pipe stays inside its cell"
    (is (= "**open** 2026-10-09" (:status (nth (ks/register-rows notes-now) 2))))))

(deftest rows-opened-since-still-open-or-watched
  (is (= [3 4] (map :row (ks/open-rows-since notes-before notes-now))))
  (testing "no earlier register: every open row"
    (is (= [1 3 4] (map :row (ks/open-rows-since nil notes-now))))))

(deftest a-local-readme-names-its-entries
  (let [readme "plans/\n  kit-plan.md  the plan\ngates/  logs\nSESSION_STATE.md the log\n"]
    (is (= ["bake-offs" "gate"]
           (ks/unnamed-entries readme ["README.md" ".DS_Store" "plans" "gates" "SESSION_STATE.md" "bake-offs" "gate"])))))

(deftest flaky-since-the-last-tag
  (let [runs [{:started "2026-10-01T00:00:00Z" :tree "T" :exit 1 :step "test" :failed-tests ["a/old"]}
              {:started "2026-10-01T01:00:00Z" :tree "T" :exit 0}
              {:started "2026-10-09T00:00:00Z" :tree "U" :exit 1 :step "test" :failed-tests ["a/new"]}
              {:started "2026-10-09T01:00:00Z" :tree "U" :exit 0}]]
    (is (= ["a/old" "a/new"] (map :test (ks/flaky-since runs nil))))
    (is (= ["a/new"] (map :test (ks/flaky-since runs "2026-10-05T00:00:00Z"))))))

(deftest every-local-folder-is-found
  (let [dir (fs/create-temp-dir)]
    (fs/create-dirs (fs/path dir ".local" "inner" ".local"))
    (fs/create-dirs (fs/path dir "harness" ".local"))
    (fs/create-dirs (fs/path dir ".git" ".local"))
    (is (= #{".local" "harness/.local"}
           (set (map #(str (fs/relativize dir %)) (ks/local-dirs dir)))))))

(deftest the-plan-is-named-by-the-branch
  (is (= "/r/.local/plans/kit-plan-v6.2.md" (str (ks/plan-file "/r" "plan-v6.2"))))
  (is (nil? (ks/plan-file "/r" "main"))))
