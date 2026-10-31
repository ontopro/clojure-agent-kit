(ns harness.setup.env-file-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.models.profile :as profile]
   [harness.setup.env-file :as env-file]))

(def kit-dir (str (fs/parent (fs/real-path "."))))

(defn- shipped [seat] (profile/read-profile (env-file/shipped-profile kit-dir seat)))

(deftest the-template-is-the-seats-key-names-empty
  ;; plan 7.0, item 8.6b; the person, 2026-10-28: "initialize a '.env' file with just the KEYS so that
  ;; the Adopter can put in the relevant keys - basically a '.env' template"; written by bb self-check (8.3d)
  (let [env (env-file/env-template (shipped "claude") "claude")
        lines (str/split-lines env)]
    (is (str/starts-with? env "# THIS PROJECT'S KEYS - written by `bb self-check` with the names the claude seat's profile needs,"))
    (is (str/includes? env "`bb init --seat <name>` says what\n# another seat needs"))
    (is (= ["ANTHROPIC_API_KEY=" "GEMINI_API_KEY=" "OPENROUTER_API_KEY="] (filter #(re-matches #"[A-Z_]+=.*" %) lines))
        "the claude example's three names, sorted, every value empty")
    (is (some #{"# blueprint-reviewer, orchestrator, tester - api.anthropic.com"} lines)
        "what uses each, its roles sorted, and where it is sent")
    (is (some #(and (str/starts-with? % "# coder, ") (str/includes? % "security panel (gemini-4-argon)")) lines)
        "the security panel's members count")
    (is (not (str/includes? env "sk-")) "no value, ever"))
  (testing "a role with no key names no line; a profile with none, no template"
    (let [local {:seat :claude :roles {:coder {:model "m" :endpoint "http://localhost:8080/v1"}
                                       :tester {:model "t" :endpoint "https://api.anthropic.com" :key-env "ANTHROPIC_API_KEY"}}}]
      (is (= ["ANTHROPIC_API_KEY="] (filter #(re-matches #"[A-Z_]+=.*" %) (str/split-lines (env-file/env-template local "claude")))))
      (is (nil? (env-file/env-template (update local :roles dissoc :tester) "claude"))))))

(deftest the-names-a-seat-lacks-are-said-never-written
  ;; plan 7.0, item 8.3d: what bb init --seat says (From Opus 5.5's read of the 8.3d design)
  (let [claude (shipped "claude")
        template (env-file/env-template claude "claude")]
    (is (= #{"A" "B_2" "C"} (env-file/env-names "# A=x\nA=1\n export B_2=\n\nC = 3\nnot a line\n=x\n")) "comments, blanks and junk aside")
    (is (= [] (env-file/missing-names claude template)) "the template lacks nothing its seat needs")
    (is (= ["GEMINI_API_KEY" "OPENROUTER_API_KEY"] (env-file/missing-names claude "ANTHROPIC_API_KEY=x\n")))
    (testing "what bb init says, by case"
      (is (= ["no .env in /p: `bb self-check` writes the template"] (env-file/init-lines "/p/.env" nil claude)))
      (is (nil? (env-file/init-lines "/p/.env" template claude)) "nothing lacking: nothing said")
      (is (= ["add OPENROUTER_API_KEY to /p/.env - fill it"]
             (env-file/init-lines "/p/.env" "ANTHROPIC_API_KEY=\nGEMINI_API_KEY=\n" claude)))
      (is (= ["add GEMINI_API_KEY, OPENROUTER_API_KEY to /p/.env - fill them"]
             (env-file/init-lines "/p/.env" "ANTHROPIC_API_KEY=\n" claude))))
    (testing "another seat's names against the default seat's template"
      (let [agy (shipped "agy-ide")]
        (is (= (vec (sort (remove (env-file/env-names template) (env-file/missing-names agy ""))))
               (env-file/missing-names agy template)))))))
