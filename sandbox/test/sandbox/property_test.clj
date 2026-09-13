(ns sandbox.property-test
  "Property-based tests, exercising method §05's named property targets and the
  three rule-source conventions that nothing else in this project touches:
  generators are values bound with `def`, `defspec` takes no docstring, and
  there are no top-level side effects."
  (:require
   [clojure.string :as str]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [sandbox.api :as api]
   [sandbox.parse :as parse]
   [sandbox.render :as render]
   [sandbox.shapes :as shapes]))

;; Rule :generators-are-values — bound with def, not defn.

(def gen-operand
  (gen/one-of [(gen/fmap str gen/small-integer)
               (gen/elements ["x" "y" "z"])]))

;; Division is excluded on purpose: a generated divide-by-zero is a real error
;; and would make every property test about error handling instead.
(def gen-op
  (gen/elements ["+" "-" "*"]))

(def gen-expr-string
  (gen/fmap (fn [[head tail]]
              (str/join " " (cons head (apply concat tail))))
            (gen/tuple gen-operand
                       (gen/vector (gen/tuple gen-op gen-operand) 0 5))))

(def bindings
  {"x" 2 "y" 3 "z" 5})

;; ---------------------------------------------------------------------------
;; §05 target: round-trip — parse -> render -> parse preserves the tree
;; ---------------------------------------------------------------------------

(defspec parse-render-round-trips 200
  (prop/for-all [s gen-expr-string]
                (let [once (parse/parse s)]
                  (= once (parse/parse (render/render once))))))

(defspec canonical-is-idempotent 200
  (prop/for-all [s gen-expr-string]
                (let [c (api/canonical s)]
                  (= c (api/canonical c)))))

;; ---------------------------------------------------------------------------
;; §05 target: cross-surface consistency — two paths to the same answer agree
;; ---------------------------------------------------------------------------

(defn- reference
  "Fold the tokens left-to-right in plain Clojure, independently of the
  expression tree. If this and `calculate` ever disagree, one of them has the
  associativity wrong."
  [s]
  (let [tokens (remove str/blank? (str/split s #"\s+"))
        value (fn [t] (if (re-matches #"^-?\d+$" t)
                        (Long/parseLong t)
                        (get bindings t)))
        f {"+" + "-" - "*" *}]
    (reduce (fn [acc [op operand]] ((f op) acc (value operand)))
            (value (first tokens))
            (partition 2 (rest tokens)))))

(defspec calculate-agrees-with-an-independent-fold 200
  (prop/for-all [s gen-expr-string]
                (= (reference s) (api/calculate s bindings))))

;; ---------------------------------------------------------------------------
;; The contract holds for everything the parser can produce
;; ---------------------------------------------------------------------------

(defspec every-parsed-expression-conforms-to-the-shape 200
  (prop/for-all [s gen-expr-string]
                (shapes/valid? (parse/parse s))))
