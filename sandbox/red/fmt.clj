(ns sandbox.red-fmt)

;; Padded column alignment — exactly what rule :align-forms names. cljfmt only
;; catches this with :remove-multiple-non-indenting-spaces? enabled, which is
;; why .cljfmt.edn sets it.
(defn classify [n]
  (cond
    (neg? n)  :negative
    (zero? n) :zero
    :else     :positive))
