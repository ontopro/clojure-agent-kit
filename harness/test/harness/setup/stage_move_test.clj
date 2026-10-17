(ns harness.setup.stage-move-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.stage-move :as sm]))

(deftest a-flat-entry-is-its-stages-by-name-and-a-template-is-none
  (is (= [0 1 1 nil nil nil nil]
         (map sm/flat-entry-id ["stage-0-plan.md" "stage-1-security-review" "stage-1-interactions.edn"
                                "stage-N-template.md" "stage-0-spike-template.md" "stage-0" "README.md"]))))

(deftest a-moved-documents-paths-follow-it-one-folder-down
  (let [r #(sm/rewrite-moved % 1)]
    (testing "one more ../ where a run begins, however long the run"
      (is (= "`../../00-overview.md` §4a" (r "`../00-overview.md` §4a")))
      (is (= "![p](../../../../extracted/pictures/x.png)" (r "![p](../../../extracted/pictures/x.png)")))
      (is (= "`../../../runs/s0-t01.edn`" (r "`../../runs/s0-t01.edn`"))))
    (testing "a reading at its old place: its own folder's reviews/, another stage's, the project's unmoved"
      (is (= "`reviews/blueprint-review.edn`" (r "`../../reviews/stage-1/blueprint-review.edn`")))
      (is (= "`reviews/plan-review.edn`" (r "`reviews/stage-1/plan-review.edn`")) "written from the build's root")
      (is (= "`../stage-0/reviews/plan-review.edn`" (r "`../../reviews/stage-0/plan-review.edn`")))
      (is (= "`../../../reviews/plan-review.edn`" (r "`../../reviews/plan-review.edn`")) "the project plan review stays"))
    (testing "another stage's file named bare goes through its folder; its own and a template's stay"
      (is (= "(`../stage-0/stage-0-blueprint.md` §1)" (r "(`stage-0-blueprint.md` §1)")))
      (is (= "`../stage-0/stage-0-security-review/`" (r "`stage-0-security-review/`")))
      (is (= "`stage-1-plan.md`, `stage-1-gates.edn`" (r "`stage-1-plan.md`, `stage-1-gates.edn`")))
      (is (= "`stage-0-spike-template.md`" (r "`stage-0-spike-template.md`"))))
    (testing "a path that led nowhere from the old place is a quotation, left as written"
      (let [old #{"../00-overview.md" "../../runs/s0.edn"}
            r* #(sm/rewrite-moved % 1 old)]
        (is (= "`../../00-overview.md` §4a" (r* "`../00-overview.md` §4a")))
        (is (= "`../../../runs/s0.edn`." (r* "`../../runs/s0.edn`.")) "its trailing full stop is not the path's")
        (is (= "links like `../i-am-a-patient/my-surgery.md#4-axillary` in the content"
               (r* "links like `../i-am-a-patient/my-surgery.md#4-axillary` in the content")))))
    (testing "a fenced block is left alone: a packet's paths are the application's, for the Coder"
      (let [fenced "```clojure\n{:files/target \"../src/x.clj\" :see \"stage-0-plan.md\"}\n```\n"]
        (is (= fenced (r fenced)))
        (is (= (str "`../../a.md`\n" fenced "`../../b.md`\n") (r (str "`../a.md`\n" fenced "`../b.md`\n"))))))))

(deftest the-builds-other-documents-name-the-folders
  (is (= "`stages/stage-0/stage-0-plan.md` §7" (sm/rewrite-doc "`stages/stage-0-plan.md` §7")))
  (is (= "`docs/stages/stage-1/stage-1-blueprint.md`" (sm/rewrite-doc "`docs/stages/stage-1-blueprint.md`")))
  (is (= "(`stages/stage-0/reviews/`, `stages/stage-1/reviews/plan-review.edn`)"
         (sm/rewrite-doc "(`reviews/stage-0/`, `../reviews/stage-1/plan-review.edn`)")))
  (is (= "`stages/stage-0-spike-template.md`, `reviews/plan-review.edn`"
         (sm/rewrite-doc "`stages/stage-0-spike-template.md`, `reviews/plan-review.edn`"))
      "a template and the project plan review are where they were"))

(def ^:private git-env
  {"GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@t" "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@t"})

(defn- git [dir & args]
  (str/trim (:out (apply p/shell {:dir (str dir) :out :string :err :string :extra-env git-env} "git" args))))

(defn- flat-build!
  "A build in the layout before the folders: two stages flat in docs/stages/ beside a template,
  their readings under reviews/stage-N/, the project plan review, a security review folder with
  an untracked folder inside, an untracked note, documents citing all of it. Committed."
  []
  (let [b (fs/path (fs/real-path (fs/create-temp-dir {:prefix "stage-move-"})) "xyx-build")
        put (fn [rel s] (let [f (fs/path b rel)] (fs/create-dirs (fs/parent f)) (spit (str f) s)))]
    (put "docs/00-overview.md" "See `stages/stage-0-plan.md` §7 and `reviews/stage-1/plan-review.edn`.\n")
    (put "docs/stages/stage-N-template.md" "# template `../00-overview.md`\n")
    (put "docs/stages/stage-0-plan.md" "# Stage 0\n\n`../00-overview.md`\n")
    (put "docs/stages/stage-0-gates.edn" "{:stage/id \"0\"}\n")
    (put "docs/stages/stage-0-security-review/r.edn" "{:quote \"../../x\"}\n")
    (put "docs/stages/stage-1-plan.md" "# Stage 1\n\nafter `stage-0-plan.md` §7\n")
    (put "docs/stages/stage-1-blueprint.md"
         "# Blueprint\n\nread `../../reviews/stage-1/blueprint-review.edn`\n\n```clojure\n{:task/id \"t1\" :p \"../src/a.clj\"}\n```\n")
    (put "reviews/plan-review.edn" "{:count 1}\n")
    (put "reviews/stage-0/plan-review.edn" "{:count 2}\n")
    (put "reviews/stage-1/blueprint-review.edn" "{:count 3}\n")
    (git b "init" "-q" "-b" "main")
    (git b "add" "-A")
    (git b "commit" "-q" "-m" "the build, flat")
    (put "docs/stages/stage-0-security-review/r-tests/test.clj" "(ns t)\n")
    (put "docs/stages/stage-1-notes.md" "an untracked note `../00-overview.md`\n")
    (str b)))

(deftest the-move-on-a-build-made-before-the-folders
  (let [b (flat-build!)
        exists? #(fs/exists? (fs/path b %))
        text #(slurp (str (fs/path b %)))]
    (testing "an uncommitted edit among what it would touch stops it, nothing moved"
      (spit (str (fs/path b "docs" "00-overview.md")) "edited\n" :append true)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"docs/00-overview.md in .* has uncommitted edits"
                            (sm/move! b "KIT 0.6.7: a folder per stage")))
      (is (exists? "docs/stages/stage-0-plan.md"))
      (git b "checkout" "--" "docs/00-overview.md"))
    (let [{:keys [moved rewritten]} (sm/move! b "KIT 0.6.7: a folder per stage")]
      (is (= 8 moved) "six flat entries, the untracked note among them, and two stages' readings")
      (testing "the layout"
        (doseq [rel ["docs/stages/stage-0/stage-0-plan.md" "docs/stages/stage-0/stage-0-gates.edn"
                     "docs/stages/stage-0/stage-0-security-review/r.edn"
                     "docs/stages/stage-0/stage-0-security-review/r-tests/test.clj"
                     "docs/stages/stage-0/reviews/plan-review.edn"
                     "docs/stages/stage-1/stage-1-plan.md" "docs/stages/stage-1/stage-1-blueprint.md"
                     "docs/stages/stage-1/stage-1-notes.md" "docs/stages/stage-1/reviews/blueprint-review.edn"
                     "docs/stages/stage-N-template.md" "reviews/plan-review.edn"]]
          (is (exists? rel) rel))
        (is (not (exists? "reviews/stage-0")))
        (is (not (exists? "docs/stages/stage-1-plan.md"))))
      (testing "the paths that named them"
        (is (= "See `stages/stage-0/stage-0-plan.md` §7 and `stages/stage-1/reviews/plan-review.edn`.\n" (text "docs/00-overview.md")))
        (is (str/includes? (text "docs/stages/stage-0/stage-0-plan.md") "`../../00-overview.md`"))
        (is (str/includes? (text "docs/stages/stage-1/stage-1-plan.md") "after `../stage-0/stage-0-plan.md` §7"))
        (is (str/includes? (text "docs/stages/stage-1/stage-1-blueprint.md") "read `reviews/blueprint-review.edn`"))
        (is (str/includes? (text "docs/stages/stage-1/stage-1-blueprint.md") ":p \"../src/a.clj\"") "the packet untouched")
        (is (= "# template `../00-overview.md`\n" (text "docs/stages/stage-N-template.md")) "the template is the upgrade's build-docs step's")
        (is (= "{:quote \"../../x\"}\n" (text "docs/stages/stage-0/stage-0-security-review/r.edn")) "a security review's files quote the application")
        (is (some #{"docs/stages/stage-1/stage-1-notes.md"} rewritten) "rewritten on disk, though untracked"))
      (testing "one commit, renames git sees, and what was untracked still untracked"
        (is (= "KIT 0.6.7: a folder per stage" (git b "log" "-1" "--format=%s")))
        (let [status (git b "show" "--name-status" "-M" "--format=" "HEAD")]
          (is (str/includes? status "R100\tdocs/stages/stage-0-gates.edn\tdocs/stages/stage-0/stage-0-gates.edn"))
          (is (re-find #"R\d+\tdocs/stages/stage-1-blueprint.md\tdocs/stages/stage-1/stage-1-blueprint.md" status) "a rename with a small edit")
          (is (str/includes? status "R100\treviews/stage-1/blueprint-review.edn\tdocs/stages/stage-1/reviews/blueprint-review.edn"))
          (is (not (str/includes? status "notes")) "the untracked note is not committed"))
        (is (= #{"?? docs/stages/stage-0/stage-0-security-review/r-tests/" "?? docs/stages/stage-1/stage-1-notes.md"}
               (set (str/split-lines (git b "status" "--porcelain")))))))
    (testing "again: nothing flat, nothing to move"
      (is (= 0 (:moved (sm/move! b "again"))))
      (is (= "KIT 0.6.7: a folder per stage" (git b "log" "-1" "--format=%s"))))))
