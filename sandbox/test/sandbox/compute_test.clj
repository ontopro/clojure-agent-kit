(ns sandbox.compute-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sandbox.compute :as compute]
   [sandbox.expr :as expr]
   [sandbox.memory :as memory]))

(def ^:private empty-bindings (memory/bindings))

(deftest evaluates-expressions
  (is (= 7 (compute/compute (expr/literal 7) empty-bindings)))
  (is (= 3 (compute/compute (expr/binary :add (expr/literal 1) (expr/literal 2))
                            empty-bindings)))
  (testing "nesting recurses"
    (is (= 9 (compute/compute
              (expr/binary :mul
                           (expr/binary :add (expr/literal 1) (expr/literal 2))
                           (expr/literal 3))
              empty-bindings)))))

(deftest resolves-variables-through-the-protocol
  (let [b (memory/bindings {"x" 10})]
    (is (= 10 (compute/compute (expr/variable "x") b)))
    (is (= 11 (compute/compute (expr/binary :add (expr/variable "x") (expr/literal 1)) b)))
    (testing "an unbound variable names the node"
      (is (thrown-with-msg? Exception #"unbound variable"
                            (compute/compute (expr/variable "y") b))))))

(deftest failures-carry-context
  (testing "division by zero names the node, not just the operator"
    (is (thrown-with-msg? Exception #"division by zero"
                          (compute/compute
                           (expr/binary :div (expr/literal 1) (expr/literal 0))
                           empty-bindings))))
  (testing "a hand-built malformed node is rejected at the seam"
    (is (thrown-with-msg? Exception #"invalid expression"
                          (compute/compute {:expr/op :bogus} empty-bindings)))))
