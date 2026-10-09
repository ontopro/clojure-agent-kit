(ns harness.setup.health-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.setup.health :as health]))

(def kit-dir (str (fs/parent (fs/real-path "."))))

(defn- fake-project
  "A project whose four gates are shell commands that fail exactly when that
  gate's breaker file is present - the mechanics of `check-red` without a JVM."
  []
  (let [dir (str (fs/create-temp-dir))
        bs (health/breakers "xyx")
        gate (fn [k] [k (str "test ! -e " (get-in bs [k :path]))])]
    (fs/create-dirs (fs/path dir "src" "xyx"))
    {:subject :fake :dir dir :root "xyx"
     :gates [(gate :fmt) (gate :lint) (gate :test) (gate :deps)]}))

(deftest the-breakers-take-a-root-and-each-fails-only-its-own-gate
  (let [bs (health/breakers "my-app")]
    (is (= #{:fmt :lint :test :deps} (set (keys bs))))
    (testing "paths are under the root's folder, hyphens as underscores; the test breaker under test/"
      (is (= "src/my_app/red_fmt.clj" (get-in bs [:fmt :path])))
      (is (= "test/my_app/red_test.clj" (get-in bs [:test :path]))))
    (testing "every breaker but fmt's is one form per line: a project's cljfmt config cannot reflow it"
      (doseq [k [:lint :test :deps]
              line (str/split-lines (get-in bs [k :content]))
              :when (not (str/blank? line))]
        (is (not (str/starts-with? line " ")) (str k ": " line))))
    (is (str/includes? (get-in bs [:fmt :content]) "\n(inc x)") "the fmt breaker is the mis-indented one")
    (is (str/includes? (get-in bs [:lint :content]) "unused"))
    (is (str/includes? (get-in bs [:test :content]) "(= 1 2)"))))

(deftest check-red-writes-each-breaker-sees-its-own-gate-fail-and-removes-it
  (let [{:keys [dir] :as subject} (fake-project)]
    (is (true? (:ok? (health/check-gates subject))) "green untouched")
    (let [r (health/check-red subject)]
      (is (true? (:ok? r)))
      (is (str/includes? (:detail r) "each of 4 gates")))
    (is (empty? (filter #(str/includes? (str %) "red_") (fs/glob dir "**"))) "no breaker left behind")))

(deftest check-red-fails-when-a-gate-stays-green-or-fails-at-the-wrong-key
  (let [{:keys [dir] :as subject} (fake-project)
        bs (health/breakers "xyx")]
    (testing "a gate that never fails is a finding: it proves nothing"
      (let [r (health/check-red (assoc subject :gates [[:fmt "true"] [:lint (str "test ! -e " (get-in bs [:lint :path]))]]))]
        (is (false? (:ok? r)))
        (is (str/includes? (:detail r) ":fmt the gates stayed green"))
        (is (str/includes? (:detail r) ":lint ok"))))
    (testing "a breaker that trips an earlier gate is a finding too"
      (let [r (health/check-red (assoc subject :gates [[:fmt "false"] [:lint "true"]]))]
        (is (false? (:ok? r)))
        (is (str/includes? (:detail r) ":lint failed at :fmt instead"))))
    (testing "and the breaker is removed even then"
      (is (empty? (filter #(str/includes? (str %) "red_") (fs/glob dir "**")))))))

(deftest check-gates-reports-the-failed-gate-and-its-last-lines
  (let [r (health/check-gates {:subject :fake :dir (str (fs/create-temp-dir)) :root "x"
                               :gates [[:fmt "true"] [:lint "sh -c 'echo one; echo two; exit 3'"]]})]
    (is (false? (:ok? r)))
    (is (str/includes? (:detail r) "failed at :lint"))
    (is (str/includes? (:detail r) "two"))))

(deftest the-trivial-task-is-the-clamp-of-method-03-in-a-new-declared-layer
  (let [{:keys [spec writes layer]} (health/trivial-task "my-app")]
    (is (= ["src/my_app/health.clj"] (:files/impl spec)))
    (is (= ["test/my_app/health_test.clj"] (:files/test spec)))
    (is (= (set (:files/impl spec)) (set (keys (:coder writes)))) "the Coder writes exactly its target")
    (is (= (set (:files/test spec)) (set (keys (:tester writes)))))
    (is (= 'my-app.health layer) "declared through :architecture, since no role may write layers.edn")
    (is (str/includes? (get (:coder writes) "src/my_app/health.clj") "(ns my-app.health)"))))

(deftest the-loops-record-is-held-to-the-repositories-the-copy-and-the-plan
  ;; The three hashes are the claim: a record that names commits nobody checked against the
  ;; repositories is the memory it replaced, written down. The check reads HEAD itself.
  (let [heads {:kit "aaa" :app "bbb" :plan "ccc"}
        record {:run/status :awaiting-merge :run/kit-commit "aaa" :run/app-commit "bbb" :run/plan-commit "ccc"}
        kept {:path "/w/hc-plan/runs/health.edn" :exists? true :same? true}
        plan {:rules "hc-plan/rules.edn" :profile "hc-plan/profile.edn"}]
    (testing "a record that says what it promises"
      (is (= [] (health/loop-problems record heads kept plan))))
    (testing "the selfcheck subject: no plan, no copy, and a nil plan commit is the truth"
      (is (= [] (health/loop-problems (assoc record :run/plan-commit nil) (assoc heads :plan nil) nil nil))))
    (testing "each way of breaking it is one sentence"
      (is (= ["run.edn was not written"] (health/loop-problems nil heads kept plan)))
      (is (= ["the record's status is :escalated, not :awaiting-merge"]
             (health/loop-problems (assoc record :run/status :escalated) heads kept plan)))
      (is (= ["run/app-commit is bbb, HEAD of the app is bb2"]
             (health/loop-problems record (assoc heads :app "bb2") kept plan)))
      (is (= ["run/plan-commit is nil, HEAD of the plan is ccc"]
             (health/loop-problems (assoc record :run/plan-commit nil) heads kept plan)))
      (is (= ["run/plan-commit is ccc but the workspace has no plan repository"]
             (health/loop-problems record (assoc heads :plan nil) kept plan)))
      (is (= ["no copy at /w/hc-plan/runs/health.edn, the plan's records folder"]
             (health/loop-problems record heads (assoc kept :exists? false :same? false) plan)))
      (is (= ["the copy at /w/hc-plan/runs/health.edn differs from run.edn"]
             (health/loop-problems record heads (assoc kept :same? false) plan)))
      (is (= ["the loop's rules overlay is not the plan's" "the loop's profile is not the plan's"]
             (health/loop-problems record heads kept {:rules nil :profile nil}))
          "a loop that read the clone's rules or profile would prove the wrong thing"))))

(deftest the-selfcheck-subject-is-a-scratch-repository-not-the-checked-in-one
  (let [{:keys [dir workspace gates]} (health/selfcheck-subject kit-dir)]
    (is (not (str/starts-with? dir kit-dir)) "a copy elsewhere")
    (is (fs/exists? (fs/path dir ".git")))
    (is (fs/exists? (fs/path dir ".gitignore")) "the KIT's, so .nrepl-port is not a file a role wrote")
    (is (= "" (str/trim (:out (p/shell {:dir dir :out :string :err :string} "git" "status" "--porcelain")))))
    (is (fs/exists? (fs/path workspace "workspace.edn")))
    (is (= [:fmt :lint :test :deps] (mapv first gates)))
    (is (str/includes? (second (last gates)) (str kit-dir "/harness/bb.edn"))
        "the boundary gate by the KIT's path: .. no longer leads to the harness")
    (fs/delete-tree (fs/parent workspace))))

;; ---------------------------------------------------------------------------
;; the record and the README block
;; ---------------------------------------------------------------------------

(def ^:private checks
  [{:check :gates :subject :selfcheck :ok? true :ms 3000 :detail "4 gates green"}
   {:check :red :subject :selfcheck :ok? true :ms 5000 :detail "each of 4 gates fails"}
   {:check :serve :subject :app :ok? true :ms 7600 :detail "GET http://localhost:8000/ -> 200; /var/folders/x/hc-app"}])

(def ^:private pin
  {:template 'io.github.ontopro/clojure-stack-lite :git/tag "kit-v1"
   :git/sha "a2c0eaa567b01eefdf7dcde74a0960e72968a850" :git/url "https://example.invalid"})

(deftest the-record-carries-what-certifies-and-nothing-of-the-host-or-the-scratch
  (let [rec (health/record kit-dir checks pin
                           [{:tool :bb :req :required :version "1.13.223"}
                            {:tool :claude :req :optional :version "2.1.280"}])]
    (is (re-matches #"\d{4}-\d{2}-\d{2}" (:health/as-of rec)))
    (is (= (:key (health/platform)) (get-in rec [:platform :key])))
    (is (re-matches #"[0-9a-f]{40}" (get-in rec [:kit :sha])))
    (is (contains? (:kit rec) :dirty?) "a record from a dirty tree says so")
    (is (= {:template 'io.github.ontopro/clojure-stack-lite :git/tag "kit-v1"
            :git/sha "a2c0eaa567b01eefdf7dcde74a0960e72968a850"}
           (:template rec))
        "the pin, not its url")
    (is (= {:bb "1.13.223"} (:tools rec)) "required tools only")
    (is (= [{:subject :selfcheck :check :gates :ok? true :ms 3000}
            {:subject :selfcheck :check :red :ok? true :ms 5000}
            {:subject :app :check :serve :ok? true :ms 7600}]
           (:checks rec))
        "outcome and time; never the detail, which carries scratch paths")
    (is (not (str/includes? (pr-str rec) "/var/folders")))
    (is (true? (:ok? rec)))))

(deftest the-readme-block-is-rendered-from-the-records
  (let [rec {:health/as-of "2026-09-22" :platform {:key "macos-arm64" :label "macOS 26.5 arm64"}
             :kit {:short "a001c95" :dirty? false} :template pin
             :checks (mapv #(select-keys % [:subject :check :ok? :ms]) checks) :ok? true}]
    (is (str/starts-with? (health/render {}) "No health record yet"))
    (let [out (health/render {"macos-arm64" rec})]
      (is (str/includes? out "| macOS 26.5 arm64 | 2026-09-22 | `a001c95` | `kit-v1` (`a2c0eaa`) | 3 of 3 ok: selfcheck gates, selfcheck red, app serve | 16s |"))
      (is (str/includes? out "a platform not in the table has none")))
    (is (str/includes? (health/render {"macos-arm64" (assoc-in rec [:kit :dirty?] true)}) "(uncommitted changes)"))
    (is (str/includes? (health/render {"macos-arm64" (assoc-in rec [:checks 2 :ok?] false)}) "app serve FAILED"))))

(deftest the-security-check-runs-the-pack-and-carries-its-warns
  (let [subject {:subject :app :dir (str (fs/create-temp-dir)) :kit "/k"}
        files {"check" "security.edn" "deps" "security-deps.edn" "secrets" "security-secrets.edn"}
        ;; `records` is command -> [record exit]; a command it does not name passes with one ok row
        pack (fn [records]
               (fn [_dir _env argv]
                 (let [out (second (drop-while #(not= "--out" %) argv))
                       cmd (nth argv 3)
                       [record exit] (get records cmd [{:ok? true :counts {:ok 1 :warn 0 :fail 0 :skipped 0}
                                                        :rows [{:check (keyword cmd) :status :ok :says "clean"}]} 0])]
                   (when record (spit (str (fs/path out (files cmd))) (pr-str record)))
                   {:exit exit :out "" :err ""})))
        clean {:ok? true :counts {:ok 10 :warn 1 :fail 0 :skipped 1}
               :rows [{:check :headers :status :ok :says "nosniff"}
                      {:check :csp :status :warn :says "no Content-Security-Policy"}
                      {:check :tls :status :skipped :says "the base is http"}]}]
    (testing "ok when each command exits 0 with its record; the warns and skips in the detail"
      (let [seen (atom [])
            r (health/check-security subject {:run (fn [dir env argv] (swap! seen conj {:dir dir :argv argv}) ((pack {"check" [clean 0]}) dir env argv))})]
        (is (true? (:ok? r)))
        (is (= [["check"] ["deps"] ["secrets" "--all"]] (mapv #(vec (take-while (fn [a] (not= "--out" a)) (drop 3 (:argv %)))) @seen))
            "the three commands, secrets over the whole history")
        (is (= ["bb" "--config" "/k/tools/security/bb.edn"] (vec (take 3 (:argv (first @seen))))))
        (is (every? #(= (:dir subject) (:dir %)) @seen))
        (is (str/starts-with? (:detail r) "10 ok, 1 warn, 0 fail, 1 skipped, from outside\n1 ok, 0 warn, 0 fail, 0 skipped, the dependencies"))
        (is (str/includes? (:detail r) "warn csp: no Content-Security-Policy"))
        (is (str/includes? (:detail r) "skipped tls: the base is http"))
        (is (not (str/includes? (:detail r) "nosniff")) "an ok row is not repeated")))
    (testing "failed on a failing row in any command, and on no record"
      (let [r (health/check-security subject {:run (pack {"check" [(assoc clean :ok? false :rows [{:check :cookies :status :fail :says "no HttpOnly"}]) 1]})})]
        (is (false? (:ok? r)))
        (is (str/includes? (:detail r) "fail cookies: no HttpOnly")))
      (let [r (health/check-security subject {:run (pack {"deps" [{:ok? false :counts {:ok 0 :warn 0 :fail 1 :skipped 0}
                                                                   :rows [{:check :deps :status :fail :subject "org.eclipse.jetty/jetty-http 12.1.0"
                                                                           :says "HIGH GHSA-x, fixed in 12.1.7"}]} 1]})})]
        (is (false? (:ok? r)) "an advisory published since the pin fails health")
        (is (str/includes? (:detail r) "fail deps org.eclipse.jetty/jetty-http 12.1.0: HIGH GHSA-x")))
      (let [r (health/check-security subject {:run (pack {"secrets" [nil 2]})})]
        (is (false? (:ok? r)))
        (is (str/includes? (:detail r) "the security pack's secrets exited 2, no record written"))))))

(deftest a-port-another-program-holds-on-loopback-is-not-free
  ;; The application binds the wildcard address, which can succeed on a port
  ;; held on 127.0.0.1 alone; `localhost` would then reach the other program.
  (let [port (with-open [held (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
               (is (false? (health/port-free? (.getLocalPort held))))
               (.getLocalPort held))]
    (is (true? (health/port-free? port)) "and free once the other program lets go")))

(deftest the-browser-check-performs-and-is-skipped-where-it-cannot
  ;; `geckodriver --version` passes on a machine where no browser can start; the
  ;; permission on macOS is the terminal application's, and a shell under a daemon
  ;; gets a silent refusal. Only a real start shows it, so health starts one.
  (let [subject {:subject :app :dir (str (fs/create-temp-dir)) :kit "/k"}]
    (testing "skipped, and said so, where geckodriver or Firefox is absent"
      (let [r (health/check-browser subject {:available? (constantly false)})]
        (is (true? (:ok? r)) "a run without a browser is still healthy")
        (is (true? (:skipped? r)))
        (is (str/includes? (:detail r) "skipped"))))
    (testing "ok when the template's task exits 0 and wrote the screenshot"
      (let [seen (atom nil)
            r (health/check-browser subject {:available? (constantly true)
                                             :run (fn [dir _env argv]
                                                    (reset! seen {:dir dir :argv argv})
                                                    (let [out (second (drop-while #(not= "--out" %) argv))
                                                          f (fs/path out "1440" "home.png")]
                                                      (fs/create-dirs (fs/parent f))
                                                      (spit (str f) "png")
                                                      {:exit 0 :out "/   1440x900 scrollWidth 1440\n1 page(s) at 1440 wide; 0 overflow\nstopped\n" :err ""}))})]
        (is (true? (:ok? r)))
        (is (not (:skipped? r)))
        (is (= ["bb" "--config" "/k/tools/browser/bb.edn" "check" "--only" "screenshots" "--out"] (vec (take 7 (:argv @seen))))
            "the KIT's pack from the clone given, screenshots only")
        (is (= "/" (last (:argv @seen))) "the home page")
        (is (= (:dir subject) (:dir @seen)))
        (is (str/includes? (:detail r) "scrollWidth 1440") "the measure line travels into the detail")))
    (testing "failed when the task exits non-zero, or exits 0 with no screenshot"
      (let [r (health/check-browser subject {:available? (constantly true)
                                             :run (fn [_ _ _] {:exit 1 :out "" :err "Firefox did not start: timeout"})})]
        (is (false? (:ok? r)))
        (is (str/includes? (:detail r) "Firefox did not start"))
        (is (str/includes? (:detail r) "Privacy & Security > Files & Folders"))
        (is (str/includes? (:detail r) "run `bb health` again")))
      (let [r (health/check-browser subject {:available? (constantly true)
                                             :run (fn [_ _ _] {:exit 0 :out "" :err ""})})]
        (is (false? (:ok? r)))
        (is (str/includes? (:detail r) "no screenshot written"))))
    (testing "the availability test: geckodriver and a Firefox where the driver looks"
      (is (health/browser-available? #{"geckodriver" "firefox"} (constantly false)))
      (is (health/browser-available? #{"geckodriver"} #{"/Applications/Firefox.app/Contents/MacOS/firefox"}))
      (is (not (health/browser-available? #{"firefox"} (constantly true))) "no geckodriver, no check")
      (is (not (health/browser-available? #{"geckodriver"} (constantly false))) "no Firefox, no check"))))

(deftest a-skipped-check-is-in-the-record-and-the-readme-row-says-so
  (let [skipped {:subject :app :check :browser :ok? true :ms 0 :skipped? true :detail "skipped: no geckodriver"}
        rec (health/record kit-dir (conj checks skipped) pin [])]
    (is (= {:subject :app :check :browser :ok? true :ms 0 :skipped? true} (last (:checks rec)))
        "the skip travels into the record; a passed check has no such key")
    (is (true? (:ok? rec)) "a skipped check does not make the run unhealthy")
    (let [out (health/render {"x" (assoc rec :platform {:key "x" :label "X"} :kit {:short "abc1234" :dirty? false})})]
      (is (str/includes? out "3 of 4 ok, 1 skipped: selfcheck gates, selfcheck red, app serve, app browser SKIPPED")
          "never counted as passed"))))

(deftest write-record-writes-the-platform-file-and-the-known-good-set
  (let [dir (str (fs/create-temp-dir))
        rec {:health/as-of "2026-09-22" :platform {:key "test-arm64" :label "Test arm64"}
             :kit {:sha "x" :short "x" :dirty? false} :template pin :tools {:bb "1.13.223"} :checks [] :ok? true}]
    (with-redefs [health/records-dir (str (fs/path dir "health"))
                  health/known-good-path (str (fs/path dir "known-good.edn"))]
      (health/write-record! rec)
      (is (= rec (edn/read-string (slurp (str (fs/path dir "health" "test-arm64.edn"))))))
      (is (= {:as-of "2026-09-22" :platform "Test arm64" :tools {:bb "1.13.223"}}
             (edn/read-string (slurp (str (fs/path dir "known-good.edn")))))
          "the doctor's known-good set is what the run saw"))))
