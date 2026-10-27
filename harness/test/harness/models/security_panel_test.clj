(ns harness.models.security-panel-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.loop.security-routing :as routing]
   [harness.models.security-panel :as panel]
   [harness.models.security-review :as review]
   [harness.money.stage-report :as sr]
   [harness.setup.stages :as stages]))

(defn- finding [title where & [kind]]
  {:title title :kind (or kind :reproduced) :test "test/app/x_test.clj" :where where :why "w"})

(defn- rec [member & findings] {:member member :model (str "x/" member) :status :done :findings (vec findings)})

(deftest the-functions-a-where-names
  (is (= ["login" "logout!"] (panel/functions-in "src/app/web.clj: logout!, login; src/app/db.clj")))
  (is (= [] (panel/functions-in "src/app/web.clj")))
  (is (= [] (panel/functions-in "the session handling"))))

(deftest two-members-findings-of-one-place-are-one-row
  (let [{:keys [rows hypotheses]}
        (panel/combine [{:file "a.edn" :record (rec "astra" (finding "logout keeps the session" "src/app/web.clj: logout!")
                                                    (finding "a guess" "src/app/web.clj" :hypothesis))}
                        {:file "b.edn" :record (rec "argon" (finding "session outlives logout" "src/app/web.clj: logout!")
                                                    (finding "export reads any file" "src/app/export.clj: download"))}
                        {:file "c.edn" :record (rec "glm")}])]
    (is (= 2 (count rows)))
    (testing "one row, both members, both titles, the first member's finding and record"
      (let [r (first rows)]
        (is (= [{:member "astra" :record "a.edn"} {:member "argon" :record "b.edn"}] (:by r)))
        (is (= [{:member "glm" :record "c.edn"}] (:not-by r)) "ran, answered and did not")
        (is (= [["astra" "logout keeps the session"] ["argon" "session outlives logout"]] (:titles r)))
        (is (= "a.edn" (:record r)))))
    (testing "another place is another row, though the same member found the first"
      (is (= [{:member "argon" :record "b.edn"}] (:by (second rows))))
      (is (= [{:member "astra" :record "a.edn"} {:member "glm" :record "c.edn"}] (:not-by (second rows)))))
    (is (= [{:member "astra" :record "a.edn" :finding (finding "a guess" "src/app/web.clj" :hypothesis)}] hypotheses)
        "a claim no test showed is apart, never merged")))

(deftest a-member-that-said-nothing-counts-for-nothing
  (let [{:keys [rows]} (panel/combine [{:file "a.edn" :record (rec "astra" (finding "f" "src/app/web.clj: h"))}
                                       {:file "b.edn" :record (assoc (rec "fable") :refused? true)}])]
    (is (= [] (:not-by (first rows))) "a refusal is not a member that looked and found nothing")))

(deftest findings-naming-no-file-are-never-merged
  (let [{:keys [rows]} (panel/combine [{:file "a.edn" :record (rec "astra" (finding "f1" "the session") (finding "f2" "cookies"))}])]
    (is (= 2 (count rows)))))

(deftest the-rotation-is-read-from-the-folders
  (let [el ["astra" "glm" "fable"]]
    (is (= "astra" (panel/next-member el [])) "no stage read yet: the first")
    (is (= "glm" (panel/next-member el [[1 #{"astra"}] [2 #{}]])) "a stage with no review moves nothing")
    (is (= "astra" (panel/next-member el [[1 #{"astra"}] [2 #{"fable"}]])) "it wraps")
    (is (= "fable" (panel/next-member el [[3 #{"astra" "glm"}]])) "after the later of two")
    (is (= "astra" (panel/next-member el [[1 #{"argon"}]])) "a member not eligible is not counted")
    (is (nil? (panel/next-member [] [])))))

(deftest choosing-who-reads
  (let [split {:eligible [{:name "astra"} {:name "glm"}] :left-out [{:name "argon" :reason "the Coder's family (google)"}]}]
    (is (= ["glm"] (mapv :name (:members (panel/choose split {} [[1 #{"astra"}]])))))
    (is (str/includes? (:why (panel/choose split {} [[1 #{"astra"}]])) "astra read stage 1"))
    (is (= ["astra" "glm"] (mapv :name (:members (panel/choose split {:all true} [])))))
    (is (= ["glm"] (mapv :name (:members (panel/choose split {:members ["glm"]} [])))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"argon is left out of the panel: the Coder's family"
                          (panel/choose split {:members ["argon"]} [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not on the panel" (panel/choose split {:members ["x"]} [])))))

(defn- write-rec! [dir id r tests]
  (review/write-record! dir id (assoc r :tests tests)))

(deftest the-table-and-the-routing-over-it
  (let [dir (str (fs/create-temp-dir))
        clone (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path clone "src/app"))
    (spit (str (fs/path clone "src/app/web.clj")) "(ns app.web)")
    (write-rec! dir "astra-tracer-1" (assoc (rec "astra" (finding "f" "src/app/web.clj: h"))
                                            :cost 1.0 :steps [{:cost 1.0 :cost-source :list-price}])
                {"test/app/x_test.clj" "(ns x) ;; astra's"})
    (write-rec! dir "glm-tracer-1" (assoc (rec "glm" (finding "g" "src/app/web.clj: h")) :cost 0.5 :steps [{:cost 0.5}])
                {"test/app/x_test.clj" "(ns x) ;; glm's"})
    (spit (str (fs/path dir "security-routing.edn")) (pr-str {:routed []}))
    (let [t (panel/table! dir [{:name "astra"} {:name "glm"}] [{:name "argon" :reason "r"}] {})]
      (is (= ["astra" "glm"] (mapv :name (:members t))) "the routing file is no member's record")
      (is (= 1 (count (:rows t))))
      (is (= :list-price (:cost-source (first (:members t)))) "the record says how its cost was got")
      (is (= (mapv :record (:members t)) (:view/of t)) "a view, naming the member records it was made of (plan 7.0, item 11.5)")
      (let [md (slurp (str (fs/path dir "security-panel.md")))]
        (is (str/includes? md "~$1.00") "a computed cost is marked")
        (is (str/includes? md "Left out: argon, r"))))
    (testing "one triage call per row, its test from the member that carried it"
      (let [calls (atom [])
            r (routing/route! {:review-file (str (fs/path dir "security-panel.edn")) :clone clone :architecture ""
                               :out dir
                               :triage-fn (fn [trig] (swap! calls conj trig) {:route :architect :reason "a gap"})})]
        (is (= 1 (count @calls)))
        (is (= "(ns x) ;; astra's" (get-in (first @calls) [:payload :test-text])))
        (is (= ["astra" "glm"] (mapv :member (:by (first (:routed r))))) "each reading, its member and record")
        (let [run (last (sr/routing-records dir))
              table (read-string (slurp (str (fs/path dir "security-panel.edn"))))]
          (is (str/ends-with? (:review run) "security-panel.edn"))
          (is (= (:at table) (:review/at run)) "the table as it was when routed, by its :at (plan 7.0, item 11.5)")
          (is (= (:view/of table) (:review/of run)) "and the member records, which never change"))))))

(deftest one-members-two-findings-at-one-place-keep-both-titles
  (let [{:keys [rows]} (panel/combine [{:file "a.edn" :record (rec "astra" (finding "one" "src/app/web.clj: h")
                                                                   (finding "two" "src/app/web.clj: h"))}])]
    (is (= [["astra" "one"] ["astra" "two"]] (:titles (first rows))))))

(deftest the-same-files-and-no-function-are-two-rows
  ;; an insecure direct object reference and an SQL injection named at one file pair were one row,
  ;; the second's title in no table a person read
  (let [{:keys [rows]} (panel/combine [{:file "a.edn" :record (rec "argon" (finding "IDOR in note retrieval" "src/notes/web.clj, src/notes/notebook.clj"))}
                                       {:file "b.edn" :record (rec "glm" (finding "SQL in note search" "src/notes/notebook.clj and src/notes/web.clj"))}])]
    (is (= 2 (count rows)))))

(deftest the-table-shows-every-title-and-a-member-that-said-nothing
  (let [members [{:name "astra" :status :answered :record "a.edn"} {:name "argon" :status :answered :record "b.edn"}
                 {:name "fable" :status :refused :record "c.edn"}]
        md (panel/render {:members members
                          :rows [{:title "a" :by [{:member "astra" :record "a.edn"} {:member "argon" :record "b.edn"}] :not-by []
                                  :titles [["astra" "a"] ["argon" "b"]]
                                  :finding {:test "t"} :record "r.edn"}]})]
    (is (str/includes? md "a (astra) / b (argon)"))
    (is (str/includes? md "| ✓ | ✓ | refused |"))))

;; From the first KIT project's stage 6: Sol read twice, the second time after an amendment, and
;; every Sol row carried a mark in both Sol columns - the columns had been looked up by name
(deftest one-members-two-readings-keep-their-own-marks
  (let [dir (str (fs/create-temp-dir))
        build (str (fs/create-temp-dir))]
    (write-rec! dir "20261020-100000-astra-tracer" (rec "astra" (finding "a" "src/app/a.clj: f")) {})
    (write-rec! dir "20261020-110000-sol-tracer" (rec "sol" (finding "b" "src/app/b.clj: g") (finding "c" "src/app/c.clj: h")) {})
    (write-rec! dir "20261023-090000-sol-tracer" (rec "sol" (finding "d" "src/app/d.clj: i") (finding "e" "src/app/e.clj: j")
                                                      (finding "f" "src/app/f.clj: k")) {})
    (let [t (panel/table! dir [{:name "astra"} {:name "sol"}] [] {})
          md (slurp (str (fs/path dir "security-panel.md")))
          row (fn [title] (first (filter #(str/starts-with? % (str "| " title " |")) (str/split-lines md))))]
      (is (= ["astra" "sol" "sol"] (mapv :name (:members t))))
      (is (= [[{:member "sol" :record "20261020-110000-sol-tracer.edn"}]
              [{:member "sol" :record "20261023-090000-sol-tracer.edn"}]]
             (mapv :by (filter #(#{"b" "d"} (:title %)) (:rows t))))
          "a row names the reading that reproduced it, not the member")
      (is (str/includes? md "| Finding | astra | sol (1) | sol (2) | Test |"))
      (is (str/starts-with? (row "b") "| b | – | ✓ | – |") "the first reading's finding, not the second's")
      (is (str/starts-with? (row "d") "| d | – | – | ✓ |") "the second reading's, not the first's")
      (is (str/includes? md "| sol (1) | answered | 2 |"))
      (is (str/includes? md "| sol (2) | answered | 3 |")))
    (testing "the stage report counts the readings by record too: three, Sol's two apart"
      (fs/create-dirs (stages/stage-dir build 6))
      (fs/copy-tree dir (stages/document build 6 "-security-review"))
      (is (= 3 (nth (first (filter #(= "security review" (first %)) (sr/stage-readings build 6))) 2))))))

(deftest a-re-rendered-table-keeps-the-runs-facts
  (let [dir (str (fs/create-temp-dir))]
    (write-rec! dir "astra-tracer-1" (rec "astra" (finding "f" "src/app/web.clj: h")) {})
    (panel/table! dir [{:name "astra"}] [] {:stance :tracer :clone-tip "abc123"})
    (let [t (panel/table! dir [{:name "astra"}] [] {})]
      (is (= [:tracer "abc123"] [(:stance t) (:clone-tip t)])))))

(deftest members-without-names-is-said
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--members needs names" (panel/parse-args ["x" "--members"]))))

;; From the KIT's own security review (GPT-6 Astra and Gemini 4 Argon, 2026-10-21): the member's copy
;; followed a link in the project's repository, so a host file became a regular file of the copy
(deftest a-member-s-copy-keeps-a-link-a-link
  (let [outer (fs/create-temp-dir {:prefix "kit-panel-copy-link"})
        clone (fs/path outer "clone")
        seen (atom nil)]
    (try
      (fs/create-dirs (fs/path clone "src"))
      (spit (str (fs/path outer "host-secret")) "HOST")
      (fs/create-sym-link (fs/path clone "src" "linked.clj") (fs/path outer "host-secret"))
      (with-redefs [review/review!
                    (fn [_ _ copy _]
                      (reset! seen {:link? (fs/sym-link? (fs/path copy "src" "linked.clj"))})
                      {:status :done :findings [] :tests {} :steps [] :calls [] :ms 1})
                    review/write-record! (fn [& _] "rec.edn")]
        (try (panel/run-panel! {:kit "." :clone (str clone) :stance :tracer :parallel 1 :out (str (fs/path outer "out"))}
                               [{:name "m" :model "x"}])
             (catch Exception _ nil)))
      (is (true? (:link? @seen)) "the copy carries the link as a link, which the review's tools then refuse")
      (finally (fs/delete-tree outer)))))
