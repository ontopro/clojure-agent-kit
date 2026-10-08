(ns harness.tools.security-scan-test
  "The security pack's two scans over the source (`tools/security/src/security_scan.clj`), on
  canned text: the dependency rows from canned OSV records, the secret rules on values built
  here. The commands and the network - `clojure -X:deps list`, osv.dev, `git log` - are tried
  by running the pack, which `bb health` does.

  NO VALUE IN THIS FILE IS SHAPED LIKE A SECRET. Each is put together from its prefix and
  random characters when the test runs, so the file never trips a scanner - this pack's, or a
  host's push protection - and the tests still try the real shapes."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [security-scan :as ss]))

;; ---------------------------------------------------------------------------
;; the dependencies
;; ---------------------------------------------------------------------------

(deftest the-listing-and-the-tree-are-read
  (is (= [{:lib "ring/ring-core" :name "ring:ring-core" :version "1.15.3"}
          {:lib "org.eclipse.jetty/jetty-http" :name "org.eclipse.jetty:jetty-http" :version "12.1.0"}]
         (ss/parse-deps-list "ring/ring-core 1.15.3  (MIT)\norg.eclipse.jetty/jetty-http 12.1.0  (EPL-2.0)\n\n")))
  (let [tree (ss/parse-tree (str "ring/ring-jetty-adapter 1.15.3\n"
                                 "  . org.eclipse.jetty/jetty-server 12.1.0\n"
                                 "    . org.eclipse.jetty/jetty-http 12.1.0\n"
                                 "  X ring/ring-core 1.15.2 :older-version\n"
                                 "metosin/reitit-middleware 0.9.2\n"
                                 "  . org.eclipse.jetty/jetty-http 12.1.0\n"))]
    (is (= #{"ring/ring-jetty-adapter" "metosin/reitit-middleware"} (tree "org.eclipse.jetty/jetty-http"))
        "every top-level library that brings it")
    (is (= #{"ring/ring-jetty-adapter"} (tree "ring/ring-jetty-adapter")) "a top-level library brings itself")
    (is (nil? (tree "ring/ring-core")) "an omitted one is brought by nothing")))

(deftest versions-order-as-maven-does-closely-enough
  (is (neg? (ss/compare-versions "12.1.0" "12.1.7")))
  (is (neg? (ss/compare-versions "2.18.2" "2.18.11")) "numbers, not text")
  (is (zero? (ss/compare-versions "1.5" "1.5.0")) "trailing zeros are no difference")
  (is (neg? (ss/compare-versions "1.0-rc1" "1.0")) "a qualifier is below the release")
  (is (neg? (ss/compare-versions "1.0" "1.0.1")))
  (is (pos? (ss/compare-versions "0.9.2" "0.9.2-alpha"))))

(def jetty-advisory
  {:id "GHSA-0000-0000-0001" :aliases ["CVE-2026-0001"] :database_specific {:severity "HIGH"}
   :affected [{:package {:ecosystem "Maven" :name "org.eclipse.jetty:jetty-http"}
               :ranges [{:type "ECOSYSTEM" :events [{:introduced "12.0.0"} {:fixed "12.0.33"}]}
                        {:type "ECOSYSTEM" :events [{:introduced "12.1.0"} {:fixed "12.1.7"}]}
                        {:type "GIT" :events [{:introduced "0"} {:fixed "abc123"}]}]}]})

(deftest the-fix-is-the-one-for-the-version-line-in-use
  (is (= "12.1.7" (ss/fixed-in jetty-advisory "org.eclipse.jetty:jetty-http" "12.1.0")))
  (is (= "12.0.33" (ss/fixed-in jetty-advisory "org.eclipse.jetty:jetty-http" "12.0.5")))
  (is (nil? (ss/fixed-in jetty-advisory "org.eclipse.jetty:jetty-http" "12.1.7")) "a fixed version is in no range")
  (is (nil? (ss/fixed-in jetty-advisory "org.eclipse.jetty:jetty-server" "12.1.0")) "another package's ranges are not read")
  (is (= :none (ss/fixed-in {:affected [{:package {:name "a:b"} :ranges [{:type "ECOSYSTEM" :events [{:introduced "0"} {:last_affected "2.0"}]}]}]}
                            "a:b" "1.4"))
      "a range that ends in last_affected has no fix"))

(def jetty {:lib "org.eclipse.jetty/jetty-http" :name "org.eclipse.jetty:jetty-http" :version "12.1.0"})

(defn- advisory [id sev fixed]
  {:id id :database_specific (when sev {:severity sev})
   :affected [{:package {:name "org.eclipse.jetty:jetty-http"}
               :ranges [{:type "ECOSYSTEM" :events [{:introduced "12.1.0"} {:fixed fixed}]}]}]})

(deftest a-high-advisory-fails-and-names-the-fix-and-the-line-to-change
  (let [r (ss/dep-row jetty [(advisory "GHSA-a" "HIGH" "12.1.7") (advisory "GHSA-b" "LOW" "12.1.5")]
                      #{"ring/ring-jetty-adapter" "org.eclipse.jetty/jetty-http"} {} "2026-10-08")]
    (is (= :fail (:status r)))
    (is (str/includes? (:says r) "HIGH GHSA-a, fixed in 12.1.7; LOW GHSA-b, fixed in 12.1.5"))
    (is (str/includes? (:says r) "brought by ring/ring-jetty-adapter"))
    (is (str/includes? (:fix r) "at 12.1.7 or later") "the highest fix clears them all")
    (is (str/includes? (:fix r) "raise ring/ring-jetty-adapter"))))

(deftest below-high-warns-and-an-accepted-advisory-says-why-until-it-ends
  (is (= :warn (:status (ss/dep-row jetty [(advisory "GHSA-m" "MODERATE" "12.1.9")] #{} {} "2026-10-08"))))
  (let [accepted {"GHSA-a" {:reason "no Digest auth in this application" :until "2026-12-31"}}
        r (ss/dep-row jetty [(advisory "GHSA-a" "HIGH" "12.1.7")] #{} accepted "2026-10-08")]
    (is (= :warn (:status r)))
    (is (str/includes? (:says r) "accepted until 2026-12-31: no Digest auth")))
  (let [r (ss/dep-row jetty [(advisory "GHSA-a" "HIGH" "12.1.7")] #{} {"GHSA-a" {:reason "x" :until "2026-09-30"}} "2026-10-08")]
    (is (= :fail (:status r)) "an acceptance that ended accepts nothing")
    (is (str/includes? (:says r) "its acceptance ended 2026-09-30")))
  (let [r (ss/dep-row jetty [(advisory "GHSA-u" nil "12.1.7")] #{} {} "2026-10-08")]
    (is (= :warn (:status r)))
    (is (str/includes? (:says r) "no severity: read it")))
  (is (str/includes? (:says (ss/dep-row jetty [(advisory "GHSA-m" "LOW" "12.1.9")] #{"org.eclipse.jetty/jetty-http"} {} "2026-10-08"))
                     "in deps.edn")
      "a library deps.edn names itself"))

(deftest no-advisory-is-one-ok-row-and-no-answer-is-a-skip
  (is (= [:ok] (mapv :status (ss/dep-rows [jetty] {} {} {} "2026-10-08"))))
  (is (= [:skipped] (mapv :status (ss/dep-skipped "OSV did not answer")))))

;; ---------------------------------------------------------------------------
;; secrets
;; ---------------------------------------------------------------------------

(defn- rnd [chars n] (apply str (repeatedly n #(rand-nth chars))))
(def alnum "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
(def hex "0123456789abcdef")

(defn- fakes
  "One value of each shape, made now."
  []
  {:aws-access-key (str "AK" "IA" (rnd "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" 16))
   :github-token (str "gh" "p_" (rnd alnum 36))
   :openrouter-key (str "sk-" "or-v1-" (rnd hex 64))
   :anthropic-key (str "sk-" "ant-api03-" (rnd alnum 93))
   :openai-key (str "sk-" "proj-" (rnd alnum 48))
   :stripe-key (str "sk" "_live_" (rnd alnum 24))
   :slack-token (str "xo" "xb-" (rnd "0123456789" 12) "-" (rnd alnum 24))
   :google-api-key (str "AI" "za" (rnd alnum 35))
   :private-key (str "-----BEGIN " "OPENSSH PRIVATE" " KEY-----")
   :url-credentials (str "postgres://app:" (rnd alnum 16) "@db:5432/app")})

(deftest every-shape-is-named-by-its-rule
  (doseq [[rule v] (fakes)]
    (is (= rule (ss/line-rule (str "(def x \"" v "\")"))) (name rule))))

(deftest a-random-value-given-to-a-secrets-name-is-a-hit-and-a-plain-one-is-not
  (is (= :assigned-high-entropy (ss/line-rule (str "(def aws-secret \"" (rnd alnum 40) "\")"))) "a Clojure def")
  (is (= :assigned-high-entropy (ss/line-rule (str "SESSION_SECRET_KEY=" (rnd hex 32)))) "an env line")
  (is (= :assigned-high-entropy (ss/line-rule (str ":api-key \"" (rnd alnum 32) "\""))) "a map entry")
  (testing "the decoys"
    (is (nil? (ss/line-rule "(def placeholder \"<your-api-key>\")")))
    (is (nil? (ss/line-rule "(System/getenv \"OPENROUTER_API_KEY\")")))
    (is (nil? (ss/line-rule ":session-secret-key \"test-session-key-0000\"")) "a word is low entropy")
    (is (nil? (ss/line-rule (str "(def sha \"" (rnd hex 40) "\")"))) "a hash under a name that is not a secret's")
    (is (nil? (ss/line-rule ":password \"replace-with-a-real-secret\"")) "words joined are a placeholder")))

(deftest random-looking-is-shape-not-entropy-alone
  ;; one in five random 32-character hex keys scores below 3.5 bits a character
  (is (every? ss/random-looking? (repeatedly 200 #(rnd hex 32))) "long hex, whatever its score")
  (is (every? ss/random-looking? (repeatedly 200 #(rnd alnum 24))))
  (is (not-any? ss/random-looking? ["test-session-key-0000" "changeme-changeme-123" "your_api_key_goes_here"
                                    "PLACEHOLDER_SECRET_VALUE" "development-only-key"])))

(deftest an-env-file-is-a-hit-and-its-example-is-not
  (is (ss/env-file? ".env"))
  (is (ss/env-file? "deploy/.env.production"))
  (is (not (ss/env-file? ".env.example")))
  (is (not (ss/env-file? "src/env.clj"))))

(deftest the-added-lines-carry-their-commit-file-and-number
  (let [log (str "commit abc1234\n\ndiff --git a/a.clj b/a.clj\n--- a/a.clj\n+++ b/a.clj\n@@ -3,0 +4,2 @@\n+one\n+two\n"
                 "commit def5678\n\ndiff --git a/b.clj b/b.clj\n--- a/b.clj\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone\n")]
    (is (= [{:commit "abc1234" :file "a.clj" :line 4 :text "one"}
            {:commit "abc1234" :file "a.clj" :line 5 :text "two"}]
           (ss/added-lines log))
        "a deleted file adds nothing"))
  (is (= [{:commit "abc1234" :file ".env"} {:commit "abc1234" :file "src/a.clj"}]
         (ss/added-files "commit abc1234\n\n.env\nsrc/a.clj\n"))))

(deftest a-hit-fails-without-its-value-and-an-allowed-one-warns
  (let [v (:github-token (fakes))
        lines [{:commit "abc1234" :file "src/config.clj" :line 3 :text (str "(def gh \"" v "\")")}
               {:commit "abc1234" :file "test/fixture.clj" :line 9 :text (str "(def k \"" (str "sk" "_live_" (rnd alnum 24)) "\")")}]
        rows (ss/secret-rows lines [{:commit "abc1234" :file ".env"}]
                             [{:file "test/fixture.clj" :reason "a revoked test key"}] "abc..HEAD")]
    (is (= [:fail :fail :warn] (mapv :status rows)))
    (is (= ".env @abc1234" (:subject (first rows))))
    (is (= "src/config.clj:3 @abc1234" (:subject (second rows))))
    (is (str/includes? (:says (nth rows 2)) "allowed: a revoked test key"))
    (is (not (str/includes? (pr-str rows) v)) "the value is in no row")))

(deftest nothing-found-is-one-ok-row
  (is (= [:ok] (mapv :status (ss/secret-rows [{:file "a.clj" :line 1 :text "(ns a)"}] [] [] "HEAD")))))
