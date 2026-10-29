(ns harness.tools.toolchain-install-test
  "The toolchain pack's install (`tools/toolchain/src/toolchain_install.clj`) and its plan
  (`toolchain_download_matrix.clj`, plan 7.0, item 8.1c): the plan on the four platforms and three
  cache states, the Clojure CLI's layout, the sha256 refusal, the links. The fetching is tried by
  running `toolchain-install` into a scratch cache, and by the KIT's toolchain image, which installs
  its tools by it."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [toolchain-download-matrix :as dm]
   [toolchain-install :as install]))

(def matrix (dm/read-matrix "../tools/toolchain/download-matrix.edn"))

;; a map of the steps by tool; `(keys …)` keeps the plan's order because it has no more than eight
;; entries, which Clojure keeps as an array map (From Fable 5.1's read of cf554a7)
(defn- steps [platform opts]
  (into {} (map (juxt :tool identity)) (dm/plan matrix "/c" platform opts)))

(deftest the-plan-on-each-platform-and-cache
  (testing "a fresh cache on a Mac: bb kept as it runs, the JDK only when named, the rest installed"
    (let [s (steps :macos-aarch64 {:running {:bb "1.13.229"}})]
      (is (= [:bb :java :clj-kondo :clojure] (keys s)) "no image-only tool")
      (is (= :skip (:step (:bb s))))
      (is (str/includes? (:why (:bb s)) "the bb running this is 1.13.229, at or above 1.13.229"))
      (is (= "fetched only when named: --only java" (:why (:java s))))
      (is (= {:tool :clj-kondo :version "2026.10.26" :step :install
              :bin "/c/clj-kondo/2026.10.26/clj-kondo" :dir "/c/clj-kondo/2026.10.26"
              :download "/c/downloads/clj-kondo-2026.10.26-macos-aarch64.zip"}
             (dissoc (:clj-kondo s) :asset)))
      (is (= "https://download.clojure.org/install/clojure-tools-1.12.6.1673.tar.gz" (get-in s [:clojure :asset :url])))))
  (testing "an older running bb: the matrix's is installed beside it"
    (is (= :install (:step (:bb (steps :macos-amd64 {:running {:bb "1.13.226"}}))))))
  (testing "a newer running bb is used as it is"
    (is (= :skip (:step (:bb (steps :linux-amd64 {:running {:bb "1.13.300"}}))))))
  (testing "a cache that has a tool keeps it"
    (is (= :kept (:step (:clj-kondo (steps :linux-aarch64 {:present? #{"/c/clj-kondo/2026.10.26/clj-kondo"}}))))))
  (testing "the image's --only: the image-only tools when named, the JDK when named, on Linux"
    (let [s (steps :linux-aarch64 {:only [:java :clj-kondo :clojure :geckodriver]})]
      (is (= [:java :clj-kondo :clojure :geckodriver] (keys s)))
      (is (every? #(= :install (:step %)) (vals s)))
      (is (= "/c/java/25.0.5+8" (get-in s [:java :dir])))
      (is (= "/c/java/25.0.5+8/jdk-25.0.5+8/bin/java" (get-in s [:java :bin])))
      (is (= "geckodriver-v0.37.1-linux-aarch64.tar.gz" (get-in s [:geckodriver :asset :file])))))
  (testing "git is Linux's alone, and a Mac never takes it"
    (is (empty? (dm/plan matrix "/c" :macos-aarch64 {:only [:git]}))))
  (testing "an unknown tool is refused by name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not in the download matrix: nope"
                          (dm/plan matrix "/c" :linux-amd64 {:only [:nope]})))))

(deftest versions-compare-by-their-numbers
  (is (pos? (dm/compare-versions "1.13.229" "1.13.226")))
  (is (zero? (dm/compare-versions "25.0.5" "25.0.5+8")))
  (is (neg? (dm/compare-versions "2026.08.04" "2026.10.26"))))

(deftest the-clojure-cli-is-laid-out-as-posix-install-does
  (let [layout (install/cli-layout "1.12.6.1673" "/p")]
    (is (= 9 (count layout)) "the tarball's nine files - its install.sh, Homebrew's, is not one")
    (is (= {:from "clojure" :to "/p/bin/clojure" :mode "rwxr-xr-x" :subst ["PREFIX" "/p/lib/clojure"]}
           (first (filter #(= "clojure" (:from %)) layout))))
    (is (= {:from "clj" :to "/p/bin/clj" :mode "rwxr-xr-x" :subst ["BINDIR" "/p/bin"]}
           (first (filter #(= "clj" (:from %)) layout))))
    (is (= #{"/p/lib/clojure/libexec/exec.jar" "/p/lib/clojure/libexec/clojure-tools-1.12.6.1673.jar"}
           (set (keep #(when (str/includes? (:to %) "/libexec/") (:to %)) layout))))
    (is (= #{"rw-r--r--"} (set (map :mode (remove :subst layout)))) "every other file 644"))
  (testing "laid out from a folder: the placeholders filled, the modes set, a missing file refused"
    (let [from (fs/create-temp-dir) to (str (fs/create-temp-dir) "/cli")
          layout (install/cli-layout "9.9" to)]
      (doseq [{f :from} layout] (spit (str (fs/path from f)) (str f " PREFIX BINDIR")))
      (install/lay-out! layout from)
      (is (= "clojure /x BINDIR" (str/replace (slurp (str to "/bin/clojure")) (str to "/lib/clojure") "/x"))
          "only its own placeholder")
      (is (= (str "clj PREFIX " to "/bin") (slurp (str to "/bin/clj"))))
      (is (= "rwxr-xr-x" (fs/posix->str (fs/posix-file-permissions (str to "/bin/clojure")))))
      (is (= "rw-r--r--" (fs/posix->str (fs/posix-file-permissions (str to "/lib/clojure/deps.edn")))))
      (fs/delete (fs/path from "tools.edn"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the Clojure tools tarball has no tools.edn"
                            (install/lay-out! layout from))))))

(deftest a-download-that-is-not-the-matrixs-is-deleted-and-refused
  (let [f (str (fs/path (fs/create-temp-dir) "a.zip"))]
    (spit f "what was downloaded")
    (is (= f (install/verify! f (install/sha256-of f))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a.zip: its sha256 is [0-9a-f]{64}, the download matrix says 00 - deleted, nothing installed"
                          (install/verify! f "00")))
    (is (not (fs/exists? f)))))

(deftest a-tool-in-place-is-moved-aside-not-deleted-first
  ;; From Fable 5.1's read of cf554a7: links and PATH point at the folder, so it is never missing
  ;; longer than a rename, and a failed move puts the old one back
  (let [root (fs/create-temp-dir)
        dir (str (fs/path root "t" "1.0"))
        staged (str (fs/path root "staged"))]
    (fs/create-dirs dir) (spit (str dir "/old") "old")
    (fs/create-dirs staged) (spit (str staged "/new") "new")
    (install/swap-in! staged dir)
    (is (= ["new"] (map (comp str fs/file-name) (fs/list-dir dir))))
    (is (not (fs/exists? (str dir ".old"))) "the old one gone once the new is in")
    (testing "a move that fails puts the old one back"
      (is (thrown? Exception (install/swap-in! (str (fs/path root "no-such")) dir)))
      (is (= ["new"] (map (comp str fs/file-name) (fs/list-dir dir))))
      (is (not (fs/exists? (str dir ".old")))))))

(deftest the-links-and-the-arguments
  (is (= [["java" "/c/java/v/jdk/bin/java"] ["javac" "/c/java/v/jdk/bin/javac"]
          ["clojure" "/c/clojure/v/bin/clojure"] ["clj" "/c/clojure/v/bin/clj"]
          ["clj-kondo" "/c/clj-kondo/v/clj-kondo"]]
         (install/link-targets [{:tool :java :step :installed :bin "/c/java/v/jdk/bin/java"}
                                {:tool :clojure :step :kept :bin "/c/clojure/v/bin/clojure"}
                                {:tool :clj-kondo :step :installed :bin "/c/clj-kondo/v/clj-kondo"}
                                {:tool :bb :step :skip :bin "/c/bb/v/bb"}]
                               (fn [_] ["/c/java/v/jdk/bin/java" "/c/java/v/jdk/bin/javac"])))
      "the JDK's every executable, the CLI's two, a tool's one; a skipped tool none")
  (is (= {:only [:java :clojure] :link "/usr/local/bin" :dry-run? true}
         (install/parse-args ["--only" "java,clojure" "--link" "/usr/local/bin" "--dry-run"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"toolchain-install \[--only" (install/parse-args ["--everything"]))))

(def leaving
  "The Dockerfile's ARGs of tools the pack does not hold yet, each with the sub-task that removes it
  - deleted from this list by the commit that deletes its ARG (plan 7.0, item 8.1c, Q3)."
  {"CLJFMT_VERSION" "8.2a: cljfmt the pack's dependency"
   "BBIN_VERSION" "8.2d: bbin goes"
   "MCP_LIGHT_TAG" "8.2d: clojure-mcp-light the pack's dependency"
   "MCP_LIGHT_SHA" "8.2d: clojure-mcp-light the pack's dependency"})

(deftest the-images-build-arguments-are-the-matrixs
  ;; D-9: the image installs by running the pack; only what the shell fetches before bb exists is
  ;; a build argument, from the matrix, with no default - a default is a second owner
  (let [dockerfile (slurp "../Dockerfile")
        declared (into {} (for [[_ k v] (re-seq #"(?m)^ARG ([A-Z_0-9]+)(?:=(\S+))?" dockerfile)] [k v]))
        args (dm/image-args matrix :linux-aarch64)]
    (is (= (set (concat (keys args) ["KIT_AS_OF" "TARGETARCH"] (keys leaving))) (set (keys declared)))
        "every ARG is the matrix's, the date, Docker's own, or one leaving with its sub-task")
    (is (every? nil? (map declared (cons "KIT_AS_OF" (keys args)))) "no default for the matrix's or the date")
    (is (= {"BB_VERSION" "1.13.229" "GIT_VERSION" "2.56.0"} (select-keys args ["BB_VERSION" "GIT_VERSION"])))
    (is (= "https://github.com/babashka/babashka/releases/download/v1.13.229/babashka-1.13.229-linux-amd64-static.tar.gz"
           (get (dm/image-args matrix :linux-amd64) "BB_URL")))
    (is (str/includes? dockerfile "toolchain-install \\\n      --only java,clj-kondo,clojure,geckodriver --link /usr/local/bin")
        "the rest by the pack")))
