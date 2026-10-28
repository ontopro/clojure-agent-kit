(ns harness.setup.project-set-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.setup.project-set :as ps]))

;; the template's own files at kit-v1.3, cut to the lines the KIT rewrites and their neighbours
(def ^:private mise
  (str "# check, lint, tests, and the dev system booted and served. Newer versions are expected\n"
       "# to work and are not a fault. The one real constraint is the JDK: stay on 21 (XTDB,\n"
       "# an optional store, documents a minimum of 21 and its early v2 releases failed at\n"
       "# class-load on newer JDKs). Babashka must be 1.12.212 or newer for bbin.\n"
       "[tools]\n"
       "java = \"temurin-21\"\n"
       "clojure = \"1.12.6.1673\"\n"
       "tailwindcss = \"4.3.3\"\n"
       "\n[alias]\n"))

(def ^:private dockerfile
  (str "FROM clojure:temurin-21-tools-deps-1.12.1.1550-alpine AS build\n\n"
       "WORKDIR /app\n\n"
       "ARG BB_VERSION=1.12.206\n"
       "ARG TAILWIND_VERSION=v4.1.11\n\n"
       "FROM eclipse-temurin:21.0.2_13-jre-alpine\n"
       "CMD [\"java\", \"-Xmx256m\", \"-jar\", \"standalone.jar\"]\n"))

(def ^:private the-set
  {:java "temurin-25"
   :build-image "clojure:temurin-25-tools-deps-1.12.6.1673-alpine"
   :runtime-image "eclipse-temurin:25.0.4.1_1-jre-alpine"
   :tailwindcss "4.3.3"})

(deftest the-shipped-set-is-the-jdk-25-set
  (let [s (ps/load-set)]
    (is (= "temurin-25" (:java s)))
    (is (str/starts-with? (:build-image s) "clojure:temurin-25-tools-deps-"))
    (is (str/starts-with? (:runtime-image s) "eclipse-temurin:25."))
    (is (re-matches #"\d+\.\d+\.\d+" (:tailwindcss s)))))

(deftest the-java-line-is-25-with-the-kits-note-and-the-stay-on-21-sentence-gone
  (let [{:keys [text missed]} (ps/with-java mise "temurin-25")]
    (is (= [] missed))
    (is (str/includes? text (str ps/java-note "java = \"temurin-25\"\n")))
    (is (not (str/includes? text "stay on 21")))
    (is (str/includes? text "The JDK is 21 and newer, 25 the default (the java line below). Babashka must"))
    (is (str/includes? text "clojure = \"1.12.6.1673\"") "the other lines kept")
    (is (every? #(str/starts-with? % "#") (take-while #(not= "[tools]" %) (str/split-lines text)))
        "the comment still a comment")
    (testing "written twice is written once"
      (is (= text (:text (ps/with-java text "temurin-25")))))
    (testing "a later set moves the line, the note kept once"
      (let [t (:text (ps/with-java text "temurin-27"))]
        (is (str/includes? t "java = \"temurin-27\""))
        (is (= 1 (count (re-seq #"THE JDK, WRITTEN BY THE KIT" t))))))
    (testing "no java line: said, nothing written"
      (is (= {:text "[tools]\nclojure = \"1\"\n" :missed ["the java line under [tools]"]}
             (ps/with-java "[tools]\nclojure = \"1\"\n" "temurin-25"))))))

(deftest the-dockerfile-takes-both-images-and-the-tailwind-version
  (let [{:keys [text missed]} (ps/with-images dockerfile the-set)]
    (is (= [] missed))
    (is (str/includes? text "FROM clojure:temurin-25-tools-deps-1.12.6.1673-alpine AS build\n") "the stage's name kept")
    (is (str/includes? text "FROM eclipse-temurin:25.0.4.1_1-jre-alpine\n"))
    (is (str/includes? text "ARG TAILWIND_VERSION=v4.3.3\n"))
    (is (str/includes? text "ARG BB_VERSION=1.12.206") "what the set does not name kept")
    (is (= text (:text (ps/with-images text the-set))) "written twice is written once")
    (testing "a project's own FROM is kept, and said"
      (let [r (ps/with-images "FROM ubuntu:24.04 AS build\nFROM eclipse-temurin:21-jre\n" the-set)]
        (is (= "FROM ubuntu:24.04 AS build\nFROM eclipse-temurin:25.0.4.1_1-jre-alpine\n" (:text r)))
        (is (= ["the build stage's FROM clojure:temurin-..." "ARG TAILWIND_VERSION"] (:missed r)))))))

(deftest the-gitignore-gains-the-local-mise-file-once
  (is (= "/target\n.mise.local.toml\n" (:text (ps/with-ignore "/target\n"))))
  (is (= "/target\n.mise.local.toml\n" (:text (ps/with-ignore "/target"))) "a last line with no newline")
  (is (= "/target\n.mise.local.toml\n" (:text (ps/with-ignore "/target\n.mise.local.toml\n")))))

(deftest write-says-what-changed-and-what-it-could-not-find
  (let [app (str (fs/create-temp-dir {:prefix "project-set"}))]
    (try
      (spit (str (fs/path app ".mise.toml")) mise)
      (spit (str (fs/path app "Dockerfile")) dockerfile)
      (is (= {:changed [".mise.toml" "Dockerfile"] :missed [".gitignore: none in the application"]} (ps/write! app the-set)))
      (spit (str (fs/path app ".gitignore")) "/target\n")
      (is (= {:changed [".gitignore"] :missed []} (ps/write! app the-set)) "the rest already written")
      (is (= {:changed [] :missed []} (ps/write! app the-set)))
      (finally (fs/delete-tree app)))))
