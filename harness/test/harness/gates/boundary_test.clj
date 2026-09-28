(ns harness.gates.boundary-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.gates.boundary :as boundary]))

(defn- tree!
  "A src/ tree from {relative-path source}; returns the src dir."
  [files]
  (let [src (str (fs/path (fs/create-temp-dir) "src"))]
    (doseq [[path content] files]
      (fs/create-dirs (fs/parent (fs/path src path)))
      (spit (str (fs/path src path)) content))
    src))

;; The sandbox's shape in miniature: a protocol, an implementation, and a caller that
;; must see the protocol and never the implementation.
(def intact
  {"xyx/bindings.clj" "(ns xyx.bindings)\n(defprotocol Lookup (lookup [_ k]))"
   "xyx/memory.clj" "(ns xyx.memory (:require [xyx.bindings :as b]))"
   "xyx/compute.clj" "(ns xyx.compute\n  (:require [clojure.string :as str]\n            [xyx.bindings :as b]))"})

(def ruleset
  '{xyx.bindings #{} xyx.memory #{xyx.bindings} xyx.compute #{xyx.bindings}})

(deftest boundaries-intact-is-an-empty-list-and-foreign-requires-are-not-checked
  (is (= [] (boundary/violations (tree! intact) ruleset))
      "clojure.string is not xyx.*: gate 4 is about your layers, not your dependencies")
  (is (= "boundary: 3 namespaces, boundaries intact" (boundary/render ruleset []))))

(deftest the-two-violations-the-sandbox-proves-and-the-one-it-let-through
  (testing "undeclared: a layer discovered rather than declared"
    (let [src (tree! (assoc intact "xyx/extra.clj" "(ns xyx.extra (:require [xyx.compute :as c]))"))]
      (is (= [{:ns 'xyx.extra :kind :undeclared
               :detail "not declared in layers.edn - declare the layer, do not discover it"}]
             (boundary/violations src ruleset)))))
  (testing "forbidden: reaching past the protocol to its implementation"
    (let [src (tree! (assoc intact "xyx/compute.clj"
                            "(ns xyx.compute (:require [xyx.bindings :as b] [xyx.memory :as m]))"))]
      (is (= [{:ns 'xyx.compute :kind :forbidden-dependency
               :detail "requires xyx.memory, which its layer does not allow"}]
             (boundary/violations src ruleset)))))
  (testing "orphan: declared, no file - the dead config the fixture's check passed"
    (is (= [{:ns 'xyx.gone :kind :orphan
             :detail "declared in layers.edn but no file under src/ defines it - dead config"}]
           (boundary/violations (tree! intact) (assoc ruleset 'xyx.gone #{})))))
  (testing "a fixture is declared but absent, and that is not an orphan"
    (is (= [] (boundary/violations (tree! intact) (assoc ruleset 'xyx.red #{} :boundary/fixtures '#{xyx.red}))))
    (is (= "boundary: 4 namespaces, boundaries intact"
           (boundary/render (assoc ruleset 'xyx.red #{} :boundary/fixtures '#{xyx.red}) []))
        "the keyword key is not a namespace"))
  (testing "every violation, not the first; rendered one per line, exit is the caller's"
    (let [src (tree! (assoc intact "xyx/extra.clj" "(ns xyx.extra (:require [xyx.compute :as c]))"))
          vs (boundary/violations src (assoc ruleset 'xyx.gone #{}))]
      (is (= [:undeclared :orphan] (mapv :kind vs)))
      (is (= ["boundary: violations"
              "  xyx.extra [undeclared] not declared in layers.edn - declare the layer, do not discover it"
              "  xyx.gone [orphan] declared in layers.edn but no file under src/ defines it - dead config"]
             (str/split-lines (boundary/render ruleset vs)))))))

(deftest a-file-that-is-not-a-namespace-is-skipped-and-requires-are-read-in-every-form
  (let [src (tree! (assoc intact "xyx/data.clj" "{:not \"a namespace\"}"))]
    (is (= [] (boundary/violations src ruleset)) "data files under src/ are not layers"))
  (is (= '#{a.b c.d e.f}
         (boundary/requires-of '(ns x (:require [a.b :as b] c.d [e.f :refer [g]]) (:import (java.io File)))))
      "vector, bare symbol and :refer forms; :import is not a require"))
