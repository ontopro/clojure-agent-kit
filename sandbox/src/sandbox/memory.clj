(ns sandbox.memory
  "An in-memory Bindings, backed by a map.

  Depends on the protocol and on nothing else. The composition root is the
  only namespace allowed to know this exists."
  (:require
   [sandbox.bindings :as bindings]))

(defrecord MapBindings [m]
  bindings/Bindings
  (lookup [_ nm] (get m nm)))

(defn bindings
  "A Bindings over `m`, a map of name string to number. Defaults to empty, so
  the common case — an expression with no variables — needs no argument."
  ([] (bindings {}))
  ([m] (->MapBindings m)))
