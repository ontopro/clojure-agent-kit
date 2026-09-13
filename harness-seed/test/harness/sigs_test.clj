(ns harness.sigs-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [harness.sigs :as sigs]))

(defn- tree!
  "A temp dir holding `files`, a map of relative path -> source."
  [files]
  (let [dir (str (fs/create-temp-dir))]
    (doseq [[p src] files]
      (fs/create-dirs (fs/path dir (fs/parent p)))
      (spit (str (fs/path dir p)) src))
    dir))

(def store-src
  "(ns a.store)
(def defaults {:limit 10})
(defn query \"doc\" {:added \"1\"} [store q opts] nil)
(defn- secret [x] x)
(defn variadic [a & more] a)
(defn multi ([a]) ([a b]))
(defmulti open :kind)
(def wrapped (fn [x y] x))
(defmacro mac [x] x)
(defrecord R [x])")

(def proto-src
  "(ns a.seam)
(defprotocol Store
  (lookup [this k] \"doc\")
  (put [this k v]))")

(def ctx ["src/a/store.clj" "src/a/seam.clj"])

(defn- both [] (tree! {"src/a/store.clj" store-src "src/a/seam.clj" proto-src}))

;; ---------------------------------------------------------------------------
;; reading the context files
;; ---------------------------------------------------------------------------

(deftest reads-what-the-context-files-define
  (let [{:keys [defs ns-of unparsed]} (sigs/definitions (both) ctx)
        s (get defs 'a.store)]
    (is (= #{} unparsed))
    (is (= 'a.store (get ns-of "src/a/store.clj")))
    (is (= :value (:kind (s 'defaults))))
    (is (= #{3} (:fixed (s 'query))) "a docstring and attr-map are not arities")
    (is (true? (:private? (s 'secret))))
    (is (= 1 (:variadic-min (s 'variadic))))
    (is (= #{1 2} (:fixed (s 'multi))))
    (is (= :open (:kind (s 'open))) "a defmulti's arity is decided by its methods")
    (is (= #{2} (:fixed (s 'wrapped))) "(def f (fn [x y])) is a function, not a value")
    (is (= #{1} (:fixed (s 'mac))))))

(deftest reads-what-a-hand-rolled-reader-would-have-missed
  ;; The reason for reading clj-kondo's analysis instead of parsing the forms:
  ;; it knows what a form EXPANDS to, not only what it looks like.
  (let [s (get (:defs (sigs/definitions (both) ctx)) 'a.store)]
    (is (= :value (:kind (s 'R))) "the record type itself is not callable")
    (is (= #{1} (:fixed (s '->R))) "but its generated constructors are")
    (is (= #{1} (:fixed (s 'map->R))))))

(deftest reads-protocol-methods
  ;; sandbox.bindings is a protocol and nothing else, and a slice's dependency
  ;; on a seam is the one this most needs to get right.
  (let [s (get (:defs (sigs/definitions (both) ctx)) 'a.seam)]
    (is (= #{2} (:fixed (s 'lookup))))
    (is (= #{3} (:fixed (s 'put))))
    (is (= :value (:kind (s 'Store))) "the protocol itself takes no arguments")))

;; ---------------------------------------------------------------------------
;; reading a signature
;; ---------------------------------------------------------------------------

(deftest a-signature-has-two-forms
  (testing "a call signature, whose arity is checked"
    (let [s (sigs/parse-sig '(a.store/query [store q opts]))]
      (is (= 'a.store (:sig/ns s)))
      (is (= 'query (:sig/name s)))
      (is (= 3 (count (:sig/argv s))))))
  (testing "and a name, granting read access to a var that is not a function"
    (is (nil? (:sig/argv (sigs/parse-sig 'a.store/defaults))))
    (is (nil? (:sig/argv (sigs/parse-sig '(a.store/defaults)))))))

(deftest an-unreadable-entry-is-not-an-accusation
  (is (nil? (sigs/parse-sig "a string")))
  (is (nil? (sigs/parse-sig '(:keyword [x]))))
  (is (nil? (sigs/parse-sig 42))))

;; ---------------------------------------------------------------------------
;; the check
;; ---------------------------------------------------------------------------

(defn- v [sigs] (sigs/violations (both) ctx sigs))

(deftest a-truthful-slice-produces-nothing
  (is (= [] (v '[(a.store/query [store q opts])
                 (a.store/variadic [a])
                 (a.store/variadic [a b c])
                 (a.store/multi [a b])
                 (a.store/open [x y z])
                 (a.store/->R [x])
                 (a.seam/lookup [this k])
                 a.store/defaults
                 (query [store q opts])]))
      "including an unqualified name, a protocol method, a record constructor
       and a value"))

(deftest a-call-signature-for-a-value-is-the-run-3-defect
  ;; Run 3 declared (sandbox.expr/operators []) — a zero-arity call signature
  ;; for a map. It rode through packet validation, two dispatches, four gates
  ;; and review, and nothing looked.
  (let [[x & more] (v '[(a.store/defaults [])])]
    (is (empty? more))
    (is (= :not-a-function (:violation x)))
    (is (re-find #"is a value" (:detail x)))))

(deftest the-other-four-kinds
  (is (= :arity-mismatch (:violation (first (v '[(a.store/query [store q])])))))
  (is (= :unknown-var (:violation (first (v '[(a.store/nope [x])])))))
  (is (= :unknown-namespace (:violation (first (v '[(a.other/f [x])])))))
  (is (= :private-var (:violation (first (v '[(a.store/secret [x])]))))
      "a private var cannot be called across the seam, whatever the arity"))

(deftest an-ambiguous-name-is-skipped-rather-than-guessed
  ;; A check that cries wolf about a correct packet gets switched off. Two
  ;; context files defining `dup` leaves no way to say which was meant.
  (let [dir (tree! {"src/a/one.clj" "(ns a.one) (defn dup [x] x)"
                    "src/a/two.clj" "(ns a.two) (defn dup [x y] x)"})
        files ["src/a/one.clj" "src/a/two.clj"]]
    (is (= [] (sigs/violations dir files '[(dup [x y z])])))
    (testing "but qualifying it resolves the ambiguity and the check applies"
      (is (= :arity-mismatch
             (:violation (first (sigs/violations dir files '[(a.one/dup [x y z])]))))))))

(deftest a-context-file-that-is-not-there-is-reported
  ;; Silently not verifying is the failure this namespace exists to end.
  (let [dir (tree! {"src/a/store.clj" store-src})]
    (is (= [:missing-context-file]
           (mapv :violation (sigs/violations dir ctx '[(a.store/query [s q o])]))))))

(deftest a-context-file-that-does-not-parse-is-reported-not-guessed-at
  ;; clj-kondo still emits partial analysis for a file it could not read, and
  ;; using it would report every var it never reached as undefined — the exact
  ;; false accusation this must not make. So: say the file did not parse, and
  ;; say nothing about the signatures that depend on it.
  (let [dir (tree! {"src/a/store.clj" store-src
                    "src/a/seam.clj" "(ns a.seam)\n(defn broken [x"})
        out (sigs/violations dir ctx '[(a.seam/lookup [this k])
                                       (a.store/query [store q])])]
    (is (= #{:unparsed-context-file :arity-mismatch} (set (map :violation out)))
        "the unreadable file is named, the readable one is still checked")
    (is (nil? (some #{:unknown-var :unknown-namespace} (map :violation out)))
        "and nothing is claimed about what the unreadable file defines")))

(deftest verify-throws-so-a-wrong-packet-cannot-reach-an-agent
  (let [dir (both)]
    (is (= [] (sigs/verify! dir ctx '[(a.store/query [s q o])])))
    (is (thrown-with-msg? Exception #"do not match :files/context"
                          (sigs/verify! dir ctx '[(a.store/defaults [])])))))
