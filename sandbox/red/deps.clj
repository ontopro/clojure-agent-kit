(ns sandbox.red-deps
  "Undeclared in layers.edn — the violation gate 4 raises for a layer that was
  discovered rather than declared."
  (:require
   [sandbox.compute :as compute]
   [sandbox.parse :as parse]))

(defn calculate [s bindings]
  (compute/compute (parse/parse s) bindings))
