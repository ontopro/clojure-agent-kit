(ns harness.gates.toolchain-image-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [harness.gates.toolchain-image :as ti]))

(def kit (str (fs/normalize (fs/absolutize ".."))))

(deftest the-image-is-named-for-what-it-holds
  ;; From Fable 5.1's read of 720f2af: the tag names what the image holds, so a change not yet built
  ;; is a missing image, never the old toolchain
  (is (re-matches #"clojure-agent-kit-toolchain:\d{4}-\d{2}-\d{2}-[0-9a-f]{7}" (ti/image-tag kit)))
  (let [ctx (ti/image-context kit)]
    (is (= "Dockerfile" (ffirst ctx)) "the Dockerfile, then the pack's files, sorted")
    (is (some #(= "tools/toolchain/download-matrix.edn" (first %)) ctx))
    (is (= (ti/context-digest ctx) (ti/context-digest (reverse (reverse ctx)))) "the same context, the same digest")
    (is (not= (ti/context-digest ctx)
              (ti/context-digest (map (fn [[p c]] [p (if (str/ends-with? p "download-matrix.edn") (str c " ") c)]) ctx)))
        "a byte of the matrix moves it")))

(deftest a-hidden-file-docker-is-sent-is-in-the-digest
  ;; From Fable 5.1's read of 1de97ad: `.dockerignore`'s `!tools/toolchain` sends hidden files, so
  ;; the digest covers them - it covers exactly the context
  (let [k (fs/create-temp-dir)]
    (spit (str (fs/path k "Dockerfile")) "FROM x")
    (fs/create-dirs (fs/path k "tools" "toolchain"))
    (spit (str (fs/path k "tools" "toolchain" "bb.edn")) "{}")
    (let [before (ti/context-digest (ti/image-context k))]
      (spit (str (fs/path k "tools" "toolchain" ".DS_Store")) "finder")
      (is (some #(= "tools/toolchain/.DS_Store" (first %)) (ti/image-context k)))
      (is (not= before (ti/context-digest (ti/image-context k)))))))
