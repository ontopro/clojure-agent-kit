(ns sandbox.bindings-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sandbox.bindings :as bindings]
   [sandbox.memory :as memory]))

(deftest memory-satisfies-the-protocol
  (let [b (memory/bindings {"x" 41})]
    (is (satisfies? bindings/Bindings b))
    (is (= 41 (bindings/lookup b "x")))
    (testing "an unbound name is nil, not an error — the caller decides"
      (is (nil? (bindings/lookup b "nope")))))
  (testing "the no-arg case is the common one: no variables at all"
    (is (nil? (bindings/lookup (memory/bindings) "x")))))
