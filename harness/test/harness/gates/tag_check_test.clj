(ns harness.gates.tag-check-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.tag-check :as tc]))

(def sha-a "aaaaaaa1111111111111111111111111111111111")
(def sha-b "bbbbbbb2222222222222222222222222222222222")

(deftest the-devlog-heading-and-its-line
  (is (:ok? (tc/devlog-check "# Devlog\n\n## 0.6.2 — the boundary\n\nA workspace made before it lacks x.\n" "0.6.2")))
  (testing "no heading, or a heading with nothing under it"
    (is (not (:ok? (tc/devlog-check "## 0.6.1 — older\n\ntext\n" "0.6.2"))))
    (is (str/includes? (:detail (tc/devlog-check "## 0.6.2 — x\n\n## 2026-10-09\n" "0.6.2")) "no line under it"))
    (is (not (:ok? (tc/devlog-check "## 0.6.2 — x\n" "0.6.2"))))))

(deftest the-readme-names-the-tag-in-code
  (is (:ok? (tc/readme-check "the current one is `0.6.2`, and" "0.6.2")))
  (is (not (:ok? (tc/readme-check "the current one is `0.6.21`" "0.6.2")))))

(deftest the-tree-is-clean-on-a-plan-branch-or-main
  (is (:ok? (tc/tree-check "plan-v6.2" "")))
  (is (:ok? (tc/tree-check "main" nil)))
  (is (str/includes? (:detail (tc/tree-check "lessons-1" "")) "not a `plan-v*`"))
  (is (str/includes? (:detail (tc/tree-check "main" " M bb.edn\n?? new.clj")) "bb.edn, new.clj")))

(deftest health-records-fresh-for-both-platforms
  (let [fresh (fn [p] {:platform p :sha sha-a :dirty? false :changed []})]
    (is (:ok? (tc/health-check [(fresh "macos-arm64") (fresh "linux-arm64")])))
    (is (= #{} (tc/stale-platforms [(fresh "macos-arm64") (fresh "linux-arm64")])))
    (testing "a platform missing"
      (is (str/includes? (:detail (tc/health-check [(fresh "macos-arm64")])) "no record for linux-arm64"))
      (is (= #{"linux-arm64"} (tc/stale-platforms [(fresh "macos-arm64")]))))
    (testing "code it exercises changed since, or recorded dirty"
      (let [stale (assoc (fresh "linux-arm64") :changed ["harness/src/a.clj"])]
        (is (str/includes? (:detail (tc/health-check [(fresh "macos-arm64") stale])) "linux-arm64 (on aaaaaaa) is stale: harness/src/a.clj"))
        (is (= #{"linux-arm64"} (tc/stale-platforms [(fresh "macos-arm64") stale]))))
      (is (= #{"macos-arm64"} (tc/stale-platforms [(assoc (fresh "macos-arm64") :dirty? true) (fresh "linux-arm64")]))))))

(deftest the-gates-and-docker-gates-records-for-head
  (let [green {:exit 0 :tree "T" :tree-after "T"}]
    (is (:ok? (tc/gates-check green "T")))
    (is (not (:ok? (tc/gates-check green "U"))))
    (is (not (:ok? (tc/gates-check (assoc green :exit 1 :step "lint") "T"))))
    (is (not (:ok? (tc/gates-check nil "T")))))
  (is (:ok? (tc/docker-gates-check {:commit sha-a :exit 0} sha-a)))
  (is (str/includes? (:detail (tc/docker-gates-check {:commit sha-b :exit 0} sha-a)) "for bbbbbbb, not HEAD"))
  (is (not (:ok? (tc/docker-gates-check {:commit sha-a :exit 2} sha-a))))
  (is (not (:ok? (tc/docker-gates-check nil sha-a)))))

(deftest a-tag-from-ls-remote
  (testing "an annotated tag is read at its peeled commit"
    (is (= sha-b (tc/peeled (str sha-a "\trefs/tags/0.6.1\n" sha-b "\trefs/tags/0.6.1^{}\n") "0.6.1"))))
  (testing "a lightweight tag at its own line"
    (is (= sha-a (tc/peeled (str sha-a "\trefs/tags/kit-v1.2\n") "kit-v1.2"))))
  (is (nil? (tc/peeled "" "0.6.1"))))

(deftest the-tag-absent-and-the-pin-present
  (is (:ok? (tc/tag-absent-check "0.6.2" nil "")))
  (is (str/includes? (:detail (tc/tag-absent-check "0.6.2" sha-a "")) "already exists here"))
  (is (str/includes? (:detail (tc/tag-absent-check "0.6.2" nil (str sha-a "\trefs/tags/0.6.2"))) "on origin"))
  (is (:ok? (tc/pin-check {:git/tag "kit-v1.2" :git/sha sha-a} sha-a)))
  (is (str/includes? (:detail (tc/pin-check {:git/tag "kit-v1.2" :git/sha sha-a} nil)) "not on the fork"))
  (is (str/includes? (:detail (tc/pin-check {:git/tag "kit-v1.2" :git/sha sha-a} sha-b)) "the fork's `kit-v1.2` is bbbbbbb")))

(deftest landed-is-origin-tag-and-main-at-the-commit
  (let [ls (fn [tag-sha main-sha] (str "x\trefs/tags/0.6.2\n" tag-sha "\trefs/tags/0.6.2^{}\n" main-sha "\trefs/heads/main\n"))]
    (is (:ok? (tc/landed-check "0.6.2" sha-a (ls sha-a sha-a))))
    (is (str/includes? (:detail (tc/landed-check "0.6.2" sha-a (ls sha-a sha-b))) "origin's `main` is bbbbbbb"))
    (is (str/includes? (:detail (tc/landed-check "0.6.2" sha-a (str sha-a "\trefs/heads/main\n"))) "is absent"))
    (is (not (:ok? (tc/landed-check "0.6.2" nil (ls sha-a sha-a)))))))

(deftest a-record-from-a-local-clone-of-the-template-is-refused
  (let [fresh (fn [p] {:platform p :sha sha-a :dirty? false :changed []})
        local (assoc (fresh "macos-arm64") :local-root "/src/clojure-stack-lite")]
    (is (not (:ok? (tc/health-check [local (fresh "linux-arm64")]))))
    (is (str/includes? (:detail (tc/health-check [local (fresh "linux-arm64")]))
                       "macos-arm64 (on aaaaaaa) is stale: recorded from a LOCAL CLONE of the template (/src/clojure-stack-lite), not the pin"))
    (is (= #{"macos-arm64"} (tc/stale-platforms [local (fresh "linux-arm64")])) "and --run records it again")))

(deftest a-record-is-stale-by-the-set-when-its-tools-differ-from-the-macs
  ;; plan 7.0, item 8.3f: the set is the Mac's health record, so the rule is a comparison of two
  ;; records, no history - row 127's two halves still hold: a re-date is no move, and a run that
  ;; moves the set is evidence for it (From Fable 5.1's read of 4eab9df)
  (let [mac {:bb "1.13.229" :clj-kondo "2026.10.26" :java "25.0.5" :git "2.57.0"}]
    (is (not (tc/set-stales? mac mac)) "the Mac's record, being the set: never")
    (is (not (tc/set-stales? (assoc mac :git "2.43.0") mac)) "a Linux record equal over the pinned tools: fresh - its git apart")
    (is (tc/set-stales? (assoc mac :java "25.0.4") mac) "a record on another JDK than the set's: stale, since 8.3a")
    (is (tc/set-stales? (assoc mac :bb "1.13.228") mac) "a record one patch behind the set: stale")
    (is (not (tc/set-stales? mac nil)) "no set to compare with: not stale by it")
    (is (false? (tc/set-stales? mac mac)) "a boolean")))

(deftest the-records-are-read-against-the-macs-and-a-re-date-is-no-move
  (let [root (str (fs/create-temp-dir))
        records (fs/path root "harness" "health" "records")
        rec (fn [platform as-of tools] {:health/as-of as-of :platform {:key platform :label platform}
                                        :kit {:sha "HEAD" :dirty? false} :tools tools})
        write! (fn [r] (spit (str (fs/path records (str (get-in r [:platform :key]) ".edn"))) (pr-str r)))
        git! #(apply p/shell {:dir root :out :string :err :string} "git" %&)]
    (fs/create-dirs records)
    (git! "init" "-q")
    (write! (rec "macos-arm64" "2026-10-30" {:bb "1.13.229" :java "25.0.5"}))
    (write! (rec "linux-arm64" "2026-10-12" {:bb "1.13.229" :java "25.0.5"}))
    (git! "add" "-A")
    (git! "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "records")
    (is (= [[] []] (map :changed (tc/read-records root)))
        "the Linux record dated before the Mac's, the same tools: fresh")
    (write! (rec "macos-arm64" "2026-10-31" {:bb "1.13.230" :java "25.0.5"}))
    (is (= {"linux-arm64" ["harness/health/records/macos-arm64.edn"] "macos-arm64" []}
           (into {} (map (juxt :platform :changed)) (tc/read-records root)))
        "the Mac's moved past it: the Linux record stale, the reason the set's record")
    (testing "a pin moved and the Linux run recorded first: the Mac's is the stale one, the Linux record fresh"
      ;; From Fable 5.1's read of 836255d: a set about to be rewritten stales nothing
      (let [before (str/trim (:out (git! "rev-parse" "HEAD")))]
        (fs/create-dirs (fs/path root "tools"))
        (spit (str (fs/path root "tools" "matrix.edn")) "{:bb \"1.13.231\"}\n")
        (git! "add" "-A")
        (git! "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "the pin moved")
        (write! (assoc-in (rec "macos-arm64" "2026-10-31" {:bb "1.13.230" :java "25.0.5"}) [:kit :sha] before))
        (write! (rec "linux-arm64" "2026-11-01" {:bb "1.13.231" :java "25.0.5"}))
        (is (= {"linux-arm64" [] "macos-arm64" ["tools/matrix.edn"]}
               (into {} (map (juxt :platform :changed)) (tc/read-records root))))))))

(deftest the-pins-are-what-the-health-runs-recorded
  ;; the person, 2026-10-29: "The KIT that an Adopter gets should have matrix = known-good versions
  ;; (with the Java exception noted)"; 2026-10-30, the exception folded in: "(a) fold java in"
  (let [pins {:bb {:version "1.13.229"} :clj-kondo {:version "2026.10.26"} :clojure {:version "1.12.6.1673"}
              :cljfmt {:version "0.16.6"} :git {:version "2.56.0"} :java {:version "25.0.5+8"}
              :clojure-mcp-light {:version "d341c23"}}
        good {:bb "1.13.229" :clj-kondo "2026.10.26" :clojure "1.12.6.1673" :cljfmt "0.16.6"
              :clj-nrepl-eval "d341c23" :git "2.57.0" :java "25.0.5"}]
    (is (:ok? (tc/versions-check pins good))
        "the JDK's build no difference (25.0.5 is 25.0.5+8); git not compared, whatever either side says")
    (is (str/includes? (:detail (tc/versions-check pins (assoc good :java "21.0.12.1"))) "java pinned 25.0.5+8, recorded 21.0.12.1")
        "the JDK compared since 8.3a")
    (let [c (tc/versions-check (assoc-in pins [:bb :version] "1.13.230") good)]
      (is (not (:ok? c)))
      (is (= "bb pinned 1.13.230, recorded 1.13.229 - a health run records the pins: `bb tag-check <tag> --run`" (:detail c))))
    (is (str/includes? (:detail (tc/versions-check (assoc-in pins [:clojure-mcp-light :version] "aaaaaaa") good)) "clojure-mcp-light pinned aaaaaaa, recorded d341c23"))
    (is (not (str/includes? (:detail (tc/versions-check pins good)) "git")) "git is no pin since 8.2e")))
