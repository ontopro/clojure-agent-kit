(ns harness.setup.template-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.template :as template]))

(def pins
  {:default :one
   :templates {:one {:template 'io.github.someone/one
                     :git/url "https://example.invalid/one.git"
                     :git/sha "0123456789abcdef0123456789abcdef01234567"
                     :git/tag "one-v1"
                     :deps-new {:git/tag "v0.8.0" :git/sha "2f96530"}}
               :two {:template 'io.github.someone/two
                     :git/url "https://example.invalid/two.git"
                     :git/sha "fedcba9876543210fedcba9876543210fedcba98"
                     :deps-new {:git/tag "v0.8.0" :git/sha "2f96530"}}}})

(deftest the-shipped-pin-is-a-whole-commit-and-names-its-template
  ;; A short sha resolves today and is ambiguous tomorrow; the pin is what a health-check
  ;; record will cite, so it is the full forty characters.
  (let [p (template/pin (template/load-pins))]
    (is (symbol? (:template p)))
    (is (re-matches #"[0-9a-f]{40}" (:git/sha p)))
    (is (str/starts-with? (:git/url p) "https://"))
    (is (string? (:git/tag p)) "a label for people, beside the sha that resolves")
    (is (map? (:deps-new p)))))

(deftest a-template-is-named-in-data-and-a-wrong-name-is-an-error
  (is (= :one (:template/key (template/pin pins))) "the default")
  (is (= 'io.github.someone/two (:template (template/pin pins :two))) "a second entry needs no code")
  (testing "a typo does not fall back to another template"
    (let [e (try (template/pin pins :tow) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= [:one :two] (:known (ex-data e))))
      (is (str/includes? (ex-message e) ":tow")))))

(deftest the-command-generates-from-the-pinned-commit-or-from-a-local-clone
  (let [p (template/pin pins)
        argv (template/create-command p {:app-name "xyx" :target-dir "/tmp/ws/xyx-app"})
        deps (edn/read-string (nth argv 3))]
    (is (= ["clojure" "-Srepro" "-Sdeps"] (subvec argv 0 3))
        "-Srepro: a personal deps.edn stays out of generation")
    (is (= {:git/url "https://example.invalid/one.git"
            :git/sha "0123456789abcdef0123456789abcdef01234567"}
           (get-in deps [:deps 'io.github.someone/one]))
        "the pinned commit, not a tag and not a branch")
    (is (= {:git/tag "v0.8.0" :git/sha "2f96530"} (get-in deps [:deps 'io.github.seancorfield/deps-new]))
        "deps-new rides along as a dependency: nothing for an adopter to install first")
    (is (= ["-X" "org.corfield.new/create" ":template" "io.github.someone/one" ":name" "xyx"
            ":target-dir" "\"/tmp/ws/xyx-app\""]
           (subvec argv 4)))
    (testing "a local clone replaces the pin, for someone developing the template"
      (let [local (template/create-command p {:app-name "xyx" :target-dir "/tmp/x" :local-root "/src/one"})]
        (is (= {:local/root "/src/one"}
               (get-in (edn/read-string (nth local 3)) [:deps 'io.github.someone/one])))))))
