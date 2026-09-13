(ns sandbox.parse
  "Text to expression. A sibling of sandbox.compute: both may depend on
  sandbox.expr and sandbox.shapes, and neither may depend on the other.

  Deliberately trivial: tokens are split on whitespace and folded
  left-to-right, so `1 + 2 * 3` is `(1 + 2) * 3`. No precedence, no
  parentheses. A fixture, not a calculator."
  (:require
   [clojure.string :as str]
   [sandbox.expr :as expr]
   [sandbox.shapes :as shapes]))

(def ^:private symbols
  {"+" :add "-" :sub "*" :mul "/" :div})

(def ^:private identifier #"^[a-zA-Z][a-zA-Z0-9_-]*$")

(defn- operand
  "A token as a literal or a variable. Anything that is neither names itself
  in the ex-info — a boundary failure should carry the offending token."
  [t]
  (cond
    (re-matches #"^-?\d+$" t) (expr/literal (Long/parseLong t))
    (re-matches identifier t) (expr/variable t)
    :else (throw (ex-info "not a number or identifier"
                          {:sandbox/error :bad-token :token t}))))

(defn parse
  "Parse `s`. Validated against the Expr shape on the way out — this is a
  seam, and seams validate."
  [s]
  (let [tokens (remove str/blank? (str/split (or s "") #"\s+"))]
    (when (empty? tokens)
      (throw (ex-info "empty expression" {:sandbox/error :empty :input s})))
    (shapes/check!
     ::parse
     (loop [acc (operand (first tokens))
            [op rhs & more] (rest tokens)]
       (cond
         (nil? op) acc
         (nil? rhs) (throw (ex-info "trailing operator"
                                    {:sandbox/error :trailing-operator :op op}))
         :else
         (let [k (symbols op)]
           (when-not k
             (throw (ex-info "unknown operator token"
                             {:sandbox/error :bad-operator :token op})))
           (recur (expr/binary k acc (operand rhs)) more)))))))
