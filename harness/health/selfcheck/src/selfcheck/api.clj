(ns selfcheck.api
  "The composition root — the only namespace allowed to know that
  selfcheck.memory is the Bindings implementation. Gate 4 exempts it; every
  other crossing of that seam is a violation."
  (:require
   [selfcheck.compute :as compute]
   [selfcheck.memory :as memory]
   [selfcheck.parse :as parse]
   [selfcheck.render :as render]))

(defn calculate
  "Parse and evaluate `s`, resolving any variables against `vars`
  (name string -> number)."
  ([s] (calculate s {}))
  ([s vars]
   (compute/compute (parse/parse s) (memory/bindings vars))))

(defn canonical
  "`s` parsed and rendered back — the normal form parse would produce again.
  Idempotent: canonical of canonical is canonical."
  [s]
  (render/render (parse/parse s)))
