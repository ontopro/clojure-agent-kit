(ns harness.tools.svg-trace-files-test
  "The SVG pack's trace command, where it reads and writes: names, pictures, links, the contact sheet."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [svg-trace :as t]
   [svg-trace-files :as files]))

;; From the KIT's own security review (GPT-6 Astra, the tool-packs flow, 2026-10-21): an icon's name,
;; a picture's path and the output folder came from the project's icons file, and the command
;; followed each wherever it led

(deftest an-icons-name-is-a-name
  (is (= [] (files/bad-names ["phone" "badge_2" "a-b"])))
  (is (= ["../../victim" "<img src=x onerror=alert(1)>" "a/b" "" :kw nil]
         (files/bad-names ["ok" "../../victim" "<img src=x onerror=alert(1)>" "a/b" "" :kw nil]))))

(deftest a-picture-is-a-file-of-the-icons-files-folder
  (let [root (fs/create-temp-dir {:prefix "svg-pics-"})
        project (fs/path root "project")]
    (try
      (fs/create-dirs (fs/path project "crops"))
      (spit (str (fs/path project "crops" "in.png")) "picture")
      (spit (str (fs/path root "private.png")) "host file")
      (fs/create-sym-link (fs/path project "crops" "linked.png") (fs/path root "private.png"))
      (let [base (fs/file project)]
        (is (= (str (fs/real-path (fs/path project "crops" "in.png"))) (str (files/picture-file base "crops/in.png"))))
        (testing "out by `..`, absolute, through a link, missing, not a string: nil"
          (doseq [png ["../private.png" (str (fs/path root "private.png")) "crops/linked.png" "crops/none.png" "" nil 7]]
            (is (nil? (files/picture-file base png)) (pr-str png))))
        ;; from Fable 5.1's read of `c5d7e01`: an absolute path that happened to land inside was taken
        (is (nil? (files/picture-file base (str (fs/path project "crops" "in.png")))) "absolute, though inside")
        (testing "a folder that is a link to outside is followed, and refused"
          (fs/create-dirs (fs/path root "outside-crops"))
          (spit (str (fs/path root "outside-crops" "in.png")) "host file")
          (fs/create-sym-link (fs/path project "linked-crops") (fs/path root "outside-crops"))
          (is (nil? (files/picture-file base "linked-crops/in.png")))))
      (finally (fs/delete-tree root)))))

(deftest nothing-is-written-through-a-link
  (let [root (fs/create-temp-dir {:prefix "svg-out-"})
        out (fs/path root "project" "traced")
        victim (fs/path root "private.conf")]
    (try
      (fs/create-dirs out)
      (spit (str victim) "keep me")
      (fs/create-sym-link (fs/path out "icon.svg") victim)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is a link - not written through"
                            (files/write! (fs/file out) "icon.svg" "<svg/>")))
      (is (= "keep me" (slurp (str victim))))
      (testing "a file that is not a link is written, and written again"
        (files/write! (fs/file out) "other.svg" "one")
        (files/write! (fs/file out) "other.svg" "two")
        (is (= "two" (slurp (str (fs/path out "other.svg"))))))
      (testing "the default output folder, when it is a link, is refused"
        (fs/create-dirs (fs/path root "p2"))
        (fs/create-sym-link (fs/path root "p2" "traced") (fs/path root "elsewhere"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is a link"
                              (files/out-folder (fs/file (fs/path root "p2")) nil)))
        (is (= (str (fs/path root "given")) (str (files/out-folder (fs/file (fs/path root "p2")) (str (fs/path root "given")))))
            "a folder the person gives is theirs"))
      (finally (fs/delete-tree root)))))

(deftest text-in-the-contact-sheet-is-text
  (is (= "&lt;img src=x onerror=alert(1)&gt;" (files/escape-html "<img src=x onerror=alert(1)>")))
  (is (= "&quot;&#39;&amp;" (files/escape-html "\"'&"))))

(deftest an-arcs-flags-are-0-or-1
  (let [grid (t/ink-grid 4 4 (fn [_ _] [255 255 255]) :saturation)
        injection "0\"/><script>alert('trace')</script><path d=\"M0 0 A1 1 0 0"]
    (doseq [flag [injection 2 1.0 nil]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"an arc's flags are 0 or 1"
                            (t/icon grid {:region [0 0 0 0] :add [{:path [["M" [0 0]] ["A" 1 flag 1 [2 2]]]}]} 1.5))
          (pr-str flag)))
    (is (str/includes? (:svg (t/icon grid {:region [0 0 0 0] :add [{:path [["M" [0 0]] ["A" 1 0 1 [2 2]]]}]} 1.5)) " 0 0 1 ")
        "0 and 1 are drawn")))
