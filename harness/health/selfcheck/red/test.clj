(ns selfcheck.red-test
  (:require
   [clojure.test :refer [deftest is]]
   [selfcheck.api :as api]))

(deftest deliberately-red
  (is (= 999 (api/calculate "1 + 1"))))
