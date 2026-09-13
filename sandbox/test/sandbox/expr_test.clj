(ns sandbox.expr-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sandbox.expr :as expr]))

(deftest literals-and-binaries
  (is (expr/literal? (expr/literal 1)))
  (is (not (expr/literal? (expr/binary :add (expr/literal 1) (expr/literal 2)))))
  (testing "an unknown operator throws at construction, not at evaluation"
    (is (thrown-with-msg? Exception #"unknown operator"
                          (expr/binary :pow (expr/literal 2) (expr/literal 3))))))
