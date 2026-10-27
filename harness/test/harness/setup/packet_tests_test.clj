(ns harness.setup.packet-tests-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.packet-tests :as pt]))

(deftest a-source-file-names-its-namespace-by-its-path
  (is (= 'app.foo-bar (pt/impl-namespace "src/app/foo_bar.clj")))
  (is (= 'app.views.search (pt/impl-namespace "xyx-app/src/app/views/search.clj")))
  (is (nil? (pt/impl-namespace "resources/app/x.clj")) "no src/ folder, no namespace"))

(deftest a-test-requires-what-its-ns-form-names
  (is (= '#{app.routes app.server clojure.test}
         (pt/required-namespaces "(ns app.routes-test\n  (:require [app.routes :as r] app.server\n   [clojure.test :refer [deftest]]))\n(deftest x)")))
  (is (= '#{app.views.a app.views.b} (pt/required-namespaces "(ns t (:require (app.views [a :as a] b)))"))
      "a prefix list")
  (is (= #{} (pt/required-namespaces "(def x 1)")) "no ns form")
  (is (= #{} (pt/required-namespaces "(ns broken (:require [a")) "unreadable"))

(deftest the-targets-routes-are-paths-not-file-paths
  (is (= ["/search" "/search/go"]
         (pt/target-routes ["GET `/search?q=x` answers 200, and `/search/go` redirects; `/search` again"
                            "the file `src/app/search.clj` and `resources/icons/x.svg` are not routes"
                            "https://example.com/path is a URL, and / alone is the root"]))))

(deftest a-test-reads-the-packet-by-its-namespace-or-a-route-it-names
  (let [spec {:files/impl ["src/app/routes.clj"]
              :files/test ["test/app/routes_test.clj"]
              :property-targets ["`/search` answers 200"]}
        tests [["test/app/routes_test.clj" "(ns app.routes-test (:require [app.routes :as r]))"]
               ["test/app/server_test.clj" "(ns app.server-test (:require [app.server]))\n(get \"/search?q=a\")"]
               ["test/app/pages_test.clj" "(ns app.pages-test (:require [app.pages]))\n(get \"/search/suggest\")"]
               ["test/app/other_test.clj" "(ns app.other-test)"]]]
    (is (= [{:file "test/app/routes_test.clj" :requires '[app.routes] :routes [] :own? true}
            {:file "test/app/server_test.clj" :requires [] :routes ["/search"] :own? false}]
           (pt/readers spec tests))
        "a longer path under the route is another route; a test reading neither is not listed")
    (testing "the report names each, says the string match, and what to do"
      (let [r (pt/report "t-05" "docs/stages/stage-4/stage-4-blueprint.md" spec (pt/readers spec tests))]
        (is (str/starts-with? r "packet-tests t-05 (docs/stages/stage-4/stage-4-blueprint.md): 2 test files read what it changes"))
        (is (str/includes? r "  test/app/routes_test.clj - requires app.routes (the packet's own :files/test)"))
        (is (str/includes? r "  test/app/server_test.clj - names \"/search\""))
        (is (str/includes? r "a string match"))))
    (is (= "packet-tests t-05 (bp): no test file requires app.routes or names /search\nRoutes from the targets: /search - a string match: a test naming a path for another reason is listed too."
           (pt/report "t-05" "bp" spec [])))))

(deftest the-latest-stage-carrying-the-task-is-read
  (let [build (fs/create-temp-dir)
        bp (fn [id body] (let [f (fs/path build "docs" "stages" (str "stage-" id) (str "stage-" id "-blueprint.md"))]
                           (fs/create-dirs (fs/parent f))
                           (spit (str f) body)))]
    (bp 1 "# B\n\n```clojure\n{:task/id \"t-01\" :files/impl [\"src/a/one.clj\"]}\n```\n")
    (bp 2 "# B\n\n```clojure\n{:task/id \"t-01\" :files/impl [\"src/a/two.clj\"]}\n```\n")
    (is (= {:stage 2 :spec {:task/id "t-01" :files/impl ["src/a/two.clj"]}}
           (dissoc (pt/packet-in build "t-01") :blueprint))
        "a task id a later stage reuses is that stage's")
    (is (nil? (pt/packet-in build "t-09")))
    (fs/delete-tree build)))
