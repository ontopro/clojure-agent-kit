(ns harness.setup.app-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.forms :as forms]
   [harness.loop.driver :as driver]
   [harness.rules :as rules]
   [harness.setup.app :as app]
   [harness.setup.init :as init]
   [harness.setup.template :as template]
   [harness.setup.workspace :as workspace]))

(def git-env
  {"GIT_AUTHOR_NAME" "harness-test" "GIT_AUTHOR_EMAIL" "harness@test"
   "GIT_COMMITTER_NAME" "harness-test" "GIT_COMMITTER_EMAIL" "harness@test"})

(def pin (template/pin (template/load-pins)))

(def views-clj
  "A `views.clj` shaped as the pinned template generates it: the ns, `base`, `error-page`, and
  the `home-page` the KIT writes its first page over."
  (str "(ns xyx.views\n  (:require [manifest-edn.core :as manifest]))\n\n"
       "(defn base\n  \"Base component for html page.\"\n  [content]\n  [:html {:lang \"en\"} [:body content]])\n\n"
       "(defn error-page\n  [text]\n  (base [:h1 text]))\n\n"
       "(def home-page\n  (base\n    ; ========= TODO: Update home page  ========================\n    [:h1 \"Welcome to \" [:span \"Clojure Stack Lite\"]]))\n"))

(defn- generator
  "Stands in for the JVM: a `clojure` argv writes `files` into the `:target-dir`
  it was given - with `views-clj` unless `files` names `src/xyx/views.clj` (nil: none) - and
  is recorded in `seen`; git runs for real."
  [seen files]
  (fn [dir env argv]
    (if (= "clojure" (first argv))
      (let [target (edn/read-string (last argv))]
        (swap! seen conj {:dir (str dir) :argv argv})
        (doseq [[path content] (merge {"src/xyx/views.clj" views-clj} files)
                :when content]
          (fs/create-dirs (fs/parent (fs/path target path)))
          (spit (str (fs/path target path)) content))
        {:exit 0 :out ""})
      (#'app/run-process dir env argv))))

(defn- git-out [dir & args]
  (str/trim (:out (apply p/shell {:dir dir :out :string :err :string} "git" args))))

(deftest the-application-is-two-commits-the-first-untouched
  (let [ws (str (fs/real-path (fs/create-temp-dir)))
        app-dir (str (fs/path ws "xyx-app"))
        seen (atom [])]
    ((app/app-fn pin {:app-name "xyx" :git-env git-env
                      :run (generator seen {"deps.edn" "{}" "src/xyx/core.clj" "(ns xyx.core)"})})
     app-dir)
    (testing "generation is the pinned commit's argv, run beside the application's folder"
      (is (= [{:dir ws :argv (template/create-command pin {:app-name "xyx" :target-dir app-dir})}]
             @seen)))
    (testing "commit one is what the template made, and says which commit of it"
      (let [first-commit (git-out app-dir "rev-list" "--max-parents=0" "HEAD")]
        (is (= #{"deps.edn" "src/xyx/core.clj" "src/xyx/views.clj"}
               (set (str/split-lines (git-out app-dir "ls-tree" "-r" "--name-only" first-commit)))))
        (is (str/includes? (git-out app-dir "log" "-1" "--format=%s" first-commit)
                           (str (:git/tag pin) " (" (subs (:git/sha pin) 0 7) ")")))))
    (testing "commit two is the KIT's hand, and only that"
      (is (= "2" (git-out app-dir "rev-list" "--count" "HEAD")))
      (is (= #{"AGENTS.md" "CLAUDE.md" "layers.edn" "src/xyx/views.clj"}
             (set (str/split-lines (git-out app-dir "diff" "--name-only" "HEAD~1" "HEAD")))))
      (is (= "" (git-out app-dir "status" "--porcelain"))))
    (testing "the mirror is current against the rule source, and CLAUDE.md imports it"
      (is (false? (:changed? (rules/sync! (str (fs/path app-dir "AGENTS.md")) :check? true))))
      (is (str/starts-with? (slurp (str (fs/path app-dir "CLAUDE.md"))) "@AGENTS.md"))
      (is (str/includes? (slurp (str (fs/path app-dir "AGENTS.md"))) "`../xyx-build/rules.edn`")
          "with no folder given, the sentence names the default plan folder"))
    (testing "layers.edn is the pin's tails under the application's name, and the gate reads it"
      (let [ruleset (edn/read-string (slurp (str (fs/path app-dir "layers.edn"))))]
        (is (= (into {} (map (fn [[k v]] [(symbol (str "xyx." k)) (set (map #(symbol (str "xyx." %)) v))]))
                     (:layers pin))
               ruleset))
        (is (every? #(str/starts-with? (str %) "xyx.") (keys ruleset)))))))

(deftest the-agents-md-sentence-names-the-build-folder-the-workspace-has
  ;; Outside the markers, so no sync corrects it: a project that named its plan
  ;; folder with `bb init --plan` (then its name) read about a `<name>-build/` that was not there.
  (is (str/includes? (app/agents-md "xyx" "xyx-shop-plan") "`../xyx-shop-plan/rules.edn`"))
  (is (not (str/includes? (app/agents-md "xyx" "xyx-shop-plan") "xyx-plan")))
  (is (str/includes? (app/agents-md "xyx") "`../xyx-build/rules.edn`") "the default is the name's")
  (let [ws (str (fs/real-path (fs/create-temp-dir)))
        app-dir (str (fs/path ws "the-site"))]
    ((app/app-fn pin {:app-name "xyx" :build-folder "the-build" :git-env git-env
                      :run (generator (atom []) {"deps.edn" "{}"})})
     app-dir)
    (is (str/includes? (slurp (str (fs/path app-dir "AGENTS.md"))) "`../the-build/rules.edn`")
        "app-fn hands the folder on")))

(deftest a-local-clone-replaces-the-pin-and-the-commit-says-so
  (let [app-dir (str (fs/path (fs/real-path (fs/create-temp-dir)) "xyx-app"))
        seen (atom [])]
    ((app/app-fn pin {:app-name "xyx" :git-env git-env :local-root "/src/template"
                      :run (generator seen {"deps.edn" "{}"})})
     app-dir)
    (is (= {:local/root "/src/template"}
           (get-in (edn/read-string (nth (:argv (first @seen)) 3)) [:deps (:template pin)])))
    (is (str/includes? (git-out app-dir "log" "--format=%s" "--max-parents=0" "HEAD")
                       "NOT the pinned commit"))))

(deftest what-goes-wrong-is-said-and-nothing-is-overwritten
  (let [dir #(str (fs/path (fs/real-path (fs/create-temp-dir)) "xyx-app"))
        thrown (fn [run] (try ((app/app-fn pin {:app-name "xyx" :git-env git-env :run run}) (dir)) nil
                              (catch clojure.lang.ExceptionInfo e e)))]
    (testing "a failed generation carries the end of its output"
      (let [e (thrown (fn [_ _ _] {:exit 1 :out "line one\nCould not resolve the template"}))]
        (is (= :command (:app/error (ex-data e))))
        (is (str/includes? (ex-message e) "Could not resolve the template"))))
    (testing "an exit of 0 that made nothing is not a success"
      (is (= :nothing-generated (:app/error (ex-data (thrown (fn [_ _ _] {:exit 0 :out ""})))))))
    (testing "a template that ships its own AGENTS.md or layers.edn keeps it"
      (is (= :exists (:app/error (ex-data (thrown (generator (atom []) {"AGENTS.md" "theirs"}))))))
      (is (= :exists (:app/error (ex-data (thrown (generator (atom []) {"layers.edn" "{}"})))))))))

(deftest the-two-parts-meet-in-one-workspace
  (let [kit-dir (str (fs/parent (fs/real-path ".")))
        ws (str (fs/path (fs/real-path (fs/create-temp-dir)) "xyx"))
        lay (init/layout {:name "xyx" :kit-dir kit-dir :dir ws
                          :build-docs (init/build-docs-files kit-dir)
                          :rule-mirrors ["xyx-app/AGENTS.md"]
                          :loop/defaults (:loop/defaults pin)})
        made (init/create! lay {:git-env git-env
                                :app-fn (app/app-fn pin {:app-name "xyx" :git-env git-env
                                                         :run (generator (atom []) {"deps.edn" "{}"})})})]
    (is (true? (:app/created? made)))
    (is (= "2" (git-out (str (fs/path ws "xyx-app")) "rev-list" "--count" "HEAD")))
    (testing "the shipped pin tells a run its four gates and its REPL; the KIT's gate by the workspace's KIT"
      (let [cfg (edn/read-string (slurp (str (fs/path ws "xyx-build" "loop.edn"))))]
        (is (= [:fmt :lint :test :deps] (mapv first (:gates cfg))))
        (is (= "bb --config {{kit}}/harness/bb.edn boundary" (second (last (:gates cfg))))
            "{{kit}} as it stands, so the workspace can be copied or moved")
        (is (= (str "bb --config " kit-dir "/harness/bb.edn boundary")
               (second (last (:gates (driver/resolve-config (str (fs/path ws "work" "runs")) "/repo"
                                                            (assoc cfg :run/id "r")
                                                            (workspace/find-workspace (str (fs/path ws "work" "runs"))))))))
            "and a run started in the workspace resolves it to the workspace's KIT: a worktree under work/ has no relative path to it")
        (is (= ["clojure" "-Srepro" "-M:test:nrepl"] (:nrepl/cmd cfg)))))
    (testing "the application's mirror is rendered from the plan's rules overlay, and follows it"
      (let [mirror (str (fs/path ws "xyx-app" "AGENTS.md"))
            overlay (str (fs/path ws "xyx-build" "rules.edn"))
            kit-state (fn []
                        {:status (git-out kit-dir "status" "--porcelain" "--" "harness/AGENTS.md" "harness/resources")
                         :mirror (slurp (str (fs/path kit-dir "harness" "AGENTS.md")))
                         :rules (slurp (str (fs/path kit-dir "harness" "resources" "agent-rules.edn")))})
            before (kit-state)]
        (is (false? (:changed? (rules/sync! mirror :check? true))) "in sync as generated: the overlay is as shipped")
        (spit overlay (pr-str [{:id :layer-boundaries :text "Respect the boundaries of layer {{layer}}. FILLED BY THE PROJECT."}]))
        (is (true? (:changed? (rules/sync! mirror :check? true))) "an edit in the plan is drift in the application's mirror")
        (rules/sync! mirror)
        (is (str/includes? (slurp mirror) "FILLED BY THE PROJECT."))
        (is (= before (kit-state))
            "and nothing of it reached the KIT's clone: its status, its mirror and its rule source are as they were - committed or not")))))

(deftest the-first-page-is-the-kits-written-over-the-templates
  (let [page (app/home-page "xyx" "0.6.5")
        out (app/with-home-page views-clj page "src/xyx/views.clj")]
    (testing "the page names the KIT, the project, the next step and its version"
      (doseq [s ["Welcome to " "Clojure Agent Kit" "(aka \\\"KIT\\\")" "\"xyx\"" "cd .." "claude" "/kit-start"
                 "stage 0 replaces it" "Made by KIT 0.6.5"]]
        (is (str/includes? page s) s)))
    (testing "the one form replaced, every other line as it was"
      (is (str/includes? out page))
      (is (not (str/includes? out "Clojure Stack Lite\"]")))
      (is (str/starts-with? out (subs views-clj 0 (str/index-of views-clj "(def home-page"))))
      (is (str/ends-with? out "\n"))
      (is (= ["ns" "defn" "defn" "def"] (mapv :kind (forms/table out)))))
    (testing "no home-page, or two, is refused by name - never a page written beside it"
      (doseq [[views n] [["(ns xyx.views)\n(defn base [c] c)\n" 0]
                         [(str views-clj "\n(def home-page nil)\n") 2]]]
        (let [e (try (app/with-home-page views page "src/xyx/views.clj") nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= {:app/error :no-home-page :path "src/xyx/views.clj" :found n} (ex-data e)))
          (is (str/includes? (ex-message e) "src/xyx/views.clj")))))))

(deftest a-template-with-no-home-page-stops-before-the-kits-commit
  (let [app-dir (str (fs/path (fs/real-path (fs/create-temp-dir)) "xyx-app"))
        e (try ((app/app-fn pin {:app-name "xyx" :git-env git-env
                                 :run (generator (atom []) {"deps.edn" "{}" "src/xyx/views.clj" nil})})
                app-dir)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :no-home-page (:app/error (ex-data e))))
    (is (= "1" (git-out app-dir "rev-list" "--count" "HEAD")) "the generated commit alone")
    (is (not (fs/exists? (fs/path app-dir "AGENTS.md"))) "nothing of the KIT's written")))
