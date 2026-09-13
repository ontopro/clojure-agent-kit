(ns sandbox.red-test
  (:require
   [clojure.test :refer [deftest is]]
   [sandbox.api :as api]))

(deftest deliberately-red
  (is (= 999 (api/calculate "1 + 1"))))
