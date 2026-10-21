(ns harness.contained-path-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [harness.contained-path :as contained-path]))

(deftest a-path-is-inside-by-its-parts-never-its-string
  (let [outer (fs/create-temp-dir {:prefix "kit-contained"})
        root (fs/path outer "run-1-coder")]
    (try
      (fs/create-dirs (fs/path root "src"))
      (fs/create-dirs (fs/path outer "run-1-coder-other"))
      (spit (str (fs/path outer "run-1-coder.nrepl.log")) "log")
      (testing "inside"
        (is (= (str (fs/path (fs/absolutize root) "src" "a.clj")) (str (contained-path/inside root "src/a.clj"))))
        (is (some? (contained-path/inside root "src/../src/a.clj")) "a `..` that stays inside")
        (is (some? (contained-path/inside root "not/yet/written.clj")) "a path that does not exist yet")
        (is (some? (contained-path/inside root ".")) "the folder itself"))
      (testing "outside"
        (is (nil? (contained-path/inside root "../run-1-coder.nrepl.log")) "a sibling whose name starts with the folder's")
        (is (nil? (contained-path/inside root "../run-1-coder-other/x.clj")))
        (is (nil? (contained-path/inside root "src/../../x")))
        (is (nil? (contained-path/inside root (str (fs/path outer "x")))) "an absolute path, even one that would be inside")
        (is (nil? (contained-path/inside root (str (fs/path (fs/absolutize root) "src")))))
        (is (nil? (contained-path/inside root "")))
        (is (nil? (contained-path/inside root nil))))
      (finally (fs/delete-tree outer)))))

(deftest a-symlink-is-followed-before-it-is-trusted
  (let [outer (fs/create-temp-dir {:prefix "kit-contained-link"})
        root (fs/path outer "ws")]
    (try
      (fs/create-dirs (fs/path root "src"))
      (fs/create-dirs (fs/path outer "elsewhere"))
      (spit (str (fs/path outer "secret.txt")) "s")
      (spit (str (fs/path root "src" "real.clj")) "r")
      (fs/create-sym-link (fs/path root "src" "linked.clj") (fs/path outer "secret.txt"))
      (fs/create-sym-link (fs/path root "out") (fs/path outer "elsewhere"))
      (fs/create-sym-link (fs/path root "alias.clj") (fs/path root "src" "real.clj"))
      (is (nil? (contained-path/inside root "src/linked.clj")) "a file linked to outside")
      (is (nil? (contained-path/inside root "out/new_test.clj")) "a new file under a folder linked to outside")
      (is (some? (contained-path/inside root "alias.clj")) "a link that stays inside")
      (finally (fs/delete-tree outer)))))

;; From the KIT's own security review (GPT-6 Astra, the contract flow, 2026-10-21): `fs/exists?` follows a
;; link, so a dangling one had read as a file not yet written and the write created its target outside
(deftest a-dangling-link-is-judged-where-it-points
  (let [outer (fs/create-temp-dir {:prefix "kit-contained-dangling"})
        root (fs/path outer "ws")]
    (try
      (fs/create-dirs (fs/path root "src"))
      (fs/create-sym-link (fs/path root "src" "out.clj") (fs/path outer "made-outside.clj"))
      (fs/create-sym-link (fs/path root "src" "in.clj") (fs/path root "src" "written-later.clj"))
      (fs/create-sym-link (fs/path root "loop-a") (fs/path root "loop-b"))
      (fs/create-sym-link (fs/path root "loop-b") (fs/path root "loop-a"))
      (is (nil? (contained-path/inside root "src/out.clj")) "a dangling link pointing outside")
      (is (some? (contained-path/inside root "src/in.clj")) "a dangling link pointing inside")
      (is (nil? (contained-path/inside root "loop-a/x")) "a loop of links")
      (finally (fs/delete-tree outer)))))

;; From the KIT's own security review (Gemini 4 Argon, the seams flow, 2026-10-21): `..` was taken from the
;; string before the links were followed, while the file tools opened the string - where the OS takes the
;; link first and the `..` after it
(deftest a-dotdot-after-a-link-goes-where-the-os-goes
  (let [outer (fs/create-temp-dir {:prefix "kit-contained-dotdot"})
        root (fs/path outer "ws")]
    (try
      (fs/create-dirs (fs/path root "src" "deep" "er"))
      (fs/create-dirs (fs/path outer "other" "sub"))
      (spit (str (fs/path outer "other" "secret")) "s")
      (fs/create-sym-link (fs/path root "linkdir") (fs/path outer "other" "sub"))
      (fs/create-sym-link (fs/path root "inlink") (fs/path root "src" "deep" "er"))
      (is (nil? (contained-path/inside root "linkdir/../secret")) "the OS lands outside")
      (is (nil? (contained-path/inside root "inlink/../x.clj"))
          "inside either way, but two different files: the string a caller opens is not the one judged")
      (is (some? (contained-path/inside root "src/../src/a.clj")) "a `..` with no link before it is one place")
      (is (some? (contained-path/inside root "./src/./a.clj")))
      (finally (fs/delete-tree outer)))))
