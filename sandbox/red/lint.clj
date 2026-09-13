(ns sandbox.red-lint)

(defn unused-binding [x]
  (let [never-read (* x 2)]
    x))
