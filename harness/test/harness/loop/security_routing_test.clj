(ns harness.loop.security-routing-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.loop.security-routing :as routing]))

(deftest a-finding-s-where-names-its-files
  (is (= ["src/app/web.clj" "src/app/accounts.clj"]
         (routing/named-files "src/app/web.clj: login!; src/app/accounts.clj: find-by-username; src/app/web.clj")))
  (is (= ["resources/migrations/0002.up.sql"] (routing/named-files "resources/migrations/0002.up.sql")))
  (is (= [] (routing/named-files nil))))

(deftest the-architecture-s-security-sections-are-cut-out-whole
  (let [md (str "# Part 1\n\n## 3. Shape\nshape text\n\n## 4. Security, as given\ngiven text\n### a sub-heading\nkept\n\n"
                "## 5. Layers\nlayers\n\n# Part 2\n\n## 8. Security choices\nchosen\n\n## 15. Threat model\nthreats\n\n## 16. Summary\nno")]
    (is (= "## 4. Security, as given\ngiven text\n### a sub-heading\nkept\n\n## 8. Security choices\nchosen\n\n## 15. Threat model\nthreats"
           (routing/sections md routing/security-sections)))))

(deftest a-fix-is-tested-beside-the-file-it-changes
  (is (= "test/app/web_security_test.clj" (routing/test-path "src/app/web.clj")))
  (is (nil? (routing/test-path nil))))

(deftest a-draft-fills-what-triage-named-and-marks-the-rest
  (let [d (routing/fix-draft 1 {:title "Logout does not end the session" :why "a copy still works" :test "test/app/x_test.clj"}
                             {:reason "02 §8 says it ends" :impl "src/app/web.clj" :target "After POST /logout the old cookie is no login"}
                             "/kept/test/app/x_test.clj")]
    (is (str/starts-with? d "### fix-01 — Logout does not end the session"))
    (is (str/includes? d ":task/id          \"fix-01-logout-does-not-end-the-session\""))
    (is (str/includes? d ":files/impl       [\"src/app/web.clj\"]"))
    (is (str/includes? d ":files/test       [\"test/app/web_security_test.clj\"]"))
    (is (str/includes? d ":property-targets [\"After POST /logout the old cookie is no login\"]"))
    (is (str/includes? d ":interfaces [<the functions") "the slice is the Architect's to fill")
    (is (str/includes? d "(kept at `/kept/test/app/x_test.clj`)"))
    (is (str/includes? d "It is not given to the Tester")))
  (testing "a coder route that named nothing leaves marks, not blanks"
    (let [d (routing/fix-draft 2 {:title "t" :why "w" :test "x"} {:reason "r"} "k")]
      (is (str/includes? d "[\"<the one file the fix changes>\"]"))
      (is (str/includes? d "[\"<its security test>\"]"))
      (is (str/includes? d "<the promise the fix must keep")))))

(defn- review-dir
  "A review record with three findings and its tests beside it, and a clone holding one named file."
  []
  (let [dir (fs/create-temp-dir {:prefix "kit-security-route"})
        clone (fs/path dir "clone")
        record (fs/path dir "rev-1.edn")]
    (fs/create-dirs (fs/path clone "src" "app"))
    (spit (str (fs/path clone "src" "app" "web.clj")) "(ns app.web)")
    (fs/create-dirs (fs/path dir "rev-1-tests" "test" "app"))
    (spit (str (fs/path dir "rev-1-tests" "test" "app" "a_test.clj")) "(deftest a)")
    (spit (str record)
          (pr-str {:findings [{:title "A" :kind :reproduced :test "test/app/a_test.clj" :where "src/app/web.clj: f" :why "wa"}
                              {:title "B" :kind :reproduced :test "test/app/b_test.clj" :where "src/app/none.clj" :why "wb"}
                              {:title "C" :kind :hypothesis :where "src/app/web.clj" :why "wc"}]
                   :tests ["test/app/a_test.clj"]}))
    {:dir (str dir) :clone (str clone) :record (str record)}))

(deftest reproduced-findings-are-routed-and-a-hypothesis-is-not
  (let [{:keys [dir clone record]} (review-dir)
        seen (atom [])
        triage-fn (fn [t]
                    (swap! seen conj t)
                    (if (= "A" (get-in t [:payload :finding :title]))
                      {:route :coder :reason "ra" :impl "src/app/web.clj" :target "ta" :prompt "p" :answer "a"}
                      {:route :architect :reason "rb" :guidance "gb"}))
        r (routing/route! {:review-file record :clone clone :architecture "## 4. arch" :triage-fn triage-fn :out dir})]
    (is (= 2 (count @seen)) "one triage call per reproduced finding, none for the hypothesis")
    (testing "the trigger carries the test, the named files as merged, and the architecture"
      (let [[a b] @seen]
        (is (= :security-finding (:trigger a)))
        (is (= "(deftest a)" (get-in a [:payload :test-text])))
        (is (= [["src/app/web.clj" "(ns app.web)"]] (get-in a [:payload :files])))
        (is (= "## 4. arch" (get-in a [:payload :architecture])))
        (is (nil? (get-in b [:payload :test-text])) "a test the record does not hold is said to be missing")
        (is (= [["src/app/none.clj" nil]] (get-in b [:payload :files])))))
    (is (= [:coder :architect] (map (comp :route :verdict) (:routed r))))
    (is (= ["C"] (map :title (:hypotheses r))))
    (testing "the routing is recorded, and the coder's finding drafted"
      (let [rec (edn/read-string (slurp (str (fs/path dir "security-routing.edn"))))]
        (is (= ["A" "B"] (map (comp :title :finding) (:routed rec))))
        (is (= ["C"] (map :title (:hypotheses rec)))))
      (let [fixes (slurp (str (fs/path dir "security-fixes.md")))]
        (is (str/includes? fixes "### fix-01 — A"))
        (is (not (str/includes? fixes "— B")) "an architect route is not drafted")
        (is (str/includes? fixes (str (fs/path dir "rev-1-tests" "test/app/a_test.clj"))))))))

(deftest no-fix-file-when-nothing-was-routed-coder
  (let [{:keys [dir clone record]} (review-dir)
        r (routing/route! {:review-file record :clone clone :architecture nil
                           :triage-fn (fn [_] {:route :human :reason "accept"}) :out dir})]
    (is (= 1 (count (:files r))))
    (is (not (fs/exists? (fs/path dir "security-fixes.md"))))))
