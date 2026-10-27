(ns harness.setup.roadmap-page-test
  "`bb roadmap-page`: the drawing parses and has a lane a phase and a box a stage; the page
  carries the drawing as it is and every link it makes opens a file of the workspace."
  (:require
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.roadmap-page :as page]
   [harness.setup.where :as where]
   [harness.setup.where-test :as where-test]
   [harness.setup.workspace :as workspace]))

(defn- elements
  "Every element of the parsed tree `t`, depth first."
  [t]
  (tree-seq #(seq (:content %)) #(filter map? (:content %)) t))

(defn- group [t id] (first (filter #(= id (get-in % [:attrs :id])) (elements t))))

(defn- local [tag] (some-> tag name keyword))

(defn- children [e] (filter map? (:content e)))

(deftest text-is-escaped-for-markup
  (is (= "D7 &amp; D8 &lt;b&gt; &quot;q&quot;" (page/esc "D7 & D8 <b> \"q\""))))

(deftest a-decision-is-drawn-on-the-stage-that-owns-it
  (let [stages [{:id 0 :state :closed :decisions ["D1"] :risks ["R1"]}
                {:id 1 :state :running :decisions ["D2"]}
                {:id 2 :state :ahead :decisions ["D1" "D2"] :risks ["R1"]}]]
    (is (= 2 (page/owner-stage stages :decisions "D1")) "the first stage not closed that names it")
    (is (= 1 (page/owner-stage stages :decisions "D2")))
    (is (= 0 (page/owner-stage [(first stages)] :decisions "D1")) "only closed stages name it: the last of them")
    (is (nil? (page/owner-stage stages :decisions "D9")) "named by no stage: drawn nowhere, listed under Open")))

(deftest the-page-says-when-it-was-generated-and-the-projects-name
  (is (= "Generated 20 October 2026, 11:50 CDT (22:20 IST)"
         (page/generated-line "2026-10-20T16:50:00Z" "America/Chicago" "Asia/Kolkata")))
  (is (= "Generated 20 October 2026, 11:50 CDT" (page/generated-line "2026-10-20T16:50:00Z" "America/Chicago" nil)))
  (is (= "Generated 20 October 2026, 22:20 IST" (page/generated-line "2026-10-20T16:50:00Z" "Asia/Kolkata" "Asia/Kolkata"))
      "the project's zone said only when its clock reads otherwise")
  (is (nil? (page/generated-line nil "UTC" nil)))
  (is (= "XYX-ONE" (page/project-name {:build "xyx-build" :display-name "XYX-ONE"})))
  (is (= "xyx" (page/project-name {:build "xyx-build"}))))

(deftest the-page-is-the-maps-and-opens-from-its-folder
  (let [ws-dir (where-test/workspace-on-disk)
        ws (workspace/find-workspace ws-dir)
        d (assoc-in (where/where-data ws {:at "2026-10-17T12:00:00Z" :plan-commit "abc1234"}) [:generated :zone] "UTC")
        [svg-file html-file] (page/write! ws d)
        svg (slurp svg-file)
        html (slurp html-file)
        tree (xml/parse-str svg)]
    (testing "kept under its time, the newest copy beside it, and the reports log a line longer"
      (is (re-matches #".*/work/where/roadmap-\d{8}-\d{6}\.svg" svg-file))
      (is (re-matches #".*/work/where/roadmap-\d{8}-\d{6}\.html" html-file))
      (is (= svg (slurp (str (fs/path ws-dir "work" "where" "roadmap.svg")))))
      (is (= html (slurp (str (fs/path ws-dir "work" "where" "roadmap.html")))))
      (is (= [{:at "2026-10-17T12:00:00Z" :plan-commit "abc1234" :files [(str "work/where/" (fs/file-name html-file))
                                                                         (str "work/where/" (fs/file-name svg-file))]}]
             (mapv #(dissoc % :kit-version) (where/reports ws))))
      (let [[svg2 html2] (page/write! ws d)]
        (is (not= html-file html2) "a second page of one second is a page of its own, never the first again")
        (is (fs/exists? html-file))
        (is (= 2 (count (where/reports ws))))
        (is (fs/exists? svg2))))
    (testing "the drawing: a lane a phase, a box a stage, a badge an open decision or risk on its stage"
      (is (= :svg (local (:tag tree))))
      (is (= 5 (count (filter #(and (= :rect (local (:tag %))) (= "88" (get-in % [:attrs :height])))
                              (children (group tree "lanes"))))))
      (is (= 3 (count (filter #(= :a (local (:tag %))) (children (group tree "stages"))))) "stages 0, 1, 2")
      (is (= ["D1" "R2"] (->> (elements (group tree "badges")) (filter #(= :text (local (:tag %)))) (mapcat :content) (filter string?))))
      (is (str/includes? svg "▶ you are here"))
      (is (str/includes? svg "$1.00 of $20 · 1 run<") "the money bar's caption: the stage's spend of its cap")
      (is (not (re-find #"font-family=\"[^\"]*(Segoe UI|-apple-system)" svg))
          "a font list the SVG pack's renderer reads as CSS: generic names, no bare multi-word one"))
    (testing "the page carries the same drawing and links only to files the workspace has"
      (is (str/includes? html (page/svg-body svg)))
      (let [hrefs (distinct (map second (re-seq #"href=\"([^\"]+)\"" html)))
            dir (fs/parent html-file)]
        (is (seq hrefs))
        (doseq [h hrefs]
          (is (str/starts-with? h "../../") h)
          (is (fs/exists? (fs/path dir h)) h))))
    (testing "the page's sections, from the map"
      (is (= ["Waits on you (2)" "Money and time" "Money by provider" "Money by role" "Since the last report" "Open" "Next"]
             (map second (re-seq #"<h2>([^<]+)</h2>" html))))
      (is (str/includes? html "<td class=\"num\">$0.25 (readings only)</td>"))
      (is (str/includes? html "Generated 2026-10-17T12:00:00Z from <code>xyx-build</code> at <code>abc1234</code>"))
      (is (str/includes? html "<h1>xyx - Roadmap Report</h1>") "the page is the report on the roadmap, dated")
      (is (str/includes? svg "<title>xyx - the roadmap: five phases") "the drawing alone is the roadmap")
      (is (str/includes? html "<p class=\"generated\">Generated 17 October 2026, 12:00 UTC</p>") "under the title"))))

(deftest a-prep-rows-parts-are-a-list-under-its-line
  (is (= (str "  <li><strong>HP-2</strong>: a font - the prep. <a href=\"../../b/x.md\">b/x.md §0</a>"
              "<ul><li>its licence</li><li>the &lt;subset&gt;</li></ul></li>")
         (page/wait-li "../../" {:what "HP-2" :detail "a font" :items ["its licence" "the <subset>"] :why "the prep" :where "b/x.md §0"})))
  (is (= "  <li><strong>D1, Hosting</strong> - why. <a href=\"../../d.md\">d.md §2</a></li>"
         (page/wait-li "../../" {:what "D1, Hosting" :why "why" :where "d.md §2"}))))
