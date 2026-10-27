(ns harness.models.code-review-tools-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.models.code-review-tools :as crt]
   [harness.models.runner :as runner]
   [harness.models.tools :as tools]))

(defn- call [ctx code]
  (tools/invoke crt/registry ctx {:id "1" :name "nrepl_eval" :args {:code code}}))

(deftest nrepl-eval-runs-in-the-sandbox-and-reads-as-every-other-eval
  (let [ran (atom nil)]
    (with-redefs [crt/exec (fn [argv] (reset! ran argv) {:exit 0 :out "\"\"\n" :err ""})]
      (let [r (call {:sandbox "kit-review-x" :port 51234} "(clojure.string/trim \"\\u2003\")")]
        (is (not (:error? r)))
        (is (str/includes? (:content r) "\"\""))
        (is (= ["docker" "exec" "-w" "/work/app" "kit-review-x" "clj-nrepl-eval" "-p" "51234" "(clojure.string/trim \"\\u2003\")"] @ran)
            "a docker exec into the sandbox, never the host"))))
  (testing "a timeout and a failed form read as they do for the Coder (tools/eval-result)"
    (with-redefs [crt/exec (fn [_] {:exit 0 :out tools/timeout-marker :err ""})]
      (is (str/includes? (:content (call {:sandbox "s" :port 51234} "(loop [] (recur))")) "timed out")))
    (with-redefs [crt/exec (fn [_] {:exit 0 :out "" :err "Execution error (ArithmeticException) at x\n"})]
      (is (:error? (call {:sandbox "s" :port 51234} "(/ 1 0)")))))
  (testing "no sandbox, no REPL"
    (is (str/includes? (:content (call {} "(+ 1 1)")) "no REPL is attached to this review"))
    (is (:error? (call {:sandbox "s" :port 51234} " ")))))

(deftest the-registry-declares-three-tools-in-both-spellings
  (is (= #{"read_file" "note" "nrepl_eval"} crt/offered (set (keys crt/registry))))
  (is (= ["note" "nrepl_eval" "read_file"] (mapv #(get-in % [:function :name]) (tools/declarations :openai crt/offered crt/registry))))
  (is (= ["note" "nrepl_eval" "read_file"] (mapv :name (tools/declarations :anthropic crt/offered crt/registry))))
  (is (= #{"read_file" "note"} (tools/for-role :reviewer)) "without a sandbox the reviewer has what it had"))

(deftest a-reviewer-with-a-sandbox-is-told-it-has-a-repl
  (let [pkt {:task/role :reviewer :task/id "t" :task/title "x" :repl/worktree "/c"}]
    (is (str/includes? (runner/packet-prompt pkt) "You write nothing and you have no REPL."))
    (let [p (runner/packet-prompt (assoc pkt :repl/sandbox "kit-review-x" :repl/port 51234))]
      (is (str/includes? p "You have a REPL, nrepl_eval, over a copy of the code under review"))
      (is (not (str/includes? p "you have no REPL"))))))
