(ns harness.gates.tag-check-test
  (:require
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
