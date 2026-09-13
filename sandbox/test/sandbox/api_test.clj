(ns sandbox.api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sandbox.api :as api]))

(deftest calculates-end-to-end
  (is (= 3 (api/calculate "1 + 2")))
  (testing "left-to-right, so this is (1 + 2) * 3 and not 1 + (2 * 3)"
    (is (= 9 (api/calculate "1 + 2 * 3")))))

(deftest resolves-variables-supplied-by-the-caller
  (is (= 15 (api/calculate "x + 5" {"x" 10})))
  (is (= 30 (api/calculate "x * y" {"x" 5 "y" 6})))
  (testing "the composition root is where the Bindings implementation is chosen"
    (is (thrown-with-msg? Exception #"unbound variable" (api/calculate "z + 1")))))
