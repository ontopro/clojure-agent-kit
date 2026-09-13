(ns sandbox.render
  "Expression to text — the inverse of sandbox.parse, and a third sibling at
  that layer. It exists so round-trip can be stated as a property (method §05
  names round-trip first of four): `parse` after `render` after `parse` must
  agree with `parse`.

  Note what it is NOT: a pretty-printer. Because parse folds left with no
  precedence, render emits the flat form that re-parses to the same tree. On a
  right-leaning tree — which parse never produces, but a hand-built one might —
  it would emit a string that parses to a different shape. Round-trip holds on
  the canonical subset, and saying so is the honest version."
  (:require
   [sandbox.expr :as expr]
   [sandbox.shapes :as shapes]))

(def ^:private symbols
  {:add "+" :sub "-" :mul "*" :div "/"})

(defn render
  "The flat text form of `e`."
  [e]
  (shapes/check! ::render e)
  (letfn [(go [e]
            (cond
              (expr/literal? e) (str (:expr/value e))
              (expr/variable? e) (:expr/name e)
              :else (str (go (:expr/left e)) " "
                         (symbols (:expr/op e)) " "
                         (go (:expr/right e)))))]
    (go e)))
