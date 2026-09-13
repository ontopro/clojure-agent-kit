(ns sandbox.shapes-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sandbox.shapes :as shapes]))

(deftest expr-shape-is-the-contract
  (is (shapes/valid? {:expr/op :literal :expr/value 1}))
  (is (shapes/valid? {:expr/op :var :expr/name "x"}))
  (is (shapes/valid? {:expr/op :add
                      :expr/left {:expr/op :literal :expr/value 1}
                      :expr/right {:expr/op :var :expr/name "x"}}))
  (testing "a malformed leaf is caught however deep it sits"
    (is (not (shapes/valid? {:expr/op :add
                             :expr/left {:expr/op :literal :expr/value 1}
                             :expr/right {:expr/op :literal :expr/value "two"}}))))
  (testing "closed maps — a stray key is a contract violation, not extra data"
    (is (not (shapes/valid? {:expr/op :literal :expr/value 1 :extra true}))))
  (testing "check! names where the failure happened"
    (is (thrown-with-msg? Exception #"invalid expression"
                          (shapes/check! ::somewhere {:expr/op :nope})))))
