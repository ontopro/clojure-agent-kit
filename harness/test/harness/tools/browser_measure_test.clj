(ns harness.tools.browser-measure-test
  "The browser pack's judgement of a measured page (`tools/browser/src/browser_measure.clj`). The
  measuring itself - a real Firefox, a frame for a narrow width - is tried by `bb health`, which
  screenshots the generated application at 390 and 1440."
  (:require
   [browser-measure :as bm]
   [clojure.test :refer [deftest is testing]]))

(deftest a-width-under-the-windows-minimum-is-a-frames
  (is (bm/framed? 390))
  (is (bm/framed? 499))
  (is (not (bm/framed? 500)))
  (is (not (bm/framed? 1440)))
  (is (< bm/min-window bm/frame-window) "the frame's window is never narrower than the frame"))

;; THE 390 THAT WAS 500. Before the frame, a 390 asked of headless Firefox measured 500 and passed
;; as 390 on every page of three stage ends of the first KIT project.
(deftest a-page-measured-at-another-width-is-a-problem-never-a-pass
  (is (nil? (bm/width-problem 390 {:innerWidth 390 :scrollWidth 390})))
  (is (nil? (bm/width-problem 1440 {:innerWidth 1440})))
  (testing "the measure the pack recorded before the frame"
    (is (= "measured 500 px wide, not the 390 asked"
           (bm/width-problem 390 {:innerWidth 500 :scrollWidth 500 :overflows? false})))))
