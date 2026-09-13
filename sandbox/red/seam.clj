(ns sandbox.red-seam
  "Reaches past the protocol to its implementation — the violation gate 4
  exists to catch, and the one rule :review-scope tells a Reviewer to look for."
  (:require
   [sandbox.memory :as memory]))

(defn crosses-the-seam []
  (memory/bindings {"x" 1}))
