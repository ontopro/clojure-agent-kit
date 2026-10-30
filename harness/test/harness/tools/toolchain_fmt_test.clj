(ns harness.tools.toolchain-fmt-test
  "The toolchain pack's `fmt` and `fmt-check` (`tools/toolchain/src/toolchain_fmt.clj`, plan 7.0, item
  8.2a): cljfmt's binary at the matrix's version, each path held to the first config from its own
  folder up, else `--dir`'s - the search and the runs as data, then the tasks run as the gates run
  them, `bb --config <pack>`, in cljfmt's own words and exits."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.repair :as repair]
   [toolchain-fmt :as fmt]))

(defn- pack [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
         "bb" "--config" (repair/toolchain-pack) args))

(def ^:private padded "(ns a)\n\n(def m {:a  1})\n")

(deftest the-config-is-cljfmts-own-search-from-the-path-else-from-dir
  (let [root (fs/create-temp-dir)
        proj (fs/path root "proj")]
    (fs/create-dirs (fs/path proj "src" "deep"))
    (fs/create-dirs (fs/path root "sibling" "src"))
    (fs/create-dirs (fs/path proj "own"))
    (spit (str (fs/path proj ".cljfmt.edn")) "{}\n")
    (spit (str (fs/path proj "own" "cljfmt.edn")) "{}\n")
    (spit (str (fs/path proj "own" ".cljfmt.clj")) "{}\n")
    (is (= (str (fs/path proj ".cljfmt.edn")) (fmt/config-file (str proj) "src/deep")) "found walking up")
    (is (= (str (fs/path proj ".cljfmt.edn")) (fmt/config-file (str proj) "../sibling/src"))
        "a folder with none of its own up to the root: --dir's")
    (is (= (str (fs/path proj "own" "cljfmt.edn")) (fmt/config-file (str proj) "own"))
        "a folder's own, by either name - never the .clj form, which cljfmt reads only when told")
    (is (nil? (fmt/config-file (str (fs/path root "sibling")) "src")) "none anywhere: cljfmt's defaults")
    (testing "one run per config, the paths in the order given, from --dir"
      (is (= [["cljfmt" "check" "--config" (str (fs/path proj ".cljfmt.edn")) "src" "../sibling/src"]
              ["cljfmt" "check" "--config" (str (fs/path proj "own" "cljfmt.edn")) "own"]]
             (fmt/runs "cljfmt" :check (str proj) ["src" "own" "../sibling/src"])))
      (is (= [["cljfmt" "fix" "a.clj"]] (fmt/runs "cljfmt" :fix (str (fs/path root "sibling" "src")) ["a.clj"]))
          "no config: no --config, cljfmt's defaults"))))

(deftest each-file-is-held-to-the-config-found-from-its-folder-else-the-dirs
  (let [root (fs/create-temp-dir)
        proj (fs/path root "proj")
        sibling (fs/path root "sibling" "src")
        own (fs/path proj "own")]
    (fs/create-dirs (fs/path proj "src"))
    (fs/create-dirs sibling)
    (fs/create-dirs own)
    (spit (str (fs/path proj ".cljfmt.edn")) "{:remove-multiple-non-indenting-spaces? true}\n")
    (spit (str (fs/path own ".cljfmt.edn")) "{}\n")
    (doseq [d [(fs/path proj "src") sibling own]] (spit (str (fs/path d "a.clj")) padded))
    (let [{:keys [exit out err]} (pack proj "fmt-check" "--dir" "." "src" "../sibling/src" "own")]
      (is (= 1 exit))
      (is (str/includes? err "src/a.clj has incorrect formatting") "the project's config")
      (is (str/includes? err "../sibling/src/a.clj has incorrect formatting")
          "a folder with no config of its own: --dir's, as the fmt gate held it")
      (is (not (str/includes? err "own/a.clj")) "a nearer config wins: its own, cljfmt's defaults")
      (is (str/includes? err "2 file(s) formatted incorrectly"))
      (is (str/includes? out "-(def m {:a  1})") "the diff on stdout, as cljfmt prints it"))))

(deftest fmt-fixes-and-fmt-check-says-it-in-cljfmts-words
  (let [d (fs/create-temp-dir)]
    (fs/create-dirs (fs/path d "src"))
    (spit (str (fs/path d "src" "a.clj")) "(ns a)\n(defn f [x]\n      (+ x\n  1))\n")
    (is (= 1 (:exit (pack d "fmt-check" "src"))) "--dir defaults to the folder it runs in")
    (let [{:keys [exit err]} (pack d "fmt" "--dir" "." "src/a.clj")]
      (is (zero? exit))
      (is (str/includes? err "Reformatted src/a.clj")))
    (is (= "(ns a)\n(defn f [x]\n  (+ x\n     1))\n" (slurp (str (fs/path d "src" "a.clj")))))
    (let [{:keys [exit err]} (pack d "fmt-check" "src")]
      (is (zero? exit))
      (is (str/includes? err "All source files formatted correctly")))
    (testing "a file that cannot be read: exit 2, named"
      (spit (str (fs/path d "src" "b.clj")) "(ns b)\n(defn f [] (\n")
      (let [{:keys [exit err]} (pack d "fmt-check" "src")]
        (is (= 2 exit))
        (is (str/includes? err "Failed to format file: src/b.clj"))
        (is (str/includes? err "1 file(s) could not be parsed for formatting"))))
    (testing "no path: refused, exit 2"
      (let [{:keys [exit err]} (pack d "fmt-check")]
        (is (= 2 exit))
        (is (str/includes? err "name the paths"))))))
