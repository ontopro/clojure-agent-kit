(ns harness.tools.toolchain-env-test
  "The toolchain pack's `toolchain-env` (`tools/toolchain/src/toolchain_env.clj`, plan 7.0, items
  8.3c and 8.6a): the `.env` checked before the shell sources it, the pin read as the doctor reads it,
  the JDK looked for in order, the lines to eval - never a value. Tried by hand in a scratch workspace:
  a local pin of 21 over the project's 25, the keys loaded by the shell, a `$(...)` line refused with
  nothing on stdout."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.doctor :as doctor]
   [toolchain-env :as env]))

(deftest the-env-file-is-checked-before-the-shell-sources-it
  (let [ok (env/check-env (str "# a comment\n\nA_KEY=bare-value\nB='single quoted, spaces'\n"
                               "C=\"double quoted\"\nEMPTY=\n"))]
    (is (:ok? ok))
    (is (= ["A_KEY" "B" "C" "EMPTY"] (:names ok)) "an empty value is taken - the template's state"))
  (testing "refused by line number, the value never in the reason"
    (let [{:keys [ok? refused]} (env/check-env (str "export A=1\nB\n1X=2\nD=$(touch /tmp/x)\nE=`id`\n"
                                                    "F=a b\nG='it's'\nH=\"$HOME\"\nI=x;rm\n"))]
      (is (not ok?))
      (is (= [1 2 3 4 5 6 7 8 9] (map :line refused)))
      (is (= "an `export` keyword - write NAME=value alone" (:why (first refused))))
      (is (= "no `=` - a line is NAME=value" (:why (second refused))))
      (is (not-any? #(re-find #"touch|id|rm|HOME" (:why %)) refused) "no value echoed"))))

(deftest the-pin-is-read-as-the-doctor-reads-it
  ;; the pack cannot require the doctor, so a test holds the two readers to one answer
  (let [check (fn [files nested?]
                (let [d (fs/create-temp-dir)]
                  (doseq [[f text] files] (spit (str (fs/path d f)) text))
                  (let [at (if nested? (doto (fs/path d "a" "b") fs/create-dirs) d)]
                    [(env/java-pin at) (get-in (doctor/mise-pins (str at)) [:java :major])])))]
    (doseq [[label files nested?] [["the project's file" {".mise.toml" "[tools]\njava = \"temurin-25\"\n"} false]
                                   ["the local one over it" {".mise.toml" "[tools]\njava = \"temurin-25\"\n"
                                                             ".mise.local.toml" "[tools]\njava = \"temurin-21\"\n"} false]
                                   ["found walking up" {".mise.toml" "[tools]\njava = \"21\"\nclojure = \"1.12\"\n"} true]]]
      (let [[ours doctors] (check files nested?)]
        (is (= doctors ours) label)
        (is (some? ours) label)))
    (is (nil? (env/java-pin (fs/create-temp-dir))) "no file up: no pin")))

(deftest in-a-workspace-the-pin-is-the-applications
  (let [ws (fs/create-temp-dir)]
    (spit (str (fs/path ws "workspace.edn")) "{:workspace/app \"xyx-app\"}")
    (fs/create-dirs (fs/path ws "xyx-app"))
    (spit (str (fs/path ws "xyx-app" ".mise.toml")) "[tools]\njava = \"temurin-21\"\n")
    (is (= 21 (env/java-pin (env/pin-dir (str ws)))))
    (spit (str (fs/path ws ".env")) "K=\n")
    (is (= (str (fs/path ws ".env")) (:file (env/env-file (str ws) "/no-kit"))) "the workspace's .env")
    (is (nil? (:file (env/env-file (str (fs/create-temp-dir)) "/no-kit"))) "neither: no line")))

(deftest the-jdk-is-the-first-lookup-that-finds-the-major
  (let [lookups [["the toolchain cache" (constantly nil)]
                 ["macOS's java_home" (fn [m] (str "/jdk/" m))]
                 ["jenv" (fn [m] (str "/jenv/" m))]]
        home? (fn [h m] (and h (str/ends-with? h (str m))))]
    (is (= {:home "/jdk/25" :from "macOS's java_home"} (env/find-jdk 25 lookups home?)))
    (is (nil? (env/find-jdk 25 lookups (constantly false))) "none of that major: nil, the install named")
    (testing "a home is a JDK of the major only when its own release file says so"
      (let [h (fs/create-temp-dir)]
        (fs/create-dirs (fs/path h "bin"))
        (spit (str (fs/path h "bin" "java")) "#!/bin/sh\n")
        (fs/set-posix-file-permissions (fs/path h "bin" "java") "rwxr-xr-x")
        (spit (str (fs/path h "release")) "JAVA_VERSION=\"25.0.5\"\n")
        (is (env/jdk-home? (str h) 25))
        (is (not (env/jdk-home? (str h) 21)))))))

(deftest the-lines-carry-paths-never-a-value
  (is (= ["export JAVA_HOME='/j d'" "export PATH='/j d/bin':\"$PATH\"" "set -a; . '/w/.env'; set +a"]
         (env/env-lines "/j d" "/w/.env")))
  (is (= 2 (count (env/env-lines "/j" nil))) "no .env: no keys line")
  (is (= "'it'\\''s'" (env/sq "it's")) "a quote in a path stays a path"))
