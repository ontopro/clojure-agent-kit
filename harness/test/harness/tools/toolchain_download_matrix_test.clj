(ns harness.tools.toolchain-download-matrix-test
  "The toolchain pack's download matrix (`tools/toolchain/download-matrix.edn`) and its reader
  (`tools/toolchain/src/toolchain_download_matrix.clj`), with the status rows and toolchain-sum's
  rows (`toolchain_status.clj`, `toolchain_install.clj`) on canned input. The fetching is tried by
  running `toolchain-sum`, which fills the matrix's sha256s."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.doctor :as doctor]
   [toolchain-download-matrix :as dm]
   [toolchain-install :as install]
   [toolchain-status :as status]))

(def matrix (dm/read-matrix "../tools/toolchain/download-matrix.edn"))

(deftest the-platform-is-one-of-three-and-any-other-is-refused-by-name
  (is (= :macos-aarch64 (dm/platform-key "Mac OS X" "aarch64")))
  (is (= :linux-aarch64 (dm/platform-key "Linux" "aarch64")))
  (is (= :linux-amd64 (dm/platform-key "Linux" "amd64")))
  (doseq [[os arch] [["Mac OS X" "x86_64"] ["Windows 11" "amd64"] ["Linux" "riscv64"] ["FreeBSD" "amd64"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          (re-pattern (str "no toolchain for " os " " arch " - the download matrix has macos-aarch64"))
                          (dm/platform-key os arch)))))

(deftest every-archive-has-its-url-filled-and-a-sha256
  (doseq [t (keys matrix)
          a (dm/assets matrix t)]
    (testing (str t " " (:platform a))
      (is (not (str/includes? (:url a) "{")) "no placeholder left")
      (is (str/ends-with? (:url a) (:file a)))
      (is (re-matches #"[0-9a-f]{64}" (str (:sha256 a))) "a sha256, pasted from toolchain-sum")))
  (testing "Temurin's two spellings of its version"
    (is (= (str "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/"
                "OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.5_8.tar.gz")
           (:url (dm/asset {:java (assoc (:java matrix) :version "25.0.5+8")} :java :macos-aarch64))))))

(deftest a-machine-takes-every-tool-but-the-image-only-ones-unless-named
  (is (= [:bb :java :clj-kondo :clojure :cljfmt] (dm/machine-tools matrix :macos-aarch64 nil)))
  (is (= [:bb :java :clj-kondo :clojure :cljfmt] (dm/machine-tools matrix :linux-amd64 nil)))
  (is (= [:java :clj-kondo :clojure :geckodriver]
         (dm/machine-tools matrix :linux-aarch64 [:java :clj-kondo :clojure :geckodriver]))
      "the KIT's toolchain image names them")
  (is (= [] (dm/machine-tools matrix :macos-aarch64 [:geckodriver])) "no archive for the platform")
  (is (= [] (dm/machine-tools matrix :macos-aarch64 [:git])) "git builds on Linux alone (:os)")
  (is (= [:git] (dm/machine-tools matrix :linux-amd64 [:git])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not in the download matrix: jaava - it has bb, java"
                        (dm/machine-tools matrix :linux-amd64 [:java :jaava]))))

(deftest the-matrix-meets-the-environment-tables-floors
  (let [floors (into {} (map (juxt :tool :min)) (edn/read-string (slurp "resources/toolchain.edn")))]
    (doseq [t [:bb :java]]
      (is (not (neg? (doctor/version-cmp (first (str/split (get-in matrix [t :version]) #"\+"))
                                         (floors t))))
          (str t " at or above its floor")))))

(deftest a-tool-lives-at-its-cache-path
  (is (= "/c/bb/1.13.229/bb" (dm/bin-path matrix "/c" :bb :macos-aarch64)))
  (is (= "/c/clojure/1.12.6.1673/bin/clojure" (dm/bin-path matrix "/c" :clojure :linux-amd64)))
  (is (= "/c/java/25.0.5+8/jdk-25.0.5+8/Contents/Home/bin/java" (dm/bin-path matrix "/c" :java :macos-aarch64)))
  (is (= "/c/java/25.0.5+8/jdk-25.0.5+8/bin/java" (dm/bin-path matrix "/c" :java :linux-aarch64))))

(deftest a-publishers-checksum-is-read-for-its-file
  (let [h (apply str (repeat 8 "0123abcd"))
        h2 (apply str (repeat 8 "ffff0000"))]
    (is (= h (dm/published-sha256 (str h "\n") "a.tar.gz")) "one checksum, no name")
    (is (= h (dm/published-sha256 (str (str/upper-case h) "  a.tar.gz\n") "a.tar.gz")) "lower case")
    (is (= h2 (dm/published-sha256 (str h "  git-2.56.0.tar.gz\n" h2 "  git-2.56.0.tar.xz\n" h "  git-2.56.0.tar.xz.sign\n")
                                   "git-2.56.0.tar.xz"))
        "a list: the line naming the file exactly")
    (is (= h2 (dm/published-sha256 (str h2 " *a.zip") "a.zip")) "sha256sum's binary mark")
    (is (nil? (dm/published-sha256 (str h "  b.tar.gz\n" h2 "  c.tar.gz\n") "a.tar.gz")) "a list without it")
    (is (nil? (dm/published-sha256 nil "a.tar.gz")) "none published")))

(deftest the-project-folder-is-the-one-above-the-clone
  ;; plan 7.0, item 8.2b (the person, 2026-10-30: "the folder above the clone, KIT/.kit/toolchain is
  ;; fine - for KIT development too")
  (is (= "/w/p/KIT" (status/clone-of "/w/p/KIT/tools/toolchain")))
  (is (= "/w/p/KIT" (status/clone-of "/w/p/KIT/tools/toolchain/")) "a trailing slash")
  (is (= "/w/p" (status/project-folder "/w/p/KIT")))
  (is (nil? (status/folder-refusal "/w/p" "/home/u")))
  (is (str/includes? (status/folder-refusal "/home/u" "/home/u") "your home folder")
      "a clone straight under home: the .env would be ~/.env")
  (is (str/includes? (status/folder-refusal "/home/u" "/home/u/") "your home folder") "home as the JVM gives it")
  (is (str/includes? (status/folder-refusal "/" "/home/u") "the file system's root"))
  (testing "the place: the folder, the tools and the keys in it; a note inside a git repository"
    (let [root (fs/create-temp-dir)
          pack (str (fs/path root "p" "KIT" "tools" "toolchain"))
          folder (str (fs/normalize (fs/absolutize (fs/path root "p"))))]
      (fs/create-dirs pack)
      (is (= {:folder folder :cache (str folder "/.kit/toolchain") :env (str folder "/.env")}
             (status/place pack {} "/home/u")))
      (is (= "/t" (:cache (status/place pack {"KIT_TOOLCHAIN_DIR" "/t"} "/home/u"))))
      (p/shell {:dir (str root) :out :string :err :string} "git" "init" "-q")
      (is (str/includes? (:note (status/place pack {} "/home/u")) "inside a git repository")))))

(deftest the-status-rows-are-the-contract-the-doctor-will-read
  (is (= "/k" (status/cache-dir {"KIT_TOOLCHAIN_DIR" "/k"} "/w/p")))
  (is (= "/w/p/.kit/toolchain" (status/cache-dir {} "/w/p")) "the project folder's (plan 7.0, item 8.2b)")
  (is (= [{:tool :bb :version "1.13.229" :path "/c/bb/1.13.229/bb" :present? true}
          {:tool :java :version "25.0.5+8" :path "/c/java/25.0.5+8/jdk-25.0.5+8/bin/java" :present? false
           :fix "bb --config /k/bb.edn toolchain-install --only java"}
          {:tool :clj-kondo :version "2026.10.26" :path "/c/clj-kondo/2026.10.26/clj-kondo" :present? false
           :fix "bb --config /k/bb.edn toolchain-install --only clj-kondo"}
          {:tool :clojure :version "1.12.6.1673" :path "/c/clojure/1.12.6.1673/bin/clojure" :present? false
           :fix "bb --config /k/bb.edn toolchain-install --only clojure"}]
         (status/rows (select-keys matrix [:bb :java :clj-kondo :clojure :git]) "/c" :linux-amd64
                      #{"/c/bb/1.13.229/bb"} {:config "/k/bb.edn"})))
  ;; From Fable 5.1's read of cf554a7: status and the plan say one thing of bb - present when the bb
  ;; running it is at or above the matrix's, which the install then skips; else its fix, runnable
  (let [bb-row #(first (status/rows (select-keys matrix [:bb]) "/c" :linux-amd64 #{} {:running {:bb %} :config "/k/bb.edn"}))]
    (is (= {:tool :bb :version "1.13.229" :path "/c/bb/1.13.229/bb" :present? true :running "1.13.229"} (bb-row "1.13.229")))
    (is (:present? (bb-row "1.13.300")))
    (is (= {:tool :bb :version "1.13.229" :path "/c/bb/1.13.229/bb" :present? false
            :fix "bb --config /k/bb.edn toolchain-install --only bb"}
           (bb-row "1.13.226")))
    (is (= ["toolchain folder /c (linux-amd64)"
            "  bb         1.13.229         the bb running this, 1.13.229"
            "  java       25.0.5+8         not installed - bb --config /k/bb.edn toolchain-install --only java"]
           (status/lines "/c" :linux-amd64 (status/rows (select-keys matrix [:bb :java]) "/c" :linux-amd64 #{}
                                                        {:running {:bb "1.13.229"} :config "/k/bb.edn"}))))))

(deftest toolchain-sum-says-what-the-checksums-say-together
  (let [a {:platform :linux-amd64 :file "x.tar.gz" :sha256 "m"}]
    (is (= :matches (:says (install/sum-row a "h" "h"))))
    (is (= :differs (:says (install/sum-row a "h" "p"))))
    (is (= :unpublished (:says (install/sum-row a "h" nil))))
    (is (= ["bb 1.0"
            "  :linux-amd64 {:sha256 \"h\"}  ; x.tar.gz - DIFFERS from the publisher's checksum - not to be pasted, the matrix has ANOTHER"]
           (install/sum-lines :bb "1.0" [(install/sum-row a "h" "p")])))
    (is (str/ends-with? (second (install/sum-lines :bb "1.0" [(install/sum-row (assoc a :sha256 "") "h" "h")]))
                        "the publisher's checksum matches, the matrix has none yet"))))
