(ns sandbox.compute
  "Expression to number.

  Depends on sandbox.bindings — the PROTOCOL — and never on an implementation
  of it. That is the seam: swapping how variables are resolved must not touch
  this namespace, and a require of sandbox.memory here is a gate-4 failure.

  Named `compute` rather than `eval` so nothing shadows a clojure.core name."
  (:require
   [sandbox.bindings :as bindings]
   [sandbox.expr :as expr]
   [sandbox.shapes :as shapes]))

(defn compute
  "Evaluate `e` against `b`, a sandbox.bindings/Bindings. Validates `e` on the
  way in: compute is the other side of parse's seam, and a caller that built a
  node by hand gets the same error a parser would have."
  [e b]
  (shapes/check! ::compute e)
  (letfn [(go [e]
            (cond
              (expr/literal? e) (:expr/value e)

              (expr/variable? e)
              (or (bindings/lookup b (:expr/name e))
                  (throw (ex-info "unbound variable"
                                  {:sandbox/error :unbound :node e})))

              :else
              (let [f (get expr/operators (:expr/op e))
                    l (go (:expr/left e))
                    r (go (:expr/right e))]
                (when (and (= :div (:expr/op e)) (zero? r))
                  (throw (ex-info "division by zero"
                                  {:sandbox/error :div-by-zero :node e})))
                (f l r))))]
    (go e)))
