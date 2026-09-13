(ns sandbox.shapes
  "Malli schemas — the contract, and a leaf layer.

  Data-first (rule :shapes-are-the-contract): the shape is agreed before the
  functions, and validated at the seams rather than trusted through them. In a
  task packet these are what `:blueprint/slice`'s `:shapes` carries, and what a
  Tester derives tests from without reading an implementation."
  (:require
   [malli.core :as m]
   [malli.error :as me]))

(def Operator
  [:enum :add :sub :mul :div])

(def Expr
  "An expression tree. Recursive, because the interesting boundary failures are
  nested ones — a malformed leaf three levels down should name itself."
  [:schema {:registry {::expr [:multi {:dispatch :expr/op}
                               [:literal [:map {:closed true}
                                          [:expr/op [:= :literal]]
                                          [:expr/value :int]]]
                               [:var [:map {:closed true}
                                      [:expr/op [:= :var]]
                                      [:expr/name [:string {:min 1}]]]]
                               [::m/default [:map
                                             [:expr/op Operator]
                                             [:expr/left [:ref ::expr]]
                                             [:expr/right [:ref ::expr]]]]]}}
   ::expr])

(defn explain
  "Humanized errors for `e`, or nil when it conforms."
  [e]
  (some-> (m/explain Expr e) me/humanize))

(defn valid? [e] (m/validate Expr e))

(defn check!
  "Return `e`, or throw naming `where` and the errors. Seams throw; interiors
  assume. This is the ex-info a boundary failure produces."
  [where e]
  (if-let [errs (explain e)]
    (throw (ex-info "invalid expression" {:sandbox/error :invalid-expr
                                          :where where
                                          :errors errs}))
    e))
