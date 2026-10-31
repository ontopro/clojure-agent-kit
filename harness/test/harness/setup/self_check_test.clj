(ns harness.setup.self-check-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [harness.setup.self-check :as self-check]))

(def kit-dir (str (fs/parent (fs/real-path "."))))

(deftest the-steps-that-need-no-tool-come-first
  ;; From Opus 5.5's read of the 8.3d design: an N to the offer, a doctor's no or a failing health
  ;; check still leaves the hooks and the template
  (is (= [:place :hooks :env :doctor :health :next] self-check/steps)))

(deftest the-place-is-said-only-when-the-step-stops
  ;; From Fable 5.1's read of 28e231b: the doctor's header names the folder a moment later
  (is (nil? (self-check/place-stop {:folder "/p" :cache "/p/.kit/toolchain" :env "/p/.env"} "/p/KIT")))
  (is (= "refused" (self-check/place-stop {:refusal "refused"} "/p/KIT")))
  (is (= "the toolchain pack named no toolchain folder" (self-check/place-stop {:folder "/p"} "/p/KIT")))
  (is (= "the toolchain pack's status did not answer - `bb --config /p/KIT/tools/toolchain/bb.edn status` says why"
         (self-check/place-stop nil "/p/KIT"))))

(deftest the-hooks-are-set-once-and-said-only-when-they-change
  (let [dir (str (fs/create-temp-dir))
        hooks #(str/trim (:out (p/shell {:dir dir :out :string :continue true} "git" "config" "--get" "core.hooksPath")))]
    (is (str/ends-with? (self-check/hooks! dir) "is not a git clone: its hooks not set"))
    (p/shell {:dir dir :out :string} "git" "init" "-q")
    (is (= "core.hooksPath = .githooks: every commit in this clone now runs `bb commit-check`" (self-check/hooks! dir)))
    (is (= ".githooks" (hooks)) "read back")
    (is (nil? (self-check/hooks! dir)) "a second run: silent")))

(deftest the-env-is-written-once-at-600-and-never-touched-after
  (let [folder (fs/create-temp-dir)
        env (fs/path folder ".env")]
    (is (= (str "wrote " env " - the names the claude seat needs, empty; fill them") (self-check/env! env kit-dir)))
    (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions env))))
    (is (str/includes? (slurp (str env)) "\nANTHROPIC_API_KEY=\n"))
    (spit (str env) "ANTHROPIC_API_KEY=filled\n")
    (let [before (slurp (str env))]
      (is (= (str env " is there - not touched") (self-check/env! env kit-dir)))
      (is (= before (slurp (str env))) "byte-equal"))))
