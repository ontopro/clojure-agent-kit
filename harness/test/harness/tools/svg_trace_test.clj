(ns harness.tools.svg-trace-test
  "The SVG pack's trace, its fitting: pictures made here, in pixels, so nothing outside the KIT is read."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [svg-trace :as t]))

(defn- picture
  "A 112-pixel picture, white, with ink where `ink?` says, in `colour`."
  [ink? colour]
  (t/ink-grid 112 112 (fn [x y] (if (ink? x y) colour [255 255 255]))
              (if (= colour [20 20 20]) :darkness :saturation)))

(defn- ring [x y] (<= 18.5 (Math/hypot (- x 40) (- y 40)) 21.5))
(defn- bar [x y] (and (<= 70 x 100) (<= 78 y 81)))

(deftest a-ring-is-a-circle-and-a-bar-a-line
  (let [{:keys [elements svg]} (t/icon (picture #(or (ring %1 %2) (bar %1 %2)) [200 30 30]) {:region [10 10 105 95]} 1.5)]
    (is (= #{:circle :path} (set (map :t elements))))
    (is (some #(= "L" (first (second (:cmds %)))) elements) "the bar, a straight line")
    (testing "the icon contract: 24 grid, no fill, currentColor at the stroke, round caps and joins, path and circle only"
      (is (str/includes? svg "viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\""))
      (is (= #{"svg" "path" "circle"} (set (map second (re-seq #"<(\w+)" svg))))))))

(deftest black-line-art-is-read-by-its-darkness
  (is (= [:circle] (map :t (:elements (t/icon (picture ring [20 20 20]) {:region [10 10 70 70] :ink :darkness} 1.5)))))
  (testing "and nothing of it as colour"
    (is (empty? (:elements (t/icon (t/ink-grid 112 112 (fn [x y] (if (ring x y) [20 20 20] [255 255 255])) :saturation)
                                   {:region [10 10 70 70]} 1.5))))))

(deftest what-tracing-cannot-follow-is-left-out-or-added
  (let [grid (picture #(or (ring %1 %2) (bar %1 %2)) [200 30 30])]
    (testing "a region left out drops the chains inside it"
      (is (= [:circle] (map :t (:elements (t/icon grid {:region [10 10 105 95] :exclude [[65 70 105 90]]} 1.5))))))
    (testing "an element added in the picture's pixels joins the trace"
      (let [{:keys [elements]} (t/icon grid {:region [10 10 105 95] :add [{:circle [90 30 4]} {:path [["M" [70 20]] ["L" [70 40]]]}]} 1.5)]
        (is (= 4 (count elements)))))
    (testing "a shape the picture fills is added filled, as the icon's own element"
      (let [{:keys [svg]} (t/icon grid {:region [10 10 105 95] :add [{:circle [90 30 4] :fill true}]} 1.5)]
        (is (re-find #"<circle [^>]*fill=\"currentColor\" />" svg))
        (is (str/includes? svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\"")
            "the svg itself has none")))
    (testing "an added arc's end is inside the fitted box"
      (let [{:keys [svg]} (t/icon (picture ring [200 30 30]) {:region [10 10 70 70] :add [{:path [["M" [40 60]] ["A" 30 0 1 [100 60]]]}]} 1.5)
            xs (map #(parse-double (second %)) (re-seq #"A[\d.]+ [\d.]+ 0 \d \d ([\d.]+)" svg))]
        (is (every? #(<= % 23.01) xs))))))

(deftest a-dot-standing-alone-is-a-filled-circle-at-the-strokes-width
  (let [dot? (fn [x y] (<= (Math/hypot (- x 30) (- y 30)) 1.5))
        {:keys [svg]} (t/icon (picture #(or (ring %1 %2) (dot? %1 %2)) [200 30 30]) {:region [10 10 70 70] :dots? true} 1.5)]
    (is (re-find #"<circle [^>]*r=\"0.75\" fill=\"currentColor\" stroke=\"none\" />" svg))))

(deftest an-arc-is-an-arc
  ;; a band of the circle between two angles, with round ends, as a pen draws it
  (let [end (fn [deg] [(+ 56 (* 30 (Math/cos (Math/toRadians deg)))) (+ 70 (* 30 (Math/sin (Math/toRadians deg))))])
        [ax ay] (end 210) [bx by] (end 330)
        on-arc? (fn [x y] (let [a (Math/toDegrees (Math/atan2 (- y 70) (- x 56))) a (if (neg? a) (+ a 360) a)]
                            (and (<= 28.5 (Math/hypot (- x 56) (- y 70)) 31.5) (<= 210 a 330))))
        arc? (fn [x y] (or (on-arc? x y) (<= (Math/hypot (- x ax) (- y ay)) 1.5) (<= (Math/hypot (- x bx) (- y by)) 1.5)))
        {:keys [elements]} (t/icon (picture arc? [30 90 200]) {:region [15 30 100 70]} 1.5)]
    (is (some (fn [e] (some #(= "A" (first %)) (:cmds e))) elements))))
