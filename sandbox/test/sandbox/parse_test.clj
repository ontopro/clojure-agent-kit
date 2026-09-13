(ns sandbox.parse-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sandbox.expr :as expr]
   [sandbox.parse :as parse]))

(deftest parses-literals-and-variables
  (is (= (expr/literal 7) (parse/parse "7")))
  (is (= (expr/literal -7) (parse/parse "-7")))
  (is (= (expr/variable "x") (parse/parse "x")))
  (testing "folding is left-associative, with no precedence by design"
    (let [e (parse/parse "1 + 2")]
      (is (= :add (:expr/op e)))
      (is (= (expr/literal 1) (:expr/left e))))))

(deftest bad-input-names-the-token
  (is (thrown-with-msg? Exception #"empty expression" (parse/parse "  ")))
  (is (thrown-with-msg? Exception #"not a number or identifier" (parse/parse "1 + !")))
  (is (thrown-with-msg? Exception #"unknown operator token" (parse/parse "1 ^ 2")))
  (is (thrown-with-msg? Exception #"trailing operator" (parse/parse "1 +"))))
