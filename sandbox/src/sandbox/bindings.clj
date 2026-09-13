(ns sandbox.bindings
  "The variable-lookup seam, as a protocol and nothing else.

  A leaf layer on purpose. `sandbox.compute` depends on THIS, never on a
  concrete implementation — that is the boundary `layers.edn` enforces and the
  one a Reviewer is told to look for: *a dependency on a concrete
  implementation where a protocol crosses the seam is a finding*.

  Keeping the protocol in its own namespace rather than beside an
  implementation is what makes the rule checkable. If they shared a file, no
  dependency graph could tell the two apart.")

(defprotocol Bindings
  (lookup [this nm]
    "The value bound to `nm`, or nil. Returning nil rather than throwing keeps
     the decision about unbound variables with the caller — compute throws with
     the whole node attached, which is more context than this seam has."))
