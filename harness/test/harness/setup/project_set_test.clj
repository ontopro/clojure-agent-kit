(ns harness.setup.project-set-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.setup.project-set :as ps]
            [harness.setup.template-fixture :as fixture]))

(def ^:private the-set
  {:tools {:java "temurin-25" :clojure "1.12.6.1673" :babashka "1.13.226"
           :clj-kondo "2026.08.04" :cljfmt "0.16.6" :tailwindcss "4.3.3"}
   :build-image "clojure:temurin-25-tools-deps-1.12.6.1673-alpine"
   :runtime-image "eclipse-temurin:25.0.4.1_1-jre-alpine"})

(deftest the-shipped-set-is-the-whole-toolchain-on-jdk-25
  (let [{:keys [tools build-image runtime-image]} (ps/load-set)]
    (is (= #{:java :clojure :babashka :clj-kondo :cljfmt :tailwindcss} (set (keys tools)))
        "every line the template's [tools] carries")
    (is (= "temurin-25" (:java tools)))
    (is (= (str "clojure:temurin-25-tools-deps-" (:clojure tools) "-alpine") build-image)
        "the build image's Clojure CLI is the set's")
    (is (str/starts-with? runtime-image "eclipse-temurin:25."))))

(deftest every-tools-line-is-the-kits-with-the-note-above-the-table
  (let [{:keys [text missed]} (ps/with-tools fixture/mise (:tools the-set))]
    (is (= [] missed))
    (is (str/includes? text (str "you do not need to run it.\n#\n" ps/tools-note "[tools]\n"
                                 "java = \"temurin-25\"\nclojure = \"1.12.6.1673\"\nbabashka = \"1.13.226\"\n"
                                 "clj-kondo = \"2026.08.04\"\ncljfmt = \"0.16.6\"\ntailwindcss = \"4.3.3\"\n")))
    (is (str/includes? text "cljfmt = \"asdf:https://github.com/b-social/asdf-cljfmt\"") "the [alias] table untouched")
    (is (not (str/includes? text "stay on 21")))
    (is (not (str/includes? text "2026-09-21")) "the template's known-good sentence replaced")
    (is (str/starts-with? text (str "# A KNOWN-GOOD SET, not a set of requirements: the versions under [tools] are the KIT's,\n"
                                    "# run together through its gates and `bb health` (its `known-good.edn`), and written here\n"
                                    "# by `bb init` and `bb upgrade`. Newer versions are expected\n")))
    (is (str/includes? text "fault. The JDK is 21 and newer, 25 the default\n# (the java line below). Babashka must"))
    (is (every? #(str/starts-with? % "#") (take-while #(not= "[tools]" %) (str/split-lines text)))
        "the comment still a comment")
    (is (every? #(<= (count %) 100) (str/split-lines text)) "the paragraph keeps its width")
    (testing "written twice is written once"
      (is (= {:text text :missed []} (ps/with-tools text (:tools the-set)))))
    (testing "a later set moves the lines, the note kept once"
      (let [t (:text (ps/with-tools text (assoc (:tools the-set) :java "temurin-27")))]
        (is (str/includes? t "java = \"temurin-27\""))
        (is (= 1 (count (re-seq #"THE VERSIONS UNDER \[tools\] ARE THE KIT'S" t))))))))

(deftest what-the-mise-file-lacks-is-said-never-added
  (testing "a tool with no line"
    (let [r (ps/with-tools (str/replace fixture/mise "cljfmt = \"0.16.5\"\n" "") (:tools the-set))]
      (is (= ["the cljfmt line under [tools]"] (:missed r)))
      (is (not (str/includes? (:text r) "cljfmt = \"0.16.6\"")))))
  (testing "the template's sentence reworded: said, so a template release that rewrites it is seen"
    (let [r (ps/with-tools (str/replace fixture/mise "stay on 21" "keep to 21") (:tools the-set))]
      (is (= ["the template's sentence on the JDK"] (:missed r)))))
  (testing "the known-good sentence reworded: said too"
    (let [r (ps/with-tools (str/replace fixture/mise "run together on" "tried together on") (:tools the-set))]
      (is (= ["the template's sentence on the known-good set"] (:missed r)))))
  (testing "no [tools] table: said, nothing written"
    (is (= {:text "[alias]\n" :missed ["the [tools] table"]} (ps/with-tools "[alias]\n" (:tools the-set))))))

(deftest the-dockerfile-takes-both-images-and-the-bb-and-tailwind-versions
  (let [{:keys [text missed]} (ps/with-images fixture/dockerfile the-set)]
    (is (= [] missed))
    (is (str/includes? text "FROM clojure:temurin-25-tools-deps-1.12.6.1673-alpine AS build\n") "the stage's name kept")
    (is (str/includes? text "FROM eclipse-temurin:25.0.4.1_1-jre-alpine\n"))
    (is (str/includes? text "ARG BB_VERSION=1.13.226\n"))
    (is (str/includes? text "ARG TAILWIND_VERSION=v4.3.3\n"))
    (is (= text (:text (ps/with-images text the-set))) "written twice is written once")
    (testing "a project's own FROM is kept, and said"
      (let [r (ps/with-images "FROM ubuntu:24.04 AS build\nFROM eclipse-temurin:21-jre\n" the-set)]
        (is (= "FROM ubuntu:24.04 AS build\nFROM eclipse-temurin:25.0.4.1_1-jre-alpine\n" (:text r)))
        (is (= ["the build stage's FROM clojure:temurin-..." "ARG BB_VERSION" "ARG TAILWIND_VERSION"] (:missed r)))))))

(deftest the-gitignore-gains-the-kits-lines-once
  (let [all "/target\n.mise.local.toml\n.env\n.env-record.edn\n.kit/\n"]
    (is (= all (:text (ps/with-ignore "/target\n"))))
    (is (= all (:text (ps/with-ignore "/target"))) "a last line with no newline")
    (is (= all (:text (ps/with-ignore all))) "written twice is written once"))
  (testing "a line is there bare or rooted, trimmed; nothing is ever removed"
    (is (= {:text "/.env\n .kit/\nx\n.mise.local.toml\n.env-record.edn\n" :added [".mise.local.toml" ".env-record.edn"]}
           (ps/lines-added "/.env\n .kit/\nx\n" ps/app-ignored)))
    (is (= {:text "a\n" :added []} (ps/lines-added "a\n" [])))
    (is (= {:text ".env\n" :added [".env"]} (ps/lines-added "" [".env"])) "an empty file")))

(deftest write-says-what-changed-and-what-it-could-not-find
  (let [app (str (fs/create-temp-dir {:prefix "project-set"}))]
    (try
      (spit (str (fs/path app ".mise.toml")) fixture/mise)
      (spit (str (fs/path app "Dockerfile")) fixture/dockerfile)
      (is (= {:changed [".mise.toml" "Dockerfile"] :missed [".gitignore: none in the application"]} (ps/write! app the-set)))
      (spit (str (fs/path app ".gitignore")) fixture/gitignore)
      (is (= {:changed [".gitignore"] :missed []} (ps/write! app the-set)) "the rest already written")
      (is (= {:changed [] :missed []} (ps/write! app the-set)))
      (finally (fs/delete-tree app)))))
