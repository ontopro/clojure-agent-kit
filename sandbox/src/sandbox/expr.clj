(ns sandbox.expr
  "Expression constructors — the shapes made by hand.

  Depends only on sandbox.shapes, which holds the contract. Everything that
  builds an expression comes through here, so there is one place where a
  malformed node can be caught at construction rather than at evaluation."
  (:require
   [sandbox.shapes :as shapes]))

(def operators
  "Binary operators, mapped to the function that applies them."
  {:add + :sub - :mul * :div /})

(defn literal
  [n]
  (shapes/check! ::literal {:expr/op :literal :expr/value n}))

(defn variable
  "A named leaf, resolved against a Bindings at evaluation."
  [nm]
  (shapes/check! ::variable {:expr/op :var :expr/name nm}))

(defn binary
  "An application of `op`. An unknown operator throws here rather than
  producing a node that only fails later, with less context."
  [op left right]
  (when-not (contains? operators op)
    (throw (ex-info "unknown operator" {:op op :known (set (keys operators))})))
  (shapes/check! ::binary {:expr/op op :expr/left left :expr/right right}))

(defn literal? [e] (= :literal (:expr/op e)))
(defn variable? [e] (= :var (:expr/op e)))
