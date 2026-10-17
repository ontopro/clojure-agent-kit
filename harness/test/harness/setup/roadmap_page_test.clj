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

(deftest the-page-is-the-maps-and-opens-from-its-folder
  (let [ws-dir (where-test/workspace-on-disk)
        ws (workspace/find-workspace ws-dir)
        d (where/where-data ws {:at "2026-10-17T12:00:00Z" :plan-commit "abc1234"})
        [svg-file html-file] (page/write! ws d)
        svg (slurp svg-file)
        html (slurp html-file)
        tree (xml/parse-str svg)]
    (is (= [(str (fs/path ws-dir "work" "where" "roadmap.svg")) (str (fs/path ws-dir "work" "where" "roadmap.html"))]
           [svg-file html-file]))
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
      (is (= ["Waits on you (2)" "Money and time" "Since you last looked" "Open" "Next"]
             (map second (re-seq #"<h2>([^<]+)</h2>" html))))
      (is (str/includes? html "<td class=\"num\">$0.25 (readings only)</td>"))
      (is (str/includes? html "Generated 2026-10-17T12:00:00Z from <code>xyx-build</code> at <code>abc1234</code>")))))
