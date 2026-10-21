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

(deftest a-commit-is-read-when-its-task-status-line-marks-it
  (let [plan (str "# Plan\n\n## Task Status\n\n**Next:** Item 3\n\n"
                  "- [x] Item 1: x - `c0b52e2` (read: 2 nits → `d8d62bf`, 1 declined - Sessions), `d8d62bf` (read: holds) (row 167)\n"
                  "- [x] Item 2: y - `0ec404d` 3b-3c (read: 1 low → `bb24a9b`), `bb24a9b` (row 169)\n"
                  "- [ ] Item 3: z\n\n## Sessions\n\n- `aaaaaaa` (read: holds) is the log, not the status\n")
        commits [["c0b52e2aaaa" "one"] ["d8d62bfbbbb" "a fix, marked after its mention in another's mark"]
                 ["0ec404dcccc" "two"] ["bb24a9bdddd" "a fix named in a mark, with none of its own"]
                 ["aaaaaaaeeee" "marked only in the Sessions log"] ["1234567ffff" "on no line"]]]
    (is (= ["bb24a9bdddd" "aaaaaaaeeee" "1234567ffff"] (map first (ks/unread plan commits))))
    (is (= commits (ks/unread "no task status" commits)) "a plan with no Task Status marks nothing")))
