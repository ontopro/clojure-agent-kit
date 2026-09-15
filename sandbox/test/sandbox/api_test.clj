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

;; NOTES.md row 20: `canonical` returning its input survived every test, since
;; idempotence alone is satisfied by the identity. These pin the normal form.
(deftest canonical-is-the-single-spaced-form
  (is (= "1 + 2" (api/canonical "  1   +  2 ")))
  (is (= "1 + 2" (api/canonical "1\t+\n2")))
  (is (= "7" (api/canonical "  7  ")))
  (is (= "-3 - x * y" (api/canonical "-3 -   x  *\ty")))
  (testing "already canonical input comes back unchanged"
    (is (= "x + 1" (api/canonical "x + 1")))))
